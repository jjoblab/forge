package jo.forge.engine.deps;

/**
 * Observateur des téléchargements de la résolution Maven : chaque transfert réseau
 * est visible au fil de l'eau (début, avancement, fin) — la résolution d'un projet
 * Compose, c'est des centaines de requêtes séquentielles, et un silence de plusieurs
 * minutes sur un appareil se lit comme un plantage.
 *
 * <p>Parité assumée avec le moteur de référence : Gradle affiche chaque téléchargement
 * (« Downloading https://… ») et, en console riche, une barre de progression par
 * fichier. Les événements émis ici alimentent le journal du build ET les événements
 * structurés vers la coquille ({@code DownloadEvent}, protocole 2).</p>
 *
 * <p>Contrat : appelé depuis le fil de la résolution (jamais multi-fil) ; aucune
 * méthode ne lève ; l'implémentation doit être rapide (rendu ou mise en file, pas
 * de travail).</p>
 */
public interface ProgressionTéléchargement {

    /**
     * Un téléchargement démarre.
     *
     * @param ressource     fichier court (ex. {@code ui-android-1.7.1.aar}).
     * @param totalAttendu  taille annoncée en octets, ou {@code -1} si inconnue.
     */
    default void début(String ressource, long totalAttendu) {
    }

    /**
     * Avancement d'un téléchargement en cours — émission bornée (au moins 128 Kio
     * entre deux appels) pour ne pas saturer le canal.
     *
     * @param lus   octets reçus à ce stade.
     * @param total taille totale annoncée, ou {@code -1} si inconnue.
     */
    default void avance(long lus, long total) {
    }

    /**
     * Un téléchargement est terminé (fichier posé dans le cache, somme vérifiée).
     *
     * @param ressource le fichier court.
     * @param octets    la taille finale en octets.
     */
    default void fin(String ressource, long octets) {
    }
}
