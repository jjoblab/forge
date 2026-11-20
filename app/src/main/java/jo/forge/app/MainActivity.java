package jo.forge.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import java.io.File;

import jo.forge.setup.install.EngineInstaller;
import jo.forge.setup.install.ForgeHome;
import jo.forge.setup.install.InstalledState;

/**
 * Écran principal de la coquille.
 *
 * <p>Affiche l'état du moteur de build (installé et sa version, ou absent avec son
 * explication et les fonctions de build désactivées) et démarre le service hôte qui
 * supervise le moteur. Cette activité ne contient aucune logique de construction.</p>
 */
public class MainActivity extends Activity {

    private TextView statusText;
    private TextView statusHint;
    private Button testButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        statusText = findViewById(R.id.engine_status);
        statusHint = findViewById(R.id.engine_status_hint);
        testButton = findViewById(R.id.action_test_engine);
        testButton.setOnClickListener(vue -> {
            Intent service = new Intent(this, ForgeHostService.class);
            startForegroundService(service);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        rafraichirEtat();
    }

    /**
     * Met à jour l'écran depuis le registre d'installation et l'état du service hôte.
     */
    private void rafraichirEtat() {
        ForgeHome home = new ForgeHome(new File(getFilesDir(), "forge").toPath());
        // L'installateur sert ici de lecteur d'état : aucune installation n'est tentée.
        EngineInstaller lecteur = new EngineInstaller(home, null, null);
        InstalledState installe = lecteur.currentState();

        if (installe == null) {
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
}
