package jo.forge.api;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.Flushable;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Écrit des trames sur un flux de sortie, avec une longueur devant chaque trame.
 *
 * <p>Thread-sûreté : les écritures sont synchronisées — plusieurs threads (lecteur du canal,
 * travailleuses de build) peuvent émettre des trames sans entrelacer les octets. Chaque
 * trame est écrite en une seule section critique puis purgée ({@code flush}), pour qu'une
 * trame ne soit jamais visible à moitié chez le pair.</p>
 *
 * <p>Format : {@code [int32 longueur][int16 type][int64 corrélation][charge utile]}, où
 * {@code longueur} compte tout ce qui la suit ({@code 10 + charge utile}).</p>
 */
public final class FrameWriter implements Closeable, Flushable {

    private final DataOutputStream out;
    private final Object lock = new Object();
    private volatile boolean closed;

    /**
     * Crée un écrivain par-dessus le flux donné.
     *
     * @param out flux de sortie du canal (souvent {@code stdout} d'un processus enfant).
     */
    public FrameWriter(OutputStream out) {
        this.out = new DataOutputStream(new BufferedOutputStream(out, 32 * 1024));
    }

    /**
     * Écrit une trame et la purge.
     *
     * @param frame trame à écrire, jamais {@code null}.
     * @throws IOException en cas d'erreur d'écriture ou si l'écrivain est fermé.
     */
    public void write(Frame frame) throws IOException {
        java.util.Objects.requireNonNull(frame, "frame");
        synchronized (lock) {
            if (closed) {
                throw new IOException("Écrivain de trames déjà fermé");
            }
            out.writeInt(10 + frame.payload().length);
            out.writeShort(frame.type().wireCode());
            out.writeLong(frame.correlationId());
            out.write(frame.payload());
            out.flush();
        }
    }

    /**
     * Écrit une trame construite par un encodeur.
     *
     * @param type          type de la trame.
     * @param correlationId identifiant de corrélation.
     * @param encoder       encodeur qui remplit la charge utile.
     * @throws IOException en cas d'erreur d'écriture.
     */
    public void write(FrameType type, long correlationId, PayloadWriter encoder)
            throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        encoder.writeTo(new java.io.DataOutputStream(buffer));
        write(new Frame(type, correlationId, buffer.toByteArray()));
    }

    /**
     * Ferme le flux sous-jacent. Les écritures ultérieures échouent proprement.
     *
     * @throws IOException en cas d'erreur de fermeture.
     */
    @Override
    public void close() throws IOException {
        synchronized (lock) {
            closed = true;
            out.close();
        }
    }

    @Override
    public void flush() throws IOException {
        synchronized (lock) {
            out.flush();
        }
    }

    /** Fonction d'encodage d'une charge utile. */
    @FunctionalInterface
    public interface PayloadWriter {

        /**
         * Encode la charge utile dans le flux donné.
         *
         * @param out flux cible.
         * @throws IOException en cas d'erreur d'encodage.
         */
        void writeTo(java.io.DataOutput out) throws IOException;
    }
}
