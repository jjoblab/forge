/**
 * Supervision du moteur de build installé, côté coquille.
 *
 * <p>Responsabilités : lancer la machine virtuelle enfant qui héberge le moteur
 * ({@code app_process} sur appareil, {@code java} en développement et en test), conduire la
 * poignée de main et la négociation de version, corréler requêtes et réponses, distribuer les
 * événements de build, détecter la mort du moteur et le redémarrer proprement. Pur Java :
 * les chemins et les paramètres d'environnement (ABI, mémoire) sont injectés par la
 * coquille Android (voir {@code docs/conception.md}, décisions D-01 à D-03).</p>
 */
package jo.forge.client;
