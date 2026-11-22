package jo.forge.engine.pipeline.java;

import jo.forge.api.BuildEvent;
import jo.forge.engine.deps.ConflictPolicy;
import jo.forge.engine.deps.Coordinate;
import jo.forge.engine.deps.DependencyResolver;
import jo.forge.engine.deps.ProgressionTéléchargement;
import jo.forge.engine.deps.ResolutionResultCache;
import jo.forge.engine.deps.ResolverCache;
import jo.forge.engine.model.DependencyScope;
import jo.forge.engine.model.ForgeModule;
import jo.forge.engine.model.ModuleDependency;
import jo.forge.engine.pipeline.ProjectLayout;
import jo.forge.engine.tasks.Task;
import jo.forge.engine.tasks.TaskContext;
import jo.forge.engine.tasks.TaskIO;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Tâche {@code <module>:resolveDependencies} : résout les dépendances Maven du module
 * (directes + exportées par les modules frères en portée {@code api}) dans le cache du
 * projet et écrit le fichier de classpath consommé par la compilation.
 *
 * <p>Entrées de l'oracle : la liste triée des dépendances à résoudre (coordonnées et
 * portées) et les dépôts — toute variation de {@code forge.json} à ce sujet change
 * l'empreinte. Sortie : le fichier de classpath (atomique). Les téléchargements vont
 * dans le cache Maven du projet ({@code .forge/deps}), jamais dans les sources.</p>
 *
 * <p>Politique de médiation : la plus récente gagne (comportement Gradle) —
 * l'échec-sur-conflit et la plus proche existent dans le résolveur et restent à
 * exposer dans le modèle (écart consigné).</p>
 */
public final class ResolveDependenciesTask implements Task {

    /** Version du code de cette tâche. */
    static final String TASK_VERSION = "1";

    private final ForgeModule module;
    private final ProjectLayout layout;
    private final List<DependencyResolver.RootDependency> racines;
    private final List<String> dépôts;
    private final boolean horsLigne;

    /**
     * Crée la tâche.
     *
     * @param module     module concerné.
     * @param layout     disposition du projet.
     * @param racines    dépendances à résoudre (directes + exportées par les frères).
     * @param dépôts     dépôts Maven du projet (URLs).
     * @param horsLigne  mode hors ligne (cache seul).
     */
    public ResolveDependenciesTask(ForgeModule module, ProjectLayout layout,
                                   List<DependencyResolver.RootDependency> racines,
                                   List<String> dépôts, boolean horsLigne) {
        this.module = module;
        this.layout = layout;
        this.racines = List.copyOf(racines);
        this.dépôts = List.copyOf(dépôts);
        this.horsLigne = horsLigne;
    }

    @Override
    public String name() {
        return module.name() + ":resolveDependencies";
    }

    @Override
    public List<String> dependencies() {
        return List.of();
    }

    @Override
    public TaskIO io() {
        Map<String, String> propriétés = new TreeMap<>();
        propriétés.put("dépôts", String.join("|", dépôts));
        racines.forEach(racine -> propriétés.put(
                "dép:" + racine.coordinate(),
                racine.scope().wireName()));
        return new TaskIO(List.of(), List.of(), propriétés,
                List.of(cheminClasspath()), TASK_VERSION);
    }

    @Override
    public void execute(TaskContext context) throws IOException {
        context.checkCancelled();
        if (racines.isEmpty()) {
            ClasspathFile.écrire(cheminClasspath(), List.of(), List.of());
            context.logger().info("aucune dépendance Maven à résoudre");
            return;
        }
        AtomicLong téléchargées = new AtomicLong();
        ProgressionTéléchargement progression = progressionVersÉvénements(context,
                téléchargées);
        DependencyResolver résolveur = new DependencyResolver(dépôts,
                new ResolverCache(layout.root().resolve(".forge").resolve("deps")),
                horsLigne,
                avertissement -> context.logger().warn(avertissement),
                module.type() == jo.forge.engine.model.ModuleType.APPLICATION
                        || module.type() == jo.forge.engine.model.ModuleType.ANDROID_LIBRARY,
                module.android().map(jo.forge.engine.model.AndroidFacet::targetSdk)
                        .orElse(-1),
                new ResolutionResultCache(layout.root().resolve(".forge")
                        .resolve("résolutions")),
                context.cancellation()::isCancelled,
                progression);
        context.checkCancelled();
        DependencyResolver.Result résultat = résolveur.resolve(module.name(), racines,
                ConflictPolicy.NEWEST);
        if (résolveur.dernièreDepuisCache()) {
            // Le graphe n'a pas été re-marché : sortie produite en relisant le cache
            // de résolution — l'état FROM_CACHE dit exactement ça à la coquille.
            context.marquerDepuisCache();
            context.logger().info(résultat.compileClasspath().size()
                    + " dépendance(s) relue(s) du cache de résolution — aucune requête"
                    + " réseau");
        }

        List<ClasspathFile.Entrée> compilation = new ArrayList<>();
        résultat.compileClasspath().forEach(artefact -> compilation.add(
                new ClasspathFile.Entrée(artefact.coordinate().toString(),
                        artefact.jar(), artefact.sha256(),
                        artefact.packaging(), artefact.exploded())));
        List<ClasspathFile.Entrée> exécution = new ArrayList<>();
        résultat.runtimeClasspath().forEach(artefact -> exécution.add(
                new ClasspathFile.Entrée(artefact.coordinate().toString(),
                        artefact.jar(), artefact.sha256(),
                        artefact.packaging(), artefact.exploded())));
        ClasspathFile.écrire(cheminClasspath(), compilation, exécution);
        long aars = compilation.stream().filter(ClasspathFile.Entrée::estAar).count();
        context.logger().info(compilation.size() + " dépendance(s) au classpath"
                + " de compilation, " + exécution.size() + " à l'exécution"
                + (aars > 0 ? " (dont " + aars + " AAR)" : "")
                + (téléchargées.get() > 0
                        ? ", " + téléchargées.get() + " téléchargée(s)"
                        : ", cache complet"));
    }

    /**
     * Adapte la progression des téléchargements en événements structurés vers la
     * coquille — chaque fichier vu passer, jamais de silence réseau long.
     */
    private static ProgressionTéléchargement progressionVersÉvénements(
            TaskContext context, AtomicLong téléchargées) {
        String buildId = context.buildId();
        Consumer<BuildEvent.DownloadEvent> puits = context.téléchargements();
        return new ProgressionTéléchargement() {
            @Override
            public void début(String ressource, long totalAttendu) {
                puits.accept(new BuildEvent.DownloadEvent(buildId,
                        System.currentTimeMillis(), ressource, 0, totalAttendu, false));
            }

            @Override
            public void avance(long lus, long total) {
                puits.accept(new BuildEvent.DownloadEvent(buildId,
                        System.currentTimeMillis(), "", lus, total, false));
            }

            @Override
            public void fin(String ressource, long octets) {
                téléchargées.incrementAndGet();
                puits.accept(new BuildEvent.DownloadEvent(buildId,
                        System.currentTimeMillis(), ressource, octets, octets, true));
            }
        };
    }

    /**
     * Chemin du fichier de classpath du module.
     *
     * @return {@code build/<module>/resolved/classpath.json}.
     */
    public Path cheminClasspath() {
        return layout.root().resolve("build").resolve(module.name())
                .resolve("resolved").resolve("classpath.json");
    }

    /**
     * Racines de résolution d'un module : ses dépendances Maven directes (portées de
     * compilation et d'exécution) et les dépendances Maven exportées par ses modules
     * frères en portée {@code api}.
     *
     * @param modules   tous les modules du projet, indexés par nom.
     * @param module    le module à câbler.
     * @return les racines (sans doublon, ordre stable).
     */
    public static List<DependencyResolver.RootDependency> racinesDe(
            Map<String, ForgeModule> modules, ForgeModule module) {
        List<DependencyResolver.RootDependency> racines = new ArrayList<>();
        for (ModuleDependency dépendance : module.dependencies()) {
            if (dépendance instanceof ModuleDependency.MavenDependency maven) {
                racines.add(new DependencyResolver.RootDependency(
                        new Coordinate(maven.group(), maven.artifact(),
                                maven.version()), maven.scope()));
            }
        }
        exportedParFrères(modules, module, new java.util.HashSet<>(), racines);
        return racines;
    }

    /**
     * Ajoute les dépendances Maven exportées ({@code api}) par les modules frères dont
     * le module dépend, directement ou par export transitif.
     */
    private static void exportedParFrères(Map<String, ForgeModule> modules,
            ForgeModule module, java.util.Set<String> vus,
            List<DependencyResolver.RootDependency> racines) {
        if (!vus.add(module.name())) {
            return;
        }
        for (ModuleDependency dépendance : module.dependencies()) {
            if (dépendance instanceof ModuleDependency.ProjectDependency projet
                    && projet.scope().exported()) {
                ForgeModule frère = modules.get(projet.module());
                if (frère == null) {
                    continue;
                }
                for (ModuleDependency depFrère : frère.dependencies()) {
                    if (depFrère instanceof ModuleDependency.MavenDependency maven
                            && depFrère.scope().exported()) {
                        racines.add(new DependencyResolver.RootDependency(
                                new Coordinate(maven.group(), maven.artifact(),
                                        maven.version()), DependencyScope.API));
                    }
                }
                exportedParFrères(modules, frère, vus, racines);
            }
        }
    }
}
