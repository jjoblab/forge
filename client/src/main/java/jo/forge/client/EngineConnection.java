package jo.forge.client;

import jo.forge.api.BuildEvent;
import jo.forge.api.Frame;
import jo.forge.api.FrameReader;
import jo.forge.api.FrameType;
import jo.forge.api.FrameWriter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
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
 * <p>Diagnostics du moteur : stderr est drainé en continu (tube jamais saturé — un
 * moteur bavard qui écrirait dans un tube plein finirait bloqué) vers un journal borné
 * ({@link JournalMoteur}) et, si fourni, vers un consommateur en flux. Une sentinelle
 * publie le code de sortie dès qu'il est connu : c'est l'indice décisif d'une mort par
 * signal (négatif ou 128+n) face à un arrêt propre (0).</p>
 *
 * <p>Cycle de vie : {@link #start()} démarre les fils ; {@link #close()} ferme le canal
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
    private final Consumer<String> stderrListener;
    private final Runnable onDead;
    private final Thread readerThread;
    private final Thread stderrThread;
    private final Thread sentinelThread;
    private final JournalMoteur journalErreurs = new JournalMoteur(
            JournalMoteur.CAPACITÉ_PAR_DÉFAUT);
    private final AtomicReference<Integer> codeSortie = new AtomicReference<>();
    private volatile boolean closed;

    /** Mort du canal publiée (garde d'unicité) — les requêtes suivantes échouent vite. */
    private final AtomicBoolean morte = new AtomicBoolean();

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
        this(process, eventListener, spontaneousHandler, onDead, null);
    }

    /**
     * Crée la connexion avec écoute du journal du moteur : chaque ligne écrite par le
     * processus sur stderr est conservée dans le journal borné interne ET transmise au
     * consommateur (appelé depuis le fil de drainage : rapide, jamais bloquant).
     *
     * @param process           processus du moteur (ses stdio portent le canal).
     * @param eventListener     rappel des événements de build.
     * @param spontaneousHandler rappel des trames spontanées hors requête.
     * @param onDead            rappel de mort du moteur.
     * @param stderrListener    consommateur des lignes de stderr (null : journal interne
     *                          seul).
     */
    public EngineConnection(Process process, Consumer<BuildEvent> eventListener,
                            Consumer<Frame> spontaneousHandler, Runnable onDead,
                            Consumer<String> stderrListener) {
        this.process = process;
        this.eventListener = eventListener;
        this.spontaneousHandler = spontaneousHandler;
        this.onDead = onDead;
        this.stderrListener = stderrListener;
        this.writer = new FrameWriter(process.getOutputStream());
        this.reader = new FrameReader(process.getInputStream());
        this.readerThread = new Thread(this::readLoop, "forge-lecteur-moteur");
        this.readerThread.setDaemon(true);
        this.stderrThread = new Thread(this::drainerStderr, "forge-draine-stderr");
        this.stderrThread.setDaemon(true);
        this.sentinelThread = new Thread(this::surveillerSortie, "forge-sentinelle");
        this.sentinelThread.setDaemon(true);
    }

    /**
     * Démarre les fils (lecteur du canal, drainage de stderr, sentinelle de sortie).
     * À appeler une fois, après construction.
     *
     * @return cette connexion (chaînage).
     */
    public EngineConnection start() {
        readerThread.start();
        stderrThread.start();
        sentinelThread.start();
        return this;
    }

    /**
     * Envoie une requête et retourne son accusé futur.
     *
     * <p>Constat M-3 : après la mort du canal, la requête échoue IMMÉDIATEMENT —
     * elle ne s'inscrit plus pour une réponse qui ne viendra jamais (un appel
     * enregistré après le nettoyage des requêtes en attente attendait indéfiniment
     * si l'écriture réussissait dans le tampon du tube).</p>
     *
     * @param type    type de la trame requête.
     * @param payload encodeur de la charge utile.
     * @return le futur complété par la réponse de même identifiant de corrélation.
     */
    public CompletableFuture<Frame> request(FrameType type,
                                            FrameWriter.PayloadWriter payload) {
        long id = CORRELATION.getAndIncrement();
        CompletableFuture<Frame> future = new CompletableFuture<>();
        if (morte.get()) {
            future.completeExceptionally(new EngineConnectionException(
                    "Canal du moteur déjà fermé : requête refusée"));
            return future;
        }
        pending.put(id, future);
        // Une requête qui échoue d'elle-même (délai côté appelant) libère son entrée :
        // la réponse tardive, si elle vient, ne trouvera qu'un futur déjà complété.
        future.whenComplete((réponse, erreur) -> {
            if (erreur != null) {
                pending.remove(id);
            }
        });
        try {
            writer.write(type, id, payload);
        } catch (IOException ioError) {
            pending.remove(id);
            future.completeExceptionally(
                    new EngineConnectionException("Écriture impossible : " + ioError.getMessage(),
                            ioError));
            return future;
        }
        // Course : la mort a pu survenir entre la garde initiale et l'inscription —
        // les requêtes en attente viennent d'être toutes échouées, la nôtre y échappe.
        if (morte.get()) {
            pending.remove(id);
            future.completeExceptionally(new EngineConnectionException(
                    "Canal du moteur fermé pendant l'envoi : requête refusée"));
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
     * Journal d'erreurs du moteur : lignes de stderr capturées, bornées aux dernières
     * {@link JournalMoteur#CAPACITÉ_PAR_DÉFAUT}.
     *
     * @return la copie des lignes (vide si le moteur n'a rien écrit).
     */
    public List<String> journalErreurs() {
        return journalErreurs.copie();
    }

    /**
     * Code de sortie du moteur publié par la sentinelle.
     *
     * <p>Convention Unix : 0 = arrêt propre ; négatif = mort par signal (le tueur de
     * mémoire du noyau donne -9) ; positif = sortie explicite du moteur.</p>
     *
     * <p>Auto-guérison : si la sentinelle n'a pas encore publié alors que le processus
     * est fini (interruption du fil, latence de publication), {@code waitFor} retourne
     * immédiatement — le code est publié ici plutôt que de laisser le diagnostic
     * dire « inconnu » d'un processus déjà mort (constat v0.2.3 sur appareil).</p>
     *
     * @return le code, ou {@code null} tant que le processus vit.
     */
    public Integer codeSortie() {
        Integer code = codeSortie.get();
        if (code == null && !process.isAlive()) {
            try {
                code = process.waitFor();
                codeSortie.compareAndSet(null, code);
            } catch (InterruptedException attente) {
                Thread.currentThread().interrupt();
            }
        }
        return codeSortie.get();
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
            handleDeath(messageDErreurCanal(channelError));
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
            remettre(jo.forge.api.MessageIO.readBuildEvent(in));
            return;
        }
        CompletableFuture<Frame> future = pending.remove(frame.correlationId());
        if (future != null) {
            future.complete(frame);
            return;
        }
        // Trame spontanée hors requête : poignée de main, erreur interne non corrélatée.
        if (spontaneousHandler != null) {
            try {
                spontaneousHandler.accept(frame);
            } catch (RuntimeException erreurCoquille) {
                // Constat M-3 : une faute du code de la coquille ne doit JAMAIS être
                // lue comme une coupure du canal — le moteur, lui, est sain.
                journalErreurs.ajouter("Rappel de trame spontanée en échec (canal intact) : "
                        + description(erreurCoquille));
            }
        }
    }

    /**
     * Remet un événement de build au rappel de la coquille — ISOLÉMENT (constat
     * M-3) : une exception du rappel était attrapée par la boucle de lecture comme
     * une coupure du canal, échouait toutes les requêtes en attente et déclarait
     * morte un moteur parfaitement sain (le build en cours était perdu avec lui).
     * Elle est désormais journalisée dans le journal du moteur et le canal continue.
     */
    private void remettre(BuildEvent événement) {
        try {
            eventListener.accept(événement);
        } catch (RuntimeException erreurCoquille) {
            journalErreurs.ajouter("Rappel d'événement en échec (canal intact) : "
                    + description(erreurCoquille));
        }
    }

    /** Description courte d'une faute du rappel (message ou classe). */
    private static String description(RuntimeException erreur) {
        return erreur.getMessage() == null
                ? erreur.getClass().getSimpleName() : erreur.getMessage();
    }

    /**
     * Signale la mort du moteur — UNE seule fois : complète les requêtes en attente,
     * marque le canal mort (les requêtes suivantes échouent vite, constat M-3) et
     * prévient le rappel. Le rappel lui-même est isolé : une faute dans le code de
     * la coquille ne doit pas masquer la mort réelle du moteur.
     */
    private void handleDeath(String reason) {
        if (!morte.compareAndSet(false, true)) {
            return; // Déjà publiée : la mort n'est annoncée qu'une fois.
        }
        EngineConnectionException death = new EngineConnectionException(
                reason == null ? "Le moteur a fermé le canal" : "Canal du moteur coupé : " + reason);
        pending.values().forEach(future -> future.completeExceptionally(death));
        pending.clear();
        if (onDead != null) {
            try {
                onDead.run();
            } catch (RuntimeException erreurCoquille) {
                journalErreurs.ajouter("Rappel de mort en échec : " + description(erreurCoquille));
            }
        }
    }

    /**
     * Boucle de drainage de stderr : chaque ligne rejoint le journal borné interne et,
     * si fourni, le consommateur. Sans ce drainage, un moteur bavard finirait bloqué sur
     * un tube plein — et ses diagnostics d'écroulement seraient perdus avec lui.
     */
    private void drainerStderr() {
        try (BufferedReader lecteur = new BufferedReader(new InputStreamReader(
                process.getErrorStream(), StandardCharsets.UTF_8))) {
            String ligne;
            while ((ligne = lecteur.readLine()) != null) {
                journalErreurs.ajouter(ligne);
                Consumer<String> consommateur = stderrListener;
                if (consommateur != null) {
                    consommateur.accept(ligne);
                }
            }
        } catch (IOException canalFermé) {
            // Le moteur est mort (ou a été détruit) : la sentinelle publie le code.
        }
    }

    /**
     * Sentinelle : publie le code de sortie dès que l'OS le rapporte. Indépendante de
     * l'EOF du canal — un moteur tué par signal ferme ses flux, mais seul le code dit
     * COMMENT il est mort (proprement, en échec, ou assassiné par le noyau).
     */
    private void surveillerSortie() {
        try {
            codeSortie.set(process.waitFor());
        } catch (InterruptedException fin) {
            Thread.currentThread().interrupt();
        }
    }

    private static String messageDErreurCanal(Exception error) {
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
