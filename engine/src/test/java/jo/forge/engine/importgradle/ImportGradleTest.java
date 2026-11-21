package jo.forge.engine.importgradle;

import jo.forge.engine.model.ForgeModel;
import jo.forge.engine.model.ModelException;
import jo.forge.engine.model.ForgeModule;
import jo.forge.engine.model.ModuleDependency;
import jo.forge.engine.model.ModuleType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests de l'import Gradle statique : projet multi-modules typique, portées, dépôts,
 * bloc android, limites (constructions ignorées avec avertissement, namespace
 * obligatoire pour une application).
 */
class ImportGradleTest {

    @TempDir
    Path temporaire;

    @Test
    void importeUnProjetTypique() throws IOException {
        écrire("settings.gradle", """
                rootProject.name = 'demo'
                include ':core', ':app'
                """);
        écrire("build.gradle", """
                repositories {
                    google()
                    mavenCentral()
                }
                """);
        écrire("core/build.gradle", """
                plugins {
                    id 'java-library'
                }
                dependencies {
                    api 'com.exemple:outils:1.2.3'
                    implementation 'org.autre:chose:2.0'
                }
                """);
        écrire("app/build.gradle", """
                plugins {
                    id 'com.android.application'
                }
                android {
                    namespace 'demo.app'
                    compileSdk 34
                    defaultConfig {
                        applicationId 'fr.demo.app'
                        minSdk 28
                        targetSdk 34
                    }
                }
                dependencies {
                    implementation project(':core')
                    compileOnly 'com.exemple:annots:5.0'
                }
                """);

        ImportGradle.RésultatImport résultat = ImportGradle.importerAverti(temporaire);
        ForgeModel modèle = résultat.modèle();

        assertEquals(2, modèle.modules().size());
        assertEquals(2, modèle.repositories().size(),
                "google() et mavenCentral() du build racine");
        ForgeModule core = modèle.module("core").orElseThrow();
        assertEquals(ModuleType.JAVA_LIBRARY, core.type());
        assertEquals(2, core.dependencies().size());
        ModuleDependency.MavenDependency api = (ModuleDependency.MavenDependency)
                core.dependencies().get(0);
        assertEquals(jo.forge.engine.model.DependencyScope.API, api.scope());

        ForgeModule app = modèle.module("app").orElseThrow();
        assertEquals(ModuleType.APPLICATION, app.type());
        var facette = app.android().orElseThrow();
        assertEquals(28, facette.minSdk());
        assertEquals(34, facette.targetSdk());
        assertEquals("demo.app", facette.namespace());
        assertEquals("fr.demo.app", facette.applicationId());
        assertTrue(app.dependencies().stream()
                        .anyMatch(d -> d instanceof ModuleDependency.ProjectDependency p
                                && p.module().equals("core")),
                "la dépendance de projet est reconnue");
        assertTrue(résultat.avertissements().isEmpty(),
                "aucune construction ignorée dans ce projet typique : "
                        + résultat.avertissements());
    }

    @Test
    void leModèleRéimportéEstConstruisableParLeCodec() throws IOException {
        écrire("settings.gradle", "include ':core'\n");
        écrire("core/build.gradle", """
                plugins { id 'java-library' }
                dependencies { implementation 'com.exemple:o:1.0' }
                """);
        ForgeModel modèle = ImportGradle.importer(temporaire);
        // Le JSON produit est relu par le codec officiel : l'import ne crée jamais
        // un modèle que le moteur refuserait.
        ForgeModel relu = jo.forge.engine.model.ModelCodec.parse(
                jo.forge.engine.model.ModelCodec.write(modèle));
        assertEquals(modèle, relu);
    }

    @Test
    void constructionsInconnuesIgnoréesAvecAvertissement() throws IOException {
        écrire("settings.gradle", "include ':a'\n");
        écrire("build.gradle", """
                repositories {
                    maven { url 'https://perso.fr/maven' }
                }
                """);
        écrire("a/build.gradle", """
                plugins { id 'java-library' }
                dependencies {
                    implementation "$outilsVersion"
                    testImplementation 'junit:junit:4.13'
                }
                """);

        ImportGradle.RésultatImport résultat = ImportGradle.importerAverti(temporaire);
        assertTrue(résultat.avertissements().stream().anyMatch(a ->
                        a.contains("perso.fr")),
                "dépôt personnalisé ignoré avec avertissement : "
                        + résultat.avertissements());
        assertTrue(résultat.avertissements().stream().anyMatch(a ->
                        a.contains("outilsVersion")),
                "interpolation non résolue signalée : " + résultat.avertissements());
        // testImplementation n'est pas une portée Forge : ignorée, pas silencieuse.
        assertTrue(résultat.avertissements().stream().anyMatch(a ->
                a.contains("testImplementation")));
        assertTrue(résultat.modèle().module("a").orElseThrow()
                .dependencies().isEmpty());
    }

    @Test
    void applicationSansNamespaceRefuséeAvecMessageClair() throws IOException {
        écrire("settings.gradle", "include ':app'\n");
        écrire("app/build.gradle", """
                plugins { id 'com.android.application' }
                android {
                    compileSdk 34
                }
                """);
        ModelException refus = assertThrows(
                ModelException.class,
                () -> ImportGradle.importer(temporaire));
        assertTrue(refus.getMessage().contains("namespace"),
                "le message cite le champ manquant : " + refus.getMessage());
    }

    @Test
    void sansSettingsGradleRefusPropre() {
        ModelException refus = assertThrows(
                ModelException.class,
                () -> ImportGradle.importer(temporaire));
        assertTrue(refus.getMessage().contains("settings.gradle"));
    }

    /**
     * v0.2.18 : les constructions de génération de code et de configuration
     * du script sont importées — buildFeatures, buildConfigField,
     * versionCode/versionName, manifestPlaceholders, compileOptions (niveau
     * Java), plugin Kotlin (langage du module).
     */
    @Test
    void importeGénérationEtConfiguration() throws IOException {
        écrire("settings.gradle", "include ':app'\n");
        écrire("app/build.gradle", """
                plugins {
                    id 'com.android.application'
                    id 'org.jetbrains.kotlin.android'
                }
                android {
                    namespace 'demo.app'
                    compileSdk 34
                    compileOptions {
                        sourceCompatibility JavaVersion.VERSION_17
                    }
                    defaultConfig {
                        applicationId 'fr.demo.app'
                        minSdk 28
                        targetSdk 34
                        versionCode 7
                        versionName "1.7"
                        manifestPlaceholders = [hôteDeFrais: "frais.exemple.com"]
                        buildConfigField "String", "API_HOST", "\\"https://api.exemple.com\\""
                    }
                    buildFeatures {
                        viewBinding true
                        buildConfig true
                    }
                }
                """);

        ImportGradle.RésultatImport résultat = ImportGradle.importerAverti(temporaire);
        ForgeModule app = résultat.modèle().module("app").orElseThrow();
        var facette = app.android().orElseThrow();

        assertTrue(app.estKotlin(),
                "le plugin org.jetbrains.kotlin.android importe le module comme Kotlin");
        assertEquals(17, app.javaRelease(),
                "compileOptions.sourceCompatibility devient le niveau Java du module");
        assertEquals(7, facette.versionCode());
        assertEquals("1.7", facette.versionName());
        assertTrue(facette.buildFeatures().viewBinding());
        assertTrue(facette.buildFeatures().buildConfig());
        assertEquals(1, facette.buildConfigFields().size());
        assertEquals("API_HOST", facette.buildConfigFields().get(0).name());
        assertEquals("\"https://api.exemple.com\"",
                facette.buildConfigFields().get(0).value(),
                "l'expression Java passe verbatim (guillemets inclus)");
        assertEquals("frais.exemple.com",
                facette.manifestPlaceholders().get("hôteDeFrais"));
        assertTrue(résultat.avertissements().isEmpty(),
                "une écriture Gradle moderne s'importe sans bruit : "
                        + résultat.avertissements());

        // Le modèle réimporté est relisible par le codec (boucle import →
        // forge.json → moteur).
        ForgeModel relu = jo.forge.engine.model.ModelCodec.parse(
                jo.forge.engine.model.ModelCodec.write(résultat.modèle()));
        assertEquals(résultat.modèle(), relu);
    }

    private void écrire(String chemin, String contenu) throws IOException {
        Path fichier = temporaire.resolve(chemin);
        fichier.getParent().toFile().mkdirs();
        java.nio.file.Files.writeString(fichier, contenu);
    }
}
