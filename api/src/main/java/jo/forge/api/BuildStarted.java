package jo.forge.api;

/**
 * Accusé de prise en charge d'un build (trame {@code BUILD_STARTED}).
 *
 * @param buildId identifiant du build accepté (identique à celui de la requête).
 */
public record BuildStarted(String buildId) {
}
