package jdk.jfr;

/**
 * Enregistreur de vol no-op — shim headless pour ART (correctif v0.2.14, voir
 * {@link Event} pour le pourquoi du paquet). Jamais disponible, jamais
 * initialisé : aucune inscription ne fait rien.
 */
public final class FlightRecorder {

    private FlightRecorder() {
    }

    /**
     * Indique si l'enregistreur est disponible.
     *
     * @return toujours {@code false}.
     */
    public static boolean isAvailable() {
        return false;
    }

    /**
     * Indique si l'enregistreur est initialisé.
     *
     * @return toujours {@code false}.
     */
    public static boolean isInitialized() {
        return false;
    }

    /**
     * Inscrit une classe d'événement — no-op.
     *
     * @param eventClass la classe d'événement (ignorée).
     */
    public static void register(Class<? extends Event> eventClass) {
    }

    /**
     * Désinscrit une classe d'événement — no-op.
     *
     * @param eventClass la classe d'événement (ignorée).
     */
    public static void unregister(Class<? extends Event> eventClass) {
    }

    /**
     * Ajoute un rappel périodique — no-op.
     *
     * @param eventClass la classe d'événement (ignorée).
     * @param hook       le rappel (ignoré).
     */
    public static void addPeriodicEvent(Class<? extends Event> eventClass, Runnable hook) {
    }

    /**
     * Retire un rappel périodique.
     *
     * @param hook le rappel (ignoré).
     * @return toujours {@code false} — rien n'est jamais inscrit.
     */
    public static boolean removePeriodicEvent(Runnable hook) {
        return false;
    }
}
