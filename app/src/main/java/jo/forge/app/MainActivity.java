package jo.forge.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import jo.forge.api.BuildEvent;
import jo.forge.api.BuildOptions;
import jo.forge.api.BuildRequest;
import jo.forge.api.BuildStarted;
import jo.forge.client.DeviceVmLauncher;
import jo.forge.client.EngineClient;
import jo.forge.client.JournalMoteur;
import jo.forge.setup.download.CancellationToken;
import jo.forge.setup.download.InstallCancelledException;
import jo.forge.setup.install.ArchiveExtractor;
import jo.forge.setup.install.ComponentSource;
import jo.forge.setup.install.EngineInstaller;
import jo.forge.setup.install.ForgeHome;
import jo.forge.setup.install.InstalledState;
import jo.forge.setup.manifest.CanalMoteur;
import jo.forge.setup.manifest.ComponentManifest;
import jo.forge.setup.manifest.EngineManifest;

/**
 * Écran principal de la coquille : état du moteur (installé et version, ou absent
 * avec ses explications), écran de build (étape 11) — chemin du projet, lancement,
 * progression, journal en flux, diagnostics, installation de l'APK produit — et
 * diagnostic exportable (étape de débogage sur appareil) : copie et partage du
 * journal de build, du journal du moteur (stderr) et de la dernière trace.
 *
 * <p>Cette activité ne contient aucune logique de construction : tout part dans le
 * moteur, processus séparé. Les textes viennent des ressources (jamais en dur).</p>
 */
public class MainActivity extends Activity {

    private TextView statusText;
    private TextView statusHint;
    private Button testButton;
    private EditText canalUrl;
    private Button boutonManifeste;
    private TextView texteComposants;
    private TextView avertissementDonnees;
    private Button boutonInstaller;
    private Button boutonAnnulerInstall;
    private ProgressBar barreInstall;
    private TextView résuméInstall;
    private Button boutonDésinstaller;
    private EditText cheminProjet;
    private Button boutonImporterExemples;
    private Button construireButton;
    private Button annulerButton;
    private Button installerButton;
    private Button boutonCopierJournaux;
    private Button boutonPartagerJournaux;
    private ProgressBar progression;
    private TextView journalBuild;
    private TextView résuméBuild;

    /** Manifeste du canal vérifié (null tant que rien n'a été chargé). */
    private EngineManifest manifesteCanal;

    /** Composants du canal applicables à l'ABI de cet appareil. */
    private List<ComponentManifest> composantsCanal;

    /** Jeton d'annulation de l'installation en cours (null si aucune). */
    private volatile CancellationToken jetonInstallation;

    private final ExecutorService exécuteur = Executors.newSingleThreadExecutor();
    private final AtomicReference<EngineClient> moteurCourant = new AtomicReference<>();

    /** Dernier moteur lancé (jamais remis à zéro : le rapport lit ses diagnostics). */
    private final AtomicReference<EngineClient> dernierClient = new AtomicReference<>();

    /** Génération du moteur courant : incrémentée à chaque arrêt attendu, pour
     * distinguer la mort attendue d'un moteur remplacé de la mort inattendue
     * du moteur en cours (seule la seconde doit alarmer l'utilisateur). */
    private final AtomicLong générationMoteur = new AtomicLong();

    /** Journal de diagnostics du moteur en cours (stderr capturé) ; remplacé à chaque
     * build — l'ancien moteur peut encore écrire dans l'ancien tampon, sans gêne. */
    private JournalMoteur journalMoteur = new JournalMoteur(JournalMoteur.CAPACITÉ_PAR_DÉFAUT);

    /** Journal des écroulements de la coquille (exceptions non attrapées) : installé
     * avant tout le reste pour qu'un « force close » laisse une trace copiable. */
    private JournalCrash journalCrash;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Avant super.onCreate : le moindre plantage de l'ouverture (branchement des
        // vues, disposition) doit être écrit dans le journal avant que le système ne
        // ferme le processus — c'est ce journal que « Copier les journaux » expose.
        journalCrash = JournalCrash.installer(getFilesDir().toPath());
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        statusText = findViewById(R.id.engine_status);
        statusHint = findViewById(R.id.engine_status_hint);
        testButton = findViewById(R.id.action_test_engine);
        canalUrl = findViewById(R.id.install_channel_url);
        boutonManifeste = findViewById(R.id.action_load_manifest);
        texteComposants = findViewById(R.id.install_components);
        avertissementDonnees = findViewById(R.id.install_warning);
        boutonInstaller = findViewById(R.id.action_install_engine);
        boutonAnnulerInstall = findViewById(R.id.action_cancel_install);
        barreInstall = findViewById(R.id.install_progress);
        résuméInstall = findViewById(R.id.install_summary);
        boutonDésinstaller = findViewById(R.id.action_uninstall_engine);
        cheminProjet = findViewById(R.id.build_project_path);
        boutonImporterExemples = findViewById(R.id.action_import_examples);
        construireButton = findViewById(R.id.action_build);
        annulerButton = findViewById(R.id.action_cancel_build);
        installerButton = findViewById(R.id.action_install_apk);
        progression = findViewById(R.id.build_progress);
        journalBuild = findViewById(R.id.build_log);
        résuméBuild = findViewById(R.id.build_summary);
        boutonCopierJournaux = findViewById(R.id.action_copy_logs);
        boutonPartagerJournaux = findViewById(R.id.action_share_logs);
        journalBuild.setMovementMethod(new ScrollingMovementMethod());

        canalUrl.setText(getString(R.string.install_channel_default));

        testButton.setOnClickListener(vue -> {
            Intent service = new Intent(this, ForgeHostService.class);
            startForegroundService(service);
        });
        boutonManifeste.setOnClickListener(vue -> chargerManifesteCanal());
        boutonInstaller.setOnClickListener(vue -> installerMoteur());
        boutonAnnulerInstall.setOnClickListener(vue -> annulerInstallation());
        boutonDésinstaller.setOnClickListener(vue -> confirmerDésinstallation());
        construireButton.setOnClickListener(vue -> lancerBuild());
        boutonImporterExemples.setOnClickListener(vue -> importerExemples());
        annulerButton.setOnClickListener(vue -> annulerBuild());
        installerButton.setOnClickListener(vue -> installerDernierApk());
        boutonCopierJournaux.setOnClickListener(vue -> copierJournaux());
        boutonPartagerJournaux.setOnClickListener(vue -> partagerJournaux());
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
        ForgeHome home = forgeHome();
        // L'installateur sert ici de lecteur d'état : aucune installation n'est tentée.
        EngineInstaller lecteur = new EngineInstaller(home, null, null);
        InstalledState installe = lecteur.currentState();

        boolean moteurDisponible = installe != null;
        construireButton.setEnabled(moteurDisponible);
        boutonDésinstaller.setVisibility(moteurDisponible
                ? android.view.View.VISIBLE : android.view.View.GONE);
        boutonInstaller.setText(moteurDisponible
                ? getString(R.string.action_install_engine_repair)
                : getString(R.string.action_install_engine));
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

    /** Racine Forge de la coquille (stockage privé). */
    private ForgeHome forgeHome() {
        return new ForgeHome(new File(getFilesDir(), "forge").toPath());
    }

    /**
     * Charge le manifeste du canal : téléchargement HTTPS, vérification de la
     * signature contre la clé épinglée, décodage, contrôle de compatibilité.
     */
    private void chargerManifesteCanal() {
        String url = canalUrl.getText().toString().trim();
        if (url.isEmpty()) {
            résuméInstall.setText(getString(R.string.install_manifest_error,
                    getString(R.string.install_channel_hint)));
            return;
        }
        boutonManifeste.setEnabled(false);
        boutonInstaller.setEnabled(false);
        résuméInstall.setText(getString(R.string.install_fetching));
        exécuteur.submit(() -> {
            try {
                CanalMoteur.ManifesteSigne lu = new CanalMoteur(
                        ClesForge.CLÉ_PUBLIQUE_MANIFESTE).telecharger(url);
                if (lu.manifeste().minAppVersion() > versionCodeCoquille()) {
                    throw new IllegalStateException(getString(
                            R.string.install_error_shell_too_old,
                            lu.manifeste().minAppVersion(), versionCodeCoquille()));
                }
                manifesteCanal = lu.manifeste();
                composantsCanal = lu.manifeste().forAbi(abiPrincipale());
                runOnUiThread(() -> {
                    afficherComposantsCanal();
                    résuméInstall.setText("");
                    boutonInstaller.setEnabled(true);
                    boutonManifeste.setEnabled(true);
                });
            } catch (Exception echec) {
                runOnUiThread(() -> {
                    résuméInstall.setText(getString(R.string.install_manifest_error,
                            messageDe(echec)));
                    boutonManifeste.setEnabled(true);
                });
            }
        });
    }

    /**
     * Affiche la liste des composants applicables avec tailles et total.
     */
    private void afficherComposantsCanal() {
        List<ComponentManifest> composants = composantsCanal;
        EngineManifest manifeste = manifesteCanal;
        if (composants == null || manifeste == null) {
            return;
        }
        long total = composants.stream().mapToLong(ComponentManifest::size).sum();
        StringBuilder liste = new StringBuilder(getString(
                R.string.install_components_header, manifeste.engineVersion(),
                composants.size(), tailleLisible(total)));
        for (ComponentManifest composant : composants) {
            liste.append('\n').append(getString(R.string.install_component_line,
                    composant.id(), composant.version(),
                    tailleLisible(composant.size())));
        }
        texteComposants.setText(liste.toString());
        avertissementDonnees.setVisibility(android.view.View.VISIBLE);
    }

    /**
     * Télécharge et installe (ou met à jour, ou répare) le moteur du canal chargé.
     */
    private void installerMoteur() {
        EngineManifest manifeste = manifesteCanal;
        List<ComponentManifest> composants = composantsCanal;
        if (manifeste == null || composants == null || composants.isEmpty()) {
            résuméInstall.setText(getString(R.string.install_error_no_manifest));
            return;
        }
        if (annulerButton.isEnabled()) {
            résuméInstall.setText(getString(R.string.install_error_build_running));
            return;
        }
        jetonInstallation = new CancellationToken();
        CancellationToken jeton = jetonInstallation;
        boutonInstaller.setEnabled(false);
        boutonManifeste.setEnabled(false);
        boutonDésinstaller.setEnabled(false);
        boutonAnnulerInstall.setEnabled(true);
        barreInstall.setMax((int) Math.min(Integer.MAX_VALUE,
                composants.stream().mapToLong(ComponentManifest::size).sum()));
        barreInstall.setProgress(0);
        résuméInstall.setText(getString(R.string.install_download_started));

        ForgeHome home = forgeHome();
        SourceProgressive source = new SourceProgressive(
                new ComponentSource.Network(home.downloads()), composants,
                (reçus, total) -> runOnUiThread(() -> {
                    barreInstall.setProgress((int) Math.min(Integer.MAX_VALUE, reçus));
                    résuméInstall.setText(getString(R.string.install_progress_line,
                            tailleLisible(reçus), tailleLisible(total)));
                }));
        exécuteur.submit(() -> {
            try {
                EngineInstaller installateur = new EngineInstaller(home, source,
                        new ArchiveExtractor());
                InstalledState etat = installateur.install(manifeste,
                        abiPrincipale(), null, jeton);
                runOnUiThread(() -> {
                    résuméInstall.setText(getString(R.string.install_finished,
                            etat.engineVersion()));
                    réinitialiserBoutonsInstall();
                    rafraichirEtat();
                });
            } catch (InstallCancelledException annulation) {
                runOnUiThread(() -> {
                    résuméInstall.setText(getString(R.string.install_cancelled));
                    réinitialiserBoutonsInstall();
                });
            } catch (Exception echec) {
                runOnUiThread(() -> {
                    résuméInstall.setText(getString(R.string.install_engine_error,
                            messageDe(echec)));
                    réinitialiserBoutonsInstall();
                });
            }
        });
    }

    /**
     * Annule l'installation en cours (le partiel est conservé pour reprise).
     */
    private void annulerInstallation() {
        CancellationToken jeton = jetonInstallation;
        if (jeton != null) {
            jeton.cancel();
        }
    }

    /**
     * Rétablit les boutons après une installation (réussie, annulée ou échouée).
     */
    private void réinitialiserBoutonsInstall() {
        boutonAnnulerInstall.setEnabled(false);
        boutonManifeste.setEnabled(true);
        boutonInstaller.setEnabled(manifesteCanal != null);
        boutonDésinstaller.setEnabled(true);
        jetonInstallation = null;
    }

    /**
     * Demande confirmation puis désinstalle le moteur.
     */
    private void confirmerDésinstallation() {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.uninstall_confirm_title))
                .setMessage(getString(R.string.uninstall_confirm_message))
                .setPositiveButton(getString(R.string.uninstall_confirm_ok),
                        (dialog, lequel) -> désinstallerMoteur())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Désinstalle le moteur installé (répertoire, état, téléchargements partiels).
     */
    private void désinstallerMoteur() {
        if (annulerButton.isEnabled()) {
            résuméInstall.setText(getString(R.string.install_error_build_running));
            return;
        }
        boutonDésinstaller.setEnabled(false);
        exécuteur.submit(() -> {
            try {
                new EngineInstaller(forgeHome(), null, null).uninstall();
                runOnUiThread(() -> {
                    résuméInstall.setText(getString(R.string.uninstall_done));
                    manifesteCanal = null;
                    composantsCanal = null;
                    texteComposants.setText("");
                    avertissementDonnees.setVisibility(android.view.View.GONE);
                    réinitialiserBoutonsInstall();
                    rafraichirEtat();
                });
            } catch (Exception echec) {
                runOnUiThread(() -> {
                    résuméInstall.setText(getString(R.string.uninstall_error,
                            messageDe(echec)));
                    boutonDésinstaller.setEnabled(true);
                });
            }
        });
    }

    /**
     * Taille lisible en français (Ko / Mo / Gio arrondi honnêtement).
     */
    static String tailleLisible(long octets) {
        if (octets >= 1024 * 1024) {
            return String.format(Locale.FRENCH, "%.1f Mo", octets / (1024.0 * 1024.0));
        }
        if (octets >= 1024) {
            return String.format(Locale.FRENCH, "%.1f Ko", octets / 1024.0);
        }
        return octets + " o";
    }

    /**
     * Message d'une exception sans le nom de classe quand il est inutile.
     */
    private static String messageDe(Exception echec) {
        return echec.getMessage() == null
                ? echec.getClass().getSimpleName() : echec.getMessage();
    }

    /**
     * Version de la coquille comparée au minAppVersion du manifeste.
     */
    private int versionCodeCoquille() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (Exception absent) {
            return 1;
        }
    }

    /**
     * Importe les projets d'exemple embarqués dans l'APK (assets) vers le stockage
     * privé, puis préremplit le chemin avec l'exemple Java — premier build sans
     * aucune permission de stockage.
     */
    private void importerExemples() {
        boutonImporterExemples.setEnabled(false);
        afficherRésumé(getString(R.string.import_running));
        exécuteur.submit(() -> {
            try {
                Path projets = Path.of(getFilesDir().getAbsolutePath(), "projets");
                Files.createDirectories(projets);
                List<String> importés = new ArrayList<>();
                String[] noms = getAssets().list("exemples");
                for (String nom : noms == null ? new String[0] : noms) {
                    if (!nom.endsWith(".zip")) {
                        continue;
                    }
                    Path temporaire = Files.createTempFile("forge-exemple-", ".zip");
                    try (var entrée = getAssets().open("exemples/" + nom);
                         var sortie = Files.newOutputStream(temporaire)) {
                        entrée.transferTo(sortie);
                    }
                    String nomProjet = nom.substring(0, nom.length() - ".zip".length());
                    Path destination = projets.resolve(nomProjet);
                    // Réimport propre : l'ancien exemplaire (sorties de build incluses)
                    // est supprimé — les fichiers du projet restent écrasables.
                    jo.forge.setup.util.Files2.supprimerRecursivement(destination);
                    new ArchiveExtractor().extract(temporaire, destination);
                    Files.deleteIfExists(temporaire);
                    importés.add(nomProjet);
                }
                if (importés.isEmpty()) {
                    throw new IllegalStateException(
                            "aucun exemple embarqué dans l'APK");
                }
                List<String> liste = List.copyOf(importés);
                runOnUiThread(() -> {
                    cheminProjet.setText(Path.of(getFilesDir().getAbsolutePath(),
                            "projets", "multi-modules-java").toString());
                    afficherRésumé(getString(R.string.import_done,
                            String.join(", ", liste)));
                    boutonImporterExemples.setEnabled(true);
                });
            } catch (Exception echec) {
                runOnUiThread(() -> {
                    afficherRésumé(getString(R.string.import_error, messageDe(echec)));
                    boutonImporterExemples.setEnabled(true);
                });
            }
        });
    }

    /**
     * Lance le build du projet indiqué dans le moteur installé (processus séparé) et
     * affiche le flux d'événements au fil de l'eau. Le moteur du build précédent est
     * arrêté d'abord : un moteur oisif par build finirait par épuiser la mémoire de
     * l'appareil et attirer le tueur de processus au pire moment.
     */
    private void lancerBuild() {
        String chemin = cheminProjet.getText().toString().trim();
        if (chemin.isEmpty()) {
            afficherRésumé(getString(R.string.build_error_no_path));
            return;
        }
        EngineInstaller lecteur = new EngineInstaller(forgeHome(), null, null);
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
                arrêterMoteurCourant();
                long génération = générationMoteur.get();
                JournalMoteur journal = new JournalMoteur(JournalMoteur.CAPACITÉ_PAR_DÉFAUT);
                journalMoteur = journal;
                EngineClient moteur = EngineSession.launchInstalled(
                        new DeviceVmLauncher(), forgeHome(), installe, versionCoquille(),
                        événements,
                        // Mort du moteur sur son PROPRE fil : le sondage du code de sortie
                        // peut durer 2,5 s — sur la file mono-fil de la coquille, il
                        // retardait l'arrêt du moteur (l'arrêt est mis en file APRÈS la
                        // fin du build, donc APRÈS ce sondage) et le diagnostic lisait
                        // « processus encore vivant » d'un moteur jamais prié de partir.
                        () -> new Thread(() -> publierMortMoteur(génération),
                                "forge-mort-moteur").start(),
                        false, journal::ajouter);
                moteurCourant.set(moteur);
                dernierClient.set(moteur);
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
     * Arrête proprement le moteur du build en cours (accusé puis mort naturelle) ; le
     * détruit en secours s'il n'accuse pas. La génération est avancée : la mort qui
     * suit est attendue, elle ne doit pas alarmer l'écran.
     */
    private void arrêterMoteurCourant() {
        EngineClient moteur = moteurCourant.getAndSet(null);
        if (moteur == null) {
            return;
        }
        générationMoteur.incrementAndGet();
        try {
            moteur.shutdown().get(3, TimeUnit.SECONDS);
        } catch (Exception injoignable) {
            moteur.kill();
        }
    }

    /**
     * Mort du moteur : publie le code de sortie et la queue de son journal stderr
     * dans le journal affiché — c'est l'unique trace d'un écroulement (mémoire
     * saturée, signal du noyau, exception non attrapée). Silencieux si la mort est
     * attendue (moteur remplacé ou arrêté après la fin du build).
     *
     * @param génération génération du moteur mort (comparée à la courante).
     */
    private void publierMortMoteur(long génération) {
        if (générationMoteur.get() != génération) {
            return; // Mort attendue : moteur déjà arrêté ou remplacé.
        }
        EngineClient moteur = dernierClient.get();
        Integer code = null;
        if (moteur != null) {
            // La sentinelle peut publier le code quelques instants après l'EOF.
            long fin = System.currentTimeMillis() + 2500;
            while (System.currentTimeMillis() < fin) {
                code = moteur.codeSortie();
                if (code != null) {
                    break;
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException interruption) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        Integer codeFinal = code;
        List<String> queue = journalMoteur.dernières(40);
        runOnUiThread(() -> {
            afficherRésumé(getString(R.string.host_engine_dead));
            ajouterLigne(codeFinal == null
                    // Canal fermé mais processus vivant : le dire tel quel, sans
                    // inventer un code ni prétendre que le moteur s'est arrêté.
                    ? getString(R.string.logs_engine_channel_closed)
                    : getString(R.string.logs_engine_exit_line,
                            RapportDiagnostic.décrireCodeSortie(codeFinal)));
            if (!queue.isEmpty()) {
                ajouterLigne(getString(R.string.logs_engine_stderr_tail_header));
                queue.forEach(this::ajouterLigne);
            }
            réinitialiserBoutons();
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
     * et propose l'installation si un APK a été produit, puis libère la VM du moteur
     * (un build terminé n'a plus besoin d'un processus de 512 Mo qui traîne).
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
            exécuteur.submit(this::arrêterMoteurCourant);
        }
    }

    /**
     * Copie le rapport de diagnostic complet (contexte, journal de build, journal du
     * moteur, dernière trace) dans le presse-papiers.
     */
    private void copierJournaux() {
        boutonCopierJournaux.setEnabled(false);
        exécuteur.submit(() -> {
            String rapport = composerRapport();
            runOnUiThread(() -> {
                ClipboardManager pressePapiers = getSystemService(ClipboardManager.class);
                if (pressePapiers != null) {
                    pressePapiers.setPrimaryClip(ClipData.newPlainText(
                            getString(R.string.logs_clip_label), rapport));
                }
                Toast.makeText(this, getString(R.string.logs_copied, rapport.length()),
                        Toast.LENGTH_SHORT).show();
                boutonCopierJournaux.setEnabled(true);
            });
        });
    }

    /**
     * Partage le même rapport par une application au choix (courriel, dépôt de notes,
     * messagerie) — le chemin le plus court pour sortir un journal de l'appareil.
     */
    private void partagerJournaux() {
        exécuteur.submit(() -> {
            String rapport = composerRapport();
            runOnUiThread(() -> {
                Intent envoi = new Intent(Intent.ACTION_SEND);
                envoi.setType("text/plain");
                envoi.putExtra(Intent.EXTRA_TEXT, rapport);
                envoi.putExtra(Intent.EXTRA_TITLE, getString(R.string.logs_share_title));
                startActivity(Intent.createChooser(envoi,
                        getString(R.string.logs_share_title)));
            });
        });
    }

    /**
     * Assemble le rapport : contexte de l'appareil et des versions, journal de build
     * affiché, journal stderr du moteur capturé, dernière trace JSON du projet.
     */
    private String composerRapport() {
        EngineInstaller lecteur = new EngineInstaller(forgeHome(), null, null);
        InstalledState installe = lecteur.currentState();
        EngineClient moteur = dernierClient.get();
        RapportDiagnostic.Contexte contexte = new RapportDiagnostic.Contexte(
                versionCoquille(), versionCodeCoquille(),
                installe == null ? "non installé" : installe.engineVersion(),
                moteur == null ? null : moteur.codeSortie(),
                "Android " + Build.VERSION.RELEASE, Build.VERSION.SDK_INT,
                abiPrincipale(), mémoireTotaleMo(), Build.MODEL,
                cheminProjet.getText().toString().trim(),
                résuméBuild.getText().toString());
        List<String> lignesBuild = lignesDe(journalBuild.getText().toString());
        return RapportDiagnostic.composer(contexte, lignesBuild,
                journalMoteur.copie(),
                lignesDe(journalCrash.copie()),
                dernièreTrace());
    }

    /**
     * Découpe un texte en lignes sans {@code String.lines()} (API 33+) ni
     * {@code Stream.toList()} (API 34) : la coquille promet minSdk 28, et le
     * rapport de diagnostic doit se composer sur tout appareil compatible.
     *
     * @param texte le texte à découper (fins de ligne CR et CRLF normalisées).
     * @return les lignes, éventuellement vide (jamais null).
     */
    private static List<String> lignesDe(String texte) {
        String normalisé = texte.replace("\r\n", "\n").replace('\r', '\n');
        if (normalisé.isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(Arrays.asList(normalisé.split("\n")));
    }

    /**
     * Dernière trace JSON écrite par le moteur sous {@code <projet>/.forge/traces},
     * la plus récente par date de modification (null si aucune).
     */
    private RapportDiagnostic.TraceDernière dernièreTrace() {
        String chemin = cheminProjet.getText().toString().trim();
        if (chemin.isEmpty()) {
            return null;
        }
        Path traces = Path.of(chemin, ".forge", "traces");
        if (!Files.isDirectory(traces)) {
            return null;
        }
        try (var parcours = Files.walk(traces, 1)) {
            Path dernière = parcours.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .max(Comparator.comparingLong(p -> p.toFile().lastModified()))
                    .orElse(null);
            if (dernière == null) {
                return null;
            }
            return new RapportDiagnostic.TraceDernière(
                    dernière.getFileName().toString(),
                    new String(Files.readAllBytes(dernière),
                            java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception echec) {
            return null;
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
