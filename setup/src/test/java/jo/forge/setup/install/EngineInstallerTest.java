package jo.forge.setup.install;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jo.forge.setup.download.CancellationToken;
import jo.forge.setup.download.Downloader;
import jo.forge.setup.download.InstallCancelledException;
import jo.forge.setup.download.InstallException;
import jo.forge.setup.manifest.ComponentManifest;
import jo.forge.setup.manifest.EngineManifest;
import jo.forge.setup.manifest.EmpreinteContenu;
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
     * Construit une archive de composant sur disque et retourne sa description
     * (sans empreinte : manifestes antérieurs à v0.2.9).
     */
    private ComponentManifest composantLocal(String id, String version,
                                             List<String> depends, String... fichiers)
            throws IOException {
        return composant(id, version, depends, null, fichiers);
    }

    /**
     * Construit une archive de composant AVEC empreinte de contenu (manifestes
     * v0.2.9+) — la même archive donne la même empreinte (réutilisation).
     */
    private ComponentManifest composantAvecEmpreinte(String id, String version,
                                                     List<String> depends,
                                                     String... fichiers)
            throws IOException {
        return composant(id, version, depends, "", fichiers);
    }

    private ComponentManifest composant(String id, String version, List<String> depends,
                                         String empreinteOuNull, String... fichiers)
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
                List.of("all"), 1, depends,
                empreinteOuNull == null ? null : EmpreinteContenu.deArchive(archive));
    }

    /** Source qui compte ses téléchargements (économie de données). */
    private static final class SourceComptée implements ComponentSource {
        private final ComponentSource délégué;
        final List<String> téléchargés = new ArrayList<>();

        SourceComptée(ComponentSource délégué) {
            this.délégué = délégué;
        }

        @Override
        public Path fetch(ComponentManifest component, Downloader.ProgressListener progress,
                         CancellationToken cancel) {
            téléchargés.add(component.id());
            return délégué.fetch(component, progress, cancel);
        }
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
                new CancellationToken()).état();

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
    void archiveDontLEmpreinteDivergeDuManifesteRefusée() throws IOException {
        // Régression v0.2.20 (rapport appareil v0.2.19) : la chaîne de publication
        // signait pour kotlin-compiler une empreinte de contenu SANS le jar du
        // plugin Compose tandis que l'archive publiée le portait. Un appareil sans
        // cache échouait sur la somme ; un appareil AVEC cache servait
        // silencieusement le contenu périmé — plugin jamais installé, bouton
        // Réparer impuissant. Ici la SOMME est honnête mais l'EMPREINTE ment :
        // l'installateur doit refuser nommément, AVANT extraction, état intact.
        Path archive = paquetsDir.resolve("kotlin-compiler-0.2.20.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("lib/kotlin-compiler.jar"));
            zip.write("dex du compilateur".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("lib/compose-compiler.jar"));
            zip.write("dex du plugin".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        // Le contenu périmé : l'archive sans le plugin, celle que l'empreinte des
        // manifestes v0.2.15–v0.2.19 décrivait.
        Path périmée = paquetsDir.resolve("kotlin-compiler-perime.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(périmée))) {
            zip.putNextEntry(new ZipEntry("lib/kotlin-compiler.jar"));
            zip.write("dex du compilateur".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        ComponentManifest menteur = new ComponentManifest("kotlin-compiler", "0.2.20",
                Files.size(archive), Files2.sha256(archive),
                List.of("http://127.0.0.1:" + serveur.getAddress().getPort()
                        + "/kotlin-compiler"),
                List.of("all"), 1, List.of(), EmpreinteContenu.deArchive(périmée));

        EngineInstaller installateur = installateurLocal();
        InstallException refus = assertThrows(InstallException.class,
                () -> installateur.install(manifeste("0.2.20", List.of(menteur)),
                        "arm64-v8a", null, new CancellationToken()));
        assertTrue(refus.getMessage().contains("empreinte"),
                "le refus nomme l'empreinte : " + refus.getMessage());
        assertTrue(refus.getMessage().contains("kotlin-compiler"),
                "le refus nomme le composant : " + refus.getMessage());
        assertNull(installateur.currentState(), "aucun état après refus");
        assertFalse(Files.exists(home.engineDir("0.2.20")),
                "aucun moteur visible après refus");
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
                manifeste("0.2.0", List.of(v2)), "arm64-v8a", null,
                new CancellationToken()).état();

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
                new CancellationToken()).état();
        assertNull(etat.component("aapt2-x86_64"),
                "le composant d'une autre ABI n'est pas installé");
        assertFalse(Files.exists(home.engineDir("0.1.0").resolve("bin/aapt2")));
    }

    @Test
    void miseAJourSansRetéléchargerLesComposantsInchangés() throws IOException {
        // v1 : deux composants AVEC empreinte (manifeste v0.2.9+).
        ComponentManifest coeurV1 = composantAvecEmpreinte("engine-core", "0.1.0",
                List.of(), "engine.jar");
        ComponentManifest plateforme = composantAvecEmpreinte("platform-34", "34.0.0",
                List.of("engine-core"), "components/platform-34/android.jar");
        SourceComptée source = new SourceComptée(
                new ComponentSource.LocalDirectory(paquetsDir));
        EngineInstaller installateur = new EngineInstaller(home, source,
                new ArchiveExtractor());
        installateur.install(manifeste("0.1.0", List.of(coeurV1, plateforme)),
                "arm64-v8a", null, new CancellationToken());
        assertEquals(List.of("engine-core", "platform-34"), source.téléchargés,
                "la première installation télécharge tout");

        // v2 : le moteur change, la plate-forme est IDENTIQUE (même archive).
        ComponentManifest coeurV2 = composantAvecEmpreinte("engine-core", "0.2.0",
                List.of(), "engine.jar", "lib/nouveaute.jar");
        EngineInstaller.RésultatInstallation résultat = installateur.install(
                manifeste("0.2.0", List.of(coeurV2, plateforme)),
                "arm64-v8a", null, new CancellationToken());

        assertEquals(List.of("platform-34"), résultat.composantsRéutilisés(),
                "la plate-forme inchangée est servie par le cache");
        assertEquals(3, source.téléchargés.size(),
                "seul le nouveau moteur est téléchargé");
        assertEquals(plateforme.size(), résultat.octetsÉvités(),
                "les octets évités valent la taille du composant réutilisé");
        assertTrue(installateur.verify().isEmpty(),
                "le moteur assemblé depuis le cache est intègre");
        assertFalse(Files.exists(home.engineDir("0.1.0")), "l'ancien moteur part");
    }

    @Test
    void amorçageDepuisUnMoteurSansEmpreinte() throws IOException {
        // Génération v0.2.8 : manifeste SANS empreinte (les composants sont alors
        // toujours téléchargés, même identiques).
        ComponentManifest coeurVieux = composantLocal("engine-core", "0.2.8",
                List.of(), "engine.jar");
        ComponentManifest plateformeVieille = composantLocal("platform-34", "0.2.8",
                List.of("engine-core"), "components/platform-34/android.jar");
        SourceComptée source = new SourceComptée(
                new ComponentSource.LocalDirectory(paquetsDir));
        EngineInstaller installateur = new EngineInstaller(home, source,
                new ArchiveExtractor());
        installateur.install(
                manifeste("0.2.8", List.of(coeurVieux, plateformeVieille)),
                "arm64-v8a", null, new CancellationToken());

        // Génération v0.2.9 : empreintes au manifeste, MÊME contenu de plate-forme
        // (fichiers identiques → même empreinte que l'état installé).
        ComponentManifest coeurNeuf = composantAvecEmpreinte("engine-core", "0.2.9",
                List.of(), "engine.jar", "lib/nouveaute.jar");
        ComponentManifest plateformeNeuve = composantAvecEmpreinte("platform-34",
                "34.0.0.abcdef01", List.of("engine-core"),
                "components/platform-34/android.jar");
        EngineInstaller.RésultatInstallation résultat = installateur.install(
                manifeste("0.2.9", List.of(coeurNeuf, plateformeNeuve)),
                "arm64-v8a", null, new CancellationToken());

        // La plate-forme a été AMORCÉE depuis le moteur 0.2.8 : aucun octet réseau.
        assertEquals(List.of("platform-34"), résultat.composantsRéutilisés());
        assertEquals(3, source.téléchargés.size(),
                "seul engine-core a été re-téléchargé (2 installs + 1 moteur)");
        assertTrue(installateur.verify().isEmpty());
    }

    @Test
    void cacheAltéréRetéléchargeEtSeRépare() throws IOException {
        ComponentManifest coeur = composantAvecEmpreinte("engine-core", "0.1.0",
                List.of(), "engine.jar");
        SourceComptée source = new SourceComptée(
                new ComponentSource.LocalDirectory(paquetsDir));
        EngineInstaller installateur = new EngineInstaller(home, source,
                new ArchiveExtractor());
        installateur.install(manifeste("0.1.0", List.of(coeur)),
                "arm64-v8a", null, new CancellationToken());

        // Désinstallation : le moteur part, l'ÉTAT part, le cache RESTE (c'est son
        // but) — l'amorçage ne pourra donc pas masquer l'altération qui suit.
        installateur.uninstall();

        // Altération du fichier caché : l'entrée ne doit plus être servie.
        Path fichierCaché = home.cacheComposants()
                .resolve(coeur.empreinte()).resolve("contenu").resolve("engine.jar");
        assertTrue(Files.isRegularFile(fichierCaché), "le composant est en cache");
        fichierCaché.toFile().setWritable(true, true);
        Files.write(fichierCaché, "falsifié".getBytes(StandardCharsets.UTF_8));
        assertFalse(installateur.composantEnCache(coeur),
                "une entrée altérée n'est plus réutilisable");

        // L'installation re-télécharge le composant altéré et répare le cache :
        // un cache douteux ne doit JAMAIS contaminer le moteur installé.
        ComponentManifest mêmeContenu = composantAvecEmpreinte("engine-core", "0.2.0",
                List.of(), "engine.jar");
        EngineInstaller.RésultatInstallation résultat = installateur.install(
                manifeste("0.2.0", List.of(mêmeContenu)), "arm64-v8a", null,
                new CancellationToken());
        assertTrue(résultat.composantsRéutilisés().isEmpty(),
                "rien n'est réutilisé depuis une entrée altérée");
        assertEquals(2, source.téléchargés.size(),
                "le composant altéré a été re-téléchargé");
        assertTrue(installateur.composantEnCache(mêmeContenu),
                "le composant re-téléchargé a repeuplé le cache");
        assertTrue(installateur.verify().isEmpty());
    }

    @Test
    void réparationComplèteSansRéseau() throws IOException {
        // Installation initiale (remplit le cache)…
        ComponentManifest coeur = composantAvecEmpreinte("engine-core", "0.1.0",
                List.of(), "engine.jar");
        ComponentManifest plateforme = composantAvecEmpreinte("platform-34", "34.0.0",
                List.of("engine-core"), "components/platform-34/android.jar");
        EngineInstaller installateur = installateurLocal();
        installateur.install(manifeste("0.1.0", List.of(coeur, plateforme)),
                "arm64-v8a", null, new CancellationToken());

        // …puis RÉPARATION (même version) avec une source qui échoue toujours :
        // le cache couvre tout, aucun réseau n'est nécessaire.
        ComponentSource sourceMorte = (composant, progress, cancel) -> {
            throw new InstallException("réseau coupé : " + composant.id());
        };
        EngineInstaller horsLigne = new EngineInstaller(home, sourceMorte,
                new ArchiveExtractor());
        EngineInstaller.RésultatInstallation résultat = horsLigne.install(
                manifeste("0.1.0", List.of(plateforme, coeur)), "arm64-v8a", null,
                new CancellationToken());

        assertEquals(List.of("engine-core", "platform-34"),
                résultat.composantsRéutilisés(), "tout vient du cache");
        assertEquals(coeur.size() + plateforme.size(), résultat.octetsÉvités());
        assertTrue(horsLigne.verify().isEmpty(), "le moteur réparé est intègre");
    }

    @Test
    void désinstallationConserveLeCacheEtLaPurgeLeSupprime() throws IOException {
        ComponentManifest coeur = composantAvecEmpreinte("engine-core", "0.1.0",
                List.of(), "engine.jar");
        EngineInstaller installateur = installateurLocal();
        installateur.install(manifeste("0.1.0", List.of(coeur)),
                "arm64-v8a", null, new CancellationToken());

        installateur.uninstall();
        assertTrue(installateur.composantEnCache(coeur),
                "la désinstallation ne touche pas au cache (économie conservée)");
        assertTrue(installateur.tailleCache() > 0);

        assertTrue(installateur.purgerCache());
        assertFalse(installateur.composantEnCache(coeur),
                "après purge, le composant n'est plus en cache");
        assertEquals(0, installateur.tailleCache());
    }

    /**
     * Constat M-1 : une réinstallation tuée entre la mise à l'écart de l'ancien
     * moteur ({@code .old-<uuid>}) et l'arrivée du nouveau laissait l'état pointer
     * sur un répertoire ABSENT — plus de moteur, sans retour arrière. Le démarrage
     * rétablit désormais l'ancien automatiquement.
     */
    @Test
    void réinstallationInterrompueRéparéeAuDémarrage() throws IOException {
        ComponentManifest coeur = composantLocal("engine-core", "0.1.0", List.of(),
                "engine.jar");
        EngineInstaller installateur = installateurLocal();
        installateur.install(manifeste("0.1.0", List.of(coeur)),
                "all", null, new CancellationToken());
        Path moteur = home.engineDir("0.1.0");

        // Simuler le meurtre : l'ancien moteur mis à l'écart, jamais remplacé.
        Path écart = home.engines().resolve(".old-simulé");
        Files.move(moteur, écart);
        assertFalse(Files.exists(moteur), "précondition : le moteur est absent");

        assertTrue(installateur.rétablirSiInterrompue(),
                "l'ancien moteur doit être rétabli");
        assertTrue(Files.exists(moteur), "le moteur est de retour à sa place");
        assertTrue(installateur.verify().isEmpty(), "le moteur rétabli est sain");
        assertFalse(Files.exists(écart), "la mise à l'écart est consommée");
    }

    /**
     * Constat M-1 : les préparations ({@code .staging-*}) et mises à l'écart
     * ({@code .old-*}) orphelines d'installations mortes avant le basculement
     * d'état sont purgées au démarrage — et un moteur sain en place n'est jamais
     * touché par ce nettoyage.
     */
    @Test
    void interrompusOrphelinsPurgésAuDémarrage() throws IOException {
        ComponentManifest coeur = composantLocal("engine-core", "0.1.0", List.of(),
                "engine.jar");
        EngineInstaller installateur = installateurLocal();
        installateur.install(manifeste("0.1.0", List.of(coeur)),
                "all", null, new CancellationToken());

        Path staging = home.engines().resolve(".staging-orphelin");
        Path vieux = home.engines().resolve(".old-orphelin");
        Files.createDirectories(staging);
        Files.createDirectories(vieux);
        Files.writeString(staging.resolve("bidule"), "restes");

        assertFalse(installateur.rétablirSiInterrompue(),
                "un moteur en place n'a rien à rétablir");
        assertFalse(Files.exists(staging), "le staging orphelin est purgé");
        assertFalse(Files.exists(vieux), "le .old orphelin est purgé");
        assertTrue(installateur.verify().isEmpty(), "le moteur en place est intact");
    }

    /**
     * Constat M-2 : un manifeste signé mais ANCIEN — rejoué par un miroir périmé ou
     * un canal compromis — est refusé dès qu'une séquence plus récente est installée.
     * La réparation (même séquence) et la mise à jour (plus récente) passent ; un
     * manifeste sans séquence est refusé quand le recul devient indéterminable.
     */
    @Test
    void rejeuDUnManifesteAncienRefuséAntiRecul() throws IOException {
        ComponentManifest coeur = composantLocal("engine-core", "0.1.0", List.of(),
                "engine.jar");
        EngineInstaller installateur = installateurLocal();

        // État antérieur au mécanisme (séquence 0) : rien n'est refusé.
        installateur.install(manifeste("0.1.0", List.of(coeur)),
                "all", null, new CancellationToken());
        assertNull(installateur.refusAntiRecul(
                new EngineManifest(1, "0.2.0", 1, "date", List.of(coeur))),
                "sans verrou posé, aucun refus");

        // Installation séquencée : le verrou est mémorisé dans l'état.
        installateur.install(new EngineManifest(1, "0.2.0", 1, "date",
                List.of(coeur), 42), "all", null, new CancellationToken());
        assertEquals(42, installateur.currentState().dernièreSéquence(),
                "la séquence installée est mémorisée");

        // Rejeu d'un manifeste plus ancien : refusé.
        assertNotNull(installateur.refusAntiRecul(new EngineManifest(1, "0.3.0", 1,
                "date", List.of(coeur), 41)), "le recul est refusé");
        // Manifeste sans séquence : refusé aussi (recul indéterminable).
        assertNotNull(installateur.refusAntiRecul(
                new EngineManifest(1, "0.3.0", 1, "date", List.of(coeur))),
                "sans séquence, le recul est indéterminable : refusé");
        // Réinstallation de la MÊME séquence (réparation) : acceptée.
        assertNull(installateur.refusAntiRecul(new EngineManifest(1, "0.2.0", 1,
                "date", List.of(coeur), 42)), "la réparation passe");
        // Mise à jour plus récente : acceptée.
        assertNull(installateur.refusAntiRecul(new EngineManifest(1, "0.3.0", 1,
                "date", List.of(coeur), 43)), "la mise à jour passe");

        // L'échappatoire volontaire : la désinstallation efface l'état ET le verrou.
        installateur.uninstall();
        assertNull(installateur.refusAntiRecul(new EngineManifest(1, "0.1.0", 1,
                "date", List.of(coeur))), "après désinstallation, plus de verrou");
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
