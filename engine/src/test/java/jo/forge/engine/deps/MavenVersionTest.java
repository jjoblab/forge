package jo.forge.engine.deps;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Suite du comparateur de versions : l'ordre canonique Maven sur les cas connus
 * (qualificatifs, alias, inconnus, nombres longs, séparateurs).
 */
class MavenVersionTest {

    @Test
    void ordreCanoniqueDesQualificatifs() {
        String[] ordre = {
                "1.0-alpha", "1.0-beta", "1.0-milestone", "1.0-rc", "1.0-SNAPSHOT",
                "1.0", "1.0-sp", "1.0-xyz", "1.1"
        };
        for (int i = 1; i < ordre.length; i++) {
            assertTrue(MavenVersion.INSTANCE.compare(ordre[i - 1], ordre[i]) < 0,
                    ordre[i - 1] + " doit précéder " + ordre[i]);
            assertTrue(MavenVersion.INSTANCE.compare(ordre[i], ordre[i - 1]) > 0,
                    "antisymétrie : " + ordre[i] + " doit suivre " + ordre[i - 1]);
        }
    }

    @Test
    void aliasEtÉquivalences() {
        assertEquals(0, MavenVersion.INSTANCE.compare("1.0.0", "1.0"));
        assertEquals(0, MavenVersion.INSTANCE.compare("1.0.ga", "1.0"));
        assertEquals(0, MavenVersion.INSTANCE.compare("1.0.final", "1.0"));
        assertEquals(0, MavenVersion.INSTANCE.compare("1.0.a", "1.0.alpha"));
        assertEquals(0, MavenVersion.INSTANCE.compare("1.0.b", "1.0.beta"));
        assertEquals(0, MavenVersion.INSTANCE.compare("1.0.m", "1.0.milestone"));
        assertEquals(0, MavenVersion.INSTANCE.compare("1.0.cr", "1.0.rc"));
        assertEquals(0, MavenVersion.INSTANCE.compare("1.0-release", "1.0"));
        assertTrue(MavenVersion.INSTANCE.compare("1.0.1", "1.0") > 0);
        assertTrue(MavenVersion.INSTANCE.compare("2.0.0", "1.9.9") > 0);
        assertTrue(MavenVersion.INSTANCE.compare("1.10", "1.9") > 0,
                "1.10 suit 1.9 (comparaison numérique, pas lexicographique)");
    }

    @Test
    void nombreDomineQualificatif() {
        assertTrue(MavenVersion.INSTANCE.compare("1.0", "1.0-rc") > 0);
        assertTrue(MavenVersion.INSTANCE.compare("1.1", "1.0-anything") > 0);
    }

    @Test
    void séparateursEtInconnus() {
        assertTrue(MavenVersion.INSTANCE.compare("1.0.0-1", "1.0.0") > 0,
                "un complément numérique est un numéro : 1.0.0-1 suit 1.0.0");
        assertEquals(0, MavenVersion.INSTANCE.compare("1_0", "1.0"));
        assertTrue(MavenVersion.INSTANCE.compare("1.0-abc", "1.0-abd") < 0,
                "inconnus entre eux : ordre lexicographique");
        assertTrue(MavenVersion.INSTANCE.compare("1.0-dev-1", "1.0-dev-2") < 0);
        assertTrue(MavenVersion.INSTANCE.compare("1.0-1", "1.0-2") < 0);
    }

    @Test
    void plusRécente() {
        assertEquals("2.0",
                MavenVersion.plusRécente(java.util.List.of("1.0", "2.0", "1.5")));
        assertEquals("1.0-rc2", MavenVersion.plusRécente(
                java.util.List.of("1.0-rc1", "1.0-rc2")));
        assertNull(MavenVersion.plusRécente(java.util.List.of()));
    }
}
