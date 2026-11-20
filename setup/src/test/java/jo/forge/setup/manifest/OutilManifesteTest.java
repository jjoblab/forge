package jo.forge.setup.manifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test de bout en bout de l'outil manifeste : une paire générée par l'outil signe un
 * manifeste que le vérificateur de la coquille accepte (round-trip complet de la
 * chaîne de publication).
 */
class OutilManifesteTest {

    @TempDir
    Path temporaire;

    @Test
    void signerPuisVérifierRoundTrip() throws Exception {
        // Génération par l'outil.
        Path publique = temporaire.resolve("publique.b64");
        Path privée = temporaire.resolve("privée.b64");
        OutilManifeste.main(new String[]{"generer-cle",
                publique.toString(), privée.toString()});
        assertTrue(Files.size(publique) > 0);
        assertTrue(Files.size(privée) > 0);

        // Un manifeste réel, signé par l'outil.
        Path manifeste = temporaire.resolve("manifest.json");
        Files.writeString(manifeste, """
                {"manifestVersion":1,"engineVersion":"0.2.0","minAppVersion":1,
                 "publishedAt":"2026-11-20","components":[]}
                """);
        Path signature = temporaire.resolve("manifest.sig");
        OutilManifeste.main(new String[]{"signer",
                manifeste.toString(), privée.toString(), signature.toString()});

        // Le vérificateur de la coquille accepte (octets exacts).
        ManifestVerifier vérificateur = new ManifestVerifier(
                Files.readString(publique));
        assertTrue(vérificateur.verify(Files.readAllBytes(manifeste),
                Files.readString(signature)),
                "la signature de l'outil doit être valide pour la coquille");
    }

    @Test
    void signatureIndépendanteValideAussi() throws Exception {
        // La même signature produite directement par l'API JDK doit être acceptée :
        // garantit que l'outil n'introduit aucun format exotique.
        KeyPairGenerator générateur = KeyPairGenerator.getInstance("EC");
        générateur.initialize(256);
        KeyPair paire = générateur.generateKeyPair();
        byte[] contenu = "contenu de test".getBytes();
        Signature signataire = Signature.getInstance("SHA256withECDSA");
        signataire.initSign(paire.getPrivate());
        signataire.update(contenu);
        String signature = Base64.getEncoder().encodeToString(signataire.sign());

        ManifestVerifier vérificateur = new ManifestVerifier(
                Base64.getEncoder().encodeToString(paire.getPublic().getEncoded()));
        assertTrue(vérificateur.verify(contenu, signature));
    }
}
