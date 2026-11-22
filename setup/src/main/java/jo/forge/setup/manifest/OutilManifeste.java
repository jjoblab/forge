package jo.forge.setup.manifest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Outil en ligne de commande du manifeste signé (chaîne de publication, étape 12) :
 * génération de la paire de clés, signature, vérification.
 *
 * <p>Schéma (identique à {@link ManifestVerifier}) : ECDSA P-256, SHA-256, signature
 * DER en base64 sur les octets EXACTS du fichier manifeste. Clé publique : X.509 en
 * base64 (épinglée dans la coquille) ; clé privée : PKCS#8 en base64 (JETON CI,
 * jamais dans le dépôt).</p>
 *
 * <p>Usage :</p>
 * <pre>
 *   generer-cle &lt;sortie-clé-publique-b64&gt; &lt;sortie-clé-privée-b64&gt;
 *   signer &lt;manifeste.json&gt; &lt;clé-privée-b64&gt; &lt;sortie.signature&gt;
 *   verifier &lt;manifeste.json&gt; &lt;clé-publique-b64&gt; &lt;signature&gt;
 * </pre>
 */
public final class OutilManifeste {

    private OutilManifeste() {
    }

    /**
     * Point d'entrée de l'outil.
     *
     * @param args commande et arguments.
     * @throws Exception en cas d'erreur (message lisible sur stderr, code 1).
     */
    public static void main(String[] args) throws Exception {
        try {
            if (args.length == 3 && "generer-cle".equals(args[0])) {
                genererCle(Path.of(args[1]), Path.of(args[2]));
            } else if (args.length == 4 && "signer".equals(args[0])) {
                signer(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]));
            } else if (args.length == 4 && "verifier".equals(args[0])) {
                verifier(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]));
            } else {
                usage();
            }
        } catch (Exception echec) {
            System.err.println("Erreur : " + echec.getMessage());
            System.exit(1);
        }
    }

    private static void usage() {
        System.err.println("""
                Usage :
                  generer-cle <sortie-clé-publique-b64> <sortie-clé-privée-b64>
                  signer <manifeste.json> <clé-privée-b64> <sortie.signature>
                  verifier <manifeste.json> <clé-publique-b64> <signature>""");
        System.exit(2);
    }

    /**
     * Génère une paire de clés de signature de manifeste.
     */
    private static void genererCle(Path sortiePublique, Path sortiePrivée)
            throws Exception {
        KeyPairGenerator générateur = KeyPairGenerator.getInstance("EC");
        générateur.initialize(256, new SecureRandom());
        KeyPair paire = générateur.generateKeyPair();
        Files.writeString(sortiePublique,
                Base64.getEncoder().encodeToString(
                        paire.getPublic().getEncoded()));
        Files.writeString(sortiePrivée,
                Base64.getEncoder().encodeToString(
                        paire.getPrivate().getEncoded()));
        restreindreAuxPropriétaire(sortiePrivée);
        System.out.println("Paire de clés écrite (publique X.509, privée PKCS#8,"
                + " toutes deux en base64). La clé PRIVÉE ne doit jamais être"
                + " versionnée.");
    }

    /**
     * Pose rw------- sur le fichier de clé privée (constat B-6) : le jeton CI qui
     * signe les manifestes ne doit être lisible que de son propriétaire — réflexe
     * peu coûteux là où le système de fichiers connaît les permissions POSIX
     * (ailleurs, les permissions du répertoire s'appliquent).
     */
    private static void restreindreAuxPropriétaire(Path fichier) {
        try {
            Files.setPosixFilePermissions(fichier,
                    java.nio.file.attribute.PosixFilePermissions
                            .fromString("rw-------"));
        } catch (IOException | UnsupportedOperationException pasPosix) {
            // Système sans permissions POSIX : rien de plus à faire.
        }
    }

    /**
     * Signe un manifeste avec une clé privée PKCS#8 en base64.
     */
    private static void signer(Path manifeste, Path cléPrivée, Path sortie)
            throws Exception {
        PrivateKey clé = lireCléPrivée(Files.readString(cléPrivée));
        Signature signataire = Signature.getInstance("SHA256withECDSA");
        signataire.initSign(clé);
        signataire.update(Files.readAllBytes(manifeste));
        byte[] signature = signataire.sign();
        Files.writeString(sortie, Base64.getEncoder().encodeToString(signature));
        System.out.println("Signature écrite : " + sortie);
    }

    /**
     * Vérifie une signature (parité avec la vérification de la coquille).
     */
    private static void verifier(Path manifeste, Path cléPublique, Path signature)
            throws IOException {
        ManifestVerifier vérificateur = new ManifestVerifier(
                Files.readString(cléPublique));
        boolean valide = vérificateur.verify(Files.readAllBytes(manifeste),
                Files.readString(signature));
        System.out.println(valide
                ? "Signature VALIDE"
                : "Signature INVALIDE");
        if (!valide) {
            System.exit(1);
        }
    }

    private static PrivateKey lireCléPrivée(String base64) throws Exception {
        byte[] octets = Base64.getDecoder().decode(base64.trim());
        return KeyFactory.getInstance("EC")
                .generatePrivate(new PKCS8EncodedKeySpec(octets));
    }

    // Référence statique pour la lisibilité des imports (clé publique X.509).
    @SuppressWarnings("unused")
    private static PublicKey lireCléPublique(String base64) throws Exception {
        byte[] octets = Base64.getDecoder().decode(base64.trim());
        return KeyFactory.getInstance("EC")
                .generatePublic(new X509EncodedKeySpec(octets));
    }
}
