package jo.forge.api;

/**
 * Réponse du moteur à une demande d'état ({@code STATUS}).
 *
 * @param engineVersion version du moteur.
 * @param state         état courant.
 * @param uptimeMillis  durée de vie du moteur en millisecondes.
 * @param capabilities  capacités annoncées (identiques à celles du {@link Hello}).
 */
public record Status(String engineVersion, EngineState state, long uptimeMillis,
                     java.util.List<String> capabilities) {
}
