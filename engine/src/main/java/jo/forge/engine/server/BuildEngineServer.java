package jo.forge.engine.server;

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
import jo.forge.engine.EngineVersion;
import jo.forge.engine.diag.Diagnostic;
import jo.forge.engine.exec.TaskExecutor;
import jo.forge.engine.graph.GraphException;
import jo.forge.engine.graph.TaskGraph;
import jo.forge.engine.model.ForgeModel;
import jo.forge.engine.model.ModelCodec;
import jo.forge.engine.model.ModelException;
import jo.forge.engine.pipeline.ProjectLayout;
import jo.forge.engine.pipeline.java.JavaPipeline;
import jo.forge.engine.state.FingerprintStore;
import jo.forge.engine.tasks.CancellationFlag;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Serveur du vrai moteur de build : remplace le moteur factice de l'étape 2 au même
 * point d'entrée, sans changer une ligne du protocole.
 *
 * <p>Un {@code START_BUILD} déclenche sur un exécuteur dédié : lecture de
 * {@code forge.json} à la racine reçue, construction des tâches par le pipeline,
 * graphe validé, exécuteur borné avec empreintes persistantes, événements et
 * diagnostics convertis en trames au fil de l'eau. Les erreurs de modèle (fichier
 * absent, module exigeant une étape non livrée, cycle) sont des échecs de build
 * propres avec message — jamais un écroulement du canal.</p>
 *
 * <p>Annulation (D-08) : {@code CANCEL_BUILD} pose le drapeau, interrompt les tâches,
 * attend l'arrêt effectif, puis accuse — aucun worker ne survit à l'accusé.</p>
 *
 * <p>Thread-sûreté : lecteur du canal dans le thread appelant, builds sur l'exécuteur
 * dédié, écritures toutes via le {@link FrameWriter} synchronisé.</p>
 */
public final class BuildEngineServer {

    /** Capacités annoncées dans la poignée de main. */
    public static final List<String> CAPABILITIES = List.of("ping", "status", "build");

    private static final long START_TIME = System.nanoTime();

    private final FrameWriter writer;
    private final PrintStream diagnostics;
    private final ExecutorService buildExecutor;
    private final Map<String, BuildEnCours> runningBuilds = new ConcurrentHashMap<>();

    /** Un build en cours : son exécution et son jeton d'annulation. */
    private record BuildEnCours(Future<?> future, CancellationFlag cancellation) {
    }

    /**
     * Crée le serveur sur les flux donnés.
     *
     * @param out         flux de sortie du canal de contrôle (stdout du processus).
     * @param diagnostics flux d'écroulement (stderr du processus), jamais pour les
     *                    trames.
     */
    public BuildEngineServer(OutputStream out, PrintStream diagnostics) {
        this.writer = new FrameWriter(out);
        this.diagnostics = diagnostics;
        this.buildExecutor = Executors.newCachedThreadPool();
    }

    /**
     * Boucle principale : poignée de main puis traitement des trames jusqu'à la
     * fermeture du canal ou l'arrêt demandé.
     *
     * @param in flux d'entrée du canal (stdin du processus).
     * @throws IOException en cas d'erreur d'E/S sur le canal.
     */
    public void serve(InputStream in) throws IOException {
        FrameReader reader = new FrameReader(in);
        writer.write(FrameType.HELLO, 0, out -> MessageIO.writeHello(out,
                new Hello(EngineVersion.CURRENT, ProtocolVersions.PROTOCOL_MIN,
                        ProtocolVersions.PROTOCOL_MAX, CAPABILITIES)));
        boolean running = true;
        while (running) {
            Frame frame = reader.readFrame();
            if (frame == null) {
                running = false;
                continue;
            }
            running = handle(frame);
        }
        buildExecutor.shutdownNow();
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
                    reply(frame, FrameType.PROTOCOL_ERROR, out ->
                            MessageIO.writeErrorMessage(out,
                                    "Version de protocole refusée : "
                                            + hello.protocolVersion()));
                    return false;
                }
                reply(frame, FrameType.CLIENT_HELLO, out -> MessageIO.writeClientHello(out,
                        new ClientHello(EngineVersion.CURRENT, hello.protocolVersion())));
            }
            case PING -> reply(frame, FrameType.PONG,
                    out -> MessageIO.writePong(out, new Pong(System.currentTimeMillis())));
            case STATUS_REQUEST -> reply(frame, FrameType.STATUS, out ->
                    MessageIO.writeStatus(out, new Status(EngineVersion.CURRENT,
                            runningBuilds.isEmpty() ? EngineState.IDLE
                                    : EngineState.BUILDING,
                            uptimeMillis(), CAPABILITIES)));
            case START_BUILD -> {
                BuildRequest request = decode(frame, MessageIO::readBuildRequest);
                reply(frame, FrameType.BUILD_STARTED, out ->
                        MessageIO.writeBuildStarted(out,
                                new BuildStarted(request.buildId())));
                startBuild(request);
            }
            case CANCEL_BUILD -> {
                CancelRequest cancel = decode(frame, MessageIO::readCancelRequest);
                cancelBuild(frame, cancel.buildId());
            }
            case SHUTDOWN -> {
                reply(frame, FrameType.SHUTDOWN, out -> { });
                return false;
            }
            default -> reply(frame, FrameType.PROTOCOL_ERROR, out ->
                    MessageIO.writeErrorMessage(out,
                            "Trame inattendue : " + frame.type()));
        }
        return true;
    }

    /**
     * Lance le build demandé (un seul build actif par identifiant).
     */
    private void startBuild(BuildRequest request) {
        runningBuilds.computeIfAbsent(request.buildId(), id -> {
            CancellationFlag cancellation = new CancellationFlag();
            Future<?> future = buildExecutor.submit(() -> runBuild(request, cancellation));
            return new BuildEnCours(future, cancellation);
        });
    }

    /**
     * Déroule un build complet : modèle, tâches, graphe, exécution — tout échec
     * devient un {@code BuildFinished} propre.
     */
    private void runBuild(BuildRequest request, CancellationFlag cancellation) {
        String buildId = request.buildId();
        long debut = System.currentTimeMillis();
        Path root = Path.of(request.projectRoot());
        EvenementsVersTrames events = new EvenementsVersTrames(buildId, root);
        try {
            events.log(BuildEvent.LogEvent.LogLevel.INFO,
                    "build de " + request.projectRoot());

            ForgeModel model = ModelCodec.read(root);
            List<jo.forge.engine.tasks.Task> tasks = JavaPipeline.tasksFor(model, root,
                    request.options().offline(), request.options().abi());
            TaskGraph graph = TaskGraph.of(tasks);
            ProjectLayout layout = new ProjectLayout(root);
            FingerprintStore store = new FingerprintStore(layout.stateDir(), root,
                    avertissement -> events.log(BuildEvent.LogEvent.LogLevel.WARN,
                            "magasin d'empreintes : " + avertissement));
            TaskExecutor executor = new TaskExecutor(graph, events, cancellation, store,
                    request.options().parallelism(), root);
            // Remarque de contrat : le BuildFinished émis par l'exécuteur est TOUJOURS
            // le dernier événement du build — rien ne doit être journalisé après.
            executor.execute();
        } catch (ModelException modele) {
            echecPropre(events, buildId, debut, modele.getMessage());
        } catch (GraphException graphe) {
            echecPropre(events, buildId, debut, graphe.getMessage());
        } catch (Exception inattendu) {
            diagnostics.println("Écroulement du build " + buildId + " : " + inattendu);
            inattendu.printStackTrace(diagnostics);
            echecPropre(events, buildId, debut,
                    "erreur interne du moteur : " + inattendu.getClass().getSimpleName()
                            + (inattendu.getMessage() == null ? "" : " — "
                            + inattendu.getMessage()));
        } finally {
            runningBuilds.remove(buildId);
        }
    }

    /**
     * Termine un build en échec avant toute tâche (modèle invalide, cycle…) : un
     * {@code BuildFinished} propre avec le message, jamais d'exception sur le canal.
     */
    private void echecPropre(EvenementsVersTrames events, String buildId, long debut,
                             String message) {
        events.log(BuildEvent.LogEvent.LogLevel.ERROR, message);
        events.buildFinished(new BuildEvent.BuildFinished(buildId,
                System.currentTimeMillis(), false, null,
                System.currentTimeMillis() - debut, message));
    }

    /**
     * Annule un build : drapeau + interruption, attente de l'arrêt effectif, puis
     * accusé (D-08).
     */
    private void cancelBuild(Frame requestFrame, String buildId) throws IOException {
        BuildEnCours build = runningBuilds.remove(buildId);
        boolean wasRunning = build != null;
        if (wasRunning) {
            build.cancellation().cancel();
            try {
                build.future().get(30, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // Borné : l'exécuteur interrompt tout, l'attente est une ceinture.
            }
        }
        reply(requestFrame, FrameType.CANCELLED, out -> MessageIO.writeCancelled(out,
                new Cancelled(buildId, wasRunning)));
    }

    /**
     * Adaptateur événements d'exécuteur → trames du canal : conversion des
     * diagnostics internes, rédaction des secrets (D-09), journal borné (P-7) et
     * trace exportable (A-13).
     */
    private final class EvenementsVersTrames implements TaskExecutor.BuildEvents {

        private final String buildId;
        private final Path racineProjet;
        private final AtomicReference<String> premierEchec = new AtomicReference<>();
        private final JournalBorné journal = new JournalBorné(JournalBorné.CAPACITÉ_PAR_DÉFAUT);
        private final Map<String, BuildEvent.TaskStateEvent> étatsTâches =
                new java.util.concurrent.ConcurrentHashMap<>();
        private final Map<String, Long> diagnosticsParSévérité =
                new java.util.concurrent.ConcurrentHashMap<>();

        EvenementsVersTrames(String buildId, Path racineProjet) {
            this.buildId = buildId;
            this.racineProjet = racineProjet;
        }

        @Override
        public String buildId() {
            return buildId;
        }

        @Override
        public void taskState(BuildEvent.TaskStateEvent event) {
            étatsTâches.put(event.taskName(), event);
            event(BuildEvent.TaskStateEvent.class, event);
        }

        @Override
        public void log(BuildEvent.LogEvent.LogLevel level, String message) {
            String nettoyé = jo.forge.engine.diag.SecretRedactor.rédiger(message);
            journal.ajouter(level + " " + nettoyé);
            event(BuildEvent.LogEvent.class, new BuildEvent.LogEvent(buildId,
                    System.currentTimeMillis(), level, nettoyé));
        }

        @Override
        public void progress(int total, int done) {
            event(BuildEvent.ProgressEvent.class, new BuildEvent.ProgressEvent(buildId,
                    System.currentTimeMillis(), total, done));
        }

        @Override
        public void diagnostic(Diagnostic diagnostic) {
            diagnosticsParSévérité.merge(diagnostic.severity().name(), 1L, Long::sum);
            event(BuildEvent.DiagnosticEvent.class, new BuildEvent.DiagnosticEvent(buildId,
                    System.currentTimeMillis(), diagnostic.severity(), diagnostic.file(),
                    diagnostic.line(), diagnostic.column(), diagnostic.code(),
                    jo.forge.engine.diag.SecretRedactor.rédiger(diagnostic.message()),
                    diagnostic.tool()));
        }

        @Override
        public void buildFinished(BuildEvent.BuildFinished finished) {
            event(BuildEvent.BuildFinished.class, finished);
            // Trace exportable : après l'événement de fin pour le canal, mais
            // l'écriture du FICHIER ne traverse pas le canal (ordre du canal préservé).
            try {
                List<BuildTrace.Tâche> tâches = new ArrayList<>();
                étatsTâches.values().stream()
                        .sorted(java.util.Comparator.comparing(
                                BuildEvent.TaskStateEvent::taskName))
                        .forEach(état -> tâches.add(new BuildTrace.Tâche(
                                état.taskName(), état.state().name(), état.durationMs())));
                new BuildTrace(buildId, racineProjet.toString(), EngineVersion.CURRENT,
                        tâches, new java.util.LinkedHashMap<>(diagnosticsParSévérité),
                        journal.copie())
                        .écrire(racineProjet.resolve(".forge").resolve("traces"),
                                finished.durationMs(), finished.success());
            } catch (IOException e) {
                diagnostics.println("Trace de build non écrite (" + buildId + ") : "
                        + e.getMessage());
            }
        }

        @Override
        public String dernierEchec() {
            return premierEchec.get();
        }

        @Override
        public void tacheEchouee(String taskName) {
            premierEchec.compareAndSet(null, taskName);
        }

        /** Émet un événement sur le canal (toujours dans l'ordre d'appel). */
        private void event(Class<?> type, BuildEvent evenement) {
            try {
                writer.write(FrameType.BUILD_EVENT, 0,
                        out -> MessageIO.writeBuildEvent(out, evenement));
            } catch (IOException e) {
                diagnostics.println("Canon de sortie perdu (" + type.getSimpleName()
                        + ") : " + e.getMessage());
            }
        }
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

    private long uptimeMillis() {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - START_TIME);
    }
}
