package jo.forge.engine.pipeline.android;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Clé de signature de débogage : paire RSA + certificat auto-signé, générés à la
 * première utilisation et conservés dans le magasin du projet
 * ({@code .forge/debug-key.p12}).
 *
 * <p>Politique (différente du moteur de référence, qui exige un mot de passe en clair
 * sur sa ligne de commande — constat de sécurité consigné) : la clé de DÉBOGAGE n'a
 * pas de mot de passe significatif (valeur fixe publique, comme la clé de débogage
 * d'usage universel) ; la signature de PRODUCTION, elle, restera hors de cette classe
 * — elle exigera un magasin fourni par l'utilisateur et ne journalisera jamais son
 * secret (évolution consignée dans {@code docs/limites-et-suite.md}).</p>
 *
 * <p>La génération délègue à {@code keytool} (sous-processus borné, aucune API interne
 * du JDK — interdite par {@code --release}). Sur ART, où {@code keytool} n'existe pas,
 * la clé de débogage viendra du composant de signature (écart consigné ; l'ABI du
 * bac à sable et de la CI couvre le développement).</p>
 */
public final class DebugKeyStore {

    /** Mot de passe public du magasin de débogage (aucun secret à protéger). */
    static final char[] MOT_DE_PASSE = "forge-debug".toCharArray();

    /** Alias de la clé dans le magasin. */
    static final String ALIAS = "forge-debug";

    /** Délai maximal de keytool. */
    private static final long DÉLAI_MS = TimeUnit.SECONDS.toMillis(20);

    private final Path magasin;

    /**
     * Crée le gestionnaire de clé de débogage.
     *
     * @param magasin chemin du fichier PKCS#12 ({@code .forge/debug-key.p12}).
     */
    public DebugKeyStore(Path magasin) {
        this.magasin = magasin;
    }

    /**
     * Charge la clé de débogage, en la générant à la première utilisation.
     *
     * @return la clé privée et le certificat, prêts pour la signature.
     * @throws IOException si le magasin est illisible ou la génération impossible.
     */
    public Clé chargerOuGénérer() throws IOException {
        try {
            if (!Files.isRegularFile(magasin)) {
                générer();
            }
            return lire();
        } catch (IOException echec) {
            throw echec;
        } catch (Exception e) {
            throw new IOException("clé de débogage : " + e.getMessage(), e);
        }
    }

    private void générer() throws IOException, InterruptedException {
        Files.createDirectories(magasin.getParent());
        Path temporaire = magasin.resolveSibling(magasin.getFileName() + ".part");
        Files.deleteIfExists(temporaire);
        ProcessBuilder constructeur = new ProcessBuilder(List.of(
                keytool(),
                "-genkeypair",
                "-keystore", temporaire.toString(),
                "-storetype", "PKCS12",
                "-storepass", new String(MOT_DE_PASSE),
                "-keypass", new String(MOT_DE_PASSE),
                "-alias", ALIAS,
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "10950",
                "-dname", "CN=Forge Debug,O=Forge,C=FR"));
        constructeur.redirectErrorStream(true);
        Process processus = constructeur.start();
        // Consommer la sortie pour ne pas bloquer le tampon.
        byte[] bavardage = processus.getInputStream().readAllBytes();
        boolean fini = processus.waitFor(DÉLAI_MS, TimeUnit.MILLISECONDS);
        if (!fini) {
            processus.destroyForcibly();
            throw new IOException("keytool : délai dépassé");
        }
        if (processus.exitValue() != 0 || !Files.isRegularFile(temporaire)) {
            throw new IOException("keytool a échoué (code " + processus.exitValue()
                    + ") : " + new String(bavardage, java.nio.charset.StandardCharsets.UTF_8)
                    .trim() + " — sur ART, la clé de débogage viendra du composant"
                    + " de signature (étape 12)");
        }
        try {
            Files.move(temporaire, magasin, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException pasAtomique) {
            Files.move(temporaire, magasin, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Clé lire() throws Exception {
        KeyStore pkcs12 = KeyStore.getInstance("PKCS12");
        try (var entrée = Files.newInputStream(magasin)) {
            pkcs12.load(entrée, MOT_DE_PASSE);
        }
        PrivateKey privée = (PrivateKey) pkcs12.getKey(ALIAS, MOT_DE_PASSE);
        X509Certificate certificat = (X509Certificate) pkcs12.getCertificate(ALIAS);
        if (privée == null || certificat == null) {
            throw new IOException("magasin de clé de débogage corrompu : " + magasin
                    + " — supprimez-le, il sera régénéré");
        }
        return new Clé(privée, certificat);
    }

    /**
     * Localise keytool : {@code java.home}/bin si présent, sinon le PATH.
     */
    private static String keytool() {
        Path maison = Path.of(System.getProperty("java.home", ""));
        Path candidat = maison.resolve("bin").resolve(isWindows() ? "keytool.exe" : "keytool");
        return Files.isRegularFile(candidat) ? candidat.toString() : "keytool";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** Une clé de signature avec son certificat. */
    public record Clé(PrivateKey privée, X509Certificate certificat) {
    }
}
