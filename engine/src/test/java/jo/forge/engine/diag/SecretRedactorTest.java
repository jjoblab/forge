package jo.forge.engine.diag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests de la rédaction de secrets : familles couvertes et messages sains préservés.
 */
class SecretRedactorTest {

    @Test
    void rédigeLesAffectations() {
        String message = "connexion échouée : password=SuperSecret123 serveur inaccessible";
        String rédigé = SecretRedactor.rédiger(message);
        assertTrue(rédigé.contains(SecretRedactor.MARQUE), "le secret est remplacé : "
                + rédigé);
        assertFalse(rédigé.contains("SuperSecret123"));
        assertTrue(rédigé.contains("password"), "l'étiquette reste pour le diagnostic");
    }

    @Test
    void rédigeLesFormesFrançaisesEtColonnes() {
        for (String message : new String[]{
                "jeton: abc123def456 refusé",
                "MOTDEPASSE=hunter2",
                "api-key: 0123456789abcdef",
                "clé secrète = trèstrèslongueclé"}) {
            String rédigé = SecretRedactor.rédiger(message);
            assertFalse(rédigé.matches("(?i).*(hunter2|abc123def456|0123456789abcdef"
                    + "|trèstrèslongueclé).*"),
                    "secret rédigé dans : " + message + " -> " + rédigé);
        }
    }

    @Test
    void rédigeLesJetonsPorteur() {
        String rédigé = SecretRedactor.rédiger(
                "requête rejetée : Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.xyz");
        assertFalse(rédigé.contains("eyJhbGciOiJIUzI1NiJ9"));
        assertTrue(rédigé.contains(SecretRedactor.MARQUE));
    }

    @Test
    void préserveLesMessagesSains() {
        String message = "compilation de 12 fichiers vers build/app/classes";
        assertEquals(message, SecretRedactor.rédiger(message),
                "un message sans secret passe inchangé");
    }

    @Test
    void famillesDocumentées() {
        assertEquals(3, SecretRedactor.familles().size());
    }
}
