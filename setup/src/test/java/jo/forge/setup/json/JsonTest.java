package jo.forge.setup.json;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests du parseur/écriteur JSON : aller-retours, rejets stricts.
 */
class JsonTest {

    @Test
    void parseObjetComplet() {
        Object valeur = Json.parse("""
                {"a": 1, "b": "texte", "c": true, "d": null, "e": [1, 2], "f": {"g": -3}}
                """);
        Map<String, Object> attendu = new java.util.HashMap<>();
        attendu.put("a", 1L);
        attendu.put("b", "texte");
        attendu.put("c", true);
        attendu.put("d", null);
        attendu.put("e", List.of(1L, 2L));
        attendu.put("f", Map.of("g", -3L));
        assertEquals(attendu, valeur);
    }

    @Test
    void parseTypesNombres() {
        Object valeur = Json.parse("{\"entier\": 42, \"negatif\": -7, \"decimal\": 1.5}");
        assertEquals(Map.of("entier", 42L, "negatif", -7L, "decimal", 1.5), valeur);
    }

    @Test
    void parseAccentsEtEchappements() {
        Object valeur = Json.parse("{\"nom\": \"échappé \\\"quoted\\\" et \\\\ et é\"}");
        assertEquals("échappé \"quoted\" et \\ et é",
                ((Map<?, ?>) valeur).get("nom"));
    }

    @Test
    void writePuisReparsRoundTrip() {
        Map<String, Object> racine = new java.util.LinkedHashMap<>();
        racine.put("liste", List.of("a", "b"));
        racine.put("nombre", 12L);
        racine.put("booleen", false);
        racine.put("vide", Map.of());
        String json = Json.write(racine);
        Object relue = Json.parse(json);
        assertEquals(racine, relue);
    }

    @Test
    void contenuResiduelRejete() {
        assertThrows(JsonException.class, () -> Json.parse("{\"a\": 1} résidu"));
    }

    @Test
    void chaineNonFermeeRejetee() {
        assertThrows(JsonException.class, () -> Json.parse("{\"a\": \"oups}"));
    }

    @Test
    void echappementInconnuRejete() {
        assertThrows(JsonException.class, () -> Json.parse("{\"a\": \"\\x\"}"));
    }

    @Test
    void profondeurExcessiveRejetee() {
        String profond = "[".repeat(100) + "]".repeat(100);
        assertThrows(JsonException.class, () -> Json.parse(profond));
    }

    @Test
    void virguleTraineeRejetee() {
        assertThrows(JsonException.class, () -> Json.parse("[1, 2,]"));
    }
}
