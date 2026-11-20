package jo.forge.engine.diag;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parseur générique de sortie d'outils de compilation (parité de grammaire avec le
 * moteur de référence, entrées du pipeline de diagnostics) : la forme
 * {@code chemin:ligne[:colonne]: (erreur|error|avertissement|warning): message}
 * utilisée par javac, kotlinc, aapt2 et la plupart des outils GNU.
 *
 * <p>Usage : quand un outil n'a pas d'API de diagnostics, on lui demande SA sortie et
 * on la traduit ici — jamais l'inverse (principe : préférer l'API, REF-053). Les
 * lignes non localisées contenant « error » deviennent des diagnostics globaux ; le
 * reste est considéré comme du bruit de progression et ignoré.</p>
 */
public final class ParseurSortieOutils {

    /** Forme GNU : chemin:ligne[:colonne]: sévérité: message. */
    private static final Pattern LIGNE = Pattern.compile(
            "^(.+?):(\\d+)(?::(\\d+))?(?:-\\d+(?::\\d+)?)?:\\s*"
                    + "(erreur|error|avertissement|warning)\\s*:\\s*(.*)$");

    /** Outil émetteur par défaut quand l'appelant n'en précise pas. */
    private static final String OUTIL_PAR_DÉFAUT = "outil";

    private ParseurSortieOutils() {
    }

    /**
     * Analyse une sortie complète (une ligne par entrée).
     *
     * @param sortie les lignes de sortie de l'outil.
     * @param outil  nom de l'outil émetteur (ex. {@code kotlinc}).
     * @return les diagnostics reconnus (localisés et globaux), éventuellement vide.
     */
    public static List<Diagnostic> analyser(List<String> sortie, String outil) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (String ligne : sortie) {
            Diagnostic diagnostic = analyserLigne(ligne, outil);
            if (diagnostic != null) {
                diagnostics.add(diagnostic);
            }
        }
        return diagnostics;
    }

    /**
     * Analyse une ligne.
     *
     * @param ligne la ligne brute.
     * @param outil l'outil émetteur.
     * @return le diagnostic reconnu, ou {@code null} (bruit).
     */
    public static Diagnostic analyserLigne(String ligne, String outil) {
        if (ligne == null || ligne.isBlank()) {
            return null;
        }
        String outilRéel = outil == null ? OUTIL_PAR_DÉFAUT : outil;
        Matcher forme = LIGNE.matcher(ligne.trim());
        if (forme.matches()) {
            String sévérité = forme.group(4).toLowerCase();
            return new Diagnostic(
                    sévérité.startsWith("err") || sévérité.startsWith("err")
                            ? jo.forge.api.BuildEvent.Severity.ERROR
                            : jo.forge.api.BuildEvent.Severity.WARNING,
                    forme.group(1),
                    Integer.parseInt(forme.group(2)),
                    forme.group(3) == null ? 0 : Integer.parseInt(forme.group(3)),
                    outilRéel,
                    forme.group(5),
                    outilRéel);
        }
        if (ligne.contains("error") || ligne.contains("erreur")) {
            return Diagnostic.error(outilRéel, ligne.trim());
        }
        return null;
    }
}
