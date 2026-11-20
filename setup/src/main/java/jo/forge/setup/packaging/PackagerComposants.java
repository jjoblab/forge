package jo.forge.setup.packaging;

import jo.forge.setup.manifest.ComponentManifest;
import jo.forge.setup.manifest.EngineManifest;
import jo.forge.setup.manifest.ManifestCodec;
import jo.forge.setup.util.Files2;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * <p>Usage :</p>
 * <pre>
 *   java jo.forge.setup.packaging.PackagerComposants
 *       --sortie &lt;répertoire&gt; --version &lt;x.y.z&gt;
 *       --d8 &lt;r8.jar&gt; --android-jar &lt;android.jar&gt; [--min-api 28]
 *       [--moteur jar1 jar2 …] [--compilateur ecj.jar] [--dexeur r8.jar]
 *       [--signeur apksig.jar] [--aapt2 binaire --abi arm64-v8a]
 *       [--clé-privée &lt;pkcs8-b64&gt;]
 * </pre>
 */
public final class PackagerComposants {

    /** Horodatage fixe des entrées de zip (déterminisme des composants). */
    private static final long HORODATAGE_FIXE = 315532800000L;

    /** Délai maximal d'un passage d8 (le gros jar, r8, prend environ une minute). */
    private static final long DÉLAI_D8_MS = TimeUnit.MINUTES.toMillis(10);

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
        String cléPrivée = options.containsKey("clé-privée")
                ? Files.readString(Path.of(obligatoire(options, "clé-privée"))) : null;
        Files.createDirectories(sortie);

        List<ComponentManifest> composants = new ArrayList<>();
        if (options.containsKey("moteur")) {
            composants.add(composantDexé(sortie, version, "engine-core",
                    "cœur du moteur (contrat, json, client, pipelines)",
                    List.of(), d8, androidJar, jars(options, "moteur")));
        }
        if (options.containsKey("compilateur")) {
            composants.add(composantDexé(sortie, version, "java-compiler",
                    "compilateur Java embarqué (ECJ)", List.of("engine-core"),
                    d8, androidJar, jars(options, "compilateur")));
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
        if (options.containsKey("aapt2")) {
            String abi = options.getOrDefault("abi", List.of("arm64-v8a")).get(0);
            composants.add(composantFichier(sortie, version, "aapt2-" + abi,
                    "outil de ressources Android (aapt2)", List.of("engine-core"),
                    List.of(abi), Path.of(obligatoire(options, "aapt2"))));
        }
        if (androidJar != null && Files.isRegularFile(androidJar)) {
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
        zipperFichier(archive, dex, "classes.dex");
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
        zipperFichier(archive, fichier, fichier.getFileName().toString());
        System.out.println("[composant " + id + "] " + archive.getFileName() + " ("
                + Files.size(archive) + " octets) — " + description);
        return manifesteDe(archive, version, abis, dépendances);
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
