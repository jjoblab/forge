/**
 * Contrat partagé entre la coquille et le moteur de build.
 *
 * <p>Ce module est la seule chose que la coquille connaît du moteur : les versions de
 * protocole, le codage des trames échangées sur les flux standards de la machine virtuelle
 * enfant, et les messages (requêtes, événements, diagnostics structurés). Aucun type interne
 * du moteur ne doit apparaître ici — c'est un invariant d'architecture
 * (voir {@code docs/conception.md}, décision D-07).</p>
 *
 * <p>Règles du module : zéro dépendance externe, pur Java (testable sur JVM et exécutable
 * sur ART), identifiants en anglais, javadoc et textes en français.</p>
 */
package jo.forge.api;
