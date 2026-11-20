package jo.forge.engine.pipeline.java;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests des tranches lambda embarquées : la ressource /lambda-stubs.jar est
 * produite par la construction du moteur (tâches compilerTranchesLambda et
 * tranchesLambdaJar) et portée par le classpath — ici celui des tests, qui
 * inclut les ressources du module principal.
 */
class StubsLambdaTest {

    @Test
    void laRessourcePorteLesTroisClassesAttendues() {
        Map<String, byte[]> classes = StubsLambda.classes();

        assertFalse(classes.isEmpty(),
                "/lambda-stubs.jar doit être sur le classpath (tâche "
                        + "tranchesLambdaJar de engine/build.gradle)");
        assertTrue(classes.containsKey("java/lang/invoke/StringConcatFactory.class"),
                "StringConcatFactory attendue : " + classes.keySet());
        assertTrue(classes.containsKey("java/lang/invoke/LambdaMetafactory.class"),
                "LambdaMetafactory attendue");
        assertTrue(classes.containsKey("java/lang/invoke/StringConcatException.class"),
                "StringConcatException attendue (signatures de la fabrique)");
    }

    @Test
    void lesPaquetsSontDéclarésAvecLeursPréfixes() {
        Set<String> paquets = StubsLambda.paquets();

        assertTrue(paquets.contains("java/lang/invoke"),
                "le paquet des tranches doit être déclaré");
        assertTrue(paquets.contains("java/lang"),
                "ses préfixes aussi (isPackage interroge hiérarchiquement)");
        assertTrue(paquets.contains("java"));
    }

    @Test
    void uneClasseAbsenteNEstPasServie() {
        Map<String, byte[]> classes = StubsLambda.classes();

        assertFalse(classes.containsKey("java/lang/invoke/MethodHandle.class"),
                "MethodHandle vit dans android.jar, pas dans les tranches");
        assertEquals(3, classes.size(),
                "exactement les trois tranches attendues : " + classes.keySet());
    }
}
