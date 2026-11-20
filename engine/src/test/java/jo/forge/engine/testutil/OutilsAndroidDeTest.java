package jo.forge.engine.testutil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Outils Android pour les tests JVM : extraction du binaire {@code aapt2} du jar Maven
 * (présent sur le classpath de test) et localisation d'un {@code android.jar} réel.
 *
 * <p>Sans ces outils (machine sans SDK et sans jar de plate-forme), les tests qui les
 * exigent sont ignorés — la CI les exécute (SDK installé + jar Maven).</p>
 */
public final class OutilsAndroidDeTest {

    private OutilsAndroidDeTest() {
    }

    /**
     * Extrait aapt2 du jar Maven de test vers un répertoire temporaire exécutable.
     *
     * @return le chemin du binaire, ou {@code null} si introuvable sur le classpath.
     * @throws IOException si l'extraction échoue.
     */
    public static Path extraireAapt2() throws IOException {
        String classpath = System.getProperty("java.class.path");
        for (String entrée : classpath.split(java.io.File.pathSeparator)) {
            Path jar = Path.of(entrée);
            if (!Files.isRegularFile(jar) || !jar.getFileName().toString().startsWith("aapt2")) {
                continue;
            }
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                ZipEntry binaire = zip.getEntry("aapt2");
                if (binaire == null) {
                    continue;
                }
                Path destination = Files.createTempDirectory("forge-aapt2")
                        .resolve("aapt2");
                try (InputStream flux = zip.getInputStream(binaire)) {
                    Files.copy(flux, destination,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                destination.toFile().setExecutable(true);
                return destination;
            }
        }
        return null;
    }

    /**
     * Localise un android.jar réel pour l'API 34.
     *
     * @return le chemin, ou {@code null} si aucun disponible (test ignoré).
     */
    public static Path androidJar() {
        // 1. Propriété de test (bac à sable : jar téléchargé hors du dépôt).
        String propriété = System.getProperty("forge.androidJar");
        if (propriété != null && Files.isRegularFile(Path.of(propriété))) {
            return Path.of(propriété);
        }
        // 2. Copie locale standard du bac à sable.
        Path local = Path.of("/home/z/my-project/.jdk/android-34.jar");
        if (Files.isRegularFile(local)) {
            return local;
        }
        // 3. SDK de la machine (CI).
        for (String variable : new String[]{"ANDROID_HOME", "ANDROID_SDK_ROOT"}) {
            String racine = System.getenv(variable);
            if (racine == null) {
                continue;
            }
            for (int api = 34; api >= 28; api--) {
                Path jar = Path.of(racine, "platforms", "android-" + api, "android.jar");
                if (Files.isRegularFile(jar)) {
                    return jar;
                }
            }
        }
        return null;
    }

    /**
     * Indique si les outils Android réels sont disponibles (aapt2 + android.jar).
     *
     * @return {@code true} si les tests complets peuvent tourner.
     * @throws IOException si l'extraction d'aapt2 échoue.
     */
    public static boolean disponibles() throws IOException {
        return extraireAapt2() != null && androidJar() != null;
    }
}
