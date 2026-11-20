package jo.forge.engine.pipeline;

import jo.forge.engine.model.ForgeModule;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Disposition canonique des répertoires d'un projet Forge (source de vérité unique —
 * les tâches, le magasin d'état et les diagnostics s'y réfèrent).
 *
 * <pre>
 *   &lt;racine&gt;/&lt;module&gt;/src/...          sources et ressources déclarées
 *   &lt;racine&gt;/build/&lt;module&gt;/classes/    sorties de compilation
 *   &lt;racine&gt;/build/&lt;module&gt;/libs/       artefacts (jar, plus tard APK)
 *   &lt;racine&gt;/.forge/state/              magasin d'empreintes (décision D-12)
 * </pre>
 *
 * <p>Les sorties vivent sous {@code build/} (jetables), l'état sous {@code .forge/}
 * (persistant) — jamais l'inverse, jamais mélangés aux sources.</p>
 */
public final class ProjectLayout {

    private final Path root;

    /**
     * Crée la disposition pour un projet.
     *
     * @param projectRoot racine du projet (contient {@code forge.json}).
     */
    public ProjectLayout(Path projectRoot) {
        this.root = projectRoot;
    }

    /**
     * Racine du projet.
     *
     * @return le chemin de la racine.
     */
    public Path root() {
        return root;
    }

    /**
     * Répertoire d'un module.
     *
     * @param moduleName nom du module.
     * @return {@code <racine>/<module>}.
     */
    public Path moduleDir(String moduleName) {
        return root.resolve(moduleName);
    }

    /**
     * Répertoire de build d'un module (racine de toutes ses sorties).
     *
     * @param moduleName nom du module.
     * @return {@code <racine>/build/<module>}.
     */
    public Path buildDir(String moduleName) {
        return root.resolve("build").resolve(moduleName);
    }

    /**
     * Répertoire des classes compilées d'un module.
     *
     * @param moduleName nom du module.
     * @return {@code <racine>/build/<module>/classes}.
     */
    public Path classesDir(String moduleName) {
        return buildDir(moduleName).resolve("classes");
    }

    /**
     * Artefact jar d'un module.
     *
     * @param moduleName nom du module.
     * @return {@code <racine>/build/<module>/libs/<module>.jar}.
     */
    public Path jarFile(String moduleName) {
        return root.resolve("build").resolve(moduleName).resolve("libs")
                .resolve(moduleName + ".jar");
    }

    /**
     * Répertoire du magasin d'état (empreintes, décision D-12).
     *
     * @return {@code <racine>/.forge/state}.
     */
    public Path stateDir() {
        return root.resolve(".forge").resolve("state");
    }

    /**
     * Répertoires sources Java existants d'un module (les répertoires déclarés mais
     * absents sont omis : un module peut n'avoir que des ressources).
     *
     * @param module le module.
     * @return les chemins absolus existants.
     */
    public List<Path> existingSourceDirs(ForgeModule module) {
        return existingDirs(module, module.sources());
    }

    /**
     * Répertoires de ressources Java existants d'un module.
     *
     * @param module le module.
     * @return les chemins absolus existants.
     */
    public List<Path> existingResourceDirs(ForgeModule module) {
        return existingDirs(module, module.resources());
    }

    private List<Path> existingDirs(ForgeModule module, List<String> chemins) {
        List<Path> resultat = new ArrayList<>();
        for (String chemin : chemins) {
            Path dir = moduleDir(module.name()).resolve(chemin);
            if (java.nio.file.Files.isDirectory(dir)) {
                resultat.add(dir);
            }
        }
        return resultat;
    }
}
