package jo.forge.engine.diag;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rédaction des secrets dans les messages qui traversent le canal de contrôle
 * (décision D-09) : aucun secret ne doit apparaître dans un journal, un diagnostic
 * ou une trace.
 *
 * <p>Motifs couverts : affectations {@code password=…} / {@code token: …} /
 * {@code api-key: …} (séparateurs {@code =} ou {@code :}, casse indifférente,
 * también mots français usuels), jetons {@code Bearer …}, et longues chaînes
 * hexadécimales ou base64 étiquetées comme clé/jeton. La rédaction se fait au point
 * de sortie du moteur : les messages d'outils sont nettoyés AVANT d'atteindre la
 * coquille ou le disque (trace).</p>
 *
 * <p>Limite assumée (consignée) : la détection est heuristique — un secret porté par
 * un message sans mot étiqueteur passe. La règle forte reste « ne jamais mettre de
 * secret dans une commande d'outil » (le signeur de production exigera un magasin
 * hors journal).</p>
 */
public final class SecretRedactor {

    /** Marque substituée aux secrets. */
    public static final String MARQUE = "[rédigé]";

    /** Affectations étiquetées : mot-clé puis = ou : puis la valeur. */
    private static final Pattern AFFECTATION = Pattern.compile(
            "(?i)\\b(password|passwd|passphrase|motdepasse|mot[-_ ]?de[-_ ]?passe|secret|token|jeton|api[-_]?key|"
                    + "cl[ée]{1,2}(?:[-_ ]secr[èe]te?)?)\\s*[:=]\\s*([^\\s,;)\"]+)");

    /** Jetons Bearer des en-têtes HTTP. */
    private static final Pattern PORTEUR = Pattern.compile(
            "(?i)\\b(bearer)\\s+([^\\s,;)\"]+)");

    /** Longues valeurs hexadécimales ou base64 après un mot de secret. */
    private static final Pattern VALEUR_LONGUE = Pattern.compile(
            "(?i)\\b(secret|secr[èe]te|token|jeton|cl[ée]{1,2})\\s+(\\S{24,})");

    private SecretRedactor() {
    }

    /**
     * Rédige les secrets d'un message.
     *
     * @param message le message d'origine (jamais nul).
     * @return le message nettoyé (même longueur approximative, secrets remplacés).
     */
    public static String rédiger(String message) {
        String résultat = remplacer(AFFECTATION, message);
        résultat = remplacer(PORTEUR, résultat);
        résultat = remplacer(VALEUR_LONGUE, résultat);
        return résultat;
    }

    /**
     * Applique un motif en conservant le mot étiqueteur et en masquant la valeur.
     */
    private static String remplacer(Pattern motif, String texte) {
        Matcher analyse = motif.matcher(texte);
        StringBuilder résultat = new StringBuilder();
        while (analyse.find()) {
            String remplacement = Matcher.quoteReplacement(
                    analyse.group(1) + analyse.group(2).charAt(0)
                            + "…" + MARQUE);
            analyse.appendReplacement(résultat, remplacement);
        }
        analyse.appendTail(résultat);
        return résultat.toString();
    }

    /**
     * Liste des familles de motifs couvertes (documentation et tests).
     *
     * @return les noms des familles.
     */
    public static List<String> familles() {
        return List.of("affectation", "porteur", "valeur-longue");
    }
}
