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
     */
    public static final int PROTOCOL_MAX = 3;

    private ProtocolVersions() {
        // Constantes uniquement : pas d'instanciation.
    }
}
