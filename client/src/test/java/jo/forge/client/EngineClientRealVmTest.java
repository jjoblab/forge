package jo.forge.client;

import jo.forge.api.BuildEvent;
import jo.forge.api.BuildOptions;
import jo.forge.api.BuildRequest;
import jo.forge.api.BuildStarted;
import jo.forge.api.Cancelled;
import jo.forge.api.EngineState;
import jo.forge.api.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test d'intégration de l'étape 2 : le moteur factice tourne dans un VRAI processus
 * séparé ({@code java}), lancé par le client exactement comme sur appareil
 * ({@code app_process}). Valide la poignée de main, le ping, l'état, les builds
 * scriptés, l'annulation et l'arrêt — de bout en bout à travers le canal stdio.
 */
class EngineClientRealVmTest {

    /** Classpath courant du test : contient les classes api, client et engine. */
    private static String classpath() {
        return System.getProperty("java.class.path");
    }

    private static VmSpec moteurFactice() {
        // --fake : moteur scripté de l'étape 2 (le défaut est le vrai moteur, étape 8).
        return new VmSpec(List.of(classpath()),
                "jo.forge.engine.bootstrap.EngineMain", 256, List.of("--fake"));
    }

    private static BuildRequest requete(String buildId) {
        return new BuildRequest(buildId, "/projets/demo",
                new BuildOptions(2, false, false, false, 4096, "x86_64"));
    }

    @Test
    @Timeout(60)
    void poigneeDeMainPingEtat() throws Exception {
        EngineClient client = EngineClient.launch(new JavaVmLauncher(), moteurFactice(),
                "0.1.0-test", evenement -> { }, () -> { });

        assertEquals(jo.forge.engine.bootstrap.FakeEngineServer.ENGINE_VERSION,
                client.hello().engineVersion());

        long pong = client.ping().get(10, TimeUnit.SECONDS);
        assertTrue(pong > 0, "l'horodatage du pong doit être positif");

        Status etat = client.status().get(10, TimeUnit.SECONDS);
        assertEquals(EngineState.IDLE, etat.state());
        assertTrue(etat.capabilities().contains("fake-build"));

        client.shutdown().get(10, TimeUnit.SECONDS);
        int sortie = client.awaitExit();
        assertEquals(0, sortie, "le moteur factice doit s'arrêter proprement (code 0)");
    }

    @Test
    @Timeout(60)
    void buildReussiAvecEvenements() throws Exception {
        List<BuildEvent> evenements = new CopyOnWriteArrayList<>();
        CompletableFuture<BuildEvent.BuildFinished> fin = new CompletableFuture<>();
        EngineClient client = EngineClient.launch(new JavaVmLauncher(), moteurFactice(),
                "0.1.0-test",
                evenement -> {
                    evenements.add(evenement);
                    if (evenement instanceof BuildEvent.BuildFinished buildFin) {
                        fin.complete(buildFin);
                    }
                },
                () -> { });

        BuildStarted demarrage = client.startBuild(requete("build-ok"))
                .get(10, TimeUnit.SECONDS);
        assertEquals("build-ok", demarrage.buildId());

        BuildEvent.BuildFinished finBuild = fin.get(30, TimeUnit.SECONDS);
        assertTrue(finBuild.success(), "le build factice doit réussir");

        assertTrue(evenements.stream().anyMatch(e ->
                        e instanceof BuildEvent.LogEvent), "au moins un journal attendu");
        assertTrue(evenements.stream().anyMatch(e ->
                        e instanceof BuildEvent.TaskStateEvent t
                                && t.state() == jo.forge.api.BuildEvent.TaskState.SUCCEEDED),
                "au moins une tâche réussie attendue");
        assertEquals("build-ok", evenements.get(0).buildId());

        client.shutdown().get(10, TimeUnit.SECONDS);
        assertEquals(0, client.awaitExit());
    }

    @Test
    @Timeout(60)
    void buildEchoueAvecDiagnosticEtTacheFautive() throws Exception {
        CompletableFuture<BuildEvent.BuildFinished> fin = new CompletableFuture<>();
        EngineClient client = EngineClient.launch(new JavaVmLauncher(), moteurFactice(),
                "0.1.0-test",
                evenement -> {
                    if (evenement instanceof BuildEvent.BuildFinished buildFin) {
                        fin.complete(buildFin);
                    }
                },
                () -> { });

        client.startBuild(requete("fail-9")).get(10, TimeUnit.SECONDS);
        BuildEvent.BuildFinished finBuild = fin.get(30, TimeUnit.SECONDS);

        assertTrue(!finBuild.success());
        assertEquals("demo:compile", finBuild.failedTask());

        client.shutdown().get(10, TimeUnit.SECONDS);
        assertEquals(0, client.awaitExit());
    }

    @Test
    @Timeout(60)
    void annulationBuildLongPuisArret() throws Exception {
        CountDownLatch tacheDemarree = new CountDownLatch(1);
        CompletableFuture<Cancelled> accuse = new CompletableFuture<>();
        EngineClient client = EngineClient.launch(new JavaVmLauncher(), moteurFactice(),
                "0.1.0-test",
                evenement -> {
                    if (evenement instanceof BuildEvent.TaskStateEvent) {
                        tacheDemarree.countDown();
                    }
                },
                () -> { });

        client.startBuild(requete("slow-1")).get(10, TimeUnit.SECONDS);
        assertTrue(tacheDemarree.await(10, TimeUnit.SECONDS),
                "la tâche du build lent doit démarrer");

        client.cancel("slow-1").whenComplete((resultat, erreur) -> {
            if (erreur == null) {
                accuse.complete(resultat);
            } else {
                accuse.completeExceptionally(erreur);
            }
        });
        Cancelled annulation = accuse.get(30, TimeUnit.SECONDS);
        assertEquals("slow-1", annulation.buildId());
        assertTrue(annulation.wasRunning());

        client.shutdown().get(10, TimeUnit.SECONDS);
        assertEquals(0, client.awaitExit());
    }

    @Test
    @Timeout(60)
    void mortDuMoteurSignalee() throws Exception {
        CompletableFuture<Boolean> mort = new CompletableFuture<>();
        EngineClient client = EngineClient.launch(new JavaVmLauncher(), moteurFactice(),
                "0.1.0-test", evenement -> { }, () -> mort.complete(true));

        client.kill();
        assertTrue(mort.get(15, TimeUnit.SECONDS), "la mort du moteur doit être signalée");
    }

    @Test
    @Timeout(60)
    void mainIntrouvableEchecPropre() {
        VmSpec specInvalide = new VmSpec(List.of(classpath()),
                "jo.forge.engine.bootstrap.ClasseInexistante", 256, List.of());
        EngineConnection.EngineConnectionException erreur = assertThrows(
                EngineConnection.EngineConnectionException.class,
                () -> EngineClient.launch(new JavaVmLauncher(), specInvalide,
                        "0.1.0-test", evenement -> { }, () -> { }));
        assertTrue(erreur.getMessage() != null && !erreur.getMessage().isBlank(),
                "l'échec doit porter un message");
    }
}
