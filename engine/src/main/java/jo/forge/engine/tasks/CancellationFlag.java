package jo.forge.engine.tasks;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Jeton d'annulation du moteur : posé par le serveur à la réception d'un
 * {@code CANCEL_BUILD}, testé par les tâches à leurs points sûrs.
 *
 * <p>L'annulation est coopérative <em>et</em> impérative (décision D-08, correctif du
 * constat C-1) : les threads de tâches s'enregistrent ici ; {@link #cancel()} pose le
 * drapeau <em>et interrompt</em> chaque thread enregistré — une tâche endormie ou
 * bloquée en E/S longue est réveillée immédiatement, pas seulement au prochain point
 * sûr. Les sous-processus, eux, sont détruits par le registre de l'exécuteur.</p>
 */
public final class CancellationFlag {

    private volatile boolean annule;
    private final Set<Thread> threadsInscrites = ConcurrentHashMap.newKeySet();

    /**
     * Demande l'annulation de tout le build en cours : drapeau posé et interruption
     * immédiate de toutes les tâches enregistrées.
     */
    public void cancel() {
        annule = true;
        for (Thread thread : threadsInscrites) {
            thread.interrupt();
        }
    }

    /**
     * Enregistre le thread courant comme exécutant une tâche annulable. Si l'annulation
     * est déjà posée, le thread est interrompu aussitôt.
     */
    public void registerCurrent() {
        threadsInscrites.add(Thread.currentThread());
        if (annule) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Désenregistre le thread courant (fin de tâche, quels que soient le résultat).
     */
    public void unregisterCurrent() {
        threadsInscrites.remove(Thread.currentThread());
        // Effacer le statut d'interruption résiduel pour ne pas empoisonner le pool.
        Thread.interrupted();
    }

    /**
     * Indique si l'annulation a été demandée.
     *
     * @return {@code true} si annulé.
     */
    public boolean isCancelled() {
        return annule;
    }

    /**
     * Lève {@link TaskCancelledException} si l'annulation a été demandée.
     *
     * @throws TaskCancelledException si annulé.
     */
    public void check() {
        if (annule) {
            throw new TaskCancelledException("annulation demandée");
        }
    }
}
