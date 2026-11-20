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
import jo.forge.engine.diag.MessagesErreursGraves;
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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
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
 * dédié, et <em>toutes</em> les écritures de trames passent par un fil rédacteur
 * unique (file d'attente vidée par un seul thread, qui vit aussi longtemps que le
 * serveur). C'est une exigence du protocole ET des tubes : un canal à écriture
 * multi-fils fait mourir le lecteur sitôt qu'un écrivain éphémère se termine
 * ({@code Write end dead} sur {@code PipedInputStream} — les files de tâches sont
 * précisément éphémères). Le fil rédacteur préserve l'ordre d'émission : chaque
 * trame est mise en file au point d'émission, et la file est un FIFO.</p>
 */
public final class BuildEngineServer {

    /** Capacités annoncées dans la poignée de main. */
    public static final List<String> CAPABILITIES = List.of("ping", "status", "build");

    private static final long START_TIME = System.nanoTime();

    private final FrameWriter writer;
    private final PrintStream diagnostics;
    private final ExecutorService buildExecutor;
    private final Map<String, BuildEnCours> runningBuilds = new ConcurrentHashMap<>();
    private final RédacteurCanal canal;

    /** Répertoire du moteur installé (composants aapt2, plate-forme), ou null.
     * Transmit par la coquille via la variable d'environnement FORGE_ENGINE_DIR. */
    private final java.nio.file.Path engineHome;

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
        this.canal = new RédacteurCanal();
        this.engineHome = engineHomeDepuisEnvironnement();
    }

    /**
     * Répertoire du moteur installé depuis l'environnement (coquille → moteur) ;
     * absent en développement : les outils viennent alors de la machine.
     */
    private static java.nio.file.Path engineHomeDepuisEnvironnement() {
        String valeur = System.getenv("FORGE_ENGINE_DIR");
        return valeur == null || valeur.isBlank() ? null : java.nio.file.Path.of(valeur);
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
        canal.écrire(FrameType.HELLO, 0, out -> MessageIO.writeHello(out,
                new Hello(EngineVersion.CURRENT, ProtocolVersions.PROTOCOL_MIN,
                        ProtocolVersions.PROTOCOL_MAX, CAPABILITIES)));
        try {
            boolean running = true;
            while (running) {
                Frame frame = reader.readFrame();
                if (frame == null) {
                    running = false;
                    continue;
                }
                running = handle(frame);
            }
        } finally {
            // Couper d'abord les producteurs (builds en cours), puis vider la file :
            // l'accusé SHUTDOWN en attente doit atteindre le client avant l'arrêt.
            buildExecutor.shutdownNow();
            canal.fermer();
        }
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
                    request.options().offline(), request.options().abi(), engineHome);
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
        } catch (Error erreurGrave) {
            // Filet de sécurité, miroir de celui de l'exécuteur : un Error avant les
            // tâches doit terminer le build par un échec PROPRE — pas un fil qui
            // meurt en silence et un client qui attend une fin qui ne viendra
            // jamais. La trace part sur stderr : le client la capture (journal du
            // moteur). Message classé honnêtement depuis v0.2.6 : la mémoire n'est
            // qu'UNE cause possible, et le diagnostic structuré part aussi sur le
            // canal (la trace n'est plus muette sur l'origine).
            diagnostics.println("Erreur grave dans le build " + buildId + " : "
                    + erreurGrave);
            erreurGrave.printStackTrace(diagnostics);
            try {
                String message = MessagesErreursGraves.pour(erreurGrave,
                        "le build " + buildId);
                events.diagnostic(Diagnostic.error("moteur", message));
                echecPropre(events, buildId, debut, message);
            } catch (Throwable plusRien) {
                // Le canal lui-même est épuisé : la trace stderr reste au client.
            }
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
     * accusé (D-08). L'accusé passe par le fil rédacteur : il suit donc, dans la
     * file, la trame de fin du build annulé (émise avant la fin du futur).
     */
    private void cancelBuild(Frame requestFrame, String buildId) {
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
            // La trace est écrite AVANT l'émission de la fin : le client qui voit la
            // trame BuildFinished peut affirmer immédiatement que la trace existe
            // (l'ordre du canal reste : fin = dernier événement émis).
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
            event(BuildEvent.BuildFinished.class, finished);
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
            canal.écrire(FrameType.BUILD_EVENT, 0,
                    out -> MessageIO.writeBuildEvent(out, evenement));
        }
    }

    /**
     * Fil rédacteur du canal : TOUTES les trames du serveur (poignée de main,
     * accusés, événements de build) passent par cette file FIFO vidée par un seul
     * thread, qui vit aussi longtemps que le serveur.
     *
     * <p>Pourquoi : les événements sont émis par les threads de tâches du build,
     * qui sont éphémères (le pool est arrêté à chaque fin de build). Un canal
     * multi-écrivains rend le lecteur fragile au premier écrivain mort —
     * {@code PipedInputStream} lève {@code Write end dead} dès que le dernier
     * écrivain du tube disparaît, même si d'autres trames sont encore à venir
     * (flocon CI : {@code BuildEngineServerTest}, build terminé sans que le client
     * voie la trame de fin). Avec un rédacteur unique et durable, le dernier
     * écrivain ne meurt qu'à l'arrêt du serveur — après vidange de la file.</p>
     *
     * <p>Ordre : l'ordre d'émission est l'ordre de mise en file, donc l'ordre du
     * canal — y compris « BuildFinished avant CANCELLED » (l'accusé d'annulation
     * n'est mis en file qu'après l'attente du build, dont la trame de fin est déjà
     * en file) et « BUILD_STARTED avant tout événement du build ».</p>
     */
    private final class RédacteurCanal {

        private final BlockingQueue<Écriture> file = new LinkedBlockingQueue<>();
        private final Thread fil;
        private volatile boolean fermé;

        RédacteurCanal() {
            fil = new Thread(this::boucle, "forge-rédacteur");
            fil.setDaemon(true);
            fil.start();
        }

        /**
         * Met une trame en file (appelé par le lecteur du canal et les builds).
         *
         * @param type          type de la trame.
         * @param corrélation   identifiant de corrélation.
         * @param charge        encodeur de la charge utile.
         */
        void écrire(FrameType type, long corrélation,
                    FrameWriter.PayloadWriter charge) {
            if (fermé) {
                return; // Serveur arrêté : les dernières trames sont abandonnées.
            }
            file.add(new Écriture(type, corrélation, charge));
        }

        /** Vide la file puis arrête le rédacteur (fin de serve). */
        void fermer() {
            fermé = true;
            file.add(new Écriture(null, 0, null)); // Pastille d'arrêt.
            try {
                fil.join(TimeUnit.SECONDS.toMillis(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void boucle() {
            try {
                while (true) {
                    Écriture écriture = file.take();
                    if (écriture.charge() == null) {
                        return; // Pastille : tout ce qui précède est déjà écrit.
                    }
                    try {
                        writer.write(écriture.type(), écriture.corrélation(),
                                écriture.charge());
                    } catch (RuntimeException encodage) {
                        // Une charge invalide ne doit pas tuer le rédacteur : la
                        // trame est perdue, le canal continue.
                        diagnostics.println("Trame non écrite (encodage) : "
                                + encodage.getMessage());
                    }
                }
            } catch (InterruptedException fin) {
                Thread.currentThread().interrupt();
            } catch (IOException canalPerdu) {
                // Le pair a fermé le canal : plus rien ne passera.
                diagnostics.println("Canal de sortie fermé par le pair : "
                        + canalPerdu.getMessage());
                fermé = true;
            }
        }

        /** Une trame en attente (charge nulle = pastille d'arrêt). */
        private record Écriture(FrameType type, long corrélation,
                                FrameWriter.PayloadWriter charge) {
        }
    }

    /**
     * Répond à une requête en réutilisant son identifiant de corrélation — via le fil
     * rédacteur, pour un canal à écrivain unique.
     */
    private void reply(Frame request, FrameType type,
                       FrameWriter.PayloadWriter payload) {
        canal.écrire(type, request.correlationId(), payload);
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
