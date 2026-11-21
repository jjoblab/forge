package jo.forge.engine.pipeline.java;

import jo.forge.engine.diag.Diagnostic;
import jo.forge.engine.diag.DiagnosticSink;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Compilateur Kotlin embarqué : kotlin-compiler-embeddable (Apache-2.0) appelé EN
 * PROCESSUS par réflexion — la feuille de route langages, v0.2.9.
 *
 * <p><b>Pourquoi la réflexion</b> : le moteur ne peut pas dépendre du compilateur
 * Kotlin à la compilation (jar de ~55 Mo) alors qu'il l'utilise à l'exécution —
 * sur l'appareil, le composant installé {@code kotlin-compiler} le porte en
 * {@code lib/kotlin-compiler.jar} (dexé, multi-dex) et le chargeur de classes le
 * sert ; sur la JVM de développement, il vient du classpath de test. Le pont ne
 * touche que des éléments PUBLICS et stables du compilateur : la classe
 * {@code K2JVMCompiler}, la méthode {@code exec(MessageCollector, Services,
 * String...)} de son parent CLICompiler, un collecteur de messages proxyfié et
 * l'énumération {@code ExitCode}. Si le composant est absent, la tâche échoue
 * avec un message actionnable (et NON un NoClassDefFoundError au milieu d'un
 * build).</p>
 *
 * <p><b>État de la validation</b> : la compilation Kotlin est VALIDÉE sur JVM
 * (tests {@code KotlinCompilerTest} et {@code BuildKotlinTest}). Sur ART, le
 * moteur de référence a dû instrumenter le compilateur en profondeur (passes
 * bytecode, shims, contournements JIT — REF-133/135) : notre propre audit ART
 * reste À MENER, constat consigné dans {@code docs/limites-et-suite.md}. Le
 * composant est publié marqué « expérimental ».</p>
 *
 * <p><b>Politique d'options</b> (miroir de celle d'ECJ) : avertissements coupés
 * ({@code -nowarn}), cible JVM alignée sur {@code javaRelease} du module,
 * {@code -no-reflect} (le module qui veut kotlin-reflect le déclare en dépendance
 * Maven). La bibliothèque standard est laissée au compilateur (l'embarquable la
 * porte) ; à l'exécution, elle passe par le résolveur Maven du module.</p>
 */
public final class KotlinCompiler {

    /** Outil émetteur des diagnostics. */
    public static final String TOOL = "kotlin-compiler";

    /** Classe du compilateur JVM (kotlin-compiler-embeddable). */
    private static final String CLASSE_COMPILATEUR =
            "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler";

    /** Interface du collecteur de messages du compilateur. */
    private static final String CLASSE_COLLECTEUR =
            "org.jetbrains.kotlin.cli.common.messages.MessageCollector";

    /** Conteneur de services (l'instance vide suffit à une compilation par lots). */
    private static final String CLASSE_SERVICES = "org.jetbrains.kotlin.config.Services";

    /** Suffixe des sources Kotlin. */
    private static final String SUFFIXE_KOTLIN = ".kt";

    private KotlinCompiler() {
    }

    /**
     * Indique si le compilateur Kotlin est présent sur le classpath du moteur
     * (composant {@code kotlin-compiler} installé, ou classpath de test).
     *
     * @return {@code true} si une compilation Kotlin est possible.
     */
    public static boolean disponible() {
        return classeCompilateur() != null;
    }

    /**
     * Version du compilateur embarqué (pour les empreintes : recompiler quand
     * l'outil change). Lu du manifeste du jar, repli honnête si absent.
     *
     * @return la version, ou {@code "inconnue"}.
     */
    public static String compilerVersion() {
        try {
            Class<?> compilateur = Class.forName(CLASSE_COMPILATEUR);
            String version = compilateur.getPackage().getImplementationVersion();
            return version == null || version.isBlank() ? "inconnue" : version;
        } catch (ClassNotFoundException absent) {
            return "absent";
        }
    }

    /**
     * Compile des sources Kotlin vers un répertoire de classes.
     *
     * @param sources    fichiers {@code .kt} à compiler (au moins un).
     * @param classpath  entrées du classpath de compilation (jars et répertoires,
     *                   dépendances de projet déjà compilées comprises).
     * @param plateforme bibliothèque de plate-forme ({@code android.jar}) : ajoutée
     *                   au classpath — la validation ART reste à mener (voir la
     *                   javadoc de classe).
     * @param outputDir  répertoire de sortie des {@code .class} (créé s'il manque).
     * @param jvmCible   cible JVM ({@code -jvm-target}, ex. 17).
     * @param sink       puits des diagnostics structurés.
     * @return le résultat : succès, lignes de console, nombre d'erreurs.
     * @throws IOException si la préparation du répertoire de sortie échoue ou que
     *                     le compilateur est absent (message actionnable).
     */
    public static CompileResult compiler(List<Path> sources, List<Path> classpath,
                                         List<Path> plateforme, Path outputDir,
                                         int jvmCible, DiagnosticSink sink)
            throws IOException {
        Files.createDirectories(outputDir);
        if (sources.isEmpty()) {
            return new CompileResult(true, List.of("aucune source Kotlin à compiler"), 0);
        }
        Class<?> compilateur = classeCompilateur();
        if (compilateur == null) {
            String message = "compilateur Kotlin absent du moteur — installez le "
                    + "composant kotlin-compiler (assistant du moteur) ; sur la JVM "
                    + "de développement, il vient du classpath de test";
            sink.report(Diagnostic.error(TOOL, message));
            return new CompileResult(false, List.of("erreur : " + message), 1);
        }

        List<String> argumentsCli = new ArrayList<>(List.of(
                "-nowarn", "-no-reflect",
                "-jvm-target", Integer.toString(jvmCible),
                "-d", outputDir.toString()));
        List<Path> cheminComplet = new ArrayList<>(classpath);
        cheminComplet.addAll(plateforme);
        // Bibliothèque standard : le compilateur embarqué ne la porte PAS — il la
        // cherche dans un « Kotlin home » inexistant (avertissements) ou exige
        // -no-stdlib. On la localise SUR NOTRE CLASSPATH (jar de la stdlib à côté
        // du compilateur, composant ou classpath de test) et on la pose
        // explicitement : symboles résolus, aucun avertissement, aucun répertoire
        // maison à deviner. Introuvable (classloader muet) : -no-stdlib quand
        // même, le module devra porter sa stdlib en dépendance Maven.
        Path stdlib = jarDeLaClasse("kotlin.jvm.internal.Intrinsics");
        if (stdlib != null) {
            cheminComplet.add(stdlib);
            argumentsCli.add("-no-stdlib");
        }
        if (!cheminComplet.isEmpty()) {
            argumentsCli.add("-classpath");
            argumentsCli.add(String.join(java.io.File.pathSeparator,
                    cheminComplet.stream().map(Path::toString).toList()));
        }
        sources.forEach(source -> argumentsCli.add(source.toString()));

        Collecteur collecteur = new Collecteur(sink);
        try {
            Class<?> collecteurClasse = Class.forName(CLASSE_COLLECTEUR);
            Class<?> services = Class.forName(CLASSE_SERVICES);
            Object instance = compilateur.getDeclaredConstructor().newInstance();
            Object servicesVides = services.getField("EMPTY").get(null);
            Object collecteurProxy = Proxy.newProxyInstance(
                    compilateur.getClassLoader(),
                    new Class<?>[] {collecteurClasse},
                    collecteur);

            // Chaîne publique de CLITool (stable depuis 1.x) : createArguments(),
            // parseArguments(String[], arguments), exec(collecteur, services,
            // arguments) — tout est public, aucune API interne. Les paramètres
            // génériques sont EFFACÉS (CommonToolArguments) : les méthodes se
            // cherchent par forme, jamais par type exact de la sous-classe.
            Object argumentsCompilateur = compilateur.getMethod("createArguments")
                    .invoke(instance);
            Method analyse = méthodeParForme(compilateur, "parseArguments", 2,
                    paramètres -> paramètres[0] == String[].class);
            analyse.invoke(instance, argumentsCli.toArray(new String[0]),
                    argumentsCompilateur);
            Method exec = méthodeParForme(compilateur, "exec", 3, paramètres ->
                    paramètres[0] == collecteurClasse && paramètres[1] == services);
            Object code = exec.invoke(instance, collecteurProxy, servicesVides,
                    argumentsCompilateur);
            boolean succes = code != null && "OK".equals(((Enum<?>) code).name());
            List<String> console = new ArrayList<>();
            console.add("kotlinc " + argumentsCli.size() + " argument(s), sortie : "
                    + ((Enum<?>) code).name());
            return new CompileResult(succes, console, collecteur.erreurs);
        } catch (Exception echec) {
            Throwable cause = echec.getCause() != null ? echec.getCause() : echec;
            String message = "le compilateur Kotlin a échoué : " + cause.getMessage();
            sink.report(Diagnostic.error(TOOL, message));
            return new CompileResult(false, List.of("erreur : " + message), 1);
        }
    }

    /**
     * Cherche une méthode publique par NOM et par forme (nombre de paramètres +
     * prédicat) — les signatures génériques de CLITool sont effacées, un getMethod
     * par type exact échouerait selon la version du compilateur.
     */
    private static Method méthodeParForme(Class<?> classe, String nom, int paramètres,
                                          java.util.function.Predicate<Class<?>[]> forme)
            throws NoSuchMethodException {
        for (Method méthode : classe.getMethods()) {
            if (!nom.equals(méthode.getName())
                    || méthode.getParameterCount() != paramètres) {
                continue;
            }
            if (forme.test(méthode.getParameterTypes())) {
                return méthode;
            }
        }
        throw new NoSuchMethodException(nom + "(" + paramètres
                + " paramètres, forme attendue) introuvable sur " + classe.getName()
                + " — version de kotlin-compiler inattendue");
    }

    /**
     * Indique si un fichier est une source Kotlin.
     *
     * @param chemin fichier à tester.
     * @return {@code true} pour un {@code .kt}.
     */
    public static boolean estSourceKotlin(Path chemin) {
        return chemin.getFileName().toString().endsWith(SUFFIXE_KOTLIN);
    }

    /**
     * Localise le jar qui porte une classe, sur le classpath du moteur (réflexion
     * inverse : la ressource sert son archive). Utilisé pour poser la bibliothèque
     * standard Kotlin au classpath de compilation.
     *
     * @param nomCanonique classe à chercher (ex. {@code kotlin.jvm.internal.Intrinsics}).
     * @return le chemin du jar, ou {@code null} si le chargeur ne l'expose pas.
     */
    static Path jarDeLaClasse(String nomCanonique) {
        try {
            java.net.URL ressource = KotlinCompiler.class.getClassLoader().getResource(
                    nomCanonique.replace('.', '/') + ".class");
            if (ressource == null || !"jar".equals(ressource.getProtocol())) {
                return null;
            }
            String chemin = ressource.getPath();
            int séparateur = chemin.indexOf("!/");
            if (séparateur <= 0) {
                return null;
            }
            Path jar = Path.of(java.net.URLDecoder.decode(
                    chemin.substring(0, séparateur),
                    java.nio.charset.StandardCharsets.UTF_8));
            return java.nio.file.Files.isRegularFile(jar) ? jar : null;
        } catch (Exception introuvable) {
            return null;
        }
    }

    /** Classe du compilateur, ou {@code null} si absente. */
    private static Class<?> classeCompilateur() {
        try {
            return Class.forName(CLASSE_COMPILATEUR);
        } catch (ClassNotFoundException absent) {
            return null;
        }
    }

    /**
     * Collecteur de messages proxyfié : chaque rapport devient un diagnostic Forge
     * (sévérité, fichier, ligne, colonne — lus par réflexion sur l'emplacement
     * réel du compilateur), la console texte reste en secours.
     */
    private static final class Collecteur implements InvocationHandler {

        private final DiagnosticSink sink;
        private int erreurs;

        Collecteur(DiagnosticSink sink) {
            this.sink = sink;
        }

        @Override
        public Object invoke(Object proxy, Method méthode, Object[] arguments) {
            if (!"report".equals(méthode.getName()) || arguments == null
                    || arguments.length < 2) {
                if ("hasErrors".equals(méthode.getName())) {
                    return erreurs > 0;
                }
                return null; // clearErrors et défauts : rien à faire.
            }
            String sévérité = String.valueOf(arguments[0]);
            String texte = String.valueOf(arguments[1]);
            Position position = position(
                    arguments.length > 2 ? arguments[2] : null);
            if (sévérité.startsWith("ERROR")) {
                erreurs++;
                sink.report(new Diagnostic(jo.forge.api.BuildEvent.Severity.ERROR,
                        position.fichier(), position.ligne(), position.colonne(),
                        "kotlinc", texte, TOOL));
            } else if (sévérité.startsWith("WARNING")) {
                sink.report(new Diagnostic(jo.forge.api.BuildEvent.Severity.WARNING,
                        position.fichier(), position.ligne(), position.colonne(),
                        "kotlinc", texte, TOOL));
            } else if (!"INFO".equals(sévérité) && !"LOGGING".equals(sévérité)) {
                sink.report(new Diagnostic(jo.forge.api.BuildEvent.Severity.INFO,
                        position.fichier(), position.ligne(), position.colonne(),
                        "kotlinc", texte, TOOL));
            }
            return null;
        }

        /** Position d'un diagnostic (fichier vide et zéros si non localisé). */
        private record Position(String fichier, int ligne, int colonne) {

            Position {
                fichier = fichier == null ? "" : fichier;
            }
        }

        /**
         * Position (fichier, ligne, colonne) d'un emplacement de compilateur, lue
         * par réflexion — {@code CompilerMessageLocation.getPath()/getLine()/getColumn()}.
         */
        private static Position position(Object emplacement) {
            if (emplacement == null) {
                return new Position("", 0, 0);
            }
            try {
                String fichier = (String) emplacement.getClass()
                        .getMethod("getPath").invoke(emplacement);
                int ligne = (int) emplacement.getClass()
                        .getMethod("getLine").invoke(emplacement);
                int colonne = (int) emplacement.getClass()
                        .getMethod("getColumn").invoke(emplacement);
                return new Position(fichier, Math.max(0, ligne), Math.max(0, colonne));
            } catch (Exception illisible) {
                return new Position("", 0, 0); // Emplacement non localisé.
            }
        }
    }

    /** Résultat d'une compilation. */
    public record CompileResult(boolean success, List<String> consoleLines,
                                int errorCount) {
    }
}
