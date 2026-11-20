package jo.forge.engine.pipeline.java;

import jo.forge.engine.diag.Diagnostic;
import jo.forge.engine.diag.DiagnosticSink;
import jo.forge.engine.testutil.OutilsAndroidDeTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests du compilateur ECJ embarqué : succès, diagnostics structurés localisés
 * (fichier, ligne, colonne, code machine), cas vide — et l'aiguillage
 * plate-forme (correctif v0.2.7) : {@code android.jar} réel, chemin sans image
 * au niveau 17, frontal avec amorçage au niveau 8, échec actionnable sur ART
 * sans plate-forme.
 */
class EcjCompilerTest {

    @TempDir
    Path temporaire;

    @Test
    void compileUneClasseSimple() throws IOException {
        Path source = Files.writeString(temporaire.resolve("Demo.java"), """
                public class Demo {
                    public static int doubler(int v) { return v * 2; }
                }
                """);
        Path sortie = temporaire.resolve("classes");
        EcjCompiler.CompileResult resultat = EcjCompiler.compile(List.of(source),
                List.of(), sortie, 17, DiagnosticSink.NOOP);

        assertTrue(resultat.success(), "la compilation doit réussir");
        assertTrue(Files.isRegularFile(sortie.resolve("Demo.class")),
                "le .class doit être écrit");
    }

    @Test
    void rapporteUneErreurLocalisée() throws IOException {
        Path source = Files.writeString(temporaire.resolve("Fautif.java"), """
                public class Fautif {
                    public int calcule() {
                        return variableInconnue;
                    }
                }
                """);
        List<Diagnostic> diagnostiques = new ArrayList<>();
        EcjCompiler.CompileResult resultat = EcjCompiler.compile(List.of(source),
                List.of(), temporaire.resolve("classes"), 17, diagnostiques::add);

        assertFalse(resultat.success(), "la compilation doit échouer");
        assertTrue(diagnostiques.stream().anyMatch(d ->
                        d.severity() == jo.forge.api.BuildEvent.Severity.ERROR),
                "au moins une erreur attendue");
        Diagnostic erreur = diagnostiques.stream()
                .filter(d -> d.severity() == jo.forge.api.BuildEvent.Severity.ERROR)
                .findFirst().orElseThrow();
        assertEquals("Fautif.java", Path.of(erreur.file()).getFileName().toString(),
                "le fichier fautif doit être nommé");
        assertEquals(3, erreur.line(), "l'erreur est ligne 3");
        assertTrue(erreur.column() > 0, "la colonne doit être calculée (> 0)");
        assertTrue(erreur.code() != null && erreur.code().startsWith("ecj:"),
                "le code machine doit être préfixé ecj: : " + erreur.code());
        assertEquals(EcjCompiler.TOOL, erreur.tool());
    }

    @Test
    void respecteLeNiveauDeRelease() throws IOException {
        // ``String.repeat`` n'existe qu'à partir de Java 11 : compiler en release 10
        // doit échouer, en release 17 réussir.
        Path source = Files.writeString(temporaire.resolve("Repete.java"), """
                public class Repete {
                    public String repete(String s) { return s.repeat(2); }
                }
                """);
        assertFalse(EcjCompiler.compile(List.of(source), List.of(),
                temporaire.resolve("v10"), 10, DiagnosticSink.NOOP).success(),
                "release 10 doit refuser String.repeat");
        assertTrue(EcjCompiler.compile(List.of(source), List.of(),
                temporaire.resolve("v17"), 17, DiagnosticSink.NOOP).success(),
                "release 17 doit accepter String.repeat");
    }

    @Test
    void aucuneSourceRéussitSansEffet() throws IOException {
        EcjCompiler.CompileResult resultat = EcjCompiler.compile(List.of(), List.of(),
                temporaire.resolve("classes"), 17, DiagnosticSink.NOOP);
        assertTrue(resultat.success());
    }

    @Test
    void versionDisponible() {
        assertFalse(EcjCompiler.compilerVersion().isBlank(),
                "la version du compilateur doit être connue (pour les empreintes)");
    }

    @Test
    void détecteArtParNomDeVmEtÉditeur() {
        assertTrue(EcjCompiler.surArt("Dalvik", "The Android Project"),
                "ART se présente encore comme Dalvik");
        assertTrue(EcjCompiler.surArt("OpenJDK 64-Bit Server VM", "The Android Project"),
                "l'éditeur suffit");
        assertFalse(EcjCompiler.surArt("OpenJDK 64-Bit Server VM", "Temurin"),
                "une JVM de bureau n'est pas ART");
        assertFalse(EcjCompiler.surArt(null, null),
                "un environnement muet n'est pas déclaré ART");
    }

    /**
     * La compilation d'un module sur ART avec android.jar au niveau 17 passe
     * par le chemin sans image : concaténation et lambda (invokedynamic vers
     * des fabriques absentes d'android.jar, servies par les tranches
     * embarquées) comme String.join et var — le exact du projet exemple de
     * l'appareil.
     */
    @Test
    void plateformeAndroidCompileJava17SansImage() throws IOException {
        Path androidJar = OutilsAndroidDeTest.androidJar();
        assumeTrue(androidJar != null,
                "android.jar réel indisponible — test ignoré");
        Path source = Files.writeString(temporaire.resolve("Salutations.java"), """
                package demo.core;

                import java.util.function.IntSupplier;

                public final class Salutations {
                    private Salutations() { }

                    public static String saluer(String nom) {
                        return "Salut, " + nom + " !";
                    }

                    public static String joindre(String sep, String... mots) {
                        return String.join(sep, mots);
                    }

                    public static int taille(String s) {
                        IntSupplier taille = () -> s.length();
                        var texte = "total: " + taille.getAsInt();
                        return texte.length();
                    }
                }
                """);
        Path sortie = temporaire.resolve("classes");
        EcjCompiler.CompileResult resultat = EcjCompiler.compile(List.of(source),
                List.of(), List.of(androidJar), sortie, 17, DiagnosticSink.NOOP);

        assertTrue(resultat.success(), "la compilation niveau 17 contre android.jar "
                + "doit réussir : " + resultat.consoleLines());
        assertTrue(resultat.errorCount() == 0, "aucune erreur attendue");
        assertTrue(Files.isRegularFile(sortie.resolve("demo/core/Salutations.class")),
                "le .class doit être écrit à son paquet");
    }

    /**
     * Sur ART sans plate-forme, l'échec est immédiat et actionnable — pas onze
     * « cannot be resolved » qui noient la cause (le constat appareil v0.2.6).
     */
    @Test
    void sansPlateformeSurArtÉchoueActionnable() throws IOException {
        Path source = Files.writeString(temporaire.resolve("Demo.java"), """
                public class Demo { }
                """);
        List<Diagnostic> diagnostiques = new ArrayList<>();
        EcjCompiler.CompileResult resultat = EcjCompiler.compile(List.of(source),
                List.of(), List.of(), temporaire.resolve("classes"), 17,
                diagnostiques::add, true);

        assertFalse(resultat.success(), "sans plate-forme sur ART, échec");
        assertEquals(1, resultat.errorCount(), "un seul diagnostic, qui nomme la cause");
        assertTrue(diagnostiques.get(0).message().contains("platform"),
                "le message doit orienter vers le composant platform : "
                        + diagnostiques.get(0).message());
    }

    /**
     * Au niveau 8, le frontal par lots accepte android.jar sur
     * {@code -bootclasspath} : ce chemin reste le sien (jar dexable partout,
     * aucune fabrique requise — la concaténation passe par StringBuilder).
     */
    @Test
    void plateformeAndroidNiveau8PasseParLeFrontal() throws IOException {
        Path androidJar = OutilsAndroidDeTest.androidJar();
        assumeTrue(androidJar != null,
                "android.jar réel indisponible — test ignoré");
        Path source = Files.writeString(temporaire.resolve("Textes.java"), """
                package demo.core;

                public final class Textes {
                    private Textes() { }

                    public static String joindre(String sep, String... mots) {
                        return String.join(sep, mots);
                    }

                    public static String crier(String s) {
                        return s.toUpperCase() + " !";
                    }
                }
                """);
        EcjCompiler.CompileResult resultat = EcjCompiler.compile(List.of(source),
                List.of(), List.of(androidJar), temporaire.resolve("classes"), 8,
                DiagnosticSink.NOOP);

        assertTrue(resultat.success(), "la compilation niveau 8 contre android.jar "
                + "doit réussir : " + resultat.consoleLines());
    }
}
