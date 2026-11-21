package jo.forge.engine.model;

import jo.forge.json.Json;
import jo.forge.json.JsonException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Lecture et écriture du modèle déclaratif {@code forge.json} (décision D-11).
 *
 * <p>L'analyse est stricte et les messages d'erreur sont actionnables : chaque rejet
 * cite le champ fautif sous forme de chemin JSON ({@code modules[1].dependencies[0]}),
 * la valeur trouvée et ce qui était attendu. Aucune valeur inconnue n'est ignorée
 * silencieusement — un modèle qui passe le codec est complet.</p>
 *
 * <p>Contraintes vérifiées : version du format exacte, noms de modules uniques et non
 * vides, dépendances de projet résolubles (pas d'inconnu, pas d'auto-dépendance),
 * coordonnées Maven bien formées, chemins relatifs sans remontée ({@code ..} refusé),
 * facette Android présente si et seulement si le type l'exige.</p>
 */
public final class ModelCodec {

    /** Nom du fichier de modèle à la racine d'un projet. */
    public static final String MODEL_FILE = "forge.json";

    private ModelCodec() {
    }

    /**
     * Lit et valide le modèle d'un projet.
     *
     * @param projectRoot racine du projet (répertoire contenant {@code forge.json}).
     * @return le modèle validé.
     * @throws ModelException si le fichier manque, est illisible ou invalide.
     */
    public static ForgeModel read(Path projectRoot) {
        Path file = projectRoot.resolve(MODEL_FILE);
        if (!Files.isRegularFile(file)) {
            throw new ModelException("Aucun " + MODEL_FILE + " dans " + projectRoot
                    + " : répertoire non projet ?");
        }
        String text;
        try {
            text = Files.readString(file);
        } catch (IOException e) {
            throw new ModelException("Lecture impossible de " + file + " : " + e.getMessage(), e);
        }
        return parse(text);
    }

    /**
     * Analyse et valide un contenu {@code forge.json}.
     *
     * @param json le texte du modèle.
     * @return le modèle validé.
     * @throws ModelException si le contenu est invalide (champ fautif cité).
     */
    public static ForgeModel parse(String json) {
        Object racine;
        try {
            racine = Json.parse(json);
        } catch (JsonException e) {
            throw new ModelException("forge.json malformé : " + e.getMessage(), e);
        }
        Map<String, Object> objet = asObject(racine, "$");
        int version = asInt(objet.get("modelVersion"), "$.modelVersion");
        // Précédence : la version se vérifie avant tout le reste (un modèle d'une
        // version future peut ne pas être compris — on ne dit rien de son contenu).
        if (version != ForgeModel.CURRENT_MODEL_VERSION) {
            throw new ModelException("Version de modèle non supportée : " + version
                    + " (attendue : " + ForgeModel.CURRENT_MODEL_VERSION
                    + ") — mettez à jour le moteur");
        }
        List<String> repositories = asStringList(objet.get("repositories"),
                "$.repositories", List.of());
        List<Map<String, Object>> modulesBruts = asObjectList(objet.get("modules"),
                "$.modules", null);
        if (modulesBruts == null) {
            throw new ModelException("Champ $.modules manquant (liste des modules)");
        }
        if (modulesBruts.isEmpty()) {
            throw new ModelException("$.modules est vide : un projet déclare au moins un module");
        }

        Set<String> noms = new HashSet<>();
        List<ForgeModule> modules = new ArrayList<>();
        for (int i = 0; i < modulesBruts.size(); i++) {
            ForgeModule module = readModule(modulesBruts.get(i), "$.modules[" + i + "]");
            if (!noms.add(module.name())) {
                throw new ModelException("Nom de module dupliqué : " + module.name());
            }
            modules.add(module);
        }
        checkProjectDependencies(modules);
        return new ForgeModel(version, repositories, modules);
    }

    /**
     * Écrit le modèle sous forme de texte {@code forge.json} (round-trip stable,
     * utilisé par l'import de scripts Gradle et les tests).
     *
     * @param model le modèle à sérialiser.
     * @return le texte JSON.
     */
    public static String write(ForgeModel model) {
        Map<String, Object> racine = new java.util.LinkedHashMap<>();
        racine.put("modelVersion", model.modelVersion());
        racine.put("repositories", model.repositories());
        List<Object> modules = new ArrayList<>();
        for (ForgeModule module : model.modules()) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("name", module.name());
            m.put("type", module.type().wireName());
            if (!ForgeModule.LANGUAGE_JAVA.equals(module.language())) {
                m.put("language", module.language());
            }
            if (module.javaRelease() != ForgeModule.DEFAULT_JAVA_RELEASE) {
                m.put("javaRelease", module.javaRelease());
            }
            if (!module.sources().isEmpty()) {
                m.put("sources", module.sources());
            }
            if (!module.resources().isEmpty()) {
                m.put("resources", module.resources());
            }
            if (!module.dependencies().isEmpty()) {
                List<Object> deps = new ArrayList<>();
                for (ModuleDependency dep : module.dependencies()) {
                    Map<String, Object> d = new java.util.LinkedHashMap<>();
                    if (dep instanceof ModuleDependency.ProjectDependency projet) {
                        d.put("project", projet.module());
                    } else if (dep instanceof ModuleDependency.MavenDependency maven) {
                        d.put("maven", maven.coordinate());
                    }
                    if (dep.scope() != DependencyScope.IMPLEMENTATION) {
                        d.put("scope", dep.scope().wireName());
                    }
                    deps.add(d);
                }
                m.put("dependencies", deps);
            }
            module.android().ifPresent(facet -> {
                Map<String, Object> a = new java.util.LinkedHashMap<>();
                a.put("manifest", facet.manifest());
                if (!facet.resourceDirs().isEmpty()) {
                    a.put("res", facet.resourceDirs());
                }
                if (!facet.assetDirs().isEmpty()) {
                    a.put("assets", facet.assetDirs());
                }
                a.put("minSdk", facet.minSdk());
                a.put("targetSdk", facet.targetSdk());
                a.put("namespace", facet.namespace());
                if (facet.applicationId() != null) {
                    a.put("applicationId", facet.applicationId());
                }
                if (facet.versionCode() != AndroidFacet.VERSION_CODE_DÉFAUT) {
                    a.put("versionCode", facet.versionCode());
                }
                if (!AndroidFacet.VERSION_NOM_DÉFAUT.equals(facet.versionName())) {
                    a.put("versionName", facet.versionName());
                }
                if (facet.buildFeatures().buildConfig()
                        || facet.buildFeatures().viewBinding()) {
                    Map<String, Object> f = new java.util.LinkedHashMap<>();
                    if (facet.buildFeatures().buildConfig()) {
                        f.put("buildConfig", true);
                    }
                    if (facet.buildFeatures().viewBinding()) {
                        f.put("viewBinding", true);
                    }
                    a.put("buildFeatures", f);
                }
                if (!facet.buildConfigFields().isEmpty()) {
                    List<Object> champs = new ArrayList<>();
                    for (AndroidFacet.BuildConfigField champ
                            : facet.buildConfigFields()) {
                        Map<String, Object> ch = new java.util.LinkedHashMap<>();
                        ch.put("type", champ.type());
                        ch.put("name", champ.name());
                        ch.put("value", champ.value());
                        champs.add(ch);
                    }
                    a.put("buildConfigFields", champs);
                }
                if (!facet.manifestPlaceholders().isEmpty()) {
                    a.put("manifestPlaceholders", facet.manifestPlaceholders());
                }
                m.put("android", a);
            });
            modules.add(m);
        }
        racine.put("modules", modules);
        return Json.write(racine);
    }

    /**
     * Lit un module depuis sa représentation JSON.
     *
     * <p>Conventions quand les champs {@code sources}/{@code resources} sont absents :
     * {@code src/main/java} et {@code src/main/resources} pour un module Java ;
     * {@code src/main/kotlin} PUIS {@code src/main/java} pour un module Kotlin
     * (les deux langages cohabitent — le Kotlin compile d'abord, le Java contre ses
     * classes). Une liste explicite, même vide, désactive la convention.</p>
     */
    private static ForgeModule readModule(Map<String, Object> brut, String chemin) {
        String nom = asString(brut.get("name"), chemin + ".name");
        ModuleType type = ModuleType.fromWire(
                asString(brut.get("type"), chemin + ".type"));
        String language = asString(brut.get("language"), chemin + ".language",
                ForgeModule.LANGUAGE_JAVA);
        int javaRelease = asInt(brut.get("javaRelease"), chemin + ".javaRelease",
                ForgeModule.DEFAULT_JAVA_RELEASE);
        // Convention des sources : Java → src/main/java ; Kotlin → src/main/kotlin
        // PUIS src/main/java (les deux langages cohabitent dans un module Kotlin —
        // le Java compile après le Kotlin, contre ses classes). Une liste
        // explicite, même vide, désactive la convention.
        List<String> sources = brut.containsKey("sources")
                ? asStringList(brut.get("sources"), chemin + ".sources", List.of())
                : ForgeModule.LANGUAGE_KOTLIN.equals(language)
                ? List.of("src/main/kotlin", "src/main/java")
                : List.of("src/main/java");
        List<String> resources = brut.containsKey("resources")
                ? asStringList(brut.get("resources"), chemin + ".resources", List.of())
                : List.of("src/main/resources");
        List<ModuleDependency> dependencies = readDependencies(
                asObjectList(brut.get("dependencies"), chemin + ".dependencies", List.of()),
                chemin + ".dependencies");
        Optional<AndroidFacet> android = brut.containsKey("android")
                ? Optional.of(readAndroidFacet(
                        asObject(brut.get("android"), chemin + ".android"), chemin + ".android"))
                : Optional.empty();
        checkRelativePaths(nom, sources, "sources");
        checkRelativePaths(nom, resources, "resources");
        return new ForgeModule(nom, type, javaRelease, sources, resources, dependencies,
                android, language);
    }

    /**
     * Lit les dépendances d'un module.
     */
    private static List<ModuleDependency> readDependencies(List<Map<String, Object>> brutes,
                                                           String chemin) {
        List<ModuleDependency> deps = new ArrayList<>();
        for (int i = 0; i < brutes.size(); i++) {
            String c = chemin + "[" + i + "]";
            Map<String, Object> d = brutes.get(i);
            DependencyScope scope = d.containsKey("scope")
                    ? DependencyScope.fromWire(asString(d.get("scope"), c + ".scope"))
                    : DependencyScope.IMPLEMENTATION;
            if (d.containsKey("project") && d.containsKey("maven")) {
                throw new ModelException(c + " : une dépendance est soit project, soit maven");
            }
            if (d.containsKey("project")) {
                deps.add(new ModuleDependency.ProjectDependency(
                        asString(d.get("project"), c + ".project"), scope));
            } else if (d.containsKey("maven")) {
                deps.add(parseCoordinate(asString(d.get("maven"), c + ".maven"), scope));
            } else {
                throw new ModelException(c + " : champ project ou maven attendu");
            }
        }
        return deps;
    }

    /**
     * Analyse une coordonnée Maven de la forme {@code groupe:artefact:version}.
     */
    private static ModuleDependency.MavenDependency parseCoordinate(String coord,
                                                                    DependencyScope scope) {
        String[] parties = coord.split(":");
        if (parties.length != 3 || parties[0].isBlank() || parties[1].isBlank()
                || parties[2].isBlank()) {
            throw new ModelException("Coordonnée Maven mal formée : \"" + coord
                    + "\" (attendu groupe:artefact:version)");
        }
        return new ModuleDependency.MavenDependency(parties[0], parties[1], parties[2], scope);
    }

    /**
     * Lit la facette Android d'un module : les champs de génération (v0.2.18 —
     * {@code buildFeatures}, {@code buildConfigFields}, versions,
     * {@code manifestPlaceholders}) sont OPTIONNELS et désactivés par défaut
     * (position d'AGP 8 — un projet qui n'a rien demandé n'obtient pas de
     * classes surprises).
     */
    private static AndroidFacet readAndroidFacet(Map<String, Object> brut, String chemin) {
        List<String> res = asStringList(brut.get("res"), chemin + ".res", List.of());
        List<String> assets = asStringList(brut.get("assets"), chemin + ".assets", List.of());
        Map<String, Object> fonctionnalités = asObject(
                brut.getOrDefault("buildFeatures", java.util.Map.of()),
                chemin + ".buildFeatures");
        List<AndroidFacet.BuildConfigField> champs = new ArrayList<>();
        List<Map<String, Object>> champsBruts = asObjectList(
                brut.get("buildConfigFields"), chemin + ".buildConfigFields",
                List.of());
        for (int i = 0; i < champsBruts.size(); i++) {
            String c = chemin + ".buildConfigFields[" + i + "]";
            Map<String, Object> champ = champsBruts.get(i);
            champs.add(new AndroidFacet.BuildConfigField(
                    asString(champ.get("type"), c + ".type"),
                    asString(champ.get("name"), c + ".name"),
                    asString(champ.get("value"), c + ".value")));
        }
        Map<String, String> marqueurs = new java.util.LinkedHashMap<>();
        Map<String, Object> marqueursBruts = asObject(
                brut.getOrDefault("manifestPlaceholders", java.util.Map.of()),
                chemin + ".manifestPlaceholders");
        for (Map.Entry<String, Object> entrée : marqueursBruts.entrySet()) {
            if (!(entrée.getValue() instanceof String valeur) || valeur.isBlank()) {
                throw new ModelException(chemin + ".manifestPlaceholders."
                        + entrée.getKey() + " : chaîne non vide attendue");
            }
            marqueurs.put(entrée.getKey(), valeur);
        }
        AndroidFacet facet = new AndroidFacet(
                asString(brut.get("manifest"), chemin + ".manifest"),
                res,
                assets,
                asInt(brut.get("minSdk"), chemin + ".minSdk"),
                asInt(brut.get("targetSdk"), chemin + ".targetSdk"),
                brut.containsKey("applicationId")
                        ? asString(brut.get("applicationId"), chemin + ".applicationId")
                        : null,
                asString(brut.get("namespace"), chemin + ".namespace"),
                asInt(brut.get("versionCode"), chemin + ".versionCode",
                        AndroidFacet.VERSION_CODE_DÉFAUT),
                asString(brut.get("versionName"), chemin + ".versionName",
                        AndroidFacet.VERSION_NOM_DÉFAUT),
                new AndroidFacet.BuildFeatures(
                        asBoolean(fonctionnalités.get("buildConfig"),
                                chemin + ".buildFeatures.buildConfig", false),
                        asBoolean(fonctionnalités.get("viewBinding"),
                                chemin + ".buildFeatures.viewBinding", false)),
                champs,
                marqueurs);
        checkRelativePaths(chemin, res, "res");
        checkRelativePaths(chemin, assets, "assets");
        return facet;
    }

    /**
     * Vérifie que les dépendances de projet pointent vers des modules connus.
     */
    private static void checkProjectDependencies(List<ForgeModule> modules) {
        Set<String> noms = new HashSet<>();
        modules.forEach(m -> noms.add(m.name()));
        for (ForgeModule module : modules) {
            for (ModuleDependency dep : module.dependencies()) {
                if (dep instanceof ModuleDependency.ProjectDependency projet) {
                    if (projet.module().equals(module.name())) {
                        throw new ModelException("Le module " + module.name()
                                + " dépend de lui-même");
                    }
                    if (!noms.contains(projet.module())) {
                        throw new ModelException("Le module " + module.name()
                                + " dépend d'un module inconnu : " + projet.module());
                    }
                }
            }
        }
    }

    /**
     * Refuse les chemins absolus et les remontées de répertoire.
     */
    private static void checkRelativePaths(String owner, List<String> paths, String champ) {
        for (String chemin : paths) {
            Path p = Path.of(chemin);
            if (p.isAbsolute() || chemin.contains("..")) {
                throw new ModelException(owner + " : chemin " + champ
                        + " interdit (doit être relatif au module, sans remontée) : " + chemin);
            }
        }
    }

    /** Convertit en objet JSON avec message d'erreur contextualisé. */
    private static Map<String, Object> asObject(Object valeur, String chemin) {
        if (valeur instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> resultat = (Map<String, Object>) map;
            return resultat;
        }
        throw new ModelException(chemin + " : objet attendu, trouvé "
                + descriptionType(valeur));
    }

    /** Convertit en liste d'objets JSON (valeur par défaut si absent). */
    private static List<Map<String, Object>> asObjectList(Object valeur, String chemin,
                                                          List<Map<String, Object>> defaut) {
        if (valeur == null) {
            if (defaut == null) {
                return null;
            }
            return defaut;
        }
        if (!(valeur instanceof List<?> liste)) {
            throw new ModelException(chemin + " : liste attendue, trouvé "
                    + descriptionType(valeur));
        }
        List<Map<String, Object>> resultat = new ArrayList<>();
        for (int i = 0; i < liste.size(); i++) {
            resultat.add(asObject(liste.get(i), chemin + "[" + i + "]"));
        }
        return resultat;
    }

    /** Convertit en chaîne non vide. */
    private static String asString(Object valeur, String chemin, String defaut) {
        return valeur == null ? defaut : asString(valeur, chemin);
    }

    private static String asString(Object valeur, String chemin) {
        if (valeur instanceof String s && !s.isBlank()) {
            return s;
        }
        throw new ModelException(chemin + " : chaîne non vide attendue, trouvé "
                + descriptionType(valeur));
    }

    /** Convertit en entier (valeur par défaut si absent). */
    private static int asInt(Object valeur, String chemin, int defaut) {
        if (valeur == null) {
            return defaut;
        }
        return asInt(valeur, chemin);
    }

    /** Convertit en entier obligatoire. */
    private static int asInt(Object valeur, String chemin) {
        if (valeur instanceof Long l) {
            if (l < Integer.MIN_VALUE || l > Integer.MAX_VALUE) {
                throw new ModelException(chemin + " : entier hors bornes : " + l);
            }
            return l.intValue();
        }
        if (valeur instanceof Double d && d == Math.floor(d) && !d.isInfinite()) {
            return (int) (double) d;
        }
        throw new ModelException(chemin + " : entier attendu, trouvé "
                + descriptionType(valeur));
    }

    /** Convertit en liste de chaînes (valeur par défaut si absent). */
    private static List<String> asStringList(Object valeur, String chemin,
                                             List<String> defaut) {
        if (valeur == null) {
            return defaut;
        }
        if (!(valeur instanceof List<?> liste)) {
            throw new ModelException(chemin + " : liste de chaînes attendue, trouvé "
                    + descriptionType(valeur));
        }
        List<String> resultat = new ArrayList<>();
        for (int i = 0; i < liste.size(); i++) {
            if (!(liste.get(i) instanceof String s) || s.isBlank()) {
                throw new ModelException(chemin + "[" + i
                        + "] : chaîne non vide attendue");
            }
            resultat.add(s);
        }
        return resultat;
    }

    /** Convertit en booléen (valeur par défaut si absent). */
    private static boolean asBoolean(Object valeur, String chemin, boolean defaut) {
        if (valeur == null) {
            return defaut;
        }
        if (valeur instanceof Boolean b) {
            return b;
        }
        throw new ModelException(chemin + " : booléen attendu, trouvé "
                + descriptionType(valeur));
    }

    /** Description du type JSON trouvé, pour les messages d'erreur. */
    private static String descriptionType(Object valeur) {
        if (valeur == null) {
            return "absent";
        }
        if (valeur instanceof String) {
            return "la chaîne \"" + valeur + "\"";
        }
        if (valeur instanceof Long || valeur instanceof Double) {
            return "le nombre " + valeur;
        }
        if (valeur instanceof Boolean) {
            return "le booléen " + valeur;
        }
        if (valeur instanceof List) {
            return "une liste";
        }
        return "un objet";
    }
}
