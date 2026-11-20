package jo.forge.engine.deps;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * Transport des dépôts Maven : HTTP(S) strict, temporisations obligatoires, sommes
 * vérifiées, téléchargement en flux (jamais en mémoire).
 *
 * <p>Règles de sécurité (le moteur de référence accepte HTTP partout — constat
 * consigné dans la revue ; nous refusons) : une URL de dépôt doit être en
 * <strong>HTTPS</strong>, à l'exception de la boucle locale (développement et tests) ;
 * les redirections ne sont pas suivies automatiquement (pas de dégradation de
 * protocole).</p>
 *
 * <p>Intégrité : le fichier d'empreinte {@code .sha1} publié à côté de la ressource
 * est systématiquement téléchargé et comparé au flux reçu ; s'il est absent, un
 * avertissement est émis (politique consignée : refuser aurait exclu des dépôts réels
 * sans sommes) — les artefacts du MOTEUR, eux, exigent TOUJOURS une somme signée
 * (installateur, manifeste ECDSA).</p>
 */
public final class HttpTransport {

    /** Temps d'attente maximal à l'établissement d'une connexion. */
    static final Duration DÉLAI_CONNEXION = Duration.ofSeconds(10);

    /** Temps d'attente maximal entre deux lectures. */
    static final Duration DÉLAI_LECTURE = Duration.ofSeconds(30);

    /** Taille maximale acceptée pour un fichier d'empreinte (défense). */
    private static final int TAILLE_SOMME_MAX = 1024;

    /** Taille de la mémoire tampon de flux. */
    private static final int TAILLE_TAMPON = 64 * 1024;

    private final Consumer<String> avertissements;

    /**
     * Crée le transport.
     *
     * @param avertissements consommateur d'avertissements (journal du build).
     */
    public HttpTransport(Consumer<String> avertissements) {
        this.avertissements = avertissements == null ? message -> { } : avertissements;
    }

    /**
     * Valide l'URL d'un dépôt : HTTPS exigé, boucle locale tolérée.
     *
     * @param url l'URL du dépôt.
     * @throws ResolverException si le protocole est refusé.
     */
    public static void vérifierUrlDépôt(String url) {
        try {
            URI uri = URI.create(url);
            String schéma = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            String hôte = uri.getHost() == null ? "" : uri.getHost();
            boolean boucleLocale = hôte.equals("localhost") || hôte.equals("127.0.0.1")
                    || hôte.equals("::1") || hôte.equals("[::1]");
            if (!"https".equals(schéma) && !("http".equals(schéma) && boucleLocale)) {
                throw new ResolverException("Dépôt refusé (HTTPS exigé, HTTP toléré "
                        + "uniquement en boucle locale) : " + url);
            }
        } catch (ResolverException e) {
            throw e;
        } catch (Exception e) {
            throw new ResolverException("URL de dépôt invalide : " + url, e);
        }
    }

    /**
     * Télécharge une ressource vers un fichier, en flux, avec vérification de somme.
     *
     * @param url           URL complète de la ressource.
     * @param destination   fichier de destination (écrasé).
     * @param urlSomme      URL du fichier {@code .sha1} (ou null : aucune somme
     *                      publiée).
     * @return le digest SHA-256 du contenu reçu (pour la trace de build).
     * @throws IOException       erreur réseau ou d'écriture.
     * @throws ResolverException si la somme publiée ne correspond pas au contenu reçu.
     */
    public String téléchargerVersFichier(String url, Path destination, String urlSomme)
            throws IOException {
        String sommeAttendue = urlSomme == null ? null : téléchargerSomme(urlSomme);
        byte[] tampon = new byte[TAILLE_TAMPON];
        MessageDigest sha1 = digest("SHA-1");
        MessageDigest sha256 = digest("SHA-256");
        HttpURLConnection connexion = ouvrir(url);
        int code = connexion.getResponseCode();
        if (code == HttpURLConnection.HTTP_NOT_FOUND || code == 410) {
            throw new Introuvable(url);
        }
        if (code != HttpURLConnection.HTTP_OK) {
            throw new IOException("HTTP " + code + " pour " + url);
        }
        Files.createDirectories(destination.getParent());
        Path temporaire = destination.resolveSibling(
                destination.getFileName() + ".part");
        try (InputStream entrée = connexion.getInputStream();
             OutputStream sortie = Files.newOutputStream(temporaire)) {
            int lus;
            while ((lus = entrée.read(tampon)) >= 0) {
                sha1.update(tampon, 0, lus);
                sha256.update(tampon, 0, lus);
                sortie.write(tampon, 0, lus);
            }
        } catch (IOException echec) {
            Files.deleteIfExists(temporaire);
            throw echec;
        }
        if (sommeAttendue != null) {
            String sommeCalculée = hex(sha1.digest());
            if (!sommeAttendue.equalsIgnoreCase(sommeCalculée)) {
                Files.deleteIfExists(temporaire);
                throw new ResolverException("Intégrité refusée pour " + url
                        + " : sha1 attendu " + sommeAttendue + ", calculé "
                        + sommeCalculée);
            }
        } else {
            avertissements.accept("aucune somme publiée pour " + url
                    + " — intégrité assurée par HTTPS seul");
        }
        try {
            Files.move(temporaire, destination, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException pasAtomique) {
            Files.move(temporaire, destination, StandardCopyOption.REPLACE_EXISTING);
        }
        return hex(sha256.digest());
    }

    /**
     * Lit le fichier de somme (sha1), borné.
     */
    private String téléchargerSomme(String urlSomme) {
        try {
            HttpURLConnection connexion = ouvrir(urlSomme);
            if (connexion.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return null;
            }
            if (connexion.getContentLengthLong() > TAILLE_SOMME_MAX) {
                return null;
            }
            try (InputStream entrée = connexion.getInputStream()) {
                byte[] octets = entrée.readNBytes(TAILLE_SOMME_MAX);
                String texte = new String(octets, java.nio.charset.StandardCharsets.UTF_8)
                        .trim();
                // Format Maven : "somme" ou "somme  nom-du-fichier".
                int espace = texte.indexOf(' ');
                return espace > 0 ? texte.substring(0, espace) : texte;
            }
        } catch (IOException | RuntimeException e) {
            avertissements.accept("somme illisible pour " + urlSomme
                    + " (" + e.getMessage() + ") — téléchargement non vérifié");
            return null;
        }
    }

    /**
     * Ouvre une connexion avec temporisations et sans suivi de redirection.
     */
    private static HttpURLConnection ouvrir(String url) throws IOException {
        URL cible = new URL(url);
        HttpURLConnection connexion = (HttpURLConnection) cible.openConnection();
        connexion.setConnectTimeout((int) DÉLAI_CONNEXION.toMillis());
        connexion.setReadTimeout((int) DÉLAI_LECTURE.toMillis());
        connexion.setInstanceFollowRedirects(false);
        connexion.setRequestProperty("User-Agent", "Forge-moteur/0.2");
        return connexion;
    }

    private static MessageDigest digest(String algorithme) {
        try {
            return MessageDigest.getInstance(algorithme);
        } catch (Exception impossible) {
            throw new IllegalStateException(algorithme + " absent de la plate-forme",
                    impossible);
        }
    }

    private static String hex(byte[] somme) {
        StringBuilder texte = new StringBuilder(somme.length * 2);
        for (byte octet : somme) {
            texte.append(Character.forDigit((octet >> 4) & 0xF, 16));
            texte.append(Character.forDigit(octet & 0xF, 16));
        }
        return texte.toString();
    }

    /** Signal d'absence définitive (à mémoriser dans le cache négatif). */
    static final class Introuvable extends IOException {

        private static final long serialVersionUID = 1L;

        Introuvable(String url) {
            super("introuvable : " + url);
        }
    }
}
