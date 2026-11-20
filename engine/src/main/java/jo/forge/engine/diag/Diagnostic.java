package jo.forge.engine.diag;

import jo.forge.api.BuildEvent;

/**
 * Diagnostic structuré du moteur : position exacte dans un fichier, sévérité, code
 * machine, outil émetteur.
 *
 * <p>C'est la forme interne produite par les tâches (compilateur Java, {@code aapt2},
 * dexeur…) et consommée par le serveur de build, qui la convertit en
 * {@code BuildEvent.DiagnosticEvent} sur le canal de contrôle. L'éditeur de la coquille
 * peut sauter directement à l'erreur : c'est la raison d'être de cette structure (parité
 * de principe avec le format d'événements du contrat {@code api/}).</p>
 *
 * <p>Principe : quand un outil offre une API de diagnostics, on lui demande — on ne
 * parse pas sa sortie texte (locale-dépendante). Le parseur de secours texte existe
 * pour les outils sans API (étape 11).</p>
 *
 * @param severity sévérité du diagnostic.
 * @param file     fichier concerné (chemin relatif au projet), jamais nul.
 * @param line     ligne (1-based, 0 si inconnue).
 * @param column   colonne (1-based, 0 si inconnue).
 * @param code     code machine stable de l'outil (ex. {@code ecj:16777218}), peut être
 *                 nul.
 * @param message  message humain, jamais nul.
 * @param tool     outil émetteur (ex. {@code java-compiler}), jamais nul.
 */
public record Diagnostic(BuildEvent.Severity severity, String file, int line, int column,
                         String code, String message, String tool) {

    /**
     * Valide le diagnostic.
     *
     * @throws NullPointerException si un champ obligatoire est nul.
     */
    public Diagnostic {
        java.util.Objects.requireNonNull(severity, "severity");
        java.util.Objects.requireNonNull(file, "file");
        java.util.Objects.requireNonNull(message, "message");
        java.util.Objects.requireNonNull(tool, "tool");
    }

    /**
     * Diagnostic d'erreur non localisé (fichier vide, ligne et colonne 0).
     *
     * @param tool    outil émetteur.
     * @param message message.
     * @return le diagnostic.
     */
    public static Diagnostic error(String tool, String message) {
        return new Diagnostic(BuildEvent.Severity.ERROR, "", 0, 0, null, message, tool);
    }

    /**
     * Représentation courte pour le journal (une ligne).
     *
     * @return la forme texte.
     */
    public String toLogLine() {
        StringBuilder ligne = new StringBuilder();
        if (!file.isEmpty()) {
            ligne.append(file);
            if (line > 0) {
                ligne.append(':').append(line);
                if (column > 0) {
                    ligne.append(':').append(column);
                }
            }
            ligne.append(" : ");
        }
        return ligne.append(message).toString();
    }
}
