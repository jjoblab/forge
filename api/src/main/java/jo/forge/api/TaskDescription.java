package jo.forge.api;

import java.util.List;

/**
 * Le descripteur d'une tâche telle que le moteur la présente à la coquille
 * (protocole 5) : le moteur est la source de vérité du répertoire des tâches —
 * comme {@code gradlew tasks} énumère le graphe du build, Forge énumère le sien.
 *
 * <p>La coquille ne DEVINE plus rien : avant un build, elle peut demander CE QUI
 * PEUT TOURNER (noms, groupes, descriptions, dépendances), afficher la liste et
 * laisser l'utilisateur choisir — la sélection repart vers le moteur dans
 * {@code BuildOptions.tasks()} (la clôture des dépendances se calcule côté
 * moteur, jamais côté coquille).</p>
 *
 * <p>Les tâches de cycle de vie sont des descripteurs comme les autres, marqués
 * {@code lifecycle} : elles n'ont pas d'équivalent concret dans le graphe par
 * défaut — elles s'y injectent à la demande. Le répertoire (protocole 6, D-23)
 * expose {@code assemble}, {@code build} (l'alias HONNÊTE : ses dépendances et
 * sa description disent ce qu'il vaut AUJOURD'HUI), {@code clean}, les agrégats
 * de vérification SOUS-TENDUS ({@code check}/{@code lint}/{@code test} —
 * absents tant qu'aucune tâche {@code :lint}/{@code :test} n'existe), et par
 * sous-ensemble de modules les agrégats propres de chacun
 * ({@code core:assemble}, {@code core:clean}) : une coquille qui affiche
 * « assemble » honnêtement sait qu'il s'agit d'une tâche d'agrégation, pas
 * d'une étape de compilation.</p>
 *
 * @param name         nom complet et unique de la tâche (ex. {@code app:compileJava},
 *                     {@code assemble}) — jamais nul ni vide.
 * @param group        groupe d'affichage (ex. {@code compilation}, {@code assemblage},
 *                     {@code cycle de vie}) ; jamais nul (vide = sans groupe).
 * @param description  une ligne humaine qui dit CE QUE FAIT la tâche ; jamais nulle.
 * @param dependencies noms des tâches dont celle-ci dépend (directement) ; jamais nulle.
 * @param lifecycle    vrai pour une tâche de cycle de vie (agrégat ou nettoyage) —
 *                     absente du graphe concret par défaut, injectée à la demande.
 */
public record TaskDescription(String name, String group, String description,
                              List<String> dependencies, boolean lifecycle) {

    public TaskDescription {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Nom de tâche absent");
        }
        group = group == null ? "" : group;
        description = description == null ? "" : description;
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
    }

    /** Forme minimale (sans groupe ni description) — tests et lectures tolérantes. */
    public TaskDescription(String name, List<String> dependencies) {
        this(name, "", "", dependencies, false);
    }
}
