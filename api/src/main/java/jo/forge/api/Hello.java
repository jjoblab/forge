package jo.forge.api;

/**
 * Poignée de main moteur → coquille, première trame émise au démarrage du moteur.
 *
 * @param engineVersion version du moteur (numérotation sémantique).
 * @param protocolMin   plus ancienne version de protocole acceptée par le moteur.
 * @param protocolMax   plus récente version de protocole acceptée par le moteur.
 * @param capabilities  capacités annoncées (ex. {@code ping}, {@code fake-build}) ;
 *                      la coquille ne doit pas dépendre d'une capacité absente.
 */
public record Hello(String engineVersion, int protocolMin, int protocolMax,
                    java.util.List<String> capabilities) {
}
