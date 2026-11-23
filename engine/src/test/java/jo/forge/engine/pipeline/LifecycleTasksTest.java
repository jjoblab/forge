package jo.forge.engine.pipeline;

import jo.forge.api.TaskDescription;
import jo.forge.engine.graph.GraphException;
import jo.forge.engine.tasks.Task;
import jo.forge.engine.tasks.TaskContext;
import jo.forge.engine.tasks.TaskLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests du contrat de tâches (protocole 5, décision D-22) : cycle de vie
 * ({@code assemble}/{@code clean}), clôture des dépendances et répertoire —
 * le moteur est la source de vérité de CE QUI PEUT TOURNER, la coquille affiche.
 */
class LifecycleTasksTest {

    @TempDir
    Path temporaire;

    /** Une tâche factice, purement nominale. */
    private static Task tâche(String nom, String... dépendances) {
        return new Task() {
            @Override public String name() { return nom; }
            @Override public List<String> dependencies() { return List.of(dépendances); }
            @Override public void execute(TaskContext context) { }
        };
    }

    /** Un contexte minimal, muet : les tâches de cycle de vie ne font que progresser. */
    private static TaskContext contexteMuet(Path racine) {
        return new TaskContext() {
            @Override public Path projectRoot() { return racine; }
            @Override public jo.forge.engine.tasks.CancellationFlag cancellation() {
                return new jo.forge.engine.tasks.CancellationFlag();
            }
            @Override public TaskLogger logger() {
                return new TaskLogger() {
                    @Override public void debug(String message) { }
                    @Override public void info(String message) { }
                    @Override public void warn(String message) { }
                    @Override public void error(String message) { }
                };
            }
        };
    }

    @Test
    void terminalesDuGrapheConcret() {
        // app:jar est la SEULE terminale : core:jar est une dépendance d'app,
        // compileJava/resolveDependencies nourrissent leur jar.
        List<String> terminales = LifecycleTasks.tâchesTerminales(List.of(
                tâche("core:resolveDependencies"),
                tâche("core:compileJava", "core:resolveDependencies"),
                tâche("core:jar", "core:compileJava"),
                tâche("app:resolveDependencies"),
                tâche("app:compileJava", "app:resolveDependencies", "core:jar"),
                tâche("app:jar", "app:compileJava")));
        assertEquals(List.of("app:jar"), terminales,
                "assemble dépend des sorties finales, pas des étapes intermédiaires");
    }

    @Test
    void assembleDépendDesTerminalesEtNeFaitRien() throws Exception {
        Task assemble = LifecycleTasks.tâcheAssemble(List.of(
                tâche("app:resolveDependencies"),
                tâche("app:compileJava", "app:resolveDependencies"),
                tâche("app:jar", "app:compileJava")));
        assertEquals("assemble", assemble.name());
        assertEquals(List.of("app:jar"), assemble.dependencies(),
                "le travail d'un agrégat EST sa liste de dépendances");
        assemble.execute(contexteMuet(temporaire)); // ne fait rien, ne lève rien.
    }

    @Test
    void cleanSupprimeLeBuildJamaisLesSources() throws Exception {
        Path build = temporaire.resolve("build").resolve("app").resolve("classes");
        Files.createDirectories(build);
        Files.writeString(build.resolve("App.class"), "binaire");
        Path source = temporaire.resolve("app").resolve("src").resolve("App.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "source");

        LifecycleTasks.tâcheClean(temporaire).execute(contexteMuet(temporaire));

        assertFalse(Files.exists(temporaire.resolve("build")),
                "le répertoire build/ doit disparaître");
        assertTrue(Files.exists(source), "les sources ne sont JAMAIS touchées");
    }

    @Test
    void cleanSansSortiesResteSage() throws Exception {
        // Rien à nettoyer : pas d'échec, pas de création — un clean honnête.
        LifecycleTasks.tâcheClean(temporaire).execute(contexteMuet(temporaire));
        assertFalse(Files.exists(temporaire.resolve("build")));
    }

    @Test
    void cibleVideGardeToutLeGraphe() {
        List<Task> concrètes = List.of(tâche("app:compileJava"),
                tâche("app:jar", "app:compileJava"));
        assertEquals(concrètes, LifecycleTasks.cible(temporaire, concrètes, List.of()),
                "sans demande, tout le graphe — la forme historique");
        assertEquals(concrètes, LifecycleTasks.cible(temporaire, concrètes, null),
                "null est la liste vide, pas une erreur");
    }

    @Test
    void clôtureSélectionneEtTireLesDépendances() {
        List<Task> cible = LifecycleTasks.cible(temporaire, List.of(
                tâche("core:resolveDependencies"),
                tâche("core:compileJava", "core:resolveDependencies"),
                tâche("core:jar", "core:compileJava"),
                tâche("app:resolveDependencies"),
                tâche("app:compileJava", "app:resolveDependencies", "core:jar"),
                tâche("app:jar", "app:compileJava")),
                List.of("core:compileJava"));
        List<String> noms = cible.stream().map(Task::name).sorted().toList();
        assertEquals(List.of("core:compileJava", "core:resolveDependencies"), noms,
                "demander compileJava tire resolveDependencies — jamais le jar, "
                        + "jamais app");
    }

    @Test
    void cibleAssembleInjecteLAgrégatEtRetrouveTout() {
        List<Task> cible = LifecycleTasks.cible(temporaire, List.of(
                tâche("app:resolveDependencies"),
                tâche("app:compileJava", "app:resolveDependencies"),
                tâche("app:jar", "app:compileJava")),
                List.of("assemble"));
        List<String> noms = cible.stream().map(Task::name).sorted().toList();
        assertEquals(List.of("app:compileJava", "app:jar", "app:resolveDependencies",
                "assemble"), noms,
                "assemble = la clôture de toutes les sorties + l'agrégat lui-même");
    }

    @Test
    void cibleCleanNettoieSansConstruire() {
        List<Task> cible = LifecycleTasks.cible(temporaire, List.of(
                tâche("app:compileJava"),
                tâche("app:jar", "app:compileJava")),
                List.of("clean"));
        assertEquals(List.of("clean"),
                cible.stream().map(Task::name).toList(),
                "clean demandé seul : le nettoyage, RIEN d'autre");
    }

    @Test
    void nomInconnuÉchoueAvecLesCandidats() {
        GraphException échec = assertThrows(GraphException.class,
                () -> LifecycleTasks.cible(temporaire, List.of(
                        tâche("app:compileJava"), tâche("app:jar", "app:compileJava")),
                        List.of("app:dex")));
        assertTrue(échec.getMessage().contains("app:dex"),
                "le nom fautif est cité : " + échec.getMessage());
        assertTrue(échec.getMessage().contains("app:compileJava"),
                "les candidats disponibles sont proposés (le refus de Gradle)");
    }

    @Test
    void répertoireConcretPuisCycleDeVie() {
        List<TaskDescription> répertoire = LifecycleTasks.descripteurs(List.of(
                tâche("core:resolveDependencies"),
                tâche("core:compileJava", "core:resolveDependencies"),
                tâche("app:compileJava"),
                tâche("app:jar", "app:compileJava")));
        assertEquals(6, répertoire.size(),
                "quatre tâches concrètes + assemble + clean");
        // Ordre déterministe : concrets triés par GROUPE puis nom, cycle de vie en fin.
        assertEquals("app:jar", répertoire.get(0).name());
        assertEquals("app:compileJava", répertoire.get(1).name());
        assertEquals("core:compileJava", répertoire.get(2).name());
        assertEquals("core:resolveDependencies", répertoire.get(3).name());
        assertFalse(répertoire.get(1).lifecycle());
        assertEquals("assemblage", répertoire.get(0).group());
        assertEquals("compilation", répertoire.get(1).group());
        assertEquals("dépendances", répertoire.get(3).group());
        // Cycle de vie : marqué, groupé, décrit honnêtement.
        TaskDescription assemble = répertoire.get(4);
        assertEquals("assemble", assemble.name());
        assertTrue(assemble.lifecycle());
        assertEquals("cycle de vie", assemble.group());
        assertEquals(List.of("app:jar", "core:compileJava"), assemble.dependencies(),
                "les terminales de CE graphe : app:jar et core:compileJava "
                        + "(rien n'y dépend)");
        TaskDescription clean = répertoire.get(5);
        assertEquals("clean", clean.name());
        assertTrue(clean.lifecycle());
        assertEquals(List.of(), clean.dependencies());
    }
}
