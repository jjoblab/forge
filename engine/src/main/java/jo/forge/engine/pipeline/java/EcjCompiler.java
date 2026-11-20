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
 * <p>Politique d'options (consignée dans {@code docs/REFERENCES.md}) : processeurs
 * d'annotations désactivés ({@code -proc:none}, comme le moteur de référence — le
 * support des processeurs est un écart documenté), avertissements coupés
 * ({@code -nowarn}), informations de débogage émises ({@code -g}).
 * {@code --release N} est utilisé quand la machine hôte fournit {@code ct.sym} (JVM de
 * développement) ; sinon repli {@code -source/-target} (ART).</p>
 *
 * <p>Annulation : une compilation lancée n'est pas interrompue au milieu — l'appelant
 * vérifie l'annulation avant et après (D-08, points sûrs).</p>
 */
public final class EcjCompiler {

    /** Outil émetteur des diagnostics. */
    public static final String TOOL = "java-compiler";

    /** Options de compilation fixes (politique documentée ci-dessus). */
    private static final List<String> OPTIONS_FIXES = List.of("-proc:none", "-nowarn", "-g");

    private EcjCompiler() {
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
     * Compile des sources Java vers un répertoire de classes.
     *
     * @param sources   fichiers {@code .java} à compiler (au moins un).
     * @param classpath entrées du classpath de compilation (répertoires ou jars).
     * @param outputDir répertoire de sortie des {@code .class} (créé s'il manque).
     * @param release   niveau Java cible ({@code --release}).
     * @param sink      puits des diagnostics structurés (erreurs, avertissements).
     * @return le résultat : succès et lignes de console (journal de secours).
     * @throws IOException si la préparation du répertoire de sortie échoue.
     */
    public static CompileResult compile(List<Path> sources, List<Path> classpath,
                                        Path outputDir, int release, DiagnosticSink sink)
            throws IOException {
        Files.createDirectories(outputDir);
        if (sources.isEmpty()) {
            return new CompileResult(true, List.of("aucune source à compiler"));
        }

        List<String> args = new ArrayList<>();
        if (releaseAvailable()) {
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
        return new CompileResult(succes, console);
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
    public record CompileResult(boolean success, List<String> consoleLines) {
    }

    /**
     * Frontal par lots d'ECJ, câblé pour observer chaque résultat de compilation : le
     * délégué fait le vrai travail (écriture des {@code .class}, rapport), nous ne
     * faisons que relever les problèmes portés par l'API.
     */
    private static final class CapturingMain extends Main {

        private final DiagnosticSink sink;

        CapturingMain(PrintWriter out, PrintWriter err, DiagnosticSink sink) {
            super(out, err, false);
            this.sink = sink;
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
                                sink.report(toDiagnostic(probleme, result));
                            }
                        }
                    }
                }
                delegate.acceptResult(result);
            };
        }

        /**
         * Convertit un problème ECJ en diagnostic Forge (colonne recalculée depuis les
         * positions de fin de ligne du fichier, absentes de l'API du problème).
         */
        private static Diagnostic toDiagnostic(CategorizedProblem probleme,
                                               CompilationResult resultat) {
            jo.forge.api.BuildEvent.Severity severite = probleme.isError()
                    ? jo.forge.api.BuildEvent.Severity.ERROR
                    : probleme.isWarning()
                    ? jo.forge.api.BuildEvent.Severity.WARNING
                    : jo.forge.api.BuildEvent.Severity.INFO;
            String fichier = new String(probleme.getOriginatingFileName());
            int ligne = Math.max(0, probleme.getSourceLineNumber());
            int colonne = colonneDe(probleme, resultat);
            String code = "ecj:" + probleme.getID();
            return new Diagnostic(severite, fichier, ligne, colonne, code,
                    probleme.getMessage(), TOOL);
        }

        /**
         * Colonne 1-based du début du problème, recalculée : l'API ne donne que l'offset
         * absolu du caractère fautif et les positions de fin de chaque ligne.
         */
        private static int colonneDe(CategorizedProblem probleme, CompilationResult resultat) {
            int[] finsDeLigne = resultat.getLineSeparatorPositions();
            int ligne = probleme.getSourceLineNumber();
            int debut = probleme.getSourceStart();
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
    }
}
