package jo.forge.setup.install;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jo.forge.setup.download.CancellationToken;
import jo.forge.setup.download.Downloader;
import jo.forge.setup.download.InstallCancelledException;
import jo.forge.setup.download.InstallException;
import jo.forge.setup.manifest.ComponentManifest;
import jo.forge.setup.manifest.EngineManifest;
import jo.forge.setup.util.Files2;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests de l'installateur : installation complète, ordre des dépendances, atomicité,
 * mise à jour, corruption + réparation, désinstallation, installation interrompue
 * puis reprise (vrai serveur HTTP local), installation hors ligne.
 */
class EngineInstallerTest {

    @TempDir
    Path tempDir;

    private ForgeHome home;
    private Path paquetsDir;
    private HttpServer serveur;

    @BeforeEach
    void preparer() throws IOException {
        home = new ForgeHome(tempDir.resolve("forge"));
        paquetsDir = tempDir.resolve("paquets");
        Files.createDirectories(paquetsDir);
        serveur = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serveur.start();
    }

    @AfterEach
    void arreter() {
        serveur.stop(0);
    }

    // ---- Fabriques ----------------------------------------------------------

    /**
     * Construit une archive de composant sur disque et retourne sa description.
     */
    private ComponentManifest composantLocal(String id, String version,
                                             List<String> depends, String... fichiers)
            throws IOException {
        Path archive = paquetsDir.resolve(id + "-" + version + ".zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (String fichier : fichiers) {
                zip.putNextEntry(new ZipEntry(fichier));
                zip.write(("contenu de " + fichier).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return new ComponentManifest(id, version, Files.size(archive),
                Files2.sha256(archive),
                List.of("http://127.0.0.1:" + serveur.getAddress().getPort() + "/" + id),
                List.of("all"), 1, depends);
    }

    private EngineManifest manifeste(String version, List<ComponentManifest> composants) {
        return new EngineManifest(1, version, 1, "2026-11-20T00:00:00Z", composants);
    }

    private EngineInstaller installateurLocal() {
        return new EngineInstaller(home,
                new ComponentSource.LocalDirectory(paquetsDir), new ArchiveExtractor());
    }

    // ---- Scénarios ----------------------------------------------------------

    @Test
    void installationCompleteAvecDependancesDansLOrdre() throws IOException {
        ComponentManifest coeur = composantLocal("engine-core", "0.1.0", List.of(),
                "engine.jar", "lib/ecj.jar");
        ComponentManifest plateforme = composantLocal("platform-34", "1.0.0",
                List.of("engine-core"), "android-34.jar");
        InstalledState etat = installateurLocal().install(
                manifeste("0.1.0", List.of(plateforme, coeur)), "arm64-v8a", null,
                new CancellationToken());

        assertEquals("0.1.0", etat.engineVersion());
        // engine-core (dépendance) précède platform-34 dans l'état installé.
        assertEquals("engine-core", etat.components().get(0).id());
        assertEquals("platform-34", etat.components().get(1).id());

        Path moteur = home.engineDir("0.1.0");
        assertTrue(Files.exists(moteur.resolve("engine.jar")));
        assertTrue(Files.exists(moteur.resolve("android-34.jar")));

        // Lecture seule posée (exigence ART pour le code chargé).
        assertFalse(moteur.resolve("engine.jar").toFile().canWrite(),
                "les fichiers du moteur doivent être en lecture seule");

        // Vérification d'intégrité : rien d'altéré.
        assertTrue(installateurLocal().verify().isEmpty());

        // Pas d'archive ni de staging résiduel.
        assertFalse(Files.exists(home.downloads()), "les archives sont purgées");
        try (var chemins = Files.list(home.engines())) {
            assertTrue(chemins.filter(p -> p.getFileName().toString()
                            .startsWith(".staging")).findAny().isEmpty(),
                    "aucun staging résiduel");
        }
    }

    @Test
    void echecDuneDependanceLaisseLEtatIntact() throws IOException {
        // engine-core valide, platform corrompue : somme ne correspondra pas.
        ComponentManifest coeur = composantLocal("engine-core", "0.1.0", List.of(),
                "engine.jar");
        ComponentManifest plateforme = composantLocal("platform-34", "1.0.0",
                List.of("engine-core"), "android-34.jar");
        // Altération de l'archive locale après calcul de la somme.
        Path archive = paquetsDir.resolve("platform-34-1.0.0.zip");
        Files.write(archive, "contenu falsifié".getBytes(StandardCharsets.UTF_8));

        assertThrows(InstallException.class, () -> installateurLocal().install(
                manifeste("0.1.0", List.of(plateforme, coeur)), "arm64-v8a", null,
                new CancellationToken()));
        assertNull(installateurLocal().currentState(),
                "aucun état ne doit exister après échec");
        assertFalse(Files.exists(home.engineDir("0.1.0")),
                "aucun moteur ne doit être visible après échec");
    }

    @Test
    void miseAJourRemplaceLancienMoteur() throws IOException {
        ComponentManifest v1 = composantLocal("engine-core", "0.1.0", List.of(),
                "engine.jar");
        installateurLocal().install(manifeste("0.1.0", List.of(v1)), "arm64-v8a", null,
                new CancellationToken());

        ComponentManifest v2 = composantLocal("engine-core", "0.2.0", List.of(),
                "engine.jar", "lib/nouveaute.jar");
        InstalledState etat = installateurLocal().install(
                manifeste("0.2.0", List.of(v2)), "arm64-v8a", null, new CancellationToken());

        assertEquals("0.2.0", etat.engineVersion());
        assertFalse(Files.exists(home.engineDir("0.1.0")),
                "l'ancien moteur est supprimé après la mise à jour");
        assertTrue(Files.exists(home.engineDir("0.2.0").resolve("lib/nouveaute.jar")));
    }

    @Test
    void corruptionDetecteePuisReparation() throws IOException {
        ComponentManifest coeur = composantLocal("engine-core", "0.1.0", List.of(),
                "engine.jar");
        EngineInstaller installateur = installateurLocal();
        installateur.install(manifeste("0.1.0", List.of(coeur)), "arm64-v8a", null,
                new CancellationToken());

        // Falsification d'un fichier du moteur installé (droits réactivés pour le test).
        Path fichier = home.engineDir("0.1.0").resolve("engine.jar");
        fichier.toFile().setWritable(true);
        Files.write(fichier, "code falsifié".getBytes(StandardCharsets.UTF_8));

        List<String> alteres = installateur.verify();
        assertEquals(1, alteres.size(), "la corruption doit être détectée");

        // Réparation : réinstallation complète de la même version.
        installateur.install(manifeste("0.1.0", List.of(coeur)), "arm64-v8a", null,
                new CancellationToken());
        assertTrue(installateur.verify().isEmpty(), "après réparation, tout est sain");
    }

    @Test
    void desinstallation() throws IOException {
        ComponentManifest coeur = composantLocal("engine-core", "0.1.0", List.of(),
                "engine.jar");
        EngineInstaller installateur = installateurLocal();
        installateur.install(manifeste("0.1.0", List.of(coeur)), "arm64-v8a", null,
                new CancellationToken());

        assertTrue(installateur.uninstall());
        assertNull(installateur.currentState());
        assertFalse(Files.exists(home.engineDir("0.1.0")));
        assertFalse(installateur.uninstall(), "une seconde désinstallation ne fait rien");
    }

    @Test
    void installationInterrompuePuisReprise() throws IOException {
        // Le serveur servira exactement les octets de cette archive.
        Path archiveZip = tempDir.resolve("engine.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archiveZip))) {
            zip.putNextEntry(new ZipEntry("engine.jar"));
            zip.write(new byte[8192]);
            zip.closeEntry();
        }
        final byte[] contenu = Files.readAllBytes(archiveZip);

        // Requête 1 : corps annoncé complet, coupure à 4 096 octets → l'installation
        // échoue proprement ; appel suivant : reprise Range puis installation complète.
        AtomicInteger etat = new AtomicInteger(0);
        serveur.createContext("/engine-core", exchange -> {
            String range = exchange.getRequestHeaders().getFirst("Range");
            int appel = etat.getAndIncrement();
            exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
            if (range == null && appel == 0) {
                // Annonce la taille complète puis coupe à mi-corps (vrai arrêt réseau).
                exchange.sendResponseHeaders(200, contenu.length);
                try (OutputStream sortie = exchange.getResponseBody()) {
                    sortie.write(contenu, 0, 4096);
                    sortie.flush();
                }
                exchange.close();
                return;
            }
            long debut = range == null ? 0
                    : Long.parseLong(range.substring("bytes=".length()).replace("-", ""));
            if (range != null) {
                exchange.getResponseHeaders().set("Content-Range",
                        "bytes " + debut + "-" + (contenu.length - 1) + "/" + contenu.length);
            }
            exchange.sendResponseHeaders(range == null ? 200 : 206,
                    contenu.length - debut);
            try (OutputStream sortie = exchange.getResponseBody()) {
                sortie.write(contenu, (int) debut, (int) (contenu.length - debut));
            }
        });

        ComponentManifest composant = new ComponentManifest("engine-core", "0.1.0",
                contenu.length, Files2.sha256(archiveZip),
                List.of("http://127.0.0.1:" + serveur.getAddress().getPort()
                        + "/engine-core"), List.of("all"), 1, List.of());

        Downloader telechargeur = new Downloader(home.downloads(),
                java.util.Set.of("http", "https"));
        EngineInstaller installateur = new EngineInstaller(home,
                new ComponentSource.Network(telechargeur), new ArchiveExtractor());

        // Première tentative : coupure réseau à mi-téléchargement → échec propre,
        // aucun moteur visible, partiel conservé.
        assertThrows(InstallException.class, () -> installateur.install(
                manifeste("0.1.0", List.of(composant)), "arm64-v8a", null,
                new CancellationToken()));
        assertNull(installateur.currentState());
        assertTrue(Files.exists(home.downloads().resolve("engine-core-0.1.0.zip.part")),
                "le partiel est conservé pour la reprise");

        // Seconde tentative : reprise (Range bytes=4096-) puis installation complète.
        installateur.install(manifeste("0.1.0", List.of(composant)), "arm64-v8a", null,
                new CancellationToken());
        assertNotNull(installateur.currentState());
        assertTrue(installateur.verify().isEmpty());
        assertEquals(2, etat.get(), "la reprise ne repart pas de zéro");
    }

    @Test
    void annulationPendantLinstallation() throws IOException {
        ComponentManifest coeur = composantLocal("engine-core", "0.1.0", List.of(),
                "engine.jar");
        CancellationToken jeton = new CancellationToken();
        jeton.cancel();
        assertThrows(InstallCancelledException.class, () -> installateurLocal().install(
                manifeste("0.1.0", List.of(coeur)), "arm64-v8a", null, jeton));
        assertNull(installateurLocal().currentState());
    }

    @Test
    void filtreAbiRespecte() throws IOException {
        ComponentManifest coeur = composantLocal("engine-core", "0.1.0", List.of(),
                "engine.jar");
        // Composant aapt2 pour une autre ABI : ne doit pas être installé.
        Path archive = paquetsDir.resolve("aapt2-x86_64-1.0.0.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("bin/aapt2"));
            zip.write("binaire".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        ComponentManifest aapt2X86 = new ComponentManifest("aapt2-x86_64", "1.0.0",
                Files.size(archive), Files2.sha256(archive), List.of("https://x.fr/a"),
                List.of("x86_64"), 1, List.of());

        InstalledState etat = installateurLocal().install(
                manifeste("0.1.0", List.of(coeur, aapt2X86)), "arm64-v8a", null,
                new CancellationToken());
        assertNull(etat.component("aapt2-x86_64"),
                "le composant d'une autre ABI n'est pas installé");
        assertFalse(Files.exists(home.engineDir("0.1.0").resolve("bin/aapt2")));
    }

    private void ecrire(HttpExchange exchange, byte[] contenu, long debut, long fin,
                        int code) throws IOException {
        exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
        if (code == 206) {
            exchange.getResponseHeaders().set("Content-Range",
                    "bytes " + debut + "-" + (fin - 1) + "/" + contenu.length);
        }
        exchange.sendResponseHeaders(code, fin - debut);
        try (OutputStream sortie = exchange.getResponseBody()) {
            sortie.write(contenu, (int) debut, (int) (fin - debut));
        }
    }
}
