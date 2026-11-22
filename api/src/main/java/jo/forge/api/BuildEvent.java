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
        BuildEvent.DownloadEvent,
        BuildEvent.HeartbeatEvent,
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
        DEBUG(1, 1),
        /** Information normale de progression. */
        INFO(2, 2),
        /**
         * Ligne de vie de la console (protocole 4) : tâches, verdict, annonces de
         * build. Le niveau par défaut d'une console de build — l'équivalent du
         * {@code LIFECYCLE} de Gradle, entre INFO et avertissement : ce que la
         * coquille doit afficher sans rien demander, l'essentiel du déroulé.
         */
        LIFECYCLE(5, 3),
        /** Avertissement : le build continue. */
        WARN(3, 4),
        /** Erreur : le build échouera ou a échoué. */
        ERROR(4, 5);

        private final int wireCode;
        private final int sévérité;

        LogLevel(int wireCode, int sévérité) {
            this.wireCode = wireCode;
            this.sévérité = sévérité;
        }

        /** Code filaire stable du niveau. */
        public int wireCode() {
            return wireCode;
        }

        /**
         * Sévérité ordonnée pour le filtrage : {@code DEBUG < INFO < LIFECYCLE <
         * WARN < ERROR}. Ce n'est PAS le code filaire (identifiant stable, sans
         * ordre) : la sévérité est la grandeur physique que borne un niveau de
         * console — une entrée est émise si sa sévérité atteint celle demandée.
         *
         * @return la sévérité (1 à 5).
         */
        public int sévérité() {
            return sévérité;
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
     * <p>À partir du protocole 4, ces entrées portent AUSSI les lignes de la console
     * elle-même (lignes de tâches, verdict — niveau {@code LIFECYCLE}) : le format
     * de la console appartient au moteur, la coquille affiche ce qu'elle reçoit
     * (décision D-21, parité avec Gradle qui dicte ses sorties).</p>
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
        UP_TO_DATE(5),
        /** La tâche s'est exécutée en relisant un cache (aucun travail recalculé). */
        FROM_CACHE(6);

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
     * Progression d'un téléchargement de dépendance (protocole 2).
     *
     * <p>Émis par la résolution Maven : au début (0 octet), à intervalle pendant le
     * transfert (fréquence bornée par le moteur) et à la fin. {@code done} distingue la
     * progression partielle ({@code false}) du téléchargement terminé ({@code true}).
     * La ressource est identifiée par son fichier court (lisible dans une console) ;
     * {@code bytesTotal} vaut {@code -1} quand le serveur ne l'annonce pas.</p>
     *
     * @param buildId         identifiant du build.
     * @param timestamp       horodatage côté moteur.
     * @param resource        fichier téléchargé (ex. {@code ui-android-1.7.1.aar}),
     *                        jamais nul.
     * @param bytesDownloaded octets reçus à ce stade.
     * @param bytesTotal      taille totale annoncée, ou {@code -1} si inconnue.
     * @param done            {@code true} quand le téléchargement est terminé.
     */
    record DownloadEvent(String buildId, long timestamp, String resource,
                         long bytesDownloaded, long bytesTotal, boolean done)
            implements BuildEvent {
    }

    /**
     * Pulsation d'une tâche longue (protocole 3) : la tâche est VIVANTE et son
     * exécution dure — le silence n'est plus interprétable comme un plantage.
     *
     * <p>Émis par l'exécuteur au plus toutes les 30 secondes pour CHAQUE tâche en
     * cours d'exécution (jamais pour les tâches à jour ou sautées) : temps écoulé
     * depuis le début de la tâche, et dernier message de progression posé par la
     * tâche elle-même (ex. {@code « dexage : 12/41 bibliothèques »} — vide si la
     * tâche n'en expose pas). Une coquille protocole 3 l'affiche en ligne vivante ;
     * une coquille plus ancienne ne le reçoit pas (dégradation : rien — le silence
     * était la norme avant).</p>
     *
     * @param buildId   identifiant du build.
     * @param timestamp horodatage côté moteur.
     * @param taskName  nom de la tâche en cours.
     * @param message   dernier message de progression de la tâche (peut être vide).
     * @param elapsedMs temps écoulé depuis le début de la tâche, en millisecondes.
     */
    record HeartbeatEvent(String buildId, long timestamp, String taskName,
                          String message, long elapsedMs)
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
