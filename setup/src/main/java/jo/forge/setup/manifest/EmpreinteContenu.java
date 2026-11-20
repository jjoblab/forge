package jo.forge.setup.manifest;

import jo.forge.setup.util.Files2;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Empreinte de contenu d'un composant : l'identité des FICHIERS extraits, indépendante
 * de l'archive qui les porte (compression, horodatages, nom de fichier).
 *
 * <p>But (économie de données mobiles) : deux composants de manifestes différents —
 * par exemple {@code platform-34} publié en v0.2.9 puis re-référencé en v0.2.10 —
 * portent la MÊME empreinte quand leurs contenus extraits sont identiques octet pour
 * octet. L'installateur s'en sert pour réutiliser les fichiers déjà sur l'appareil au
 * lieu de re-télécharger (cache {@code forge/cache/composants/&lt;empreinte&gt;}), et
 * l'assembleur pour donner aux composants d'outils une version dérivée de leur
 * contenu : elle ne bouge QUE quand le contenu bouge.</p>
 *
 * <p>Définition canonique (identique des deux côtés de la chaîne, assembleur comme
 * installateur) : pour chaque fichier du composant trié par chemin, une ligne
 * {@code <sha256 du contenu>  <chemin>\n} ; l'empreinte est le SHA-256 du texte UTF-8
 * de la concaténation. Les chemins utilisent le séparateur {@code /} (noms d'entrées
 * zip d'un côté, chemins relatifs du répertoire moteur de l'autre — mêmes valeurs) ;
 * les répertoires ne comptent pas, seuls les fichiers.</p>
 */
public final class EmpreinteContenu {

    private EmpreinteContenu() {
        // Utilitaire statique : pas d'instanciation.
    }

    /**
     * Empreinte d'une archive de composant : chaque entrée non-répertoire contribue
     * sa somme, comme le fait l'installateur sur les fichiers extraits.
     *
     * @param archive archive zip du composant.
     * @return l'empreinte, hexadécimale minuscule (64 caractères).
     * @throws IOException si l'archive est illisible.
     */
    public static String deArchive(Path archive) throws IOException {
        TreeMap<String, String> sommes = new TreeMap<>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            for (ZipEntry entrée : zip.stream().toList()) {
                if (entrée.isDirectory()) {
                    continue;
                }
                sommes.put(entrée.getName(),
                        jo.forge.setup.util.Files2.sha256(zip.getInputStream(entrée)));
            }
        }
        return deCarte(sommes);
    }

    /**
     * Empreinte d'une carte chemin → somme (état installé, cache, test).
     *
     * @param fichiers chemins relatifs vers SHA-256 hexadécimal.
     * @return l'empreinte, hexadécimale minuscule (64 caractères).
     */
    public static String deCarte(Map<String, String> fichiers) {
        StringBuilder canonique = new StringBuilder();
        new TreeMap<>(fichiers).forEach((chemin, somme) ->
                canonique.append(somme).append("  ").append(chemin).append('\n'));
        return Files2.sha256(canonique.toString()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
