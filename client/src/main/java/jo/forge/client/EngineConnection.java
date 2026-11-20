package jo.forge.client;

import jo.forge.api.BuildEvent;
import jo.forge.api.Frame;
import jo.forge.api.FrameReader;
import jo.forge.api.FrameType;
import jo.forge.api.FrameWriter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Connexion de contrôle à un moteur : écrit les requêtes, lit les trames en continu et
 * distribue réponses et événements.
 *
 * <p>Thread-sûreté : un thread lecteur dédié (démon) consomme le flux ; les requêtes
 * peuvent être émises depuis n'importe quel thread et sont corrélatées par identifiant ;
 * les événements de build arrivent dans l'ordre d'émission du moteur. La mort du moteur
 * (EOF ou code de sortie) complète toutes les requêtes en attente avec une erreur et
 * prévient le rappel {@code onDead}.</p>
 *
 * <p>Cycle de vie : {@link #start()} démarre le lecteur ; {@link #close()} ferme le canal
 * en douceur ; {@link #kill()} détruit le processus hôte (annulation de dernier
 * recours).</p>
 */
public final class EngineConnection {

    private static final AtomicLong CORRELATION = new AtomicLong(1);

    private final FrameWriter writer;
    private final FrameReader reader;
    private final Process process;
    private final Map<Long, CompletableFuture<Frame>> pending = new ConcurrentHashMap<>();
    private final Consumer<BuildEvent> eventListener;
    private final Consumer<Frame> spontaneousHandler;
    private final Runnable onDead;
    private final Thread readerThread;
    private volatile boolean closed;

    /**
     * Crée la connexion au-dessus d'un processus moteur déjà lancé.
     *
     * @param process           processus du moteur (ses stdio portent le canal).
     * @param eventListener     rappel des événements de build (appelé depuis le thread
     *                          lecteur : doit être rapide et ne jamais bloquer).
     * @param spontaneousHandler rappel des trames spontanées hors requête (poignée de
     *                          main {@code HELLO}, erreurs internes) ; appelé depuis le
     *                          thread lecteur.
     * @param onDead            rappel de mort du moteur (EOF ou destruction), appelé une
     *                          fois au plus.
     */
    public EngineConnection(Process process, Consumer<BuildEvent> eventListener,
                            Consumer<Frame> spontaneousHandler, Runnable onDead) {
        this.process = process;
        this.eventListener = eventListener;
        this.spontaneousHandler = spontaneousHandler;
        this.onDead = onDead;
        this.writer = new FrameWriter(process.getOutputStream());
        this.reader = new FrameReader(process.getInputStream());
        this.readerThread = new Thread(this::readLoop, "forge-lecteur-moteur");
        this.readerThread.setDaemon(true);
    }

    /**
     * Démarre le thread lecteur. À appeler une fois, après construction.
     *
     * @return cette connexion (chaînage).
     */
    public EngineConnection start() {
        readerThread.start();
        return this;
    }

    /**
     * Envoie une requête et retourne son accusé futur.
     *
     * @param type    type de la trame requête.
     * @param payload encodeur de la charge utile.
     * @return le futur complété par la réponse de même identifiant de corrélation.
     */
    public CompletableFuture<Frame> request(FrameType type,
                                            FrameWriter.PayloadWriter payload) {
        long id = CORRELATION.getAndIncrement();
        CompletableFuture<Frame> future = new CompletableFuture<>();
        pending.put(id, future);
        try {
            writer.write(type, id, payload);
        } catch (IOException ioError) {
            pending.remove(id);
            future.completeExceptionally(
                    new EngineConnectionException("Écriture impossible : " + ioError.getMessage(),
                            ioError));
        }
        return future;
    }

    /**
     * Envoie une trame sans attendre de réponse (événement, arrêt).
     *
     * @param type    type de la trame.
     * @param payload encodeur de la charge utile.
     * @throws IOException en cas d'erreur d'écriture.
     */
    public void send(FrameType type, FrameWriter.PayloadWriter payload) throws IOException {
        writer.write(type, 0, payload);
    }

    /**
     * Ferme le canal proprement (le moteur s'arrête de lui-même après EOF).
     */
    public void close() {
        closed = true;
        try {
            writer.close();
        } catch (IOException ignored) {
            // Fermeture au mieux : la mort du processus suffit à tout nettoyer.
        }
        try {
            reader.close();
        } catch (IOException ignored) {
            // Idem.
        }
    }

    /**
     * Détruit le processus du moteur (dernier recours : moteur fou, annulation totale).
     */
    public void kill() {
        process.destroyForcibly();
    }

    /**
     * Attend la fin effective du processus moteur.
     *
     * @return le code de sortie du moteur.
     * @throws InterruptedException si l'attente est interrompue.
     */
    public int awaitExit() throws InterruptedException {
        return process.waitFor();
    }

    /**
     * Boucle du thread lecteur : distribue les réponses aux requêtes en attente et les
     * événements au rappel ; détecte la mort du moteur.
     */
    private void readLoop() {
        try {
            Frame frame;
            while ((frame = reader.readFrame()) != null) {
                dispatch(frame);
            }
            // EOF propre : le moteur a fermé son canal (arrêt demandé ou fin de session).
            handleDeath(null);
        } catch (Exception channelError) {
            // Canal coupé au milieu d'une trame ou erreur de protocole : moteur écroulé.
            handleDeath(channelodeMessage(channelError));
        }
    }

    /**
     * Distribue une trame lue.
     */
    private void dispatch(Frame frame) throws IOException {
        if (frame.type() == FrameType.BUILD_EVENT) {
            java.io.DataInputStream in =
                    new java.io.DataInputStream(new java.io.ByteArrayInputStream(
                            frame.payload()));
            eventListener.accept(jo.forge.api.MessageIO.readBuildEvent(in));
            return;
        }
        CompletableFuture<Frame> future = pending.remove(frame.correlationId());
        if (future != null) {
            future.complete(frame);
            return;
        }
        // Trame spontanée hors requête : poignée de main, erreur interne non corrélatée.
        if (spontaneousHandler != null) {
            spontaneousHandler.accept(frame);
        }
    }

    /**
     * Signale la mort du moteur : complète les requêtes en attente et prévient une fois.
     */
    private void handleDeath(String reason) {
        EngineConnectionException death = new EngineConnectionException(
                reason == null ? "Le moteur a fermé le canal" : "Canal du moteur coupé : " + reason);
        pending.values().forEach(future -> future.completeExceptionally(death));
        pending.clear();
        if (onDead != null) {
            onDead.run();
        }
    }

    private static String channelodeMessage(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    /**
     * Processus hôte de la connexion (pour le diagnostic de code de sortie).
     *
     * @return le processus du moteur.
     */
    public Process process() {
        return process;
    }

    /** Flux de sortie du processus (diagnostics d'écroulement, stderr). */
    public InputStream errorStream() {
        return process.getErrorStream();
    }

    /** Exception de transport vers le moteur. */
    public static final class EngineConnectionException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /**
         * Crée l'exception de transport.
         *
         * @param message description en français.
         */
        public EngineConnectionException(String message) {
            super(message);
        }

        /**
         * Crée l'exception de transport avec cause.
         *
         * @param message description en français.
         * @param cause   cause racine.
         */
        public EngineConnectionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
