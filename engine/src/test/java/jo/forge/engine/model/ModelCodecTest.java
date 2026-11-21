package jo.forge.engine.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests du codec {@code forge.json} : lecture valide, conventions par défaut,
 * round-trip d'écriture, et rejets ciblés (chaque règle de validation a son test).
 */
class ModelCodecTest {

    @TempDir
    Path temporaire;

    @Test
    void litUnModèleComplet() throws IOException {
        Files.writeString(temporaire.resolve("forge.json"), """
                {
                  "modelVersion": 1,
                  "repositories": ["https://exemple.fr/maven"],
                  "modules": [
                    {
                      "name": "core",
                      "type": "java-library",
                      "javaRelease": 17,
                      "sources": ["src/main/java", "src/généré/java"],
                      "resources": ["src/main/resources"]
                    },
                    {
                      "name": "app",
                      "type": "java-library",
                      "dependencies": [
                        { "project": "core", "scope": "api" },
                        { "maven": "com.exemple:outils:1.2.3", "scope": "compileOnly" }
                      ]
                    }
                  ]
                }
                """);
        ForgeModel modèle = ModelCodec.read(temporaire);

        assertEquals(1, modèle.modelVersion());
        assertEquals(2, modèle.modules().size());
        ForgeModule core = modèle.module("core").orElseThrow();
        assertEquals(ModuleType.JAVA_LIBRARY, core.type());
        assertEquals(17, core.javaRelease());
        assertEquals(2, core.sources().size());
        ForgeModule app = modèle.module("app").orElseThrow();
        assertEquals(2, app.dependencies().size());
        ModuleDependency.MavenDependency maven = assertInstanceOf(
                ModuleDependency.MavenDependency.class, app.dependencies().get(1));
        assertEquals("com.exemple:outils:1.2.3", maven.coordinate());
        assertEquals(DependencyScope.COMPILE_ONLY, maven.scope());
    }

    @Test
    void conventionsParDéfautDesChemins() throws IOException {
        Files.writeString(temporaire.resolve("forge.json"), """
                { "modelVersion": 1, "modules": [ { "name": "seul", "type": "java-library" } ] }
                """);
        ForgeModule module = ModelCodec.read(temporaire).module("seul").orElseThrow();
        assertEquals(java.util.List.of("src/main/java"), module.sources());
        assertEquals(java.util.List.of("src/main/resources"), module.resources());
        assertEquals(ForgeModule.DEFAULT_JAVA_RELEASE, module.javaRelease());
        assertEquals(Optional.empty(), module.android());
    }

    @Test
    void listeExpliciteVideDésactiveLaConvention() throws IOException {
        Files.writeString(temporaire.resolve("forge.json"), """
                { "modelVersion": 1,
                  "modules": [ { "name": "seul", "type": "java-library", "sources": [] } ] }
                """);
        ForgeModule module = ModelCodec.read(temporaire).module("seul").orElseThrow();
        assertTrue(module.sources().isEmpty());
    }

    @Test
    void langageKotlinParDéfautJavaEtConventions() throws IOException {
        // Absent : Java, convention Java.
        Files.writeString(temporaire.resolve("forge.json"), """
                { "modelVersion": 1, "modules": [ { "name": "a", "type": "java-library" } ] }
                """);
        ForgeModule moduleJava = ModelCodec.read(temporaire).module("a").orElseThrow();
        assertEquals(ForgeModule.LANGUAGE_JAVA, moduleJava.language());
        assertTrue(!moduleJava.estKotlin());
        assertEquals(java.util.List.of("src/main/java"), moduleJava.sources());

        // Kotlin : convention étendue au répertoire Kotlin PUIS Java, round-trip.
        Files.writeString(temporaire.resolve("forge.json"), """
                { "modelVersion": 1,
                  "modules": [ { "name": "b", "type": "java-library",
                                 "language": "kotlin" } ] }
                """);
        ForgeModule kotlin = ModelCodec.read(temporaire).module("b").orElseThrow();
        assertEquals(ForgeModule.LANGUAGE_KOTLIN, kotlin.language());
        assertTrue(kotlin.estKotlin());
        assertEquals(java.util.List.of("src/main/kotlin", "src/main/java"),
                kotlin.sources(), "le Kotlin cohabite avec le Java du module");
        assertEquals(kotlin, ModelCodec.parse(ModelCodec.write(
                ModelCodec.read(temporaire))).module("b").orElseThrow(),
                "le langage survit au round-trip");
    }

    @Test
    void langageInconnuRefuséKotlinAndroidAdmis() throws IOException {
        Files.writeString(temporaire.resolve("forge.json"), """
                { "modelVersion": 1,
                  "modules": [ { "name": "a", "type": "java-library",
                                 "language": "cobol" } ] }
                """);
        ModelException inconnu = assertThrows(ModelException.class,
                () -> ModelCodec.read(temporaire));
        assertTrue(inconnu.getMessage().contains("langage inconnu"),
                "le message cite le langage refusé : " + inconnu.getMessage());

        // Kotlin sur un module Android : ADMIS depuis v0.2.11 — la chaîne
        // Android enchaîne compileKotlin (compilation jointe, R compris) avant
        // compileJava. (Refusé en v0.2.9-v0.2.10, « feuille de route ».)
        Files.writeString(temporaire.resolve("forge.json"), """
                { "modelVersion": 1,
                  "modules": [ { "name": "app", "type": "application",
                                 "language": "kotlin",
                                 "android": { "manifest": "AndroidManifest.xml",
                                              "minSdk": 28, "targetSdk": 34,
                                              "namespace": "demo.app" } } ] }
                """);
        ForgeModule app = ModelCodec.read(temporaire).module("app").orElseThrow();
        assertTrue(app.estKotlin(),
                "le module application Kotlin est admis tel quel");
        assertEquals(java.util.List.of("src/main/kotlin", "src/main/java"),
                app.sources(), "les sources Kotlin précèdent le Java du module");
    }

    @Test
    void facetteAndroidObligatoireEtInterdite() throws IOException {
        Files.writeString(temporaire.resolve("forge.json"), """
                { "modelVersion": 1,
                  "modules": [ { "name": "a", "type": "application" } ] }
                """);
        ModelException sansFacette = assertThrows(ModelException.class,
                () -> ModelCodec.read(temporaire));
        assertTrue(sansFacette.getMessage().contains("facette Android"),
                "le message doit citer la facette manquante : " + sansFacette.getMessage());

        Files.writeString(temporaire.resolve("forge.json"), """
                { "modelVersion": 1,
                  "modules": [ { "name": "a", "type": "java-library",
                                 "android": { "manifest": "AndroidManifest.xml",
                                              "minSdk": 28, "targetSdk": 34,
                                              "namespace": "demo.app" } } ] }
                """);
        ModelException facetteInterdite = assertThrows(ModelException.class,
                () -> ModelCodec.read(temporaire));
        assertTrue(facetteInterdite.getMessage().contains("interdite"),
                "le message doit citer l'interdiction : " + facetteInterdite.getMessage());
    }

    @Test
    void litUneFacetteAndroidComplète() throws IOException {
        Files.writeString(temporaire.resolve("forge.json"), """
                { "modelVersion": 1,
                  "modules": [ { "name": "app", "type": "application",
                    "android": { "manifest": "src/main/AndroidManifest.xml",
                                 "res": ["src/main/res"], "assets": ["src/main/assets"],
                                 "minSdk": 28, "targetSdk": 34,
                                 "applicationId": "demo.app", "namespace": "demo.app" } } ] }
                """);
        AndroidFacet facet = ModelCodec.read(temporaire).module("app").orElseThrow()
                .android().orElseThrow();
        assertEquals(28, facet.minSdk());
        assertEquals(34, facet.targetSdk());
        assertEquals("demo.app", facet.namespace());
        assertEquals(1, facet.resourceDirs().size());
    }

    @Test
    void roundTripÉcritureLecture() throws IOException {
        String json = """
                { "modelVersion": 1,
                  "repositories": ["https://exemple.fr/maven"],
                  "modules": [ { "name": "core", "type": "java-library" },
                    { "name": "app", "type": "java-library", "javaRelease": 21,
                      "dependencies": [ { "project": "core", "scope": "api" },
                        { "maven": "com.exemple:outils:2.0" } ] } ] }
                """;
        ForgeModel modèle = ModelCodec.parse(json);
        ForgeModel relu = ModelCodec.parse(ModelCodec.write(modèle));
        assertEquals(modèle, relu, "le round-trip doit donner un modèle égal");
    }

    @Test
    void rejetsCiblés() {
        assertRejet("{ \"modelVersion\": 2, \"modules\": [] }", "Version de modèle");
        assertRejet("{ \"modelVersion\": 1 }", "modules");
        assertRejet("{ \"modelVersion\": 1, \"modules\": [] }", "au moins un module");
        assertRejet("""
                { "modelVersion": 1, "modules": [
                  { "name": "a", "type": "java-library" },
                  { "name": "a", "type": "java-library" } ] }
                """, "dupliqué");
        assertRejet("""
                { "modelVersion": 1, "modules": [
                  { "name": "a", "type": "inconnu" } ] }
                """, "Type de module inconnu");
        assertRejet("""
                { "modelVersion": 1, "modules": [
                  { "name": "a", "type": "java-library",
                    "dependencies": [ { "project": "fantôme" } ] } ] }
                """, "module inconnu");
        assertRejet("""
                { "modelVersion": 1, "modules": [
                  { "name": "a", "type": "java-library",
                    "dependencies": [ { "project": "a" } ] } ] }
                """, "lui-même");
        assertRejet("""
                { "modelVersion": 1, "modules": [
                  { "name": "a", "type": "java-library",
                    "dependencies": [ { "maven": "mal:formé" } ] } ] }
                """, "Coordonnée Maven mal formée");
        assertRejet("""
                { "modelVersion": 1, "modules": [
                  { "name": "a", "type": "java-library", "sources": ["/absolu"] } ] }
                """, "relatif");
        assertRejet("""
                { "modelVersion": 1, "modules": [
                  { "name": "a", "type": "java-library", "sources": ["../ext"] } ] }
                """, "relatif");
        assertRejet("""
                { "modelVersion": 1, "modules": [
                  { "name": "a", "type": "java-library",
                    "dependencies": [ { "project": "a", "scope": "nimp" } ] } ] }
                """, "Portée");
    }

    @Test
    void projetSansForgeJson() {
        ModelException absent = assertThrows(ModelException.class,
                () -> ModelCodec.read(temporaire));
        assertTrue(absent.getMessage().contains("forge.json"));
    }

    /**
     * v0.2.18 : la facette Android porte les versions, les fonctionnalités de
     * génération, les champs BuildConfig et les marqueurs de manifeste — en
     * lecture, en round-trip d'écriture, et l'ABSENCE des champs reste valide
     * (défauts AGP 8 : rien de demandé).
     */
    @Test
    void facetteAndroidGénérationEtVersionsRoundTrip() throws IOException {
        Files.writeString(temporaire.resolve("forge.json"), """
                {
                  "modelVersion": 1,
                  "repositories": [],
                  "modules": [
                    { "name": "app", "type": "application",
                      "android": {
                        "manifest": "src/main/AndroidManifest.xml",
                        "minSdk": 28, "targetSdk": 34,
                        "namespace": "demo.app",
                        "applicationId": "com.exemple.appli",
                        "versionCode": 7, "versionName": "1.7",
                        "buildFeatures": { "buildConfig": true, "viewBinding": true },
                        "buildConfigFields": [
                          { "type": "String", "name": "API_HOST",
                            "value": "\\\"https://api.exemple.com\\\"" } ],
                        "manifestPlaceholders": { "hôteDeFrais": "frais.exemple.com" }
                      } }
                  ]
                }
                """);
        AndroidFacet facette = ModelCodec.read(temporaire).module("app")
                .orElseThrow().android().orElseThrow();
        assertEquals(7, facette.versionCode());
        assertEquals("1.7", facette.versionName());
        assertTrue(facette.buildFeatures().buildConfig());
        assertTrue(facette.buildFeatures().viewBinding());
        assertEquals(1, facette.buildConfigFields().size());
        assertEquals("API_HOST", facette.buildConfigFields().get(0).name());
        assertEquals("frais.exemple.com",
                facette.manifestPlaceholders().get("hôteDeFrais"));
        // Identifiant effectif et marqueurs dérivés.
        assertEquals("com.exemple.appli", facette.applicationIdEffectif());
        assertEquals("com.exemple.appli",
                facette.marqueursDeManifeste().get("applicationId"));

        // Round-trip : l'écriture relit avec les mêmes valeurs.
        String réécrit = ModelCodec.write(ModelCodec.read(temporaire));
        AndroidFacet relue = ModelCodec.parse(réécrit).module("app")
                .orElseThrow().android().orElseThrow();
        assertEquals(facette, relue, "le round-trip est stable");

        // Sans les nouveaux champs : défauts AGP 8 (rien de demandé) et
        // identifiant effectif = namespace.
        Files.writeString(temporaire.resolve("forge.json"), """
                { "modelVersion": 1, "repositories": [],
                  "modules": [ { "name": "app", "type": "application",
                    "android": { "manifest": "src/main/AndroidManifest.xml",
                                 "minSdk": 28, "targetSdk": 34,
                                 "namespace": "demo.app" } } ] }
                """);
        AndroidFacet sobre = ModelCodec.read(temporaire).module("app")
                .orElseThrow().android().orElseThrow();
        assertFalse(sobre.buildFeatures().buildConfig());
        assertFalse(sobre.buildFeatures().viewBinding());
        assertEquals(AndroidFacet.VERSION_CODE_DÉFAUT, sobre.versionCode());
        assertEquals(AndroidFacet.VERSION_NOM_DÉFAUT, sobre.versionName());
        assertTrue(sobre.buildConfigFields().isEmpty());
        assertEquals("demo.app", sobre.applicationIdEffectif(),
                "sans applicationId déclaré, le namespace l'emporte");

        // Rejets ciblés des nouveaux champs.
        assertRejet("""
                { "modelVersion": 1, "modules": [ { "name": "app",
                  "type": "application", "android": {
                    "manifest": "m", "minSdk": 28, "targetSdk": 34,
                    "namespace": "n",
                    "buildFeatures": { "buildConfig": "oui" } } } ] }
                """, "booléen attendu");
        assertRejet("""
                { "modelVersion": 1, "modules": [ { "name": "app",
                  "type": "application", "android": {
                    "manifest": "m", "minSdk": 28, "targetSdk": 34,
                    "namespace": "n",
                    "buildConfigFields": [ { "type": "String",
                      "name": "9INVALIDE", "value": "x" } ] } } ] }
                """, "identifiant Java");
    }

    /**
     * Vérifie qu'un contenu invalide est rejeté avec un message contenant l'indice.
     */
    private void assertRejet(String json, String indice) {
        ModelException erreur = assertThrows(ModelException.class,
                () -> ModelCodec.parse(json));
        assertTrue(erreur.getMessage().contains(indice),
                "le message doit contenir \"" + indice + "\", trouvé : "
                        + erreur.getMessage());
    }
}
