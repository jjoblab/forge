package jo.forge.engine.deps;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Un AAR explosé dans le cache de résolution : les parties qu'un moteur de build
 * Android consomme séparément — {@code classes.jar} (compilation ET dexage),
 * {@code libs/*.jar} (jars imbriqués des vieilles bibliothèques), {@code res/}
 * (fusion aapt2), {@code assets/} (empaquetage), {@code AndroidManifest.xml}
 * (fusion de manifeste + paquet pour {@code --extra-packages}) et
 * {@code aar-metadata.properties} (borne {@code minCompileSdk} d'AGP).
 *
 * <p><b>Explosion atomique</b> (motif du moteur de référence, constat REF-128) :
 * l'archive est déroulée dans un répertoire provisoire frère UNIQUE, puis le répertoire
 * est renommé d'un bloc ; le marqueur {@value #MARQUEUR} est posé EN DERNIER.
 * Un répertoire cible sans marqueur (explosion interrompue) est purgé et refait.</p>
 * <b>Garde zip-slip</b> : toute entrée dont le chemin normalisé sortirait du
 * répertoire cible est rejetée nommément — jamais {@code ../} en aveugle.</p>
 *
 * <p>Un AAR sans {@code classes.jar} reçoit un jar VIDE (archive de 22 octets —
 * le classpath d'ART refuse un zip sans entrée, et d8 veut un fichier). Ses
 * autres parties restent servies telles quelles.</p>
 */
public final class AarExplodé {

    /** Marqueur d'explosion complète, posé en dernier (voir classe). */
    static final String MARQUEUR = ".forge-exploded";

    /** Chemin de l'API AAR dans le zip. */
    private static final String CHEMIN_CLASSES = "classes.jar";

    /** Préfixe des jars imbriqués (vieilles bibliothèques). */
    private static final String CHEMIN_LIBS = "libs/";

    /** Chemins des parties consommées. */
    private static final String CHEMIN_MANIFESTE = "AndroidManifest.xml";
    private static final String CHEMIN_RES = "res";
    private static final String CHEMIN_ASSETS = "assets";
    private static final String CHEMIN_MÉTADONNÉES =
            "META-INF/com/android/build/gradle/aar-metadata.properties";

    /** Attribut {@code package} du manifeste (texte binaire compressé, mais lisible). */
    private static final Pattern PACKAGE_MANIFESTE = Pattern.compile(
            "<manifest\\b[^>]*\\bpackage\\s*=\\s*\"([^\"]+)\"");

    /** Ligne {@code minCompileSdk=N} des métadonnées AAR. */
    private static final Pattern MIN_COMPILE_SDK = Pattern.compile(
            "minCompileSdk\\s*=\\s*(\\d+)");

    /**
     * Verrous par répertoire cible (correctif v0.2.16) : les tâches de modules
     * frères s'exécutent en PARALLÈLE et peuvent résoudre la MÊME coordonnée —
     * deux explosions simultanées du même AAR se sérialisent ici, la seconde
     * relit l'œuvre complète de la première au lieu de la défaire. Un verrou
     * in-process suffit : la coquille arrête les moteurs successifs avant
     * chaque build (un seul processus touche le cache).
     */
    private static final java.util.concurrent.ConcurrentHashMap<Path, Object> VERROUS =
            new java.util.concurrent.ConcurrentHashMap<>();

    private final Path répertoire;
    private final Path classesJar;
    private final List<Path> jarsBibliothèques;
    private final boolean aManifeste;

    private AarExplodé(Path répertoire, Path classesJar, List<Path> jarsBibliothèques,
                       boolean aManifeste) {
        this.répertoire = répertoire;
        this.classesJar = classesJar;
        this.jarsBibliothèques = jarsBibliothèques;
        this.aManifeste = aManifeste;
    }

    /**
     * Explose (ou relit) un AAR dans un répertoire du cache.
     *
     * @param aar        fichier {@code .aar} (doit exister).
     * @param répertoire répertoire d'explosion (frère de l'archive dans le cache).
     * @return l'AAR explosé.
     * @throws IOException si l'archive est illisible, traverse la cible (zip-slip)
     *                     ou que l'explosion échoue.
     */
    public static AarExplodé exploser(Path aar, Path répertoire) throws IOException {
        Object verrou = VERROUS.computeIfAbsent(
                répertoire.toAbsolutePath(), p -> new Object());
        synchronized (verrou) {
            return exploserSousVerrou(aar, répertoire);
        }
    }

    private static AarExplodé exploserSousVerrou(Path aar, Path répertoire)
            throws IOException {
        if (Files.isRegularFile(répertoire.resolve(MARQUEUR))) {
            return relire(répertoire);
        }
        // Explosion interrompue : un répertoire sans marqueur est un cadavre.
        supprimer(répertoire);
        // Provisoire frère UNIQUE PAR APPEL (createTempDirectory, API 26+
        // d'Android — disponible sur ART comme sur JVM). JAMAIS
        // java.lang.ProcessHandle : cette API de bureau (Java 9) est absente
        // d'ART et le rapport appareil v0.2.15 est mort au premier AAR explosé
        // de « Failed resolution of: Ljava/lang/ProcessHandle; » — de plus, un
        // pid est CONSTANT dans un processus : il n'aurait de toute façon pas
        // distingué deux appels concurrents.
        Path parent = répertoire.getParent();
        if (parent == null) {
            throw new IOException("répertoire d'explosion sans parent : " + répertoire);
        }
        Files.createDirectories(parent);
        Path provisoire = Files.createTempDirectory(
                parent, répertoire.getFileName() + ".part-");
        List<Path> jarsLibs = new ArrayList<>();
        boolean aClasses = false;
        boolean aManifeste = false;
        try (ZipFile zip = new ZipFile(aar.toFile())) {
            for (java.util.Enumeration<? extends ZipEntry> entrées = zip.entries();
                 entrées.hasMoreElements(); ) {
                ZipEntry entrée = entrées.nextElement();
                if (entrée.isDirectory()) {
                    continue;
                }
                String nom = entrée.getName();
                Path cible = cibleSûre(provisoire, nom);
                if (nom.equals(CHEMIN_CLASSES)) {
                    Files.createDirectories(cible.getParent());
                    copier(zip, entrée, cible);
                    aClasses = true;
                } else if (nom.startsWith(CHEMIN_LIBS) && nom.endsWith(".jar")) {
                    Files.createDirectories(cible.getParent());
                    copier(zip, entrée, cible);
                    jarsLibs.add(cible);
                } else if (nom.equals(CHEMIN_MANIFESTE)) {
                    Files.createDirectories(cible.getParent());
                    copier(zip, entrée, cible);
                    aManifeste = true;
                } else if (nom.startsWith(CHEMIN_RES + "/") || nom.startsWith(CHEMIN_ASSETS + "/")
                        || nom.equals(CHEMIN_MÉTADONNÉES)) {
                    Files.createDirectories(cible.getParent());
                    copier(zip, entrée, cible);
                }
                // Le reste (proguard.txt, lint.jar, inspector.jar, baseline-prof,
                // R.txt…) n'est pas consommé en v1 — écart consigné.
            }
        }
        if (!aClasses) {
            // Le classpath exige un jar VALIDE même vide (d8 comme ART).
            écrireJarVide(provisoire.resolve(CHEMIN_CLASSES));
        }
        Files.writeString(provisoire.resolve(MARQUEUR), "ok");
        try {
            Files.move(provisoire, répertoire, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException pasAtomique) {
            // Système de fichiers sans renommage atomique de répertoire : la
            // cible est déjà purgée, un renommage simple suffit.
            Files.move(provisoire, répertoire, StandardCopyOption.REPLACE_EXISTING);
        }
        // Les chemins des jars imbriqués ont été bâtis sous le provisoire : les
        // re-bâtir sous le répertoire FINAL (celui qui vit en cache), triés.
        List<Path> libsFinales = new ArrayList<>();
        for (Path jar : jarsLibs) {
            libsFinales.add(répertoire.resolve(provisoire.relativize(jar)));
        }
        libsFinales.sort(Comparator.comparing(Path::toString));
        return new AarExplodé(répertoire, répertoire.resolve(CHEMIN_CLASSES),
                List.copyOf(libsFinales), aManifeste);
    }

    /** Relit une explosion complète déjà en cache. */
    private static AarExplodé relire(Path répertoire) {
        List<Path> jarsLibs = new ArrayList<>();
        Path libs = répertoire.resolve(CHEMIN_LIBS);
        if (Files.isDirectory(libs)) {
            try (var parcours = Files.list(libs)) {
                parcours.filter(fichier -> fichier.toString().endsWith(".jar"))
                        .forEach(jarsLibs::add);
            } catch (IOException illisible) {
                jarsLibs.clear();
            }
        }
        jarsLibs.sort(Comparator.comparing(Path::toString));
        return new AarExplodé(répertoire, répertoire.resolve(CHEMIN_CLASSES),
                List.copyOf(jarsLibs),
                Files.isRegularFile(répertoire.resolve(CHEMIN_MANIFESTE)));
    }

    /**
     * Cible d'une entrée de zip, refusée si elle sort du répertoire d'explosion
     * (zip-slip) ou traverse un lien.
     */
    private static Path cibleSûre(Path racine, String nom) throws IOException {
        Path cible = racine.resolve(nom).normalize();
        if (!cible.startsWith(racine) || Files.exists(cible)
                && !Files.isRegularFile(cible)) {
            throw new IOException("entrée d'AAR malveillante ou invalide : " + nom);
        }
        return cible;
    }

    private static void copier(ZipFile zip, ZipEntry entrée, Path cible)
            throws IOException {
        try (var entrant = zip.getInputStream(entrée);
             var sortant = Files.newOutputStream(cible)) {
            entrant.transferTo(sortant);
        }
    }

    /** Un jar vide mais VALIDE (22 octets, zéro entrée). */
    private static void écrireJarVide(Path cible) throws IOException {
        Files.createDirectories(cible.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(cible))) {
            zip.finish();
        }
    }

    private static void supprimer(Path répertoire) throws IOException {
        if (!Files.exists(répertoire)) {
            return;
        }
        try (var parcours = Files.walk(répertoire)) {
            parcours.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException impossible) {
                    // La réécriture écrasera ce qui reste.
                }
            });
        }
    }

    /**
     * Le jar de classes de la bibliothèque (jamais absent : un AAR sans classes
     * reçoit un jar vide).
     *
     * @return {@code <répertoire>/classes.jar}.
     */
    public Path classesJar() {
        return classesJar;
    }

    /**
     * Les jars imbriqués {@code libs/*.jar} (triés, déterminisme des classpaths).
     *
     * @return la liste (vide pour un AAR moderne).
     */
    public List<Path> jarsBibliothèques() {
        return jarsBibliothèques;
    }

    /**
     * Répertoire de ressources de la bibliothèque, à fusionner par aapt2.
     *
     * @return le répertoire s'il existe.
     */
    public Optional<Path> resDir() {
        return répertoirePartagé(CHEMIN_RES);
    }

    /**
     * Répertoire d'assets de la bibliothèque, à empaqueter.
     *
     * @return le répertoire s'il existe.
     */
    public Optional<Path> assetsDir() {
        return répertoirePartagé(CHEMIN_ASSETS);
    }

    /**
     * Manifeste Android de la bibliothèque (fusion + paquet).
     *
     * @return le fichier s'il existe.
     */
    public Optional<Path> manifeste() {
        return aManifeste ? Optional.of(répertoire.resolve(CHEMIN_MANIFESTE))
                : Optional.empty();
    }

    /**
     * Attribut {@code package} du manifeste de la bibliothèque : c'est lui qui
     * devient une entrée {@code --extra-packages} de la liaison aapt2 (les classes
     * de la bibliothèque référencent leur {@code R} propre, régénéré par l'app).
     *
     * @return le paquet, ou vide si le manifeste est absent ou muet.
     */
    public Optional<String> packageDuManifeste() {
        return manifeste().flatMap(AarExplodé::lirePackage);
    }

    /**
     * La borne {@code minCompileSdk} des métadonnées AAG d'AGP : la bibliothèque
     * exige ce niveau d'API À LA COMPILATION (un compose 1.7 réclame 34) — la
     * dépasser côté application est une erreur de build, pas d'exécution.
     *
     * @return la borne si les métadonnées la déclarent.
     */
    public OptionalInt minCompileSdk() {
        Path métadonnées = répertoire.resolve(CHEMIN_MÉTADONNÉES);
        if (!Files.isRegularFile(métadonnées)) {
            return OptionalInt.empty();
        }
        try {
            Matcher chercheur = MIN_COMPILE_SDK.matcher(
                    Files.readString(métadonnées, StandardCharsets.UTF_8));
            return chercheur.find() ? OptionalInt.of(Integer.parseInt(chercheur.group(1)))
                    : OptionalInt.empty();
        } catch (IOException illisible) {
            return OptionalInt.empty();
        }
    }

    private Optional<Path> répertoirePartagé(String nom) {
        Path dir = répertoire.resolve(nom);
        return Files.isDirectory(dir) ? Optional.of(dir) : Optional.empty();
    }

    private static Optional<String> lirePackage(Path manifeste) {
        try {
            Matcher chercheur = PACKAGE_MANIFESTE.matcher(
                    Files.readString(manifeste, StandardCharsets.UTF_8));
            return chercheur.find() ? Optional.of(chercheur.group(1)) : Optional.empty();
        } catch (IOException illisible) {
            return Optional.empty();
        }
    }
}
