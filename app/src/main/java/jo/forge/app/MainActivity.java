package jo.forge.app;

import android.app.Activity;
import android.os.Bundle;

/**
 * Écran principal de la coquille.
 *
 * <p>Affiche l'état du moteur de build (installé, sa version, ou absent) et donne accès à
 * l'assistant d'installation et aux projets. Cette activité ne contient aucune logique de
 * construction : elle délègue au module d'installation ({@code setup}) et, quand le moteur
 * est installé, au client de supervision ({@code client}) via le service hôte.</p>
 */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        // L'état affiché sera branché sur le registre d'installation du module setup
        // (étape 3 de la Phase 4) ; tant que rien n'est installé, l'écran affiche
        // l'état « aucun moteur » avec son explication.
    }
}
