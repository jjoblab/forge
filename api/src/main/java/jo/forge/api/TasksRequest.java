package jo.forge.api;

/**
 * Demande de la liste des tâches d'un projet (protocole 5, trame
 * {@code TASKS_REQUEST}) : la coquille donne la racine, le moteur répond
 * {@link TasksResponse}.
 *
 * <p>Une simple requête de lecture — jamais un build : aucun événement ne part,
 * rien ne s'exécute, les empreintes ne bougent pas. L'équivalent exact de
 * {@code gradlew tasks} : décrire, pas construire.</p>
 *
 * @param projectRoot racine du projet (le dossier contenant {@code forge.json}).
 */
public record TasksRequest(String projectRoot) {

    public TasksRequest {
        if (projectRoot == null || projectRoot.isBlank()) {
            throw new IllegalArgumentException("Racine de projet absente");
        }
    }
}
