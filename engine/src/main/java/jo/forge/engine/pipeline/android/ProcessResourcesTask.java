package jo.forge.engine.pipeline.android;

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

/**
 * Tâche {@code <module>:processResources} : compile les ressources Android par
 * {@code aapt2 compile}, puis lie le tout ({@code aapt2 link}) avec le manifeste et la
 * plate-forme — produit l'APK de base (ressources + manifeste) et les sources R du
 * module.
 *
 * <p>Granularité v1 : la tâche est l'unité d'incrémentalité (une ressource modifiée
 * re-compile le répertoire concerné et relance la liaison) — l'empreinte par contenu
 * évite tout travail quand rien ne change. La compilation incrémentale PAR FICHIER
 * (index source→sortie) reste l'amélioration A-9, non livrée (consignée).</p>
 *
 * <p>Les diagnostics aapt2 (fichier/ligne/colonne) sont convertis en diagnostics
 * structurés ; l'échec du binaire lui-même est distingué de l'échec des entrées par
 * l'autotest intégré à {@link Aapt2Tool}.</p>
 */
public final class ProcessResourcesTask implements Task {

    /** Version du code de cette tâche. */
    static final String TASK_VERSION = "1";

    private final ForgeModule module;
    private final ProjectLayout layout;
    private final Aapt2Tool aapt2;
    private final Path androidJar;

    /**
     * Crée la tâche.
     *
     * @param module     module Android (facette obligatoire).
     * @param layout     disposition du projet.
     * @param aapt2      outil aapt2 résolu (composant installé, SDK ou test).
     * @param androidJar jar de la plate-forme cible.
     */
    public ProcessResourcesTask(ForgeModule module, ProjectLayout layout, Aapt2Tool aapt2,
                                Path androidJar) {
        this.module = module;
        this.layout = layout;
        this.aapt2 = aapt2;
        this.androidJar = androidJar;
    }

    @Override
    public String name() {
        return module.name() + ":processResources";
    }

    @Override
    public List<String> dependencies() {
        return List.of(module.name() + ":resolveDependencies");
    }

    @Override
    public TaskIO io() {
        jo.forge.engine.model.AndroidFacet facette = module.android().orElseThrow();
        List<Path> entrées = new ArrayList<>();
        entrées.add(manifeste());
        entrées.addAll(répertoiresExistants(facette.resourceDirs()));
        entrées.addAll(répertoiresExistants(facette.assetDirs()));
        return new TaskIO(List.of(), entrées,
                Map.of("namespace", facette.namespace(),
                        "minSdk", Integer.toString(facette.minSdk()),
                        "targetSdk", Integer.toString(facette.targetSdk()),
                        "aapt2", aapt2.binaire().toString()),
                List.of(apkBase(), sourcesR()), TASK_VERSION);
    }

    @Override
    public void execute(TaskContext context)
            throws IOException, InterruptedException {
        context.checkCancelled();
        jo.forge.engine.model.AndroidFacet facette = module.android().orElseThrow();
        Path compilés = layout.buildDir(module.name()).resolve("compiled");
        List<Path> archives = new ArrayList<>();
        int index = 0;
        for (Path resDir : répertoiresExistants(facette.resourceDirs())) {
            context.checkCancelled();
            Path archive = compilés.resolve("res-" + index++ + ".zip");
            aapt2.compiler(resDir, archive, context.diagnostics());
            archives.add(archive);
            context.logger().debug("ressources compilées : " + resDir.getFileName());
        }
        context.checkCancelled();
        aapt2.lier(archives, manifeste(), androidJar, facette.namespace(),
                facette.minSdk(), facette.targetSdk(), apkBase(), sourcesR(),
                répertoiresExistants(facette.assetDirs()), context.diagnostics());
        context.logger().info(archives.size() + " répertoire(s) de ressources liés -> "
                + apkBase().getFileName());
    }

    /** Manifeste du module. */
    Path manifeste() {
        return layout.moduleDir(module.name())
                .resolve(module.android().orElseThrow().manifest());
    }

    /** APK de base (ressources + manifeste, sans dex). */
    Path apkBase() {
        return layout.buildDir(module.name()).resolve("linked").resolve("base.apk");
    }

    /** Répertoire des sources R générées. */
    Path sourcesR() {
        return layout.buildDir(module.name()).resolve("generated").resolve("r");
    }

    private List<Path> répertoiresExistants(List<String> chemins) {
        List<Path> résultat = new ArrayList<>();
        for (String chemin : chemins) {
            Path dir = layout.moduleDir(module.name()).resolve(chemin);
            if (Files.isDirectory(dir)) {
                résultat.add(dir);
            }
        }
        return résultat;
    }
}
