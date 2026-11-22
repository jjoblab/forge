package jo.forge.engine.deps;

import jo.forge.json.Json;
import jo.forge.json.JsonException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Cache du <strong>résultat</strong> de résolution, par module : le pendant du
 * « metadata cache » de Gradle. La réplique Maven ({@link ResolverCache}) évite de
 * re-télécharger les FICHIERS ; ce cache évite de re-MARCHER le graphe — des centaines
 * de lectures de POM et de métadonnées, chacune potentiellement réseau, pour un
 * projet Compose.
 *
 * <p>Clé : une empreinte SHA-256 de TOUT ce qui influe sur la résolution — racines
 * (coordonnées ET portées, ordre compris), dépôts, cible Android, niveau d'API,
 * politique de médiation, version du format. Une lecture ne vaut QUE si tous les
 * artefacts mémorisés existent toujours dans la réplique Maven (sinon : échec de
 * cache silencieux, la marche réparera).</p>
 *
 * <p>Sémantique des versions (parité Gradle, décision D-13) : une version STATIQUE
 * est crue indéfiniment — zéro requête, même pas de validation ; une racine
 * {@code -SNAPSHOT} est volatile : elle ne lit ni n'écrit JAMAIS ce cache (le moteur
 * de référence revalide les modules changeants toutes les 24 h ; Forge, qui n'a pas
 * d'horloge de build par défaut, choisit la prudence — consigné).</p>
 *
 * <p>Disposition : {@code <projet>/.forge/résolutions/<hex(module)>.json}, un fichier
 * par module, écriture atomique (temporaire + renommage). Un fichier illisible est
 * un échec de cache, jamais un échec de build.</p>
 */
public final class ResolutionResultCache {

    /** Version du format (toute évolution incrémente ET change l'empreinte). */
    private static final long VERSION_FORMAT = 1;

    private final Path répertoire;

    /** Une coordonnée mémorisée et son empaquetage. */
    public record EntréeMémorisée(Coordinate coordinate, String packaging) {
    }

    /** Un résultat de résolution mémorisé. */
    public record Mémoire(List<EntréeMémorisée> compile, List<EntréeMémorisée> runtime) {
    }

    /**
     * Crée le cache.
     *
     * @param répertoire répertoire {@code .forge/résolutions} (créé s'il manque).
     */
    public ResolutionResultCache(Path répertoire) {
        this.répertoire = répertoire;
    }

    /**
     * Indique si les racines sont mémorisables : aucune version {@code -SNAPSHOT}.
     *
     * @param racines les dépendances racines du module.
     * @return {@code true} si le cache s'applique.
     */
    public static boolean mémorisable(List<DependencyResolver.RootDependency> racines) {
        for (DependencyResolver.RootDependency racine : racines) {
            if (racine.coordinate().version().contains("-SNAPSHOT")) {
                return false;
            }
        }
        return true;
    }

    /**
     * Empreinte des entrées d'une résolution : SHA-256 de la sérialisation canonique
     * (racines ordonnées, dépôts, cible, médiation, version du format).
     *
     * @param racines      dépendances racines (l'ordre compte).
     * @param dépôts       URLs des dépôts.
     * @param cibleAndroid cible Android du module.
     * @param apiCible     niveau d'API de compilation (négatif : sans borne).
     * @param politique    politique de médiation.
     * @return l'empreinte hexadécimale.
     */
    public static String empreinte(List<DependencyResolver.RootDependency> racines,
                                   List<String> dépôts, boolean cibleAndroid,
                                   int apiCible, ConflictPolicy politique) {
        StringBuilder canonique = new StringBuilder("v").append(VERSION_FORMAT);
        canonique.append("|android=").append(cibleAndroid)
                .append("|api=").append(apiCible)
                .append("|médiation=").append(politique);
        for (String dépôt : dépôts) {
            canonique.append("|d=").append(dépôt);
        }
        for (DependencyResolver.RootDependency racine : racines) {
            canonique.append("|r=").append(racine.coordinate())
                    .append(':').append(racine.scope().wireName());
        }
        return hex(digest(canonique.toString()));
    }

    /**
     * Lit le résultat mémorisé d'un module pour une empreinte donnée.
     *
     * @param module    nom du module (partie de la clé du fichier).
     * @param empreinte empreinte des entrées actuelles (l'autre partie — le cache
     *                  étant partagé entre projets depuis D-18, deux projets au
     *                  même nom de module et à des entrées différentes doivent
     *                  coexister sans s'écraser).
     * @return le résultat, ou vide si absent/périmé/illisible.
     */
    public Optional<Mémoire> lire(String module, String empreinte) {
        Path fichier = fichierDe(module, empreinte);
        if (!Files.isRegularFile(fichier)) {
            return Optional.empty();
        }
        try {
            Map<?, ?> racine = (Map<?, ?>) Json.parse(
                    Files.readString(fichier, StandardCharsets.UTF_8));
            if (!Long.toString(VERSION_FORMAT).equals(texte(racine.get("version")))
                    || !empreinte.equals(texte(racine.get("empreinte")))) {
                return Optional.empty();
            }
            List<EntréeMémorisée> compile = entrées(racine.get("compile"));
            List<EntréeMémorisée> runtime = entrées(racine.get("runtime"));
            if (compile == null || runtime == null) {
                return Optional.empty();
            }
            return Optional.of(new Mémoire(compile, runtime));
        } catch (IOException | JsonException | ClassCastException | NumberFormatException illisible) {
            return Optional.empty(); // Échec de cache, pas de build.
        }
    }

    /**
     * Mémorise le résultat de résolution d'un module (atomique).
     *
     * @param module    nom du module.
     * @param empreinte empreinte des entrées.
     * @param compile   classpath de compilation mémorisé.
     * @param runtime   classpath d'exécution mémorisé.
     */
    public void écrire(String module, String empreinte,
                       List<EntréeMémorisée> compile, List<EntréeMémorisée> runtime) {
        Map<String, Object> racine = new LinkedHashMap<>();
        racine.put("version", VERSION_FORMAT);
        racine.put("module", module);
        racine.put("empreinte", empreinte);
        racine.put("compile", jsonDe(compile));
        racine.put("runtime", jsonDe(runtime));
        try {
            Files.createDirectories(répertoire);
            Path cible = fichierDe(module, empreinte);
            Path temporaire = répertoire.resolve(cible.getFileName() + ".part");
            Files.writeString(temporaire, Json.write(racine), StandardCharsets.UTF_8);
            try {
                Files.move(temporaire, cible, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException pasAtomique) {
                Files.move(temporaire, cible,
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException échecÉcriture) {
            // Le cache de résolution est une optimisation : son échec d'écriture
            // ne doit jamais faire échouer un build réussi.
        }
    }

    /** Sérialise une liste d'entrées mémorisées. */
    private static List<Object> jsonDe(List<EntréeMémorisée> entrées) {
        List<Object> liste = new ArrayList<>();
        for (EntréeMémorisée entrée : entrées) {
            Map<String, Object> objet = new LinkedHashMap<>();
            objet.put("g", entrée.coordinate().group());
            objet.put("a", entrée.coordinate().artifact());
            objet.put("v", entrée.coordinate().version());
            objet.put("packaging", entrée.packaging());
            liste.add(objet);
        }
        return liste;
    }

    /** Lit une liste d'entrées mémorisées (null si malformée). */
    private static List<EntréeMémorisée> entrées(Object valeur) {
        if (!(valeur instanceof List<?> liste)) {
            return null;
        }
        List<EntréeMémorisée> résultat = new ArrayList<>();
        for (Object élément : liste) {
            if (!(élément instanceof Map<?, ?> objet)) {
                return null;
            }
            String groupe = texte(objet.get("g"));
            String artefact = texte(objet.get("a"));
            String version = texte(objet.get("v"));
            String packaging = texte(objet.get("packaging"));
            if (groupe == null || artefact == null || version == null
                    || packaging == null) {
                return null;
            }
            résultat.add(new EntréeMémorisée(
                    new Coordinate(groupe, artefact, version), packaging));
        }
        return résultat;
    }

    /** Texte d'une valeur JSON (null si absent). */
    private static String texte(Object valeur) {
        return valeur == null ? null : valeur.toString();
    }

    /** Fichier d'une résolution : hexadécimal injectif du nom de module (constat
     * C-3), PRÉFIXÉ par l'empreinte des entrées — le cache étant partagé entre
     * projets (D-18), deux projets au même nom de module et à des entrées
     * différentes coexistent au lieu de s'écraser à chaque build. */
    private Path fichierDe(String module, String empreinte) {
        StringBuilder hex = new StringBuilder();
        for (byte octet : module.getBytes(StandardCharsets.UTF_8)) {
            hex.append(Character.forDigit((octet >> 4) & 0xF, 16));
            hex.append(Character.forDigit(octet & 0xF, 16));
        }
        return répertoire.resolve(empreinte.substring(0,
                Math.min(12, empreinte.length())) + "-" + hex + ".json");
    }

    private static byte[] digest(String texte) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(texte.getBytes(StandardCharsets.UTF_8));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 absent de la plate-forme",
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
}
