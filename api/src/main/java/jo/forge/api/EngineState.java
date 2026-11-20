package jo.forge.api;

/**
 * État de vie du moteur, transporté par la trame {@code STATUS}.
 */
public enum EngineState {

    /** Le moteur démarre : la poignée de main n'est pas encore terminée. */
    STARTING(1),

    /** Le moteur est prêt, aucun build en cours. */
    IDLE(2),

    /** Au moins un build est en cours. */
    BUILDING(3);

    private final int wireCode;

    EngineState(int wireCode) {
        this.wireCode = wireCode;
    }

    /**
     * Code filaire stable de l'état.
     *
     * @return le code utilisé dans la charge utile.
     */
    public int wireCode() {
        return wireCode;
    }

    /**
     * Retrouve l'état depuis son code filaire.
     *
     * @param wireCode code lu sur le canal.
     * @return l'état correspondant.
     * @throws ProtocolException si le code est inconnu.
     */
    public static EngineState fromWire(int wireCode) {
        for (EngineState state : values()) {
            if (state.wireCode == wireCode) {
                return state;
            }
        }
        throw new ProtocolException("État moteur inconnu : " + wireCode);
    }
}
