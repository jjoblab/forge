package jo.forge.api;

/**
 * Poignée de main coquille → moteur, en réponse au {@link Hello}.
 *
 * @param shellVersion   version de la coquille (à des fins de journal et diagnostic).
 * @param protocolVersion version de protocole choisie par la coquille ; le moteur la
 *                        rejettera par une erreur de protocole si elle sort de sa plage.
 */
public record ClientHello(String shellVersion, int protocolVersion) {
}
