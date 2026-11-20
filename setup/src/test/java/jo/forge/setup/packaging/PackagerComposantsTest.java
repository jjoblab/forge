package jo.forge.setup.packaging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        PackagerComposants.emballerJarDexe(dex, archive, "lib/java-compiler.jar", List.of());

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

        PackagerComposants.emballerJarDexe(dex, archive, "lib/java-compiler.jar",
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

        PackagerComposants.emballerJarDexe(dex, archive, "engine.jar",
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
                () -> PackagerComposants.emballerJarDexe(dex, autreArchive, "engine.jar",
                        List.of(premier, conflit)));
        assertTrue(refusée.getMessage().contains("commun.txt"),
                "le message doit nommer la ressource en conflit : " + refusée.getMessage());
    }

    @Test
    void cheminsDeDestinationDesComposants() {
        assertEquals("engine.jar", PackagerComposants.cheminDexe("engine-core"));
        assertEquals("lib/java-compiler.jar",
                PackagerComposants.cheminDexe("java-compiler"));
        assertEquals("lib/dexeur.jar", PackagerComposants.cheminDexe("dexeur"));
        assertEquals("lib/signeur.jar", PackagerComposants.cheminDexe("signeur"));
        assertEquals("components/aapt2-arm64-v8a/aapt2",
                PackagerComposants.cheminOutil("aapt2-arm64-v8a"));
        assertEquals("components/platform-34/android.jar",
                PackagerComposants.cheminOutil("platform-34"));
    }

    /**
     * Écrit un jar de test : chaque entrée textuelle en UTF-8 (répertoire si le nom
     * finit par une barre oblique — contenu ignoré).
     */
    private Path jarDe(Map<String, String> entrées) throws IOException {
        Path jar = temporaire.resolve("source-" + compteur++ + ".jar");
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
        byte[] octets = entréeUnique(archive, archive.getFileName().toString()
                .startsWith("engine-core") ? "engine.jar" : "lib/java-compiler.jar");
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
