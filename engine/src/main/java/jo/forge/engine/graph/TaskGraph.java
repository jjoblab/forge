package jo.forge.engine.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import jo.forge.engine.tasks.Task;

/**
 * Graphe de tâches validé : noms uniques, dépendances résolues, absence de cycle,
 * niveaux topologiques calculés.
 *
 * <p>Contrairement au moteur de référence, les dépendances sont uniquement
 * <em>explicites</em> — pas d'inférence par préfixe de chemin (constat C-5 : une
 * source de sur-sérialisation et de blocages induits). Le tri par niveaux (Kahn)
 * sert aux diagnostics et à l'ordre déterministe ; l'exécution, elle, suit une file
 * de prêtes (une tâche démarre dès que ses dépendances sont terminées, sans attendre
 * tout son niveau — constat P-3).</p>
 */
public final class TaskGraph {

    private final Map<String, Task> tasksByName;
    private final List<List<Task>> levels;

    private TaskGraph(Map<String, Task> tasksByName, List<List<Task>> levels) {
        this.tasksByName = tasksByName;
        this.levels = levels;
    }

    /**
     * Construit et valide le graphe depuis un ensemble de tâches.
     *
     * @param tasks les tâches (l'ordre d'entrée n'a pas d'importance).
     * @return le graphe validé.
     * @throws GraphException en cas de nom dupliqué, de dépendance inconnue ou de
     *                        cycle (le message cite le cycle).
     */
    public static TaskGraph of(Iterable<Task> tasks) {
        Map<String, Task> parNom = new LinkedHashMap<>();
        for (Task task : tasks) {
            String nom = task.name();
            if (nom == null || nom.isBlank()) {
                throw new GraphException("Tâche sans nom");
            }
            if (parNom.put(nom, task) != null) {
                throw new GraphException("Nom de tâche dupliqué : " + nom);
            }
        }
        for (Task task : parNom.values()) {
            for (String dependance : task.dependencies()) {
                if (!parNom.containsKey(dependance)) {
                    throw new GraphException("La tâche " + task.name()
                            + " dépend de l'inconnue " + dependance);
                }
            }
        }
        return new TaskGraph(parNom, calculerNiveaux(parNom));
    }

    /**
     * Tâche par nom.
     *
     * @param nom nom de la tâche.
     * @return la tâche correspondante.
     * @throws GraphException si aucune tâche ne porte ce nom.
     */
    public Task task(String nom) {
        Task task = tasksByName.get(nom);
        if (task == null) {
            throw new GraphException("Tâche inconnue : " + nom);
        }
        return task;
    }

    /**
     * Toutes les tâches, en ordre topologique (niveau par niveau, trié par nom au
     * sein d'un niveau pour un ordre déterministe).
     *
     * @return la liste ordonnée des tâches.
     */
    public List<Task> allTasks() {
        List<Task> toutes = new ArrayList<>();
        levels.forEach(toutes::addAll);
        return toutes;
    }

    /**
     * Niveaux topologiques (diagnostic, ordonnancement de repli).
     *
     * @return les niveaux, du plus profond au plus superficiel.
     */
    public List<List<Task>> levels() {
        return levels;
    }

    /**
     * Nombre de tâches du graphe.
     *
     * @return le nombre de tâches.
     */
    public int size() {
        return tasksByName.size();
    }

    /**
     * Calcule les niveaux par l'algorithme de Kahn ; en cas de cycle, extrait un
     * cycle réel pour le message d'erreur.
     */
    private static List<List<Task>> calculerNiveaux(Map<String, Task> parNom) {
        Map<String, Integer> dependancesRestantes = new TreeMap<>();
        Map<String, Set<String>> dependants = new HashMap<>();
        for (Task task : parNom.values()) {
            dependancesRestantes.put(task.name(), task.dependencies().size());
            for (String dependance : task.dependencies()) {
                dependants.computeIfAbsent(dependance, k -> new LinkedHashSet<>())
                        .add(task.name());
            }
        }

        List<List<Task>> niveaux = new ArrayList<>();
        Deque<String> prets = new ArrayDeque<>(dependancesRestantes.entrySet().stream()
                .filter(e -> e.getValue() == 0).map(Map.Entry::getKey).toList());

        int placees = 0;
        while (!prets.isEmpty()) {
            // Tri stable par nom dans le niveau : ordre déterministe.
            List<String> niveauCourant = new ArrayList<>(prets);
            niveauCourant.sort(String::compareTo);
            prets.clear();
            List<Task> tachesNiveau = new ArrayList<>();
            for (String nom : niveauCourant) {
                tachesNiveau.add(parNom.get(nom));
                placees++;
                for (String dependant : dependants.getOrDefault(nom, Set.of())) {
                    int restantes = dependancesRestantes.merge(dependant, -1, Integer::sum);
                    if (restantes == 0) {
                        prets.add(dependant);
                    }
                }
            }
            niveaux.add(tachesNiveau);
        }

        if (placees != parNom.size()) {
            throw new GraphException("Cycle de dépendances : " + trouverCycle(parNom));
        }
        return niveaux;
    }

    /**
     * Extrait un cycle réel (DFS coloré) pour un message d'erreur actionnable.
     */
    private static String trouverCycle(Map<String, Task> parNom) {
        Map<String, Integer> couleurs = new HashMap<>();
        Deque<String> chemin = new ArrayDeque<>();
        for (String depart : parNom.keySet()) {
            String cycle = dfsCycle(depart, parNom, couleurs, chemin);
            if (cycle != null) {
                return cycle;
            }
        }
        return "cycle non détaillé";
    }

    private static String dfsCycle(String noeud, Map<String, Task> parNom,
                                   Map<String, Integer> couleurs, Deque<String> chemin) {
        couleurs.put(noeud, 1);
        chemin.addLast(noeud);
        for (String dependance : parNom.get(noeud).dependencies()) {
            if (couleurs.getOrDefault(dependance, 0) == 1) {
                // Cycle trouvé : du premier passage sur la dépendance jusqu'ici.
                List<String> cycle = new ArrayList<>();
                boolean dansCycle = false;
                for (String etape : chemin) {
                    if (etape.equals(dependance)) {
                        dansCycle = true;
                    }
                    if (dansCycle) {
                        cycle.add(etape);
                    }
                }
                cycle.add(dependance);
                return String.join(" → ", cycle);
            }
            if (couleurs.getOrDefault(dependance, 0) == 0) {
                String trouve = dfsCycle(dependance, parNom, couleurs, chemin);
                if (trouve != null) {
                    return trouve;
                }
            }
        }
        chemin.removeLast();
        couleurs.put(noeud, 2);
        return null;
    }
}
