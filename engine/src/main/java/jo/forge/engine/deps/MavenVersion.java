package jo.forge.engine.deps;

/**
 * Ordre de versions aligné sur la sémantique Maven (ComparableVersion), testé sur la
 * suite des cas connus.
 *
 * <p>Règles : découpage en jetons numériques et qualificatifs (séparateurs
 * {@code . - _ +}) ; un numéro présent domine un qualificatif ({@code 1.0.1 >
 * 1.0.1-rc}) ; un jeton ABSENT vaut 0 face à un numéro ({@code 1.0 == 1.0.0}) et
 * le qualificatif vide face à un qualificatif ({@code 1.0-rc < 1.0 < 1.0-sp <
 * 1.0-xyz}) ; les alias se ramènent à leur forme canonique ({@code a ≡ alpha},
 * {@code b ≡ beta}, {@code m ≡ milestone}, {@code cr ≡ rc},
 * {@code ga/final/release ≡ publication}) ; les qualificatifs connus suivent
 * l'ordre Maven {@code alpha < beta < milestone < rc < snapshot < (publication) < sp}
 * et un qualificatif INCONNU se classe après la publication (bizarrerie Maven
 * assumée), entre lui-même par ordre lexicographique.</p>
 *
 * <p>Écart avec le moteur de référence assumé et voulu : son comparateur diverge de
 * Maven sur plusieurs de ces cas (voir le registre des références, entrée du
 * résolveur) ; le nôtre vise la compatibilité Maven, car c'est ce que les POM du
 * monde réel supposent.</p>
 */
public final class MavenVersion implements java.util.Comparator<String> {

    /** Instance partagée. */
    public static final MavenVersion INSTANCE = new MavenVersion();

    private MavenVersion() {
    }

    /**
     * Compare deux versions.
     *
     * @param a première version.
     * @param b seconde version.
     * @return négatif, zéro ou positif selon l'ordre.
     */
    @Override
    public int compare(String a, String b) {
        Jeton[] ta = découper(a);
        Jeton[] tb = découper(b);
        int n = Math.max(ta.length, tb.length);
        for (int i = 0; i < n; i++) {
            Jeton x = i < ta.length ? ta[i] : null;
            Jeton y = i < tb.length ? tb[i] : null;
            int c = comparer(x, y);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    /**
     * Compare deux jetons, dont l'un peut être ABSENT (liste épuisée) : l'absent
     * vaut 0 face à un nombre, le qualificatif vide face à un qualificatif.
     */
    private static int comparer(Jeton a, Jeton b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            if (b instanceof Nombre nombre) {
                return Long.compare(0, nombre.valeur());
            }
            return Qualificatif.VIDE.compareTo((Qualificatif) b);
        }
        if (b == null) {
            if (a instanceof Nombre nombre) {
                return Long.compare(nombre.valeur(), 0);
            }
            return ((Qualificatif) a).compareTo(Qualificatif.VIDE);
        }
        return a.compareTo(b);
    }

    /**
     * La plus récente d'un ensemble de versions.
     *
     * @param versions les versions candidates.
     * @return la plus récente, ou {@code null} si vide.
     */
    public static String plusRécente(java.util.Collection<String> versions) {
        String meilleure = null;
        for (String version : versions) {
            if (meilleure == null || INSTANCE.compare(version, meilleure) > 0) {
                meilleure = version;
            }
        }
        return meilleure;
    }

    /** Un jeton de version : nombre ou qualificatif. */
    private sealed interface Jeton permits Nombre, Qualificatif {

        int compareTo(Jeton autre);
    }

    /** Jeton numérique. */
    private record Nombre(long valeur) implements Jeton {

        @Override
        public int compareTo(Jeton autre) {
            if (autre instanceof Nombre nombre) {
                return Long.compare(valeur, nombre.valeur);
            }
            return 1; // un nombre domine un qualificatif : 1.0 > 1.0-rc
        }
    }

    /** Jeton qualificatif (alpha, rc, sp, xyz…). */
    private record Qualificatif(String texte) implements Jeton {

        /** Le qualificatif vide (valeur d'un jeton absent face à un qualificatif). */
        static final Qualificatif VIDE = new Qualificatif("");

        @Override
        public int compareTo(Jeton autre) {
            if (autre instanceof Nombre) {
                return -1;
            }
            Qualificatif autreQual = (Qualificatif) autre;
            int ra = rang(canonical(texte));
            int rb = rang(canonical(autreQual.texte));
            if (ra != rb) {
                return Integer.compare(ra, rb);
            }
            return canonical(texte).compareTo(canonical(autreQual.texte));
        }

        /** Alias Maven ramenés à leur forme canonique. */
        private static String canonical(String texte) {
            return switch (texte.toLowerCase(java.util.Locale.ROOT)) {
                case "a" -> "alpha";
                case "b" -> "beta";
                case "m" -> "milestone";
                case "cr" -> "rc";
                case "ga", "final", "release" -> "";
                default -> texte.toLowerCase(java.util.Locale.ROOT);
            };
        }

        /** Rang Maven d'un qualificatif canonique ; l'inconnu après la publication. */
        private static int rang(String canonique) {
            return switch (canonique) {
                case "alpha" -> 0;
                case "beta" -> 1;
                case "milestone" -> 2;
                case "rc" -> 3;
                case "snapshot" -> 4;
                case "" -> 5;
                case "sp" -> 6;
                default -> 7;
            };
        }
    }

    /**
     * Découpe une version en jetons (nombres et qualificatifs alternés).
     */
    private static Jeton[] découper(String version) {
        java.util.List<Jeton> jetons = new java.util.ArrayList<>();
        StringBuilder tampon = new StringBuilder();
        Boolean chiffres = null;
        int longueur = version.length();
        for (int i = 0; i < longueur; i++) {
            char caractère = version.charAt(i);
            if (caractère == '.' || caractère == '-' || caractère == '_'
                    || caractère == '+') {
                vider(tampon, chiffres, jetons);
                chiffres = null;
            } else if (Character.isDigit(caractère)) {
                if (Boolean.FALSE.equals(chiffres)) {
                    vider(tampon, chiffres, jetons);
                }
                tampon.append(caractère);
                chiffres = true;
            } else {
                if (Boolean.TRUE.equals(chiffres)) {
                    vider(tampon, chiffres, jetons);
                }
                tampon.append(caractère);
                chiffres = false;
            }
        }
        vider(tampon, chiffres, jetons);
        return jetons.toArray(new Jeton[0]);
    }

    /** Vide le tampon dans les jetons selon son type. */
    private static void vider(StringBuilder tampon, Boolean chiffres,
                              java.util.List<Jeton> jetons) {
        if (tampon.isEmpty()) {
            return;
        }
        String texte = tampon.toString();
        if (Boolean.TRUE.equals(chiffres)) {
            try {
                jetons.add(new Nombre(Long.parseLong(texte)));
            } catch (NumberFormatException tropGrand) {
                jetons.add(new Qualificatif(texte));
            }
        } else {
            jetons.add(new Qualificatif(texte));
        }
        tampon.setLength(0);
    }
}
