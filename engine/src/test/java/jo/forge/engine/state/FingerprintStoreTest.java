package jo.forge.engine.state;

import jo.forge.engine.exec.TaskExecutor;
import jo.forge.engine.exec.UpToDateOracle;
import jo.forge.engine.graph.TaskGraph;
import jo.forge.engine.tasks.CancellationFlag;
import jo.forge.engine.tasks.Task;
import jo.forge.engine.tasks.TaskIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests des empreintes et du magasin persistant : incrémentalité (toucher un fichier
 * ne relance que les tâches concernées), corruption traitée comme périmée (jamais un
 * faux up-to-date), sorties supprimées, écritures concurrentes.
 */
class FingerprintStoreTest {

    @TempDir
    Path tempDir;

    /** Avertissements capturés. */
    private static final class Avertissements implements FingerprintStore.WarningListener {

        final List<String> messages = new CopyOnWriteArrayList<>();

        @Override
        public void warn(String message) {
            messages.add(message);
        }
    }

    /** Tâche à entrée fichier unique et compteur d'exécutions. */
    private static class TacheFichier implements Task {

        private final String nom;
        private final Path entree;
        private final Path sortie;
        private final List<String> dependances;
        final AtomicIntegerLegacy executions = new AtomicIntegerLegacy();

        TacheFichier(String nom, Path entree, Path sortie, List<String> dependances) {
            this.nom = nom;
            this.entree = entree;
            this.sortie = sortie;
            this.dependances = dependances;
        }

        @Override
        public String name() {
            return nom;
        }

        @Override
        public List<String> dependencies() {
            return dependances;
        }

        @Override
        public TaskIO io() {
            return new TaskIO(List.of(entree), List.of(), java.util.Map.of(),
                    List.of(sortie), "v1");
        }

        @Override
        public void execute(jo.forge.engine.tasks.TaskContext context) throws Exception {
            executions.increment();
            Files.createDirectories(sortie.getParent());
            Files.writeString(sortie, "sortie de " + nom);
        }
    }

    /** Compteur minimal (nom distinct de java.util.concurrent.AtomicInteger). */
    static final class AtomicIntegerLegacy {

        private int valeur;

        synchronized void increment() {
            valeur++;
        }

        synchronized int get() {
            return valeur;
        }
    }

    private Path fichier(String nom, String contenu) throws Exception {
        Path chemin = tempDir.resolve(nom);
        Files.createDirectories(chemin.getParent());
        Files.writeString(chemin, contenu);
        return chemin;
    }

    /**
     * Exécute un build et retourne le journal avec les états de tâches.
     */
    private Build executer(List<Task> taches, Path etatDir, Avertissements avertissements) {
        List<jo.forge.api.BuildEvent.TaskStateEvent> etats =
                new CopyOnWriteArrayList<>();
        List<String> echecs = new CopyOnWriteArrayList<>();
        TaskExecutor.BuildEvents evenements = new TaskExecutor.BuildEvents() {
            @Override
            public String buildId() {
                return "test";
            }

            @Override
            public void taskState(jo.forge.api.BuildEvent.TaskStateEvent event) {
                etats.add(event);
            }

            @Override
            public void log(jo.forge.api.BuildEvent.LogEvent.LogLevel level,
                            String message) {
            }

            @Override
            public void progress(int total, int done) {
            }

            @Override
            public void buildFinished(jo.forge.api.BuildEvent.BuildFinished fin) {
            }

            @Override
            public String dernierEchec() {
                return echecs.isEmpty() ? null : echecs.get(0);
            }

            @Override
            public void tacheEchouee(String taskName) {
                echecs.add(taskName);
            }
        };
        FingerprintStore magasin = new FingerprintStore(etatDir, tempDir, avertissements);
        TaskExecutor.BuildResult resultat = new TaskExecutor(TaskGraph.of(taches),
                evenements, new CancellationFlag(), magasin, 2).execute();
        return new Build(resultat, etats);
    }

    private record Build(TaskExecutor.BuildResult resultat,
                         List<jo.forge.api.BuildEvent.TaskStateEvent> etats) {

        jo.forge.api.BuildEvent.TaskState etatFinal(String tache) {
            return etats.stream()
                    .filter(e -> e.taskName().equals(tache))
                    .reduce((a, b) -> b).orElseThrow().state();
        }
    }

    @Test
    @Timeout(30)
    void toucherUnFichierNeRelanceQueLesTachesConcernees() throws Exception {
        Path etat = tempDir.resolve("etat");
        Path sourceA = fichier("src/A.txt", "contenu A");
        Path sourceB = fichier("src/B.txt", "contenu B");
        TacheFichier tacheA = new TacheFichier("a", sourceA, tempDir.resolve("out/a"),
                List.of());
        TacheFichier tacheB = new TacheFichier("b", sourceB, tempDir.resolve("out/b"),
                List.of());

        // Premier build : tout s'exécute.
        Build premier = executer(List.of(tacheA, tacheB), etat, new Avertissements());
        assertTrue(premier.resultat().success());
        assertEquals(1, tacheA.executions.get());
        assertEquals(1, tacheB.executions.get());

        // Second build sans changement : tout est à jour.
        executer(List.of(tacheA, tacheB), etat, new Avertissements());
        assertEquals(1, tacheA.executions.get(), "a doit rester à jour");
        assertEquals(1, tacheB.executions.get(), "b doit rester à jour");

        // Modification du seul fichier de b : seule b est relancée.
        Files.writeString(sourceB, "contenu B modifié");
        Build troisieme = executer(List.of(tacheA, tacheB), etat, new Avertissements());
        assertEquals(1, tacheA.executions.get(), "a ne doit PAS être relancée");
        assertEquals(2, tacheB.executions.get(), "b doit être relancée");
        assertEquals(jo.forge.api.BuildEvent.TaskState.UP_TO_DATE,
                troisieme.etatFinal("a"));
        assertEquals(jo.forge.api.BuildEvent.TaskState.SUCCEEDED,
                troisieme.etatFinal("b"));
    }

    @Test
    @Timeout(30)
    void sortieSupprimeeInvalideLEtat() throws Exception {
        Path etat = tempDir.resolve("etat");
        Path source = fichier("src/S.txt", "contenu");
        TacheFichier tache = new TacheFichier("s", source, tempDir.resolve("out/s"),
                List.of());
        executer(List.of(tache), etat, new Avertissements());
        executer(List.of(tache), etat, new Avertissements());
        assertEquals(1, tache.executions.get());

        // Sortie supprimée : la tâche doit se ré-exécuter (sorties revérifiées).
        Files.delete(tempDir.resolve("out/s"));
        Avertissements avertissements = new Avertissements();
        executer(List.of(tache), etat, avertissements);
        assertEquals(2, tache.executions.get(),
                "une sortie supprimée doit invalider l'état");
        assertTrue(avertissements.messages.stream()
                        .anyMatch(m -> m.contains("absentes")),
                "un avertissement doit l'expliquer");
    }

    @Test
    @Timeout(30)
    void enregistrementCorrompuTraiteCommePerime() throws Exception {
        Path etat = tempDir.resolve("etat");
        Path source = fichier("src/C.txt", "contenu");
        TacheFichier tache = new TacheFichier("c", source, tempDir.resolve("out/c"),
                List.of());
        executer(List.of(tache), etat, new Avertissements());

        // Sabotage du magasin : octets aléatoires dans l'enregistrement de la tâche.
        Path enregistrement = etat.resolve("tasks")
                .resolve(hexNom("c") + ".state");
        assertTrue(Files.exists(enregistrement), "l'enregistrement doit exister");
        Files.write(enregistrement, new byte[]{1, 2, 3, 4, 5});

        Avertissements avertissements = new Avertissements();
        executer(List.of(tache), etat, avertissements);
        assertEquals(2, tache.executions.get(),
                "un enregistrement corrompu doit provoquer la ré-exécution");
        assertTrue(avertissements.messages.stream().anyMatch(m -> m.contains("corrompu")
                        || m.contains("illisible")),
                "la corruption doit être signalée : " + avertissements.messages);
    }

    @Test
    @Timeout(30)
    void versionDeTacheChangeeInvalideLEtat() throws Exception {
        Path etat = tempDir.resolve("etat");
        Path source = fichier("src/V.txt", "contenu");
        TacheFichier tacheV1 = new TacheFichier("v", source,
                tempDir.resolve("out/v"), List.of());
        executer(List.of(tacheV1), etat, new Avertissements());

        // Même tâche, version de code différente (implémentation changée).
        TacheFichier tacheV2 = new TacheFichier("v", source,
                tempDir.resolve("out/v"), List.of()) {
            @Override
            public TaskIO io() {
                return new TaskIO(List.of(source), List.of(), java.util.Map.of(),
                        List.of(tempDir.resolve("out/v")), "v2");
            }
        };
        Build second = executer(List.of(tacheV2), etat, new Avertissements());
        assertEquals(1, tacheV2.executions.get(),
                "la tâche version 2 doit s'exécuter (instance neuve, un passage)");
        assertEquals(jo.forge.api.BuildEvent.TaskState.SUCCEEDED, second.etatFinal("v"),
                "l'état enregistré avec v1 ne vaut pas pour v2");
    }

    @Test
    @Timeout(60)
    void ecrituresConcurrentesSansCorruption() throws Exception {
        Path etat = tempDir.resolve("etat");
        List<Task> taches = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Path source = fichier("src/cc" + i + ".txt", "contenu " + i);
            taches.add(new TacheFichier("cc" + i, source,
                    tempDir.resolve("out/cc" + i), List.of()));
        }
        Build build = executer(taches, etat, new Avertissements());
        assertTrue(build.resultat().success());

        // Second passage : toutes à jour — les écritures concurrentes du premier
        // passage n'ont laissé aucun enregistrement corrompu.
        Build second = executer(taches, etat, new Avertissements());
        for (int i = 0; i < 6; i++) {
            assertEquals(jo.forge.api.BuildEvent.TaskState.UP_TO_DATE,
                    second.etatFinal("cc" + i),
                    "l'enregistrement de cc" + i + " doit être sain");
        }
    }

    @Test
    @Timeout(30)
    void répertoireDéclaréCommeFichierResteÀJour() throws Exception {
        Path etat = tempDir.resolve("etat");
        // Un répertoire d'entrée déclaré dans les FICHIERS (le piège exact du
        // rapport appareil v0.2.22 : « Entrée non régulière » sur le répertoire
        // de dex de packageApk) doit être haché RÉCURSIVEMENT, pas refusé.
        Path répertoire = tempDir.resolve("dex");
        Files.createDirectories(répertoire.resolve("paquet"));
        Files.write(répertoire.resolve("paquet/classes.dex"), new byte[]{1, 2, 3});
        TacheFichier tache = new TacheFichier("d", répertoire,
                tempDir.resolve("out/d"), List.of());

        Avertissements avertissements = new Avertissements();
        executer(List.of(tache), etat, avertissements);
        assertTrue(avertissements.messages.stream()
                        .noneMatch(m -> m.contains("non régulière")),
                "aucun refus d'entrée : " + avertissements.messages);

        // Second passage sans changement : À JOUR (le statut n'est plus perdu).
        Build second = executer(List.of(tache), etat, new Avertissements());
        assertEquals(1, tache.executions.get(), "le répertoire haché vaut un fichier");
        assertEquals(jo.forge.api.BuildEvent.TaskState.UP_TO_DATE,
                second.etatFinal("d"));

        // Le CONTENU du répertoire change : l'empreinte change, la tâche rejoue.
        Files.write(répertoire.resolve("paquet/classes2.dex"), new byte[]{4, 5, 6});
        Build troisieme = executer(List.of(tache), etat, new Avertissements());
        assertEquals(2, tache.executions.get(),
                "un contenu modifié dans le répertoire doit invalider l'état");
        assertEquals(jo.forge.api.BuildEvent.TaskState.SUCCEEDED,
                troisieme.etatFinal("d"));
    }

    /** Encodage injectif du nom (même règle que le magasin). */
    private static String hexNom(String nom) {
        StringBuilder hex = new StringBuilder();
        for (byte octet : nom.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            hex.append(String.format("%02x", octet));
        }
        return hex.toString();
    }
}
