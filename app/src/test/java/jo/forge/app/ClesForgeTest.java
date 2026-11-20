package jo.forge.app;

import org.junit.jupiter.api.Test;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Test de la clé publique de production épinglée : elle doit être une clé ECDSA
 * P-256 bien formée (la vérification d'un manifeste réel signé par le jeton CI se
 * fait dans la chaîne de publication, avec la clé privée).
 */
class ClesForgeTest {

    @Test
    void cléPubliqueBienFormée() throws Exception {
        byte[] octets = Base64.getDecoder().decode(
                ClesForge.CLÉ_PUBLIQUE_MANIFESTE);
        PublicKey clé = KeyFactory.getInstance("EC")
                .generatePublic(new X509EncodedKeySpec(octets));
        assertNotNull(clé);
        assertEquals("EC", clé.getAlgorithm());
        // P-256 : point de 65 octets (préfixe 0x04 + X + Y de 32 octets) dans la
        // clé encodée.
        assertEquals(91, octets.length, "taille X.509 d'une clé P-256 attendue");
    }
}
