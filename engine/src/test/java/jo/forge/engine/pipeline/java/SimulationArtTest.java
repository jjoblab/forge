package jo.forge.engine.pipeline.java;

import jo.forge.engine.exec.TaskExecutor;
import jo.forge.engine.graph.TaskGraph;
import jo.forge.engine.model.ForgeModel;
import jo.forge.engine.model.ModelCodec;
import jo.forge.engine.pipeline.ProjectLayout;
import jo.forge.engine.state.FingerprintStore;
import jo.forge.engine.tasks.Task;
import jo.forge.engine.testutil.OutilsAndroidDeTest;
import jo.forge.engine.testutil.Projets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Simulation du scénario APPAREIL (correctif v0.2.7) : le moteur croit tourner
 * sur ART (nom de VM « Dalvik », comme sur l'appareil), le répertoire moteur
 * installé porte le composant platform-34 — et le projet
 * multi-modules-java, celui-là même qui échouait sur le moto g06 avec onze
 * « java.lang.Object cannot be resolved », doit compiler et s'empaqueter par
 * le chemin sans image.
 *
 * <p>C'est le test d'intégration du correctif : détection ART réelle (propriété
 * système), localisation réelle du composant, compilation réelle contre
 * android.jar réel (tranches lambda embarquées comprises), jusqu'aux jars.</p>
 *
 * <p>v0.2.11 — même simulation pour KOTLIN : le composant kotlin-compiler est
 * posé dans la maison (jar réel du classpath de test, comme l'installateur le
 * lie) et le premier compileKotlin — celui qui mourait sur l'appareil par
 * {@code Resource not found: …/PathUtil.class} (rapport v0.2.10) — doit passer
 * par la maison et la racine de configuration explicites.</p>
 */
class SimulationArtTest {

    @TempDir
    Path temporaire;

    @Test
    void multiModulesJavaCompileSurArtContreAndroidJar() throws Exception {
        Path androidJar = OutilsAndroidDeTest.androidJar();
        assumeTrue(androidJar != null,
                "android.jar réel indisponible — test ignoré");

        // Le projet de l'appareil : core (Salutations, Textes) + app (App),
        // bibliothèques Java pures, dépendance api.
        Projets.fichier(temporaire, "forge.json", """
                { "modelVersion": 1,
                  "repositories": [],
                  "modules": [
                    { "name": "core", "type": "java-library" },
                    { "name": "app", "type": "java-library",
                      "dependencies": [ { "project": "core", "scope": "api" } ] }
                  ] }
                """);
        Projets.fichier(temporaire, "core/src/main/java/demo/core/Salutations.java", """
                package demo.core;

                public final class Salutations {
                    private Salutations() { }

                    public static String saluer(String nom) {
                        return "Salut, " + nom + " !";
                    }
                }
                """);
        Projets.fichier(temporaire, "core/src/main/java/demo/core/Textes.java", """
                package demo.core;

                public final class Textes {
                    private Textes() { }

                    public static String joindre(String separateur, String... mots) {
                        return String.join(separateur, mots);
                    }
                }
                """);
        Projets.fichier(temporaire, "app/src/main/java/demo/app/App.java", """
                package demo.app;

                import demo.core.Salutations;
                import demo.core.Textes;

                public final class App {
                    private App() { }

                    public static String accueil(String nom) {
                        return Salutations.saluer(nom) + " "
                                + Textes.joindre(" ", "Bienvenue", "sur", "Forge");
                    }
                }
                """);

        // Le moteur installé sur l'appareil : components/platform-34/android.jar.
        Path maison = temporaire.resolve("engines/0.2.7");
        Files.createDirectories(maison.resolve("components/platform-34"));
        Files.copy(androidJar,
                maison.resolve("components/platform-34/android.jar"));

        // Le moteur croit être sur ART : ART se présente toujours comme
        // « Dalvik » dans java.vm.name.
        String ancienNom = System.getProperty("java.vm.name");
        String ancienJar = System.clearProperty("forge.androidJar");
        System.setProperty("java.vm.name", "Dalvik");
        try {
            ForgeModel modèle = ModelCodec.read(temporaire);
            List<Task> tâches = JavaPipeline.tasksFor(modèle, temporaire,
                    false, "arm64-v8a", maison);
            TaskGraph graphe = TaskGraph.of(tâches);
            ProjectLayout disposition = new ProjectLayout(temporaire);
            FingerprintStore magasin = new FingerprintStore(disposition.stateDir(),
                    temporaire, avertissement -> { });
            Enregisteur événements = new Enregisteur();
            TaskExecutor exécuteur = new TaskExecutor(graphe, événements,
                    new jo.forge.engine.tasks.CancellationFlag(), magasin, 2,
                    temporaire);
            TaskExecutor.BuildResult résultat = exécuteur.execute();

            assertTrue(résultat.success(), "le build multi-modules-java doit réussir "
                    + "sur (simulé) ART — journal : " + événements.journalErreur
                    + " diagnostics : " + événements.diagnostics
                    + " états : " + événements.états);
            assertTrue(Files.isRegularFile(temporaire.resolve("build/core/libs/core.jar")),
                    "le jar de core doit exister — arbre : " + arbre(temporaire));
            assertTrue(Files.isRegularFile(temporaire.resolve("build/app/libs/app.jar")),
                    "le jar de app doit exister — arbre : " + arbre(temporaire)
                            + " états : " + événements.états);
        } finally {
            if (ancienNom != null) {
                System.setProperty("java.vm.name", ancienNom);
            } else {
                System.clearProperty("java.vm.name");
            }
            if (ancienJar != null) {
                System.setProperty("forge.androidJar", ancienJar);
            }
        }
    }

    @Test
    void kotlinCompileSurArtAvecLeComposantInstallé() throws Exception {
        Path androidJar = OutilsAndroidDeTest.androidJar();
        assumeTrue(androidJar != null,
                "android.jar réel indisponible — test ignoré");
        assumeTrue(KotlinCompiler.disponible(),
                "kotlin-compiler-embeddable requis sur le classpath de test");

        // Le projet de l'appareil : kotlin-simple, module Kotlin mixte.
        Projets.fichier(temporaire, "forge.json", """
                { "modelVersion": 1,
                  "repositories": [],
                  "modules": [
                    { "name": "kalc", "type": "java-library", "language": "kotlin" }
                  ] }
                """);
        Projets.fichier(temporaire, "kalc/src/main/kotlin/demo/Calculs.kt", """
                package demo

                object Calculs {
                    fun doubler(x: Int): Int = x * 2

                    fun saluer(nom: String): String = "Salut, $nom !"
                }
                """);
        Projets.fichier(temporaire, "kalc/src/main/java/demo/Principal.java", """
                package demo;

                public final class Principal {
                    private Principal() { }

                    public static void main(String[] args) {
                        System.out.println(Calculs.INSTANCE.saluer("Forge"));
                        System.out.println("doubled=" + Calculs.INSTANCE.doubler(21));
                    }
                }
                """);

        // Le moteur installé sur l'appareil : platform-34 ET le composant
        // kotlin-compiler (jar réel — copie de l'embarqué du classpath de test,
        // l'installateur lie le dexé de la même façon).
        Path maison = temporaire.resolve("engines/0.2.11");
        Files.createDirectories(maison.resolve("components/platform-34"));
        Files.copy(androidJar,
                maison.resolve("components/platform-34/android.jar"));
        Path jarCompilateur = KotlinCompiler.jarDeLaClasse(
                "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler");
        assertNotNull(jarCompilateur,
                "le jar du compilateur doit être localisable sur le classpath de test");
        Files.createDirectories(maison.resolve("components/kotlin-compiler/lib"));
        Files.copy(jarCompilateur,
                maison.resolve("components/kotlin-compiler/lib/kotlin-compiler.jar"));

        // Le moteur croit être sur ART : la chaîne compile contre android.jar
        // avec -no-jdk, la maison et la racine de configuration explicites.
        String ancienNom = System.getProperty("java.vm.name");
        System.setProperty("java.vm.name", "Dalvik");
        try {
            ForgeModel modèle = ModelCodec.read(temporaire);
            List<Task> tâches = JavaPipeline.tasksFor(modèle, temporaire,
                    false, "arm64-v8a", maison);
            TaskGraph graphe = TaskGraph.of(tâches);
            ProjectLayout disposition = new ProjectLayout(temporaire);
            FingerprintStore magasin = new FingerprintStore(disposition.stateDir(),
                    temporaire, avertissement -> { });
            Enregisteur événements = new Enregisteur();
            TaskExecutor exécuteur = new TaskExecutor(graphe, événements,
                    new jo.forge.engine.tasks.CancellationFlag(), magasin, 2,
                    temporaire);
            TaskExecutor.BuildResult résultat = exécuteur.execute();

            assertTrue(résultat.success(), "le build Kotlin doit réussir sur "
                    + "(simulé) ART — journal : " + événements.journalErreur
                    + " diagnostics : " + événements.diagnostics
                    + " états : " + événements.états);
            assertTrue(Files.isRegularFile(temporaire.resolve(
                            "build/kalc/classes/demo/Calculs.class")),
                    "la classe Kotlin est émise");
            assertTrue(Files.isRegularFile(temporaire.resolve(
                            "build/kalc/classes/demo/Principal.class")),
                    "la classe Java suit (ECJ contre les classes Kotlin)");
            assertTrue(Files.isRegularFile(temporaire.resolve("build/kalc/libs/kalc.jar")),
                    "le jar sort — arbre : " + arbre(temporaire));
        } finally {
            if (ancienNom != null) {
                System.setProperty("java.vm.name", ancienNom);
            } else {
                System.clearProperty("java.vm.name");
            }
        }
    }

    /** Arbre du projet pour les messages d'assertion (débogage honnête). */
    private static String arbre(Path racine) throws IOException {
        try (var parcours = Files.walk(racine.resolve("build"))) {
            return parcours.filter(Files::isRegularFile)
                    .map(p -> racine.relativize(p).toString())
                    .sorted().toList().toString();
        } catch (IOException sansBuild) {
            return "(pas de build/)";
        }
    }

    /** Enregistreur complet (miroir de JavaPipelineTest) : états, journal,
     * diagnostics et tâche fautive — l'exécuteur lit la tâche fautive via
     * dernierEchec(). */
    private static final class Enregisteur implements TaskExecutor.BuildEvents {

        final StringBuffer journalErreur = new StringBuffer();
        final List<String> états = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<jo.forge.engine.diag.Diagnostic> diagnostics =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        private final java.util.concurrent.atomic.AtomicReference<String> premierÉchec =
                new java.util.concurrent.atomic.AtomicReference<>();

        @Override
        public String buildId() {
            return "simulation-art";
        }

        @Override
        public void taskState(jo.forge.api.BuildEvent.TaskStateEvent event) {
            états.add(event.state() + " " + event.taskName());
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
