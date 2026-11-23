package jo.forge.engine.server;

import jo.forge.api.BuildEvent;
import jo.forge.api.BuildOptions;
import jo.forge.api.BuildRequest;
import jo.forge.api.BuildStarted;
import jo.forge.api.ClientHello;
import jo.forge.api.EngineState;
import jo.forge.api.Frame;
import jo.forge.api.FrameReader;
import jo.forge.api.FrameType;
import jo.forge.api.FrameWriter;
import jo.forge.api.Hello;
import jo.forge.api.MessageIO;
import jo.forge.api.Status;
import jo.forge.api.TaskDescription;
import jo.forge.api.TasksRequest;
import jo.forge.api.TasksResponse;
import jo.forge.engine.testutil.Projets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests du vrai serveur de build sur de vrais tuyaux : le protocole complet
 * (poignée de main, build, annulation, état, arrêt) avec le pipeline réel derrière —
 * sans processus séparé, mais par le même canal que sur appareil.
 */
class BuildEngineServerTest {

    @TempDir
    Path temporaire;

    @Test
    @Timeout(60)
    void buildCompletPuisIncrémentalSurLeCanal() throws Exception {
        Projets.projetDeuxModules(temporaire);
        try (Session session = Session.démarrer()) {
            session.poignéeDeMain();

            List<BuildEvent> événements = session.build(temporaire);
            BuildEvent.BuildFinished fin = dernier(événements, BuildEvent.BuildFinished.class);
            assertTrue(fin.success(), "le build réel doit réussir : " + fin.message());
            assertTrue(événements.stream().anyMatch(e ->
                            e instanceof BuildEvent.TaskStateEvent t
                                    && t.state() == BuildEvent.TaskState.SUCCEEDED
                                    && t.taskName().equals("core:compileJava")),
                    "la compilation de core doit réussir");
            // Trace exportable (A-13) : écrite à la racine du projet, atomique.
            assertTrue(Files.isRegularFile(temporaire.resolve(".forge/traces/b2.json")),
                    "la trace du premier build doit exister");
            assertTrue(Files.readString(temporaire.resolve(".forge/traces/b2.json"))
                            .contains("core:compileJava"),
                    "la trace contient les tâches");

            // Second build sans changement : tout à jour, toujours par le canal.
            List<BuildEvent> second = session.build(temporaire);
            BuildEvent.BuildFinished fin2 = dernier(second, BuildEvent.BuildFinished.class);
            assertTrue(fin2.success());
            assertTrue(second.stream().filter(e ->
                            e instanceof BuildEvent.TaskStateEvent t
                                    && t.state() == BuildEvent.TaskState.UP_TO_DATE)
                            .count() >= 4,
                    "les quatre tâches doivent être à jour");

            // Modifier une source : seule sa branche relance.
            Projets.fichier(temporaire, "app/src/main/java/demo/app/App.java", """
                    package demo.app;

                    import demo.core.Textes;

                    public final class App {
                        private App() { }

                        public static String bienvenue() {
                            return Textes.joindre(" ", "Encore", "bonjour !");
                        }
                    }
                    """);
            List<BuildEvent> troisième = session.build(temporaire);
            assertTrue(dernier(troisième, BuildEvent.BuildFinished.class).success());
            assertTrue(troisième.stream().noneMatch(e ->
                            e instanceof BuildEvent.TaskStateEvent t
                                    && t.state() == BuildEvent.TaskState.STARTED
                                    && t.taskName().startsWith("core:")),
                    "le module amont ne doit pas se relancer");

            // État puis arrêt propre.
            Status état = session.status();
            assertEquals(EngineState.IDLE, état.state());
            assertTrue(état.capabilities().contains("build"));
            session.arrêt();
        }
    }

    @Test
    @Timeout(60)
    void projetSansModèleÉchecPropreSansCasserLeCanal() throws Exception {
        Path vide = temporaire.resolve("projet-vide");
        java.nio.file.Files.createDirectories(vide);
        try (Session session = Session.démarrer()) {
            session.poignéeDeMain();
            List<BuildEvent> événements = session.build(vide);
            BuildEvent.BuildFinished fin = dernier(événements,
                    BuildEvent.BuildFinished.class);
            assertTrue(!fin.success(), "le build doit échouer proprement");
            assertTrue(fin.message().contains("forge.json"),
                    "le message doit citer le fichier manquant : " + fin.message());
            // Le canal survit : un second build sur un vrai projet fonctionne.
            Path projet = temporaire.resolve("projet-ok");
            Projets.projetDeuxModules(projet);
            BuildEvent.BuildFinished fin2 = dernier(session.build(projet),
                    BuildEvent.BuildFinished.class);
            assertTrue(fin2.success(), "le moteur doit rester utilisable après l'échec");
            session.arrêt();
        }
    }

    @Test
    @Timeout(60)
    void diagnosticDeCompilationTraverseLeCanal() throws Exception {
        Projets.projetDeuxModules(temporaire);
        Projets.fichier(temporaire, "core/src/main/java/demo/core/Fautif.java", """
                package demo.core;

                public class Fautif {
                    int calcul() { return inconnu; }
                }
                """);
        try (Session session = Session.démarrer()) {
            session.poignéeDeMain();
            List<BuildEvent> événements = session.build(temporaire);
            BuildEvent.BuildFinished fin = dernier(événements,
                    BuildEvent.BuildFinished.class);
            assertTrue(!fin.success());
            assertEquals("core:compileJava", fin.failedTask());
            BuildEvent.DiagnosticEvent diagnostic = événements.stream()
                    .filter(e -> e instanceof BuildEvent.DiagnosticEvent)
                    .map(e -> (BuildEvent.DiagnosticEvent) e)
                    .filter(d -> d.file().endsWith("Fautif.java"))
                    .findFirst().orElseThrow();
            assertEquals(4, diagnostic.line(), "l'erreur est ligne 4 (return fautif)");
            assertTrue(diagnostic.column() > 0);
            assertEquals("java-compiler", diagnostic.tool());
            session.arrêt();
        }
    }

    @Test
    @Timeout(60)
    void syncNeCompileJamaisEtAnnonceLaCapacité() throws Exception {
        Projets.projetDeuxModules(temporaire);
        try (Session session = Session.démarrer()) {
            session.poignéeDeMain(2);
            Status état = session.status();
            assertTrue(état.capabilities().contains("sync"),
                    "le moteur annonce la capacité sync : " + état.capabilities());

            List<BuildEvent> événements = session.sync(temporaire);
            BuildEvent.BuildFinished fin = dernier(événements,
                    BuildEvent.BuildFinished.class);
            assertTrue(fin.success(), "le sync doit réussir : " + fin.message());

            List<String> tâchesExécutées = événements.stream()
                    .filter(e -> e instanceof BuildEvent.TaskStateEvent t
                            && t.state() == BuildEvent.TaskState.SUCCEEDED)
                    .map(e -> ((BuildEvent.TaskStateEvent) e).taskName())
                    .toList();
            assertEquals(List.of("app:resolveDependencies", "core:resolveDependencies")
                    .stream().sorted().toList(),
                    tâchesExécutées.stream().sorted().toList(),
                    "un sync n'exécute QUE la résolution des dépendances");
            assertTrue(événements.stream().noneMatch(e ->
                            e instanceof BuildEvent.TaskStateEvent t
                                    && t.taskName().contains("compile")),
                    "aucune tâche de compilation dans un sync");
            assertTrue(événements.stream().anyMatch(e ->
                            e instanceof BuildEvent.LogEvent l && l.message()
                                    .startsWith("sync")),
                    "le journal annonce le sync");

            // Un build complet APRÈS un sync : la résolution est à jour, la
            // compilation reste à faire — les deux mondes coexistent.
            List<BuildEvent> build = session.build(temporaire);
            assertTrue(dernier(build, BuildEvent.BuildFinished.class).success());
            assertTrue(build.stream().anyMatch(e ->
                            e instanceof BuildEvent.TaskStateEvent t
                                    && t.state() == BuildEvent.TaskState.UP_TO_DATE
                                    && t.taskName().endsWith(":resolveDependencies")),
                    "le build voit la résolution faite par le sync comme à jour");
            session.arrêt();
        }
    }

    @Test
    @Timeout(60)
    void laConsoleDuMoteurComposeTâchesEtVerdict() throws Exception {
        // Protocole 4 (D-21) : les lignes de console partent COMPOSÉES par le moteur —
        // une ligne de journal par tâche à son issue, un verdict final décompté, et
        // RIEN de plus au niveau par défaut (l'annonce « build de … » en INFO est
        // triée côté moteur, comme un -q de Gradle tait le superflu).
        Projets.projetDeuxModules(temporaire);
        try (Session session = Session.démarrer()) {
            session.poignéeDeMain(4);

            List<BuildEvent> événements = session.build(temporaire,
                    BuildEvent.LogEvent.LogLevel.LIFECYCLE);
            assertTrue(dernier(événements, BuildEvent.BuildFinished.class).success());

            List<String> lignesConsole = événements.stream()
                    .filter(e -> e instanceof BuildEvent.LogEvent l
                            && l.level() == BuildEvent.LogEvent.LogLevel.LIFECYCLE)
                    .map(e -> ((BuildEvent.LogEvent) e).message())
                    .toList();
            long tâchesÀLIssue = événements.stream()
                    .filter(e -> e instanceof BuildEvent.TaskStateEvent t
                            && t.state() != BuildEvent.TaskState.STARTED)
                    .count();
            assertEquals(tâchesÀLIssue,
                    lignesConsole.stream().filter(l -> l.startsWith("> Tâche ")).count(),
                    "une ligne de console par tâche à son issue");
            assertTrue(lignesConsole.stream().anyMatch(l -> l.startsWith("BUILD RÉUSSI en")),
                    "le verdict clôt la console : " + lignesConsole);
            assertTrue(événements.stream().noneMatch(e -> e instanceof BuildEvent.LogEvent l
                            && l.message().startsWith("build de ")),
                    "au niveau par défaut, l'annonce INFO est triée côté moteur");
            // Le verdict est la DERNIÈRE ligne : rien ne suit le BuildFinished.
            assertEquals(BuildEvent.BuildFinished.class,
                    événements.get(événements.size() - 1).getClass());
            session.arrêt();
        }
    }

    @Test
    @Timeout(60)
    void niveauInfoDuMoteurRésoutLesLignesInformations() throws Exception {
        Projets.projetDeuxModules(temporaire);
        try (Session session = Session.démarrer()) {
            session.poignéeDeMain(4);
            List<BuildEvent> événements = session.build(temporaire,
                    BuildEvent.LogEvent.LogLevel.INFO);
            assertTrue(dernier(événements, BuildEvent.BuildFinished.class).success());
            assertTrue(événements.stream().anyMatch(e -> e instanceof BuildEvent.LogEvent l
                            && l.message().startsWith("build de ")),
                    "au niveau INFO, l'annonce de build revient (l'équivalent du -i)");
            session.arrêt();
        }
    }

    @Test
    @Timeout(60)
    void niveauErreurTaitLaConsoleMaisPasLaStructure() throws Exception {
        // Console silencieuse (l'équivalent du -q de Gradle) : plus AUCUNE ligne de
        // tâche ni de verdict — mais le flux STRUCTURÉ reste entier (états de tâches,
        // progression, fin) : c'est lui qui nourrit barre et verdict d'interface.
        Projets.projetDeuxModules(temporaire);
        try (Session session = Session.démarrer()) {
            session.poignéeDeMain(4);
            List<BuildEvent> événements = session.build(temporaire,
                    BuildEvent.LogEvent.LogLevel.ERROR);
            assertTrue(dernier(événements, BuildEvent.BuildFinished.class).success());
            assertTrue(événements.stream().anyMatch(e ->
                            e instanceof BuildEvent.TaskStateEvent t
                                    && t.state() == BuildEvent.TaskState.SUCCEEDED),
                    "les états de tâches restent émis (flux structuré)");
            assertTrue(événements.stream().noneMatch(e -> e instanceof BuildEvent.LogEvent),
                    "aucune ligne de journal au niveau ERROR sur un build réussi — reçues : "
                            + événements.stream()
                            .filter(e -> e instanceof BuildEvent.LogEvent)
                            .map(e -> e.toString())
                            .toList());
            session.arrêt();
        }
    }

    @Test
    @Timeout(60)
    void coquilleDAvantLeProtocole4NeReçoitAucuneLigneComposée() throws Exception {
        // Une coquille qui négocie la version 3 compose ENCORE ses lignes depuis les
        // événements structurés : le moteur ne doit RIEN lui envoyer de composé —
        // sinon sa console afficherait chaque tâche deux fois.
        Projets.projetDeuxModules(temporaire);
        try (Session session = Session.démarrer()) {
            session.poignéeDeMain(3);
            List<BuildEvent> événements = session.build(temporaire);
            assertTrue(dernier(événements, BuildEvent.BuildFinished.class).success());
            assertTrue(événements.stream().noneMatch(e -> e instanceof BuildEvent.LogEvent l
                            && l.message().startsWith("> Tâche")),
                    "aucune ligne de tâche composée vers une coquille ancienne");
            assertTrue(événements.stream().noneMatch(e -> e instanceof BuildEvent.LogEvent l
                            && l.message().startsWith("BUILD ")),
                    "aucun verdict composé vers une coquille ancienne");
            assertTrue(événements.stream().anyMatch(e -> e instanceof BuildEvent.LogEvent l
                            && l.message().startsWith("build de ")),
                    "le comportement d'avant tient : l'INFO n'est pas trié pour elle");
            session.arrêt();
        }
    }

    @Test
    @Timeout(60)
    void diagnosticDeCompilationComposeSaLigneAuNiveauÉcho() throws Exception {
        // Protocole 4 : l'erreur de compilation garde son événement structuré (pour
        // l'éditeur) ET arrive en ligne de console composée par le moteur, au niveau
        // de sévérité correspondant (ERROR — jamais trié).
        Projets.projetDeuxModules(temporaire);
        Projets.fichier(temporaire, "core/src/main/java/demo/core/Fautif.java", """
                package demo.core;

                public class Fautif {
                    int calcul() { return inconnu; }
                }
                """);
        try (Session session = Session.démarrer()) {
            session.poignéeDeMain(4);
            List<BuildEvent> événements = session.build(temporaire,
                    BuildEvent.LogEvent.LogLevel.ERROR);
            assertTrue(!dernier(événements, BuildEvent.BuildFinished.class).success());
            assertTrue(événements.stream().anyMatch(e -> e instanceof BuildEvent.LogEvent l
                            && l.level() == BuildEvent.LogEvent.LogLevel.ERROR
                            && l.message().startsWith("⚠ ")
                            && l.message().contains("Fautif.java")),
                    "la ligne de diagnostic est composée par le moteur, même au niveau ERROR");
            assertTrue(événements.stream().anyMatch(e ->
                            e instanceof BuildEvent.TaskStateEvent t
                                    && t.state() == BuildEvent.TaskState.FAILED
                                    && t.taskName().equals("core:compileJava")),
                    "l'état structuré de la tâche fautive reste émis");
            session.arrêt();
        }
    }

    @Test
    @Timeout(60)
    void répertoireDeTâchesSurLeCanal() throws Exception {
        // Protocole 5-6 (D-22/D-23) : TASKS_REQUEST → TASKS en une trame. Le
        // moteur énumère CE QUI PEUT TOURNER — concrets (groupés, décrits) +
        // cycle de vie (assemble, build honnête, clean) — la coquille affiche
        // sans deviner.
        Projets.projetDeuxModules(temporaire);
        try (Session session = Session.démarrer()) {
            session.poignéeDeMain(6);
            TasksResponse répertoire = session.répertoire(temporaire);
            assertEquals(temporaire.toString(), répertoire.projectRoot());
            List<String> noms = répertoire.tasks().stream()
                    .map(TaskDescription::name).toList();
            assertEquals(List.of("app:jar", "core:jar", "app:compileJava",
                            "core:compileJava", "app:resolveDependencies",
                            "core:resolveDependencies", "assemble", "build",
                            "clean"), noms,
                    "six tâches concrètes (ordre : groupe puis nom) + cycle de vie "
                            + "en fin (assemble, build, clean)");
            TaskDescription compile = répertoire.tasks().stream()
                    .filter(t -> t.name().equals("app:compileJava")).findFirst().orElseThrow();
            assertEquals("compilation", compile.group());
            assertTrue(compile.description().contains("Java"),
                    "la description dit ce que fait la tâche : " + compile.description());
            assertTrue(compile.dependencies().contains("app:resolveDependencies"));
            assertTrue(compile.dependencies().contains("core:compileJava"),
                    "les dépendances de projet sont citées");
            TaskDescription assemble = répertoire.tasks().stream()
                    .filter(t -> t.name().equals("assemble")).findFirst().orElseThrow();
            assertTrue(assemble.lifecycle());
            assertEquals(List.of("app:jar", "core:jar"), assemble.dependencies(),
                    "assemble agrège les sorties terminales des deux modules");
            // build (protocole 6, D-23) : l'alias HONNÊTE — sans vérification il
            // vaut assemble, ses dépendances le MONTRENT et sa description le DIT.
            TaskDescription build = répertoire.tasks().stream()
                    .filter(t -> t.name().equals("build")).findFirst().orElseThrow();
            assertTrue(build.lifecycle());
            assertEquals(assemble.dependencies(), build.dependencies(),
                    "build = assemble aujourd'hui : les dépendances le disent, "
                            + "pas la promesse");
            assertTrue(build.description().contains("assemble"),
                    "la description de build dit ce qu'il vaut : "
                            + build.description());
            assertTrue(répertoire.tasks().stream().noneMatch(t ->
                            List.of("check", "lint", "test").contains(t.name())),
                    "aucun alias de vérification n'est promis sans tâche");
            TaskDescription clean = répertoire.tasks().stream()
                    .filter(t -> t.name().equals("clean")).findFirst().orElseThrow();
            assertTrue(clean.lifecycle());
            assertEquals(List.of(), clean.dependencies());
            session.arrêt();
        }
    }

    @Test
    @Timeout(60)
    void répertoireParSousEnsembleDeModules() throws Exception {
        // Protocole 6 (D-23) : TASKS_REQUEST porte des modules — la vue de core
        // (l'équivalent de gradlew :core:tasks) expose ses concrets + SES
        // agrégats (core:assemble, core:clean) ; jamais l'assemble global
        // redéfini en silence.
        Projets.projetDeuxModules(temporaire);
        try (Session session = Session.démarrer()) {
            session.poignéeDeMain(6);
            TasksResponse vue = session.répertoire(temporaire, List.of("core"));
            List<String> noms = vue.tasks().stream()
                    .map(TaskDescription::name).toList();
            assertEquals(List.of("core:jar", "core:compileJava",
                    "core:resolveDependencies", "core:assemble", "core:clean"), noms,
                    "la vue sous-ensemble : les concrets du module + SES agrégats");
            TaskDescription assembleCore = vue.tasks().stream()
                    .filter(t -> t.name().equals("core:assemble")).findFirst().orElseThrow();
            assertTrue(assembleCore.lifecycle());
            assertEquals(List.of("core:jar"), assembleCore.dependencies(),
                    "core:assemble agrège les sorties de core SEULEMENT");
            session.arrêt();
        }
    }

    @Test
    @Timeout(60)
    void moduleInconnuDansLeRépertoireRefuséSurLeCanal() throws Exception {
        // Un module inconnu est une erreur de la DEMANDE : INTERNAL_ERROR honnête
        // (modules disponibles cités), le moteur reste vivant.
        Projets.projetDeuxModules(temporaire);
        try (Session session = Session.démarrer()) {
            session.poignéeDeMain(6);
            session.écrirePublique(FrameType.TASKS_REQUEST, out ->
                    MessageIO.writeTasksRequest(out,
                            new TasksRequest(temporaire.toString(), List.of("zork"))));
            Frame réponse = session.lireTramePublique();
            assertEquals(FrameType.INTERNAL_ERROR, réponse.type(),
                    "le refus d'un module inconnu est une erreur de demande");
            String message = MessageIO.readErrorMessage(new DataInputStream(
                    new ByteArrayInputStream(réponse.payload())));
            assertTrue(message.contains("zork") && message.contains("core"),
                    "le refus cite le fautif et les modules disponibles : " + message);
            // Le moteur reste vivant : le répertoire complet répond encore.
            assertEquals(9, session.répertoire(temporaire).tasks().size(),
                    "le canal a survécu à la mauvaise question");
            session.arrêt();
        }
    }

    @Test
    @Timeout(60)
    void buildCommeAliasConstruitToutSurLeCanal() throws Exception {
        // build (protocole 6, D-23) : l'alias honnête — sans vérification il
        // exécute la clôture d'assemble, l'agrégat réussit comme une tâche.
        Projets.projetDeuxModules(temporaire);
        try (Session session = Session.démarrer()) {
            session.poignéeDeMain(6);
            List<BuildEvent> événements = session.build(temporaire,
                    List.of("build"));
            assertTrue(dernier(événements, BuildEvent.BuildFinished.class).success());
            List<String> exécutées = tâchesRéussies(événements);
            assertEquals(List.of("app:compileJava", "app:jar",
                    "app:resolveDependencies", "build", "core:compileJava",
                    "core:jar", "core:resolveDependencies"), exécutées,
                    "build exécute tout le graphe + l'agrégat — comme assemble");
            session.arrêt();
        }
    }

    @Test
    @Timeout(60)
    void buildDUnModuleSeulNeConstruitQueCeModule() throws Exception {
        // core:assemble (protocole 6) : le nom EST le périmètre — la clôture de
        // core, jamais app.
        Projets.projetDeuxModules(temporaire);
        try (Session session = Session.démarrer()) {
            session.poignéeDeMain(6);
            List<BuildEvent> événements = session.build(temporaire,
                    List.of("core:assemble"));
            assertTrue(dernier(événements, BuildEvent.BuildFinished.class).success());
            assertEquals(List.of("core:assemble", "core:compileJava", "core:jar",
                    "core:resolveDependencies"), tâchesRéussies(événements),
                    "core:assemble construit core, rien d'autre");
            assertTrue(Files.isDirectory(temporaire.resolve("build").resolve("core")
                    .resolve("classes")), "les sorties de core existent");
            assertFalse(Files.exists(temporaire.resolve("build").resolve("app")),
                    "les sorties de app n'existent PAS");
            session.arrêt();
        }
    }

    @Test
    @Timeout(60)
    void tâcheRefuséeHorsProtocoleCinq() throws Exception {
        // Une coquille qui n'a pas négocié le protocole 5 n'a pas à parler
        // TASKS_REQUEST ; si elle le fait quand même, le canal est désordonné :
        // PROTOCOL_ERROR puis fermeture (le contrat des trames inattendues).
        try (Session session = Session.démarrer()) {
            session.poignéeDeMain(4);
            session.écrirePublique(FrameType.TASKS_REQUEST, out ->
                    MessageIO.writeTasksRequest(out,
                            new TasksRequest(temporaire.toString())));
            Frame réponse = session.lireTramePublique();
            assertEquals(FrameType.PROTOCOL_ERROR, réponse.type());
        }
    }

    @Test
    @Timeout(60)
    void buildCibléSurUneTâcheNExécuteQueSaClôture() throws Exception {
        // La cible du build est un ARGUMENT (protocole 5) : demander
        // core:compileJava exécute resolveDependencies + compileJava — jamais le
        // jar, jamais app. La clôture se calcule côté moteur.
        Projets.projetDeuxModules(temporaire);
        try (Session session = Session.démarrer()) {
            session.poignéeDeMain(5);
            List<BuildEvent> événements = session.build(temporaire,
                    List.of("core:compileJava"));
            BuildEvent.BuildFinished fin = dernier(événements, BuildEvent.BuildFinished.class);
            assertTrue(fin.success(), "le build ciblé doit réussir : " + fin.message());
            List<String> exécutées = événements.stream()
                    .filter(e -> e instanceof BuildEvent.TaskStateEvent t
                            && t.state() == BuildEvent.TaskState.SUCCEEDED)
                    .map(e -> ((BuildEvent.TaskStateEvent) e).taskName()).sorted().toList();
            assertEquals(List.of("core:compileJava", "core:resolveDependencies"), exécutées,
                    "seule la clôture demandée s'exécute");
            assertTrue(Files.isDirectory(temporaire.resolve("build").resolve("core")
                            .resolve("classes")),
                    "les sorties de la cible existent");
            session.arrêt();
        }
    }

    @Test
    @Timeout(60)
    void buildAssembleConstruitToutesLesSorties() throws Exception {
        // assemble = la clôture des terminales + l'agrégat : tout le graphe concret
        // s'exécute et la tâche de cycle de vie elle-même réussit (elle ne fait
        // rien : son travail est ses dépendances).
        Projets.projetDeuxModules(temporaire);
        try (Session session = Session.démarrer()) {
            session.poignéeDeMain(5);
            List<BuildEvent> événements = session.build(temporaire,
                    List.of("assemble"));
            assertTrue(dernier(événements, BuildEvent.BuildFinished.class).success());
            List<String> réussies = événements.stream()
                    .filter(e -> e instanceof BuildEvent.TaskStateEvent t
                            && t.state() == BuildEvent.TaskState.SUCCEEDED)
                    .map(e -> ((BuildEvent.TaskStateEvent) e).taskName()).sorted().toList();
            assertEquals(List.of("app:compileJava", "app:jar", "app:resolveDependencies",
                            "assemble", "core:compileJava", "core:jar",
                            "core:resolveDependencies"), réussies,
                    "toutes les tâches + l'agrégat ont tourné");
            session.arrêt();
        }
    }

    @Test
    @Timeout(60)
    void buildCleanSupprimeLesSortiesPasLesSources() throws Exception {
        // clean demandé seul : le build/ du projet disparaît, les sources et
        // l'état .forge/ (empreintes) restent — le modèle du clean de Gradle.
        Projets.projetDeuxModules(temporaire);
        try (Session session = Session.démarrer()) {
            session.poignéeDeMain(5);
            assertTrue(dernier(session.build(temporaire),
                    BuildEvent.BuildFinished.class).success());
            assertTrue(Files.isDirectory(temporaire.resolve("build")),
                    "le premier build a produit des sorties");
            List<BuildEvent> nettoyage = session.build(temporaire, List.of("clean"));
            assertTrue(dernier(nettoyage, BuildEvent.BuildFinished.class).success());
            assertFalse(Files.exists(temporaire.resolve("build")),
                    "le répertoire build/ doit disparaître");
            assertTrue(Files.exists(temporaire.resolve("core").resolve("src")
                            .resolve("main").resolve("java").resolve("demo")
                            .resolve("core").resolve("Textes.java")),
                    "les sources ne sont jamais touchées");
            assertTrue(Files.isDirectory(temporaire.resolve(".forge")),
                    "l'état .forge/ (empreintes) survit au clean");
            session.arrêt();
        }
    }

    @Test
    @Timeout(60)
    void tâcheInconnueÉchoueProprementAvecLesCandidats() throws Exception {
        // Le refus de Gradle : le nom fautif est cité, les candidats suivent —
        // jamais un écroulement du canal.
        Projets.projetDeuxModules(temporaire);
        try (Session session = Session.démarrer()) {
            session.poignéeDeMain(5);
            List<BuildEvent> événements = session.build(temporaire,
                    List.of("app:dex"));
            BuildEvent.BuildFinished fin = dernier(événements, BuildEvent.BuildFinished.class);
            assertFalse(fin.success(), "une cible inconnue échoue");
            assertTrue(fin.message().contains("app:dex"),
                    "le nom fautif est cité : " + fin.message());
            assertTrue(fin.message().contains("app:jar"),
                    "les candidats disponibles accompagnent le refus");
            session.arrêt();
        }
    }

    /**
     * Récupère le dernier événement du type demandé.
     */
    /** Les noms des tâches réussies d'un build, triés — le décompte réel. */
    private static List<String> tâchesRéussies(List<BuildEvent> événements) {
        return événements.stream()
                .filter(e -> e instanceof BuildEvent.TaskStateEvent t
                        && t.state() == BuildEvent.TaskState.SUCCEEDED)
                .map(e -> ((BuildEvent.TaskStateEvent) e).taskName()).sorted().toList();
    }

    private static <T extends BuildEvent> T dernier(List<BuildEvent> événements,
                                                    Class<T> type) {
        T trouvé = null;
        for (BuildEvent événement : événements) {
            if (type.isInstance(événement)) {
                trouvé = type.cast(événement);
            }
        }
        if (trouvé == null) {
            throw new AssertionError("aucun événement " + type.getSimpleName()
                    + " reçu");
        }
        return trouvé;
    }

    /**
     * Session de test : le serveur tourne sur des tuyaux, le test joue la coquille.
     */
    private static final class Session implements AutoCloseable {

        private final BuildEngineServer serveur;
        private final Thread fil;
        private final FrameWriter versMoteur;
        private final FrameReader duMoteur;
        private final PipedOutputStream sortieVersMoteur;
        private long corrélation;

        private Session(BuildEngineServer serveur, Thread fil, FrameWriter versMoteur,
                        FrameReader duMoteur, PipedOutputStream sortieVersMoteur) {
            this.serveur = serveur;
            this.fil = fil;
            this.versMoteur = versMoteur;
            this.duMoteur = duMoteur;
            this.sortieVersMoteur = sortieVersMoteur;
        }

        static Session démarrer() throws IOException {
            PipedOutputStream versMoteurOut = new PipedOutputStream();
            PipedInputStream moteurIn = new PipedInputStream(versMoteurOut, 1 << 16);
            PipedOutputStream moteurOut = new PipedOutputStream();
            PipedInputStream depuisMoteur = new PipedInputStream(moteurOut, 1 << 16);

            BuildEngineServer serveur = new BuildEngineServer(moteurOut, System.err);
            Thread fil = new Thread(() -> {
                try {
                    serveur.serve(moteurIn);
                } catch (IOException e) {
                    // Fermeture du tuyau en fin de test : normale.
                }
            }, "serveur-moteur-test");
            fil.setDaemon(true);
            fil.start();

            Session session = new Session(serveur, fil,
                    new FrameWriter(versMoteurOut), new FrameReader(depuisMoteur),
                    versMoteurOut);
            // Poignée de main initiale du moteur.
            Frame hello = session.lireTrame();
            assertEquals(FrameType.HELLO, hello.type());
            return session;
        }

        /** Poignée de main complète : CLIENT_HELLO et accusé. */
        void poignéeDeMain() throws IOException {
            poignéeDeMain(1);
        }

        /** Poignée de main complète à une version de protocole donnée. */
        void poignéeDeMain(int version) throws IOException {
            écrire(FrameType.CLIENT_HELLO, out -> MessageIO.writeClientHello(out,
                    new ClientHello("0.1.0-test", version)));
            assertEquals(FrameType.CLIENT_HELLO, lireTrame().type());
        }

        /** Lance un build et collecte tous les événements jusqu'à la fin. */
        List<BuildEvent> build(Path racine) throws IOException {
            return build(racine, (BuildEvent.LogEvent.LogLevel) null);
        }

        /** Lance un build au niveau de console demandé et collecte les événements. */
        List<BuildEvent> build(Path racine, BuildEvent.LogEvent.LogLevel consoleLevel)
                throws IOException {
            return requête(FrameType.START_BUILD, racine, consoleLevel);
        }

        /** Lance un build CIBLÉ (protocole 5) : la liste des tâches demandées
         *  part avec la requête — la clôture se calcule côté moteur. */
        List<BuildEvent> build(Path racine, List<String> tâches) throws IOException {
            return requêteCiblée(FrameType.START_BUILD, racine, tâches);
        }

        /** Lance un sync (protocole 2) et collecte les événements jusqu'à la fin. */
        List<BuildEvent> sync(Path racine) throws IOException {
            return requête(FrameType.SYNC_BUILD, racine, null);
        }

        /** Envoie START_BUILD ou SYNC_BUILD au niveau de console donné (null : défaut). */
        List<BuildEvent> requête(FrameType type, Path racine,
                                 BuildEvent.LogEvent.LogLevel consoleLevel) throws IOException {
            return requêteCiblée(type, racine, null, consoleLevel);
        }

        /** Envoie une requête de build avec une cible (protocole 5, null : graphe
         *  complet) au niveau de console défaut. */
        List<BuildEvent> requêteCiblée(FrameType type, Path racine, List<String> tâches)
                throws IOException {
            return requêteCiblée(type, racine, tâches, null);
        }

        /** Requête complète : cible + niveau de console. */
        List<BuildEvent> requêteCiblée(FrameType type, Path racine, List<String> tâches,
                                       BuildEvent.LogEvent.LogLevel consoleLevel)
                throws IOException {
            BuildRequest requête = new BuildRequest("b" + (++corrélation),
                    racine.toString(),
                    new BuildOptions(2, false, false, false, 4096, "x86_64",
                            consoleLevel, tâches));
            écrire(type,
                    out -> MessageIO.writeBuildRequest(out, requête));
            Frame accusé = lireTrame();
            assertEquals(FrameType.BUILD_STARTED, accusé.type());
            assertEquals(requête.buildId(),
                    MessageIO.readBuildStarted(new DataInputStream(
                            new ByteArrayInputStream(accusé.payload()))).buildId());

            List<BuildEvent> événements = new ArrayList<>();
            while (true) {
                Frame trame = lireTrame();
                if (trame.type() != FrameType.BUILD_EVENT) {
                    throw new AssertionError("trame inattendue : " + trame.type());
                }
                BuildEvent événement = MessageIO.readBuildEvent(new DataInputStream(
                        new ByteArrayInputStream(trame.payload())));
                événements.add(événement);
                if (événement instanceof BuildEvent.BuildFinished) {
                    return événements;
                }
            }
        }

        /** Demande le répertoire des tâches (protocole 5). */
        TasksResponse répertoire(Path racine) throws IOException {
            return répertoire(racine, null);
        }

        /** Demande le répertoire PAR SOUS-ENSEMBLE de modules (protocole 6,
         *  null ou vide : tout le projet). */
        TasksResponse répertoire(Path racine, List<String> modules) throws IOException {
            écrire(FrameType.TASKS_REQUEST, out ->
                    MessageIO.writeTasksRequest(out,
                            new TasksRequest(racine.toString(), modules)));
            Frame réponse = lireTrame();
            assertEquals(FrameType.TASKS, réponse.type());
            return MessageIO.readTasksResponse(new DataInputStream(
                    new ByteArrayInputStream(réponse.payload())));
        }

        /** Écrit une trame brute (tests de contrat, hors helpers dédiés). */
        void écrirePublique(FrameType type, FrameWriter.PayloadWriter charge)
                throws IOException {
            écrire(type, charge);
        }

        /** Lit une trame brute (tests de contrat, hors helpers dédiés). */
        Frame lireTramePublique() throws IOException {
            return lireTrame();
        }

        /** Demande l'état du moteur. */
        Status status() throws IOException {
            écrire(FrameType.STATUS_REQUEST, out -> { });
            Frame réponse = lireTrame();
            assertEquals(FrameType.STATUS, réponse.type());
            return MessageIO.readStatus(new DataInputStream(
                    new ByteArrayInputStream(réponse.payload())));
        }

        /** Arrêt propre du moteur. */
        void arrêt() throws IOException, InterruptedException {
            écrire(FrameType.SHUTDOWN, out -> { });
            assertEquals(FrameType.SHUTDOWN, lireTrame().type());
            sortieVersMoteur.close();
            fil.join(TimeUnit.SECONDS.toMillis(10));
        }

        private Frame lireTrame() throws IOException {
            Frame trame = duMoteur.readFrame();
            if (trame == null) {
                throw new AssertionError("canal fermé avant la trame attendue");
            }
            return trame;
        }

        private void écrire(FrameType type, FrameWriter.PayloadWriter charge)
                throws IOException {
            versMoteur.write(type, ++corrélation, charge);
            versMoteur.flush();
        }

        @Override
        public void close() {
            try {
                sortieVersMoteur.close();
            } catch (IOException ignored) {
                // Déjà fermé.
            }
            try {
                fil.join(TimeUnit.SECONDS.toMillis(10));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
