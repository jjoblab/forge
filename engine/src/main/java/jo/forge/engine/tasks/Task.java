package jo.forge.engine.tasks;

import java.util.List;

/**
 * Une tâche du graphe de build : identité, dépendances, entrées/sorties déclarées et
 * travail à exécuter.
 *
 * <p>Les entrées et sorties sont déclarées <em>avant</em> l'exécution — c'est ce qui
 * rend possibles la vérification d'up-to-date et l'invalidation précise (décision
 * D-12). L'exécution doit vérifier l'annulation aux points sûrs
 * ({@link TaskContext#checkCancelled()}) et journaliser via le contexte (jamais vers
 * la console directement : tout passe par le canal d'événements).</p>
 *
 * <p>Implémentations : tâches de compilation, de dex, de packaging… Le moteur ne
 * connaît que cette interface.</p>
 */
public interface Task {

    /**
     * Nom complet et unique de la tâche (ex. {@code app:compileJava}).
     *
     * @return le nom, jamais nul ni vide.
     */
    String name();

    /**
     * Dépendances dures : chacune doit réussir (ou être à jour) avant l'exécution ;
     * l'échec d'une dépendance bloque transitivement.
     *
     * @return les noms des tâches dépendues, éventuellement vide.
     */
    default List<String> dependencies() {
        return List.of();
    }

    /**
     * Déclaration des entrées, sorties et propriétés (oracle d'up-to-date).
     *
     * @return la déclaration, {@link TaskIO#EMPTY} par défaut.
     */
    default TaskIO io() {
        return TaskIO.EMPTY;
    }

    /**
     * Exécute le travail de la tâche.
     *
     * @param context contexte d'exécution (journal, annulation, horloge).
     * @throws Exception en cas d'échec : l'exception est convertie en échec de tâche
     *                   avec cause lisible (jamais avalée).
     */
    void execute(TaskContext context) throws Exception;
}
