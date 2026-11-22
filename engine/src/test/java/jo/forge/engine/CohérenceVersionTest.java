package jo.forge.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Constat B-7 : la version vivait en huit endroits recopiés à la main — l'oubli de
 * {@code EngineVersion.CURRENT} a déjà rougi la CI (v0.2.11 : le test cherchait
 * engine-0.2.10.jar sur un classpath 0.2.11). La source unique est désormais
 * {@code gradle.properties} ({@code forgeVersion}) ; ce test fait échouer le build
 * si la constante Java n'est pas bumpée en même temps que la version du projet.
 */
class CohérenceVersionTest {

    /**
     * La constante du moteur doit être exactement la version du projet, injectée
     * par la tâche {@code test} du module engine.
     */
    @Test
    void engineVersionEstLaVersionDuProjet() {
        String attendue = System.getProperty("forge.version.attendue");
        assumeTrue(attendue != null && !attendue.isBlank(),
                "hors Gradle (IDE sans la propriété injectée) : rien à vérifier");
        assertEquals(attendue, EngineVersion.CURRENT,
                "EngineVersion.CURRENT (« " + EngineVersion.CURRENT
                        + " ») n'est pas la version du projet (« " + attendue
                        + " ») — la constante et gradle.properties se bumpent "
                        + "ENSEMBLE (constat B-7)");
    }
}
