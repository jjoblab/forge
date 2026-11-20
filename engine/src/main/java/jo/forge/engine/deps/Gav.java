package jo.forge.engine.deps;

/**
 * Identité d'un artefact Maven : groupe + artefact (sans version).
 *
 * @param group    identifiant de groupe (ex. {@code com.exemple}).
 * @param artifact identifiant d'artefact (ex. {@code outils}).
 */
public record Gav(String group, String artifact) {

    /**
     * Valide l'identité.
     *
     * @throws IllegalArgumentException si un champ est vide.
     */
    public Gav {
        if (group == null || group.isBlank() || artifact == null || artifact.isBlank()) {
            throw new IllegalArgumentException("Identité Maven incomplète : "
                    + group + ":" + artifact);
        }
    }

    @Override
    public String toString() {
        return group + ":" + artifact;
    }
}
