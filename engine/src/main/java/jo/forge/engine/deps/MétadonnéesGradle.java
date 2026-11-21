package jo.forge.engine.deps;

import jo.forge.json.Json;
import jo.forge.json.JsonException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Métadonnées de module Gradle (le {@code *.module} JSON publié à côté du
 * {@code .pom} par les bibliothèques construites par Gradle) : le sous-ensemble
 * dont la résolution a besoin pour SÉLECTIONNER UNE VARIANTE.
 *
 * <p><b>Pourquoi</b> : une bibliothèque multiplate-forme (KMP) publie ses
 * variantes — {@code -android}, {@code -jvm} — sous des coordonnées DIFFÉRENTES
 * mais son POM pont les énumère toutes avec des versions placeholder, et les
 * bibliothèques androidx (compose compris) ne sont pas consommables par un
 * résolveur POM seul. Le {@code .module} dit quelle variante correspond à quelle
 * consommation (attributs Gradle) : sélectionner la variante ici, c'est ne
 * télécharger QUE le bon artefact ({@code ui-android} et jamais {@code ui-jvm}),
 * et suivre SES dépendances (api et runtime), pas celles du pont.</p>
 *
 * <p><b>Dégradation honnête</b> : un {@code .module} absent ou malformé ramène
 * la résolution au POM (chemin historique). Ce module ne modélise NI les
 * conflits de capacités entre modules ({@code collection} vs
 * {@code collection-ktx}) — reste une préoccupation du dexage, consignée — NI
 * l'algorithme complet de négociation Gradle : les filtres durs et le score
 * ci-dessous suffisent à préférer {@code -android} à {@code -jvm} à
 * {@code common} pour une cible Android, l'inverse pour une cible JVM.</p>
 */
public final class MétadonnéesGradle {

    /** Clés d'attributs Gradle qui portent la sélection. */
    private static final String CATÉGORIE = "org.gradle.category";
    private static final String USAGE = "org.gradle.usage";
    private static final String ÉLÉMENTS_BIBLIOTHÈQUE = "org.gradle.libraryelements";
    private static final String TYPE_PLATE_FORME = "org.jetbrains.kotlin.platform.type";
    private static final String ENVIRONNEMENT_JVM = "org.gradle.jvm.environment";

    /** Usages JVM consommables (une bibliothèque Kotlin pure ne publie que kotlin-*). */
    private static final Set<String> USAGES_JVM = Set.of(
            "java-api", "java-runtime", "kotlin-api", "kotlin-runtime");

    private MétadonnéesGradle() {
    }

    /** Un module Gradle : ses variantes publiées. */
    public record Module(String groupe, String nom, String version,
                         List<Variante> variantes) {
    }

    /**
     * Une variante publiée : attributs de négociation, dépendances,
     * contraintes d'alignement, fichiers, et la redirection
     * {@code available-at} vers le module qui porte réellement les fichiers
     * (le cas des racines KMP : {@code ui} redirige vers {@code ui-android}).
     */
    public record Variante(String nom, Map<String, String> attributs,
                           List<Dépendance> dépendances,
                           List<Dépendance> contraintes,
                           List<String> fichiers,
                           Disponibilité disponibleÀ) {
    }

    /**
     * Une dépendance (ou contrainte) de variante : la version résoluble est
     * {@code requires}, sinon {@code strictly}, sinon {@code prefers} — la
     * plupart des éditeurs ne mettent que {@code requires}.
     */
    public record Dépendance(String groupe, String nom, String version,
                             Map<String, String> attributs, Set<String> exclusions) {
    }

    /** Redirection {@code available-at} vers le module porteur des fichiers. */
    public record Disponibilité(String groupe, String nom, String version) {
    }

    /**
     * Analyse un document {@code .module}.
     *
     * @param octets le contenu binaire du fichier.
     * @return le module, ou vide si le document est absent de forme ou malformé
     *         (la résolution retombe alors sur le POM).
     */
    public static Optional<Module> analyser(byte[] octets) {
        try {
            Object racine = Json.parse(new String(octets,
                    java.nio.charset.StandardCharsets.UTF_8));
            if (!(racine instanceof Map<?, ?> carte)) {
                return Optional.empty();
            }
            Object composant = carte.get("component");
            List<Variante> variantes = new ArrayList<>();
            if (carte.get("variants") instanceof List<?> liste) {
                for (Object élément : liste) {
                    variante(élément).ifPresent(variantes::add);
                }
            }
            if (variantes.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new Module(
                    composant instanceof Map<?, ?> c && c.get("group") instanceof String g
                            ? g : null,
                    composant instanceof Map<?, ?> c
                            && c.get("module") instanceof String n ? n : null,
                    composant instanceof Map<?, ?> c
                            && c.get("version") instanceof String v ? v : null,
                    List.copyOf(variantes)));
        } catch (JsonException | ClassCastException | NullPointerException malformé) {
            return Optional.empty();
        }
    }

    /**
     * Sélectionne la variante de compilation (usage {@code *-api} de préférence,
     * repli sur l'ensemble des candidats quand la bibliothèque ne publie que du
     * runtime) pour une cible Android ({@code androidJvm}) ou JVM ({@code jvm}).
     *
     * @param module  le module Gradle.
     * @param android cible Android (application / bibliothèque Android).
     * @return la variante retenue, ou vide si aucune ne convient (repli POM).
     */
    public static Optional<Variante> sélectionner(Module module, boolean android) {
        return sélectionner(module, android, false);
    }

    /**
     * Sélectionne la variante d'EXÉCUTION ({@code *-runtime}) : elle porte les
     * dépendances runtime-seulement (l'équivalent GMM de la portée Maven
     * {@code runtime}) que la fermeture d'exécution doit suivre. Vide quand le
     * module n'en publie pas (la variante api portait déjà tout).
     */
    public static Optional<Variante> varianteRuntime(Module module, boolean android) {
        return sélectionner(module, android, true);
    }

    private static Optional<Variante> sélectionner(Module module, boolean android,
                                                   boolean runtime) {
        List<Variante> candidates = new ArrayList<>();
        for (Variante variante : module.variantes()) {
            if (!passeFiltresDurs(variante)) {
                continue;
            }
            String usage = variante.attributs().get(USAGE);
            boolean estApi = usage != null && usage.endsWith("-api");
            boolean estRuntime = usage != null && usage.endsWith("-runtime");
            if (runtime ? estRuntime : (estApi || estRuntime)) {
                candidates.add(variante);
            }
        }
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        // Préférence api pour la sélection de compilation (repli : runtime seul).
        List<Variante> bassin = candidates;
        if (!runtime) {
            List<Variante> api = candidates.stream()
                    .filter(v -> v.attributs().get(USAGE) != null
                            && v.attributs().get(USAGE).endsWith("-api")).toList();
            if (!api.isEmpty()) {
                bassin = api;
            }
        }
        Variante meilleure = null;
        int meilleurScore = Integer.MIN_VALUE;
        for (Variante variante : bassin) {
            int score = score(variante, android);
            if (score > meilleurScore
                    || (score == meilleurScore && meilleure != null
                        && variante.nom().compareTo(meilleure.nom()) > 0)) {
                meilleure = variante;
                meilleurScore = score;
            }
        }
        return Optional.ofNullable(meilleure);
    }

    /**
     * Rejette les variantes qu'aucune consommation JVM/Android ne saurait
     * satisfaire : documentation (sources/métadonnées), klib natif, js, wasm,
     * éléments de classes secondaires.
     */
    private static boolean passeFiltresDurs(Variante variante) {
        Map<String, String> attributs = variante.attributs();
        String catégorie = attributs.get(CATÉGORIE);
        if (catégorie != null && !"library".equals(catégorie)) {
            return false;
        }
        String usage = attributs.get(USAGE);
        if (usage != null && !USAGES_JVM.contains(usage)) {
            return false;
        }
        String éléments = attributs.get(ÉLÉMENTS_BIBLIOTHÈQUE);
        if (éléments != null && !"jar".equals(éléments) && !"aar".equals(éléments)) {
            return false;
        }
        String plateForme = attributs.get(TYPE_PLATE_FORME);
        if (plateForme != null && !"androidJvm".equals(plateForme)
                && !"jvm".equals(plateForme) && !"common".equals(plateForme)) {
            return false;
        }
        return true;
    }

    /**
     * Le score : {@code platform.type} d'abord (la règle qui porte la décision —
     * la plateforme demandée gagne, une bibliothèque non-KMP sans attribut de
     * plateforme est neutre, jamais pénalisée), puis l'environnement JVM, puis
     * l'usage (api > runtime, java > kotlin). Départage final par le nom.
     */
    private static int score(Variante variante, boolean android) {
        int score = 0;
        String plateForme = variante.attributs().get(TYPE_PLATE_FORME);
        String demandée = android ? "androidJvm" : "jvm";
        if (demandée.equals(plateForme)) {
            score += 100;
        } else if (plateForme == null) {
            score += 20;
        } else if ("jvm".equals(plateForme)) {
            score += 10;
        } else if ("common".equals(plateForme)) {
            score += 5;
        }
        String environnement = variante.attributs().get(ENVIRONNEMENT_JVM);
        String demandé = android ? "android" : "standard-jvm";
        if (demandé.equals(environnement)) {
            score += 50;
        } else if ("standard-jvm".equals(environnement)) {
            score += 1;
        }
        score += switch (variante.attributs().getOrDefault(USAGE, "")) {
            case "java-api" -> 4;
            case "java-runtime" -> 2;
            case "kotlin-api" -> 1;
            default -> 0;
        };
        return score;
    }

    /** Analyse une variante du JSON. */
    private static Optional<Variante> variante(Object élément) {
        if (!(élément instanceof Map<?, ?> carte) || !(carte.get("name") instanceof String nom)) {
            return Optional.empty();
        }
        Map<String, String> attributs = attributs(carte.get("attributes"));
        List<Dépendance> dépendances = dépendances(carte.get("dependencies"));
        List<Dépendance> contraintes = dépendances(carte.get("dependencyConstraints"));
        List<String> fichiers = new ArrayList<>();
        if (carte.get("files") instanceof List<?> liste) {
            for (Object fichier : liste) {
                if (fichier instanceof Map<?, ?> f && f.get("name") instanceof String n) {
                    fichiers.add(n);
                }
            }
        }
        Disponibilité disponibleÀ = null;
        if (carte.get("available-at") instanceof Map<?, ?> à
                && à.get("group") instanceof String g
                && à.get("module") instanceof String m
                && à.get("version") instanceof String v) {
            disponibleÀ = new Disponibilité(g, m, v);
        }
        return Optional.of(new Variante(nom, attributs, dépendances, contraintes,
                List.copyOf(fichiers), disponibleÀ));
    }

    /** Analyse une liste de dépendances/contraintes (version : requires > strictly > prefers). */
    private static List<Dépendance> dépendances(Object valeur) {
        List<Dépendance> résultat = new ArrayList<>();
        if (!(valeur instanceof List<?> liste)) {
            return résultat;
        }
        for (Object élément : liste) {
            if (!(élément instanceof Map<?, ?> carte)
                    || !(carte.get("group") instanceof String groupe)
                    || !(carte.get("module") instanceof String nom)) {
                continue;
            }
            String version = null;
            if (carte.get("version") instanceof Map<?, ?> v) {
                if (v.get("requires") instanceof String r) {
                    version = r;
                } else if (v.get("strictly") instanceof String s) {
                    version = s;
                } else if (v.get("prefers") instanceof String p) {
                    version = p;
                }
            }
            Set<String> exclusions = new LinkedHashSet<>();
            if (carte.get("excludes") instanceof List<?> exclus) {
                for (Object exclusion : exclus) {
                    if (exclusion instanceof Map<?, ?> e && e.get("group") instanceof String g
                            && e.get("module") instanceof String m) {
                        exclusions.add(g + ":" + m);
                    }
                }
            }
            résultat.add(new Dépendance(groupe, nom, version,
                    attributs(carte.get("attributes")), Set.copyOf(exclusions)));
        }
        return résultat;
    }

    /** Coerce les valeurs d'attributs (chaîne, nombre, booléen) en chaînes. */
    private static Map<String, String> attributs(Object valeur) {
        Map<String, String> résultat = new LinkedHashMap<>();
        if (!(valeur instanceof Map<?, ?> carte)) {
            return résultat;
        }
        for (Map.Entry<?, ?> entrée : carte.entrySet()) {
            if (entrée.getKey() instanceof String clef && entrée.getValue() != null) {
                Object val = entrée.getValue();
                résultat.put(clef, val instanceof String s ? s : String.valueOf(val));
            }
        }
        return résultat;
    }
}
