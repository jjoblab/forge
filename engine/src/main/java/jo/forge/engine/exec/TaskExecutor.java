package jo.forge.engine.exec;

import jo.forge.api.BuildEvent;
import jo.forge.api.FrameType;
import jo.forge.engine.graph.TaskGraph;
import jo.forge.engine.tasks.CancellationFlag;
import jo.forge.engine.tasks.Task;
import jo.forge.engine.tasks.TaskCancelledException;
import jo.forge.engine.tasks.TaskContext;
import jo.forge.engine.tasks.TaskLogger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Exécuteur du graphe de tâches : file de prêtes, parallélisme borné, annulation de
 * bout en bout, statuts et journaux en événements.
 *
 * <p>Différences assumées avec le moteur de référence (revue §3-4) :</p>
 * <ul>
 *   <li>pas de barrière par niveaux : une tâche démarre dès que ses dépendances sont
 *       terminées (constat P-3) ;</li>
 *   <li>parallélisme <em>calculé</em>, plafonné par l'appelant (le moteur de
 *       référence le codait en dur à 2, REF-022) ;</li>
 *   <li>annulation : interruption des threads de tâches + destruction des processus
 *       enfants enregistrés, et l'orchestrateur n'attend que la fin effective de tout
 *       (constat C-1 : chez le moteur de référence, les VM forkées orphelines
 *       continuaient seules) ;</li>
 *   <li>l'échec d'une tâche bloque transitivement ses dépendantes mais laisse finir
 *       les branches indépendantes (parité avec le comportement constaté).</li>
 * </ul>
 */
public final class TaskExecutor {

    /** Au plus 4 tâches en parallèle, même sur machine très dotée (sobriété mémoire). */
    public static final int MAX_HARD_PARALLELISM = 4;

    private final TaskGraph graph;
    private final BuildEvents events;
    private final CancellationFlag cancellation;
    private final UpToDateOracle oracle;
    private final int parallelism;

    /**
     * Crée l'exécuteur.
     *
     * @param graph         graphe validé à exécuter.
     * @param events        puits d'événements du build (journal, états, progression).
     * @param cancellation  jeton d'annulation partagé (posé à la réception d'un
     *                      CANCEL_BUILD).
     * @param oracle        oracle d'up-to-date (empreintes).
     * @param parallelism   parallélisme demandé (plafonné, minimum 1).
     */
    public TaskExecutor(TaskGraph graph, BuildEvents events, CancellationFlag cancellation,
                        UpToDateOracle oracle, int parallelism) {
        this.graph = graph;
        this.events = events;
        this.cancellation = cancellation;
        this.oracle = oracle == null ? UpToDateOracle.ALWAYS_RUN : oracle;
        this.parallelism = Math.max(1, Math.min(parallelism, MAX_HARD_PARALLELISM));
    }

    /**
     * Exécute le graphe entier et produit le résultat du build.
     *
     * <p>Ne retourne jamais en exception : tout échec (tâche, annulation, échéance)
     * est reflété dans le {@link BuildResult} avec la tâche fautive et un message
     * lisible — le canal d'événements a déjà tout raconté.</p>
     *
     * @return le résultat du build.
     */
    public BuildResult execute() {
        String buildId = events.buildId();
        long debut = System.currentTimeMillis();
        Map<String, Integer> restantes = new HashMap<>();
        Map<String, List<String>> dependants = new HashMap<>();
        for (Task task : graph.allTasks()) {
            restantes.put(task.name(), task.dependencies().size());
            for (String dependance : task.dependencies()) {
                dependants.computeIfAbsent(dependance, k -> new ArrayList<>())
                        .add(task.name());
            }
        }

        ExecutorService pool = Executors.newFixedThreadPool(parallelism,
                runnable -> {
                    Thread thread = new Thread(runnable, "forge-tache");
                    thread.setDaemon(true);
                    return thread;
                });
        Phaser enCours = new Phaser(1); // Le thread orchestrateur compte pour un.
        AtomicInteger terminees = new AtomicInteger();
        AtomicInteger bloquables = new AtomicInteger(); // Sautées par blocage transitif.

        try {
            for (Task task : graph.allTasks()) {
                if (restantes.get(task.name()) == 0) {
                    soumettre(task, pool, enCours, restantes, dependants, terminees,
                            bloquables);
                }
            }
            // Attendre la fin de toutes les soumissions (et des cascades).
            enCours.arriveAndAwaitAdvance();
        } finally {
            pool.shutdownNow();
        }

        boolean annule = cancellation.isCancelled();
        boolean succes = !annule
                && terminees.get() + bloquables.get() == graph.size()
                && events.dernierEchec() == null;
        String tacheFautive = events.dernierEchec();
        String message = annule ? "build annulé"
                : succes ? "build réussi"
                : "échec à la tâche " + tacheFautive;
        events.buildFinished(new BuildEvent.BuildFinished(buildId,
                System.currentTimeMillis(), succes, tacheFautive,
                System.currentTimeMillis() - debut, message));
        return new BuildResult(succesBuild(annule, succes), tacheFautive,
                System.currentTimeMillis() - debut, annule);
    }

    private boolean succesBuild(boolean annule, boolean succes) {
        return succes && !annule;
    }

    /**
     * Soumet une tâche au pool ; à sa fin, décrémente ses dépendants et soumet les
     * prêts (ou les marque bloqués si elle a échoué/été annulée).
     */
    private void soumettre(Task task, ExecutorService pool, Phaser enCours,
                           Map<String, Integer> restantes,
                           Map<String, List<String>> dependants,
                           AtomicInteger terminees, AtomicInteger bloquables) {
        enCours.register();
        pool.submit(() -> {
            try {
                executerUne(task, restantes, dependants, pool, enCours, terminees,
                        bloquables);
            } finally {
                enCours.arriveAndDeregister();
            }
        });
    }

    /**
     * Exécute une tâche puis propage l'effet à ses dépendants.
     */
    private void executerUne(Task task, Map<String, Integer> restantes,
                             Map<String, List<String>> dependants, ExecutorService pool,
                             Phaser enCours, AtomicInteger terminees,
                             AtomicInteger bloquables) {
        String buildId = events.buildId();
        // Annulation posée avant le tour de cette tâche : saut silencieux.
        if (cancellation.isCancelled()) {
            events.taskState(new BuildEvent.TaskStateEvent(buildId,
                    System.currentTimeMillis(), task.name(),
                    BuildEvent.TaskState.SKIPPED, 0));
            terminer(task, restantes, dependants, pool, enCours, terminees, bloquables,
                    false);
            return;
        }

        // Oracle d'up-to-date : rien à faire si les entrées n'ont pas changé.
        if (oracle.isUpToDate(task)) {
            events.taskState(new BuildEvent.TaskStateEvent(buildId,
                    System.currentTimeMillis(), task.name(),
                    BuildEvent.TaskState.UP_TO_DATE, 0));
            terminer(task, restantes, dependants, pool, enCours, terminees, bloquables,
                    true);
            return;
        }

        long debut = System.currentTimeMillis();
        events.taskState(new BuildEvent.TaskStateEvent(buildId,
                System.currentTimeMillis(), task.name(),
                BuildEvent.TaskState.STARTED, 0));
        // Inscription pour l'interruption immédiate à l'annulation (décision D-08).
        cancellation.registerCurrent();
        try {
            task.execute(new ContexteTache(task, events, cancellation));
            if (cancellation.isCancelled()) {
                // Réussie mais annulation demandée pendant l'exécution : annulée.
                throw new TaskCancelledException("annulation pendant l'exécution");
            }
            oracle.recordSuccess(task);
            events.taskState(new BuildEvent.TaskStateEvent(buildId,
                    System.currentTimeMillis(), task.name(),
                    BuildEvent.TaskState.SUCCEEDED,
                    System.currentTimeMillis() - debut));
            terminer(task, restantes, dependants, pool, enCours, terminees, bloquables,
                    true);
        } catch (TaskCancelledException annulation) {
            events.taskState(new BuildEvent.TaskStateEvent(buildId,
                    System.currentTimeMillis(), task.name(),
                    BuildEvent.TaskState.FAILED, System.currentTimeMillis() - debut));
            events.log(BuildEvent.LogEvent.LogLevel.INFO,
                    "Tâche " + task.name() + " annulée");
            terminer(task, restantes, dependants, pool, enCours, terminees, bloquables,
                    false);
        } catch (Exception echec) {
            String cause = echec.getMessage() == null
                    ? echec.getClass().getSimpleName() : echec.getMessage();
            events.tacheEchouee(task.name());
            events.taskState(new BuildEvent.TaskStateEvent(buildId,
                    System.currentTimeMillis(), task.name(),
                    BuildEvent.TaskState.FAILED, System.currentTimeMillis() - debut));
            events.log(BuildEvent.LogEvent.LogLevel.ERROR,
                    "Échec de " + task.name() + " : " + cause);
            terminer(task, restantes, dependants, pool, enCours, terminees, bloquables,
                    false);
        } finally {
            cancellation.unregisterCurrent();
        }
    }

    /**
     * Marque une tâche terminée et débloque (ou bloque transitivement) ses dépendants.
     */
    private void terminer(Task task, Map<String, Integer> restantes,
                          Map<String, List<String>> dependants, ExecutorService pool,
                          Phaser enCours, AtomicInteger terminees,
                          AtomicInteger bloquables, boolean reussie) {
        terminees.incrementAndGet();
        events.progress(graph.size(), terminees.get() + bloquables.get());
        for (String dependant : dependants.getOrDefault(task.name(), List.of())) {
            if (!reussie) {
                // Échec ou annulation : les dépendants sont bloqués transitivement.
                bloquables.incrementAndGet();
                events.taskState(new BuildEvent.TaskStateEvent(events.buildId(),
                        System.currentTimeMillis(), dependant,
                        BuildEvent.TaskState.SKIPPED, 0));
                // Cascade : les dépendants du dépendant sont bloqués aussi.
                terminerBloques(dependant, dependants, bloquables);
                continue;
            }
            int restant = restantes.merge(dependant, -1, Integer::sum);
            if (restant == 0 && !cancellation.isCancelled()) {
                soumettre(graph.task(dependant), pool, enCours, restantes, dependants,
                        terminees, bloquables);
            }
        }
    }

    /**
     * Marque bloquées (transitivement) les tâches qui dépendent d'une tâche bloquée.
     */
    private void terminerBloques(String nom, Map<String, List<String>> dependants,
                                 AtomicInteger bloquables) {
        for (String dependant : dependants.getOrDefault(nom, List.of())) {
            bloquables.incrementAndGet();
            events.taskState(new BuildEvent.TaskStateEvent(events.buildId(),
                    System.currentTimeMillis(), dependant,
                    BuildEvent.TaskState.SKIPPED, 0));
            terminerBloques(dependant, dependants, bloquables);
        }
    }

    /** Résultat d'un build exécuté. */
    public record BuildResult(boolean success, String failedTask, long durationMs,
                              boolean cancelled) {
    }

    /** Puits des événements d'un build (tamponnés au nom de la tâche courante). */
    public interface BuildEvents {

        /**
         * Identifiant du build en cours.
         *
         * @return l'identifiant.
         */
        String buildId();

        /**
         * Émet un état de tâche.
         *
         * @param event l'état.
         */
        void taskState(BuildEvent.TaskStateEvent event);

        /**
         * Émet une entrée de journal.
         *
         * @param level   niveau.
         * @param message texte.
         */
        void log(BuildEvent.LogEvent.LogLevel level, String message);

        /**
         * Émet une progression globale.
         *
         * @param total tâches du graphe.
         * @param done  tâches terminées.
         */
        void progress(int total, int done);

        /**
         * Émet la fin du build.
         *
         * @param finished l'événement de fin.
         */
        void buildFinished(BuildEvent.BuildFinished finished);

        /**
         * Nom de la première tâche échouée du build (null si aucune).
         *
         * @return le nom, ou null.
         */
        String dernierEchec();

        /**
         * Enregistre une tâche échouée (première seulement).
         *
         * @param taskName nom de la tâche.
         */
        void tacheEchouee(String taskName);
    }

    /** Contexte d'exécution donné aux tâches (journal tamponné au nom de la tâche). */
    private static final class ContexteTache implements TaskContext {

        private final Task task;
        private final BuildEvents events;
        private final CancellationFlag cancellation;

        ContexteTache(Task task, BuildEvents events, CancellationFlag cancellation) {
            this.task = task;
            this.events = events;
            this.cancellation = cancellation;
        }

        @Override
        public java.nio.file.Path projectRoot() {
            return java.nio.file.Path.of(".");
        }

        @Override
        public TaskLogger logger() {
            return new TaskLogger() {
                @Override
                public void debug(String message) {
                    events.log(BuildEvent.LogEvent.LogLevel.DEBUG,
                            "[" + task.name() + "] " + message);
                }

                @Override
                public void info(String message) {
                    events.log(BuildEvent.LogEvent.LogLevel.INFO,
                            "[" + task.name() + "] " + message);
                }

                @Override
                public void warn(String message) {
                    events.log(BuildEvent.LogEvent.LogLevel.WARN,
                            "[" + task.name() + "] " + message);
                }

                @Override
                public void error(String message) {
                    events.log(BuildEvent.LogEvent.LogLevel.ERROR,
                            "[" + task.name() + "] " + message);
                }
            };
        }

        @Override
        public CancellationFlag cancellation() {
            return cancellation;
        }
    }
}
