package jo.forge.setup.manifest;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Vérification d'authenticité du manifeste : signature ECDSA P-256 (SHA-256) sur les
 * octets exacts du fichier, clé publique épinglée dans la coquille.
 *
 * <p>La signature couvre le fichier brut, pas une re-sérialisation : toute
 * réinterprétation du JSON est détectée. Format de clé : point d'encodage X.509 en
 * base64 ; format de signature : DER.</p>
 */
public final class ManifestVerifier {

    private final PublicKey clePublique;

    /**
     * Crée le vérificateur avec la clé publique épinglée.
     *
     * @param clePubliqueBase64 clé publique X.509 encodée en base64 (une seule ligne).
     * @throws ManifestException si la clé est illisible ou d'un type inattendu.
     */
    public ManifestVerifier(String clePubliqueBase64) {
        this.clePublique = lireCle(clePubliqueBase64);
    }

    /**
     * Vérifie la signature d'un manifeste.
     *
     * @param manifesteOctets octets exacts du fichier manifeste téléchargé.
     * @param signatureDer    signature DER encodée en base64 (fichier compagnon).
     * @return {@code true} si la signature est valide pour ces octets.
     * @throws ManifestException si la signature est illisible.
     */
    public boolean verify(byte[] manifesteOctets, String signatureDer) {
        byte[] signature;
        try {
            signature = Base64.getDecoder().decode(signatureDer.trim());
        } catch (IllegalArgumentException illisible) {
            throw new ManifestException("Signature illisible (base64 invalide)");
        }
        try {
            Signature verificateur = Signature.getInstance("SHA256withECDSA");
            verificateur.initVerify(clePublique);
            verificateur.update(manifesteOctets);
            return verificateur.verify(signature);
        } catch (Exception echec) {
            throw new ManifestException(
                    "Vérification impossible : " + echec.getMessage(), echec);
        }
    }

    /**
     * Décode la clé publique épinglée.
     */
    private static PublicKey lireCle(String base64) {
        byte[] octets;
        try {
            octets = Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException illisible) {
            throw new ManifestException("Clé publique illisible (base64 invalide)");
        }
        try {
            KeyFactory fabrique = KeyFactory.getInstance("EC");
            return fabrique.generatePublic(new X509EncodedKeySpec(octets));
        } catch (Exception echec) {
            throw new ManifestException("Clé publique invalide : " + echec.getMessage(), echec);
        }
    }
}
