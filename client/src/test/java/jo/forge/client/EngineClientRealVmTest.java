package jo.forge.client;

import jo.forge.api.BuildEvent;
import jo.forge.api.BuildOptions;
import jo.forge.api.BuildRequest;
import jo.forge.api.BuildStarted;
import jo.forge.api.Cancelled;
import jo.forge.api.EngineState;
import jo.forge.api.Status;
import jo.forge.api.TaskDescription;
import jo.forge.api.TasksResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void répertoireDeTâchesDeBoutEnBout() throws Exception {
        // Protocole 5 (D-22) : TASKS_REQUEST part du client, la réponse TASKS
        // revient décodée — le moteur (factice, en VRAI processus séparé) est la
        // source de vérité du répertoire, la coquille affiche.
        EngineClient client = EngineClient.launch(new JavaVmLauncher(), moteurFactice(),
                "0.1.0-test", evenement -> { }, () -> { });
        assertTrue(client.protocolVersion() >= 5,
                "le moteur factice doit négocier le protocole 5");

        TasksResponse répertoire = client.tasks("/projets/demo").get(10, TimeUnit.SECONDS);
        assertEquals("/projets/demo", répertoire.projectRoot());
        List<String> noms = répertoire.tasks().stream()
                .map(TaskDescription::name).toList();
        assertEquals(List.of("app:compileJava", "app:resolveDependencies",
                "assemble", "clean"), noms);
        TaskDescription assemble = répertoire.tasks().stream()
                .filter(t -> t.name().equals("assemble")).findFirst().orElseThrow();
        assertTrue(assemble.lifecycle(), "le cycle de vie est marqué honnêtement");
        assertEquals(List.of("app:jar"), assemble.dependencies());

        client.shutdown().get(10, TimeUnit.SECONDS);
        assertEquals(0, client.awaitExit());
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

    /**
     * Constat M-3 : une exception levée par le rappel d'événements de la coquille
     * était lue comme une COUPURE du canal — toutes les requêtes en attente
     * échouaient et un moteur sain était déclaré mort. Elle est désormais isolée :
     * journalisée dans le journal du moteur, le canal et le build continuent.
     */
    @Test
    @Timeout(60)
    void fauteDuRappelNEffondrePasLeCanal() throws Exception {
        List<BuildEvent> evenements = new CopyOnWriteArrayList<>();
        CompletableFuture<BuildEvent.BuildFinished> fin = new CompletableFuture<>();
        CompletableFuture<Boolean> mort = new CompletableFuture<>();
        EngineClient client = EngineClient.launch(new JavaVmLauncher(), moteurFactice(),
                "0.1.0-test",
                evenement -> {
                    // Capturé PUIS faute volontaire : la coquille plante en pleine
                    // réception, à chaque événement.
                    evenements.add(evenement);
                    if (evenement instanceof BuildEvent.BuildFinished buildFin) {
                        fin.complete(buildFin);
                    }
                    throw new IllegalStateException("faute volontaire du rappel");
                },
                () -> mort.complete(true));

        client.startBuild(requete("build-ok")).get(10, TimeUnit.SECONDS);
        BuildEvent.BuildFinished finBuild = fin.get(30, TimeUnit.SECONDS);
        assertTrue(finBuild.success(), "le build factice doit réussir malgré la coquille");

        // Le canal a survécu à toutes les fautes du rappel : la sonde passe.
        long pong = client.ping().get(10, TimeUnit.SECONDS);
        assertTrue(pong > 0, "le canal doit être intact après les fautes du rappel");
        assertFalse(mort.isDone(), "un moteur sain ne doit pas être déclaré mort");

        assertTrue(client.journalErreurs().stream()
                        .anyMatch(l -> l.contains("Rappel d'événement en échec")),
                "la faute du rappel doit être journalisée : "
                        + client.journalErreurs());

        client.shutdown().get(10, TimeUnit.SECONDS);
        assertEquals(0, client.awaitExit(), "arrêt propre : le canal était intact");
    }

    /**
     * Constat M-3 : après la mort du moteur, une requête échoue IMMÉDIATEMENT avec
     * un message clair — elle ne s'inscrit plus pour une réponse qui ne viendra
     * jamais.
     */
    @Test
    @Timeout(60)
    void requêteAprèsMortÉchoueImmédiatement() throws Exception {
        CompletableFuture<Boolean> mort = new CompletableFuture<>();
        EngineClient client = EngineClient.launch(new JavaVmLauncher(), moteurFactice(),
                "0.1.0-test", evenement -> { }, () -> mort.complete(true));

        client.kill();
        assertTrue(mort.get(15, TimeUnit.SECONDS), "la mort du moteur doit être signalée");

        long debut = System.currentTimeMillis();
        ExecutionException échec = assertThrows(ExecutionException.class,
                () -> client.ping().get(5, TimeUnit.SECONDS));
        long durée = System.currentTimeMillis() - debut;
        assertTrue(durée < 5_000,
                "l'échec doit être immédiat, pas attendu (durée : " + durée + " ms)");
        assertInstanceOf(EngineConnection.EngineConnectionException.class, échec.getCause());
        assertTrue(échec.getCause().getMessage().contains("déjà fermé"),
                "le message doit dire le canal fermé : " + échec.getCause().getMessage());
    }
}
