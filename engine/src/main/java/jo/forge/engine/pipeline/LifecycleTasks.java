package jo.forge.engine.pipeline;

import jo.forge.api.TaskDescription;
import jo.forge.engine.graph.GraphException;
import jo.forge.engine.tasks.Task;
import jo.forge.engine.tasks.TaskContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Tâches de cycle de vie et répertoire des tâches (protocole 5-6, décisions
 * D-22/D-23) : le moteur est la source de vérité de CE QUI PEUT TOURNER.
 *
 * <p>Les tâches d'agrégation vivent HORS du graphe concret par défaut : un build
 * complet ne doit pas nettoyer ce qu'il construit — elles s'injectent à la
 * demande, quand la requête ({@code BuildOptions.tasks}) les nomme.</p>
 *
 * <p>Le répertoire ({@link #descripteurs}) expose les tâches concrètes — groupe et
 * description dérivés du vocabulaire du pipeline — puis le cycle de vie, marqué
 * {@code lifecycle} : la coquille affiche, elle ne devine plus. Depuis le
 * protocole 6 (D-23), le répertoire se demande aussi PAR SOUS-ENSEMBLE de
 * modules ({@code gradlew :core:tasks}) : la vue expose alors les agrégats
 * propres à chaque module ({@code core:assemble}, {@code core:clean}) — le NOM
 * est le périmètre, jamais d'agrégat global redéfini en silence.</p>
 *
 * <p>Honnêteté des alias (D-23) : {@code build} agrège les terminales ET les
 * tâches de vérification qui EXISTENT — aujourd'hui aucune, il vaut exactement
 * {@code assemble} et ses dépendances le montrent ; {@code check}/{@code lint}/
 * {@code test} n'apparaissent dans le répertoire QUE SI des tâches de
 * vérification ({@code :lint}, {@code :test}) existent dans le graphe. Le
 * vocabulaire des suffixes ({@link #SUFFIXES_VÉRIFICATION}) est LE point unique
 * : la tâche concrète {@code app:lint} ajoutée à un pipeline fera vivre
 * {@code lint} et {@code check} d'elle-même — aucun registre parallèle.</p>
 */
public final class LifecycleTasks {

    /** Nom de la tâche d'agrégation des sorties (l'équivalent Gradle). */
    public static final String ASSEMBLE = "assemble";

    /** Nom de la tâche de nettoyage des sorties (l'équivalent Gradle). */
    public static final String CLEAN = "clean";

    /** Nom de la tâche d'agrégation complète : sorties + vérifications
     *  existantes (protocole 6, D-23 — l'alias HONNÊTE : il vaut aujourd'hui
     *  {@code assemble} et ses dépendances le disent). */
    public static final String BUILD = "build";

    /** Nom de l'agrégat des vérifications (présent SI ET SEULEMENT SI des
     *  tâches de vérification existent). */
    public static final String CHECK = "check";

    /** Nom de l'agrégat des tâches de lint (présent SI ET SEULEMENT SI des
     *  tâches de lint existent). */
    public static final String LINT = "lint";

    /** Nom de l'agrégat des tâches de test (présent SI ET SEULEMENT SI des
     *  tâches de test existent). */
    public static final String TEST = "test";

    /** Suffixes des tâches concrètes de vérification : LE vocabulaire qui fait
     *  exister {@code check}/{@code lint}/{@code test} — ajouter une tâche
     *  {@code app:lint} au pipeline suffit, l'alias apparaît. */
    static final Set<String> SUFFIXES_VÉRIFICATION = Set.of("lint", "test");

    private LifecycleTasks() {
    }

    /**
     * Tâche {@code assemble} : agrégat des tâches terminales du graphe concret.
     *
     * <p>Ne fait rien elle-même (une tâche d'agrégation, comme Gradle) — son travail
     * EST sa liste de dépendances : demander {@code assemble} revient à demander
     * les sorties de chaque module, l'exécuteur fait le reste.</p>
     *
     * @param concrètes les tâches concrètes du projet (graphe non injecté).
     * @return la tâche {@code assemble}.
     */
    public static Task tâcheAssemble(List<Task> concrètes) {
        return new TâcheAgrégat(ASSEMBLE, tâchesTerminales(concrètes));
    }

    /**
     * Tâche {@code build} : l'alias HONNÊTE (protocole 6, D-23) — agrégat des
     * terminales ET des tâches de vérification qui EXISTENT dans le graphe.
     *
     * <p>Aujourd'hui, aucun pipeline ne produit de tâche de vérification : ses
     * dépendances sont exactement celles d'{@code assemble}, et c'est VISIBLE
     * (le répertoire liste les dépendances, la description le dit). Quand des
     * tâches {@code :lint}/{@code :test} existeront, {@code build} les agrègera
     * automatiquement — le modèle de Gradle, sans jamais mentir en avance.</p>
     *
     * @param concrètes les tâches concrètes du projet.
     * @return la tâche {@code build}.
     */
    public static Task tâcheBuild(List<Task> concrètes) {
        return new TâcheAgrégat(BUILD, cibleDeBuild(concrètes));
    }

    /**
     * Tâche {@code clean} : supprime le répertoire {@code build/} du projet.
     *
     * <p>Les sorties seules — les sources, {@code forge.json} et l'état
     * {@code .forge/} (empreintes) restent intacts : le build d'après recompile
     * depuis zéro mais garde son incrémental (le modèle du {@code clean} de
     * Gradle, qui ne touche jamais le cache).</p>
     *
     * @param racine racine du projet.
     * @return la tâche {@code clean}.
     */
    public static Task tâcheClean(Path racine) {
        return new TâcheClean(CLEAN, racine.resolve("build"));
    }

    /**
     * Tâche {@code module:assemble} : agrégat des terminales D'UN module —
     * l'équivalent de {@code gradlew :core:assemble}. Le nom EST le périmètre :
     * {@code core:assemble} ne construit jamais les sorties d'un autre module.
     *
     * @param concrètes les tâches concrètes du projet (graphe complet).
     * @param module    le module demandé (doit exister dans le graphe).
     * @return la tâche {@code module:assemble}.
     */
    static Task tâcheAssembleDeModule(List<Task> concrètes, String module) {
        return new TâcheAgrégat(module + ":" + ASSEMBLE,
                tâchesTerminales(tâchesDuModule(concrètes, module)));
    }

    /**
     * Tâche {@code module:clean} : supprime le répertoire {@code build/<module>/}
     * — les sorties du module seul, jamais les sources ni l'état {@code .forge/}.
     * Le nom qualifié ({@code core:clean}) est le périmètre.
     *
     * @param racine racine du projet.
     * @param module le module demandé.
     * @return la tâche {@code module:clean}.
     */
    static Task tâcheCleanDeModule(Path racine, String module) {
        return new TâcheClean(module + ":" + CLEAN,
                racine.resolve("build").resolve(module));
    }

    /**
     * La cible effective d'une requête : tâches concrètes augmentées des tâches
     * de cycle de vie demandées, puis réduites à la clôture des noms demandés
     * (la clôture des dépendances se calcule côté moteur — jamais côté coquille).
     *
     * <p>Demande vide : tout le graphe concret, la forme historique. Un nom
     * inconnu échoue proprement avec les candidats — le refus de Gradle
     * (« Task 'xxx' not found in root project », liste des disponibles). Les
     * agrégats de vérification ({@code check}/{@code lint}/{@code test}) ne
     * s'injectent QUE s'ils sont sous-tendus par des tâches concrètes — sinon
     * le nom reste inconnu : aucun alias prometteur. Les agrégats de module
     * ({@code core:assemble}, {@code core:clean}) s'injectent pour tout module
     * existant ; un module inconnu est refusé avec les modules disponibles.</p>
     *
     * @param racine    racine du projet (pour {@code clean}).
     * @param concrètes les tâches concrètes du projet.
     * @param demandées les noms demandés par la requête (éventuellement vide).
     * @return les tâches à exécuter (ordre indifférent : le graphe re-classe).
     * @throws GraphException si un nom ou un module demandé est inconnu.
     */
    public static List<Task> cible(Path racine, List<Task> concrètes,
                                   List<String> demandées) {
        if (demandées == null || demandées.isEmpty()) {
            return concrètes;
        }
        Set<String> nomsConcrets = new java.util.HashSet<>();
        for (Task tâche : concrètes) {
            nomsConcrets.add(tâche.name());
        }
        List<Task> augmentées = new ArrayList<>(concrètes);
        // Un nom de cycle de vie ne remplace JAMAIS une tâche concrète homonyme
        // (sécurité du jour où un pipeline définirait son propre :assemble).
        if (demandées.contains(CLEAN) && !nomsConcrets.contains(CLEAN)) {
            augmentées.add(tâcheClean(racine));
        }
        if (demandées.contains(ASSEMBLE) && !nomsConcrets.contains(ASSEMBLE)) {
            augmentées.add(tâcheAssemble(concrètes));
        }
        if (demandées.contains(BUILD) && !nomsConcrets.contains(BUILD)) {
            augmentées.add(tâcheBuild(concrètes));
        }
        injecterAliasVérification(augmentées, concrètes, demandées, CHECK,
                SUFFIXES_VÉRIFICATION);
        injecterAliasVérification(augmentées, concrètes, demandées, LINT,
                Set.of("lint"));
        injecterAliasVérification(augmentées, concrètes, demandées, TEST,
                Set.of("test"));
        for (String demandée : demandées) {
            String module = moduleDAgrégat(demandée, ASSEMBLE);
            if (module != null && !nomsConcrets.contains(demandée)) {
                vérifierModule(concrètes, module);
                augmentées.add(tâcheAssembleDeModule(concrètes, module));
            }
            module = moduleDAgrégat(demandée, CLEAN);
            if (module != null && !nomsConcrets.contains(demandée)) {
                vérifierModule(concrètes, module);
                augmentées.add(tâcheCleanDeModule(racine, module));
            }
        }
        return clôture(augmentées, demandées);
    }

    /**
     * Vérifie qu'un module d'agrégat demandé existe dans le graphe — sinon
     * refus honnête avec les modules disponibles.
     */
    private static void vérifierModule(List<Task> concrètes, String module) {
        boolean existe = concrètes.stream()
                .anyMatch(t -> module.equals(moduleDe(t.name())));
        if (!existe) {
            Set<String> existants = new LinkedHashSet<>();
            for (Task tâche : concrètes) {
                String présent = moduleDe(tâche.name());
                if (présent != null) {
                    existants.add(présent);
                }
            }
            throw new GraphException("Module demandé inconnu : " + module
                    + " — modules disponibles : "
                    + existants.stream().sorted().toList());
        }
    }

    /**
     * Injecte l'agrégat de vérification demandé, SI ET SEULEMENT SI des tâches
     * le sous-tendent : sinon le nom reste inconnu et le refus cite les
     * candidats — le répertoire ne promet rien.
     */
    private static void injecterAliasVérification(List<Task> augmentées,
                                                  List<Task> concrètes,
                                                  List<String> demandées,
                                                  String alias, Set<String> suffixes) {
        if (!demandées.contains(alias)) {
            return;
        }
        List<String> sousTendues = tâchesDeSuffixes(concrètes, suffixes);
        if (sousTendues.isEmpty()) {
            return; // inconnu : la clôture refusera avec les candidats.
        }
        augmentées.add(new TâcheAgrégat(alias, sousTendues));
    }

    /**
     * Clôture des dépendances des noms demandés dans la liste donnée.
     *
     * @param tâches    les tâches candidates (concrètes + cycle de vie injecté).
     * @param demandées les noms demandés.
     * @return les tâches demandées et toutes leurs dépendances transitives.
     * @throws GraphException si un nom est absent de la liste (candidats cités).
     */
    private static List<Task> clôture(List<Task> tâches, List<String> demandées) {
        var parNom = new java.util.LinkedHashMap<String, Task>();
        for (Task tâche : tâches) {
            parNom.put(tâche.name(), tâche);
        }
        for (String demandée : demandées) {
            if (!parNom.containsKey(demandée)) {
                throw new GraphException("Tâche demandée inconnue : " + demandée
                        + " — tâches disponibles : "
                        + parNom.keySet().stream().sorted().toList());
            }
        }
        Set<String> retenues = new LinkedHashSet<>();
        java.util.ArrayDeque<String> àVisiter = new java.util.ArrayDeque<>(demandées);
        while (!àVisiter.isEmpty()) {
            String nom = àVisiter.pop();
            if (!retenues.add(nom)) {
                continue;
            }
            àVisiter.addAll(parNom.get(nom).dependencies());
        }
        return tâches.stream().filter(t -> retenues.contains(t.name())).toList();
    }

    /**
     * Le répertoire des tâches d'un projet (vue complète), pour la trame
     * {@code TASKS} : les descripteurs des tâches concrètes (ordre déterministe :
     * groupe, puis nom) puis le cycle de vie — la coquille affiche ce répertoire
     * sans rien deviner.
     *
     * @param concrètes les tâches concrètes du projet.
     * @return les descripteurs, concrets d'abord puis cycle de vie.
     */
    public static List<TaskDescription> descripteurs(List<Task> concrètes) {
        return descripteurs(concrètes, List.of());
    }

    /**
     * Le répertoire des tâches, éventuellement PAR SOUS-ENSEMBLE de modules
     * (protocole 6, D-23 — l'équivalent de {@code gradlew :core:tasks}).
     *
     * <p>Vue complète (modules vides) : concrets + {@code assemble},
     * {@code build} (honnête : dépendances réelles, description dérivée),
     * les agrégats de vérification SOUS-TENDUS, puis {@code clean}. Vue
     * sous-ensemble : les concrets des modules demandés + les agrégats propres
     * de chaque module ({@code module:assemble}, {@code module:clean}) — le nom
     * est le périmètre, le cycle de vie global n'y apparaît pas redéfini.</p>
     *
     * @param concrètes les tâches concrètes du projet.
     * @param modules   modules demandés (vide ou nul : tout le projet).
     * @return les descripteurs, concrets d'abord puis cycle de vie.
     * @throws GraphException si un module demandé est inconnu (candidats cités).
     */
    public static List<TaskDescription> descripteurs(List<Task> concrètes,
                                                     List<String> modules) {
        List<Task> périmètre = sousEnsemble(concrètes, modules);
        List<TaskDescription> répertoire = périmètre.stream()
                .map(t -> new TaskDescription(t.name(), groupe(t.name()),
                        description(t.name()), t.dependencies(), false))
                .sorted(Comparator.comparing(TaskDescription::group)
                        .thenComparing(TaskDescription::name))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (modules == null || modules.isEmpty()) {
            répertoire.add(new TaskDescription(ASSEMBLE, "cycle de vie",
                    "Assemble les sorties de tous les modules du projet",
                    tâchesTerminales(périmètre), true));
            répertoire.add(new TaskDescription(BUILD, "cycle de vie",
                    descriptionBuild(périmètre), cibleDeBuild(périmètre), true));
            ajouterAliasVérification(répertoire, périmètre);
            répertoire.add(new TaskDescription(CLEAN, "cycle de vie",
                    "Supprime le répertoire build/ du projet (les sorties, jamais les sources)",
                    List.of(), true));
        } else {
            Set<String> demandés = new LinkedHashSet<>(modules);
            for (String module : demandés) {
                List<String> terminales = tâchesTerminales(
                        tâchesDuModule(périmètre, module));
                répertoire.add(new TaskDescription(module + ":" + ASSEMBLE,
                        "cycle de vie",
                        "Assemble les sorties du module " + module + " ("
                                + String.join(", ", terminales) + ")",
                        terminales, true));
                répertoire.add(new TaskDescription(module + ":" + CLEAN,
                        "cycle de vie",
                        "Supprime build/" + module + "/ (les sorties du module, jamais les sources)",
                        List.of(), true));
            }
        }
        return répertoire;
    }

    /**
     * Ajoute au répertoire les agrégats de vérification SOUS-TENDUS par le
     * graphe — et seulement eux : {@code check} (toutes les vérifications),
     * {@code lint}, {@code test}. Aucun aujourd'hui : aucun ajout.
     */
    private static void ajouterAliasVérification(List<TaskDescription> répertoire,
                                                 List<Task> concrètes) {
        List<String> vérifications = tâchesDeSuffixes(concrètes,
                SUFFIXES_VÉRIFICATION);
        if (!vérifications.isEmpty()) {
            répertoire.add(new TaskDescription(CHECK, "cycle de vie",
                    "Exécute toutes les tâches de vérification du projet ("
                            + vérifications.size() + ")",
                    vérifications, true));
        }
        List<String> lint = tâchesDeSuffixes(concrètes, Set.of("lint"));
        if (!lint.isEmpty()) {
            répertoire.add(new TaskDescription(LINT, "cycle de vie",
                    "Exécute toutes les tâches de lint du projet (" + lint.size()
                            + ")",
                    lint, true));
        }
        List<String> test = tâchesDeSuffixes(concrètes, Set.of("test"));
        if (!test.isEmpty()) {
            répertoire.add(new TaskDescription(TEST, "cycle de vie",
                    "Exécute toutes les tâches de test du projet (" + test.size()
                            + ")",
                    test, true));
        }
    }

    /**
     * Le sous-ensemble des tâches des modules demandés — un module inconnu est
     * refusé avec les modules disponibles (le même refus honnête que pour un
     * nom de tâche).
     *
     * @param concrètes les tâches concrètes du projet.
     * @param modules   modules demandés (vide ou nul : tout le projet).
     * @return les tâches des modules demandés, ordre du graphe conservé.
     * @throws GraphException si un module demandé est inconnu.
     */
    private static List<Task> sousEnsemble(List<Task> concrètes,
                                           List<String> modules) {
        if (modules == null || modules.isEmpty()) {
            return concrètes;
        }
        Set<String> existants = new LinkedHashSet<>();
        for (Task tâche : concrètes) {
            String module = moduleDe(tâche.name());
            if (module != null) {
                existants.add(module);
            }
        }
        for (String demandé : modules) {
            if (!existants.contains(demandé)) {
                throw new GraphException("Module demandé inconnu : " + demandé
                        + " — modules disponibles : "
                        + existants.stream().sorted().toList());
            }
        }
        Set<String> demandés = new LinkedHashSet<>(modules);
        return concrètes.stream()
                .filter(t -> demandés.contains(moduleDe(t.name())))
                .toList();
    }

    /**
     * Les tâches terminales d'un graphe concret : celles dont personne ne dépend —
     * les sorties finales du projet ({@code :signApk} d'une application,
     * {@code :jar} d'une bibliothèque).
     *
     * @param concrètes les tâches concrètes.
     * @return les noms des tâches sans dépendant, triés.
     */
    static List<String> tâchesTerminales(List<Task> concrètes) {
        Set<String> dépendues = new java.util.HashSet<>();
        for (Task tâche : concrètes) {
            dépendues.addAll(tâche.dependencies());
        }
        return concrètes.stream()
                .map(Task::name)
                .filter(nom -> !dépendues.contains(nom))
                .sorted()
                .toList();
    }

    /**
     * Les tâches d'un module du graphe (préfixe {@code module:}).
     *
     * @param concrètes les tâches concrètes.
     * @param module    le module demandé.
     * @return les tâches du module, ordre du graphe conservé.
     */
    private static List<Task> tâchesDuModule(List<Task> concrètes, String module) {
        return concrètes.stream()
                .filter(t -> module.equals(moduleDe(t.name())))
                .toList();
    }

    /**
     * Les noms des tâches concrètes dont le suffixe est dans le vocabulaire
     * donné, triés — la base des agrégats de vérification.
     */
    private static List<String> tâchesDeSuffixes(List<Task> concrètes,
                                                 Set<String> suffixes) {
        return concrètes.stream()
                .map(Task::name)
                .filter(nom -> suffixes.contains(suffixe(nom)))
                .sorted()
                .toList();
    }

    /**
     * Les dépendances de {@code build} : les terminales ET les vérifications
     * existantes — dérivées du graphe, jamais affirmées. Une vérification
     * TERMINALE (rien n'en dépend) est déjà dans les terminales : l'union se
     * DÉDUPE, sinon le répertoire la montrerait deux fois.
     */
    private static List<String> cibleDeBuild(List<Task> concrètes) {
        Set<String> cible = new LinkedHashSet<>(tâchesTerminales(concrètes));
        cible.addAll(tâchesDeSuffixes(concrètes, SUFFIXES_VÉRIFICATION));
        return List.copyOf(cible);
    }

    /**
     * La description de {@code build}, dérivée du graphe réel : ce qu'il fait
     * AUJOURD'HUI, pas ce que Gradle ferait.
     */
    private static String descriptionBuild(List<Task> concrètes) {
        List<String> vérifications = tâchesDeSuffixes(concrètes,
                SUFFIXES_VÉRIFICATION);
        if (vérifications.isEmpty()) {
            return "Assemble les sorties du projet — aucune tâche de vérification "
                    + "n'existe aujourd'hui : assemble seul";
        }
        return "Assemble les sorties et exécute les " + vérifications.size()
                + " tâche(s) de vérification du projet (assemble + check)";
    }

    /**
     * Le module d'un agrégat qualifié : {@code core:assemble} → {@code core},
     * {@code core:clean} → {@code core} ; {@code null} sinon (nom global ou
     * tâche concrète ordinaire).
     */
    static String moduleDAgrégat(String nom, String agrégat) {
        String cherché = ":" + agrégat;
        if (!nom.endsWith(cherché) || nom.length() <= cherché.length()) {
            return null;
        }
        return nom.substring(0, nom.length() - cherché.length());
    }

    /**
     * Le module d'un nom de tâche : {@code app:compileJava} → {@code app} ;
     * {@code null} si le nom n'est pas qualifié.
     */
    private static String moduleDe(String nom) {
        int deuxPoints = nom.lastIndexOf(':');
        return deuxPoints < 0 ? null : nom.substring(0, deuxPoints);
    }

    /** Groupe d'affichage d'une tâche, dérivé du vocabulaire du pipeline. */
    private static String groupe(String nom) {
        return switch (suffixe(nom)) {
            case "resolveDependencies" -> "dépendances";
            case "processResources" -> "ressources";
            case "generateBuildConfig", "generateViewBinding" -> "génération";
            case "compileJava", "compileKotlin" -> "compilation";
            case "dex", "packageApk", "signApk", "jar" -> "assemblage";
            case "lint", "test" -> "vérification";
            default -> "autre";
        };
    }

    /** Description humaine d'une tâche, dérivée du vocabulaire du pipeline. */
    private static String description(String nom) {
        return switch (suffixe(nom)) {
            case "resolveDependencies" -> "Résout et télécharge les dépendances Maven du module";
            case "processResources" -> "Compile et fusionne les ressources du module (aapt2)";
            case "generateBuildConfig" -> "Génère la classe BuildConfig du module";
            case "generateViewBinding" -> "Génère les classes de liaison de vues du module";
            case "compileJava" -> "Compile les sources Java du module";
            case "compileKotlin" -> "Compile les sources Kotlin du module";
            case "dex" -> "Convertit les classes et bibliothèques en dex";
            case "packageApk" -> "Assemble et aligne l'APK du module";
            case "signApk" -> "Signe l'APK du module (clé de débogage, v1 + v2)";
            case "jar" -> "Assemble le jar du module";
            case "lint" -> "Vérifie le code du module (lint)";
            case "test" -> "Exécute les tests du module";
            default -> "Tâche du pipeline Forge";
        };
    }

    /** Le suffixe d'un nom de tâche ({@code app:compileJava} → {@code compileJava}). */
    private static String suffixe(String nom) {
        int deuxPoints = nom.lastIndexOf(':');
        return deuxPoints < 0 ? nom : nom.substring(deuxPoints + 1);
    }

    /** Une tâche d'agrégation : son travail est sa liste de dépendances. */
    private record TâcheAgrégat(String name, List<String> dependencies) implements Task {

        TâcheAgrégat {
            dependencies = List.copyOf(dependencies);
        }

        @Override
        public void execute(TaskContext context) {
            // Rien : une tâche de cycle de vie ne fait rien elle-même — ses
            // dépendances sont le travail. Elle SUCCEEDED dès qu'elles passent.
            context.progression(name + " : " + dependencies.size()
                    + " tâche(s) demandée(s)");
        }
    }

    /** La tâche clean : suppression récursive, bornée au répertoire qui lui
     *  est donné — le projet ({@code build/}) ou un module
     *  ({@code build/<module>/}). */
    private record TâcheClean(String nom, Path répertoire) implements Task {

        @Override
        public String name() {
            return nom;
        }

        @Override
        public void execute(TaskContext context) {
            if (Files.notExists(répertoire)) {
                context.progression("rien à nettoyer : " + répertoire
                        + " est absent");
                return;
            }
            try (Stream<Path> chemins = Files.walk(répertoire)) {
                chemins.sorted(Comparator.reverseOrder()).forEach(chemin -> {
                    try {
                        Files.delete(chemin);
                    } catch (IOException échec) {
                        throw new UncheckedIOException(échec);
                    }
                });
            } catch (IOException échec) {
                throw new UncheckedIOException(échec);
            }
            context.progression("supprimé : " + répertoire);
        }
    }
}
