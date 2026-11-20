package demo.core;

/**
 * Salutations de démonstration du module core.
 */
public final class Salutations {

    private Salutations() {
    }

    /**
     * Salue quelqu'un poliment.
     *
     * @param nom le nom à saluer.
     * @return la salutation.
     */
    public static String saluer(String nom) {
        return "Bonjour, " + nom + " !";
    }
}
