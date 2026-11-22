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
     * D-18 — caches partagés entre projets : la réplique Maven et les résultats de
     * résolution vivent sous la racine partagée du moteur, pas dans le projet. Un
     * DEUXIÈME projet (empreintes neuves, dépôt ARRÊTÉ — plus aucun réseau) doit
     * résoudre DEPUIS LE CACHE PARTAGÉ (état FROM_CACHE, aucune requête) et
     * compiler contre l'artefact servi par la réplique commune. Une réimportation
     * de projet (qui efface son .forge) ne perd plus rien.
     */
    @Test
    void cachePartagéEntreProjetsSurvitAuRéseauCoupé() throws Exception {
        jo.forge.engine.testutil.DépôtLocal dépôt =
                jo.forge.engine.testutil.DépôtLocal.démarrer();
        try {
            dépôt.publierPom(new jo.forge.engine.deps.Coordinate(
                            "demo", "outil", "1.0"),
                    "<project><groupId>demo</groupId><artifactId>outil</artifactId>"
                            + "<version>1.0</version></project>");
            dépôt.publierJar(new jo.forge.engine.deps.Coordinate(
                    "demo", "outil", "1.0"), jarAvecClasseOutil());
            String forgeJson = """
                    { "modelVersion": 1,
                      "repositories": ["%s"],
                      "modules": [ { "name": "app", "type": "java-library",
                        "dependencies": [ { "maven": "demo:outil:1.0" } ] } ] }
                    """.formatted(dépôt.url());
            Projets.fichier(temporaire, "projet1/forge.json", forgeJson);
            Projets.fichier(temporaire, "projet1/app/src/main/java/demo/app/Un.java", """
                    package demo.app;

                    import demo.outil.Outil;

                    public final class Un {
                        public static String bonjour() { return Outil.saluer(); }
                    }
                    """);
            Path partagé = temporaire.resolve("cache-du-moteur");

            // Premier projet : résolution complète (téléchargement vers la
            // réplique PARTAGÉE, jamais dans le projet).
            Enregistreur premier = new Enregistreur();
            assertTrue(exécuter(temporaire.resolve("projet1"), premier, partagé).success(),
                    "le premier build doit réussir : " + premier.journalErreur);
            assertTrue(Files.isRegularFile(partagé.resolve(
                            "maven/demo/outil/1.0/outil-1.0.jar")),
                    "la réplique vit sous le cache partagé");
            assertTrue(!Files.exists(temporaire.resolve("projet1/.forge/deps")),
                    "plus de réplique dans le projet (D-18)");

            // Le réseau tombe : le dépôt s'arrête. Le deuxième projet, aux
            // empreintes neuves, doit résoudre SANS réseau (FROM_CACHE) et
            // compiler contre la réplique partagée.
            dépôt.close();
            Projets.fichier(temporaire, "projet2/forge.json", forgeJson);
            Projets.fichier(temporaire, "projet2/app/src/main/java/demo/app/Deux.java", """
                    package demo.app;

                    import demo.outil.Outil;

                    public final class Deux {
                        public static String bonjour() { return Outil.saluer() + " 2"; }
                    }
                    """);
            Enregistreur second = new Enregistreur();
            assertTrue(exécuter(temporaire.resolve("projet2"), second, partagé).success(),
                    "le deuxième projet doit résoudre sans réseau : "
                            + second.journalErreur);
            assertTrue(second.tâchesParÉtat(
                            jo.forge.api.BuildEvent.TaskState.FROM_CACHE)
                            .contains("app:resolveDependencies"),
                    "la résolution du second projet doit être relue du cache partagé");
            assertTrue(Files.isRegularFile(temporaire.resolve(
                            "projet2/build/app/classes/demo/app/Deux.class")),
                    "la compilation du second projet utilise la réplique partagée");
        } finally {
            dépôt.close();
        }
    }

    /**
     * Exécute un build complet avec empreintes persistantes.
     */
    private TaskExecutor.BuildResult exécuter(Enregistreur événements)
            throws IOException {
        return exécuter(temporaire, événements, null);
    }

    /**
     * Exécute un build complet avec empreintes persistantes, sur une racine et un
     * cache partagé (D-18) donnés.
     */
    private TaskExecutor.BuildResult exécuter(Path racine, Enregistreur événements,
            Path cachePartagé) throws IOException {
        ForgeModel modèle = ModelCodec.read(racine);
        List<Task> tâches = JavaPipeline.tasksFor(modèle, racine, false,
                "arm64-v8a", null, cachePartagé);
        TaskGraph graphe = TaskGraph.of(tâches);
        ProjectLayout disposition = new ProjectLayout(racine);
        FingerprintStore magasin = new FingerprintStore(disposition.stateDir(),
                racine, avertissement -> { });
        return new TaskExecutor(graphe, événements,
                new jo.forge.engine.tasks.CancellationFlag(), magasin, 2,
                racine).execute();
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

    /** Enregistreur d'événements pour les assertions — collections concurrentes (le
     * puits est appelé depuis les threads du pool). */
    private static final class Enregistreur implements TaskExecutor.BuildEvents {

        final Map<jo.forge.api.BuildEvent.TaskState, List<String>> états =
                new java.util.concurrent.ConcurrentHashMap<>();
        final StringBuffer journalErreur = new StringBuffer();
        private final java.util.concurrent.atomic.AtomicReference<String> premierÉchec =
                new java.util.concurrent.atomic.AtomicReference<>();

        List<String> tâchesParÉtat(jo.forge.api.BuildEvent.TaskState état) {
            return états.getOrDefault(état, List.of());
        }

        @Override
        public String buildId() {
            return "test";
        }

        @Override
        public void taskState(jo.forge.api.BuildEvent.TaskStateEvent event) {
            états.computeIfAbsent(event.state(),
                            k -> new java.util.concurrent.CopyOnWriteArrayList<>())
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
            return premierÉchec.get();
        }

        @Override
        public void tacheEchouee(String taskName) {
            premierÉchec.compareAndSet(null, taskName);
        }
    }
}
