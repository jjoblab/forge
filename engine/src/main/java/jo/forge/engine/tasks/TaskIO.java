package jo.forge.engine.tasks;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Déclaration des entrées, sorties et propriétés d'une tâche — le contrat de
 * l'oracle d'up-to-date (décision D-12).
 *
 * <p>Une tâche est à jour si l'empreinte de ses entrées (contenu des fichiers,
 * propriétés, version du code de la tâche) correspond au dernier passage réussi ET
 * que ses sorties existent toujours. Modifier un fichier ne doit relancer que les
 * tâches qui le consomment — pas ses voisines.</p>
 *
 * @param inputFiles   fichiers d'entrée individuels (chemins absolus ou relatifs au
 *                     projet — l'empreinte les normalise).
 * @param inputDirs    répertoires d'entrée (hachés récursivement, en flux).
 * @param properties   propriétés participant à l'empreinte (options, versions
 *                     d'outils, cible).
 * @param outputPaths  sorties déclarées (fichiers ou répertoires) : leur existence
 *                     est revérifiée à chaque build.
 * @param taskVersion  version du code de la tâche : changer une implémentation
 *                     invalide les passages précédents.
 */
public record TaskIO(List<Path> inputFiles, List<Path> inputDirs,
                     Map<String, String> properties, List<Path> outputPaths,
                     String taskVersion) {

    /** Déclaration vide (tâche sans entrées ni sorties : toujours exécutée). */
    public static final TaskIO EMPTY = new TaskIO(List.of(), List.of(), Map.of(),
            List.of(), "0");

    /**
     * Valide et fige la déclaration.
     */
    public TaskIO {
        inputFiles = inputFiles == null ? List.of() : List.copyOf(inputFiles);
        inputDirs = inputDirs == null ? List.of() : List.copyOf(inputDirs);
        properties = properties == null ? Map.of() : Map.copyOf(properties);
        outputPaths = outputPaths == null ? List.of() : List.copyOf(outputPaths);
        taskVersion = taskVersion == null || taskVersion.isBlank() ? "0" : taskVersion;
    }

    /**
     * Construit une déclaration à partir de fichiers et de sorties.
     *
     * @param taskVersion  version du code de la tâche.
     * @param inputFiles   fichiers d'entrée.
     * @param outputPaths  sorties.
     * @return la déclaration.
     */
    public static TaskIO of(String taskVersion, List<Path> inputFiles,
                            List<Path> outputPaths) {
        return new TaskIO(inputFiles, List.of(), Map.of(), outputPaths, taskVersion);
    }
}
