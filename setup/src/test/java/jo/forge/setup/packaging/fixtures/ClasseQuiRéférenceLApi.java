package jo.forge.setup.packaging.fixtures;

/**
 * Fixture de test (hors production) : classe compilée par javac qui RÉFÉRENCE
 * l'API {@code java.compiler} ({@code SourceVersion}, {@code JavaCompiler},
 * {@code Processor}) — ses octets {@code .class} servent de « jar compilateur »
 * miniature au test du garde-fou du packager
 * ({@code PackagerComposants.vérifierRéférencesApi}), sans dépendre du vrai jar
 * d'ECJ.
 */
public final class ClasseQuiRéférenceLApi {

    private ClasseQuiRéférenceLApi() {
    }

    /**
     * Force javac à émettre les références de classes vers les trois paquets de
     * l'API (littéraux {@code .class} — CONSTANT_Class, exactement ce que le
     * lecteur du packager doit relever).
     *
     * @return une chaîne décrivant les trois types.
     */
    public static String décrire() {
        return javax.lang.model.SourceVersion.latest().name()
                + javax.tools.JavaCompiler.class.getSimpleName()
                + javax.annotation.processing.Processor.class.getSimpleName();
    }
}
