package jo.forge.engine.deps;

/**
 * Exception de résolution : artefact introuvable, POM invalide, conflit refusé,
 * intégrité échouée, bornes dépassées.
 *
 * <p>Le message est actionnable (coordonnées, dépôt, cause) : il remonte dans la
 * console du build tel quel.</p>
 */
public final class ResolverException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Crée l'exception.
     *
     * @param message description actionnable.
     */
    public ResolverException(String message) {
        super(message);
    }

    /**
     * Crée l'exception avec cause technique.
     *
     * @param message description.
     * @param cause   cause technique.
     */
    public ResolverException(String message, Throwable cause) {
        super(message, cause);
    }
}
