package jo.forge.engine.pipeline.java;

import jo.forge.engine.model.ForgeModel;
import jo.forge.engine.model.ForgeModule;
import jo.forge.engine.model.ModuleDependency;
import jo.forge.engine.model.ModuleType;
import jo.forge.engine.model.ModelException;
import jo.forge.engine.pipeline.ProjectLayout;
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
 * Pipeline Java : transformation du modèle déclaratif en tâches de build (décision
 * D-11 — même principe qu'un greffon de système de build, sans son infrastructure).
 *
 * <p>Chaîne par module {@code java-library} : {@code resolveDependencies} (Maven,
 * étape 9), {@code compileJava} puis {@code jar}, câblées par les dépendances de
 * projet. La sémantique des portées est celle des bibliothèques Java Gradle : {@code api}
 * se transmet aux consommateurs, {@code implementation} reste locale,
 * {@code compileOnly}/{@code runtimeOnly} ne dépassent pas la compilation/l'exécution
 * du module.</p>
 *
 * <p>Étape 10 restante : les modules Android ({@code application},
 * {@code android-library}) sont refusés avec un message explicite — le pipeline Android
 * arrive à l'étape 10.</p>
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
     * @throws ModelException si un module exige le pipeline Android (étape 10).
     */
    public static List<Task> tasksFor(ForgeModel model, Path projectRoot) {
        return tasksFor(model, projectRoot, false);
    }

    /**
     * Construit les tâches de tous les modules du projet, avec le mode réseau.
     *
     * @param model       modèle validé.
     * @param projectRoot racine du projet.
     * @param horsLigne   mode hors ligne : la résolution n'utilise que le cache.
     * @return les tâches, dans un ordre quelconque (le graphe les ordonne).
     * @throws ModelException si un module exige le pipeline Android (étape 10).
     */
    public static List<Task> tasksFor(ForgeModel model, Path projectRoot,
                                      boolean horsLigne) {
        verifierPerimetre(model);
        Map<String, ForgeModule> parNom = model.modules().stream()
                .collect(Collectors.toMap(ForgeModule::name, Function.identity()));

        List<Task> tasks = new ArrayList<>();
        for (ForgeModule module : model.modules()) {
            ProjectLayout layout = new ProjectLayout(projectRoot);
            List<String> dependances = new ArrayList<>();
            for (ModuleDependency.ProjectDependency dep : CompileJavaTask.compileDeps(module)) {
                dependances.add(dep.module() + ":compileJava");
            }
            LinkedHashSet<Path> classpath = new LinkedHashSet<>();
            fermetureCompilation(module, parNom, classpath, new HashSet<>(), projectRoot);

            ResolveDependenciesTask resolve = new ResolveDependenciesTask(module, layout,
                    ResolveDependenciesTask.racinesDe(parNom, module),
                    model.repositories(), horsLigne);
            tasks.add(resolve);
            tasks.add(new CompileJavaTask(module, layout, dependances,
                    new ArrayList<>(classpath), resolve.cheminClasspath()));
            tasks.add(new JarTask(module, layout));
        }
        return tasks;
    }

    /**
     * Refuse ce que l'étape courante ne construit pas, avec le module et la raison.
     */
    private static void verifierPerimetre(ForgeModel model) {
        for (ForgeModule module : model.modules()) {
            if (module.type() != ModuleType.JAVA_LIBRARY) {
                throw new ModelException("Le module " + module.name() + " (type "
                        + module.type().wireName()
                        + ") exige le pipeline Android, livré à l'étape 10");
            }
        }
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
