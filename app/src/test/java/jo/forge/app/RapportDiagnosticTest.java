package jo.forge.app;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test du rapport de diagnostic : sections complètes, sections vides marquées,
 * troncature de la trace, et lecture des codes de sortie (propre, signal, explicite,
 * inconnu) — c'est ce que l'utilisateur colle dans un ticket quand un build meurt
 * sur appareil.
 */
class RapportDiagnosticTest {

    private static RapportDiagnostic.Contexte contexte(Integer codeSortie) {
        return new RapportDiagnostic.Contexte("0.2.2", 4, "0.2.2", codeSortie,
                "Android 14", 34, "arm64-v8a", 5743, "Appareil de test",
                "/projets/multi-modules-java", "Build échoué à core:compileJava");
    }

    @Test
    void rapportCompletAvecToutesLesSections() {
        String rapport = RapportDiagnostic.composer(contexte(-9),
                List.of("build de /projets/demo", "core:compileJava : démarrée"),
                List.of("java.lang.OutOfMemoryError", "\tat jo.forge.engine…"),
                new RapportDiagnostic.TraceDernière("app-1.json", "{\"succès\":false}"));

        assertTrue(rapport.startsWith("Rapport de diagnostic Forge"));
        assertTrue(rapport.contains("Coquille : 0.2.2 (versionCode 4)"));
        assertTrue(rapport.contains("Moteur : 0.2.2 — code de sortie : -9"));
        assertTrue(rapport.contains("Appareil : Android 14 (SDK 34), arm64-v8a, 5743 Mo"));
        assertTrue(rapport.contains("--- Journal du build ---"));
        assertTrue(rapport.contains("core:compileJava : démarrée"));
        assertTrue(rapport.contains("--- Journal du moteur (stderr) ---"));
        assertTrue(rapport.contains("java.lang.OutOfMemoryError"));
        assertTrue(rapport.contains("--- Dernière trace (app-1.json) ---"));
        assertTrue(rapport.contains("{\"succès\":false}"));
        assertFalse(rapport.contains("(vide)"), "aucune section vide ici");
    }

    @Test
    void sectionsVidesEtAbsenceDeTraceMarquées() {
        String rapport = RapportDiagnostic.composer(contexte(null), List.of(),
                List.of(), null);
        assertTrue(rapport.contains("--- Journal du build ---\n(vide)"));
        assertTrue(rapport.contains("--- Journal du moteur (stderr) ---\n(vide)"));
        assertTrue(rapport.contains("(aucune : le build ne s'est pas terminé"));
        assertTrue(rapport.contains("code de sortie : inconnu"),
                "code null = inconnu, pas un plantage du rapport");
    }

    @Test
    void traceLongueTronquéeÀLaLimite() {
        String contenu = "x".repeat(RapportDiagnostic.LIMITE_TRACE + 5000);
        String rapport = RapportDiagnostic.composer(contexte(0), List.of(), List.of(),
                new RapportDiagnostic.TraceDernière("grosse.json", contenu));
        assertTrue(rapport.contains("… (tronquée à " + RapportDiagnostic.LIMITE_TRACE
                + " caractères)"));
        assertTrue(rapport.length() < contenu.length(),
                "la trace tronquée ne doit pas passer entière");
    }

    @Test
    void descriptionDesCodesDeSortie() {
        assertEquals("0 (arrêt propre)", RapportDiagnostic.décrireCodeSortie(0));
        assertEquals("-9 (tué par le signal 9 — souvent le tueur de mémoire du système)",
                RapportDiagnostic.décrireCodeSortie(-9));
        assertEquals("137 (mort probable par le signal 9)",
                RapportDiagnostic.décrireCodeSortie(137));
        assertEquals("2 (sortie explicite du moteur)",
                RapportDiagnostic.décrireCodeSortie(2));
        assertTrue(RapportDiagnostic.décrireCodeSortie(null).startsWith("inconnu"));
    }
}
