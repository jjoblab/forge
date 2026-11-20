package jo.forge.engine.pipeline.java;

import jo.forge.engine.diag.Diagnostic;
import jo.forge.engine.diag.DiagnosticSink;
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

/**
 * Tests du compilateur ECJ embarqué : succès, diagnostics structurés localisés
 * (fichier, ligne, colonne, code machine), et cas vide.
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
}
