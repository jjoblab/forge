package jo.forge.client.fixtures;

/**
 * Programme factice des tests de diagnostic : écrit des lignes connues sur stderr
 * (diagnostics d'écroulement simulés) puis s'arrête avec le code 7 — exactement le
 * comportement d'un moteur qui meurt en cours de route, sans protocole.
 *
 * <p>Il doit rester compilable sans Android ni moteur : seule la JVM standard y
 * apparaît, pour être lancé dans un vrai processus séparé par les tests du module
 * client.</p>
 */
public final class MoteurBavard {

    private MoteurBavard() {
    }

    /**
     * Point d'entrée : trois lignes sur stderr, un délai pour laisser le drainage
     * démarrer, puis sortie volontaire de code 7.
     *
     * @param args inutilisés.
     * @throws Exception si l'attente échoue.
     */
    public static void main(String[] args) throws Exception {
        System.err.println("diagnostic un");
        System.err.println("diagnostic deux");
        System.err.println("diagnostic trois");
        System.err.flush();
        Thread.sleep(200);
        System.exit(7);
    }
}
