package jo.forge.engine.pipeline.java;

import jo.forge.engine.diag.Diagnostic;
import jo.forge.engine.diag.DiagnosticSink;
import org.eclipse.jdt.core.compiler.CategorizedProblem;
import org.eclipse.jdt.internal.compiler.ClassFile;
import org.eclipse.jdt.internal.compiler.CompilationResult;
import org.eclipse.jdt.internal.compiler.Compiler;
import org.eclipse.jdt.internal.compiler.DefaultErrorHandlingPolicies;
import org.eclipse.jdt.internal.compiler.ICompilerRequestor;
import org.eclipse.jdt.internal.compiler.batch.CompilationUnit;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileReader;
import org.eclipse.jdt.internal.compiler.env.INameEnvironment;
import org.eclipse.jdt.internal.compiler.env.NameEnvironmentAnswer;
import org.eclipse.jdt.internal.compiler.impl.CompilerOptions;
import org.eclipse.jdt.internal.compiler.problem.DefaultProblemFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Compilation Java SANS image JVM : le compilateur interne d'ECJ piloté sur un
 * environnement de noms CLASSIQUE (non modulaire), la plate-forme Android
 * ({@code android.jar}) posée comme simple bibliothèque.
 *
 * <p><b>Pourquoi ce chemin existe</b> (constat du build appareil v0.2.6,
 * {@code multi-modules-java} — 11 erreurs « The type java.lang.Object cannot
 * be resolved ») : sur ART, le frontal par lots d'ECJ ne trouve aucune classe
 * de plate-forme — la plate-forme vit dans les images {@code .oat}/dex que le
 * compilateur ne sait pas lire, là où une JVM de bureau lui prête
 * {@code rt.jar} ou l'image {@code jrt}. Pire, {@code android.jar} ne peut pas
 * réparer ça par les options habituelles : à un niveau de conformité
 * {@code >= 9} le frontal le refuse sur {@code -bootclasspath}, et un jar non
 * modulaire n'est pas un {@code --system} valable (ART n'a pas d'image
 * {@code jrt}). La seule sortie est de contourner le frontal : le compilateur
 * interne accepte n'importe quel environnement de noms, et un environnement
 * CLASSIQUE garde ECJ en mode non modulaire à tout niveau —
 * {@code java.lang.Object} et ses frères se lient alors directement aux octets
 * d'{@code android.jar}, sans image et sans plafond de niveau.</p>
 *
 * <p><b>Bibliothèques</b> : répertoires (sorties des modules frères, classes R
 * générées) AVANT jars (android.jar, jars Maven) — une classe fraîchement
 * compilée gagne sur une copie périmée d'un jar. Les tranches lambda
 * embarquées ({@link StubsLambda}) servent en tout dernier recours : elles
 * comblent les {@code java.lang.invoke} qu'{@code android.jar} omet et qu'un
 * compilateur 9+ exige pour la concaténation et les lambdas ; D8 désucre
 * l'invokedynamic au dexage, la fabrique réelle vit dans le runtime.</p>
 *
 * <p><b>Sûr sur ART (API 28)</b> : aucune API récente — {@code readAllBytes}
 * (26+) et jamais {@code Files.readString} (33+). Les diagnostics partent par
 * l'API du compilateur ({@link CategorizedProblem}), comme le frontal par
 * lots ; la console texte n'est qu'un journal de secours.</p>
 */
final class CompilateurSansImage {

    private CompilateurSansImage() {
    }

    /**
     * Compile des sources vers un répertoire de classes, sans frontal par lots.
     *
     * @param sources      fichiers {@code .java} (au moins un).
     * @param bibliothèques jars et répertoires de classes (plate-forme et
     *                      classpath confondus — les répertoires gagnent).
     * @param sortie       répertoire des {@code .class}.
     * @param release      niveau Java cible.
     * @param sink         puits des diagnostics structurés.
     * @return le résultat (succès, lignes de console, nombre d'erreurs).
     * @throws IOException si la lecture d'une source ou d'une bibliothèque échoue.
     */
    static EcjCompiler.CompileResult compiler(List<Path> sources, List<Path> bibliothèques,
                                              Path sortie, int release,
                                              DiagnosticSink sink) throws IOException {
        Files.createDirectories(sortie);

        List<Path> répertoires = new ArrayList<>();
        List<ZipFile> jars = new ArrayList<>();
        for (Path bibliothèque : bibliothèques) {
            if (Files.isDirectory(bibliothèque)) {
                répertoires.add(bibliothèque);
            } else if (Files.isRegularFile(bibliothèque)) {
                try {
                    jars.add(new ZipFile(bibliothèque.toFile()));
                } catch (IOException illisible) {
                    sink.report(Diagnostic.error(EcjCompiler.TOOL,
                            "bibliothèque illisible, ignorée : " + bibliothèque
                                    + " (" + illisible.getMessage() + ")"));
                }
            }
        }
        try {
            Set<String> paquets = new HashSet<>();
            for (ZipFile jar : jars) {
                for (ZipEntry entrée : jar.stream()
                        .filter(e -> !e.isDirectory())
                        .collect(Collectors.toList())) {
                    noterPaquet(paquets, entrée.getName());
                }
            }
            for (Path répertoire : répertoires) {
                try (Stream<Path> parcours = Files.walk(répertoire)) {
                    parcours.filter(Files::isRegularFile)
                            .filter(p -> p.toString().endsWith(".class"))
                            .forEach(p -> noterPaquet(paquets,
                                    répertoire.relativize(p).toString()
                                            .replace('\\', '/')));
                }
            }
            paquets.addAll(StubsLambda.paquets());

            EnvironnementNoms environnement = new EnvironnementNoms(répertoires,
                    jars, paquets);
            List<String> console = new ArrayList<>();
            int erreurs = compilerInterne(sources, environnement, niveauJdk(release),
                    sortie, sink, console);
            return new EcjCompiler.CompileResult(erreurs == 0, console, erreurs);
        } finally {
            for (ZipFile jar : jars) {
                try {
                    jar.close();
                } catch (IOException ignorée) {
                    // Fermeture au mieux : le jar est déjà consommé.
                }
            }
        }
    }

    /**
     * Le passage du compilateur interne : chaque résultat de compilation est
     * observé (problèmes vers le puits, {@code .class} écrits pour les unités
     * sans erreur).
     *
     * @return le nombre d'erreurs rencontrées.
     */
    private static int compilerInterne(List<Path> sources,
                                       INameEnvironment environnement, long niveau,
                                       Path sortie, DiagnosticSink sink,
                                       List<String> console) throws IOException {
        CompilerOptions options = new CompilerOptions();
        options.complianceLevel = niveau;
        options.sourceLevel = niveau;
        options.targetJDK = niveau;
        // Correctif décisif : les liaisons des méthodes BINAIRES lisent
        // originalSourceLevel (BinaryTypeBinding.createMethod) — par défaut
        // JDK1_3, ce qui dépouille TOUTE méthode binaire de son drapeau
        // varargs (« not applicable for the arguments » sur la moindre
        // expansion String.format/join). Le frontal par lots tient ce champ à
        // jour via sa table d'options ; ici il faut le poser à la main.
        options.originalComplianceLevel = niveau;
        options.originalSourceLevel = niveau;
        // Miroir du {@code -g} du frontal : attributs de débogage complets.
        options.produceDebugAttributes = ClassFileConstants.ATTR_SOURCE
                | ClassFileConstants.ATTR_LINES | ClassFileConstants.ATTR_VARS;

        int[] erreurs = {0};
        ICompilerRequestor requêteur = résultat -> {
            if (résultat == null) {
                return;
            }
            CategorizedProblem[] problèmes = résultat.getAllProblems();
            if (problèmes != null) {
                for (CategorizedProblem problème : problèmes) {
                    if (problème == null || !problème.isError()) {
                        // Seules les erreurs remontent, miroir du -nowarn du
                        // frontal par lots.
                        continue;
                    }
                    erreurs[0]++;
                    sink.report(EcjCompiler.versDiagnostic(problème, résultat));
                    console.add(Path.of(new String(problème.getOriginatingFileName()))
                            .getFileName() + ":" + problème.getSourceLineNumber()
                            + ": erreur : " + problème.getMessage());
                }
            }
            if (!résultat.hasErrors()) {
                for (ClassFile classe : résultat.getClassFiles()) {
                    try {
                        Path destination = sortie.resolve(
                                new String(classe.fileName()) + ".class");
                        Files.createDirectories(destination.getParent());
                        Files.write(destination, classe.getBytes());
                    } catch (IOException échecÉcriture) {
                        erreurs[0]++;
                        sink.report(Diagnostic.error(EcjCompiler.TOOL,
                                "écriture impossible : " + échecÉcriture));
                        console.add("erreur : écriture impossible : "
                                + échecÉcriture);
                    }
                }
            }
        };

        List<CompilationUnit> unités = new ArrayList<>();
        for (Path source : sources) {
            // readAllBytes (API 26+), jamais readString (API 33+) : le moteur
            // tourne sur l'ART d'un appareil API 28+.
            String texte = new String(Files.readAllBytes(source),
                    StandardCharsets.UTF_8);
            unités.add(new CompilationUnit(texte.toCharArray(),
                    source.toString(), "UTF-8"));
        }
        Compiler compilateur = new Compiler(environnement,
                DefaultErrorHandlingPolicies.proceedWithAllProblems(),
                options, requêteur, new DefaultProblemFactory(Locale.getDefault()));
        compilateur.compile(unités.toArray(new CompilationUnit[0]));
        return erreurs[0];
    }

    /**
     * Niveau ECJ d'un numéro de release Java ({@code 17} → {@code JDK17}).
     * Niveau inconnu (entre deux jalons) : le jalon supérieur connu le plus
     * proche — le modèle est validé en amont, ce repli ne concerne que les
     * valeurs futures.
     */
    private static long niveauJdk(int release) {
        return switch (release) {
            case 8 -> ClassFileConstants.JDK1_8;
            case 9, 10 -> ClassFileConstants.JDK9;
            case 11, 12, 13, 14, 15, 16 -> ClassFileConstants.JDK11;
            case 17, 18, 19, 20 -> ClassFileConstants.JDK17;
            default -> ClassFileConstants.JDK21;
        };
    }

    /**
     * Note un paquet et tous ses préfixes : ECJ interroge {@code isPackage}
     * hiérarchiquement ({@code java} puis {@code java/lang} puis
     * {@code java/lang/invoke}) — chaque préfixe doit être présent.
     */
    private static void noterPaquet(Set<String> paquets, String nomEntrée) {
        if (!nomEntrée.endsWith(".class")) {
            return;
        }
        int barre = nomEntrée.lastIndexOf('/');
        if (barre <= 0) {
            return;
        }
        String paquet = nomEntrée.substring(0, barre);
        paquets.add(paquet);
        int position = paquet.indexOf('/');
        while (position >= 0) {
            paquets.add(paquet.substring(0, position));
            position = paquet.indexOf('/', position + 1);
        }
    }

    /**
     * Environnement de noms CLASSIQUE : recherche par nom binaire dans les
     * répertoires (d'abord), les jars (ensuite), les tranches lambda embarquées
     * (en dernier). Aucune notion de module — c'est ce qui garde ECJ en mode
     * non modulaire à tout niveau de conformité.
     */
    private static final class EnvironnementNoms implements INameEnvironment {

        private final List<Path> répertoires;
        private final List<ZipFile> jars;
        private final Set<String> paquets;
        private final Map<String, byte[]> tranches;

        EnvironnementNoms(List<Path> répertoires, List<ZipFile> jars,
                          Set<String> paquets) {
            this.répertoires = List.copyOf(répertoires);
            this.jars = List.copyOf(jars);
            this.paquets = paquets;
            this.tranches = StubsLambda.classes();
        }

        @Override
        public NameEnvironmentAnswer findType(char[][] compoundTypeName) {
            return trouver(joindre(compoundTypeName));
        }

        @Override
        public NameEnvironmentAnswer findType(char[] typeName, char[][] packageName) {
            String binaire = packageName.length == 0
                    ? new String(typeName)
                    : joindre(packageName) + "/" + new String(typeName);
            return trouver(binaire);
        }

        @Override
        public boolean isPackage(char[][] parentPackageName, char[] packageName) {
            String paquet = parentPackageName == null || parentPackageName.length == 0
                    ? new String(packageName)
                    : joindre(parentPackageName) + "/" + new String(packageName);
            return paquets.contains(paquet);
        }

        @Override
        public void cleanup() {
            // Les jars sont fermés par l'appelant (le passage du compilateur
            // interne peut en demander davantage qu'un nettoyage prudent).
        }

        private NameEnvironmentAnswer trouver(String binaireSlash) {
            String entrée = binaireSlash + ".class";
            for (Path répertoire : répertoires) {
                Path classe = répertoire.resolve(entrée);
                if (!Files.isRegularFile(classe)) {
                    continue;
                }
                try {
                    byte[] octets = Files.readAllBytes(classe);
                    return réponse(octets, entrée);
                } catch (IOException ignorée) {
                    // Entrée illisible : la recherche continue.
                }
            }
            for (ZipFile jar : jars) {
                ZipEntry trouvée = jar.getEntry(entrée);
                if (trouvée == null) {
                    continue;
                }
                try {
                    byte[] octets = jar.getInputStream(trouvée).readAllBytes();
                    return réponse(octets, entrée);
                } catch (IOException ignorée) {
                    // Entrée illisible : la recherche continue.
                }
            }
            byte[] tranche = tranches.get(entrée);
            return tranche == null ? null : réponse(tranche, entrée);
        }

        private static NameEnvironmentAnswer réponse(byte[] octets, String entrée) {
            try {
                return new NameEnvironmentAnswer(ClassFileReader.read(
                        new ByteArrayInputStream(octets), entrée), null);
            } catch (Exception illisible) {
                return null;
            }
        }

        private static String joindre(char[][] parties) {
            StringBuilder texte = new StringBuilder();
            for (int i = 0; i < parties.length; i++) {
                if (i > 0) {
                    texte.append('/');
                }
                texte.append(parties[i]);
            }
            return texte.toString();
        }
    }
}
