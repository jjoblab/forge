package jo.forge.engine.diag;

/**
 * Puits de diagnostics d'une tâche : là où les tâches déposent leurs diagnostics
 * structurés, indépendamment du journal texte.
 *
 * <p>Le contexte d'exécution ({@code TaskContext}) fournit le puits réel, tamponné au
 * nom de la tâche ; les tests peuvent fournir leur propre collecteur.</p>
 */
@FunctionalInterface
public interface DiagnosticSink {

    /** Puits qui ignore tout (défaut des contextes sans serveur). */
    DiagnosticSink NOOP = diagnostic -> {
    };

    /**
     * Dépose un diagnostic.
     *
     * @param diagnostic le diagnostic, jamais nul.
     */
    void report(Diagnostic diagnostic);
}
