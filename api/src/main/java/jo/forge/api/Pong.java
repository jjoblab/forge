package jo.forge.api;

/**
 * Réponse du moteur à un {@code PING}.
 *
 * @param engineTimestamp horodatage moteur au moment de la réponse (millisecondes epoch) ;
 *                        la coquille peut en dériver la latence du canal.
 */
public record Pong(long engineTimestamp) {
}
