package jo.forge.engine.pipeline.android;

import jo.forge.engine.model.ForgeModel;
import jo.forge.engine.model.ForgeModule;
import jo.forge.engine.model.ModelCodec;
import jo.forge.engine.testutil.Projets;
import jo.forge.engine.tasks.Task;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Jonction des outils Android avec le moteur INSTALLÉ (prochaines étapes de la
 * Phase 4, consignées dans limites-et-suite.md) : le pipeline localise aapt2 et
 * android.jar sous {@code components/} du répertoire moteur transmis par la coquille
 * (variable {@code FORGE_ENGINE_DIR}) — pas besoin du SDK de la machine.
 *
 * <p>Seule la LOCALISATION est testée (elle se produit à la construction des tâches) :
 * de simples fichiers factices suffisent, aucune exécution d'outil ici.</p>
 */
class OutilsMoteurInstalleTest {

    @TempDir
    Path temporaire;

    /**
     * Construit un projet Android minimal et retourne son module application.
     */
    private ForgeModule moduleApplication(Path racine) throws IOException {
        Projets.fichier(racine, "forge.json", """
                { "modelVersion": 1,
                  "repositories": [],
                  "modules": [
                    { "name": "app", "type": "application",
                      "android": { "manifest": "src/main/AndroidManifest.xml",
                                   "res": ["src/main/res"],
                                   "minSdk": 28, "targetSdk": 34,
                                   "namespace": "demo.app" } }
                  ] }
                """);
        Projets.fichier(racine, "app/src/main/AndroidManifest.xml", """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest package="demo.app" />
                """);
        ForgeModel model = ModelCodec.read(racine);
        return model.modules().stream()
                .filter(module -> module.name().equals("app"))
                .findFirst().orElseThrow();
    }

    @Test
    void aapt2EtAndroidJarSontLocalisesDansLeMoteurInstalle() throws IOException {
        Path racine = temporaire.resolve("projet");
        ForgeModule app = moduleApplication(racine);

        // Layout du moteur installé (convention des composants de l'étape 12) :
        // components/aapt2-<abi>/aapt2 et components/platform-<api>/android.jar.
        // L'installateur pose les fichiers exécutables ; ici seuls des fichiers
        // factices importent (aucune exécution d'outil à la construction).
        Path maison = temporaire.resolve("engines/0.2.0");
        Files.createDirectories(maison.resolve("components/aapt2-arm64-v8a"));
        Files.createDirectories(maison.resolve("components/platform-34"));
        Files.writeString(maison.resolve("components/aapt2-arm64-v8a/aapt2"),
                "binaire factice");
        Files.write(maison.resolve("components/platform-34/android.jar"),
                new byte[]{0x50, 0x4b, 0x03, 0x04});

        // Les propriétés de test (forge.aapt2, forge.androidJar) PRIORITAIRES sur le
        // moteur installé doivent être neutralisées pour observer la jonction.
        String ancienAapt2 = System.clearProperty("forge.aapt2");
        String ancienJar = System.clearProperty("forge.androidJar");
        try {
            ForgeModel model = ModelCodec.read(racine);
            List<Task> taches = AndroidPipeline.tâchesDe(app, model, racine, false,
                    "arm64-v8a", maison);

            assertNotNull(taches.stream()
                            .filter(tache -> tache.name().endsWith(":processResources"))
                            .findFirst().orElse(null),
                    "processResources doit être construite : aapt2 localisé dans "
                            + "le moteur installé");
            assertNotNull(taches.stream()
                            .filter(tache -> tache.name().endsWith(":dex"))
                            .findFirst().orElse(null),
                    "dex doit être construite : android.jar localisé dans le "
                            + "moteur installé");
            assertTrue(taches.size() >= 5,
                    "chaîne application complète attendue (resolve, ressources, "
                            + "compilation, dex, package, signature)");
        } finally {
            if (ancienAapt2 != null) {
                System.setProperty("forge.aapt2", ancienAapt2);
            }
            if (ancienJar != null) {
                System.setProperty("forge.androidJar", ancienJar);
            }
        }
    }
}
