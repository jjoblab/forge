package jo.forge.setup.manifest;

import java.util.List;

/**
 * Manifeste signé décrivant une version complète du moteur : ses composants, leurs
 * sommes et leurs dépendances.
 *
 * @param manifestVersion version du format du manifeste (1 aujourd'hui).
 * @param engineVersion   version du moteur décrit.
 * @param minAppVersion   version minimale de la coquille capable d'installer ce moteur.
 * @param publishedAt     date de publication (ISO-8601, information).
 * @param components      composants, au moins un, identifiants uniques.
 */
public record EngineManifest(int manifestVersion, String engineVersion, int minAppVersion,
                             String publishedAt, List<ComponentManifest> components) {

    /**
     * Valide le manifeste.
     *
     * @throws ManifestException si le format, la version ou les composants sont invalides.
     */
    public EngineManifest {
        if (manifestVersion != 1) {
            throw new ManifestException(
                    "Version de manifeste non supportée : " + manifestVersion);
        }
        if (engineVersion == null || engineVersion.isBlank()) {
            throw new ManifestException("Version du moteur manquante");
        }
        if (components == null || components.isEmpty()) {
            throw new ManifestException("Manifeste sans composant");
        }
        components = List.copyOf(components);
        long uniques = components.stream().map(ComponentManifest::id).distinct().count();
        if (uniques != components.size()) {
            throw new ManifestException("Identifiants de composants en double");
        }
        // Dépendances : chaque référence doit désigner un composant présent.
        for (ComponentManifest composant : components) {
            for (String dependance : composant.depends()) {
                if (components.stream().noneMatch(c -> c.id().equals(dependance))) {
                    throw new ManifestException("Composant " + composant.id()
                            + " dépend de l'inconnu " + dependance);
                }
            }
        }
    }

    /**
     * Retrouve un composant par identifiant.
     *
     * @param id identifiant recherché.
     * @return le composant correspondant.
     * @throws ManifestException si aucun composant ne porte cet identifiant.
     */
    public ComponentManifest component(String id) {
        return components.stream()
                .filter(composant -> composant.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new ManifestException("Composant inconnu : " + id));
    }

    /**
     * Liste les composants applicables à une ABI (composants « all » inclus).
     *
     * @param abi ABI de l'appareil.
     * @return les composants applicables, dans l'ordre du manifeste.
     */
    public List<ComponentManifest> forAbi(String abi) {
        return components.stream().filter(composant -> composant.matchesAbi(abi)).toList();
    }
}
