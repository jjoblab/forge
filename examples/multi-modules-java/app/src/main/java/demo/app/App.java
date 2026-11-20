package demo.app;

import demo.core.Salutations;
import demo.core.Textes;

/**
 * Point d'entrée de l'exemple : assemble des salutations à partir du module core.
 */
public final class App {

    private App() {
    }

    /**
     * Construit le message de bienvenue de l'application.
     *
     * @param nom nom de l'utilisateur.
     * @return le message complet.
     */
    public static String bienvenue(String nom) {
        return Textes.joindre(" — ", Salutations.saluer(nom),
                "bienvenue dans l'exemple Forge multi-modules");
    }
}
