package jo.forge.setup.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Utilitaires de fichiers de l'installateur : sommes SHA-256, écritures atomiques,
 * suppressions récursives sûres.
 *
 * <p>Écriture atomique : le contenu part dans un fichier temporaire voisin, est forcé
 * sur disque ({@code force}), puis renommé — un observateur ne voit jamais un état à
 * moitié écrit, et un écroulement laisse au pire un temporaire, jamais l'état corrompu
 * (engagement D-12 / constats C-2 et C-13 de la revue).</p>
 */
public final class Files2 {

    private Files2() {
        // Utilitaire statique : pas d'instanciation.
    }

    /**
     * Calcule la somme SHA-256 d'un fichier, en flux (aucun chargement complet).
     *
     * @param fichier fichier à hacher.
     * @return la somme en hexadécimal minuscule.
     * @throws IOException en cas d'erreur de lecture.
     */
    public static String sha256(Path fichier) throws IOException {
        MessageDigest digest = nouveauDigest();
        try (InputStream entree = Files.newInputStream(fichier)) {
            byte[] tampon = new byte[64 * 1024];
            int lus;
            while ((lus = entree.read(tampon)) >= 0) {
                digest.update(tampon, 0, lus);
            }
        }
        return hex(digest.digest());
    }

    /**
     * Calcule la somme SHA-256 d'un flux (aucun chargement complet — entrée zip
     * d'archive de composant, par exemple).
     *
     * @param entree flux à hacher (fermé par l'appelant).
     * @return la somme en hexadécimal minuscule.
     * @throws IOException en cas d'erreur de lecture.
     */
    public static String sha256(InputStream entree) throws IOException {
        MessageDigest digest = nouveauDigest();
        byte[] tampon = new byte[64 * 1024];
        int lus;
        while ((lus = entree.read(tampon)) >= 0) {
            digest.update(tampon, 0, lus);
        }
        return hex(digest.digest());
    }

    /**
     * Calcule la somme SHA-256 d'octets en mémoire (textes canoniques, empreintes
     * de contenu).
     *
     * @param octets octets à hacher.
     * @return la somme en hexadécimal minuscule.
     */
    public static String sha256(byte[] octets) {
        return hex(nouveauDigest().digest(octets));
    }

    /**
     * Calcule la somme SHA-256 en échouant bruyamment : un fichier momentanément
     * illisible est une erreur, jamais un hachage silencieusement vide (leçon du
     * constat C-4 de la revue — pas de digest vide sur erreur d'E/S).
     *
     * @param fichier fichier à hacher.
     * @return la somme en hexadécimal minuscule.
     * @throws IllegalStateException si la lecture échoue.
     */
    public static String sha256Strict(Path fichier) {
        try {
            return sha256(fichier);
        } catch (IOException illisible) {
            throw new IllegalStateException(
                    "Fichier illisible pendant le hachage : " + fichier, illisible);
        }
    }

    /**
     * Écrit un fichier de façon atomique (temporaire voisin + renommage).
     *
     * @param cible   fichier final voulu.
     * @param contenu octets à écrire.
     * @throws IOException en cas d'erreur d'écriture ou de renommage.
     */
    public static void ecrireAtomique(Path cible, byte[] contenu) throws IOException {
        Path temporaire = cible.resolveSibling(cible.getFileName() + ".tmp-"
                + Long.toHexString(System.nanoTime()));
        try {
            java.io.OutputStream sortie = Files.newOutputStream(temporaire);
            try (java.io.OutputStream flux = sortie) {
                flux.write(contenu);
                flux.flush();
            }
            // Force sur disque avant le renommage : l'état survit à une coupure brutale.
            try (java.nio.channels.FileChannel canal = java.nio.channels.FileChannel.open(
                    temporaire, java.nio.file.StandardOpenOption.READ)) {
                canal.force(true);
            }
            Files.move(temporaire, cible, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporaire);
        }
    }

    /**
     * Supprime un répertoire entier, récursivement (les échecs individuels sont
     * tolérés et consignés par l'appelant via la valeur de retour).
     *
     * @param repertoire répertoire à supprimer.
     * @return {@code true} si tout a été supprimé.
     */
    public static boolean supprimerRecursivement(Path repertoire) {
        if (repertoire == null || !Files.exists(repertoire)) {
            return true;
        }
        try (Stream<Path> chemins = Files.walk(repertoire)) {
            chemins.sorted(Comparator.reverseOrder()).forEach(chemin -> {
                try {
                    Files.deleteIfExists(chemin);
                } catch (IOException ignored) {
                    // Fichier occupé : la suppression du parent échouera et remontera.
                }
            });
        } catch (IOException echec) {
            return false;
        }
        return !Files.exists(repertoire);
    }

    /**
     * Crée un nouveau moteur de SHA-256.
     *
     * @return le moteur de hachage.
     */
    public static MessageDigest nouveauDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 absent de la plate-forme", impossible);
        }
    }

    /**
     * Encode des octets en hexadécimal minuscule.
     *
     * @param octets octets à encoder.
     * @return la représentation hexadécimale.
     */
    public static String hex(byte[] octets) {
        StringBuilder texte = new StringBuilder(octets.length * 2);
        for (byte octet : octets) {
            texte.append(Character.forDigit((octet >> 4) & 0xF, 16));
            texte.append(Character.forDigit(octet & 0xF, 16));
        }
        return texte.toString();
    }
}
