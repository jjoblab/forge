package jo.forge.client;

import jo.forge.api.BuildEvent;
import jo.forge.api.BuildRequest;
import jo.forge.api.BuildStarted;
import jo.forge.api.Cancelled;
import jo.forge.api.CancelRequest;
import jo.forge.api.ClientHello;
import jo.forge.api.Frame;
import jo.forge.api.FrameType;
import jo.forge.api.Hello;
import jo.forge.api.MessageIO;
import jo.forge.api.ProtocolVersions;
import jo.forge.api.Status;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Client du moteur : lance la VM enfant, négocie le protocole puis expose les fonctions
 * du contrat (ping, état, build, annulation, arrêt).
 *
 * <p>Utilisation :</p>
 * <pre>{@code
 * EngineClient client = EngineClient.launch(
 *         new JavaVmLauncher(), spec, "1.2.3", événements -> ...);
 * Status état = client.status().get(5, TimeUnit.SECONDS);
 * client.startBuild(requête);
 * client.shutdown().get(5, TimeUnit.SECONDS);
 * }</pre>
 *
 * <p>La négociation suit la décision D-07 : le moteur annonce sa plage de protocole ;
 * la coquille refuse proprement si sa version n'y entre pas, le moteur refuse
 * proprement la version choisie si elle sort de la sienne. Les événements de build
 * arrivent en flux vers le consommateur unique donné au lancement.</p>
 */
public final class EngineClient {

    /** Délai par défaut des requêtes en secondes. */
    private static final int DEFAULT_TIMEOUT_SECONDS = 15;

    /** Délai des requêtes courtes (état, sonde, arrêt) en secondes. */
    private static final int DÉLAI_COURT_S = 15;

    /**
     * Délai de l'accusé de DÉMARRAGE d'un build ou d'un sync (le moteur lit le
     * modèle et dresse le graphe avant d'accuser — un appareil modeste peut être
     * lent), en secondes.
     */
    private static final int DÉLAI_DÉMARRAGE_S = 60;

    /**
     * Délai de l'accusé d'ANNULATION : il n'arrive qu'une fois le build TOTALEMENT
     * arrêté (exigence D-08) — un dexage en cours doit finir son seau, en secondes.
     */
    private static final int DÉLAI_ANNULATION_S = 180;

    private final EngineConnection connection;
    private final Hello hello;

    private EngineClient(EngineConnection connection, Hello hello) {
        this.connection = connection;
        this.hello = hello;
    }

    /**
     * Lance le moteur, attend sa poignée de main et répond la nôtre.
     *
     * @param launcher      stratégie de lancement de la VM (appareil ou développement).
     * @param spec          description de la VM moteur.
     * @param shellVersion  version de la coquille (journal et diagnostic côté moteur).
     * @param eventConsumer consommateur des événements de build (appelé depuis le thread
     *                      lecteur ; doit être rapide et non bloquant).
     * @param onDead        rappel de mort du moteur (redémarrage, diagnostic).
     * @return le client connecté, protocole négocié.
     * @throws EngineConnection.EngineConnectionException si le lancement, la poignée de
     *         main ou la négociation échoue.
     */
    public static EngineClient launch(VmLauncher launcher, VmSpec spec, String shellVersion,
                                      Consumer<BuildEvent> eventConsumer, Runnable onDead) {
        return launch(launcher, spec, shellVersion, eventConsumer, onDead, null);
    }

    /**
     * Lance le moteur en écoutant, en plus, son journal de diagnostics : chaque ligne
     * que le processus moteur écrit sur stderr (traces d'écroulement, exceptions non
     * attrapées) est transmise au consommateur et conservée dans le journal borné du
     * client ({@link #journalErreurs()}) — la coquille s'en sert pour déboguer une mort
     * du moteur sur appareil.
     *
     * @param launcher      stratégie de lancement de la VM (appareil ou développement).
     * @param spec          description de la VM moteur.
     * @param shellVersion  version de la coquille (journal et diagnostic côté moteur).
     * @param eventConsumer consommateur des événements de build (appelé depuis le thread
     *                      lecteur ; doit être rapide et non bloquant).
     * @param onDead        rappel de mort du moteur (redémarrage, diagnostic).
     * @param journalMoteur consommateur des lignes de stderr du moteur (null : journal
     *                      interne seul) ; appelé depuis le fil de drainage.
     * @return le client connecté, protocole négocié.
     * @throws EngineConnection.EngineConnectionException si le lancement, la poignée de
     *         main ou la négociation échoue.
     */
    public static EngineClient launch(VmLauncher launcher, VmSpec spec, String shellVersion,
                                      Consumer<BuildEvent> eventConsumer, Runnable onDead,
                                      Consumer<String> journalMoteur) {
        Process process;
        try {
            process = launcher.start(spec);
        } catch (IOException ioError) {
            throw new EngineConnection.EngineConnectionException(
                    "Lancement du moteur impossible : " + ioError.getMessage(), ioError);
        }
        CompletableFuture<Frame> helloFrame = new CompletableFuture<>();
        EngineConnection connection = new EngineConnection(process, eventConsumer,
                helloFrame::complete, onDead, journalMoteur);
        connection.start();

        // La poignée de main est une trame spontanée : on l'attend sur le flux.
        HelloHandshake handshake = awaitHello(connection, helloFrame);
        int chosen = chooseProtocol(handshake.hello());
        ClientHello clientHello = new ClientHello(shellVersion, chosen);
        Frame acknowledgement = borné(connection
                .request(FrameType.CLIENT_HELLO, out -> MessageIO.writeClientHello(
                        out, clientHello)), DEFAULT_TIMEOUT_SECONDS,
                "l'accusé de poignée de main").join();
        if (acknowledgement.type() == FrameType.PROTOCOL_ERROR) {
            connection.kill();
            throw new EngineConnection.EngineConnectionException(
                    "Moteur incompatible : " + decodeError(acknowledgement));
        }
        if (acknowledgement.type() != FrameType.CLIENT_HELLO) {
            connection.kill();
            throw new EngineConnection.EngineConnectionException(
                    "Accusé de poignée de main inattendu : " + acknowledgement.type());
        }
        return new EngineClient(connection, handshake.hello());
    }

    /**
     * Poignée de main reçue au lancement.
     *
     * @return le message {@code HELLO} du moteur.
     */
    public Hello hello() {
        return hello;
    }

    /**
     * Journal de diagnostics du moteur : lignes de stderr capturées en continu, bornées
     * aux 500 dernières — vide si le moteur n'a rien écrit.
     *
     * @return la copie des lignes.
     */
    public List<String> journalErreurs() {
        return connection.journalErreurs();
    }

    /**
     * Code de sortie du moteur (0 = propre, négatif = signal, positif = échec interne),
     * ou {@code null} tant que le processus vit.
     *
     * @return le code, ou null.
     */
    public Integer codeSortie() {
        return connection.codeSortie();
    }

    /**
     * Interroge l'état du moteur.
     *
     * @return le futur de l'état.
     */
    public CompletableFuture<Status> status() {
        return borné(connection.request(FrameType.STATUS_REQUEST, out -> { })
                        .thenApply(frame -> decode(frame, MessageIO::readStatus)),
                DÉLAI_COURT_S, "status");
    }

    /**
     * Sonde la vivacité du moteur.
     *
     * @return le futur de l'horodatage moteur du pong.
     */
    public CompletableFuture<Long> ping() {
        return borné(connection.request(FrameType.PING, out -> { })
                        .thenApply(frame -> decode(frame, MessageIO::readPong).engineTimestamp()),
                DÉLAI_COURT_S, "ping");
    }

    /**
     * Lance un build ; les événements arrivent en flux vers le consommateur.
     *
     * @param request la requête de build.
     * @return le futur de l'accusé de démarrage (identifiant acté par le moteur).
     */
    public CompletableFuture<BuildStarted> startBuild(BuildRequest request) {
        return borné(connection.request(FrameType.START_BUILD,
                        out -> MessageIO.writeBuildRequest(out, request))
                        .thenApply(frame -> decode(frame, MessageIO::readBuildStarted)),
                DÉLAI_DÉMARRAGE_S, "le démarrage du build");
    }

    /**
     * Lance un sync : résolution des dépendances et préparation du modèle, SANS
     * compilation (protocole 2 ; un moteur ancien refuse proprement la trame).
     * Les événements arrivent en flux vers le consommateur, la fin reste un
     * {@code BuildFinished}.
     *
     * @param request la requête (mêmes champs qu'un build).
     * @return le futur de l'accusé de démarrage (identifiant acté par le moteur).
     */
    public CompletableFuture<BuildStarted> sync(BuildRequest request) {
        // Garde-fou : une trame inconnue TUE le lecteur d'un moteur plus ancien —
        // le refus doit venir de la coquille, jamais du moteur.
        if (hello.protocolMax() < 2) {
            CompletableFuture<BuildStarted> refus = new CompletableFuture<>();
            refus.completeExceptionally(new EngineConnection.EngineConnectionException(
                    "Sync non supporté : le moteur installé est trop ancien "
                            + "(protocole " + hello.protocolMax() + " < 2) — mettez "
                            + "le moteur à jour"));
            return refus;
        }
        return borné(connection.request(FrameType.SYNC_BUILD,
                        out -> MessageIO.writeBuildRequest(out, request))
                        .thenApply(frame -> {
                            if (frame.type() == FrameType.PROTOCOL_ERROR) {
                                // Moteur plus ancien que le protocole 2 : refus propre, le
                                // canal reste ouvert pour les builds ordinaires.
                                throw new EngineConnection.EngineConnectionException(
                                        "Sync non supporté par ce moteur (protocole trop ancien)"
                                                + " : " + decodeError(frame));
                            }
                            return decode(frame, MessageIO::readBuildStarted);
                        }),
                DÉLAI_DÉMARRAGE_S, "le sync");
    }

    /**
     * Annule un build ; l'accusé n'arrive qu'une fois le build totalement arrêté
     * (exigence D-08).
     *
     * @param buildId identifiant du build à annuler.
     * @return le futur de l'accusé d'annulation.
     */
    public CompletableFuture<Cancelled> cancel(String buildId) {
        return borné(connection.request(FrameType.CANCEL_BUILD,
                        out -> MessageIO.writeCancelRequest(out, new CancelRequest(buildId)))
                        .thenApply(frame -> decode(frame, MessageIO::readCancelled)),
                DÉLAI_ANNULATION_S, "l'annulation");
    }

    /**
     * Demande l'arrêt propre du moteur puis ferme le canal.
     *
     * @return le futur complété quand l'arrêt est envoyé (la mort suit de près).
     */
    public CompletableFuture<Void> shutdown() {
        return borné(connection.request(FrameType.SHUTDOWN, out -> { })
                        .thenApply(frame -> (Void) null),
                DÉLAI_COURT_S, "l'arrêt");
    }

    /**
     * Détruit immédiatement le moteur (moteur fou, dernier recours).
     */
    public void kill() {
        connection.kill();
    }

    /**
     * Attend la fin effective du processus moteur.
     *
     * @return le code de sortie.
     * @throws InterruptedException si l'attente est interrompue.
     */
    public int awaitExit() throws InterruptedException {
        return connection.awaitExit();
    }

    // ---- Aides -------------------------------------------------------------

    /**
     * Borné un futur de requête par un délai (constat M-3) : sans borne, une requête
     * vers un moteur gelé par le système (ou écroulé en silence) restait en attente
     * À JAMAIS — l'interface de la coquille montrait un build « en démarrage »
     * sans fin. Le dépassement devient une erreur parlante, pas un silence.
     */
    private static <T> CompletableFuture<T> borné(CompletableFuture<T> futur, long délaiS,
                                                   String requête) {
        return futur.orTimeout(délaiS, TimeUnit.SECONDS).exceptionally(erreur -> {
            Throwable cause = erreur instanceof CompletionException && erreur.getCause() != null
                    ? erreur.getCause() : erreur;
            if (cause instanceof TimeoutException) {
                throw new EngineConnection.EngineConnectionException(
                        "Le moteur n'a pas répondu à " + requête + " en " + délaiS
                                + " s — gelé par le système ou écroulé en silence");
            }
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new EngineConnection.EngineConnectionException(
                    "Requête " + requête + " échouée : " + cause, cause);
        });
    }

    /**
     * Attend la trame HELLO spontanée du moteur.
     */
    private static HelloHandshake awaitHello(EngineConnection connection,
                                              CompletableFuture<Frame> helloFrame) {
        try {
            Frame frame = helloFrame.get(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (frame.type() != FrameType.HELLO) {
                throw new EngineConnection.EngineConnectionException(
                        "Première trame inattendue : " + frame.type());
            }
            return new HelloHandshake(decode(frame, MessageIO::readHello));
        } catch (TimeoutException timeout) {
            connection.kill();
            throw new EngineConnection.EngineConnectionException(
                    "Le moteur n'a pas salué (démarrage trop lent ou écroulé)");
        } catch (Exception failure) {
            connection.kill();
            throw new EngineConnection.EngineConnectionException(
                    "Poignée de main échouée : " + rootMessage(failure), failure);
        }
    }

    /**
     * Choisit la version de protocole commune la plus haute, ou refuse proprement.
     */
    private static int chooseProtocol(Hello hello) {
        int min = Math.max(ProtocolVersions.PROTOCOL_MIN, hello.protocolMin());
        int max = Math.min(ProtocolVersions.PROTOCOL_MAX, hello.protocolMax());
        if (min > max) {
            throw new EngineConnection.EngineConnectionException(
                    "Protocoles incompatibles : coquille "
                            + ProtocolVersions.PROTOCOL_MIN + "-" + ProtocolVersions.PROTOCOL_MAX
                            + ", moteur " + hello.protocolMin() + "-" + hello.protocolMax());
        }
        return max;
    }

    /**
     * Décode une trame avec le décodeur donné.
     */
    private static <T> T decode(Frame frame, Decoder<T> decoder) {
        try {
            DataInputStream in = new DataInputStream(
                    new ByteArrayInputStream(frame.payload()));
            return decoder.readFrom(in);
        } catch (IOException ioError) {
            throw new EngineConnection.EngineConnectionException(
                    "Charge utile illisible (" + frame.type() + ") : " + ioError.getMessage(),
                    ioError);
        }
    }

    private static String decodeError(Frame frame) {
        try {
            DataInputStream in = new DataInputStream(
                    new ByteArrayInputStream(frame.payload()));
            return MessageIO.readErrorMessage(in);
        } catch (IOException ioError) {
            return "message illisible";
        }
    }

    private static String rootMessage(Throwable error) {
        if (error instanceof CompletionException && error.getCause() != null) {
            return rootMessage(error.getCause());
        }
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    /** Décodeur de charge utile. */
    @FunctionalInterface
    private interface Decoder<T> {

        /**
         * Décode un message.
         *
         * @param in flux source.
         * @return le message.
         * @throws IOException en cas d'erreur de lecture.
         */
        T readFrom(DataInputStream in) throws IOException;
    }

    /** Porteur interne de la poignée de main. */
    private record HelloHandshake(Hello hello) {
    }
}
