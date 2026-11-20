package jo.forge.app;

import jo.forge.setup.download.CancellationToken;
import jo.forge.setup.download.Downloader;
import jo.forge.setup.install.ComponentSource;
import jo.forge.setup.manifest.ComponentManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests de la progression globale de l'installation : les octets des composants
 * terminés s'additionnent, la progression ne recule jamais, le total annoncé est la
 * somme des tailles des composants applicables.
 */
class SourceProgressiveTest {

    @TempDir
    Path temporaire;

    /** Source factice : chaque octet du composant est « reçu » d'un bloc. */
    private static final class SourceFactice implements ComponentSource {
        private final Path répertoire;

        private SourceFactice(Path répertoire) {
            this.répertoire = répertoire;
        }

        @Override
        public Path fetch(ComponentManifest component, Downloader.ProgressListener progress,
                          CancellationToken cancel) {
            try {
                Path archive = répertoire.resolve(component.id() + ".zip");
                Files.write(archive, new byte[(int) component.size()]);
                if (progress != null) {
                    progress.onProgress(component.size(), component.size());
                }
                return archive;
            } catch (IOException ioError) {
                throw new IllegalStateException(ioError);
            }
        }
    }

    private static ComponentManifest composant(String id, long taille) {
        return new ComponentManifest(id, "0.2.0", taille, "a".repeat(64),
                List.of("https://exemple.fr/" + id + ".zip"), List.of("all"), 1,
                List.of());
    }

    @Test
    void progressionGlobaleCumuleLesComposantsTerminés() throws IOException {
        List<ComponentManifest> composants = List.of(
                composant("engine-core", 100), composant("java-compiler", 300));
        List<long[]> rapports = new ArrayList<>();
        Files.createDirectories(temporaire);
        SourceProgressive source = new SourceProgressive(
                new SourceFactice(temporaire), composants,
                (reçus, total) -> rapports.add(new long[]{reçus, total}));

        source.fetch(composants.get(0), null, new CancellationToken());
        source.fetch(composants.get(1), null, new CancellationToken());

        // Deux rapports par composant : les octets reçus, puis le composant compté
        // complet (utile quand une archive est validée d'emblée, reprise incluse).
        assertEquals(4, rapports.size());
        assertEquals(100, rapports.get(0)[0]);
        assertEquals(400, rapports.get(0)[1], "total global = somme des tailles");
        assertEquals(100, rapports.get(1)[0], "composant 1 complet : 100 octets");
        assertEquals(400, rapports.get(2)[0], "composant 2 : octets reçus cumulés");
        assertEquals(400, rapports.get(3)[0], "composant 2 complet : 400 octets");
        assertEquals(400, rapports.get(3)[1]);
    }

    @Test
    void composantsEnCacheExclusDuTotalAnnoncé() throws IOException {
        // v0.2.9 : platform-34 est en cache local — il ne traverse pas le réseau,
        // le total annoncé ne compte que ce qui sera réellement téléchargé.
        List<ComponentManifest> composants = List.of(
                composant("engine-core", 100), composant("platform-34", 900));
        List<long[]> rapports = new ArrayList<>();
        Files.createDirectories(temporaire);
        SourceProgressive source = new SourceProgressive(
                new SourceFactice(temporaire), composants,
                java.util.Set.of("platform-34"),
                (reçus, total) -> rapports.add(new long[]{reçus, total}));

        // Seul engine-core est demandé (le cache sert platform-34).
        source.fetch(composants.get(0), null, new CancellationToken());

        assertEquals(100, rapports.get(rapports.size() - 1)[0]);
        assertEquals(100, rapports.get(rapports.size() - 1)[1],
                "le total global exclut les composants servis par le cache");
    }

    @Test
    void écouteurNulAcceptéSansÉchec() throws IOException {
        List<ComponentManifest> composants = List.of(composant("engine-core", 50));
        Files.createDirectories(temporaire);
        SourceProgressive source = new SourceProgressive(
                new SourceFactice(temporaire), composants, null);
        assertEquals(temporaire.resolve("engine-core.zip"),
                source.fetch(composants.get(0), null, new CancellationToken()));
    }
}
