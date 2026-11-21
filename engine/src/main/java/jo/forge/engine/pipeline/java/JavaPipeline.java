package jo.forge.engine.pipeline.java;

import jo.forge.engine.model.ForgeModel;
import jo.forge.engine.model.ForgeModule;
import jo.forge.engine.model.ModuleDependency;
import jo.forge.engine.model.ModuleType;
import jo.forge.engine.model.ModelException;
import jo.forge.engine.pipeline.ProjectLayout;
import jo.forge.engine.pipeline.android.AndroidToolsLocator;
import jo.forge.engine.tasks.Task;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Pipelines de build : transformation du modèle déclaratif en tâches (décision
 * D-11). Point d'entrée unique : chaque module reçoit la chaîne de son type.
 *
 * <p>Chaîne par module {@code java-library} : {@code resolveDependencies} (Maven,
 * étape 9), {@code compileJava} puis {@code jar}, câblées par les dépendances de
 * projet. La sémantique des portées est celle des bibliothèques Java Gradle : {@code api}
 * se transmet aux consommateurs, {@code implementation} reste locale,
 * {@code compileOnly}/{@code runtimeOnly} ne dépassent pas la compilation/l'exécution
 * du module.</p>
 *
 * <p>Les modules Android ({@code application}, {@code android-library}) reçoivent la
 * chaîne du pipeline Android (étape 10) : {@code application} va jusqu'à l'APK signé
 * (aapt2, d8, packaging, signature), {@code android-library} compile ses classes
 * contre {@code android.jar} (v1 — voir {@code AndroidPipeline}).</p>
 *
 * <p><b>Bibliothèque de plate-forme sur ART</b> (correctif v0.2.7) : un module
 * {@code java-library} compilé sur l'ART d'un appareil reçoit lui AUSSI
 * {@code android.jar} en bibliothèque de plate-forme. Sans elle, ECJ ne trouve
 * aucune classe {@code java.*} (le runtime expose des dex, pas un jimage) et
 * échoue sur {@code java.lang.Object} — le constat appareil v0.2.6. Sur une JVM
 * de bureau, la plate-forme reste vide : ECJ emprunte les classes de l'hôte,
 * sémantique JDK d'une bibliothèque Java.</p>
 */
public final class JavaPipeline {

    private JavaPipeline() {
    }

    /**
     * Construit les tâches de tous les modules du projet.
     *
     * @param model       modèle validé.
     * @param projectRoot racine du projet.
     * @return les tâches, dans un ordre quelconque (le graphe les ordonne).
     */
    public static List<Task> tasksFor(ForgeModel model, Path projectRoot) {
        return tasksFor(model, projectRoot, false, "arm64-v8a", null);
    }

    /**
     * Construit les tâches de tous les modules du projet, avec le mode réseau.
     *
     * @param model       modèle validé.
     * @param projectRoot racine du projet.
     * @param horsLigne   mode hors ligne : la résolution n'utilise que le cache.
     * @return les tâches, dans un ordre quelconque (le graphe les ordonne).
     */
    public static List<Task> tasksFor(ForgeModel model, Path projectRoot,
                                      boolean horsLigne) {
        return tasksFor(model, projectRoot, horsLigne, "arm64-v8a", null);
    }

    /**
     * Construit les tâches de tous les modules du projet, avec le mode réseau et
     * l'ABI de l'appareil (résolution du binaire aapt2 des modules Android).
     *
     * @param model       modèle validé.
     * @param projectRoot racine du projet.
     * @param horsLigne   mode hors ligne : la résolution n'utilise que le cache.
     * @param abi         ABI de l'appareil (ex. {@code arm64-v8a}).
     * @return les tâches, dans un ordre quelconque (le graphe les ordonne).
     */
    public static List<Task> tasksFor(ForgeModel model, Path projectRoot,
                                      boolean horsLigne, String abi) {
        return tasksFor(model, projectRoot, horsLigne, abi, null);
    }

    /**
     * Construit les tâches de tous les modules du projet, forme complète.
     *
     * @param model       modèle validé.
     * @param projectRoot racine du projet.
     * @param horsLigne   mode hors ligne : la résolution n'utilise que le cache.
     * @param abi         ABI de l'appareil (ex. {@code arm64-v8a}).
     * @param engineHome  répertoire du moteur installé (outils Android embarqués :
     *                   aapt2 par ABI, android.jar par API), ou {@code null} sur
     *                   machine de développement (SDK local).
     * @return les tâches, dans un ordre quelconque (le graphe les ordonne).
     */
    public static List<Task> tasksFor(ForgeModel model, Path projectRoot,
                                      boolean horsLigne, String abi, Path engineHome) {
        return tasksFor(model, projectRoot, horsLigne, abi, engineHome,
                EcjCompiler.surArt());
    }

    /**
     * Construit les tâches de tous les modules du projet, forme complète avec
     * détection ART injectée (tests : la plate-forme des bibliothèques Java
     * n'est câblée que sur ART).
     *
     * @param model       modèle validé.
     * @param projectRoot racine du projet.
     * @param horsLigne   mode hors ligne : la résolution n'utilise que le cache.
     * @param abi         ABI de l'appareil (ex. {@code arm64-v8a}).
     * @param engineHome  répertoire du moteur installé (outils Android embarqués :
     *                   aapt2 par ABI, android.jar par API), ou {@code null} sur
     *                   machine de développement (SDK local).
     * @param surArt      le moteur tourne sur l'ART d'un appareil.
     * @return les tâches, dans un ordre quelconque (le graphe les ordonne).
     */
    static List<Task> tasksFor(ForgeModel model, Path projectRoot,
                               boolean horsLigne, String abi, Path engineHome,
                               boolean surArt) {
        Map<String, ForgeModule> parNom = model.modules().stream()
                .collect(Collectors.toMap(ForgeModule::name, Function.identity()));

        // Plate-forme des modules sans facette Android : la plus haute facette
        // du projet (cohérence avec l'application), sinon la plus haute
        // plate-forme installée, sinon celle de la publication.
        List<Path> plateformeJava = surArt
                ? List.of(AndroidToolsLocator.androidJar(
                        apiCible(model, engineHome), engineHome))
                : List.of();

        List<Task> tasks = new ArrayList<>();
        for (ForgeModule module : model.modules()) {
            if (module.type() != ModuleType.JAVA_LIBRARY) {
                tasks.addAll(jo.forge.engine.pipeline.android.AndroidPipeline
                        .tâchesDe(module, model, projectRoot, horsLigne, abi, engineHome));
                continue;
            }
            ProjectLayout layout = new ProjectLayout(projectRoot);
            // La tâche de compilation d'une dépendance porte SON langage : un
            // module Kotlin dépend des classes du compileKotlin de son frère.
            List<String> dependances = new ArrayList<>();
            for (ModuleDependency.ProjectDependency dep : CompileJavaTask.compileDeps(module)) {
                ForgeModule frère = parNom.get(dep.module());
                dependances.add(frère != null && frère.estKotlin()
                        ? frère.kotlinCompileTaskName() : dep.module() + ":compileJava");
            }
            LinkedHashSet<Path> classpath = new LinkedHashSet<>();
            fermetureCompilationPublique(module, parNom, classpath,
                    new HashSet<>(), projectRoot);

            ResolveDependenciesTask resolve = new ResolveDependenciesTask(module, layout,
                    ResolveDependenciesTask.racinesDe(parNom, module),
                    model.repositories(), horsLigne);
            tasks.add(resolve);
            if (module.estKotlin()) {
                // Module Kotlin (v0.2.9) : le Kotlin compile d'abord, le Java du
                // module compile ensuite CONTRE ses classes (même répertoire de
                // sortie — le jar n'y voit qu'un module). Un module sans source
                // .kt passe sa compileKotlin sans rien faire.
                tasks.add(new KotlinCompilerTask(module, layout, dependances,
                        new ArrayList<>(classpath), plateformeJava,
                        resolve.cheminClasspath()));
                List<String> dependancesJava = new ArrayList<>(dependances);
                dependancesJava.add(module.kotlinCompileTaskName());
                List<Path> classpathJava = new ArrayList<>(classpath);
                classpathJava.add(layout.classesDir(module.name()));
                tasks.add(new CompileJavaTask(module, layout, dependancesJava,
                        classpathJava, List.of(), plateformeJava,
                        resolve.cheminClasspath()));
            } else {
                tasks.add(new CompileJavaTask(module, layout, dependances,
                        new ArrayList<>(classpath), List.of(), plateformeJava,
                        resolve.cheminClasspath()));
            }
            tasks.add(new JarTask(module, layout));
        }
        return tasks;
    }

    /**
     * Niveau d'API de la plate-forme de compilation des modules sans facette
     * Android : le {@code targetSdk} le plus élevé du projet s'il en a un, sinon
     * la plus haute plate-forme installée dans le moteur, sinon celle de la
     * publication (34 à ce jour).
     */
    private static int apiCible(ForgeModel model, Path engineHome) {
        int desFacettes = model.modules().stream()
                .flatMap(module -> module.android().stream())
                .mapToInt(facette -> facette.targetSdk())
                .max().orElse(-1);
        if (desFacettes > 0) {
            return desFacettes;
        }
        int installée = AndroidToolsLocator.plateformeLaPlusHaute(engineHome);
        return installée > 0 ? installée : 34;
    }

    /**
     * Classpath de compilation d'un module : classes des dépendances directes visibles
     * à la compilation, puis fermeture des dépendances {@code api} transitives (les
     * seules exportées au-delà du module qui les déclare).
     *
     * <p>Ordre stable (insertion), sans doublon. Garde anti-réentrance : un modèle
     * dégénéré ne peut pas boucler ici (les cycles de modules sont de toute façon
     * rejetés par le moteur de tâches).</p>
     *
     * @param module    module dont on calcule le classpath.
     * @param parNom    index des modules par nom.
     * @param accu      accumulateur ordonné des répertoires de classes.
     * @param visites   modules déjà développés.
     * @param projectRoot racine du projet (conventions de chemins).
     */
    /**
     * Fermeture de classpath (visible du pipeline Android — même sémantique).
     */
    public static void fermetureCompilationPublique(ForgeModule module,
            Map<String, ForgeModule> parNom, LinkedHashSet<Path> accu,
            Set<String> visites, Path projectRoot) {
        fermetureCompilation(module, parNom, accu, visites, projectRoot);
    }

    private static void fermetureCompilation(ForgeModule module,
            Map<String, ForgeModule> parNom, LinkedHashSet<Path> accu,
            Set<String> visites, Path projectRoot) {
        if (!visites.add(module.name())) {
            return;
        }
        for (ModuleDependency.ProjectDependency dep : CompileJavaTask.compileDeps(module)) {
            ForgeModule cible = parNom.get(dep.module());
            accu.add(new ProjectLayout(projectRoot).classesDir(cible.name()));
            // Les dépendances exportées par la cible (api) deviennent visibles aussi.
            fermetureApi(cible, parNom, accu, visites, projectRoot);
        }
    }

    /**
     * Développe uniquement les dépendances {@code api} d'un module (celles qui se
     * transmettent aux consommateurs).
     */
    private static void fermetureApi(ForgeModule module, Map<String, ForgeModule> parNom,
            LinkedHashSet<Path> accu, Set<String> visites, Path projectRoot) {
        if (!visites.add(module.name())) {
            return;
        }
        for (ModuleDependency dep : module.dependencies()) {
            if (dep instanceof ModuleDependency.ProjectDependency projet
                    && projet.scope().exported()) {
                ForgeModule cible = parNom.get(projet.module());
                accu.add(new ProjectLayout(projectRoot).classesDir(cible.name()));
                fermetureApi(cible, parNom, accu, visites, projectRoot);
            }
        }
    }
}
