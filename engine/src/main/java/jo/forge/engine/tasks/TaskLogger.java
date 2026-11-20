package jo.forge.engine.tasks;

import java.nio.file.Path;

/**
 * Journal d'une tâche : chaque entrée part dans le flux d'événements du build avec le
 * nom de la tâche — les producteurs n'ont jamais besoin de le connaître.
 *
 * <p>Le moteur tamponne automatiquement les entrées au nom de la tâche courante
 * (parité avec le comportement constaté chez le moteur de référence, REF-027/REF-028),
 * et applique la rédaction de secrets au point d'entrée (constat S-9).</p>
 */
public interface TaskLogger {

    /**
     * Journalise un message de niveau DEBUG (visible en mode bavard).
     *
     * @param message le message.
     */
    void debug(String message);

    /**
     * Journalise un message de niveau INFO.
     *
     * @param message le message.
     */
    void info(String message);

    /**
     * Journalise un avertissement.
     *
     * @param message le message.
     */
    void warn(String message);

    /**
     * Journalise une erreur (le build échouera).
     *
     * @param message le message.
     */
    void error(String message);
}
