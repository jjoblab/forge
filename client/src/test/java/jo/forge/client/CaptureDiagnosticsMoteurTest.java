package jo.forge.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Capture des diagnostics du moteur (stderr) et du code de sortie — la chaîne qui
 * manque sur appareil quand le moteur meurt au milieu d'un build : sans elle, la trace
 * d'écroulement part avec le processus.
 *
 * <p>Deux angles : (1) un processus séparé qui écrit sur stderr puis meurt d'un code
 * connu, observé directement au travers d'{@link EngineConnection} ; (2) le moteur
 * factice complet lancé par {@link EngineClient} avec l'écoute du journal — arrêt
 * propre, code 0, journal vide.</p>
 */
class CaptureDiagnosticsMoteurTest {

    /** Binaire java courant (le même qui exécute ces tests). */
    private static String java() {
        return ProcessHandle.current().info().command().orElse("java");
    }

    /** Attend qu'un fournisseur retard publie une valeur non nulle (sentinelle). */
    private static Integer attendreCode(Supplier<Integer> code, long délaiMs)
            throws InterruptedException {
        long fin = System.currentTimeMillis() + délaiMs;
        Integer valeur = code.get();
        while (valeur == null && System.currentTimeMillis() < fin) {
            Thread.sleep(20);
            valeur = code.get();
        }
        return valeur;
    }

    @Test
    @Timeout(30)
    void stderrCaptureEtCodeDeSortiePubliés() throws Exception {
        Process process = new ProcessBuilder(java(), "-cp",
                System.getProperty("java.class.path"),
                "jo.forge.client.fixtures.MoteurBavard").start();
        List<String> reçues = new CopyOnWriteArrayList<>();
        CountDownLatch mort = new CountDownLatch(1);
        EngineConnection connexion = new EngineConnection(process,
                evenement -> { }, trame -> { }, mort::countDown, reçues::add).start();

        assertTrue(mort.await(15, TimeUnit.SECONDS),
                "la mort du processus doit être signalée sur le canal");
        Integer code = attendreCode(connexion::codeSortie, 5000);
        assertNotNull(code, "la sentinelle doit publier le code de sortie");
        assertEquals(7, code, "le code de sortie volontaire doit être rapporté tel quel");
        assertEquals(List.of("diagnostic un", "diagnostic deux", "diagnostic trois"),
                reçues, "chaque ligne de stderr doit atteindre le consommateur");
        assertEquals(reçues, connexion.journalErreurs(),
                "le journal interne de la connexion porte les mêmes lignes");
    }

    @Test
    @Timeout(30)
    void journalBorneAuxDernièresLignes() {
        JournalMoteur journal = new JournalMoteur(3);
        for (int i = 1; i <= 5; i++) {
            journal.ajouter("ligne " + i);
        }
        assertEquals(List.of("ligne 1", "ligne 2", "ligne 3", "ligne 4", "ligne 5")
                .subList(2, 5), journal.copie(), "les plus anciennes sortent");
        assertEquals(List.of("ligne 4", "ligne 5"), journal.dernières(2),
                "les n dernières demandées sont rendues");
        assertEquals(3, journal.taille());
    }

    @Test
    @Timeout(60)
    void moteurFacticeArrêtPropreCodeZéroJournalAccessible() throws Exception {
        VmSpec spec = new VmSpec(List.of(System.getProperty("java.class.path")),
                "jo.forge.engine.bootstrap.EngineMain", 256, List.of("--fake"));
        EngineClient client = EngineClient.launch(new JavaVmLauncher(), spec,
                "0.1.0-test", evenement -> { }, () -> { }, null);
        assertTrue(client.ping().get(10, TimeUnit.SECONDS) > 0);
        assertTrue(client.journalErreurs().isEmpty(),
                "le moteur factice silencieux n'écrit rien sur stderr");

        client.shutdown().get(10, TimeUnit.SECONDS);
        assertEquals(0, client.awaitExit(), "arrêt propre attendu");
        assertEquals(0, attendreCode(client::codeSortie, 5000),
                "la sentinelle doit publier le code 0 après l'arrêt effectif");
    }
}
