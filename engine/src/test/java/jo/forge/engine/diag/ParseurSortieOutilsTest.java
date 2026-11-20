package jo.forge.engine.diag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests du parseur générique de sortie d'outils : forme GNU localisée, avertissements,
 * lignes non localisées et bruit.
 */
class ParseurSortieOutilsTest {

    @Test
    void analyseFormeGnuLocalisée() {
        Diagnostic diagnostic = ParseurSortieOutils.analyserLigne(
                "src/main/java/Demo.java:12:5: error: cannot find symbol", "javac");
        assertEquals(jo.forge.api.BuildEvent.Severity.ERROR, diagnostic.severity());
        assertEquals("src/main/java/Demo.java", diagnostic.file());
        assertEquals(12, diagnostic.line());
        assertEquals(5, diagnostic.column());
        assertEquals("javac", diagnostic.tool());
    }

    @Test
    void analyseAvertissementEtPlage() {
        Diagnostic diagnostic = ParseurSortieOutils.analyserLigne(
                "src/F.kt:3:14-3:22: warning: name shadows a parameter", "kotlinc");
        assertEquals(jo.forge.api.BuildEvent.Severity.WARNING, diagnostic.severity());
        assertEquals(3, diagnostic.line());
    }

    @Test
    void analyseErreurFrançaise() {
        Diagnostic diagnostic = ParseurSortieOutils.analyserLigne(
                "outil: erreur: entrée invalide", "outil");
        assertEquals(jo.forge.api.BuildEvent.Severity.ERROR, diagnostic.severity());
    }

    @Test
    void bruitIgnoré() {
        assertNull(ParseurSortieOutils.analyserLigne("Note: building…", "javac"));
        assertNull(ParseurSortieOutils.analyserLigne("", "javac"));
        assertNull(ParseurSortieOutils.analyserLigne(null, "javac"));
    }

    @Test
    void analyseUneSortieComplète() {
        List<Diagnostic> diagnostics = ParseurSortieOutils.analyser(List.of(
                "Compiling 3 files",
                "src/A.java:1:1: error: package absent does not exist",
                "src/B.java:4: warning: unused variable",
                "build finished with 1 error"), "javac");
        assertEquals(3, diagnostics.size(), "bruit ignoré : localisée + avertissement"
                + " + la ligne de synthèse « 1 error » (diagnostic global)");
        assertTrue(diagnostics.stream().anyMatch(d -> d.line() == 1
                && d.severity() == jo.forge.api.BuildEvent.Severity.ERROR));
    }
}
