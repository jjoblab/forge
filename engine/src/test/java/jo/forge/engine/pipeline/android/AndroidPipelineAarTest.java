package jo.forge.engine.pipeline.android;

import jo.forge.engine.exec.TaskExecutor;
import jo.forge.engine.graph.TaskGraph;
import jo.forge.engine.model.ForgeModel;
import jo.forge.engine.model.ModelCodec;
import jo.forge.engine.pipeline.ProjectLayout;
import jo.forge.engine.pipeline.java.EcjCompiler;
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
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Intégration Android avec une DÉPENDANCE AAR (v0.2.15, prérequis Compose) :
 * résolution + explosion dans le cache, classes de la bibliothèque au
 * classpath de compilation ET au dex, ressources de la bibliothèque fusionnées
 * par aapt2, manifeste de la bibliothèque fusionné (provider), R de la
 * bibliothèque régénéré par {@code --extra-packages} — jusqu'à l'APK signé.
 *
 * <p>Vrais outils (aapt2, android.jar, d8, apksig) — ignoré s'ils manquent,
 * comme {@link AndroidPipelineTest}. L'AAR fixture est assemblé dans le test :
 * ses classes sont compilées par ECJ (le compilateur embarqué du moteur).</p>
 */
class AndroidPipelineAarTest {

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

    /** Assemble le classes.jar de l'AAR : une classe compilée par ECJ. */
    private byte[] classesDeBibliothèque(Path racine) throws IOException {
        Path source = racine.resolve("fixture/demo/lib/Salutations.java");
        Projets.fichier(racine, "fixture/demo/lib/Salutations.java", """
                package demo.lib;

                public final class Salutations {
                    private Salutations() { }

                    public static String app() { return "Salutations de la bibliothèque"; }
                }
                """);
        Path classes = racine.resolve("fixture-classes");
        EcjCompiler.CompileResult résultat = EcjCompiler.compile(List.of(source),
                List.of(), List.of(), classes, 17, diagnostic -> { });
        assertTrue(résultat.success(), "le fixture ECJ compile : " + résultat.consoleLines());
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
        return octets.toByteArray();
    }

    @Test
    void applicationAvecDépendanceAarJusquÀLApkSigné() throws Exception {
        try (DépôtLocal dépôt = DépôtLocal.démarrer()) {
            Path racine = temporaire.resolve("projet");

            // L'AAR : manifeste (paquet + provider RELATIF), classes.jar (ECJ),
            // res/, métadonnées AAR sous la cible du module.
            ByteArrayOutputStream aarOctets = new ByteArrayOutputStream();
            try (ZipOutputStream aar = new ZipOutputStream(aarOctets)) {
                aar.putNextEntry(new ZipEntry("AndroidManifest.xml"));
                aar.write(("""
                        <?xml version="1.0" encoding="utf-8"?>
                        <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                                package="demo.lib">
                            <uses-sdk android:minSdkVersion="21"/>
                            <application>
                                <provider android:name=".FournisseurLib"
                                        android:authorities="demo.lib.init"
                                        android:exported="false"/>
                            </application>
                        </manifest>
                        """).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                aar.closeEntry();
                aar.putNextEntry(new ZipEntry("classes.jar"));
                aar.write(classesDeBibliothèque(racine));
                aar.closeEntry();
                aar.putNextEntry(new ZipEntry("res/values/values.xml"));
                aar.write(("""
                        <?xml version="1.0" encoding="utf-8"?>
                        <resources>
                            <string name="salutation_lib">Salutations de la bibliothèque</string>
                        </resources>
                        """).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                aar.closeEntry();
                aar.putNextEntry(new ZipEntry(
                        "META-INF/com/android/build/gradle/aar-metadata.properties"));
                aar.write("aarFormatVersion=1.0\nminCompileSdk=28\n".getBytes(
                        java.nio.charset.StandardCharsets.UTF_8));
                aar.closeEntry();
            }
            jo.forge.engine.deps.Coordinate lib =
                    new jo.forge.engine.deps.Coordinate("demo", "lib-android", "1.0");
            dépôt.publierPom(lib, """
                    <project><groupId>demo</groupId><artifactId>lib-android</artifactId>
                    <version>1.0</version><packaging>aar</packaging></project>
                    """);
            dépôt.publierAar(lib, aarOctets.toByteArray());

            Projets.fichier(racine, "forge.json", """
                    { "modelVersion": 1,
                      "repositories": [ "%s" ],
                      "modules": [
                        { "name": "app", "type": "application",
                          "dependencies": [ { "maven": "demo:lib-android:1.0" } ],
                          "android": { "manifest": "src/main/AndroidManifest.xml",
                                       "res": ["src/main/res"],
                                       "minSdk": 28, "targetSdk": 34,
                                       "namespace": "demo.app" } }
                      ] }
                    """.formatted(dépôt.url()));
            // Le manifeste de l'application UTILISE la ressource de la
            // bibliothèque : la fusion res doit la rendre visible de la liaison.
            Projets.fichier(racine, "app/src/main/AndroidManifest.xml", """
                    <?xml version="1.0" encoding="utf-8"?>
                    <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                            package="demo.app">
                        <application android:label="@string/salutation_lib">
                            <activity android:name=".MainActivity" android:exported="true">
                                <intent-filter>
                                    <action android:name="android.intent.action.MAIN"/>
                                    <category android:name="android.intent.category.LAUNCHER"/>
                                </intent-filter>
                            </activity>
                        </application>
                    </manifest>
                    """);
            Projets.fichier(racine, "app/src/main/res/values/styles.xml", """
                    <?xml version="1.0" encoding="utf-8"?>
                    <resources>
                        <style name="AppTheme" parent="android:Theme.Material.Light"/>
                    </resources>
                    """);
            // L'activité UTILISE la classe de la bibliothèque : elle doit être au
            // classpath de compilation (implementation = fermeture api, v0.2.15).
            Projets.fichier(racine, "app/src/main/java/demo/app/MainActivity.java", """
                    package demo.app;

                    import android.app.Activity;
                    import android.os.Bundle;
                    import android.widget.TextView;
                    import demo.lib.Salutations;

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

            Enregistreur journal = new Enregistreur();
            TaskExecutor.BuildResult résultat = exécuter(racine, journal);
            assertTrue(résultat.success(), "le build avec AAR doit réussir : "
                    + journal.erreurs + journal.diagnostics + " journal="
                    + journal.toutesLignes + " échec=" + résultat.failedTask());

            // Le manifeste FUSIONNÉ porte le provider de la bibliothèque (nom
            // relatif étendu au paquet de la lib).
            String fusionné = Files.readString(racine.resolve(
                    "build/app/merged/AndroidManifest.xml"));
            assertTrue(fusionné.contains("demo.lib.FournisseurLib"),
                    "le provider de la bibliothèque est fusionné : " + fusionné);

            // Le R de la bibliothèque est généré (extra-packages) et compilé.
            assertTrue(Files.isRegularFile(racine.resolve(
                    "build/app/generated/r/demo/lib/R.java")),
                    "le R de la bibliothèque est régénéré par --extra-packages");
            assertTrue(Files.isRegularFile(racine.resolve(
                    "build/app/classes/demo/lib/R.class")),
                    "…et compilé dans le module");

            // L'APK : la classe de la bibliothèque est DEXÉE (runtime), la table
            // de ressources porte la fusion.
            Path apk = racine.resolve("build/app/outputs/app-debug.apk");
            assertTrue(Files.isRegularFile(apk), "l'APK signé existe : " + apk);
            try (ZipFile zip = new ZipFile(apk.toFile())) {
                assertNotNull(zip.getEntry("resources.arsc"),
                        "la table de ressources est liée");
                byte[] dex = zip.getInputStream(zip.getEntry("classes.dex"))
                        .readAllBytes();
                String contenu = new String(dex, java.nio.charset.StandardCharsets.ISO_8859_1);
                assertTrue(contenu.contains("demo/lib/Salutations"),
                        "la classe de la bibliothèque est dexée dans l'APK");
            }
        }
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

    /** Enregistreur d'événements (harnais du pipeline Android). */
    private static final class Enregistreur implements TaskExecutor.BuildEvents {

        final Map<jo.forge.api.BuildEvent.TaskState, List<String>> états =
                new java.util.concurrent.ConcurrentHashMap<>();
        final List<jo.forge.engine.diag.Diagnostic> diagnostics =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<String> toutesLignes =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        final StringBuffer erreurs = new StringBuffer();
        private final java.util.concurrent.atomic.AtomicReference<String> premierÉchec =
                new java.util.concurrent.atomic.AtomicReference<>();

        @Override
        public String buildId() {
            return "test-android-aar";
        }

        @Override
        public void taskState(jo.forge.api.BuildEvent.TaskStateEvent event) {
            états.computeIfAbsent(event.state(),
                            k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                    .add(event.taskName());
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
        public String dernierEchec() {
            return premierÉchec.get();
        }

        @Override
        public void tacheEchouee(String taskName) {
            premierÉchec.compareAndSet(null, taskName);
        }
    }
}
