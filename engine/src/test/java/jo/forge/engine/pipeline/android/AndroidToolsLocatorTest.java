package jo.forge.engine.pipeline.android;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests du localisateur d'outils Android du moteur : la plus haute
 * plate-forme installée (correctif v0.2.7 — les bibliothèques Java sur ART
 * compilent contre elle).
 */
class AndroidToolsLocatorTest {

    @TempDir
    Path temporaire;

    @Test
    void laPlusHautePlateFormeInstalléeGagne() throws IOException {
        Path maison = temporaire.resolve("engines/0.2.7");
        for (int api : new int[]{33, 34, 36}) {
            Path composant = maison.resolve("components/platform-" + api);
            Files.createDirectories(composant);
            Files.write(composant.resolve("android.jar"),
                    new byte[]{0x50, 0x4b, 0x03, 0x04});
        }

        assertEquals(36, AndroidToolsLocator.plateformeLaPlusHaute(maison),
                "le niveau le plus élevé des composants installés");
    }

    @Test
    void répertoiresNonConformesIgnorés() throws IOException {
        Path maison = temporaire.resolve("engines/0.2.7");
        Files.createDirectories(maison.resolve("components/platform-34"));
        Files.write(maison.resolve("components/platform-34/android.jar"),
                new byte[]{0x50, 0x4b, 0x03, 0x04});
        // Sans android.jar : ignoré.
        Files.createDirectories(maison.resolve("components/platform-99"));
        // Niveau non numérique : ignoré.
        Files.createDirectories(maison.resolve("components/platform-beta"));
        // Autre composant : ignoré.
        Files.createDirectories(maison.resolve("components/aapt2-arm64-v8a"));
        Files.write(maison.resolve("components/aapt2-arm64-v8a/aapt2"),
                "binaire".getBytes());

        assertEquals(34, AndroidToolsLocator.plateformeLaPlusHaute(maison),
                "seule la plate-forme portant un android.jar compte");
    }

    @Test
    void aucunePlateFormeRendMoinsUn() throws IOException {
        assertEquals(-1, AndroidToolsLocator.plateformeLaPlusHaute(null),
                "sans moteur installé");
        assertEquals(-1, AndroidToolsLocator.plateformeLaPlusHaute(temporaire),
                "sans composants");
    }

    @Test
    void messageAbsenceAapt2NommeLeComposantExact() {
        // Rapport appareil du 2026-11-20 : « installez le composant aapt2 » sans
        // le suffixe d'ABI ne désignait AUCUN composant réel de l'assistant —
        // le message doit citer aapt2-arm64-v8a tel qu'il figure au manifeste.
        String message = AndroidToolsLocator.messageAapt2Introuvable("arm64-v8a");
        assertTrue(message.contains("aapt2-arm64-v8a"),
                "le composant exact est nommé : " + message);
        assertTrue(message.contains("assistant du moteur"),
                "l'action à mener est citée");
        assertTrue(message.contains("forge.aapt2"),
                "le repli machine de développement est cité");
    }
}
