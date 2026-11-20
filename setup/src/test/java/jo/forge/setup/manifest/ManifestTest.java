package jo.forge.setup.manifest;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests du manifeste : analyse, validation, signature ECDSA et rejets.
 */
class ManifestTest {

    private static KeyPair paire;
    private static String clePubliqueBase64;

    @BeforeAll
    static void genererCles() throws Exception {
        KeyPairGenerator generateur = KeyPairGenerator.getInstance("EC");
        generateur.initialize(new ECGenParameterSpec("secp256r1"));
        paire = generateur.generateKeyPair();
        clePubliqueBase64 = Base64.getEncoder()
                .encodeToString(paire.getPublic().getEncoded());
    }

    private static String signer(byte[] contenu) throws Exception {
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(paire.getPrivate());
        signature.update(contenu);
        return Base64.getEncoder().encodeToString(signature.sign());
    }

    private static ComponentManifest composant(String id) {
        return new ComponentManifest(id, "0.1.0", 100, "a".repeat(64),
                List.of("https://exemple.fr/" + id + ".zip"), List.of("all"), 1, List.of());
    }

    @Test
    void parseEtRewriteRoundTrip() {
        String json = """
                {
                  "manifestVersion": 1,
                  "engineVersion": "0.1.0",
                  "minAppVersion": 1,
                  "publishedAt": "2026-11-20T08:00:00Z",
                  "components": [
                    {"id": "engine-core", "version": "0.1.0", "size": 100,
                     "sha256": "%s",
                     "urls": ["https://exemple.fr/engine-core.zip"],
                     "abis": ["all"], "minAppVersion": 1, "depends": []},
                    {"id": "kotlin", "version": "2.0.0", "size": 200,
                     "sha256": "%s",
                     "urls": ["https://exemple.fr/kotlin.zip"],
                     "abis": ["all"], "minAppVersion": 1, "depends": ["engine-core"]}
                  ]
                }
                """.formatted("a".repeat(64), "b".repeat(64));
        EngineManifest manifest = ManifestCodec.parse(json);
        assertEquals(2, manifest.components().size());
        assertEquals(List.of("engine-core"), manifest.component("kotlin").depends());

        // Ré-écriture et re-lecture : mêmes données.
        EngineManifest relu = ManifestCodec.parse(ManifestCodec.write(manifest));
        assertEquals(manifest, relu);
    }

    @Test
    void filtreParAbi() {
        ComponentManifest pourArm = new ComponentManifest("aapt2-arm64-v8a", "34.0.0", 1,
                "c".repeat(64), List.of("https://exemple.fr/a.zip"),
                List.of("arm64-v8a"), 1, List.of());
        EngineManifest manifest = new EngineManifest(1, "0.1.0", 1, "date",
                List.of(composant("engine-core"), pourArm));
        assertEquals(2, manifest.forAbi("arm64-v8a").size());
        assertEquals(1, manifest.forAbi("x86_64").size());
    }

    @Test
    void httpRefuseALanalyse() {
        String json = """
                {
                  "manifestVersion": 1,
                  "engineVersion": "0.1.0",
                  "minAppVersion": 1,
                  "publishedAt": "d",
                  "components": [
                    {"id": "x", "version": "1.0.0", "size": 1, "sha256": "%s",
                     "urls": ["http://exemple.fr/x.zip"],
                     "abis": ["all"], "minAppVersion": 1, "depends": []}
                  ]
                }
                """.formatted("d".repeat(64));
        ManifestException erreur = assertThrows(ManifestException.class,
                () -> ManifestCodec.parse(json));
        assertTrue(erreur.getMessage().contains("HTTPS"),
                "le message doit expliquer le refus HTTPS");
    }

    @Test
    void shaInvalideRefuse() {
        assertThrows(ManifestException.class, () ->
                new ComponentManifest("x", "1.0.0", 1, "court", List.of("https://x.fr/a"),
                        List.of("all"), 1, List.of()));
    }

    @Test
    void empreinteOptionnelleMaisContrôlée() {
        // Absente (manifeste antérieur à v0.2.9) : acceptée, jamais réutilisable.
        ComponentManifest sansEmpreinte = new ComponentManifest("x", "1.0.0", 1,
                "e".repeat(64), List.of("https://x.fr/a"), List.of("all"), 1, List.of());
        assertNull(sansEmpreinte.empreinte());

        // Présente et bien formée : acceptée, conservée par la ré-écriture.
        ComponentManifest avecEmpreinte = new ComponentManifest("x", "1.0.0", 1,
                "e".repeat(64), List.of("https://x.fr/a"), List.of("all"), 1, List.of(),
                "f".repeat(64));
        assertEquals("f".repeat(64), avecEmpreinte.empreinte());
        String json = ManifestCodec.write(new EngineManifest(1, "0.1.0", 1, "date",
                List.of(avecEmpreinte)));
        assertTrue(json.contains("\"empreinte\""),
                "l'empreinte est écrite dans le manifeste");
        assertEquals("f".repeat(64),
                ManifestCodec.parse(json).component("x").empreinte());
        assertFalse(ManifestCodec.write(new EngineManifest(1, "0.1.0", 1, "date",
                List.of(sansEmpreinte))).contains("empreinte"),
                "pas de champ empreinte pour un composant qui n'en a pas");

        // Mal formée : refusée net (un manifeste mal construit, pas un cas toléré).
        assertThrows(ManifestException.class, () -> new ComponentManifest("x", "1.0.0",
                1, "e".repeat(64), List.of("https://x.fr/a"), List.of("all"), 1,
                List.of(), "court"));
    }

    @Test
    void dependanceInconnueRefusee() {
        ComponentManifest orphelin = new ComponentManifest("x", "1.0.0", 1, "e".repeat(64),
                List.of("https://x.fr/a"), List.of("all"), 1, List.of("inexistant"));
        assertThrows(ManifestException.class, () ->
                new EngineManifest(1, "0.1.0", 1, "date", List.of(orphelin)));
    }

    @Test
    void versionManifesteInconnueRefusee() {
        String json = """
                {"manifestVersion": 99, "engineVersion": "0.1.0", "minAppVersion": 1,
                 "publishedAt": "d", "components": []}
                """;
        assertThrows(ManifestException.class, () -> ManifestCodec.parse(json));
    }

    @Test
    void signatureValidePuisFalsifiee() throws Exception {
        byte[] contenu = "{\"ok\": true}".getBytes(StandardCharsets.UTF_8);
        ManifestVerifier verificateur = new ManifestVerifier(clePubliqueBase64);
        assertTrue(verificateur.verify(contenu, signer(contenu)),
                "la signature valide doit être acceptée");

        // Un seul octet changé : rejet net.
        byte[] falsifie = "{\"ok\": false}".getBytes(StandardCharsets.UTF_8);
        assertFalse(verificateur.verify(falsifie, signer(contenu)),
                "une signature d'un autre contenu doit être refusée");
    }

    @Test
    void clePubliqueInvalideRefusee() {
        assertThrows(ManifestException.class,
                () -> new ManifestVerifier("ceci-nest-pas-une-cle"));
    }
}
