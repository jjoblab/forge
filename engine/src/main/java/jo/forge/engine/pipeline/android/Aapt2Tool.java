package jo.forge.engine.pipeline.android;

import jo.forge.engine.diag.Diagnostic;
import jo.forge.engine.diag.DiagnosticSink;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Outil {@code aapt2} natif : compilation et liaison des ressources Android.
 *
 * <p>Exécution sous-processus <strong>bornée</strong> (délai d'exécution obligatoire —
 * le moteur de référence n'en met aucun, constat S-8) et <strong>annulable</strong> :
 * l'interruption du thread (annulation D-08) détruit le processus immédiatement, aucun
 * orphelin ne survit au build.</p>
 *
 * <p>Les diagnostics sont demandés par l'analyse de la sortie standard d'erreur
 * (aapt2 n'a pas d'API) au format {@code fichier:ligne[:colonne]: error: message} —
 * les erreurs non localisées deviennent des diagnostics sans position. À l'échec, un
 * <em>autotest</em> ({@code aapt2 version}) distingue « le binaire ne s'exécute pas
 * sur cette machine » (ABI, noyau) de « les entrées sont fausses ».</p>
 *
 * <p>Bit exécutable : l'installateur pose {@code +x} sur tout le contenu du moteur
 * ({@code EngineInstaller.poserLectureSeule}), mais un binaire pointé par la
 * propriété {@code forge.aapt2} ou copié à la main peut ne pas l'être — la première
 * exécution répare le droit (échec propre et lisible s'il ne peut pas l'être) au
 * lieu d'un {@code Permission denied} nu de {@code ProcessBuilder}.</p>
 */
public final class Aapt2Tool {

    /** Outil émetteur des diagnostics. */
    public static final String TOOL = "aapt2";

    /** Délai maximal d'une commande aapt2. */
    static final Duration DÉLAI = Duration.ofMinutes(5);

    /** Ligne aapt2 : chemin:ligne[:colonne][-ligne[:colonne]]: (error|warning): message. */
    private static final Pattern LIGNE_LOCALISÉE = Pattern.compile(
            "^(.+?):(\\d+)(?::(\\d+))?(?:-\\d+(?::\\d+)?)?:\\s*(error|warning):\\s*(.*)$");

    private final Path binaire;

    /** Bit exécutable déjà assuré pour ce binaire (une seule tentative par outil). */
    private boolean exécutableAssuré;

    /**
     * Crée l'outil.
     *
     * @param binaire chemin exécutable d'aapt2 (fourni par le composant installé, par
     *                ABI — décision D-04).
     */
    public Aapt2Tool(Path binaire) {
        this.binaire = binaire;
    }

    /**
     * Chemin du binaire wrappé.
     *
     * @return le chemin.
     */
    public Path binaire() {
        return binaire;
    }

    /**
     * Autotest : la version du binaire s'exécute-t-elle sur cette machine ?
     *
     * @return les lignes de diagnostic à journaliser (vide si tout va bien).
     */
    public List<String> autotest() {
        try {
            Résultat résultat = exécuter(List.of("version"));
            String bannière = (String.join(" ", résultat.sortie) + " "
                    + String.join(" ", résultat.erreurs)).trim();
            if (résultat.succès) {
                return List.of("[diagnostic] aapt2 version répond (" + bannière
                        + ") : le binaire s'exécute, l'échec vient des entrées");
            }
            return List.of("[diagnostic] aapt2 version ÉCHOUE aussi :"
                    + " le binaire ne s'exécute pas sur cette machine (ABI ou noyau ?)"
                    + " — remplacez le composant aapt2");
        } catch (IOException e) {
            return List.of("[diagnostic] aapt2 version ne démarre pas : " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of("[diagnostic] aapt2 version interrompu (annulation du build)");
        }
    }

    /**
     * Répare le bit exécutable avant la première exécution (voir la javadoc de
     * classe) ; idempotent, jamais fatal deux fois.
     */
    private void assurerExécutable() throws IOException {
        if (exécutableAssuré) {
            return;
        }
        if (Files.isRegularFile(binaire) && !Files.isExecutable(binaire)
                && !binaire.toFile().setExecutable(true, true)) {
            throw new IOException("aapt2 n'est pas exécutable et le droit ne peut pas "
                    + "être posé : " + binaire
                    + " (réinstallez le composant aapt2 ou posez +x à la main)");
        }
        exécutableAssuré = true;
    }

    /**
     * Compile un répertoire de ressources en archive plate.
     *
     * @param répertoireRes répertoire {@code res} à compiler.
     * @param archive       archive de sortie (fichier .zip).
     * @param diagnostics   puits des diagnostics.
     * @throws IOException en cas d'échec du sous-processus (le détail est dans les
     *                     diagnostics et le journal).
     */
    public void compiler(Path répertoireRes, Path archive, DiagnosticSink diagnostics)
            throws IOException, InterruptedException {
        Files.createDirectories(archive.getParent());
        exiger(exécuter(List.of("compile", "--dir", répertoireRes.toString(),
                "-o", archive.toString())), diagnostics);
    }

    /**
     * Lie les archives compilées avec le manifeste et la plate-forme : produit l'APK
     * de base (ressources + manifeste) et les sources R.
     *
     * @param archives     archives plates compilées.
     * @param manifeste    manifeste Android du module.
     * @param androidJar   jar de la plate-forme cible.
     * @param namespace    paquet des classes R à générer.
     * @param minSdk       niveau d'API minimal.
     * @param targetSdk    niveau d'API cible.
     * @param sortieApk    APK de base produit.
     * @param sourcesR     répertoire des sources R générées.
     * @param assetsDirs   répertoires d'assets à inclure (éventuellement vide).
     * @param diagnostics  puits des diagnostics.
     * @throws IOException en cas d'échec (détail dans diagnostics/journal).
     */
    public void lier(List<Path> archives, Path manifeste, Path androidJar, String namespace,
                     int minSdk, int targetSdk, Path sortieApk, Path sourcesR,
                     List<Path> assetsDirs, DiagnosticSink diagnostics)
            throws IOException, InterruptedException {
        Files.createDirectories(sourcesR);
        Files.createDirectories(sortieApk.getParent());
        List<String> arguments = new ArrayList<>(List.of(
                "link",
                "-o", sortieApk.toString(),
                "-I", androidJar.toString(),
                "--manifest", manifeste.toString(),
                "--java", sourcesR.toString(),
                "--custom-package", namespace,
                "--min-sdk-version", Integer.toString(minSdk),
                "--target-sdk-version", Integer.toString(targetSdk),
                "--auto-add-overlay",
                "--version-code", "1",
                "--version-name", "1.0"));
        for (Path assets : assetsDirs) {
            arguments.add("-A");
            arguments.add(assets.toString());
        }
        archives.forEach(archive -> arguments.add(archive.toString()));
        exiger(exécuter(arguments), diagnostics);
    }

    /**
     * Exécute une commande aapt2 avec délai et interruption.
     */
    private Résultat exécuter(List<String> arguments) throws IOException, InterruptedException {
        assurerExécutable();
        List<String> commande = new ArrayList<>();
        commande.add(binaire.toString());
        commande.addAll(arguments);
        ProcessBuilder constructeur = new ProcessBuilder(commande);
        constructeur.redirectErrorStream(false);
        Process processus = constructeur.start();
        List<String> sortie = new ArrayList<>();
        List<String> erreurs = new ArrayList<>();
        // Lecteurs en arrière-plan : sans eux, un tampon plein bloquerait aapt2.
        Thread lectureSortie = lire(processus.getInputStream(), sortie);
        Thread lectureErreurs = lire(processus.getErrorStream(), erreurs);
        lectureSortie.start();
        lectureErreurs.start();
        boolean fini;
        try {
            fini = processus.waitFor(DÉLAI.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException annulation) {
            // D-08 : le build est annulé — pas d'orphelin.
            processus.destroyForcibly();
            throw annulation;
        }
        if (!fini) {
            processus.destroyForcibly();
            throw new IOException("aapt2 " + arguments.get(0) + " : délai de "
                    + DÉLAI + " dépassé (processus détruit)");
        }
        attendre(lectureSortie);
        attendre(lectureErreurs);
        return new Résultat(processus.exitValue() == 0, sortie, erreurs);
    }

    /**
     * Exige la réussite ; à l'échec : diagnostics parsés + autotest, puis exception
     * lisible.
     */
    private void exiger(Résultat résultat, DiagnosticSink diagnostics) throws IOException {
        for (String ligne : résultat.erreurs) {
            Diagnostic diagnostic = parserLigne(ligne);
            if (diagnostic != null) {
                diagnostics.report(diagnostic);
            }
        }
        if (résultat.succès) {
            return;
        }
        List<String> autotest = autotest();
        StringBuilder message = new StringBuilder("aapt2 a échoué");
        for (String ligne : autotest) {
            message.append(" — ").append(ligne);
        }
        throw new IOException(message.toString());
    }

    /**
     * Parse une ligne de diagnostic aapt2 en diagnostic structuré.
     */
    static Diagnostic parserLigne(String ligne) {
        if (ligne.isBlank()) {
            return null;
        }
        Matcher forme = LIGNE_LOCALISÉE.matcher(ligne.trim());
        if (forme.matches()) {
            return new Diagnostic(
                    "error".equals(forme.group(4))
                            ? jo.forge.api.BuildEvent.Severity.ERROR
                            : jo.forge.api.BuildEvent.Severity.WARNING,
                    forme.group(1),
                    Integer.parseInt(forme.group(2)),
                    forme.group(3) == null ? 0 : Integer.parseInt(forme.group(3)),
                    "aapt2",
                    forme.group(5),
                    TOOL);
        }
        // Ligne non localisée : bruit de progression ignoré, le reste en erreur.
        if (ligne.contains("error")) {
            return Diagnostic.error(TOOL, ligne.trim());
        }
        return null;
    }

    private static Thread lire(java.io.InputStream flux, List<String> cible) {
        return new Thread(() -> {
            try (BufferedReader lecteur = new BufferedReader(
                    new InputStreamReader(flux, StandardCharsets.UTF_8))) {
                String ligne;
                while ((ligne = lecteur.readLine()) != null) {
                    cible.add(ligne);
                }
            } catch (IOException ignored) {
                // Processus mort : flux fermé.
            }
        }, "aapt2-lecture");
    }

    private static void attendre(Thread fil) throws InterruptedException {
        fil.join(TimeUnit.SECONDS.toMillis(10));
    }

    /** Résultat d'une commande. */
    private record Résultat(boolean succès, List<String> sortie, List<String> erreurs) {
    }
}
