package jo.forge.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import jo.forge.api.BuildEvent;
import jo.forge.api.Status;
import jo.forge.client.DeviceVmLauncher;
import jo.forge.client.EngineClient;
import jo.forge.setup.install.EngineInstaller;
import jo.forge.setup.install.ForgeHome;
import jo.forge.setup.install.InstalledState;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Service hôte en premier plan : supervise la machine virtuelle du moteur pendant les
 * sessions de build.
 *
 * <p>Rôle (décision D-02) : lancer le moteur installé, répondre à l'interface par des
 * états simples, relancer au besoin, et surtout garder une <em>présence de premier
 * plan</em> pendant les builds (notification obligatoire, atténuation du tueur de
 * processus fantômes d'Android 12+). Aucune logique de build ici : tout part dans le
 * moteur, processus séparé.</p>
 */
public class ForgeHostService extends Service {

    /** Canal de notification du service. */
    private static final String CHANNEL_ID = "forge-hote";

    /** Identifiant de la notification de premier plan. */
    private static final int NOTIFICATION_ID = 1;

    /** État observable par l'interface. */
    public enum HostState {
        /** Le service démarre. */
        DEMARRAGE,
        /** Le moteur tourne et a répondu à la poignée de main. */
        MOTEUR_PRET,
        /** Le moteur est mort ou injoignable. */
        MOTEUR_MORT,
        /** Aucun moteur installé. */
        AUCUN_MOTEUR
    }

    private static volatile HostState state = HostState.DEMARRAGE;
    private static volatile String stateDetail = "";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile EngineClient client;

    /**
     * État courant du service hôte (polling simple par l'interface).
     *
     * @return l'état observable.
     */
    public static HostState hostState() {
        return state;
    }

    /**
     * Détail de l'état courant (version du moteur, message d'erreur).
     *
     * @return le détail, éventuellement vide.
     */
    public static String hostStateDetail() {
        return stateDetail;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        creerCanal();
        startForeground(NOTIFICATION_ID, notification(
                getString(jo.forge.app.R.string.host_notification_title),
                getString(jo.forge.app.R.string.host_notification_starting)));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        state = HostState.DEMARRAGE;
        stateDetail = "";
        executor.submit(this::demarrerMoteur);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        // Arrêt propre du moteur : accusé puis mort naturelle du processus enfant.
        EngineClient moteur = client;
        if (moteur != null) {
            try {
                moteur.shutdown().get(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                moteur.kill();
            }
        }
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null; // Service démarré, pas lié.
    }

    /**
     * Lance le moteur installé, ping, publie l'état.
     */
    private void demarrerMoteur() {
        ForgeHome home = new ForgeHome(getFilesDir().toPath().resolve("forge"));
        EngineInstaller installateur = new EngineInstaller(home, null, null);
        InstalledState installe = installateur.currentState();
        if (installe == null) {
            state = HostState.AUCUN_MOTEUR;
            stateDetail = getString(R.string.status_engine_missing);
            stopSelf();
            return;
        }
        Consumer<BuildEvent> evenements = evenement -> {
            // Les événements de build seront affichés par l'écran de build (Phase 4.11).
        };
        try {
            EngineClient moteur = EngineSession.launchInstalled(
                    new DeviceVmLauncher(), home, installe,
                    getVersionName(), evenements, () -> {
                        state = HostState.MOTEUR_MORT;
                        stateDetail = getString(R.string.host_engine_dead);
                    }, false);
            client = moteur;
            Status statut = moteur.status().get(15, TimeUnit.SECONDS);
            state = HostState.MOTEUR_PRET;
            stateDetail = getString(R.string.host_engine_ready,
                    statut.engineVersion());
            notifier(getString(R.string.host_engine_ready, statut.engineVersion()));
        } catch (Exception echec) {
            state = HostState.MOTEUR_MORT;
            stateDetail = echec.getMessage() == null
                    ? echec.getClass().getSimpleName() : echec.getMessage();
            notifier(stateDetail);
        }
    }

    /**
     * Version affichable de la coquille (journal côté moteur).
     */
    private String getVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception absent) {
            return "inconnue";
        }
    }

    private void notifier(String texte) {
        NotificationManager gestionnaire = getSystemService(NotificationManager.class);
        if (gestionnaire != null) {
            gestionnaire.notify(NOTIFICATION_ID + 1,
                    notification(getString(R.string.host_notification_title), texte));
        }
    }

    private Notification notification(String titre, String texte) {
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setContentTitle(titre)
                .setContentText(texte)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void creerCanal() {
        NotificationManager gestionnaire = getSystemService(NotificationManager.class);
        if (gestionnaire != null) {
            NotificationChannel canal = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.host_notification_channel),
                    NotificationManager.IMPORTANCE_LOW);
            gestionnaire.createNotificationChannel(canal);
        }
    }
}
