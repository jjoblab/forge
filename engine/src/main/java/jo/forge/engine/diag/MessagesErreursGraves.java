package jo.forge.engine.diag;

/**
 * Messages honnêtes pour les erreurs graves ({@code java.lang.Error}) des tâches
 * et du moteur — correctif v0.2.6, causes racines dépliées en v0.2.19.
 *
 * <p>Constat à l'origine : le crash d'ECJ sur ART
 * ({@code Failed resolution of: Ljavax/lang/model/SourceVersion;}) était affiché
 * « probablement un manque de mémoire : réessayez en mode basse mémoire » — un
 * {@link LinkageError} n'est pas un manque de mémoire, et le mauvais conseil
 * envoyait l'utilisateur sur une fausse piste. La mémoire n'est qu'UNE cause
 * possible d'erreur grave ; chaque famille a désormais son message et son
 * conseil, sans spéculation.</p>
 *
 * <p><b>Causes racines dépliées</b> (v0.2.19, rapport appareil v0.2.18) : la
 * régression a tué {@code processResources} d'un {@code ExceptionInInitializerError}
 * dont le message était VIDE sur ART — l'exception portée dedans (une
 * {@code PatternSyntaxException} nommant le motif fautif et l'index) n'était
 * jamais montrée, et le rapport ne disait rien de plus que « signalez le
 * problème ». Désormais la CHAÎNE des causes est dépliée dans le message :
 * classe racine, message racine, et première trame du moteur — l'appareil
 * s'autodiagnostique au lieu d'exiger une reproduction côté bureau.</p>
 */
public final class MessagesErreursGraves {

    /** Plafond du message de la cause racine (un message d'ICU ou d'un parseur peut être long). */
    private static final int PLAFOND_MESSAGE = 300;

    private MessagesErreursGraves() {
    }

    /**
     * Rédige le message d'une erreur grave pour le contexte donné (tâche ou
     * build), classée par famille réelle, cause racine dépliée.
     *
     * @param erreur   l'erreur à décrire.
     * @param contexte où elle s'est produite (ex. {@code core:compileJava}).
     * @return le message honnête, avec le conseil adapté à la famille.
     */
    public static String pour(Throwable erreur, String contexte) {
        String cause = enTête(erreur);
        String préambule = "Erreur grave dans " + contexte + " (" + cause + ")";
        String racine = chaîneCauses(erreur);
        if (erreur instanceof OutOfMemoryError) {
            return préambule + racine + " — mémoire insuffisante :"
                    + " réessayez en mode basse mémoire";
        }
        if (erreur instanceof LinkageError) {
            // NoClassDefFoundError, ExceptionInInitializerError,
            // NoSuchMethodError, VerifyError… : une classe ou un membre manque
            // — ou un INITIALISEUR STATIQUE a levé — sur le classpath du
            // moteur. Deux familles réelles : un COMPOSANT incomplet
            // (téléchargement interrompu — l'assistant répare), ou une API ou
            // une SÉMANTIQUE de bureau divergente qu'ART ne porte pas (v0.2.13
            // : javax.xml.stream/StAX ; v0.2.15 : java.lang.ProcessHandle dans
            // l'explosion d'AAR ; v0.2.18 : regex compilée par ICU qui refuse
            // l'accolade orpheline qu'OpenJDK tolère). Ce n'est PAS un problème
            // de mémoire ; la cause racine dépliée dit laquelle.
            return préambule + racine + " — classe ou membre manquant sur le"
                    + " classpath du moteur (ou initialiseur statique en échec)"
                    + " : composant probablement incomplet (réparation par"
                    + " l'assistant d'installation) ou API de bureau absente"
                    + " d'ART — signalez le problème avec le rapport de diagnostic";
        }
        if (erreur instanceof StackOverflowError) {
            return préambule + racine + " — pile d'exécution épuisée (récursion),"
                    + " signalez le problème avec le rapport de diagnostic";
        }
        return préambule + racine;
    }

    /**
     * L'en-tête entre parenthèses : le message de l'erreur s'il existe, son nom
     * de classe sinon (sur ART, un {@code ExceptionInInitializerError} n'a PAS
     * de message — c'était tout ce que le rapport v0.2.18 montrait).
     */
    private static String enTête(Throwable erreur) {
        return erreur.getMessage() == null
                ? erreur.getClass().getSimpleName() : erreur.getMessage();
    }

    /**
     * Déplie la chaîne des causes ({@code getCause()}, et
     * {@code getException()} d'un {@code ExceptionInInitializerError}) jusqu'à
     * la racine : « — cause racine Classe : message — levée à Trame ».
     *
     * <p>La trame retenue est la première du moteur ({@code jo.forge.}) dans
     * la pile de la racine — le point du NÔTRE qui a déclenché, pas un
     * interne de plate-forme. Vide si l'erreur n'a pas de cause (le message
     * reste alors exactement celui d'avant v0.2.19).</p>
     */
    private static String chaîneCauses(Throwable erreur) {
        Throwable racine = erreur.getCause();
        if (racine == null && erreur instanceof ExceptionInInitializerError) {
            // Le porteur historique de l'exception d'initialisation (souvent
            // absent de getCause() sur ART).
            racine = ((ExceptionInInitializerError) erreur).getException();
        }
        while (racine != null && racine.getCause() != null) {
            racine = racine.getCause(); // Jusqu'à la RACINE, pas au premier étage.
        }
        if (racine == null) {
            return "";
        }
        StringBuilder texte = new StringBuilder(" — cause racine ")
                .append(racine.getClass().getName());
        if (racine.getMessage() != null) {
            String message = racine.getMessage().strip();
            if (message.length() > PLAFOND_MESSAGE) {
                message = message.substring(0, PLAFOND_MESSAGE) + "…";
            }
            texte.append(" : ").append(message.replace('\n', ' '));
        }
        String trame = trameMoteur(racine);
        if (trame != null) {
            texte.append(" — levée à ").append(trame);
        }
        return texte.toString();
    }

    /** La première trame du moteur dans la pile de la cause racine. */
    private static String trameMoteur(Throwable racine) {
        StackTraceElement[] trames = racine.getStackTrace();
        if (trames == null) {
            return null;
        }
        for (StackTraceElement trame : trames) {
            if (trame.getClassName().startsWith("jo.forge.")) {
                return trame.toString();
            }
        }
        return null;
    }
}
