package jo.forge.api;

/**
 * Trame unitaire du canal de contrôle : un type, un identifiant de corrélation et une
 * charge utile opaque.
 *
 * <p>Format filaire (petit-boutiste, après l'entier de longueur) :
 * {@code [int16 codeType][int64 idCorrelation][charge utile]}. La charge utile est décodée
 * par les codecs de {@link MessageIO} selon le type de trame.</p>
 *
 * @param type          type de la trame, jamais {@code null}.
 * @param correlationId identifiant de corrélation (requête → réponse) ; 0 pour les trames
 *                      spontanées (événements, poignée de main).
 * @param payload       charge utile, éventuellement vide, jamais {@code null}.
 */
public record Frame(FrameType type, long correlationId, byte[] payload) {

    /** Taille maximale d'une trame (hors entier de longueur) : 8 Mio. */
    public static final int MAX_FRAME_BYTES = 8 * 1024 * 1024;

    /**
     * Construit une trame en vérifiant ses invariants.
     *
     * @throws NullPointerException     si le type ou la charge utile est nul.
     * @throws ProtocolException        si la charge utile dépasse {@link #MAX_FRAME_BYTES}.
     */
    public Frame {
        java.util.Objects.requireNonNull(type, "type");
        java.util.Objects.requireNonNull(payload, "payload");
        if (payload.length + 10 > MAX_FRAME_BYTES) {
            throw new ProtocolException(
                    "Trame trop grande : " + (payload.length + 10) + " octets (max "
                            + MAX_FRAME_BYTES + ")");
        }
    }

    /**
     * Trame sans charge utile du type donné.
     *
     * @param type          type de la trame.
     * @param correlationId identifiant de corrélation.
     * @return la trame construite.
     */
    public static Frame empty(FrameType type, long correlationId) {
        return new Frame(type, correlationId, new byte[0]);
    }
}
