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
import java.util.concurrent.atomic.AtomicInteger;
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
    private Button synchroniserButton;
    private Button annulerButton;
    private Button installerButton;
    private Button boutonVerbosité;
    private Button boutonCopierJournaux;
    private Button boutonPartagerJournaux;
    private ProgressBar progression;
    private TextView journalBuild;
    private TextView résuméBuild;

    /**
     * Verbosité du journal de build — le modèle des consoles de build classiques :
     * essentiel (lignes de tâches, avertissements, verdict), détaillé (+ informations
     * et téléchargements), tout (+ débogage).
     */
    private enum Verbosité {
        ESSENTIEL, DÉTAILLÉ, TOUT
    }

    private Verbosité verbosité = Verbosité.ESSENTIEL;

    /** Clé de persistance de la verbosité (constat du rapport du 2026-11-21 : le
     * champ vivait en mémoire seule — un redémarrage de la coquille le remettait à
     * « essentiel », et l'utilisateur croyait ses téléchargements disparus du
     * journal). */
    private static final String PRÉFÉRENCES = "forge-coquille";
    private static final String CLÉ_VERBOSITÉ = "verbosité";

    /** Fichier en cours de téléchargement (ligne vivante du résumé). */
    private String téléchargementCourant = "";

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

    /** Build en cours côté moteur (décision D-20) : vrai entre l'accusé de
     * démarrage et le verdict final — une mort du moteur dans cet intervalle
     * est REPRISE automatiquement, pas affichée comme un échec. */
    private volatile boolean buildEnCours = false;

    /**
     * Le moteur courant compose-t-il lui-même les lignes de la console (protocole
     * 4, décision D-21) ? Vrai : la coquille n'AFFICHE plus que les lignes reçues
     * (le moteur dicte le format, exactement comme Gradle dicte ses sorties) et le
     * tri se fait côté moteur par l'argument {@code consoleLevel}. Faux (moteur plus
     * ancien) : la coquille retombe sur sa composition locale d'avant — le protocole
     * négocié dit laquelle des deux consoles vit.
     */
    private volatile boolean consoleDuMoteur = false;

    /** Chemin du build en cours (reprise automatique D-20). */
    private volatile String buildChemin = "";

    /** Le build en cours est-il un sync (reprise automatique D-20). */
    private volatile boolean buildSync = false;

    /** Tentative courante du build (1 = initiale ; +1 à chaque reprise D-20). */
    private final AtomicInteger tentativesBuild = new AtomicInteger(1);

    /** Maximum de tentatives d'un même build (D-20) : l'initiale + 2 reprises. */
    private static final int TENTATIVES_BUILD_MAX = 3;

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
        synchroniserButton = findViewById(R.id.action_sync);
        annulerButton = findViewById(R.id.action_cancel_build);
        installerButton = findViewById(R.id.action_install_apk);
        boutonVerbosité = findViewById(R.id.action_log_verbosity);
        progression = findViewById(R.id.build_progress);
        journalBuild = findViewById(R.id.build_log);
        résuméBuild = findViewById(R.id.build_summary);
        boutonCopierJournaux = findViewById(R.id.action_copy_logs);
        boutonPartagerJournaux = findViewById(R.id.action_share_logs);
        journalBuild.setMovementMethod(new ScrollingMovementMethod());
        chargerVerbosité();
        mettreÀJourBoutonVerbosité();

        canalUrl.setText(getString(R.string.install_channel_default));

        testButton.setOnClickListener(vue -> {
            Intent service = new Intent(this, ForgeHostService.class);
            startForegroundService(service);
        });
        boutonManifeste.setOnClickListener(vue -> chargerManifesteCanal());
        boutonInstaller.setOnClickListener(vue -> installerMoteur());
        boutonAnnulerInstall.setOnClickListener(vue -> annulerInstallation());
        boutonDésinstaller.setOnClickListener(vue -> confirmerDésinstallation());
        construireButton.setOnClickListener(vue -> lancerBuild(false));
        synchroniserButton.setOnClickListener(vue -> lancerBuild(true));
        boutonImporterExemples.setOnClickListener(vue -> importerExemples());
        annulerButton.setOnClickListener(vue -> annulerBuild());
        installerButton.setOnClickListener(vue -> installerDernierApk());
        boutonCopierJournaux.setOnClickListener(vue -> copierJournaux());
        boutonPartagerJournaux.setOnClickListener(vue -> partagerJournaux());
        boutonVerbosité.setOnClickListener(vue -> {
            verbosité = switch (verbosité) {
                case ESSENTIEL -> Verbosité.DÉTAILLÉ;
                case DÉTAILLÉ -> Verbosité.TOUT;
                case TOUT -> Verbosité.ESSENTIEL;
            };
            getSharedPreferences(PRÉFÉRENCES, MODE_PRIVATE).edit()
                    .putString(CLÉ_VERBOSITÉ, verbosité.name()).apply();
            mettreÀJourBoutonVerbosité();
        });

        // Constat M-1 : réparer au démarrage une installation interrompue (un moteur
        // mis à l'écart par une réinstallation tuée entre deux renommages, jamais
        // remplacé) AVANT que l'utilisateur ne touche aux boutons — l'ancien moteur
        // reprend sa place et redevient utilisable sans aucune action.
        exécuteur.submit(() -> {
            EngineInstaller lecteur = new EngineInstaller(forgeHome(), null, null);
            if (lecteur.rétablirSiInterrompue()) {
                runOnUiThread(() -> afficherRésumé(
                        getString(R.string.install_recovered_after_interrupt)));
            }
        });
    }

    /** Charge la verbosité mémorisée (défaut : essentiel). */
    private void chargerVerbosité() {
        String mémorisée = getSharedPreferences(PRÉFÉRENCES, MODE_PRIVATE)
                .getString(CLÉ_VERBOSITÉ, Verbosité.ESSENTIEL.name());
        try {
            verbosité = Verbosité.valueOf(mémorisée);
        } catch (IllegalArgumentException inconnue) {
            verbosité = Verbosité.ESSENTIEL;
        }
    }

    /** Texte du bouton de verbosité selon le niveau courant. */
    private void mettreÀJourBoutonVerbosité() {
        int identifiant = switch (verbosité) {
            case ESSENTIEL -> R.string.verbosity_essential;
            case DÉTAILLÉ -> R.string.verbosity_detailed;
            case TOUT -> R.string.verbosity_everything;
        };
        boutonVerbosité.setText(getString(R.string.action_log_verbosity,
                getString(identifiant)));
    }

    /**
     * Un niveau de journal est-il affiché à la verbosité courante ? (Mode composition
     * locale uniquement : face à un moteur protocole 4, le tri est déjà fait côté
     * moteur par l'argument {@code consoleLevel}.)
     *
     * @param niveau le niveau de l'entrée.
     * @return {@code true} si l'entrée doit figurer au journal.
     */
    private boolean niveauAffiché(BuildEvent.LogEvent.LogLevel niveau) {
        return switch (verbosité) {
            case ESSENTIEL -> niveau == BuildEvent.LogEvent.LogLevel.WARN
                    || niveau == BuildEvent.LogEvent.LogLevel.ERROR;
            case DÉTAILLÉ -> niveau != BuildEvent.LogEvent.LogLevel.DEBUG;
            case TOUT -> true;
        };
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
        synchroniserButton.setEnabled(moteurDisponible);
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
                        ClesForge.CLÉ_PUBLIQUE_MANIFESTE,
                        ClesForge.CLÉ_PUBLIQUE_MANIFESTE_SUIVANTE).telecharger(url);
                if (lu.manifeste().minAppVersion() > versionCodeCoquille()) {
                    throw new IllegalStateException(getString(
                            R.string.install_error_shell_too_old,
                            lu.manifeste().minAppVersion(), versionCodeCoquille()));
                }
                // Porte anti-recul (constat M-2) : un manifeste signé mais ANCIEN
                // — rejoué par un miroir périmé ou un canal compromis — est refusé
                // AVANT tout téléchargement de composant.
                String refusRecul = new EngineInstaller(forgeHome(), null, null)
                        .refusAntiRecul(lu.manifeste());
                if (refusRecul != null) {
                    throw new IllegalStateException(getString(
                            R.string.install_manifest_rollback_refused, refusRecul));
                }
                manifesteCanal = lu.manifeste();
                composantsCanal = lu.manifeste().forAbi(abiPrincipale());
                // Quels composants sont déjà en cache ? (vérification d'intégrité
                // locale : quelques instants d'E/S, aucun réseau — c'est l'information
                // « déjà téléchargé » de la liste et le total RÉEL de la barre.)
                EngineInstaller lecteurCache = new EngineInstaller(forgeHome(), null, null);
                java.util.Set<String> enCache = new java.util.HashSet<>();
                for (ComponentManifest composant : composantsCanal) {
                    if (lecteurCache.composantEnCache(composant)) {
                        enCache.add(composant.id());
                    }
                }
                final java.util.Set<String> enCacheFinal = enCache;
                runOnUiThread(() -> {
                    afficherComposantsCanal(enCacheFinal);
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
     * Affiche la liste des composants applicables avec tailles et total, en signalant
     * ceux que le cache local dispensera de télécharger (économie de données).
     *
     * @param enCache identifiants des composants déjà présents dans le cache local.
     */
    private void afficherComposantsCanal(java.util.Set<String> enCache) {
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
            if (enCache != null && enCache.contains(composant.id())) {
                liste.append('\n').append(getString(
                        R.string.install_component_line_cached,
                        composant.id(), composant.version()));
            } else {
                liste.append('\n').append(getString(R.string.install_component_line,
                        composant.id(), composant.version(),
                        tailleLisible(composant.size())));
            }
        }
        if (enCache != null && !enCache.isEmpty()) {
            long économisés = composants.stream()
                    .filter(composant -> enCache.contains(composant.id()))
                    .mapToLong(ComponentManifest::size).sum();
            liste.append('\n').append(getString(R.string.install_components_savings,
                    tailleLisible(total - économisés), tailleLisible(économisés)));
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
        // Barre de progression sur le volume RÉELLEMENT téléchargé : les composants
        // servis par le cache local ne traversent pas le réseau (l'installateur
        // re-vérifie l'état du cache à l'instant de l'installation).
        EngineInstaller lecteurCache = new EngineInstaller(forgeHome(), null, null);
        long àTélécharger = composants.stream()
                .filter(composant -> !lecteurCache.composantEnCache(composant))
                .mapToLong(ComponentManifest::size).sum();
        barreInstall.setMax((int) Math.min(Integer.MAX_VALUE,
                Math.max(1, àTélécharger)));
        barreInstall.setProgress(0);
        résuméInstall.setText(getString(R.string.install_download_started));

        ForgeHome home = forgeHome();
        // Le même jeu de composants en cache sert à la barre ET au total annoncé par
        // la source de progression : seuls les composants à télécharger comptent.
        java.util.Set<String> enCache = new java.util.HashSet<>();
        for (ComponentManifest composant : composants) {
            if (lecteurCache.composantEnCache(composant)) {
                enCache.add(composant.id());
            }
        }
        SourceProgressive source = new SourceProgressive(
                new ComponentSource.Network(home.downloads()), composants, enCache,
                (reçus, total) -> runOnUiThread(() -> {
                    barreInstall.setProgress((int) Math.min(Integer.MAX_VALUE, reçus));
                    résuméInstall.setText(getString(R.string.install_progress_line,
                            tailleLisible(reçus), tailleLisible(total)));
                }));
        exécuteur.submit(() -> {
            try {
                EngineInstaller installateur = new EngineInstaller(home, source,
                        new ArchiveExtractor());
                EngineInstaller.RésultatInstallation résultat = installateur.install(
                        manifeste, abiPrincipale(), null, jeton);
                runOnUiThread(() -> {
                    if (résultat.octetsÉvités() > 0) {
                        résuméInstall.setText(getString(
                                R.string.install_finished_savings,
                                résultat.état().engineVersion(),
                                tailleLisible(résultat.octetsÉvités()),
                                résultat.composantsRéutilisés().size()));
                    } else {
                        résuméInstall.setText(getString(R.string.install_finished,
                                résultat.état().engineVersion()));
                    }
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
                Path projets = java.nio.file.Paths.get(getFilesDir().getAbsolutePath(),
                        "projets");
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
                        // Copie manuelle : transferTo exige l'API 33, la coquille
                        // promet minSdk 28 (plantage à l'import sinon).
                        byte[] tampon = new byte[8192];
                        int lus;
                        while ((lus = entrée.read(tampon)) >= 0) {
                            sortie.write(tampon, 0, lus);
                        }
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
                    cheminProjet.setText(java.nio.file.Paths.get(
                            getFilesDir().getAbsolutePath(),
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
     * Lance le build (ou le sync : résolution des dépendances seule, protocole 2) du
     * projet indiqué dans le moteur installé (processus séparé) et affiche le flux
     * d'événements au fil de l'eau. Le moteur du build précédent est arrêté d'abord :
     * un moteur oisif par build finirait par épuiser la mémoire de l'appareil et
     * attirer le tueur de processus au pire moment. Un moteur trop ancien refuse le
     * sync proprement — le message l'explique, le canal reste utilisable.
     */
    private void lancerBuild(boolean sync) {
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
        synchroniserButton.setEnabled(false);
        annulerButton.setEnabled(true);
        installerButton.setEnabled(false);
        journalBuild.setText("");
        téléchargementCourant = "";
        progression.setIndeterminate(true);
        afficherRésumé(getString(R.string.build_starting));
        tentativesBuild.set(1); // Tentative initiale (les reprises D-20 suivent).
        exécuteur.submit(() -> lancerMoteurEtBuild(chemin, sync));
    }

    /**
     * Lance le moteur puis lui soumet le build — le cœur PARTAGÉ par le premier
     * passage et les REPRISES automatiques (décision D-20) : le tueur de processus
     * d'Android 12+ (D-02) peut faucher le moteur au milieu d'un build ; depuis la
     * v0.2.23 la coquille relance ELLE-MÊME le moteur et le build, sans rien
     * demander à l'utilisateur — les caches du moteur (résolution, seaux de dex,
     * empreintes de tâches — décisions D-16/D-18) font que chaque tentative part de
     * plus près du but et le build converge.
     *
     * @param chemin chemin du projet à construire.
     * @param sync   true pour un sync (résolution seule), false pour un build.
     */
    private void lancerMoteurEtBuild(String chemin, boolean sync) {
        Consumer<BuildEvent> événements = événement ->
                runOnUiThread(() -> afficherÉvénement(événement));
        try {
            // L'état d'installation est RELU à chaque tentative : une reprise peut
            // suivre une réparation ou un changement de canal entre-temps.
            EngineInstaller lecteur = new EngineInstaller(forgeHome(), null, null);
            InstalledState installe = lecteur.currentState();
            if (installe == null) {
                throw new IllegalStateException(getString(R.string.status_engine_missing));
            }
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
                    // v0.2.15 : mode basse mémoire (tas moteur 256 Mo au lieu de
                    // 1024) sur les appareils modestes — le tas par défaut a monté
                    // pour le dexage d'une fermeture Compose (~60 Mo de classes en
                    // processus par d8), il ne faut pas l'imposer à 2 Go de RAM.
                    mémoireTotaleMo() < 2500, journal::ajouter);
            moteurCourant.set(moteur);
            dernierClient.set(moteur);
            consoleDuMoteur = moteur.protocolVersion() >= 4;
            String buildId = (sync ? "sync-" : "app-") + System.currentTimeMillis();
            dernierBuildId = buildId;
            // Le tri des sorties est un ARGUMENT du moteur (D-21, l'équivalent des
            // -q/-i/-d de Gradle) : la verbosité choisie part avec la requête, le
            // moteur n'émet que ce niveau et plus.
            BuildRequest requête = new BuildRequest(buildId, chemin,
                    new BuildOptions(2, false, false, false, mémoireTotaleMo(),
                            abiPrincipale(), niveauConsoleDe(verbosité)));
            BuildStarted démarré = sync
                    ? moteur.sync(requête).get()
                    : moteur.startBuild(requête).get();
            buildChemin = chemin;
            buildSync = sync;
            buildEnCours = true; // D-20 : la mort du moteur est désormais REPRISE.
            runOnUiThread(() -> afficherRésumé(sync
                    ? getString(R.string.sync_started, démarré.buildId())
                    : getString(R.string.build_started, démarré.buildId())));
        } catch (Exception echec) {
            buildEnCours = false;
            runOnUiThread(() -> {
                afficherRésumé(getString(sync ? R.string.sync_error : R.string.build_error,
                        echec.getMessage() == null
                                ? echec.getClass().getSimpleName()
                                : echec.getMessage()));
                réinitialiserBoutons();
            });
        }
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
        // D-20 — reprise automatique : un moteur fauché AU MILIEU d'un build est
        // relancé et le build re-soumis (constat appareil v0.2.22 : « le moteur a
        // été tué, j'ai relancé, réussi » — la coquille fait désormais la relance
        // elle-même). Les tentatives sont bornées ; au-delà, l'échec est affiché
        // comme avant. Un build terminé ou annulé n'est jamais repris.
        int tentative = 0;
        boolean reprendre = false;
        if (buildEnCours) {
            tentative = tentativesBuild.incrementAndGet();
            reprendre = tentative <= TENTATIVES_BUILD_MAX;
        }
        String cheminReprise = buildChemin;
        boolean syncReprise = buildSync;
        int tentativeFinale = tentative;
        final boolean repriseAuto = reprendre;
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
            if (repriseAuto) {
                String reprise = getString(R.string.build_auto_resume,
                        tentativeFinale, TENTATIVES_BUILD_MAX);
                ajouterLigne(reprise);
                afficherRésumé(reprise);
                progression.setIndeterminate(true);
                exécuteur.submit(() -> lancerMoteurEtBuild(cheminReprise, syncReprise));
            } else {
                réinitialiserBoutons();
            }
        });
    }

    /**
     * Annule le build en cours.
     */
    private void annulerBuild() {
        buildEnCours = false; // D-20 : un build ANNULÉ ne se reprend jamais seul.
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
     * Affiche un événement de build dans le journal. Depuis le protocole 4 (D-21),
     * DEUX consoles existent selon le moteur négocié :
     *
     * <ul>
     *   <li>moteur moderne : la coquille n'AFFICHE plus que les lignes de journal
     *       reçues — lignes de tâches, verdict, diagnostics, tout est composé et trié
     *       par le MOTEUR (l'argument {@code consoleLevel}, comme les -q/-i/-d de
     *       Gradle) ; les événements structurés ne servent plus qu'à la barre, à la
     *       ligne vivante et aux boutons ;</li>
     *   <li>moteur plus ancien : la composition locale d'avant (une ligne par tâche
     *       avec son issue, verdict décompté) — le même écran, la même coquille, sans
     *       jamais rien doubler.</li>
     * </ul>
     */
    private void afficherÉvénement(BuildEvent événement) {
        if (consoleDuMoteur) {
            afficherÉvénementConsoleMoteur(événement);
            return;
        }
        if (événement instanceof BuildEvent.LogEvent journal) {
            if (niveauAffiché(journal.level())) {
                ajouterLigne(journal.message());
            }
        } else if (événement instanceof BuildEvent.DiagnosticEvent diagnostic) {
            ajouterLigne(getString(R.string.build_diagnostic_line,
                    diagnostic.file().isEmpty() ? "?" : diagnostic.file(),
                    diagnostic.line(), diagnostic.message()));
        } else if (événement instanceof BuildEvent.TaskStateEvent tâche) {
            afficherTâche(tâche);
        } else if (événement instanceof BuildEvent.DownloadEvent téléchargement) {
            afficherTéléchargement(téléchargement);
        } else if (événement instanceof BuildEvent.HeartbeatEvent pulsation) {
            afficherPulsation(pulsation);
        } else if (événement instanceof BuildEvent.ProgressEvent progression) {
            this.progression.setIndeterminate(false);
            this.progression.setMax(Math.max(1, progression.tasksTotal()));
            this.progression.setProgress(Math.min(progression.tasksDone(),
                    progression.tasksTotal()));
        } else if (événement instanceof BuildEvent.BuildFinished fin) {
            afficherFinLegacy(fin);
        }
    }

    /**
     * Affichage face à un moteur qui compose sa console (protocole 4) : chaque ligne
     * du journal arrive DÉJÀ rédigée par le moteur (lignes de tâches à l'issue,
     * diagnostics, téléchargements finis, verdict final) et DÉJÀ triée (le niveau de
     * console est un argument du moteur) — la coquille représente, elle ne rédige
     * plus. Les événements structurés gardent leurs rôles : barre de progression,
     * ligne vivante du résumé, verdict pour les boutons.
     */
    private void afficherÉvénementConsoleMoteur(BuildEvent événement) {
        if (événement instanceof BuildEvent.LogEvent journal) {
            ajouterLigne(journal.message());
        } else if (événement instanceof BuildEvent.ProgressEvent progression) {
            this.progression.setIndeterminate(false);
            this.progression.setMax(Math.max(1, progression.tasksTotal()));
            this.progression.setProgress(Math.min(progression.tasksDone(),
                    progression.tasksTotal()));
        } else if (événement instanceof BuildEvent.HeartbeatEvent pulsation) {
            afficherPulsation(pulsation);
        } else if (événement instanceof BuildEvent.DownloadEvent téléchargement) {
            afficherTéléchargementRésumé(téléchargement);
        } else if (événement instanceof BuildEvent.BuildFinished fin) {
            // La ligne de verdict est déjà arrivée par le journal (composée par le
            // moteur) : ici, seuls l'état des boutons et le résumé changent.
            buildEnCours = false; // D-20 : verdict rendu — plus aucune reprise.
            progression.setIndeterminate(false);
            afficherRésumé(fin.message());
            réinitialiserBoutons();
            installerButton.setEnabled(fin.success() && dernierApkConstruit() != null);
            exécuteur.submit(this::arrêterMoteurCourant);
        }
        // TaskStateEvent et DiagnosticEvent : rien à afficher — le moteur a déjà
        // composé leurs lignes ; la structure reste pour l'éditeur (saut à l'erreur).
    }

    /**
     * Verdict en mode composition locale (moteur plus ancien) : le journal doit se
     * terminer par son PROPRE verdict — le rapport appareil du 2026-11-20 a lu
     * « build jamais terminé » sur un build pourtant réussi (le journal s'arrêtait
     * à la dernière tâche, le verdict ne vivait que dans le résumé, effacé par la
     * réimportation des exemples).
     */
    private void afficherFinLegacy(BuildEvent.BuildFinished fin) {
        buildEnCours = false; // D-20 : verdict rendu — plus aucune reprise.
        progression.setIndeterminate(false);
        String verdict = fin.success()
                ? getString(R.string.build_finished_ok,
                        Math.max(1, fin.durationMs() / 1000))
                : getString(R.string.build_finished_ko,
                        fin.failedTask() == null ? "?" : fin.failedTask(),
                        fin.message());
        ajouterLigne(verdict);
        ajouterLigne(fin.message());
        afficherRésumé(verdict + " — " + fin.message());
        réinitialiserBoutons();
        installerButton.setEnabled(fin.success() && dernierApkConstruit() != null);
        exécuteur.submit(this::arrêterMoteurCourant);
    }

    /**
     * La verbosité de la coquille, traduite en argument de console du moteur (D-21) :
     * essentiel → console de build classique (lignes de tâches et verdict), détaillé
     * → + informations et téléchargements, tout → + débogage. C'est l'équivalent des
     * choix -q/-i/-d de Gradle : le MOTEUR trie à l'émission, la coquille demande.
     *
     * @param verbosité la verbosité mémorisée.
     * @return le niveau de console à passer à la requête de build.
     */
    private static BuildEvent.LogEvent.LogLevel niveauConsoleDe(Verbosité verbosité) {
        return switch (verbosité) {
            case ESSENTIEL -> BuildEvent.LogEvent.LogLevel.LIFECYCLE;
            case DÉTAILLÉ -> BuildEvent.LogEvent.LogLevel.INFO;
            case TOUT -> BuildEvent.LogEvent.LogLevel.DEBUG;
        };
    }

    /**
     * Ligne d'une tâche, à la manière des consoles de build classiques : une ligne
     * par tâche à son issue (rien au démarrage, rien pour une tâche exécutée —
     * l'absence de badge VEUT dire « travail fait »).
     */
    private void afficherTâche(BuildEvent.TaskStateEvent tâche) {
        String badge = badgeÉtat(tâche.state());
        if (tâche.state() == BuildEvent.TaskState.STARTED) {
            return; // Comme les consoles classiques : rien au démarrage.
        }
        ajouterLigne(getString(R.string.build_task_line, tâche.taskName(),
                badge.isEmpty() ? "" : " " + badge));
    }

    /**
     * Pulsation d'une tâche longue (protocole 3) : la ligne vivante du résumé dit
     * QUELLE tâche tourne, depuis combien de temps et où elle en est (« app:dex —
     * dexage : 12/41 — 1 min 30 s ») — le constat du rapport du 2026-11-21 (des
     * minutes de silence pendant le dexage lues comme un plantage) est refermé. Le
     * journal, lui, reste calme : la pulsation vit dans le résumé.
     */
    private void afficherPulsation(BuildEvent.HeartbeatEvent pulsation) {
        long secondes = Math.max(1, pulsation.elapsedMs() / 1000);
        String durée = secondes < 60
                ? getString(R.string.elapsed_seconds, secondes)
                : getString(R.string.elapsed_minutes, secondes / 60, secondes % 60);
        if (pulsation.message().isEmpty()) {
            afficherRésumé(getString(R.string.heartbeat_line,
                    pulsation.taskName(), durée));
        } else {
            afficherRésumé(getString(R.string.heartbeat_line_with_progress,
                    pulsation.taskName(), pulsation.message(), durée));
        }
    }

    /**
     * Un téléchargement (mode composition locale) : la ligne vivante du résumé suit
     * le fichier en cours (nom, octets reçus sur total), et le journal (niveau
     * détaillé) garde une ligne par fichier terminé — le silence réseau long
     * n'existe plus.
     */
    private void afficherTéléchargement(BuildEvent.DownloadEvent téléchargement) {
        if (téléchargement.done()) {
            if (verbosité != Verbosité.ESSENTIEL) {
                ajouterLigne(getString(R.string.download_finished_line,
                        téléchargement.resource(), tailleLisible(
                        téléchargement.bytesDownloaded())));
            }
            afficherRésumé(getString(R.string.build_in_progress));
            return;
        }
        afficherTéléchargementRésumé(téléchargement);
    }

    /**
     * Un téléchargement, côté résumé seul : la ligne vivante suit le fichier en
     * cours (nom, octets reçus sur total). Utilisé seul face à un moteur protocole 4
     * — la ligne de journal du fichier terminé est composée par le moteur.
     */
    private void afficherTéléchargementRésumé(BuildEvent.DownloadEvent téléchargement) {
        if (téléchargement.done()) {
            afficherRésumé(getString(R.string.build_in_progress));
            return;
        }
        if (!téléchargement.resource().isEmpty()) {
            téléchargementCourant = téléchargement.resource();
        }
        if (téléchargementCourant.isEmpty()) {
            return;
        }
        if (téléchargement.bytesTotal() >= 0) {
            afficherRésumé(getString(R.string.download_progress_line,
                    téléchargementCourant,
                    tailleLisible(téléchargement.bytesDownloaded()),
                    tailleLisible(téléchargement.bytesTotal())));
        } else {
            afficherRésumé(getString(R.string.download_progress_line_unknown,
                    téléchargementCourant,
                    tailleLisible(téléchargement.bytesDownloaded())));
        }
    }

    /**
     * Badge d'issue d'une tâche (vide pour une tâche exécutée — comme les consoles
     * classiques, l'absence de badge est l'information).
     */
    private String badgeÉtat(BuildEvent.TaskState état) {
        return switch (état) {
            case STARTED, SUCCEEDED -> "";
            case FAILED -> getString(R.string.task_state_failed);
            case SKIPPED -> getString(R.string.task_state_skipped);
            case UP_TO_DATE -> getString(R.string.task_state_up_to_date);
            case FROM_CACHE -> getString(R.string.task_state_from_cache);
        };
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
        Path traces = java.nio.file.Paths.get(chemin, ".forge", "traces");
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
        synchroniserButton.setEnabled(true);
        annulerButton.setEnabled(false);
    }

    /**
     * Dernier APK signé produit sous la racine du projet courant.
     */
    private Path dernierApkConstruit() {
        String chemin = cheminProjet.getText().toString().trim();
        if (chemin.isEmpty()) {
            return null;
        }
        Path sorties = java.nio.file.Paths.get(chemin, "build");
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
