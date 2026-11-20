package jo.forge.engine.pipeline.java;

import jo.forge.engine.exec.TaskExecutor;
import jo.forge.engine.graph.TaskGraph;
import jo.forge.engine.model.ForgeModel;
import jo.forge.engine.model.ModelCodec;
import jo.forge.engine.model.ModelException;
import jo.forge.engine.pipeline.ProjectLayout;
import jo.forge.engine.state.FingerprintStore;
import jo.forge.engine.tasks.Task;
import jo.forge.engine.testutil.Projets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests du pipeline Java : câblage du modèle en tâches, build réel de bout en bout
 * (compilation + jar), incrémentalité par empreintes (modifier un fichier ne relance
 * que sa branche), déterminisme du jar, et refus explicites des modules hors périmètre
 * (Android, Maven).
 */
class JavaPipelineTest {

    @TempDir
    Path temporaire;

    @Test
    void câbleLeModèleEnTâches() throws IOException {
        Projets.projetDeuxModules(temporaire);
        ForgeModel modèle = ModelCodec.read(temporaire);
        List<Task> tâches = JavaPipeline.tasksFor(modèle, temporaire);

        assertEquals(6, tâches.size(), "2 modules × (resolve, compile, jar)");
        Map<String, Task> parNom = tâches.stream()
                .collect(Collectors.toMap(Task::name, t -> t));
        assertEquals(List.of("app:resolveDependencies", "core:compileJava"),
                parNom.get("app:compileJava").dependencies().stream().sorted().toList(),
                "app:compileJava dépend de la compilation amont et de sa résolution");
        assertEquals(List.of("app:compileJava"),
                parNom.get("app:jar").dependencies());
        assertTrue(parNom.get("core:compileJava").dependencies()
                .contains("core:resolveDependencies"));
    }

    /**
     * Correctif v0.2.7 : sur ART, une bibliothèque Java reçoit android.jar du
     * moteur installé comme bibliothèque de plate-forme (sans elle, ECJ ne
     * résout pas java.lang.Object — constat appareil). La plate-forme entre
     * dans l'empreinte PAR SON CONTENU.
     */
    @Test
    void bibliothèqueJavaReçoitLaPlateformeSurArt() throws IOException {
        Projets.projetDeuxModules(temporaire);
        Path maison = temporaire.resolve("engines/0.2.7");
        Files.createDirectories(maison.resolve("components/platform-34"));
        Files.write(maison.resolve("components/platform-34/android.jar"),
                new byte[]{0x50, 0x4b, 0x03, 0x04});

        String ancienJar = System.clearProperty("forge.androidJar");
        try {
            ForgeModel modèle = ModelCodec.read(temporaire);
            List<Task> tâches = JavaPipeline.tasksFor(modèle, temporaire, false,
                    "arm64-v8a", maison, true);
            Task compilation = tâches.stream()
                    .filter(tache -> tache.name().equals("core:compileJava"))
                    .findFirst().orElseThrow();
            assertTrue(compilation.io().inputFiles().stream()
                            .anyMatch(entrée -> entrée.toString()
                                    .endsWith("components/platform-34/android.jar")),
                    "android.jar du moteur installé doit être entrée d'empreinte : "
                            + compilation.io().inputFiles());
        } finally {
            if (ancienJar != null) {
                System.setProperty("forge.androidJar", ancienJar);
            }
        }
    }

    /**
     * Sur une JVM de bureau, la plate-forme des bibliothèques Java reste vide :
     * ECJ emprunte les classes de l'hôte, sémantique JDK (comportement
     * historique inchangé — les tests existants en dépendent).
     */
    @Test
    void horsArtLaPlateformeResteVide() throws IOException {
        Projets.projetDeuxModules(temporaire);
        Path maison = temporaire.resolve("engines/0.2.7");
        Files.createDirectories(maison.resolve("components/platform-34"));
        Files.write(maison.resolve("components/platform-34/android.jar"),
                new byte[]{0x50, 0x4b, 0x03, 0x04});

        ForgeModel modèle = ModelCodec.read(temporaire);
        List<Task> tâches = JavaPipeline.tasksFor(modèle, temporaire, false,
                "arm64-v8a", maison, false);
        Task compilation = tâches.stream()
                .filter(tache -> tache.name().equals("core:compileJava"))
                .findFirst().orElseThrow();
        assertTrue(compilation.io().inputFiles().stream()
                        .noneMatch(entrée -> entrée.toString().contains("components/")),
                "aucune entrée de plate-forme hors ART : "
                        + compilation.io().inputFiles());
    }

    /**
     * La plate-forme d'un projet MIXTE suit le targetSdk de ses modules
     * Android (cohérence de compilation au sein du projet), même si une
     * plate-forme plus récente est installée.
     */
    @Test
    void projetMixteSuitLeTargetSdkDuProjet() throws IOException {
        Projets.projetDeuxModules(temporaire);
        Projets.fichier(temporaire, "forge.json", """
                { "modelVersion": 1,
                  "repositories": [],
                  "modules": [
                    { "name": "core", "type": "java-library" },
                    { "name": "app", "type": "application",
                      "android": { "manifest": "src/main/AndroidManifest.xml",
                                   "res": ["src/main/res"],
                                   "minSdk": 28, "targetSdk": 34,
                                   "namespace": "demo.app" } }
                  ] }
                """);
        Projets.fichier(temporaire, "app/src/main/AndroidManifest.xml", """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest package="demo.app" />
                """);
        Path maison = temporaire.resolve("engines/0.2.7");
        for (int api : new int[]{34, 35}) {
            Files.createDirectories(maison.resolve("components/platform-" + api));
            Files.write(maison.resolve("components/platform-" + api + "/android.jar"),
                    new byte[]{0x50, 0x4b, 0x03, 0x04});
        }
        // aapt2 requis par le module application : composant factice.
        Files.createDirectories(maison.resolve("components/aapt2-arm64-v8a"));
        Files.writeString(maison.resolve("components/aapt2-arm64-v8a/aapt2"),
                "binaire factice");

        String ancienJar = System.clearProperty("forge.androidJar");
        String ancienAapt2 = System.clearProperty("forge.aapt2");
        try {
            ForgeModel modèle = ModelCodec.read(temporaire);
            List<Task> tâches = JavaPipeline.tasksFor(modèle, temporaire, false,
                    "arm64-v8a", maison, true);
            Task compilation = tâches.stream()
                    .filter(tache -> tache.name().equals("core:compileJava"))
                    .findFirst().orElseThrow();
            assertTrue(compilation.io().inputFiles().stream()
                            .anyMatch(entrée -> entrée.toString()
                                    .endsWith("components/platform-34/android.jar")),
                    "la bibliothèque compile contre la plate-forme du targetSdk du "
                            + "projet (34), pas la plus haute installée (35)");
        } finally {
            if (ancienJar != null) {
                System.setProperty("forge.androidJar", ancienJar);
            }
            if (ancienAapt2 != null) {
                System.setProperty("forge.aapt2", ancienAapt2);
            }
        }
    }

    @Test
    void buildCompletPuisToutÀJour() throws Exception {
        Projets.projetDeuxModules(temporaire);
        Enregistreur événements = new Enregistreur();
        TaskExecutor.BuildResult premier = exécuter(événements);

        assertTrue(premier.success(), "le premier build doit réussir : "
                + événements.journalErreur);
        assertTrue(Files.isRegularFile(temporaire.resolve(
                "build/app/libs/app.jar")), "le jar de app doit exister");
        assertTrue(Files.isRegularFile(temporaire.resolve(
                "build/core/libs/core.jar")), "le jar de core doit exister");

        événements = new Enregistreur();
        TaskExecutor.BuildResult second = exécuter(événements);
        assertTrue(second.success());
        assertEquals(6, événements.tâchesParÉtat(
                jo.forge.api.BuildEvent.TaskState.UP_TO_DATE).size(),
                "le second build sans changement doit tout trouver à jour : "
                        + événements.tâchesParÉtat(
                                jo.forge.api.BuildEvent.TaskState.STARTED));
    }

    @Test
    void modifierLeHautRelanceLaDescendanceNécessaire() throws Exception {
        Projets.projetDeuxModules(temporaire);
        assertTrue(exécuter(new Enregistreur()).success(),
                "le premier build doit réussir");

        // Modifier UNE source du module bas de la pile : core:compileJava, core:jar et
        // la compilation descendante se relancent (le classpath de app a changé par
        // CONTENU — sémantique v1 ; l'empreinte d'ABI qui éviterait cette cascade est
        // l'amélioration A-10, non livrée). Le jar de app, lui, reste à jour : ses
        // classes n'ont pas changé.
        Projets.fichier(temporaire, "core/src/main/java/demo/core/Textes.java", """
                package demo.core;

                public final class Textes {
                    private Textes() { }

                    public static String joindre(String sep, String... mots) {
                        return String.join(sep, mots);
                    }

                    public static String crier(String s) { return s.toUpperCase(); }
                }
                """);

        Enregistreur événements = new Enregistreur();
        TaskExecutor.BuildResult relance = exécuter(événements);
        assertTrue(relance.success());
        assertEquals(List.of("app:compileJava", "core:compileJava", "core:jar"),
                trier(événements.tâchesParÉtat(
                        jo.forge.api.BuildEvent.TaskState.STARTED)),
                "relancées : la branche basse ET la compilation descendante (classpath "
                        + "changé), mais pas le jar descendant (classes identiques)"
                        + " — à jour : " + événements.tâchesParÉtat(
                                jo.forge.api.BuildEvent.TaskState.UP_TO_DATE)
                        + " journal : " + événements.journalErreur);
    }

    @Test
    void modifierLeBasNeRelancePasLeHaut() throws Exception {
        Projets.projetDeuxModules(temporaire);
        assertTrue(exécuter(new Enregistreur()).success(),
                "le premier build doit réussir");

        Projets.fichier(temporaire, "app/src/main/java/demo/app/App.java", """
                package demo.app;

                import demo.core.Textes;

                public final class App {
                    private App() { }

                    public static String bienvenue() {
                        return Textes.joindre(" ", "Rebonjour,", "l'exemple !");
                    }
                }
                """);

        Enregistreur événements = new Enregistreur();
        assertTrue(exécuter(événements).success());
        assertEquals(List.of("app:compileJava", "app:jar"),
                trier(événements.tâchesParÉtat(
                        jo.forge.api.BuildEvent.TaskState.STARTED)),
                "le module amont doit rester à jour");
    }

    @Test
    void jarDéterministeEntreDeuxExécutions() throws Exception {
        Projets.projetDeuxModules(temporaire);
        assertTrue(exécuter(new Enregistreur()).success(),
                "le premier build doit réussir");
        byte[] premier = Files.readAllBytes(
                temporaire.resolve("build/app/libs/app.jar"));

        // Détruire la sortie (pas l'état) : la tâche jar se relance et doit produire
        // des octets identiques (entrées triées, horodatage fixe, manifeste stable).
        Files.delete(temporaire.resolve("build/app/libs/app.jar"));
        Enregistreur événements = new Enregistreur();
        assertTrue(exécuter(événements).success(), "le second build doit réussir");
        assertTrue(événements.tâchesParÉtat(
                jo.forge.api.BuildEvent.TaskState.STARTED).contains("app:jar"));
        byte[] second = Files.readAllBytes(
                temporaire.resolve("build/app/libs/app.jar"));
        assertArrayEquals(premier, second,
                "le jar doit être reproductible octet par octet");
        assertTrue(MessageDigest.getInstance("SHA-256").digest(premier).length > 0);
    }

    @Test
    void erreurDeCompilationÉchecPropreAvecDiagnostic() throws Exception {
        Projets.projetDeuxModules(temporaire);
        Projets.fichier(temporaire, "app/src/main/java/demo/app/Fautif.java", """
                package demo.app;

                public class Fautif {
                    int calcul() { return inconnu; }
                }
                """);

        Enregistreur événements = new Enregistreur();
        TaskExecutor.BuildResult échec = exécuter(événements);
        assertTrue(!échec.success(), "le build doit échouer");
        assertEquals("app:compileJava", échec.failedTask());
        assertTrue(événements.diagnostics.stream().anyMatch(d ->
                        d.severity() == jo.forge.api.BuildEvent.Severity.ERROR
                                && d.file().endsWith("Fautif.java")),
                "un diagnostic localisé sur Fautif.java doit être émis");
        assertTrue(événements.journalErreur.toString().contains("app:compileJava"),
                "le journal doit citer la tâche fautive");
    }

    /**
     * Exécute un build complet du projet temporaire avec empreintes persistantes.
     */
    private TaskExecutor.BuildResult exécuter(Enregistreur événements) throws IOException {
        ForgeModel modèle = ModelCodec.read(temporaire);
        List<Task> tâches = JavaPipeline.tasksFor(modèle, temporaire);
        TaskGraph graphe = TaskGraph.of(tâches);
        ProjectLayout disposition = new ProjectLayout(temporaire);
        FingerprintStore magasin = new FingerprintStore(disposition.stateDir(),
                temporaire, avertissement -> { });
        TaskExecutor exécuteur = new TaskExecutor(graphe, événements,
                new jo.forge.engine.tasks.CancellationFlag(), magasin, 2, temporaire);
        return exécuteur.execute();
    }

    /** Trie une liste de chaînes (ordre stable des assertions). */
    private static List<String> trier(List<String> liste) {
        return liste.stream().sorted().toList();
    }

    /** Enregistreur d'événements pour les assertions — collections CONCURRENTES : le
     * puits est appelé depuis les threads du pool (une carte HashMap/ArrayList
     * partagée perd des événements sous charge — flocon constaté en CI). */
    private static final class Enregistreur implements TaskExecutor.BuildEvents {

        final Map<jo.forge.api.BuildEvent.TaskState, List<String>> états =
                new java.util.concurrent.ConcurrentHashMap<>();
        final List<jo.forge.engine.diag.Diagnostic> diagnostics =
                new java.util.concurrent.CopyOnWriteArrayList<>();
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
        public void diagnostic(jo.forge.engine.diag.Diagnostic diagnostic) {
            diagnostics.add(diagnostic);
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
