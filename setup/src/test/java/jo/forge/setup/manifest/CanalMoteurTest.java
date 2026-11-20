package jo.forge.setup.manifest;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests du canal de distribution : manifeste signé accepté, manifeste falsifié refusé,
 * schéma non HTTPS refusé par le constructeur de production, erreurs réseau claires,
 * convention de l'URL de signature. Vrai serveur HTTP local et vraie paire de clés
 * ECDSA P-256 (celle de la coquille est épinglée, celle du test est générée).
 */
class CanalMoteurTest {

    private HttpServer serveur;
    private KeyPair paire;
    private String clePubliqueBase64;
    private final AtomicInteger requetesSignature = new AtomicInteger();

    @BeforeEach
    void preparer() throws Exception {
        KeyPairGenerator générateur = KeyPairGenerator.getInstance("EC");
        générateur.initialize(new ECGenParameterSpec("secp256r1"));
        paire = générateur.generateKeyPair();
        clePubliqueBase64 = Base64.getEncoder().encodeToString(
                paire.getPublic().getEncoded());
        serveur = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serveur.start();
    }

    @AfterEach
    void arreter() {
        serveur.stop(0);
    }

    // ---- Fabriques ----------------------------------------------------------

    /**
     * Manifeste minimal valide en JSON (via le codec officiel).
     */
    private String manifesteJson(String versionMoteur) {
        ComponentManifest composant = new ComponentManifest("engine-core",
                versionMoteur, 42, "a".repeat(64),
                List.of("https://exemple.fr/engine-core.zip"), List.of("all"), 1,
                List.of());
        return ManifestCodec.write(new EngineManifest(1, versionMoteur, 1,
                "2026-11-20", List.of(composant)));
    }

    /**
     * Signe des octets avec la clé privée de test (SHA-256 avec ECDSA, DER, base64).
     */
    private String signer(byte[] octets) throws Exception {
        Signature signataire = Signature.getInstance("SHA256withECDSA");
        signataire.initSign(paire.getPrivate());
        signataire.update(octets);
        return Base64.getEncoder().encodeToString(signataire.sign());
    }

    /**
     * Sert un manifeste et sa signature sur le serveur local.
     *
     * @return l'URL du manifeste.
     */
    private String servir(String corpsManifeste, String signature) throws IOException {
        byte[] octets = corpsManifeste.getBytes(StandardCharsets.UTF_8);
        serveur.createContext("/manifest.json", echange -> {
            echange.getResponseHeaders().set("Content-Type", "application/json");
            repondre(echange, octets);
        });
        serveur.createContext("/manifest.sig", echange -> {
            requetesSignature.incrementAndGet();
            repondre(echange, signature.getBytes(StandardCharsets.UTF_8));
        });
        return "http://127.0.0.1:" + serveur.getAddress().getPort() + "/manifest.json";
    }

    private static void repondre(com.sun.net.httpserver.HttpExchange echange,
                                 byte[] corps) throws IOException {
        try (OutputStream sortie = echange.getResponseBody()) {
            echange.sendResponseHeaders(200, corps.length);
            sortie.write(corps);
        }
    }

    private CanalMoteur canalTest() {
        return new CanalMoteur(clePubliqueBase64, Set.of("http"));
    }

    // ---- Scénarios ----------------------------------------------------------

    @Test
    void manifesteSigneAccepteEtDecode() throws Exception {
        String corps = manifesteJson("0.2.0");
        String url = servir(corps, signer(corps.getBytes(StandardCharsets.UTF_8)));

        CanalMoteur.ManifesteSigne lu = canalTest().telecharger(url);

        assertEquals("0.2.0", lu.manifeste().engineVersion());
        assertEquals(1, lu.manifeste().components().size());
        assertEquals(corps, new String(lu.octets(), StandardCharsets.UTF_8));
        assertEquals(1, requetesSignature.get(),
                "la signature doit être demandée exactement une fois");
    }

    @Test
    void manifesteFalsifieRefuseNet() throws Exception {
        // Signature couvrant un AUTRE manifeste : le contenu servi a été modifié.
        String url = servir(manifesteJson("0.2.0-empoisonné"),
                signer(manifesteJson("0.2.0").getBytes(StandardCharsets.UTF_8)));

        ManifestException echec = assertThrows(ManifestException.class,
                () -> canalTest().telecharger(url));
        assertTrue(echec.getMessage().contains("INVALIDE"),
                "le refus doit citer une signature invalide : " + echec.getMessage());
    }

    @Test
    void schemaNonHttpsRefuseParLeCanalDeProduction() throws Exception {
        String corps = manifesteJson("0.2.0");
        // Canal servi en HTTP (boucle locale) : le constructeur de production doit
        // refuser AVANT toute requête réseau.
        String url = servir(corps, signer(corps.getBytes(StandardCharsets.UTF_8)));

        ManifestException echec = assertThrows(ManifestException.class,
                () -> new CanalMoteur(clePubliqueBase64).telecharger(url));
        assertTrue(echec.getMessage().contains("HTTPS exigé"),
                "le message doit exiger HTTPS : " + echec.getMessage());
    }

    @Test
    void signatureAbsenteDonneUneErreurClaire() throws Exception {
        String corps = manifesteJson("0.2.0");
        String url = servir(corps, signer(corps.getBytes(StandardCharsets.UTF_8)));
        serveur.removeContext("/manifest.sig");
        serveur.createContext("/manifest.sig", echange -> {
            try (OutputStream sortie = echange.getResponseBody()) {
                echange.sendResponseHeaders(404, -1);
            }
        });

        ManifestException echec = assertThrows(ManifestException.class,
                () -> canalTest().telecharger(url));
        assertTrue(echec.getMessage().contains("HTTP 404"),
                "l'erreur doit citer le code : " + echec.getMessage());
    }

    @Test
    void conventionDeLUrlDeSignature() {
        assertEquals("https://exemple.fr/manifest.sig",
                CanalMoteur.urlSignature("https://exemple.fr/manifest.json"));
        assertEquals("https://exemple.fr/canal.sig",
                CanalMoteur.urlSignature("https://exemple.fr/canal"));
    }
}
