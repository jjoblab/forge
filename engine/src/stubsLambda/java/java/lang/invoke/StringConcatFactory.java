package java.lang.invoke;

/**
 * Tranche de compilation (stub) de la fabrique de concaténation de chaînes.
 *
 * <p>{@code android.jar} omet cette classe (API masquée du runtime) alors qu'un
 * compilateur Java 9+ abaisse {@code "a" + b} vers un {@code invokedynamic}
 * pointant ici : sans le stub, ECJ échoue avec « The type
 * java.lang.invoke.StringConcatFactory cannot be resolved ». Le stub ne porte
 * que les signatures — il sert à la compilation, jamais à l'exécution (le
 * runtime API 26+ fournit la vraie fabrique, et D8 désucre l'invokedynamic
 * au dexage quand il le faut).</p>
 *
 * <p>Compilé par ECJ {@code --release 8} (la plate-forme Java 8 porte
 * {@code MethodHandles} mais pas cette fabrique : aucune collision de
 * définition) puis embarqué comme entrée de {@code /lambda-stubs.jar} —
 * voir {@code engine/build.gradle}. Jamais dexé dans le moteur : définir
 * {@code java.*} dans un dex est interdit sur ART.</p>
 */
public final class StringConcatFactory {

    private StringConcatFactory() {
    }

    /**
     * Amorce de concaténation simple (sans recette).
     *
     * @param lookup     propriétaire du site d'appel.
     * @param name       nom de l'invokedynamic.
     * @param concatType type de la méthode de concaténation.
     * @return le site d'appel — jamais retourné par le stub.
     * @throws StringConcatException déclarée pour la compatibilité des signatures.
     */
    public static CallSite makeConcat(MethodHandles.Lookup lookup, String name,
                                      MethodType concatType)
            throws StringConcatException {
        throw new RuntimeException("Stub de compilation, jamais exécuté");
    }

    /**
     * Amorce de concaténation avec recette et constantes.
     *
     * @param lookup     propriétaire du site d'appel.
     * @param name       nom de l'invokedynamic.
     * @param concatType type de la méthode de concaténation.
     * @param recipe     recette de concaténation.
     * @param constants  constantes de la recette.
     * @return le site d'appel — jamais retourné par le stub.
     * @throws StringConcatException déclarée pour la compatibilité des signatures.
     */
    public static CallSite makeConcatWithConstants(MethodHandles.Lookup lookup,
                                                   String name, MethodType concatType,
                                                   String recipe, Object... constants)
            throws StringConcatException {
        throw new RuntimeException("Stub de compilation, jamais exécuté");
    }
}
