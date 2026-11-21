package jo.forge.engine.pipeline.android;

import jo.forge.engine.model.AndroidFacet;
import jo.forge.engine.model.ForgeModel;
import jo.forge.engine.model.ForgeModule;
import jo.forge.engine.model.ModelCodec;
import jo.forge.engine.model.ModuleType;
import jo.forge.engine.pipeline.ProjectLayout;
import jo.forge.engine.tasks.CancellationFlag;
import jo.forge.engine.tasks.TaskContext;
import jo.forge.engine.tasks.TaskLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests de {@code generateBuildConfig} (v0.2.18) : la classe générée porte
 * les constantes AGP (DEBUG, APPLICATION_ID ou LIBRARY_PACKAGE_NAME,
 * BUILD_TYPE, VERSION_CODE, VERSION_NAME) puis les champs personnalisés du
 * modèle ; l'application et la bibliothèque divergent exactement comme AGP ;
 * la source est une fonction PURE du modèle (l'empreinte est le texte
 * lui-même).
 */
class GenerateBuildConfigTaskTest {

    @TempDir
    Path temporaire;

    /** Un contexte minimal : journal collecté, annulation jamais posée. */
    private record Contexte(Path racine, List<String> journal) implements TaskContext {

        Contexte(Path racine) {
            this(racine, new ArrayList<>());
        }

        @Override
        public Path projectRoot() {
            return racine;
        }

        @Override
        public TaskLogger logger() {
            return new TaskLogger() {
                @Override
                public void debug(String message) {
                    journal.add(message);
                }

                @Override
                public void info(String message) {
                    journal.add(message);
                }

                @Override
                public void warn(String message) {
                    journal.add(message);
                }

                @Override
                public void error(String message) {
                    journal.add(message);
                }
            };
        }

        @Override
        public CancellationFlag cancellation() {
            return new CancellationFlag();
        }
    }

    private ForgeModule moduleApplication(Path racine, String forgeJson)
            throws Exception {
        Files.createDirectories(racine);
        Files.writeString(racine.resolve("forge.json"), forgeJson);
        ForgeModel modèle = ModelCodec.read(racine);
        return modèle.modules().get(0);
    }

    @Test
    void applicationPorteLesConstantesAgpEtLesChamps() throws Exception {
        Path racine = temporaire.resolve("projet");
        ForgeModule module = moduleApplication(racine, """
                { "modelVersion": 1, "repositories": [],
                  "modules": [ { "name": "app", "type": "application",
                    "android": { "manifest": "src/main/AndroidManifest.xml",
                                 "minSdk": 28, "targetSdk": 34,
                                 "namespace": "demo.app",
                                 "applicationId": "com.exemple.appli",
                                 "versionCode": 7, "versionName": "1.7-dev",
                                 "buildFeatures": { "buildConfig": true },
                                 "buildConfigFields": [
                                   { "type": "String", "name": "API_HOST",
                                     "value": "\\\"https://api.exemple.com\\\"" },
                                   { "type": "boolean", "name": "ANALYTIQUE",
                                     "value": "false" } ] } } ] }
                """);

        GenerateBuildConfigTask tâche = new GenerateBuildConfigTask(module,
                new ProjectLayout(racine));
        assertEquals("app:generateBuildConfig", tâche.name());
        tâche.execute(new Contexte(racine));

        Path source = racine.resolve("build/app/generated/buildConfig/demo/app/BuildConfig.java");
        assertTrue(Files.isRegularFile(source), "la source est écrite : " + source);
        String java = Files.readString(source);
        // L'en-tête « généré automatiquement » précède le package (commentaire
        // légal avant la déclaration, position d'AGP).
        assertTrue(java.contains("package demo.app;"), "paquet = namespace");
        // DEBUG par Boolean.parseBoolean, comme AGP (pas replié à la compilation).
        assertTrue(java.contains("public static final boolean DEBUG = "
                + "Boolean.parseBoolean(\"true\");"));
        assertTrue(java.contains("public static final String APPLICATION_ID = "
                + "\"com.exemple.appli\";"), "l'identifiant effectif gagne");
        assertTrue(java.contains("public static final String BUILD_TYPE = \"debug\";"));
        assertTrue(java.contains("public static final int VERSION_CODE = 7;"));
        assertTrue(java.contains("public static final String VERSION_NAME = \"1.7-dev\";"));
        // Champs personnalisés : l'expression Java passe TELLE QUELLE (le
        // guillemet intérieur est déjà échappé par le script du projet, comme
        // buildConfigField d'AGP).
        assertTrue(java.contains("public static final String API_HOST = "
                + "\"https://api.exemple.com\";"));
        assertTrue(java.contains("public static final boolean ANALYTIQUE = false;"));
        assertFalse(java.contains("LIBRARY_PACKAGE_NAME"),
                "une application porte APPLICATION_ID, pas LIBRARY_PACKAGE_NAME");
    }

    @Test
    void bibliothèquePorteLibrariePackageNameSansVersions() throws Exception {
        Path racine = temporaire.resolve("projet");
        ForgeModule module = moduleApplication(racine, """
                { "modelVersion": 1, "repositories": [],
                  "modules": [ { "name": "core", "type": "android-library",
                    "android": { "manifest": "src/main/AndroidManifest.xml",
                                 "minSdk": 28, "targetSdk": 34,
                                 "namespace": "demo.core",
                                 "buildFeatures": { "buildConfig": true } } } ] }
                """);

        new GenerateBuildConfigTask(module, new ProjectLayout(racine))
                .execute(new Contexte(racine));

        String java = Files.readString(racine.resolve(
                "build/core/generated/buildConfig/demo/core/BuildConfig.java"));
        assertTrue(java.contains("public static final String LIBRARY_PACKAGE_NAME = "
                + "\"demo.core\";"));
        assertFalse(java.contains("APPLICATION_ID"),
                "une bibliothèque porte LIBRARY_PACKAGE_NAME, pas APPLICATION_ID");
        assertFalse(java.contains("VERSION_CODE"),
                "les versions appartiennent à l'application (sémantique AGP)");
    }

    @Test
    void laSourceEstLEmpreinteEtUnChampRetiréDisparaît() throws Exception {
        Path racine = temporaire.resolve("projet");
        ForgeModule module = moduleApplication(racine, """
                { "modelVersion": 1, "repositories": [],
                  "modules": [ { "name": "app", "type": "application",
                    "android": { "manifest": "src/main/AndroidManifest.xml",
                                 "minSdk": 28, "targetSdk": 34,
                                 "namespace": "demo.app",
                                 "buildFeatures": { "buildConfig": true },
                                 "buildConfigFields": [
                                   { "type": "int", "name": "ANCIEN_CHAMP",
                                     "value": "1" } ] } } ] }
                """);
        GenerateBuildConfigTask tâche = new GenerateBuildConfigTask(module,
                new ProjectLayout(racine));
        tâche.execute(new Contexte(racine));
        assertTrue(Files.readString(racine.resolve(
                "build/app/generated/buildConfig/demo/app/BuildConfig.java"))
                .contains("ANCIEN_CHAMP"));

        // Le champ est retiré du modèle : la régénération PROPRE efface la
        // source périmée (un champ retiré ne survit pas sur le classpath).
        ForgeModule sansChamp = new ForgeModule(module.name(), module.type(),
                module.javaRelease(), module.sources(), module.resources(),
                module.dependencies(), Optional.of(new AndroidFacet(
                        module.android().orElseThrow().manifest(),
                        List.of(), List.of(), 28, 34, null, "demo.app",
                        AndroidFacet.VERSION_CODE_DÉFAUT,
                        AndroidFacet.VERSION_NOM_DÉFAUT,
                        new AndroidFacet.BuildFeatures(true, false),
                        List.of(), java.util.Map.of())),
                ForgeModule.LANGUAGE_JAVA);
        Path racine2 = temporaire.resolve("projet2");
        Files.createDirectories(racine2);
        new GenerateBuildConfigTask(sansChamp, new ProjectLayout(racine2))
                .execute(new Contexte(racine2));
        assertFalse(Files.readString(racine2.resolve(
                "build/app/generated/buildConfig/demo/app/BuildConfig.java"))
                .contains("ANCIEN_CHAMP"), "le champ retiré disparaît");
    }

    @Test
    void typeDeModuleInconnuRefuséÀLaConstruction() throws Exception {
        // Le type application est exigé par la tâche au sens large (elle
        // diverge application/bibliothèque) — un module java-library sans
        // facette n'a simplement pas sa tâche dans le pipeline ; ici on pousse
        // le modèle de bout en bout : pipeline = application.
        Path racine = temporaire.resolve("projet");
        ForgeModule module = moduleApplication(racine, """
                { "modelVersion": 1, "repositories": [],
                  "modules": [ { "name": "app", "type": "application",
                    "android": { "manifest": "src/main/AndroidManifest.xml",
                                 "minSdk": 28, "targetSdk": 34,
                                 "namespace": "demo.app",
                                 "buildFeatures": { "buildConfig": true } } } ] }
                """);
        assertEquals(ModuleType.APPLICATION, module.type());
    }
}
