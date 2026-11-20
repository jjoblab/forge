package jo.forge.engine.tasks;

/**
 * Annulation coopérative d'une tâche : levée aux points sûrs après demande
 * d'annulation du build. L'exécuteur la distingue d'un échec (le build se termine
 * « annulé », pas « échoué »).
 */
public class TaskCancelledException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Crée l'exception d'annulation.
     *
     * @param message description en français.
     */
    public TaskCancelledException(String message) {
        super(message);
    }
}
