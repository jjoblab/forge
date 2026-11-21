package demo.core;

/**
 * Salutations du module core (Java) de l'exemple Android Kotlin : le Kotlin de
 * l'application référence ce Java — la compilation jointe s'en charge.
 */
public final class Salutations {

    private Salutations() {
    }

    /**
     * Message d'accueil de l'application.
     *
     * @return le message.
     */
    public static String app() {
        return "Salutations de Forge";
    }
}
