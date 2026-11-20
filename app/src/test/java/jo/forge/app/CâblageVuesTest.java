package jo.forge.app;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Garde anti-régression du câblage des vues de l'écran principal, établie après
 * la v0.2.2 : un bouton de journal y était branché (setOnClickListener) sans
 * jamais être assigné par findViewById — NullPointerException à l'ouverture,
 * « force close » immédiat sur appareil, invisible des tests JVM existants.
 *
 * <p>Ce test lit les sources comme du texte (la coquille n'a pas de tests
 * d'instrumentation) et échoue AVANT l'installation sur appareil :</p>
 * <ul>
 *   <li>chaque champ de vue déclaré dans MainActivity doit être assigné par
 *       {@code findViewById} ;</li>
 *   <li>chaque identifiant {@code R.id.…} référencé doit exister dans la
 *       disposition {@code activity_main.xml} ;</li>
 *   <li>chaque bouton qui reçoit un écouteur doit être un champ câblé.</li>
 * </ul>
 */
class CâblageVuesTest {

    /** Répertoires candidats : les tests de module tournent depuis app/. */
    private static final String[] CHEMINS_SOURCES = {
            "src/main/java/jo/forge/app/MainActivity.java",
            "app/src/main/java/jo/forge/app/MainActivity.java"};

    private static final String[] CHEMINS_DISPOSITION = {
            "src/main/res/layout/activity_main.xml",
            "app/src/main/res/layout/activity_main.xml"};

    /** Types de vues dont les champs doivent passer par findViewById. */
    private static final Pattern DÉCLARATION_VUE = Pattern.compile(
            "private\\s+(?:final\\s+)?(?:TextView|Button|EditText|ProgressBar|View)\\s+"
                    + "([\\w]+)\\s*(?:=[^;]*)?;",
            Pattern.UNICODE_CHARACTER_CLASS);

    private static String lireSource(String[] chemins, String description) {
        for (String chemin : chemins) {
            try {
                Path fichier = Path.of(chemin);
                if (Files.isRegularFile(fichier)) {
                    // readAllBytes (API 26) : readString est absent d'android.jar,
                    // et les tests de la coquille compilent contre lui aussi.
                    return new String(Files.readAllBytes(fichier),
                            StandardCharsets.UTF_8);
                }
            } catch (Exception échecLecture) {
                fail("Lecture impossible de " + chemin + " : " + échecLecture);
            }
        }
        fail(description + " introuvable (chemins essayés : "
                + String.join(", ", chemins) + ") — la garde ne doit jamais être ignorée");
        return null; // jamais atteint (fail lève)
    }

    @Test
    void chaqueChampDeVueEstAssignéParFindViewById() {
        String source = lireSource(CHEMINS_SOURCES, "la source de MainActivity");
        Map<String, Boolean> assignations = new LinkedHashMap<>();
        Matcher déclarations = DÉCLARATION_VUE.matcher(source);
        while (déclarations.find()) {
            String champ = déclarations.group(1);
            Pattern assignation = Pattern.compile(Pattern.quote(champ)
                    + "\\s*=\\s*findViewById\\s*\\(");
            assignations.put(champ, assignation.matcher(source).find());
        }
        // Le test ne doit pas tourner à vide : l'écran principal a de nombreuses vues.
        assertTrue(assignations.size() >= 15,
                "champs de vues détectés : " + assignations.size()
                        + " — le motif de détection est-il cassé ?");

        String nonCâblés = assignations.entrySet().stream()
                .filter(e -> !e.getValue())
                .map(Map.Entry::getKey)
                .collect(Collectors.joining(", "));
        assertTrue(nonCâblés.isEmpty(),
                "Champs déclarés mais jamais assignés par findViewById ( NullPointerException "
                        + "garantie à l'ouverture) : " + nonCâblés);
    }

    @Test
    void chaqueIdentifiantRéférencéExisteDansLaDisposition() {
        String source = lireSource(CHEMINS_SOURCES, "la source de MainActivity");
        String disposition = lireSource(CHEMINS_DISPOSITION,
                "la disposition activity_main.xml");

        TreeSet<String> référencés = new TreeSet<>();
        Matcher utilisation = Pattern.compile("R\\.id\\.(\\w+)").matcher(source);
        while (utilisation.find()) {
            référencés.add(utilisation.group(1));
        }
        TreeSet<String> déclarés = new TreeSet<>();
        Matcher déclaration = Pattern.compile("@\\+id/(\\w+)").matcher(disposition);
        while (déclaration.find()) {
            déclarés.add(déclaration.group(1));
        }

        assertTrue(référencés.size() >= 15,
                "identifiants R.id détectés : " + référencés.size()
                        + " — le motif est-il cassé ?");
        référencés.removeAll(déclarés);
        assertTrue(référencés.isEmpty(),
                "R.id référencés dans MainActivity mais absents de activity_main.xml : "
                        + String.join(", ", référencés));
    }

    @Test
    void chaqueBoutonAvecÉcouteurEstUnChampCâblé() {
        String source = lireSource(CHEMINS_SOURCES, "la source de MainActivity");
        // Toute la disposition des vues vit dans onCreate : un écouteur posé hors
        // de cette méthode tournerait avant l'assignation (ou sur un champ local).
        int débutCorps = source.indexOf("protected void onCreate");
        int finCorps = source.indexOf("protected void onResume");
        assertTrue(débutCorps > 0 && finCorps > débutCorps,
                "méthodes onCreate/onResume introuvables — signature changée ?");
        String onCreate = source.substring(débutCorps, finCorps);

        // Les noms de champs sont français : é et compagnie comptent comme lettres.
        Matcher écouteurs = Pattern.compile("(\\w+)\\.setOnClickListener",
                Pattern.UNICODE_CHARACTER_CLASS).matcher(onCreate);
        int câblés = 0;
        while (écouteurs.find()) {
            String bouton = écouteurs.group(1);
            Pattern champAssigné = Pattern.compile(Pattern.quote(bouton)
                    + "\\s*=\\s*findViewById\\s*\\(");
            assertTrue(champAssigné.matcher(source).find(),
                    "« " + bouton + ".setOnClickListener » posé sur un champ jamais "
                            + "assigné par findViewById — plantage à l'ouverture");
            câblés++;
        }
        // Garde de non-vacuité : l'écran a au moins une dizaine d'écouteurs.
        assertTrue(câblés >= 10, "écouteurs détectés : " + câblés);
    }
}
