package jo.forge.engine.model;

/**
 * Dépendance d'un module : vers un module frère du projet ou vers une coordonnée Maven.
 *
 * <p>Hiérarchie fermée à deux implémentations. La portée ({@link DependencyScope})
 * suit la dépendance, pas le module.</p>
 */
public sealed interface ModuleDependency permits
        ModuleDependency.ProjectDependency,
        ModuleDependency.MavenDependency {

    /**
     * Portée de la dépendance.
     *
     * @return la portée ({@code IMPLEMENTATION} par défaut dans le modèle).
     */
    DependencyScope scope();

    /**
     * Dépendance vers un module du même projet.
     *
     * @param module nom du module dépendu.
     * @param scope  portée.
     */
    record ProjectDependency(String module, DependencyScope scope)
            implements ModuleDependency {
    }

    /**
     * Dépendance vers un artefact Maven.
     *
     * @param group    identifiant de groupe (ex. {@code com.exemple}).
     * @param artifact identifiant d'artefact (ex. {@code outils}).
     * @param version  version (ex. {@code 1.2.3}).
     * @param scope    portée.
     */
    record MavenDependency(String group, String artifact, String version,
                           DependencyScope scope) implements ModuleDependency {

        /**
         * Coordonnée canonique {@code groupe:artefact:version}.
         *
         * @return la coordonnée complète.
         */
        public String coordinate() {
            return group + ":" + artifact + ":" + version;
        }
    }
}
