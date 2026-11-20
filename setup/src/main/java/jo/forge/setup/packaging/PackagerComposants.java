package jo.forge.setup.packaging;

import jo.forge.setup.manifest.ComponentManifest;
import jo.forge.setup.manifest.EngineManifest;
import jo.forge.setup.manifest.ManifestCodec;
import jo.forge.setup.util.Files2;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Assemblage des composants du moteur et du manifeste signé (chaîne de publication,
 * étape 12) : dexage par d8 en sous-processus borné, zips déterministes, manifeste
 * JSON via le codec de l'installateur, signature optionnelle.
 *
 * <p>Composants produits (découpage D-06) : {@code engine-core} (api, json, client,
 * moteur — dexés ensemble), {@code java-compiler} (ECJ dexé), {@code dexeur} (d8/R8
 * dexé), {@code signeur} (apksig dexé), {@code aapt2-&lt;abi&gt;} (binaire),
 * {@code platform-&lt;api&gt;} (android.jar). Les composants d'outils absents de la
 * machine sont omis avec un avertissement : la CI, qui a le SDK complet, produit
 * l'ensemble entier.</p>
 *
 * <p>Layout interne des archives — il épouse la convention d'installation des étapes
 * 3-4 (chaque entrée est posée à son chemin de destination, l'extracteur ne fait
 * aucun remappage) : le dex du cœur s'appelle {@code engine.jar} à la racine du
 * répertoire moteur, les dex compagnons vivent en {@code lib/&lt;id&gt;.jar}, le binaire
 * aapt2 en {@code components/aapt2-&lt;abi&gt;/aapt2} et la plate-forme en
 * {@code components/platform-&lt;api&gt;/android.jar} (chemins attendus par la session
 * de la coquille et par le localisateur d'outils du moteur).</p>
 *
 * <p><b>Ressources conservées à côté du dex</b> (correctif v0.2.4) : d8 ne convertit
 * que les classes — les autres entrées des jars sources (messages localisés, tables du
 * parseur) étaient perdues au dexage, et ECJ mourait sur l'appareil au premier message
 * compilé ({@code Can't find bundle for base name …batch.messages} : un
 * {@link java.util.ResourceBundle} ne trouve que ce que le classloader sert). Or sur
 * ART le classpath de la VM moteur est fait de zips, et le {@code findResource} de
 * {@code DexPathList} sert les entrées zip d'un jar du classpath (API 28+) :
 * les jars dexés embarquent donc aussi, à côté de {@code classes.dex}, toute entrée
 * non-classe des jars sources — sauf manifeste et signatures, sans valeur une fois
 * fusionnés. {@code about.html} d'ECJ est conservé : c'est la notice EPL-2.0 exigée
 * à la redistribution (distribution.md §7).</p>
 *
 * <p><b>Composants portant l'API java.compiler</b> (correctif v0.2.6) : ART
 * n'embarque pas le module {@code java.compiler} alors qu'ECJ référence 63 de
 * ses classes — dont {@code javax.lang.model.SourceVersion} sur le chemin par
 * lots ({@code FileSystem} l'appelle à chaque compilation : crash réel sur
 * appareil, {@code Failed resolution of: Ljavax/lang/model/SourceVersion;}). Le
 * composant {@code java-compiler} embarque donc, à côté d'ECJ, l'API du module
 * extraite de la JVM d'assemblage (image {@code jrt}) — MOINS
 * {@code javax.lang.model.SourceVersion}, dont l'initialiseur statique appelle
 * {@code Runtime.version()} (absent d'ART et non redéfinissable : {@code java.*}
 * est interdit dans un dex), remplacé par la réécriture de
 * {@code --compat-art} compilée par ECJ avec {@code android.jar} en chemin
 * d'amorçage. Un garde-fou vérifie ensuite que TOUTE classe de l'API référencée
 * par ECJ est servie par le jar produit (voir
 * {@link #vérifierRéférencesApi}).</p>
 *
 * <p>Usage :</p>
 * <pre>
 *   java jo.forge.setup.packaging.PackagerComposants
 *       --sortie &lt;répertoire&gt; --version &lt;x.y.z&gt;
 *       --d8 &lt;r8.jar&gt; --android-jar &lt;android.jar&gt; [--min-api 28]
 *       [--moteur jar1 jar2 …] [--compilateur ecj.jar]
 *       [--compat-art &lt;répertoire de sources&gt;]
 *       [--dexeur r8.jar] [--signeur apksig.jar] [--aapt2 binaire --abi arm64-v8a]
 *       [--clé-privée &lt;pkcs8-b64&gt;] [--sans-outils-android]
 * </pre>
 *
 * <p>{@code --sans-outils-android} omet les composants redistribuables de la
 * plate-forme ({@code platform-&lt;api&gt;} et {@code aapt2-&lt;abi&gt;}) tout en conservant
 * {@code android.jar} pour le DEXAGE (modélisation de bibliothèque de d8, jamais
 * redistribué). Depuis v0.2.7 la publication ne l'emploie plus : le composant
 * {@code platform-&lt;api&gt;} EST publié (sans lui, ECJ ne résout pas
 * {@code java.lang.Object} sur ART — correctif du build appareil) ; seul
 * {@code aapt2-&lt;abi&gt;} reste omis tant que sa construction arm64 n'existe pas
 * (le prébuilt des build-tools est x86_64/Linux) — l'assembleur Gradle le tient
 * à jour via {@code FORGE_SANS_AAPT2}.</p>
 */
public final class PackagerComposants {

    /** Horodatage fixe des entrées de zip (déterminisme des composants). */
    private static final long HORODATAGE_FIXE = 315532800000L;

    /** Délai maximal d'un passage d8 (le gros jar, r8, prend environ une minute). */
    private static final long DÉLAI_D8_MS = TimeUnit.MINUTES.toMillis(10);

    /** Paquets de l'API java.compiler, absents d'ART (module non embarqué). */
    private static final List<String> PRÉFIXES_API = List.of(
            "javax/lang/model/", "javax/annotation/processing/", "javax/tools/");

    /** Classe de l'API réécrite pour ART (l'original lit {@code Runtime.version()}). */
    static final String SOURCE_VERSION_RÉÉCRIT = "javax/lang/model/SourceVersion";

    /** Notice OpenJDK embarquée dans le jar API (distribution.md §7). */
    private static final String NOTICE_OPENJDK = ""
            + "L'API javax.lang.model / javax.annotation.processing / javax.tools de ce\n"
            + "fichier provient du module java.compiler de l'OpenJDK (GPLv2 avec\n"
            + "exception de classpath), extraite à l'assemblage par Forge.\n"
            + "javax.lang.model.SourceVersion est une réécriture ART-sûre de Forge\n"
            + "(Apache-2.0) : l'original appelle Runtime.version(), absent d'ART.\n";

    private PackagerComposants() {
    }

    /**
     * Point d'entrée de l'assembleur.
     *
     * @param args arguments nommés (voir la javadoc de classe).
     * @throws Exception en cas d'échec (message lisible, code 1).
     */
    public static void main(String[] args) throws Exception {
        Map<String, List<String>> options = lireArguments(args);
        Path sortie = Path.of(obligatoire(options, "sortie"));
        String version = obligatoire(options, "version");
        Path d8 = Path.of(obligatoire(options, "d8"));
        Path androidJar = options.containsKey("android-jar")
                ? Path.of(obligatoire(options, "android-jar")) : null;
        boolean sansOutilsAndroid = options.containsKey("sans-outils-android");
        String cléPrivée = options.containsKey("clé-privée")
                ? Files.readString(Path.of(obligatoire(options, "clé-privée"))) : null;
        Files.createDirectories(sortie);

        List<ComponentManifest> composants = new ArrayList<>();
        Path dirCompatArt = options.containsKey("compat-art")
                ? Path.of(obligatoire(options, "compat-art")) : null;
        if (options.containsKey("moteur")) {
            composants.add(composantDexé(sortie, version, "engine-core",
                    "cœur du moteur (contrat, json, client, pipelines)",
                    List.of(), d8, androidJar, jars(options, "moteur")));
        }
        if (options.containsKey("compilateur")) {
            List<Path> jarsCompilateur = new ArrayList<>(jars(options, "compilateur"));
            if (dirCompatArt != null) {
                if (androidJar == null || !Files.isRegularFile(androidJar)) {
                    throw new IOException("--compat-art exige --android-jar "
                            + "(chemin d'amorçage de la compilation des sources de "
                            + "compatibilité)");
                }
                jarsCompilateur.add(assemblerApiCompiler(jarsCompilateur.get(0),
                        androidJar, dirCompatArt));
            }
            composants.add(composantDexé(sortie, version, "java-compiler",
                    "compilateur Java embarqué (ECJ) et API java.compiler pour ART",
                    List.of("engine-core"),
                    d8, androidJar, jarsCompilateur));
        }
        if (options.containsKey("dexeur")) {
            composants.add(composantDexé(sortie, version, "dexeur",
                    "convertisseur de bytecode (d8/R8)", List.of("engine-core"),
                    d8, androidJar, jars(options, "dexeur")));
        }
        if (options.containsKey("signeur")) {
            composants.add(composantDexé(sortie, version, "signeur",
                    "signature d'APK (apksig)", List.of("engine-core"),
                    d8, androidJar, jars(options, "signeur")));
        }
        if (options.containsKey("aapt2") && !sansOutilsAndroid) {
            String abi = options.getOrDefault("abi", List.of("arm64-v8a")).get(0);
            composants.add(composantFichier(sortie, version, "aapt2-" + abi,
                    "outil de ressources Android (aapt2)", List.of("engine-core"),
                    List.of(abi), Path.of(obligatoire(options, "aapt2"))));
        }
        if (sansOutilsAndroid) {
            System.out.println("[avertissement] composants d'outils Android omis "
                    + "(option --sans-outils-android : garde juridique "
                    + "docs/distribution.md §7)");
        } else if (androidJar != null && Files.isRegularFile(androidJar)) {
            int api = options.containsKey("min-api")
                    ? Integer.parseInt(obligatoire(options, "min-api"))
                    : versionApi(androidJar);
            composants.add(composantFichier(sortie, version, "platform-" + api,
                    "plate-forme Android de compilation (android.jar)",
                    List.of("engine-core"), List.of("all"), androidJar));
        } else {
            System.err.println("[avertissement] android.jar absent : composant "
                    + "platform-N omis (la CI le produit avec le SDK complet)");
        }

        if (composants.isEmpty()) {
            throw new IOException("aucun composant à assembler — précisez au moins "
                    + "--moteur");
        }

        EngineManifest manifeste = new EngineManifest(1, version, 1,
                LocalDate.now().toString(), composants);
        Path fichierManifeste = sortie.resolve("manifest.json");
        Files.writeString(fichierManifeste, ManifestCodec.write(manifeste));
        System.out.println("Manifeste écrit : " + fichierManifeste + " ("
                + composants.size() + " composants)");

        if (cléPrivée != null) {
            byte[] signature = signer(fichierManifeste, cléPrivée);
            Path fichierSignature = sortie.resolve("manifest.sig");
            Files.writeString(fichierSignature,
                    Base64.getEncoder().encodeToString(signature));
            System.out.println("Manifeste SIGNÉ : " + fichierSignature);
        } else {
            System.out.println("Manifeste non signé (aucune clé privée fournie) — "
                    + "signez-le avec OutilManifeste avant publication");
        }
    }

    /**
     * Assemble un composant dexé : d8 sur les jars, classes.dex dans un zip.
     */
    private static ComponentManifest composantDexé(Path sortie, String version,
            String id, String description, List<String> dépendances, Path d8,
            Path androidJar, List<Path> jars) throws IOException, InterruptedException {
        if (androidJar == null || !Files.isRegularFile(androidJar)) {
            throw new IOException("composant " + id + " : --android-jar requis "
                    + "pour le dexage");
        }
        Path travail = Files.createTempDirectory("forge-dex-" + id);
        List<String> commande = new ArrayList<>(List.of(
                java(), "-Xmx3g", "-cp", d8.toString(),
                "com.android.tools.r8.D8", "--release", "--min-api", "28",
                "--lib", androidJar.toString(),
                "--output", travail.toString()));
        jars.forEach(jar -> commande.add(jar.toString()));
        System.out.println("[composant " + id + "] dexage de " + jars.size()
                + " jar(s)…");
        ProcessBuilder constructeur = new ProcessBuilder(commande)
                .redirectErrorStream(true);
        Process processus = constructeur.start();
        byte[] bavardage = processus.getInputStream().readAllBytes();
        boolean fini = processus.waitFor(DÉLAI_D8_MS, TimeUnit.MILLISECONDS);
        if (!fini) {
            processus.destroyForcibly();
            throw new IOException("composant " + id + " : d8 a dépassé "
                    + TimeUnit.MILLISECONDS.toMinutes(DÉLAI_D8_MS) + " minutes");
        }
        if (processus.exitValue() != 0) {
            throw new IOException("composant " + id + " : d8 a échoué — "
                    + new String(bavardage, java.nio.charset.StandardCharsets.UTF_8));
        }
        Path dex = travail.resolve("classes.dex");
        if (!Files.isRegularFile(dex)) {
            throw new IOException("composant " + id + " : d8 n'a pas produit de dex");
        }
        Path archive = sortie.resolve(id + "-" + version + ".zip");
        emballerJarDexe(dex, archive, cheminDexe(id), jars);
        System.out.println("[composant " + id + "] " + archive.getFileName() + " ("
                + Files.size(archive) + " octets) — " + description);
        return manifesteDe(archive, version, List.of("all"), dépendances);
    }

    /**
     * Assemble un composant fichier unique (binaire aapt2, android.jar…).
     */
    private static ComponentManifest composantFichier(Path sortie, String version,
            String id, String description, List<String> dépendances,
            List<String> abis, Path fichier) throws IOException {
        Path archive = sortie.resolve(id + "-" + version + ".zip");
        zipperFichier(archive, fichier, cheminOutil(id));
        System.out.println("[composant " + id + "] " + archive.getFileName() + " ("
                + Files.size(archive) + " octets) — " + description);
        return manifesteDe(archive, version, abis, dépendances);
    }

    /**
     * Assemble le jar de l'API java.compiler pour ART (correctif v0.2.6) :
     * classes du module extraites de l'image {@code jrt} de la JVM d'assemblage,
     * MOINS {@code javax.lang.model.SourceVersion} (fatal sur ART : son
     * initialiseur lit {@code Runtime.version()}) remplacé par la réécriture de
     * {@code dirSources} compilée par ECJ avec {@code android.jar} en chemin
     * d'amorçage.
     *
     * <p>Garde-fou : toute classe des paquets de l'API référencée par le jar du
     * compilateur doit être servie par le jar produit — sinon échec nommé de
     * l'assemblage (un ECJ futur qui référencerait une classe de plus casse la
     * chaîne de publication proprement, pas le build sur l'appareil).</p>
     *
     * @param jarCompilateur jar ECJ (compilateur ET source des références à couvrir).
     * @param androidJar     plate-forme Android (chemin d'amorçage de la compilation).
     * @param dirSources     sources de compatibilité ({@code --compat-art}).
     * @return le jar assemblé (fichier temporaire, à dexer avec ECJ).
     * @throws IOException          en cas d'échec de lecture, compilation ou vérification.
     * @throws InterruptedException si la compilation ECJ est interrompue.
     */
    static Path assemblerApiCompiler(Path jarCompilateur, Path androidJar,
                                     Path dirSources)
            throws IOException, InterruptedException {
        Path classesCompat = Files.createTempDirectory("forge-compat-art");
        Path jarSortie = Files.createTempFile("forge-api-java-compiler", ".jar");
        compilerParEcj(jarCompilateur, androidJar, dirSources, classesCompat);
        if (!Files.isRegularFile(classesCompat.resolve(
                SOURCE_VERSION_RÉÉCRIT + ".class"))) {
            throw new IOException("la compilation n'a pas produit "
                    + SOURCE_VERSION_RÉÉCRIT + " — sources de --compat-art inattendues");
        }
        SortedMap<String, byte[]> classes = classesApiDepuisJrt();
        // La compat REMPLACE les classes du JDK : seul SourceVersion a ce droit.
        ajouterClassesCompilées(classes, classesCompat);
        vérifierRéférencesApi(jarCompilateur, classes.keySet());
        écrireJarApi(jarSortie, classes);
        System.out.println("[composant java-compiler] API java.compiler pour ART : "
                + classes.size() + " classes (" + SOURCE_VERSION_RÉÉCRIT
                + " réécrit pour ART)");
        return jarSortie;
    }

    /**
     * Compile les sources de compatibilité avec ECJ lui-même, en Java 8
     * (dexable partout), {@code android.jar} en chemin d'amorçage : c'est la
     * seule façon de définir un type dans un paquet possédé par la plateforme
     * ({@code javax.lang.model}) sans conflit avec le module {@code java.compiler}
     * de la JVM qui compile.
     */
    private static void compilerParEcj(Path jarEcj, Path androidJar, Path dirSources,
                                       Path sortieClasses)
            throws IOException, InterruptedException {
        List<Path> sources;
        try (java.util.stream.Stream<Path> parcours = Files.walk(dirSources)) {
            sources = parcours.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }
        if (sources.isEmpty()) {
            throw new IOException("aucune source de compatibilité ART dans "
                    + dirSources);
        }
        List<String> commande = new ArrayList<>(List.of(
                java(), "-cp", jarEcj.toString(),
                "org.eclipse.jdt.internal.compiler.batch.Main",
                "-1.8", "-nowarn", "-encoding", "UTF-8",
                "-bootclasspath", androidJar.toString(),
                "-d", sortieClasses.toString()));
        sources.forEach(source -> commande.add(source.toString()));
        Process processus = new ProcessBuilder(commande)
                .redirectErrorStream(true)
                .start();
        byte[] bavardage = processus.getInputStream().readAllBytes();
        boolean fini = processus.waitFor(DÉLAI_D8_MS, TimeUnit.MILLISECONDS);
        if (!fini) {
            processus.destroyForcibly();
            throw new IOException("compat ART : ECJ a dépassé "
                    + TimeUnit.MILLISECONDS.toMinutes(DÉLAI_D8_MS) + " minutes");
        }
        if (processus.exitValue() != 0) {
            throw new IOException("compat ART : compilation ECJ échouée — "
                    + new String(bavardage, java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    /**
     * Extrait du module {@code java.compiler} de la JVM d'assemblage toutes les
     * classes sous {@code javax/} ({@code module-info} exclu).
     *
     * <p>La JVM d'assemblage doit être un JDK (ou JRE) 9+ portant le module —
     * vérifié pour JDK 17 (CI) et JRE 21. Le contenu suit la version de cette
     * JVM : la CI l'épingle, ce qui rend les composants reproductibles.</p>
     *
     * @return classes vers octets, triées par chemin.
     * @throws IOException si l'image {@code jrt} ou le module est indisponible.
     */
    static SortedMap<String, byte[]> classesApiDepuisJrt() throws IOException {
        java.nio.file.FileSystem jrt;
        try {
            jrt = java.nio.file.FileSystems.getFileSystem(URI.create("jrt:/"));
        } catch (Exception pasDeJrt) {
            throw new IOException("la JVM d'assemblage n'expose pas d'image jrt "
                    + "(JDK 9+ requis pour extraire l'API java.compiler)", pasDeJrt);
        }
        Path module = jrt.getPath("/modules/java.compiler");
        if (!Files.isDirectory(module)) {
            throw new IOException("module java.compiler absent de la JVM "
                    + "d'assemblage — assemblez les composants sur un JDK 9+ complet");
        }
        List<Path> fichiers = new ArrayList<>();
        try (java.util.stream.Stream<Path> parcours = Files.walk(module)) {
            parcours.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".class"))
                    .filter(p -> !p.getFileName().toString().equals("module-info.class"))
                    .filter(p -> p.startsWith(module.resolve("javax")))
                    .forEach(fichiers::add);
        }
        SortedMap<String, byte[]> classes = new TreeMap<>();
        for (Path fichier : fichiers) {
            classes.put(module.relativize(fichier).toString(),
                    Files.readAllBytes(fichier));
        }
        return classes;
    }

    /**
     * Ajoute au jar API les classes compilées de la compat ART (chemin relatif
     * au répertoire de sortie d'ECJ). Le remplacement d'une classe du module
     * n'est permis que pour {@link #SOURCE_VERSION_RÉÉCRIT} — tout autre
     * masquage est une erreur (sources de compat inattendues).
     */
    private static void ajouterClassesCompilées(SortedMap<String, byte[]> classes,
                                                Path classesCompat) throws IOException {
        List<Path> fichiers;
        try (java.util.stream.Stream<Path> parcours = Files.walk(classesCompat)) {
            fichiers = parcours.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".class"))
                    .sorted()
                    .toList();
        }
        for (Path fichier : fichiers) {
            String nom = classesCompat.relativize(fichier).toString();
            byte[] précédent = classes.put(nom, Files.readAllBytes(fichier));
            if (précédent != null
                    && !nom.equals(SOURCE_VERSION_RÉÉCRIT + ".class")) {
                throw new IOException("la classe de compatibilité " + nom
                        + " masque une classe du module java.compiler — seul "
                        + SOURCE_VERSION_RÉÉCRIT + " a ce droit");
            }
        }
    }

    /**
     * Garde-fou de l'API embarquée : toute classe des paquets
     * {@code javax.lang.model}, {@code javax.annotation.processing} et
     * {@code javax.tools} référencée par le jar du compilateur doit être servie
     * par le jar API — sinon échec nommé (le composant ne doit jamais partir
     * incomplet : c'est ce qui tuait {@code core:compileJava} sur l'appareil).
     *
     * @param jarCompilateur jar ECJ dont on lit les références.
     * @param classesServies chemins de classes servis par le jar API.
     * @throws IOException nommant les classes manquantes.
     */
    static void vérifierRéférencesApi(Path jarCompilateur, java.util.Set<String> classesServies)
            throws IOException {
        List<String> manquantes = AnalyseurReferences.classesRéférencées(jarCompilateur)
                .stream()
                .filter(référence -> PRÉFIXES_API.stream().anyMatch(référence::startsWith))
                .filter(référence -> !classesServies.contains(référence + ".class"))
                .sorted()
                .toList();
        if (!manquantes.isEmpty()) {
            throw new IOException("classes de l'API java.compiler référencées par "
                    + "le compilateur mais absentes du jar API : " + manquantes);
        }
    }

    /**
     * Écrit le jar API : entrées triées, horodatage fixe (déterminisme), notice
     * OpenJDK à la racine (elle traversera le dexage avec les autres ressources
     * et restera lisible sur l'appareil).
     */
    private static void écrireJarApi(Path jarSortie, SortedMap<String, byte[]> classes)
            throws IOException {
        Path temporaire = jarSortie.resolveSibling(jarSortie.getFileName() + ".part");
        try (ZipOutputStream jar = new ZipOutputStream(
                Files.newOutputStream(temporaire))) {
            for (Map.Entry<String, byte[]> classe : classes.entrySet()) {
                ZipEntry entrée = new ZipEntry(classe.getKey());
                entrée.setTime(HORODATAGE_FIXE);
                jar.putNextEntry(entrée);
                jar.write(classe.getValue());
                jar.closeEntry();
            }
            ZipEntry notice = new ZipEntry("about-openjdk-java-compiler.txt");
            notice.setTime(HORODATAGE_FIXE);
            jar.putNextEntry(notice);
            jar.write(NOTICE_OPENJDK.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        Files.move(temporaire, jarSortie, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Chemin d'installation d'un composant dexé : le cœur devient {@code engine.jar}
     * (première entrée du CLASSPATH de la VM moteur), les compagnons vivent en
     * {@code lib/<id>.jar} (ajoutés au CLASSPATH par la session de la coquille).
     */
    static String cheminDexe(String id) {
        return "engine-core".equals(id) ? "engine.jar" : "lib/" + id + ".jar";
    }

    /**
     * Chemin d'installation d'un composant d'outil : sous {@code components/<id>/},
     * avec le nom attendu par le localisateur du moteur (aapt2 par ABI, android.jar
     * par niveau d'API).
     */
    static String cheminOutil(String id) {
        String nom = id.startsWith("platform-") ? "android.jar" : "aapt2";
        return "components/" + id + "/" + nom;
    }

    /**
     * Emballe un dex produit par d8 en jar (zip : {@code classes.dex} puis, dans l'ordre
     * alphabétique, les ressources des jars sources), puis l'archive du composant avec ce
     * jar posé à son chemin de destination. ART charge le {@code classes.dex} d'un jar
     * exactement comme celui d'un APK, et son classloader sert les autres entrées du zip
     * à {@code getResource} — c'est ce qui rend les messages d'ECJ trouvables sur appareil.
     *
     * @param dex               fichier {@code classes.dex} produit par d8.
     * @param archive           archive du composant à écrire ({@code <id>-<version>.zip}).
     * @param cheminDestination chemin d'installation du jar dans le répertoire moteur.
     * @param jarsSources       jars dexés (leurs ressources non-classe sont conservées).
     */
    static void emballerJarDexe(Path dex, Path archive, String cheminDestination,
                                List<Path> jarsSources) throws IOException {
        Path travail = Files.createTempFile("forge-jar-dexe", ".jar");
        try {
            try (ZipOutputStream jar = new ZipOutputStream(
                    Files.newOutputStream(travail))) {
                ZipEntry entree = new ZipEntry("classes.dex");
                entree.setTime(HORODATAGE_FIXE);
                jar.putNextEntry(entree);
                Files.copy(dex, jar);
                jar.closeEntry();
                for (Map.Entry<String, byte[]> ressource
                        : ressourcesConservées(jarsSources).entrySet()) {
                    ZipEntry entréeRessource = new ZipEntry(ressource.getKey());
                    entréeRessource.setTime(HORODATAGE_FIXE);
                    jar.putNextEntry(entréeRessource);
                    jar.write(ressource.getValue());
                    jar.closeEntry();
                }
            }
            zipperFichier(archive, travail, cheminDestination);
        } finally {
            Files.deleteIfExists(travail);
        }
    }

    /**
     * Collecte les entrées à conserver des jars sources, chemin vers contenu : toute
     * entrée non-classe sauf répertoires, manifeste et fichiers de signature (une fois
     * fusionnés dans notre jar, ils ne signent plus rien et tromperaient la vérification).
     *
     * <p>Doublons : deux jars qui portent le même chemin avec le même contenu ne
     * l'écrivent qu'une fois ; le même chemin avec des contenus différents échoue — un
     * composant dexé fusionné ne doit pas résoudre ses conflits de ressources au hasard
     * de l'ordre des jars.</p>
     *
     * @param jarsSources jars dexés dans le même composant.
     * @return entrées triées par chemin (déterminisme du jar produit).
     */
    private static SortedMap<String, byte[]> ressourcesConservées(List<Path> jarsSources)
            throws IOException {
        SortedMap<String, byte[]> conservées = new TreeMap<>();
        for (Path jarSource : jarsSources) {
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(
                    jarSource.toFile())) {
                for (ZipEntry entrée : zip.stream()
                        .filter(e -> !e.isDirectory())
                        .sorted(Comparator.comparing(ZipEntry::getName))
                        .toList()) {
                    String chemin = entrée.getName();
                    if (!àConserver(chemin)) {
                        continue;
                    }
                    byte[] contenu = zip.getInputStream(entrée).readAllBytes();
                    byte[] précédent = conservées.putIfAbsent(chemin, contenu);
                    if (précédent != null && !java.util.Arrays.equals(précédent, contenu)) {
                        throw new IOException("ressource en conflit entre jars du composant : "
                                + chemin + " (contenus différents) — fusion ambiguë, refusée");
                    }
                }
            }
        }
        return conservées;
    }

    /**
     * Indique si une entrée de jar doit être conservée à côté du dex : ni classe (d8
     * l'a déjà convertie — la garder alourdirait le composant du poids mort des
     * {@code .class}), ni répertoire (recréé à l'extraction), ni manifeste ni signature
     * du jar d'origine (sans valeur une fois fusionnés). Tout le reste — messages
     * localisés, tables du parseur, notices de licence, services — est servi par le
     * classloader d'ART et doit traverser le dexage.
     */
    private static boolean àConserver(String chemin) {
        if (chemin.endsWith(".class")) {
            return false;
        }
        if (!chemin.startsWith("META-INF/")) {
            return true;
        }
        String nomCourt = chemin.substring("META-INF/".length());
        if (nomCourt.equalsIgnoreCase("MANIFEST.MF")) {
            return false;
        }
        for (String extension : new String[] {".SF", ".RSA", ".DSA", ".EC"}) {
            if (nomCourt.regionMatches(true, nomCourt.length() - extension.length(),
                    extension, 0, extension.length())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Signe le manifeste (SHA-256 avec ECDSA, DER — même schéma que la coquille).
     */
    private static byte[] signer(Path manifeste, String cléPrivéeBase64)
            throws Exception {
        byte[] octetsClé = Base64.getDecoder().decode(base64Nu(cléPrivéeBase64));
        var clé = java.security.KeyFactory.getInstance("EC")
                .generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(octetsClé));
        var signataire = java.security.Signature.getInstance("SHA256withECDSA");
        signataire.initSign(clé);
        signataire.update(Files.readAllBytes(manifeste));
        return signataire.sign();
    }

    /**
     * Base64 sans l'armure PEM éventuelle (le jeton CI est nu, un fichier de
     * développement peut être blindé).
     */
    private static String base64Nu(String texte) {
        StringBuilder nu = new StringBuilder();
        for (String ligne : texte.split("\\r?\\n")) {
            if (!ligne.contains("-----")) {
                nu.append(ligne.trim());
            }
        }
        return nu.toString();
    }

    /**
     * Entrée de manifeste d'une archive produite (taille + somme + URL de release).
     */
    private static ComponentManifest manifesteDe(Path archive, String version,
            List<String> abis, List<String> dépendances) throws IOException {
        return new ComponentManifest(
                nomSansVersion(archive.getFileName().toString()),
                version, Files.size(archive), Files2.sha256(archive),
                List.of("https://github.com/jjoblab/forge/releases/download"
                        + "/v" + version + "/" + archive.getFileName()),
                abis, 1, dépendances);
    }

    /** Identifiant du composant depuis un nom de fichier versionné. */
    private static String nomSansVersion(String nomFichier) {
        return nomFichier.substring(0, nomFichier.lastIndexOf('-'));
    }

    /**
     * Niveau d'API d'un android.jar (nom de répertoire platforms/android-N).
     */
    private static int versionApi(Path androidJar) {
        try {
            String chemin = androidJar.toAbsolutePath().toString();
            int position = chemin.lastIndexOf("android-");
            if (position >= 0) {
                String fin = chemin.substring(position + "android-".length());
                return Integer.parseInt(fin.split("[^0-9]")[0]);
            }
        } catch (Exception ignorée) {
            // Chemin sans convention : repli.
        }
        return 34;
    }

    /**
     * Zip déterministe d'un fichier unique (entrée renommée).
     */
    private static void zipperFichier(Path archive, Path fichier, String nomInterne)
            throws IOException {
        Path temporaire = archive.resolveSibling(archive.getFileName() + ".part");
        try (ZipOutputStream zip = new ZipOutputStream(
                Files.newOutputStream(temporaire))) {
            ZipEntry entrée = new ZipEntry(nomInterne);
            entrée.setTime(HORODATAGE_FIXE);
            zip.putNextEntry(entrée);
            Files.copy(fichier, zip);
            zip.closeEntry();
        }
        try {
            Files.move(temporaire, archive, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException pasAtomique) {
            Files.move(temporaire, archive, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Java exécutable de la machine. */
    private static String java() {
        Path maison = Path.of(System.getProperty("java.home", ""));
        Path candidat = maison.resolve("bin").resolve("java");
        return Files.isRegularFile(candidat) ? candidat.toString() : "java";
    }

    /** Extrait les jars d'une option. */
    private static List<Path> jars(Map<String, List<String>> options, String clé) {
        List<Path> jars = new ArrayList<>();
        for (String valeur : options.get(clé)) {
            Path jar = Path.of(valeur);
            if (!Files.isRegularFile(jar)) {
                throw new IllegalArgumentException("jar introuvable : " + jar);
            }
            jars.add(jar);
        }
        return jars;
    }

    /** Lit les arguments nommés (chaque option peut porter plusieurs valeurs). */
    private static Map<String, List<String>> lireArguments(String[] args) {
        Map<String, List<String>> options = new LinkedHashMap<>();
        String courante = null;
        for (String argument : args) {
            if (argument.startsWith("--")) {
                courante = argument.substring(2);
                options.putIfAbsent(courante, new ArrayList<>());
            } else if (courante != null) {
                options.get(courante).add(argument);
            } else {
                throw new IllegalArgumentException("argument orphelin : " + argument);
            }
        }
        return options;
    }

    private static String obligatoire(Map<String, List<String>> options, String clé) {
        List<String> valeurs = options.get(clé);
        if (valeurs == null || valeurs.isEmpty()) {
            throw new IllegalArgumentException("option --" + clé + " obligatoire");
        }
        return valeurs.get(0);
    }
}
