package jo.forge.setup.install;

import jo.forge.setup.download.InstallException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests de l'extracteur : extraction normale et gardes (zip-slip, chemin absolu,
 * bombe zip).
 */
class ArchiveExtractorTest {

    @TempDir
    Path tempDir;

    private Path zipSimple(String nomEntree, byte[] donnees) throws IOException {
        Path fichier = tempDir.resolve("simple.zip");
        try (ZipOutputStream sortie = new ZipOutputStream(
                Files.newOutputStream(fichier))) {
            sortie.putNextEntry(new ZipEntry(nomEntree));
            sortie.write(donnees);
            sortie.closeEntry();
        }
        return fichier;
    }

    @Test
    void extractionNormale() throws IOException {
        Path archive = zipSimple("dossier/fichier.txt", "bonjour".getBytes(
                StandardCharsets.UTF_8));
        Path cible = tempDir.resolve("cible");
        new ArchiveExtractor().extract(archive, cible);
        assertEquals("bonjour", Files.readString(cible.resolve("dossier/fichier.txt")));
    }

    @Test
    void zipSlipRefuse() throws IOException {
        Path archive = zipSimple("../../ailleurs/evil.txt",
                "pwn".getBytes(StandardCharsets.UTF_8));
        Path cible = tempDir.resolve("cible");
        InstallException erreur = assertThrows(InstallException.class,
                () -> new ArchiveExtractor().extract(archive, cible));
        assertTrue(erreur.getMessage().contains("refus"),
                "le refus doit être explicite : " + erreur.getMessage());
    }

    @Test
    void cheminAbsoluRefuse() throws IOException {
        Path archive = zipSimple("/etc/evil.txt", "x".getBytes(StandardCharsets.UTF_8));
        assertThrows(InstallException.class, () -> new ArchiveExtractor()
                .extract(archive, tempDir.resolve("cible")));
    }

    @Test
    void disqueWindowsRefuse() throws IOException {
        Path archive = zipSimple("C:/evil.txt", "x".getBytes(StandardCharsets.UTF_8));
        assertThrows(InstallException.class, () -> new ArchiveExtractor()
                .extract(archive, tempDir.resolve("cible")));
    }

    @Test
    void plafondDeTailleRespecte() throws IOException {
        // Archive d'apparence modeste mais contenu décompressé énorme (répétition).
        byte[] gros = new byte[64 * 1024];
        Path archive = zipSimple("gros.bin", gros);
        ArchiveExtractor borne = new ArchiveExtractor(32 * 1024); // plafond 32 Ko
        assertThrows(InstallException.class, () -> borne
                .extract(archive, tempDir.resolve("cible")));
    }
}
