package jo.forge.engine.pipeline.android;

import jo.forge.engine.util.DomSûr;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilder;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests de la fusion des manifestes d'AAR dans celui de l'application
 * (v0.2.15, prérequis Compose) : l'application gagne, les bibliothèques
 * AJOUTENT (permissions, composants), le nom relatif d'une bibliothèque est
 * étendu à son paquet, et {@code uses-sdk} d'une bibliothèque ne contamine
 * jamais les niveaux de l'application.
 *
 * <p>v0.2.17 (rapport appareil v0.2.16 : « manifeste illisible :
 * …disallow-doctype-decl » à {@code processResources}) : le DOCTYPE est
 * refusé NETTEMENT sur tout runtime (scan du prologue — la fonctionnalité
 * Xerces qui portait cette garde n'existe pas sur ART), et la sérialisation
 * se fait SANS {@code javax.xml.transform} (écrivain maison, éléments à
 * attributs : le Transformer n'est pas fiable sur ART, motif du moteur de
 * référence REF-163) — la sortie doit relire proprement.</p>
 *
 * <p>v0.2.18 (rapport appareil v0.2.17 : aapt2 refusait le manifeste fusionné,
 * « unbound prefix » ligne du premier composant de bibliothèque) : les
 * directives {@code tools:} sont des directives AU FUSIONNEUR, jamais du
 * contenu pour aapt2 — {@code tools:node="remove"} RETIRE l'élément, tout
 * autre attribut d'espace de noms étranger est éliminé, et les marqueurs
 * {@code ${applicationId}} sont substitués (le manifeste d'androidx.core
 * réclame une permission placeholder, celui d'androidx.startup construit
 * ses autorités dessus).</p>
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

        int ajouts = FusionManifestes.fusionner(app, List.of(lib), Map.of(),
                sortie);

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

        assertEquals(0, FusionManifestes.fusionner(app, List.of(), Map.of(),
                sortie));
        assertEquals(Files.readString(app), Files.readString(sortie),
                "aucune bibliothèque : le manifeste passe tel quel");
    }

    @Test
    void doctypeDeBibliothèqueRefuséNettement() throws Exception {
        // Rapport appareil v0.2.16 : la garde anti-XXE devait tuer la tâche sur
        // ART (fonctionnalité Xerces inconnue de KXml) ; elle refuse désormais
        // le DOCTYPE par SCAN DU PROLOGUE — même refus net sur JVM et sur ART.
        Path app = temporaire.resolve("app.xml");
        Path lib = temporaire.resolve("lib.xml");
        Files.writeString(app, MANIFESTE_APP);
        Files.writeString(lib, """
                <?xml version="1.0" encoding="utf-8"?>
                <!DOCTYPE manifest SYSTEM "http://attaquant/xxe.dtd">
                <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                        package="demo.lib"/>
                """);

        IOException rejet = assertThrows(IOException.class, () ->
                FusionManifestes.fusionner(app, List.of(lib), Map.of(),
                        temporaire.resolve("fusion.xml")));
        assertTrue(rejet.getMessage().contains("DOCTYPE interdit"),
                "le DOCTYPE d'un manifeste de bibliothèque est refusé : "
                        + rejet.getMessage());
        assertTrue(rejet.getMessage().contains("manifeste illisible"),
                "le diagnostic reste celui de la tâche : " + rejet.getMessage());
    }

    @Test
    void doctypeDeLApplicationRefuséNettement() throws Exception {
        Path app = temporaire.resolve("app.xml");
        Path lib = temporaire.resolve("lib.xml");
        Files.writeString(app, """
                <!DOCTYPE manifest>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                        package="demo.app"/>
                """);
        Files.writeString(lib, MANIFESTE_LIB);

        IOException rejet = assertThrows(IOException.class, () ->
                FusionManifestes.fusionner(app, List.of(lib), Map.of(),
                        temporaire.resolve("fusion.xml")));
        assertTrue(rejet.getMessage().contains("DOCTYPE interdit"),
                "le DOCTYPE du manifeste applicatif est refusé : "
                        + rejet.getMessage());
    }

    @Test
    void sérialisationSansTransformerÉchappeEtRelit() throws Exception {
        // Écrivain maison (REF-163) : jamais de javax.xml.transform — le
        // Transformer n'est pas fiable sur ART. Les éléments de manifeste ne
        // portent que des attributs : échappement &amp; &lt; &gt; &quot;,
        // éléments vides auto-fermants, et la sortie RELIT proprement (un
        // second passage doit retrouver les mêmes attributs par URI).
        Path app = temporaire.resolve("app.xml");
        Path lib = temporaire.resolve("lib.xml");
        Path sortie = temporaire.resolve("fusion.xml");
        Files.writeString(app, """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                        package="demo.app">
                    <application android:label="Démo &amp; &quot;Forge&quot;">
                        <activity android:name=".MainActivity"/>
                    </application>
                </manifest>
                """);
        Files.writeString(lib, MANIFESTE_LIB);

        FusionManifestes.fusionner(app, List.of(lib), Map.of(), sortie);

        String fusion = Files.readString(sortie);
        assertTrue(fusion.startsWith("<?xml version=\"1.0\" encoding=\"utf-8\"?>"),
                "déclaration XML en tête : " + premièreLigne(fusion));
        assertTrue(fusion.contains("android:label=\"Démo &amp; &quot;Forge&quot;\""),
                "la valeur d'attribut est ré-échappée : " + fusion);
        assertTrue(fusion.contains("<activity android:name=\".MainActivity\"/>"),
                "l'élément sans enfant est auto-fermant (le nom RELATIF de "
                        + "l'application passe tel quel — seuls ceux des "
                        + "bibliothèques sont étendus) : " + fusion);
        assertFalse(fusion.contains("</activity>"),
                "pas de balise fermante pour un élément vide : " + fusion);
        assertTrue(fusion.contains("xmlns:android="),
                "la déclaration d'espace de noms survit à l'écriture : " + fusion);

        // La sortie RELIT : mêmes attributs par URI d'espace de noms.
        byte[] octets = Files.readAllBytes(sortie);
        DomSûr.refuserDoctype(octets);
        DocumentBuilder constructeur = DomSûr.nouveauParseur(true);
        Document relu = constructeur.parse(new ByteArrayInputStream(octets));
        Element racine = relu.getDocumentElement();
        assertEquals("demo.app", racine.getAttribute("package"));
        Element application = (Element) racine
                .getElementsByTagName("application").item(0);
        assertNotNull(application.getAttributeNodeNS(
                "http://schemas.android.com/apk/res/android", "label"),
                "l'attribut android:label relit PAR URI après la fusion");
        assertEquals("Démo & \"Forge\"",
                application.getAttributeNodeNS(
                        "http://schemas.android.com/apk/res/android", "label")
                        .getValue(),
                "la valeur échappée fait le aller-retour intacte");
        assertNotNull(racine.getElementsByTagName("provider"),
                "le composant de bibliothèque a bien été fusionné avant écriture");
    }

    private static String premièreLigne(String texte) {
        int fin = texte.indexOf('\n');
        return fin < 0 ? texte : texte.substring(0, fin);
    }

    /**
     * La chaîne de fer du rapport appareil v0.2.17 : startup (provider à
     * {@code tools:node="merge"} + autorités placeholder), profileinstaller
     * (MÊME provider avec meta-data + receiver) et core (permission
     * placeholder) — la fusion doit rendre un manifeste QU'AAPT2 PEUT LIER
     * (aucun préfixe non lié, aucun {@code ${…}}) et complet (meta-data du
     * provider fusionné, receiver présent).
     */
    @Test
    void manifestesAndroidXRéelsPassentLeLienAapt2() throws Exception {
        Path app = temporaire.resolve("app.xml");
        Path startup = temporaire.resolve("startup.xml");
        Path profileinstaller = temporaire.resolve("profileinstaller.xml");
        Path core = temporaire.resolve("core.xml");
        Path sortie = temporaire.resolve("fusion.xml");
        Files.writeString(app, """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                        package="demo.app">
                    <application android:label="Demo">
                        <activity android:name=".MainActivity" android:exported="true">
                            <intent-filter>
                                <action android:name="android.intent.action.MAIN"/>
                                <category android:name="android.intent.category.LAUNCHER"/>
                            </intent-filter>
                        </activity>
                    </application>
                </manifest>
                """);
        // Le manifeste réel d'androidx.startup 1.1.1 (Apache-2.0, AOSP).
        Files.writeString(startup, """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                    xmlns:tools="http://schemas.android.com/tools"
                    package="androidx.startup" >
                    <application>
                        <provider
                            android:name="androidx.startup.InitializationProvider"
                            android:authorities="${applicationId}.androidx-startup"
                            android:exported="false"
                            tools:node="merge" />
                    </application>
                </manifest>
                """);
        // Le manifeste réel d'androidx.profileinstaller 1.3.1 (Apache-2.0, AOSP) :
        // le MÊME provider, porteur d'un meta-data, plus un receiver.
        Files.writeString(profileinstaller, """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                    xmlns:tools="http://schemas.android.com/tools"
                    package="androidx.profileinstaller" >
                    <application>
                        <provider
                            android:name="androidx.startup.InitializationProvider"
                            android:authorities="${applicationId}.androidx-startup"
                            android:exported="false"
                            tools:node="merge" >
                            <meta-data
                                android:name="androidx.profileinstaller.ProfileInstallerInitializer"
                                android:value="androidx.startup" />
                        </provider>
                        <receiver
                            android:name="androidx.profileinstaller.ProfileInstallReceiver"
                            android:exported="true"
                            android:permission="android.permission.DUMP" >
                            <intent-filter>
                                <action android:name="androidx.profileinstaller.action.INSTALL_PROFILE" />
                            </intent-filter>
                        </receiver>
                    </application>
                </manifest>
                """);
        // La permission placeholder réelle d'androidx.core (1.13).
        Files.writeString(core, """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                        package="androidx.core">
                    <uses-permission android:name="${applicationId}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"/>
                </manifest>
                """);

        int ajouts = FusionManifestes.fusionner(app,
                List.of(startup, profileinstaller, core),
                Map.of("applicationId", "demo.app"), sortie);

        assertEquals(3, ajouts, "provider + receiver + permission ajoutés");
        String fusion = Files.readString(sortie);
        // 1. AUCUN préfixe non lié : c'est le « unbound prefix » qui tuait aapt2.
        assertFalse(fusion.contains("tools:"),
                "les directives tools: ne fuient pas vers aapt2 : " + fusion);
        assertFalse(fusion.contains("xmlns:tools"),
                "la déclaration xmlns:tools des bibliothèques non plus : " + fusion);
        // 2. AUCUN marqueur : substitués partout (autorisés ET permission).
        assertFalse(fusion.contains("${"),
                "aucun marqueur ${…} ne survit : " + fusion);
        assertTrue(fusion.contains("demo.app.androidx-startup"),
                "les autorités du provider portent l'identifiant effectif");
        assertTrue(fusion.contains("demo.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"),
                "la permission placeholder est substituée");
        // 3. Le meta-data du profileinstaller rejoint le provider CONNU de
        // startup (sans lui, l'initialisateur ne s'exécute pas).
        assertTrue(fusion.contains("androidx.profileinstaller.ProfileInstallerInitializer"),
                "le meta-data du second provider est fusionné dans le premier : "
                        + fusion);
        // 4. Le receiver et le provider existent, une seule fois chacun.
        assertEquals(1, fusion.split("androidx.startup.InitializationProvider", -1).length - 1,
                "le provider n'est pas dupliqué");
        assertTrue(fusion.contains("androidx.profileinstaller.ProfileInstallReceiver"));
        // 5. La sortie RELIT en DOM conscient des espaces de noms (ce qu'aapt2
        // exige d'un document bien formé).
        byte[] octets = Files.readAllBytes(sortie);
        DomSûr.refuserDoctype(octets);
        Document relu = DomSûr.nouveauParseur(true)
                .parse(new ByteArrayInputStream(octets));
        assertNotNull(relu.getElementsByTagName("provider"));
    }

    /** tools:node="remove" : l'élément est RETIRÉ de la fusion (sémantique AGP). */
    @Test
    void directiveRemoveRetireLEntrée() throws Exception {
        Path app = temporaire.resolve("app.xml");
        Path lib = temporaire.resolve("lib.xml");
        Path sortie = temporaire.resolve("fusion.xml");
        Files.writeString(app, MANIFESTE_APP);
        Files.writeString(lib, """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                        xmlns:tools="http://schemas.android.com/tools"
                        package="demo.lib">
                    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"/>
                    <uses-permission android:name="android.permission.DUMP"
                            tools:node="remove"/>
                </manifest>
                """);

        int ajouts = FusionManifestes.fusionner(app, List.of(lib), Map.of(),
                sortie);

        assertEquals(1, ajouts, "seule la permission sans directive est ajoutée");
        String fusion = Files.readString(sortie);
        assertTrue(fusion.contains("android.permission.ACCESS_FINE_LOCATION"));
        assertFalse(fusion.contains("android.permission.DUMP"),
                "tools:node=\"remove\" retire la permission de la fusion (un tel "
                        + "marqueur sert précisément à RETIRER ce qu'une autre "
                        + "bibliothèque demande) : " + fusion);
        assertFalse(fusion.contains("tools:"), "aucune directive ne fuit");
    }

    /** Un marqueur inconnu est refusé nettement : l'APK aux littéraux « ${…} » est cassé. */
    @Test
    void marqueurInconnuRefuséNettement() throws Exception {
        Path app = temporaire.resolve("app.xml");
        Path lib = temporaire.resolve("lib.xml");
        Files.writeString(app, MANIFESTE_APP);
        Files.writeString(lib, """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                        package="demo.lib">
                    <application>
                        <meta-data android:name="demo.lib.hôte"
                                android:value="${hôteInconnu}"/>
                    </application>
                </manifest>
                """);

        IOException rejet = assertThrows(IOException.class, () ->
                FusionManifestes.fusionner(app, List.of(lib),
                        Map.of("applicationId", "demo.app"),
                        temporaire.resolve("fusion.xml")));
        assertTrue(rejet.getMessage().contains("hôteInconnu"),
                "le diagnostic nomme le marqueur manquant : " + rejet.getMessage());
    }

    /**
     * Un manifeste applicatif qui emploie un AUTRE préfixe que {@code android}
     * pour l'espace de noms android : l'écrivain CANONISE (préfixe android +
     * déclaration sur la racine) au lieu d'écrire des préfixes orphelins.
     */
    @Test
    void préfixeNonCanoniqueRéécrit() throws Exception {
        Path app = temporaire.resolve("app.xml");
        Path sortie = temporaire.resolve("fusion.xml");
        Files.writeString(app, """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest xmlns:ann="http://schemas.android.com/apk/res/android"
                        package="demo.app">
                    <application ann:label="Démo">
                        <activity ann:name=".MainActivity"/>
                    </application>
                </manifest>
                """);

        FusionManifestes.fusionner(app, List.of(),
                Map.of("applicationId", "demo.app"), sortie);

        String fusion = Files.readString(sortie);
        assertTrue(fusion.contains("xmlns:android="),
                "la déclaration canonique est posée : " + fusion);
        assertTrue(fusion.contains("android:name=\".MainActivity\""),
                "l'attribut est réécrit sous le préfixe canonique : " + fusion);
        assertFalse(fusion.contains("ann:"),
                "aucun préfixe non déclaré ne survit : " + fusion);
        // La sortie relit PAR URI (l'espace de noms est le même, seul le
        // préfixe a changé).
        Document relu = DomSûr.nouveauParseur(true).parse(
                new ByteArrayInputStream(Files.readAllBytes(sortie)));
        Element application = (Element) relu.getDocumentElement()
                .getElementsByTagName("application").item(0);
        assertEquals("Démo", application.getAttributeNodeNS(
                "http://schemas.android.com/apk/res/android", "label").getValue());
    }
}
