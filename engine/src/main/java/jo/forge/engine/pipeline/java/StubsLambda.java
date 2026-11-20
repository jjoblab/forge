package jo.forge.engine.pipeline.java;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Tranches lambda embarquées : les classes {@code java.lang.invoke}
 * (StringConcatFactory, StringConcatException, LambdaMetafactory) qu'
 * {@code android.jar} omet et qu'un compilateur Java 9+ doit pourtant
 * résoudre pour abaisser concaténations et lambdas en {@code invokedynamic}.
 *
 * <p>Le jar est fabriqué par la construction du moteur (tâche
 * {@code tranchesLambdaJar} d'{@code engine/build.gradle}, sources sous
 * {@code src/stubsLambda/java}) et voyage comme entrée
 * {@code /lambda-stubs.jar} du jar du moteur : une entrée non-classe survit au
 * dexage (correctif v0.2.4) et le chargeur d'ART la sert via
 * {@code getResource} — le même mécanisme que les messages d'ECJ. Le
 * compilateur la consulte en dernier recours, après répertoires et jars du
 * classpath : ces classes n'existent nulle part ailleurs.</p>
 */
final class StubsLambda {

    /** Chemin de la ressource embarquée (racine du classpath du moteur). */
    static final String RESSOURCE = "/lambda-stubs.jar";

    /** Classes servies, chargées une fois (péser quelques kilo-octets). */
    private static volatile Map<String, byte[]> classes;

    private StubsLambda() {
    }

    /**
     * Les classes des tranches, chemin interne ({@code java/lang/invoke/…})
     * vers octets du {@code .class}.
     *
     * @return les classes, vide si la ressource est absente (jar moteur bâti
     *         sans la tâche — jamais en production ni en test).
     */
    static Map<String, byte[]> classes() {
        Map<String, byte[]> chargées = classes;
        if (chargées != null) {
            return chargées;
        }
        synchronized (StubsLambda.class) {
            if (classes == null) {
                classes = charger();
            }
            return classes;
        }
    }

    /**
     * Les paquets couverts par les tranches ({@code java/lang/invoke} et ses
     * préfixes) — l'environnement de noms doit les déclarer à {@code isPackage}.
     *
     * @return les paquets, vide si la ressource est absente.
     */
    static Set<String> paquets() {
        Set<String> paquets = new HashSet<>();
        for (String chemin : classes().keySet()) {
            int barre = chemin.lastIndexOf('/');
            if (barre > 0) {
                String paquet = chemin.substring(0, barre);
                paquets.add(paquet);
                int position = paquet.indexOf('/');
                while (position >= 0) {
                    paquets.add(paquet.substring(0, position));
                    position = paquet.indexOf('/', position + 1);
                }
            }
        }
        return paquets;
    }

    /**
     * Lit le jar embarqué depuis le classpath du moteur (pas de fichier
     * intermédiaire : quelques kilo-octets tenus en mémoire).
     */
    private static Map<String, byte[]> charger() {
        Map<String, byte[]> lues = new HashMap<>();
        try (InputStream brut = StubsLambda.class.getResourceAsStream(RESSOURCE)) {
            if (brut == null) {
                return Map.of();
            }
            try (ZipInputStream zip = new ZipInputStream(
                    new ByteArrayInputStream(brut.readAllBytes()))) {
                ZipEntry entrée;
                while ((entrée = zip.getNextEntry()) != null) {
                    if (entrée.isDirectory() || !entrée.getName().endsWith(".class")) {
                        continue;
                    }
                    lues.put(entrée.getName(), zip.readAllBytes());
                }
            }
        } catch (IOException illisible) {
            // Ressource corrompue : on sert les classes lues jusqu'ici (vide
            // au pire) — la compilation signalera les types manquants.
            return Map.copyOf(lues);
        }
        return Map.copyOf(lues);
    }
}
