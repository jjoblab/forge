package jo.forge.engine.pipeline.android;

import jo.forge.engine.deps.Coordinate;
import jo.forge.engine.deps.DependencyResolver;
import jo.forge.engine.model.ForgeModel;
import jo.forge.engine.model.ForgeModule;
import jo.forge.engine.model.ModuleDependency;
import jo.forge.engine.model.ModuleType;
import jo.forge.engine.pipeline.ProjectLayout;
import jo.forge.engine.pipeline.java.CompileJavaTask;
import jo.forge.engine.pipeline.java.JavaPipeline;
import jo.forge.engine.pipeline.java.JarTask;
import jo.forge.engine.pipeline.java.KotlinCompiler;
import jo.forge.engine.pipeline.java.KotlinCompilerTask;
import jo.forge.engine.pipeline.java.ResolveDependenciesTask;
import jo.forge.engine.tasks.Task;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Pipeline Android : chaîne complète d'un module {@code application} jusqu'à l'APK
 * signé, et chaîne réduite d'un module {@code android-library} (classes + jar — le
 * traitement des ressources de bibliothèque et l'AAR restent une évolution consignée).
 *
 * <p>Chaîne {@code application} : {@code resolveDependencies} →
 * {@code processResources} (aapt2 : APK de base + sources R) →
 * {@code generateBuildConfig}/{@code generateViewBinding} quand le module les
 * demande ({@code buildFeatures}, v0.2.18 — sémantique AGP 8 : opt-in) →
 * {@code compileKotlin} pour un module Kotlin (v0.2.11 : compilation jointe,
 * R.java et sources générées comprises) → {@code compileJava} (ECJ, R et
 * générées sur le chemin des sources, {@code android.jar} au classpath) →
 * {@code dex} (d8 en processus) → {@code packageApk} (assemblage déterministe) →
 * {@code signApk} (v1+v2, clé de débogage du projet).</p>
 */
public final class AndroidPipeline {

    /** Le runtime ViewBinding ajouté à la résolution quand la fonctionnalité
     * est demandée (AGP fait de même — dernière stable 8.x épinglée). */
    static final String RUNTIME_VIEWBINDING = "8.13.2";

    private AndroidPipeline() {
    }

    /**
     * Construit les tâches Android d'un module.
     *
     * @param module    module {@code application} ou {@code android-library}.
     * @param model     modèle complet (pour les dépendances).
     * @param projectRoot racine du projet.
     * @param horsLigne mode hors ligne pour la résolution.
     * @param abi       ABI de l'appareil (résolution du binaire aapt2).
     * @param engineHome répertoire du moteur installé (outils Android embarqués),
     *                   ou {@code null} (machine de développement : SDK local).
     * @return les tâches du module (le graphe les ordonne).
     */
    public static List<Task> tâchesDe(ForgeModule module, ForgeModel model,
                                      Path projectRoot, boolean horsLigne, String abi,
                                      Path engineHome) {
        Map<String, ForgeModule> parNom = model.modules().stream()
                .collect(Collectors.toMap(ForgeModule::name, Function.identity()));
        ProjectLayout layout = new ProjectLayout(projectRoot);
        List<Task> tâches = new ArrayList<>();

        ResolveDependenciesTask resolve = new ResolveDependenciesTask(module, layout,
                racinesAvecGénération(parNom, module),
                model.repositories(), horsLigne);
        tâches.add(resolve);

        List<String> dépendancesCompilation = new ArrayList<>();
        List<Path> classesDépendances = new ArrayList<>();
        for (ModuleDependency.ProjectDependency dep : CompileJavaTask.compileDeps(module)) {
            // La tâche d'un frère porte SON langage (kotlin → compileKotlin) —
            // un module Android peut être Kotlin depuis v0.2.11.
            ForgeModule frère = parNom.get(dep.module());
            dépendancesCompilation.add(frère != null && frère.estKotlin()
                    ? frère.kotlinCompileTaskName() : dep.module() + ":compileJava");
            classesDépendances.add(layout.classesDir(dep.module()));
        }
        Path jarKotlinCompilateur = KotlinCompiler.jarComposant(engineHome);
        Path jarPluginCompose = KotlinCompiler.jarComposePlugin(engineHome);

        if (module.type() == ModuleType.ANDROID_LIBRARY) {
            // V1 : une bibliothèque Android compile comme une bibliothèque Java
            // contre android.jar (pas de ressources, pas d'AAR — écart consigné).
            // La génération BuildConfig y est ADMISE (LIBRARY_PACKAGE_NAME) ;
            // ViewBinding y est refusé nettement — sans chaîne de ressources, la
            // classe générée ne compilerait pas (R.id introuvable).
            Path androidJar = AndroidToolsLocator.androidJar(
                    module.android().orElseThrow().targetSdk(), engineHome);
            List<String> depsJava = new ArrayList<>(dépendancesCompilation);
            List<Path> classpathJava = new ArrayList<>(classesDépendances);
            List<Path> générées = List.of();
            if (module.android().orElseThrow().buildFeatures().buildConfig()) {
                GenerateBuildConfigTask buildConfig =
                        new GenerateBuildConfigTask(module, layout);
                tâches.add(buildConfig);
                depsJava.add(buildConfig.name());
                générées = List.of(buildConfig.sortie());
            }
            if (module.android().orElseThrow().buildFeatures().viewBinding()) {
                throw new IllegalStateException("Module " + module.name()
                        + " : buildFeatures.viewBinding n'est pas livré pour une "
                        + "android-library (chaîne de ressources non livrée — "
                        + "voir limites-et-suite.md)");
            }
            if (module.estKotlin()) {
                tâches.add(new KotlinCompilerTask(module, layout,
                        dépendancesCompilation, classesDépendances,
                        List.of(androidJar), resolve.cheminClasspath(),
                        générées, jarKotlinCompilateur, jarPluginCompose));
                depsJava.add(module.kotlinCompileTaskName());
                // Le Java du module compile CONTRE les classes Kotlin fraîches
                // (même répertoire de sortie — le jar n'y voit qu'un module).
                // Cette entrée reste HORS classesDépendances : le dexeur embarque
                // déjà le répertoire propre du module, la doubler ferait échouer
                // d8 sur « defined multiple times ».
                classpathJava.add(layout.classesDir(module.name()));
            }
            tâches.add(new CompileJavaTask(module, layout, depsJava,
                    classpathJava, générées, List.of(androidJar),
                    resolve.cheminClasspath()));
            tâches.add(new JarTask(module, layout));
            return tâches;
        }

        jo.forge.engine.model.AndroidFacet facette = module.android().orElseThrow();
        Aapt2Tool aapt2 = new Aapt2Tool(
                AndroidToolsLocator.aapt2(abi, engineHome));
        Path androidJar = AndroidToolsLocator.androidJar(facette.targetSdk(), engineHome);

        ProcessResourcesTask ressources = new ProcessResourcesTask(module, layout,
                aapt2, androidJar, resolve.cheminClasspath());
        tâches.add(ressources);

        // Génération de code (v0.2.18, opt-in buildFeatures — position AGP 8) :
        // les sources générées rejoignent le chemin des sources des DEUX
        // compilateurs, comme les sources R.
        List<String> depsCompilation = new ArrayList<>(dépendancesCompilation);
        depsCompilation.add(ressources.name());
        List<Path> sourcesGénérées = new ArrayList<>();
        sourcesGénérées.add(ressources.sourcesR());
        if (facette.buildFeatures().buildConfig()) {
            GenerateBuildConfigTask buildConfig =
                    new GenerateBuildConfigTask(module, layout);
            tâches.add(buildConfig);
            depsCompilation.add(buildConfig.name());
            sourcesGénérées.add(buildConfig.sortie());
        }
        if (facette.buildFeatures().viewBinding()) {
            GenerateViewBindingTask viewBinding =
                    new GenerateViewBindingTask(module, layout);
            tâches.add(viewBinding);
            depsCompilation.add(viewBinding.name());
            sourcesGénérées.add(viewBinding.sortie());
        }
        List<Path> classpathJava = new ArrayList<>(classesDépendances);
        if (module.estKotlin()) {
            // Kotlin d'application (v0.2.11) : compileKotlin suit processResources
            // — le R.java généré participe à la compilation jointe — et précède
            // compileJava ; le Kotlin et le Java partagent le répertoire de
            // classes, le dexeur n'y voit qu'un module.
            tâches.add(new KotlinCompilerTask(module, layout, depsCompilation,
                    classesDépendances, List.of(androidJar), resolve.cheminClasspath(),
                    sourcesGénérées, jarKotlinCompilateur,
                    jarPluginCompose));
            depsCompilation.add(module.kotlinCompileTaskName());
            // Le Java du module compile CONTRE les classes Kotlin fraîches
            // (même répertoire de sortie). Cette entrée reste HORS
            // classesDépendances : le dexeur embarque déjà le répertoire propre
            // du module, la doubler ferait échouer d8 sur « defined multiple
            // times ».
            classpathJava.add(layout.classesDir(module.name()));
        }
        tâches.add(new CompileJavaTask(module, layout, depsCompilation,
                classpathJava, sourcesGénérées,
                List.of(androidJar), resolve.cheminClasspath()));

        DexTask dex = new DexTask(module, layout, classesDépendances,
                resolve.cheminClasspath(), androidJar);
        tâches.add(dex);

        PackageApkTask empaquetage = new PackageApkTask(module, layout, ressources, dex);
        tâches.add(empaquetage);
        tâches.add(new SignApkTask(module, layout, empaquetage));
        return tâches;
    }

    /**
     * Les racines de résolution du module, PLUS le runtime ViewBinding quand
     * la fonctionnalité est demandée (même comportement qu'AGP : la dépendance
     * arrive avec l'opt-in, le script du projet ne la porte pas) — sauf si le
     * projet la déclare déjà (sa version gagne).
     */
    private static List<DependencyResolver.RootDependency> racinesAvecGénération(
            Map<String, ForgeModule> parNom, ForgeModule module) {
        List<DependencyResolver.RootDependency> racines =
                ResolveDependenciesTask.racinesDe(parNom, module);
        if (module.android().isEmpty()
                || !module.android().orElseThrow().buildFeatures().viewBinding()) {
            return racines;
        }
        boolean déjàDéclarée = module.dependencies().stream()
                .filter(d -> d instanceof ModuleDependency.MavenDependency maven
                        && "androidx.databinding".equals(maven.group())
                        && "viewbinding".equals(maven.artifact()))
                .findFirst().isPresent();
        if (déjàDéclarée) {
            return racines;
        }
        List<DependencyResolver.RootDependency> avec = new ArrayList<>(racines);
        avec.add(new DependencyResolver.RootDependency(
                new Coordinate("androidx.databinding", "viewbinding",
                        RUNTIME_VIEWBINDING),
                jo.forge.engine.model.DependencyScope.IMPLEMENTATION));
        return avec;
    }

    /**
     * Fermeture de classpath d'un module (déléguée au pipeline Java — même sémantique
     * de portées pour les dépendances de projet).
     *
     * @param module      le module.
     * @param parNom      index des modules.
     * @param projectRoot racine du projet.
     * @return les répertoires de classes (sans doublon, ordre stable).
     */
    static LinkedHashSet<Path> fermeture(ForgeModule module,
            Map<String, ForgeModule> parNom, Path projectRoot) {
        LinkedHashSet<Path> classpath = new LinkedHashSet<>();
        JavaPipeline.fermetureCompilationPublique(module, parNom, classpath,
                new HashSet<>(), projectRoot);
        return classpath;
    }
}
