package jo.forge.api;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * Lit des trames longueur-préfixées depuis un flux d'entrée.
 *
 * <p>Thread-sûreté : un seul thread lit ; les trames lues sont ensuite distribuées par
 * l'appelant. La fin propre du pair (EOF exactement entre deux trames) retourne
 * {@code null} ; un EOF au milieu d'une trame lève {@link EOFException} — c'est le signe
 * d'un écroulement du pair, à distinguer d'une fermeture propre.</p>
 */
public final class FrameReader implements Closeable {

    private final DataInputStream in;

    /**
     * Crée un lecteur par-dessus le flux donné.
     *
     * @param in flux d'entrée du canal (souvent {@code stdin} d'un processus enfant).
     */
    public FrameReader(InputStream in) {
        this.in = new DataInputStream(new BufferedInputStream(in, 32 * 1024));
    }

    /**
     * Lit la prochaine trame, en bloquant si nécessaire.
     *
     * @return la trame lue, ou {@code null} si le pair a fermé proprement le canal.
     * @throws EOFException    si le flux s'est coupé au milieu d'une trame.
     * @throws IOException     en cas d'erreur de lecture.
     * @throws ProtocolException si la longueur annoncée est invalide (négative, dépassant
     *                           {@link Frame#MAX_FRAME_BYTES}) ou si l'en-tête est trop court.
     */
    public Frame readFrame() throws IOException {
        int length;
        try {
            length = in.readInt();
        } catch (EOFException firstByteEof) {
            // EOF franc avant même la longueur : fermeture propre du pair.
            return null;
        }
        if (length < 10 || length > Frame.MAX_FRAME_BYTES) {
            throw new ProtocolException("Longueur de trame invalide : " + length);
        }
        int typeCode = in.readUnsignedShort();
        long correlationId = in.readLong();
        byte[] payload = new byte[length - 10];
        in.readFully(payload);
        return new Frame(FrameType.fromWire(typeCode), correlationId, payload);
    }

    /**
     * Ferme le flux sous-jacent.
     *
     * @throws IOException en cas d'erreur de fermeture.
     */
    @Override
    public void close() throws IOException {
        in.close();
    }
}
