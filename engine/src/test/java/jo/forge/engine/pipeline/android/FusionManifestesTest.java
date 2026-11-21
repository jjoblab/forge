package jo.forge.engine.pipeline.android;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests de la fusion des manifestes d'AAR dans celui de l'application
 * (v0.2.15, prérequis Compose) : l'application gagne, les bibliothèques
 * AJOUTENT (permissions, composants), le nom relatif d'une bibliothèque est
 * étendu à son paquet, et {@code uses-sdk} d'une bibliothèque ne contamine
 * jamais les niveaux de l'application.
 */
class FusionManifestesTest {

    @TempDir
    Path temporaire;

    private static final String MANIFESTE_APP = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                    package="demo.app">
                <uses-permission android:name="android.permission.INTERNET"/>
                <uses-sdk android:minSdkVersion="28" android:targetSdkVersion="34"/>
                <application android:label="Demo">
                    <activity android:name=".MainActivity" android:exported="true"/>
                    <provider android:name="demo.app.FournisseurApp"
                            android:authorities="demo.app.app"/>
                </application>
            </manifest>
            """;

    private static final String MANIFESTE_LIB = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                    package="demo.lib">
                <uses-permission android:name="android.permission.VIBRATE"/>
                <uses-permission android:name="android.permission.INTERNET"/>
                <uses-sdk android:minSdkVersion="21" android:targetSdkVersion="35"/>
                <application>
                    <provider android:name=".FournisseurLib"
                            android:authorities="demo.lib.init"
                            android:exported="false"/>
                    <service android:name="demo.lib.ServiceComplet"/>
                    <meta-data android:name="demo.lib.cle" android:value="1"/>
                </application>
            </manifest>
            """;

    @Test
    void bibliothèquesAjoutentSansContaminer() throws Exception {
        Path app = temporaire.resolve("app.xml");
        Path lib = temporaire.resolve("lib.xml");
        Path sortie = temporaire.resolve("fusion.xml");
        Files.writeString(app, MANIFESTE_APP);
        Files.writeString(lib, MANIFESTE_LIB);

        int ajouts = FusionManifestes.fusionner(app, List.of(lib), sortie);

        assertTrue(ajouts >= 4, "permissions + provider + service + meta-data ajoutés : "
                + ajouts);
        String fusion = Files.readString(sortie);
        assertTrue(fusion.contains("android.permission.VIBRATE"),
                "une permission de bibliothèque est AJOUTÉE");
        assertEquals(1, fusion.split("android.permission.INTERNET", -1).length - 1,
                "une permission déjà là n'est pas dupliquée");
        assertTrue(fusion.contains("demo.lib.FournisseurLib"),
                "le provider de la bibliothèque rejoint l'application (sinon elle "
                        + "plante à l'exécution)");
        assertTrue(fusion.contains("demo.app.FournisseurApp"),
                "le provider de l'application reste");
        assertTrue(fusion.contains("demo.lib.ServiceComplet"),
                "un service de bibliothèque est ajouté");
        assertTrue(fusion.contains("demo.lib.cle"),
                "un meta-data de bibliothèque est ajouté");
        // Le nom RELATIF .FournisseurLib a été étendu au paquet de la bibliothèque.
        assertTrue(!fusion.contains("\".FournisseurLib\""),
                "le nom relatif est étendu avant fusion");
        // uses-sdk de la bibliothèque : JAMAIS importé.
        assertEquals(1, fusion.split("uses-sdk", -1).length - 1,
                "le uses-sdk de la bibliothèque ne contamine pas celui de l'app");
        assertTrue(fusion.contains("targetSdkVersion=\"34\""),
                "les niveaux de l'application sont intacts");
    }

    @Test
    void sansBibliothèqueLeManifesteEstRecopié() throws Exception {
        Path app = temporaire.resolve("app.xml");
        Path sortie = temporaire.resolve("fusion.xml");
        Files.writeString(app, MANIFESTE_APP);

        assertEquals(0, FusionManifestes.fusionner(app, List.of(), sortie));
        assertEquals(Files.readString(app), Files.readString(sortie),
                "aucune bibliothèque : le manifeste passe tel quel");
    }
}
