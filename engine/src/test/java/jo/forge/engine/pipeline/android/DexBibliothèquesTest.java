package jo.forge.engine.pipeline.android;

import jo.forge.engine.tasks.CancellationFlag;
import jo.forge.engine.tasks.TaskContext;
import jo.forge.engine.tasks.TaskLogger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests du dexage PAR BIBLIOTHÈQUE (décision D-16) : seaux à hash de contenu scellés
 * par sentinelle, reprise après mort brutale (un seau partiel est RE-dexé, les autres
 * repris tels quels), fusion en {@code classes*.dex} vérifiée (en-tête dex), et
 * réutilisation intégrale au deuxième passage (plus AUCUN re-dexage). Depuis la
 * D-19 : la VOIE FINALE (minApi ≥ 26) assemble par COPIE, module d'abord, et se
 * vérifie sur les classes DÉFINIES.
 *
 * <p>Le contexte du constat : la mort par signal 9 de la v0.2.21 au dexage monolithique
 * d'une fermeture Compose — chaque seau terminé doit SURVIVRE à une mort brutale, le
 * build suivant ne re-dexe que ce qui manque ; puis le constat v0.2.22 : la FUSION
 * restait le goulot (104 s) et la fenêtre de tir du tueur de processus.</p>
 */
class DexBibliothèquesTest {

    @TempDir
    Path tmp;

    /** Contexte minimal (journal muet, annulation jamais posée). */
    private static final class Contexte implements TaskContext {
        @Override
        public Path projectRoot() {
            return null;
        }

        @Override
        public TaskLogger logger() {
            return new TaskLogger() {
                @Override
                public void debug(String message) {
                }

                @Override
                public void info(String message) {
                }

                @Override
                public void warn(String message) {
                }

                @Override
                public void error(String message) {
                }
            };
        }

        @Override
        public CancellationFlag cancellation() {
            return new CancellationFlag();
        }
    }

    /** Compile des sources en .class puis les emballe en jar déterministe. */
    private Path jarDe(String nom, Map<String, String> sources) throws IOException {
        Path classes = tmp.resolve("classes-" + nom);
        Files.createDirectories(classes);
        List<String> arguments = new ArrayList<>(List.of("-d", classes.toString()));
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path fichier = tmp.resolve(source.getKey());
            Files.createDirectories(fichier.getParent());
            Files.writeString(fichier, source.getValue());
            arguments.add(fichier.toString());
        }
        javax.tools.JavaCompiler compilateur =
                javax.tools.ToolProvider.getSystemJavaCompiler();
        int code = compilateur.run(null, new ByteArrayOutputStream(),
                new ByteArrayOutputStream(), arguments.toArray(new String[0]));
        assertEquals(0, code, "javac doit réussir : " + sources.keySet());
        Path jar = tmp.resolve(nom + ".jar");
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(
                Files.newOutputStream(jar))) {
            List<Path> fichiers = new ArrayList<>();
            try (var parcours = Files.walk(classes)) {
                parcours.filter(Files::isRegularFile).forEach(fichiers::add);
            }
            fichiers.sort(Path::compareTo);
            for (Path fichier : fichiers) {
                java.util.zip.ZipEntry entrée = new java.util.zip.ZipEntry(
                        classes.relativize(fichier).toString().replace('\\', '/'));
                entrée.setTime(315532800000L);
                zip.putNextEntry(entrée);
                Files.copy(fichier, zip);
                zip.closeEntry();
            }
        }
        return jar;
    }

    /** Les .dex par classe d'un seau (parcours récursif). */
    private static List<Path> dexDuSeau(Path seau) throws IOException {
        List<Path> fichiers = new ArrayList<>();
        try (var parcours = Files.walk(seau)) {
            parcours.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".dex"))
                    .forEach(fichiers::add);
        }
        return fichiers;
    }

    @Test
    void seauxReprisAprèsMortBrutaleEtFusionValide() throws IOException {
        Path androidJar = jo.forge.engine.testutil.OutilsAndroidDeTest.androidJar();
        Assumptions.assumeTrue(androidJar != null,
                "android.jar requis (la CI l'installe)");
        Contexte contexte = new Contexte();

        Path jarA = jarDe("bibli-a", Map.of(
                "A.java", "public class A { public static int un() { return 1; } }"));
        Path jarB = jarDe("bibli-b", Map.of(
                "B.java", "public class B { public static int deux() { return 2; } }",
                "C.java", "public class C {}"));

        // minApi 21 : voie INTERMÉDIAIRE de D-16 (sous 26, le désucrage exige la
        // fusion par lots — la voie finale D-19 a ses tests dédiés plus bas).
        DexBibliothèques dexeur = new DexBibliothèques(tmp.resolve("cache"), 21);
        Path seauA = dexeur.seauDe(jarA, androidJar, contexte);
        Path seauB = dexeur.seauDe(jarB, androidJar, contexte);
        assertEquals(1, dexDuSeau(seauA).size(), "une classe dans A");
        assertEquals(2, dexDuSeau(seauB).size(), "deux classes dans B");
        assertTrue(Files.isRegularFile(seauA.resolve(".seau-complet")),
                "seau A scellé");
        assertTrue(Files.isRegularFile(seauB.resolve(".seau-complet")),
                "seau B scellé");

        // MORT BRUTALE simulée sur B : le seau partiel (sentinelle retirée, un
        // .dex amputé) doit être RE-dexé intégralement ; A reste tel quel.
        long horodatageA = dexDuSeau(seauA).get(0).toFile().lastModified();
        Files.deleteIfExists(seauB.resolve(".seau-complet"));
        Files.deleteIfExists(dexDuSeau(seauB).get(0));
        Path seauB2 = dexeur.seauDe(jarB, androidJar, contexte);
        assertEquals(seauB, seauB2, "même clé de contenu, même seau");
        assertEquals(2, dexDuSeau(seauB2).size(), "B entièrement re-dexé");
        assertTrue(Files.isRegularFile(seauB2.resolve(".seau-complet")));
        assertEquals(horodatageA, dexDuSeau(seauA).get(0).toFile().lastModified(),
                "A n'a pas été re-dexé — le seau survit à la mort brutale");

        // Fusion : un classes.dex d'en-tête valide (magic « dex\n »).
        Path sortie = tmp.resolve("dex");
        dexeur.fusionner(List.of(seauA, seauB2), sortie, androidJar, contexte);
        Path classesDex = sortie.resolve("classes.dex");
        assertTrue(Files.isRegularFile(classesDex), "classes.dex produit");
        byte[] enTête = new byte[4];
        try (var entrée = Files.newInputStream(classesDex)) {
            assertEquals(4, entrée.read(enTête));
        }
        assertEquals('d', enTête[0], "magic dex");
        assertEquals('e', enTête[1]);
        assertEquals('x', enTête[2]);
        assertEquals('\n', enTête[3]);

        // Deuxième passage : les seaux sont repris SANS re-dexage (l'empreinte du
        // contenu n'a pas changé) et la fusion re-produit le dex.
        Path seauA3 = dexeur.seauDe(jarA, androidJar, contexte);
        assertEquals(seauA, seauA3);
        assertEquals(horodatageA, dexDuSeau(seauA3).get(0).toFile().lastModified(),
                "reprise immédiate — zéro re-dexage au deuxième passage");
    }

    @Test
    void fusionMultidexParLotsProduitPlusieursClassesDex() throws IOException {
        Path androidJar = jo.forge.engine.testutil.OutilsAndroidDeTest.androidJar();
        Assumptions.assumeTrue(androidJar != null,
                "android.jar requis (la CI l'installe)");

        // Deux jars d'un paquet chacun : la fusion par lots garde le multidex
        // natif (minSdk 21+) — ici un seul lot (peu de classes), un classes.dex.
        Path jarA = jarDe("multi-a", Map.of(
                "A.java", "package p.a; public class A {}"));
        Path jarB = jarDe("multi-b", Map.of(
                "B.java", "package p.b; public class B {}"));
        Contexte contexte = new Contexte();
        DexBibliothèques dexeur = new DexBibliothèques(tmp.resolve("cache"), 21);
        List<Path> seaux = List.of(dexeur.seauDe(jarA, androidJar, contexte),
                dexeur.seauDe(jarB, androidJar, contexte));
        Path sortie = tmp.resolve("dex");
        dexeur.fusionner(seaux, sortie, androidJar, contexte);
        try (DirectoryStream<Path> parcours =
                     Files.newDirectoryStream(sortie, "classes*.dex")) {
            List<Path> produits = new ArrayList<>();
            parcours.forEach(produits::add);
            assertFalse(produits.isEmpty(), "au moins un classes*.dex");
        }
        // Sous minSdk 21 : un SEUL lot (multidex légendaire interdit) — le même
        // seaux doit fusionner en un classes.dex unique.
        DexBibliothèques dexeurBas = new DexBibliothèques(
                tmp.resolve("cache-bas"), 19);
        List<Path> seauxBas = List.of(dexeurBas.seauDe(jarA, androidJar, contexte),
                dexeurBas.seauDe(jarB, androidJar, contexte));
        Path sortieBas = tmp.resolve("dex-bas");
        dexeurBas.fusionner(seauxBas, sortieBas, androidJar, contexte);
        try (DirectoryStream<Path> parcours =
                     Files.newDirectoryStream(sortieBas, "classes*.dex")) {
            List<Path> produits = new ArrayList<>();
            parcours.forEach(produits::add);
            assertEquals(1, produits.size(), "un seul classes.dex sous minSdk 21");
        }
    }

    @Test
    void voieFinaleAssembleParCopieAvecLeModuleDabord() throws IOException {
        Path androidJar = jo.forge.engine.testutil.OutilsAndroidDeTest.androidJar();
        Assumptions.assumeTrue(androidJar != null,
                "android.jar requis (la CI l'installe)");
        Contexte contexte = new Contexte();

        Path jarModule = jarDe("module-app", Map.of(
                "Main.java", "package p.app; public class Main {}"));
        Path jarLib = jarDe("bibli-externe", Map.of(
                "Outil.java", "package p.lib; public class Outil {}",
                "Aide.java", "package p.lib; public class Aide {}"));

        // minApi 28 : voie FINALE (D-19) — seaux en dex INDEXÉS à la racine, un
        // classes.dex par bibliothèque (d8 découpe seul au-delà de 64 k réf.),
        // scellés et vérifiés sur les classes DÉFINIES.
        DexBibliothèques dexeur = new DexBibliothèques(tmp.resolve("cache-final"), 28);
        Path seauModule = dexeur.seauDe(jarModule, androidJar, contexte);
        Path seauLib = dexeur.seauDe(jarLib, androidJar, contexte);
        assertTrue(Files.isRegularFile(seauModule.resolve("classes.dex")),
                "seau final : classes.dex indexé à la racine");
        assertTrue(Files.isRegularFile(seauLib.resolve("classes.dex")),
                "les deux classes de la bibliothèque tiennent dans UN dex final");
        assertTrue(Files.isRegularFile(seauModule.resolve(".seau-complet")));
        assertTrue(DexDéfinies.lire(seauLib.resolve("classes.dex"))
                        .containsAll(List.of("Lp/lib/Outil;", "Lp/lib/Aide;")),
                "les classes DÉFINIES couvrent le jar entier");

        // Assemblage PAR COPIE : le module D'ABORD (classes.dex — ses classes
        // ombragent celles des bibliothèques à l'exécution), la bibliothèque en
        // classes2.dex. Les octets des seaux sont recopiés TELS QUELS.
        Path sortie = tmp.resolve("dex-final");
        dexeur.fusionner(List.of(seauModule, seauLib), sortie, androidJar, contexte);
        Path classesDex = sortie.resolve("classes.dex");
        Path classes2Dex = sortie.resolve("classes2.dex");
        assertTrue(Files.isRegularFile(classesDex), "classes.dex = module");
        assertTrue(Files.isRegularFile(classes2Dex), "classes2.dex = bibliothèque");
        assertEquals(-1L, Files.mismatch(seauModule.resolve("classes.dex"), classesDex),
                "classes.dex = copie EXACTE du seau du module (aucune fusion d8)");
        assertEquals(-1L, Files.mismatch(seauLib.resolve("classes.dex"), classes2Dex),
                "classes2.dex = copie EXACTE du seau de la bibliothèque");
        assertTrue(DexDéfinies.lire(classesDex).contains("Lp/app/Main;"));
        assertTrue(DexDéfinies.lire(classes2Dex).contains("Lp/lib/Outil;"));

        // MORT BRUTALE simulée sur la bibliothèque (sentinelle amputée + dex
        // supprimé) : son seau est RE-dexé, le seau du module est repris TEL QUEL.
        long avant = Files.getLastModifiedTime(
                seauModule.resolve("classes.dex")).toMillis();
        Files.deleteIfExists(seauLib.resolve(".seau-complet"));
        Files.deleteIfExists(seauLib.resolve("classes.dex"));
        Path seauLib2 = dexeur.seauDe(jarLib, androidJar, contexte);
        assertEquals(seauLib, seauLib2, "même clé de contenu, même seau");
        assertTrue(Files.isRegularFile(seauLib2.resolve("classes.dex")),
                "bibliothèque entièrement re-dexée");
        assertEquals(avant, Files.getLastModifiedTime(
                        seauModule.resolve("classes.dex")).toMillis(),
                "le seau du module survit à la mort brutale — zéro re-dexage");

        // Ré-assemblage : reprise immédiate de TOUS les seaux, sorties identiques.
        Path sortie2 = tmp.resolve("dex-final-2");
        dexeur.fusionner(List.of(seauModule, seauLib2), sortie2, androidJar, contexte);
        assertEquals(-1L, Files.mismatch(sortie.resolve("classes.dex"),
                sortie2.resolve("classes.dex")), "assemblage déterministe");
        assertEquals(-1L, Files.mismatch(sortie.resolve("classes2.dex"),
                sortie2.resolve("classes2.dex")), "assemblage déterministe");
    }

    @Test
    void sous26LaFusionIntermédiaireReste() throws IOException {
        Path androidJar = jo.forge.engine.testutil.OutilsAndroidDeTest.androidJar();
        Assumptions.assumeTrue(androidJar != null,
                "android.jar requis (la CI l'installe)");
        Contexte contexte = new Contexte();

        // minApi 21 : la voie de D-16 est INCHANGÉE sous 26 — un .dex PAR CLASSE
        // dans l'arborescence des paquets, fusion d8 par lots à la fin.
        Path jarA = jarDe("inter-a", Map.of(
                "A.java", "package q.a; public class A {}"));
        DexBibliothèques dexeur = new DexBibliothèques(tmp.resolve("cache-inter"), 21);
        Path seau = dexeur.seauDe(jarA, androidJar, contexte);
        assertTrue(Files.isRegularFile(seau.resolve("q/a/A.dex")),
                "mode intermédiaire conservé sous 26 : un .dex par classe");
        assertFalse(Files.isRegularFile(seau.resolve("classes.dex")),
                "pas de dex final sous 26");
        Path sortie = tmp.resolve("dex-inter");
        dexeur.fusionner(List.of(seau), sortie, androidJar, contexte);
        assertTrue(Files.isRegularFile(sortie.resolve("classes.dex")),
                "la fusion par lots produit toujours le classes.dex");
    }

    @Test
    void voieFinaleToléranteAuJarVide() throws IOException {
        Path androidJar = jo.forge.engine.testutil.OutilsAndroidDeTest.androidJar();
        Assumptions.assumeTrue(androidJar != null,
                "android.jar requis (la CI l'installe)");
        Contexte contexte = new Contexte();

        // Zip vide VALIDE (22 octets) — le cas réel d'un AAR sans classes.jar
        // (AarExplodé en produit un) : d8 ne produit RIEN, le seau se scelle
        // sans erreur et l'assemblage le SAUTE sans casser le build.
        Path vide = tmp.resolve("vide.jar");
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(
                Files.newOutputStream(vide))) {
            // Aucune entrée : zip vide valide.
        }
        DexBibliothèques dexeur = new DexBibliothèques(tmp.resolve("cache-vide"), 28);
        Path seauVide = dexeur.seauDe(vide, androidJar, contexte);
        assertTrue(Files.isRegularFile(seauVide.resolve(".seau-complet")),
                "un jar vide se scelle sans erreur (aucune classe attendue)");
        Path jarPlein = jarDe("plein", Map.of(
                "P.java", "package p; public class P {}"));
        Path seauPlein = dexeur.seauDe(jarPlein, androidJar, contexte);
        Path sortie = tmp.resolve("dex-vide");
        dexeur.fusionner(List.of(seauPlein, seauVide), sortie, androidJar, contexte);
        assertTrue(Files.isRegularFile(sortie.resolve("classes.dex")),
                "le seau vide est sauté, le seau plein embarqué");
        try (DirectoryStream<Path> parcours =
                     Files.newDirectoryStream(sortie, "classes*.dex")) {
            List<Path> produits = new ArrayList<>();
            parcours.forEach(produits::add);
            assertEquals(1, produits.size(), "un seul classes.dex : le seau vide n'y est pas");
        }
    }
}
