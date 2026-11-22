package jo.forge.engine.pipeline.android;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;
import java.util.concurrent.TimeUnit;

/**
 * Clé de signature de débogage : paire RSA + certificat auto-signé, générés EN
 * PROCESSUS à la première utilisation et conservés dans le magasin du projet
 * ({@code .forge/debug-key.forge}).
 *
 * <p><b>Correctif v0.2.9 (crash ART)</b> : la génération passait par
 * {@code keytool} en sous-processus — un binaire du JDK, absent d'ART : la
 * tâche {@code signApk} mourait sur l'appareil avec {@code Cannot run program
 * "keytool"}. Elle est désormais entièrement en pur Java via
 * {@link CertificatAutoSigné} (API publiques de {@code java.security},
 * présentes sur JVM et ART), et le stockage est un conteneur maison (PKCS#8 +
 * certificat DER) : le lecteur PKCS12 d'ART ne sait PAS déchiffrer les clés
 * protégées PBES2/PBKDF2 qu'écrit un keytool moderne — constat
 * d'interopérabilité pris au moteur de référence — et la clé de DÉBOGAGE
 * n'a de toute façon rien à cacher (mot de passe public de l'ancien magasin :
 * valeur documentée dans la coquille de référence).</p>
 *
 * <p>Les magasins historiques {@code .forge/debug-key.p12} (écrits par la
 * chaîne v0.2.8 sur JVM) restent LUS quand la plate-forme les accepte, pour
 * qu'un projet démarré sur JVM garde sa signature ; sur ART, un p12 PBES2
 * illisible déclenche simplement la génération d'une nouvelle clé au format
 * Forge. La signature de PRODUCTION reste hors de cette classe (magasin
 * fourni par l'utilisateur, secret jamais journalisé — évolution consignée
 * dans {@code docs/limites-et-suite.md}).</p>
 */
public final class DebugKeyStore {

    /** Alias de la clé (informationnel, conservé du format PKCS12). */
    static final String ALIAS = "forge-debug";

    /** Taille de la clé RSA générée (2048 bits, minimum courant d'apksig). */
    private static final int TAILLE_RSA = 2048;

    /** Validité du certificat de débogage (30 ans, comme -validity 10950). */
    private static final int ANNÉES_VALIDITÉ = 30;

    /** Magie du conteneur Forge (une version, un format). */
    private static final byte[] MAGIE = "FORGEDEBUG1".getBytes(StandardCharsets.US_ASCII);

    private final Path répertoireForge;

    /**
     * Crée le gestionnaire de clé de débogage d'un projet.
     *
     * @param répertoireForge répertoire {@code .forge} du projet.
     */
    public DebugKeyStore(Path répertoireForge) {
        this.répertoireForge = répertoireForge;
    }

    /**
     * Charge la clé de débogage, en la générant à la première utilisation.
     *
     * @return la clé privée et le certificat, prêts pour la signature.
     * @throws IOException si aucun magasin n'est lisible et que la génération
     *                     ou l'écriture échoue.
     */
    public Clé chargerOuGénérer() throws IOException {
        Path forge = répertoireForge.resolve("debug-key.forge");
        if (Files.isRegularFile(forge)) {
            return lireConteneurForge(forge);
        }
        Clé héritée = lireMagasinHérité();
        if (héritée != null) {
            return héritée;
        }
        return générer(forge);
    }

    // ---- Génération ---------------------------------------------------------

    /**
     * Génère la paire et le certificat, puis les pose atomiquement.
     */
    private Clé générer(Path cible) throws IOException {
        try {
            KeyPairGenerator générateur = KeyPairGenerator.getInstance("RSA");
            générateur.initialize(TAILLE_RSA);
            KeyPair paire = générateur.generateKeyPair();
            Date maintenant = new Date();
            X509Certificate certificat = CertificatAutoSigné.certificat(paire,
                    CertificatAutoSigné.nomDébogage(),
                    CertificatAutoSigné.série(new SecureRandom()), maintenant,
                    new Date(maintenant.getTime()
                            + TimeUnit.DAYS.toMillis(365) * ANNÉES_VALIDITÉ));

            Files.createDirectories(répertoireForge);
            Path temporaire = cible.resolveSibling(cible.getFileName() + ".part");
            try (DataOutputStream sortie = new DataOutputStream(
                    Files.newOutputStream(temporaire))) {
                sortie.write(MAGIE);
                byte[] certificatDér = certificat.getEncoded();
                sortie.writeInt(certificatDér.length);
                sortie.write(certificatDér);
                byte[] cléPkcs8 = paire.getPrivate().getEncoded();
                sortie.writeInt(cléPkcs8.length);
                sortie.write(cléPkcs8);
            }
            try {
                Files.move(temporaire, cible, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException pasAtomique) {
                Files.move(temporaire, cible, StandardCopyOption.REPLACE_EXISTING);
            }
            restreindreAuxPropriétaire(cible);
            return new Clé(paire.getPrivate(), certificat);
        } catch (IOException echec) {
            throw echec;
        } catch (Exception e) {
            throw new IOException("clé de débogage : " + e.getMessage(), e);
        }
    }

    /**
     * Pose rw------- sur le conteneur de clé (constat B-6) : la clé vit dans le
     * répertoire du PROJET — un projet zippé ou versionné emporte sa clé de
     * signature ; autant ne pas l'exposer aux autres comptes de la machine.
     * Sans système POSIX (Windows), le fichier reste soumis aux permissions du
     * répertoire utilisateur.
     */
    private static void restreindreAuxPropriétaire(Path fichier) {
        try {
            Files.setPosixFilePermissions(fichier,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (IOException | UnsupportedOperationException pasPosix) {
            // Système sans permissions POSIX : rien de plus à faire.
        }
    }

    // ---- Lecture ------------------------------------------------------------

    /**
     * Lit le conteneur Forge (magie, certificat DER, clé PKCS#8) — uniquement
     * des API publiques, lisibles sur JVM comme sur ART.
     */
    private Clé lireConteneurForge(Path fichier) throws IOException {
        try (DataInputStream entrée = new DataInputStream(
                Files.newInputStream(fichier))) {
            byte[] magie = new byte[MAGIE.length];
            entrée.readFully(magie);
            if (!java.util.Arrays.equals(magie, MAGIE)) {
                throw new IOException("magie du magasin de débogage inconnue : "
                        + fichier + " — supprimez-le, il sera régénéré");
            }
            byte[] certificatDér = new byte[entrée.readInt()];
            entrée.readFully(certificatDér);
            byte[] cléPkcs8 = new byte[entrée.readInt()];
            entrée.readFully(cléPkcs8);
            X509Certificate certificat = (X509Certificate) CertificateFactory
                    .getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(certificatDér));
            PrivateKey privée = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(cléPkcs8));
            return new Clé(privée, certificat);
        } catch (IOException echec) {
            throw echec;
        } catch (Exception e) {
            throw new IOException("magasin de clé de débogage corrompu : " + fichier
                    + " — supprimez-le, il sera régénéré (" + e.getMessage() + ")", e);
        }
    }

    /**
     * Tente le magasin historique PKCS12 de la chaîne v0.2.8 (clé lisible sur
     * JVM ; sur ART le lecteur de la plate-forme ne déchiffre pas les clés
     * PBES2 modernes — la méthode rend alors {@code null} et une nouvelle clé
     * est générée au format Forge).
     */
    private Clé lireMagasinHérité() {
        Path p12 = répertoireForge.resolve("debug-key.p12");
        if (!Files.isRegularFile(p12)) {
            return null;
        }
        try {
            KeyStore pkcs12 = KeyStore.getInstance("PKCS12");
            try (var entrée = Files.newInputStream(p12)) {
                pkcs12.load(entrée, "forge-debug".toCharArray());
            }
            PrivateKey privée = (PrivateKey) pkcs12.getKey(ALIAS,
                    "forge-debug".toCharArray());
            X509Certificate certificat = (X509Certificate) pkcs12.getCertificate(ALIAS);
            if (privée == null || certificat == null) {
                return null;
            }
            return new Clé(privée, certificat);
        } catch (Exception illisibleSurCettePlateForme) {
            return null; // Pas de clé héritée exploitable : génération neuve.
        }
    }

    /** Une clé de signature avec son certificat. */
    public record Clé(PrivateKey privée, X509Certificate certificat) {
    }
}
