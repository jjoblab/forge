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

    @Test
    void entréeBombeInterrompuePendantLaCopie() throws IOException {
        // Constat B-3 : le plafond était contrôlé APRÈS l'écriture complète d'une
        // entrée — une seule « bombe » très compressible écrivait bien au-delà du
        // plafond sur le disque AVANT d'être détectée. Elle est désormais
        // interrompue EN FLUX, au premier tampon de trop.
        Path archive = zipSimple("bombe.bin", new byte[16 * 1024]);
        ArchiveExtractor borne = new ArchiveExtractor(4 * 1024);
        InstallException échec = assertThrows(InstallException.class,
                () -> borne.extract(archive, tempDir.resolve("cible")));
        assertTrue(échec.getMessage().contains("interrompue"),
                "le refus doit dire l'interruption en flux : " + échec.getMessage());
        Path partielle = tempDir.resolve("cible/bombe.bin");
        assertTrue(Files.exists(partielle), "l'entrée a bel et bien commencé");
        assertTrue(Files.size(partielle) < 16 * 1024,
                "l'entrée fautive est interrompue, pas écrite en entier ("
                        + Files.size(partielle) + " octets)");
    }

    @Test
    void plafondDentréesRespecté() throws IOException {
        // Constat B-3 : un million de micro-entrées est une bombe aussi — le
        // nombre d'entrées est plafonné, pas seulement la taille totale.
        Path archive = tempDir.resolve("beaucoup.zip");
        try (ZipOutputStream sortie = new ZipOutputStream(
                Files.newOutputStream(archive))) {
            for (int i = 0; i < 3; i++) {
                sortie.putNextEntry(new ZipEntry("f" + i));
                sortie.write('x');
                sortie.closeEntry();
            }
        }
        ArchiveExtractor borne = new ArchiveExtractor(1024 * 1024, 2);
        InstallException échec = assertThrows(InstallException.class,
                () -> borne.extract(archive, tempDir.resolve("cible")));
        assertTrue(échec.getMessage().contains("entrées"),
                "le refus doit citer le plafond d'entrées : " + échec.getMessage());
    }
}
