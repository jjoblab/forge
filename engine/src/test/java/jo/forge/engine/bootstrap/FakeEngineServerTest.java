package jo.forge.engine.bootstrap;

import jo.forge.api.BuildEvent;
import jo.forge.api.BuildOptions;
import jo.forge.api.BuildRequest;
import jo.forge.api.BuildStarted;
import jo.forge.api.CancelRequest;
import jo.forge.api.Cancelled;
import jo.forge.api.ClientHello;
import jo.forge.api.EngineState;
import jo.forge.api.Frame;
import jo.forge.api.FrameReader;
import jo.forge.api.FrameType;
import jo.forge.api.FrameWriter;
import jo.forge.api.Hello;
import jo.forge.api.MessageIO;
import jo.forge.api.ProtocolVersions;
import jo.forge.api.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests du serveur factice en conversation directe (tubes, sans processus) : poignée de
 * main, refus d'incompatibilité, ping, état, builds scriptés et annulation.
 */
class FakeEngineServerTest {

    private FakeEngineServer serveur;
    private FrameWriter versServeur;
    private PipedOutputStream versServeurFlux;
    private FrameReader depuisServeur;
    private Thread threadServeur;

    @BeforeEach
    void demarrer() throws IOException {
        // Câblage : la coquille (test) écrit vers le serveur, le serveur écrit vers la coquille.
        versServeurFlux = new PipedOutputStream();
        PipedInputStream entreeServeur = new PipedInputStream(versServeurFlux, 1 << 16);
        PipedOutputStream sortieServeur = new PipedOutputStream();
        PipedInputStream versCoquille = new PipedInputStream(sortieServeur, 1 << 16);

        serveur = new FakeEngineServer(sortieServeur,
                new PrintStream(new ByteArrayOutputStream(), true));
        versServeur = new FrameWriter(versServeurFlux);
        depuisServeur = new FrameReader(versCoquille);

        threadServeur = new Thread(() -> {
            try {
                serveur.serve(entreeServeur);
            } catch (IOException ignored) {
                // Fin de test : le tube est fermé, le serveur s'arrête.
            }
        }, "serveur-factice-test");
        threadServeur.setDaemon(true);
        threadServeur.start();
    }

    @AfterEach
    void arreter() throws IOException, InterruptedException {
        // Fermer le tube d'entrée débloque la lecture bloquante du serveur.
        versServeurFlux.close();
        threadServeur.join(TimeUnit.SECONDS.toMillis(2));
    }

    private Frame lire() throws IOException {
        Frame frame = depuisServeur.readFrame();
        assertTrue(frame != null, "trame attendue, pas de fin de canal");
        return frame;
    }

    private <T> T charge(Frame frame, Lecteur<T> lecteur) throws IOException {
        return lecteur.lire(new DataInputStream(
                new ByteArrayInputStream(frame.payload())));
    }

    @Test
    void poigneeDeMainEtPing() throws IOException {
        Frame hello = lire();
        assertEquals(FrameType.HELLO, hello.type());
        Hello salut = charge(hello, MessageIO::readHello);
        assertEquals(FakeEngineServer.ENGINE_VERSION, salut.engineVersion());
        assertEquals(ProtocolVersions.PROTOCOL_MIN, salut.protocolMin());
        assertEquals(ProtocolVersions.PROTOCOL_MAX, salut.protocolMax());

        versServeur.write(FrameType.CLIENT_HELLO, 1, out ->
                MessageIO.writeClientHello(out, new ClientHello("test-shell", 1)));
        assertEquals(FrameType.CLIENT_HELLO, lire().type());

        versServeur.write(Frame.empty(FrameType.PING, 2));
        Frame pong = lire();
        assertEquals(FrameType.PONG, pong.type());
        assertEquals(2, pong.correlationId());
    }

    @Test
    void versionIncompatibleRefusee() throws IOException {
        lire(); // HELLO
        versServeur.write(FrameType.CLIENT_HELLO, 1, out ->
                MessageIO.writeClientHello(out, new ClientHello("test-shell", 99)));
        Frame erreur = lire();
        assertEquals(FrameType.PROTOCOL_ERROR, erreur.type());
        String message = charge(erreur, MessageIO::readErrorMessage);
        assertTrue(message.contains("99"), "le message doit citer la version refusée");
    }

    @Test
    void etatPuisBuildReussi() throws IOException {
        saluer();

        versServeur.write(Frame.empty(FrameType.STATUS_REQUEST, 5));
        Status etat = charge(lire(), MessageIO::readStatus);
        assertEquals(EngineState.IDLE, etat.state());

        versServeur.write(FrameType.START_BUILD, 6, out -> MessageIO.writeBuildRequest(out,
                new BuildRequest("build-ok", "/projets/demo",
                        new BuildOptions(2, false, false, false, 4096, "arm64-v8a"))));
        Frame demarrage = lire();
        assertEquals(FrameType.BUILD_STARTED, demarrage.type());
        assertEquals("build-ok", charge(demarrage, MessageIO::readBuildStarted).buildId());

        boolean finVue = false;
        while (!finVue) {
            Frame trame = lire();
            assertEquals(FrameType.BUILD_EVENT, trame.type());
            BuildEvent evenement = charge(trame, MessageIO::readBuildEvent);
            assertEquals("build-ok", evenement.buildId());
            finVue = evenement instanceof BuildEvent.BuildFinished fin && fin.success();
        }
    }

    @Test
    void buildEchoueAvecDiagnostic() throws IOException {
        saluer();
        versServeur.write(FrameType.START_BUILD, 6, out -> MessageIO.writeBuildRequest(out,
                new BuildRequest("fail-1", "/projets/demo",
                        new BuildOptions(2, false, false, false, 4096, "arm64-v8a"))));
        lire(); // BUILD_STARTED

        boolean echecVu = false;
        boolean diagnosticVu = false;
        while (!echecVu) {
            BuildEvent evenement = charge(lire(), MessageIO::readBuildEvent);
            if (evenement instanceof BuildEvent.DiagnosticEvent diag) {
                assertEquals(BuildEvent.Severity.ERROR, diag.severity());
                diagnosticVu = true;
            }
            if (evenement instanceof BuildEvent.BuildFinished fin) {
                echecVu = true;
                assertTrue(!fin.success());
                assertEquals("demo:compile", fin.failedTask());
            }
        }
        assertTrue(diagnosticVu, "un diagnostic d'erreur doit précéder l'échec");
    }

    @Test
    void annulationBuildLong() throws IOException {
        saluer();
        versServeur.write(FrameType.START_BUILD, 6, out -> MessageIO.writeBuildRequest(out,
                new BuildRequest("slow-1", "/projets/demo",
                        new BuildOptions(2, false, false, false, 4096, "arm64-v8a"))));
        lire(); // BUILD_STARTED

        versServeur.write(FrameType.CANCEL_BUILD, 7, out ->
                MessageIO.writeCancelRequest(out, new CancelRequest("slow-1")));
        Frame accuse = lire();
        assertEquals(FrameType.CANCELLED, accuse.type());
        Cancelled annulation = charge(accuse, MessageIO::readCancelled);
        assertEquals("slow-1", annulation.buildId());
        assertTrue(annulation.wasRunning(), "le build était en cours");
    }

    private void saluer() throws IOException {
        lire(); // HELLO
        versServeur.write(FrameType.CLIENT_HELLO, 1, out ->
                MessageIO.writeClientHello(out, new ClientHello("test-shell", 1)));
        lire(); // accusé CLIENT_HELLO
    }

    /** Lecteur typé de charge utile. */
    @FunctionalInterface
    private interface Lecteur<T> {

        T lire(DataInputStream in) throws IOException;
    }
}
