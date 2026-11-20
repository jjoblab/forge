package jo.forge.engine.server;

import jo.forge.api.BuildEvent;
import jo.forge.api.BuildOptions;
import jo.forge.api.BuildRequest;
import jo.forge.api.BuildStarted;
import jo.forge.api.ClientHello;
import jo.forge.api.EngineState;
import jo.forge.api.Frame;
import jo.forge.api.FrameReader;
import jo.forge.api.FrameType;
import jo.forge.api.FrameWriter;
import jo.forge.api.Hello;
import jo.forge.api.MessageIO;
import jo.forge.api.Status;
import jo.forge.engine.testutil.Projets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests du vrai serveur de build sur de vrais tuyaux : le protocole complet
 * (poignée de main, build, annulation, état, arrêt) avec le pipeline réel derrière —
 * sans processus séparé, mais par le même canal que sur appareil.
 */
class BuildEngineServerTest {

    @TempDir
    Path temporaire;

    @Test
    @Timeout(60)
    void buildCompletPuisIncrémentalSurLeCanal() throws Exception {
        Projets.projetDeuxModules(temporaire);
        try (Session session = Session.démarrer()) {
            session.poignéeDeMain();

            List<BuildEvent> événements = session.build(temporaire);
            BuildEvent.BuildFinished fin = dernier(événements, BuildEvent.BuildFinished.class);
            assertTrue(fin.success(), "le build réel doit réussir : " + fin.message());
            assertTrue(événements.stream().anyMatch(e ->
                            e instanceof BuildEvent.TaskStateEvent t
                                    && t.state() == BuildEvent.TaskState.SUCCEEDED
                                    && t.taskName().equals("core:compileJava")),
                    "la compilation de core doit réussir");
            // Trace exportable (A-13) : écrite à la racine du projet, atomique.
            assertTrue(Files.isRegularFile(temporaire.resolve(".forge/traces/b1.json")),
                    "la trace du premier build doit exister");
            assertTrue(Files.readString(temporaire.resolve(".forge/traces/b1.json"))
                            .contains("core:compileJava"),
                    "la trace contient les tâches");

            // Second build sans changement : tout à jour, toujours par le canal.
            List<BuildEvent> second = session.build(temporaire);
            BuildEvent.BuildFinished fin2 = dernier(second, BuildEvent.BuildFinished.class);
            assertTrue(fin2.success());
            assertTrue(second.stream().filter(e ->
                            e instanceof BuildEvent.TaskStateEvent t
                                    && t.state() == BuildEvent.TaskState.UP_TO_DATE)
                            .count() >= 4,
                    "les quatre tâches doivent être à jour");

            // Modifier une source : seule sa branche relance.
            Projets.fichier(temporaire, "app/src/main/java/demo/app/App.java", """
                    package demo.app;

                    import demo.core.Textes;

                    public final class App {
                        private App() { }

                        public static String bienvenue() {
                            return Textes.joindre(" ", "Encore", "bonjour !");
                        }
                    }
                    """);
            List<BuildEvent> troisième = session.build(temporaire);
            assertTrue(dernier(troisième, BuildEvent.BuildFinished.class).success());
            assertTrue(troisième.stream().noneMatch(e ->
                            e instanceof BuildEvent.TaskStateEvent t
                                    && t.state() == BuildEvent.TaskState.STARTED
                                    && t.taskName().startsWith("core:")),
                    "le module amont ne doit pas se relancer");

            // État puis arrêt propre.
            Status état = session.status();
            assertEquals(EngineState.IDLE, état.state());
            assertTrue(état.capabilities().contains("build"));
            session.arrêt();
        }
    }

    @Test
    @Timeout(60)
    void projetSansModèleÉchecPropreSansCasserLeCanal() throws Exception {
        Path vide = temporaire.resolve("projet-vide");
        java.nio.file.Files.createDirectories(vide);
        try (Session session = Session.démarrer()) {
            session.poignéeDeMain();
            List<BuildEvent> événements = session.build(vide);
            BuildEvent.BuildFinished fin = dernier(événements,
                    BuildEvent.BuildFinished.class);
            assertTrue(!fin.success(), "le build doit échouer proprement");
            assertTrue(fin.message().contains("forge.json"),
                    "le message doit citer le fichier manquant : " + fin.message());
            // Le canal survit : un second build sur un vrai projet fonctionne.
            Path projet = temporaire.resolve("projet-ok");
            Projets.projetDeuxModules(projet);
            BuildEvent.BuildFinished fin2 = dernier(session.build(projet),
                    BuildEvent.BuildFinished.class);
            assertTrue(fin2.success(), "le moteur doit rester utilisable après l'échec");
            session.arrêt();
        }
    }

    @Test
    @Timeout(60)
    void diagnosticDeCompilationTraverseLeCanal() throws Exception {
        Projets.projetDeuxModules(temporaire);
        Projets.fichier(temporaire, "core/src/main/java/demo/core/Fautif.java", """
                package demo.core;

                public class Fautif {
                    int calcul() { return inconnu; }
                }
                """);
        try (Session session = Session.démarrer()) {
            session.poignéeDeMain();
            List<BuildEvent> événements = session.build(temporaire);
            BuildEvent.BuildFinished fin = dernier(événements,
                    BuildEvent.BuildFinished.class);
            assertTrue(!fin.success());
            assertEquals("core:compileJava", fin.failedTask());
            BuildEvent.DiagnosticEvent diagnostic = événements.stream()
                    .filter(e -> e instanceof BuildEvent.DiagnosticEvent)
                    .map(e -> (BuildEvent.DiagnosticEvent) e)
                    .filter(d -> d.file().endsWith("Fautif.java"))
                    .findFirst().orElseThrow();
            assertEquals(4, diagnostic.line(), "l'erreur est ligne 4 (return fautif)");
            assertTrue(diagnostic.column() > 0);
            assertEquals("java-compiler", diagnostic.tool());
            session.arrêt();
        }
    }

    /**
     * Récupère le dernier événement du type demandé.
     */
    private static <T extends BuildEvent> T dernier(List<BuildEvent> événements,
                                                    Class<T> type) {
        T trouvé = null;
        for (BuildEvent événement : événements) {
            if (type.isInstance(événement)) {
                trouvé = type.cast(événement);
            }
        }
        if (trouvé == null) {
            throw new AssertionError("aucun événement " + type.getSimpleName()
                    + " reçu");
        }
        return trouvé;
    }

    /**
     * Session de test : le serveur tourne sur des tuyaux, le test joue la coquille.
     */
    private static final class Session implements AutoCloseable {

        private final BuildEngineServer serveur;
        private final Thread fil;
        private final FrameWriter versMoteur;
        private final FrameReader duMoteur;
        private final PipedOutputStream sortieVersMoteur;
        private long corrélation;

        private Session(BuildEngineServer serveur, Thread fil, FrameWriter versMoteur,
                        FrameReader duMoteur, PipedOutputStream sortieVersMoteur) {
            this.serveur = serveur;
            this.fil = fil;
            this.versMoteur = versMoteur;
            this.duMoteur = duMoteur;
            this.sortieVersMoteur = sortieVersMoteur;
        }

        static Session démarrer() throws IOException {
            PipedOutputStream versMoteurOut = new PipedOutputStream();
            PipedInputStream moteurIn = new PipedInputStream(versMoteurOut, 1 << 16);
            PipedOutputStream moteurOut = new PipedOutputStream();
            PipedInputStream depuisMoteur = new PipedInputStream(moteurOut, 1 << 16);

            BuildEngineServer serveur = new BuildEngineServer(moteurOut, System.err);
            Thread fil = new Thread(() -> {
                try {
                    serveur.serve(moteurIn);
                } catch (IOException e) {
                    // Fermeture du tuyau en fin de test : normale.
                }
            }, "serveur-moteur-test");
            fil.setDaemon(true);
            fil.start();

            Session session = new Session(serveur, fil,
                    new FrameWriter(versMoteurOut), new FrameReader(depuisMoteur),
                    versMoteurOut);
            // Poignée de main initiale du moteur.
            Frame hello = session.lireTrame();
            assertEquals(FrameType.HELLO, hello.type());
            return session;
        }

        /** Poignée de main complète : CLIENT_HELLO et accusé. */
        void poignéeDeMain() throws IOException {
            écrire(FrameType.CLIENT_HELLO, out -> MessageIO.writeClientHello(out,
                    new ClientHello("0.1.0-test", 1)));
            assertEquals(FrameType.CLIENT_HELLO, lireTrame().type());
        }

        /** Lance un build et collecte tous les événements jusqu'à la fin. */
        List<BuildEvent> build(Path racine) throws IOException {
            BuildRequest requête = new BuildRequest("b" + (++corrélation),
                    racine.toString(),
                    new BuildOptions(2, false, false, false, 4096, "x86_64"));
            écrire(FrameType.START_BUILD,
                    out -> MessageIO.writeBuildRequest(out, requête));
            Frame accusé = lireTrame();
            assertEquals(FrameType.BUILD_STARTED, accusé.type());
            assertEquals(requête.buildId(),
                    MessageIO.readBuildStarted(new DataInputStream(
                            new ByteArrayInputStream(accusé.payload()))).buildId());

            List<BuildEvent> événements = new ArrayList<>();
            while (true) {
                Frame trame = lireTrame();
                if (trame.type() != FrameType.BUILD_EVENT) {
                    throw new AssertionError("trame inattendue : " + trame.type());
                }
                BuildEvent événement = MessageIO.readBuildEvent(new DataInputStream(
                        new ByteArrayInputStream(trame.payload())));
                événements.add(événement);
                if (événement instanceof BuildEvent.BuildFinished) {
                    return événements;
                }
            }
        }

        /** Demande l'état du moteur. */
        Status status() throws IOException {
            écrire(FrameType.STATUS_REQUEST, out -> { });
            Frame réponse = lireTrame();
            assertEquals(FrameType.STATUS, réponse.type());
            return MessageIO.readStatus(new DataInputStream(
                    new ByteArrayInputStream(réponse.payload())));
        }

        /** Arrêt propre du moteur. */
        void arrêt() throws IOException, InterruptedException {
            écrire(FrameType.SHUTDOWN, out -> { });
            assertEquals(FrameType.SHUTDOWN, lireTrame().type());
            sortieVersMoteur.close();
            fil.join(TimeUnit.SECONDS.toMillis(10));
        }

        private Frame lireTrame() throws IOException {
            Frame trame = duMoteur.readFrame();
            if (trame == null) {
                throw new AssertionError("canal fermé avant la trame attendue");
            }
            return trame;
        }

        private void écrire(FrameType type, FrameWriter.PayloadWriter charge)
                throws IOException {
            versMoteur.write(type, ++corrélation, charge);
            versMoteur.flush();
        }

        @Override
        public void close() {
            try {
                sortieVersMoteur.close();
            } catch (IOException ignored) {
                // Déjà fermé.
            }
            try {
                fil.join(TimeUnit.SECONDS.toMillis(10));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
