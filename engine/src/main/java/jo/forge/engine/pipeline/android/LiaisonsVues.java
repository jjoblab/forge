package jo.forge.engine.pipeline.android;

import jo.forge.engine.util.DomSûr;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Le modèle d'une classe ViewBinding dérivée d'un layout Android (v0.2.18) :
 * ce que la tâche {@code generateViewBinding} compile — une réimplémentation
 * de la SÉMANTIQUE du générateur d'AGP (écrite à partir de son comportement
 * documenté et observé, pas de son code).
 *
 * <p>Règles reprises d'AGP :</p>
 * <ul>
 *   <li>une classe {@code <Layout>Binding} par NOM de layout (les variantes
 *       de configuration {@code layout/}, {@code layout-land/}… fusionnent :
 *       l'UNION de leurs ids — une vue présente dans UNE configuration a son
 *       champ) ;</li>
 *   <li>un champ public final par {@code android:id="@+id/…"} (les ids du
 *       framework {@code @android:id/…} n'en produisent pas), nommé en
 *       camelCase depuis l'id ({@code foo_bar} → {@code fooBar}), typé par la
 *       classe de la balise résolue comme le fait l'inflater ;</li>
 *   <li>un {@code <include>} DANS ce module expose la LIAISON du layout
 *       inclus (le champ appelle sa méthode {@code bind}) ; un include d'un
 *       autre module ou sans identifiant reste une {@code android.view.View} ;
 *       un include SANS id ne produit AUCUN champ (rien à quoi le lier) ;</li>
 *   <li>{@code tools:viewBindingIgnore="true"} sur la racine écarte le layout
 *       entier, sur une vue écarte cette vue ;</li>
 *   <li>la racine d'un layout {@code <merge>} est la {@code android.view.View}
 *       passée à l'attach (le binding des sous-vues se fait par
 *       findViewById sur cette racine).</li>
 * </ul>
 *
 * <p>Limite assumée (consignée dans limites-et-suite.md) : un
 * {@code <ViewStub>} porte un champ de type {@code android.view.ViewStub} —
 * AGP l'enveloppe dans un {@code ViewStubProxy} qui re-lie à l'inflation ;
 * le type brut compile et s'utilite de la même façon à la compilation.</p>
 */
final class LiaisonsVues {

    /** L'espace de noms android des attributs de layout. */
    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    /** L'espace de noms tools (viewBindingIgnore). */
    private static final String TOOLS_NS = "http://schemas.android.com/tools";

    /** Le suffixe de paquet des classes générées (convention AGP). */
    private static final String SUFFIXE_PAQUET = "databinding";

    /** Les balises de vues du framework qui vivent en android.view (pas widget). */
    private static final Set<String> PAQUET_VIEW = Set.of("View", "ViewGroup",
            "ViewStub", "SurfaceView", "TextureView");

    /** Une liaison dérivée d'un layout. */
    record Liaison(String nomLayout, String nomClasse, String paquet,
                   String typeVueRacine, List<Champ> champs) {
    }

    /** Un champ de liaison (une vue identifiée du layout). */
    record Champ(String nom, String idRessource, String typeVue,
                 boolean liaisonIncluse) {
    }

    private LiaisonsVues() {
    }

    /** Le paquet des liaisons d'un module : {@code <namespace>.databinding}. */
    static String paquet(String namespace) {
        return namespace.isBlank() ? SUFFIXE_PAQUET
                : namespace + "." + SUFFIXE_PAQUET;
    }

    /** {@code activity_main} → {@code ActivityMainBinding} (règle AGP). */
    static String nomClasse(String nomLayout) {
        StringBuilder nom = new StringBuilder();
        for (String segment : nomLayout.split("_")) {
            if (segment.isEmpty()) {
                continue;
            }
            nom.append(Character.toUpperCase(segment.charAt(0)))
                    .append(segment.substring(1));
        }
        return nom + "Binding";
    }

    /** {@code foo_bar} → {@code fooBar} (règle AGP). */
    static String nomChamp(String id) {
        String[] segments = id.split("_");
        StringBuilder nom = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (segments[i].isEmpty()) {
                continue;
            }
            if (i == 0) {
                nom.append(segments[i]);
            } else {
                nom.append(Character.toUpperCase(segments[i].charAt(0)))
                        .append(segments[i].substring(1));
            }
        }
        return nom.isEmpty() ? id : nom.toString();
    }

    /**
     * Toutes les liaisons des layouts PROPRES du module (les rés d'un AAR ne
     * génèrent rien chez le consommateur), triées par nom — sortie stable et
     * dédupliquée.
     *
     * @param répertoiresRes les répertoires res du module.
     * @param namespace      l'espace de noms du module.
     * @return les liaisons, triées par nom de layout.
     * @throws IOException si un layout est illisible.
     */
    static List<Liaison> liaisons(List<Path> répertoiresRes, String namespace)
            throws IOException {
        Map<String, List<Path>> parNom = new LinkedHashMap<>();
        for (Path res : répertoiresRes) {
            if (!Files.isDirectory(res)) {
                continue;
            }
            try (var dir = Files.list(res)) {
                dir.filter(Files::isDirectory)
                        .filter(d -> d.getFileName().toString()
                                .split("-", 2)[0].equals("layout"))
                        .forEach(d -> {
                            try (var fichiers = Files.list(d)) {
                                fichiers.filter(f -> f.toString().endsWith(".xml"))
                                        .forEach(f -> parNom
                                                .computeIfAbsent(nomSansExtension(f),
                                                        k -> new ArrayList<>())
                                                .add(f));
                            } catch (IOException échec) {
                                throw new java.io.UncheckedIOException(échec);
                            }
                        });
            } catch (java.io.UncheckedIOException échec) {
                throw échec.getCause();
            }
        }
        Set<String> layoutsConnus = parNom.keySet();
        List<Liaison> liaisons = new ArrayList<>();
        for (Map.Entry<String, List<Path>> entrée : parNom.entrySet()) {
            Liaison liaison = liaison(entrée.getKey(), entrée.getValue(),
                    namespace, layoutsConnus);
            if (liaison != null) {
                liaisons.add(liaison);
            }
        }
        liaisons.sort((a, b) -> a.nomLayout().compareTo(b.nomLayout()));
        return liaisons;
    }

    /** La liaison d'un nom de layout, union de toutes ses configurations. */
    private static Liaison liaison(String nomLayout, List<Path> fichiers,
                                   String namespace, Set<String> layoutsConnus)
            throws IOException {
        List<LayoutAnalyse> analyses = new ArrayList<>();
        for (Path fichier : fichiers) {
            LayoutAnalyse analyse = analyser(fichier);
            if (analyse != null) {
                analyses.add(analyse);
            }
        }
        if (analyses.isEmpty()) {
            return null;
        }
        // tools:viewBindingIgnore sur la racine d'UNE configuration écarte le
        // layout ENTIER (le binding ne peut pas ignorer une config : le champ
        // manquerait au runtime).
        if (analyses.stream().anyMatch(a -> a.ignoré)) {
            return null;
        }
        Map<String, Champ> champs = new LinkedHashMap<>();
        String typeRacine = "android.view.View";
        for (LayoutAnalyse analyse : analyses) {
            if (!analyse.racineMerge) {
                typeRacine = typeDeVue(analyse.baliseRacine, analyse.classeRacine);
            }
            for (VueBrute vue : analyse.vues) {
                champs.computeIfAbsent(nomChamp(vue.id),
                        nom -> champDe(nom, vue, namespace, layoutsConnus));
            }
        }
        return new Liaison(nomLayout, nomClasse(nomLayout), paquet(namespace),
                typeRacine, new ArrayList<>(champs.values()));
    }

    /** Le champ dérivé d'une vue identifiée (vue simple ou include interne). */
    private static Champ champDe(String nom, VueBrute vue, String namespace,
                                 Set<String> layoutsConnus) {
        if (vue.layoutInclus != null && layoutsConnus.contains(vue.layoutInclus)) {
            return new Champ(nom, vue.id,
                    paquet(namespace) + "." + nomClasse(vue.layoutInclus), true);
        }
        return new Champ(nom, vue.id, typeDeVue(vue.balise, vue.classe), false);
    }

    /** Le type pleinement qualifié d'une balise de vue, comme l'inflater la résout. */
    private static String typeDeVue(String balise, String attributClasse) {
        if (balise.contains(".")) {
            return balise; // Déjà qualifiée : vue AndroidX ou personnalisée.
        }
        return switch (balise) {
            case "merge", "include", "fragment" -> "android.view.View";
            case "view" -> attributClasse.isBlank() ? "android.view.View"
                    : attributClasse;
            case "WebView" -> "android.webkit.WebView";
            default -> PAQUET_VIEW.contains(balise)
                    ? "android.view." + balise : "android.widget." + balise;
        };
    }

    /** Le résultat de l'analyse d'un fichier de layout. */
    private record LayoutAnalyse(boolean ignoré, boolean racineMerge,
                                 String baliseRacine, String classeRacine,
                                 List<VueBrute> vues) {
    }

    /** Une vue identifiée du layout, avant résolution de type. */
    private record VueBrute(String id, String balise, String classe,
                            String layoutInclus) {
    }

    /**
     * Analyse un layout par le DOM durci (DOCTYPE refusé par scan, résolveur
     * d'entités vide — un layout est une donnée locale, jamais une entité
     * externe).
     */
    private static LayoutAnalyse analyser(Path fichier) throws IOException {
        Document document;
        try {
            byte[] octets = Files.readAllBytes(fichier);
            DomSûr.refuserDoctype(octets);
            document = DomSûr.nouveauParseur(true)
                    .parse(new java.io.ByteArrayInputStream(octets));
        } catch (Exception échec) {
            throw new IOException("layout illisible : " + fichier + " ("
                    + échec.getMessage() + ")", échec);
        }
        Element racine = document.getDocumentElement();
        boolean ignoré = "true".equals(
                racine.getAttributeNS(TOOLS_NS, "viewBindingIgnore"));
        String baliseRacine = racine.getTagName();
        List<VueBrute> vues = new ArrayList<>();
        parcourir(racine, vues);
        return new LayoutAnalyse(ignoré, "merge".equals(baliseRacine),
                baliseRacine, racine.getAttribute("class"), vues);
    }

    /** Parcourt les éléments en profondeur, collectant les vues identifiées. */
    private static void parcourir(Element racine, List<VueBrute> vues) {
        List<Element> pile = new ArrayList<>();
        pile.add(racine);
        while (!pile.isEmpty()) {
            Element élément = pile.remove(pile.size() - 1);
            if (!"true".equals(
                    élément.getAttributeNS(TOOLS_NS, "viewBindingIgnore"))) {
                String id = idDe(élément);
                if (id != null) {
                    String layoutInclus = "include".equals(élément.getTagName())
                            && élément.getAttribute("layout")
                                    .startsWith("@layout/")
                            ? élément.getAttribute("layout")
                                    .substring("@layout/".length())
                            : null;
                    vues.add(new VueBrute(id, élément.getTagName(),
                            élément.getAttribute("class"), layoutInclus));
                }
            }
            for (Node nœud = élément.getFirstChild(); nœud != null;
                 nœud = nœud.getNextSibling()) {
                if (nœud instanceof Element enfant) {
                    pile.add(enfant);
                }
            }
        }
    }

    /**
     * L'id LOCAL d'une vue ({@code @+id/foo} → {@code foo}) ; les ids du
     * framework ({@code @android:id/…}) ne portent pas de champ.
     */
    private static String idDe(Element élément) {
        String brut = élément.getAttributeNS(ANDROID_NS, "id");
        if (brut.isBlank()) {
            return null;
        }
        String corps = brut.startsWith("@+") ? brut.substring(2)
                : brut.startsWith("@") ? brut.substring(1) : brut;
        if (corps.startsWith("android:")) {
            return null;
        }
        int barre = corps.lastIndexOf('/');
        String nom = barre < 0 ? corps : corps.substring(barre + 1);
        return !nom.isEmpty()
                && nom.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '_')
                ? nom : null;
    }

    /** Le nom de fichier sans extension. */
    private static String nomSansExtension(Path fichier) {
        String nom = fichier.getFileName().toString();
        return nom.endsWith(".xml") ? nom.substring(0, nom.length() - 4) : nom;
    }
}
