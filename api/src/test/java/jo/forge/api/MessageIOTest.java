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
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void buildRequestAvecNiveauDeConsole() throws IOException {
        for (BuildEvent.LogEvent.LogLevel niveau : BuildEvent.LogEvent.LogLevel.values()) {
            BuildRequest message = new BuildRequest("b-" + niveau, "/p",
                    new BuildOptions(2, false, false, false, 4096, "arm64-v8a", niveau));
            BuildRequest relue = roundTrip(message, MessageIO::writeBuildRequest,
                    MessageIO::readBuildRequest);
            assertEquals(niveau, relue.options().consoleLevel(),
                    "le niveau de console doit survivre à l'aller-retour");
            assertEquals(niveau == BuildEvent.LogEvent.LogLevel.DEBUG,
                    relue.options().verbose(),
                    "verbose dérive du niveau (une seule source de vérité)");
        }
    }

    @Test
    void chargeDAvantSansNiveauDeConsoleRemplaceParLeDéfaut() throws IOException {
        // Une coquille d'avant le protocole 4 n'écrit PAS le niveau de console : le
        // lecteur tolérant dérive du booléen verbose (bavard → DEBUG, sinon le
        // LIFECYCLE par défaut) — l'ancienne charge reste lisible.
        for (boolean verbose : new boolean[] {false, true}) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            MessageIO.writeString(out, "b-ancienne");
            MessageIO.writeString(out, "/projets/demo");
            out.writeInt(2);
            out.writeBoolean(false);
            out.writeBoolean(true);
            out.writeBoolean(verbose);
            out.writeLong(4096);
            MessageIO.writeString(out, "arm64-v8a");

            BuildRequest relue = MessageIO.readBuildRequest(new DataInputStream(
                    new ByteArrayInputStream(bytes.toByteArray())));
            assertEquals(verbose ? BuildEvent.LogEvent.LogLevel.DEBUG
                            : BuildEvent.LogEvent.LogLevel.LIFECYCLE,
                    relue.options().consoleLevel());
        }
    }

    @Test
    void buildRequestAvecTâches() throws IOException {
        // La cible du build (protocole 5) survit à l'aller-retour : noms complets
        // ET tâches de cycle de vie.
        BuildRequest message = new BuildRequest("b-cible", "/projets/demo",
                new BuildOptions(2, false, false, false, 4096, "arm64-v8a", null,
                        List.of("app:compileJava", "assemble")));
        BuildRequest relue = roundTrip(message, MessageIO::writeBuildRequest,
                MessageIO::readBuildRequest);
        assertEquals(List.of("app:compileJava", "assemble"), relue.options().tasks(),
                "la cible demandée doit survivre à l'aller-retour");
        assertEquals(List.of(), new BuildRequest("b-vide", "/p",
                new BuildOptions(2, false, false, false, 4096, "x86_64", null, null))
                .options().tasks(),
                "null devient la liste vide (le graphe complet)");
    }

    @Test
    void chargeDAvantSansTâchesResteLeGrapheComplet() throws IOException {
        // Une coquille d'avant le protocole 5 n'écrit PAS la liste de tâches : le
        // lecteur tolérant s'arrête après le niveau de console — la cible est le
        // graphe complet, la forme d'alors. Rien ne casse.
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        MessageIO.writeString(out, "b-avant-cinq");
        MessageIO.writeString(out, "/projets/demo");
        out.writeInt(2);
        out.writeBoolean(false);
        out.writeBoolean(false);
        out.writeBoolean(false);
        out.writeLong(4096);
        MessageIO.writeString(out, "arm64-v8a");
        out.writeInt(BuildEvent.LogEvent.LogLevel.LIFECYCLE.wireCode());
        // Fin de charge : pas de liste de tâches.

        BuildRequest relue = MessageIO.readBuildRequest(new DataInputStream(
                new ByteArrayInputStream(bytes.toByteArray())));
        assertEquals(List.of(), relue.options().tasks(),
                "sans liste écrite, la cible reste le graphe complet");
    }

    @Test
    void demandeEtRépertoireDeTâches() throws IOException {
        // Le répertoire (protocole 5) : racine, descripteurs complets (groupe,
        // description, dépendances, marque de cycle de vie) — aller-retour exact.
        TasksRequest demande = new TasksRequest("/projets/demo");
        assertEquals(demande, roundTrip(demande, MessageIO::writeTasksRequest,
                MessageIO::readTasksRequest));
        assertEquals(List.of(), roundTrip(demande, MessageIO::writeTasksRequest,
                MessageIO::readTasksRequest).modules(),
                "la forme historique reste « tout le projet »");

        // Protocole 6 (D-23) : le sous-ensemble de modules survit intact.
        TasksRequest sousEnsemble = new TasksRequest("/projets/demo",
                List.of("app", "core"));
        assertEquals(sousEnsemble, roundTrip(sousEnsemble,
                MessageIO::writeTasksRequest, MessageIO::readTasksRequest),
                "le répertoire par sous-ensemble doit survivre intact");

        TasksResponse répertoire = new TasksResponse("/projets/demo", List.of(
                new TaskDescription("app:compileJava", "compilation",
                        "Compile les sources Java du module",
                        List.of("app:resolveDependencies"), false),
                new TaskDescription("assemble", "cycle de vie",
                        "Assemble les sorties de tous les modules du projet",
                        List.of("app:signApk"), true),
                new TaskDescription("clean", "cycle de vie",
                        "Supprime le répertoire build/ du projet", List.of(), true)));
        TasksResponse relu = roundTrip(répertoire, MessageIO::writeTasksResponse,
                MessageIO::readTasksResponse);
        assertEquals(répertoire, relu, "le répertoire complet doit survivre intact");
        assertTrue(relu.tasks().stream().anyMatch(TaskDescription::lifecycle),
                "les tâches de cycle de vie restent marquées");
    }

    @Test
    void demandeDAvantSansModulesResteToutLeProjet() throws IOException {
        // Une coquille d'avant le protocole 6 n'écrit PAS la liste de modules :
        // le lecteur tolérant s'arrête après la racine — le sous-ensemble est
        // vide, la vue reste « tout le projet ». Rien ne casse.
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        MessageIO.writeString(out, "/projets/demo");
        // Fin de charge : pas de liste de modules.

        TasksRequest relue = MessageIO.readTasksRequest(new DataInputStream(
                new ByteArrayInputStream(bytes.toByteArray())));
        assertEquals("/projets/demo", relue.projectRoot());
        assertEquals(List.of(), relue.modules(),
                "sans liste écrite, la vue reste tout le projet");
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
            new BuildEvent.LogEvent("b1", 2, BuildEvent.LogEvent.LogLevel.LIFECYCLE,
                    "> Tâche app:compileJava — À JOUR"),
            new BuildEvent.DiagnosticEvent("b1", 3, BuildEvent.Severity.ERROR, "A.java",
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
            new BuildEvent.HeartbeatEvent("b1", 10, "app:dex", "", 30_000),
            new BuildEvent.HeartbeatEvent("b1", 11, "app:dex",
                    "dexage : 12/41 — ui-android-1.7.1.aar", 61_500),
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
    void lifecycleASonCodeFiliaireEtSaPlaceDansLaSévérité() {
        // Code filaire : identifiant STABLE, choisi librement (5 — pas d'ordre).
        assertEquals(5, BuildEvent.LogEvent.LogLevel.LIFECYCLE.wireCode());
        assertEquals(BuildEvent.LogEvent.LogLevel.LIFECYCLE,
                BuildEvent.LogEvent.LogLevel.fromWire(5));
        assertThrows(ProtocolException.class,
                () -> BuildEvent.LogEvent.LogLevel.fromWire(99));

        // Sévérité : la grandeur ordonnée du filtrage de console (D-21).
        BuildEvent.LogEvent.LogLevel[] duPlusLégerAuPlusLourd = {
                BuildEvent.LogEvent.LogLevel.DEBUG,
                BuildEvent.LogEvent.LogLevel.INFO,
                BuildEvent.LogEvent.LogLevel.LIFECYCLE,
                BuildEvent.LogEvent.LogLevel.WARN,
                BuildEvent.LogEvent.LogLevel.ERROR,
        };
        for (int i = 1; i < duPlusLégerAuPlusLourd.length; i++) {
            assertTrue(duPlusLégerAuPlusLourd[i - 1].sévérité()
                            < duPlusLégerAuPlusLourd[i].sévérité(),
                    "la sévérité ordonne DEBUG < INFO < LIFECYCLE < WARN < ERROR");
        }
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
