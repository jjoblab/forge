package jo.forge.setup.json;

/**
 * Erreur d'analyse ou d'écriture JSON : entrée mal formée, type inattendu, profondeur
 * excessive.
 */
public class JsonException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Crée une erreur JSON avec un message.
     *
     * @param message description de l'erreur, en français.
     */
    public JsonException(String message) {
        super(message);
    }
}
