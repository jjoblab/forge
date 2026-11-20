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
            // ou un membre manque sur le classpath du moteur — le composant est
            // probablement incomplet, ce n'est PAS un problème de mémoire.
            return préambule + " — classe ou membre manquant sur le classpath"
                    + " du moteur : composant probablement incomplet,"
                    + " signalez le problème avec le rapport de diagnostic";
        }
        if (erreur instanceof StackOverflowError) {
            return préambule + " — pile d'exécution épuisée (récursion),"
                    + " signalez le problème avec le rapport de diagnostic";
        }
        return préambule;
    }
}
