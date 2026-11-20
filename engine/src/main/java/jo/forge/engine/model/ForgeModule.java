package jo.forge.engine.model;

import java.util.List;
import java.util.Optional;

/**
 * Un module d'un projet Forge (décision D-11).
 *
 * <p>Le module est purement déclaratif : des chemins relatifs, des dépendances, des
 * options. Toute la logique de build vit dans les tâches, pas ici. Le répertoire du
 * module sur le disque porte son nom ({@code <racine>/<nom>/}).</p>
 *
 * @param name        nom unique du module (identifiant de répertoire et de tâche).
 * @param type        type du module (pipeline applicable).
 * @param javaRelease niveau Java de compilation ({@code --release} du compilateur).
 * @param sources     répertoires sources Java, relatifs au répertoire du module.
 * @param resources   répertoires de ressources Java, relatifs au répertoire du module.
 * @param dependencies dépendances du module (projets frères et coordonnées Maven).
 * @param android     facette Android (types {@code application} et
 *                    {@code android-library} uniquement, sinon absente).
 */
public record ForgeModule(String name, ModuleType type, int javaRelease,
                          List<String> sources, List<String> resources,
                          List<ModuleDependency> dependencies,
                          Optional<AndroidFacet> android) {

    /** Niveau Java par défaut when le modèle ne le précise pas. */
    public static final int DEFAULT_JAVA_RELEASE = 17;

    /**
     * Valide et fige le module.
     *
     * @throws ModelException si un nom est vide, si la facette Android manque là où
     *                        elle est obligatoire ou est présente là où elle est
     *                        interdite.
     */
    public ForgeModule {
        if (name == null || name.isBlank()) {
            throw new ModelException("Module sans nom");
        }
        javaRelease = javaRelease <= 0 ? DEFAULT_JAVA_RELEASE : javaRelease;
        sources = sources == null ? List.of() : List.copyOf(sources);
        resources = resources == null ? List.of() : List.copyOf(resources);
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        android = android == null ? Optional.empty() : android;
        boolean androidType = type == ModuleType.APPLICATION
                || type == ModuleType.ANDROID_LIBRARY;
        if (androidType && android.isEmpty()) {
            throw new ModelException("Module " + name
                    + " : le type " + type.wireName() + " exige une facette Android");
        }
        if (!androidType && android.isPresent()) {
            throw new ModelException("Module " + name
                    + " : facette Android interdite pour le type " + type.wireName());
        }
    }

    /**
     * Nom de la tâche de compilation Java de ce module.
     *
     * @return {@code <module>:compileJava}.
     */
    public String compileTaskName() {
        return name + ":compileJava";
    }

    /**
     * Nom de la tâche de packaging jar de ce module.
     *
     * @return {@code <module>:jar}.
     */
    public String jarTaskName() {
        return name + ":jar";
    }
}
