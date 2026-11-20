package jo.forge.app;

import jo.forge.client.EngineClient;
import jo.forge.client.VmLauncher;
import jo.forge.client.VmSpec;
import jo.forge.setup.install.ForgeHome;
import jo.forge.setup.install.InstalledState;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

import jo.forge.api.BuildEvent;

/**
 * Chargement du moteur installé : construit la description de VM depuis l'état
 * d'installation, puis lance et supervise le moteur.
 *
 * <p>Classe volontairement sans dépendance Android : testable sur JVM (le test lance le
 * vrai moteur installé par le module setup dans un vrai processus séparé). La coquille
 * câble ici le résultat de l'installateur ({@code setup}) avec le client
 * ({@code client}) : ces deux modules ne se connaissent pas.</p>
 */
public final class EngineSession {

    /** Classe principale du moteur (point d'entrée de la VM enfant). */
    public static final String ENGINE_MAIN = "jo.forge.engine.bootstrap.EngineMain";

    /** Variable d'environnement transmettant au moteur son répertoire installé
     * (localisation des outils Android du moteur : aapt2, android.jar). */
    public static final String ENV_ENGINE_DIR = "FORGE_ENGINE_DIR";

    /** Tas par défaut du moteur en Mo (décision D-02). */
    public static final int HEAP_DEFAULT_MB = 512;

    /** Tas en mode basse mémoire en Mo. */
    public static final int HEAP_LOW_MEMORY_MB = 256;

    private EngineSession() {
        // Fabriques statiques uniquement.
    }

    /**
     * Construit la description de la VM moteur à partir de l'état installé.
     *
     * <p>Convention de l'artefact moteur : {@code engine.jar} à la racine du répertoire
     * moteur, bibliothèques compagnon dexées dans {@code lib/}.</p>
     *
     * @param home  racine Forge (répertoires connus de l'installateur).
     * @param state état d'installation lu (moteur installé).
     * @param heapMb tas maximum de la VM moteur.
     * @return la description prête à lancer.
     * @throws IllegalStateException si l'installation est incomplète (jar principal
     *                               absent) — à attraper par la coquille pour proposer
     *                               une réparation.
     */
    public static VmSpec specFor(ForgeHome home, InstalledState state, int heapMb) {
        Path engineDir = home.engineDir(state.engineVersion());
        Path engineJar = engineDir.resolve("engine.jar");
        if (!Files.isRegularFile(engineJar)) {
            throw new IllegalStateException(
                    "Installation du moteur invalide : engine.jar manquant dans "
                            + engineDir + " — une réparation est nécessaire");
        }
        List<String> classpath = new ArrayList<>();
        classpath.add(engineJar.toString());
        Path lib = engineDir.resolve("lib");
        if (Files.isDirectory(lib)) {
            List<Path> jars = new ArrayList<>();
            try (DirectoryStream<Path> entrees = Files.newDirectoryStream(lib, "*.jar")) {
                entrees.forEach(jars::add);
            } catch (IOException ioError) {
                throw new IllegalStateException(
                        "Bibliothèques du moteur illisibles : " + ioError.getMessage());
            }
            jars.sort(Comparator.comparing(Path::getFileName));
            jars.forEach(jar -> classpath.add(jar.toString()));
        }
        // Le moteur reçoit son répertoire installé : c'est là qu'il localise ses
        // outils (composants aapt2 et plate-forme éventuellement installés).
        java.util.Map<String, String> env = new java.util.LinkedHashMap<>();
        env.put(ENV_ENGINE_DIR, engineDir.toString());
        return new VmSpec(classpath, ENGINE_MAIN, heapMb, List.of(), env);
    }

    /**
     * Lance le moteur installé et attend la poignée de main.
     *
     * @param launcher      stratégie de lancement (appareil : app_process ; test : java).
     * @param home          racine Forge.
     * @param state         état d'installation.
     * @param shellVersion  version de la coquille (journal côté moteur).
     * @param eventConsumer consommateur des événements de build.
     * @param onDead        rappel de mort du moteur.
     * @param lowMemory     mode basse mémoire (tas réduit).
     * @return le client connecté, protocole négocié.
     */
    public static EngineClient launchInstalled(VmLauncher launcher, ForgeHome home,
                                               InstalledState state, String shellVersion,
                                               Consumer<BuildEvent> eventConsumer,
                                               Runnable onDead, boolean lowMemory) {
        VmSpec spec = specFor(home, state,
                lowMemory ? HEAP_LOW_MEMORY_MB : HEAP_DEFAULT_MB);
        return EngineClient.launch(launcher, spec, shellVersion, eventConsumer, onDead);
    }
}
