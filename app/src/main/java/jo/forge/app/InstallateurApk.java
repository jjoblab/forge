package jo.forge.app;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Installation d'un APK construit par le moteur, via le {@link PackageInstaller} de la
 * plate-forme (parité avec le pipeline de la référence ; l'installation reste un acte
 * de la COQUILLE, jamais du moteur).
 *
 * <p>L'installation passe par une session et demande la confirmation de l'utilisateur
 * — le système affiche sa propre boîte de dialogue : jamais d'installation silencieuse.
 * Non vérifié sur appareil réel à ce jour (consigné dans
 * {@code docs/limites-et-suite.md}).</p>
 */
public final class InstallateurApk {

    /** Code de la confirmation d'installation. */
    private static final int CODE_CONFIRMATION = 7001;

    private InstallateurApk() {
    }

    /**
     * Installe (ou met à jour) un APK.
     *
     * @param contexte contexte d'application.
     * @param apk      chemin de l'APK signé construit par le moteur.
     * @throws IOException en cas d'échec d'ouverture du fichier ou de session.
     */
    public static void installer(Context contexte, Path apk) throws IOException {
        PackageInstaller installateur = contexte.getPackageManager()
                .getPackageInstaller();
        PackageInstaller.Session session = null;
        int identifiant = -1;
        try {
            identifiant = installateur.createSession(
                    new PackageInstaller.SessionParams(
                            PackageInstaller.SessionParams.MODE_FULL_INSTALL));
            session = installateur.openSession(identifiant);
            try (InputStream flux = Files.newInputStream(apk)) {
                try (var sortie = session.openWrite("forge-apk", 0, Files.size(apk))) {
                    flux.transferTo(sortie);
                }
            }
            // La confirmation système suit : jamais d'installation silencieuse.
            Intent confirmation = new Intent(contexte, MainActivity.class)
                    .setAction(Intent.ACTION_VIEW);
            int fanions = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) {
                fanions |= PendingIntent.FLAG_MUTABLE;
            }
            session.commit(PendingIntent.getActivity(contexte, CODE_CONFIRMATION,
                    confirmation, fanions).getIntentSender());
        } finally {
            if (session != null) {
                session.close();
            }
        }
    }
}
