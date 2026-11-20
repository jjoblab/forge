package jo.forge.engine.model;

import java.util.List;

/**
 * Portée d'une dépendance, en miroir des portées connues des systèmes de build JVM
 * (sémantique Gradle/Maven : ce qui se voit à la compilation, ce qui se voit à
 * l'exécution, ce qui se transmet aux consommateurs).
 *
 * <p>Ensemble fermé, nommé par son nom filaire {@code forge.json}.</p>
 */
public enum DependencyScope {

    /** Visible à la compilation du module ET transmise aux consommateurs (Gradle {@code api}). */
    API("api"),

    /** Visible à la compilation du module, non transmise aux consommateurs (Gradle {@code implementation}). */
    IMPLEMENTATION("implementation"),

    /** Visible uniquement à la compilation (Gradle {@code compileOnly}, Maven {@code provided}). */
    COMPILE_ONLY("compileOnly"),

    /** Visible uniquement à l'exécution (Gradle {@code runtimeOnly}, Maven {@code runtime}). */
    RUNTIME_ONLY("runtimeOnly");

    private final String wireName;

    DependencyScope(String wireName) {
        this.wireName = wireName;
    }

    /**
     * Nom stable utilisé dans {@code forge.json}.
     *
     * @return le nom en camelCase.
     */
    public String wireName() {
        return wireName;
    }

    /**
     * Retrouve la portée depuis son nom {@code forge.json}.
     *
     * @param wireName nom lu dans le modèle (insensible à la casse).
     * @return la portée correspondante.
     * @throws ModelException si le nom est inconnu.
     */
    public static DependencyScope fromWire(String wireName) {
        for (DependencyScope scope : values()) {
            if (scope.wireName.equalsIgnoreCase(wireName)) {
                return scope;
            }
        }
        throw new ModelException("Portée de dépendance inconnue : " + wireName
                + " (attendues : api, implementation, compileOnly, runtimeOnly)");
    }

    /**
     * Indique si la dépendance participe au classpath de compilation du module dépendant.
     *
     * @return {@code true} pour {@code API}, {@code IMPLEMENTATION} et {@code COMPILE_ONLY}.
     */
    public boolean onCompileClasspath() {
        return this != RUNTIME_ONLY;
    }

    /**
     * Indique si la dépendance est transmise au classpath des consommateurs du module.
     *
     * @return {@code true} pour {@code API} uniquement.
     */
    public boolean exported() {
        return this == API;
    }

    /**
     * Les portées qui participent au classpath de compilation, dans l'ordre de déclaration
     * de l'énumération.
     *
     * @return la liste immuable.
     */
    public static List<DependencyScope> compileScopes() {
        return List.of(API, IMPLEMENTATION, COMPILE_ONLY);
    }
}
