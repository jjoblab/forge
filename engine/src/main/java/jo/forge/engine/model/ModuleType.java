package jo.forge.engine.model;

/**
 * Type d'un module de projet Forge.
 *
 * <p>Le type détermine le pipeline qui s'applique : chaîne Java pour une bibliothèque
 * JVM, chaîne Android complète pour une application (ressources, dex, packaging,
 * signature). Les types forment un ensemble fermé — en ajouter un est une décision de
 * conception, pas un simple ajout de constante.</p>
 */
public enum ModuleType {

    /** Bibliothèque Java pure : {@code compileJava} puis {@code jar}. */
    JAVA_LIBRARY("java-library"),

    /** Application Android : chaîne complète jusqu'à l'APK signé. */
    APPLICATION("application"),

    /** Bibliothèque Android : ressources, classes et AAR. */
    ANDROID_LIBRARY("android-library");

    private final String wireName;

    ModuleType(String wireName) {
        this.wireName = wireName;
    }

    /**
     * Nom stable utilisé dans {@code forge.json}.
     *
     * @return le nom en kebab-case.
     */
    public String wireName() {
        return wireName;
    }

    /**
     * Retrouve le type depuis son nom {@code forge.json}.
     *
     * @param wireName nom lu dans le modèle (insensible à la casse).
     * @return le type correspondant.
     * @throws ModelException si le nom est inconnu.
     */
    public static ModuleType fromWire(String wireName) {
        for (ModuleType type : values()) {
            if (type.wireName.equalsIgnoreCase(wireName)) {
                return type;
            }
        }
        throw new ModelException("Type de module inconnu : " + wireName
                + " (attendus : java-library, application, android-library)");
    }
}
