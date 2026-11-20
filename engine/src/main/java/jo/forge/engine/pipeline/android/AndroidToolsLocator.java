package jo.forge.engine.pipeline.android;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Localisation des outils Android du moteur : binaire {@code aapt2} et jar de
 * plate-forme ({@code android.jar}).
 *
 * <p>Ordre de résolution (le premier gagne) :</p>
 * <ol>
 *   <li>propriétés système {@code forge.aapt2} et {@code forge.androidJar} (tests) ;</li>
 *   <li>répertoire du moteur : {@code components/aapt2-&lt;abi&gt;/aapt2} et
 *       {@code components/platform-&lt;api&gt;/android.jar} (installation réelle —
 *       l'ABI vient de la requête de build ; le composant {@code aapt2-&lt;abi&gt;} est
 *       publié depuis v0.2.8, prébuilt Android du projet ReVanced/aapt2) ;</li>
 *   <li>variables d'environnement {@code ANDROID_HOME}/{@code ANDROID_SDK_ROOT}
 *       (machine de développement, CI) : build-tools puis platforms.</li>
 * </ol>
 *
 * <p>Les messages d'absence sont actionnables : ils citent le composant manquant et la
 * façon de l'obtenir — un module Android sans ces outils échoue proprement.</p>
 */
public final class AndroidToolsLocator {

    private AndroidToolsLocator() {
    }

    /**
     * Localise le binaire aapt2.
     *
     * @param abi       ABI de l'appareil (pour le composant installé).
     * @param engineHome racine du moteur installé (peut être null).
     * @return le chemin exécutable.
     * @throws IllegalStateException si introuvable (message actionnable).
     */
    public static Path aapt2(String abi, Path engineHome) {
        Path parPropriété = cheminPropriété("forge.aapt2");
        if (parPropriété != null) {
            return parPropriété;
        }
        if (engineHome != null) {
            Path composant = engineHome.resolve("components")
                    .resolve("aapt2-" + abi).resolve("aapt2");
            if (Files.isRegularFile(composant)) {
                return composant;
            }
        }
        Path sdk = sdk();
        if (sdk != null) {
            try (var buildTools = Files.list(sdk.resolve("build-tools"))) {
                Path trouvé = buildTools.filter(Files::isDirectory)
                        .sorted(java.util.Comparator.reverseOrder())
                        .map(d -> d.resolve("aapt2"))
                        .filter(Files::isRegularFile)
                        .findFirst().orElse(null);
                if (trouvé != null) {
                    return trouvé;
                }
            } catch (Exception ignored) {
                // Répertoire build-tools absent ou illisible : continue.
            }
        }
        throw new IllegalStateException(messageAapt2Introuvable(abi));
    }

    /**
     * Message d'absence d'aapt2, actionnable : il cite le composant exact de
     * l'assistant ({@code aapt2-<abi>}, publié depuis v0.2.8 — prébuilt Android
     * du projet ReVanced/aapt2, alignement 16 Ko) et les replis machine de
     * développement.
     *
     * <p>Package-privé pour être testé sans dépendre de l'environnement : la CI
     * porte un SDK local (ANDROID_HOME + build-tools), qui fait réussir la
     * résolution de secours — le chemin d'exception n'y est jamais pris.</p>
     *
     * @param abi ABI demandée (ex. {@code arm64-v8a}).
     * @return le message complet.
     */
    static String messageAapt2Introuvable(String abi) {
        return "aapt2 introuvable pour " + abi + " — installez le composant aapt2-"
                + abi + " (assistant du moteur) ou définissez forge.aapt2 / ANDROID_HOME";
    }

    /**
     * Localise le jar de la plate-forme Android.
     *
     * @param api       niveau d'API cible.
     * @param engineHome racine du moteur installé (peut être null).
     * @return le chemin du jar.
     * @throws IllegalStateException si introuvable (message actionnable).
     */
    public static Path androidJar(int api, Path engineHome) {
        Path parPropriété = cheminPropriété("forge.androidJar");
        if (parPropriété != null) {
            return parPropriété;
        }
        if (engineHome != null) {
            Path composant = engineHome.resolve("components")
                    .resolve("platform-" + api).resolve("android.jar");
            if (Files.isRegularFile(composant)) {
                return composant;
            }
        }
        Path sdk = sdk();
        if (sdk != null) {
            // La plate-forme exacte, sinon la plus proche disponible au-dessus.
            for (int niveau = api; niveau <= api + 6; niveau++) {
                Path jar = sdk.resolve("platforms").resolve("android-" + niveau)
                        .resolve("android.jar");
                if (Files.isRegularFile(jar)) {
                    return jar;
                }
            }
        }
        throw new IllegalStateException("android.jar (API " + api + ") introuvable — "
                + "installez le composant platform-" + api + " ou définissez "
                + "forge.androidJar / ANDROID_HOME");
    }

    /**
     * Niveau d'API le plus élevé des plate-formes installées dans le moteur.
     *
     * <p>Sert aux modules sans facette Android (bibliothèques Java sur ART) :
     * ils compilent contre la plate-forme installée, puisque leur code tourne
     * sur l'appareil qui porte cette installation.</p>
     *
     * @param engineHome racine du moteur installé (peut être null).
     * @return le niveau le plus élevé, ou {@code -1} si aucune plate-forme.
     */
    public static int plateformeLaPlusHaute(Path engineHome) {
        if (engineHome == null) {
            return -1;
        }
        Path composants = engineHome.resolve("components");
        if (!Files.isDirectory(composants)) {
            return -1;
        }
        int laPlusHaute = -1;
        try (var plateformes = Files.list(composants)) {
            for (Path répertoire : plateformes.filter(Files::isDirectory).toList()) {
                if (!Files.isRegularFile(répertoire.resolve("android.jar"))) {
                    continue;
                }
                String nom = répertoire.getFileName().toString();
                if (!nom.startsWith("platform-")) {
                    continue;
                }
                try {
                    laPlusHaute = Math.max(laPlusHaute,
                            Integer.parseInt(nom.substring("platform-".length())));
                } catch (NumberFormatException ignorée) {
                    // Répertoire non conforme : ignoré.
                }
            }
        } catch (Exception ignorée) {
            // Composants illisibles : aucune plate-forme retenue.
        }
        return laPlusHaute;
    }

    private static Path cheminPropriété(String propriété) {
        String valeur = System.getProperty(propriété);
        if (valeur == null || valeur.isBlank()) {
            return null;
        }
        Path chemin = Path.of(valeur);
        if (!Files.isRegularFile(chemin)) {
            throw new IllegalStateException("La propriété " + propriété
                    + " pointe un fichier absent : " + valeur);
        }
        return chemin;
    }

    private static Path sdk() {
        for (String variable : List.of("ANDROID_HOME", "ANDROID_SDK_ROOT")) {
            String valeur = System.getenv(variable);
            if (valeur != null && !valeur.isBlank()) {
                return Path.of(valeur);
            }
        }
        return null;
    }
}
