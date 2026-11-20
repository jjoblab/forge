package jo.forge.setup.install;

import jo.forge.setup.download.Downloader;
import jo.forge.setup.download.InstallCancelledException;
import jo.forge.setup.download.InstallException;
import jo.forge.setup.manifest.ComponentManifest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Source des archives de composants : réseau (téléchargeur reprenable) ou répertoire
 * local (installation hors ligne — paquet déjà téléchargé).
 */
public interface ComponentSource {

    /**
     * Fournit l'archive vérifiée d'un composant.
     *
     * @param component composant voulu.
     * @param progress  rapporteur de progression (peut être nul).
     * @param cancel    jeton d'annulation.
     * @return le chemin d'une archive complète dont la somme correspond au manifeste.
     * @throws InstallCancelledException si annulation demandée.
     * @throws InstallException          si la source ne peut pas fournir l'archive.
     */
    Path fetch(ComponentManifest component, Downloader.ProgressListener progress,
               jo.forge.setup.download.CancellationToken cancel);

    /**
     * Source réseau : télécharge (avec reprise) puis vérifie.
     */
    final class Network implements ComponentSource {

        private final Downloader downloader;

        /**
         * Crée la source réseau sur un téléchargeur donné (permet aux tests de câbler
         * un serveur local ; la production passe par {@code new Network(downloadsDir)}).
         *
         * @param downloader téléchargeur reprenable.
         */
        public Network(Downloader downloader) {
            this.downloader = downloader;
        }

        /**
         * Crée la source réseau avec le téléchargeur de production (HTTPS strict).
         *
         * @param downloadsDir répertoire des partiels et archives.
         */
        public Network(Path downloadsDir) {
            this(new Downloader(downloadsDir));
        }

        @Override
        public Path fetch(ComponentManifest component, Downloader.ProgressListener progress,
                          jo.forge.setup.download.CancellationToken cancel) {
            return downloader.download(component, progress, cancel);
        }
    }

    /**
     * Source locale (hors ligne) : les archives doivent exister sous le nom canonique
     * {@code <id>-<version>.zip} dans le répertoire fourni, sommes vérifiées.
     */
    final class LocalDirectory implements ComponentSource {

        private final Path directory;

        /**
         * Crée la source locale.
         *
         * @param directory répertoire contenant manifeste, signature et archives.
         */
        public LocalDirectory(Path directory) {
            this.directory = directory;
        }

        @Override
        public Path fetch(ComponentManifest component, Downloader.ProgressListener progress,
                          jo.forge.setup.download.CancellationToken cancel) {
            cancel.check();
            Path archive = directory.resolve(Downloader.archiveName(component));
            if (!Files.exists(archive)) {
                throw new InstallException("Archive locale manquante : " + archive);
            }
            String somme;
            try {
                if (Files.size(archive) != component.size()) {
                    throw new InstallException("Archive locale " + component.id()
                            + " de taille inattendue");
                }
                somme = jo.forge.setup.util.Files2.sha256(archive);
            } catch (IOException ioError) {
                throw new InstallException("Lecture de l'archive locale impossible : "
                        + ioError.getMessage(), ioError);
            }
            if (!somme.equals(component.sha256())) {
                throw new InstallException("Archive locale " + component.id()
                        + " ne correspond pas au manifeste (somme invalide)");
            }
            return archive;
        }
    }

    /**
     * Construit une archive ZIP en mémoire de test (fichiers donnés).
     *
     * @param target   fichier ZIP à écrire.
     * @param contents entrées (chemin relatif → contenu).
     * @throws IOException en cas d'erreur d'écriture.
     */
    static void writeZip(Path target, List<ZipContent> contents) throws IOException {
        Files.createDirectories(target.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target))) {
            for (ZipContent content : contents) {
                zip.putNextEntry(new ZipEntry(content.name()));
                zip.write(content.data());
                zip.closeEntry();
            }
        }
        Files.setLastModifiedTime(target, FileTime.fromMillis(System.currentTimeMillis()));
    }

    /** Entrée d'archive de test. */
    record ZipContent(String name, byte[] data) {
    }
}
