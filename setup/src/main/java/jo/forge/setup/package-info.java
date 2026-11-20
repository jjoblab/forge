/**
 * Installation, mise à jour, réparation et désinstallation du moteur.
 *
 * <p>Le moteur est un ensemble de composants décrits par un manifeste JSON signé (ECDSA
 * P-256, clé publique épinglée dans la coquille). Ce module télécharge les composants de
 * façon reprenable, vérifie intégrité (SHA-256) et authenticité avant toute installation,
 * extrait les archives avec une garde anti-traversée de chemin, et installe de façon
 * atomique (répertoire temporaire puis renommage). Toutes les entrées/sorties passent par
 * des interfaces injectées (stockage, réseau, horloge) : le module est testable sur JVM
 * sans Android (voir {@code docs/conception.md}, section 4).</p>
 */
package jo.forge.setup;
