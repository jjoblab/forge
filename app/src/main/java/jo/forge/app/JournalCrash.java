package jo.forge.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Journal des écroulements de la coquille : l'exception non attrapée qui ferme
 * l'application (« force close ») est écrite dans un fichier borné du stockage
 * privé AVANT que le système ne tue le processus.
 *
 * <p>Pourquoi : un « force close » ne dit rien à l'utilisateur — ni la classe
 * fautive, ni la ligne. Sans ce journal, le seul recours est adb logcat, absent
 * sur un appareil de poche. Avec lui, le rapport copiable (« Copier les
 * journaux ») contient la pile d'appels exacte du dernier écroulement.</p>
 *
 * <p>Le gestionnaire délègue toujours au gestionnaire précédent : la boîte de
 * dialogue système « l'application s'est arrêtée » reste affichée, inchangée.
 * L'écriture est un meilleur effort : si elle échoue (disque plein, répertoire
 * illisible), l'écroulement suit son cours normal sans boucle — d'où la garde
 * anti-récursion : un crash pendant l'écriture du journal ne doit pas
 * re-déclencher le journal.</p>
 *
 * <p>Classe volontairement sans dépendance Android (répertoire passé en
 * paramètre) : testable sur JVM comme les autres classes pures de la coquille.</p>
 */
public final class JournalCrash {

    /** Nom du fichier journal, sous le répertoire privé de la coquille. */
    public static final String NOM_FICHIER = "journal-crash.txt";

    /** Taille maximale du fichier (octets) : au-delà, le début est supprimé —
     * les écroulements récents (la fin) sont ceux qui intéressent le débogage. */
    public static final int LIMITE_OCTETS = 64 * 1024;

    /** Profondeur maximale de la chaîne de causes parcourue. */
    private static final int PROFONDEUR_CAUSES = 8;

    /** Journal installé pour ce processus (installation unique, idempotente). */
    private static JournalCrash installé;

    private static final DateTimeFormatter HORODATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final Path fichier;

    /** Gestionnaire précédent, rappelé après l'écriture (peut être null). */
    Thread.UncaughtExceptionHandler précédent;

    /** Garde anti-récursion : vraie pendant l'écriture d'un écroulement. */
    private boolean enCours;

    /** Gestionnaire installé : enregistre puis délègue toujours. */
    final Thread.UncaughtExceptionHandler gestionnaire = (fil, échec) -> {
        enregistrer(fil, échec);
        Thread.UncaughtExceptionHandler avant = précédent;
        if (avant != null) {
            avant.uncaughtException(fil, échec);
        }
    };

    /** Construction directe (tests du même paquet) ; l'installation passe par
     * {@link #installer(Path)}. */
    JournalCrash(Path fichier) {
        this.fichier = fichier;
    }

    /**
     * Installe le journal du processus (une seule fois) : exceptions non
     * attrapées de tous les fils écrites sous {@code répertoire}.
     *
     * <p>À appeler le plus tôt possible de l'ouverture — avant le gonflement de
     * l'interface : la v0.2.2 a planté à l'ouverture sans laisser de trace,
     * faute d'avoir installé ce journal avant le branchement des vues.</p>
     *
     * @param répertoire stockage privé de la coquille (getFilesDir()).
     * @return le journal du processus (le même à chaque appel).
     */
    public static synchronized JournalCrash installer(Path répertoire) {
        if (installé == null) {
            JournalCrash journal = new JournalCrash(répertoire.resolve(NOM_FICHIER));
            journal.précédent = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler(journal.gestionnaire);
            installé = journal;
        }
        return installé;
    }

    /**
     * Écrit un écroulement (horodatage, fil, exception, pile, causes) puis
     * borne le fichier. Ne lève jamais : la panne éventuelle du journal ne doit
     * pas masquer l'écroulement d'origine.
     *
     * @param fil   le fil mort.
     * @param échec l'exception non attrapée.
     */
    public void enregistrer(Thread fil, Throwable échec) {
        synchronized (this) {
            if (enCours) {
                return; // écroulement PENDANT l'écriture : on ne boucle pas.
            }
            enCours = true;
        }
        try {
            Files.createDirectories(fichier.getParent());
            // Files.writeString n'existe pas avant l'API 35 : la coquille promet
            // minSdk 28, donc écriture d'octets — disponible depuis l'API 26.
            Files.write(fichier, entrée(fil, échec).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            borner();
        } catch (Exception échecÉcriture) {
            // Meilleur effort assumé : le processus meurt de toute façon.
        } finally {
            synchronized (this) {
                enCours = false;
            }
        }
    }

    /**
     * Contenu du journal (vide si aucun écroulement ou fichier illisible) —
     * section « Écroulements de la coquille » du rapport de diagnostic.
     *
     * @return le texte du journal, jamais null.
     */
    public String copie() {
        try {
            // Files.readString : API 35 — remplacé par la lecture d'octets (API 26).
            return new String(Files.readAllBytes(fichier), StandardCharsets.UTF_8);
        } catch (Exception absent) {
            return "";
        }
    }

    /** Fichier du journal (test). */
    Path fichier() {
        return fichier;
    }

    /**
     * Texte d'une entrée : exception, pile, puis chaîne de causes.
     */
    private static String entrée(Thread fil, Throwable échec) {
        StringBuilder texte = new StringBuilder();
        texte.append("\n=== ").append(LocalDateTime.now().format(HORODATE))
                .append(" — fil « ").append(fil.getName()).append(" » ===\n");
        empiler(texte, échec);
        Throwable cause = échec.getCause();
        int profondeur = 0;
        while (cause != null && profondeur++ < PROFONDEUR_CAUSES) {
            texte.append("causée par : ").append(cause).append('\n');
            empiler(texte, cause);
            cause = cause.getCause();
        }
        return texte.toString();
    }

    private static void empiler(StringBuilder texte, Throwable échec) {
        texte.append(échec).append('\n');
        for (StackTraceElement cadre : échec.getStackTrace()) {
            texte.append("    à ").append(cadre).append('\n');
        }
    }

    /**
     * Borne le fichier : au-delà de {@link #LIMITE_OCTETS}, le début est
     * supprimé (la moitié de la limite est conservée, coupée au premier
     * retour à la ligne pour garder des entrées entières).
     */
    private void borner() throws IOException {
        if (Files.size(fichier) <= LIMITE_OCTETS) {
            return;
        }
        String contenu = new String(Files.readAllBytes(fichier),
                StandardCharsets.UTF_8);
        int coupe = Math.max(0, contenu.length() - LIMITE_OCTETS / 2);
        int début = contenu.indexOf('\n', coupe);
        String garde = début >= 0 ? contenu.substring(début + 1) : contenu;
        Files.write(fichier,
                ("… (journal borné à " + LIMITE_OCTETS + " octets : "
                        + "le début a été supprimé)\n" + garde)
                        .getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }
}
