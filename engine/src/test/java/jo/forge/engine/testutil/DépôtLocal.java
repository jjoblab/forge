package jo.forge.engine.testutil;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jo.forge.engine.deps.Coordinate;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/**
 * Dépôt Maven local de test : sert des POM et des jars publiés à la volée sur la
 * boucle locale (seul HTTP toléré par le transport).
 */
public final class DépôtLocal implements AutoCloseable {

    private final HttpServer serveur;
    private final Map<String, byte[]> ressources = new HashMap<>();
    private final Map<String, Integer> demandes = new HashMap<>();

    private DépôtLocal(HttpServer serveur) {
        this.serveur = serveur;
    }

    /**
     * Démarre le dépôt sur un port libre de la boucle locale.
     *
     * @return le dépôt prêt à publier.
     * @throws IOException si le démarrage échoue.
     */
    public static DépôtLocal démarrer() throws IOException {
        HttpServer serveur = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        DépôtLocal dépôt = new DépôtLocal(serveur);
        serveur.createContext("/", dépôt::servir);
        serveur.start();
        return dépôt;
    }

    /**
     * Publie un POM (avec sa somme) pour une coordonnée.
     *
     * @param coordonnée la coordonnée.
     * @param pomXml     le contenu du POM.
     */
    public void publierPom(Coordinate coordonnée, String pomXml) {
        publier(chemin(coordonnée, ".pom"),
                pomXml.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Publie un jar (avec sa somme) pour une coordonnée.
     *
     * @param coordonnée la coordonnée.
     * @param jar        le contenu binaire.
     */
    public void publierJar(Coordinate coordonnée, byte[] jar) {
        publier(chemin(coordonnée, ".jar"), jar);
    }

    /**
     * Publie un AAR (avec sa somme) pour une coordonnée.
     *
     * @param coordonnée la coordonnée.
     * @param aar        le contenu binaire.
     */
    public void publierAar(Coordinate coordonnée, byte[] aar) {
        publier(chemin(coordonnée, ".aar"), aar);
    }

    /**
     * Publie des métadonnées de module Gradle ({@code .module}) pour une
     * coordonnée.
     *
     * @param coordonnée la coordonnée.
     * @param json       le contenu JSON.
     */
    public void publierModule(Coordinate coordonnée, String json) {
        publier(chemin(coordonnée, ".module"), json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Publie un fichier arbitraire à un chemin de dépôt (fichier de variante
     * Gradle, ex. {@code demo/lib-android/1.0/lib-release.aar}).
     *
     * @param chemin   chemin absolu dans le dépôt (avec barre initiale).
     * @param contenu  le contenu binaire.
     */
    public void publierFichier(String chemin, byte[] contenu) {
        publier(chemin, contenu);
    }

    /**
     * Publie une somme FAUSSE pour l'artefact d'une coordonnée (test de rejet).
     *
     * @param coordonnée la coordonnée.
     */
    public void corrompreSommeJar(Coordinate coordonnée) {
        ressources.put(chemin(coordonnée, ".jar") + ".sha1",
                "0000000000000000000000000000000000000000".getBytes());
    }

    /**
     * Publie explicitement l'absence de somme (404 sur le .sha1).
     *
     * @param coordonnée la coordonnée.
     */
    public void sansSommeJar(Coordinate coordonnée) {
        ressources.remove(chemin(coordonnée, ".jar") + ".sha1");
    }

    /**
     * URL racine du dépôt.
     *
     * @return l'URL (boucle locale, HTTP toléré).
     */
    public String url() {
        return "http://127.0.0.1:" + serveur.getAddress().getPort() + "/";
    }

    /**
     * Nombre de requêtes reçues pour un chemin (cache négatif, etc.).
     *
     * @param chemin le chemin (celui de {@link #chemin}).
     * @return le nombre de requêtes.
     */
    public int demandesPour(String chemin) {
        return demandes.getOrDefault(chemin, 0);
    }

    /**
     * Chemin de ressource Maven d'une coordonnée (clé publique du dépôt).
     *
     * @param coordonnée la coordonnée.
     * @param suffixe    {@code ".pom"} ou {@code ".jar"}.
     * @return le chemin.
     */
    public static String chemin(Coordinate coordonnée, String suffixe) {
        return "/" + coordonnée.group().replace('.', '/') + "/" + coordonnée.artifact()
                + "/" + coordonnée.version() + "/" + coordonnée.artifact() + "-"
                + coordonnée.version() + suffixe;
    }

    private void publier(String chemin, byte[] contenu) {
        ressources.put(chemin, contenu);
        ressources.put(chemin + ".sha1", sha1(contenu).getBytes(StandardCharsets.UTF_8));
    }

    private void servir(HttpExchange échange) throws IOException {
        String chemin = échange.getRequestURI().getPath();
        demandes.merge(chemin, 1, Integer::sum);
        byte[] contenu = ressources.get(chemin);
        if (contenu == null) {
            échange.sendResponseHeaders(404, -1);
            échange.close();
            return;
        }
        échange.sendResponseHeaders(200, contenu.length);
        try (OutputStream sortie = échange.getResponseBody()) {
            sortie.write(contenu);
        }
    }

    private static String sha1(byte[] octets) {
        try {
            byte[] somme = MessageDigest.getInstance("SHA-1").digest(octets);
            StringBuilder hex = new StringBuilder(somme.length * 2);
            for (byte octet : somme) {
                hex.append(Character.forDigit((octet >> 4) & 0xF, 16));
                hex.append(Character.forDigit(octet & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @Override
    public void close() {
        serveur.stop(0);
    }
}
