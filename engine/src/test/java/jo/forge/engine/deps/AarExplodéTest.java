package jo.forge.engine.deps;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests de l'explosion d'AAR dans le cache de résolution (v0.2.15, prérequis
 * Compose) : parties servies (classes, libs, res, assets, manifeste,
 * métadonnées), explosion atomique réutilisable, garde zip-slip, jar vide
 * pour un AAR sans classes, borne minCompileSdk.
 */
class AarExplodéTest {

    @TempDir
    Path temporaire;

    /** Fabrique un AAR : entrées chemin → contenu (les répertoires implicites). */
    private byte[] aar(java.util.Map<String, byte[]> entrées) throws IOException {
        ByteArrayOutputStream octets = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(octets)) {
            for (java.util.Map.Entry<String, byte[]> entrée : entrées.entrySet()) {
                zip.putNextEntry(new ZipEntry(entrée.getKey()));
                zip.write(entrée.getValue());
                zip.closeEntry();
            }
        }
        return octets.toByteArray();
    }

    private static byte[] jarVide() throws IOException {
        ByteArrayOutputStream octets = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(octets)) {
            zip.finish();
        }
        return octets.toByteArray();
    }

    @Test
    void partiesServiesEtMarqueur() throws Exception {
        Path aar = temporaire.resolve("lib-1.0.aar");
        Files.write(aar, aar(java.util.Map.of(
                "classes.jar", jarVide(),
                "libs/imbrique.jar", jarVide(),
                "AndroidManifest.xml", ("<manifest package=\"demo.lib\">\n"
                        + "  <uses-sdk android:minSdkVersion=\"21\"/>\n"
                        + "</manifest>").getBytes(),
                "res/values/values.xml", "<resources/>".getBytes(),
                "META-INF/com/android/build/gradle/aar-metadata.properties",
                        "aarFormatVersion=1.0\nminCompileSdk=31\n".getBytes(),
                "proguard.txt", "-keep class **".getBytes())));
        Path cible = temporaire.resolve("exploded");

        AarExplodé explosé = AarExplodé.exploser(aar, cible);

        assertTrue(Files.isRegularFile(cible.resolve(".forge-exploded")),
                "le marqueur est posé (en dernier) — l'explosion est complète");
        assertEquals(cible.resolve("classes.jar"), explosé.classesJar(),
                "le jar de classes est l'entrée de classpath");
        assertEquals(List.of(cible.resolve("libs").resolve("imbrique.jar")),
                explosé.jarsBibliothèques(), "les libs/*.jar sont servis, triés");
        assertEquals(Optional.of(cible.resolve("res")), explosé.resDir());
        assertEquals(Optional.empty(), explosé.assetsDir(),
                "pas d'assets dans ce fixture");
        assertEquals(Optional.of("demo.lib"), explosé.packageDuManifeste(),
                "le paquet du manifeste devient un --extra-packages");
        assertEquals(OptionalInt.of(31), explosé.minCompileSdk(),
                "la borne minCompileSdk est lue des métadonnées AAR");
        assertTrue(!Files.exists(cible.resolve("proguard.txt")),
                "les parties non consommées ne sont pas extraites (v1)");

        // Ré-explosion : le marqueur court-circuite (le répertoire est réutilisé).
        AarExplodé relue = AarExplodé.exploser(aar, cible);
        assertEquals(Optional.of("demo.lib"), relue.packageDuManifeste());
    }

    @Test
    void aarSansClassesReçoitUnJarValide() throws Exception {
        Path aar = temporaire.resolve("vide-1.0.aar");
        Files.write(aar, aar(java.util.Map.of(
                "AndroidManifest.xml",
                "<manifest package=\"demo.vide\"/>".getBytes())));
        AarExplodé explosé = AarExplodé.exploser(aar, temporaire.resolve("explosé"));
        assertEquals(22, Files.size(explosé.classesJar()),
                "un AAR sans classes.jar reçoit un zip vide VALIDE (22 octets) — "
                        + "d8 comme le classpath d'ART exigent un fichier zip réel");
    }

    @Test
    void explosionsConcurrentesDuMêmeAarToutesValables() throws Exception {
        // Régression v0.2.16 (rapport appareil v0.2.15) : le provisoire était
        // nommé par java.lang.ProcessHandle.current().pid() — CONSTANT dans un
        // processus donné, donc DEUX explosions concurrentes de la même
        // coordonnée (tâches resolveDependencies de modules frères parallèles)
        // partageaient LE MÊME répertoire provisoire, s'écrasaient dessus, et
        // la perdante du renommage détruisait l'œuvre du gagnant. Le
        // provisoire est désormais UNIQUE PAR APPEL (createTempDirectory) et
        // la perdante réutilise le résultat complet du gagnant.
        byte[] archive = aar(java.util.Map.of(
                "classes.jar", jarVide(),
                "AndroidManifest.xml", "<manifest package=\"demo.lib\"/>"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "res/values/ids.xml", "<resources/>"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        Path aarFichier = temporaire.resolve("demo.aar");
        Files.write(aarFichier, archive);
        Path cible = temporaire.resolve("demo-exploded");

        java.util.concurrent.ExecutorService fils =
                java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            List<java.util.concurrent.Future<AarExplodé>> résultats = new java.util.ArrayList<>();
            for (int i = 0; i < 16; i++) {
                résultats.add(fils.submit(() -> AarExplodé.exploser(aarFichier, cible)));
            }
            for (java.util.concurrent.Future<AarExplodé> résultat : résultats) {
                AarExplodé explosé = résultat.get(30, java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(cible.resolve("classes.jar"), explosé.classesJar(),
                        "chaque explosion concurrente sert le même classes.jar final");
                assertEquals(Optional.of("demo.lib"), explosé.packageDuManifeste());
            }
        } finally {
            fils.shutdownNow();
        }
        // Le répertoire final est complet et AUCUN provisoire ne survit à la course.
        assertTrue(Files.isRegularFile(cible.resolve(".forge-exploded")),
                "le marqueur d'explosion complète est posé");
        assertTrue(Files.isRegularFile(cible.resolve("AndroidManifest.xml")));
        assertTrue(Files.isDirectory(cible.resolve("res/values")));
        try (var parcours = Files.list(temporaire)) {
            assertTrue(parcours.filter(p -> p.getFileName().toString()
                                    .startsWith("demo-exploded.part-"))
                            .findAny().isEmpty(),
                    "aucun provisoire .part- ne doit survivre à la course");
        }
    }

    @Test
    void gardeZipSlip() throws Exception {
        Path aar = temporaire.resolve("malveillant-1.0.aar");
        Files.write(aar, aar(java.util.Map.of(
                "classes.jar", jarVide(),
                "../évasion.txt", "hors de la cible".getBytes())));
        IOException refusée = assertThrows(IOException.class,
                () -> AarExplodé.exploser(aar, temporaire.resolve("cible")),
                "une entrée qui sort du répertoire d'explosion est rejetée nommément");
        assertTrue(refusée.getMessage().contains("évasion"),
                "le message nomme l'entrée fautive : " + refusée.getMessage());
        assertTrue(!Files.exists(temporaire.resolve("évasion.txt")),
                "rien n'est écrit hors de la cible");
    }
}
