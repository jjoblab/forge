package jo.forge.api;

/**
 * Événement d'un build en cours, envoyé en flux par le moteur (trame {@code BUILD_EVENT}).
 *
 * <p>Hiérarchie fermée : chaque sous-type est un type d'événement précis. L'ordre des
 * événements d'un même build est préservé de bout en bout (écriture synchronisée côté
 * moteur, distribution dans l'ordre d'arrivée côté coquille).</p>
 */
public sealed interface BuildEvent permits
        BuildEvent.LogEvent,
        BuildEvent.DiagnosticEvent,
        BuildEvent.TaskStateEvent,
        BuildEvent.ProgressEvent,
        BuildEvent.BuildFinished {

    /**
     * Identifiant du build auquel se rattache l'événement.
     *
     * @return l'identifiant non nul.
     */
    String buildId();

    /** Niveau de journal d'une entrée de log. */
    enum LogLevel {
        /** Détail de diagnostic, utile uniquement en mode bavard. */
        DEBUG(1),
        /** Information normale de progression. */
        INFO(2),
        /** Avertissement : le build continue. */
        WARN(3),
        /** Erreur : le build échouera ou a échoué. */
        ERROR(4);

        private final int wireCode;

        LogLevel(int wireCode) {
            this.wireCode = wireCode;
        }

        /** Code filaire stable du niveau. */
        public int wireCode() {
            return wireCode;
        }

        /**
         * Retrouve le niveau depuis son code filaire.
         *
         * @param wireCode code lu sur le canal.
         * @return le niveau correspondant.
         * @throws ProtocolException si le code est inconnu.
         */
        public static LogLevel fromWire(int wireCode) {
            for (LogLevel level : values()) {
                if (level.wireCode == wireCode) {
                    return level;
                }
            }
            throw new ProtocolException("Niveau de journal inconnu : " + wireCode);
        }
    }

    /**
     * Entrée de journal du build.
     *
     * @param buildId    identifiant du build.
     * @param timestamp  horodatage côté moteur (millisecondes epoch).
     * @param level      niveau de l'entrée.
     * @param message    texte du journal, jamais nul (les secrets y sont déjà rédigés).
     */
    record LogEvent(String buildId, long timestamp, LogLevel level, String message)
            implements BuildEvent {
    }

    /** Sévérité d'un diagnostic structuré. */
    enum Severity {
        /** Information. */
        INFO(1),
        /** Avertissement. */
        WARNING(2),
        /** Erreur bloquante. */
        ERROR(3);

        private final int wireCode;

        Severity(int wireCode) {
            this.wireCode = wireCode;
        }

        /** Code filaire stable de la sévérité. */
        public int wireCode() {
            return wireCode;
        }

        /**
         * Retrouve la sévérité depuis son code filaire.
         *
         * @param wireCode code lu sur le canal.
         * @return la sévérité correspondante.
         * @throws ProtocolException si le code est inconnu.
         */
        public static Severity fromWire(int wireCode) {
            for (Severity severity : values()) {
                if (severity.wireCode == wireCode) {
                    return severity;
                }
            }
            throw new ProtocolException("Sévérité inconnue : " + wireCode);
        }
    }

    /**
     * Diagnostic structuré : position exacte dans un fichier, exploitable par l'éditeur
     * (saut à l'erreur). Parité assumée avec le format du moteur de référence
     * (voir {@code REFERENCES.md}, entrées du pipeline de diagnostics).
     *
     * @param buildId   identifiant du build.
     * @param timestamp horodatage côté moteur.
     * @param severity  sévérité du diagnostic.
     * @param file      fichier concerné (chemin relatif au projet), éventuellement nul.
     * @param line      ligne (1-based, 0 si inconnue).
     * @param column    colonne (1-based, 0 si inconnue).
     * @param code      code machine du diagnostic (ex. {@code javac:cannot-find-symbol}),
     *                  éventuellement nul.
     * @param message   message humain, jamais nul.
     * @param tool      outil émetteur (ex. {@code java-compiler}), jamais nul.
     */
    record DiagnosticEvent(String buildId, long timestamp, Severity severity, String file,
                           int line, int column, String code, String message, String tool)
            implements BuildEvent {
    }

    /** État d'une tâche du graphe. */
    enum TaskState {
        /** La tâche démarre. */
        STARTED(1),
        /** La tâche s'est terminée avec succès (travail effectué). */
        SUCCEEDED(2),
        /** La tâche a échoué. */
        FAILED(3),
        /** La tâche a été sautée (non demandée, condition non remplie). */
        SKIPPED(4),
        /** La tâche était à jour : aucun travail nécessaire. */
        UP_TO_DATE(5);

        private final int wireCode;

        TaskState(int wireCode) {
            this.wireCode = wireCode;
        }

        /** Code filaire stable de l'état. */
        public int wireCode() {
            return wireCode;
        }

        /**
         * Retrouve l'état depuis son code filaire.
         *
         * @param wireCode code lu sur le canal.
         * @return l'état correspondant.
         * @throws ProtocolException si le code est inconnu.
         */
        public static TaskState fromWire(int wireCode) {
            for (TaskState state : values()) {
                if (state.wireCode == wireCode) {
                    return state;
                }
            }
            throw new ProtocolException("État de tâche inconnu : " + wireCode);
        }
    }

    /**
     * Changement d'état d'une tâche du graphe de build.
     *
     * @param buildId    identifiant du build.
     * @param timestamp  horodatage côté moteur.
     * @param taskName   nom complet de la tâche (ex. {@code app:compileJava}).
     * @param state      nouvel état.
     * @param durationMs durée d'exécution (0 pour {@code STARTED}).
     */
    record TaskStateEvent(String buildId, long timestamp, String taskName, TaskState state,
                          long durationMs) implements BuildEvent {
    }

    /**
     * Progression globale du build.
     *
     * @param buildId   identifiant du build.
     * @param timestamp horodatage côté moteur.
     * @param tasksTotal nombre total de tâches du graphe.
     * @param tasksDone  nombre de tâches terminées (quel que soit leur état final).
     */
    record ProgressEvent(String buildId, long timestamp, int tasksTotal, int tasksDone)
            implements BuildEvent {
    }

    /**
     * Fin d'un build : toujours le dernier événement du build.
     *
     * @param buildId    identifiant du build.
     * @param timestamp  horodatage côté moteur.
     * @param success    {@code true} si le build a réussi.
     * @param failedTask nom de la tâche fautive, ou {@code null} en cas de succès.
     * @param durationMs durée totale du build en millisecondes.
     * @param message    message de synthèse, jamais nul.
     */
    record BuildFinished(String buildId, long timestamp, boolean success, String failedTask,
                         long durationMs, String message) implements BuildEvent {
    }
}
