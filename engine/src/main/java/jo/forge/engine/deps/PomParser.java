package jo.forge.engine.deps;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import jo.forge.engine.util.DomSûr;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parseur POM strict et durci.
 *
 * <p>Défenses XXE <strong>portables</strong> (constat v0.2.9 sur ART : la
 * fonctionnalité Xerces {@code disallow-doctype-decl} n'y est pas reconnue et
 * tuait la PREMIÈRE résolution Maven d'un build appareil — kotlin-simple) :
 * depuis la v0.2.17 elles vivent dans {@link DomSûr}, PARTAGÉES avec la fusion
 * de manifestes (qui a réappris le même mur en v0.2.16 — rapport appareil
 * Compose) : DOCTYPE interdit par scan du PROLOGUE en octets bruts
 * (indépendant du runtime, même position que Maven), entités externes mortes
 * par un résolveur d'entités vide, expansion désactivée, fonctionnalités de
 * durcissement Xerces/JAXP posées AU MIEUX — une fonctionnalité absente du
 * runtime ne tue jamais la résolution (le moteur de référence enveloppe les
 * siennes de {@code runCatching} pour la même raison, REF-163). Les espaces de
 * noms sont ignorés (les POM du monde réel mélangent avec et sans préfixe).
 * Toute anomalie structurelle est rejetée avec un message citant la
 * coordonnée.</p>
 */
public final class PomParser {

    private PomParser() {
    }

    /**
     * Analyse un fichier POM.
     *
     * @param fichier     chemin du POM.
     * @param coordonnée  coordonnée attendue (pour les messages d'erreur).
     * @return le POM brut.
     * @throws ResolverException si le fichier est illisible ou structurellement invalide.
     */
    public static RawPom parse(Path fichier, Coordinate coordonnée) {
        try {
            return parse(Files.readAllBytes(fichier), coordonnée);
        } catch (ResolverException e) {
            throw e;
        } catch (IOException e) {
            throw new ResolverException("POM illisible pour " + coordonnée + " ("
                    + fichier + ") : " + e.getMessage(), e);
        }
    }

    /**
     * Analyse un POM depuis un flux (lu en entier : le prologue est scanné
     * avant toute construction de parseur).
     *
     * @param entrée     flux XML.
     * @param coordonnée coordonnée attendue (messages).
     * @return le POM brut.
     * @throws ResolverException en cas d'erreur.
     */
    public static RawPom parse(InputStream entrée, Coordinate coordonnée) {
        try {
            return parse(entrée.readAllBytes(), coordonnée);
        } catch (IOException e) {
            throw new ResolverException("POM illisible pour " + coordonnée + " : "
                    + e.getMessage(), e);
        }
    }

    /**
     * Analyse un POM depuis ses octets — la porte d'entrée commune : le
     * prologue est scanné AVANT tout parseur (rejet DOCTYPE absolu), puis le
     * DOM durci s'exécute.
     *
     * @param octets      octets du POM.
     * @param coordonnée  coordonnée attendue (messages).
     * @return le POM brut.
     * @throws ResolverException si le contenu est structurellement invalide ou
     *                           porte un DOCTYPE.
     */
    public static RawPom parse(byte[] octets, Coordinate coordonnée) {
        refuserDoctype(octets, coordonnée);
        try {
            Document document = DomSûr.nouveauParseur(false)
                    .parse(new ByteArrayInputStream(octets));
            Element projet = document.getDocumentElement();
            if (!"project".equals(nomLocal(projet))) {
                throw new ResolverException("POM " + coordonnée
                        + " : racine <project> attendue, trouvé <"
                        + nomLocal(projet) + ">");
            }
            Element parent = enfant(projet, "parent");
            Coordinate parentCoord = parent == null ? null : new Coordinate(
                    texte(parent, "groupId"), texte(parent, "artifactId"),
                    texte(parent, "version"));
            String group = texteOuNul(projet, "groupId");
            String artifact = texteOuNul(projet, "artifactId");
            String version = texteOuNul(projet, "version");
            if (group == null && parentCoord != null) {
                group = parentCoord.group();
            }
            if (version == null && parentCoord != null) {
                version = parentCoord.version();
            }
            String packaging = texteOuNul(projet, "packaging");
            Map<String, String> propriétés = new LinkedHashMap<>();
            Element blocPropriétés = enfant(projet, "properties");
            if (blocPropriétés != null) {
                for (Element propriété : enfants(blocPropriétés)) {
                    propriétés.put(nomLocal(propriété), propriété.getTextContent().trim());
                }
            }
            List<RawPom.ManagedDep> managed = new ArrayList<>();
            Element gestion = enfant(projet, "dependencyManagement");
            if (gestion != null) {
                Element deps = enfant(gestion, "dependencies");
                if (deps != null) {
                    for (Element dep : enfantsNommés(deps, "dependency")) {
                        String g = texte(dep, "groupId");
                        String a = texte(dep, "artifactId");
                        String v = texteOuNul(dep, "version");
                        String portée = texteOuNul(dep, "scope");
                        if ("import".equals(portée)) {
                            // Import de nomenclature (BOM) : traité par le résolveur,
                            // conservé comme entrée avec la marque « import ».
                            managed.add(new RawPom.ManagedDep(new Gav(g, a),
                                    "import:" + v));
                        } else {
                            managed.add(new RawPom.ManagedDep(new Gav(g, a), v));
                        }
                    }
                }
            }
            List<RawPom.PomDep> dependencies = new ArrayList<>();
            Element blocDeps = enfant(projet, "dependencies");
            if (blocDeps != null) {
                for (Element dep : enfantsNommés(blocDeps, "dependency")) {
                    dependencies.add(lireDépendance(dep));
                }
            }
            Coordinate relocation = null;
            Element distribution = enfant(projet, "distributionManagement");
            if (distribution != null) {
                Element relogement = enfant(distribution, "relocation");
                if (relogement != null) {
                    String g = texte(relogement, "groupId");
                    String a = texte(relogement, "artifactId");
                    String v = texteOuNul(relogement, "version");
                    relocation = new Coordinate(g, a,
                            v == null ? coordonnée.version() : v);
                }
            }
            return new RawPom(group, artifact, version, parentCoord,
                    packaging == null ? "jar" : packaging, propriétés, managed,
                    dependencies, relocation);
        } catch (ResolverException e) {
            throw e;
        } catch (Exception e) {
            throw new ResolverException("POM invalide pour " + coordonnée + " : "
                    + e.getMessage(), e);
        }
    }

    /**
     * Lit une dépendance déclarée.
     */
    private static RawPom.PomDep lireDépendance(Element dep) {
        Set<Gav> exclusions = new LinkedHashSet<>();
        Element blocExclusions = enfant(dep, "exclusions");
        if (blocExclusions != null) {
            for (Element exclusion : enfantsNommés(blocExclusions, "exclusion")) {
                exclusions.add(new Gav(texte(exclusion, "groupId"),
                        texte(exclusion, "artifactId")));
            }
        }
        String optionnel = texteOuNul(dep, "optional");
        return new RawPom.PomDep(
                texte(dep, "groupId"),
                texte(dep, "artifactId"),
                texteOuNul(dep, "version"),
                texteOuNul(dep, "scope"),
                "true".equalsIgnoreCase(optionnel),
                exclusions);
    }

    /**
     * Rejette tout POM dont le PROLOGUE porte une déclaration DOCTYPE — la garde
     * XXE absolue et PORTABLE du parseur : la fonctionnalité Xerces qui la portait
     * sur la JVM n'existe pas sur ART, et le traitement sûr JAXP standard
     * n'y interdit pas les DOCTYPE. Le scan ne lit que le prologue (avant
     * l'élément racine) : le mot-clé {@code <!DOCTYPE} y est en MAJUSCULES
     * (grammaire XML), et une section CDATA du CONTENU ne peut pas créer de
     * faux positif — elle suit nécessairement la racine. Le refus est total :
     * un DOCTYPE suffit à porter des entités externes (XXE) comme internes
     * (bombe à entités) ; aucun POM légitime n'en porte (même position que
     * Maven, qui rejette pareillement).
     *
     * <p>Depuis la v0.2.17, le scan vit dans {@link DomSûr} — PARTAGÉ avec la
     * fusion de manifestes (le rapport appareil v0.2.16 a montré le coût d'un
     * correctif local non généralisé).</p>
     *
     * @param octets      octets du POM.
     * @param coordonnée  coordonnée attendue (message).
     * @throws ResolverException si le prologue porte une déclaration DOCTYPE.
     */
    static void refuserDoctype(byte[] octets, Coordinate coordonnée) {
        try {
            DomSûr.refuserDoctype(octets);
        } catch (IOException échec) {
            throw new ResolverException("POM invalide pour " + coordonnée
                    + " : " + échec.getMessage(), échec);
        }
    }

    /** Premier enfant direct portant ce nom (espaces de noms ignorés). */
    private static Element enfant(Element parent, String nom) {
        for (Element enfant : enfants(parent)) {
            if (nomLocal(enfant).equals(nom)) {
                return enfant;
            }
        }
        return null;
    }

    /** Enfants directs éléments. */
    private static List<Element> enfants(Element parent) {
        List<Element> résultat = new ArrayList<>();
        NodeList nœuds = parent.getChildNodes();
        for (int i = 0; i < nœuds.getLength(); i++) {
            if (nœuds.item(i) instanceof Element élément) {
                résultat.add(élément);
            }
        }
        return résultat;
    }

    /** Enfants directs éléments portant ce nom. */
    private static List<Element> enfantsNommés(Element parent, String nom) {
        List<Element> résultat = new ArrayList<>();
        for (Element enfant : enfants(parent)) {
            if (nomLocal(enfant).equals(nom)) {
                résultat.add(enfant);
            }
        }
        return résultat;
    }

    /** Texte obligatoire d'un enfant (rejet avec contexte si absent). */
    private static String texte(Element parent, String nom) {
        String valeur = texteOuNul(parent, nom);
        if (valeur == null || valeur.isBlank()) {
            throw new ResolverException("POM : <" + nom + "> manquant sous <"
                    + nomLocal(parent) + ">");
        }
        return valeur.trim();
    }

    /** Texte facultatif d'un enfant (null si absent ou vide). */
    private static String texteOuNul(Element parent, String nom) {
        Element enfant = enfant(parent, nom);
        if (enfant == null) {
            return null;
        }
        String texte = enfant.getTextContent().trim();
        return texte.isEmpty() ? null : texte;
    }

    /** Nom local d'un élément (sans préfixe d'espace de noms). */
    private static String nomLocal(Element élément) {
        String nom = élément.getNodeName();
        int deuxPoints = nom.indexOf(':');
        return deuxPoints >= 0 ? nom.substring(deuxPoints + 1) : nom;
    }
}
