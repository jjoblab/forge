package jo.forge.app;

/**
 * Clés publiques de production épinglées dans la coquille (décision D-05).
 *
 * <p>La clé privée correspondante vit UNIQUEMENT en jeton CI
 * ({@code FORGE_MANIFEST_KEY}) et hors du dépôt : toute la chaîne d'approvisionnement
 * du moteur (manifeste + composants) se vérifie contre la clé ci-dessous. Une
 * compromission de la clé impose d'en générer une nouvelle PAIRE et de mettre à jour
 * cette constante — c'est le seul endroit du dépôt habilité à la porter.</p>
 */
public final class ClesForge {

    /**
     * Clé publique ECDSA P-256 (X.509, base64) de signature du manifeste des
     * composants — générée le 2026-11-20, première génération.
     */
    public static final String CLÉ_PUBLIQUE_MANIFESTE =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEZ2I0By86IiBVR1cB1wKeWYG25wWcpg1RJ360V6sl"
                    + "OQlacWA6T4jNi7U1KXFFNyDL/gyCxrBikr0h2eNQzocPDw==";

    /**
     * Clé publique de ROTATION (constat M-2) : vide tant qu'aucune rotation n'est
     * engagée. Le jour où la clé courante doit être remplacée (fuite du jeton CI,
     * compromission), la nouvelle clé publique vient ici SANS toucher à la
     * courante — les manifestes signés par l'une ou l'autre sont acceptés le temps
     * que toute la flotte migre ; la clé courante ne part que lorsqu'aucune
     * installation raisonnable ne la référence encore.
     *
     * <p>La clé privée de rotation vit elle aussi en jeton CI, jamais dans le
     * dépôt. Cette constante est le seul endroit habilité à porter la valeur.</p>
     */
    public static final String CLÉ_PUBLIQUE_MANIFESTE_SUIVANTE = "";

    private ClesForge() {
    }
}
