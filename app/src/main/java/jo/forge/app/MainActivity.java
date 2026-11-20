package jo.forge.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import jo.forge.api.BuildEvent;
import jo.forge.api.BuildOptions;
import jo.forge.api.BuildRequest;
import jo.forge.api.BuildStarted;
import jo.forge.client.DeviceVmLauncher;
import jo.forge.client.EngineClient;
import jo.forge.setup.install.EngineInstaller;
import jo.forge.setup.install.ForgeHome;
import jo.forge.setup.install.InstalledState;

/**
 * Écran principal de la coquille : état du moteur (installé et version, ou absent
 * avec ses explications) et écran de build (étape 11) — chemin du projet, lancement,
 * progression, journal en flux, diagnostics, installation de l'APK produit.
 *
 * <p>Cette activité ne contient aucune logique de construction : tout part dans le
 * moteur, processus séparé. Les textes viennent des ressources (jamais en dur).</p>
 */
public class MainActivity extends Activity {

    private TextView statusText;
    private TextView statusHint;
    private Button testButton;
    private EditText cheminProjet;
    private Button construireButton;
    private Button annulerButton;
    private Button installerButton;
    private ProgressBar progression;
    private TextView journalBuild;
    private TextView résuméBuild;

    private final ExecutorService exécuteur = Executors.newSingleThreadExecutor();
    private final AtomicReference<EngineClient> moteurCourant = new AtomicReference<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        statusText = findViewById(R.id.engine_status);
        statusHint = findViewById(R.id.engine_status_hint);
        testButton = findViewById(R.id.action_test_engine);
        cheminProjet = findViewById(R.id.build_project_path);
        construireButton = findViewById(R.id.action_build);
        annulerButton = findViewById(R.id.action_cancel_build);
        installerButton = findViewById(R.id.action_install_apk);
        progression = findViewById(R.id.build_progress);
        journalBuild = findViewById(R.id.build_log);
        résuméBuild = findViewById(R.id.build_summary);
        journalBuild.setMovementMethod(new ScrollingMovementMethod());

        testButton.setOnClickListener(vue -> {
            Intent service = new Intent(this, ForgeHostService.class);
            startForegroundService(service);
        });
        construireButton.setOnClickListener(vue -> lancerBuild());
        annulerButton.setOnClickListener(vue -> annulerBuild());
        installerButton.setOnClickListener(vue -> installerDernierApk());
    }

    @Override
    protected void onResume() {
        super.onResume();
        rafraichirEtat();
    }

    @Override
    protected void onDestroy() {
        exécuteur.shutdownNow();
        super.onDestroy();
    }

    /**
     * Met à jour l'écran depuis le registre d'installation et l'état du service hôte.
     */
    private void rafraichirEtat() {
        ForgeHome home = new ForgeHome(new File(getFilesDir(), "forge").toPath());
        // L'installateur sert ici de lecteur d'état : aucune installation n'est tentée.
        EngineInstaller lecteur = new EngineInstaller(home, null, null);
        InstalledState installe = lecteur.currentState();

        boolean moteurDisponible = installe != null;
        construireButton.setEnabled(moteurDisponible);
        if (!moteurDisponible) {
            statusText.setText(R.string.status_engine_missing);
            statusHint.setText(R.string.status_engine_missing_hint);
            testButton.setEnabled(false);
            return;
        }
        statusText.setText(getString(R.string.status_engine_installed,
                installe.engineVersion()));
        String detail = ForgeHostService.hostStateDetail();
        statusHint.setText(detail.isEmpty()
                ? getString(R.string.host_notification_starting) : detail);
        testButton.setEnabled(true);
    }

    /**
     * Lance le build du projet indiqué dans le moteur installé (processus séparé) et
     * affiche le flux d'événements au fil de l'eau.
     */
    private void lancerBuild() {
        String chemin = cheminProjet.getText().toString().trim();
        if (chemin.isEmpty()) {
            afficherRésumé(getString(R.string.build_error_no_path));
            return;
        }
        ForgeHome home = new ForgeHome(new File(getFilesDir(), "forge").toPath());
        EngineInstaller lecteur = new EngineInstaller(home, null, null);
        InstalledState installe = lecteur.currentState();
        if (installe == null) {
            afficherRésumé(getString(R.string.status_engine_missing));
            return;
        }
        construireButton.setEnabled(false);
        annulerButton.setEnabled(true);
        installerButton.setEnabled(false);
        journalBuild.setText("");
        progression.setIndeterminate(true);
        afficherRésumé(getString(R.string.build_starting));

        Consumer<BuildEvent> événements = événement ->
                runOnUiThread(() -> afficherÉvénement(événement));
        exécuteur.submit(() -> {
            try {
                EngineClient moteur = EngineSession.launchInstalled(
                        new DeviceVmLauncher(), home, installe, versionCoquille(),
                        événements, () -> runOnUiThread(() -> {
                            afficherRésumé(getString(R.string.host_engine_dead));
                            réinitialiserBoutons();
                        }), false);
                moteurCourant.set(moteur);
                String buildId = "app-" + System.currentTimeMillis();
                dernierBuildId = buildId;
                BuildRequest requête = new BuildRequest(buildId, chemin,
                        new BuildOptions(2, false, false, false, mémoireTotaleMo(),
                                abiPrincipale()));
                BuildStarted démarré = moteur.startBuild(requête).get();
                runOnUiThread(() -> afficherRésumé(
                        getString(R.string.build_started, démarré.buildId())));
            } catch (Exception echec) {
                runOnUiThread(() -> {
                    afficherRésumé(getString(R.string.build_error,
                            echec.getMessage() == null
                                    ? echec.getClass().getSimpleName()
                                    : echec.getMessage()));
                    réinitialiserBoutons();
                });
            }
        });
    }

    /**
     * Annule le build en cours.
     */
    private void annulerBuild() {
        EngineClient moteur = moteurCourant.get();
        if (moteur != null) {
            exécuteur.submit(() -> {
                try {
                    moteur.cancel(moteurCourantBuildId());
                } catch (Exception ignored) {
                    // Le moteur est peut-être déjà arrêté : rien à annuler.
                }
            });
        }
    }

    /**
     * Installe le dernier APK produit par un build réussi (confirmation système).
     */
    private void installerDernierApk() {
        Path apk = dernierApkConstruit();
        if (apk == null) {
            afficherRésumé(getString(R.string.install_error_no_apk));
            return;
        }
        exécuteur.submit(() -> {
            try {
                InstallateurApk.installer(this, apk);
                runOnUiThread(() -> afficherRésumé(
                        getString(R.string.install_started, apk.getFileName())));
            } catch (Exception echec) {
                runOnUiThread(() -> afficherRésumé(getString(R.string.install_error,
                        echec.getMessage() == null
                                ? echec.getClass().getSimpleName()
                                : echec.getMessage())));
            }
        });
    }

    /**
     * Affiche un événement de build dans le journal ; à la fin, rétablit les boutons
     * et propose l'installation si un APK a été produit.
     */
    private void afficherÉvénement(BuildEvent événement) {
        if (événement instanceof BuildEvent.LogEvent journal) {
            ajouterLigne(journal.message());
        } else if (événement instanceof BuildEvent.DiagnosticEvent diagnostic) {
            ajouterLigne(getString(R.string.build_diagnostic_line,
                    diagnostic.file().isEmpty() ? "?" : diagnostic.file(),
                    diagnostic.line(), diagnostic.message()));
        } else if (événement instanceof BuildEvent.TaskStateEvent tâche) {
            ajouterLigne(getString(R.string.build_task_line, tâche.taskName(),
                    nomÉtat(tâche.state())));
        } else if (événement instanceof BuildEvent.ProgressEvent progression) {
            this.progression.setIndeterminate(false);
            this.progression.setMax(Math.max(1, progression.tasksTotal()));
            this.progression.setProgress(Math.min(progression.tasksDone(),
                    progression.tasksTotal()));
        } else if (événement instanceof BuildEvent.BuildFinished fin) {
            progression.setIndeterminate(false);
            afficherRésumé(fin.success()
                    ? getString(R.string.build_finished_ok, fin.durationMs())
                    : getString(R.string.build_finished_ko,
                            fin.failedTask() == null ? "?" : fin.failedTask(),
                            fin.message()));
            réinitialiserBoutons();
            installerButton.setEnabled(fin.success() && dernierApkConstruit() != null);
        }
    }

    private void ajouterLigne(String ligne) {
        journalBuild.append(ligne);
        journalBuild.append("\n");
    }

    private void afficherRésumé(String texte) {
        résuméBuild.setText(texte);
    }

    private void réinitialiserBoutons() {
        construireButton.setEnabled(true);
        annulerButton.setEnabled(false);
    }

    /**
     * Nom affichable d'un état de tâche.
     */
    private String nomÉtat(BuildEvent.TaskState état) {
        int identifiant = switch (état) {
            case STARTED -> R.string.task_state_started;
            case SUCCEEDED -> R.string.task_state_succeeded;
            case FAILED -> R.string.task_state_failed;
            case SKIPPED -> R.string.task_state_skipped;
            case UP_TO_DATE -> R.string.task_state_up_to_date;
        };
        return getString(identifiant);
    }

    /**
     * Dernier APK signé produit sous la racine du projet courant.
     */
    private Path dernierApkConstruit() {
        String chemin = cheminProjet.getText().toString().trim();
        if (chemin.isEmpty()) {
            return null;
        }
        Path sorties = Path.of(chemin, "build");
        if (!Files.isDirectory(sorties)) {
            return null;
        }
        try (var parcours = Files.walk(sorties)) {
            return parcours.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith("-debug.apk"))
                    .findFirst().orElse(null);
        } catch (Exception echec) {
            return null;
        }
    }

    private String moteurCourantBuildId() {
        return dernierBuildId;
    }

    /** Dernier identifiant de build lancé (pour l'annulation). */
    private volatile String dernierBuildId = "";

    private String versionCoquille() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception absent) {
            return "inconnue";
        }
    }

    private long mémoireTotaleMo() {
        android.app.ActivityManager gestionnaire =
                getSystemService(android.app.ActivityManager.class);
        android.app.ActivityManager.MemoryInfo mémoire =
                new android.app.ActivityManager.MemoryInfo();
        gestionnaire.getMemoryInfo(mémoire);
        return mémoire.totalMem / (1024 * 1024);
    }

    private String abiPrincipale() {
        return Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0
                ? Build.SUPPORTED_ABIS[0] : "arm64-v8a";
    }
}
