package jo.forge.api;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests des codecs de messages : aller-retour de chaque type, chaînes longues, valeurs
 * nulles et rejets de codes inconnus.
 */
class MessageIOTest {

    private static DataOutputStream out() {
        return new DataOutputStream(new ByteArrayOutputStream());
    }

    private static <T> T roundTrip(T message, Writer<T> writer, Reader<T> reader)
            throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        writer.writeTo(out, message);
        return reader.readFrom(new DataInputStream(
                new ByteArrayInputStream(bytes.toByteArray())));
    }

    @Test
    void helloComplet() throws IOException {
        Hello message = new Hello("1.2.3", 1, 4, List.of("ping", "fake-build"));
        Hello relue = roundTrip(message, MessageIO::writeHello, MessageIO::readHello);
        assertEquals(message, relue);
    }

    @Test
    void clientHello() throws IOException {
        ClientHello message = new ClientHello("0.4.0", 2);
        assertEquals(message, roundTrip(message, MessageIO::writeClientHello,
                MessageIO::readClientHello));
    }

    @Test
    void statusAvecEtat() throws IOException {
        Status message = new Status("1.2.3", EngineState.BUILDING, 12_345,
                List.of("ping"));
        assertEquals(message, roundTrip(message, MessageIO::writeStatus, MessageIO::readStatus));
    }

    @Test
    void buildRequestEtOptions() throws IOException {
        BuildRequest message = new BuildRequest("build-7", "/projets/demo",
                new BuildOptions(3, false, true, true, 4096, "arm64-v8a"));
        assertEquals(message, roundTrip(message, MessageIO::writeBuildRequest,
                MessageIO::readBuildRequest));
    }

    @Test
    void chaineAuDelaDe64Kio() throws IOException {
        String grande = "é".repeat(70_000); // 2 octets par caractère : 140 000 octets.
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        MessageIO.writeString(out, grande);
        String relue = MessageIO.readString(
                new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
        assertEquals(grande, relue);
    }

    @Test
    void chainesNullesEtVides() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        MessageIO.writeString(out, null);
        MessageIO.writeString(out, "");
        DataInputStream in = new DataInputStream(
                new ByteArrayInputStream(bytes.toByteArray()));
        assertNull(MessageIO.readString(in));
        assertEquals("", MessageIO.readString(in));
    }

    @Test
    void chaqueTypeEvenement() throws IOException {
        BuildEvent[] evenements = {
            new BuildEvent.LogEvent("b1", 1, BuildEvent.LogEvent.LogLevel.WARN, "attention"),
            new BuildEvent.DiagnosticEvent("b1", 2, BuildEvent.Severity.ERROR, "A.java",
                    12, 3, "javac:err", "symbole introuvable", "compilateur-java"),
            new BuildEvent.TaskStateEvent("b1", 3, "app:compile",
                    BuildEvent.TaskState.UP_TO_DATE, 45),
            new BuildEvent.TaskStateEvent("b1", 6, "app:resolveDependencies",
                    BuildEvent.TaskState.FROM_CACHE, 12),
            new BuildEvent.ProgressEvent("b1", 4, 10, 6),
            new BuildEvent.BuildFinished("b1", 5, false, "app:compile", 1_234, "échec"),
            new BuildEvent.DownloadEvent("b1", 7, "ui-android-1.7.1.aar", 0, 4_000_000, false),
            new BuildEvent.DownloadEvent("b1", 8, "ui-android-1.7.1.aar", 4_000_000,
                    4_000_000, true),
            new BuildEvent.DownloadEvent("b1", 9, "pom-inconnu.xml", 512, -1, false),
        };
        for (BuildEvent evenement : evenements) {
            assertEquals(evenement, roundTrip(evenement, MessageIO::writeBuildEvent,
                    MessageIO::readBuildEvent));
        }
    }

    @Test
    void etatFromCacheEtEvenementTéléchargementOntLeursCodesFiliaires() {
        assertEquals(6, BuildEvent.TaskState.FROM_CACHE.wireCode());
        assertEquals(BuildEvent.TaskState.FROM_CACHE,
                BuildEvent.TaskState.fromWire(6));
        assertThrows(ProtocolException.class, () -> BuildEvent.TaskState.fromWire(99));
    }

    @Test
    void buildRequestInvalideRefuse() {
        assertThrows(ProtocolException.class,
                () -> new BuildRequest("", "/x", new BuildOptions(1, false, false, false, 1,
                        "arm64-v8a")));
    }

    @Test
    void cancelRequestVideRefuse() {
        assertThrows(ProtocolException.class, () -> new CancelRequest(""));
    }

    @Test
    void etatMoteurInconnuRefuse() {
        assertThrows(ProtocolException.class, () -> EngineState.fromWire(99));
    }

    /** Écrivain typé pour l'aller-retour. */
    @FunctionalInterface
    private interface Writer<T> {

        void writeTo(DataOutputStream out, T message) throws IOException;
    }

    /** Lecteur typé pour l'aller-retour. */
    @FunctionalInterface
    private interface Reader<T> {

        T readFrom(DataInputStream in) throws IOException;
    }
}
