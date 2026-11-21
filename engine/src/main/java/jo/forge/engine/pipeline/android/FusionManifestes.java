package jo.forge.engine.pipeline.android;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import jo.forge.engine.util.DomSûr;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 *       la bibliothèque s'ajoutent au composant existant (les filtres
 *       s'additionnent, ils ne se remplacent pas), et ses
 *       {@code <meta-data>} manquants de même (v0.2.18) ;</li>
 *   <li>un nom de composant RELATIF de bibliothèque ({@code .Foo}) est étendu
 *       au paquet de cette bibliothèque avant fusion (il doit continuer de
 *       résoudre une fois sous le paquet de l'application).</li>
 * </ul>
 *
 * <p><b>Marqueurs de manifeste</b> (v0.2.18) : les valeurs
 * {@code ${applicationId}} (et tout marqueur déclaré par le projet) sont
 * substituées dans TOUT le document fusionné, application comprise — le
 * manifeste de {@code androidx.core} réclame par exemple
 * {@code ${applicationId}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION} et
 * {@code androidx.startup} construit ses autorités de provider dessus ; un
 * marqueur INCONNU est une erreur nette (même verdict qu'AGP : un APK aux
 * autorités littérales « ${…} » est silencieusement cassé).</p>
 *
 * <p><b>Marqueurs {@code tools:}</b> (v0.2.18, rapport appareil v0.2.17 : aapt2
 * refusait le manifeste fusionné — « unbound prefix », ligne du premier
 * composant de bibliothèque) : ces attributs sont des DIRECTIVES AU
 * FUSIONNEUR, jamais du contenu pour aapt2 — AGP ne les laisse d'ailleurs pas
 * dans sa sortie. La v0.2.17 les importait tels quels : le manifeste de
 * l'application ne déclare pas {@code xmlns:tools}, et le préfixe non lié tuait
 * {@code aapt2 link} (« unbound prefix », expat est strict). Désormais :
 * {@code tools:node="remove"} RETIRE l'élément de la fusion (sémantique AGP —
 * un tel {@code <uses-permission>} ne doit pas être ajouté) ; tout autre
 * attribut d'espace de noms étranger ({@code tools:} ou autre) est ÉLIMINÉ à
 * l'import, et un élément d'espace de noms étranger ({@code <dist:module>})
 * est écarté ; l'application subit le même nettoyage (ses propres
 * {@code tools:} ne doivent pas fuiter vers aapt2 non plus). L'écrivain
 * maison garde une DEFENSE finale : aucun préfixe non lié ne sort — mieux
 * vaut un diagnostic précis que l'« unbound prefix » d'aapt2.</p>
 *
 * <p>Hors périmètre (consigné dans limites-et-suite.md) : les marqueurs
 * {@code tools:} autres que {@code remove} (replace, overrideLibrary…) —
 * l'élément est fusionné normalement, le marqueur éliminé ; l'élévation
 * d'erreur sur conflit d'attribut — le manifeste applicatif GAGNE
 * silencieusement et la fusion est annoncée au journal.</p>
 *
 * <p><b>ART</b> (rapport appareil v0.2.16, correctif v0.2.17) : JAXP/DOM existe
 * sur la JVM comme sur ART, mais PAS ses fonctionnalités de durcissement
 * Xerces — la première mouture posait {@code disallow-doctype-decl} en dur et
 * tuait {@code processResources} sur l'appareil (« manifeste illisible :
 * …disallow-doctype-decl », récidive exacte du crash POM de v0.2.9).
 * L'analyse passe désormais par {@link DomSûr} (DOCTYPE refusé par scan du
 * prologue, garde-fous au mieux, résolveur d'entités vide) et la
 * sérialisation par un ÉCRIVEUR MAISON : {@code javax.xml.transform} n'est
 * pas fiable sur ART — le moteur de référence écrit lui aussi son manifeste
 * fusionné à la main pour cette raison (REF-163) ; les éléments de manifeste
 * ne portent que des attributs, jamais de contenu texte mélangé.</p>
 */
public final class FusionManifestes {

    /** L'espace de noms android (attributs fusionnés par nom). */
    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    /** L'espace de noms des déclarations xmlns (attributs xmlns:*). */
    private static final String XMLNS_NS = "http://www.w3.org/2000/xmlns/";

    /** L'espace de noms des directives de fusion tools:. */
    private static final String TOOLS_NS = "http://schemas.android.com/tools";

    /** La directive tools:node (seule sémantique honorée : remove). */
    private static final String DIRECTIVE_NŒUD = "node";

    /**
     * Un marqueur ${clef} dans une valeur d'attribut. L'accolade fermante est
     * ÉCHAPPÉE : les tests JVM passeraient sans (OpenJDK tient l'accolade
     * orpheline pour un littéral), mais ART compile ses regex par ICU natif,
     * qui la REFUSE (U_REGEX_RULE_SYNTAX — l'accolade hors classe n'y est
     * jamais un littéral, cf. SurfaceRegexIcuTest ; régression v0.2.18 sur
     * appareil : ExceptionInInitializerError au premier chargement de cette
     * classe, la fusion mourait avant tout message).
     */
    private static final Pattern MARQUEUR = Pattern.compile("\\$\\{([^}]+)\\}");

    /** Enfants de <application> fusionnés par (élément, android:name). */
    private static final Set<String> COMPOSANTS = Set.of(
            "activity", "activity-alias", "service", "receiver", "provider",
            "meta-data", "uses-library");

    private FusionManifestes() {
    }

    /**
     * Fusionne les manifestes de bibliothèques dans celui de l'application,
     * substitue les marqueurs et écrit un manifeste qu'aapt2 peut lier.
     *
     * @param manifesteApplication le manifeste de l'application (priorité maximale).
     * @param manifestesBibliothèques manifestes des AAR, priorité décroissante
     *                                (l'ordre du classpath résolu).
     * @param marqueurs             valeurs de substitution des {@code ${clef}}
     *                              (au moins {@code applicationId}) — vide pour
     *                              n'en substituer aucun.
     * @param sorties               fichier de sortie (manifeste fusionné).
     * @return le nombre de nœuds ajoutés par les bibliothèques (journal).
     * @throws IOException si un manifeste est illisible, un marqueur est
     *                     inconnu ou la fusion échoue.
     */
    public static int fusionner(Path manifesteApplication,
                                List<Path> manifestesBibliothèques,
                                Map<String, String> marqueurs,
                                Path sorties) throws IOException {
        if (manifestesBibliothèques.isEmpty() && marqueurs.isEmpty()) {
            Files.createDirectories(sorties.toAbsolutePath().getParent());
            Files.copy(manifesteApplication, sorties,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return 0;
        }
        Document fusion = analyser(Files.readAllBytes(manifesteApplication));
        Element racineFusion = fusion.getDocumentElement();
        // Le document de l'application est nettoyé et substitué lui aussi :
        // ses propres tools: ne doivent pas fuiter et ses ${marqueurs} sont
        // aussi réels que ceux des bibliothèques.
        nettoyerÉlément(racineFusion, marqueurs);
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
                    if (directiveRetrait(entrée)) {
                        continue; // tools:node="remove" : retiré de la fusion.
                    }
                    ajouts += ajouterSiAbsent(racineFusion, entrée, fusion,
                            marqueurs);
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
                if (directiveRetrait(composant)) {
                    continue;
                }
                Element entrée = importerEnÉtendant(composant, fusion,
                        paquetBibliothèque, marqueurs);
                ajouts += fusionnerComposant(application, entrée);
            }
        }
        Files.createDirectories(sorties.toAbsolutePath().getParent());
        écrire(fusion, sorties);
        return ajouts;
    }

    /**
     * Fusionne un composant de bibliothèque dans l'application : nouveau →
     * ajouté ; existant (même balise, même android:name) → ses
     * intent-filters ET ses meta-data manquants s'ajoutent au gagnant
     * applicatif (v0.2.18 : un {@code <provider>} d'androidx.startup connu
     * reçoit le {@code <meta-data>} du profileinstaller — sans lui,
     * l'initialisateur ne s'exécute pas).
     *
     * @return 1 si un nœud a été ajouté, 0 sinon.
     */
    private static int fusionnerComposant(Element application, Element entrée)
            throws IOException {
        String balise = entrée.getTagName();
        String nom = attribut(entrée, "name");
        for (Element existant : enfants(application, balise)) {
            if (nom != null && nom.equals(attribut(existant, "name"))) {
                for (Element filtre : enfants(entrée, "intent-filter")) {
                    existant.appendChild(
                            entrée.getOwnerDocument().importNode(filtre, true));
                }
                for (Element données : enfants(entrée, "meta-data")) {
                    ajouterSiAbsent(existant, données,
                            entrée.getOwnerDocument(), Map.of());
                }
                return 0;
            }
        }
        application.appendChild(entrée);
        return 1;
    }

    /** Ajoute une entrée (permission, feature…) par nom si absente. */
    private static int ajouterSiAbsent(Element racine, Element entrée,
                                       Document document, Map<String, String> marqueurs)
            throws IOException {
        String balise = entrée.getTagName();
        String nom = attribut(entrée, "name");
        for (Element existant : enfants(racine, balise)) {
            if (nom != null && nom.equals(attribut(existant, "name"))) {
                return 0;
            }
        }
        Element importé = (Element) document.importNode(entrée, true);
        nettoyerÉlément(importé, marqueurs);
        racine.appendChild(importé);
        return 1;
    }

    /**
     * Importe un élément de bibliothèque en étendant son nom RELATIF de
     * composant au paquet de la bibliothèque (un {@code .Foo} de
     * {@code androidx.x} devient {@code androidx.x.Foo}), puis en le
     * nettoyant de ses attributs d'espace de noms étranger.
     */
    private static Element importerEnÉtendant(Element composant, Document document,
                                              String paquetBibliothèque,
                                              Map<String, String> marqueurs)
            throws IOException {
        Element entrée = (Element) document.importNode(composant, true);
        nettoyerÉlément(entrée, marqueurs);
        String nom = attribut(entrée, "name");
        if (paquetBibliothèque != null && nom != null && nom.startsWith(".")) {
            entrée.setAttributeNS(ANDROID_NS, "android:name",
                    paquetBibliothèque + nom);
        }
        return entrée;
    }

    /**
     * La directive {@code tools:node} d'un élément de bibliothèque vaut-elle
     * « remove » (AGP : l'élément est RETIRÉ de la fusion, jamais ajouté) ?
     * Lue AVANT le nettoyage, qui élimine l'attribut.
     */
    private static boolean directiveRetrait(Element élément) {
        Attr directive = élément.getAttributeNodeNS(TOOLS_NS, DIRECTIVE_NŒUD);
        return directive != null && "remove".equals(directive.getValue());
    }

    /**
     * Nettoie RÉCURSIVEMENT un élément importé (ou le document applicatif
     * entier) pour que seul du contenu POUR aapt2 en ressorte :
     * <ul>
     *   <li>les attributs d'espace de noms étranger ( {@code tools:node},
     *       {@code tools:ignore}, {@code dist:…}) sont ÉLIMINÉS — ce sont des
     *       directives au fusionneur, jamais du contenu (rapport appareil
     *       v0.2.17 : « unbound prefix ») ; les déclarations xmlns:* restent ;</li>
     *   <li>un élément ENTIÈREMENT dans un espace de noms étranger
     *       ({@code <dist:module>}) est écarté ;</li>
     *   <li>les valeurs d'attributs voient leurs {@code ${marqueurs}}
     *       substitués — un marqueur inconnu est une ERREUR (même verdict
     *       qu'AGP : des autorités littérales « ${…} » cassent l'APK en
     *       silence).</li>
     * </ul>
     */
    private static void nettoyerÉlément(Element élément,
                                        Map<String, String> marqueurs)
            throws IOException {
        // Attributs : éliminer les espaces de noms étrangers, substituer les
        // valeurs. Copie des clefs d'abord : la carte change pendant la marche.
        NamedNodeMap attributs = élément.getAttributes();
        List<Attr> àParcourir = new ArrayList<>();
        for (int i = 0; i < attributs.getLength(); i++) {
            àParcourir.add((Attr) attributs.item(i));
        }
        for (Attr attribut : àParcourir) {
            String espace = attribut.getNamespaceURI();
            if (espace != null && !ANDROID_NS.equals(espace)
                    && !XMLNS_NS.equals(espace)) {
                attributs.removeNamedItem(attribut.getName());
                continue;
            }
            attribut.setValue(substituer(attribut.getValue(), marqueurs,
                    élément));
        }
        // Enfants : écarter les éléments d'espace de noms étranger, nettoyer
        // les autres en profondeur.
        List<Element> enfants = enfants(élément);
        for (Element enfant : enfants) {
            String espace = enfant.getNamespaceURI();
            if (espace != null && !ANDROID_NS.equals(espace)) {
                élément.removeChild(enfant);
                continue;
            }
            nettoyerÉlément(enfant, marqueurs);
        }
    }

    /** Substitue les ${clef} d'une valeur ; un marqueur inconnu est une erreur. */
    private static String substituer(String valeur, Map<String, String> marqueurs,
                                     Element où) throws IOException {
        if (marqueurs.isEmpty() || valeur.indexOf("${") < 0) {
            return valeur;
        }
        StringBuilder texte = new StringBuilder(valeur.length());
        Matcher chercheur = MARQUEUR.matcher(valeur);
        int finPrécédente = 0;
        while (chercheur.find()) {
            String clef = chercheur.group(1);
            String remplacement = marqueurs.get(clef);
            if (remplacement == null) {
                throw new IOException("marqueur de manifeste non résolu ${" + clef
                        + "} (élément <" + où.getTagName() + ">) — déclarez-le "
                        + "dans la facette android du module "
                        + "(manifestPlaceholders)");
            }
            texte.append(valeur, finPrécédente, chercheur.start());
            texte.append(remplacement);
            finPrécédente = chercheur.end();
        }
        texte.append(valeur.substring(finPrécédente));
        return texte.toString();
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

    /**
     * Analyse un manifeste par le DOM durci de {@link DomSûr} : le DOCTYPE est
     * refusé par SCAN DU PROLOGUE avant tout parseur (absolu et portable — la
     * fonctionnalité Xerces qui portait cette garde n'existe pas sur ART, le
     * rapport appareil v0.2.16 en a fait les frais), puis le DOM conscient des
     * espaces de noms s'exécute avec ses garde-fous posés UN PAR UN au mieux.
     */
    private static Document analyser(byte[] octets) throws IOException {
        try {
            DomSûr.refuserDoctype(octets);
            return DomSûr.nouveauParseur(true)
                    .parse(new ByteArrayInputStream(octets));
        } catch (Exception échec) {
            throw new IOException("manifeste illisible : " + échec.getMessage(),
                    échec);
        }
    }

    /**
     * Écrit le manifeste fusionné par un ÉCRIVEUR MAISON — jamais
     * {@code javax.xml.transform} : le Transformer n'est pas fiable sur ART et
     * le moteur de référence sérialise pareillement à la main (REF-163). Les
     * éléments de manifeste ne portent que des attributs : éléments + attributs
     * échappés, indentation stable, éléments vides auto-fermants — exactement
     * ce qu'aapt2 attend.
     *
     * <p><b>Canonisation des espaces de noms</b> : la racine déclare
     * {@code xmlns:android} (ajoutée si le manifeste applicatif employait un
     * autre préfixe), les attributs android sont écrits sous le préfixe
     * {@code android:} quel que soit leur préfixe source, et AUCUN préfixe non
     * lié ne sort — le rapport appareil v0.2.17 a montré ce qu'aapt2 en dit
     * (« unbound prefix ») ; ici le diagnostic nomme le préfixe fautif.</p>
     */
    private static void écrire(Document document, Path sorties) throws IOException {
        Element racine = document.getDocumentElement();
        // Canonisation des déclarations : tout attribut android est écrit sous
        // le préfixe « android: » (quel que soit son préfixe source) et tout
        // l'étranger est éliminé au nettoyage — la SEULE déclaration utile à
        // la racine est xmlns:android. Les autres (un « xmlns:ann » d'un
        // manifeste applicatif exotique) sont retirées : jamais de préfixe
        // lié pour rien, jamais de préfixe utilisé non lié.
        if (racine.getAttributeNode("xmlns:android") == null) {
            racine.setAttributeNS(XMLNS_NS, "xmlns:android", ANDROID_NS);
        }
        for (Attr déclaration : déclarations(racine)) {
            if (!"xmlns:android".equals(déclaration.getName())) {
                racine.getAttributes().removeNamedItem(déclaration.getName());
            }
        }
        Set<String> préfixesLiés = préfixesLiés(racine);
        StringBuilder texte = new StringBuilder();
        texte.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        écrireÉlément(racine, texte, 0, préfixesLiés);
        try {
            Files.write(sorties, texte.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException échec) {
            throw new IOException("manifeste fusionné non sérialisable : "
                    + échec.getMessage(), échec);
        }
    }

    /** Les déclarations xmlns:* d'un élément (copiées : la carte change). */
    private static List<Attr> déclarations(Element élément) {
        List<Attr> déclarations = new ArrayList<>();
        NamedNodeMap attributs = élément.getAttributes();
        for (int i = 0; i < attributs.getLength(); i++) {
            Attr attribut = (Attr) attributs.item(i);
            if (attribut.getName().startsWith("xmlns")) {
                déclarations.add(attribut);
            }
        }
        return déclarations;
    }

    /** Les préfixes que la racine déclare (xmlns:xxx="…"). */
    private static Set<String> préfixesLiés(Element racine) {
        Set<String> préfixes = new LinkedHashSet<>();
        NamedNodeMap attributs = racine.getAttributes();
        for (int i = 0; i < attributs.getLength(); i++) {
            Attr attribut = (Attr) attributs.item(i);
            if (attribut.getName().startsWith("xmlns:")
                    && !attribut.getName().equals("xmlns")) {
                préfixes.add(attribut.getName().substring("xmlns:".length()));
            }
        }
        return préfixes;
    }

    /**
     * Écrit un élément : attributs en ordre de document, enfants indentés.
     * Le nom d'un attribut android est CANONISÉ en {@code android:local} ;
     * tout préfixe non lié est refusé nettement.
     */
    private static void écrireÉlément(Element élément, StringBuilder texte,
                                       int profondeur, Set<String> préfixesLiés)
            throws IOException {
        String indentation = "    ".repeat(profondeur);
        String balise = nomSortie(élément.getTagName(), élément, préfixesLiés);
        texte.append(indentation).append('<').append(balise);
        NamedNodeMap attributs = élément.getAttributes();
        for (int i = 0; i < attributs.getLength(); i++) {
            Attr attribut = (Attr) attributs.item(i);
            String nom;
            if (ANDROID_NS.equals(attribut.getNamespaceURI())) {
                // Canonisation : le préfixe android, quel que soit celui de la
                // source.
                nom = "android:" + attribut.getLocalName();
            } else if (attribut.getName().startsWith("xmlns")) {
                // Déclaration d'espace de noms : elle LIE, elle ne l'est pas —
                // écrite telle quelle.
                nom = attribut.getName();
            } else {
                nom = nomSortie(attribut.getName(), élément, préfixesLiés);
            }
            texte.append(' ').append(nom).append("=\"")
                    .append(échapper(attribut.getValue())).append('"');
        }
        List<Element> enfants = enfants(élément);
        if (enfants.isEmpty()) {
            texte.append("/>\n");
        } else {
            texte.append(">\n");
            for (Element enfant : enfants) {
                écrireÉlément(enfant, texte, profondeur + 1, préfixesLiés);
            }
            texte.append(indentation).append("</")
                    .append(balise).append(">\n");
        }
    }

    /** Le nom qualifié de sortie, refusé s'il porte un préfixe non lié. */
    private static String nomSortie(String nomQualifié, Element où,
                                    Set<String> préfixesLiés) throws IOException {
        int deuxPoints = nomQualifié.indexOf(':');
        if (deuxPoints <= 0) {
            return nomQualifié; // Sans préfixe : aucun espace de noms à lier.
        }
        String préfixe = nomQualifié.substring(0, deuxPoints);
        if (!préfixesLiés.contains(préfixe)) {
            throw new IOException("préfixe XML non lié \"" + préfixe + ":\" (élément "
                    + "<" + où.getTagName() + ">) — aapt2 refuserait le manifeste "
                    + "(« unbound prefix ») ; les attributs de fusion tools: doivent "
                    + "être éliminés avant l'écriture");
        }
        return nomQualifié;
    }

    /** Échappe une valeur d'attribut (&amp;, &lt;, &gt;, &quot;). */
    private static String échapper(String valeur) {
        StringBuilder texte = new StringBuilder(valeur.length());
        for (int i = 0; i < valeur.length(); i++) {
            switch (valeur.charAt(i)) {
                case '&' -> texte.append("&amp;");
                case '<' -> texte.append("&lt;");
                case '>' -> texte.append("&gt;");
                case '"' -> texte.append("&quot;");
                default -> texte.append(valeur.charAt(i));
            }
        }
        return texte.toString();
    }
}
