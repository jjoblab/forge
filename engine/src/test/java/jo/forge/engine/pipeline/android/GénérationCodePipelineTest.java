package jo.forge.engine.pipeline.android;

import jo.forge.engine.exec.TaskExecutor;
import jo.forge.engine.graph.TaskGraph;
import jo.forge.engine.model.ForgeModel;
import jo.forge.engine.model.ModelCodec;
import jo.forge.engine.pipeline.ProjectLayout;
import jo.forge.engine.pipeline.java.JavaPipeline;
import jo.forge.engine.state.FingerprintStore;
import jo.forge.engine.tasks.Task;
import jo.forge.engine.testutil.DépôtLocal;
import jo.forge.engine.testutil.OutilsAndroidDeTest;
import jo.forge.engine.testutil.Projets;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Intégration de la GÉNÉRATION DE CODE (v0.2.18) dans la chaîne Android
 * complète : un module application avec {@code buildFeatures {
 * buildConfig, viewBinding }} — les tâches {@code generateBuildConfig} et
 * {@code generateViewBinding} précèdent les compilations, leurs sources
 * rejoignent le chemin des sources (comme les R), le runtime ViewBinding est
 * AJOUTÉ à la résolution comme AGP le fait (le script ne le porte pas), et
 * l'APK signé contient les classes générées DEXÉES.
 *
 * <p>Vrais outils (aapt2, android.jar, d8, apksig) — ignoré s'ils manquent,
 * comme {@link AndroidPipelineAarTest}. Le runtime ViewBinding est servi par
 * le dépôt local de test à la coordonnée exacte que le pipeline ajoute.</p>
 */
class GénérationCodePipelineTest {

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

    /**
     * Assemble le runtime ViewBinding (l'interface seule) en AAR : le moteur
     * l'ajoute à la résolution sous la coordonnée
     * {@code androidx.databinding:viewbinding} quand la fonctionnalité est
     * demandée — le dépôt local de test la sert.
     */
    private byte[] runtimeViewBinding(Path racine) throws IOException {
        Path source = Projets.fichier(racine, "fixture/androidx/viewbinding/ViewBinding.java", """
                package androidx.viewbinding;

                public interface ViewBinding {
                    android.view.View getRoot();
                }
                """);
        Path classes = racine.resolve("fixture-classes");
        jo.forge.engine.pipeline.java.EcjCompiler.CompileResult résultat =
                jo.forge.engine.pipeline.java.EcjCompiler.compile(List.of(source),
                        List.of(), List.of(androidJar), classes, 17,
                        diagnostic -> { });
        assertTrue(résultat.success(), "l'interface ViewBinding compile : "
                + résultat.consoleLines());
        ByteArrayOutputStream octets = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(octets);
             var parcours = Files.walk(classes)) {
            for (Path classe : parcours.filter(Files::isRegularFile).toList()) {
                zip.putNextEntry(new ZipEntry(
                        classes.relativize(classe).toString()));
                zip.write(Files.readAllBytes(classe));
                zip.closeEntry();
            }
        }
        ByteArrayOutputStream aarOctets = new ByteArrayOutputStream();
        try (ZipOutputStream aar = new ZipOutputStream(aarOctets)) {
            aar.putNextEntry(new ZipEntry("AndroidManifest.xml"));
            aar.write(("""
                    <?xml version="1.0" encoding="utf-8"?>
                    <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                            package="androidx.viewbinding"/>
                    """).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            aar.closeEntry();
            aar.putNextEntry(new ZipEntry("classes.jar"));
            aar.write(octets.toByteArray());
            aar.closeEntry();
        }
        return aarOctets.toByteArray();
    }

    @Test
    void buildConfigEtViewBindingJusquÀLApkSigné() throws Exception {
        try (DépôtLocal dépôt = DépôtLocal.démarrer()) {
            Path racine = temporaire.resolve("projet");

            jo.forge.engine.deps.Coordinate runtime =
                    new jo.forge.engine.deps.Coordinate("androidx.databinding",
                            "viewbinding", AndroidPipeline.RUNTIME_VIEWBINDING);
            dépôt.publierPom(runtime, """
                    <project><groupId>androidx.databinding</groupId>
                    <artifactId>viewbinding</artifactId>
                    <version>%s</version>
                    <packaging>aar</packaging></project>
                    """.formatted(runtime.version()));
            dépôt.publierAar(runtime, runtimeViewBinding(racine));

            // Le script ne porte PAS le runtime ViewBinding : le pipeline
            // l'ajoute (même comportement qu'AGP).
            Projets.fichier(racine, "forge.json", """
                    { "modelVersion": 1,
                      "repositories": [ "%s" ],
                      "modules": [
                        { "name": "app", "type": "application",
                          "android": { "manifest": "src/main/AndroidManifest.xml",
                                       "res": ["src/main/res"],
                                       "minSdk": 28, "targetSdk": 34,
                                       "namespace": "demo.app",
                                       "versionCode": 3, "versionName": "2.0",
                                       "buildFeatures": { "buildConfig": true,
                                                          "viewBinding": true },
                                       "buildConfigFields": [
                                         { "type": "String", "name": "API_HOST",
                                           "value": "\\"https://api.exemple.com\\"" } ] } }
                      ] }
                    """.formatted(dépôt.url()));
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
                        <string name="app_name">Démo génération</string>
                    </resources>
                    """);
            Projets.fichier(racine, "app/src/main/res/layout/activity_main.xml", """
                    <?xml version="1.0" encoding="utf-8"?>
                    <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                            android:layout_width="match_parent"
                            android:layout_height="match_parent"
                            android:orientation="vertical">
                        <TextView
                                android:id="@+id/titre"
                                android:layout_width="wrap_content"
                                android:layout_height="wrap_content"/>
                    </LinearLayout>
                    """);
            // L'activité UTILISE les deux générations : ActivityMainBinding
            // (ViewBinding) et BuildConfig (constantes + champ personnalisé).
            Projets.fichier(racine, "app/src/main/java/demo/app/MainActivity.java", """
                    package demo.app;

                    import android.app.Activity;
                    import android.os.Bundle;
                    import demo.app.databinding.ActivityMainBinding;

                    public final class MainActivity extends Activity {
                        @Override
                        protected void onCreate(Bundle état) {
                            super.onCreate(état);
                            ActivityMainBinding liaison =
                                    ActivityMainBinding.inflate(getLayoutInflater());
                            liaison.titre.setText(BuildConfig.VERSION_NAME
                                    + " " + BuildConfig.API_HOST);
                            setContentView(liaison.getRoot());
                        }
                    }
                    """);

            Enregistreur journal = new Enregistreur();
            TaskExecutor.BuildResult résultat = exécuter(racine, journal);
            assertTrue(résultat.success(), "le build avec génération doit réussir : "
                    + journal.erreurs + journal.diagnostics + " journal="
                    + journal.toutesLignes + " échec=" + résultat.failedTask());

            // Les tâches de génération ont tourné AVANT les compilations.
            assertTrue(journal.toutesLignes.stream().anyMatch(l ->
                            l.contains("BuildConfig généré")),
                    "la génération BuildConfig a journalisé");
            assertTrue(journal.toutesLignes.stream().anyMatch(l ->
                            l.contains("classe(s) de liaison générée(s)")),
                    "la génération ViewBinding a journalisé");

            // Les sources générées existent, avec le bon contenu.
            assertTrue(Files.isRegularFile(racine.resolve(
                    "build/app/generated/buildConfig/demo/app/BuildConfig.java")));
            String buildConfig = Files.readString(racine.resolve(
                    "build/app/generated/buildConfig/demo/app/BuildConfig.java"));
            assertTrue(buildConfig.contains("VERSION_CODE = 3"));
            assertTrue(buildConfig.contains("API_HOST"));
            assertTrue(Files.isRegularFile(racine.resolve(
                    "build/app/generated/viewbinding/demo/app/databinding/"
                            + "ActivityMainBinding.java")));

            // Les classes générées sont COMPILÉES dans le module.
            assertTrue(Files.isRegularFile(racine.resolve(
                    "build/app/classes/demo/app/BuildConfig.class")),
                    "BuildConfig est compilé");
            assertTrue(Files.isRegularFile(racine.resolve(
                    "build/app/classes/demo/app/databinding/"
                            + "ActivityMainBinding.class")),
                    "la liaison est compilée");

            // Le runtime ViewBinding a bien été AJOUTÉ à la résolution (le
            // script ne le portait pas) : sa classe est DEXÉE dans l'APK.
            Path apk = racine.resolve("build/app/outputs/app-debug.apk");
            assertTrue(Files.isRegularFile(apk), "l'APK signé existe : " + apk);
            try (ZipFile zip = new ZipFile(apk.toFile())) {
                byte[] dex = zip.getInputStream(zip.getEntry("classes.dex"))
                        .readAllBytes();
                String contenu = new String(dex,
                        java.nio.charset.StandardCharsets.ISO_8859_1);
                assertTrue(contenu.contains("demo/app/databinding/ActivityMainBinding"),
                        "la liaison générée est dexée");
                assertTrue(contenu.contains("androidx/viewbinding/ViewBinding"),
                        "le runtime ajouté automatiquement est dexé");
                assertTrue(contenu.contains("demo/app/BuildConfig"),
                        "le BuildConfig généré est dexé");
            }
        }
    }

    private TaskExecutor.BuildResult exécuter(Path racine, Enregistreur journal)
            throws IOException {
        ForgeModel modèle = ModelCodec.read(racine);
        List<Task> tâches = JavaPipeline.tasksFor(modèle, racine, false, "x86_64");
        TaskGraph graphe = TaskGraph.of(tâches);
        ProjectLayout disposition = new ProjectLayout(racine);
        FingerprintStore magasin = new FingerprintStore(disposition.stateDir(),
                racine, avertissement -> { });
        return new TaskExecutor(graphe, journal,
                new jo.forge.engine.tasks.CancellationFlag(), magasin, 2,
                racine).execute();
    }

    /** Enregistreur d'événements (harnais du pipeline Android). */
    static final class Enregistreur implements TaskExecutor.BuildEvents {

        final List<jo.forge.engine.diag.Diagnostic> diagnostics =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<String> toutesLignes =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        final StringBuffer erreurs = new StringBuffer();

        @Override
        public String buildId() {
            return "test-génération-code";
        }

        @Override
        public void taskState(jo.forge.api.BuildEvent.TaskStateEvent event) {
        }

        @Override
        public void log(jo.forge.api.BuildEvent.LogEvent.LogLevel level, String message) {
            toutesLignes.add(message);
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
        public void tacheEchouee(String nomTâche) {
            erreurs.append(nomTâche).append(' ');
        }

        @Override
        public String dernierEchec() {
            return null;
        }
    }
}
