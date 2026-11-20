package demo.core;

/**
 * Petits utilitaires de texte, pour l'exemple multi-modules.
 */
public final class Textes {

    private Textes() {
    }

    /**
     * Joint des mots avec un séparateur.
     *
     * @param separateur séparateur inséré entre les mots.
     * @param mots       mots à joindre.
     * @return la chaîne jointe.
     */
    public static String joindre(String separateur, String... mots) {
        return String.join(separateur, mots);
    }
}
