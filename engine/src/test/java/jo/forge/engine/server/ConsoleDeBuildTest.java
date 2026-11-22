package jo.forge.engine.server;

import jo.forge.api.BuildEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests de la console du moteur (D-21) : le FORMAT des lignes composées et le TRI
 * par niveau — les deux responsabilités qui déménagent de la coquille vers le moteur
 * (parité Gradle : le moteur dicte ses sorties, la coquille affiche).
 */
class ConsoleDeBuildTest {

    private static BuildEvent.TaskStateEvent tâche(BuildEvent.TaskState état) {
        return new BuildEvent.TaskStateEvent("b1", 1, "app:compileJava", état, 120);
    }

    @Test
    void lignesDeTâchesCommeLesConsolesClassiques() {
        ConsoleDeBuild console = new ConsoleDeBuild(null);
        assertEquals("> Tâche app:compileJava", console.ligneTâche(tâche(BuildEvent.TaskState.SUCCEEDED)),
                "une tâche exécutée n'a pas de badge : l'absence de badge dit le travail fait");
        assertEquals("> Tâche app:compileJava — À JOUR",
                console.ligneTâche(tâche(BuildEvent.TaskState.UP_TO_DATE)));
        assertEquals("> Tâche app:compileJava — DU CACHE",
                console.ligneTâche(tâche(BuildEvent.TaskState.FROM_CACHE)));
        assertEquals("> Tâche app:compileJava — SAUTÉE",
                console.ligneTâche(tâche(BuildEvent.TaskState.SKIPPED)));
        assertEquals("> Tâche app:compileJava — ÉCHOUÉE",
                console.ligneTâche(tâche(BuildEvent.TaskState.FAILED)));
        assertNull(console.ligneTâche(tâche(BuildEvent.TaskState.STARTED)),
                "rien au démarrage, comme les consoles classiques");
    }

    @Test
    void verdictRéussiDécomptéEtDuréeLisible() {
        ConsoleDeBuild console = new ConsoleDeBuild(null);
        assertEquals("BUILD RÉUSSI en 12 s — 4 tâches : 3 exécutées, 1 à jour",
                console.verdict(new BuildEvent.BuildFinished("b1", 1, true, null,
                        12_400, "4 tâches : 3 exécutées, 1 à jour")));
        assertEquals("BUILD RÉUSSI en 1 min 05 s — ok",
                console.verdict(new BuildEvent.BuildFinished("b1", 1, true, null,
                        65_000, "ok")));
    }

    @Test
    void verdictÉchouéEtAnnulé() {
        ConsoleDeBuild console = new ConsoleDeBuild(null);
        assertEquals("BUILD ÉCHOUÉ à la tâche app:dex — 2 tâches : 1 exécutée",
                console.verdict(new BuildEvent.BuildFinished("b1", 1, false, "app:dex",
                        3_000, "2 tâches : 1 exécutée")));
        assertEquals("BUILD ANNULÉ — build annulé — 2 tâches sautées",
                console.verdict(new BuildEvent.BuildFinished("b1", 1, false, null,
                        3_000, "build annulé — 2 tâches sautées")));
        assertEquals("BUILD ÉCHOUÉ — modèle invalide",
                console.verdict(new BuildEvent.BuildFinished("b1", 1, false, null,
                        300, "modèle invalide")));
    }

    @Test
    void ligneDiagnosticEtNiveauCorrespondant() {
        ConsoleDeBuild console = new ConsoleDeBuild(null);
        assertEquals("⚠ src/Demo.java:7 — symbole introuvable",
                console.ligneDiagnostic(new BuildEvent.DiagnosticEvent("b1", 1,
                        BuildEvent.Severity.ERROR, "src/Demo.java", 7, 12,
                        "javac:err", "symbole introuvable", "java-compiler")));
        assertEquals("⚠ ? — diagnostic sans fichier",
                console.ligneDiagnostic(new BuildEvent.DiagnosticEvent("b1", 1,
                        BuildEvent.Severity.WARNING, null, 0, 0, null,
                        "diagnostic sans fichier", "outil")));

        assertEquals(BuildEvent.LogEvent.LogLevel.ERROR,
                ConsoleDeBuild.niveauDiagnostic(BuildEvent.Severity.ERROR));
        assertEquals(BuildEvent.LogEvent.LogLevel.WARN,
                ConsoleDeBuild.niveauDiagnostic(BuildEvent.Severity.WARNING));
        assertEquals(BuildEvent.LogEvent.LogLevel.INFO,
                ConsoleDeBuild.niveauDiagnostic(BuildEvent.Severity.INFO));
    }

    @Test
    void ligneTéléchargementEtTaillesLisibles() {
        ConsoleDeBuild console = new ConsoleDeBuild(null);
        assertEquals("↓ ui-android-1.7.1.aar (512 o)", console.ligneTéléchargement(
                new BuildEvent.DownloadEvent("b1", 1, "ui-android-1.7.1.aar", 512, 512, true)));
        assertEquals("↓ ui-android-1.7.1.aar (64 Kio)", console.ligneTéléchargement(
                new BuildEvent.DownloadEvent("b1", 1, "ui-android-1.7.1.aar",
                        64 * 1024, 64 * 1024, true)));
        assertEquals("↓ compose.aar (4,2 Mio)", console.ligneTéléchargement(
                new BuildEvent.DownloadEvent("b1", 1, "compose.aar",
                        4_400_000, 4_400_000, true)));
    }

    @Test
    void leNiveauDemandéTrieSansJamaisTaireLesErreurs() {
        // Console par défaut (LIFECYCLE) : la vie du build, pas les détails.
        ConsoleDeBuild console = new ConsoleDeBuild(null);
        assertTrue(console.visible(BuildEvent.LogEvent.LogLevel.LIFECYCLE));
        assertTrue(console.visible(BuildEvent.LogEvent.LogLevel.WARN));
        assertTrue(console.visible(BuildEvent.LogEvent.LogLevel.ERROR));
        assertFalse(console.visible(BuildEvent.LogEvent.LogLevel.INFO));
        assertFalse(console.visible(BuildEvent.LogEvent.LogLevel.DEBUG));

        // Console détaillée (+ informations et téléchargements).
        ConsoleDeBuild info = new ConsoleDeBuild(BuildEvent.LogEvent.LogLevel.INFO);
        assertTrue(info.visible(BuildEvent.LogEvent.LogLevel.INFO));
        assertFalse(info.visible(BuildEvent.LogEvent.LogLevel.DEBUG));

        // Console silencieuse (l'équivalent du -q) : avertissements et erreurs
        // seulement — une erreur ne peut JAMAIS être filtrée.
        ConsoleDeBuild silencieuse = new ConsoleDeBuild(BuildEvent.LogEvent.LogLevel.WARN);
        assertFalse(silencieuse.visible(BuildEvent.LogEvent.LogLevel.LIFECYCLE));
        assertTrue(silencieuse.visible(BuildEvent.LogEvent.LogLevel.WARN));
        assertTrue(silencieuse.visible(BuildEvent.LogEvent.LogLevel.ERROR));

        // Console bavarde (l'équivalent du -d) : tout.
        ConsoleDeBuild bavarde = new ConsoleDeBuild(BuildEvent.LogEvent.LogLevel.DEBUG);
        for (BuildEvent.LogEvent.LogLevel niveau : BuildEvent.LogEvent.LogLevel.values()) {
            assertTrue(bavarde.visible(niveau));
        }

        assertEquals(BuildEvent.LogEvent.LogLevel.LIFECYCLE, new ConsoleDeBuild(null).niveau(),
                "null est accepté et vaut le défaut LIFECYCLE");
    }
}
