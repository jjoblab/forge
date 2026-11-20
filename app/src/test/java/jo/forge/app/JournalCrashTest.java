package jo.forge.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests du journal d'écroulements de la coquille : un « force close » (exception
 * non attrapée) doit laisser une pile d'appels lisible dans un fichier borné,
 * sans jamais boucler ni masquer l'écroulement d'origine.
 */
class JournalCrashTest {

    @TempDir
    Path temporaire;

    /** Gestionnaire précédent factice : le journal doit lui déléguer TOUJOURS. */
    private static final class Capteur implements Thread.UncaughtExceptionHandler {
        final AtomicBoolean appelé = new AtomicBoolean();
        Throwable dernière;

        @Override
        public void uncaughtException(Thread fil, Throwable échec) {
            appelé.set(true);
            dernière = échec;
        }
    }

    @Test
    void écroulementÉcritPileEtDélègueAuGestionnairePrécédent() {
        JournalCrash journal = new JournalCrash(temporaire.resolve("crash.txt"));
        Capteur capteur = new Capteur();
        journal.précédent = capteur;

        IllegalStateException échec = new IllegalStateException("boum à l'ouverture");
        journal.gestionnaire.uncaughtException(Thread.currentThread(), échec);

        assertTrue(capteur.appelé.get(), "le gestionnaire précédent doit être rappelé");
        assertEquals(échec, capteur.dernière);
        String écrit = journal.copie();
        assertTrue(écrit.contains("IllegalStateException: boum à l'ouverture"));
        assertTrue(écrit.contains("fil « " + Thread.currentThread().getName() + " »"));
        assertTrue(écrit.contains("à jo.forge.app.JournalCrashTest"),
                "la pile d'appels doit être écrite");
    }

    @Test
    void chaîneDeCausesÉcrite() {
        JournalCrash journal = new JournalCrash(temporaire.resolve("crash.txt"));
        journal.précédent = new Capteur();
        Exception racine = new IllegalArgumentException("cause racine");
        Exception enveloppe = new IllegalStateException("enveloppe", racine);

        journal.gestionnaire.uncaughtException(Thread.currentThread(), enveloppe);

        String écrit = journal.copie();
        assertTrue(écrit.contains("enveloppe"));
        assertTrue(écrit.contains("causée par : java.lang.IllegalArgumentException: cause racine"),
                "la cause doit suivre l'exception");
    }

    @Test
    void bornageLeFichierEnGardantLesÉcroulementsRécents() throws Exception {
        JournalCrash journal = new JournalCrash(temporaire.resolve("crash.txt"));
        journal.précédent = new Capteur();
        Path fichier = journal.fichier();
        Files.createDirectories(temporaire);

        journal.gestionnaire.uncaughtException(Thread.currentThread(),
                new IllegalStateException("premier"));
        // Bourrage artificiel : le journal réel grossit par écroulements successifs.
        Files.write(fichier, "x".repeat(JournalCrash.LIMITE_OCTETS + 4096)
                        .getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.APPEND);
        journal.gestionnaire.uncaughtException(Thread.currentThread(),
                new IllegalStateException("dernier"));

        assertTrue(Files.size(fichier) <= JournalCrash.LIMITE_OCTETS + 4096,
                "le fichier borné ne doit pas dépasser la limite (marge : une entrée)");
        assertTrue(Files.size(fichier) < JournalCrash.LIMITE_OCTETS / 2 + 8192,
                "le bourrage initial doit avoir été supprimé");
        String écrit = journal.copie();
        assertTrue(écrit.contains("journal borné"), "la coupure doit être marquée");
        assertFalse(écrit.contains("premier"), "l'ancien écroulement est sorti du journal");
        assertTrue(écrit.contains("dernier"), "l'écroulement récent est conservé");
    }

    @Test
    void échecDÉcritureSansBoucleEtDélégationPréservée() {
        // Répertoire impossible : un fichier fait office de répertoire parent.
        JournalCrash journal = new JournalCrash(
                temporaire.resolve("bloqué").resolve("crash.txt"));
        // « bloqué » n'existe pas encore comme FICHIER seulement après écriture :
        // on le crée comme fichier pour rendre createDirectories impossible.
        try {
            Files.write(temporaire.resolve("bloqué"), new byte[0]);
        } catch (Exception ignorée) {
            // Si la création échoue, le test garde son sens : écrire dessous échoue.
        }
        Capteur capteur = new Capteur();
        journal.précédent = capteur;

        journal.gestionnaire.uncaughtException(Thread.currentThread(),
                new IllegalStateException("aucune écriture possible"));
        // Aucune exception propagée, et la délégation a quand même eu lieu.
        assertTrue(capteur.appelé.get(),
                "la panne du journal ne doit pas masquer la délégation");
        assertEquals("", journal.copie(), "rien n'a pu être écrit");
    }

    @Test
    void copieVideQuandAucunÉcroulement() {
        JournalCrash journal = new JournalCrash(temporaire.resolve("crash.txt"));
        assertEquals("", journal.copie());
    }
}
