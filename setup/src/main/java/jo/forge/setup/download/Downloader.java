package jo.forge.setup.download;

import jo.forge.setup.manifest.ComponentManifest;
import jo.forge.setup.util.Files2;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;

/**
 * Téléchargeur reprenable : écrit dans un {@code .part}, reprend par requête
 * {@code Range} quand le serveur le permet, vérifie la somme SHA-256 en fin de parcours.
 *
 * <p>Corrige les constats S-1 (aucune vérification d'intégrité) et P-4 (aucune reprise)
 * de la revue : un échec à 90 % repart de là où il s'est arrêté ; un contenu altéré est
 * rejeté net, jamais installé. HTTPS obligatoire — refus immédiat de toute autre
 * scheme (constat S-2).</p>
 *
 * <p>Thread-sûreté : une instance traite un téléchargement à la fois ; l'annulation se
 * fait par {@link CancellationToken} depuis un autre thread.</p>
 */
public final class Downloader {

    /** Délai de connexion en millisecondes. */
    private static final int CONNECT_TIMEOUT_MS = 10_000;

    /** Délai de lecture en millisecondes. */
    private static final int READ_TIMEOUT_MS = 30_000;

    /** Schémas acceptés par le constructeur public : HTTPS obligatoire (constat S-2). */
    private static final java.util.Set<String> HTTPS_SEUL = java.util.Set.of("https");

    private final Path downloadsDir;
    private final java.util.Set<String> allowedSchemes;

    /**
     * Crée le téléchargeur pour le répertoire de téléchargements donné — HTTPS seul.
     *
     * @param downloadsDir répertoire des {@code .part} et archives complètes.
     */
    public Downloader(Path downloadsDir) {
        this(downloadsDir, HTTPS_SEUL);
    }

    /**
     * Constructeur d'injection : schémas acceptés explicites. Réservé aux tests (serveur
     * HTTP local) et au câblage avancé — la production passe par le constructeur à un
     * argument (HTTPS strict) et le manifeste n'accepte que des URLs HTTPS.
     *
     * @param downloadsDir répertoire des téléchargements.
     * @param allowedSchemes schémas acceptés.
     */
    public Downloader(Path downloadsDir, java.util.Set<String> allowedSchemes) {
        this.downloadsDir = downloadsDir;
        this.allowedSchemes = java.util.Set.copyOf(allowedSchemes);
    }

    /**
     * Télécharge (ou reprend, ou réutilise) l'archive d'un composant.
     *
     * @param component composant à télécharger (somme et taille attendues).
     * @param progress  rapporteur de progression (octets reçus, total) ; peut être nul.
     * @param cancel    jeton d'annulation ; le partiel est conservé pour reprise.
     * @return le chemin de l'archive complète vérifiée.
     * @throws InstallCancelledException si annulation demandée.
     * @throws InstallException          en cas d'erreur réseau ou d'intégrité.
     */
    public Path download(ComponentManifest component, ProgressListener progress,
                         CancellationToken cancel) {
        try {
            Files.createDirectories(downloadsDir);
        } catch (IOException ioError) {
            throw new InstallException("Répertoire de téléchargement inaccessible : "
                    + ioError.getMessage());
        }
        Path cible = downloadsDir.resolve(archiveName(component));
        Path partiel = cible.resolveSibling(cible.getFileName() + ".part");

        // Archive déjà complète et valide : réutilisation (reprise après coupure globale).
        if (Files.exists(cible)) {
            verifier(cible, component);
            return cible;
        }

        long dejaRecu = Files.exists(partiel) ? tailleDe(partiel) : 0;
        if (dejaRecu > component.size()) {
            // Partiel incohérent (manifeste changé) : on repart de zéro.
            supprimer(partiel);
            dejaRecu = 0;
        }

        Exception derniereErreur = null;
        for (String url : component.urls()) {
            cancel.check();
            try {
                telechargerDepuis(url, partiel, dejaRecu, component, progress, cancel);
                verifier(cible, component);
                return cible;
            } catch (InstallCancelledException annulation) {
                throw annulation;
            } catch (Exception erreurMiroir) {
                derniereErreur = erreurMiroir;
                // Miroir suivant ; le partiel sert de point de reprise.
            }
        }
        throw new InstallException("Téléchargement de " + component.id()
                + " impossible après tous les miroirs : "
                + (derniereErreur == null ? "aucune URL" : dernereMessage(derniereErreur)),
                derniereErreur);
    }

    /**
     * Télécharge depuis une URL, en reprenant si le serveur le permet.
     */
    private void telechargerDepuis(String urlTexte, Path partiel, long dejaRecu,
                                   ComponentManifest component, ProgressListener progress,
                                   CancellationToken cancel) throws IOException {
        URL url = new URL(urlTexte);
        if (!allowedSchemes.contains(url.getProtocol())) {
            throw new InstallException("Schéma d'URL refusé pour " + component.id()
                    + " : " + url.getProtocol());
        }
        HttpURLConnection connexion = (HttpURLConnection) url.openConnection();
        try {
            connexion.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connexion.setReadTimeout(READ_TIMEOUT_MS);
            if (dejaRecu > 0) {
                connexion.setRequestProperty("Range", "bytes=" + dejaRecu + "-");
            }
            boolean repriseDemandee = dejaRecu > 0;
            int code = connexion.getResponseCode();
            // Reprise acceptée seulement si on l'a demandée ET que le partiel existe
            // encore (un serveur fautif peut répondre 206 sans demande : téléchargement
            // complet déguisé, à traiter comme tel).
            boolean reprise = code == HttpURLConnection.HTTP_PARTIAL
                    && repriseDemandee && Files.exists(partiel);
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                throw new IOException("HTTP " + code + " pour " + urlTexte);
            }
            long totalAttendu = reprise ? dejaRecu + connexion.getContentLengthLong()
                    : connexion.getContentLengthLong();
            if (!reprise && dejaRecu > 0) {
                // Le serveur ignore Range (ou le partiel a disparu) : repart de zéro.
                supprimer(partiel);
            }

            // CREATE avec APPEND : crée le partiel s'il a disparu, sinon ajoute à la suite.
            StandardOpenOption[] options = reprise
                    ? new StandardOpenOption[]{StandardOpenOption.APPEND,
                            StandardOpenOption.WRITE, StandardOpenOption.CREATE}
                    : new StandardOpenOption[]{StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING};
            try (InputStream entree = connexion.getInputStream();
                 OutputStream sortie = Files.newOutputStream(partiel, options)) {
                byte[] tampon = new byte[64 * 1024];
                long recus = reprise ? dejaRecu : 0;
                int lus;
                while ((lus = entree.read(tampon)) >= 0) {
                    cancel.check();
                    sortie.write(tampon, 0, lus);
                    recus += lus;
                    if (recus > component.size()) {
                        throw new IOException("Archive plus grande que la taille annoncée");
                    }
                    if (progress != null) {
                        progress.onProgress(recus, totalAttendu > 0 ? totalAttendu
                                : component.size());
                    }
                }
                sortie.flush();
            }

            long tailleFinale = tailleDe(partiel);
            if (tailleFinale != component.size()) {
                throw new IOException("Taille reçue " + tailleFinale
                        + " différente de l'annoncée " + component.size());
            }
            // Somme vérifiée sur le fichier complet (une reprise n'a lu que la fin).
            String somme = Files2.sha256(partiel);
            if (!somme.equals(component.sha256())) {
                supprimer(partiel);
                throw new IOException("SHA-256 invalide pour " + component.id());
            }
            Files.move(partiel, partiel.resolveSibling(
                    archiveName(component)), java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } finally {
            connexion.disconnect();
        }
    }

    /**
     * Vérifie somme et taille d'une archive déjà présente.
     */
    private void verifier(Path archive, ComponentManifest component) {
        try {
            if (Files.size(archive) != component.size()) {
                throw new InstallException("Archive " + component.id()
                        + " de taille inattendue : re-téléchargement nécessaire");
            }
            if (!Files2.sha256(archive).equals(component.sha256())) {
                Files.deleteIfExists(archive);
                throw new InstallException("Archive " + component.id()
                        + " altérée : supprimée, re-téléchargement nécessaire");
            }
        } catch (IOException ioError) {
            throw new InstallException("Vérification de " + component.id()
                    + " impossible : " + ioError.getMessage(), ioError);
        }
    }

    /**
     * Nom de fichier canonique de l'archive d'un composant.
     */
    public static String archiveName(ComponentManifest component) {
        return component.id() + "-" + component.version() + ".zip";
    }

    private static long tailleDe(Path fichier) {
        try {
            return Files.size(fichier);
        } catch (IOException absent) {
            return 0;
        }
    }

    private static void supprimer(Path fichier) {
        try {
            Files.deleteIfExists(fichier);
        } catch (IOException ignored) {
            // Si la suppression échoue, l'écrasement TRUNCATE s'en chargera.
        }
    }

    private static String dernereMessage(Exception erreur) {
        return erreur.getMessage() == null ? erreur.getClass().getSimpleName()
                : erreur.getMessage();
    }

    /** Rapporteur de progression de téléchargement. */
    @FunctionalInterface
    public interface ProgressListener {

        /**
         * Appelé régulièrement pendant le téléchargement.
         *
         * @param bytesReceived octets reçus au total (reprise incluse).
         * @param bytesTotal    total attendu (annoncé), -1 si inconnu.
         */
        void onProgress(long bytesReceived, long bytesTotal);
    }
}
