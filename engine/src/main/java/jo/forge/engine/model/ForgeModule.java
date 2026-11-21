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
 * @param javaRelease niveau Java de compilation ({@code --release} du compilateur —
 *                    cible JVM de kotlinc pour un module Kotlin).
 * @param sources     répertoires sources, relatifs au répertoire du module (Java,
 *                    Kotlin ou les deux selon {@code language}).
 * @param resources   répertoires de ressources Java, relatifs au répertoire du module.
 * @param dependencies dépendances du module (projets frères et coordonnées Maven).
 * @param android     facette Android (types {@code application} et
 *                    {@code android-library} uniquement, sinon absente).
 * @param language    langage principal du module depuis v0.2.9 : {@code java}
 *                    (défaut) ou {@code kotlin} (feuille de route langages —
 *                    {@code compileKotlin} avant {@code compileJava}).
 */
public record ForgeModule(String name, ModuleType type, int javaRelease,
                          List<String> sources, List<String> resources,
                          List<ModuleDependency> dependencies,
                          Optional<AndroidFacet> android, String language) {

    /** Langage par défaut quand le modèle ne le précise pas. */
    public static final String LANGUAGE_JAVA = "java";

    /** Langage Kotlin (feuille de route langages, v0.2.9). */
    public static final String LANGUAGE_KOTLIN = "kotlin";

    /** Niveau Java par défaut quand le modèle ne le précise pas. */
    public static final int DEFAULT_JAVA_RELEASE = 17;

    /**
     * Constructeur historique sans langage (défaut : Java) — modules et tests
     * existants.
     *
     * @param name        nom unique du module.
     * @param type        type du module.
     * @param javaRelease niveau Java de compilation.
     * @param sources     répertoires sources Java.
     * @param resources   répertoires de ressources.
     * @param dependencies dépendances du module.
     * @param android     facette Android (facultative).
     */
    public ForgeModule(String name, ModuleType type, int javaRelease,
                       List<String> sources, List<String> resources,
                       List<ModuleDependency> dependencies,
                       Optional<AndroidFacet> android) {
        this(name, type, javaRelease, sources, resources, dependencies, android,
                LANGUAGE_JAVA);
    }

    /**
     * Valide et fige le module.
     *
     * @throws ModelException si un nom est vide, si le langage est inconnu, si la
     *                        facette Android manque là où elle est obligatoire ou est
     *                        présente là où elle est interdite.
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
        language = language == null || language.isBlank() ? LANGUAGE_JAVA : language;
        boolean androidType = type == ModuleType.APPLICATION
                || type == ModuleType.ANDROID_LIBRARY;
        if (!LANGUAGE_JAVA.equals(language) && !LANGUAGE_KOTLIN.equals(language)) {
            throw new ModelException("Module " + name + " : langage inconnu \""
                    + language + "\" (attendu : " + LANGUAGE_JAVA + " ou "
                    + LANGUAGE_KOTLIN + ")");
        }
        if (LANGUAGE_KOTLIN.equals(language) && androidType) {
            // Refus explicite plutôt que sources .kt ignorées en silence : le
            // pipeline Android ne sait pas encore enchaîner compileKotlin (v1,
            // feuille de route langages — Compose viendra avec).
            throw new ModelException("Module " + name + " : le langage " + language
                    + " n'est pas encore câblé pour le type " + type.wireName()
                    + " (java-library d'abord — feuille de route langages)");
        }
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
     * Indique si le module est écrit en Kotlin (tâche {@code compileKotlin} en tête
     * de chaîne).
     *
     * @return {@code true} pour un module Kotlin.
     */
    public boolean estKotlin() {
        return LANGUAGE_KOTLIN.equals(language);
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
     * Nom de la tâche de compilation Kotlin de ce module.
     *
     * @return {@code <module>:compileKotlin}.
     */
    public String kotlinCompileTaskName() {
        return name + ":compileKotlin";
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
