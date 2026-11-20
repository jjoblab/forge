package jo.forge.engine.model;

import java.util.List;
import java.util.Optional;

/**
 * Modèle déclaratif d'un projet Forge, lu depuis {@code forge.json} à la racine du
 * projet (décision D-11).
 *
 * <p>Le moteur transforme ce modèle statique en DAG de tâches ; il n'exécute jamais de
 * code du modèle. Les dépendances entre modules forment un graphe orienté — les cycles
 * sont rejetés au moment de construire le graphe de tâches (moteur de tâches, étape 5),
 * avec le nom des modules en cause.</p>
 *
 * @param modelVersion version du format (1 actuellement ; toute autre valeur est
 *                     refusée avec un message explicite).
 * @param repositories dépôts Maven du projet (URL absolues ; HTTPS exigé à la
 *                     résolution).
 * @param modules      modules du projet, au moins un.
 */
public record ForgeModel(int modelVersion, List<String> repositories,
                         List<ForgeModule> modules) {

    /** Version courante du format {@code forge.json}. */
    public static final int CURRENT_MODEL_VERSION = 1;

    /**
     * Valide et fige le modèle.
     *
     * @throws ModelException si la version est inconnue ou s'il n'y a aucun module.
     */
    public ForgeModel {
        repositories = repositories == null ? List.of() : List.copyOf(repositories);
        modules = modules == null ? List.of() : List.copyOf(modules);
        if (modelVersion != CURRENT_MODEL_VERSION) {
            throw new ModelException("Version de modèle non supportée : " + modelVersion
                    + " (attendue : " + CURRENT_MODEL_VERSION + ")");
        }
        if (modules.isEmpty()) {
            throw new ModelException("Le projet ne déclare aucun module");
        }
    }

    /**
     * Retrouve un module par son nom.
     *
     * @param name nom exact du module.
     * @return le module, s'il existe.
     */
    public Optional<ForgeModule> module(String name) {
        return modules.stream().filter(m -> m.name().equals(name)).findFirst();
    }
}
