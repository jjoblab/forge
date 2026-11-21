package jdk.jfr;

/**
 * Type d'événement no-op — shim headless pour ART (correctif v0.2.14, voir
 * {@link Event} pour le pourquoi du paquet). Le type unique répond toujours
 * « désactivé, sans nom » : aucun consommateur n'existe headless.
 */
public final class EventType {

    private static final EventType INSTANCE = new EventType();

    private EventType() {
    }

    /**
     * Le type d'une classe d'événement.
     *
     * @param eventClass la classe (ignorée).
     * @return l'instance unique, désactivée.
     */
    public static EventType getEventType(Class<? extends Event> eventClass) {
        return INSTANCE;
    }

    /**
     * Indique si le type est activé.
     *
     * @return toujours {@code false}.
     */
    public boolean isEnabled() {
        return false;
    }

    /**
     * Le nom du type.
     *
     * @return toujours la chaîne vide.
     */
    public String getName() {
        return "";
    }

    /**
     * Le libellé du type.
     *
     * @return toujours la chaîne vide.
     */
    public String getLabel() {
        return "";
    }

    /**
     * L'identifiant du type.
     *
     * @return toujours {@code 0}.
     */
    public long getId() {
        return 0L;
    }
}
