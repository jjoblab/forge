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
 * <p><b>Audit ART (v0.2.11, corrigé réellement en v0.2.12)</b> : le premier
 * {@code compileKotlin} sur l'appareil (rapport v0.2.10, projet kotlin-simple)
 * mourait par {@code IllegalStateException: Resource not found: …/PathUtil.class}
 * — le compilateur s'auto-localise par {@code ClassLoader.getResource("…class")},
 * or le jar dexé ne porte PLUS d'entrées {@code .class} (remplacées par les
 * dex). Le bytecode 2.0.21 lu montre DEUX appels distincts de cette
 * auto-localisation, tous deux évitables par arguments publics, SANS la
 * moindre passe bytecode (divergence assumée d'avec le moteur de référence,
 * REF-133, qui recompile le compilateur : notre jar dexé — vérifié sur
 * l'assemblage — CONSERVE {@code META-INF/extensions/compiler.xml}, les
 * {@code .kotlin_builtins} et les {@code .kotlin_module}, lus par
 * {@code java.util.zip.ZipFile}, disponible sur ART) :</p>
 * <ol>
 *   <li>{@code computeKotlinPaths} : {@code -kotlin-home <répertoire existant>}
 *       court-circuite la recherche par ressource (le répertoire doit exister —
 *       sinon « Kotlin home does not exist or is not a directory »). Le
 *       rapport appareil v0.2.11 a montré que l'argument n'était JAMAIS posé :
 *       le jar du composant était cherché sous {@code components/} alors que
 *       l'installateur l'extrait à plat dans {@code lib/} (v0.2.12) ;</li>
 *   <li>le démarrage de l'environnement ({@code KotlinCoreEnvironment}) :
 *       {@code -Xintellij-plugin-root <kotlin-compiler.jar>} désigne le jar réel
 *       qui porte la configuration des points d'extension.</li>
 * </ol>
 *
 * <p><b>Et le mur d'après</b> (rapport v0.2.13, corrigé en v0.2.14 par le
 * PACKAGING, pas ici) : la lecture du descripteur désigné ci-dessus meurt sur ART
 * par {@code NoClassDefFoundError javax/xml/stream/XMLStreamException} — le cœur
 * IntelliJ embarqué le lit avec l'API StAX, absente d'Android. Le composant
 * kotlin-compiler porte désormais l'API JSR-173 standalone, son implémentation
 * aalto et la surface javax de chargement (Icon, Notification*) avec ses shims
 * headless : rien à faire dans ce pont, l'environnement est servi par le
 * classpath du moteur comme tout le reste (garde-fou et test dédiés côté
 * assemblage — {@code PackagerComposants}, {@code StaxSurArtSimuléTest}).</p>
 *
 * <p><b>Politique d'options</b> : avertissements coupés ({@code -nowarn}), cible
 * JVM alignée sur {@code javaRelease} du module, {@code -no-reflect} (le module
 * qui veut kotlin-reflect le déclare en dépendance Maven), {@code -no-stdlib}
 * TOUJOURS — la bibliothèque standard vient du classpath (jar de la stdlib
 * localisé sur le classpath du moteur en JVM de test, dépendance Maven du
 * module sur l'appareil : le chargeur d'ART ne sert pas les entrées
 * {@code .class} d'un jar dexé) ; son absence est un AVERTISSEMENT actionnable
 * (le Kotlin builtins-only compile, l'exécution exigera la stdlib) — et
 * {@code -no-jdk} quand la bibliothèque de plate-forme est fournie
 * ({@code android.jar} au classpath, ART compris : il n'y a pas de JDK hôte à
 * emprunter).</p>
 *
 * <p><b>Compilation jointe</b> (v0.2.11) : les sources {@code .java} du module
 * accompagnent les {@code .kt} en arguments libres — kotlinc les PARSE pour la
 * résolution de symboles (le Kotlin peut référencer le Java du même module, le
 * {@code R.java} d'une application Android compris) mais n'émets DES classes
 * que pour le Kotlin ; ECJ compile ensuite le Java CONTRE ces classes. Motif du
 * moteur de référence (KotlinJvmCompiler.compile).</p>
 *
 * <p><b>Plugins compilateur</b> (v0.2.15, Compose) : des jars passés à kotlinc
 * par {@code -Xplugin}. Le compilateur lit leur descripteur de services
 * ({@code META-INF/services}) DANS LE FICHIER, puis résout les classes du
 * registrar par DÉLÉGATION PARENTE — sur ART, où les octets {@code .class}
 * d'un jar ne peuvent pas être définis, le plugin doit donc AUSSI vivre dexé
 * dans le classpath du moteur (composant installé {@code lib/compose-compiler.jar},
 * servi par la session comme tout compagnon de {@code lib/}) ; sur la JVM de
 * développement, le jar du classpath de test sert tel quel. L'activation est
 * une SONDE DE CLASSPATH (motif du moteur de référence : la présence de
 * {@code androidx/compose/runtime/Composable.class} au classpath de compilation
 * du module signifie que le plugin est requis) — aucune configuration à exiger
 * de l'utilisateur.</p>
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

    /**
     * Classe du registrar du plugin compilateur Compose (sonde de localisation
     * du jar sur le classpath du moteur, JVM de développement).
     */
    private static final String CLASSE_REGISTRAR_COMPOSE =
            "androidx.compose.compiler.plugins.kotlin.ComposePluginRegistrar";

    /** Classe marquant une dépendance Compose au classpath (l'activation du plugin). */
    private static final String ENTRÉE_COMPOSABLE =
            "androidx/compose/runtime/Composable.class";

    /** Suffixe des sources Kotlin. */
    private static final String SUFFIXE_KOTLIN = ".kt";

    /** Suffixe des sources Java (compilation jointe : parsées, non émises). */
    private static final String SUFFIXE_JAVA = ".java";

    /** Propriété publique du compilateur : garder l'environnement entre builds. */
    private static final String PROPRIÉTÉ_KEEPALIVE = "kotlin.environment.keepalive";

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
     * Localise le jar du composant kotlin-compiler installé dans le moteur :
     * {@code lib/kotlin-compiler.jar} — la convention RÉELLE d'installation
     * (l'installateur extrait les composants applicatifs à plat dans
     * {@code lib/}, comme java-compiler, dexeur et signeur ; seuls les outils
     * par ABI vont dans {@code components/}). Le correctif v0.2.11 cherchait
     * {@code components/kotlin-compiler/lib/…}, un chemin que le packaging ne
     * produit JAMAIS : le rapport appareil v0.2.11 (moto g06, Android 15)
     * montrait {@code -kotlin-home} jamais posé et le crash {@code PathUtil}
     * intact. Retourne {@code null} s'il n'y est pas (JVM de développement :
     * le compilateur vient du classpath de test et n'a besoin ni de maison ni
     * de racine).
     *
     * @param engineHome racine du moteur installé (peut être null).
     * @return le chemin du jar dexé, ou null.
     */
    public static Path jarComposant(Path engineHome) {
        if (engineHome == null) {
            return null;
        }
        Path jar = engineHome.resolve("lib").resolve("kotlin-compiler.jar");
        return Files.isRegularFile(jar) ? jar : null;
    }

    /**
     * Localise le jar du plugin compilateur Compose : sur l'appareil, le jar
     * DEXÉ du composant kotlin-compiler ({@code lib/compose-compiler.jar} — ses
     * entrées {@code META-INF/services} sont conservées par le packaging, c'est
     * le descripteur que kotlinc lit ; ses classes sont servies par le chargeur
     * du moteur, la délégation parente du chargeur de plugins fait le reste) ;
     * sur la JVM de développement, le jar du classpath de test (localisé par la
     * classe de son registrar).
     *
     * @param engineHome racine du moteur installé (peut être null).
     * @return le chemin du jar, ou null si le plugin est absent.
     */
    public static Path jarComposePlugin(Path engineHome) {
        if (engineHome != null) {
            Path jar = engineHome.resolve("lib").resolve("compose-compiler.jar");
            if (Files.isRegularFile(jar)) {
                return jar;
            }
        }
        return jarDeLaClasse(CLASSE_REGISTRAR_COMPOSE);
    }

    /**
     * Sonde Compose du classpath de compilation d'un module : la présence de
     * {@code androidx/compose/runtime/Composable.class} dans un jar (ou un
     * répertoire de classes) signifie que le module écrit des composables — le
     * plugin compilateur Compose est requis (sans lui, le bytecode émis est
     * inutilisable à l'exécution : le plugin tisse les groupes de redémarrage
     * et le {@code Composer} synthétique). S'arrête à la première trouvaille.
     *
     * @param classpath entrées du classpath de compilation (jars, répertoires).
     * @return {@code true} si le runtime Compose y est.
     */
    public static boolean moduleCompose(List<Path> classpath) {
        for (Path entrée : classpath) {
            if (entrée == null || !Files.exists(entrée)) {
                continue;
            }
            if (Files.isDirectory(entrée)) {
                if (Files.exists(entrée.resolve(ENTRÉE_COMPOSABLE))) {
                    return true;
                }
            } else if (entrée.toString().endsWith(".jar")) {
                try (java.util.zip.ZipFile zip =
                        new java.util.zip.ZipFile(entrée.toFile())) {
                    if (zip.getEntry(ENTRÉE_COMPOSABLE) != null) {
                        return true;
                    }
                } catch (IOException illisible) {
                    // Jar illisible : la compilation le dira nommément.
                }
            }
        }
        return false;
    }

    /**
     * Compile des sources Kotlin vers un répertoire de classes.
     *
     * @param sources        fichiers {@code .kt} à compiler (au moins un).
     * @param sourcesJava    fichiers {@code .java} du module (compilation jointe :
     *                       parsés pour la résolution de symboles, jamais émis).
     * @param classpath      entrées du classpath de compilation (jars et
     *                       répertoires, dépendances de projet comprises).
     * @param plateforme     bibliothèque de plate-forme ({@code android.jar}) :
     *                       ajoutée au classpath, signalée par {@code -no-jdk}.
     * @param jarCompilateur jar du composant kotlin-compiler (maison Kotlin et
     *                       racine de configuration IntelliJ sur l'appareil), ou
     *                       null sur la JVM de développement.
     * @param plugins        jars de plugins compilateur ({@code -Xplugin} —
     *                       Compose ; vide si aucun), les fichiers inexistants
     *                       sont écartés.
     * @param outputDir      répertoire de sortie des {@code .class} (créé s'il manque).
     * @param jvmCible       cible JVM ({@code -jvm-target}, ex. 17).
     * @param sink           puits des diagnostics structurés.
     * @return le résultat : succès, lignes de console, nombre d'erreurs.
     * @throws IOException si la préparation du répertoire de sortie échoue ou que
     *                     le compilateur est absent (message actionnable).
     */
    public static CompileResult compiler(List<Path> sources, List<Path> sourcesJava,
                                         List<Path> classpath, List<Path> plateforme,
                                         Path jarCompilateur, List<Path> plugins,
                                         Path outputDir,
                                         int jvmCible, DiagnosticSink sink)
            throws IOException {
        Files.createDirectories(outputDir);
        if (sources.isEmpty()) {
            return new CompileResult(true, List.of("aucune source Kotlin à compiler"), 0);
        }
        environnementPersistant();
        Class<?> compilateur = classeCompilateur();
        if (compilateur == null) {
            String message = "compilateur Kotlin absent du moteur — installez le "
                    + "composant kotlin-compiler (assistant du moteur) ; sur la JVM "
                    + "de développement, il vient du classpath de test";
            sink.report(Diagnostic.error(TOOL, message));
            return new CompileResult(false, List.of("erreur : " + message), 1);
        }

        List<String> argumentsCli = new ArrayList<>(List.of(
                "-nowarn", "-no-reflect", "-no-stdlib",
                "-jvm-target", Integer.toString(jvmCible),
                "-d", outputDir.toString()));
        List<Path> cheminComplet = new ArrayList<>(classpath);
        cheminComplet.addAll(plateforme);
        // Bibliothèque standard : elle vient TOUJOURS du classpath, jamais d'un
        // « Kotlin home » à deviner (c'est la recherche automatique qui tue le
        // compilateur sur ART). Le jar qui la porte (classpath de test, JVM) est
        // posé explicitement ; sur l'appareil, le module porte sa stdlib en
        // dépendance Maven — le chargeur d'ART ne sert pas les entrées .class
        // d'un jar dexé.
        Path stdlib = jarDeLaClasse("kotlin.jvm.internal.Intrinsics");
        if (stdlib != null) {
            cheminComplet.add(stdlib);
        }
        String stdlibAbsente = problèmeStdlib(stdlib, cheminComplet);
        if (stdlibAbsente != null) {
            // AVERTISSEMENT, pas un échec : du Kotlin qui n'utilise que les
            // builtins (Int, String, Any…) compile sans stdlib — mais TOUTE
            // classe Kotlin référencera kotlin.jvm.internal.Intrinsics à
            // l'EXÉCUTION : le module doit porter sa stdlib pour tourner.
            sink.report(new Diagnostic(
                    jo.forge.api.BuildEvent.Severity.WARNING, "", 0, 0, null,
                    stdlibAbsente, TOOL));
        }
        // Plate-forme : fournie (android.jar, ART compris) → le compilateur ne
        // cherchera pas de JDK hôte qui n'existe pas (motif du moteur de
        // référence : -no-jdk sur ART, la plate-forme est déjà au classpath).
        if (EcjCompiler.surArt() || !plateforme.isEmpty()) {
            argumentsCli.add("-no-jdk");
        }
        // Maison Kotlin et racine de configuration IntelliJ (correctifs v0.2.11
        // puis v0.2.12 — le premier cherchait le jar du composant sous
        // components/, chemin jamais produit par l'installateur : sur
        // l'appareil, -kotlin-home n'était JAMAIS posé et computeKotlinPaths
        // retombait dans l'auto-localisation qui tue ART). Avec le jar réel
        // (lib/kotlin-compiler.jar), la maison est le répertoire moteur
        // lui-même — un répertoire existant, ce que le bytecode exige.
        // L'option AVANCÉE prend la forme « = » (rapport appareil v0.2.12 :
        // « Advanced option value is passed in an obsolete form »).
        Path maisonKotlin = null;
        if (jarCompilateur != null && jarCompilateur.getParent() != null
                && jarCompilateur.getParent().getParent() != null
                && Files.isDirectory(jarCompilateur.getParent().getParent())) {
            maisonKotlin = jarCompilateur.getParent().getParent();
            argumentsCli.add("-kotlin-home");
            argumentsCli.add(maisonKotlin.toString());
            argumentsCli.add("-Xintellij-plugin-root=" + jarCompilateur);
        }
        if (EcjCompiler.surArt() && maisonKotlin == null) {
            // Franchise du diagnostic (politique du projet) : sans maison
            // explicite sur ART, le compilateur mourra par l'auto-localisation
            // (jar dexé sans entrées .class) — autant le dire AVANT, avec la
            // réparation, plutôt qu'une IllegalStateException obscure.
            String message = "composant kotlin-compiler introuvable dans le "
                    + "moteur installé (lib/kotlin-compiler.jar attendu) — sur "
                    + "ART le compilateur ne peut pas s'auto-localiser depuis un "
                    + "jar dexé ; réparez l'installation du moteur (bouton "
                    + "Réparer de l'assistant)";
            sink.report(Diagnostic.error(TOOL, message));
            return new CompileResult(false, List.of("erreur : " + message), 1);
        }
        if (EcjCompiler.surArt() && !ombreUnsafeActive()) {
            // Garde franche v0.2.13 : le jar du compilateur porte un conteneur
            // Unsafe dont l'initialiseur avide meurt sur ART (API cachée
            // copyMemory, rapport v0.2.12) — l'ombre tolérante vit dans
            // engine.jar, PREMIER du classpath moteur ; si le chargeur retient
            // l'originale (ordre cassé, installation corrompue), le compilateur
            // mourra d'un java.lang.Error opaque : autant le nommer avant.
            String message = "l'ombre Forge d'Unsafe (org.jetbrains.kotlin.com."
                    + "intellij.util.containers.Unsafe, tolérante aux API cachées "
                    + "d'ART) n'est pas la classe chargée — engine.jar doit "
                    + "précéder lib/kotlin-compiler.jar sur le classpath moteur ; "
                    + "réparez l'installation du moteur (bouton Réparer de "
                    + "l'assistant)";
            sink.report(Diagnostic.error(TOOL, message));
            return new CompileResult(false, List.of("erreur : " + message), 1);
        }
        if (!cheminComplet.isEmpty()) {
            argumentsCli.add("-classpath");
            argumentsCli.add(String.join(java.io.File.pathSeparator,
                    cheminComplet.stream().map(Path::toString).toList()));
        }
        // Plugins compilateur (-Xplugin=jar) : le jar doit exister sur disque,
        // c'est de lui que kotlinc lit le descripteur de services. La forme « = »
        // est exigée (la forme à deux arguments crie « obsolete form »).
        List<Path> pluginsPrésents = new ArrayList<>();
        for (Path plugin : plugins) {
            if (plugin != null && Files.isRegularFile(plugin)) {
                pluginsPrésents.add(plugin);
            }
        }
        for (Path plugin : pluginsPrésents) {
            argumentsCli.add("-Xplugin=" + plugin);
        }
        sources.forEach(source -> argumentsCli.add(source.toString()));
        sourcesJava.forEach(source -> argumentsCli.add(source.toString()));

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
            if (maisonKotlin != null) {
                console.add("maison Kotlin : " + maisonKotlin
                        + " ; configuration IntelliJ : " + jarCompilateur.getFileName());
            }
            for (Path plugin : pluginsPrésents) {
                console.add("plugin compilateur : " + plugin.getFileName());
            }
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
     * Indique si un fichier est une source Java (compilation jointe).
     *
     * @param chemin fichier à tester.
     * @return {@code true} pour un {@code .java}.
     */
    public static boolean estSourceJava(Path chemin) {
        return chemin.getFileName().toString().endsWith(SUFFIXE_JAVA);
    }

    /**
     * Message d'absence de la bibliothèque standard Kotlin au classpath, ou
     * {@code null} si elle y est : soit le jar du moteur la porte (JVM de test,
     * localisé par {@link #jarDeLaClasse}), soit le classpath de compilation
     * contient un jar {@code kotlin-stdlib*.jar} (dépendance Maven du module,
     * seul chemin sur l'appareil). Testable indépendamment du classloader.
     *
     * @param stdlibMoteur jar de la stdlib localisé sur le classpath du moteur.
     * @param classpath    classpath de compilation complet (plate-forme comprise).
     * @return le message d'erreur, ou null si la stdlib est présente.
     */
    static String problèmeStdlib(Path stdlibMoteur, List<Path> classpath) {
        if (stdlibMoteur != null) {
            return null;
        }
        for (Path entrée : classpath) {
            String nom = entrée.getFileName().toString();
            if (nom.endsWith(".jar") && nom.startsWith("kotlin-stdlib")) {
                return null;
            }
        }
        return "bibliothèque standard Kotlin absente du classpath — déclarez la "
                + "dépendance Maven org.jetbrains.kotlin:kotlin-stdlib pour ce "
                + "module dans forge.json (le compilateur du moteur ne fournit "
                + "pas la stdlib)";
    }

    /**
     * Demande au compilateur de garder son environnement applicatif entre les
     * compilations : {@code kotlin.environment.keepalive}, propriété publique
     * lue à la PREMIÈRE création de l'environnement — sans elle, chaque build
     * re-dresse l'environnement et relit tous les jars du classpath (motif du
     * moteur de référence, KotlinEnvironmentKeepAlive). Idempotente, ne remplace
     * jamais un choix explicite de l'hôte.
     */
    private static void environnementPersistant() {
        if (System.getProperty(PROPRIÉTÉ_KEEPALIVE) == null) {
            System.setProperty(PROPRIÉTÉ_KEEPALIVE, "true");
        }
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
            String brut = chemin.substring(0, séparateur);
            // Le chemin d'une URL jar EMBARQUE le schéma interne (« file:/… ») :
            // le retirer, sinon Path.of fabrique un chemin inexistant. Le bug —
            // concret, trouvé par la sonde du worker de test v0.2.11 — faisait
            // renvoyer null PARTOUT, même sur la JVM : la stdlib n'était jamais
            // posée au classpath de compilation, seuls les builtins (chargés du
            // chargeur, pas d'un « home ») permettaient de compiler.
            if (brut.startsWith("file:")) {
                brut = brut.substring("file:".length());
            }
            Path jar = Path.of(java.net.URLDecoder.decode(
                    brut, java.nio.charset.StandardCharsets.UTF_8));
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
     * Vérifie que l'ombre Forge du conteneur Unsafe du compilateur (tolérante aux
     * API cachées d'ART — {@code copyMemory} notamment, rapport v0.2.12) est bien
     * la classe que le chargeur retient : elle porte le champ marqueur
     * {@code OMBRE_FORGE}, absent de l'originale. Ne s'exécute que sur ART (sur
     * JVM, l'originale fonctionne et l'ombre n'a rien à sauver… mais elle est
     * quand même première, et les tests la traversent).
     */
    private static boolean ombreUnsafeActive() {
        try {
            Class<?> ombre = Class.forName(
                    "org.jetbrains.kotlin.com.intellij.util.containers.Unsafe");
            ombre.getField("OMBRE_FORGE");
            return true;
        } catch (Throwable pasLOmbre) {
            return false;
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
