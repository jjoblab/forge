package jo.forge.app;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Rapport de diagnostic assemblé par la coquille : tout ce qu'il faut pour déboguer un
 * build à distance, en un seul texte copiable ou partageable.
 *
 * <p>Sections : contexte (versions, appareil, projet, résumé), journal du build tel
 * qu'affiché, journal du moteur (stderr capturé par le client — la seule trace qui
 * survit à une mort brutale du processus), écroulements de la coquille (exceptions
 * non attrapées, écrites avant la fermeture du processus — un « force close » y
 * laisse sa pile d'appels), et dernière trace JSON écrite par le moteur si un build
 * s'est terminé.</p>
 *
 * <p>Classe volontairement sans dépendance Android (données passées en texte) :
 * testable sur JVM comme les autres classes pures de la coquille.</p>
 */
public final class RapportDiagnostic {

    /** Taille maximale de la trace incluse (caractères) : le presse-papiers n'est pas un dépôt. */
    public static final int LIMITE_TRACE = 128 * 1024;

    /** Contexte du rapport : versions, appareil, projet, résumé du dernier build. */
    public record Contexte(String versionCoquille, int versionCode, String versionMoteur,
                           Integer codeSortieMoteur, String versionAndroid, int sdk,
                           String abi, long mémoireTotaleMo, String modèle,
                           String cheminProjet, String résuméBuild) {
    }

    /** Dernière trace de build trouvée (nom de fichier et contenu JSON). */
    public record TraceDernière(String nom, String contenu) {
    }

    /** Horodatage lisible du rapport. */
    private static final DateTimeFormatter HORODATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private RapportDiagnostic() {
    }

    /**
     * Assemble le rapport complet.
     *
     * @param contexte      versions, appareil, projet, résumé.
     * @param journalBuild  lignes du journal de build affiché (éventuellement vide).
     * @param journalMoteur lignes de stderr du moteur (éventuellement vide).
     * @param journalCrash  lignes du journal d'écroulements de la coquille
     *                      (éventuellement vide).
     * @param trace         dernière trace JSON du projet (null si aucune).
     * @return le rapport en texte brut.
     */
    public static String composer(Contexte contexte, List<String> journalBuild,
                                  List<String> journalMoteur, List<String> journalCrash,
                                  TraceDernière trace) {
        StringBuilder rapport = new StringBuilder();
        rapport.append("Rapport de diagnostic Forge\n");
        rapport.append("Généré le ").append(LocalDateTime.now().format(HORODATE)).append('\n');
        rapport.append("Coquille : ").append(contexte.versionCoquille())
                .append(" (versionCode ").append(contexte.versionCode()).append(")\n");
        rapport.append("Moteur : ").append(contexte.versionMoteur())
                .append(" — code de sortie : ")
                .append(décrireCodeSortie(contexte.codeSortieMoteur())).append('\n');
        rapport.append("Appareil : ").append(contexte.versionAndroid())
                .append(" (SDK ").append(contexte.sdk()).append("), ")
                .append(contexte.abi()).append(", ")
                .append(contexte.mémoireTotaleMo()).append(" Mo, ")
                .append(contexte.modèle()).append('\n');
        rapport.append("Projet : ").append(contexte.cheminProjet()).append('\n');
        rapport.append("Résumé : ").append(contexte.résuméBuild()).append("\n\n");
        section(rapport, "Journal du build", journalBuild);
        section(rapport, "Journal du moteur (stderr)", journalMoteur);
        section(rapport, "Écroulements de la coquille", journalCrash);
        rapport.append("--- Dernière trace ");
        if (trace == null) {
            rapport.append("---\n(aucune : le build ne s'est pas terminé — "
                    + "voir le journal du moteur)\n");
        } else {
            rapport.append('(').append(trace.nom()).append(") ---\n");
            rapport.append(tronquer(trace.contenu())).append('\n');
        }
        return rapport.toString();
    }

    /**
     * Texte lisible d'un code de sortie de moteur.
     *
     * <p>Conventions Unix mêlées selon la VM : 0 = arrêt propre ; négatif = mort par
     * signal (le tueur de mémoire du système laisse -9) ; 128+n = mort par le signal n
     * (convention shell) ; autre positif = sortie explicite du programme.</p>
     *
     * @param code le code (null tant que le processus vit).
     * @return la description en français.
     */
    public static String décrireCodeSortie(Integer code) {
        if (code == null) {
            return "inconnu (processus encore vivant ou code non encore publié)";
        }
        if (code == 0) {
            return "0 (arrêt propre)";
        }
        if (code < 0) {
            return code + " (tué par le signal " + (-code)
                    + " — souvent le tueur de mémoire du système)";
        }
        if (code > 128 && code < 256) {
            return code + " (mort probable par le signal " + (code - 128) + ")";
        }
        return code + " (sortie explicite du moteur)";
    }

    /**
     * Écrit une section titrée ; vide = « (vide) ».
     */
    private static void section(StringBuilder rapport, String titre, List<String> lignes) {
        rapport.append("--- ").append(titre).append(" ---\n");
        if (lignes == null || lignes.isEmpty()) {
            rapport.append("(vide)\n\n");
            return;
        }
        for (String ligne : lignes) {
            rapport.append(ligne).append('\n');
        }
        rapport.append('\n');
    }

    /**
     * Tronque un contenu long en marquant la coupure.
     */
    private static String tronquer(String contenu) {
        if (contenu.length() <= LIMITE_TRACE) {
            return contenu;
        }
        return contenu.substring(0, LIMITE_TRACE) + "\n… (tronquée à " + LIMITE_TRACE
                + " caractères)";
    }
}
