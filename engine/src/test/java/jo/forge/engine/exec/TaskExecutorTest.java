package jo.forge.engine.exec;

import jo.forge.api.BuildEvent;
import jo.forge.engine.graph.TaskGraph;
import jo.forge.engine.tasks.CancellationFlag;
import jo.forge.engine.tasks.Task;
import jo.forge.engine.tasks.TaskCancelledException;
import jo.forge.engine.tasks.TaskContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests de l'exécuteur : plafond de parallélisme mesuré, file de prêtes, échec qui
 * bloque transitivement mais laisse finir les branches indépendantes, annulation
 * rapide, up-to-date, progression.
 */
class TaskExecutorTest {

    /** Collecteur d'événements mémoire. */
    private static final class Journal implements TaskExecutor.BuildEvents {

        final String buildId;
        final List<BuildEvent.TaskStateEvent> etats =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<String> journaux = new java.util.concurrent.CopyOnWriteArrayList<>();
        final AtomicInteger total = new AtomicInteger();
        final AtomicInteger done = new AtomicInteger();
        volatile BuildEvent.BuildFinished fin;
        volatile String premierEchec;

        Journal(String buildId) {
            this.buildId = buildId;
        }

        @Override
        public String buildId() {
            return buildId;
        }

        @Override
        public void taskState(BuildEvent.TaskStateEvent event) {
            etats.add(event);
        }

        @Override
        public void log(BuildEvent.LogEvent.LogLevel level, String message) {
            journaux.add(level + " " + message);
        }

        @Override
        public void progress(int totalTasks, int doneTasks) {
            total.set(totalTasks);
            done.set(doneTasks);
        }

        @Override
        public void buildFinished(BuildEvent.BuildFinished finished) {
            this.fin = finished;
        }

        @Override
        public String dernierEchec() {
            return premierEchec;
        }

        @Override
        public void tacheEchouee(String taskName) {
            if (premierEchec == null) {
                premierEchec = taskName;
            }
        }

        BuildEvent.TaskStateEvent etatDe(String tache) {
            return etats.stream()
                    .filter(e -> e.taskName().equals(tache))
                    .reduce((premier, dernier) -> dernier)
                    .orElseThrow();
        }
    }

    /** Tâche configurable. */
    private static final class TacheTest implements Task {

        private final String nom;
        private final List<String> dependances;
        private final Runnable travail;

        TacheTest(String nom, List<String> dependances, Runnable travail) {
            this.nom = nom;
            this.dependances = dependances;
            this.travail = travail;
        }

        @Override
        public String name() {
            return nom;
        }

        @Override
        public List<String> dependencies() {
            return dependances;
        }

        @Override
        public void execute(TaskContext context) throws Exception {
            travail.run();
        }
    }

    @Test
    @Timeout(30)
    void plafondDeParalleleRespecte() throws Exception {
        int parallelisme = 2;
        AtomicInteger simultanees = new AtomicInteger();
        AtomicInteger maxSimultane = new AtomicInteger();
        CountDownLatch deuxDemarrees = new CountDownLatch(2);
        CountDownLatch liberer = new CountDownLatch(1);

        List<Task> taches = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            taches.add(new TacheTest("t" + i, List.of(), () -> {
                int courantes = simultanees.incrementAndGet();
                simultanees.accumulateAndGet(courantes, Math::max);
                maxSimultane.accumulateAndGet(courantes, Math::max);
                deuxDemarrees.countDown();
                try {
                    // Bloque jusqu'à ce que le test ait vérifié qu'il n'y en a que 2.
                    liberer.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    simultanees.decrementAndGet();
                }
            }));
        }

        Journal journal = new Journal("b-parallele");
        TaskExecutor executeur = new TaskExecutor(TaskGraph.of(taches), journal,
                new CancellationFlag(), UpToDateOracle.ALWAYS_RUN, parallelisme);

        Thread build = new Thread(executeur::execute);
        build.start();
        assertTrue(deuxDemarrees.await(10, TimeUnit.SECONDS),
                "deux tâches doivent démarrer");
        // Un délai court pour prouver qu'aucune troisième ne démarre.
        Thread.sleep(300);
        assertEquals(2, simultanees.get(),
                "le plafond de parallélisme doit être respecté");
        liberer.countDown();
        build.join(20_000);

        assertTrue(journal.fin.success());
        assertTrue(maxSimultane.get() <= parallelisme,
                "jamais plus que le plafond (max vu : " + maxSimultane.get() + ")");
        assertEquals(4, journal.done.get(), "les 4 tâches sont terminées");
    }

    @Test
    @Timeout(30)
    void fileDePretesSansBarriereDeNiveau() {
        // Sans file de prêtes, d attendrait la fin de TOUT le niveau 0 (dont b, 600 ms) ;
        // avec elle, d démarre dès la fin de a (300 ms).
        Journal journal = new Journal("b-file");
        Map<String, Long> fins = new ConcurrentHashMap<>();
        long debut = System.currentTimeMillis();
        List<Task> taches = List.of(
                new TacheTest("a", List.of(), () -> dormir(300, "a", fins, debut)),
                new TacheTest("b", List.of(), () -> dormir(600, "b", fins, debut)),
                new TacheTest("c", List.of(), () -> dormir(10, "c", fins, debut)),
                new TacheTest("d", List.of("a"), () -> dormir(1, "d", fins, debut)));

        TaskExecutor.BuildResult resultat = new TaskExecutor(TaskGraph.of(taches),
                journal, new CancellationFlag(), UpToDateOracle.ALWAYS_RUN, 3).execute();

        assertTrue(resultat.success());
        assertTrue(fins.get("d") < fins.get("b"),
                "d (dépend de a seule) doit finir avant b : " + fins);
    }

    private void dormir(long ms, String nom, Map<String, Long> fins, long debut) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        fins.put(nom, System.currentTimeMillis() - debut);
    }

    @Test
    @Timeout(30)
    void echecBloquantMaisBrancheIndependante() {
        Journal journal = new Journal("b-echec");
        List<Task> taches = List.of(
                new TacheTest("a", List.of(), () -> {
                    throw new IllegalStateException("boom");
                }),
                new TacheTest("depend-a", List.of("a"), () -> { }),
                new TacheTest("depend-depend-a", List.of("depend-a"), () -> { }),
                new TacheTest("independante", List.of(), () -> { }));

        TaskExecutor.BuildResult resultat = new TaskExecutor(TaskGraph.of(taches),
                journal, new CancellationFlag(), UpToDateOracle.ALWAYS_RUN, 2).execute();

        assertFalse(resultat.success());
        assertEquals("a", resultat.failedTask());

        assertEquals(BuildEvent.TaskState.SUCCEEDED, journal.etatDe("independante").state(),
                "la branche indépendante doit finir");
        assertEquals(BuildEvent.TaskState.FAILED, journal.etatDe("a").state());
        assertEquals(BuildEvent.TaskState.SKIPPED, journal.etatDe("depend-a").state(),
                "les dépendantes directes sont bloquées");
        assertEquals(BuildEvent.TaskState.SKIPPED,
                journal.etatDe("depend-depend-a").state(),
                "le blocage est transitif");
        assertTrue(journal.journaux.stream().anyMatch(l -> l.contains("boom")),
                "la cause de l'échec doit être journalisée");
    }

    @Test
    @Timeout(30)
    void annulationRapideEtPropre() {
        CancellationFlag jeton = new CancellationFlag();
        AtomicInteger annulees = new AtomicInteger();
        Journal journal = new Journal("b-annul");
        List<Task> taches = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            taches.add(new TacheTest("lente" + i, List.of(), () -> {
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new TaskCancelledException("interrompue");
                }
            }));
        }

        new Thread(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            jeton.cancel();
        }).start();

        long debut = System.currentTimeMillis();
        TaskExecutor.BuildResult resultat = new TaskExecutor(TaskGraph.of(taches),
                journal, jeton, UpToDateOracle.ALWAYS_RUN, 2).execute();
        long duree = System.currentTimeMillis() - debut;

        assertFalse(resultat.success());
        assertTrue(resultat.cancelled(), "le résultat doit porter l'annulation");
        assertTrue(duree < 5_000, "l'annulation ne doit pas attendre les 10 s (durée : "
                + duree + " ms)");
        assertTrue(journal.fin != null && !journal.fin.success());
    }

    @Test
    @Timeout(30)
    void tacheAJourSauteeParLOracle() {
        // Oracle : tout est à jour.
        UpToDateOracle toutAJour = new UpToDateOracle() {
            @Override
            public boolean isUpToDate(Task task) {
                return true;
            }

            @Override
            public void recordSuccess(Task task) {
                // Pas appelé : rien ne s'exécute.
            }
        };
        AtomicInteger executions = new AtomicInteger();
        Journal journal = new Journal("b-ajour");
        List<Task> taches = List.of(
                new TacheTest("x", List.of(), executions::incrementAndGet),
                new TacheTest("y", List.of("x"), executions::incrementAndGet));

        TaskExecutor.BuildResult resultat = new TaskExecutor(TaskGraph.of(taches),
                journal, new CancellationFlag(), toutAJour, 2).execute();

        assertTrue(resultat.success());
        assertEquals(0, executions.get(), "aucune tâche ne doit s'exécuter");
        assertEquals(BuildEvent.TaskState.UP_TO_DATE, journal.etatDe("x").state());
        assertEquals(BuildEvent.TaskState.UP_TO_DATE, journal.etatDe("y").state());
        assertTrue(journal.journaux.stream().noneMatch(l -> l.contains("ERROR")));
    }
}
