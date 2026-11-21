package jo.forge.engine.pipeline.android;

import com.android.tools.r8.CompilationFailedException;
import com.android.tools.r8.D8;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.Diagnostic;
import com.android.tools.r8.DiagnosticsHandler;
import com.android.tools.r8.OutputMode;
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
 * dépendances) en {@code classes.dex} par d8, <strong>en processus</strong> (pur Java,
 * aucun fork de VM nécessaire à cette échelle — l'échelle « travailleuse forkée » du
 * moteur de référence vise ses volumes de bibliothèques).
 *
 * <p>Entrées de l'oracle : le répertoire de classes du module, les répertoires de
 * classes des dépendances de projet, le fichier de classpath Maven résolu (les jars y
 * sont résumés par coordonnées), le niveau d'API minimal et la version du dexeur.
 * Sortie : le répertoire de dex.</p>
 *
 * <p>Les diagnostics d8 passent par son API de gestionnaire (pas de parsing de
 * sortie) ; l'échec du dexeur cite la première erreur.</p>
 */
public final class DexTask implements Task {

    /** Version du code de cette tâche. */
    static final String TASK_VERSION = "1";

    private final ForgeModule module;
    private final ProjectLayout layout;
    private final List<Path> programmesSupplémentaires;
    private final Path classpathFile;
    private final Path androidJar;

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
        this.module = module;
        this.layout = layout;
        this.programmesSupplémentaires = List.copyOf(programmesSupplémentaires);
        this.classpathFile = classpathFile;
        this.androidJar = androidJar;
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
                Map.of("minApi", Integer.toString(minSdk), "dexeur", "d8"),
                List.of(répertoireDex()), TASK_VERSION);
    }

    @Override
    public void execute(TaskContext context) throws IOException {
        context.checkCancelled();
        Path dex = répertoireDex();
        Files.createDirectories(dex);
        List<Path> programmes = new ArrayList<>();
        for (Path source : programmesSupplémentaires) {
            if (Files.isDirectory(source)) {
                programmes.add(enJar(source, context));
            } else {
                programmes.add(source);
            }
        }
        programmes.add(enJar(layout.classesDir(module.name()), context));
        if (Files.isRegularFile(classpathFile)) {
            // Le classpath d'exécution résolu : les bibliothèques Maven sont dexées
            // avec le module (APK monobloc v1 — pas de dex séparé par bibliothèque).
            // Les descripteurs de modules Java (module-info.class, racine comme
            // répertoires META-INF/versions/* des jars multi-versions) sont
            // ÉCARTÉS : ils ne sont pas dexables et, présents dans plusieurs jars
            // (stdlib, coroutines, annotations…), tuent d8 par « defined multiple
            // times » — la fermeture Compose en porte six.
            for (Path jar : ClasspathFile.lireJarsExécution(classpathFile)) {
                programmes.add(assainir(jar, context));
            }
        }
        D8Command.Builder commande = D8Command.builder(
                        new DiagnosticsVersPuits(context))
                .addProgramFiles(programmes)
                .addLibraryFiles(androidJar)
                .setMinApiLevel(module.android().orElseThrow().minSdk())
                .setOutput(dex, OutputMode.DexIndexed);
        try {
            D8.run(commande.build());
        } catch (CompilationFailedException echec) {
            // Les diagnostics détaillés sont déjà partis dans le puits.
            throw new IOException("échec du dex de " + module.name()
                    + " — voir les diagnostics", echec);
        }
        context.logger().info("dex produit : " + dex.resolve("classes.dex"));
    }

    /**
     * Assainit un jar pour d8 : sans {@code module-info.class} (racine ou
     * {@code META-INF/versions/*}). Le jar d'origine est renvoyé TEL QUEL
     * quand il est déjà propre (le cas de tous les jars du module) ; la copie
     * assainie vit dans {@code build/<module>/dex-input/} (atomique).
     */
    private Path assainir(Path jar, TaskContext context) throws IOException {
        boolean àNettoyer;
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
            àNettoyer = zip.stream().anyMatch(entrée ->
                    entrée.getName().endsWith("module-info.class"));
        } catch (IOException illisible) {
            return jar; // jar absent/illisible : d8 le dira nommément.
        }
        if (!àNettoyer) {
            return jar;
        }
        String nom = jar.getFileName().toString();
        Path cible = layout.buildDir(module.name()).resolve("dex-input")
                .resolve(nom.substring(0, nom.length() - 4) + "-nettoye.jar");
        if (Files.isRegularFile(cible)) {
            return cible;
        }
        Files.createDirectories(cible.getParent());
        Path temporaire = cible.resolveSibling(cible.getFileName() + ".part");
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile());
             java.util.zip.ZipOutputStream sortie = new java.util.zip.ZipOutputStream(
                     Files.newOutputStream(temporaire))) {
            for (java.util.zip.ZipEntry entrée : zip.stream()
                    .filter(e -> !e.isDirectory())
                    .filter(e -> !e.getName().endsWith("module-info.class"))
                    .sorted(java.util.Comparator.comparing(
                            java.util.zip.ZipEntry::getName))
                    .toList()) {
                java.util.zip.ZipEntry copie = new java.util.zip.ZipEntry(
                        entrée.getName());
                copie.setTime(315532800000L);
                sortie.putNextEntry(copie);
                zip.getInputStream(entrée).transferTo(sortie);
                sortie.closeEntry();
            }
        }
        try {
            Files.move(temporaire, cible,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException pasAtomique) {
            Files.move(temporaire, cible,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        context.logger().debug("jar assaini pour d8 (module-info écarté) : "
                + cible.getFileName());
        return cible;
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

    /**
     * Traduit les diagnostics d8 vers notre puits (fichier/ligne quand disponibles).
     */
    private record DiagnosticsVersPuits(TaskContext context) implements DiagnosticsHandler {

        @Override
        public void warning(Diagnostic diagnostic) {
            context.logger().warn("d8 : " + diagnostic.getDiagnosticMessage());
        }

        @Override
        public void error(Diagnostic diagnostic) {
            jo.forge.engine.diag.Diagnostic traduit =
                    new jo.forge.engine.diag.Diagnostic(
                            jo.forge.api.BuildEvent.Severity.ERROR,
                            diagnostic.getOrigin() == null ? ""
                                    : diagnostic.getOrigin().toString(),
                            0, 0,
                            "d8",
                            diagnostic.getDiagnosticMessage(),
                            "dexeur");
            context.diagnostics().report(traduit);
        }
    }
}
