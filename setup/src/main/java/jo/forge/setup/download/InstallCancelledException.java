package jo.forge.setup.download;

/**
 * Annulation demandée par l'utilisateur : l'opération s'est arrêtée proprement à un
 * point sûr, l'état sur disque est cohérent (reprise possible).
 */
public class InstallCancelledException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Crée l'exception d'annulation.
     *
     * @param message description en français.
     */
    public InstallCancelledException(String message) {
        super(message);
    }
}
