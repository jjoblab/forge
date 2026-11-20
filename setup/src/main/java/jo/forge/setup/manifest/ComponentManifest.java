package jo.forge.setup.manifest;

import java.util.List;

/**
 * Description d'un composant du moteur dans le manifeste signé.
 *
 * <p>Le contrôle « HTTPS obligatoire » est fait à l'analyse du manifeste
 * ({@link ManifestCodec}) et au téléchargement ({code Downloader}) — les deux
 * frontières de confiance. Le constructeur programme (tests, outils) n'impose pas le
 * schéma.</p>
 *
 * @param id           identifiant stable du composant (ex. {@code engine-core},
 *                     {@code aapt2-arm64-v8a}).
 * @param version      version du composant (numérotation sémantique).
 * @param size         taille de l'archive en octets (contrôle avant installation).
 * @param sha256       somme SHA-256 de l'archive, hexadécimale minuscule (64 caractères).
 * @param urls         URLs de téléchargement (miroirs), au moins une.
 * @param abis         ABI concernées ({@code all} pour un composant indépendant de l'ABI).
 * @param minAppVersion version minimale de la coquille compatible.
 * @param depends      identifiants des composants requis (installation topologique).
 */
public record ComponentManifest(String id, String version, long size, String sha256,
                                List<String> urls, List<String> abis, int minAppVersion,
                                List<String> depends) {

    /**
     * Valide le composant.
     *
     * @throws ManifestException si un champ est manquant ou incohérent.
     */
    public ComponentManifest {
        if (id == null || id.isBlank()) {
            throw new ManifestException("Identifiant de composant manquant");
        }
        if (version == null || version.isBlank()) {
            throw new ManifestException("Version manquante pour " + id);
        }
        if (size <= 0) {
            throw new ManifestException("Taille invalide pour " + id + " : " + size);
        }
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new ManifestException("SHA-256 invalide pour " + id);
        }
        if (urls == null || urls.isEmpty()) {
            throw new ManifestException("Aucune URL pour " + id);
        }
        urls = List.copyOf(urls);
        abis = abis == null ? List.of("all") : List.copyOf(abis);
        depends = depends == null ? List.of() : List.copyOf(depends);
    }

    /**
     * Indique si le composant s'applique à l'ABI donnée.
     *
     * @param abi ABI de l'appareil (ex. {@code arm64-v8a}).
     * @return {@code true} si le composant est {@code all} ou vise cette ABI.
     */
    public boolean matchesAbi(String abi) {
        return abis.contains("all") || abis.contains(abi);
    }
}
