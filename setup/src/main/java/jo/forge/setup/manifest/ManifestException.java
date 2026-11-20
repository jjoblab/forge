package jo.forge.setup.manifest;

/**
 * Erreur de manifeste : champ manquant ou invalide, signature fausse, dépendance
 * inconnue. Toujours accompagnée d'un message exploitable par l'assistant.
 */
public class ManifestException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Crée une erreur de manifeste.
     *
     * @param message description de l'erreur, en français.
     */
    public ManifestException(String message) {
        super(message);
    }

    /**
     * Crée une erreur de manifeste avec cause.
     *
     * @param message description de l'erreur, en français.
     * @param cause   cause racine.
     */
    public ManifestException(String message, Throwable cause) {
        super(message, cause);
    }
}
