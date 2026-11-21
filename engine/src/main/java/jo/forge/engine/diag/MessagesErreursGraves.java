package jo.forge.engine.diag;

/**
 * Messages honnêtes pour les erreurs graves ({@code java.lang.Error}) des tâches
 * et du moteur — correctif v0.2.6.
 *
 * <p>Constat à l'origine : le crash d'ECJ sur ART
 * ({@code Failed resolution of: Ljavax/lang/model/SourceVersion;}) était affiché
 * « probablement un manque de mémoire : réessayez en mode basse mémoire » — un
 * {@link LinkageError} n'est pas un manque de mémoire, et le mauvais conseil
 * envoyait l'utilisateur sur une fausse piste. La mémoire n'est qu'UNE cause
 * possible d'erreur grave ; chaque famille a désormais son message et son
 * conseil, sans spéculation.</p>
 */
public final class MessagesErreursGraves {

    private MessagesErreursGraves() {
    }

    /**
     * Rédige le message d'une erreur grave pour le contexte donné (tâche ou
     * build), classée par famille réelle.
     *
     * @param erreur   l'erreur à décrire.
     * @param contexte où elle s'est produite (ex. {@code core:compileJava}).
     * @return le message honnête, avec le conseil adapté à la famille.
     */
    public static String pour(Throwable erreur, String contexte) {
        String cause = erreur.getMessage() == null
                ? erreur.getClass().getSimpleName() : erreur.getMessage();
        String préambule = "Erreur grave dans " + contexte + " (" + cause + ")";
        if (erreur instanceof OutOfMemoryError) {
            return préambule + " — mémoire insuffisante :"
                    + " réessayez en mode basse mémoire";
        }
        if (erreur instanceof LinkageError) {
            // NoClassDefFoundError, NoSuchMethodError, VerifyError… : une classe
            // ou un membre manque sur le classpath du moteur. Deux familles
            // réelles : un COMPOSANT incomplet (téléchargement interrompu —
            // l'assistant répare), ou une API de BUREAU référencée par le code du
            // moteur qu'ART ne porte pas (v0.2.13 : javax.xml.stream/StAX ;
            // v0.2.15 : java.lang.ProcessHandle dans l'explosion d'AAR) — ce
            // n'est PAS un problème de mémoire, et le rapport de diagnostic dit
            // laquelle des deux familles par le nom de la classe manquante.
            return préambule + " — classe ou membre manquant sur le classpath"
                    + " du moteur : composant probablement incomplet (réparation"
                    + " par l'assistant d'installation) ou API de bureau absente"
                    + " d'ART — signalez le problème avec le rapport de diagnostic";
        }
        if (erreur instanceof StackOverflowError) {
            return préambule + " — pile d'exécution épuisée (récursion),"
                    + " signalez le problème avec le rapport de diagnostic";
        }
        return préambule;
    }
}
