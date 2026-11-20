package jo.forge.engine.graph;

import jo.forge.engine.tasks.Task;
import jo.forge.engine.tasks.TaskContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests du graphe de tâches : niveaux, ordre déterministe, détection de cycle avec
 * message actionnable, noms dupliqués, dépendances inconnues.
 */
class TaskGraphTest {

    /** Tâche factice minimale. */
    private static Task tache(String nom, String... dependances) {
        return new Task() {
            @Override
            public String name() {
                return nom;
            }

            @Override
            public List<String> dependencies() {
                return List.of(dependances);
            }

            @Override
            public void execute(TaskContext context) {
                // Rien : le graphe n'exécute pas.
            }
        };
    }

    @Test
    void niveauxTopologiquesCorrects() {
        TaskGraph graphe = TaskGraph.of(List.of(
                tache("c", "a", "b"),
                tache("a"),
                tache("b")));
        assertEquals(3, graphe.size());
        assertEquals(2, graphe.levels().size());
        // Niveau 0 : a et b (indépendantes), triées par nom.
        assertEquals(List.of("a", "b"),
                graphe.levels().get(0).stream().map(Task::name).toList());
        // Niveau 1 : c seule.
        assertEquals(List.of("c"),
                graphe.levels().get(1).stream().map(Task::name).toList());
    }

    @Test
    void ordreTotalDeterministe() {
        TaskGraph graphe = TaskGraph.of(List.of(
                tache("z"),
                tache("m", "z"),
                tache("a", "z"),
                tache("b", "m", "a")));
        assertEquals(List.of("z", "a", "m", "b"),
                graphe.allTasks().stream().map(Task::name).toList());
    }

    @Test
    void cycleDetecteAvecLeChemin() {
        GraphException erreur = assertThrows(GraphException.class, () -> TaskGraph.of(
                List.of(tache("a", "c"), tache("b", "a"), tache("c", "b"))));
        assertTrue(erreur.getMessage().contains("→"),
                "le message doit citer le cycle : " + erreur.getMessage());
        assertTrue(erreur.getMessage().contains("a") && erreur.getMessage().contains("b")
                && erreur.getMessage().contains("c"));
    }

    @Test
    void autoDependanceDetectee() {
        GraphException erreur = assertThrows(GraphException.class, () ->
                TaskGraph.of(List.of(tache("a", "a"))));
        assertTrue(erreur.getMessage().contains("Cycle"));
    }

    @Test
    void dependanceInconnueSignalee() {
        GraphException erreur = assertThrows(GraphException.class, () ->
                TaskGraph.of(List.of(tache("a", "fantome"))));
        assertTrue(erreur.getMessage().contains("fantome"));
        assertTrue(erreur.getMessage().contains("a"));
    }

    @Test
    void nomDupliqueRefuse() {
        GraphException erreur = assertThrows(GraphException.class, () ->
                TaskGraph.of(List.of(tache("a"), tache("a"))));
        assertTrue(erreur.getMessage().contains("dupliqué"));
    }

    @Test
    void accesParNom() {
        TaskGraph graphe = TaskGraph.of(List.of(tache("a"), tache("b", "a")));
        assertEquals("b", graphe.task("b").name());
        assertThrows(GraphException.class, () -> graphe.task("inconnue"));
    }
}
