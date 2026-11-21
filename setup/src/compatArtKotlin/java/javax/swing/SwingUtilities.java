package javax.swing;

/**
 * SwingUtilities headless — réécriture embarquée pour ART (correctif v0.2.14).
 *
 * <p>Pourquoi cette classe existe : Android n'embarque pas Swing (module
 * {@code java.desktop} absent), mais le cœur IntelliJ du compilateur Kotlin
 * embarqué y touche dès l'enregistrement des points d'extension — six classes
 * de l'embarquable 2.0.21 référencent {@code SwingUtilities}
 * ({@code MockApplication.invokeLater}, les vérifications de fil EDT…), et le
 * rapport appareil v0.2.13 a montré l'environnement mourir dans
 * {@code CoreApplicationEnvironment.registerExtensionPointAndExtensions}. Le
 * rapport v0.2.12 avant lui mourait avant d'y arriver : chaque mur franchi
 * dévoile le suivant.</p>
 *
 * <p>{@code javax.swing} n'est pas un paquet possédé par la plateforme Android
 * (contrairement à {@code java.*}, interdit dans un dex) : une classe
 * applicative PEUT le définir. Ce shim headless satisfait les trois membres
 * référencés par l'embarquable — exactement la surface épinglée par l'audit
 * des pools de constantes ({@code invokeLater}, {@code invokeAndWait},
 * {@code isEventDispatchThread}). Il n'y a pas de fil EDT sur Android : les
 * rappels s'exécutent en ligne, sur le fil appelant.</p>
 *
 * <p>Compilée par ECJ avec {@code android.jar} en chemin d'amorçage (seule
 * façon de définir un type dans un paquet possédé par une plate-forme sans
 * conflit de module), puis dexée DANS le composant kotlin-compiler — jamais
 * dans le cœur : c'est un besoin du compilateur, pas du moteur.</p>
 */
public final class SwingUtilities {

    private SwingUtilities() {
    }

    /**
     * Indique si le fil courant est le fil de diffusion des événements.
     *
     * @return toujours {@code false} — pas de fil EDT headless.
     */
    public static boolean isEventDispatchThread() {
        return false;
    }

    /**
     * Exécute le rappel : en ligne, sur le fil appelant (pas de fil EDT).
     *
     * @param doRun le rappel à exécuter.
     */
    public static void invokeLater(Runnable doRun) {
        doRun.run();
    }

    /**
     * Exécute le rappel et revient : en ligne, sur le fil appelant.
     *
     * @param doRun le rappel à exécuter.
     */
    public static void invokeAndWait(Runnable doRun) {
        doRun.run();
    }
}
