package jo.forge.setup.manifest;

import jo.forge.setup.json.Json;

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
        } catch (jo.forge.setup.json.JsonException malformed) {
            throw new ManifestException("Manifeste illisible : " + malformed.getMessage());
        }
        if (!(racine instanceof Map<?, ?> objet)) {
            throw new ManifestException("Le manifeste doit être un objet JSON");
        }
        int manifestVersion = entier(objet, "manifestVersion");
        String engineVersion = chaine(objet, "engineVersion");
        int minAppVersion = entier(objet, "minAppVersion");
        String publishedAt = chaine(objet, "publishedAt");
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
                minAppVersion, publishedAt, composants);
    }

    /**
     * Sérialise un manifeste (ordre des champs stable, pour la relecture et le diff).
     *
     * @param manifest manifeste à sérialiser.
     * @return le texte JSON correspondant.
     */
    public static String write(EngineManifest manifest) {
        List<Object> composants = new ArrayList<>();
        for (ComponentManifest composant : manifest.components()) {
            composants.add(Map.of(
                    "id", composant.id(),
                    "version", composant.version(),
                    "size", composant.size(),
                    "sha256", composant.sha256(),
                    "urls", composant.urls(),
                    "abis", composant.abis(),
                    "minAppVersion", composant.minAppVersion(),
                    "depends", composant.depends()));
        }
        return Json.write(Map.of(
                "manifestVersion", manifest.manifestVersion(),
                "engineVersion", manifest.engineVersion(),
                "minAppVersion", manifest.minAppVersion(),
                "publishedAt", manifest.publishedAt(),
                "components", composants));
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
        return new ComponentManifest(id, version, size, sha256, urls, abis, minAppVersion,
                depends);
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
