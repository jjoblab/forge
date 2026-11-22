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
                    // Copie manuelle (8 Kio) : InputStream.transferTo exige l'API 33 —
                    // la coquille promet minSdk 28, la méthode manquait sur l'appareil
                    // (plantage NoSuchMethodError à l'installation, bloqué par lint).
                    byte[] tampon = new byte[8192];
                    int lus;
                    while ((lus = flux.read(tampon)) >= 0) {
                        sortie.write(tampon, 0, lus);
                    }
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
        } catch (IOException échec) {
            // Constat B-4 : une session ouverte qui échoue (ouverture, écriture,
            // commit) doit être ABANDONNÉE — close() ne suffit pas, elle restait
            // ouverte côté système et accumulait les sessions fantômes.
            abandonner(installateur, identifiant);
            throw échec;
        } catch (RuntimeException échec) {
            abandonner(installateur, identifiant);
            throw échec;
        } finally {
            if (session != null) {
                session.close();
            }
        }
    }

    /**
     * Abandonne une session d'installation en échec — au mieux : si le système l'a
     * déjà fermée, l'échec d'origine prime (constat B-4). Jamais appelée après un
     * commit réussi : abandonner une session commitée annulerait l'installation.
     */
    private static void abandonner(PackageInstaller installateur, int identifiant) {
        if (identifiant < 0) {
            return; // La session n'a jamais été créée.
        }
        try {
            installateur.abandonSession(identifiant);
        } catch (RuntimeException sessionDéjàMorte) {
            // Rien à faire : l'échec d'origine est le message utile.
        }
    }
}
