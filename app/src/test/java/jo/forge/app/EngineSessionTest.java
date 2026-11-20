package jo.forge.app;

import jo.forge.api.BuildEvent;
import jo.forge.api.EngineState;
import jo.forge.api.Status;
import jo.forge.client.EngineClient;
import jo.forge.client.JavaVmLauncher;
import jo.forge.client.VmSpec;
import jo.forge.setup.download.CancellationToken;
import jo.forge.setup.install.ArchiveExtractor;
import jo.forge.setup.install.ComponentSource;
import jo.forge.setup.install.EngineInstaller;
import jo.forge.setup.install.ForgeHome;
import jo.forge.setup.install.InstalledState;
import jo.forge.setup.manifest.ComponentManifest;
import jo.forge.setup.manifest.EngineManifest;
import jo.forge.setup.util.Files2;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test de bout en bout de l'étape 4.4 : le moteur VRAI (module engine) est installé
 * comme composant par le module setup (archive, sommes, installation atomique), puis
 * chargé depuis l'état installé et lancé dans un VRAI processus séparé — poignée de
 * main, ping, état, arrêt propre.
 *
 * <p>C'est la chaîne complète coquille → installateur → état → lanceur → moteur, sans
 * aucune maquette : le seul écart avec l'appareil est le lanceur ({@code java} ici,
 * {@code app_process} sur appareil — signalé dans limites-et-suite.md).</p>
 */
class EngineSessionTest {

    @TempDir
    Path tempDir;

    /**
     * Trouve un jar du classpath de test par son nom de fichier.
     */
    private Path jarDuClasspath(String nom) {
        for (String entree : System.getProperty("java.class.path")
                .split(java.io.File.pathSeparator)) {
            Path chemin = Path.of(entree);
            if (chemin.getFileName().toString().equals(nom)) {
                return chemin;
            }
        }
        throw new IllegalStateException("Jar introuvable sur le classpath : " + nom);
    }

    /**
     * Construit l'archive du composant engine-core : le vrai jar du moteur et celui du
     * contrat api, comme l'artefact de production les disposera.
     */
    private ComponentManifest composantMoteur(Path paquets) throws IOException {
        Path archive = paquets.resolve("engine-core-0.1.0.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("engine.jar"));
            Files.copy(jarDuClasspath("engine-0.1.0.jar"), zip);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("lib/api.jar"));
            Files.copy(jarDuClasspath("api-0.1.0.jar"), zip);
            zip.closeEntry();
        }
        return new ComponentManifest("engine-core", "0.1.0", Files.size(archive),
                Files2.sha256(archive), List.of("https://exemple.fr/engine-core.zip"),
                List.of("all"), 1, List.of());
    }

    @Test
    @Timeout(90)
    void chargerEtLancerLeMoteurInstalle() throws Exception {
        ForgeHome home = new ForgeHome(tempDir.resolve("forge"));
        Path paquets = tempDir.resolve("paquets");
        Files.createDirectories(paquets);
        ComponentManifest composant = composantMoteur(paquets);
        EngineManifest manifeste = new EngineManifest(1, "0.1.0", 1, "2026-11-20",
                List.of(composant));

        // 1. Installation (module setup) : atomique, sommes, lecture seule.
        EngineInstaller installateur = new EngineInstaller(home,
                new ComponentSource.LocalDirectory(paquets), new ArchiveExtractor());
        InstalledState etat = installateur.install(manifeste, "arm64-v8a", null,
                new CancellationToken());
        assertEquals("0.1.0", etat.engineVersion());
        assertTrue(installateur.verify().isEmpty());

        // 2. Chargement depuis l'état installé (EngineSession).
        VmSpec spec = EngineSession.specFor(home, etat, 256);
        assertEquals(EngineSession.ENGINE_MAIN, spec.mainClass());
        assertEquals(2, spec.classpathEntries().size(),
                "engine.jar + lib/api.jar attendus sur le classpath");

        // 3. Lancement dans un vrai processus séparé + poignée de main + ping + état.
        EngineClient client = EngineSession.launchInstalled(
                new JavaVmLauncher(), home, etat, "0.1.0-test",
                (BuildEvent evenement) -> { }, () -> { }, false);
        assertEquals("0.1.0", client.hello().engineVersion());
        long pong = client.ping().get(15, TimeUnit.SECONDS);
        assertTrue(pong > 0);
        Status statut = client.status().get(15, TimeUnit.SECONDS);
        assertEquals(EngineState.IDLE, statut.state());

        // 4. Arrêt propre : accusé puis code de sortie 0.
        client.shutdown().get(15, TimeUnit.SECONDS);
        assertEquals(0, client.awaitExit());
    }

    @Test
    void installationIncompleteRefuseeAvecMessageClair() throws IOException {
        ForgeHome home = new ForgeHome(tempDir.resolve("forge"));
        Path paquets = tempDir.resolve("paquets");
        Files.createDirectories(paquets);
        ComponentManifest composant = composantMoteur(paquets);

        EngineInstaller installateur = new EngineInstaller(home,
                new ComponentSource.LocalDirectory(paquets), new ArchiveExtractor());
        installateur.install(new EngineManifest(1, "0.1.0", 1, "2026-11-20",
                List.of(composant)), "arm64-v8a", null, new CancellationToken());

        // Sabotage : engine.jar retiré de l'installation (réécriture autorisée pour le
        // besoin du test).
        Path moteur = home.engineDir("0.1.0");
        moteur.toFile().setWritable(true);
        Files.delete(moteur.resolve("engine.jar"));

        InstalledState etat = installateur.currentState();
        assertNotNull(etat);
        IllegalStateException erreur = assertThrows(IllegalStateException.class,
                () -> EngineSession.specFor(home, etat, 256));
        assertTrue(erreur.getMessage().contains("réparation"),
                "le message doit proposer une réparation : " + erreur.getMessage());
    }
}
