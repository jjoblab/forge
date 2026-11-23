package jo.forge.api;

import java.util.List;

/**
 * Options d'exécution d'un build, fixées par la coquille à partir de son environnement.
 *
 * <p>La coquille est la seule à connaître l'appareil (mémoire totale, ABI, préférences
 * utilisateur) : le moteur, pur Java, reçoit ces valeurs au lieu de les deviner.</p>
 *
 * <p>{@code consoleLevel} (protocole 4) est l'ARGUMENT de tri des sorties —
 * l'équivalent des {@code -q}/{@code -i}/{@code -d} de Gradle : le moteur n'émet sur
 * le canal que les entrées dont la sévérité atteint ce niveau. Le défaut
 * ({@code LIFECYCLE}) est la console de build classique : lignes de tâches et verdict.
 * La coquille reste libre de filtrer ENCORE pour son affichage — mais le tri de
 * référence, celui que fait le moteur, se pilote ici.</p>
 *
 * <p>{@code tasks} (protocole 5) est la CIBLE du build — l'équivalent des arguments
 * de {@code gradlew} ({@code gradlew :app:compileJava assemble}) : la liste des tâches
 * demandées, la clôture de leurs dépendances se calcule côté MOTEUR. Vide ou nulle
 * (le défaut, la forme historique) : tout le graphe du projet, comme hier. Les noms
 * de cycle de vie ({@code assemble}, {@code clean}) sont acceptés et résolus par le
 * moteur ; un nom inconnu échoue proprement avec la liste des tâches disponibles.</p>
 *
 * @param parallelism  nombre maximal de tâches exécutées en parallèle souhaité ;
 *                     le moteur peut le plafonner selon son propre budget.
 * @param lowMemory    mode basse mémoire : tas et budgets réduits, une seule travailleuse.
 * @param offline      mode hors ligne : aucune résolution réseau autorisée.
 * @param verbose      journal détaillé demandé (dérivé : {@code true} si et seulement
 *                     si {@code consoleLevel} vaut {@code DEBUG}).
 * @param totalRamMb   mémoire totale de l'appareil en Mo (indication, pas une limite).
 * @param abi          ABI de l'appareil (ex. {@code arm64-v8a}), pour les composants natifs.
 * @param consoleLevel niveau de console demandé au moteur (protocole 4) : sévérité
 *                     minimale des entrées de journal émises sur le canal ;
 *                     {@code null} accepté pour le défaut {@code LIFECYCLE}.
 * @param tasks        tâches demandées (protocole 5) : noms complets
 *                     ({@code app:compileJava}) ou de cycle de vie ({@code assemble},
 *                     {@code clean}) ; {@code null} accepté pour le graphe complet.
 */
public record BuildOptions(int parallelism, boolean lowMemory, boolean offline,
                           boolean verbose, long totalRamMb, String abi,
                           BuildEvent.LogEvent.LogLevel consoleLevel,
                           List<String> tasks) {

    /**
     * Construit les options avec le niveau de console et les tâches explicites.
     */
    public BuildOptions {
        if (consoleLevel == null) {
            consoleLevel = BuildEvent.LogEvent.LogLevel.LIFECYCLE;
        }
        // Le booléen historique suit le niveau : une seule source de vérité.
        verbose = consoleLevel == BuildEvent.LogEvent.LogLevel.DEBUG;
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
    }

    /**
     * Construit les options à la console près (le graphe complet — forme du
     * protocole 4, compatibilité des appelants et des charges d'alors).
     *
     * @param parallelism parallélisme souhaité.
     * @param lowMemory   mode basse mémoire.
     * @param offline     mode hors ligne.
     * @param verbose     journal détaillé demandé.
     * @param totalRamMb  mémoire totale de l'appareil en Mo.
     * @param abi         ABI de l'appareil.
     * @param consoleLevel niveau de console demandé (null → LIFECYCLE).
     */
    public BuildOptions(int parallelism, boolean lowMemory, boolean offline,
                        boolean verbose, long totalRamMb, String abi,
                        BuildEvent.LogEvent.LogLevel consoleLevel) {
        this(parallelism, lowMemory, offline, verbose, totalRamMb, abi, consoleLevel, null);
    }

    /**
     * Construit les options à l'ancienne (sans niveau de console ni tâches) — le booléen
     * {@code verbose} choisit : bavard → {@code DEBUG}, sinon → {@code LIFECYCLE}
     * (le défaut d'une console de build). Compatibilité des appelants et des
     * charges utiles d'avant le protocole 4.
     *
     * @param parallelism parallélisme souhaité.
     * @param lowMemory   mode basse mémoire.
     * @param offline     mode hors ligne.
     * @param verbose     journal détaillé demandé.
     * @param totalRamMb  mémoire totale de l'appareil en Mo.
     * @param abi         ABI de l'appareil.
     */
    public BuildOptions(int parallelism, boolean lowMemory, boolean offline,
                        boolean verbose, long totalRamMb, String abi) {
        this(parallelism, lowMemory, offline, verbose, totalRamMb, abi,
                verbose ? BuildEvent.LogEvent.LogLevel.DEBUG
                        : BuildEvent.LogEvent.LogLevel.LIFECYCLE,
                null);
    }
}
