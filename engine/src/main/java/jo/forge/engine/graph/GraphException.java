package jo.forge.engine.graph;

/**
 * Erreur de construction du graphe : nom dupliqué, dépendance inconnue ou cycle. Le
 * message est directement affichable (il cite le cycle).
 */
public class GraphException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Crée une erreur de graphe.
     *
     * @param message description en français.
     */
    public GraphException(String message) {
        super(message);
    }
}
