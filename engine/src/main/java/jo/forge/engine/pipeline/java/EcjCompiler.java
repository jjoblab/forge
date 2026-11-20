package jo.forge.engine.pipeline.java;

import jo.forge.engine.diag.Diagnostic;
import jo.forge.engine.diag.DiagnosticSink;
import org.eclipse.jdt.core.compiler.CategorizedProblem;
import org.eclipse.jdt.internal.compiler.CompilationResult;
import org.eclipse.jdt.internal.compiler.ICompilerRequestor;
import org.eclipse.jdt.internal.compiler.batch.Main;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Compilateur Java embarqué : ECJ (Eclipse Compiler for Java, EPL-2.0) appelé en
 * processus, sans passer par la ligne de commande ni par une VM séparée.
 *
 * <p>Les diagnostics sont demandés à l'API du compilateur (le point d'extension du
 * frontal par lots, qui remet chaque {@link CategorizedProblem}) et non parsés depuis
 * sa sortie texte — celle-ci dépend de la locale et perd colonne et code machine. Le
 * texte de console n'est conservé que comme journal de secours.</p>
 *
 * <p><b>Politique d'options</b> (consignée dans {@code docs/REFERENCES.md}) :
 * processeurs d'annotations désactivés ({@code -proc:none}, comme le moteur de
 * référence — le support des processeurs est un écart documenté), avertissements
 * coupés ({@code -nowarn}), informations de débogage émises ({@code -g}).
 * {@code --release N} est utilisé quand la machine hôte fournit {@code ct.sym} (JVM de
 * développement) ; sinon repli {@code -source/-target} (ART).</p>
 *
 * <p><b>Aiguillage plate-forme</b> (correctif v0.2.7) : une compilation peut porter
 * une <em>bibliothèque de plate-forme</em> — {@code android.jar} du composant
 * installé. Sur ART elle est OBLIGATOIRE (le runtime ne expose aucune classe lisible
 * par ECJ : sans elle, « The type java.lang.Object cannot be resolved », constat
 * appareil v0.2.6). À un niveau {@code >= 9}, le frontal par lots ne sait PAS
 * l'employer ({@code -bootclasspath} refusé au-delà de 8, pas de {@code --system}
 * pour un jar non modulaire, pas d'image {@code jrt}) : la compilation passe alors
 * par {@link CompilateurSansImage}, le compilateur interne d'ECJ sur un
 * environnement de noms classique. Sur une JVM de bureau, l'absence de plate-forme
 * garde le comportement historique : ECJ emprunte les classes de l'hôte.</p>
 *
 * <p>Annulation : une compilation lancée n'est pas interrompue au milieu — l'appelant
 * vérifie l'annulation avant et après (D-08, points sûrs).</p>
 */
public final class EcjCompiler {

    /** Outil émetteur des diagnostics. */
    public static final String TOOL = "java-compiler";

    /** Options de compilation fixes (politique documentée ci-dessus). */
    private static final List<String> OPTIONS_FIXES = List.of("-proc:none", "-nowarn", "-g");

    /** Niveau à partir duquel le frontal par lots refuse un -bootclasspath. */
    private static final int NIVEAU_FRONTAL_CLASSIQUE_MAX = 8;

    private EcjCompiler() {
    }

    /**
     * Indique si le moteur tourne sur l'ART d'un appareil Android : le nom de VM
     * d'ART porte encore « Dalvik » et son éditeur « Android ». C'est le seul
     * cas où ECJ ne peut emprunter les classes de la machine hôte.
     *
     * @return {@code true} sur l'ART d'un appareil.
     */
    public static boolean surArt() {
        return surArt(System.getProperty("java.vm.name"), System.getProperty("java.vendor"));
    }

    /**
     * Forme testable de la détection ART (chaînes injectées).
     */
    static boolean surArt(String nomVm, String éditeur) {
        return (nomVm != null && nomVm.contains("Dalvik"))
                || (éditeur != null && éditeur.contains("Android"));
    }

    /**
     * Version du compilateur embarqué (pour les empreintes : recompiler quand l'outil
     * change).
     *
     * @return la version lue du jar, ou {@code "inconnue"}.
     */
    public static String compilerVersion() {
        String version = EcjCompiler.class.getPackage().getImplementationVersion();
        return version == null || version.isBlank() ? "inconnue" : version;
    }

    /**
     * Compile des sources Java vers un répertoire de classes, sans bibliothèque de
     * plate-forme (JVM de bureau : ECJ emprunte les classes de l'hôte ; sur ART,
     * l'échec est immédiat et actionnable).
     *
     * @param sources   fichiers {@code .java} à compiler (au moins un).
     * @param classpath entrées du classpath de compilation (répertoires ou jars).
     * @param outputDir répertoire de sortie des {@code .class} (créé s'il manque).
     * @param release   niveau Java cible.
     * @param sink      puits des diagnostics structurés (erreurs, avertissements).
     * @return le résultat : succès, lignes de console et nombre d'erreurs.
     * @throws IOException si la préparation du répertoire de sortie échoue.
     */
    public static CompileResult compile(List<Path> sources, List<Path> classpath,
                                        Path outputDir, int release, DiagnosticSink sink)
            throws IOException {
        return compile(sources, classpath, List.of(), outputDir, release, sink);
    }

    /**
     * Compile des sources Java vers un répertoire de classes, avec une bibliothèque
     * de plate-forme.
     *
     * <p>Sur ART, la plate-forme ({@code android.jar} du composant installé) est la
     * seule source des classes {@code java.*} : le pipeline la fournit pour tout
     * module. À un niveau {@code >= 9} elle passe par le compilateur interne (le
     * frontal par lots ne sait pas l'employer) ; au niveau 8, le frontal l'accepte
     * sur {@code -bootclasspath}.</p>
     *
     * @param sources    fichiers {@code .java} à compiler (au moins un).
     * @param classpath  entrées du classpath de compilation (répertoires ou jars).
     * @param plateforme bibliothèque de plate-forme (ex. {@code android.jar}),
     *                   vide sur JVM de bureau.
     * @param outputDir  répertoire de sortie des {@code .class} (créé s'il manque).
     * @param release    niveau Java cible.
     * @param sink       puits des diagnostics structurés (erreurs, avertissements).
     * @return le résultat : succès, lignes de console et nombre d'erreurs.
     * @throws IOException si la préparation du répertoire de sortie échoue.
     */
    public static CompileResult compile(List<Path> sources, List<Path> classpath,
                                        List<Path> plateforme, Path outputDir,
                                        int release, DiagnosticSink sink)
            throws IOException {
        return compile(sources, classpath, plateforme, outputDir, release, sink, surArt());
    }

    /**
     * Forme testable de la compilation (détection ART injectée).
     */
    static CompileResult compile(List<Path> sources, List<Path> classpath,
                                 List<Path> plateforme, Path outputDir, int release,
                                 DiagnosticSink sink, boolean surArt)
            throws IOException {
        Files.createDirectories(outputDir);
        if (sources.isEmpty()) {
            return new CompileResult(true, List.of("aucune source à compiler"), 0);
        }

        if (plateforme.isEmpty()) {
            if (!surArt) {
                return compilerParFrontal(sources, classpath, List.of(),
                        outputDir, release, sink);
            }
            // Sur ART sans plate-forme, ne laissons pas trente diagnostics
            // « cannot be resolved » noyer la cause : un échec net, actionnable.
            String message = "aucune bibliothèque de plate-forme pour la compilation — "
                    + "sur appareil, installez le composant platform (android.jar) "
                    + "du moteur ou définissez forge.androidJar";
            sink.report(Diagnostic.error(TOOL, message));
            return new CompileResult(false, List.of("erreur : " + message), 1);
        }

        if (release > NIVEAU_FRONTAL_CLASSIQUE_MAX) {
            // Le frontal par lots ne sait pas employer android.jar au-delà de
            // Java 8 : compilateur interne, environnement classique.
            List<Path> bibliothèques = new ArrayList<>(plateforme);
            bibliothèques.addAll(classpath);
            return CompilateurSansImage.compiler(sources, bibliothèques,
                    outputDir, release, sink);
        }
        return compilerParFrontal(sources, classpath, plateforme,
                outputDir, release, sink);
    }

    /**
     * Passe par le frontal par lots d'ECJ (chemin historique) : {@code --release}
     * quand la machine fournit {@code ct.sym}, sinon {@code -source/-target} ;
     * {@code -bootclasspath} seulement pour une plate-forme au niveau 8.
     */
    private static CompileResult compilerParFrontal(List<Path> sources,
                                                    List<Path> classpath,
                                                    List<Path> plateforme,
                                                    Path outputDir, int release,
                                                    DiagnosticSink sink) throws IOException {
        List<String> args = new ArrayList<>();
        if (plateforme.isEmpty() && releaseAvailable()) {
            args.add("--release");
            args.add(Integer.toString(release));
        } else {
            args.add("-source");
            args.add(Integer.toString(release));
            args.add("-target");
            args.add(Integer.toString(release));
        }
        args.addAll(OPTIONS_FIXES);
        args.add("-d");
        args.add(outputDir.toString());
        if (!plateforme.isEmpty()) {
            args.add("-bootclasspath");
            args.add(String.join(File.pathSeparator,
                    plateforme.stream().map(Path::toString).toList()));
        }
        if (!classpath.isEmpty()) {
            args.add("-classpath");
            args.add(String.join(File.pathSeparator,
                    classpath.stream().map(Path::toString).toList()));
        }
        sources.forEach(source -> args.add(source.toString()));

        StringWriter sortie = new StringWriter();
        StringWriter erreurs = new StringWriter();
        CapturingMain main = new CapturingMain(new PrintWriter(sortie, true),
                new PrintWriter(erreurs, true), sink);
        boolean succes;
        try {
            succes = main.compile(args.toArray(new String[0]));
        } catch (RuntimeException e) {
            // Le frontal peut échouer avant toute unité compilée (argument invalide) :
            // le texte d'erreur devient un diagnostic non localisé.
            sink.report(Diagnostic.error(TOOL,
                    "le compilateur a échoué avant de démarrer : " + e.getMessage()));
            succes = false;
        }
        List<String> console = new ArrayList<>();
        for (String ligne : erreurs.toString().lines().toList()) {
            if (!ligne.isBlank()) {
                console.add(ligne);
            }
        }
        for (String ligne : sortie.toString().lines().toList()) {
            if (!ligne.isBlank()) {
                console.add(ligne);
            }
        }
        return new CompileResult(succes, console, main.erreursComptées());
    }

    /**
     * Indique si {@code --release} est utilisable : la machine hôte fournit
     * {@code ct.sym} (JVM de développement ; jamais sur ART).
     *
     * @return {@code true} si l'option doit être employée.
     */
    private static boolean releaseAvailable() {
        String javaHome = System.getProperty("java.home");
        return javaHome != null
                && Files.isRegularFile(Path.of(javaHome, "lib", "ct.sym"));
    }

    /** Résultat d'une compilation. */
    public record CompileResult(boolean success, List<String> consoleLines,
                                int errorCount) {
    }

    /**
     * Convertit un problème ECJ en diagnostic Forge : sévérité, fichier, ligne,
     * colonne (recalculée des positions de fin de ligne, absentes de l'API du
     * problème), code machine et outil.
     *
     * @param problème  le problème rendu par ECJ.
     * @param résultat  le résultat de compilation porteur (positions de lignes).
     * @return le diagnostic structuré.
     */
    static Diagnostic versDiagnostic(CategorizedProblem problème,
                                     CompilationResult résultat) {
        jo.forge.api.BuildEvent.Severity sévérité = problème.isError()
                ? jo.forge.api.BuildEvent.Severity.ERROR
                : problème.isWarning()
                ? jo.forge.api.BuildEvent.Severity.WARNING
                : jo.forge.api.BuildEvent.Severity.INFO;
        String fichier = new String(problème.getOriginatingFileName());
        int ligne = Math.max(0, problème.getSourceLineNumber());
        int colonne = colonneDe(problème, résultat);
        String code = "ecj:" + problème.getID();
        return new Diagnostic(sévérité, fichier, ligne, colonne, code,
                problème.getMessage(), TOOL);
    }

    /**
     * Colonne 1-based du début du problème, recalculée : l'API ne donne que l'offset
     * absolu du caractère fautif et les positions de fin de chaque ligne.
     */
    private static int colonneDe(CategorizedProblem problème, CompilationResult résultat) {
        int[] finsDeLigne = résultat.getLineSeparatorPositions();
        int ligne = problème.getSourceLineNumber();
        int debut = problème.getSourceStart();
        if (ligne <= 0 || debut < 0) {
            return 0;
        }
        int debutDeLigne;
        if (finsDeLigne == null || finsDeLigne.length == 0) {
            debutDeLigne = 0; // Document sans séparateur : une seule ligne.
        } else if (ligne == 1) {
            debutDeLigne = 0;
        } else if (ligne - 2 < finsDeLigne.length) {
            debutDeLigne = finsDeLigne[ligne - 2] + 1;
        } else {
            return 0;
        }
        return Math.max(1, debut - debutDeLigne + 1);
    }

    /**
     * Frontal par lots d'ECJ, câblé pour observer chaque résultat de compilation : le
     * délégué fait le vrai travail (écriture des {@code .class}, rapport), nous ne
     * faisons que relever les problèmes portés par l'API et compter les erreurs.
     */
    private static final class CapturingMain extends Main {

        private final DiagnosticSink sink;
        private int erreurs;

        CapturingMain(PrintWriter out, PrintWriter err, DiagnosticSink sink) {
            super(out, err, false);
            this.sink = sink;
        }

        /** Nombre d'erreurs relevées pendant le passage. */
        int erreursComptées() {
            return erreurs;
        }

        @Override
        public ICompilerRequestor getBatchRequestor() {
            ICompilerRequestor delegate = super.getBatchRequestor();
            return result -> {
                if (result != null) {
                    // getAllProblems() rend null quand tout est propre.
                    CategorizedProblem[] problemes = result.getAllProblems();
                    if (problemes != null) {
                        for (CategorizedProblem probleme : problemes) {
                            if (probleme != null) {
                                if (probleme.isError()) {
                                    erreurs++;
                                }
                                sink.report(versDiagnostic(probleme, result));
                            }
                        }
                    }
                }
                delegate.acceptResult(result);
            };
        }
    }
}
