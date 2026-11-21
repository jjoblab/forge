package jo.forge.engine.diag;

import org.junit.jupiter.api.Test;

import java.util.regex.PatternSyntaxException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests de {@link MessagesErreursGraves} — causes racines DÉPLIÉES (v0.2.19,
 * rapport appareil v0.2.18 : un {@code ExceptionInInitializerError} au
 * message vide sur ART cachait une {@code PatternSyntaxException} qui nommait
 * le motif fautif ; le rapport ne disait que « signalez le problème »).
 */
class MessagesErreursGravesTest {

    /** Porteuse d'un initialiseur statique qui lève : la machine la
     * refile en {@code ExceptionInInitializerError} au premier toucher. */
    private static final class BombeStatique {
        static {
            if (true) { // Garde anti-« code inatteignable » du compilateur.
                throw new PatternSyntaxException(
                        "Syntax error in regexp pattern near index 21",
                        "\\$\\{([^}]+)}", 21);
            }
        }
    }

    @Test
    void laCauseRacineDUnInitialiseurStatiqueEstDépliée() {
        ExceptionInInitializerError erreur;
        try {
            // Premier toucher : l'initialiseur lève, la machine enveloppe.
            Class.forName(BombeStatique.class.getName());
            throw new AssertionError("l'initialiseur devait lever");
        } catch (ClassNotFoundException introuvable) {
            throw new AssertionError("classe de test introuvable", introuvable);
        } catch (ExceptionInInitializerError enveloppe) {
            erreur = enveloppe;
        }
        String message = MessagesErreursGraves.pour(erreur, "app:processResources");
        assertTrue(message.contains("Erreur grave dans app:processResources"),
                message);
        assertTrue(message.contains("cause racine java.util.regex.PatternSyntaxException"),
                "la cause racine doit être nommée : " + message);
        assertTrue(message.contains("Syntax error in regexp pattern"),
                "le message de la cause racine doit être porté : " + message);
        assertTrue(message.contains("levée à"),
                "la trame du moteur doit être portée : " + message);
        assertTrue(message.contains("BombeStatique"),
                "la trame doit nommer le coupable : " + message);
    }

    @Test
    void laCauseRacineCherchéeEnProfondeur() {
        // Une erreur enveloppant une cause enveloppant la racine : c'est la
        // RACINE qui doit parler, pas l'étage intermédiaire.
        NoClassDefFoundError racine = new NoClassDefFoundError("java/lang/ProcessHandle");
        IllegalStateException étage = new IllegalStateException("étage", racine);
        ExceptionInInitializerError erreur = new ExceptionInInitializerError(étage);
        String message = MessagesErreursGraves.pour(erreur, "app:resolveDependencies");
        assertTrue(message.contains("cause racine java.lang.NoClassDefFoundError"),
                message);
        assertTrue(message.contains("java/lang/ProcessHandle"), message);
        assertFalse(message.contains("étage"),
                "l'étage intermédiaire n'écrase pas la racine : " + message);
    }

    @Test
    void unLinkageSansCauseGardeLeMessageDeFamille() {
        NoClassDefFoundError erreur = new NoClassDefFoundError("javax/xml/stream/XMLEventFactory");
        String message = MessagesErreursGraves.pour(erreur, "core:compileJava");
        assertTrue(message.contains("classe ou membre manquant"), message);
        assertTrue(message.contains("assistant d'installation"), message);
        assertFalse(message.contains("cause racine"),
                "sans cause, pas de section racine : " + message);
    }

    @Test
    void laMémoireResteLaMémoire() {
        String message = MessagesErreursGraves.pour(
                new OutOfMemoryError("Java heap space"), "app:dex");
        assertTrue(message.contains("mémoire insuffisante"), message);
        assertTrue(message.contains("mode basse mémoire"), message);
    }

    @Test
    void leMessageRacineEstPlafonné() {
        String longTexte = "x".repeat(5000);
        String message = MessagesErreursGraves.pour(
                new ExceptionInInitializerError(new IllegalStateException(longTexte)),
                "app:dex");
        assertTrue(message.length() < 1000,
                "un message racine plafonné, pas un journal noyé : " + message.length());
        assertTrue(message.endsWith("…") || !message.contains(longTexte));
    }
}
