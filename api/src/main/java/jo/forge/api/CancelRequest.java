package jo.forge.api;

/**
 * Demande d'annulation d'un build (trame {@code CANCEL_BUILD}).
 *
 * @param buildId identifiant du build à annuler.
 */
public record CancelRequest(String buildId) {

    /**
     * Valide la demande.
     *
     * @throws ProtocolException si l'identifiant est vide.
     */
    public CancelRequest {
        if (buildId == null || buildId.isBlank()) {
            throw new ProtocolException("Identifiant de build vide");
        }
    }
}
