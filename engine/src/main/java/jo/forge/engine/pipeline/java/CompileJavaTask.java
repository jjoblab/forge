package jo.forge.engine.pipeline.java;

import jo.forge.engine.model.DependencyScope;
import jo.forge.engine.model.ForgeModule;
import jo.forge.engine.model.ModuleDependency;
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
 * Tâche {@code <module>:compileJava} : compile les sources Java du module avec le
 * compilateur embarqué, classpath des modules frères en entrée.
 *
 * <p>Entrées de l'oracle d'up-to-date : répertoires sources du module (contenu haché
 * récursivement), répertoires de classes des dépendances (le changement d'une classe
 * amont invalide la compilation — c'est l'incrémentalité par contenu, décision D-12),
 * niveau Java, version du compilateur. Sortie : le répertoire de classes.</p>
 *
 * <p>Les diagnostics de compilation (erreurs localisées fichier/ligne/colonne) sont
 * déposés dans le puits du contexte AVANT l'échec : l'éditeur les reçoit même si la
 * tâche échoue.</p>
 */
public final class CompileJavaTask implements Task {

    /** Version du code de cette tâche (invalide les passages précédents si elle change). */
    static final String TASK_VERSION = "1";

    private final ForgeModule module;
    private final ProjectLayout layout;
    private final List<String> compileDependencies;
    private final List<Path> classpathDirs;
    private final Path classpathFile;

    /**
     * Crée la tâche.
     *
     * @param module            module à compiler.
     * @param layout            disposition du projet.
     * @param compileDependencies noms des tâches {@code compileJava} des dépendances de
     *                            projet visibles à la compilation.
     * @param classpathDirs     répertoires de classes des dépendances de projet.
     * @param classpathFile     fichier de classpath Maven résolu (entrée d'empreinte et
     *                          source des jars à l'exécution).
     */
    public CompileJavaTask(ForgeModule module, ProjectLayout layout,
                           List<String> compileDependencies, List<Path> classpathDirs,
                           Path classpathFile) {
        this.module = module;
        this.layout = layout;
        this.compileDependencies = List.copyOf(compileDependencies);
        this.classpathDirs = List.copyOf(classpathDirs);
        this.classpathFile = classpathFile;
    }

    @Override
    public String name() {
        return module.compileTaskName();
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
        return new TaskIO(List.of(classpathFile), entrees,
                Map.of("release", Integer.toString(module.javaRelease()),
                        "compilateur", "ecj:" + EcjCompiler.compilerVersion()),
                List.of(layout.classesDir(module.name())), TASK_VERSION);
    }

    @Override
    public void execute(TaskContext context) throws IOException {
        context.checkCancelled();
        List<Path> sources = collecteSources();
        Path sortie = layout.classesDir(module.name());
        if (sources.isEmpty()) {
            Files.createDirectories(sortie);
            context.logger().info("aucune source Java — rien à compiler");
            return;
        }
        context.logger().debug(sources.size() + " fichiers à compiler");
        List<Path> classpath = new ArrayList<>(classpathDirs);
        if (Files.isRegularFile(classpathFile)) {
            classpath.addAll(ClasspathFile.lireJarsCompilation(classpathFile));
        }
        EcjCompiler.CompileResult resultat = EcjCompiler.compile(sources, classpath,
                sortie, module.javaRelease(), context.diagnostics());
        for (String ligne : resultat.consoleLines()) {
            context.logger().debug(ligne);
        }
        context.checkCancelled();
        if (!resultat.success()) {
            throw new IllegalStateException("échec de compilation de " + module.name()
                    + " : " + nombreErreurs(resultat) + " — voir les diagnostics");
        }
    }

    /**
     * Compte des erreurs pour le message d'échec (approximation volontaire : les
     * diagnostics détaillés sont déjà partis dans le puits).
     */
    private String nombreErreurs(EcjCompiler.CompileResult resultat) {
        long erreurs = resultat.consoleLines().stream()
                .filter(ligne -> ligne.contains("ERROR in"))
                .count();
        return erreurs > 0 ? erreurs + " erreur(s)" : "erreurs";
    }

    /**
     * Collecte les fichiers {@code .java} des répertoires sources, triés (déterminisme
     * des diagnostics et de l'ordre de compilation).
     */
    private List<Path> collecteSources() throws IOException {
        List<Path> fichiers = new ArrayList<>();
        for (Path dir : layout.existingSourceDirs(module)) {
            try (Stream<Path> parcours = Files.walk(dir)) {
                parcours.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".java"))
                        .forEach(fichiers::add);
            }
        }
        fichiers.sort(Path::compareTo);
        return fichiers;
    }

    /**
     * Dépendances de projet du module visibles à la compilation.
     *
     * @param module le module.
     * @return les dépendances de projet de portée compilation.
     */
    public static List<ModuleDependency.ProjectDependency> compileDeps(ForgeModule module) {
        return module.dependencies().stream()
                .filter(dep -> dep instanceof ModuleDependency.ProjectDependency)
                .map(dep -> (ModuleDependency.ProjectDependency) dep)
                .filter(dep -> dep.scope().onCompileClasspath())
                .toList();
    }

    /**
     * Portée exportée la plus large d'une dépendance pour la fermeture de classpath.
     *
     * @return toujours {@link DependencyScope#API} (les seules dépendances exportées).
     */
    static DependencyScope scopeExportee() {
        return DependencyScope.API;
    }
}
