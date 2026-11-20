package jo.forge.engine.pipeline.android;

import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests de la clé de débogage générée en pur Java (correctif v0.2.9 du crash
 * {@code keytool} absent d'ART) : certificat auto-signé conforme et revalidé,
 * conteneur {@code .forge/debug-key.forge} relu identique, magasin PKCS12
 * hérité encore lu sur JVM, et signature d'un vrai APK vérifiée par apksig.
 */
class DebugKeyStoreTest {

    @TempDir
    Path temporaire;

    @Test
    void certificatAutoSignéConformeEtRevalidé() throws Exception {
        KeyPairGenerator générateur = KeyPairGenerator.getInstance("RSA");
        générateur.initialize(2048);
        KeyPair paire = générateur.generateKeyPair();
        Date avant = new Date();
        Date après = new Date(avant.getTime()
                + TimeUnit.DAYS.toMillis(365) * 30);

        X509Certificate certificat = CertificatAutoSigné.certificat(paire,
                CertificatAutoSigné.nomDébogage(),
                CertificatAutoSigné.série(new SecureRandom()), avant, après);

        // générée() relit déjà par CertificateFactory et vérifie la signature :
        // arriver ici prouve la conformité. Compléments :
        assertTrue(certificat.getSubjectX500Principal().getName()
                        .contains("CN=Forge Debug"),
                "sujet : " + certificat.getSubjectX500Principal().getName());
        assertEquals(certificat.getSubjectX500Principal(),
                certificat.getIssuerX500Principal(), "auto-signé : émetteur = sujet");
        assertEquals(1, certificat.getVersion(), "certificat v1, comme keytool");
        assertTrue(certificat.getNotAfter().after(new Date()),
                "validité trentenaire");
        assertEquals("RSA", certificat.getPublicKey().getAlgorithm());
        // La clé publique du certificat EST celle de la paire.
        assertEquals(((RSAPublicKey) paire.getPublic()).getModulus(),
                ((RSAPublicKey) certificat.getPublicKey()).getModulus());
        // Les dates au-delà de 2050 passent par GeneralizedTime : la preuve
        // vient de CertificateFactory, qui aurait rejeté un Time mal encodé.
        assertTrue(certificat.getNotAfter().getYear() + 1900 >= 2050,
                "la validité de 30 ans dépasse 2050 (GeneralizedTime exercé)");
    }

    @Test
    void conteneurForgeGénéréPuisReluIdentique() throws Exception {
        DebugKeyStore clés = new DebugKeyStore(temporaire.resolve(".forge"));
        DebugKeyStore.Clé première = clés.chargerOuGénérer();
        assertNotNull(temporaire.resolve(".forge/debug-key.forge").toFile(),
                "le conteneur Forge est posé à la première utilisation");

        // Relecture : MÊME clé (octets PKCS#8 identiques), MÊME certificat.
        DebugKeyStore.Clé relue = clés.chargerOuGénérer();
        assertArrayEquals(première.privée().getEncoded(),
                relue.privée().getEncoded(), "la clé privée survit au conteneur");
        assertArrayEquals(première.certificat().getEncoded(),
                relue.certificat().getEncoded(),
                "le certificat survit au conteneur");

        // Aucun processus externe, aucun sous-processus keytool — le test
        // s'exécute ici comme il s'exécutera sur ART.
    }

    @Test
    void magasinHéritéPkcs12ToujoursLuSurJvm() throws Exception {
        // La chaîne v0.2.8 générait .forge/debug-key.p12 par keytool : un tel
        // projet doit garder SA signature (pas de régénération silencieuse).
        Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
        assumeTrue(Files.isRegularFile(keytool),
                "keytool indisponible — lecture héritée non testable ici");
        Path répertoire = temporaire.resolve(".forge");
        Files.createDirectories(répertoire);
        Path p12 = répertoire.resolve("debug-key.p12");
        new ProcessBuilder(List.of(keytool.toString(),
                "-genkeypair", "-keystore", p12.toString(),
                "-storetype", "PKCS12", "-storepass", "forge-debug",
                "-keypass", "forge-debug", "-alias", "forge-debug",
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "10950",
                "-dname", "CN=Forge Debug,O=Forge,C=FR"))
                .redirectErrorStream(true).start().waitFor();

        DebugKeyStore clés = new DebugKeyStore(répertoire);
        DebugKeyStore.Clé héritée = clés.chargerOuGénérer();
        assertEquals("CN=Forge Debug,O=Forge,C=FR",
                héritée.certificat()
                        .getSubjectX500Principal().getName(),
                "la clé du magasin v0.2.8 est reprise telle quelle (forme RFC2253)");
        assertTrue(Files.notExists(répertoire.resolve("debug-key.forge")),
                "aucun nouveau magasin n'écrase l'héritage");
    }

    @Test
    void apkSignéAvecLaCléGénéréeVérifiéParApksig() throws Exception {
        DebugKeyStore clés = new DebugKeyStore(temporaire.resolve(".forge"));
        DebugKeyStore.Clé clé = clés.chargerOuGénérer();

        // Un « APK » minimal : entrées zip arbitraires (la signature porte sur
        // le fichier, pas sur son contenu sémantique).
        Path nonSigné = temporaire.resolve("unsigned.apk");
        try (ZipOutputStream zip = new ZipOutputStream(
                Files.newOutputStream(nonSigné))) {
            zip.putNextEntry(new ZipEntry("AndroidManifest.xml"));
            zip.write("manifeste factice".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("classes.dex"));
            zip.write("dex factice".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        Path signé = temporaire.resolve("signed.apk");
        ApkSigner.SignerConfig config = new ApkSigner.SignerConfig.Builder(
                DebugKeyStore.ALIAS, clé.privée(), List.of(clé.certificat())).build();
        new ApkSigner.Builder(List.of(config))
                .setInputApk(nonSigné.toFile())
                .setOutputApk(signé.toFile())
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(false)
                .setMinSdkVersion(1)
                .build()
                .sign();

        ApkVerifier.Result résultat = new ApkVerifier.Builder(signé.toFile())
                .setMinCheckedPlatformVersion(1)
                .build()
                .verify();
        assertTrue(résultat.isVerified(), "l'APK signé par la clé générée est vérifié");
        assertTrue(résultat.isVerifiedUsingV1Scheme() && résultat.isVerifiedUsingV2Scheme(),
                "les deux schémas v1 et v2 sont posés et reconnus");
        // Le certificat du vérificateur EST celui de la clé (chaîne de bout en bout).
        assertArrayEquals(clé.certificat().getEncoded(),
                résultat.getSignerCertificates().get(0).getEncoded());
    }

    @Test
    void deuxGénérationsDonnentDeuxClésDifférentes() throws Exception {
        // Une clé PAR PROJET (pas une clé universelle embarquée : un APK de
        // débogage ne doit pas pouvoir être remplacé par un autre construit
        // ailleurs avec la même clé).
        DebugKeyStore.Clé première = new DebugKeyStore(
                temporaire.resolve("a/.forge")).chargerOuGénérer();
        DebugKeyStore.Clé seconde = new DebugKeyStore(
                temporaire.resolve("b/.forge")).chargerOuGénérer();
        PrivateKey privéeA = première.privée();
        PrivateKey privéeB = seconde.privée();
        assertNotEquals(MessageDigest.getInstance("SHA-256")
                        .digest(privéeA.getEncoded()),
                MessageDigest.getInstance("SHA-256").digest(privéeB.getEncoded()),
                "deux projets, deux clés");
    }
}
