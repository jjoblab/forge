package jo.forge.engine.model;

/**
 * Exception du modèle déclaratif : {@code forge.json} absent, illisible ou invalide.
 *
 * <p>Le message est toujours actionnable (chemin concerné, champ fautif, attendu
 * trouvé) — il remonte tel quel dans la console du build.</p>
 */
public final class ModelException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Crée l'exception avec un message actionnable.
     *
     * @param message description de l'erreur (chemin, champ, attendu/trouvé).
     */
    public ModelException(String message) {
        super(message);
    }

    /**
     * Crée l'exception avec un message et une cause technique.
     *
     * @param message description de l'erreur.
     * @param cause   cause technique (erreur d'E/S, JSON malformé…).
     */
    public ModelException(String message, Throwable cause) {
        super(message, cause);
    }
}
