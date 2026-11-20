package jo.forge.engine.deps;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * POM brut (« tel que publié ») : les valeurs de substitution, de gestion de
 * dépendances et de parent ne sont PAS encore appliquées — c'est le rôle du résolveur
 * d'assembler le POM effectif.
 *
 * @param group        groupe déclaré (peut venir du parent si absent).
 * @param artifact     artefact déclaré.
 * @param version      version déclarée (peut venir du parent si absente).
 * @param parent       coordonnée du parent, ou {@code null}.
 * @param packaging    type d'empaquetage ({@code jar} par défaut).
 * @param properties   propriétés déclarées.
 * @param managed      entrées de {@code dependencyManagement}.
 * @param dependencies dépendances directes déclarées.
 * @param relocation   nouvelle coordonnée si l'artefact est délocalisé, ou {@code null}.
 */
public record RawPom(String group, String artifact, String version, Coordinate parent,
                     String packaging, Map<String, String> properties,
                     List<ManagedDep> managed, List<PomDep> dependencies,
                     Coordinate relocation) {

    /** Entrée de gestion de dépendances (version imposée, sans résolution transitive). */
    public record ManagedDep(Gav gav, String version) {
    }

    /**
     * Dépendance déclarée.
     *
     * @param group      groupe.
     * @param artifact   artefact.
     * @param version    version (peut être nulle : fournie par la gestion de dépendances).
     * @param scope      portée Maven ({@code compile}, {@code runtime}, {@code provided},
     *                   {@code test} ; nulle = compile).
     * @param optional   dépendance optionnelle (jamais transitive).
     * @param exclusions exclusions (identités à ne pas résoudre dans ce sous-arbre).
     */
    public record PomDep(String group, String artifact, String version, String scope,
                         boolean optional, Set<Gav> exclusions) {

        /**
         * Identité de la dépendance.
         *
         * @return l'identité.
         */
        public Gav gav() {
            return new Gav(group, artifact);
        }
    }
}
