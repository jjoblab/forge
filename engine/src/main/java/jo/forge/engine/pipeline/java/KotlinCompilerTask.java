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
 * le Java du module compile contre les classes Kotlin fraîches. Depuis v0.2.11,
 * la compilation est JOINTE : les sources {@code .java} du module sont aussi
 * livrées à kotlinc (parsées pour la résolution de symboles — le Kotlin peut
 * référencer le Java du même module, le {@code R.java} d'une application
 * Android compris), sans jamais y être émises ; ECJ les compile ensuite contre
 * les classes Kotlin. La sortie rejoint le MÊME répertoire de classes que la
 * compilation Java : le jar et les modules aval n'y voient qu'un seul module.</p>
 *
 * <p>Entrées de l'oracle d'up-to-date : répertoires sources du module (contenu
 * haché — {@code .kt} ET {@code .java}, la compilation jointe dépend des deux),
 * répertoires de classes des dépendances, bibliothèque de plate-forme,
 * classpath Maven résolu, jar du composant kotlin-compiler (le jar dexé n'a
 * plus de manifeste — c'est le fichier qui invalide quand le composant
 * change), cible JVM, version du compilateur. Sortie : le répertoire de classes
 * (partagé avec {@code compileJava} — les deux tâches écrivent des fichiers
 * disjoints, {@code .class} de sources distinctes).</p>
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
    private final List<Path> racinesJavaAdditionnelles;
    private final Path jarCompilateur;

    /**
     * Crée la tâche.
     *
     * @param module            module à compiler.
     * @param layout            disposition du projet.
     * @param compileDependencies noms des tâches de compilation des dépendances de
     *                            projet visibles à la compilation ({@code compileJava}
     *                            ou {@code compileKotlin} selon leur langage, et
     *                            {@code processResources} pour une application
     *                            Android — le {@code R.java} participe à la
     *                            compilation jointe).
     * @param classpathDirs     répertoires de classes des dépendances de projet.
     * @param plateforme        bibliothèque de plate-forme ({@code android.jar}),
     *                          vide sur JVM de bureau sans plateforme.
     * @param classpathFile     fichier de classpath Maven résolu.
     * @param racinesJavaAdditionnelles répertoires de sources Java hors module
     *                            (ex. {@code R.java} généré d'une application
     *                            Android) à parser en compilation jointe.
     * @param jarCompilateur    jar du composant kotlin-compiler (maison Kotlin et
     *                            racine de configuration IntelliJ sur l'appareil),
     *                            ou null sur la JVM de développement.
     */
    public KotlinCompilerTask(ForgeModule module, ProjectLayout layout,
                              List<String> compileDependencies, List<Path> classpathDirs,
                              List<Path> plateforme, Path classpathFile,
                              List<Path> racinesJavaAdditionnelles,
                              Path jarCompilateur) {
        this.module = module;
        this.layout = layout;
        this.compileDependencies = List.copyOf(compileDependencies);
        this.classpathDirs = List.copyOf(classpathDirs);
        this.plateforme = List.copyOf(plateforme);
        this.classpathFile = classpathFile;
        this.racinesJavaAdditionnelles = List.copyOf(racinesJavaAdditionnelles);
        this.jarCompilateur = jarCompilateur;
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
        entrees.addAll(racinesJavaAdditionnelles);
        List<Path> fichiers = new ArrayList<>(List.of(classpathFile));
        fichiers.addAll(plateforme);
        if (jarCompilateur != null) {
            // Le jar dexé n'a plus de manifeste : compilerVersion() vaut
            // « inconnue » sur l'appareil — c'est le fichier qui invalide
            // l'up-to-date quand le composant change.
            fichiers.add(jarCompilateur);
        }
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
        List<Path> sourcesJava = collecteSourcesJava();
        context.logger().debug(sources.size() + " fichiers Kotlin à compiler"
                + (sourcesJava.isEmpty() ? ""
                        : " + " + sourcesJava.size() + " Java (compilation jointe)"));
        List<Path> classpath = new ArrayList<>(classpathDirs);
        if (Files.isRegularFile(classpathFile)) {
            classpath.addAll(ClasspathFile.lireJarsCompilation(classpathFile));
        }
        KotlinCompiler.CompileResult resultat = KotlinCompiler.compiler(sources,
                sourcesJava, classpath, plateforme, jarCompilateur,
                layout.classesDir(module.name()),
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
        return collecte(KotlinCompiler::estSourceKotlin, layout.existingSourceDirs(module));
    }

    /**
     * Collecte les fichiers {@code .java} du module ET des racines
     * additionnelles (compilation jointe : parsés par kotlinc pour la
     * résolution de symboles — le {@code R.java} d'une application Android
     * compris — jamais émis).
     */
    private List<Path> collecteSourcesJava() throws IOException {
        List<Path> racines = new ArrayList<>(layout.existingSourceDirs(module));
        racines.addAll(racinesJavaAdditionnelles);
        return collecte(KotlinCompiler::estSourceJava, racines);
    }

    /** Collecte triée et déterministe des fichiers d'un filtre sur des racines. */
    private static List<Path> collecte(java.util.function.Predicate<Path> filtre,
                                       List<Path> racines) throws IOException {
        List<Path> fichiers = new ArrayList<>();
        for (Path dir : racines) {
            try (Stream<Path> parcours = Files.walk(dir)) {
                parcours.filter(Files::isRegularFile)
                        .filter(filtre)
                        .forEach(fichiers::add);
            }
        }
        fichiers.sort(Path::compareTo);
        return fichiers;
    }
}
