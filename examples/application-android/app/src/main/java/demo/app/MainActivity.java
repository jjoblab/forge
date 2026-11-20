package demo.app;

import android.app.Activity;
import android.os.Bundle;
import demo.core.Salutations;

/**
 * Activité principale de l'exemple Android : affiche le layout lié par aapt2 et un
 * message venu du module Java « core ».
 */
public final class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle état) {
        super.onCreate(état);
        setContentView(R.layout.main);
        android.util.Log.i("Forge", Salutations.app());
    }
}
