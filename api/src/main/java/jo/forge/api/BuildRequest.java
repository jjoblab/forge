package jo.forge.api;

/**
 * Demande de lancement d'un build.
 *
 * <p>Les données volumineuses (sources, classpath) ne traversent jamais ce message :
 * le moteur lit les fichiers depuis la racine du projet, sur le stockage partagé.</p>
 *
 * @param buildId     identifiant du build, choisi par la coquille (unique par session).
 * @param projectRoot racine absolue du projet à construire (répertoire contenant
 *                    {@code forge.json}).
 * @param options     options d'exécution, jamais {@code null}.
 */
public record BuildRequest(String buildId, String projectRoot, BuildOptions options) {

    /**
     * Valide la requête au moment de la construction.
     *
     * @throws NullPointerException si un champ obligatoire est nul.
     * @throws ProtocolException    si l'identifiant ou la racine est vide.
     */
    public BuildRequest {
        java.util.Objects.requireNonNull(buildId, "buildId");
        java.util.Objects.requireNonNull(projectRoot, "projectRoot");
        java.util.Objects.requireNonNull(options, "options");
        if (buildId.isBlank()) {
            throw new ProtocolException("Identifiant de build vide");
        }
        if (projectRoot.isBlank()) {
            throw new ProtocolException("Racine de projet vide");
        }
    }
}
