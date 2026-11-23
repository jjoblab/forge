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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests du contrat de tâches (protocoles 5-6, décisions D-22/D-23) : cycle de
 * vie ({@code assemble}/{@code clean}/{@code build}), agrégats de vérification
 * conditionnels ({@code check}/{@code lint}/{@code test}), clôture des
 * dépendances et répertoire — y compris PAR SOUS-ENSEMBLE de modules. Le moteur
 * est la source de vérité de CE QUI PEUT TOURNER, la coquille affiche.
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

    /** Le graphe de référence : app dépend de core (le modèle multi-modules). */
    private static List<Task> grapheDeuxModules() {
        return List.of(
                tâche("core:resolveDependencies"),
                tâche("core:compileJava", "core:resolveDependencies"),
                tâche("core:jar", "core:compileJava"),
                tâche("app:resolveDependencies"),
                tâche("app:compileJava", "app:resolveDependencies", "core:jar"),
                tâche("app:jar", "app:compileJava"));
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
        List<Task> cible = LifecycleTasks.cible(temporaire, grapheDeuxModules(),
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
    void cibleBuildConstruitToutCommeAssemble() {
        // build, l'alias HONNÊTE (D-23) : sans tâche de vérification, sa cible
        // est EXACTEMENT celle d'assemble — et l'agrégat réussit.
        List<Task> cible = LifecycleTasks.cible(temporaire, grapheDeuxModules(),
                List.of("build"));
        List<String> noms = cible.stream().map(Task::name).sorted().toList();
        assertEquals(List.of("app:compileJava", "app:jar", "app:resolveDependencies",
                        "build", "core:compileJava", "core:jar",
                        "core:resolveDependencies"), noms,
                "build = assemble tant qu'aucune vérification n'existe — dit tel quel");
        List<Task> cibleAssemble = LifecycleTasks.cible(temporaire,
                grapheDeuxModules(), List.of("assemble"));
        assertEquals(
                cibleAssemble.stream().map(Task::name).filter(n -> !n.equals("assemble"))
                        .sorted().toList(),
                cible.stream().map(Task::name).filter(n -> !n.equals("build"))
                        .sorted().toList(),
                "les clôtures de build et d'assemble sont identiques aujourd'hui");
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
        assertEquals(7, répertoire.size(),
                "quatre tâches concrètes + assemble + build + clean");
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
        // build (protocole 6) : même dépendances qu'assemble — l'honnêteté de
        // l'alias, la description le DIT.
        TaskDescription build = répertoire.get(5);
        assertEquals("build", build.name());
        assertTrue(build.lifecycle());
        assertEquals(assemble.dependencies(), build.dependencies(),
                "sans vérification, build vaut assemble — et ses dépendances le montrent");
        assertTrue(build.description().contains("assemble"),
                "la description de build dit ce qu'il vaut aujourd'hui : "
                        + build.description());
        TaskDescription clean = répertoire.get(6);
        assertEquals("clean", clean.name());
        assertTrue(clean.lifecycle());
        assertEquals(List.of(), clean.dependencies());
    }

    // ---- Protocole 6 (D-23) : check/lint/test, conditionnels -----------------

    @Test
    void sansVérificationsAucunAliasNEstPromis() {
        // Aucune tâche :lint/:test dans le graphe → ni check, ni lint, ni test
        // dans le répertoire, et la cible les REFUSE avec les candidats.
        List<TaskDescription> répertoire = LifecycleTasks.descripteurs(
                grapheDeuxModules());
        for (TaskDescription descripteur : répertoire) {
            assertFalse(List.of("check", "lint", "test")
                            .contains(descripteur.name()),
                    "aucun alias de vérification sans tâche : " + descripteur.name());
        }
        GraphException refus = assertThrows(GraphException.class,
                () -> LifecycleTasks.cible(temporaire, grapheDeuxModules(),
                        List.of("check")));
        assertTrue(refus.getMessage().contains("check"),
                "le refus cite le nom demandé : " + refus.getMessage());
        assertTrue(refus.getMessage().contains("app:jar"),
                "le refus cite les candidats réels");
    }

    @Test
    void lesVérificationsFontVivreLesAliasDellesMêmes() {
        // Le vocabulaire des suffixes est LE point unique : une tâche app:lint
        // et une tâche core:test suffisent — check, lint et test apparaissent.
        List<Task> avecVérifications = List.of(
                tâche("core:resolveDependencies"),
                tâche("core:compileJava", "core:resolveDependencies"),
                tâche("core:jar", "core:compileJava"),
                tâche("core:test", "core:compileJava"),
                tâche("app:resolveDependencies"),
                tâche("app:compileJava", "app:resolveDependencies", "core:jar"),
                tâche("app:jar", "app:compileJava"),
                tâche("app:lint", "app:compileJava"));
        List<TaskDescription> répertoire = LifecycleTasks.descripteurs(
                avecVérifications);
        TaskDescription check = descripteur(répertoire, "check");
        assertEquals(List.of("app:lint", "core:test"), check.dependencies(),
                "check agrège TOUTES les vérifications");
        assertEquals("vérification", descripteur(répertoire, "app:lint").group(),
                "le vocabulaire place les vérifications dans leur groupe");
        assertEquals(List.of("app:lint"), descripteur(répertoire, "lint").dependencies());
        assertEquals(List.of("core:test"), descripteur(répertoire, "test").dependencies());
        // build a grandi tout seul : terminales + vérifications.
        TaskDescription build = descripteur(répertoire, "build");
        assertEquals(List.of("app:jar", "app:lint", "core:test"), build.dependencies(),
                "build = assemble + les vérifications EXISTANTES — dérivées, pas affirmées");
        assertTrue(build.description().contains("2"),
                "la description compte les vérifications réelles : "
                        + build.description());
        // Et la cible les exécute.
        List<Task> cible = LifecycleTasks.cible(temporaire, avecVérifications,
                List.of("check"));
        List<String> noms = cible.stream().map(Task::name).sorted().toList();
        assertEquals(List.of("app:compileJava", "app:lint", "app:resolveDependencies",
                "check", "core:compileJava", "core:jar", "core:resolveDependencies",
                "core:test"), noms,
                "check tire la clôture des vérifications — jamais les sorties");
    }

    // ---- Protocole 6 (D-23) : le répertoire par sous-ensembles --------------

    @Test
    void répertoireDUneModuleNAgrégatsQueDeCeModule() {
        // gradlew :core:tasks — la vue de core : ses concrets + core:assemble +
        // core:clean ; JAMAIS l'assemble global redéfini en silence.
        List<TaskDescription> répertoire = LifecycleTasks.descripteurs(
                grapheDeuxModules(), List.of("core"));
        List<String> noms = répertoire.stream().map(TaskDescription::name).toList();
        assertEquals(List.of("core:jar", "core:compileJava", "core:resolveDependencies",
                "core:assemble", "core:clean"), noms,
                "la vue sous-ensemble : TOUS les concrets du module + SES agrégats "
                        + "(comme gradlew :core:tasks, qui liste :core:jar)");
        TaskDescription assembleCore = descripteur(répertoire, "core:assemble");
        assertEquals(List.of("core:jar"), assembleCore.dependencies(),
                "core:assemble agrège les sorties de core SEULEMENT");
        assertTrue(assembleCore.lifecycle());
        TaskDescription cleanCore = descripteur(répertoire, "core:clean");
        assertTrue(cleanCore.description().contains("build/core"),
                "le clean de module dit son périmètre : " + cleanCore.description());
    }

    @Test
    void répertoireDunSousEnsembleMultiModules() {
        List<TaskDescription> répertoire = LifecycleTasks.descripteurs(
                grapheDeuxModules(), List.of("app", "core"));
        List<String> noms = répertoire.stream().map(TaskDescription::name).toList();
        assertEquals(List.of("app:jar", "core:jar", "app:compileJava",
                "core:compileJava", "app:resolveDependencies", "core:resolveDependencies",
                "app:assemble", "app:clean", "core:assemble", "core:clean"), noms,
                "deux modules demandés : concrets des deux + agrégats de chacun");
        assertEquals(List.of("app:jar"), descripteur(répertoire, "app:assemble")
                .dependencies());
    }

    @Test
    void moduleInconnuDansLeRépertoireRefuséAvecCandidats() {
        GraphException échec = assertThrows(GraphException.class,
                () -> LifecycleTasks.descripteurs(grapheDeuxModules(),
                        List.of("zork")));
        assertTrue(échec.getMessage().contains("zork"),
                "le module fautif est cité : " + échec.getMessage());
        assertTrue(échec.getMessage().contains("core"),
                "les modules disponibles sont proposés (le refus de Gradle)");
    }

    @Test
    void cibleModuleAssembleNeConstruitQueCeModule() {
        // gradlew :core:assemble — le nom EST le périmètre : la clôture tire
        // core complet mais JAMAIS app.
        List<Task> cible = LifecycleTasks.cible(temporaire, grapheDeuxModules(),
                List.of("core:assemble"));
        List<String> noms = cible.stream().map(Task::name).sorted().toList();
        assertEquals(List.of("core:assemble", "core:compileJava", "core:jar",
                "core:resolveDependencies"), noms,
                "core:assemble construit les sorties de core, rien d'autre");
    }

    @Test
    void cibleModuleCleanNeNettoieQueCeModule() throws Exception {
        Path buildApp = temporaire.resolve("build").resolve("app");
        Path buildCore = temporaire.resolve("build").resolve("core");
        Files.createDirectories(buildApp);
        Files.createDirectories(buildCore);

        List<Task> cible = LifecycleTasks.cible(temporaire, grapheDeuxModules(),
                List.of("app:clean"));
        assertEquals(List.of("app:clean"),
                cible.stream().map(Task::name).toList(),
                "app:clean demandé seul : le nettoyage du module, RIEN d'autre");
        for (Task tâche : cible) {
            tâche.execute(contexteMuet(temporaire));
        }
        assertFalse(Files.exists(buildApp), "les sorties de app disparaissent");
        assertTrue(Files.exists(buildCore),
                "les sorties de core restent — le périmètre est tenu");
    }

    @Test
    void cibleModuleInconnuRefuséeAvecCandidats() {
        GraphException échec = assertThrows(GraphException.class,
                () -> LifecycleTasks.cible(temporaire, grapheDeuxModules(),
                        List.of("zork:assemble")));
        assertTrue(échec.getMessage().contains("zork"),
                "le module fautif est cité : " + échec.getMessage());
        assertTrue(échec.getMessage().contains("modules disponibles"),
                "le refus propose les modules réels : " + échec.getMessage());
    }

    /** Le descripteur du répertoire par son nom — échoue s'il manque. */
    private static TaskDescription descripteur(List<TaskDescription> répertoire,
                                               String nom) {
        return répertoire.stream().filter(d -> d.name().equals(nom))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "descripteur absent : " + nom + " dans " + répertoire));
    }

    /** Garde-fou de lisibilité : le nom d'agrégat ne confond jamais les noms. */
    @Test
    void moduleDAgrégatNeConfondPasLesNoms() {
        // "assemble" est global ; "core:assemble" est de module ; "app:jar"
        // n'est un agrégat d'aucune espèce.
        assertNull(LifecycleTasks.moduleDAgrégat("assemble", "assemble"));
        assertEquals("core", LifecycleTasks.moduleDAgrégat("core:assemble", "assemble"));
        assertNull(LifecycleTasks.moduleDAgrégat("app:jar", "assemble"));
        assertEquals("core", LifecycleTasks.moduleDAgrégat("core:clean", "clean"));
        assertNull(LifecycleTasks.moduleDAgrégat("clean", "clean"));
    }
}
