package jo.forge.engine.util;

import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;

/**
 * Analyse DOM durcie et PORTABLE — JVM comme ART.
 *
 * <p><b>Deux fois le même mur, à deux étapes différentes</b> : le parseur DOM
 * d'ART (KXml) ne reconnaît NI les fonctionnalités de durcissement Xerces NI
 * les attributs JAXP 1.5 — {@code setFeature} y lève
 * {@code ParserConfigurationException(URI)}. La v0.2.9 l'a appris sur les POM
 * (mort de {@code resolveDependencies} au premier build Maven sur appareil,
 * correctif v0.2.10 : {@code PomParser}) ; la v0.2.16 l'a réappris sur les
 * manifestes (mort de {@code processResources} au premier build Compose sur
 * appareil — « manifeste illisible :
 * http://apache.org/xml/features/disallow-doctype-decl », correctif v0.2.17) :
 * le garde-fou y était codé EN DUR. Les tests JVM ne voient jamais ce piège :
 * Xerces, sur le bureau, reconnaît tout. Le moteur de référence enveloppe
 * chacune de ses {@code setFeature} de {@code runCatching} pour exactement
 * cette raison (REF-163).</p>
 *
 * <p><b>Défense, plus forte que le « au mieux » seul</b> (même position que le
 * correctif v0.2.10 des POM) :</p>
 * <ul>
 *   <li>le rejet des DOCTYPE — la seule de ces fonctionnalités qui porte un
 *       VRAI droit (anti-XXE et anti-bombe à entités) — est garanti par le
 *       SCAN DU PROLOGUE en octets bruts : absolu, indépendant du runtime,
 *       même position que Maven (un DOCTYPE suffit à porter des entités
 *       externes comme internes ; aucun document légitime de Forge — POM ni
 *       manifeste — n'en porte) ;</li>
 *   <li>les garde-fous SUPPLÉMENTAIRES que le runtime propose se posent
 *       UN PAR UN AU MIEUX : une fonctionnalité absente ne tue JAMAIS la
 *       lecture — c'est là le motif du moteur de référence (REF-163), les
 *       menaces restant couvertes par le scan et le résolveur ci-dessous ;</li>
 *   <li>le résolveur d'entités vide : TOUTE entité externe résout vers le
 *       vide — interface standard honorée de la JVM comme d'ART.</li>
 * </ul>
 *
 * <p><b>Garde structurelle</b> : {@code SurfaceXmlArtTest} fait échouer la CI
 * si une classe du composant engine-core autre que celle-ci référence
 * {@code DocumentBuilderFactory}/{@code SAXParserFactory} ou porte les URI de
 * fonctionnalités Xerces/SAX — tout nouveau site d'analyse XML DOIT passer
 * par ici.</p>
 */
public final class DomSûr {

    private DomSûr() {
    }

    /**
     * Rejette tout document dont le PROLOGUE porte une déclaration DOCTYPE —
     * la garde XXE absolue et PORTABLE : la fonctionnalité Xerces qui la portait
     * sur la JVM n'existe pas sur ART, et le traitement sûr JAXP standard n'y
     * interdit pas les DOCTYPE. Le scan ne lit que le prologue (avant l'élément
     * racine) : le mot-clé {@code <!DOCTYPE} y est en MAJUSCULES (grammaire
     * XML), et une section CDATA du CONTENU ne peut pas créer de faux positif —
     * elle suit nécessairement la racine.
     *
     * @param octets octets du document.
     * @throws IOException si le prologue porte une déclaration DOCTYPE.
     */
    public static void refuserDoctype(byte[] octets) throws IOException {
        int début = bomUtf8(octets);
        String prologue = new String(octets, début, octets.length - début,
                StandardCharsets.ISO_8859_1);
        int i = 0;
        while (true) {
            while (i < prologue.length() && prologue.charAt(i) <= ' ') {
                i++; // Espaces du prologue (tabulations, sauts, espaces).
            }
            if (commence(prologue, i, "<?")) {
                int fin = prologue.indexOf("?>", i + 2);
                if (fin < 0) {
                    return; // Prologue inachevé : le parseur donnera l'erreur réelle.
                }
                i = fin + 2;
            } else if (commence(prologue, i, "<!--")) {
                int fin = prologue.indexOf("-->", i + 4);
                if (fin < 0) {
                    return;
                }
                i = fin + 3;
            } else if (commence(prologue, i, "<!DOCTYPE")) {
                throw new IOException("DOCTYPE interdit dans le prologue "
                        + "(garde XXE, portable sur tout runtime)");
            } else {
                return; // Élément racine (ou fin) : plus de DOCTYPE possible après.
            }
        }
    }

    /**
     * Un constructeur DOM durci : le rejet des DOCTYPE est déjà garanti par le
     * scan du prologue AVANT ce parseur ({@link #refuserDoctype}) ; ici viennent
     * les garde-fous SUPPLÉMENTAIRES que le runtime propose — chacun au mieux —
     * puis le résolveur d'entités vide (mort des entités externes sur tout
     * runtime, interface standard).
     *
     * @param conscientEspacesDeNoms {@code true} pour un DOM conscient des
     *                               espaces de noms (manifestes Android :
     *                               attributs {@code android:*} lus par URI) ;
     *                               {@code false} pour les POM (espaces mélangés).
     * @return le constructeur prêt à analyser.
     * @throws ParserConfigurationException si la fabrique ne peut être créée.
     */
    public static DocumentBuilder nouveauParseur(boolean conscientEspacesDeNoms)
            throws ParserConfigurationException {
        DocumentBuilderFactory fabrique = DocumentBuilderFactory.newInstance();
        durcir(fabrique, conscientEspacesDeNoms);
        DocumentBuilder constructeur = fabrique.newDocumentBuilder();
        // Résolveur d'entités vide : TOUTE entité externe résout vers le vide —
        // interface standard honorée de la JVM comme d'ART, là où les
        // fonctionnalités SAX/Xerces ne sont pas reconnues (motif du moteur
        // de référence, REF-163).
        constructeur.setEntityResolver((identifiantPublic, identifiantSystème) ->
                new InputSource(new StringReader("")));
        return constructeur;
    }

    /**
     * Configure une fabrique DOM avec tous les garde-fous disponibles — CHACUN
     * AU MIEUX. Constat v0.2.9 (POM, rapport appareil) puis v0.2.16 (manifestes,
     * rapport appareil) : les URI de fonctionnalités Xerces (et même certaines
     * standard SAX) ne sont pas reconnues par le parseur DOM d'ART (KXml), dont
     * {@code setFeature} lève {@code ParserConfigurationException(URI)} — une
     * configuration en dur TUAIT la tâche sur l'appareil alors que la JVM de
     * test passait. Un garde-fou absent ne doit jamais tuer la lecture : le scan
     * du prologue ({@link #refuserDoctype}) et le résolveur d'entités vide
     * couvrent les mêmes menaces sur tout runtime. Visible pour les tests
     * (double de fabrique simulant ART).
     *
     * @param fabrique               la fabrique à durcir.
     * @param conscientEspacesDeNoms conscience des espaces de noms voulue.
     */
    static void durcir(DocumentBuilderFactory fabrique,
                       boolean conscientEspacesDeNoms) {
        fabrique.setNamespaceAware(conscientEspacesDeNoms);
        fabrique.setExpandEntityReferences(false);
        try {
            fabrique.setXIncludeAware(false);
        } catch (UnsupportedOperationException nonProposé) {
            // Fabriques sans support XInclude : inactif par défaut de toute façon.
        }
        activer(fabrique, "http://apache.org/xml/features/disallow-doctype-decl");
        activer(fabrique, XMLConstants.FEATURE_SECURE_PROCESSING);
        désactiver(fabrique, "http://xml.org/sax/features/external-general-entities");
        désactiver(fabrique, "http://xml.org/sax/features/external-parameter-entities");
        désactiver(fabrique,
                "http://apache.org/xml/features/nonvalidating/load-external-dtd");
        attributVide(fabrique, XMLConstants.ACCESS_EXTERNAL_DTD);
        attributVide(fabrique, XMLConstants.ACCESS_EXTERNAL_SCHEMA);
    }

    /**
     * Pose une fonctionnalité de durcissement {@code true} si le runtime la
     * reconnaît (sinon : rien — voir la javadoc de {@link #durcir}).
     */
    private static void activer(DocumentBuilderFactory fabrique, String nom) {
        try {
            fabrique.setFeature(nom, true);
        } catch (ParserConfigurationException nonReconnu) {
            // Fonctionnalité absente de ce runtime : le scan et le résolveur
            // d'entités vide couvrent la même menace.
        }
    }

    /**
     * Pose une fonctionnalité de durcissement {@code false} si le runtime la
     * reconnaît (sinon : rien).
     */
    private static void désactiver(DocumentBuilderFactory fabrique, String nom) {
        try {
            fabrique.setFeature(nom, false);
        } catch (ParserConfigurationException nonReconnu) {
            // Idem.
        }
    }

    /** Vide un attribut d'accès externe (JAXP 1.5) s'il est reconnu (sinon : rien). */
    private static void attributVide(DocumentBuilderFactory fabrique, String nom) {
        try {
            fabrique.setAttribute(nom, "");
        } catch (IllegalArgumentException | UnsupportedOperationException nonReconnu) {
            // Attribut absent de ce runtime.
        }
    }

    /** Longueur du BOM UTF-8 s'il ouvre les octets (0 sinon). */
    private static int bomUtf8(byte[] octets) {
        return octets.length >= 3 && (octets[0] & 0xFF) == 0xEF
                && (octets[1] & 0xFF) == 0xBB && (octets[2] & 0xFF) == 0xBF ? 3 : 0;
    }

    /** Le texte commence par le préfixe à l'indice donné ? */
    private static boolean commence(String texte, int indice, String préfixe) {
        return texte.regionMatches(indice, préfixe, 0, préfixe.length());
    }
}
