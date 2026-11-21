package jo.forge.setup.packaging;

import jo.forge.setup.packaging.fixtures.ClasseQuiRéférenceLApi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests du layout des composants publiés : chaque entrée d'archive doit être posée à
 * son chemin de destination lors de l'installation (convention des étapes 3-4) — le
 * cœur en {@code engine.jar}, les compagnons dexés en {@code lib/&lt;id&gt;.jar}, les
 * outils en {@code components/&lt;id&gt;/…} (chemins attendus par la session de la coquille
 * et par le localisateur d'outils du moteur).
 *
 * <p>Correctif v0.2.4 : les jars dexés doivent aussi porter les <b>ressources</b> des
 * jars sources ({@code messages.properties} d'ECJ, tables {@code .rsc} du parseur) —
 * sur ART, c'est par les entrées zip du classpath que le classloader les sert, et leur
 * perte tuait la compilation sur l'appareil ({@code Can't find bundle for base name
 * …batch.messages}).</p>
 *
 * <p>Correctif v0.2.6 : le composant {@code java-compiler} embarque l'API
 * {@code java.compiler} (absente d'ART, 63 références côté ECJ) extraite du module
 * de la JVM d'assemblage, avec {@code javax.lang.model.SourceVersion} RÉÉCRIT —
 * l'original lit {@code Runtime.version()} dans son initialiseur, classe absente
 * d'ART et non redéfinissable ({@code java.*} interdit dans un dex). Le garde-fou
 * de l'assemblage vérifie que toute classe de l'API référencée par le compilateur
 * est servie par le jar produit.</p>
 */
class PackagerComposantsTest {

    /** Horodatage fixe des entrées (déterminisme) — même constante que le packager. */
    private static final long HORODATAGE_FIXE = 315532800000L;

    @TempDir
    Path temporaire;

    /** Compteur pour des noms de fichiers uniques entre les tests. */
    private int compteur;

    @Test
    void jarDexePoséASonCheminDeDestinationAvecClassesDex() throws IOException {
        Path dex = temporaire.resolve("classes.dex");
        byte[] octetsDex = "dex factice".getBytes(StandardCharsets.UTF_8);
        Files.write(dex, octetsDex);
        Path archive = temporaire.resolve("java-compiler-0.2.0.zip");

        PackagerComposants.emballerJarDexe(List.of(dex), archive, "lib/java-compiler.jar", List.of());

        byte[] jarEmballé = entréeUnique(archive, "lib/java-compiler.jar");
        // Le jar interne sans sources est un zip à entrée unique classes.dex, octets identiques.
        try (ZipInputStream jar = new ZipInputStream(
                new ByteArrayInputStream(jarEmballé))) {
            ZipEntry entree = jar.getNextEntry();
            assertNotNull(entree, "le jar interne doit contenir classes.dex");
            assertEquals("classes.dex", entree.getName());
            assertEquals("dex factice",
                    new String(jar.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void ressourcesDesJarsConservéesÀCôtéDuDex() throws IOException {
        // Un jar source façonné comme ECJ : messages localisés, tables du parseur,
        // notice de licence, services — mais aussi classes, manifeste, signatures et
        // répertoires qui ne doivent PAS traverser le dexage.
        Path jarSource = jarDe(Map.ofEntries(
                Map.entry("org/eclipse/jdt/internal/compiler/batch/messages.properties",
                        "compile.ok=oui"),
                Map.entry("org/eclipse/jdt/internal/compiler/parser/tables.rsc",
                        "TABLES"),
                Map.entry("about.html", "EPL-2.0 — notice"),
                Map.entry("META-INF/services/javax.tools.JavaCompiler",
                        "org.eclipse.jdt.internal.compiler.tool.EclipseCompiler"),
                Map.entry("a/b/C.class", "CAFE BABE"),
                Map.entry("META-INF/MANIFEST.MF", "Manifest-Version: 1.0"),
                Map.entry("META-INF/ECLIPSE_.SF", "SIGNATURE"),
                Map.entry("META-INF/ECLIPSE_.RSA", "SIGNATURE"),
                Map.entry("org/", "")));
        Path dex = temporaire.resolve("classes.dex");
        Files.write(dex, "dex factice".getBytes(StandardCharsets.UTF_8));
        Path archive = temporaire.resolve("java-compiler-0.2.4.zip");

        PackagerComposants.emballerJarDexe(List.of(dex), archive, "lib/java-compiler.jar",
                List.of(jarSource));

        try (ZipFile jar = new ZipFile(jarInterne(archive).toFile())) {
            List<String> noms = jar.stream().map(ZipEntry::getName).toList();
            // classes.dex d'abord, puis les ressources dans l'ordre alphabétique :
            // le jar produit est déterministe, entrée pour entrée.
            assertEquals(List.of(
                    "classes.dex",
                    "META-INF/services/javax.tools.JavaCompiler",
                    "about.html",
                    "org/eclipse/jdt/internal/compiler/batch/messages.properties",
                    "org/eclipse/jdt/internal/compiler/parser/tables.rsc"),
                    noms, "ordre attendu : classes.dex puis ressources triées");
            assertEquals("compile.ok=oui",
                    texte(jar, "org/eclipse/jdt/internal/compiler/batch/messages.properties"));
            assertEquals("TABLES",
                    texte(jar, "org/eclipse/jdt/internal/compiler/parser/tables.rsc"));
            // Les classes sont dans le dex, pas en doublon ; manifeste et signatures du
            // jar d'origine ne signent plus rien une fois fusionnés ; les répertoires
            // sont recréés à l'extraction.
            assertFalse(noms.contains("a/b/C.class"),
                    "les .class sont déjà dans le dex");
            assertFalse(noms.contains("META-INF/MANIFEST.MF"),
                    "le manifeste d'origine ne doit pas être fusionné");
            assertFalse(noms.contains("META-INF/ECLIPSE_.SF"),
                    "les signatures d'origine doivent être écartées");
            assertFalse(noms.contains("META-INF/ECLIPSE_.RSA"),
                    "les signatures d'origine doivent être écartées");
            assertFalse(noms.contains("org/"), "les répertoires ne doivent pas être copiés");
            jar.stream().forEach(entrée ->
                    assertEquals(HORODATAGE_FIXE, entrée.getTime(),
                            "horodatage fixe exigé (déterminisme) : " + entrée.getName()));
        }
    }

    @Test
    void ressourcesIdentiquesDédoublonnéesConflitRefusé() throws IOException {
        Path premier = jarDe(Map.of("commun.txt", "MÊME", "premier.txt", "A"));
        Path secondIdentique = jarDe(Map.of("commun.txt", "MÊME", "second.txt", "B"));
        Path dex = temporaire.resolve("classes.dex");
        Files.write(dex, "dex factice".getBytes(StandardCharsets.UTF_8));
        Path archive = temporaire.resolve("engine-core-0.2.4.zip");

        PackagerComposants.emballerJarDexe(List.of(dex), archive, "engine.jar",
                List.of(premier, secondIdentique));

        try (ZipFile jar = new ZipFile(jarInterne(archive).toFile())) {
            List<String> noms = jar.stream().map(ZipEntry::getName).toList();
            assertEquals(1, noms.stream().filter("commun.txt"::equals).count(),
                    "une ressource identique portée par deux jars n'est écrite qu'une fois");
            assertTrue(noms.containsAll(List.of("premier.txt", "second.txt")));
        }

        Path conflit = jarDe(Map.of("commun.txt", "CONTENU DIFFÉRENT"));
        Path autreArchive = temporaire.resolve("engine-core-conflit-0.2.4.zip");
        IOException refusée = assertThrows(IOException.class,
                () -> PackagerComposants.emballerJarDexe(List.of(dex), autreArchive,
                        "engine.jar", List.of(premier, conflit)));
        assertTrue(refusée.getMessage().contains("commun.txt"),
                "le message doit nommer la ressource en conflit : " + refusée.getMessage());
    }

    @Test
    void métadonnéesKotlinModuleEnConflitPremierJarGagnant() throws IOException {
        // Constat v0.2.9 (composant kotlin-compiler) : la distribution Kotlin livre
        // les mêmes META-INF/*.kotlin_module dans plusieurs jars — kotlin-reflect
        // rebundle la désérialisation du compilateur (11 chemins en conflit mesurés
        // sur l'ensemble 2.0.21/1.6.10). La fusion garde la copie du PREMIER jar de
        // l'ordre passé (l'embarquable avant ses dépendances — même gagnant qu'un
        // classpath de bureau) au lieu de refuser ; tout autre conflit reste refusé.
        Path compilateur = jarDe(Map.of(
                "META-INF/compiler.common.jvm.kotlin_module", "CARTE DU COMPILATEUR",
                "messages/compilateur.properties", "messages"));
        Path reflect = jarDe(Map.of(
                "META-INF/compiler.common.jvm.kotlin_module", "CARTE DU REFLECT",
                "messages/reflect.properties", "messages"));
        Path dex = temporaire.resolve("classes.dex");
        Files.write(dex, "dex factice".getBytes(StandardCharsets.UTF_8));
        Path archive = temporaire.resolve("kotlin-compiler-0.2.9.zip");

        PackagerComposants.emballerJarDexe(List.of(dex), archive,
                "lib/kotlin-compiler.jar", List.of(compilateur, reflect));

        try (ZipFile jar = new ZipFile(
                jarInterne(archive, "lib/kotlin-compiler.jar").toFile())) {
            byte[] carte = jar.getInputStream(
                    jar.getEntry("META-INF/compiler.common.jvm.kotlin_module"))
                    .readAllBytes();
            assertEquals("CARTE DU COMPILATEUR",
                    new String(carte, StandardCharsets.UTF_8),
                    "la copie du PREMIER jar gagne (ordre passé = classpath de bureau)");
            assertNotNull(jar.getEntry("messages/compilateur.properties"));
            assertNotNull(jar.getEntry("messages/reflect.properties"),
                    "les ressources propres à chaque jar sont conservées");
        }
    }

    @Test
    void noticeSupplémentairePoséeDansLeJarDexé() throws IOException {
        // LGPL-2.1 (trove4j du composant kotlin-compiler) : le jar Maven n'embarque
        // AUCUNE notice — la redistribution exige la licence et l'offre de source,
        // l'assembleur les pose dans le jar du composant (distribution.md §7).
        Path jar = jarDe(Map.of("messages/compilateur.properties", "messages"));
        Path dex = temporaire.resolve("classes.dex");
        Files.write(dex, "dex factice".getBytes(StandardCharsets.UTF_8));
        Path archive = temporaire.resolve("kotlin-compiler-0.2.9.zip");

        PackagerComposants.emballerJarDexe(List.of(dex), archive,
                "lib/kotlin-compiler.jar", List.of(jar),
                Map.of("META-INF/notice-trove4j.txt", "LGPL-2.1 — voir source amont"));

        try (ZipFile interne = new ZipFile(
                jarInterne(archive, "lib/kotlin-compiler.jar").toFile())) {
            assertEquals("LGPL-2.1 — voir source amont",
                    texte(interne, "META-INF/notice-trove4j.txt"),
                    "la notice supplémentaire est posée dans le jar du composant");
        }
    }

    @Test
    void licencesDivergentesConservéesSousLeurCheminDeJar() throws IOException {
        // Constat v0.2.14 (jars StAX du composant kotlin-compiler) : aalto porte
        // un META-INF/LICENSE Apache-2.0 et stax2 un BSD 2-clauses — contenus
        // LÉGITIMEMENT divergents. Refuser la fusion ne protège rien et casse
        // la chaîne ; la copie du premier jar reste au chemin canonique et
        // chaque copie divergente vit sous META-INF/licenses/<jar>/… : aucune
        // notice de licence n'est perdue, aucune n'en écrase une autre.
        Path aalto = jarNommé("aalto-xml-1.3.0.jar",
                Map.of("META-INF/LICENSE", "Apache-2.0 (aalto)"));
        Path stax2 = jarNommé("stax2-api-4.2.1.jar",
                Map.of("META-INF/LICENSE", "BSD 2-clauses (stax2)"));
        Path dex = temporaire.resolve("classes.dex");
        Files.write(dex, "dex factice".getBytes(StandardCharsets.UTF_8));
        Path archive = temporaire.resolve("kotlin-compiler-0.2.14.zip");

        PackagerComposants.emballerJarDexe(List.of(dex), archive,
                "lib/kotlin-compiler.jar", List.of(aalto, stax2));

        try (ZipFile jar = new ZipFile(
                jarInterne(archive, "lib/kotlin-compiler.jar").toFile())) {
            assertEquals("Apache-2.0 (aalto)", texte(jar, "META-INF/LICENSE"),
                    "la copie du PREMIER jar gagne le chemin canonique");
            assertEquals("BSD 2-clauses (stax2)",
                    texte(jar, "META-INF/licenses/stax2-api-4.2.1/LICENSE"),
                    "la copie divergente est conservée sous son chemin de jar");
        }
    }

    @Test
    void cheminsDeDestinationDesComposants() {
        assertEquals("engine.jar", PackagerComposants.cheminDexe("engine-core"));
        assertEquals("lib/java-compiler.jar",
                PackagerComposants.cheminDexe("java-compiler"));
        assertEquals("lib/dexeur.jar", PackagerComposants.cheminDexe("dexeur"));
        assertEquals("lib/signeur.jar", PackagerComposants.cheminDexe("signeur"));
        assertEquals("lib/kotlin-compiler.jar",
                PackagerComposants.cheminDexe("kotlin-compiler"));
        assertEquals("components/aapt2-arm64-v8a/aapt2",
                PackagerComposants.cheminOutil("aapt2-arm64-v8a"));
        assertEquals("components/platform-34/android.jar",
                PackagerComposants.cheminOutil("platform-34"));
    }

    // --- Plugin compilateur Compose (v0.2.15) -------------------------------------------

    /** Un jar de plugin façon compose-compiler : registrar + descripteurs de services. */
    private Path jarPluginCompose() throws IOException {
        return jarDe(Map.of(
                "androidx/compose/compiler/plugins/kotlin/ComposePluginRegistrar.class",
                "CAFE BABE",
                "META-INF/services/org.jetbrains.kotlin.compiler.plugin.ComponentRegistrar",
                "androidx.compose.compiler.plugins.kotlin.ComposePluginRegistrar",
                "META-INF/services/org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor",
                "androidx.compose.compiler.plugins.kotlin.ComposeCommandLineProcessor",
                "META-INF/compiler-hosted.kotlin_module",
                "METADATA"));
    }

    @Test
    void gardeFouDuPluginCompose() throws IOException {
        // Bien formé : passe.
        PackagerComposants.vérifierPluginCompose(jarPluginCompose());

        // Sans registrar : la chaîne de publication casse ICI, pas sur l'appareil.
        Path sansRegistrar = jarDe(Map.of(
                "META-INF/services/org.jetbrains.kotlin.compiler.plugin.ComponentRegistrar",
                "androidx.compose.compiler.plugins.kotlin.ComposePluginRegistrar"));
        IOException refus1 = assertThrows(IOException.class,
                () -> PackagerComposants.vérifierPluginCompose(sansRegistrar));
        assertTrue(refus1.getMessage().contains("ComposePluginRegistrar"),
                "le message nomme la classe manquante : " + refus1.getMessage());

        // Sans descripteur de services : kotlinc ne pourrait pas découvrir le
        // plugin par -Xplugin.
        Path sansDescripteur = jarDe(Map.of(
                "androidx/compose/compiler/plugins/kotlin/ComposePluginRegistrar.class",
                "CAFE BABE"));
        IOException refus2 = assertThrows(IOException.class,
                () -> PackagerComposants.vérifierPluginCompose(sansDescripteur));
        assertTrue(refus2.getMessage().contains("META-INF/services"),
                "le message nomme le descripteur manquant : " + refus2.getMessage());
    }

    @Test
    void jarDexéDuPluginComposeGardeSesDescripteursDeServices() throws IOException {
        // Le jar dexé passé à -Xplugin doit porter les descripteurs : c'est EUX
        // que le compilateur lit dans le fichier (sur ART, les classes viennent
        // du classpath du moteur par délégation parente).
        Path plugin = jarPluginCompose();
        Path dex = temporaire.resolve("classes.dex");
        Files.write(dex, "dex du plugin".getBytes(StandardCharsets.UTF_8));
        Path archive = temporaire.resolve("kotlin-compiler-0.2.15.zip");

        PackagerComposants.emballerJarDexe(List.of(dex), archive,
                "lib/compose-compiler.jar", List.of(plugin), Map.of(
                        "META-INF/notice-compose-compiler.txt", "Apache-2.0"));

        try (ZipFile jar = new ZipFile(
                jarInterne(archive, "lib/compose-compiler.jar").toFile())) {
            assertEquals("dex du plugin", texte(jar, "classes.dex"),
                    "le dex du plugin est dans le jar");
            assertEquals("androidx.compose.compiler.plugins.kotlin"
                            + ".ComposePluginRegistrar",
                    texte(jar, "META-INF/services/org.jetbrains.kotlin.compiler.plugin"
                            + ".ComponentRegistrar"),
                    "le descripteur du registrar traverse le dexage");
            assertNotNull(jar.getEntry("META-INF/services/org.jetbrains.kotlin.compiler"
                    + ".plugin.CommandLineProcessor"),
                    "le descripteur du processeur de ligne de commande traverse");
            assertNotNull(jar.getEntry("META-INF/notice-compose-compiler.txt"),
                    "la notice de licence est embarquée");
            assertTrue(jar.getEntry("androidx/compose/compiler/plugins/kotlin/"
                    + "ComposePluginRegistrar.class") == null,
                    "les .class ne doublonnent pas le dex");
        }
    }

    @Test
    void multiDexToutesLesSuitesDansLeJar() throws IOException {
        // Un composant qui dépasse 64K méthodes : d8 écrit classes.dex,
        // classes2.dex, classes3.dex… — toutes doivent rejoindre le jar, dans
        // l'ordre, sinon seules les premières classes chargeront sur l'appareil.
        Path dex1 = temporaire.resolve("classes.dex");
        Path dex2 = temporaire.resolve("classes2.dex");
        Path dex3 = temporaire.resolve("classes3.dex");
        Files.write(dex1, "un".getBytes(StandardCharsets.UTF_8));
        Files.write(dex2, "deux".getBytes(StandardCharsets.UTF_8));
        Files.write(dex3, "trois".getBytes(StandardCharsets.UTF_8));
        Path archive = temporaire.resolve("kotlin-compiler-0.2.9.zip");

        PackagerComposants.emballerJarDexe(List.of(dex1, dex2, dex3), archive,
                "lib/kotlin-compiler.jar", List.of());

        byte[] octets = entréeUnique(archive, "lib/kotlin-compiler.jar");
        Path interne = temporaire.resolve("interne-multidex.jar");
        Files.write(interne, octets);
        try (ZipFile jar = new ZipFile(interne.toFile())) {
            assertEquals(List.of("classes.dex", "classes2.dex", "classes3.dex"),
                    jar.stream().map(ZipEntry::getName).toList(),
                    "toutes les suites multi-dex, dans l'ordre");
        }
    }

    @Test
    void empreinteDeContenuIndépendanteDuConteneur() throws IOException {
        // Deux archives différentes (nom, horodatage d'écriture) portant les MÊMES
        // entrées : même empreinte — c'est elle qui rend le composant réutilisable
        // d'une publication à l'autre malgré un re-zippage.
        Path binaire = temporaire.resolve("aapt2");
        Files.write(binaire, "binaire identique".getBytes(StandardCharsets.UTF_8));
        Path première = temporaire.resolve("a.zip");
        Path seconde = temporaire.resolve("b.zip");
        zipperAvecHorodatage(première, binaire, HORODATAGE_FIXE);
        zipperAvecHorodatage(seconde, binaire, HORODATAGE_FIXE + 86_400_000L);

        assertEquals(jo.forge.setup.manifest.EmpreinteContenu.deArchive(première),
                jo.forge.setup.manifest.EmpreinteContenu.deArchive(seconde),
                "l'empreinte ne dépend pas de l'archive, seulement des fichiers");
        // Une entrée différente : empreinte différente.
        Path autre = temporaire.resolve("autre-aapt2");
        Files.write(autre, "binaire différent".getBytes(StandardCharsets.UTF_8));
        Path troisième = temporaire.resolve("c.zip");
        zipperAvecHorodatage(troisième, autre, HORODATAGE_FIXE);
        assertNotEquals(jo.forge.setup.manifest.EmpreinteContenu.deArchive(première),
                jo.forge.setup.manifest.EmpreinteContenu.deArchive(troisième));
    }

    /** Zip d'un fichier unique sous un nom interne, horodatage paramétrable. */
    private static void zipperAvecHorodatage(Path archive, Path fichier, long horodatage)
            throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            ZipEntry entrée = new ZipEntry("components/aapt2-arm64-v8a/aapt2");
            entrée.setTime(horodatage);
            zip.putNextEntry(entrée);
            Files.copy(fichier, zip);
            zip.closeEntry();
        }
    }

    @Test
    void apiJavaCompilerExtraiteDuJrtSansModuleInfo() throws IOException {
        // L'extraction brute du module java.compiler : les trois paquets, sans
        // module-info (d8 ne doit pas le voir), avant remplacement du SourceVersion.
        SortedMap<String, byte[]> classes = PackagerComposants.classesApiDepuisJrt();
        assertTrue(classes.size() > 100,
                "l'API complète du module, pas un échantillon : " + classes.size());
        assertTrue(classes.containsKey("javax/lang/model/SourceVersion.class"));
        assertTrue(classes.containsKey("javax/annotation/processing/Processor.class"));
        assertTrue(classes.containsKey("javax/tools/JavaCompiler.class"));
        classes.keySet().forEach(nom -> assertTrue(nom.startsWith("javax/"),
                "seule l'API javax du module est extraite : " + nom));
        assertFalse(classes.containsKey("module-info.class"));
    }

    @Test
    void gardeFouApiÉchoueEnNommantLesClassesManquantes() throws IOException {
        // La fixture (compilée par javac) référence SourceVersion, JavaCompiler et
        // Processor : le garde-fou doit les nommer TOUTES quand rien n'est servi.
        Path jarRéférenceur = jarDeClasseCompilée(ClasseQuiRéférenceLApi.class);

        IOException échec = assertThrows(IOException.class,
                () -> PackagerComposants.vérifierRéférencesApi(jarRéférenceur, Set.of()));
        for (String attendue : List.of("javax/lang/model/SourceVersion",
                "javax/annotation/processing/Processor", "javax/tools/JavaCompiler")) {
            assertTrue(échec.getMessage().contains(attendue),
                    attendue + " doit être nommée : " + échec.getMessage());
        }

        // Tout est servi : le garde-fou se tait (références couvertes = assemblage OK).
        PackagerComposants.vérifierRéférencesApi(jarRéférenceur, Set.of(
                "javax/lang/model/SourceVersion.class",
                "javax/annotation/processing/Processor.class",
                "javax/tools/JavaCompiler.class"));
    }

    @Test
    void sourceVersionRéécritEstArtSûr() throws Exception {
        // Assemblage COMPLET du jar API avec le vrai ECJ et un vrai android.jar
        // (chemin d'amorçage de la compilation) : ignoré si l'un manque — la CI
        // les a tous deux.
        Path jarEcj = jarEcjDuClasspath();
        Path androidJar = androidJarDeTest();
        assumeTrue(jarEcj != null && androidJar != null,
                "ECJ (class de test) et android.jar (SDK ou copie locale) requis");

        Path jarApi = PackagerComposants.assemblerApiCompiler(jarEcj, androidJar,
                Path.of("src", "compatArt", "java"));

        try (ZipFile jar = new ZipFile(jarApi.toFile())) {
            // Le SourceVersion du jar est NOTRE réécriture : Java 8 (52, dexable
            // partout), AUCUNE référence à Runtime$Version, gamme complète.
            byte[] sourceVersion = jar.getInputStream(
                    jar.getEntry("javax/lang/model/SourceVersion.class")).readAllBytes();
            assertEquals(52, majeur(sourceVersion),
                    "compilé par ECJ en -1.8");
            String contenu = new String(sourceVersion, StandardCharsets.ISO_8859_1);
            assertFalse(contenu.contains("Runtime$Version"),
                    "l'original du JDK lit Runtime.version() dans son initialiseur —"
                            + " absent d'ART, et java.* est interdit dans un dex");
            assertTrue(contenu.contains("RELEASE_21"),
                    "la gamme de constantes doit couvrir ce qu'ECJ sait énumérer");
            assertTrue(contenu.contains("latestSupported"));
            // L'API complète est servie à côté, notice OpenJDK comprise ; le
            // garde-fou a déjà validé toutes les références d'ECJ avant l'écriture.
            assertNotNull(jar.getEntry("javax/tools/JavaCompiler.class"));
            assertNotNull(jar.getEntry("javax/annotation/processing/Processor.class"));
            assertNotNull(jar.getEntry("about-openjdk-java-compiler.txt"));
            assertNull(jar.getEntry("module-info.class"));
        }
    }

    /**
     * Emballe les octets compilés d'une classe de test en jar (le garde-fou lit
     * des jars, pas des classes isolées).
     */
    private Path jarDeClasseCompilée(Class<?> classe) throws IOException {
        byte[] octets;
        try (java.io.InputStream flux = classe.getResourceAsStream(
                classe.getSimpleName() + ".class")) {
            assertNotNull(flux, "octets de " + classe.getName());
            octets = flux.readAllBytes();
        }
        Path jar = temporaire.resolve("classe-" + compteur++ + ".jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            ZipEntry entrée = new ZipEntry(
                    classe.getName().replace('.', '/') + ".class");
            entrée.setTime(HORODATAGE_FIXE);
            zip.putNextEntry(entrée);
            zip.write(octets);
            zip.closeEntry();
        }
        return jar;
    }

    /** Localise le jar ECJ du classpath de test (dépendance de test). */
    private static Path jarEcjDuClasspath() {
        for (String entrée : System.getProperty("java.class.path")
                .split(java.io.File.pathSeparator)) {
            Path jar = Path.of(entrée);
            if (Files.isRegularFile(jar)
                    && jar.getFileName().toString().startsWith("ecj-")) {
                return jar;
            }
        }
        return null;
    }

    /** Localise un android.jar réel (même convention que les tests du moteur). */
    private static Path androidJarDeTest() {
        String propriété = System.getProperty("forge.androidJar");
        if (propriété != null && Files.isRegularFile(Path.of(propriété))) {
            return Path.of(propriété);
        }
        Path locale = Path.of("/home/z/my-project/.jdk/android-34.jar");
        if (Files.isRegularFile(locale)) {
            return locale;
        }
        for (String variable : new String[]{"ANDROID_HOME", "ANDROID_SDK_ROOT"}) {
            String racine = System.getenv(variable);
            if (racine == null) {
                continue;
            }
            for (int api = 34; api >= 28; api--) {
                Path jar = Path.of(racine, "platforms", "android-" + api,
                        "android.jar");
                if (Files.isRegularFile(jar)) {
                    return jar;
                }
            }
        }
        return null;
    }

    /** Version majeure d'un fichier de classe (octets 6-7, gros-boutiste). */
    private static int majeur(byte[] classe) {
        return ((classe[6] & 0xFF) << 8) | (classe[7] & 0xFF);
    }

    /**
     * Écrit un jar de test : chaque entrée textuelle en UTF-8 (répertoire si le nom
     * finit par une barre oblique — contenu ignoré).
     */
    private Path jarDe(Map<String, String> entrées) throws IOException {
        return jarNommé("source-" + compteur++ + ".jar", entrées);
    }

    /**
     * Écrit un jar de test NOMMÉ (le nom compte : les notices de licence
     * divergentes sont relocalisées sous {@code META-INF/licenses/<jar>/…}).
     */
    private Path jarNommé(String nom, Map<String, String> entrées) throws IOException {
        Path jar = temporaire.resolve(nom);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (Map.Entry<String, String> entrée : entrées.entrySet()) {
                zip.putNextEntry(new ZipEntry(entrée.getKey()));
                if (!entrée.getKey().endsWith("/")) {
                    zip.write(entrée.getValue().getBytes(StandardCharsets.UTF_8));
                }
                zip.closeEntry();
            }
        }
        return jar;
    }

    /**
     * Extrait le jar dexé interne de l'archive du composant vers un fichier lisible
     * par {@link ZipFile} (noms, contenus et horodatages).
     */
    private Path jarInterne(Path archive) throws IOException {
        return jarInterne(archive, archive.getFileName().toString()
                .startsWith("engine-core") ? "engine.jar" : "lib/java-compiler.jar");
    }

    /**
     * Extrait le jar dexé interne de l'archive (chemin interne explicite — un
     * composant compagnon vit en {@code lib/<id>.jar}).
     */
    private Path jarInterne(Path archive, String cheminInterne) throws IOException {
        byte[] octets = entréeUnique(archive, cheminInterne);
        Path fichier = temporaire.resolve("interne-" + compteur++ + ".jar");
        Files.write(fichier, octets);
        return fichier;
    }

    /** Contenu texte d'une entrée du jar. */
    private static String texte(ZipFile jar, String nom) throws IOException {
        ZipEntry entrée = jar.getEntry(nom);
        assertNotNull(entrée, "entrée attendue : " + nom);
        return new String(jar.getInputStream(entrée).readAllBytes(), StandardCharsets.UTF_8);
    }

    /**
     * Lit l'entrée unique attendue d'une archive (toute autre entrée échoue).
     */
    private static byte[] entréeUnique(Path archive, String nomAttendu)
            throws IOException {
        byte[] contenu = null;
        try (ZipInputStream zip = new ZipInputStream(
                Files.newInputStream(archive))) {
            ZipEntry entree;
            while ((entree = zip.getNextEntry()) != null) {
                assertEquals(nomAttendu, entree.getName(),
                        "entrée inattendue dans l'archive");
                contenu = zip.readAllBytes();
            }
        }
        assertNotNull(contenu, "entrée " + nomAttendu + " absente de l'archive");
        return contenu;
    }
}
