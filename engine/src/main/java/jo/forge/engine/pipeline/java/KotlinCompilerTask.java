package jo.forge.engine.pipeline.java;

import jo.forge.engine.model.ForgeModule;
import jo.forge.engine.pipeline.ProjectLayout;
import jo.forge.engine.tasks.Task;
import jo.forge.engine.tasks.TaskContext;
import jo.forge.engine.tasks.TaskIO;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Tâche {@code <module>:compileKotlin} : compile les sources Kotlin du module avec
 * le compilateur embarqué (feuille de route langages, v0.2.9), classpath des
 * modules frères en entrée.
 *
 * <p>Ordre d'un module Kotlin : {@code compileKotlin} PUIS {@code compileJava} —
 * le Java du module compile contre les classes Kotlin fraîches (l'inverse — du
 * Kotlin qui référencerait du Java du même module — relève de la compilation
 * jointe, évolution consignée dans {@code docs/limites-et-suite.md}). La sortie
 * rejoint le MÊME répertoire de classes que la compilation Java : le jar et les
 * modules aval n'y voient qu'un seul module.</p>
 *
 * <p>Entrées de l'oracle d'up-to-date : répertoires sources du module (contenu
 * haché), répertoires de classes des dépendances, bibliothèque de plate-forme,
 * classpath Maven résolu, cible JVM, version du compilateur. Sortie : le
 * répertoire de classes (partagé avec {@code compileJava} — les deux tâches
 * écrivent des fichiers disjoints, {@code .class} de sources distinctes).</p>
 */
public final class KotlinCompilerTask implements Task {

    /** Version du code de cette tâche. */
    static final String TASK_VERSION = "1";

    private final ForgeModule module;
    private final ProjectLayout layout;
    private final List<String> compileDependencies;
    private final List<Path> classpathDirs;
    private final List<Path> plateforme;
    private final Path classpathFile;

    /**
     * Crée la tâche.
     *
     * @param module            module à compiler.
     * @param layout            disposition du projet.
     * @param compileDependencies noms des tâches de compilation des dépendances de
     *                            projet visibles à la compilation ({@code compileJava}
     *                            ou {@code compileKotlin} selon leur langage).
     * @param classpathDirs     répertoires de classes des dépendances de projet.
     * @param plateforme        bibliothèque de plate-forme ({@code android.jar}),
     *                          vide sur JVM de bureau.
     * @param classpathFile     fichier de classpath Maven résolu.
     */
    public KotlinCompilerTask(ForgeModule module, ProjectLayout layout,
                              List<String> compileDependencies, List<Path> classpathDirs,
                              List<Path> plateforme, Path classpathFile) {
        this.module = module;
        this.layout = layout;
        this.compileDependencies = List.copyOf(compileDependencies);
        this.classpathDirs = List.copyOf(classpathDirs);
        this.plateforme = List.copyOf(plateforme);
        this.classpathFile = classpathFile;
    }

    @Override
    public String name() {
        return module.kotlinCompileTaskName();
    }

    @Override
    public List<String> dependencies() {
        List<String> dépendances = new ArrayList<>(compileDependencies);
        dépendances.add(module.name() + ":resolveDependencies");
        return dépendances;
    }

    @Override
    public TaskIO io() {
        List<Path> entrees = new ArrayList<>(layout.existingSourceDirs(module));
        entrees.addAll(classpathDirs);
        List<Path> fichiers = new ArrayList<>(List.of(classpathFile));
        fichiers.addAll(plateforme);
        return new TaskIO(fichiers, entrees,
                Map.of("jvmTarget", Integer.toString(module.javaRelease()),
                        "compilateur", "kotlinc:" + KotlinCompiler.compilerVersion()),
                List.of(layout.classesDir(module.name())), TASK_VERSION);
    }

    @Override
    public void execute(TaskContext context) throws IOException {
        context.checkCancelled();
        List<Path> sources = collecteSources();
        if (sources.isEmpty()) {
            Files.createDirectories(layout.classesDir(module.name()));
            context.logger().info("aucune source Kotlin — rien à compiler");
            return;
        }
        context.logger().debug(sources.size() + " fichiers Kotlin à compiler");
        List<Path> classpath = new ArrayList<>(classpathDirs);
        if (Files.isRegularFile(classpathFile)) {
            classpath.addAll(ClasspathFile.lireJarsCompilation(classpathFile));
        }
        KotlinCompiler.CompileResult resultat = KotlinCompiler.compiler(sources,
                classpath, plateforme, layout.classesDir(module.name()),
                module.javaRelease(), context.diagnostics());
        for (String ligne : resultat.consoleLines()) {
            context.logger().debug(ligne);
        }
        context.checkCancelled();
        if (!resultat.success()) {
            throw new IllegalStateException("échec de compilation Kotlin de "
                    + module.name() + " : "
                    + (resultat.errorCount() > 0 ? resultat.errorCount() + " erreur(s)"
                    : "erreurs") + " — voir les diagnostics");
        }
    }

    /**
     * Collecte les fichiers {@code .kt} des répertoires sources, triés (déterminisme
     * des diagnostics et de l'ordre de compilation).
     */
    private List<Path> collecteSources() throws IOException {
        List<Path> fichiers = new ArrayList<>();
        for (Path dir : layout.existingSourceDirs(module)) {
            try (Stream<Path> parcours = Files.walk(dir)) {
                parcours.filter(Files::isRegularFile)
                        .filter(KotlinCompiler::estSourceKotlin)
                        .forEach(fichiers::add);
            }
        }
        fichiers.sort(Path::compareTo);
        return fichiers;
    }
}
