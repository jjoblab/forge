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
                FusionManifestes.fusionner(app, List.of(lib),
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
                FusionManifestes.fusionner(app, List.of(lib),
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

        FusionManifestes.fusionner(app, List.of(lib), sortie);

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
}
