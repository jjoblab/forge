package jo.forge.api;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Codecs des charges utiles du protocole : un encodeur et un décodeur par message.
 *
 * <p>Toutes les chaînes sont écrites en UTF-8 avec une longueur sur 32 bits (les
 * {@code writeUTF} de la plate-forme sont limités à 64 Kio, insuffisant pour des listes
 * de chemins). Un champ chaîne nul est encodé par la longueur {@code -1} ; les listes
 * sont préfixées par leur taille. Ces règles sont la version 1 du format — tout
 * changement passe par une nouvelle version de protocole
 * (voir {@link ProtocolVersions}).</p>
 */
public final class MessageIO {

    /** Marqueur filaire d'une chaîne nulle. */
    private static final int NULL_STRING = -1;

    /** Longueur maximale raisonnable d'une chaîne : 64 Mio (garde-fou contre un canal fou). */
    private static final int MAX_STRING_BYTES = 64 * 1024 * 1024;

    /** Codes des types d'événement dans la charge utile de {@code BUILD_EVENT}. */
    private static final int KIND_LOG = 1;
    private static final int KIND_DIAGNOSTIC = 2;
    private static final int KIND_TASK_STATE = 3;
    private static final int KIND_PROGRESS = 4;
    private static final int KIND_FINISHED = 5;

    private MessageIO() {
        // Utilitaire statique : pas d'instanciation.
    }

    // ---- Chaînes et listes -------------------------------------------------

    /**
     * Écrit une chaîne UTF-8 (ou le marqueur nul).
     *
     * @param out flux cible.
     * @param value chaîne, éventuellement nulle.
     * @throws IOException en cas d'erreur d'écriture.
     */
    public static void writeString(DataOutput out, String value) throws IOException {
        if (value == null) {
            out.writeInt(NULL_STRING);
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    /**
     * Lit une chaîne UTF-8 (éventuellement nulle).
     *
     * @param in flux source.
     * @return la chaîne lue, ou {@code null}.
     * @throws IOException en cas d'erreur de lecture.
     */
    public static String readString(DataInput in) throws IOException {
        int length = in.readInt();
        if (length == NULL_STRING) {
            return null;
        }
        if (length < 0 || length > MAX_STRING_BYTES) {
            throw new ProtocolException("Longueur de chaîne invalide : " + length);
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Écrit une liste de chaînes (éventuellement vide, jamais nulle côté écrivain).
     *
     * @param out flux cible.
     * @param values liste de chaînes.
     * @throws IOException en cas d'erreur d'écriture.
     */
    public static void writeStringList(DataOutput out, List<String> values) throws IOException {
        out.writeInt(values.size());
        for (String value : values) {
            writeString(out, value);
        }
    }

    /**
     * Lit une liste de chaînes.
     *
     * @param in flux source.
     * @return la liste lue, éventuellement vide.
     * @throws IOException en cas d'erreur de lecture.
     */
    public static List<String> readStringList(DataInput in) throws IOException {
        int size = in.readInt();
        if (size < 0 || size > 1_000_000) {
            throw new ProtocolException("Taille de liste invalide : " + size);
        }
        List<String> values = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            values.add(readString(in));
        }
        return values;
    }

    // ---- Poignée de main et état -------------------------------------------

    /**
     * Encode un {@link Hello}.
     *
     * @param out    flux cible.
     * @param message message à encoder.
     * @throws IOException en cas d'erreur d'écriture.
     */
    public static void writeHello(DataOutput out, Hello message) throws IOException {
        writeString(out, message.engineVersion());
        out.writeInt(message.protocolMin());
        out.writeInt(message.protocolMax());
        writeStringList(out, message.capabilities());
    }

    /**
     * Décode un {@link Hello}.
     *
     * @param in flux source.
     * @return le message décodé.
     * @throws IOException en cas d'erreur de lecture.
     */
    public static Hello readHello(DataInput in) throws IOException {
        return new Hello(readString(in), in.readInt(), in.readInt(), readStringList(in));
    }

    /**
     * Encode un {@link ClientHello}.
     *
     * @param out    flux cible.
     * @param message message à encoder.
     * @throws IOException en cas d'erreur d'écriture.
     */
    public static void writeClientHello(DataOutput out, ClientHello message) throws IOException {
        writeString(out, message.shellVersion());
        out.writeInt(message.protocolVersion());
    }

    /**
     * Décode un {@link ClientHello}.
     *
     * @param in flux source.
     * @return le message décodé.
     * @throws IOException en cas d'erreur de lecture.
     */
    public static ClientHello readClientHello(DataInput in) throws IOException {
        return new ClientHello(readString(in), in.readInt());
    }

    /**
     * Encode un {@link Status}.
     *
     * @param out    flux cible.
     * @param message message à encoder.
     * @throws IOException en cas d'erreur d'écriture.
     */
    public static void writeStatus(DataOutput out, Status message) throws IOException {
        writeString(out, message.engineVersion());
        out.writeInt(message.state().wireCode());
        out.writeLong(message.uptimeMillis());
        writeStringList(out, message.capabilities());
    }

    /**
     * Décode un {@link Status}.
     *
     * @param in flux source.
     * @return le message décodé.
     * @throws IOException en cas d'erreur de lecture.
     */
    public static Status readStatus(DataInput in) throws IOException {
        return new Status(readString(in), EngineState.fromWire(in.readInt()),
                in.readLong(), readStringList(in));
    }

    /**
     * Encode un {@link Pong}.
     *
     * @param out    flux cible.
     * @param message message à encoder.
     * @throws IOException en cas d'erreur d'écriture.
     */
    public static void writePong(DataOutput out, Pong message) throws IOException {
        out.writeLong(message.engineTimestamp());
    }

    /**
     * Décode un {@link Pong}.
     *
     * @param in flux source.
     * @return le message décodé.
     * @throws IOException en cas d'erreur de lecture.
     */
    public static Pong readPong(DataInput in) throws IOException {
        return new Pong(in.readLong());
    }

    // ---- Builds ------------------------------------------------------------

    /**
     * Encode un {@link BuildRequest}.
     *
     * @param out    flux cible.
     * @param message message à encoder.
     * @throws IOException en cas d'erreur d'écriture.
     */
    public static void writeBuildRequest(DataOutput out, BuildRequest message) throws IOException {
        writeString(out, message.buildId());
        writeString(out, message.projectRoot());
        out.writeInt(message.options().parallelism());
        out.writeBoolean(message.options().lowMemory());
        out.writeBoolean(message.options().offline());
        out.writeBoolean(message.options().verbose());
        out.writeLong(message.options().totalRamMb());
        writeString(out, message.options().abi());
    }

    /**
     * Décode un {@link BuildRequest}.
     *
     * @param in flux source.
     * @return le message décodé.
     * @throws IOException en cas d'erreur de lecture.
     */
    public static BuildRequest readBuildRequest(DataInput in) throws IOException {
        String buildId = readString(in);
        String projectRoot = readString(in);
        int parallelism = in.readInt();
        boolean lowMemory = in.readBoolean();
        boolean offline = in.readBoolean();
        boolean verbose = in.readBoolean();
        long totalRamMb = in.readLong();
        String abi = readString(in);
        return new BuildRequest(buildId, projectRoot,
                new BuildOptions(parallelism, lowMemory, offline, verbose, totalRamMb, abi));
    }

    /**
     * Encode un {@link CancelRequest}.
     *
     * @param out    flux cible.
     * @param message message à encoder.
     * @throws IOException en cas d'erreur d'écriture.
     */
    public static void writeCancelRequest(DataOutput out, CancelRequest message)
            throws IOException {
        writeString(out, message.buildId());
    }

    /**
     * Décode un {@link CancelRequest}.
     *
     * @param in flux source.
     * @return le message décodé.
     * @throws IOException en cas d'erreur de lecture.
     */
    public static CancelRequest readCancelRequest(DataInput in) throws IOException {
        return new CancelRequest(readString(in));
    }

    /**
     * Encode un {@link BuildStarted}.
     *
     * @param out    flux cible.
     * @param message message à encoder.
     * @throws IOException en cas d'erreur d'écriture.
     */
    public static void writeBuildStarted(DataOutput out, BuildStarted message) throws IOException {
        writeString(out, message.buildId());
    }

    /**
     * Décode un {@link BuildStarted}.
     *
     * @param in flux source.
     * @return le message décodé.
     * @throws IOException en cas d'erreur de lecture.
     */
    public static BuildStarted readBuildStarted(DataInput in) throws IOException {
        return new BuildStarted(readString(in));
    }

    /**
     * Encode un {@link Cancelled}.
     *
     * @param out    flux cible.
     * @param message message à encoder.
     * @throws IOException en cas d'erreur d'écriture.
     */
    public static void writeCancelled(DataOutput out, Cancelled message) throws IOException {
        writeString(out, message.buildId());
        out.writeBoolean(message.wasRunning());
    }

    /**
     * Décode un {@link Cancelled}.
     *
     * @param in flux source.
     * @return le message décodé.
     * @throws IOException en cas d'erreur de lecture.
     */
    public static Cancelled readCancelled(DataInput in) throws IOException {
        return new Cancelled(readString(in), in.readBoolean());
    }

    // ---- Événements --------------------------------------------------------

    /**
     * Encode un {@link BuildEvent} (quel que soit son sous-type).
     *
     * @param out    flux cible.
     * @param event  événement à encoder.
     * @throws IOException en cas d'erreur d'écriture.
     */
    public static void writeBuildEvent(DataOutput out, BuildEvent event) throws IOException {
        writeString(out, event.buildId());
        if (event instanceof BuildEvent.LogEvent log) {
            out.writeByte(KIND_LOG);
            out.writeLong(log.timestamp());
            out.writeInt(log.level().wireCode());
            writeString(out, log.message());
        } else if (event instanceof BuildEvent.DiagnosticEvent diag) {
            out.writeByte(KIND_DIAGNOSTIC);
            out.writeLong(diag.timestamp());
            out.writeInt(diag.severity().wireCode());
            writeString(out, diag.file());
            out.writeInt(diag.line());
            out.writeInt(diag.column());
            writeString(out, diag.code());
            writeString(out, diag.message());
            writeString(out, diag.tool());
        } else if (event instanceof BuildEvent.TaskStateEvent task) {
            out.writeByte(KIND_TASK_STATE);
            out.writeLong(task.timestamp());
            writeString(out, task.taskName());
            out.writeInt(task.state().wireCode());
            out.writeLong(task.durationMs());
        } else if (event instanceof BuildEvent.ProgressEvent progress) {
            out.writeByte(KIND_PROGRESS);
            out.writeLong(progress.timestamp());
            out.writeInt(progress.tasksTotal());
            out.writeInt(progress.tasksDone());
        } else if (event instanceof BuildEvent.BuildFinished finished) {
            out.writeByte(KIND_FINISHED);
            out.writeLong(finished.timestamp());
            out.writeBoolean(finished.success());
            writeString(out, finished.failedTask());
            out.writeLong(finished.durationMs());
            writeString(out, finished.message());
        } else {
            throw new ProtocolException("Type d'événement non géré : " + event.getClass());
        }
    }

    /**
     * Décode un {@link BuildEvent}.
     *
     * @param in flux source.
     * @return l'événement décodé.
     * @throws IOException en cas d'erreur de lecture.
     */
    public static BuildEvent readBuildEvent(DataInput in) throws IOException {
        String buildId = readString(in);
        int kind = in.readUnsignedByte();
        long timestamp = in.readLong();
        switch (kind) {
            case KIND_LOG:
                return new BuildEvent.LogEvent(buildId, timestamp,
                        BuildEvent.LogEvent.LogLevel.fromWire(in.readInt()), readString(in));
            case KIND_DIAGNOSTIC:
                return new BuildEvent.DiagnosticEvent(buildId, timestamp,
                        BuildEvent.Severity.fromWire(in.readInt()), readString(in),
                        in.readInt(), in.readInt(), readString(in), readString(in),
                        readString(in));
            case KIND_TASK_STATE:
                return new BuildEvent.TaskStateEvent(buildId, timestamp, readString(in),
                        BuildEvent.TaskState.fromWire(in.readInt()), in.readLong());
            case KIND_PROGRESS:
                return new BuildEvent.ProgressEvent(buildId, timestamp,
                        in.readInt(), in.readInt());
            case KIND_FINISHED:
                return new BuildEvent.BuildFinished(buildId, timestamp, in.readBoolean(),
                        readString(in), in.readLong(), readString(in));
            default:
                throw new ProtocolException("Type d'événement inconnu : " + kind);
        }
    }

    // ---- Erreurs -----------------------------------------------------------

    /**
     * Encode le message d'une trame d'erreur ({@code PROTOCOL_ERROR}, {@code INTERNAL_ERROR}).
     *
     * @param out     flux cible.
     * @param message message humain.
     * @throws IOException en cas d'erreur d'écriture.
     */
    public static void writeErrorMessage(DataOutput out, String message) throws IOException {
        writeString(out, message);
    }

    /**
     * Décode le message d'une trame d'erreur.
     *
     * @param in flux source.
     * @return le message humain.
     * @throws IOException en cas d'erreur de lecture.
     */
    public static String readErrorMessage(DataInput in) throws IOException {
        return readString(in);
    }
}
