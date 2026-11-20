package jo.forge.engine.state;

import jo.forge.engine.exec.UpToDateOracle;
import jo.forge.engine.tasks.Task;
import jo.forge.engine.tasks.TaskIO;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Magasin persistant des empreintes de tâches : un fichier par tâche sous
 * {@code state/tasks/}, nom <em>injectif</em> (hexadécimal du nom — constat C-3),
 * écriture atomique (temporaire + renommage — constats C-2 et C-13), en-tête magique
 * + version + somme de contrôle de la charge utile.
 *
 * <p>Corruption : un enregistrement illisible (magic, version inconnue, somme
 * invalide) est traité comme <em>périmé</em> — la tâche est ré-exécutée, jamais
 * déclarée à jour sur la foi d'un fichier douteux ; un avertissement est journalisé
 * via le consommateur fourni au constructeur.</p>
 */
public final class FingerprintStore implements UpToDateOracle {

    /** Magie d'en-tête des enregistrements. */
    private static final int MAGIC = 0x46525354; // "FRST"

    /** Version courante du format d'enregistrement. */
    private static final int FORMAT_VERSION = 1;

    private final Path tasksDir;
    private final Path projectRoot;
    private final WarningListener warnings;

    /** Consommateur d'avertissements (corruption, réinitialisation). */
    public interface WarningListener {

        /**
         * Appelé pour un avertissement non fatal du magasin.
         *
         * @param message l'avertissement.
         */
        void warn(String message);
    }

    /**
     * Crée le magasin.
     *
     * @param stateDir    répertoire d'état ({@code state/}) ; les enregistrements vont
     *                    dans {@code state/tasks/}.
     * @param projectRoot racine du projet (normalisation des chemins d'entrées).
     * @param warnings    consommateur d'avertissements (peut être nul).
     */
    public FingerprintStore(Path stateDir, Path projectRoot, WarningListener warnings) {
        this.tasksDir = stateDir.resolve("tasks");
        this.projectRoot = projectRoot;
        this.warnings = warnings == null ? message -> { } : warnings;
    }

    @Override
    public boolean isUpToDate(Task task) {
        Path enregistrement = fichierDe(task.name());
        if (!Files.exists(enregistrement)) {
            return false;
        }
        Enregistrement lu = lire(enregistrement, task.name());
        if (lu == null) {
            return false; // Corrompu : périmé par sécurité.
        }
        String actuelle;
        try {
            actuelle = TaskFingerprints.compute(task.io(), projectRoot);
        } catch (IllegalStateException illisible) {
            warnings.warn("Entrée illisible pour " + task.name() + " : "
                    + illisible.getMessage() + " — la tâche sera ré-exécutée");
            return false;
        }
        if (!lu.fingerprint.equals(actuelle)) {
            return false;
        }
        if (!TaskFingerprints.outputsExist(task.io(), projectRoot)) {
            warnings.warn("Sorties de " + task.name()
                    + " absentes — la tâche sera ré-exécutée");
            return false;
        }
        return true;
    }

    @Override
    public void recordSuccess(Task task) {
        String fingerprint;
        try {
            fingerprint = TaskFingerprints.compute(task.io(), projectRoot);
        } catch (IllegalStateException illisible) {
            // Le succès est enregistré sans empreinte exploitable : prochain passage
            // ré-exécuté (sûr, jamais faux up-to-date).
            warnings.warn("Empreinte impossible pour " + task.name() + " : "
                    + illisible.getMessage());
            return;
        }
        List<String> sorties = new ArrayList<>();
        task.io().outputPaths().forEach(sortie ->
                sorties.add(sortie.toString().replace('\\', '/')));
        ecrire(fichierDe(task.name()), new Enregistrement(fingerprint, sorties));
    }

    /**
     * Chemin d'enregistrement d'une tâche : encodage injectif du nom (hexadécimal),
     * deux noms distincts ne partagent jamais leur fichier (constat C-3).
     */
    private Path fichierDe(String nomTache) {
        StringBuilder hex = new StringBuilder();
        for (byte octet : nomTache.getBytes(StandardCharsets.UTF_8)) {
            hex.append(String.format("%02x", octet));
        }
        return tasksDir.resolve(hex + ".state");
    }

    /**
     * Lit un enregistrement ; {@code null} si corrompu (avec avertissement).
     */
    private Enregistrement lire(Path fichier, String nomTache) {
        try {
            byte[] contenu = Files.readAllBytes(fichier);
            DataInputStream in = new DataInputStream(
                    new ByteArrayInputStream(contenu));
            if (in.readInt() != MAGIC) {
                warnings.warn("Enregistrement de " + nomTache
                        + " corrompu (magie invalide) — ré-exécution");
                return null;
            }
            if (in.readInt() != FORMAT_VERSION) {
                warnings.warn("Enregistrement de " + nomTache
                        + " d'un format inconnu — ré-exécution");
                return null;
            }
            int longueur = in.readInt();
            if (longueur < 0 || longueur > contenu.length) {
                warnings.warn("Enregistrement de " + nomTache
                        + " corrompu (longueur invalide) — ré-exécution");
                return null;
            }
            byte[] charge = new byte[longueur];
            in.readFully(charge);
            String somme = hex(digest(charge));
            String attendu = in.readUTF();
            if (!somme.equals(attendu)) {
                warnings.warn("Enregistrement de " + nomTache
                        + " corrompu (somme invalide) — ré-exécution");
                return null;
            }
            DataInputStream chargeIn = new DataInputStream(
                    new ByteArrayInputStream(charge));
            return new Enregistrement(chargeIn.readUTF(), lireListe(chargeIn));
        } catch (IOException | RuntimeException echec) {
            warnings.warn("Enregistrement de " + nomTache + " illisible ("
                    + echec.getClass().getSimpleName() + ") — ré-exécution");
            return null;
        }
    }

    /**
     * Écrit un enregistrement atomiquement : sérialisation, somme, temporaire voisin,
     * renommage.
     */
    private void ecrire(Path fichier, Enregistrement enregistrement) {
        try {
            ByteArrayOutputStream charge = new ByteArrayOutputStream();
            DataOutputStream chargeOut = new DataOutputStream(charge);
            chargeOut.writeUTF(enregistrement.fingerprint);
            chargeOut.writeInt(enregistrement.outputs.size());
            for (String sortie : enregistrement.outputs) {
                chargeOut.writeUTF(sortie);
            }
            byte[] octets = charge.toByteArray();

            Files.createDirectories(tasksDir);
            ByteArrayOutputStream complet = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(complet);
            out.writeInt(MAGIC);
            out.writeInt(FORMAT_VERSION);
            out.writeInt(octets.length);
            out.write(octets);
            out.writeUTF(hex(digest(octets)));

            Path temporaire = fichier.resolveSibling(
                    fichier.getFileName() + ".tmp-" + Long.toHexString(System.nanoTime()));
            Files.write(temporaire, complet.toByteArray());
            Files.move(temporaire, fichier,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException echec) {
            warnings.warn("Écriture de l'état de tâche impossible ("
                    + echec.getMessage() + ") — le prochain build ré-exécutera");
        }
    }

    private static List<String> lireListe(DataInputStream in) throws IOException {
        int taille = in.readInt();
        if (taille < 0 || taille > 100_000) {
            throw new IOException("taille de liste invalide : " + taille);
        }
        List<String> valeurs = new ArrayList<>(taille);
        for (int i = 0; i < taille; i++) {
            valeurs.add(in.readUTF());
        }
        return valeurs;
    }

    private static byte[] digest(byte[] octets) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(octets);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 absent", impossible);
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

    /** Enregistrement sérialisé d'un passage réussi. */
    private record Enregistrement(String fingerprint, List<String> outputs) {
    }
}
