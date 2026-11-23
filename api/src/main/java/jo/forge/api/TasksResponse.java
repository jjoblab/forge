package jo.forge.api;

import java.util.List;

/**
 * Réponse du moteur à une demande de tâches (protocole 5, trame {@code TASKS}) :
 * le répertoire des tâches d'un projet, composé par le MOTEUR (décision D-22 —
 * le moteur dicte ses sorties, la coquille affiche).
 *
 * <p>L'ordre est déterministe : tâches concrètes en ordre topologique (niveau par
 * niveau, triées par nom au sein d'un niveau — l'ordre de {@code gradlew tasks}),
 * puis les tâches de cycle de vie ({@code assemble}, {@code clean}) à la fin.</p>
 *
 * @param projectRoot la racine demandée (renvoyée telle quelle : la réponse se
 *                    corrèle à la demande sans état côté moteur).
 * @param tasks       les descripteurs, éventuellement vide (projet sans modules) ;
 *                    jamais nulle.
 */
public record TasksResponse(String projectRoot, List<TaskDescription> tasks) {

    public TasksResponse {
        if (projectRoot == null) {
            projectRoot = "";
        }
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
    }
}
