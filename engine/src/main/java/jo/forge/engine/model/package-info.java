/**
 * Modèle de projet déclaratif (décision D-11) : {@code forge.json} et ses types.
 *
 * <p>Le modèle est la seule description d'un projet que le moteur accepte : statique,
 * sans code à exécuter, transformé en DAG de tâches par les pipelines. L'import de
 * scripts Gradle (étape 13) produit ce format, il ne le contourne jamais.</p>
 */
package jo.forge.engine.model;
