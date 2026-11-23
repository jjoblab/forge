package jo.forge.engine.bootstrap;

import jo.forge.api.BuildEvent;
import jo.forge.api.BuildRequest;
import jo.forge.api.BuildStarted;
import jo.forge.api.CancelRequest;
import jo.forge.api.Cancelled;
import jo.forge.api.ClientHello;
import jo.forge.api.EngineState;
import jo.forge.api.Frame;
import jo.forge.api.FrameReader;
import jo.forge.api.FrameType;
import jo.forge.api.FrameWriter;
import jo.forge.api.Hello;
import jo.forge.api.MessageIO;
import jo.forge.api.Pong;
import jo.forge.api.ProtocolVersions;
import jo.forge.api.Status;
import jo.forge.api.TaskDescription;
import jo.forge.api.TasksRequest;
import jo.forge.api.TasksResponse;
import jo.forge.engine.graph.GraphException;
import jo.forge.engine.pipeline.LifecycleTasks;
import jo.forge.engine.server.ConsoleDeBuild;
import jo.forge.engine.tasks.Task;
import jo.forge.engine.tasks.TaskContext;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Serveur du moteur factice de l'étape 2 de la Phase 4 : poignée de main, ping, état et
 * builds scriptés. Il valide le contrat {@code api/} de bout en bout dans un vrai
 * processus séparé, avant que le vrai moteur de tâches ne le remplace.
 *
 * <p>Comportement scripté (marqueurs d'identifiants de build, utiles aux tests) :</p>
 * <ul>
 *   <li>identifiant commençant par {@code slow-} : build long (10 s) annulable ;</li>
 *   <li>identifiant commençant par {@code fail-} : build échoué à la tâche
 *       {@code demo:compile} ;</li>
 *   <li>tout autre identifiant : build réussi en quelques événements.</li>
 * </ul>
 *
 * <p>Thread-sûreté : le lecteur du canal tourne dans le thread appelant ; les builds
 * tournent sur un exécuteur dédié ; toutes les écritures passent par le
 * {@link FrameWriter} synchronisé. stderr ne porte jamais de trame (réservé aux
 * diagnostics d'écroulement).</p>
 */
public final class FakeEngineServer {

    /** Version annoncée par ce moteur factice (source unique : EngineVersion). */
    public static final String ENGINE_VERSION = jo.forge.engine.EngineVersion.CURRENT;

    /** Capacités annoncées dans la poignée de main. */
    public static final List<String> CAPABILITIES = List.of("ping", "status", "fake-build");

    private static final long START_TIME = System.nanoTime();

    private final FrameWriter writer;
    private final PrintStream diagnostics;
    private final ExecutorService buildExecutor;
    private final Map<String, Future<?>> runningBuilds = new ConcurrentHashMap<>();

    /** Version de protocole négociée à la poignée de main (1 avant négociation). */
    private volatile int protocoleNégocié = ProtocolVersions.PROTOCOL_MIN;

    /**
     * Crée le serveur sur les flux donnés.
     *
     * @param out         flux de sortie du canal de contrôle (stdout du processus).
     * @param diagnostics flux d'écroulement (stderr du processus), jamais utilisé pour
     *                    les trames.
     */
    public FakeEngineServer(OutputStream out, PrintStream diagnostics) {
        this.writer = new FrameWriter(out);
        this.diagnostics = diagnostics;
        this.buildExecutor = Executors.newCachedThreadPool();
    }

    /**
     * Boucle principale : envoie la poignée de main puis traite les trames jusqu'à la
     * fermeture du canal, à l'arrêt demandé ou à une erreur irrécupérable.
     *
     * @param in flux d'entrée du canal (stdin du processus).
     * @throws IOException en cas d'erreur d'entrée/sortie sur le canal.
     */
    public void serve(InputStream in) throws IOException {
        FrameReader reader = new FrameReader(in);
        sendHello();
        boolean running = true;
        while (running) {
            Frame frame = reader.readFrame();
            if (frame == null) {
                running = false;
                continue;
            }
            running = handle(frame);
        }
        shutdownExecutors();
    }

    /**
     * Traite une trame ; retourne {@code false} pour arrêter la boucle.
     */
    private boolean handle(Frame frame) throws IOException {
        switch (frame.type()) {
            case CLIENT_HELLO -> {
                ClientHello hello = decode(frame, MessageIO::readClientHello);
                if (hello.protocolVersion() < ProtocolVersions.PROTOCOL_MIN
                        || hello.protocolVersion() > ProtocolVersions.PROTOCOL_MAX) {
                    reply(frame, FrameType.PROTOCOL_ERROR, out -> MessageIO.writeErrorMessage(
                            out, "Version de protocole refusée : " + hello.protocolVersion()));
                    return false;
                }
                protocoleNégocié = hello.protocolVersion();
                // Accusé de poignée de main : la coquille attend cette confirmation.
                reply(frame, FrameType.CLIENT_HELLO, out -> MessageIO.writeClientHello(
                        out, new ClientHello(ENGINE_VERSION, hello.protocolVersion())));
            }
            case PING -> reply(frame, FrameType.PONG,
                    out -> MessageIO.writePong(out, new Pong(System.currentTimeMillis())));
            case STATUS_REQUEST -> reply(frame, FrameType.STATUS, out -> MessageIO.writeStatus(
                    out, new Status(ENGINE_VERSION, runningBuilds.isEmpty()
                            ? EngineState.IDLE : EngineState.BUILDING, uptimeMillis(),
                            CAPABILITIES)));
            case START_BUILD -> {
                BuildRequest request = decode(frame, MessageIO::readBuildRequest);
                reply(frame, FrameType.BUILD_STARTED,
                        out -> MessageIO.writeBuildStarted(out, new BuildStarted(
                                request.buildId())));
                startScriptedBuild(request);
            }
            case CANCEL_BUILD -> {
                CancelRequest cancel = decode(frame, MessageIO::readCancelRequest);
                cancelBuild(frame, cancel.buildId());
            }
            case TASKS_REQUEST -> {
                // Parité protocole 5-6 (D-22/D-23) avec le vrai moteur : répertoire
                // fixe mais HONNÊTE — délégué au VRAI compositeur (LifecycleTasks),
                // donc assemble + build + refus des modules inconnus se
                // comportent exactement comme chez le vrai moteur. La négociation
                // protocole protège les coquilles anciennes comme toujours.
                if (protocoleNégocié < 5) {
                    reply(frame, FrameType.PROTOCOL_ERROR, out ->
                            MessageIO.writeErrorMessage(out,
                                    "TASKS_REQUEST refusée : protocole "
                                            + protocoleNégocié + " < 5"));
                    return false;
                }
                TasksRequest demande = decode(frame, MessageIO::readTasksRequest);
                try {
                    List<TaskDescription> descripteurs = LifecycleTasks.descripteurs(
                            tâchesFactices(), demande.modules());
                    reply(frame, FrameType.TASKS, out ->
                            MessageIO.writeTasksResponse(out,
                                    new TasksResponse(demande.projectRoot(),
                                            descripteurs)));
                } catch (GraphException refus) {
                    reply(frame, FrameType.INTERNAL_ERROR, out ->
                            MessageIO.writeErrorMessage(out, refus.getMessage()));
                }
            }
            case SHUTDOWN -> {
                // Accusé avant de s'arrêter : la coquille sait que l'arrêt est pris en
                // compte avant que le canal ne se ferme.
                reply(frame, FrameType.SHUTDOWN, out -> { });
                return false;
            }
            default -> reply(frame, FrameType.PROTOCOL_ERROR, out -> MessageIO.writeErrorMessage(
                    out, "Trame inattendue : " + frame.type()));
        }
        return true;
    }

    /**
     * Lance le build scripté correspondant à la requête.
     */
    private void startScriptedBuild(BuildRequest request) {
        runningBuilds.computeIfAbsent(request.buildId(), id ->
                buildExecutor.submit(() -> runScriptedBuild(request)));
    }

    /**
     * Le graphe concret du répertoire factice : un module {@code app} au
     * vocabulaire du vrai pipeline ({@code resolveDependencies} →
     * {@code compileJava} → {@code jar}) — le compositeur réel (D-22/D-23) y
     * dérive le cycle de vie ({@code assemble}, {@code build}, {@code clean},
     * agrégats de module) comme chez le vrai moteur.
     */
    private static List<Task> tâchesFactices() {
        return List.of(
                tâcheFactice("app:resolveDependencies", List.of()),
                tâcheFactice("app:compileJava", List.of("app:resolveDependencies")),
                tâcheFactice("app:jar", List.of("app:compileJava")));
    }

    /** Une tâche factice, purement nominale (le répertoire ne l'exécute jamais). */
    private static Task tâcheFactice(String nom, List<String> dépendances) {
        return new Task() {
            @Override public String name() { return nom; }
            @Override public List<String> dependencies() { return dépendances; }
            @Override public void execute(TaskContext context) { }
        };
    }

    /**
     * Déroule le scénario du build scripté (succès, échec ou lent selon l'identifiant).
     *
     * <p>Parité protocole 4 (D-21) avec le vrai moteur : vers une coquille moderne,
     * les lignes de console (tâche à l'issue, verdict) partent composées PAR LE
     * MOTEUR et le niveau demandé ({@code consoleLevel}) filtre le journal ; vers une
     * coquille ancienne, le scénario d'avant — la coquille compose elle-même.</p>
     */
    private void runScriptedBuild(BuildRequest request) {
        String id = request.buildId();
        long start = System.currentTimeMillis();
        ConsoleDeBuild console = new ConsoleDeBuild(request.options().consoleLevel());
        boolean consoleMoteur = protocoleNégocié >= 4;
        try {
            if (!consoleMoteur || console.visible(BuildEvent.LogEvent.LogLevel.INFO)) {
                event(new BuildEvent.LogEvent(id, now(), BuildEvent.LogEvent.LogLevel.INFO,
                        "Build factice démarré pour " + request.projectRoot()));
            }
            event(new BuildEvent.TaskStateEvent(id, now(), "demo:compile",
                    BuildEvent.TaskState.STARTED, 0));
            if (id.startsWith("slow-")) {
                // Build volontairement long : laisse le temps d'annuler en cours de route.
                for (int i = 0; i < 100 && !Thread.currentThread().isInterrupted(); i++) {
                    Thread.sleep(100);
                }
                if (Thread.currentThread().isInterrupted()) {
                    return; // L'accusé d'annulation est envoyé par cancelBuild().
                }
            } else {
                Thread.sleep(50);
            }
            if (id.startsWith("fail-")) {
                event(new BuildEvent.DiagnosticEvent(id, now(),
                        BuildEvent.Severity.ERROR, "src/Demo.java", 7, 12,
                        "demo:error", "erreur simulée pour les tests", "fake-engine"));
                BuildEvent.TaskStateEvent échec = new BuildEvent.TaskStateEvent(id, now(),
                        "demo:compile", BuildEvent.TaskState.FAILED, 60);
                event(échec);
                if (consoleMoteur) {
                    consoleLigne(id, console.ligneTâche(échec));
                }
                BuildEvent.BuildFinished fin = new BuildEvent.BuildFinished(id, now(),
                        false, "demo:compile", System.currentTimeMillis() - start,
                        "échec simulé");
                if (consoleMoteur) {
                    consoleLigne(id, console.verdict(fin));
                }
                event(fin);
                return;
            }
            BuildEvent.TaskStateEvent succès = new BuildEvent.TaskStateEvent(id, now(),
                    "demo:compile", BuildEvent.TaskState.SUCCEEDED, 60);
            event(succès);
            if (consoleMoteur) {
                consoleLigne(id, console.ligneTâche(succès));
            }
            event(new BuildEvent.ProgressEvent(id, now(), 1, 1));
            BuildEvent.BuildFinished fin = new BuildEvent.BuildFinished(id, now(), true,
                    null, System.currentTimeMillis() - start, "build factice réussi");
            if (consoleMoteur) {
                consoleLigne(id, console.verdict(fin));
            }
            event(fin);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            // L'accusé d'annulation est envoyé par cancelBuild(), qui nous a interrompus.
        } catch (IOException ioError) {
            diagnostics.println("Erreur d'écriture du canal : " + ioError.getMessage());
        }
    }

    /**
     * Émet une ligne de console composée par le moteur (protocole 4, LIFECYCLE).
     */
    private void consoleLigne(String buildId, String ligne) throws IOException {
        event(new BuildEvent.LogEvent(buildId, now(),
                BuildEvent.LogEvent.LogLevel.LIFECYCLE, ligne));
    }

    /**
     * Annule le build demandé et n'accuse qu'une fois le scénario totalement arrêté
     * (exigence D-08 : l'accusé d'annulation suit l'arrêt effectif).
     */
    private void cancelBuild(Frame requestFrame, String buildId) throws IOException {
        Future<?> build = runningBuilds.remove(buildId);
        boolean wasRunning = build != null;
        if (wasRunning) {
            build.cancel(true);
            try {
                build.get(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // Le scénario est borné : on ne peut pas attendre indéfiniment ici.
            }
        }
        reply(requestFrame, FrameType.CANCELLED, out -> MessageIO.writeCancelled(
                out, new Cancelled(buildId, wasRunning)));
    }

    /**
     * Envoie la poignée de main initiale.
     */
    private void sendHello() throws IOException {
        writer.write(FrameType.HELLO, 0, out -> MessageIO.writeHello(out,
                new Hello(ENGINE_VERSION, ProtocolVersions.PROTOCOL_MIN,
                        ProtocolVersions.PROTOCOL_MAX, CAPABILITIES)));
    }

    /**
     * Émet un événement de build.
     */
    private void event(BuildEvent buildEvent) throws IOException {
        writer.write(FrameType.BUILD_EVENT, 0,
                out -> MessageIO.writeBuildEvent(out, buildEvent));
    }

    /**
     * Répond à une requête en réutilisant son identifiant de corrélation.
     */
    private void reply(Frame request, FrameType type,
                       FrameWriter.PayloadWriter payload) throws IOException {
        writer.write(type, request.correlationId(), payload);
    }

    /**
     * Décode la charge utile d'une trame avec le décodeur donné.
     */
    private <T> T decode(Frame frame, PayloadReader<T> reader) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(frame.payload()));
        return reader.readFrom(in);
    }

    /**
     * Arrête l'exécuteur de builds (les scénarios en cours sont interrompus).
     */
    private void shutdownExecutors() {
        buildExecutor.shutdownNow();
    }

    private long now() {
        return System.currentTimeMillis();
    }

    private long uptimeMillis() {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - START_TIME);
    }

    /** Lecteur de charge utile typé. */
    @FunctionalInterface
    private interface PayloadReader<T> {

        /**
         * Décode un message depuis le flux.
         *
         * @param in flux source.
         * @return le message décodé.
         * @throws IOException en cas d'erreur de lecture.
         */
        T readFrom(DataInputStream in) throws IOException;
    }
}
