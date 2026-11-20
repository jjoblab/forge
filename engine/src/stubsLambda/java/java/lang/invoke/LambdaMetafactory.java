package java.lang.invoke;

/**
 * Tranche de compilation (stub) de la fabrique de lambdas.
 *
 * <p>{@code android.jar} omet cette classe (API masquée du runtime) alors qu'un
 * compilateur Java 8+ abaisse une lambda vers un {@code invokedynamic} pointant
 * ici : sans le stub, ECJ échoue avec « The type
 * java.lang.invoke.LambdaMetafactory cannot be resolved ». Signatures seules —
 * compilation uniquement ; le runtime API 26+ fournit la vraie fabrique et D8
 * désucre l'invokedynamic au dexage quand il le faut.</p>
 */
public final class LambdaMetafactory {

    /** Marqueur : la lambda implémente {@code Serializable}. */
    public static final int FLAG_SERIALIZABLE = 1;

    /** Marqueur : interfaces marqueurs additionnels. */
    public static final int FLAG_MARKERS = 2;

    /** Marqueur : interfaces ponts additionnelles. */
    public static final int FLAG_BRIDGES = 4;

    private LambdaMetafactory() {
    }

    /**
     * Amorce standard d'une lambda.
     *
     * @param owner                  propriétaire du site d'appel.
     * @param invokedName            nom de la méthode fonctionnelle.
     * @param invokedType            type de l'invokedynamic.
     * @param samMethodType          type de la méthode de l'interface.
     * @param implMethod             méthode d'implémentation.
     * @param instantiatedMethodType type instancié de l'implémentation.
     * @return le site d'appel — jamais retourné par le stub.
     * @throws LambdaConversionException déclarée pour la compatibilité des signatures.
     */
    public static CallSite metafactory(MethodHandles.Lookup owner, String invokedName,
                                       MethodType invokedType, MethodType samMethodType,
                                       MethodHandle implMethod,
                                       MethodType instantiatedMethodType)
            throws LambdaConversionException {
        throw new RuntimeException("Stub de compilation, jamais exécuté");
    }

    /**
     * Amorce généralisée (sérialisation, marqueurs, ponts).
     *
     * @param owner       propriétaire du site d'appel.
     * @param invokedName nom de la méthode fonctionnelle.
     * @param invokedType type de l'invokedynamic.
     * @param args        arguments de méta-fabrique.
     * @return le site d'appel — jamais retourné par le stub.
     * @throws LambdaConversionException déclarée pour la compatibilité des signatures.
     */
    public static CallSite altMetafactory(MethodHandles.Lookup owner, String invokedName,
                                          MethodType invokedType, Object... args)
            throws LambdaConversionException {
        throw new RuntimeException("Stub de compilation, jamais exécuté");
    }
}
