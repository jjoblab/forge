package jo.forge.api;

/**
 * Versions du protocole d'échange coquille ↔ moteur.
 *
 * <p>La coquille et le moteur négocient à la poignée de main : chacun publie la plage de
 * versions qu'il accepte ({@code [min, max]}) et la coquille choisit la version commune la
 * plus haute. Une absence d'intersection est une erreur propre (trame {@code PROTOCOL_ERROR}
 * puis fermeture) — jamais un comportement indéfini
 * (voir {@code docs/conception.md}, décision D-07).</p>
 *
 * <p>Règles de compatibilité : toute évolution <em>rétrocompatible</em> (ajout d'un type de
 * trame, d'un champ en fin de charge utile) incrémente {@link #PROTOCOL_MAX} sans toucher
 * {@link #PROTOCOL_MIN} ; toute rupture incrémente {@link #PROTOCOL_MIN}.</p>
 */
public final class ProtocolVersions {

    /** Plus ancienne version de protocole comprise par ce module. */
    public static final int PROTOCOL_MIN = 1;

    /**
     * Plus récente version de protocole comprise par ce module.
     *
     * <p>Version 2 (additive) : événement de téléchargement
     * ({@code BuildEvent.DownloadEvent}), état de tâche {@code FROM_CACHE} et trame
     * {@code SYNC_BUILD}. Le moteur NE PULSE ces nouveautés que vers une coquille
     * ayant négocié la version 2 ; une coquille version 1 reçoit les dégradations
     * équivalentes (journal INFO, {@code SUCCEEDED}, refus propre de la trame).</p>
     *
     * <p>Version 3 (additive) : événement de pulsation
     * ({@code BuildEvent.HeartbeatEvent}) — une tâche longue est vivante et visible
     * (nom, dernier message de progression, temps écoulé), le silence n'est plus
     * interprétable comme un plantage. Le moteur n'émet la pulsation QUE vers une
     * coquille ayant négocié la version 3 ; une coquille plus ancienne ne la reçoit
     * pas (le silence d'avant restait la norme pour elle).</p>
     *
     * <p>Version 4 (additive) : la console appartient au moteur (décision D-21). Le
     * moteur compose les lignes de la console de build (lignes de tâches, verdict,
     * diagnostics, téléchargements finis — niveau {@code LIFECYCLE} nouveau) et la
     * coquille se contente d'afficher ce qu'elle reçoit ; le tri des sorties devient
     * un argument ({@code BuildOptions.consoleLevel}, équivalent des
     * {@code -q}/{@code -i}/{@code -d} de Gradle) que le moteur APPLIQUE à l'émission.
     * Une coquille plus ancienne négocie la version 3 : le moteur ne compose aucune
     * ligne (elle les composait elle-même hier — rien ne double), le niveau
     * {@code LIFECYCLE} ne part jamais sur le canal, et une charge de requête sans
     * niveau de console reste lisible (lecteur tolérant, {@code verbose} dérivé).</p>
     *
     * <p>Version 5 (additive) : le répertoire des tâches appartient au moteur
     * (décision D-22). Nouvelles trames {@code TASKS_REQUEST}/{@code TASKS} : le
     * moteur énumère ce qui peut tourner (descripteurs : nom, groupe, description,
     * dépendances, tâches de cycle de vie {@code assemble}/{@code clean}) — la
     * coquille affiche, elle ne devine plus. La CIBLE du build devient un argument
     * ({@code BuildOptions.tasks}, l'équivalent des arguments de {@code gradlew}) :
     * le moteur calcule la clôture des dépendances, résout les noms de cycle de vie
     * et échoue proprement sur un nom inconnu avec les candidats. Une coquille plus
     * ancienne négocie la version 4 : jamais de trame {@code TASKS_REQUEST} vers elle
     * (refus côté client avant émission), et une charge de build sans liste de
     * tâches reste le graphe complet — la forme d'alors (lecteur tolérant).</p>
     *
     * <p>Version 6 (additive) : le répertoire parle par sous-ensembles et les
     * alias deviennent honnêtes (décision D-23). {@code TasksRequest} gagne
     * {@code modules} (liste en fin de charge — lecteur tolérant, la forme d'avant
     * reste « tout le projet » ; un moteur plus ancien ignore l'excédent, la trame
     * est bornée) : la vue d'un sous-ensemble énumère les concrets des modules
     * demandés et leurs agrégats propres {@code module:assemble} /
     * {@code module:clean} — l'équivalent de {@code gradlew :core:tasks}. Nouvel
     * alias {@code build}, HONNÊTE : il agrège les terminales ET les tâches de
     * vérification qui EXISTENT (aujourd'hui aucune — ses dépendances et sa
     * description le disent, il vaut exactement {@code assemble} et grandira seul
     * quand les vérifications existeront). {@code check}/{@code lint}/{@code test}
     * apparaissent dans le répertoire SI ET SEULEMENT SI des tâches de vérification
     * ({@code :lint}/{@code :test}) existent dans le graphe — aucun alias
     * prometteur ; un module inconnu est refusé avec les modules disponibles.</p>
     */
    public static final int PROTOCOL_MAX = 6;

    private ProtocolVersions() {
        // Constantes uniquement : pas d'instanciation.
    }
}
