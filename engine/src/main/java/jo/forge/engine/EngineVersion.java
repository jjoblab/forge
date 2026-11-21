package jo.forge.engine;

/**
 * Version courante du moteur Forge (source unique : poignée de main, manifeste du jar,
 * trace de build).
 *
 * <p>Sémantique : {@code majeure.mineure.corrective} — la mineure bouge quand le
 * contrat {@code api/} ou le format {@code forge.json} évolue, la corrective pour tout
 * le reste. Le protocole du canal a sa propre plage (voir {@code ProtocolVersions}).</p>
 */
public final class EngineVersion {

    /** Version courante. */
    public static final String CURRENT = "0.2.18";

    private EngineVersion() {
    }
}
