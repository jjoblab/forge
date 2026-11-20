package jo.forge.api;

/**
 * Accusé d'annulation d'un build (trame {@code CANCELLED}).
 *
 * <p>Le moteur n'envoie cet accusé qu'une fois l'annulation <em>terminée</em> : plus
 * aucune travailleuse ni processus enfant du build n'est vivant
 * (voir {@code docs/conception.md}, décision D-08).</p>
 *
 * @param buildId identifiant du build annulé.
 * @param wasRunning {@code true} si un build était réellement en cours.
 */
public record Cancelled(String buildId, boolean wasRunning) {
}
