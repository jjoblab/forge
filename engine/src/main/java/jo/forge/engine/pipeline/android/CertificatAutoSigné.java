package jo.forge.engine.pipeline.android;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;
import java.util.List;

/**
 * Certificat X.509 auto-signé généré en pur Java, sans {@code keytool} ni Bouncy
 * Castle (correctif v0.2.9 du crash {@code Cannot run program "keytool"} sur ART).
 *
 * <p>Sur l'appareil (rapport v0.2.8), la tâche {@code signApk} échouait :
 * {@code keytool} est un binaire du JDK, absent d'ART. La génération se fait
 * ici <em>en processus</em> avec les seules API publiques de {@code java.security}
 * — présentes sur JVM et ART — et un encodage DER minimal écrit à la main
 * (INTEGER, SEQUENCE, OID, PrintableString, UTCTime/GeneralizedTime, BIT
 * STRING) : la philosophie du moteur, qui a déjà réécrit les fabriques lambda
 * absentes d'android.jar plutôt que d'embarquer une dépendance de plus.</p>
 *
 * <p>Le certificat produit est un v1 auto-signé (RSA, SHA256withRSA) :
 * exactement ce qu'un {@code keytool -genkeypair} produit pour la signature de
 * DÉBOGAGE — aucune extension requise ni par apksig ni par la vérification
 * des APK. Il est REVALIDÉ à la sortie par {@link CertificateFactory} (une
 * forme mal encodée échoue ici, en JVM, plutôt que silencieusement sur
 * l'appareil) et sa signature vérifiée par {@link X509Certificate#verify}.</p>
 *
 * <p>Constat d'interopérabilité pris au moteur de référence : ART ne sait PAS
 * déchiffrer les magasins PKCS12 modernes (clés protégées PBES2/PBKDF2 —
 * {@code SecretKeyFactory 1.2.840.113549.1.5.12 not available}) ; c'est
 * pourquoi {@link DebugKeyStore} ne ré-écrit JAMAIS de PKCS12 et stocke la
 * clé dans son propre conteneur (PKCS#8 + certificat DER, la clé de débogage
 * étant publique par définition).</p>
 */
public final class CertificatAutoSigné {

    /** OID sha256WithRSAEncryption (signature du certificat). */
    private static final String OID_SHA256_AVEC_RSA = "1.2.840.113549.1.1.11";

    /** OID rsaEncryption (algorithme de la clé publique). */
    private static final String OID_RSA = "1.2.840.113549.1.1.1";

    /** OID des attributs de nom (CN, O, C — l'ordre importe peu à la vérification). */
    private static final String OID_CN = "2.5.4.3";
    private static final String OID_O = "2.5.4.10";
    private static final String OID_C = "2.5.4.6";

    private CertificatAutoSigné() {
        // Utilitaire statique : pas d'instanciation.
    }

    /**
     * Un attribut du nom distinctif (OID + valeur).
     *
     * @param oid     OID de l'attribut (ex. {@link #OID_CN}).
     * @param valeur  valeur textuelle (ASCII, encodée PrintableString).
     */
    public record Attribut(String oid, String valeur) {
    }

    /**
     * Nom distinctif de la clé de débogage Forge (CN, O, C — celui de l'ancien
     * {@code keytool -dname}).
     *
     * @return les trois attributs, dans l'ordre d'écriture.
     */
    public static List<Attribut> nomDébogage() {
        return List.of(new Attribut(OID_CN, "Forge Debug"),
                new Attribut(OID_O, "Forge"), new Attribut(OID_C, "FR"));
    }

    /**
     * Signe la paire de clés d'un certificat X.509 v1 auto-signé.
     *
     * <p>Le certificat est relu par {@link CertificateFactory} et sa signature
     * vérifiée avant d'être rendu : un encodage défaillant ne quitte jamais
     * cette méthode.</p>
     *
     * @param paire     paire RSA (l'émetteur et le sujet sont la même clé).
     * @param nom       nom distinctif (émetteur = sujet, certificat auto-signé).
     * @param série     numéro de série (positif).
     * @param pasAvant  début de validité.
     * @param pasAprès  fin de validité.
     * @return le certificat auto-signé, déjà revalidé.
     * @throws IllegalStateException si la signature ou l'encodage échoue
     *                               (algorithme absent de la plate-forme).
     */
    public static X509Certificate certificat(KeyPair paire, List<Attribut> nom,
                                             BigInteger série, Date pasAvant,
                                             Date pasAprès) {
        try {
            if (!(paire.getPublic() instanceof RSAPublicKey rsa)) {
                throw new IllegalArgumentException(
                        "une clé publique RSA est requise (reçue : "
                                + paire.getPublic().getAlgorithm() + ")");
            }
            byte[] algorithme = algorithmeSignature();
            byte[] tbs = séquence(
                    entierPositif(série),
                    algorithme,
                    nom(nom),
                    validité(pasAvant, pasAprès),
                    nom(nom),
                    cléPublique(rsa));

            Signature signataire = Signature.getInstance("SHA256withRSA");
            signataire.initSign(paire.getPrivate());
            signataire.update(tbs);
            byte[] signature = signataire.sign();

            byte[] dér = séquence(tbs, algorithme, chaîneBits(signature));
            CertificateFactory fabrique = CertificateFactory.getInstance("X.509");
            X509Certificate relu = (X509Certificate) fabrique.generateCertificate(
                    new ByteArrayInputStream(dér));
            relu.verify(paire.getPublic());
            return relu;
        } catch (Exception echec) {
            throw new IllegalStateException("génération du certificat impossible : "
                    + echec.getMessage(), echec);
        }
    }

    /**
     * Numéro de série positif aléatoire (128 bits, comme keytool).
     *
     * @param aléatoire source d'aléa.
     * @return un entier strictement positif.
     */
    public static BigInteger série(SecureRandom aléatoire) {
        BigInteger valeur;
        do {
            valeur = new BigInteger(128, aléatoire);
        } while (valeur.signum() <= 0);
        return valeur;
    }

    // ---- Encodage DER minimal ----------------------------------------------

    /** Étiquette SEQUENCE. */
    private static final byte SÉQUENCE = 0x30;

    /** Étiquette INTEGER. */
    private static final byte ENTIER = 0x02;

    /** Étiquette OBJECT IDENTIFIER. */
    private static final byte OID = 0x06;

    /** Étiquette PrintableString. */
    private static final byte IMPRIMABLE = 0x13;

    /** Étiquette UTCTime. */
    private static final byte TEMPS_UTC = 0x17;

    /** Étiquette GeneralizedTime (dates à partir de 2050, RFC 5280 §4.1.2.5). */
    private static final byte TEMPS_GÉNÉRALISÉ = 0x18;

    /** Étiquette BIT STRING. */
    private static final byte CHAÎNE_BITS = 0x03;

    /** Étiquette NULL. */
    private static final byte NUL = 0x05;

    /** Année limite des UTCTime (au-delà : GeneralizedTime obligatoire). */
    private static final int ANNÉE_LIMITE_UTC = 2050;

    /**
     * AlgorithmIdentifier de la signature : sha256WithRSAEncryption, paramètre NULL.
     */
    private static byte[] algorithmeSignature() {
        return séquence(oid(OID_SHA256_AVEC_RSA), new byte[] {NUL, 0x00});
    }

    /**
     * Name ::= RDNSequence — chaque attribut dans son propre SET (RDN simples).
     */
    private static byte[] nom(List<Attribut> attributs) {
        ByteArrayOutputStream suite = new ByteArrayOutputStream();
        for (Attribut attribut : attributs) {
            byte[] typeEtValeur = séquence(oid(attribut.oid()),
                    imprimable(attribut.valeur()));
            // RelativeDistinguishedName ::= SET OF AttributeTypeAndValue.
            byte[] rdn = enTête((byte) 0x31, typeEtValeur.length, typeEtValeur);
            suite.writeBytes(rdn);
        }
        return enTête(SÉQUENCE, suite.size(), suite.toByteArray());
    }

    /**
     * Validity ::= SEQUENCE { notBefore Time, notAfter Time } — UTCTime avant
     * 2050, GeneralizedTime ensuite (les clés de débogage valent 30 ans).
     */
    private static byte[] validité(Date pasAvant, Date pasAprès) {
        return séquence(temps(pasAvant), temps(pasAprès));
    }

    /**
     * Time ::= CHOICE { UTCTime, GeneralizedTime } selon l'année. UTCTime porte
     * l'année sur DEUX chiffres (2026 → 26) ; GeneralizedTime sur quatre.
     */
    private static byte[] temps(Date date) {
        java.util.Calendar calendrier = java.util.Calendar.getInstance(
                java.util.TimeZone.getTimeZone("UTC"));
        calendrier.setTime(date);
        int année = calendrier.get(java.util.Calendar.YEAR);
        boolean utc = année < ANNÉE_LIMITE_UTC;
        String texte = String.format(utc ? "%02d" : "%04d",
                utc ? année % 100 : année)
                + String.format("%02d%02d%02d%02d%02d",
                calendrier.get(java.util.Calendar.MONTH) + 1,
                calendrier.get(java.util.Calendar.DAY_OF_MONTH),
                calendrier.get(java.util.Calendar.HOUR_OF_DAY),
                calendrier.get(java.util.Calendar.MINUTE),
                calendrier.get(java.util.Calendar.SECOND))
                + "Z";
        byte[] octets = texte.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        return enTête(utc ? TEMPS_UTC : TEMPS_GÉNÉRALISÉ, octets.length, octets);
    }

    /**
     * SubjectPublicKeyInfo ::= SEQUENCE { AlgorithmIdentifier(rsaEncryption),
     * BIT STRING { RSAPublicKey ::= SEQUENCE { modulus, publicExponent } } }.
     */
    private static byte[] cléPublique(RSAPublicKey clé) {
        byte[] rsaPublique = séquence(
                entierPositif(clé.getModulus()),
                entierPositif(clé.getPublicExponent()));
        return séquence(
                séquence(oid(OID_RSA), new byte[] {NUL, 0x00}),
                chaîneBits(rsaPublique));
    }

    /**
     * SEQUENCE d'éléments déjà encodés.
     */
    private static byte[] séquence(byte[]... éléments) {
        ByteArrayOutputStream corps = new ByteArrayOutputStream();
        for (byte[] élément : éléments) {
            corps.writeBytes(élément);
        }
        return enTête(SÉQUENCE, corps.size(), corps.toByteArray());
    }

    /**
     * INTEGER DER d'un grand nombre positif (zéro non signé de tête si le bit
     * de signe est posé — un module RSA commence souvent par un octet fort).
     */
    private static byte[] entierPositif(BigInteger valeur) {
        byte[] brute = valeur.toByteArray(); // Déjà normalisé par BigInteger.
        if (brute[0] == 0 && brute.length > 1) {
            brute = java.util.Arrays.copyOfRange(brute, 1, brute.length);
        }
        return enTête(ENTIER, brute.length, brute);
    }

    /**
     * PrintableString ASCII (les valeurs de noms distinctifs de débogage y
     * restent : CN=Forge Debug, O=Forge, C=FR).
     */
    private static byte[] imprimable(String texte) {
        byte[] octets = texte.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        return enTête(IMPRIMABLE, octets.length, octets);
    }

    /**
     * BIT STRING (zéro bit inutilisé).
     */
    private static byte[] chaîneBits(byte[] contenu) {
        byte[] octets = new byte[contenu.length + 1];
        octets[0] = 0; // Bits inutilisés : aucun.
        System.arraycopy(contenu, 0, octets, 1, contenu.length);
        return enTête(CHAÎNE_BITS, octets.length, octets);
    }

    /**
     * OBJECT IDENTIFIER en base 128 : les deux premiers arcs sont compressés
     * ({@code 40·a+b}), les arcs suivants sur plusieurs octets le cas échéant
     * (bit de continuation à 1 sur tous sauf le dernier octet).
     */
    private static byte[] oid(String pointé) {
        String[] arcs = pointé.split("\\.");
        ByteArrayOutputStream corps = new ByteArrayOutputStream();
        corps.write(40 * Integer.parseInt(arcs[0]) + Integer.parseInt(arcs[1]));
        for (int indice = 2; indice < arcs.length; indice++) {
            long arc = Long.parseLong(arcs[indice]);
            int octets = 0;
            for (long reste = arc; reste > 0; reste >>= 7) {
                octets++;
            }
            for (int rang = octets - 1; rang >= 0; rang--) {
                int octet = (int) ((arc >> (7 * rang)) & 0x7F);
                corps.write(rang > 0 ? octet | 0x80 : octet);
            }
        }
        return enTête(OID, corps.size(), corps.toByteArray());
    }

    /**
     * Étiquette + longueur (forme courte ou longue) + corps.
     */
    private static byte[] enTête(byte étiquette, int longueur, byte[] corps) {
        ByteArrayOutputStream dér = new ByteArrayOutputStream();
        dér.write(étiquette);
        if (longueur < 0x80) {
            dér.write(longueur);
        } else if (longueur <= 0xFF) {
            dér.write(0x81);
            dér.write(longueur);
        } else if (longueur <= 0xFFFF) {
            dér.write(0x82);
            dér.write(longueur >> 8);
            dér.write(longueur & 0xFF);
        } else {
            dér.write(0x83);
            dér.write(longueur >> 16);
            dér.write((longueur >> 8) & 0xFF);
            dér.write(longueur & 0xFF);
        }
        dér.writeBytes(corps);
        return dér.toByteArray();
    }
}
