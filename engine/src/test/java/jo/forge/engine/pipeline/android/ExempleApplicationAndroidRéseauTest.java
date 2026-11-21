package jo.forge.engine.pipeline.android;

import jo.forge.engine.exec.TaskExecutor;
import jo.forge.engine.graph.TaskGraph;
import jo.forge.engine.model.ForgeModel;
import jo.forge.engine.model.ModelCodec;
import jo.forge.engine.pipeline.ProjectLayout;
import jo.forge.engine.pipeline.java.JavaPipeline;
import jo.forge.engine.state.FingerprintStore;
import jo.forge.engine.tasks.Task;
import jo.forge.engine.testutil.OutilsAndroidDeTest;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Construction de l'EXEMPLE {@code application-android} du dépôt avec les VRAIS
 * dépôts Maven (Google compris) — la validation de bureau de ce que l'appareil
 * va construire. Opt-in : {@code -Dforge.exempleRéseau=1} (la CI ne le lance
 * pas — la résolution réseau y serait lente et non déterministe ; les tests
 * harnais couvrent déjà la chaîne avec des dépôts locaux).
 *
 * <p>v0.2.18 : l'exemple active {@code buildFeatures} (BuildConfig +
 * ViewBinding) — ce test prouve que la génération tient sur un projet réel,
 * runtime ViewBinding auto-ajouté compris, jusqu'à l'APK signé.</p>
 */
class ExempleApplicationAndroidRéseauTest {

    @TempDir
    Path temporaire;

    private static Path aapt2;
    private static Path androidJar;

    @BeforeAll
    static void outils() throws IOException {
        Assumptions.assumeTrue("1".equals(System.getProperty("forge.exempleRéseau")),
                "test réseau opt-in : -Dforge.exempleRéseau=1");
        aapt2 = OutilsAndroidDeTest.extraireAapt2();
        androidJar = OutilsAndroidDeTest.androidJar();
        Assumptions.assumeTrue(aapt2 != null && androidJar != null,
                "aapt2 ou android.jar indisponible — test ignoré");
        System.setProperty("forge.aapt2", aapt2.toString());
        System.setProperty("forge.androidJar", androidJar.toString());
    }

    @Test
    void exempleCompletAvecGénérationJusquÀLApk() throws Exception {
        // Copie de l'exemple du dépôt vers le temporaire (le build n'y écrit
        // jamais dans le dépôt).
        Path racine = temporaire.resolve("projet");
        Path exemple = Path.of("..", "examples", "application-android");
        Assumptions.assumeTrue(Files.isDirectory(exemple),
                "exemple introuvable : " + exemple.toAbsolutePath());
        try (var parcours = Files.walk(exemple)) {
            parcours.filter(Files::isRegularFile).forEach(source -> {
                try {
                    Path cible = racine.resolve(exemple.relativize(source).toString());
                    Files.createDirectories(cible.getParent());
                    Files.copy(source, cible);
                } catch (IOException échec) {
                    throw new java.io.UncheckedIOException(échec);
                }
            });
        }

        ForgeModel modèle = ModelCodec.read(racine);
        List<Task> tâches = JavaPipeline.tasksFor(modèle, racine, false, "x86_64");
        TaskGraph graphe = TaskGraph.of(tâches);
        ProjectLayout disposition = new ProjectLayout(racine);
        FingerprintStore magasin = new FingerprintStore(disposition.stateDir(),
                racine, avertissement -> { });
        GénérationCodePipelineTest.Enregistreur journal =
                new GénérationCodePipelineTest.Enregistreur();
        TaskExecutor.BuildResult résultat = new TaskExecutor(graphe, journal,
                new jo.forge.engine.tasks.CancellationFlag(), magasin, 2,
                racine).execute();

        assertTrue(résultat.success(), "l'exemple application-android construit de "
                + "bout en bout avec génération : " + journal.diagnostics
                + " journal=" + journal.toutesLignes
                + " échec=" + résultat.failedTask());
        assertTrue(Files.isRegularFile(racine.resolve(
                "build/app/classes/demo/app/databinding/MainBinding.class")),
                "la liaison de l'exemple est générée ET compilée");
        assertTrue(Files.isRegularFile(racine.resolve(
                "build/app/classes/demo/app/BuildConfig.class")),
                "le BuildConfig de l'exemple est généré ET compilé");
        assertTrue(Files.isRegularFile(racine.resolve(
                "build/app/outputs/app-debug.apk")),
                "l'APK signé de l'exemple existe");
    }
}
