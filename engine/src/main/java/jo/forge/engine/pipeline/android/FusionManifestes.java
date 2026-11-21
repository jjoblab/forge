package jo.forge.engine.pipeline.android;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Fusion des manifestes Android des AAR d'un module dans le manifeste de
 * l'application (v0.2.15, prérequis Compose) : sans elle, les
 * {@code <provider>}/{@code <service>} déclarés par les bibliothèques
 * (androidx.startup, lifecycle-process, profileinstaller…) manquent à l'APK et
 * l'application plante à l'exécution.
 *
 * <p>Algorithme (une réimplémentation volontairement MINIMALE du fusionneur
 * d'AGP, écrite à partir de sa sémantique documentée — pas de son code) :</p>
 * <ul>
 *   <li>le manifeste de l'application est le document de PLUS HAUTE priorité ;
 *       les manifestes de bibliothèques suivent, par priorité décroissante ;</li>
 *   <li>{@code <uses-permission>} et {@code <uses-feature>} : fusion par
 *       {@code android:name} — une bibliothèque AJOUTE, jamais ne retire ;</li>
 *   <li>{@code <uses-sdk>} des bibliothèques : IGNORÉ — le module est maître de
 *       ses niveaux (déjà passés à aapt2 par {@code --min/--target-sdk-version}) ;</li>
 *   <li>les enfants de {@code <application>} fusionnent par (élément,
 *       {@code android:name}) : le gagnant de plus haute priorité est gardé, un
 *       nouvel arrivant est ajouté ; {@code <meta-data>} par
 *       {@code android:name} ; {@code <intent-filter>} d'un composant connu de
 *       la bibliothèque sont ajoutés au composant existant (les filtres
 *       s'additionnent, ils ne se remplacent pas) ;</li>
 *   <li>un nom de composant RELATIF de bibliothèque ({@code .Foo}) est étendu
 *       au paquet de cette bibliothèque avant fusion (il doit continuer de
 *       résoudre une fois sous le paquet de l'application).</li>
 * </ul>
 *
 * <p>Hors périmètre (consigné dans limites-et-suite.md) : les marqueurs
 * {@code tools:} (replace/remove/overrideLibrary), les placeholders
 * {@code ${applicationId}}, et l'élévation d'erreur sur conflit d'attribut —
 * ici le manifeste applicatif GAGNE silencieusement et la fusion est annoncée
 * au journal. JAXP/DOM est disponible sur la JVM comme sur ART.</p>
 */
public final class FusionManifestes {

    /** L'espace de noms android (attributs fusionnés par nom). */
    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    /** Enfants de <application> fusionnés par (élément, android:name). */
    private static final Set<String> COMPOSANTS = Set.of(
            "activity", "activity-alias", "service", "receiver", "provider",
            "meta-data", "uses-library");

    private FusionManifestes() {
    }

    /**
     * Fusionne les manifestes de bibliothèques dans celui de l'application.
     *
     * @param manifesteApplication le manifeste de l'application (priorité maximale).
     * @param manifestesBibliothèques manifestes des AAR, priorité décroissante
     *                                (l'ordre du classpath résolu).
     * @param sorties              fichier de sortie (manifeste fusionné).
     * @return le nombre de nœuds ajoutés par les bibliothèques (journal).
     * @throws IOException si un manifeste est illisible ou la fusion échoue.
     */
    public static int fusionner(Path manifesteApplication,
                                List<Path> manifestesBibliothèques,
                                Path sorties) throws IOException {
        if (manifestesBibliothèques.isEmpty()) {
            Files.createDirectories(sorties.getParent());
            Files.copy(manifesteApplication, sorties,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return 0;
        }
        Document fusion = analyser(Files.readAllBytes(manifesteApplication));
        Element racineFusion = fusion.getDocumentElement();
        Element application = premierEnfant(racineFusion, "application");
        int ajouts = 0;
        for (Path manifeste : manifestesBibliothèques) {
            Document bibliothèque = analyser(Files.readAllBytes(manifeste));
            Element racineBibliothèque = bibliothèque.getDocumentElement();
            // L'attribut package du manifeste est SANS espace de noms (contrairement
            // aux attributs android:*) — getAttribute nu, pas la recherche qualifiée.
            String paquetBibliothèque = racineBibliothèque.getAttribute("package");
            // permissions et features du manifeste racine
            for (String balise : List.of("uses-permission", "uses-feature")) {
                for (Element entrée : enfants(racineBibliothèque, balise)) {
                    ajouts += ajouterSiAbsent(racineFusion, entrée, fusion);
                }
            }
            // composants de <application>
            Element applicationBibliothèque =
                    premierEnfant(racineBibliothèque, "application");
            if (applicationBibliothèque == null) {
                continue;
            }
            if (application == null) {
                application = fusion.createElement("application");
                racineFusion.appendChild(application);
            }
            for (Element composant : enfants(applicationBibliothèque)) {
                String balise = composant.getTagName();
                if (!COMPOSANTS.contains(balise)) {
                    continue; // Autres nœuds : non fusionnés en v1 (consigné).
                }
                Element entrée = importerEnÉtendant(composant, fusion,
                        paquetBibliothèque);
                ajouts += fusionnerComposant(application, entrée);
            }
        }
        Files.createDirectories(sorties.getParent());
        écrire(fusion, sorties);
        return ajouts;
    }

    /**
     * Fusionne un composant de bibliothèque dans l'application : nouveau →
     * ajouté ; existant (même balise, même android:name) → ses intent-filters
     * manquants s'ajoutent au gagnant applicatif.
     *
     * @return 1 si un nœud a été ajouté, 0 sinon.
     */
    private static int fusionnerComposant(Element application, Element entrée) {
        String balise = entrée.getTagName();
        String nom = attribut(entrée, "name");
        for (Element existant : enfants(application, balise)) {
            if (nom != null && nom.equals(attribut(existant, "name"))) {
                for (Element filtre : enfants(entrée, "intent-filter")) {
                    existant.appendChild(
                            entrée.getOwnerDocument().importNode(filtre, true));
                }
                return 0;
            }
        }
        application.appendChild(entrée);
        return 1;
    }

    /** Ajoute une entrée (permission, feature…) par nom si absente. */
    private static int ajouterSiAbsent(Element racine, Element entrée,
                                       Document document) {
        String balise = entrée.getTagName();
        String nom = attribut(entrée, "name");
        for (Element existant : enfants(racine, balise)) {
            if (nom != null && nom.equals(attribut(existant, "name"))) {
                return 0;
            }
        }
        racine.appendChild(document.importNode(entrée, true));
        return 1;
    }

    /**
     * Importe un élément de bibliothèque en étendant son nom RELATIF de
     * composant au paquet de la bibliothèque (un {@code .Foo} de
     * {@code androidx.x} devient {@code androidx.x.Foo}).
     */
    private static Element importerEnÉtendant(Element composant, Document document,
                                              String paquetBibliothèque) {
        Element entrée = (Element) document.importNode(composant, true);
        String nom = attribut(entrée, "name");
        if (paquetBibliothèque != null && nom != null && nom.startsWith(".")) {
            entrée.setAttributeNS(ANDROID_NS, "android:name",
                    paquetBibliothèque + nom);
        }
        return entrée;
    }

    private static Element premierEnfant(Element parent, String balise) {
        for (Element enfant : enfants(parent, balise)) {
            return enfant;
        }
        return null;
    }

    private static List<Element> enfants(Element parent) {
        List<Element> résultat = new ArrayList<>();
        for (Node nœud = parent.getFirstChild(); nœud != null;
             nœud = nœud.getNextSibling()) {
            if (nœud instanceof Element élément) {
                résultat.add(élément);
            }
        }
        return résultat;
    }

    private static List<Element> enfants(Element parent, String balise) {
        List<Element> résultat = new ArrayList<>();
        for (Element enfant : enfants(parent)) {
            if (enfant.getTagName().equals(balise)) {
                résultat.add(enfant);
            }
        }
        return résultat;
    }

    /** L'attribut android:name d'un élément (ou null). */
    private static String attribut(Element élément, String nomLocal) {
        Attr attribut = élément.getAttributeNodeNS(ANDROID_NS, nomLocal);
        if (attribut != null) {
            return attribut.getValue();
        }
        // Certains manifestes d'AAR n'ont pas d'espace de noms résolu : repli
        // sur le nom préfixé tel qu'écrit.
        return élément.getAttribute("android:" + nomLocal);
    }

    private static Document analyser(byte[] octets) throws IOException {
        try {
            DocumentBuilderFactory fabrique =
                    DocumentBuilderFactory.newInstance();
            fabrique.setNamespaceAware(true);
            fabrique.setFeature(
                    "http://apache.org/xml/features/disallow-doctype-decl", true);
            return fabrique.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(octets));
        } catch (Exception échec) {
            throw new IOException("manifeste illisible : " + échec.getMessage(),
                    échec);
        }
    }

    private static void écrire(Document document, Path sorties) throws IOException {
        try {
            Transformer transformeur = TransformerFactory.newInstance()
                    .newTransformer();
            transformeur.setOutputProperty(OutputKeys.ENCODING, "utf-8");
            transformeur.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
            transformeur.transform(new DOMSource(document),
                    new StreamResult(Files.newOutputStream(sorties)));
        } catch (Exception échec) {
            throw new IOException("manifeste fusionné non sérialisable : "
                    + échec.getMessage(), échec);
        }
    }
}
