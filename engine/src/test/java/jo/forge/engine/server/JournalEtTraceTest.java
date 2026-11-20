package jo.forge.engine.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests du journal borné et de la trace exportable.
 */
class JournalEtTraceTest {

    @TempDir
    Path temporaire;

    @Test
    void journalBornéÉvinceLesAnciennesEntrées() {
        JournalBorné journal = new JournalBorné(3);
        journal.ajouter("un");
        journal.ajouter("deux");
        journal.ajouter("trois");
        journal.ajouter("quatre");
        assertEquals(List.of("deux", "trois", "quatre"), journal.copie(),
                "la plus ancienne sort, la mémoire reste bornée");
        assertEquals(3, journal.taille());
    }

    @Test
    void traceÉcriteAtomiquementEtComplète() throws Exception {
        BuildTrace trace = new BuildTrace("b42", "/projets/demo", "0.2.0",
                List.of(new BuildTrace.Tâche("app:compileJava", "SUCCEEDED", 120),
                        new BuildTrace.Tâche("app:jar", "UP_TO_DATE", 0)),
                Map.of("ERROR", 2L, "WARNING", 1L),
                List.of("INFO build démarré", "ERROR échec partiel"));
        Path écrit = trace.écrire(temporaire.resolve("traces"), 2500, false);

        assertTrue(Files.isRegularFile(écrit), "la trace est écrite");
        assertTrue(écrit.getFileName().toString().equals("b42.json"));
        String contenu = Files.readString(écrit);
        assertTrue(contenu.contains("\"app:compileJava\""));
        assertTrue(contenu.contains("\"SUCCEEDED\""));
        assertTrue(contenu.contains("\"ERROR\""));
        assertTrue(contenu.contains("INFO build démarré"));
        assertTrue(contenu.contains("\"succès\""));
        assertTrue(Files.list(temporaire.resolve("traces")).noneMatch(
                p -> p.getFileName().toString().endsWith(".part")),
                "aucun temporaire oublié");
    }
}
