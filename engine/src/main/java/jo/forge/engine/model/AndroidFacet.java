package jo.forge.engine.model;

import java.util.List;

/**
 * Facette Android d'un module ({@link ModuleType#APPLICATION} ou
 * {@link ModuleType#ANDROID_LIBRARY}) : chemins Android et paramètres de plate-forme.
 *
 * <p>Les chemins sont relatifs à la racine du module (répertoire portant son nom sous
 * la racine du projet). La signature « debug » est générée par le moteur à la première
 * utilisation (clé dédiée sous {@code .forge/debug-key}, jamais un mot de passe en
 * clair — la signature de production reste une évolution documentée dans
 * {@code docs/limites-et-suite.md}).</p>
 *
 * @param manifest       chemin du manifeste Android du module.
 * @param resourceDirs   répertoires de ressources (fusionnés dans l'ordre).
 * @param assetDirs      répertoires d'assets (fusionnés dans l'ordre).
 * @param minSdk         niveau d'API minimal du module.
 * @param targetSdk      niveau d'API cible du module.
 * @param applicationId  identifiant d'application (suffixe de package), si différent
 *                       du namespace.
 * @param namespace      espace de noms Java du module (package des classes R et
 *                       BuildConfig).
 */
public record AndroidFacet(String manifest, List<String> resourceDirs,
                           List<String> assetDirs, int minSdk, int targetSdk,
                           String applicationId, String namespace) {

    /**
     * Valide et fige la facette.
     */
    public AndroidFacet {
        resourceDirs = resourceDirs == null ? List.of() : List.copyOf(resourceDirs);
        assetDirs = assetDirs == null ? List.of() : List.copyOf(assetDirs);
        if (manifest == null || manifest.isBlank()) {
            throw new ModelException("Facette Android sans manifeste");
        }
        if (namespace == null || namespace.isBlank()) {
            throw new ModelException("Facette Android sans espace de noms");
        }
        if (minSdk <= 0) {
            throw new ModelException("minSdk invalide : " + minSdk);
        }
    }
}
