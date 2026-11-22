package jo.forge.engine.exec;

import jo.forge.api.BuildEvent;
import jo.forge.api.FrameType;
import jo.forge.engine.diag.Diagnostic;
import jo.forge.engine.diag.MessagesErreursGraves;
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
import java.util.Set;
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
    private final java.nio.file.Path projectRoot;

    /**
     * Crée l'exécuteur (racine de projet : répertoire courant).
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
        this(graph, events, cancellation, oracle, parallelism, java.nio.file.Path.of("."));
    }

    /**
     * Crée l'exécuteur avec la racine du projet construit (résolution des chemins des
     * tâches).
     *
     * @param graph         graphe validé à exécuter.
     * @param events        puits d'événements du build (journal, états, progression).
     * @param cancellation  jeton d'annulation partagé (posé à la réception d'un
     *                      CANCEL_BUILD).
     * @param oracle        oracle d'up-to-date (empreintes).
     * @param parallelism   parallélisme demandé (plafonné, minimum 1).
     * @param projectRoot   racine du projet, fournie aux tâches par leur contexte.
     */
    public TaskExecutor(TaskGraph graph, BuildEvents events, CancellationFlag cancellation,
                        UpToDateOracle oracle, int parallelism,
                        java.nio.file.Path projectRoot) {
        this(graph, events, cancellation, oracle, parallelism, projectRoot,
                PÉRIODE_PULSATION_MS);
    }

    /**
     * Crée l'exécuteur avec période de pulsation explicite (tests : une période
     * courte rend la pulsation observable sans attendre 30 s).
     *
     * @param graph             graphe validé à exécuter.
     * @param events            puits d'événements du build.
     * @param cancellation      jeton d'annulation partagé.
     * @param oracle            oracle d'up-to-date (empreintes).
     * @param parallelism       parallélisme demandé (plafonné, minimum 1).
     * @param projectRoot       racine du projet construit.
     * @param périodePulsationMs période de la pulsation des tâches longues (D-17).
     */
    TaskExecutor(TaskGraph graph, BuildEvents events, CancellationFlag cancellation,
                 UpToDateOracle oracle, int parallelism,
                 java.nio.file.Path projectRoot, long périodePulsationMs) {
        this.graph = graph;
        this.events = events;
        this.cancellation = cancellation;
        this.oracle = oracle == null ? UpToDateOracle.ALWAYS_RUN : oracle;
        this.parallelism = Math.max(1, Math.min(parallelism, MAX_HARD_PARALLELISM));
        this.projectRoot = java.util.Objects.requireNonNull(projectRoot, "projectRoot");
        this.périodePulsationMs = Math.max(1, périodePulsationMs);
    }

    /** Période de la pulsation des tâches longues (décision D-17), en millisecondes. */
    static final long PÉRIODE_PULSATION_MS = 30_000;

    /** Période effective de la pulsation (30 s, raccourcie par les tests). */
    private final long périodePulsationMs;

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
        // ConcurrentHashMap OBLIGATOIRE : les terminaisons de tâches (et donc les
        // décréments) se produisent en parallèle sur le pool — un HashMap simple se
        // corromprait (double soumission ou tâche jamais soumise, selon la course).
        Map<String, Integer> restantes = new java.util.concurrent.ConcurrentHashMap<>();
        // dependants : construit ici, uniquement LU ensuite (publication sûre par la
        // création des threads du pool).
        Map<String, List<String>> dependants = new HashMap<>();
        // Noms des tâches déjà terminées (idempotence de terminer, filet de sécurité).
        Set<String> terminées = java.util.concurrent.ConcurrentHashMap.newKeySet();
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
        // Constat B-1 : les tâches bloquées transitivement peuvent être atteintes par
        // PLUSIEURS chemins d'échec (un losange dont les deux branches échouent) — sans
        // garde, elles étaient comptées et notifiées une fois PAR chemin : événements
        // SKIPPED en double, décompte final faux, progression au-delà du total et coût
        // potentiellement exponentiel de la cascade sur les graphes denses.
        Set<String> bloquées = java.util.concurrent.ConcurrentHashMap.newKeySet();
        CompteurIssues issues = new CompteurIssues();

        // Pulsation (D-17) : les tâches EN COURS d'exécution sont inscrites ici
        // (nom → contexte + début) ; le chien de garde ci-dessous émet, au plus toutes
        // les 30 s, une pulsation par tâche inscrite — une tâche longue n'est jamais
        // muette, et son dernier message de progression voyage avec.
        Map<String, TâchePulsée> tâchesEnCours =
                new java.util.concurrent.ConcurrentHashMap<>();
        java.util.concurrent.ScheduledExecutorService pulsateur =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                        runnable -> {
                            Thread thread = new Thread(runnable, "forge-pulsation");
                            thread.setDaemon(true);
                            return thread;
                        });
        java.util.concurrent.ScheduledFuture<?> battement = pulsateur.scheduleAtFixedRate(
                () -> {
                    long maintenant = System.currentTimeMillis();
                    for (Map.Entry<String, TâchePulsée> inscription
                            : tâchesEnCours.entrySet()) {
                        TâchePulsée pulsée = inscription.getValue();
                        try {
                            events.pulsation(inscription.getKey(),
                                    pulsée.contexte().messageProgression(),
                                    maintenant - pulsée.début());
                        } catch (Throwable puitsEnPanne) {
                            // Le puits d'événements est en panne : la pulsation est un
                            // confort, jamais une obligation — le build continue.
                        }
                    }
                },
                périodePulsationMs, périodePulsationMs,
                java.util.concurrent.TimeUnit.MILLISECONDS);

        // Racines : déterminées AVANT tout démarrage du pool, depuis les dépendances
        // STATIQUES — jamais depuis la carte « restantes » (mutée en parallèle par les
        // terminaisons) : une racine rapide pourrait décrémenter une tâche à zéro
        // pendant le balayage et provoquer une double soumission (course réelle,
        // corrigée à l'étape 9).
        List<Task> racines = graph.allTasks().stream()
                .filter(task -> task.dependencies().isEmpty()).toList();
        try {
            for (Task task : racines) {
                soumettre(task, pool, enCours, restantes, dependants, terminees,
                        bloquables, terminées, bloquées, issues, tâchesEnCours);
            }
            // Attendre la fin de toutes les soumissions (et des cascades).
            enCours.arriveAndAwaitAdvance();
            // Filet final (constat B-2) : toute tâche restée sans issue — la course
            // « annulation posée entre le succès d'une dépendance et la soumission du
            // dépendant » est la seule fenêtre connue — est dite SAUTÉE : aucune tâche
            // ne reste « en attente » à jamais dans l'interface, et le décompte final
            // couvre tout le graphe.
            for (Task task : graph.allTasks()) {
                if (terminées.contains(task.name())
                        || !bloquées.add(task.name())) {
                    continue;
                }
                bloquables.incrementAndGet();
                issues.compter(BuildEvent.TaskState.SKIPPED);
                try {
                    events.taskState(new BuildEvent.TaskStateEvent(buildId,
                            System.currentTimeMillis(), task.name(),
                            BuildEvent.TaskState.SKIPPED, 0));
                } catch (Throwable puitsEnPanne) {
                    // Le puits est en panne : le comptage reste notre seule obligation.
                }
            }
            if (bloquables.get() > 0) {
                events.progress(graph.size(), terminees.get() + bloquables.get());
            }
        } finally {
            battement.cancel(false);
            pulsateur.shutdownNow();
            pool.shutdownNow();
        }

        boolean annule = cancellation.isCancelled();
        boolean succes = !annule
                && terminees.get() + bloquables.get() == graph.size()
                && events.dernierEchec() == null;
        String tacheFautive = events.dernierEchec();
        String message = annule ? "build annulé — " + issues.résumé()
                : succes ? "build réussi — " + issues.résumé()
                : "échec à la tâche " + tacheFautive + " — " + issues.résumé();
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
     *
     * <p>Filet de sécurité : tout throwable sorti d'{@link #executerUne} (erreur
     * grave avant le corps protégé, puits d'événements en panne…) est converti en
     * échec PROPRE de la tâche — jamais en mort silencieuse dans le {@link Future}.
     * Sans ce filet, le comptage se rompt et un sous-graphe entier disparaît sans
     * diagnostic (flocons constatés en CI : tâches « jamais soumises », build
     * prématurément terminé sans tâche fautive).</p>
     */
    private void soumettre(Task task, ExecutorService pool, Phaser enCours,
                           Map<String, Integer> restantes,
                           Map<String, List<String>> dependants,
                           AtomicInteger terminees, AtomicInteger bloquables,
                           Set<String> terminées, Set<String> bloquées,
                           CompteurIssues issues,
                           Map<String, TâchePulsée> tâchesEnCours) {
        enCours.register();
        pool.submit(() -> {
            try {
                executerUne(task, restantes, dependants, pool, enCours, terminees,
                        bloquables, terminées, bloquées, issues, tâchesEnCours);
            } catch (Throwable fuite) {
                échecImprévu(task, fuite, restantes, dependants, pool, enCours,
                        terminees, bloquables, terminées, bloquées, issues, tâchesEnCours);
            } finally {
                enCours.arriveAndDeregister();
            }
        });
    }

    /**
     * Dernier recours : une tâche dont l'exécution s'est écroulée hors des zones
     * protégées échoue PROPREMENT — état FAILED, tâche fautive enregistrée,
     * dépendants bloqués, comptage exact.
     */
    private void échecImprévu(Task task, Throwable fuite, Map<String, Integer> restantes,
                              Map<String, List<String>> dependants, ExecutorService pool,
                              Phaser enCours, AtomicInteger terminees,
                              AtomicInteger bloquables, Set<String> terminées,
                              Set<String> bloquées, CompteurIssues issues,
                              Map<String, TâchePulsée> tâchesEnCours) {
        String cause = fuite.getMessage() == null
                ? fuite.getClass().getSimpleName() : fuite.getMessage();
        issues.compter(BuildEvent.TaskState.FAILED);
        try {
            events.tacheEchouee(task.name());
            events.taskState(new BuildEvent.TaskStateEvent(events.buildId(),
                    System.currentTimeMillis(), task.name(),
                    BuildEvent.TaskState.FAILED, 0));
            events.log(BuildEvent.LogEvent.LogLevel.ERROR,
                    "Erreur non attrapée dans " + task.name() + " ("
                            + fuite.getClass().getSimpleName() + ") : " + cause);
        } catch (Throwable puitsEnPanne) {
            // Le puits d'événements est lui-même écroulé : la comptabilité ci-dessous
            // reste notre seule obligation.
        }
        terminer(task, restantes, dependants, pool, enCours, terminees, bloquables,
                terminées, bloquées, issues, tâchesEnCours, false);
    }

    /**
     * Exécute une tâche puis propage l'effet à ses dépendants.
     */
    private void executerUne(Task task, Map<String, Integer> restantes,
                             Map<String, List<String>> dependants, ExecutorService pool,
                             Phaser enCours, AtomicInteger terminees,
                             AtomicInteger bloquables, Set<String> terminées,
                             Set<String> bloquées, CompteurIssues issues,
                             Map<String, TâchePulsée> tâchesEnCours) {
        String buildId = events.buildId();
        // Annulation posée avant le tour de cette tâche : saut silencieux.
        if (cancellation.isCancelled()) {
            issues.compter(BuildEvent.TaskState.SKIPPED);
            events.taskState(new BuildEvent.TaskStateEvent(buildId,
                    System.currentTimeMillis(), task.name(),
                    BuildEvent.TaskState.SKIPPED, 0));
            terminer(task, restantes, dependants, pool, enCours, terminees, bloquables,
                    terminées, bloquées, issues, tâchesEnCours, false);
            return;
        }

        // Oracle d'up-to-date : rien à faire si les entrées n'ont pas changé.
        if (oracle.isUpToDate(task)) {
            issues.compter(BuildEvent.TaskState.UP_TO_DATE);
            events.taskState(new BuildEvent.TaskStateEvent(buildId,
                    System.currentTimeMillis(), task.name(),
                    BuildEvent.TaskState.UP_TO_DATE, 0));
            terminer(task, restantes, dependants, pool, enCours, terminees, bloquables,
                    terminées, bloquées, issues, tâchesEnCours, true);
            return;
        }

        long debut = System.currentTimeMillis();
        // L'émission STARTED et l'inscription au jeton vivent DANS la zone protégée :
        // une erreur grave (mémoire saturée au moment de la construction de
        // l'événement, puits en panne) est convertie en échec propre, pas en perte
        // silencieuse de la tâche.
        ContexteTache contexte = new ContexteTache(task, events, cancellation, projectRoot);
        tâchesEnCours.put(task.name(), new TâchePulsée(contexte, debut));
        try {
            events.taskState(new BuildEvent.TaskStateEvent(buildId,
                    System.currentTimeMillis(), task.name(),
                    BuildEvent.TaskState.STARTED, 0));
            // Inscription pour l'interruption immédiate à l'annulation (décision D-08).
            cancellation.registerCurrent();
            task.execute(contexte);
            if (cancellation.isCancelled()) {
                // Réussie mais annulation demandée pendant l'exécution : annulée.
                throw new TaskCancelledException("annulation pendant l'exécution");
            }
            oracle.recordSuccess(task);
            BuildEvent.TaskState étatFinal = contexte.depuisCache()
                    ? BuildEvent.TaskState.FROM_CACHE
                    : BuildEvent.TaskState.SUCCEEDED;
            issues.compter(étatFinal);
            events.taskState(new BuildEvent.TaskStateEvent(buildId,
                    System.currentTimeMillis(), task.name(), étatFinal,
                    System.currentTimeMillis() - debut));
            terminer(task, restantes, dependants, pool, enCours, terminees, bloquables,
                    terminées, bloquées, issues, tâchesEnCours, true);
        } catch (TaskCancelledException annulation) {
            issues.compter(BuildEvent.TaskState.FAILED);
            events.taskState(new BuildEvent.TaskStateEvent(buildId,
                    System.currentTimeMillis(), task.name(),
                    BuildEvent.TaskState.FAILED, System.currentTimeMillis() - debut));
            events.log(BuildEvent.LogEvent.LogLevel.INFO,
                    "Tâche " + task.name() + " annulée");
            terminer(task, restantes, dependants, pool, enCours, terminees, bloquables,
                    terminées, bloquées, issues, tâchesEnCours, false);
        } catch (Exception echec) {
            String cause = echec.getMessage() == null
                    ? echec.getClass().getSimpleName() : echec.getMessage();
            issues.compter(BuildEvent.TaskState.FAILED);
            events.tacheEchouee(task.name());
            events.taskState(new BuildEvent.TaskStateEvent(buildId,
                    System.currentTimeMillis(), task.name(),
                    BuildEvent.TaskState.FAILED, System.currentTimeMillis() - debut));
            events.log(BuildEvent.LogEvent.LogLevel.ERROR,
                    "Échec de " + task.name() + " : " + cause);
            terminer(task, restantes, dependants, pool, enCours, terminees, bloquables,
                    terminées, bloquées, issues, tâchesEnCours, false);
        } catch (java.lang.Error erreurGrave) {
            // Un Error ne doit JAMAIS rompre le comptage silencieusement : la tâche
            // échoue proprement, la cause est journalisée ET diagnostiquée, le build
            // se termine avec un message honnête — la mémoire n'est qu'UNE cause
            // possible (correctif v0.2.6 : un LinkageError n'est pas un manque de
            // mémoire ; le crash ECJ sur ART s'affichait sous un faux diagnostic et
            // le rapport n'avait aucun diagnostic structuré).
            String message = MessagesErreursGraves.pour(erreurGrave, task.name());
            issues.compter(BuildEvent.TaskState.FAILED);
            events.tacheEchouee(task.name());
            events.taskState(new BuildEvent.TaskStateEvent(buildId,
                    System.currentTimeMillis(), task.name(),
                    BuildEvent.TaskState.FAILED, System.currentTimeMillis() - debut));
            events.log(BuildEvent.LogEvent.LogLevel.ERROR, message);
            events.diagnostic(Diagnostic.error(task.name(), message));
            terminer(task, restantes, dependants, pool, enCours, terminees, bloquables,
                    terminées, bloquées, issues, tâchesEnCours, false);
        } finally {
            tâchesEnCours.remove(task.name());
            cancellation.unregisterCurrent();
        }
    }

    /**
     * Marque une tâche terminée et débloque (ou bloque transitivement) ses dépendants.
     *
     * <p>Idempotente : un même nom de tâche n'est compté et ne décrémente ses
     * dépendants qu'une seule fois, quels que soient les chemins d'erreur
     * parcourus (filet de sécurité compris).</p>
     */
    private void terminer(Task task, Map<String, Integer> restantes,
                          Map<String, List<String>> dependants, ExecutorService pool,
                          Phaser enCours, AtomicInteger terminees,
                          AtomicInteger bloquables, Set<String> terminées,
                          Set<String> bloquées, CompteurIssues issues,
                          Map<String, TâchePulsée> tâchesEnCours,
                          boolean reussie) {
        if (!terminées.add(task.name())) {
            return; // Déjà terminée (double passage d'un chemin d'erreur) : ignorer.
        }
        terminees.incrementAndGet();
        events.progress(graph.size(), terminees.get() + bloquables.get());
        for (String dependant : dependants.getOrDefault(task.name(), List.of())) {
            if (!reussie) {
                // Échec ou annulation : les dépendants sont bloqués transitivement —
                // UNE seule fois chacun, quel que soit le nombre de chemins d'échec
                // qui les atteignent (garde du constat B-1).
                if (bloquées.add(dependant)) {
                    bloquables.incrementAndGet();
                    issues.compter(BuildEvent.TaskState.SKIPPED);
                    events.taskState(new BuildEvent.TaskStateEvent(events.buildId(),
                            System.currentTimeMillis(), dependant,
                            BuildEvent.TaskState.SKIPPED, 0));
                    // Cascade : les dépendants du dépendant sont bloqués aussi.
                    terminerBloques(dependant, dependants, bloquables, issues, bloquées);
                }
                continue;
            }
            int restant = restantes.merge(dependant, -1, Integer::sum);
            if (restant == 0) {
                if (cancellation.isCancelled()) {
                    // Constat B-2 : la tâche est prête mais l'annulation est posée —
                    // ni soumise (correct) ni laissée muette (le défaut) : elle est
                    // dite SAUTÉE, l'interface ne la garde pas « en attente » à jamais.
                    if (bloquées.add(dependant)) {
                        bloquables.incrementAndGet();
                        issues.compter(BuildEvent.TaskState.SKIPPED);
                        events.taskState(new BuildEvent.TaskStateEvent(
                                events.buildId(), System.currentTimeMillis(),
                                dependant, BuildEvent.TaskState.SKIPPED, 0));
                        events.progress(graph.size(),
                                terminees.get() + bloquables.get());
                    }
                } else {
                    soumettre(graph.task(dependant), pool, enCours, restantes, dependants,
                            terminees, bloquables, terminées, bloquées, issues,
                            tâchesEnCours);
                }
            }
        }
    }

    /**
     * Marque bloquées (transitivement) les tâches qui dépendent d'une tâche bloquée.
     */
    private void terminerBloques(String nom, Map<String, List<String>> dependants,
                                 AtomicInteger bloquables, CompteurIssues issues,
                                 Set<String> bloquées) {
        for (String dependant : dependants.getOrDefault(nom, List.of())) {
            // Garde B-1 : chaque tâche bloquée est comptée et notifiée une seule fois,
            // même si plusieurs branches échouées y convergent.
            if (!bloquées.add(dependant)) {
                continue;
            }
            bloquables.incrementAndGet();
            issues.compter(BuildEvent.TaskState.SKIPPED);
            events.taskState(new BuildEvent.TaskStateEvent(events.buildId(),
                    System.currentTimeMillis(), dependant,
                    BuildEvent.TaskState.SKIPPED, 0));
            terminerBloques(dependant, dependants, bloquables, issues, bloquées);
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

        /**
         * Émet un diagnostic structuré (erreur localisée, avertissement) — distinct du
         * journal texte. Implémentation par défaut : ignore (tests sans diagnostics).
         *
         * @param diagnostic le diagnostic.
         */
        default void diagnostic(jo.forge.engine.diag.Diagnostic diagnostic) {
        }

        /**
         * Émet un événement de téléchargement (progression d'une dépendance).
         * Implémentation par défaut : ignore (tests sans réseau).
         *
         * @param événement la progression du téléchargement.
         */
        default void téléchargement(BuildEvent.DownloadEvent événement) {
        }

        /**
         * Émet une pulsation de tâche longue (D-17) : la tâche nommée est EN COURS
         * d'exécution depuis {@code elapsedMs}, avec son dernier message de
         * progression. Implémentation par défaut : ignore (tests sans exécuteur).
         *
         * @param taskName  nom de la tâche en cours.
         * @param message   dernier message de progression (peut être vide).
         * @param elapsedMs temps écoulé depuis le début de la tâche.
         */
        default void pulsation(String taskName, String message, long elapsedMs) {
        }
    }

    /** Une tâche inscrite à la pulsation : son contexte et son début. */
    private record TâchePulsée(ContexteTache contexte, long début) {
    }

    /**
     * Comptage des issues par état terminal — le décompte final du build, à la
     * manière du « N actionable tasks : X executed, Y up-to-date » du moteur de
     * référence : les sautées ne sont pas actionnables, elles n'entrent pas dans
     * le compte des tâches utiles.
     */
    static final class CompteurIssues {

        private final java.util.Map<BuildEvent.TaskState, Integer> issues =
                new java.util.concurrent.ConcurrentHashMap<>();

        /** Compte une issue (thread-safe). */
        void compter(BuildEvent.TaskState état) {
            issues.merge(état, 1, Integer::sum);
        }

        /**
         * Le résumé lisible du décompte (ex. {@code 24 tâches : 5 exécutées,
         * 12 à jour, 2 du cache, 5 sautées}).
         */
        String résumé() {
            int exécutées = issues.getOrDefault(BuildEvent.TaskState.SUCCEEDED, 0);
            int àJour = issues.getOrDefault(BuildEvent.TaskState.UP_TO_DATE, 0);
            int duCache = issues.getOrDefault(BuildEvent.TaskState.FROM_CACHE, 0);
            int échouées = issues.getOrDefault(BuildEvent.TaskState.FAILED, 0);
            int sautées = issues.getOrDefault(BuildEvent.TaskState.SKIPPED, 0);
            int actionnables = exécutées + àJour + duCache + échouées;
            StringBuilder texte = new StringBuilder().append(actionnables).append(" tâche")
                    .append(actionnables > 1 ? "s" : "").append(" : ");
            List<String> parties = new ArrayList<>();
            if (exécutées > 0) {
                parties.add(exécutées + " exécutée" + (exécutées > 1 ? "s" : ""));
            }
            if (àJour > 0) {
                parties.add(àJour + " à jour");
            }
            if (duCache > 0) {
                parties.add(duCache + " du cache");
            }
            if (échouées > 0) {
                parties.add(échouées + " échouée" + (échouées > 1 ? "s" : ""));
            }
            if (parties.isEmpty()) {
                texte.append("aucune");
            } else {
                texte.append(String.join(", ", parties));
            }
            if (sautées > 0) {
                texte.append(", ").append(sautées).append(" sautée")
                        .append(sautées > 1 ? "s" : "").append(" (non actionnables)");
            }
            return texte.toString();
        }
    }

    /** Contexte d'exécution donné aux tâches (journal tamponné au nom de la tâche). */
    private static final class ContexteTache implements TaskContext {

        private final Task task;
        private final BuildEvents events;
        private final CancellationFlag cancellation;
        private final java.nio.file.Path projectRoot;

        /** Posé par {@link #marquerDepuisCache()} pendant l'exécution de la tâche. */
        private volatile boolean depuisCache;

        /** Dernier message de progression posé par la tâche (D-17). */
        private volatile String messageProgression = "";

        ContexteTache(Task task, BuildEvents events, CancellationFlag cancellation,
                      java.nio.file.Path projectRoot) {
            this.task = task;
            this.events = events;
            this.cancellation = cancellation;
            this.projectRoot = projectRoot;
        }

        /** La tâche a-t-elle marqué qu'elle relisait un cache ? */
        boolean depuisCache() {
            return depuisCache;
        }

        @Override
        public java.nio.file.Path projectRoot() {
            return projectRoot;
        }

        @Override
        public jo.forge.engine.diag.DiagnosticSink diagnostics() {
            return events::diagnostic;
        }

        @Override
        public java.util.function.Consumer<BuildEvent.DownloadEvent> téléchargements() {
            return events::téléchargement;
        }

        @Override
        public String buildId() {
            return events.buildId();
        }

        @Override
        public void marquerDepuisCache() {
            depuisCache = true;
        }

        @Override
        public void progression(String message) {
            messageProgression = message == null ? "" : message;
        }

        @Override
        public String messageProgression() {
            return messageProgression;
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
