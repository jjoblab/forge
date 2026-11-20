package jo.forge.setup.download;

import java.io.IOException;

/**
 * Erreur de téléchargement ou d'installation : serveur injoignable, somme invalide,
 * espace insuffisant, archive corrompue. Le message est montré tel quel à l'utilisateur.
 */
public class InstallException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Crée une erreur d'installation.
     *
     * @param message description de l'erreur, en français.
     */
    public InstallException(String message) {
        super(message);
    }

    /**
     * Crée une erreur d'installation avec cause.
     *
     * @param message description de l'erreur, en français.
     * @param cause   cause racine.
     */
    public InstallException(String message, Throwable cause) {
        super(message, cause);
    }
}
