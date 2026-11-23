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
 * Tâches de cycle de vie et répertoire des tâches (protocole 5, décision D-22) :
 * le moteur est la source de vérité de CE QUI PEUT TOURNER.
 *
 * <p>Deux tâches d'agrégation à la Gradle : {@code assemble} (dépend des tâches
 * terminales du graphe concret — les sorties de tous les modules) et {@code clean}
 * (supprime {@code build/} du projet, jamais les sources ni l'état de
 * {@code .forge/}). Elles vivent HORS du graphe concret par défaut : un build
 * complet ne doit pas nettoyer ce qu'il construit — elles s'injectent à la
 * demande, quand la requête ({@code BuildOptions.tasks}) les nomme.</p>
 *
 * <p>Le répertoire ({@link #descripteurs}) expose les tâches concrètes — groupe et
 * description dérivés du vocabulaire du pipeline — plus les deux tâches de cycle
 * de vie, marquées {@code lifecycle} : la coquille affiche, elle ne devine plus.</p>
 */
public final class LifecycleTasks {

    /** Nom de la tâche d'agrégation des sorties (l'équivalent Gradle). */
    public static final String ASSEMBLE = "assemble";

    /** Nom de la tâche de nettoyage des sorties (l'équivalent Gradle). */
    public static final String CLEAN = "clean";

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
        List<String> terminales = tâchesTerminales(concrètes);
        return new TâcheAgrégat(ASSEMBLE, terminales);
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
        return new TâcheClean(racine);
    }

    /**
     * La cible effective d'une requête : tâches concrètes augmentées des tâches
     * de cycle de vie demandées, puis réduites à la clôture des noms demandés
     * (la clôture des dépendances se calcule côté moteur — jamais côté coquille).
     *
     * <p>Demande vide : tout le graphe concret, la forme historique. Un nom
     * inconnu échoue proprement avec les candidats — le refus de Gradle
     * (« Task 'xxx' not found in root project », liste des disponibles).</p>
     *
     * @param racine    racine du projet (pour {@code clean}).
     * @param concrètes les tâches concrètes du projet.
     * @param demandées les noms demandés par la requête (éventuellement vide).
     * @return les tâches à exécuter (ordre indifférent : le graphe re-classe).
     * @throws GraphException si un nom demandé est inconnu.
     */
    public static List<Task> cible(Path racine, List<Task> concrètes,
                                   List<String> demandées) {
        if (demandées == null || demandées.isEmpty()) {
            return concrètes;
        }
        List<Task> augmentées = new ArrayList<>(concrètes);
        if (demandées.contains(CLEAN)) {
            augmentées.add(tâcheClean(racine));
        }
        if (demandées.contains(ASSEMBLE)) {
            augmentées.add(tâcheAssemble(concrètes));
        }
        return clôture(augmentées, demandées);
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
     * Le répertoire des tâches d'un projet, pour la trame {@code TASKS} : les
     * descripteurs des tâches concrètes (ordre déterministe : groupe, puis nom)
     * puis les deux tâches de cycle de vie — la coquille affiche ce répertoire
     * sans rien deviner (les noms de cycle de vie sont honnêtement marqués).
     *
     * @param concrètes les tâches concrètes du projet.
     * @return les descripteurs, concrets d'abord puis cycle de vie.
     */
    public static List<TaskDescription> descripteurs(List<Task> concrètes) {
        List<TaskDescription> répertoire = concrètes.stream()
                .map(t -> new TaskDescription(t.name(), groupe(t.name()),
                        description(t.name()), t.dependencies(), false))
                .sorted(Comparator.comparing(TaskDescription::group)
                        .thenComparing(TaskDescription::name))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        répertoire.add(new TaskDescription(ASSEMBLE, "cycle de vie",
                "Assemble les sorties de tous les modules du projet",
                tâchesTerminales(concrètes), true));
        répertoire.add(new TaskDescription(CLEAN, "cycle de vie",
                "Supprime le répertoire build/ du projet (les sorties, jamais les sources)",
                List.of(), true));
        return répertoire;
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
        Set<String> dépendées = new java.util.HashSet<>();
        for (Task tâche : concrètes) {
            dépendées.addAll(tâche.dependencies());
        }
        return concrètes.stream()
                .map(Task::name)
                .filter(nom -> !dépendées.contains(nom))
                .sorted()
                .toList();
    }

    /** Groupe d'affichage d'une tâche, dérivé du vocabulaire du pipeline. */
    private static String groupe(String nom) {
        return switch (suffixe(nom)) {
            case "resolveDependencies" -> "dépendances";
            case "processResources" -> "ressources";
            case "generateBuildConfig", "generateViewBinding" -> "génération";
            case "compileJava", "compileKotlin" -> "compilation";
            case "dex", "packageApk", "signApk", "jar" -> "assemblage";
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
                    + " tâche(s) terminale(s) demandée(s)");
        }
    }

    /** La tâche clean : suppression récursive, bornée au répertoire build/. */
    private record TâcheClean(Path racine) implements Task {

        @Override
        public String name() {
            return CLEAN;
        }

        @Override
        public void execute(TaskContext context) {
            Path buildDir = racine.resolve("build");
            if (Files.notExists(buildDir)) {
                context.progression("rien à nettoyer : " + buildDir + " est absent");
                return;
            }
            try (Stream<Path> chemins = Files.walk(buildDir)) {
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
            context.progression("supprimé : " + buildDir);
        }
    }
}
