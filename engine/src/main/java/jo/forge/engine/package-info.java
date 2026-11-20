/**
 * Moteur de build embarqué : s'exécute dans une machine virtuelle enfant, hors du
 * processus de la coquille.
 *
 * <p>Sous-packages : {@code bootstrap} (point d'entrée {@code EngineMain} de la VM),
 * modèle de projet déclaratif, graphe de tâches et ordonnancement, exécuteur parallèle
 * borné, empreintes et magasin d'état, pipelines de compilation (Java, Android) et
 * résolution de dépendances. Le moteur ne dépend d'aucune API Android : il reçoit ses
 * paramètres (répertoires, mémoire, ABI) par la coquille et rend compte par les trames
 * du module {@code api} (voir {@code docs/conception.md}).</p>
 */
package jo.forge.engine;
