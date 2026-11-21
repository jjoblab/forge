package demo.app;

import android.app.Activity;
import android.os.Bundle;
import demo.app.databinding.MainBinding;
import demo.core.Salutations;

/**
 * Activité principale de l'exemple Android : la classe de liaison
 * {@code MainBinding} (ViewBinding, générée par {@code generateViewBinding})
 * remplace le {@code findViewById}, et les constantes de
 * {@code BuildConfig} ({@code generateBuildConfig}) affichent la version et
 * le champ personnalisé du modèle.
 */
public final class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle état) {
        super.onCreate(état);
        MainBinding liaison = MainBinding.inflate(getLayoutInflater());
        liaison.message.setText(Salutations.app() + "\n"
                + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")\n"
                + BuildConfig.EXEMPLE_URL);
        setContentView(liaison.getRoot());
    }
}
