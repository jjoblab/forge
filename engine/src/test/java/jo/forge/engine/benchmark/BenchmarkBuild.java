package jo.forge.engine.benchmark;

import jo.forge.engine.exec.TaskExecutor;
import jo.forge.engine.graph.TaskGraph;
import jo.forge.engine.model.ForgeModel;
import jo.forge.engine.model.ModelCodec;
import jo.forge.engine.pipeline.ProjectLayout;
import jo.forge.engine.pipeline.java.JavaPipeline;
import jo.forge.engine.state.FingerprintStore;
import jo.forge.engine.tasks.Task;
import jo.forge.engine.testutil.Projets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Benchmark du pipeline Java : build complet contre build incrémental sur un projet
 * multi-modules généré (3 modules, 120 classes), mesures affichées dans le journal de
 * test et consignées dans {@code docs/limites-et-suite.md}.
 *
 * <p>Assertion robuste (pas de seuil temps, instable en CI) : le build incrémental
 * exécute strictement moins de tâches que le complet — la mesure de temps est
 * indicative.</p>
 */
class BenchmarkBuild {

    @TempDir
    Path temporaire;

    @Test
    void buildCompletContreBuildIncrémental() throws Exception {
        générerProjet(temporaire, 3, 40);

        Mesure complet = exécuter(temporaire, "complet");
        assertTrue(complet.succès, "le build complet doit réussir");

        // Un seul fichier modifié, au plus bas de la pile : la branche basse relance.
        Path uneSource = temporaire.resolve("m0/src/main/java/gen/m0/C0.java");
        Projets.fichier(temporaire, "m0/src/main/java/gen/m0/C0.java", """
                package gen.m0;

                public final class C0 {
                    public static int réponse() { return 42; }
                }
                """).toString();
        assertTrue(java.nio.file.Files.exists(uneSource));

        Mesure incrément = exécuter(temporaire, "incrémental");
        assertTrue(incrément.succès);

        System.out.println("[benchmark] tâches exécutées : complet="
                + complet.tâchesExécutées + "/" + complet.tâchesTotales
                + ", incrémental=" + incrément.tâchesExécutées + "/" + incrément.tâchesTotales);
        System.out.println("[benchmark] durées : complet=" + complet.duréeMs
                + " ms, incrémental=" + incrément.duréeMs + " ms");

        assertTrue(incrément.tâchesExécutées < complet.tâchesExécutées,
                "le build incrémental doit exécuter moins de tâches ("
                        + incrément.tâchesExécutées + " < " + complet.tâchesExécutées
                        + ")");
    }

    /**
     * Génère un projet en chaîne : m0 ← m1 ← m2, chaque module avec N classes.
     */
    private void générerProjet(Path racine, int modules, int classes) throws IOException {
        StringBuilder json = new StringBuilder("""
                { "modelVersion": 1,
                  "modules": [
                """);
        for (int m = 0; m < modules; m++) {
            if (m > 0) {
                json.append(",\n");
            }
            json.append("{ \"name\": \"m").append(m)
                    .append("\", \"type\": \"java-library\"");
            if (m > 0) {
                json.append(", \"dependencies\": [ { \"project\": \"m").append(m - 1)
                        .append("\", \"scope\": \"api\" } ]");
            }
            json.append(" }");
        }
        json.append("\n  ]\n}\n");
        Projets.fichier(racine, "forge.json", json.toString());
        for (int m = 0; m < modules; m++) {
            for (int c = 0; c < classes; c++) {
                String nom = "C" + c;
                Projets.fichier(racine, "m" + m + "/src/main/java/gen/m" + m + "/"
                                + nom + ".java", """
                        package gen.m%d;

                        public final class %s {
                            public static int valeur() { return %d; }
                        }
                        """.formatted(m, nom, m * 1000 + c));
            }
        }
    }

    /**
     * Exécute un build et mesure : durée, tâches exécutées (STARTED), total.
     */
    private Mesure exécuter(Path racine, String nom) throws IOException {
        ForgeModel modèle = ModelCodec.read(racine);
        List<Task> tâches = JavaPipeline.tasksFor(modèle, racine);
        TaskGraph graphe = TaskGraph.of(tâches);
        ProjectLayout disposition = new ProjectLayout(racine);
        FingerprintStore magasin = new FingerprintStore(disposition.stateDir(), racine,
                avertissement -> { });

        AtomicInteger exécutées = new AtomicInteger();
        Compteur compteur = new Compteur(exécutées);
        TaskExecutor.BuildResult résultat = new TaskExecutor(graphe,
                compteur, new jo.forge.engine.tasks.CancellationFlag(),
                magasin, 2, racine).execute();

        Mesure mesure = new Mesure();
        mesure.succès = résultat.success();
        mesure.duréeMs = résultat.durationMs();
        mesure.tâchesExécutées = exécutées.get();
        mesure.tâchesTotales = graphe.size();
        System.out.println("[benchmark] " + nom + " : " + mesure.duréeMs + " ms, "
                + mesure.tâchesExécutées + "/" + mesure.tâchesTotales
                + " tâches exécutées " + compteur.nomsExécutées);
        return mesure;
    }

    /** Puits d'événements qui compte les tâches réellement exécutées. */
    private static final class Compteur implements TaskExecutor.BuildEvents {

        final AtomicInteger exécutées;
        final List<String> nomsExécutées = new java.util.concurrent.CopyOnWriteArrayList<>();

        Compteur(AtomicInteger exécutées) {
            this.exécutées = exécutées;
        }

        @Override
        public String buildId() {
            return "benchmark";
        }

        @Override
        public void taskState(jo.forge.api.BuildEvent.TaskStateEvent event) {
            if (event.state() == jo.forge.api.BuildEvent.TaskState.STARTED) {
                exécutées.incrementAndGet();
                nomsExécutées.add(event.taskName());
            }
        }

        @Override
        public void log(jo.forge.api.BuildEvent.LogEvent.LogLevel level, String message) {
        }

        @Override
        public void progress(int total, int done) {
        }

        @Override
        public void buildFinished(jo.forge.api.BuildEvent.BuildFinished finished) {
        }

        @Override
        public String dernierEchec() {
            return null;
        }

        @Override
        public void tacheEchouee(String taskName) {
        }
    }

    /** Résultat mesuré d'un build. */
    private static final class Mesure {
        boolean succès;
        long duréeMs;
        int tâchesExécutées;
        int tâchesTotales;
    }
}
