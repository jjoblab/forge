package jo.forge.engine.pipeline.android;

import jo.forge.engine.model.AndroidFacet;
import jo.forge.engine.model.ForgeModule;
import jo.forge.engine.model.ModuleType;
import jo.forge.engine.pipeline.ProjectLayout;
import jo.forge.engine.tasks.Task;
import jo.forge.engine.tasks.TaskContext;
import jo.forge.engine.tasks.TaskIO;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Tâche {@code <module>:generateBuildConfig} (v0.2.18) : écrit la classe
 * {@code <namespace>.BuildConfig} du module — les constantes de build qu'AGP
 * génère ({@code DEBUG}, {@code APPLICATION_ID} ou
 * {@code LIBRARY_PACKAGE_NAME}, {@code VERSION_CODE}, {@code VERSION_NAME})
 * puis les champs personnalisés ({@code buildConfigFields} du modèle,
 * sémantique AGP : la valeur est l'expression Java écrite telle quelle).
 *
 * <p>Gated sur {@code buildFeatures.buildConfig} (défaut : désactivé, position
 * d'AGP 8). La source générée est une FONCTION PURE du modèle (pas des
 * entrées du disque) : la signature de la tâche est la source ELLE-MÊME en
 * propriété — un champ, une version ou un type de build modifié la
 * régénère, rien d'autre.</p>
 *
 * <p>Sortie : {@code build/<module>/generated/buildConfig} — une racine de
 * sources Java qui rejoint la compilation jointe Kotlin (parsée) et ECJ
 * (compilée), comme les sources R.</p>
 */
public final class GenerateBuildConfigTask implements Task {

    /** Version du code de cette tâche. */
    static final String TASK_VERSION = "1";

    private final ForgeModule module;
    private final ProjectLayout layout;
    private final boolean application;

    /**
     * Crée la tâche.
     *
     * @param module module Android (application ou bibliothèque).
     * @param layout disposition du projet.
     */
    public GenerateBuildConfigTask(ForgeModule module, ProjectLayout layout) {
        this.module = module;
        this.layout = layout;
        this.application = module.type() == ModuleType.APPLICATION;
    }

    @Override
    public String name() {
        return module.name() + ":generateBuildConfig";
    }

    @Override
    public TaskIO io() {
        AndroidFacet facette = module.android().orElseThrow();
        // La source générée EST l'empreinte : rien d'autre ne peut l'invalider.
        return new TaskIO(List.of(), List.of(),
                Map.of("source", source()),
                List.of(sortie()), TASK_VERSION);
    }

    @Override
    public void execute(TaskContext context) throws IOException {
        context.checkCancelled();
        Path sortie = sortie();
        régénérerProprement(sortie);
        AndroidFacet facette = module.android().orElseThrow();
        Path paquet = sortie.resolve(facette.namespace().replace('.', '/'));
        Files.createDirectories(paquet);
        Files.writeString(paquet.resolve("BuildConfig.java"), source());
        context.logger().info("BuildConfig généré -> "
                + facette.namespace() + ".BuildConfig ("
                + (champComptage()) + " champ(s))");
    }

    /** Le nombre de champs de la classe (journal). */
    private int champComptage() {
        AndroidFacet facette = module.android().orElseThrow();
        return 3 + (application ? 2 : 0)
                + facette.buildConfigFields().size();
    }

    /** Répertoire de sortie (racine de sources Java). */
    Path sortie() {
        return layout.buildDir(module.name()).resolve("generated")
                .resolve("buildConfig");
    }

    /** La source Java de la classe BuildConfig. */
    private String source() {
        AndroidFacet facette = module.android().orElseThrow();
        StringBuilder texte = new StringBuilder();
        texte.append("/**\n * Fichier généré automatiquement. NE PAS MODIFIER.\n */\n");
        texte.append("package ").append(facette.namespace()).append(";\n\n");
        texte.append("public final class BuildConfig {\n");
        // DEBUG n'est pas une constante repliée : le moteur ne produit que des
        // builds signés debug (signature de production consignée), la valeur
        // dit la vérité de CE build.
        texte.append("  public static final boolean DEBUG = "
                + "Boolean.parseBoolean(\"true\");\n");
        if (application) {
            texte.append("  public static final String APPLICATION_ID = ")
                    .append(quoter(facette.applicationIdEffectif())).append(";\n");
        } else {
            texte.append("  public static final String LIBRARY_PACKAGE_NAME = ")
                    .append(quoter(facette.namespace())).append(";\n");
        }
        texte.append("  public static final String BUILD_TYPE = \"debug\";\n");
        if (application) {
            texte.append("  public static final int VERSION_CODE = ")
                    .append(facette.versionCode()).append(";\n");
            texte.append("  public static final String VERSION_NAME = ")
                    .append(quoter(facette.versionName())).append(";\n");
        }
        for (AndroidFacet.BuildConfigField champ : facette.buildConfigFields()) {
            texte.append("  public static final ").append(champ.type())
                    .append(' ').append(champ.name()).append(" = ")
                    .append(champ.value()).append(";\n");
        }
        texte.append("}\n");
        return texte.toString();
    }

    /** Un littéral de chaîne Java, échappé. */
    private static String quoter(String valeur) {
        return "\"" + valeur.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** Vide le répertoire de sortie : un champ retiré ne doit pas survivre. */
    private static void régénérerProprement(Path sortie) throws IOException {
        if (!Files.isDirectory(sortie)) {
            return;
        }
        try (Stream<Path> parcours = Files.walk(sortie)) {
            parcours.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException échec) {
                            throw new java.io.UncheckedIOException(échec);
                        }
                    });
        }
    }
}
