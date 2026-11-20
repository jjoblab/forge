package jo.forge.api;

/**
 * Type d'une trame échangée sur le canal de contrôle.
 *
 * <p>Le code filaire est explicite (jamais l'ordinal de l'énumération) : il est stable
 * d'une version de protocole à l'autre et sert de contrat binaire.</p>
 */
public enum FrameType {

    /** Poignée de main moteur → coquille : version du moteur, plage de protocole, capacités. */
    HELLO(1),

    /** Poignée de main coquille → moteur : version de la coquille, version choisie. */
    CLIENT_HELLO(2),

    /** Sonde de vie coquille → moteur. */
    PING(3),

    /** Réponse du moteur à {@link #PING}. */
    PONG(4),

    /** Demande d'état et de version du moteur. */
    STATUS_REQUEST(5),

    /** Réponse du moteur : état, version, capacité, durée de vie. */
    STATUS(6),

    /** Demande de lancement d'un build (charge utile : requête de build). */
    START_BUILD(7),

    /** Accusé du moteur : build accepté, identifiant acté. */
    BUILD_STARTED(8),

    /** Événement de build en flux (journal, diagnostic, état de tâche, progression, fin). */
    BUILD_EVENT(9),

    /** Demande d'annulation d'un build en cours. */
    CANCEL_BUILD(10),

    /** Accusé d'annulation : le build est arrêté, plus aucune travailleuse n'est vivante. */
    CANCELLED(11),

    /** Demande d'arrêt propre du moteur. */
    SHUTDOWN(12),

    /** Erreur de protocole (version non négociée, trame invalide) : le canal se ferme ensuite. */
    PROTOCOL_ERROR(13),

    /** Erreur interne du moteur non liée à un build (le moteur reste vivant). */
    INTERNAL_ERROR(14);

    private final int wireCode;

    FrameType(int wireCode) {
        this.wireCode = wireCode;
    }

    /**
     * Code filaire stable de ce type de trame.
     *
     * @return le code utilisé dans l'en-tête de trame.
     */
    public int wireCode() {
        return wireCode;
    }

    /**
     * Retrouve le type de trame depuis son code filaire.
     *
     * @param wireCode code lu sur le canal.
     * @return le type correspondant.
     * @throws ProtocolException si le code est inconnu de cette version du contrat.
     */
    public static FrameType fromWire(int wireCode) {
        for (FrameType type : values()) {
            if (type.wireCode == wireCode) {
                return type;
            }
        }
        throw new ProtocolException("Type de trame inconnu : " + wireCode);
    }
}
