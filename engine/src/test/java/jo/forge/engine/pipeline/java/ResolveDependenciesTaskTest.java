package jo.forge.engine.pipeline.java;

import jo.forge.engine.exec.TaskExecutor;
import jo.forge.engine.graph.TaskGraph;
import jo.forge.engine.model.ForgeModel;
import jo.forge.engine.model.ModelCodec;
import jo.forge.engine.pipeline.ProjectLayout;
import jo.forge.engine.state.FingerprintStore;
import jo.forge.engine.tasks.Task;
import jo.forge.engine.testutil.DépôtLocal;
import jo.forge.engine.testutil.Projets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Intégration de bout en bout de la résolution dans le pipeline : un module dépend
 * d'une bibliothèque Maven servie par un vrai dépôt HTTP local, la compilation
 * s'appuie sur le jar téléchargé, et la modification de la version dans
 * {@code forge.json} relance exactement la résolution et la compilation.
 */
class ResolveDependenciesTaskTest {

    @TempDir
    Path temporaire;

    @Test
    void construitContreUneDépendanceMavenRéelle() throws Exception {
        try (DépôtLocal dépôt = DépôtLocal.démarrer()) {
            byte[] jarOutil = jarAvecClasseOutil();
            dépôt.publierPom(new jo.forge.engine.deps.Coordinate(
                            "demo", "outil", "1.0"),
                    "<project><groupId>demo</groupId><artifactId>outil</artifactId>"
                            + "<version>1.0</version></project>");
            dépôt.publierJar(new jo.forge.engine.deps.Coordinate(
                    "demo", "outil", "1.0"), jarOutil);

            Projets.fichier(temporaire, "forge.json", """
                    { "modelVersion": 1,
                      "repositories": ["%s"],
                      "modules": [ { "name": "app", "type": "java-library",
                        "dependencies": [ { "maven": "demo:outil:1.0" } ] } ] }
                    """.formatted(dépôt.url()));
            Projets.fichier(temporaire, "app/src/main/java/demo/app/Calcul.java", """
                    package demo.app;

                    import demo.outil.Outil;

                    public final class Calcul {
                        public static String bonjour() {
                            return Outil.saluer();
                        }
                    }
                    """);

            Enregistreur événements = new Enregistreur();
            assertTrue(exécuter(événements).success(),
                    "le build doit réussir contre la dépendance téléchargée : "
                            + événements.journalErreur);
            assertTrue(Files.isRegularFile(temporaire.resolve(
                            "build/app/classes/demo/app/Calcul.class")),
                    "la classe utilisant la dépendance doit être compilée");
            assertTrue(Files.isRegularFile(temporaire.resolve(
                    "build/app/resolved/classpath.json")));

            // Second build : tout à jour (le classpath résolu n'a pas changé).
            Enregistreur secondPassage = new Enregistreur();
            assertTrue(exécuter(secondPassage).success());
            assertTrue(secondPassage.tâchesParÉtat(
                    jo.forge.api.BuildEvent.TaskState.UP_TO_DATE).size() >= 3,
                    "résolution + compilation + jar à jour au second passage");
        }
    }

    @Test
    void changerLaVersionRelanceRésolutionEtCompilation() throws Exception {
        try (DépôtLocal dépôt = DépôtLocal.démarrer()) {
            for (String version : new String[]{"1.0", "2.0"}) {
                jo.forge.engine.deps.Coordinate coordonnée =
                        new jo.forge.engine.deps.Coordinate("demo", "outil", version);
                dépôt.publierPom(coordonnée,
                        "<project><groupId>demo</groupId><artifactId>outil</artifactId>"
                                + "<version>" + version + "</version></project>");
                dépôt.publierJar(coordonnée, jarAvecClasseOutil());
            }

            Projets.fichier(temporaire, "forge.json", """
                    { "modelVersion": 1,
                      "repositories": ["%s"],
                      "modules": [ { "name": "app", "type": "java-library",
                        "dependencies": [ { "maven": "demo:outil:1.0" } ] } ] }
                    """.formatted(dépôt.url()));
            Projets.fichier(temporaire, "app/src/main/java/demo/app/Calcul.java", """
                    package demo.app;

                    public final class Calcul {
                        public static int réponse() { return 42; }
                    }
                    """);
            assertTrue(exécuter(new Enregistreur()).success());

            // Version bump : la résolution et la compilation repartent, pas le jar
            // (classes identiques), preuve que l'empreinte du classpath travaille.
            Projets.fichier(temporaire, "forge.json", """
                    { "modelVersion": 1,
                      "repositories": ["%s"],
                      "modules": [ { "name": "app", "type": "java-library",
                        "dependencies": [ { "maven": "demo:outil:2.0" } ] } ] }
                    """.formatted(dépôt.url()));
            Enregistreur relance = new Enregistreur();
            assertTrue(exécuter(relance).success());
            List<String> exécutées = relance.tâchesParÉtat(
                    jo.forge.api.BuildEvent.TaskState.STARTED).stream().sorted().toList();
            assertTrue(exécutées.contains("app:resolveDependencies")
                            && exécutées.contains("app:compileJava"),
                    "résolution et compilation relancées : " + exécutées);
            assertTrue(!exécutées.contains("app:jar"),
                    "le jar reste à jour (classes inchangées) : " + exécutées);
        }
    }

    /**
     * Exécute un build complet avec empreintes persistantes.
     */
    private TaskExecutor.BuildResult exécuter(Enregistreur événements)
            throws IOException {
        ForgeModel modèle = ModelCodec.read(temporaire);
        List<Task> tâches = JavaPipeline.tasksFor(modèle, temporaire, false);
        TaskGraph graphe = TaskGraph.of(tâches);
        ProjectLayout disposition = new ProjectLayout(temporaire);
        FingerprintStore magasin = new FingerprintStore(disposition.stateDir(),
                temporaire, avertissement -> { });
        return new TaskExecutor(graphe, événements,
                new jo.forge.engine.tasks.CancellationFlag(), magasin, 2,
                temporaire).execute();
    }

    /**
     * Construit un vrai jar contenant {@code demo.outil.Outil} compilé par ECJ.
     */
    private byte[] jarAvecClasseOutil() throws IOException {
        Path sources = Files.createTempDirectory("outil-src");
        Path classes = Files.createTempDirectory("outil-classes");
        Files.writeString(sources.resolve("Outil.java"), """
                package demo.outil;

                public final class Outil {
                    private Outil() { }

                    public static String saluer() { return "salut de la lib"; }
                }
                """);
        EcjCompiler.compile(List.of(sources.resolve("Outil.java")), List.of(), classes,
                17, jo.forge.engine.diag.DiagnosticSink.NOOP);
        java.io.ByteArrayOutputStream octets = new java.io.ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(octets)) {
            zip.putNextEntry(new ZipEntry("demo/outil/Outil.class"));
            Files.copy(classes.resolve("demo/outil/Outil.class"), zip);
            zip.closeEntry();
        }
        return octets.toByteArray();
    }

    /** Enregistreur d'événements pour les assertions. */
    private static final class Enregistreur implements TaskExecutor.BuildEvents {

        final Map<jo.forge.api.BuildEvent.TaskState, List<String>> états =
                new HashMap<>();
        final StringBuilder journalErreur = new StringBuilder();
        private String premierÉchec;

        List<String> tâchesParÉtat(jo.forge.api.BuildEvent.TaskState état) {
            return états.getOrDefault(état, List.of());
        }

        @Override
        public String buildId() {
            return "test";
        }

        @Override
        public void taskState(jo.forge.api.BuildEvent.TaskStateEvent event) {
            états.computeIfAbsent(event.state(), k -> new ArrayList<>())
                    .add(event.taskName());
        }

        @Override
        public void log(jo.forge.api.BuildEvent.LogEvent.LogLevel level, String message) {
            if (level == jo.forge.api.BuildEvent.LogEvent.LogLevel.ERROR) {
                journalErreur.append(message).append('\n');
            }
        }

        @Override
        public void progress(int total, int done) {
        }

        @Override
        public void buildFinished(jo.forge.api.BuildEvent.BuildFinished finished) {
        }

        @Override
        public String dernierEchec() {
            return premierÉchec;
        }

        @Override
        public void tacheEchouee(String taskName) {
            if (premierÉchec == null) {
                premierÉchec = taskName;
            }
        }
    }
}
