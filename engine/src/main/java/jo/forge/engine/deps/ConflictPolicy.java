package jo.forge.engine.deps;

/**
 * Politique de médiation quand plusieurs versions d'un même artefact sont demandées
 * par le graphe de dépendances.
 */
public enum ConflictPolicy {

    /** La version la plus récente gagne (comportement Gradle par défaut). */
    NEWEST,

    /** La version la plus proche de la racine gagne, puis la première rencontrée (comportement Maven). */
    NEAREST,

    /** Toute divergence de version pour une même identité échoue le build avec un diagnostic listant les chemins. */
    FAIL_ON_CONFLICT
}
