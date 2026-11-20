package jo.forge.engine.server;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Journal circulaire borné d'un build (correctif du constat P-7 : la référence
 * conserve des journaux sans borne).
 *
 * <p>Tout le flux part en temps réel sur le canal ; ce tampon ne garde que les
 * dernières entrées pour la trace exportable et le diagnostic après coup. Les plus
 * anciennes sortent silencieusement : la mémoire reste bornée, quel que soit le
 * build.</p>
 */
public final class JournalBorné {

    /** Capacité par défaut (entrées). */
    public static final int CAPACITÉ_PAR_DÉFAUT = 500;

    private final int capacité;
    private final Deque<String> entrées = new ArrayDeque<>();

    /**
     * Crée le journal borné.
     *
     * @param capacité nombre maximal d'entrées conservées (minimum 1).
     */
    public JournalBorné(int capacité) {
        this.capacité = Math.max(1, capacité);
    }

    /**
     * Ajoute une entrée (la plus ancienne sort si la capacité est atteinte).
     *
     * @param entrée la ligne de journal (déjà rédigée des secrets).
     */
    public synchronized void ajouter(String entrée) {
        if (entrées.size() >= capacité) {
            entrées.pollFirst();
        }
        entrées.addLast(entrée);
    }

    /**
     * Copie des entrées conservées, de la plus ancienne à la plus récente.
     *
     * @return la copie (jamais la file interne).
     */
    public synchronized List<String> copie() {
        return new ArrayList<>(entrées);
    }

    /**
     * Nombre d'entrées conservées.
     *
     * @return le nombre courant.
     */
    public synchronized int taille() {
        return entrées.size();
    }
}
