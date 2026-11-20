package jo.forge.engine.pipeline.android;

import com.android.apksig.ApkVerifier;
import jo.forge.engine.exec.TaskExecutor;
import jo.forge.engine.graph.TaskGraph;
import jo.forge.engine.model.ForgeModel;
import jo.forge.engine.model.ModelCodec;
import jo.forge.engine.pipeline.ProjectLayout;
import jo.forge.engine.state.FingerprintStore;
import jo.forge.engine.tasks.Task;
import jo.forge.engine.testutil.OutilsAndroidDeTest;
import jo.forge.engine.testutil.Projets;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Intégration du pipeline Android : chaîne complète d'un module {@code application}
 * jusqu'à l'APK signé, avec de VRAIS outils (aapt2 extrait du jar Maven, android.jar
 * réel, d8 et apksig en processus) — ignoré si les outils sont absents de la machine.
 *
 * <p>Couverture : construction du graphe, incrémentalité par empreintes (une source
 * modifiée ne relance pas les ressources ; une ressource modifiée relance la chaîne
 * aval), diagnostics de ressources localisés, signature vérifiée par apksig (schémas
 * v1 et v2), contenu de l'APK (dex, arsc, manifeste).</p>
 */
class AndroidPipelineTest {

    @TempDir
    Path temporaire;

    private static Path aapt2;
    private static Path androidJar;

    @BeforeAll
    static void outils() throws IOException {
        aapt2 = OutilsAndroidDeTest.extraireAapt2();
        androidJar = OutilsAndroidDeTest.androidJar();
        Assumptions.assumeTrue(aapt2 != null && androidJar != null,
                "aapt2 ou android.jar indisponible sur cette machine — test ignoré "
                        + "(la CI l'exécute avec le SDK)");
        System.setProperty("forge.aapt2", aapt2.toString());
        System.setProperty("forge.androidJar", androidJar.toString());
    }

    private void projetAndroid(Path racine) throws IOException {
        Projets.fichier(racine, "forge.json", """
                { "modelVersion": 1,
                  "repositories": [],
                  "modules": [
                    { "name": "core", "type": "java-library" },
                    { "name": "app", "type": "application",
                      "dependencies": [ { "project": "core", "scope": "api" } ],
                      "android": { "manifest": "src/main/AndroidManifest.xml",
                                   "res": ["src/main/res"],
                                   "minSdk": 28, "targetSdk": 34,
                                   "namespace": "demo.app" } }
                  ] }
                """);
        Projets.fichier(racine, "core/src/main/java/demo/core/Salutations.java", """
                package demo.core;

                public final class Salutations {
                    private Salutations() { }

                    public static String app() { return "Salutations !"; }
                }
                """);
        Projets.fichier(racine, "app/src/main/AndroidManifest.xml", """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                        package="demo.app">
                    <application android:label="@string/app_name">
                        <activity android:name=".MainActivity" android:exported="true">
                            <intent-filter>
                                <action android:name="android.intent.action.MAIN"/>
                                <category android:name="android.intent.category.LAUNCHER"/>
                            </intent-filter>
                        </activity>
                    </application>
                </manifest>
                """);
        Projets.fichier(racine, "app/src/main/res/values/strings.xml", """
                <?xml version="1.0" encoding="utf-8"?>
                <resources>
                    <string name="app_name">Démo Forge</string>
                </resources>
                """);
        Projets.fichier(racine, "app/src/main/res/layout/main.xml", """
                <?xml version="1.0" encoding="utf-8"?>
                <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                        android:layout_width="match_parent"
                        android:layout_height="match_parent"
                        android:orientation="vertical">
                    <TextView
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:text="@string/app_name"/>
                </LinearLayout>
                """);
        Projets.fichier(racine, "app/src/main/java/demo/app/MainActivity.java", """
                package demo.app;

                import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import demo.core.Salutations;

public final class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle état) {
        super.onCreate(état);
        TextView texte = new TextView(this);
        texte.setText(Salutations.app() + " " + getString(R.string.app_name));
        setContentView(texte);
    }
}
""");
    }

    @Test
    void chaîneComplèteJusquÀLApkSigné() throws Exception {
        Path racine = temporaire.resolve("projet");
        projetAndroid(racine);

        Enregistreur journal = new Enregistreur();
        TaskExecutor.BuildResult premier = exécuter(racine, journal);
        assertTrue(premier.success(), "le build Android complet doit réussir : "
                + journal.erreurs + journal.diagnostics);

        Path apk = racine.resolve("build/app/outputs/app-debug.apk");
        assertTrue(Files.isRegularFile(apk), "l'APK signé doit exister : " + apk);

        // Contenu : dex, table de ressources, manifeste.
        try (ZipFile zip = new ZipFile(apk.toFile())) {
            assertNotNull(zip.getEntry("classes.dex"), "l'APK contient classes.dex");
            assertNotNull(zip.getEntry("resources.arsc"), "l'APK contient resources.arsc");
            assertNotNull(zip.getEntry("AndroidManifest.xml"),
                    "l'APK contient le manifeste");
        }

        // Signature : vérifiée par apksig (v1 ET v2 — plateforme 1 pour forcer
        // l'évaluation de v1, que le manifeste rendrait sinon facultative).
        ApkVerifier.Result vérification = new ApkVerifier.Builder(apk.toFile())
                .setMinCheckedPlatformVersion(1)
                .build().verify();
        assertTrue(vérification.isVerified(), "signature vérifiée : "
                + vérification.getErrors());
        assertTrue(vérification.isVerifiedUsingV1Scheme(), "schéma v1 vérifié : "
                + vérification.getErrors());
        assertTrue(vérification.isVerifiedUsingV2Scheme(), "schéma v2 vérifié : "
                + vérification.getErrors());

        // Les sources R ont bien été générées et compilées.
        assertTrue(Files.isRegularFile(racine.resolve(
                "build/app/generated/r/demo/app/R.java")));
        assertTrue(Files.isRegularFile(racine.resolve(
                "build/app/classes/demo/app/MainActivity.class")));

        // Second build : tout à jour.
        Enregistreur second = new Enregistreur();
        assertTrue(exécuter(racine, second).success());
        assertTrue(second.tâches(jo.forge.api.BuildEvent.TaskState.UP_TO_DATE).size()
                        >= second.tâches(jo.forge.api.BuildEvent.TaskState.STARTED).size()
                        + 4,
                "au second passage, tout est à jour : "
                        + second.tâches(jo.forge.api.BuildEvent.TaskState.UP_TO_DATE)
                        + " vs relancées "
                        + second.tâches(jo.forge.api.BuildEvent.TaskState.STARTED));

        // Une SOURCE modifiée : les ressources restent à jour, la chaîne aval relance.
        Projets.fichier(racine, "app/src/main/java/demo/app/MainActivity.java", """
                package demo.app;

                import android.app.Activity;
                import android.os.Bundle;
                import android.widget.TextView;
                import demo.core.Salutations;

                public final class MainActivity extends Activity {
                    @Override
                    protected void onCreate(Bundle état) {
                        super.onCreate(état);
                        TextView texte = new TextView(this);
                        texte.setText(Salutations.app());
                        setContentView(texte);
                    }
                }
                """);
        Enregistreur aprèsSource = new Enregistreur();
        assertTrue(exécuter(racine, aprèsSource).success());
        assertTrue(aprèsSource.tâches(jo.forge.api.BuildEvent.TaskState.UP_TO_DATE)
                        .contains("app:processResources"),
                "les ressources restent à jour quand seule une source change : "
                        + aprèsSource.tâches(jo.forge.api.BuildEvent.TaskState.STARTED));
        assertTrue(aprèsSource.tâches(jo.forge.api.BuildEvent.TaskState.STARTED)
                        .contains("app:compileJava"));

        // Une RESSOURCE modifiée : la chaîne aval relance, les sources ne relancent
        // que la compilation (R régénéré).
        Projets.fichier(racine, "app/src/main/res/values/strings.xml", """
                <?xml version="1.0" encoding="utf-8"?>
                <resources>
                    <string name="app_name">Démo Forge (modifiée)</string>
                </resources>
                """);
        Enregistreur aprèsRessource = new Enregistreur();
        assertTrue(exécuter(racine, aprèsRessource).success());
        assertTrue(aprèsRessource.tâches(jo.forge.api.BuildEvent.TaskState.STARTED)
                        .contains("app:processResources"),
                "les ressources relancent après modification : "
                        + aprèsRessource.tâches(jo.forge.api.BuildEvent.TaskState.STARTED));
    }

    @Test
    void diagnosticDeRessourceLocaliséRemonte() throws Exception {
        Path racine = temporaire.resolve("projet-fautif");
        projetAndroid(racine);
        // Sabotage : attribut inconnu dans le layout.
        Projets.fichier(racine, "app/src/main/res/layout/main.xml", """
                <?xml version="1.0" encoding="utf-8"?>
                <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                        android:layout_width="match_parent"
                        android:layout_height="match_parent"
                        android:orientation="vertical">
                    <TextView
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:fontFamilee="sans-serif"/>
                </LinearLayout>
                """);

        Enregistreur journal = new Enregistreur();
        TaskExecutor.BuildResult échec = exécuter(racine, journal);
        assertTrue(!échec.success(), "le build doit échouer sur la ressource fautive");
        assertEquals("app:processResources", échec.failedTask());
        assertTrue(journal.diagnostics.stream().anyMatch(d ->
                        d.severity() == jo.forge.api.BuildEvent.Severity.ERROR
                                && d.tool().equals("aapt2")
                                && d.file().contains("main.xml")
                                && d.line() > 0),
                "un diagnostic aapt2 localisé doit remonter : " + journal.diagnostics);
    }

    private TaskExecutor.BuildResult exécuter(Path racine, Enregistreur journal)
            throws IOException {
        ForgeModel modèle = ModelCodec.read(racine);
        List<Task> tâches = jo.forge.engine.pipeline.java.JavaPipeline.tasksFor(
                modèle, racine, false, "x86_64");
        TaskGraph graphe = TaskGraph.of(tâches);
        ProjectLayout disposition = new ProjectLayout(racine);
        FingerprintStore magasin = new FingerprintStore(disposition.stateDir(),
                racine, avertissement -> { });
        return new TaskExecutor(graphe, journal,
                new jo.forge.engine.tasks.CancellationFlag(), magasin, 2,
                racine).execute();
    }

    /** Enregistreur d'événements pour les assertions. */
    private static final class Enregistreur implements TaskExecutor.BuildEvents {

        final Map<jo.forge.api.BuildEvent.TaskState, List<String>> états =
                new HashMap<>();
        final List<jo.forge.engine.diag.Diagnostic> diagnostics = new ArrayList<>();
        final StringBuilder erreurs = new StringBuilder();
        private String premierÉchec;

        List<String> tâches(jo.forge.api.BuildEvent.TaskState état) {
            return états.getOrDefault(état, List.of());
        }

        @Override
        public String buildId() {
            return "test-android";
        }

        @Override
        public void taskState(jo.forge.api.BuildEvent.TaskStateEvent event) {
            états.computeIfAbsent(event.state(), k -> new ArrayList<>())
                    .add(event.taskName());
        }

        @Override
        public void log(jo.forge.api.BuildEvent.LogEvent.LogLevel level, String message) {
            if (level == jo.forge.api.BuildEvent.LogEvent.LogLevel.ERROR) {
                erreurs.append(message).append('\n');
            }
        }

        @Override
        public void progress(int total, int done) {
        }

        @Override
        public void diagnostic(jo.forge.engine.diag.Diagnostic diagnostic) {
            diagnostics.add(diagnostic);
        }

        @Override
        public void buildFinished(jo.forge.api.BuildEvent.BuildFinished finished) {
        }

        @Override
        public String dernierEchec() {
            return premierÉchec;
        }

        @Override
        public void tacheEchouee(String taskName) {
            if (premierÉchec == null) {
                premierÉchec = taskName;
            }
        }
    }
}
