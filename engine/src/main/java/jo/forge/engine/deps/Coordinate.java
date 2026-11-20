package jo.forge.engine.deps;

/**
 * Coordonnée complète d'un artefact Maven : groupe, artefact, version.
 *
 * <p>Le classifieur (artefacts secondaires comme {@code -sources} ou
 * {@code -javadoc}) n'est pas supporté à cette étape — écart consigné dans
 * {@code docs/limites-et-suite.md}.</p>
 *
 * @param group    identifiant de groupe.
 * @param artifact identifiant d'artefact.
 * @param version  version.
 */
public record Coordinate(String group, String artifact, String version) {

    /**
     * Valide la coordonnée.
     *
     * @throws IllegalArgumentException si un champ est vide.
     */
    public Coordinate {
        if (group == null || group.isBlank() || artifact == null || artifact.isBlank()
                || version == null || version.isBlank()) {
            throw new IllegalArgumentException("Coordonnée Maven incomplète : "
                    + group + ":" + artifact + ":" + version);
        }
    }

    /**
     * Identité (groupe + artefact) de cette coordonnée.
     *
     * @return l'identité.
     */
    public Gav gav() {
        return new Gav(group, artifact);
    }

    /**
     * Analyse une coordonnée texte {@code groupe:artefact:version}.
     *
     * @param texte la coordonnée.
     * @return la coordonnée parsée.
     * @throws IllegalArgumentException si le format est incorrect.
     */
    public static Coordinate parse(String texte) {
        String[] parties = texte.split(":");
        if (parties.length != 3) {
            throw new IllegalArgumentException(
                    "Coordonnée Maven mal formée : \"" + texte + "\"");
        }
        return new Coordinate(parties[0], parties[1], parties[2]);
    }

    @Override
    public String toString() {
        return group + ":" + artifact + ":" + version;
    }
}
