package jo.forge.engine.state;

import jo.forge.engine.tasks.TaskIO;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Calcul des empreintes de tâches (décision D-12) : SHA-256 sur la version du code de
 * la tâche, les propriétés, et le <em>contenu</em> de chaque entrée.
 *
 * <p>Corrige les constats de la revue : l'empreinte d'un classpath ou d'une sortie
 * sœurs inclut un digest de contenu, pas seulement le chemin (C-6, C-18) ; une erreur
 * de lecture invalide au lieu de hacher silencieusement vide (C-4) ; les liens
 * symboliques sont refusés avec diagnostic plutôt qu'ignorés (C-12) ; les répertoires
 * sont hachés en flux avec ordre déterministe (tri par chemin relatif).</p>
 */
public final class TaskFingerprints {

    private TaskFingerprints() {
        // Utilitaire statique : pas d'instanciation.
    }

    /**
     * Calcule l'empreinte d'une tâche à partir de sa déclaration.
     *
     * @param io           déclaration (entrées, propriétés, version).
     * @param projectRoot  racine servant à normaliser les chemins relatifs.
     * @return l'empreinte en hexadécimal minuscule.
     * @throws IllegalStateException si une entrée est illisible (le magasin doit alors
     *                               traiter la tâche comme périmée — jamais l'inverse).
     */
    public static String compute(TaskIO io, Path projectRoot) {
        MessageDigest digest = nouveauDigest();
        maj(digest, "version", io.taskVersion());

        // Propriétés : ordre déterministe (tri par clé).
        Map<String, String> proprieties = new TreeMap<>(io.properties());
        for (Map.Entry<String, String> entry : proprieties.entrySet()) {
            maj(digest, "prop:" + entry.getKey(), entry.getValue());
        }

        // Fichiers d'entrée : chemin normalisé + contenu.
        for (Path fichier : io.inputFiles()) {
            hasherFichier(digest, "file", resoudre(fichier, projectRoot));
        }
        // Répertoires d'entrée : parcours trié, chemin relatif + contenu de chaque
        // fichier régulier (pas de lien symbolique : refus explicite, constat C-12).
        for (Path repertoire : io.inputDirs()) {
            hasherRepertoire(digest, resoudre(repertoire, projectRoot));
        }
        return hex(digest.digest());
    }

    /**
     * Vérifie que les sorties déclarées existent toujours (oracle up-to-date).
     *
     * @param io          déclaration de la tâche.
     * @param projectRoot racine de normalisation.
     * @return {@code true} si toutes les sorties existent (ou aucune déclarée).
     */
    public static boolean outputsExist(TaskIO io, Path projectRoot) {
        for (Path sortie : io.outputPaths()) {
            if (!Files.exists(resoudre(sorte(sortie), projectRoot))) {
                return false;
            }
        }
        return true;
    }

    private static Path sorte(Path sortie) {
        return sortie;
    }

    private static void hasherFichier(MessageDigest digest, String prefixe, Path fichier) {
        if (Files.isSymbolicLink(fichier)) {
            throw new IllegalStateException(
                    "Lien symbolique refusé dans les entrées : " + fichier
                            + " (remplacez-le par un fichier réel)");
        }
        if (!Files.exists(fichier)) {
            // Entrée absente : empreinte du vide — la tâche reste valide (fichier
            // optionnel) mais l'ajout du fichier changera l'empreinte.
            maj(digest, prefixe + ":" + normaliser(fichier), "\u0000absent");
            return;
        }
        if (!Files.isRegularFile(fichier)) {
            throw new IllegalStateException("Entrée non régulière : " + fichier);
        }
        maj(digest, prefixe + ":" + normaliser(fichier), digestFichier(fichier));
    }

    private static void hasherRepertoire(MessageDigest digest, Path repertoire) {
        if (!Files.exists(repertoire)) {
            maj(digest, "dir:" + normaliser(repertoire), "\u0000absent");
            return;
        }
        List<Path> fichiers = new ArrayList<>();
        try (var chemins = Files.walk(repertoire)) {
            chemins.filter(Files::isRegularFile).forEach(fichiers::add);
        } catch (IOException ioError) {
            throw new IllegalStateException("Répertoire d'entrée illisible : "
                    + repertoire + " (" + ioError.getMessage() + ")");
        }
        // Ordre déterministe : tri par chemin relatif.
        fichiers.sort((a, b) -> repertoire.relativize(a).toString()
                .compareTo(repertoire.relativize(b).toString()));
        for (Path fichier : fichiers) {
            if (Files.isSymbolicLink(fichier)) {
                throw new IllegalStateException(
                        "Lien symbolique refusé dans les entrées : " + fichier);
            }
            maj(digest, "dir:" + normaliser(repertoire),
                    repertoire.relativize(fichier) + ":" + digestFichier(fichier));
        }
    }

    private static String digestFichier(Path fichier) {
        try {
            MessageDigest digest = nouveauDigest();
            try (var entree = Files.newInputStream(fichier)) {
                byte[] tampon = new byte[64 * 1024];
                int lus;
                while ((lus = entree.read(tampon)) >= 0) {
                    digest.update(tampon, 0, lus);
                }
            }
            return hex(digest.digest());
        } catch (IOException illisible) {
            // Constat C-4 : jamais de digest silencieusement vide sur erreur de lecture.
            throw new IllegalStateException("Fichier d'entrée illisible : " + fichier
                    + " (" + illisible.getMessage() + ")");
        }
    }

    private static Path resoudre(Path chemin, Path projectRoot) {
        return chemin.isAbsolute() ? chemin : projectRoot.resolve(chemin);
    }

    private static String normaliser(Path chemin) {
        return chemin.toString().replace('\\', '/');
    }

    private static void maj(MessageDigest digest, String cle, String valeur) {
        digest.update(cle.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        digest.update((byte) 0x1F);
        digest.update(valeur.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        digest.update((byte) 0x1E);
    }

    private static MessageDigest nouveauDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 absent de la plate-forme", impossible);
        }
    }

    private static String hex(byte[] octets) {
        StringBuilder texte = new StringBuilder(octets.length * 2);
        for (byte octet : octets) {
            texte.append(Character.forDigit((octet >> 4) & 0xF, 16));
            texte.append(Character.forDigit(octet & 0xF, 16));
        }
        return texte.toString();
    }
}
