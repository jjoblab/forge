package jo.forge.setup.install;

import jo.forge.setup.download.InstallException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Extraction d'archives ZIP avec garde anti-traversée de chemin (zip-slip) et plafond
 * de taille (protection contre les bombes zip).
 *
 * <p>Chaque nom d'entrée est validé : refus des chemins absolus, des segments
 * {@code ..}, des composants vides ou « disque » ; la cible canonisée doit rester sous
 * le répertoire d'extraction. Un plafond de taille totale protège contre les archives
 * explosives. L'extraction est en flux : aucun chargement complet en mémoire
 * (engagement de sobriété mémoire du cahier des charges).</p>
 */
public final class ArchiveExtractor {

    /** Plafond par défaut de taille totale décompressée : 512 Mo. */
    public static final long DEFAULT_MAX_TOTAL_BYTES = 512L * 1024 * 1024;

    private final long maxTotalBytes;

    /**
     * Crée un extracteur avec le plafond par défaut.
     */
    public ArchiveExtractor() {
        this(DEFAULT_MAX_TOTAL_BYTES);
    }

    /**
     * Crée un extracteur avec un plafond explicite.
     *
     * @param maxTotalBytes taille totale décompressée maximale acceptée.
     */
    public ArchiveExtractor(long maxTotalBytes) {
        this.maxTotalBytes = maxTotalBytes;
    }

    /**
     * Extrait une archive ZIP dans un répertoire cible (créé s'il n'existe pas).
     *
     * @param archive  fichier ZIP à extraire.
     * @param cible    répertoire de destination.
     * @throws InstallException si une entrée tente de sortir du répertoire cible
     *                          (zip-slip), si le plafond de taille est dépassé ou en cas
     *                          d'erreur d'entrée/sortie.
     */
    public void extract(Path archive, Path cible) {
        long total = 0;
        try {
            Files.createDirectories(cible);
            try (InputStream fichier = Files.newInputStream(archive);
                 ZipInputStream zip = new ZipInputStream(fichier)) {
                ZipEntry entree;
                while ((entree = zip.getNextEntry()) != null) {
                    Path destination = validerEntree(entree, cible);
                    if (entree.isDirectory()) {
                        Files.createDirectories(destination);
                    } else {
                        Files.createDirectories(destination.getParent());
                        total += copier(zip, destination);
                        if (total > maxTotalBytes) {
                            throw new InstallException("Archive trop volumineuse après "
                                    + "décompression (plafond " + maxTotalBytes + " octets)");
                        }
                    }
                    zip.closeEntry();
                }
            }
        } catch (IOException ioError) {
            throw new InstallException("Extraction impossible : " + ioError.getMessage(),
                    ioError);
        }
    }

    /**
     * Valide le nom d'une entrée et retourne sa destination sûre.
     */
    private Path validerEntree(ZipEntry entree, Path cible) {
        String nom = entree.getName();
        if (nom == null || nom.isEmpty()) {
            throw new InstallException("Entrée d'archive sans nom");
        }
        if (nom.startsWith("/") || nom.contains(":") || nom.startsWith("\\")) {
            throw new InstallException("Chemin d'entrée absolu refusé : " + nom);
        }
        Path relatif = Path.of(nom.replace('\\', '/'));
        for (Path segment : relatif) {
            String texte = segment.toString();
            if (texte.isEmpty() || texte.equals("..")) {
                throw new InstallException("Traversée de chemin refusée dans l'archive : "
                        + nom);
            }
        }
        Path destination = cible.resolve(relatif).normalize();
        if (!destination.startsWith(cible.normalize())) {
            throw new InstallException("Entrée d'archive hors cible refusée : " + nom);
        }
        return destination;
    }

    /**
     * Copie le flux courant vers sa destination.
     */
    private long copier(ZipInputStream zip, Path destination) throws IOException {
        long copies = 0;
        try (java.io.OutputStream sortie = Files.newOutputStream(destination)) {
            byte[] tampon = new byte[64 * 1024];
            int lus;
            while ((lus = zip.read(tampon)) >= 0) {
                sortie.write(tampon, 0, lus);
                copies += lus;
            }
        }
        return copies;
    }
}
