package jo.forge.setup.install;

import jo.forge.setup.download.InstallException;
import jo.forge.json.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * État d'installation persisté : quel moteur est en place, de quels composants il est
 * fait, et la somme de chaque fichier extrait (base de la réparation).
 *
 * <p>Écrit et relu de façon atomique ({@code Files2.ecrireAtomique}) ; un fichier
 * illisible est traité comme « aucun moteur installé » — jamais comme un moteur valide
 * (on ne charge rien d'incontrôlé).</p>
 *
 * @param formatVersion version du format (1).
 * @param engineVersion version du moteur installé.
 * @param installedAt   date d'installation (ISO-8601, information).
 * @param components    composants installés, avec fichiers et sommes.
 * @param dernièreSéquence séquence anti-recul (constat M-2) du manifeste installé :
 *                        tout manifeste signé d'une séquence STRICTEMENT inférieure
 *                        sera refusé ; 0 = état antérieur au mécanisme.
 */
public record InstalledState(int formatVersion, String engineVersion, String installedAt,
                             List<InstalledComponent> components, long dernièreSéquence) {

    /** Version courante du format. */
    public static final int FORMAT_VERSION = 1;

    /**
     * Construit un état SANS séquence (états antérieurs au mécanisme anti-recul).
     *
     * @param formatVersion version du format.
     * @param engineVersion version du moteur.
     * @param installedAt   date d'installation.
     * @param components    composants installés.
     */
    public InstalledState(int formatVersion, String engineVersion, String installedAt,
                          List<InstalledComponent> components) {
        this(formatVersion, engineVersion, installedAt, components, 0L);
    }

    /**
     * Valide l'état.
     *
     * @throws InstallException si les champs obligatoires manquent.
     */
    public InstalledState {
        if (formatVersion != FORMAT_VERSION) {
            throw new InstallException("Format d'état inconnu : " + formatVersion);
        }
        if (engineVersion == null || engineVersion.isBlank()) {
            throw new InstallException("Version du moteur absente de l'état");
        }
        if (dernièreSéquence < 0) {
            throw new InstallException("Séquence d'état négative : " + dernièreSéquence);
        }
        components = components == null ? List.of() : List.copyOf(components);
    }

    /**
     * Recherche un composant installé par identifiant.
     *
     * @param id identifiant du composant.
     * @return le composant installé, ou {@code null}.
     */
    public InstalledComponent component(String id) {
        return components.stream()
                .filter(composant -> composant.id().equals(id))
                .findFirst().orElse(null);
    }

    /**
     * Sérialise l'état en JSON.
     *
     * @return le texte JSON à écrire.
     */
    public String toJson() {
        List<Object> composants = new ArrayList<>();
        for (InstalledComponent composant : components) {
            Map<String, Object> objet = new LinkedHashMap<>();
            objet.put("id", composant.id());
            objet.put("version", composant.version());
            objet.put("files", new LinkedHashMap<>(composant.files()));
            composants.add(objet);
        }
        Map<String, Object> racine = new LinkedHashMap<>();
        racine.put("formatVersion", formatVersion);
        racine.put("engineVersion", engineVersion);
        racine.put("installedAt", installedAt);
        racine.put("components", composants);
        if (dernièreSéquence > 0) {
            // Séquence anti-recul (constat M-2) : écrite seulement quand elle
            // existe — les états sans séquence restent conformes au format lu par
            // les coquilles antérieures (les champs inconnus sont ignorés).
            racine.put("dernièreSéquence", dernièreSéquence);
        }
        return Json.write(racine);
    }

    /**
     * Lit l'état depuis un fichier ; un fichier absent ou illisible donne {@code null}
     * (aucun moteur installé).
     *
     * @param fichier chemin de {@code installed.json}.
     * @return l'état lu, ou {@code null}.
     * @throws InstallException si le contenu existe mais est incohérent (on préfère
     *                          échouer bruyamment plutôt que d'accepter un état douteux).
     */
    public static InstalledState fromFile(Path fichier) {
        if (!Files.exists(fichier)) {
            return null;
        }
        String texte;
        try {
            texte = Files.readString(fichier);
        } catch (IOException illisible) {
            return null; // État corrompu : considéré comme absent (aucun moteur).
        }
        Object racine;
        try {
            racine = Json.parse(texte);
        } catch (RuntimeException malforme) {
            throw new InstallException("Fichier d'état illisible : " + malforme.getMessage());
        }
        if (!(racine instanceof Map<?, ?> objet)) {
            throw new InstallException("Fichier d'état inattendu");
        }
        Object composantsBruts = objet.get("components");
        if (!(composantsBruts instanceof List<?> liste)) {
            throw new InstallException("Composants absents du fichier d'état");
        }
        List<InstalledComponent> composants = ((List<?>) liste).stream()
                .map(element -> lireComponent((Map<?, ?>) element))
                .toList();
        // Séquence anti-recul (constat M-2) : optionnelle — absente des états
        // antérieurs au mécanisme.
        long séquence = objet.get("dernièreSéquence") instanceof Number nombre
                ? nombre.longValue() : 0L;
        return new InstalledState(
                ((Number) objet.get("formatVersion")).intValue(),
                (String) objet.get("engineVersion"),
                String.valueOf(objet.get("installedAt")),
                composants, séquence);
    }

    @SuppressWarnings("unchecked")
    private static InstalledComponent lireComponent(Map<?, ?> objet) {
        Map<String, String> fichiers = new LinkedHashMap<>();
        Object fichiersBruts = objet.get("files");
        if (fichiersBruts instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                fichiers.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
        }
        return new InstalledComponent((String) objet.get("id"),
                (String) objet.get("version"), fichiers);
    }

    /**
     * Composant installé : identité et sommes par fichier extrait.
     *
     * @param id      identifiant du composant.
     * @param version version du composant.
     * @param files   chemins relatifs (dans le répertoire moteur) vers somme SHA-256.
     */
    public record InstalledComponent(String id, String version, Map<String, String> files) {

        /**
         * Valide le composant installé.
         */
        public InstalledComponent {
            if (id == null || id.isBlank()) {
                throw new InstallException("Composant installé sans identifiant");
            }
            files = files == null ? Map.of() : Map.copyOf(files);
        }
    }
}
