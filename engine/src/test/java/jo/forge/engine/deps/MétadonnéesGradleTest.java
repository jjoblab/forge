package jo.forge.engine.deps;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests des métadonnées de module Gradle (v0.2.15) : analyse du JSON publié à
 * côté des POMs, sélection de variante pour une cible Android
 * ({@code -android}) contre une cible JVM ({@code -jvm}), redirection
 * {@code available-at}, contraintes d'alignement, exclusion, dégradation sur
 * document malformé.
 */
class MétadonnéesGradleTest {

    /** Un .module façon androidx : racine KMP redirigeant vers -android/-jvm. */
    private static final String MODULE_KMP = """
            {
              "formatVersion": "1.1",
              "component": { "group": "demo", "module": "lib", "version": "1.0" },
              "variants": [
                { "name": "metadataApiElements",
                  "attributes": { "org.gradle.usage": "kotlin-metadata",
                                  "org.jetbrains.kotlin.platform.type": "common" },
                  "files": [ { "name": "lib-metadata-1.0.jar", "url": "lib-metadata-1.0.jar" } ] },
                { "name": "releaseApiElements-published",
                  "attributes": { "org.gradle.category": "library",
                                  "org.gradle.usage": "java-api",
                                  "org.jetbrains.kotlin.platform.type": "androidJvm",
                                  "org.gradle.jvm.environment": "android" },
                  "available-at": { "group": "demo", "module": "lib-android",
                                    "version": "1.0", "url": "../../lib-android/1.0/lib-android-1.0.module" } },
                { "name": "jvmApiElements-published",
                  "attributes": { "org.gradle.category": "library",
                                  "org.gradle.usage": "java-api",
                                  "org.jetbrains.kotlin.platform.type": "jvm",
                                  "org.gradle.jvm.environment": "standard-jvm" },
                  "available-at": { "group": "demo", "module": "lib-jvm",
                                    "version": "1.0", "url": "../../lib-jvm/1.0/lib-jvm-1.0.module" } }
              ]
            }
            """;

    /** Le module porteur : variante api (fichier + dépendances + contraintes) et runtime. */
    private static final String MODULE_ANDROID = """
            {
              "component": { "group": "demo", "module": "lib-android", "version": "1.0" },
              "variants": [
                { "name": "releaseApiElements-published",
                  "attributes": { "org.gradle.category": "library",
                                  "org.gradle.usage": "java-api",
                                  "org.jetbrains.kotlin.platform.type": "androidJvm",
                                  "org.gradle.jvm.environment": "android" },
                  "dependencies": [
                    { "group": "demo", "module": "autre",
                      "version": { "requires": "1.0" } },
                    { "group": "demo", "module": "exclue",
                      "version": { "requires": "1.0" },
                      "excludes": [ { "group": "demo", "module": "interdite" } ] }
                  ],
                  "dependencyConstraints": [
                    { "group": "demo", "module": "autre",
                      "version": { "requires": "1.1" } }
                  ],
                  "files": [ { "name": "lib-release.aar", "url": "lib-release.aar",
                               "size": 10 } ] },
                { "name": "releaseRuntimeElements-published",
                  "attributes": { "org.gradle.category": "library",
                                  "org.gradle.usage": "java-runtime",
                                  "org.jetbrains.kotlin.platform.type": "androidJvm",
                                  "org.gradle.jvm.environment": "android" },
                  "dependencies": [
                    { "group": "demo", "module": "runtime-seule",
                      "version": { "prefers": "2.0" } }
                  ],
                  "files": [ { "name": "lib-release.aar", "url": "lib-release.aar" } ] }
              ]
            }
            """;

    @Test
    void sélectionAndroidSuitLaVarianteAndroid() {
        MétadonnéesGradle.Module module = MétadonnéesGradle.analyser(
                MODULE_KMP.getBytes()).orElseThrow();
        MétadonnéesGradle.Variante android = MétadonnéesGradle
                .sélectionner(module, true).orElseThrow();
        assertEquals("releaseApiElements-published", android.nom(),
                "une cible Android suit la variante androidJvm (java-api)");
        assertEquals("demo", android.disponibleÀ().groupe());
        assertEquals("lib-android", android.disponibleÀ().nom(),
                "la redirection available-at désigne le module porteur");

        MétadonnéesGradle.Variante jvm = MétadonnéesGradle
                .sélectionner(module, false).orElseThrow();
        assertEquals("jvmApiElements-published", jvm.nom(),
                "une cible JVM suit la variante jvm — le -android ne la contamine jamais");
    }

    @Test
    void variantePorteuseDépendancesContraintesEtFichier() {
        MétadonnéesGradle.Module module = MétadonnéesGradle.analyser(
                MODULE_ANDROID.getBytes()).orElseThrow();
        MétadonnéesGradle.Variante api = MétadonnéesGradle
                .sélectionner(module, true).orElseThrow();

        assertEquals(List.of("lib-release.aar"), api.fichiers(),
                "le fichier de variante ne porte PAS le nom Maven conventionnel");
        assertEquals(2, api.dépendances().size());
        assertEquals("1.0", api.dépendances().get(0).version(),
                "requires est la version résoluble");
        assertTrue(api.dépendances().get(1).exclusions()
                        .contains("demo:interdite"),
                "les exclusions de la variante traversent");

        assertEquals("1.1", api.contraintes().get(0).version(),
                "les contraintes d'alignement sont servies (médiation)");

        MétadonnéesGradle.Variante runtime = MétadonnéesGradle
                .varianteRuntime(module, true).orElseThrow();
        assertEquals("2.0", runtime.dépendances().get(0).version(),
                "prefers sert de repli quand requires manque");
        assertEquals("runtime-seule", runtime.dépendances().get(0).nom(),
                "les dépendances runtime-seulement vivent dans la variante runtime");
    }

    @Test
    void documentMalforméDégrade() {
        assertEquals(Optional.empty(),
                MétadonnéesGradle.analyser("pas du tout json".getBytes()),
                "un document malformé dégrade vers le POM, jamais d'échec");
        assertEquals(Optional.empty(),
                MétadonnéesGradle.analyser("{\"component\":{}}".getBytes()),
                "un module sans variante est absent de forme");
    }
}
