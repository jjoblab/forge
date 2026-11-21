package jo.forge.engine.pipeline.android;

import jo.forge.engine.model.ForgeModule;
import jo.forge.engine.pipeline.ProjectLayout;
import jo.forge.engine.pipeline.java.ClasspathFile;
import jo.forge.engine.tasks.Task;
import jo.forge.engine.tasks.TaskContext;
import jo.forge.engine.tasks.TaskIO;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * <p><b>Ressources des AAR</b> (v0.2.15, Compose) : les {@code res/} des AAR du
 * classpath d'exécution sont compilés AVANT ceux du module et passés à la liaison
 * dans le même ordre (les ressources de l'application ont priorité — le motif
 * {@code --auto-add-overlay}) ; les paquets des manifestes d'AAR rejoignent
 * {@code --extra-packages}, qui génère le {@code R} de CHAQUE bibliothèque avec
 * les identifiants finaux de l'application (les classes d'un AAR référencent
 * leur {@code R} propre) ; les manifestes d'AAR sont FUSIONNÉS dans celui de
 * l'application (voir {@link FusionManifestes} — sans les
 * {@code <provider>}/{@code <service>} des bibliothèques, l'APK plante) ; les
 * assets d'AAR sont liés comme ceux du module. Les AAR runtime-seulement
 * (dépendances {@code implementation} d'un module frère bibliothèque)
 * contribuent aussi : AGP fusionne depuis le classpack d'exécution.</p>
 *
 * <p>Les diagnostics aapt2 (fichier/ligne/colonne) sont convertis en diagnostics
 * structurés ; l'échec du binaire lui-même est distingué de l'échec des entrées par
 * l'autotest intégré à {@link Aapt2Tool}.</p>
 */
public final class ProcessResourcesTask implements Task {

    /** Version du code de cette tâche. */
    static final String TASK_VERSION = "2";

    private final ForgeModule module;
    private final ProjectLayout layout;
    private final Aapt2Tool aapt2;
    private final Path androidJar;
    private final Path classpathFile;

    /**
     * Crée la tâche.
     *
     * @param module     module Android (facette obligatoire).
     * @param layout     disposition du projet.
     * @param aapt2      outil aapt2 résolu (composant installé, SDK ou test).
     * @param androidJar jar de la plate-forme cible.
     * @param classpathFile fichier de classpath Maven résolu (parties AAR).
     */
    public ProcessResourcesTask(ForgeModule module, ProjectLayout layout, Aapt2Tool aapt2,
                                Path androidJar, Path classpathFile) {
        this.module = module;
        this.layout = layout;
        this.aapt2 = aapt2;
        this.androidJar = androidJar;
        this.classpathFile = classpathFile;
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
        return new TaskIO(List.of(classpathFile), entrées,
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
        List<ClasspathFile.Entrée> aars = aars(context);
        List<Path> archives = new ArrayList<>();
        int index = 0;
        // 1. Les ressources des AAR d'abord (priorité basse) : chaque res/
        // compilé séparément, ordre du classpath résolu.
        for (ClasspathFile.Entrée aar : aars) {
            Path res = aar.exploded().resolve("res");
            if (!Files.isDirectory(res)) {
                continue;
            }
            context.checkCancelled();
            Path archive = compilés.resolve("res-aar-" + index++ + ".zip");
            aapt2.compiler(res, archive, context.diagnostics());
            archives.add(archive);
        }
        // 2. Les ressources du module ensuite (priorité haute — elles
        // recouvrent celles des bibliothèques par --auto-add-overlay).
        for (Path resDir : répertoiresExistants(facette.resourceDirs())) {
            context.checkCancelled();
            Path archive = compilés.resolve("res-" + index++ + ".zip");
            aapt2.compiler(resDir, archive, context.diagnostics());
            archives.add(archive);
            context.logger().debug("ressources compilées : " + resDir.getFileName());
        }
        context.checkCancelled();
        // 3. Manifeste fusionné (bibliothèques) et paquets additionnels (R).
        List<Path> manifestesBibliothèques = new ArrayList<>();
        Set<String> paquetsSupplémentaires = new LinkedHashSet<>();
        for (ClasspathFile.Entrée aar : aars) {
            Path manifeste = aar.exploded().resolve("AndroidManifest.xml");
            if (Files.isRegularFile(manifeste)) {
                manifestesBibliothèques.add(manifeste);
                paquetDe(manifeste).ifPresent(paquetsSupplémentaires::add);
            }
        }
        Path manifesteFusionné = layout.buildDir(module.name())
                .resolve("merged").resolve("AndroidManifest.xml");
        int ajouts = FusionManifestes.fusionner(manifeste(), manifestesBibliothèques,
                manifesteFusionné);
        if (ajouts > 0) {
            context.logger().info(ajouts + " entrée(s) de manifeste ajoutée(s) par "
                    + manifestesBibliothèques.size() + " bibliothèque(s) AAR");
        }
        // 4. Les assets : ceux des AAR, puis ceux du module.
        List<Path> assets = new ArrayList<>();
        for (ClasspathFile.Entrée aar : aars) {
            Path dir = aar.exploded().resolve("assets");
            if (Files.isDirectory(dir)) {
                assets.add(dir);
            }
        }
        assets.addAll(répertoiresExistants(facette.assetDirs()));
        aapt2.lier(archives, manifesteFusionné, androidJar, facette.namespace(),
                List.copyOf(paquetsSupplémentaires), facette.minSdk(),
                facette.targetSdk(), apkBase(), sourcesR(), assets,
                context.diagnostics());
        context.logger().info(archives.size() + " répertoire(s) de ressources liés -> "
                + apkBase().getFileName() + (paquetsSupplémentaires.isEmpty() ? ""
                : " (+" + paquetsSupplémentaires.size() + " paquet(s) de bibliothèque)"));
    }

    /** Les entrées AAR du classpath d'exécution (celles qui sont packagées). */
    private List<ClasspathFile.Entrée> aars(TaskContext context) throws IOException {
        if (!Files.isRegularFile(classpathFile)) {
            return List.of();
        }
        List<ClasspathFile.Entrée> aars = new ArrayList<>();
        for (ClasspathFile.Entrée entrée : ClasspathFile.lireEntréesExécution(
                classpathFile)) {
            if (entrée.estAar() && Files.isDirectory(entrée.exploded())) {
                aars.add(entrée);
            }
        }
        return aars;
    }

    /** Le paquet d'un manifeste d'AAR (attribut package). */
    private static java.util.Optional<String> paquetDe(Path manifeste) {
        try {
            String texte = Files.readString(manifeste, java.nio.charset.StandardCharsets.UTF_8);
            java.util.regex.Matcher chercheur = java.util.regex.Pattern.compile(
                    "<manifest\\b[^>]*\\bpackage\\s*=\\s*\"([^\"]+)\"").matcher(texte);
            return chercheur.find()
                    ? java.util.Optional.of(chercheur.group(1))
                    : java.util.Optional.empty();
        } catch (IOException illisible) {
            return java.util.Optional.empty();
        }
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
