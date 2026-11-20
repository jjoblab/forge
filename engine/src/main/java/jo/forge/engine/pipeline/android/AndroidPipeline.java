package jo.forge.engine.pipeline.android;

import jo.forge.engine.model.ForgeModel;
import jo.forge.engine.model.ForgeModule;
import jo.forge.engine.model.ModuleDependency;
import jo.forge.engine.model.ModuleType;
import jo.forge.engine.pipeline.ProjectLayout;
import jo.forge.engine.pipeline.java.CompileJavaTask;
import jo.forge.engine.pipeline.java.JavaPipeline;
import jo.forge.engine.pipeline.java.JarTask;
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
 * {@code processResources} (aapt2 : APK de base + sources R) → {@code compileJava}
 * (ECJ, R générées sur le chemin des sources, {@code android.jar} au classpath) →
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
     * @return les tâches du module (le graphe les ordonne).
     */
    public static List<Task> tâchesDe(ForgeModule module, ForgeModel model,
                                      Path projectRoot, boolean horsLigne, String abi) {
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
            dépendancesCompilation.add(dep.module() + ":compileJava");
            classesDépendances.add(layout.classesDir(dep.module()));
        }

        if (module.type() == ModuleType.ANDROID_LIBRARY) {
            // V1 : une bibliothèque Android compile comme une bibliothèque Java
            // contre android.jar (pas de ressources, pas d'AAR — écart consigné).
            Path androidJar = AndroidToolsLocator.androidJar(
                    module.android().orElseThrow().targetSdk(), null);
            tâches.add(new CompileJavaTask(module, layout, dépendancesCompilation,
                    classesDépendances, List.of(), List.of(androidJar),
                    resolve.cheminClasspath()));
            tâches.add(new JarTask(module, layout));
            return tâches;
        }

        jo.forge.engine.model.AndroidFacet facette = module.android().orElseThrow();
        Aapt2Tool aapt2 = new Aapt2Tool(
                AndroidToolsLocator.aapt2(abi, null));
        Path androidJar = AndroidToolsLocator.androidJar(facette.targetSdk(), null);

        ProcessResourcesTask ressources = new ProcessResourcesTask(module, layout,
                aapt2, androidJar);
        tâches.add(ressources);

        List<String> depsCompilation = new ArrayList<>(dépendancesCompilation);
        depsCompilation.add(ressources.name());
        tâches.add(new CompileJavaTask(module, layout, depsCompilation,
                classesDépendances, List.of(ressources.sourcesR()),
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
