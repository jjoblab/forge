package jo.forge.engine.server;

import jo.forge.api.BuildEvent;

import java.util.Locale;

/**
 * La console de build du moteur (protocole 4, décision D-21) : LE point unique où
 * se compose le format des lignes de sortie d'un build.
 *
 * <p>Parité assumée avec Gradle : c'est le moteur qui dicte la forme de ses sorties
 * (lignes de tâches à l'issue, verdict décompté, lignes de diagnostics et de
 * téléchargements) et la coquille n'a plus qu'à AFFICHER ce qu'elle reçoit — à le
 * représenter et, si elle le veut, à filtrer encore pour son affichage. Avant, chaque
 * coquille recomposait ces lignes elle-même : deux coquilles = deux consoles qui
 * divergent ; désormais le format vit dans le moteur, comme la sémantique des tâches
 * et l'ordre du canal.</p>
 *
 * <p>Cette classe est aussi l'arbitre du tri par argument ({@code BuildOptions.consoleLevel},
 * l'équivalent des {@code -q}/{@code -i}/{@code -d} de Gradle) : {@link #visible}
 * borne la sévérité minimale des entrées qui partent sur le canal. Le journal borné
 * de la trace, lui, reçoit TOUT (post-mortem complet même sur une console silencieuse
 * — le modèle du scan Gradle).</p>
 *
 * <p>Formats (stables, testés) :</p>
 * <ul>
 *   <li>tâche : {@code > Tâche app:compileJava} — sans badge si elle a travaille,
 *       {@code — À JOUR}, {@code — DU CACHE}, {@code — SAUTÉE}, {@code — ÉCHOUÉE}
 *       sinon (rien au démarrage, comme les consoles classiques) ;</li>
 *   <li>verdict : {@code BUILD RÉUSSI en 12 s — 4 tâches : 3 exécutées, 1 à jour} ou
 *       {@code BUILD ÉCHOUÉ à la tâche fautive — …} ou {@code BUILD ANNULÉ — …} ;</li>
 *   <li>téléchargement fini (INFO) : {@code ↓ ui-android-1.7.1.aar (4,2 Mio)} ;</li>
 *   <li>diagnostic : {@code ⚠ src/Main.java:7 — symbole introuvable}.</li>
 * </ul>
 */
public final class ConsoleDeBuild {

    /** Niveau de console par défaut : la console de build classique. */
    public static final BuildEvent.LogEvent.LogLevel NIVEAU_PAR_DÉFAUT =
            BuildEvent.LogEvent.LogLevel.LIFECYCLE;

    /** Niveau de console demandé par la requête (jamais nul). */
    private final BuildEvent.LogEvent.LogLevel niveauConsole;

    /**
     * Crée la console au niveau demandé.
     *
     * @param niveauConsole sévérité minimale des entrées émises sur le canal
     *                      ({@code null} → {@link #NIVEAU_PAR_DÉFAUT}).
     */
    public ConsoleDeBuild(BuildEvent.LogEvent.LogLevel niveauConsole) {
        this.niveauConsole = niveauConsole == null ? NIVEAU_PAR_DÉFAUT : niveauConsole;
    }

    /**
     * Le niveau de console demandé.
     *
     * @return le niveau (jamais nul).
     */
    public BuildEvent.LogEvent.LogLevel niveau() {
        return niveauConsole;
    }

    /**
     * Une entrée de ce niveau part-elle sur le canal ? (Le journal de trace, lui,
     * reçoit tout : le tri ne prive que la console, jamais le post-mortem.)
     *
     * @param niveau niveau de l'entrée candidate.
     * @return {@code true} si la sévérité atteint celle demandée.
     */
    public boolean visible(BuildEvent.LogEvent.LogLevel niveau) {
        return niveau.sévérité() >= niveauConsole.sévérité();
    }

    /**
     * Compose la ligne d'une tâche à son issue (rien au démarrage : comme les
     * consoles classiques, l'absence de badge signifie « travail fait »).
     *
     * @param tâche l'événement d'état de la tâche.
     * @return la ligne, ou {@code null} si l'état n'a pas de ligne (démarrage).
     */
    public String ligneTâche(BuildEvent.TaskStateEvent tâche) {
        if (tâche.state() == BuildEvent.TaskState.STARTED) {
            return null;
        }
        String badge = badgeÉtat(tâche.state());
        return badge == null
                ? "> Tâche " + tâche.taskName()
                : "> Tâche " + tâche.taskName() + badge;
    }

    /**
     * Compose le verdict final du build.
     *
     * @param fin l'événement de fin (toujours le dernier du build).
     * @return la ligne de verdict.
     */
    public String verdict(BuildEvent.BuildFinished fin) {
        String durée = duréeLisible(fin.durationMs());
        if (fin.success()) {
            return "BUILD RÉUSSI en " + durée + " — " + fin.message();
        }
        if (fin.failedTask() != null) {
            return "BUILD ÉCHOUÉ à la tâche " + fin.failedTask() + " — " + fin.message();
        }
        // Annulation ou échec avant toute tâche : le message dit lequel.
        if (fin.message().contains("annulé")) {
            return "BUILD ANNULÉ — " + fin.message();
        }
        return "BUILD ÉCHOUÉ — " + fin.message();
    }

    /**
     * Compose la ligne humaine d'un diagnostic (l'événement structuré reste le canal
     * de l'éditeur : saut à l'erreur, filtrage par fichier).
     *
     * @param diagnostic l'événement de diagnostic.
     * @return la ligne de console.
     */
    public String ligneDiagnostic(BuildEvent.DiagnosticEvent diagnostic) {
        String position = (diagnostic.file() == null || diagnostic.file().isEmpty()
                ? "?" : diagnostic.file());
        if (diagnostic.line() > 0) {
            position += ":" + diagnostic.line();
        }
        return "⚠ " + position + " — " + diagnostic.message();
    }

    /**
     * Niveau de journal d'un diagnostic : la sévérité structurée devient un niveau
     * de console (INFO reste INFO, WARNING devient WARN, ERROR reste ERROR).
     *
     * @param sévérité la sévérité du diagnostic.
     * @return le niveau de journal correspondant.
     */
    public static BuildEvent.LogEvent.LogLevel niveauDiagnostic(BuildEvent.Severity sévérité) {
        return switch (sévérité) {
            case INFO -> BuildEvent.LogEvent.LogLevel.INFO;
            case WARNING -> BuildEvent.LogEvent.LogLevel.WARN;
            case ERROR -> BuildEvent.LogEvent.LogLevel.ERROR;
        };
    }

    /**
     * Compose la ligne d'un téléchargement terminé (INFO, comme le moteur de
     * référence qui annonce chaque fichier, jamais plus finement en console).
     *
     * @param téléchargement l'événement de fin de téléchargement.
     * @return la ligne de console.
     */
    public String ligneTéléchargement(BuildEvent.DownloadEvent téléchargement) {
        return "↓ " + téléchargement.resource() + " ("
                + tailleLisible(téléchargement.bytesDownloaded()) + ")";
    }

    /**
     * Badge d'issue d'une tâche (null pour les états sans badge : démarrage et
     * exécution réussie — comme les consoles classiques, l'absence de badge est
     * l'information).
     */
    private static String badgeÉtat(BuildEvent.TaskState état) {
        return switch (état) {
            case STARTED, SUCCEEDED -> null;
            case FAILED -> " — ÉCHOUÉE";
            case SKIPPED -> " — SAUTÉE";
            case UP_TO_DATE -> " — À JOUR";
            case FROM_CACHE -> " — DU CACHE";
        };
    }

    /**
     * Durée lisible : secondes sous la minute, sinon minutes et secondes.
     *
     * @param duréeMs durée en millisecondes.
     * @return la forme lisible (ex. {@code 12 s}, {@code 1 min 05 s}).
     */
    private static String duréeLisible(long duréeMs) {
        long secondes = Math.max(1, duréeMs / 1000);
        if (secondes < 60) {
            return secondes + " s";
        }
        return secondes / 60 + " min " + String.format(Locale.ROOT, "%02d", secondes % 60)
                + " s";
    }

    /**
     * Taille lisible : octets, kio, Mio (une décimale à partir du Mio).
     *
     * @param octets la taille en octets.
     * @return la forme lisible (ex. {@code 512 o}, {@code 64 Kio}, {@code 4,2 Mio}).
     */
    private static String tailleLisible(long octets) {
        if (octets < 1024) {
            return octets + " o";
        }
        if (octets < 1024L * 1024) {
            return octets / 1024 + " Kio";
        }
        return String.format(Locale.ROOT, "%.1f", octets / (1024.0 * 1024.0))
                .replace('.', ',') + " Mio";
    }
}
