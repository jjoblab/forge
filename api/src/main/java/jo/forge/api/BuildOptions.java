package jo.forge.api;

/**
 * Options d'exécution d'un build, fixées par la coquille à partir de son environnement.
 *
 * <p>La coquille est la seule à connaître l'appareil (mémoire totale, ABI, préférences
 * utilisateur) : le moteur, pur Java, reçoit ces valeurs au lieu de les deviner.</p>
 *
 * @param parallelism  nombre maximal de tâches exécutées en parallèle souhaité ;
 *                     le moteur peut le plafonner selon son propre budget.
 * @param lowMemory    mode basse mémoire : tas et budgets réduits, une seule travailleuse.
 * @param offline      mode hors ligne : aucune résolution réseau autorisée.
 * @param verbose      journal détaillé demandé.
 * @param totalRamMb   mémoire totale de l'appareil en Mo (indication, pas une limite).
 * @param abi          ABI de l'appareil (ex. {@code arm64-v8a}), pour les composants natifs.
 */
public record BuildOptions(int parallelism, boolean lowMemory, boolean offline,
                           boolean verbose, long totalRamMb, String abi) {
}
