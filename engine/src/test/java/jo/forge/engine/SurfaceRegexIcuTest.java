package jo.forge.engine;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audit des REGEX du moteur contre la sémantique d'ICU (correctif v0.2.19).
 *
 * <p><b>Constat à l'origine</b> (rapport appareil v0.2.18, régression) :
 * {@code FusionManifestes} compilait {@code "\$\{([^}]+)}"} — l'accolade
 * fermante finale NON ÉCHAPPÉE. Les tests JVM passaient : OpenJDK tient
 * l'accolade orpheline hors classe pour un LITTÉRAL. Mais ART compile ses
 * expressions rationnelles par ICU NATIF
 * ({@code PatternNative.create} — la façade {@code java.util.regex} d'Android
 * délègue la compilation à ICU4C), et ICU la REFUSE : dans sa table d'états
 * (regexcst.txt), l'état {@code term} n'a aucune transition pour une accolade
 * fermante isolée — l'ensemble des caractères littéraux est le COMPLÉMENT de
 * {@code *?+[()&#123;&#125;^$|&#92;.} (regexst.cpp) — et l'accolade retombe dans
 * {@code default → errorDeath doRuleError} ({@code U_REGEX_RULE_SYNTAX}).
 * Résultat sur appareil : {@code PatternSyntaxException} dans l'initialiseur
 * statique, donc {@code ExceptionInInitializerError} au premier chargement de
 * la classe — {@code processResources} mourait sans un mot après « ressources
 * compilées », sur les trois exemples.</p>
 *
 * <p><b>La garde</b> : les regex du code qui s'exécute sur l'appareil
 * (mêmes unités que {@link SurfaceArtTest} — le composant engine-core) sont
 * extraites de leurs LITTÉRAUX Java et vérifiées : hors classe de caractères,
 * toute accolade doit être échappée ou former un quantificateur valide
 * {@code \{n\}}, {@code \{n,\}}, {@code \{n,m\}} (acceptés par ICU). La même
 * famille de divergence (JVM de bureau tolérante, ART/ICU strict) que StAX
 * (v0.2.13), ProcessHandle (v0.2.15) et disallow-doctype-decl (v0.2.17) —
 * mais celle-ci est ATTRAPABLE sur JVM, alors elle est attrapée.</p>
 *
 * <p><b>Limites assumées</b> : seules les regex LITTÉRALES sont auditées (une
 * regex construite à l'exécution est construite par du code audité) ; le
 * petit scanneur de classes ({@code [}…{@code ]}) ne connaît pas les classes
 * POSIX imbriquées — aucune regex du moteur n'en utilise. Les AUTRES écarts
 * connus d'ICU (intersections {@code &&} des classes, réflexion arrière de
 * longueur variable…) restent hors garde : ils exigeraient un vrai compileur
 * ICU sur JVM ; la règle des accolades couvre la divergence constatée.</p>
 */
class SurfaceRegexIcuTest {

    /** Les quatre modules du composant engine-core (même périmètre que SurfaceArtTest). */
    private static final List<String> MODULES = List.of(
            "engine", "api", "json", "client");

    /** Plancher de sites audités : si le scanneur ne trouve plus rien, la garde doit crier. */
    private static final int SITES_MINIMAUX = 15;

    @Test
    void lesRegexDuMoteurSontCompatiblesIcu() throws IOException {
        Path racine = racineDépôt();
        List<String> manquants = new ArrayList<>();
        int sites = 0;
        for (String module : MODULES) {
            Path sources = racine.resolve(module).resolve("src/main/java");
            if (!Files.isDirectory(sources)) {
                manquants.add(module + " : " + sources + " introuvable");
                continue;
            }
            try (Stream<Path> parcours = Files.walk(sources)) {
                for (Path fichier : parcours
                        .filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".java"))
                        .sorted().toList()) {
                    sites += auditer(racine, fichier, manquants);
                }
            }
        }
        assertTrue(manquants.isEmpty(), () -> String.join("\n", manquants));
        assertTrue(sites >= SITES_MINIMAUX,
                "Le scanneur n'a trouvé que " + sites + " site(s) Pattern.compile — "
                        + "la garde est probablement cassée par un remaniement "
                        + "(seuil plancher " + SITES_MINIMAUX + ")");
    }

    /**
     * Audite un fichier source : chaque appel {@code Pattern.compile("…")}
     * dont l'argument est un littéral (ou une concaténation de littéraux)
     * donne une regex décodée, vérifiée contre la règle des accolades.
     *
     * @return le nombre de sites à argument littéral rencontrés.
     */
    private static int auditer(Path racine, Path fichier, List<String> manquants)
            throws IOException {
        String texte = Files.readString(fichier);
        int sites = 0;
        int position = 0;
        while (true) {
            int appel = texte.indexOf("Pattern.compile", position);
            if (appel < 0) {
                return sites;
            }
            position = appel + "Pattern.compile".length();
            int parenthèse = texte.indexOf('(', position);
            if (parenthèse < 0) {
                return sites;
            }
            int début = premierCaractère(texte, parenthèse + 1);
            if (texte.charAt(début) != '"') {
                continue; // Argument dynamique (variable, constante externe) : non auditable.
            }
            String regex = littérauxConcaténés(texte, début);
            if (regex == null) {
                continue; // Bloc de texte ou autre forme exotique : non auditable.
            }
            sites++;
            String provenance = racine.relativize(fichier).toString();
            for (String problème : accoladesICU(regex)) {
                manquants.add(provenance + " — " + problème);
            }
        }
    }

    /**
     * La règle ICU : hors classe de caractères, une accolade non échappée
     * doit former un quantificateur valide — l'accolade FERMANTE seule n'a
     * AUCUN état qui l'accepte (elle n'est jamais un littéral hors classe).
     *
     * @return la liste des problèmes (vide = compatible ICU).
     */
    static List<String> accoladesICU(String regex) {
        List<String> problèmes = new ArrayList<>();
        boolean échappement = false;
        boolean classe = false;
        for (int i = 0; i < regex.length(); i++) {
            char c = regex.charAt(i);
            if (échappement) {
                échappement = false;
                continue;
            }
            if (c == '\\') {
                échappement = true;
                continue;
            }
            if (classe) {
                if (c == ']') {
                    classe = false;
                }
                continue; // En classe, { et } sont des littéraux pour ICU comme pour OpenJDK.
            }
            if (c == '[') {
                classe = true;
                continue;
            }
            if (c == '{') {
                int fin = finQuantificateur(regex, i);
                if (fin < 0) {
                    problèmes.add("accolade OUVRANTE non quantificateur à l'index "
                            + i + " (ICU : U_REGEX_RULE_SYNTAX — échappez-la) : « "
                            + extrait(regex, i) + " »");
                } else {
                    i = fin; // Quantificateur {n}/{n,}/{n,m} : valide pour ICU, sa fermeture est consommée.
                }
                continue;
            }
            if (c == '}') {
                problèmes.add("accolade FERMANTE orpheline à l'index " + i
                        + " (OpenJDK la tient pour un littéral, ICU la REFUSE — "
                        + "régression v0.2.18 : ExceptionInInitializerError sur "
                        + "appareil ; échappez-la en \\}) : « "
                        + extrait(regex, i) + " »");
            }
        }
        return problèmes;
    }

    /**
     * La fin d'un quantificateur valide démarrant à {@code début}
     * ({@code {n}}, {@code {n,}}, {@code {n,m}}), ou -1 si l'accolade
     * n'en forme pas un.
     */
    private static int finQuantificateur(String regex, int début) {
        int i = début + 1;
        int chiffresMin = 0;
        while (i < regex.length() && Character.isDigit(regex.charAt(i))) {
            i++;
            chiffresMin++;
        }
        if (chiffresMin == 0) {
            return -1;
        }
        if (i < regex.length() && regex.charAt(i) == ',') {
            i++;
            while (i < regex.length() && Character.isDigit(regex.charAt(i))) {
                i++;
            }
        }
        return i < regex.length() && regex.charAt(i) == '}' ? i : -1;
    }

    /** Un extrait court autour d'un index, pour nommer le coupable. */
    private static String extrait(String regex, int index) {
        int début = Math.max(0, index - 8);
        int fin = Math.min(regex.length(), index + 12);
        return (début > 0 ? "…" : "") + regex.substring(début, fin)
                + (fin < regex.length() ? "…" : "");
    }

    /** Le premier indice d'un caractère non blanc. */
    private static int premierCaractère(String texte, int depuis) {
        int i = depuis;
        while (i < texte.length() && Character.isWhitespace(texte.charAt(i))) {
            i++;
        }
        return i;
    }

    /**
     * Décode un littéral Java, PUIS les concaténations de littéraux qui
     * suivent ({@code "a" + "b"}). Retourne null si la suite n'est pas une
     * simple suite de littéraux (bloc de texte {@code """} ou expression).
     */
    private static String littérauxConcaténés(String texte, int débutLittéral) {
        StringBuilder valeur = new StringBuilder();
        int i = débutLittéral;
        while (true) {
            if (i + 2 < texte.length() && texte.startsWith("\"\"\"", i)) {
                return null; // Bloc de texte : hors périmètre du décodeur.
            }
            if (i >= texte.length() || texte.charAt(i) != '"') {
                return null;
            }
            int fin = littéralSimple(texte, i, valeur);
            if (fin < 0) {
                return null;
            }
            int suite = premierCaractère(texte, fin);
            if (suite < texte.length() && texte.charAt(suite) == '+') {
                i = premierCaractère(texte, suite + 1);
                continue;
            }
            return valeur.toString();
        }
    }

    /**
     * Décode le littéral démarrant à {@code ouverture} dans {@code valeur} ;
     * retourne l'indice JUSTE après le guillemet fermant, ou -1 si non
     * décodable.
     */
    private static int littéralSimple(String texte, int ouverture,
                                      StringBuilder valeur) {
        for (int i = ouverture + 1; i < texte.length(); i++) {
            char c = texte.charAt(i);
            if (c == '\\' && i + 1 < texte.length()) {
                char suivant = texte.charAt(i + 1);
                switch (suivant) {
                    case 'n' -> valeur.append('\n');
                    case 'r' -> valeur.append('\r');
                    case 't' -> valeur.append('\t');
                    case 'f' -> valeur.append('\f');
                    case 'b' -> valeur.append('\b');
                    case '0' -> valeur.append('\0');
                    case '\\' -> valeur.append('\\');
                    case '"' -> valeur.append('"');
                    case '\'' -> valeur.append('\'');
                    case 'u' -> {
                        if (i + 5 >= texte.length()) {
                            return -1;
                        }
                        try {
                            valeur.append((char) Integer.parseInt(
                                    texte.substring(i + 2, i + 6), 16));
                        } catch (NumberFormatException hexaInvalide) {
                            return -1;
                        }
                        i += 4;
                    }
                    default -> { // \d, \w, \$… : l'échappement passe tel quel à la regex.
                        valeur.append('\\').append(suivant);
                    }
                }
                i++;
                continue;
            }
            if (c == '"') {
                return i + 1;
            }
            valeur.append(c);
        }
        return -1;
    }

    /**
     * La racine du dépôt : remonte depuis le répertoire de travail (le module
     * engine sous Gradle, la racine sous un IDE) jusqu'au répertoire portant
     * settings.gradle ET les sources du moteur.
     */
    private static Path racineDépôt() throws IOException {
        Path ici = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path candidate = ici; candidate != null; candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle"))
                    && Files.isDirectory(candidate.resolve("engine/src/main/java"))) {
                return candidate;
            }
        }
        throw new IOException("racine du dépôt introuvable depuis " + ici
                + " — le test doit tourner depuis le dépôt (Gradle ou IDE)");
    }
}
