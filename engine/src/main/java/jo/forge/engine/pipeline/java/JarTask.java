package jo.forge.engine.pipeline.java;

import jo.forge.engine.EngineVersion;
import jo.forge.engine.model.ForgeModule;
import jo.forge.engine.pipeline.ProjectLayout;
import jo.forge.engine.tasks.Task;
import jo.forge.engine.tasks.TaskContext;
import jo.forge.engine.tasks.TaskIO;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Tâche {@code <module>:jar} : assemble les classes et ressources du module en un jar
 * **déterministe**.
 *
 * <p>Déterminisme (parité avec l'exigence de builds reproductibles) : entrées triées
 * par nom, horodatage fixe des entrées (1<sup>er</sup> janvier 1980, époque DOS),
 * manifeste sans donnée variable. Deux exécutions sur les mêmes entrées produisent des
 * octets identiques — testé par assertion d'égalité d'empreinte.</p>
 *
 * <p>Contenu : fichiers du répertoire de classes + fichiers des répertoires de
 * ressources, chemins relatifs préservés, collisions refusées (le jar qui écraserait
 * silencieusement est un bug).</p>
 */
public final class JarTask implements Task {

    /** Version du code de cette tâche. */
    static final String TASK_VERSION = "1";

    /** Horodatage fixe de toutes les entrées du zip (1980-01-01T00:00:00Z). */
    private static final long HORODATAGE_FIXE = 315532800000L;

    private final ForgeModule module;
    private final ProjectLayout layout;

    /**
     * Crée la tâche.
     *
     * @param module module à empaqueter.
     * @param layout disposition du projet.
     */
    public JarTask(ForgeModule module, ProjectLayout layout) {
        this.module = module;
        this.layout = layout;
    }

    @Override
    public String name() {
        return module.jarTaskName();
    }

    @Override
    public List<String> dependencies() {
        return List.of(module.compileTaskName());
    }

    @Override
    public TaskIO io() {
        List<Path> entrees = new ArrayList<>();
        entrees.add(layout.classesDir(module.name()));
        entrees.addAll(layout.existingResourceDirs(module));
        return new TaskIO(List.of(), entrees,
                Map.of("module", module.name()),
                List.of(layout.jarFile(module.name())), TASK_VERSION);
    }

    @Override
    public void execute(TaskContext context) throws IOException {
        context.checkCancelled();
        Path jar = layout.jarFile(module.name());
        Files.createDirectories(jar.getParent());
        Path staging = jar.resolveSibling(jar.getFileName() + ".part");
        List<Contenu> contenu = collecteContenu(context);
        try (OutputStream fichier = Files.newOutputStream(staging);
             ZipOutputStream zip = new ZipOutputStream(fichier)) {
            ecrireManifeste(zip);
            for (Contenu entree : contenu) {
                zip.putNextEntry(entreeZip(entree.nom()));
                Files.copy(entree.fichier(), zip);
                zip.closeEntry();
            }
        }
        // Écriture atomique : staging + renommage (jamais de jar à moitié écrit).
        Files.move(staging, jar,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        context.logger().info(contenu.size() + " entrées -> " + jar.getFileName());
    }

    /**
     * Entrée zip standard avec horodatage fixe.
     */
    private ZipEntry entreeZip(String nom) {
        ZipEntry entree = new ZipEntry(nom);
        entree.setTime(HORODATAGE_FIXE);
        return entree;
    }

    /**
     * Manifeste minimal et stable (aucune donnée variable : la trace reste
     * reproductible).
     */
    private void ecrireManifeste(ZipOutputStream zip) throws IOException {
        zip.putNextEntry(entreeZip("META-INF/MANIFEST.MF"));
        zip.write(("Manifest-Version: 1.0\nCreated-By: Forge " + EngineVersion.CURRENT
                + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    /**
     * Collecte classes + ressources, triées par nom d'entrée, collisions refusées.
     */
    private List<Contenu> collecteContenu(TaskContext context) throws IOException {
        List<Contenu> contenu = new ArrayList<>();
        Path classes = layout.classesDir(module.name());
        if (Files.isDirectory(classes)) {
            ajouterArbre(classes, classes, contenu);
        }
        for (Path ressources : layout.existingResourceDirs(module)) {
            ajouterArbre(ressources, ressources, contenu);
        }
        contenu.sort(Comparator.comparing(Contenu::nom));
        for (int i = 1; i < contenu.size(); i++) {
            if (contenu.get(i - 1).nom().equals(contenu.get(i).nom())) {
                throw new IOException("conflit d'entrées dans le jar de "
                        + module.name() + " : " + contenu.get(i).nom());
            }
        }
        return contenu;
    }

    /**
     * Ajoute tous les fichiers d'un arbre, relativement à sa racine.
     */
    private void ajouterArbre(Path racine, Path dir, List<Contenu> contenu)
            throws IOException {
        try (Stream<Path> parcours = Files.walk(dir)) {
            parcours.filter(Files::isRegularFile).forEach(fichier -> {
                String nom = racine.relativize(fichier).toString()
                        .replace('\\', '/');
                contenu.add(new Contenu(nom, fichier));
            });
        }
    }

    /** Une entrée du jar : nom relatif et fichier source. */
    private record Contenu(String nom, Path fichier) {
    }
}
