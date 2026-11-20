package jo.forge.engine.pipeline.java;

import jo.forge.json.Json;
import jo.forge.json.JsonException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fichier de classpath résolu d'un module : la sortie de la tâche de résolution et
 * l'entrée de la tâche de compilation.
 *
 * <p>Format JSON versionné : liste ordonnée de coordonnées et de chemins de jars, pour
 * les classpaths de compilation et d'exécution. Son contenu sert d'empreinte à la
 * compilation : toute variation de résolution invalide la tâche aval — c'est le
 * mécanisme d'incrémentalité de la résolution (la résolution elle-même n'a que ce
 * fichier comme sortie déclarée).</p>
 */
public final class ClasspathFile {

    /** Version du format. */
    private static final long VERSION_FORMAT = 1;

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
     * Lit les jars du classpath de compilation.
     *
     * @param fichier chemin du fichier de classpath.
     * @return les chemins de jars, ordonnés.
     * @throws IOException si la lecture échoue.
     */
    public static List<Path> lireJarsCompilation(Path fichier) throws IOException {
        return lireJars(fichier, "compile");
    }

    /**
     * Lit les jars du classpath d'exécution.
     *
     * @param fichier chemin du fichier de classpath.
     * @return les chemins de jars, ordonnés.
     * @throws IOException si la lecture échoue.
     */
    public static List<Path> lireJarsExécution(Path fichier) throws IOException {
        return lireJars(fichier, "runtime");
    }

    @SuppressWarnings("unchecked")
    private static List<Path> lireJars(Path fichier, String champ) throws IOException {
        Map<String, Object> racine;
        try {
            racine = (Map<String, Object>) Json.parse(Files.readString(fichier));
        } catch (JsonException e) {
            throw new IOException("fichier de classpath illisible : " + fichier
                    + " (" + e.getMessage() + ")", e);
        }
        List<Path> jars = new ArrayList<>();
        Object liste = racine.get(champ);
        if (liste instanceof List<?> entrées) {
            for (Object entrée : entrées) {
                if (entrée instanceof Map<?, ?> carte
                        && carte.get("jar") instanceof String jar) {
                    jars.add(Path.of(jar));
                }
            }
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
            liste.add(carte);
        }
        return liste;
    }

    /** Une entrée de classpath résolue. */
    public record Entrée(String coordonnée, Path jar, String sha256) {
    }
}
