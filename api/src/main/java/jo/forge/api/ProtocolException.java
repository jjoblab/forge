package jo.forge.api;

/**
 * Erreur de protocole : trame ou charge utile malformée, version non négociable, code
 * inconnu.
 *
 * <p>Cette exception est runtime car elle signale un défaut de programmation ou un canal
 * corrompu, pas une condition attendue du domaine.</p>
 */
public class ProtocolException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Crée une erreur de protocole avec un message.
     *
     * @param message description de l'erreur, en français.
     */
    public ProtocolException(String message) {
        super(message);
    }

    /**
     * Crée une erreur de protocole avec un message et une cause.
     *
     * @param message description de l'erreur, en français.
     * @param cause   cause racine.
     */
    public ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
