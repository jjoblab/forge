package jo.forge.engine.util;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests du DOM durci de {@link DomSûr} (v0.2.17). Deux incidents appareil, un
 * seul piège : le parseur DOM d'ART (KXml) ne reconnaît AUCUNE fonctionnalité
 * de durcissement Xerces ({@code setFeature} y lève
 * {@code ParserConfigurationException(URI)}) — un garde-fou codé en dur y a tué
 * la résolution Maven en v0.2.9 (kotlin-simple), puis la fusion de manifestes
 * en v0.2.16 (application-compose, « manifeste illisible :
 * …disallow-doctype-decl »). Ici : le durcissement SURVIT à une fabrique qui
 * refuse tout (double du parseur ART, déplacé de PomParserTest avec le
 * durcissement partagé), le refus des DOCTYPE est ABSOLU (prologue scanné en
 * octets bruts, BOM comprise), et le DOM conscient des espaces de noms lit
 * bien les attributs {@code android:*} par URI.
 */
class DomSûrTest {

    private static final String ANDROID_NS =
            "http://schemas.android.com/apk/res/android";

    @Test
    void durcissementSurvitÀUnParseurSansFonctionnalités() {
        // Constat v0.2.9 (POM) puis v0.2.16 (manifestes) : le parseur DOM
        // d'ART ne reconnaît AUCUNE des fonctionnalités de durcissement
        // (setFeature lève ParserConfigurationException(URI)) et la
        // configuration en dur tuait la tâche sur l'appareil. durcir doit
        // survivre à une fabrique qui refuse tout — et poser quand même les
        // réglages de l'API standard, dans CHAQUE mode d'espaces de noms
        // (POM : sans ; manifestes : avec).
        FabriqueArt pom = new FabriqueArt();
        assertDoesNotThrow(() -> DomSûr.durcir(pom, false));
        assertTrue(pom.posées.contains("namespaceAware=false"),
                "POM : les réglages standard restent posés : " + pom.posées);
        assertTrue(pom.posées.contains("expandEntityReferences=false"),
                "POM : l'expansion des entités reste morte : " + pom.posées);

        FabriqueArt manifeste = new FabriqueArt();
        assertDoesNotThrow(() -> DomSûr.durcir(manifeste, true));
        assertTrue(manifeste.posées.contains("namespaceAware=true"),
                "manifeste : DOM conscient des espaces de noms : "
                        + manifeste.posées);
        assertTrue(manifeste.posées.contains("expandEntityReferences=false"),
                "manifeste : l'expansion des entités reste morte : "
                        + manifeste.posées);
    }

    @Test
    void doctypeDuPrologueRefuséAbsolument() {
        // Le refus ne dépend d'AUCUNE fonctionnalité du runtime : scan du
        // prologue en octets bruts. Même message sur JVM et sur ART.
        assertRefusé("<!DOCTYPE manifest SYSTEM \"http://attaquant/xxe.dtd\">"
                .getBytes(StandardCharsets.UTF_8));
        assertRefusé(("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<!-- un commentaire honnête -->\n"
                + "<!DOCTYPE manifest>").getBytes(StandardCharsets.UTF_8));
        // Un BOM UTF-8 ne masque pas le DOCTYPE au scan (les octets du BOM,
        // décodés autrement, précéderaient la déclaration).
        assertRefusé(octetsAvecBom("<!DOCTYPE manifest>".getBytes(
                StandardCharsets.UTF_8)));
    }

    @Test
    void doctypeCitéDansUneCdataDuContenuPasRejeté() {
        // Une CDATA du CONTENU peut citer « <!DOCTYPE » sans danger : le scan
        // ne lit que le prologue (avant la racine) — pas de faux positif.
        assertDoesNotThrow(() -> DomSûr.refuserDoctype((
                "<manifest><application><![CDATA[ <!DOCTYPE piège> ]]>"
                        + "</application></manifest>")
                .getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void parseConscientDesEspacesDeNomsLitLesAttributsAndroid()
            throws Exception {
        DocumentBuilder constructeur = DomSûr.nouveauParseur(true);
        Document document = constructeur.parse(new ByteArrayInputStream((
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<manifest xmlns:android=\"" + ANDROID_NS + "\""
                + " package=\"demo.app\">\n"
                + "  <application android:label=\"Démo\">\n"
                + "    <activity android:name=\".MainActivity\"/>\n"
                + "  </application>\n"
                + "</manifest>").getBytes(StandardCharsets.UTF_8)));

        Element racine = document.getDocumentElement();
        assertEquals("manifest", racine.getTagName());
        assertEquals("demo.app", racine.getAttribute("package"),
                "l'attribut SANS espace de noms se lit en nu");
        Element application = (Element) racine
                .getElementsByTagName("application").item(0);
        assertNotNull(application.getAttributeNodeNS(ANDROID_NS, "label"),
                "l'attribut android:label se lit PAR URI d'espace de noms — "
                        + "c'est ce que la fusion exige");
    }

    private static void assertRefusé(byte[] octets) {
        IOException rejet = assertThrows(IOException.class, () ->
                DomSûr.refuserDoctype(octets));
        assertTrue(rejet.getMessage().contains("DOCTYPE interdit"),
                "le DOCTYPE du prologue est refusé : " + rejet.getMessage());
    }

    /** Préfixe les octets d'un BOM UTF-8. */
    private static byte[] octetsAvecBom(byte[] corps) {
        byte[] avecBom = new byte[3 + corps.length];
        avecBom[0] = (byte) 0xEF;
        avecBom[1] = (byte) 0xBB;
        avecBom[2] = (byte) 0xBF;
        System.arraycopy(corps, 0, avecBom, 3, corps.length);
        return avecBom;
    }

    /**
     * Double de fabrique simulant le parseur DOM d'ART : NE CONNAÎT aucune
     * fonctionnalité ({@code setFeature} lève {@code ParserConfigurationException(URI)}
     * — le crash appareil v0.2.9, récidivé v0.2.16) ni aucun attribut JAXP 1.5.
     */
    private static final class FabriqueArt extends DocumentBuilderFactory {

        final List<String> posées = new ArrayList<>();

        @Override
        public DocumentBuilder newDocumentBuilder() {
            throw new UnsupportedOperationException("pas utilisé par durcir");
        }

        @Override
        public void setFeature(String nom, boolean valeur)
                throws ParserConfigurationException {
            throw new ParserConfigurationException(nom);
        }

        @Override
        public boolean getFeature(String nom)
                throws ParserConfigurationException {
            throw new ParserConfigurationException(nom);
        }

        @Override
        public void setAttribute(String nom, Object valeur) {
            throw new IllegalArgumentException(nom);
        }

        @Override
        public Object getAttribute(String nom) {
            throw new IllegalArgumentException(nom);
        }

        @Override
        public void setNamespaceAware(boolean conscience) {
            posées.add("namespaceAware=" + conscience);
        }

        @Override
        public void setExpandEntityReferences(boolean expansion) {
            posées.add("expandEntityReferences=" + expansion);
        }

        @Override
        public void setXIncludeAware(boolean état) {
            posées.add("xIncludeAware=" + état);
        }
    }
}
