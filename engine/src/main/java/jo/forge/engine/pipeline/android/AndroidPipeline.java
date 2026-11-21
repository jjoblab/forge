package jo.forge.engine.pipeline.android;

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
 * {@code compileKotlin} pour un module Kotlin (v0.2.11 : compilation jointe,
 * R.java compris) → {@code compileJava} (ECJ, R générées sur le chemin des
 * sources, {@code android.jar} au classpath) →
 * {@code dex} (d8 en processus) → {@code packageApk} (assemblage déterministe) →
 * {@code signApk} (v1+v2, clé de débogage du projet).</p>
 */
public final class AndroidPipeline {

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
                ResolveDependenciesTask.racinesDe(parNom, module),
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
            Path androidJar = AndroidToolsLocator.androidJar(
                    module.android().orElseThrow().targetSdk(), engineHome);
            List<String> depsJava = new ArrayList<>(dépendancesCompilation);
            List<Path> classpathJava = new ArrayList<>(classesDépendances);
            if (module.estKotlin()) {
                tâches.add(new KotlinCompilerTask(module, layout,
                        dépendancesCompilation, classesDépendances,
                        List.of(androidJar), resolve.cheminClasspath(),
                        List.of(), jarKotlinCompilateur, jarPluginCompose));
                depsJava.add(module.kotlinCompileTaskName());
                // Le Java du module compile CONTRE les classes Kotlin fraîches
                // (même répertoire de sortie — le jar n'y voit qu'un module).
                // Cette entrée reste HORS classesDépendances : le dexeur embarque
                // déjà le répertoire propre du module, la doubler ferait échouer
                // d8 sur « defined multiple times ».
                classpathJava.add(layout.classesDir(module.name()));
            }
            tâches.add(new CompileJavaTask(module, layout, depsJava,
                    classpathJava, List.of(), List.of(androidJar),
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

        List<String> depsCompilation = new ArrayList<>(dépendancesCompilation);
        depsCompilation.add(ressources.name());
        List<Path> classpathJava = new ArrayList<>(classesDépendances);
        if (module.estKotlin()) {
            // Kotlin d'application (v0.2.11) : compileKotlin suit processResources
            // — le R.java généré participe à la compilation jointe — et précède
            // compileJava ; le Kotlin et le Java partagent le répertoire de
            // classes, le dexeur n'y voit qu'un module.
            tâches.add(new KotlinCompilerTask(module, layout, depsCompilation,
                    classesDépendances, List.of(androidJar), resolve.cheminClasspath(),
                    List.of(ressources.sourcesR()), jarKotlinCompilateur,
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
                classpathJava, List.of(ressources.sourcesR()),
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
