package jo.forge.setup.download;

/**
 * Jeton d'annulation coopératif : posé par l'appelant (interface), testé par les
 * opérations longues (téléchargement, installation) à leurs points sûrs.
 *
 * <p>Thread-sûr : une position visible depuis tous les threads ; une fois posé, l'annulation
 * est définitive.</p>
 */
public final class CancellationToken {

    private volatile boolean annule;

    /**
     * Pose l'annulation. Les opérations en cours s'arrêteront à leur prochain point sûr.
     */
    public void cancel() {
        annule = true;
    }

    /**
     * Indique si l'annulation a été demandée.
     *
     * @return {@code true} si annulé.
     */
    public boolean isCancelled() {
        return annule;
    }

    /**
     * Lève l'exception d'annulation si l'annulation a été demandée.
     *
     * @throws InstallCancelledException si annulé.
     */
    public void check() {
        if (annule) {
            throw new InstallCancelledException("Annulation demandée");
        }
    }
}
