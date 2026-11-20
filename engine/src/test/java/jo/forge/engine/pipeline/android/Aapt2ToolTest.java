package jo.forge.engine.pipeline.android;

import jo.forge.engine.diag.Diagnostic;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests unitaires du wrapper aapt2 : analyse des lignes de diagnostic (localisées et
 * bruit) et messages de l'autotest.
 */
class Aapt2ToolTest {

    @Test
    void analyseLigneLocalisée() {
        Diagnostic diagnostic = Aapt2Tool.parserLigne(
                "res/layout/main.xml:3:15-3:23: error: attribute android:toto not found.");
        assertEquals(jo.forge.api.BuildEvent.Severity.ERROR, diagnostic.severity());
        assertEquals("res/layout/main.xml", diagnostic.file());
        assertEquals(3, diagnostic.line());
        assertEquals(15, diagnostic.column());
        assertEquals("aapt2", diagnostic.code());
        assertTrue(diagnostic.message().contains("toto"));
        assertEquals("aapt2", diagnostic.tool());
    }

    @Test
    void analyseAvertissementSansColonne() {
        Diagnostic diagnostic = Aapt2Tool.parserLigne(
                "res/values/strings.xml:7: warning: resource name 'a-b' is invalid.");
        assertEquals(jo.forge.api.BuildEvent.Severity.WARNING, diagnostic.severity());
        assertEquals(7, diagnostic.line());
        assertEquals(0, diagnostic.column());
    }

    @Test
    void bruitEtVideIgnorés() {
        assertNull(Aapt2Tool.parserLigne(""));
        assertNull(Aapt2Tool.parserLigne("   "));
        assertNull(Aapt2Tool.parserLigne("Note: some input files use unchecked"));
    }

    @Test
    void erreurNonLocaliséeDevientDiagnosticGlobal() {
        Diagnostic diagnostic = Aapt2Tool.parserLigne(
                "error: failed parsing manifest.");
        assertEquals(jo.forge.api.BuildEvent.Severity.ERROR, diagnostic.severity());
        assertEquals("", diagnostic.file());
        assertTrue(diagnostic.message().contains("manifest"));
    }

    @Test
    void autotestBinaireAbsent() {
        Aapt2Tool outil = new Aapt2Tool(java.nio.file.Path.of(
                "/chemin/inexistant/aapt2"));
        List<String> lignes = outil.autotest();
        assertEquals(1, lignes.size());
        List<String> tampon = new ArrayList<>();
        assertTrue(lignes.get(0).contains("[diagnostic]"));
    }

    @Test
    void binaireSansBitExécutableEstRéparé() throws Exception {
        // Le composant installé reçoit +x de l'installateur, mais un binaire
        // copié à la main ou pointé par forge.aapt2 peut perdre le droit : la
        // première exécution doit le poser plutôt qu'échouer d'un Permission
        // denied nu. Vrai binaire (Maven) : la réparation se prouve en exécutant.
        java.nio.file.Path binaire = jo.forge.engine.testutil.OutilsAndroidDeTest
                .extraireAapt2();
        org.junit.jupiter.api.Assumptions.assumeTrue(binaire != null,
                "binaire aapt2 de test absent du classpath");
        assertTrue(binaire.toFile().setExecutable(false, false),
                "le test sait retirer le droit d'exécution");
        Aapt2Tool outil = new Aapt2Tool(binaire);
        List<String> lignes = outil.autotest();
        assertTrue(java.nio.file.Files.isExecutable(binaire),
                "le droit est réparé par la première exécution");
        assertTrue(lignes.get(0).contains("aapt2 version répond"),
                "le binaire s'exécute après réparation : " + lignes);
    }
}
