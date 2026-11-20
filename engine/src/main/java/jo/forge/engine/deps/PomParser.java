package jo.forge.engine.deps;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
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
 * <p>Défenses (parité avec l'exigence de sécurité du projet, et avec la garde du moteur
 * de référence) : <strong>DOCTYPE interdit</strong> (défense XXE), entités externes
 * désactivées, DTD externe non chargée. Les espaces de noms sont ignorés (les POM du
 * monde réel mélangent avec et sans préfixe). Toute anomalie structurelle est rejetée
 * avec un message citant la coordonnée.</p>
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
        try (InputStream entrée = Files.newInputStream(fichier)) {
            return parse(entrée, coordonnée);
        } catch (ResolverException e) {
            throw e;
        } catch (Exception e) {
            throw new ResolverException("POM illisible pour " + coordonnée + " ("
                    + fichier + ") : " + e.getMessage(), e);
        }
    }

    /**
     * Analyse un POM depuis un flux.
     *
     * @param entrée     flux XML.
     * @param coordonnée coordonnée attendue (messages).
     * @return le POM brut.
     * @throws ResolverException en cas d'erreur.
     */
    public static RawPom parse(InputStream entrée, Coordinate coordonnée) {
        try {
            DocumentBuilder constructeur = nouveauParseur();
            Document document = constructeur.parse(entrée);
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
     * Constructeur DOM durci : DOCTYPE interdit, entités externes mortes.
     */
    private static DocumentBuilder nouveauParseur() throws Exception {
        DocumentBuilderFactory fabrique = DocumentBuilderFactory.newInstance();
        fabrique.setNamespaceAware(false);
        fabrique.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        fabrique.setFeature("http://xml.org/sax/features/external-general-entities", false);
        fabrique.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        fabrique.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd",
                false);
        fabrique.setXIncludeAware(false);
        fabrique.setExpandEntityReferences(false);
        fabrique.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        fabrique.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return fabrique.newDocumentBuilder();
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
