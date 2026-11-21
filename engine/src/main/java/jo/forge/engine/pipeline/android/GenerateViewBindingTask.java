package jo.forge.engine.pipeline.android;

import jo.forge.engine.model.AndroidFacet;
import jo.forge.engine.model.ForgeModule;
import jo.forge.engine.pipeline.ProjectLayout;
import jo.forge.engine.tasks.Task;
import jo.forge.engine.tasks.TaskContext;
import jo.forge.engine.tasks.TaskIO;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Tâche {@code <module>:generateViewBinding} (v0.2.18) : écrit une classe
 * {@code <namespace>.databinding.<Layout>Binding} par layout du module —
 * la génération de code qu'AGP porte derrière {@code buildFeatures {
 * viewBinding true }} (le modèle dérivé des layouts vit dans
 * {@link LiaisonsVues}).
 *
 * <p>La classe générée est du JAVA COMPILABLE, la vraie sortie du build (pas
 * un leurre d'éditeur) : champ public final typé par vue identifiée,
 * {@code inflate(LayoutInflater[, ViewGroup, boolean])}, {@code bind(View)}
 * avec le contrôle de présence (NullPointerException nommant l'id manquant,
 * comme AGP), {@code getRoot()} covariant, et l'interface
 * {@code androidx.viewbinding.ViewBinding} — le runtime que le build ajoute
 * au classpath quand la fonctionnalité est demandée (voir
 * {@code AndroidPipeline} : même comportement qu'AGP, la dépendance arrive
 * avec l'opt-in).</p>
 *
 * <p>Empreinte : les fichiers de LAYOUT seuls (une édition de valeurs ne
 * régénère rien) et le namespace. Sortie : le répertoire des liaisons,
 * régénéré proprement — le binding d'un layout renommé ou supprimé ne doit
 * pas survivre sur le chemin des sources.</p>
 */
public final class GenerateViewBindingTask implements Task {

    /** Version du code de cette tâche. */
    static final String TASK_VERSION = "1";

    private final ForgeModule module;
    private final ProjectLayout layout;

    /**
     * Crée la tâche.
     *
     * @param module module Android (application ou bibliothèque).
     * @param layout disposition du projet.
     */
    public GenerateViewBindingTask(ForgeModule module, ProjectLayout layout) {
        this.module = module;
        this.layout = layout;
    }

    @Override
    public String name() {
        return module.name() + ":generateViewBinding";
    }

    @Override
    public TaskIO io() {
        AndroidFacet facette = module.android().orElseThrow();
        Map<String, String> propriétés = new java.util.TreeMap<>(
                Map.of("namespace", facette.namespace()));
        return new TaskIO(fichiersLayout(), List.of(), propriétés,
                List.of(sortie()), TASK_VERSION);
    }

    @Override
    public void execute(TaskContext context) throws IOException {
        context.checkCancelled();
        AndroidFacet facette = module.android().orElseThrow();
        List<Path> répertoires = répertoiresRes();
        List<LiaisonsVues.Liaison> liaisons = LiaisonsVues.liaisons(répertoires,
                facette.namespace());
        régénérerProprement(sortie());
        if (liaisons.isEmpty()) {
            Files.createDirectories(sortie());
            context.logger().info("aucun layout à lier — ViewBinding inactif");
            return;
        }
        Path paquet = sortie().resolve(LiaisonsVues.paquet(facette.namespace())
                .replace('.', '/'));
        Files.createDirectories(paquet);
        for (LiaisonsVues.Liaison liaison : liaisons) {
            Files.writeString(paquet.resolve(liaison.nomClasse() + ".java"),
                    source(liaison, facette.namespace()));
        }
        context.logger().info(liaisons.size() + " classe(s) de liaison générée(s)"
                + " -> " + LiaisonsVues.paquet(facette.namespace()));
    }

    /** Répertoire de sortie (racine de sources Java). */
    Path sortie() {
        return layout.buildDir(module.name()).resolve("generated")
                .resolve("viewbinding");
    }

    /** Les répertoires res PROPRES du module (chemins absolus existants). */
    private List<Path> répertoiresRes() {
        AndroidFacet facette = module.android().orElseThrow();
        List<Path> répertoires = new ArrayList<>();
        for (String chemin : facette.resourceDirs()) {
            Path dir = layout.moduleDir(module.name()).resolve(chemin);
            if (Files.isDirectory(dir)) {
                répertoires.add(dir);
            }
        }
        return répertoires;
    }

    /** Les fichiers de layout du module (seuls eux comptent à l'empreinte). */
    private List<Path> fichiersLayout() {
        List<Path> fichiers = new ArrayList<>();
        for (Path res : répertoiresRes()) {
            try (var dir = Files.list(res)) {
                dir.filter(Files::isDirectory)
                        .filter(d -> d.getFileName().toString()
                                .split("-", 2)[0].equals("layout"))
                        .forEach(d -> {
                            try (var entries = Files.list(d)) {
                                entries.filter(f -> f.toString().endsWith(".xml"))
                                        .forEach(fichiers::add);
                            } catch (IOException échec) {
                                throw new java.io.UncheckedIOException(échec);
                            }
                        });
            } catch (IOException | java.io.UncheckedIOException échec) {
                // Répertoire absent ou illisible au moment de l'empreinte :
                // l'exécution relira et dira.
            }
        }
        fichiers.sort(Path::compareTo);
        return fichiers;
    }

    /** La source Java d'une classe de liaison. */
    private static String source(LiaisonsVues.Liaison liaison, String namespace) {
        String r = LiaisonsVues.paquet(namespace).equals("databinding")
                ? "R" : namespace + ".R";
        StringBuilder texte = new StringBuilder();
        texte.append("// Généré par Forge — ne pas modifier.\n");
        texte.append("package ").append(liaison.paquet()).append(";\n\n");
        texte.append("public final class ").append(liaison.nomClasse())
                .append(" implements androidx.viewbinding.ViewBinding {\n\n");
        texte.append("    private final ").append(liaison.typeVueRacine())
                .append(" vueRacine;\n");
        for (LiaisonsVues.Champ champ : liaison.champs()) {
            texte.append("    public final ").append(champ.typeVue())
                    .append(' ').append(champ.nom()).append(";\n");
        }
        texte.append('\n');
        // Constructeur privé : la racine puis chaque champ, en ordre de déclaration.
        texte.append("    private ").append(liaison.nomClasse()).append('(');
        texte.append(liaison.typeVueRacine()).append(" vueRacine");
        for (LiaisonsVues.Champ champ : liaison.champs()) {
            texte.append(", ").append(champ.typeVue()).append(' ')
                    .append(champ.nom());
        }
        texte.append(") {\n");
        texte.append("        this.vueRacine = vueRacine;\n");
        for (LiaisonsVues.Champ champ : liaison.champs()) {
            texte.append("        this.").append(champ.nom()).append(" = ")
                    .append(champ.nom()).append(";\n");
        }
        texte.append("    }\n\n");
        texte.append("    @Override\n");
        texte.append("    public ").append(liaison.typeVueRacine())
                .append(" getRoot() { return vueRacine; }\n\n");
        texte.append("    public static ").append(liaison.nomClasse())
                .append(" inflate(android.view.LayoutInflater inflater) {\n");
        texte.append("        return inflate(inflater, null, false);\n");
        texte.append("    }\n\n");
        texte.append("    public static ").append(liaison.nomClasse())
                .append(" inflate(android.view.LayoutInflater inflater, ")
                .append("android.view.ViewGroup parent, boolean attacherAuParent) {\n");
        texte.append("        android.view.View racine = inflater.inflate(")
                .append(r).append(".layout.").append(liaison.nomLayout())
                .append(", parent, false);\n");
        texte.append("        if (attacherAuParent) {\n");
        texte.append("            parent.addView(racine);\n");
        texte.append("        }\n");
        texte.append("        return bind(racine);\n");
        texte.append("    }\n\n");
        texte.append("    public static ").append(liaison.nomClasse())
                .append(" bind(android.view.View vueRacine) {\n");
        for (LiaisonsVues.Champ champ : liaison.champs()) {
            if (champ.liaisonIncluse()) {
                String vue = champ.nom() + "Vue";
                texte.append("        android.view.View ").append(vue)
                        .append(" = vueRacine.findViewById(").append(r)
                        .append(".id.").append(champ.idRessource()).append(");\n");
                texte.append("        if (").append(vue)
                        .append(" == null) { throw new NullPointerException(")
                        .append("\"Vue requise manquante : ")
                        .append(champ.idRessource()).append("\"); }\n");
                texte.append("        ").append(champ.typeVue()).append(' ')
                        .append(champ.nom()).append(" = ").append(champ.typeVue())
                        .append(".bind(").append(vue).append(");\n");
            } else {
                texte.append("        ").append(champ.typeVue()).append(' ')
                        .append(champ.nom()).append(" = vueRacine.findViewById(")
                        .append(r).append(".id.").append(champ.idRessource())
                        .append(");\n");
                texte.append("        if (").append(champ.nom())
                        .append(" == null) { throw new NullPointerException(")
                        .append("\"Vue requise manquante : ")
                        .append(champ.idRessource()).append("\"); }\n");
            }
        }
        texte.append("        return new ").append(liaison.nomClasse()).append("((")
                .append(liaison.typeVueRacine()).append(") vueRacine");
        for (LiaisonsVues.Champ champ : liaison.champs()) {
            texte.append(", ").append(champ.nom());
        }
        texte.append(");\n");
        texte.append("    }\n");
        texte.append("}\n");
        return texte.toString();
    }

    /** Vide le répertoire de sortie : le binding d'un layout supprimé meurt. */
    private static void régénérerProprement(Path sortie) throws IOException {
        if (!Files.isDirectory(sortie)) {
            return;
        }
        try (Stream<Path> parcours = Files.walk(sortie)) {
            parcours.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException échec) {
                            throw new java.io.UncheckedIOException(échec);
                        }
                    });
        }
    }
}
