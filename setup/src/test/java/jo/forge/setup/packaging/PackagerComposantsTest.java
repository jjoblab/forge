package jo.forge.setup.packaging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Tests du layout des composants publiés : chaque entrée d'archive doit être posée à
 * son chemin de destination lors de l'installation (convention des étapes 3-4) — le
 * cœur en {@code engine.jar}, les compagnons dexés en {@code lib/<id>.jar}, les
 * outils en {@code components/<id>/…} (chemins attendus par la session de la coquille
 * et par le localisateur d'outils du moteur).
 */
class PackagerComposantsTest {

    @TempDir
    Path temporaire;

    @Test
    void jarDexePoséASonCheminDeDestinationAvecClassesDex() throws IOException {
        Path dex = temporaire.resolve("classes.dex");
        byte[] octetsDex = "dex factice".getBytes(StandardCharsets.UTF_8);
        Files.write(dex, octetsDex);
        Path archive = temporaire.resolve("java-compiler-0.2.0.zip");

        PackagerComposants.emballerJarDexe(dex, archive, "lib/java-compiler.jar");

        byte[] jarEmballé = entréeUnique(archive, "lib/java-compiler.jar");
        // Le jar interne est un zip à entrée unique classes.dex, octets identiques.
        try (ZipInputStream jar = new ZipInputStream(
                new ByteArrayInputStream(jarEmballé))) {
            ZipEntry entree = jar.getNextEntry();
            assertNotNull(entree, "le jar interne doit contenir classes.dex");
            assertEquals("classes.dex", entree.getName());
            assertEquals("dex factice",
                    new String(jar.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void cheminsDeDestinationDesComposants() {
        assertEquals("engine.jar", PackagerComposants.cheminDexe("engine-core"));
        assertEquals("lib/java-compiler.jar",
                PackagerComposants.cheminDexe("java-compiler"));
        assertEquals("lib/dexeur.jar", PackagerComposants.cheminDexe("dexeur"));
        assertEquals("lib/signeur.jar", PackagerComposants.cheminDexe("signeur"));
        assertEquals("components/aapt2-arm64-v8a/aapt2",
                PackagerComposants.cheminOutil("aapt2-arm64-v8a"));
        assertEquals("components/platform-34/android.jar",
                PackagerComposants.cheminOutil("platform-34"));
    }

    /**
     * Lit l'entrée unique attendue d'une archive (toute autre entrée échoue).
     */
    private static byte[] entréeUnique(Path archive, String nomAttendu)
            throws IOException {
        byte[] contenu = null;
        try (ZipInputStream zip = new ZipInputStream(
                Files.newInputStream(archive))) {
            ZipEntry entree;
            while ((entree = zip.getNextEntry()) != null) {
                assertEquals(nomAttendu, entree.getName(),
                        "entrée inattendue dans l'archive");
                contenu = zip.readAllBytes();
            }
        }
        assertNotNull(contenu, "entrée " + nomAttendu + " absente de l'archive");
        return contenu;
    }
}
