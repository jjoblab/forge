package jo.forge.engine.pipeline.java;

import jo.forge.json.Json;
import jo.forge.json.JsonException;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fichier de classpath résolu d'un module : la sortie de la tâche de résolution et
 * l'entrée des tâches de compilation.
 *
 * <p>Format JSON versionné : liste ordonnée de coordonnées et de chemins de jars, pour
 * les classpaths de compilation et d'exécution. Son contenu sert d'empreinte à la
 * compilation : toute variation de résolution invalide la tâche aval — c'est le
 * mécanisme d'incrémentalité de la résolution (la résolution elle-même n'a que ce
 * fichier comme sortie déclarée).</p>
 *
 * <p><b>Format v2</b> (v0.2.15, AAR/Compose) : chaque entrée porte son
 * {@code packaging} ({@code jar} par défaut — v1 lu tel quel) et, pour un AAR, le
 * répertoire {@code exploded} où la résolution a déroulé l'archive. La lecture des
 * JARS d'un AAR y ajoute son {@code classes.jar} ET ses {@code libs/*.jar} ; les
 * tâches Android (ressources, manifestes) consomment le répertoire explosé.
 * L'entrée d'un AAR pointe le {@code classes.jar} : un lecteur qui ne connaît que
 * les jars fonctionne sans changement.</p>
 */
public final class ClasspathFile {

    /** Version du format. */
    private static final long VERSION_FORMAT = 2;

    private ClasspathFile() {
    }

    /**
     * Écrit le fichier de classpath de façon atomique.
     *
     * @param cible    chemin du fichier.
     * @param compile  entrées du classpath de compilation (coordonnée + jar).
     * @param runtime  entrées du classpath d'exécution.
     * @throws IOException si l'écriture échoue.
     */
    public static void écrire(Path cible, List<Entrée> compile, List<Entrée> runtime)
            throws IOException {
        Map<String, Object> racine = new LinkedHashMap<>();
        racine.put("version", VERSION_FORMAT);
        racine.put("compile", entréesVersJson(compile));
        racine.put("runtime", entréesVersJson(runtime));
        Files.createDirectories(cible.getParent());
        Path temporaire = cible.resolveSibling(cible.getFileName() + ".part");
        Files.writeString(temporaire, Json.write(racine));
        try {
            Files.move(temporaire, cible, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException pasAtomique) {
            Files.move(temporaire, cible, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Lit les entrées du classpath de compilation (jars ET parties AAR).
     *
     * @param fichier chemin du fichier de classpath.
     * @return les entrées complètes, ordonnées.
     * @throws IOException si la lecture échoue.
     */
    public static List<Entrée> lireEntréesCompilation(Path fichier) throws IOException {
        return lireEntrées(fichier, "compile");
    }

    /**
     * Lit les entrées du classpath d'exécution (jars ET parties AAR).
     *
     * @param fichier chemin du fichier de classpath.
     * @return les entrées complètes, ordonnées.
     * @throws IOException si la lecture échoue.
     */
    public static List<Entrée> lireEntréesExécution(Path fichier) throws IOException {
        return lireEntrées(fichier, "runtime");
    }

    /**
     * Lit les jars du classpath de compilation.
     *
     * @param fichier chemin du fichier de classpath.
     * @return les chemins de jars, ordonnés (un AAR y contribue par son
     *         {@code classes.jar} et ses {@code libs/*.jar}).
     * @throws IOException si la lecture échoue.
     */
    public static List<Path> lireJarsCompilation(Path fichier) throws IOException {
        return lireJars(fichier, "compile");
    }

    /**
     * Lit les jars du classpath d'exécution.
     *
     * @param fichier chemin du fichier de classpath.
     * @return les chemins de jars, ordonnés (un AAR y contribue par son
     *         {@code classes.jar} et ses {@code libs/*.jar}).
     * @throws IOException si la lecture échoue.
     */
    public static List<Path> lireJarsExécution(Path fichier) throws IOException {
        return lireJars(fichier, "runtime");
    }

    /**
     * Les jars d'une entrée : elle-même, plus les {@code libs/*.jar} d'un AAR
     * explosé (triés — déterminisme des classpaths et du dexage).
     */
    private static List<Path> jarsDe(Entrée entrée) {
        List<Path> jars = new ArrayList<>();
        jars.add(entrée.jar());
        if (entrée.exploded() != null) {
          Path libs = entrée.exploded().resolve("libs");
            if (Files.isDirectory(libs)) {
                try (DirectoryStream<Path> parcours =
                        Files.newDirectoryStream(libs, "*.jar")) {
                    List<Path> imbriqués = new ArrayList<>();
                    parcours.forEach(imbriqués::add);
                    imbriqués.sort(Path::compareTo);
                    jars.addAll(imbriqués);
                } catch (IOException illisible) {
                    // Les jars imbriqués sont absents des AAR modernes.
                }
            }
        }
        return jars;
    }

    @SuppressWarnings("unchecked")
    private static List<Entrée> lireEntrées(Path fichier, String champ)
            throws IOException {
        Map<String, Object> racine;
        try {
            racine = (Map<String, Object>) Json.parse(Files.readString(fichier));
        } catch (JsonException e) {
            throw new IOException("fichier de classpath illisible : " + fichier
                    + " (" + e.getMessage() + ")", e);
        }
        List<Entrée> entrées = new ArrayList<>();
        Object liste = racine.get(champ);
        if (liste instanceof List<?> éléments) {
            for (Object élément : éléments) {
                if (élément instanceof Map<?, ?> carte
                        && carte.get("jar") instanceof String jar) {
                    String coordonnée = carte.get("coordinate") instanceof String c
                            ? c : "";
                    String sha256 = carte.get("sha256") instanceof String s ? s : null;
                    String packaging = carte.get("packaging") instanceof String p
                            ? p : "jar";
                    Path exploded = carte.get("exploded") instanceof String e
                            ? Path.of(e) : null;
                    entrées.add(new Entrée(coordonnée, Path.of(jar), sha256,
                            packaging, exploded));
                }
            }
        }
        return entrées;
    }

    private static List<Path> lireJars(Path fichier, String champ) throws IOException {
        List<Path> jars = new ArrayList<>();
        for (Entrée entrée : lireEntrées(fichier, champ)) {
            jars.addAll(jarsDe(entrée));
        }
        return jars;
    }

    private static List<Object> entréesVersJson(List<Entrée> entrées) {
        List<Object> liste = new ArrayList<>();
        for (Entrée entrée : entrées) {
            Map<String, Object> carte = new LinkedHashMap<>();
            carte.put("coordinate", entrée.coordonnée());
            carte.put("jar", entrée.jar().toString());
            if (entrée.sha256() != null) {
                carte.put("sha256", entrée.sha256());
            }
            if (!"jar".equals(entrée.packaging())) {
                carte.put("packaging", entrée.packaging());
            }
            if (entrée.exploded() != null) {
                carte.put("exploded", entrée.exploded().toString());
            }
            liste.add(carte);
        }
        return liste;
    }

    /**
     * Une entrée de classpath résolue.
     *
     * @param coordonnée identité Maven (déjà résolue, après relogement).
     * @param jar        jar de classpath ({@code classes.jar} pour un AAR).
     * @param sha256     somme de contenu (facultative).
     * @param packaging  {@code jar} (défaut) ou {@code aar}.
     * @param exploded   répertoire d'explosion de l'AAR (null pour un jar).
     */
    public record Entrée(String coordonnée, Path jar, String sha256,
                         String packaging, Path exploded) {

        /** Forme jar simple (compatibilité historique). */
        public Entrée(String coordonnée, Path jar, String sha256) {
            this(coordonnée, jar, sha256, "jar", null);
        }

        /** Un AAR ? (parties res/assets/manifeste dans {@link #exploded}). */
        public boolean estAar() {
            return "aar".equals(packaging) && exploded != null;
        }
    }
}
