package jo.forge.engine.tasks;

import jo.forge.engine.diag.DiagnosticSink;

import java.nio.file.Path;

/**
 * Contexte d'exécution d'une tâche : journal, annulation, horloge et racines de
 * travail.
 *
 * <p>Le contexte est le seul canal autorisé vers l'extérieur de la tâche : pas
 * d'écriture console, pas d'accès direct aux événements. Les chemins sont résolus
 * contre la racine du projet fournie par la requête de build.</p>
 */
public interface TaskContext {

    /**
     * Racine du projet en construction.
     *
     * @return le chemin de la racine.
     */
    Path projectRoot();

    /**
     * Résout un chemin contre la racine du projet (absolu s'il l'est déjà).
     *
     * @param chemin chemin relatif ou absolu.
     * @return le chemin résolu.
     */
    default Path resolve(String chemin) {
        Path cheminPath = Path.of(chemin);
        return cheminPath.isAbsolute() ? cheminPath : projectRoot().resolve(cheminPath);
    }

    /**
     * Journal de la tâche (entrées tamponnées au nom de la tâche).
     *
     * @return le journal.
     */
    TaskLogger logger();

    /**
     * Puits de diagnostics structurés de la tâche (erreurs localisées, avertissements) —
     * distinct du journal texte : ces diagnostics sont exploitables par l'éditeur.
     *
     * @return le puits (puits vide par défaut).
     */
    default DiagnosticSink diagnostics() {
        return DiagnosticSink.NOOP;
    }

    /**
     * Jeton d'annulation du build.
     *
     * @return le jeton (à vérifier aux points sûrs).
     */
    CancellationFlag cancellation();

    /**
     * Vérifie l'annulation : lève {@link TaskCancelledException} si demandée.
     */
    default void checkCancelled() {
        cancellation().check();
    }

    /**
     * Observateur des téléchargements de la tâche : chaque transfert réseau visible
     * devient un événement structuré vers la coquille (progression en direct, plus un
     * silence jamais long). L'implémentation par défaut ignore — les tâches sans
     * réseau n'ont rien à rapporter.
     *
     * @return le consommateur d'événements de téléchargement (jamais nul).
     */
    default java.util.function.Consumer<jo.forge.api.BuildEvent.DownloadEvent>
    téléchargements() {
        return événement -> {
        };
    }

    /**
     * Identifiant du build en cours (pour horodater les événements de la tâche).
     *
     * @return l'identifiant (chaîne vide par défaut — exécutions hors build).
     */
    default String buildId() {
        return "";
    }

    /**
     * Marque la tâche comme exécutée DEPUIS UN CACHE : elle a produit ses sorties en
     * relisant un résultat mémorisé, sans recalculer (l'état rapporté à la coquille
     * sera {@code FROM_CACHE} au lieu de {@code SUCCEEDED} — parité avec le décompte
     * « from-cache » du moteur de référence).
     */
    default void marquerDepuisCache() {
    }

    /**
     * Pose le dernier message de progression de la tâche — il nourrit la pulsation
     * de l'exécuteur (décision D-17) : une tâche longue qui progresse par étapes
     * mesurables (ex. « dexage : 12/41 bibliothèques ») ne laisse plus un silence
     * opaque entre deux lignes de journal. Dernier appel gagnant ; chaîne vide pour
     * effacer. Implémentation par défaut : ignoré (contextes de test hors exécuteur).
     *
     * @param message le message de progression (jamais null).
     */
    default void progression(String message) {
    }

    /**
     * Le dernier message de progression posé par la tâche (chaîne vide si aucun).
     *
     * @return le message (jamais null).
     */
    default String messageProgression() {
        return "";
    }
}
