package jo.forge.api;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests du codage des trames : aller-retour, bornes et détection de canal coupé.
 */
class FrameRoundTripTest {

    @Test
    void allerRetourTrameAvecChargeUtile() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        FrameWriter writer = new FrameWriter(bytes);
        byte[] payload = {1, 2, 3, (byte) 0xFF};
        writer.write(new Frame(FrameType.PING, 42, payload));

        FrameReader reader = new FrameReader(new ByteArrayInputStream(bytes.toByteArray()));
        Frame frame = reader.readFrame();

        assertEquals(FrameType.PING, frame.type());
        assertEquals(42, frame.correlationId());
        assertArrayEquals(payload, frame.payload());
        assertNull(reader.readFrame(), "EOF propre attendu après la trame");
    }

    @Test
    void allerRetourTrameVide() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new FrameWriter(bytes).write(Frame.empty(FrameType.STATUS_REQUEST, 7));

        Frame frame = new FrameReader(new ByteArrayInputStream(bytes.toByteArray()))
                .readFrame();

        assertEquals(FrameType.STATUS_REQUEST, frame.type());
        assertEquals(0, frame.payload().length);
    }

    @Test
    void plusieursTramesSeSuivent() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        FrameWriter writer = new FrameWriter(bytes);
        writer.write(Frame.empty(FrameType.PING, 1));
        writer.write(Frame.empty(FrameType.PING, 2));
        writer.write(Frame.empty(FrameType.PING, 3));

        FrameReader reader = new FrameReader(new ByteArrayInputStream(bytes.toByteArray()));
        assertEquals(1, reader.readFrame().correlationId());
        assertEquals(2, reader.readFrame().correlationId());
        assertEquals(3, reader.readFrame().correlationId());
    }

    @Test
    void trameTropGrandeRefusee() {
        byte[] tropGrande = new byte[Frame.MAX_FRAME_BYTES];
        assertThrows(ProtocolException.class,
                () -> new Frame(FrameType.BUILD_EVENT, 1, tropGrande));
    }

    @Test
    void longueurInvalideRefusee() throws IOException {
        // Longueur annoncée trop petite (moins que l'en-tête seul).
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (java.io.DataOutputStream out = new java.io.DataOutputStream(bytes)) {
            out.writeInt(5);
        }
        FrameReader reader = new FrameReader(new ByteArrayInputStream(bytes.toByteArray()));
        assertThrows(ProtocolException.class, reader::readFrame);
    }

    @Test
    void typeInconnuRefuse() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (java.io.DataOutputStream out = new java.io.DataOutputStream(bytes)) {
            out.writeInt(10);
            out.writeShort(999);
            out.writeLong(1);
        }
        FrameReader reader = new FrameReader(new ByteArrayInputStream(bytes.toByteArray()));
        assertThrows(ProtocolException.class, reader::readFrame);
    }

    @Test
    void canalCoupeAuMilieuDuneTrame() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new FrameWriter(bytes).write(Frame.empty(FrameType.PING, 1));
        byte[] completes = bytes.toByteArray();
        byte[] tronquees = new byte[completes.length - 3];
        System.arraycopy(completes, 0, tronquees, 0, tronquees.length);

        FrameReader reader = new FrameReader(new ByteArrayInputStream(tronquees));
        assertThrows(EOFException.class, reader::readFrame);
    }
}
