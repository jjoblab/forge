package jo.forge.setup.download;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jo.forge.setup.manifest.ComponentManifest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests du téléchargeur reprenable avec un vrai serveur HTTP local : téléchargement
 * complet, reprise après coupure (requêtes Range comptées), somme invalide rejetée,
 * annulation propre.
 *
 * <p>Note : le serveur local tourne en HTTP pour les besoins du test ; le contrôle
 * « HTTPS obligatoire » s'applique aux URLs du manifeste (testé dans ManifestTest) —
 * ici on vérifie la mécanique de reprise, pas le schéma (le téléchargeur du test parle
 * au serveur local par une URL acceptée pour l'occasion).</p>
 */
class DownloaderTest {

    @TempDir
    Path tempDir;

    private HttpServer serveur;
    private Path downloadsDir;
    private byte[] contenuArchive;

    @BeforeEach
    void demarrerServeur() throws IOException {
        downloadsDir = tempDir.resolve("downloads");
        contenuArchive = new byte[300_000];
        for (int i = 0; i < contenuArchive.length; i++) {
            contenuArchive[i] = (byte) (i * 31);
        }

        serveur = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serveur.start();
    }

    @AfterEach
    void arreterServeur() {
        serveur.stop(0);
    }

    /**
     * Téléchargeur câblé pour le serveur HTTP local des tests (schéma http accepté par
     * le constructeur de test ; la production reste en HTTPS strict).
     */
    private Downloader telechargeurLocal() {
        return new Downloader(downloadsDir, java.util.Set.of("http", "https"));
    }

    /**
     * Enregistre un contexte qui coupe la connexion à mi-parcours (simule une coupure).
     */
    private AtomicInteger enregistrerContenu(byte[] contenu, int couperApresOctets,
                                             int nombreDeCoupages) {
        AtomicInteger coupures = new AtomicInteger();
        serveur.createContext("/archive", exchange -> {
            String range = exchange.getRequestHeaders().getFirst("Range");
            long debut = 0;
            if (range != null && range.startsWith("bytes=")) {
                debut = Long.parseLong(range.substring("bytes=".length())
                        .replace("-", ""));
            }
            if (debut == 0 && coupures.getAndIncrement() < nombreDeCoupages) {
                // Première tentative : envoi partiel puis coupure brutale.
                ecrireReponse(exchange, contenu, 0, couperApresOctets, 206,
                        contenu.length);
                return;
            }
            if (range != null) {
                ecrireReponse(exchange, contenu, debut, contenu.length, 206,
                        contenu.length);
            } else {
                ecrireReponse(exchange, contenu, 0, contenu.length, 200, contenu.length);
            }
        });
        return coupures;
    }

    private void ecrireReponse(HttpExchange exchange, byte[] contenu, long debut,
                               long fin, int code, long total) throws IOException {
        exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
        if (code == 206) {
            exchange.getResponseHeaders().set("Content-Range",
                    "bytes " + debut + "-" + (fin - 1) + "/" + total);
        }
        long longueur = fin - debut;
        exchange.sendResponseHeaders(code, longueur);
        try (OutputStream sortie = exchange.getResponseBody()) {
            sortie.write(contenu, (int) debut, (int) longueur);
        }
    }

    private ComponentManifest composant(String sha256, int taille) {
        return new ComponentManifest("moteur", "0.1.0", taille, sha256,
                List.of("http://127.0.0.1:" + serveur.getAddress().getPort()
                        + "/archive"), List.of("all"), 1, List.of());
    }

    @Test
    void telechargementComplet() throws IOException {
        enregistrerContenu(contenuArchive, Integer.MAX_VALUE, 0);
        String somme = jo.forge.setup.util.Files2.sha256(
                ecrireTemporaire(contenuArchive));
        ComponentManifest composant = composant(somme, contenuArchive.length);

        Path archive = telechargeurLocal().download(composant, null,
                new CancellationToken());

        assertEquals(contenuArchive.length, Files.size(archive));
        assertTrue(archive.getFileName().toString().equals("moteur-0.1.0.zip"));
    }

    @Test
    void repriseApresCoupure() throws IOException {
        // Requête 1 : Content-Length complet, connexion coupée à 100 000 octets —
        // l'appel échoue et le partiel est conservé ; un second appel reprend par Range.
        AtomicInteger etat = new AtomicInteger(0);
        serveur.createContext("/archive", exchange -> {
            String range = exchange.getRequestHeaders().getFirst("Range");
            int appel = etat.getAndIncrement();
            if (range == null && appel == 0) {
                ecrirePuisCouper(exchange, contenuArchive, 100_000);
                return;
            }
            long debut = range == null ? 0
                    : Long.parseLong(range.substring("bytes=".length()).replace("-", ""));
            ecrireReponse(exchange, contenuArchive, debut, contenuArchive.length, 206,
                    contenuArchive.length);
        });
        String somme = jo.forge.setup.util.Files2.sha256(
                ecrireTemporaire(contenuArchive));
        ComponentManifest composant = composant(somme, contenuArchive.length);
        Downloader telechargeur = telechargeurLocal();

        // Premier appel : coupure puis panne du miroir → échec, partiel conservé.
        assertThrows(InstallException.class, () ->
                telechargeur.download(composant, null, new CancellationToken()));
        assertTrue(Files.exists(downloadsDir.resolve("moteur-0.1.0.zip.part")),
                "le partiel doit être conservé pour reprise");
        assertEquals(100_000, Files.size(downloadsDir.resolve("moteur-0.1.0.zip.part")),
                "le partiel contient exactement les octets reçus");

        // Second appel : reprise là où la coupure a eu lieu, sans re-télécharger le début.
        Path archive = telechargeur.download(composant, null, new CancellationToken());
        assertEquals(contenuArchive.length, Files.size(archive));
        assertEquals(somme, jo.forge.setup.util.Files2.sha256(archive));
        // Deux requêtes au total : 1 coupée, 1 reprise partielle (pas de re-téléchargement
        // du début).
        assertEquals(2, etat.get(), "la reprise ne doit pas repartir de zéro");
    }

    /**
     * Annonce la taille complète puis coupe la connexion à mi-corps : le client voit
     * une erreur de lecture (vrai arrêt réseau), pas une fin propre.
     */
    private void ecrirePuisCouper(HttpExchange exchange, byte[] contenu, int octets)
            throws IOException {
        exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
        exchange.sendResponseHeaders(200, contenu.length);
        try (OutputStream sortie = exchange.getResponseBody()) {
            sortie.write(contenu, 0, octets);
            sortie.flush();
        }
        // Fermer brutalement : le client attend encore la fin du corps annoncé.
        exchange.close();
    }

    @Test
    void archiveDejaCompleteReutilisee() throws IOException {
        enregistrerContenu(contenuArchive, Integer.MAX_VALUE, 0);
        String somme = jo.forge.setup.util.Files2.sha256(
                ecrireTemporaire(contenuArchive));
        ComponentManifest composant = composant(somme, contenuArchive.length);

        Path archive = telechargeurLocal().download(composant, null,
                new CancellationToken());

        // Supprimons le contexte réseau : le second appel ne doit pas re-télécharger.
        serveur.removeContext("/archive");
        Path memeArchive = telechargeurLocal().download(composant, null,
                new CancellationToken());
        assertEquals(archive, memeArchive);
    }

    @Test
    void sommeInvalideRejetee() throws IOException {
        enregistrerContenu(contenuArchive, Integer.MAX_VALUE, 0);
        ComponentManifest composant = composant("0".repeat(64), contenuArchive.length);

        assertThrows(InstallException.class, () -> telechargeurLocal()
                .download(composant, null, new CancellationToken()));
    }

    @Test
    void annulationPropre() throws IOException {
        enregistrerContenu(contenuArchive, Integer.MAX_VALUE, 0);
        String somme = jo.forge.setup.util.Files2.sha256(
                ecrireTemporaire(contenuArchive));
        ComponentManifest composant = composant(somme, contenuArchive.length);
        CancellationToken jeton = new CancellationToken();
        jeton.cancel();

        assertThrows(InstallCancelledException.class, () -> telechargeurLocal()
                .download(composant, null, jeton));
    }

    @Test
    void miroirSuivantApresErreur() throws IOException {
        serveur.createContext("/mort", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        String somme = jo.forge.setup.util.Files2.sha256(
                ecrireTemporaire(contenuArchive));
        ComponentManifest composant = new ComponentManifest("moteur", "0.1.0",
                contenuArchive.length, somme,
                List.of("http://127.0.0.1:" + serveur.getAddress().getPort() + "/mort",
                        "http://127.0.0.1:" + serveur.getAddress().getPort() + "/archive"),
                List.of("all"), 1, List.of());
        enregistrerContenu(contenuArchive, Integer.MAX_VALUE, 0);

        Path archive = telechargeurLocal().download(composant, null,
                new CancellationToken());
        assertEquals(contenuArchive.length, Files.size(archive));
    }

    /**
     * Écrit un contenu temporaire pour en calculer la somme.
     */
    private Path ecrireTemporaire(byte[] contenu) throws IOException {
        Path temporaire = tempDir.resolve("reference.bin");
        Files.write(temporaire, contenu);
        return temporaire;
    }
}
