package jo.forge.engine.pipeline.java;

import jo.forge.engine.exec.TaskExecutor;
import jo.forge.engine.graph.TaskGraph;
import jo.forge.engine.model.ForgeModel;
import jo.forge.engine.model.ModelCodec;
import jo.forge.engine.pipeline.ProjectLayout;
import jo.forge.engine.state.FingerprintStore;
import jo.forge.engine.tasks.Task;
import jo.forge.engine.testutil.Projets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests de la chaîne Kotlin (feuille de route langages, v0.2.9) : câblage du
 * module Kotlin en tâches (compileKotlin AVANT compileJava, jar au bout), build
 * complet MIXTE — le Java du module compile CONTRE les classes Kotlin — avec
 * exécution réelle du résultat, et diagnostic structuré d'une erreur de
 * compilation Kotlin.
 *
 * <p>v0.2.11 : compilation JOINTE (le Kotlin voit le Java du même module),
 * maison Kotlin + racine de configuration IntelliJ explicites (correctif ART),
 * et message actionnable quand la stdlib manque au classpath.</p>
 *
 * <p>Le compilateur Kotlin vient du classpath de TEST (kotlin-compiler-embeddable) ;
 * sur l'appareil il vient du composant {@code kotlin-compiler} installé. Sans lui,
 * les tests de compilation sont IGNORÉS (assumeTrue), pas échoués.</p>
 */
class KotlinPipelineTest {

    @TempDir
    Path temporaire;

    @Test
    void câblageDUnModuleKotlin() throws IOException {
        projetKotlin();
        ForgeModel modèle = ModelCodec.read(temporaire);
        List<Task> tâches = JavaPipeline.tasksFor(modèle, temporaire);

        // 4 tâches : resolve, compileKotlin, compileJava, jar.
        assertEquals(4, tâches.size(), "resolve + compileKotlin + compileJava + jar : "
                + tâches.stream().map(Task::name).toList());
        Map<String, Task> parNom = tâches.stream()
                .collect(Collectors.toMap(Task::name, t -> t));
        assertEquals(List.of("kalc:resolveDependencies"),
                parNom.get("kalc:compileKotlin").dependencies(),
                "compileKotlin suit sa résolution");
        assertTrue(parNom.get("kalc:compileJava").dependencies()
                        .contains("kalc:compileKotlin"),
                "le Java compile APRÈS le Kotlin du même module");
        assertEquals(List.of("kalc:compileJava"),
                parNom.get("kalc:jar").dependencies());
    }

    @Test
    void buildCompletKotlinAvecJavaContreSesClasses() throws Exception {
        assumeTrue(KotlinCompiler.disponible(),
                "kotlin-compiler-embeddable requis sur le classpath de test");
        projetKotlin();

        Enregistreur événements = new Enregistreur();
        TaskExecutor.BuildResult résultat = exécuter(événements);
        assertTrue(résultat.success(),
                "le build mixte Kotlin+Java doit réussir : " + résultat
                        + " — diagnostics : " + événements.diagnostics.stream()
                        .map(jo.forge.engine.diag.Diagnostic::toLogLine).toList());

        // Le jar contient les classes DES DEUX langages (même répertoire de
        // sortie, un seul module pour l'aval).
        Path jar = temporaire.resolve("build/kalc/libs/kalc.jar");
        try (ZipFile archive = new ZipFile(jar.toFile())) {
            assertTrue(archive.getEntry("demo/Calculs.class") != null,
                    "la classe Kotlin est dans le jar");
            assertTrue(archive.getEntry("demo/Principal.class") != null,
                    "la classe Java est dans le jar");
        }

        // Exécution RÉELLE du résultat : le Java appelle l'objet Kotlin, la
        // bibliothèque standard Kotlin du classpath sert l'exécution. C'est la
        // preuve de bout en bout (compilation, classpath, bytecode).
        String sortie = exécuterJava(
                temporaire.resolve("build/kalc/classes"),
                "demo.Principal");
        assertTrue(sortie.contains("doubled=42"),
                "le Java exécute le Kotlin : " + sortie);
    }

    @Test
    void erreurDeCompilationKotlinDonneUnDiagnosticStructuré() throws Exception {
        assumeTrue(KotlinCompiler.disponible(),
                "kotlin-compiler-embeddable requis sur le classpath de test");
        projetKotlin();
        // Faute de syntaxe : « fun » mal formé.
        Projets.fichier(temporaire, "kalc/src/main/kotlin/demo/Calculs.kt", """
                package demo

                object Calculs {
                    fu double(x: Int): Int = x * 2
                }
                """);

        Enregistreur événements = new Enregistreur();
        TaskExecutor.BuildResult résultat = exécuter(événements);
        assertTrue(!résultat.success(), "la compilation doit échouer");

        // Un diagnostic structuré, localisé au fichier fautif, outil kotlinc.
        assertTrue(événements.diagnostics.stream().anyMatch(diagnostic ->
                        diagnostic.tool().equals(KotlinCompiler.TOOL)
                                && diagnostic.file().endsWith("Calculs.kt")
                                && diagnostic.line() > 0),
                "diagnostic localisé attendu, reçus : "
                        + événements.diagnostics.stream()
                        .map(diagnostic -> diagnostic.toLogLine()).toList());
    }

    @Test
    void moduleSansSourceKotlinPasseSansÉchec() throws Exception {
        // Un module déclaré kotlin mais Java pur : compileKotlin ne fait rien,
        // compileJava compile, le jar sort — la déclaration n'est jamais un piège.
        assumeTrue(KotlinCompiler.disponible(),
                "kotlin-compiler-embeddable requis sur le classpath de test");
        projetKotlin();
        Files.delete(temporaire.resolve("kalc/src/main/kotlin/demo/Calculs.kt"));
        Projets.fichier(temporaire, "kalc/src/main/java/demo/Principal.java", """
                package demo;

                public final class Principal {
                    public static void main(String[] args) {
                        System.out.println("java seul");
                    }
                }
                """);

        TaskExecutor.BuildResult résultat = exécuter(new Enregistreur());
        assertTrue(résultat.success(), "build sans .kt : " + résultat);
    }

    @Test
    void compilationJointeLeKotlinVoitLeJavaDuModule() throws Exception {
        assumeTrue(KotlinCompiler.disponible(),
                "kotlin-compiler-embeddable requis sur le classpath de test");
        // Le Kotlin référencE le Java du MÊME module (l'inverse du test précédent)
        // — impossible avant v0.2.11 : kotlinc ne voyait que les .kt.
        Projets.fichier(temporaire, "forge.json", """
                { "modelVersion": 1,
                  "repositories": [],
                  "modules": [
                    { "name": "kalc", "type": "java-library", "language": "kotlin" }
                  ] }
                """);
        Projets.fichier(temporaire, "kalc/src/main/java/demo/Devise.java", """
                package demo;

                public enum Devise {
                    EURO("\u20ac"),
                    DOLLAR("$");

                    private final String symbole;

                    Devise(String symbole) {
                        this.symbole = symbole;
                    }

                    public String symbole() {
                        return symbole;
                    }
                }
                """);
        Projets.fichier(temporaire, "kalc/src/main/kotlin/demo/Calculs.kt", """
                package demo

                object Calculs {
                    fun prixLibellé(montant: Int, devise: Devise): String =
                            "$montant${devise.symbole()}"
                }
                """);
        Projets.fichier(temporaire, "kalc/src/main/java/demo/Principal.java", """
                package demo;

                public final class Principal {
                    private Principal() { }

                    public static void main(String[] args) {
                        System.out.println(Calculs.INSTANCE.prixLibellé(42, Devise.EURO));
                    }
                }
                """);

        Enregistreur événements = new Enregistreur();
        TaskExecutor.BuildResult résultat = exécuter(événements);
        assertTrue(résultat.success(), "la compilation jointe doit réussir : " + résultat
                + " — diagnostics : " + événements.diagnostics.stream()
                .map(jo.forge.engine.diag.Diagnostic::toLogLine).toList());

        // Le Java n'est JAMAIS émis par kotlinc : Devise.class vient d'ECJ, après.
        Path classes = temporaire.resolve("build/kalc/classes");
        assertTrue(Files.isRegularFile(classes.resolve("demo/Calculs.class"))
                        && Files.isRegularFile(classes.resolve("demo/Devise.class"))
                        && Files.isRegularFile(classes.resolve("demo/Principal.class")),
                "les trois classes existent (Kotlin émise par kotlinc, Java par ECJ)");

        String sortie = exécuterJava(classes, "demo.Principal");
        assertTrue(sortie.contains("42\u20ac"),
                "le Kotlin a bien résolu le Java du même module : " + sortie);
    }

    @Test
    void maisonKotlinExpliciteDuComposantNeCassePasLaCompilation() throws Exception {
        assumeTrue(KotlinCompiler.disponible(),
                "kotlin-compiler-embeddable requis sur le classpath de test");
        projetKotlin();
        // Le composant kotlin-compiler tel que l'installateur le pose sur
        // l'appareil : components/kotlin-compiler/lib/kotlin-compiler.jar (ici la
        // copie du jar embarqué du classpath de test — les arguments -kotlin-home
        // et -Xintellij-plugin-root sont les mêmes, correctif v0.2.11).
        Path jarEmbarqué = KotlinCompiler.jarDeLaClasse(
                "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler");
        assertNotNull(jarEmbarqué,
                "le jar du compilateur doit être localisable sur le classpath de test");
        Path maison = temporaire.resolve("engines/0.2.11");
        Files.createDirectories(maison.resolve("components/kotlin-compiler/lib"));
        Files.copy(jarEmbarqué,
                maison.resolve("components/kotlin-compiler/lib/kotlin-compiler.jar"));

        ForgeModel modèle = ModelCodec.read(temporaire);
        List<Task> tâches = JavaPipeline.tasksFor(modèle, temporaire,
                false, "arm64-v8a", maison);
        TaskGraph graphe = TaskGraph.of(tâches);
        ProjectLayout disposition = new ProjectLayout(temporaire);
        FingerprintStore magasin = new FingerprintStore(disposition.stateDir(),
                temporaire, avertissement -> { });
        TaskExecutor exécuteur = new TaskExecutor(graphe, new Enregistreur(),
                new jo.forge.engine.tasks.CancellationFlag(), magasin, 2, temporaire);
        assertTrue(exécuteur.execute().success(),
                "le build doit réussir avec la maison et la racine explicites");
        assertTrue(Files.isRegularFile(temporaire.resolve("build/kalc/libs/kalc.jar")),
                "le jar sort malgré les arguments supplémentaires");
    }

    @Test
    void stdlibAbsenteEstDiteClairement() {
        // Le jar du moteur la porte → tout va bien.
        assertNull(KotlinCompiler.problèmeStdlib(Path.of("kotlin-compiler.jar"),
                List.of()));
        // Dépendance Maven du module au classpath → tout va bien (chemin appareil).
        assertNull(KotlinCompiler.problèmeStdlib(null,
                List.of(Path.of("cache/kotlin-stdlib-2.0.21.jar"))));
        // Nulle part (appareil, module sans dépendance) → message actionnable.
        String message = KotlinCompiler.problèmeStdlib(null,
                List.of(Path.of("components/platform-34/android.jar")));
        assertNotNull(message, "l'absence de stdlib doit être dite");
        assertTrue(message.contains("kotlin-stdlib"),
                "le message cite la dépendance à déclarer : " + message);
    }

    // ---- Harnais -----------------------------------------------------------

    /** Projet d'un module Kotlin, sources Kotlin ET Java (le Java appelle le Kotlin). */
    private void projetKotlin() throws IOException {
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
                }
                """);
        Projets.fichier(temporaire, "kalc/src/main/java/demo/Principal.java", """
                package demo;

                public final class Principal {
                    public static void main(String[] args) {
                        System.out.println("doubled=" + Calculs.INSTANCE.doubler(21));
                    }
                }
                """);
    }

    /** Exécute un build complet avec empreintes persistantes (harnais du pipeline Java). */
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

    /**
     * Lance la machine virtuelle de test sur les classes compilées : le classpath
     * ajoute le jar du compilateur embarqué et la bibliothèque standard Kotlin,
     * trouvés sur le classpath de test (l'exécution Kotlin exige la stdlib —
     * à l'usage réel, elle vient du résolveur Maven du module).
     */
    private static String exécuterJava(Path classes, String classePrincipale)
            throws IOException, InterruptedException {
        String exécutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        StringBuilder classpath = new StringBuilder(classes.toString());
        for (String entrée : System.getProperty("java.class.path")
                .split(java.io.File.pathSeparator)) {
            String nom = Path.of(entrée).getFileName().toString();
            if (nom.startsWith("kotlin-compiler-embeddable-")
                    || nom.startsWith("kotlin-stdlib-")) {
                classpath.append(java.io.File.pathSeparator).append(entrée);
            }
        }
        Process processus = new ProcessBuilder(exécutable, "-cp", classpath.toString(),
                classePrincipale).redirectErrorStream(true).start();
        String sortie = new String(processus.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        if (!processus.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) {
            processus.destroyForcibly();
            fail("l'exécution de " + classePrincipale + " a dépassé le délai");
        }
        return sortie;
    }

    /** Enregistreur d'événements (harnais du pipeline Java) — mémorise l'échec,
     * sans quoi l'exécuteur croit le build réussi (les tâches bloquées comptent
     * comme terminées et SONT des terminaisons légitimes). */
    private static final class Enregistreur implements TaskExecutor.BuildEvents {

        final Map<jo.forge.api.BuildEvent.TaskState, List<String>> états =
                new java.util.concurrent.ConcurrentHashMap<>();
        final List<jo.forge.engine.diag.Diagnostic> diagnostics =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        private final java.util.concurrent.atomic.AtomicReference<String> premierÉchec =
                new java.util.concurrent.atomic.AtomicReference<>();

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
