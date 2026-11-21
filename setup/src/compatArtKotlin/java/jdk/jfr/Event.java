package jdk.jfr;

/**
 * Événement Java Flight Recorder no-op — shim headless pour ART (correctif
 * v0.2.14).
 *
 * <p>Pourquoi ce paquet existe : {@code jdk.jfr} (module {@code jdk.jfr} du
 * JDK) est absent d'Android, mais des classes applicatives PEUVENT le définir
 * (contrairement à {@code java.*}, interdit dans un dex). L'audit des pools de
 * constantes de notre ensemble 2.0.21 ne montre AUCUNE référence à ces classes
 * — elles sont préventives, au contraire de {@code javax.swing.SwingUtilities}
 * (touché, lui) : le registre de compatibilité du moteur de référence, qui
 * embarque une plateforme plus récente, y a rencontré des classes déclarant
 * des événements JFR et le correctif v0.2.14 les embarque pour la même raison
 * — le contrat de maintenance consigné (toute montée de version du
 * compilateur ré-audite la surface) les rendra nécessaires le jour où une
 * montée les référence. Le coût est nul : dix classes no-op, jamais
 * exécutées.</p>
 *
 * <p>Un événement n'est jamais activé headless : {@link #isEnabled()} et
 * {@link #shouldCommit()} répondent toujours faux, les mutations ne font
 * rien. Compilé par ECJ contre {@code android.jar}, dexé dans le composant
 * kotlin-compiler.</p>
 */
public abstract class Event {

    /**
     * Marque le début de l'événement — no-op.
     */
    public void begin() {
    }

    /**
     * Marque la fin de l'événement — no-op.
     */
    public void end() {
    }

    /**
     * Soumet l'événement — no-op.
     */
    public void commit() {
    }

    /**
     * Indique si l'événement est activé.
     *
     * @return toujours {@code false}.
     */
    public boolean isEnabled() {
        return false;
    }

    /**
     * Indique si la soumission ferait quelque chose.
     *
     * @return toujours {@code false}.
     */
    public boolean shouldCommit() {
        return false;
    }

    /**
     * Pose la valeur d'un champ de l'événement — no-op.
     *
     * @param index indice du champ (ignoré).
     * @param value valeur (ignorée).
     */
    public void set(int index, Object value) {
    }
}
