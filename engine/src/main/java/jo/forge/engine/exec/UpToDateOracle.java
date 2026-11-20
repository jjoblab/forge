package jo.forge.engine.exec;

import jo.forge.engine.tasks.Task;

/**
 * Oracle d'up-to-date : décide si une tâche doit réellement s'exécuter.
 *
 * <p>Implémentation par défaut : jamais à jour (tout s'exécute). L'implémentation à
 * empreintes (magasin persistant, décision D-12) arrive avec l'étape 7 de la Phase 4
 * et remplace la valeur par défaut au câblage du serveur de build.</p>
 */
public interface UpToDateOracle {

    /** Oracle qui exécute toujours tout (par défaut). */
    UpToDateOracle ALWAYS_RUN = new UpToDateOracle() {
        @Override
        public boolean isUpToDate(Task task) {
            return false;
        }

        @Override
        public void recordSuccess(Task task) {
            // Rien à retenir.
        }
    };

    /**
     * Indique si la tâche est à jour (entrées inchangées depuis le dernier passage
     * réussi ET sorties toujours présentes).
     *
     * @param task la tâche examinée.
     * @return {@code true} si elle peut être sautée.
     */
    boolean isUpToDate(Task task);

    /**
     * Enregistre un passage réussi (empreintes des entrées, sorties déclarées).
     *
     * @param task la tâche réussie.
     */
    void recordSuccess(Task task);
}
