package jo.forge.api;

import java.util.List;

/**
 * Demande de la liste des tâches d'un projet (protocole 5, trame
 * {@code TASKS_REQUEST}) : la coquille donne la racine, le moteur répond
 * {@link TasksResponse}.
 *
 * <p>Une simple requête de lecture — jamais un build : aucun événement ne part,
 * rien ne s'exécute, les empreintes ne bougent pas. L'équivalent exact de
 * {@code gradlew tasks} : décrire, pas construire.</p>
 *
 * <p>{@code modules} (protocole 6, décision D-23) demande le répertoire d'un
 * SOUS-ENSEMBLE de modules — l'équivalent de {@code gradlew :core:tasks} : le
 * moteur filtre le graphe aux modules demandés AVANT de composer les
 * descripteurs et le cycle de vie ({@code core:assemble} y agrège les sorties
 * de core, pas celles du projet). Vide ou nulle (le défaut, la forme
 * d'avant) : tout le projet. Un module inconnu échoue proprement avec les
 * modules disponibles — le même refus honnête que pour un nom de tâche.</p>
 *
 * @param projectRoot racine du projet (le dossier contenant {@code forge.json}).
 * @param modules     modules demandés (noms sans préfixe, ex. {@code app},
 *                    {@code core}) ; vide ou nul = tout le projet.
 */
public record TasksRequest(String projectRoot, List<String> modules) {

    public TasksRequest {
        if (projectRoot == null || projectRoot.isBlank()) {
            throw new IllegalArgumentException("Racine de projet absente");
        }
        modules = modules == null ? List.of() : List.copyOf(modules);
    }

    /**
     * Construit la demande historique (protocole 5) : tout le projet, sans
     * sous-ensemble. Compatibilité des appelants et des charges d'alors.
     *
     * @param projectRoot racine du projet.
     */
    public TasksRequest(String projectRoot) {
        this(projectRoot, null);
    }
}
