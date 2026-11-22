package jo.forge.engine.pipeline.android;

import jo.forge.engine.model.ForgeModule;
import jo.forge.engine.pipeline.ProjectLayout;
import jo.forge.engine.pipeline.java.ClasspathFile;
import jo.forge.engine.tasks.Task;
import jo.forge.engine.tasks.TaskContext;
import jo.forge.engine.tasks.TaskIO;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tâche {@code <module>:dex} : conversion des classes du module (et de ses
 * dépendances) en {@code classes.dex} par d8, <strong>en processus</strong> — mais
 * PAR BIBLIOTHÈQUE depuis la v0.2.22 (décision D-16, {@link DexBibliothèques}) :
 * chaque jar est dexé séparément dans un seau à hash de contenu. Le pic de mémoire
 * de chaque invocation d8 est borné par la plus grosse bibliothèque, et chaque seau
 * terminé survit à une mort brutale.
 *
 * <p>Depuis la v0.2.23 (décision D-19), dès {@code minSdk 26} chaque seau est dexé
 * en forme FINALE et l'assemblage multidex est une COPIE renumérotée — plus aucune
 * fusion d8 : le constat appareil v0.2.22 (moteur tué pendant la fusion, puis
 * 104 s de fusion au retry réussi) est refermé. Sous 26, la fusion par lots
 * intermédiaires de D-16 reste (le désucrage exige l'univers des jars).</p>
 *
 * <p>Entrées de l'oracle : le répertoire de classes du module, les répertoires de
 * classes des dépendances de projet, le fichier de classpath Maven résolu (les jars y
 * sont résumés par coordonnées), le niveau d'API minimal et la voie du dexeur.
 * Sortie : le répertoire de dex (un ou plusieurs {@code classes*.dex} en multidex
 * natif dès minSdk 21).</p>
 *
 * <p>Les diagnostics d8 passent par son API de gestionnaire (pas de parsing de
 * sortie) ; l'échec du dexeur cite la première erreur. La progression est PULSÉE :
 * chaque bibliothèque posée en message de progression, plus l'assemblage — plus
 * jamais de silence opaque pendant le dexage.</p>
 */
public final class DexTask implements Task {

    /** Version du code de cette tâche. */
    static final String TASK_VERSION = "3";

    private final ForgeModule module;
    private final ProjectLayout layout;
    private final List<Path> programmesSupplémentaires;
    private final Path classpathFile;
    private final Path androidJar;
    private final Path cachePartagé;

    /**
     * Crée la tâche.
     *
     * @param module      module Android.
     * @param layout      disposition du projet.
     * @param programmesSupplémentaires classes des dépendances de projet (répertoires).
     * @param classpathFile fichier de classpath Maven résolu.
     * @param androidJar  jar de la plate-forme (bibliothèque de référence d8).
     */
    public DexTask(ForgeModule module, ProjectLayout layout,
                   List<Path> programmesSupplémentaires, Path classpathFile,
                   Path androidJar) {
        this(module, layout, programmesSupplémentaires, classpathFile, androidJar,
                null);
    }

    /**
     * Crée la tâche avec cache de seaux partagé entre projets (décision D-18 :
     * {@code <moteur>/cache/dex} — survit à une réimportation du projet).
     *
     * @param module      module Android.
     * @param layout      disposition du projet.
     * @param programmesSupplémentaires classes des dépendances de projet (répertoires).
     * @param classpathFile fichier de classpath Maven résolu.
     * @param androidJar  jar de la plate-forme (bibliothèque de référence d8).
     * @param cachePartagé racine du cache de seaux partagé (null : cache projet).
     */
    public DexTask(ForgeModule module, ProjectLayout layout,
                   List<Path> programmesSupplémentaires, Path classpathFile,
                   Path androidJar, Path cachePartagé) {
        this.module = module;
        this.layout = layout;
        this.programmesSupplémentaires = List.copyOf(programmesSupplémentaires);
        this.classpathFile = classpathFile;
        this.androidJar = androidJar;
        this.cachePartagé = cachePartagé;
    }

    @Override
    public String name() {
        return module.name() + ":dex";
    }

    @Override
    public List<String> dependencies() {
        return List.of(module.name() + ":compileJava");
    }

    @Override
    public TaskIO io() {
        List<Path> entrées = new ArrayList<>(programmesSupplémentaires);
        entrées.add(layout.classesDir(module.name()));
        int minSdk = module.android().orElseThrow().minSdk();
        return new TaskIO(List.of(classpathFile), entrées,
                Map.of("minApi", Integer.toString(minSdk),
                        "dexeur", minSdk >= 26 ? "d8-final-multidex" : "d8-lots"),
                List.of(répertoireDex()), TASK_VERSION);
    }

    @Override
    public void execute(TaskContext context) throws IOException {
        context.checkCancelled();
        int minSdk = module.android().orElseThrow().minSdk();
        boolean voieFinale = minSdk >= 26; // D-19 : assemblage par copie, sans fusion.

        // Les programmes : les classes du module (emballées en jar déterministe),
        // celles des dépendances de projet, puis les bibliothèques Maven du
        // classpath d'exécution (un AAR y contribue par son classes.jar et ses
        // libs/*.jar).
        List<Path> programmesDéps = new ArrayList<>();
        for (Path source : programmesSupplémentaires) {
            if (Files.isDirectory(source)) {
                programmesDéps.add(enJar(source, context));
            } else {
                programmesDéps.add(source);
            }
        }
        Path jarModule = enJar(layout.classesDir(module.name()), context);
        List<Path> jarsMaven = Files.isRegularFile(classpathFile)
                ? ClasspathFile.lireJarsExécution(classpathFile) : List.of();

        // Ordre APK (D-19) : le module D'ABORD — ses classes ombragent celles des
        // bibliothèques à l'exécution (classes.dex parcouru le premier) — puis les
        // dépendances de projet, puis les bibliothèques Maven. La voie intermédiaire
        // utilise le même ordre (la fusion re-indexe tout, l'ordre n'y est que
        // déterminisme).
        List<Path> ordre = new ArrayList<>();
        ordre.add(jarModule);
        ordre.addAll(programmesDéps);
        ordre.addAll(jarsMaven);

        // Dexage PAR BIBLIOTHÈQUE (D-16) : clé de contenu seul dès minApi 26 (pas de
        // désucrage) ; sous 26, le désucrage dépend de l'ensemble — l'empreinte
        // d'univers entre dans la clé des seaux.
        Path racineCache = cachePartagé != null
                ? cachePartagé : layout.root().resolve(".forge").resolve("dex");
        DexBibliothèques dexeur = new DexBibliothèques(racineCache, minSdk,
                minSdk < 26 ? DexBibliothèques.empreinteUnivers(ordre) : "");

        List<Path> seaux = new ArrayList<>();
        int reprises = 0;
        for (int i = 0; i < ordre.size(); i++) {
            context.checkCancelled();
            Path programme = ordre.get(i);
            context.progression("dexage : " + (i + 1) + "/" + ordre.size()
                    + " — " + programme.getFileName());
            Path seau = dexeur.seauDe(programme, androidJar, context);
            if (seaux.contains(seau)) {
                continue; // Deux chemins vers le même artefact : un seul seau.
            }
            seaux.add(seau);
            if (Files.isRegularFile(seau.resolve(".seau-complet"))) {
                reprises++;
            }
        }
        context.logger().info(seaux.size() + (voieFinale
                ? " bibliothèque(s) à assembler ("
                : " bibliothèque(s) à fusionner (")
                + reprises + " reprise(s) du cache de dex)");
        dexeur.fusionner(seaux, répertoireDex(), androidJar, context);
        context.logger().info("dex produit : " + répertoireDex());
    }

    /**
     * Emballe un répertoire de classes en jar déterministe (d8 n'accepte que des
     * fichiers) : entrées triées, horodatage fixe, écriture atomique.
     */
    private Path enJar(Path répertoire, TaskContext context) throws IOException {
        String nom = répertoire.getParent() == null
                ? répertoire.getFileName().toString()
                : répertoire.getParent().getFileName() + "-"
                        + répertoire.getFileName();
        Path jar = layout.buildDir(module.name()).resolve("dex-input")
                .resolve(nom + ".jar");
        Files.createDirectories(jar.getParent());
        Path temporaire = jar.resolveSibling(jar.getFileName() + ".part");
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(
                Files.newOutputStream(temporaire))) {
            List<Path> fichiers = new ArrayList<>();
            if (Files.isDirectory(répertoire)) {
                try (var parcours = Files.walk(répertoire)) {
                    parcours.filter(Files::isRegularFile).forEach(fichiers::add);
                }
            }
            fichiers.sort(Path::compareTo);
            for (Path fichier : fichiers) {
                java.util.zip.ZipEntry entrée = new java.util.zip.ZipEntry(
                        répertoire.relativize(fichier).toString().replace('\\', '/'));
                entrée.setTime(315532800000L);
                zip.putNextEntry(entrée);
                Files.copy(fichier, zip);
                zip.closeEntry();
            }
        }
        try {
            Files.move(temporaire, jar, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException pasAtomique) {
            Files.move(temporaire, jar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        context.logger().debug("classes emballées pour d8 : " + jar.getFileName());
        return jar;
    }

    /** Répertoire de sortie du dex. */
    Path répertoireDex() {
        return layout.buildDir(module.name()).resolve("dex");
    }
}
