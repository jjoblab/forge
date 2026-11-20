package jo.forge.engine.testutil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Fabrication de projets de test sur disque (répertoires temporaires).
 */
public final class Projets {

    private Projets() {
    }

    /**
     * Écrit un fichier texte en créant ses répertoires parents.
     *
     * @param racine   racine du projet.
     * @param chemin   chemin relatif du fichier.
     * @param contenu  contenu texte.
     * @return le chemin du fichier écrit.
     * @throws IOException si l'écriture échoue.
     */
    public static Path fichier(Path racine, String chemin, String contenu)
            throws IOException {
        Path fichier = racine.resolve(chemin);
        Files.createDirectories(fichier.getParent());
        Files.writeString(fichier, contenu);
        return fichier;
    }

    /**
     * Projet minimal à deux modules Java : {@code core} (utilitaires) et {@code app}
     * (dépend de {@code core} en portée {@code api}).
     *
     * @param racine répertoire du projet (créé s'il manque).
     * @return la racine.
     * @throws IOException si l'écriture échoue.
     */
    public static Path projetDeuxModules(Path racine) throws IOException {
        fichier(racine, "forge.json", """
                {
                  "modelVersion": 1,
                  "repositories": ["https://repo.maven.apache.org/maven2"],
                  "modules": [
                    { "name": "core", "type": "java-library" },
                    { "name": "app", "type": "java-library",
                      "dependencies": [ { "project": "core", "scope": "api" } ] }
                  ]
                }
                """);
        fichier(racine, "core/src/main/java/demo/core/Textes.java", """
                package demo.core;

                public final class Textes {
                    private Textes() { }

                    public static String joindre(String sep, String... mots) {
                        return String.join(sep, mots);
                    }
                }
                """);
        fichier(racine, "core/src/main/resources/demo/core/messages.properties",
                "bonjour=Bonjour !\n");
        fichier(racine, "app/src/main/java/demo/app/App.java", """
                package demo.app;

                import demo.core.Textes;

                public final class App {
                    private App() { }

                    public static String bienvenue() {
                        return Textes.joindre(" ", "Bonjour,", "l'exemple !");
                    }
                }
                """);
        return racine;
    }
}
