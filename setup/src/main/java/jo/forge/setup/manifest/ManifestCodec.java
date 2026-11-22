package jo.forge.setup.manifest;

import jo.forge.json.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Lecture et écriture du manifeste : JSON vers {@link EngineManifest} et réciproquement.
 *
 * <p>La lecture est défensive : champs obligatoires vérifiés, types contrôlés, dépendances
 * résolues contre les identifiants présents (aucune référence pendante).</p>
 */
public final class ManifestCodec {

    private ManifestCodec() {
        // Utilitaire statique : pas d'instanciation.
    }

    /**
     * Analyse le contenu d'un manifeste.
     *
     * @param json texte JSON du manifeste.
     * @return le manifeste validé.
     * @throws ManifestException si le contenu est mal formé ou incohérent.
     */
    public static EngineManifest parse(String json) {
        Object racine;
        try {
            racine = Json.parse(json);
        } catch (jo.forge.json.JsonException malformed) {
            throw new ManifestException("Manifeste illisible : " + malformed.getMessage());
        }
        if (!(racine instanceof Map<?, ?> objet)) {
            throw new ManifestException("Le manifeste doit être un objet JSON");
        }
        int manifestVersion = entier(objet, "manifestVersion");
        String engineVersion = chaine(objet, "engineVersion");
        int minAppVersion = entier(objet, "minAppVersion");
        String publishedAt = chaine(objet, "publishedAt");
        // Séquence anti-recul (constat M-2) : optionnelle — les manifestes
        // antérieurs au mécanisme (et ceux produits sans numéro) valent 0.
        long séquence = 0;
        Object séquenceBrute = objet.get("séquence");
        if (séquenceBrute instanceof Number nombre) {
            séquence = nombre.longValue();
        } else if (séquenceBrute != null) {
            throw new ManifestException("Champ séquence non numérique");
        }
        Object brut = objet.get("components");
        if (!(brut instanceof List<?> liste) || liste.isEmpty()) {
            throw new ManifestException("components doit être un tableau non vide");
        }
        List<ComponentManifest> composants = new ArrayList<>();
        for (Object element : liste) {
            if (!(element instanceof Map<?, ?> composant)) {
                throw new ManifestException("Chaque composant doit être un objet JSON");
            }
            composants.add(parseComponent(composant));
        }
        return new EngineManifest(manifestVersion, engineVersion,
                minAppVersion, publishedAt, composants, séquence);
    }

    /**
     * Sérialise un manifeste (ordre des champs stable, pour la relecture et le diff).
     * L'empreinte de contenu, présente depuis v0.2.9, n'est écrite que quand elle
     * existe — les manifestes sans empreinte restent conformes au format lu par les
     * coquilles antérieures (les champs inconnus sont ignorés à l'analyse).
     *
     * @param manifest manifeste à sérialiser.
     * @return le texte JSON correspondant.
     */
    public static String write(EngineManifest manifest) {
        List<Object> composants = new ArrayList<>();
        for (ComponentManifest composant : manifest.components()) {
            Map<String, Object> objet = new java.util.LinkedHashMap<>();
            objet.put("id", composant.id());
            objet.put("version", composant.version());
            objet.put("size", composant.size());
            objet.put("sha256", composant.sha256());
            objet.put("urls", composant.urls());
            objet.put("abis", composant.abis());
            objet.put("minAppVersion", composant.minAppVersion());
            objet.put("depends", composant.depends());
            if (composant.empreinte() != null) {
                objet.put("empreinte", composant.empreinte());
            }
            composants.add(objet);
        }
        Map<String, Object> racine = new java.util.LinkedHashMap<>();
        racine.put("manifestVersion", manifest.manifestVersion());
        racine.put("engineVersion", manifest.engineVersion());
        racine.put("minAppVersion", manifest.minAppVersion());
        racine.put("publishedAt", manifest.publishedAt());
        racine.put("components", composants);
        if (manifest.séquence() > 0) {
            // Séquence anti-recul (constat M-2) : écrite seulement quand elle
            // existe — les sorties des manifestes sans séquence restent octet
            // pour octet celles d'avant (tests et coquilles anciennes).
            racine.put("séquence", manifest.séquence());
        }
        return Json.write(racine);
    }

    /**
     * Analyse un composant individuel. Le schéma HTTPS est vérifié ici : c'est la
     * frontière de confiance (le constructeur programme n'impose rien).
     */
    private static ComponentManifest parseComponent(Map<?, ?> composant) {
        String id = chaine(composant, "id");
        String version = chaine(composant, "version");
        long size = entier(composant, "size");
        String sha256 = chaine(composant, "sha256");
        List<String> urls = listeDeChaines(composant.get("urls"), "urls de " + id);
        for (String url : urls) {
            if (!url.startsWith("https://")) {
                throw new ManifestException(
                        "URL non HTTPS refusée pour " + id + " : " + url);
            }
        }
        List<String> abis = listeDeChaines(composant.get("abis"), "abis de " + id);
        int minAppVersion = entier(composant, "minAppVersion");
        List<String> depends = listeDeChaines(composant.get("depends"), "depends de " + id);
        // Empreinte de contenu : optionnelle (manifestes antérieurs à v0.2.9),
        // contrôlée quand présente — une empreinte mal formée est un manifeste
        // mal construit, pas un cas tolérable.
        String empreinte = composant.get("empreinte") == null
                ? null : chaine(composant, "empreinte");
        return new ComponentManifest(id, version, size, sha256, urls, abis, minAppVersion,
                depends, empreinte);
    }

    private static String chaine(Map<?, ?> objet, String cle) {
        Object valeur = objet.get(cle);
        if (!(valeur instanceof String texte) || texte.isBlank()) {
            throw new ManifestException("Champ " + cle + " manquant ou invalide");
        }
        return texte;
    }

    private static int entier(Map<?, ?> objet, String cle) {
        Object valeur = objet.get(cle);
        if (!(valeur instanceof Number nombre)) {
            throw new ManifestException("Champ " + cle + " manquant ou non numérique");
        }
        return nombre.intValue();
    }

    private static List<String> listeDeChaines(Object valeur, String description) {
        if (valeur == null) {
            return List.of();
        }
        if (!(valeur instanceof List<?> liste)) {
            throw new ManifestException(description + " doit être un tableau");
        }
        List<String> chaines = new ArrayList<>();
        for (Object element : liste) {
            if (!(element instanceof String texte)) {
                throw new ManifestException(description + " contient un non-texte");
            }
            chaines.add(texte);
        }
        return chaines;
    }
}
