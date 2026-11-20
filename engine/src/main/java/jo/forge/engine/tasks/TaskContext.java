package jo.forge.engine.tasks;

import java.nio.file.Path;

/**
 * Contexte d'exécution d'une tâche : journal, annulation, horloge et racines de
 * travail.
 *
 * <p>Le contexte est le seul canal autorisé vers l'extérieur de la tâche : pas
 * d'écriture console, pas d'accès direct aux événements. Les chemins sont résolus
 * contre la racine du projet fournie par la requête de build.</p>
 */
public interface TaskContext {

    /**
     * Racine du projet en construction.
     *
     * @return le chemin de la racine.
     */
    Path projectRoot();

    /**
     * Résout un chemin contre la racine du projet (absolu s'il l'est déjà).
     *
     * @param chemin chemin relatif ou absolu.
     * @return le chemin résolu.
     */
    default Path resolve(String chemin) {
        Path cheminPath = Path.of(chemin);
        return cheminPath.isAbsolute() ? cheminPath : projectRoot().resolve(cheminPath);
    }

    /**
     * Journal de la tâche (entrées tamponnées au nom de la tâche).
     *
     * @return le journal.
     */
    TaskLogger logger();

    /**
     * Jeton d'annulation du build.
     *
     * @return le jeton (à vérifier aux points sûrs).
     */
    CancellationFlag cancellation();

    /**
     * Vérifie l'annulation : lève {@link TaskCancelledException} si demandée.
     */
    default void checkCancelled() {
        cancellation().check();
    }
}
