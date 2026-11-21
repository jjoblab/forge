package demo.app;

/**
 * Java du module KOTLIN : compile CONTRE les classes Kotlin fraîches (le Java
 * voit le Kotlin, le Kotlin voit le Java — les deux sens sont couverts).
 */
public final class Vérificateur {

    private Vérificateur() {
    }

    /**
     * Compte les mots via l'objet Kotlin du module.
     *
     * @param texte texte à mesurer.
     * @return le nombre de mots.
     */
    public static int mots(String texte) {
        return Outils.INSTANCE.nombreDeMots(texte);
    }
}
