package jo.forge.engine.pipeline.android;

import jo.forge.engine.model.ForgeModule;
import jo.forge.engine.pipeline.ProjectLayout;
import jo.forge.engine.tasks.Task;
import jo.forge.engine.tasks.TaskContext;
import jo.forge.engine.tasks.TaskIO;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Tâche {@code <module>:packageApk} : assemble l'APK non signé — les entrées de l'APK
 * de base issues d'aapt2 (ressources, manifeste, assets) plus le {@code classes.dex}
 * produit par d8.
 *
 * <p>Déterminisme et sûreté : entrées recopiées triées par nom, horodatage fixe
 * (époque DOS), dex compressé (DEFLATED), écriture temporaire + renommage atomique.
 * Les noms d'entrées traversants ({@code ../}, chemins absolus) d'un APK source sont
 * refusés — la même garde que l'extracteur de l'installateur.</p>
 */
public final class PackageApkTask implements Task {

    /** Version du code de cette tâche. */
    static final String TASK_VERSION = "1";

    /** Horodatage fixe de toutes les entrées (1980-01-01, époque DOS). */
    private static final long HORODATAGE_FIXE = 315532800000L;

    private final ForgeModule module;
    private final ProjectLayout layout;
    private final ProcessResourcesTask ressources;
    private final DexTask dex;

    /**
     * Crée la tâche.
     *
     * @param module    module Android.
     * @param layout    disposition du projet.
     * @param ressources tâche de ressources (pour l'APK de base).
     * @param dex       tâche de dex (pour classes.dex).
     */
    public PackageApkTask(ForgeModule module, ProjectLayout layout,
                          ProcessResourcesTask ressources, DexTask dex) {
        this.module = module;
        this.layout = layout;
        this.ressources = ressources;
        this.dex = dex;
    }

    @Override
    public String name() {
        return module.name() + ":packageApk";
    }

    @Override
    public List<String> dependencies() {
        return List.of(ressources.name(), dex.name());
    }

    @Override
    public TaskIO io() {
        return new TaskIO(List.of(ressources.apkBase(), dex.répertoireDex().resolve("classes.dex")),
                List.of(),
                Map.of("module", module.name()),
                List.of(apkNonSigné()), TASK_VERSION);
    }

    @Override
    public void execute(TaskContext context) throws IOException {
        context.checkCancelled();
        Path sortie = apkNonSigné();
        Files.createDirectories(sortie.getParent());
        Path temporaire = sortie.resolveSibling(sortie.getFileName() + ".part");
        try (ZipOutputStream zip = new ZipOutputStream(
                Files.newOutputStream(temporaire))) {
            // Toutes les entrées de l'APK de base, triées (déterminisme), noms sûrs.
            for (Map.Entry<String, byte[]> entrée : entréesBase().entrySet()) {
                écrire(zip, entrée.getKey(), entrée.getValue(), déflate(entrée.getKey()));
            }
            // Le dex, compressé (alignement géré par la signature v2).
            try (InputStream entréeDex = Files.newInputStream(
                    dex.répertoireDex().resolve("classes.dex"))) {
                écrire(zip, "classes.dex", entréeDex.readAllBytes(), true);
            }
        }
        try {
            Files.move(temporaire, sortie, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException pasAtomique) {
            Files.move(temporaire, sortie, StandardCopyOption.REPLACE_EXISTING);
        }
        context.logger().info("APK non signé assemblé : " + sortie.getFileName());
    }

    /** APK non signé. */
    Path apkNonSigné() {
        return layout.buildDir(module.name()).resolve("packaged")
                .resolve("unsigned.apk");
    }

    /**
     * Lecture ordonnée des entrées de l'APK de base (contenu en mémoire borné par la
     * taille de l'APK de ressources — acceptable pour v1 ; le flux direct viendra avec
     * le cache d'entrées de l'étape 12).
     */
    private TreeMap<String, byte[]> entréesBase() throws IOException {
        TreeMap<String, byte[]> entrées = new TreeMap<>();
        try (ZipInputStream zip = new ZipInputStream(
                Files.newInputStream(ressources.apkBase()))) {
            ZipEntry entrée;
            while ((entrée = zip.getNextEntry()) != null) {
                if (entrée.isDirectory()) {
                    continue;
                }
                vérifierNom(entrée.getName());
                entrées.put(entrée.getName(), zip.readAllBytes());
            }
        }
        return entrées;
    }

    /**
     * Refuse les noms d'entrées traversants (zip-slip en lecture).
     */
    private static void vérifierNom(String nom) throws IOException {
        if (nom.startsWith("/") || nom.contains("..") || nom.contains("\\")
                || nom.chars().anyMatch(c -> c == '\0')) {
            throw new IOException("nom d'entrée interdit dans l'APK de base : " + nom);
        }
    }

    /**
     * Compression : l'arsc et les ressources natives restent non compressées comme
     * aapt2 les a produites — v1 : tout le reste déflaté, l'arsc STORED pour l'alignement.
     */
    private static boolean déflate(String nom) {
        return !nom.endsWith(".arsc") && !nom.endsWith(".dex");
    }

    private static void écrire(ZipOutputStream zip, String nom, byte[] contenu,
                               boolean compressé) throws IOException {
        ZipEntry entrée = new ZipEntry(nom);
        entrée.setTime(HORODATAGE_FIXE);
        if (!compressé) {
            entrée.setMethod(ZipEntry.STORED);
            entrée.setSize(contenu.length);
            entrée.setCrc(new java.util.zip.CRC32() {
                {
                    update(contenu);
                }
            }.getValue());
        }
        zip.putNextEntry(entrée);
        zip.write(contenu);
        zip.closeEntry();
    }
}
