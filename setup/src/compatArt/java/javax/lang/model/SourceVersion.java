package javax.lang.model;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Version de source Java — réécriture embarquée pour ART (correctif v0.2.6).
 *
 * <p>Pourquoi cette classe existe : Android n'embarque pas le module
 * {@code java.compiler} ({@code javax.lang.model}, {@code javax.annotation.processing},
 * {@code javax.tools}), alors que le compilateur ECJ référence 63 classes de ces
 * paquets — dont {@code javax.lang.model.SourceVersion} sur le chemin par lots
 * ({@code org.eclipse.jdt.internal.compiler.batch.FileSystem} l'appelle à chaque
 * compilation). L'assembleur de composants embarque donc l'API du module dans le
 * composant {@code java-compiler}… mais le {@code SourceVersion} de l'OpenJDK
 * appelle {@code Runtime.version()} dans son initialiseur statique
 * ({@code Runtime$Version.feature()}), une classe absente d'ART et non
 * redéfinissable par une application ({@code java.*} est interdit dans un dex).
 * Le jar de l'OpenJDK verbatim ferait simplement échanger un
 * {@code NoClassDefFoundError} contre un autre.</p>
 *
 * <p>Cette réécriture conserve la surface de l'API (mêmes constantes jusqu'à
 * {@code RELEASE_21}, mêmes méthodes) sans jamais toucher {@code Runtime$Version}.
 * La sémantique de {@link #isName(CharSequence)} et {@link #isKeyword(CharSequence)}
 * est portée de l'API standard (comportement décrit par la javadoc d'OpenJDK,
 * GPLv2 avec exception de classpath — voir docs/distribution.md §7) :
 * les littéraux {@code null}, {@code true}, {@code false} ne sont pas des noms,
 * {@code strictfp}, {@code assert}, {@code enum} et {@code _} deviennent des mots
 * clés selon la version demandée. Elle est compilée par ECJ lui-même avec
 * {@code android.jar} en chemin d'amorçage (seule façon de définir un type dans
 * un paquet possédé par la plateforme sans conflit de module).</p>
 */
public enum SourceVersion {

    /**
     * Les versions de source connues, de Java 1.0 ({@code RELEASE_0}) à
     * {@code RELEASE_21}. La gamme couvre tout ce qu'ECJ 3.36 sait énumérer ;
     * {@link #valueOf(String)} ne lève donc pas pour une version publiée.
     */
    RELEASE_0, RELEASE_1, RELEASE_2, RELEASE_3, RELEASE_4,
    RELEASE_5, RELEASE_6, RELEASE_7, RELEASE_8, RELEASE_9,
    RELEASE_10, RELEASE_11, RELEASE_12, RELEASE_13, RELEASE_14,
    RELEASE_15, RELEASE_16, RELEASE_17, RELEASE_18, RELEASE_19,
    RELEASE_20, RELEASE_21;

    /** La plus haute version de source connue de cette API. */
    private static final SourceVersion DERNIÈRE_CONNUE = RELEASE_21;

    /**
     * La plus haute version de source que ce compilateur peut gérer.
     *
     * @return {@code RELEASE_21}.
     */
    public static SourceVersion latest() {
        return DERNIÈRE_CONNUE;
    }

    /**
     * La plus haute version de source pleinement soutenue par le moteur.
     *
     * <p>Sur une JVM de bureau, la version standard lit la plateforme ; sur ART il
     * n'existe pas de « version Java de plateforme ». Le moteur soutient Java 17
     * (bytecode 61, dexé par d8) : c'est ce niveau que voit un processeur
     * d'annotations qui demanderait la version soutenue.</p>
     *
     * @return {@code RELEASE_17}.
     */
    public static SourceVersion latestSupported() {
        return RELEASE_17;
    }

    /**
     * Indique si la séquence est un nom syntaxiquement valide (identifiant Java
     * qui n'est ni mot clé ni littéral) au sens de la version la plus récente.
     *
     * @param cs la séquence à tester.
     * @return {@code true} si c'est un nom valide.
     */
    public static boolean isName(CharSequence cs) {
        return isName(cs, latest());
    }

    /**
     * Indique si la séquence est un nom syntaxiquement valide pour la version
     * donnée.
     *
     * @param cs      la séquence à tester.
     * @param version la version de source à appliquer.
     * @return {@code true} si c'est un nom valide.
     */
    public static boolean isName(CharSequence cs, SourceVersion version) {
        String identifiant = cs.toString();
        if (identifiant.isEmpty()) {
            return false;
        }
        if (!Character.isJavaIdentifierStart(identifiant.charAt(0))) {
            return false;
        }
        for (int position = 1; position < identifiant.length(); position++) {
            if (!Character.isJavaIdentifierPart(identifiant.charAt(position))) {
                return false;
            }
        }
        return !isKeyword(identifiant, version);
    }

    /**
     * Indique si la séquence est un mot clé ou un littéral au sens de la version
     * la plus récente.
     *
     * @param s la séquence à tester.
     * @return {@code true} si c'est un mot clé ou un littéral.
     */
    public static boolean isKeyword(CharSequence s) {
        return isKeyword(s, latest());
    }

    /**
     * Indique si la séquence est un mot clé ou un littéral pour la version
     * donnée. Les mots clés réservés ({@code const}, {@code goto}) et les
     * littéraux ({@code null}, {@code true}, {@code false}) comptent ; les mots
     * contextuels ({@code record}, {@code sealed}, {@code non-sealed}…) ne sont
     * PAS des mots clés — ils peuvent être des noms.
     *
     * @param s       la séquence à tester.
     * @param version la version de source à appliquer.
     * @return {@code true} si c'est un mot clé ou un littéral.
     */
    public static boolean isKeyword(CharSequence s, SourceVersion version) {
        String mot = s.toString();
        if ("strictfp".equals(mot)) {
            return version.compareTo(RELEASE_2) >= 0;
        }
        if ("assert".equals(mot)) {
            return version.compareTo(RELEASE_4) >= 0;
        }
        if ("enum".equals(mot)) {
            return version.compareTo(RELEASE_5) >= 0;
        }
        if ("_".equals(mot)) {
            return version.compareTo(RELEASE_9) >= 0;
        }
        return MOTS_CLÉS.contains(mot);
    }

    /**
     * Mots clés et littéraux de toutes versions (les mots clés conditionnés par
     * la version sont traités à part dans
     * {@link #isKeyword(CharSequence, SourceVersion)}).
     */
    private static final Set<String> MOTS_CLÉS;

    static {
        Set<String> mots = new HashSet<>();
        Collections.addAll(mots,
                "abstract", "continue", "for", "new", "switch",
                "default", "if", "package", "synchronized",
                "boolean", "do", "private", "this",
                "double", "implements", "protected", "throw",
                "byte", "else", "import", "public", "throws",
                "case", "instanceof", "return", "transient",
                "catch", "extends", "int", "short", "try",
                "char", "final", "interface", "static", "void",
                "class", "finally", "long", "volatile",
                "const", "float", "native", "super", "while",
                // Littéraux : pas des noms valides.
                "null", "true", "false");
        MOTS_CLÉS = Collections.unmodifiableSet(mots);
    }
}
