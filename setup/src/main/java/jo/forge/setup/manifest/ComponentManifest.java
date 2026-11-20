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
 * @param version      version du composant (numérotation sémantique ; les composants
 *                     d'outils la dérivent de leur contenu depuis v0.2.9 : elle ne
 *                     bouge que quand le contenu bouge).
 * @param size         taille de l'archive en octets (contrôle avant installation).
 * @param sha256       somme SHA-256 de l'archive, hexadécimale minuscule (64 caractères).
 * @param urls         URLs de téléchargement (miroirs), au moins une.
 * @param abis         ABI concernées ({@code all} pour un composant indépendant de l'ABI).
 * @param minAppVersion version minimale de la coquille compatible.
 * @param depends      identifiants des composants requis (installation topologique).
 * @param empreinte    empreinte de CONTENU (fichiers extraits), hexadécimale minuscule
 *                    (64 caractères) — ou {@code null} pour les manifestes antérieurs
 *                    à v0.2.9, qui ne peuvent pas être réutilisés par le cache local
 *                    (l'identité du contenu n'y est pas établie). Voir
 *                    {@link EmpreinteContenu}.
 */
public record ComponentManifest(String id, String version, long size, String sha256,
                                List<String> urls, List<String> abis, int minAppVersion,
                                List<String> depends, String empreinte) {

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
        if (empreinte != null && !empreinte.matches("[0-9a-f]{64}")) {
            throw new ManifestException("Empreinte de contenu invalide pour " + id);
        }
    }

    /**
     * Constructeur historique sans empreinte (manifestes antérieurs à v0.2.9, tests) :
     * le composant est valide mais jamais réutilisable par le cache local.
     *
     * @param id           identifiant stable du composant.
     * @param version      version du composant.
     * @param size         taille de l'archive en octets.
     * @param sha256       somme SHA-256 de l'archive.
     * @param urls         URLs de téléchargement.
     * @param abis         ABI concernées.
     * @param minAppVersion version minimale de la coquille.
     * @param depends      composants requis.
     */
    public ComponentManifest(String id, String version, long size, String sha256,
                             List<String> urls, List<String> abis, int minAppVersion,
                             List<String> depends) {
        this(id, version, size, sha256, urls, abis, minAppVersion, depends, null);
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
