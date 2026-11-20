package jo.forge.engine.pipeline.java;

import jo.forge.engine.diag.Diagnostic;
import jo.forge.engine.testutil.OutilsAndroidDeTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests du compilateur sans image (correctif v0.2.7) : le compilateur interne
 * d'ECJ sur un environnement de noms classique. Sans bibliothèque, il
 * reproduit l'échec appareil (java.lang.Object introuvable) ; avec
 * android.jar, tout se lie ; les répertoires gagnent sur les jars ; les
 * diagnostics restent localisés.
 */
class CompilateurSansImageTest {

    @TempDir
    Path temporaire;

    /**
     * Sans AUCUNE bibliothèque : l'échec est celui constaté sur l'appareil en
     * v0.2.6 — « java.lang.Object cannot be resolved ». Ce test documente la
     * cause racine : le runtime ART n'expose aucune classe lisible par ECJ.
     */
    @Test
    void sansBibliothèqueReproduitLErreurAppareil() throws IOException {
        Path source = Files.writeString(temporaire.resolve("Salutations.java"), """
                package demo.core;

                public final class Salutations {
                    public static String saluer(String nom) {
                        return "Salut, " + nom;
                    }
                }
                """);
        List<Diagnostic> diagnostiques = new ArrayList<>();
        EcjCompiler.CompileResult resultat = CompilateurSansImage.compiler(
                List.of(source), List.of(), temporaire.resolve("classes"), 17,
                diagnostiques::add);

        assertFalse(resultat.success(), "sans bibliothèque, la compilation échoue");
        assertTrue(diagnostiques.stream().anyMatch(d -> d.message()
                        .contains("java.lang.Object cannot be resolved")),
                "l'erreur appareil doit être reproduite : " + diagnostiques);
    }

    /**
     * Les répertoires gagnent sur les jars : la copie fraîche d'un module frère
     * (répertoire) doit masquer la copie périmée d'un jar du classpath.
     */
    @Test
    void lesRépertoiresGagnentSurLesJars() throws IOException {
        Path androidJar = OutilsAndroidDeTest.androidJar();
        assumeTrue(androidJar != null,
                "android.jar réel indisponible — test ignoré");

        // Deux versions de demo.core.Util : le jar porte l'ANCIENNE (ancienne()),
        // le répertoire la NOUVELLE (nouvelle()).
        Path classesJar = temporaire.resolve("périmées");
        Files.writeString(temporaire.resolve("Util.java"), """
                package demo.core;

                public final class Util {
                    private Util() { }

                    public static String ancienne() { return "ancienne"; }
                }
                """);
        assertTrue(EcjCompiler.compile(List.of(temporaire.resolve("Util.java")),
                List.of(), classesJar, 17, jo.forge.engine.diag.DiagnosticSink.NOOP)
                .success(), "la version périmée doit compiler");
        Path jarPérimé = temporaire.resolve("périmé.jar");
        try (ZipOutputStream zip = new ZipOutputStream(
                Files.newOutputStream(jarPérimé))) {
            zip.putNextEntry(new ZipEntry("demo/core/Util.class"));
            Files.copy(classesJar.resolve("demo/core/Util.class"), zip);
            zip.closeEntry();
        }

        Path classesFraîches = temporaire.resolve("fraîches");
        Files.writeString(temporaire.resolve("Util.java"), """
                package demo.core;

                public final class Util {
                    private Util() { }

                    public static String nouvelle() { return "nouvelle"; }
                }
                """);
        assertTrue(EcjCompiler.compile(List.of(temporaire.resolve("Util.java")),
                List.of(), classesFraîches, 17,
                jo.forge.engine.diag.DiagnosticSink.NOOP).success(),
                "la version fraîche doit compiler");

        Path source = Files.writeString(temporaire.resolve("Client.java"), """
                package demo.client;

                public final class Client {
                    public static String texte() {
                        return demo.core.Util.nouvelle();
                    }
                }
                """);
        // Le jar AVANT le répertoire dans la liste : le répertoire doit gagner.
        EcjCompiler.CompileResult resultat = CompilateurSansImage.compiler(
                List.of(source), List.of(androidJar, jarPérimé, classesFraîches),
                temporaire.resolve("classes"), 17,
                jo.forge.engine.diag.DiagnosticSink.NOOP);

        assertTrue(resultat.success(), "nouvelle() doit se lier au répertoire, pas au "
                + "jar périmé : " + resultat.consoleLines());
    }

    /**
     * Régression v0.2.7 (constat SimulationArtTest) : les méthodes BINAIRES
     * gardent leur drapeau varargs. Sans le champ {@code originalSourceLevel}
     * posé à la main, ECJ lie les méthodes des .class au niveau par défaut
     * (JDK 1.3) et leur DÉPOUILLE ce drapeau — la moindre expansion
     * {@code joindre(sep, "a", "b")} échouait par « not applicable for the
     * arguments ».
     */
    @Test
    void lesMéthodesBinairesGardentLeursVarargs() throws IOException {
        Path androidJar = OutilsAndroidDeTest.androidJar();
        assumeTrue(androidJar != null,
                "android.jar réel indisponible — test ignoré");

        Path core = temporaire.resolve("core");
        Files.writeString(temporaire.resolve("Textes.java"), """
                package demo.core;

                public final class Textes {
                    private Textes() { }

                    public static String joindre(String separateur, String... mots) {
                        return String.join(separateur, mots);
                    }
                }
                """);
        assertTrue(EcjCompiler.compile(List.of(temporaire.resolve("Textes.java")),
                List.of(), List.of(androidJar), core, 17,
                jo.forge.engine.diag.DiagnosticSink.NOOP).success(),
                "core doit compiler (varargs à la source)");

        // Expansion varargs (args séparés, pas un tableau) contre la méthode
        // BINAIRE de Textes.class — et contre String.join d'android.jar aussi.
        Path client = Files.writeString(temporaire.resolve("Client.java"), """
                package demo.client;

                public final class Client {
                    private Client() { }

                    public static String texte() {
                        return demo.core.Textes.joindre(" ", "Bienvenue", "sur", "Forge")
                                + String.join("-", "a", "b");
                    }
                }
                """);
        EcjCompiler.CompileResult résultat = EcjCompiler.compile(List.of(client),
                List.of(core), List.of(androidJar),
                temporaire.resolve("client"), 17,
                jo.forge.engine.diag.DiagnosticSink.NOOP);

        assertTrue(résultat.success(), "l'expansion varargs doit se lier aux méthodes "
                + "binaires : " + résultat.consoleLines());
        assertTrue(Files.isRegularFile(
                        temporaire.resolve("client/demo/client/Client.class")),
                "le client doit être compilé");
    }

    /**
     * Les diagnostics du chemin sans image restent localisés : fichier, ligne,
     * colonne — l'éditeur de la coquille les affiche comme ceux du frontal.
     */
    @Test
    void diagnosticsLocalisésSurLeCheminSansImage() throws IOException {
        Path androidJar = OutilsAndroidDeTest.androidJar();
        assumeTrue(androidJar != null,
                "android.jar réel indisponible — test ignoré");
        Path source = Files.writeString(temporaire.resolve("Fautif.java"), """
                package demo.core;

                public class Fautif {
                    public int calcule() {
                        return variableInconnue;
                    }
                }
                """);
        List<Diagnostic> diagnostiques = new ArrayList<>();
        EcjCompiler.CompileResult resultat = CompilateurSansImage.compiler(
                List.of(source), List.of(androidJar),
                temporaire.resolve("classes"), 17, diagnostiques::add);

        assertFalse(resultat.success(), "la compilation doit échouer");
        assertTrue(resultat.errorCount() >= 1, "les erreurs sont comptées");
        Diagnostic erreur = diagnostiques.stream()
                .filter(d -> d.severity() == jo.forge.api.BuildEvent.Severity.ERROR)
                .findFirst().orElseThrow();
        assertEquals("Fautif.java",
                Path.of(erreur.file()).getFileName().toString(),
                "le fichier fautif doit être nommé");
        assertEquals(5, erreur.line(), "l'erreur est ligne 5");
        assertTrue(erreur.column() > 0, "la colonne doit être calculée");
        assertEquals(EcjCompiler.TOOL, erreur.tool());
        assertTrue(erreur.code() != null && erreur.code().startsWith("ecj:"),
                "le code machine doit être préfixé ecj: : " + erreur.code());
    }
}
