package jo.forge.setup.manifest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Canal de distribution du moteur : récupère le manifeste signé et sa signature depuis
 * une URL de canal, vérifie l'authenticité (clé publique épinglée par la coquille) puis
 * décode le manifeste.
 *
 * <p>Convention de la signature : l'URL de la signature se déduit de celle du manifeste —
 * un manifeste {@code …/manifest.json} a sa signature en {@code …/manifest.sig}
 * (le suffixe {@code .json} est remplacé) ; toute autre terminaison est simplement
 * prolongée de {@code .sig}. Les deux fichiers sont publiés côte à côte (version
 * GitHub, miroir).</p>
 *
 * <p>HTTPS obligatoire en production (constat S-2 : un canal en clair permet la
 * coupure sélective des mises à jour) ; le constructeur d'injection ouvre les schémas
 * aux tests sur boucle locale. Plafonds de taille : un manifeste honnête tient en
 * quelques dizaines de kilo-octets, une signature DER en quelques centaines d'octets.</p>
 */
public final class CanalMoteur {

    /** Plafond du manifeste téléchargé (1 Mo — très au-delà du raisonnable). */
    static final int PLAFOND_MANIFESTE = 1024 * 1024;

    /** Plafond de la signature téléchargée (64 Ko). */
    static final int PLAFOND_SIGNATURE = 64 * 1024;

    /** Délai de connexion en millisecondes. */
    private static final int CONNECT_TIMEOUT_MS = 10_000;

    /** Délai de lecture en millisecondes. */
    private static final int READ_TIMEOUT_MS = 30_000;

    /** Schémas acceptés en production : HTTPS seul. */
    private static final Set<String> HTTPS_SEUL = Set.of("https");

    private final String clePubliqueBase64;

    /** Clé de rotation (constat M-2) : vide = aucune — sinon la signature peut venir
     * d'elle AUSSI, le temps de migrer toute la flotte vers la clé courante. */
    private final String cleSuivanteBase64;

    private final Set<String> schemas;

    /**
     * Crée le canal de production : HTTPS strict, vérification contre la clé publique
     * épinglée par la coquille.
     *
     * @param clePubliqueBase64 clé publique ECDSA P-256 (X.509, base64) épinglée.
     */
    public CanalMoteur(String clePubliqueBase64) {
        this(clePubliqueBase64, "");
    }

    /**
     * Crée le canal de production AVEC clé de rotation (constat M-2) : un manifeste
     * signé par la clé COURANTE ou par la clé SUIVANTE est accepté — la cohabitation
     * des deux rend possible le remplacement d'une clé compromise sans orphaner les
     * coquilles déjà installées. La clé suivante vide désactive le mécanisme.
     *
     * @param clePubliqueBase64 clé publique épinglée (base64 X.509).
     * @param cleSuivanteBase64 clé publique de rotation (base64 X.509, "" = aucune).
     */
    public CanalMoteur(String clePubliqueBase64, String cleSuivanteBase64) {
        this(clePubliqueBase64, cleSuivanteBase64, HTTPS_SEUL);
    }

    /**
     * Constructeur d'injection : schémas acceptés explicites (tests sur boucle locale).
     *
     * @param clePubliqueBase64 clé publique épinglée (base64 X.509).
     * @param schemas           schémas d'URL acceptés (ex. {@code http} pour les tests).
     */
    public CanalMoteur(String clePubliqueBase64, Set<String> schemas) {
        this(clePubliqueBase64, "", schemas);
    }

    /**
     * Constructeur complet (clé de rotation + schémas explicites).
     *
     * @param clePubliqueBase64 clé publique épinglée (base64 X.509).
     * @param cleSuivanteBase64 clé publique de rotation (base64 X.509, "" = aucune).
     * @param schemas           schémas d'URL acceptés.
     */
    public CanalMoteur(String clePubliqueBase64, String cleSuivanteBase64,
                       Set<String> schemas) {
        this.clePubliqueBase64 = clePubliqueBase64;
        this.cleSuivanteBase64 = cleSuivanteBase64 == null ? "" : cleSuivanteBase64.trim();
        this.schemas = Set.copyOf(schemas);
    }

    /**
     * Télécharge, vérifie et décode le manifeste d'un canal.
     *
     * @param urlManifeste URL du manifeste (ex. {@code …/manifest.json}).
     * @return le manifeste vérifié, ses octets exacts et la signature.
     * @throws ManifestException si le schéma est refusé, le téléchargement échoue,
     *                           la taille dépasse le plafond, la signature est
     *                           INVALIDE ou le manifeste est illisible.
     */
    public ManifesteSigne telecharger(String urlManifeste) {
        byte[] octets = lire(urlManifeste, PLAFOND_MANIFESTE, "manifeste");
        String signature = new String(lire(urlSignature(urlManifeste), PLAFOND_SIGNATURE,
                "signature"), StandardCharsets.UTF_8).trim();
        // Clé courante d'abord, clé de rotation ensuite (constat M-2) : un échec de
        // la première n'est PAS une erreur — il est simplement temps d'essayer
        // l'autre. Seule l'échec des DEUX signe le refus.
        boolean authentique = new ManifestVerifier(clePubliqueBase64).verify(octets, signature);
        if (!authentique && !cleSuivanteBase64.isEmpty()) {
            authentique = new ManifestVerifier(cleSuivanteBase64).verify(octets, signature);
        }
        if (!authentique) {
            throw new ManifestException("Signature du manifeste INVALIDE pour "
                    + urlManifeste + " — canal compromis ou clé publique obsolète :"
                    + " refusez l'installation");
        }
        EngineManifest manifeste;
        try {
            manifeste = ManifestCodec.parse(new String(octets, StandardCharsets.UTF_8));
        } catch (ManifestException illisible) {
            throw new ManifestException("Manifeste signé mais illisible : "
                    + messageDe(illisible), illisible);
        }
        return new ManifesteSigne(manifeste, octets, signature);
    }

    /**
     * Manifeste vérifié avec ses octets exacts et sa signature (export de diagnostic).
     *
     * @param manifeste   manifeste décodé et validé.
     * @param octets      octets exacts du fichier téléchargé (la signature les couvre).
     * @param signatureBase64 signature DER en base64.
     */
    public record ManifesteSigne(EngineManifest manifeste, byte[] octets,
                                 String signatureBase64) {
    }

    /**
     * URL de la signature compagnon d'un manifeste (convention de publication).
     *
     * @param urlManifeste URL du manifeste.
     * @return l'URL de la signature.
     */
    static String urlSignature(String urlManifeste) {
        return urlManifeste.endsWith(".json")
                ? urlManifeste.substring(0, urlManifeste.length() - ".json".length())
                        + ".sig"
                : urlManifeste + ".sig";
    }

    /**
     * Télécharge une petite ressource texte avec plafond de taille.
     */
    private byte[] lire(String urlTexte, int plafond, String role) {
        String schema;
        try {
            URL url = new URL(urlTexte);
            schema = url.getProtocol();
        } catch (IOException malformee) {
            throw new ManifestException("URL de " + role + " invalide : " + urlTexte);
        }
        if (!schemas.contains(schema)) {
            throw new ManifestException("Schéma " + schema + " refusé pour le canal ("
                    + role + ") : HTTPS exigé — " + urlTexte);
        }
        HttpURLConnection connexion = null;
        try {
            connexion = (HttpURLConnection) new URL(urlTexte).openConnection();
            connexion.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connexion.setReadTimeout(READ_TIMEOUT_MS);
            int code = connexion.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new ManifestException("HTTP " + code + " pour le " + role
                        + " du canal : " + urlTexte);
            }
            try (InputStream entree = connexion.getInputStream()) {
                ByteArrayOutputStream tampon = new ByteArrayOutputStream();
                byte[] bloc = new byte[8192];
                int lu;
                while ((lu = entree.read(bloc)) > 0) {
                    if (tampon.size() + lu > plafond) {
                        throw new ManifestException(role + " du canal trop volumineux ("
                                + "plafond " + plafond + " octets) : " + urlTexte);
                    }
                    tampon.write(bloc, 0, lu);
                }
                return tampon.toByteArray();
            }
        } catch (IOException reseau) {
            throw new ManifestException("Téléchargement du " + role + " impossible ("
                    + reseau.getClass().getSimpleName()
                    + (reseau.getMessage() == null ? "" : " : " + reseau.getMessage())
                    + ") : " + urlTexte, reseau);
        } finally {
            if (connexion != null) {
                connexion.disconnect();
            }
        }
    }

    /**
     * Message d'une exception sans le préfixe redondant du type.
     */
    private static String messageDe(ManifestException echec) {
        return echec.getMessage() == null ? "format invalide" : echec.getMessage();
    }
}
