package jo.forge.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Journal borné des diagnostics du moteur : les lignes que le processus moteur écrit
 * sur stderr (traces d'écroulement de la VM, exceptions non attrapées, messages
 * internes du serveur).
 *
 * <p>Pourquoi vider stderr dans un tampon borné : (1) un tube non lu se remplit
 * (quelques dizaines de Ko) et finit par BLOQUER le moteur qui écrit dedans ;
 * (2) c'est la seule trace qui survit à une mort brutale du processus (signal du
 * noyau, mémoire saturée) : le journal de build, lui, vit dans le moteur et
 * disparaît avec lui.</p>
 *
 * <p>Thread-sûreté : écrit par le fil de drainage de stderr, lu par l'interface ou
 * les tests — tout accès passe par des méthodes synchronisées.</p>
 */
public final class JournalMoteur {

    /** Capacité par défaut (lignes conservées). */
    public static final int CAPACITÉ_PAR_DÉFAUT = 500;

    private final int capacité;
    private final Deque<String> lignes = new ArrayDeque<>();

    /**
     * Crée le journal borné.
     *
     * @param capacité nombre maximal de lignes conservées (minimum 1).
     */
    public JournalMoteur(int capacité) {
        this.capacité = Math.max(1, capacité);
    }

    /**
     * Ajoute une ligne (la plus ancienne sort si la capacité est atteinte).
     *
     * @param ligne la ligne lue sur stderr du moteur.
     */
    public synchronized void ajouter(String ligne) {
        if (lignes.size() >= capacité) {
            lignes.pollFirst();
        }
        lignes.addLast(ligne);
    }

    /**
     * Copie des lignes conservées, de la plus ancienne à la plus récente.
     *
     * @return la copie (jamais la file interne).
     */
    public synchronized List<String> copie() {
        return new ArrayList<>(lignes);
    }

    /**
     * Les n dernières lignes conservées (moins s'il n'y en a pas assez), de la plus
     * ancienne à la plus récente — pour un affichage concis à la mort du moteur.
     *
     * @param n nombre de lignes demandé (minimum 1).
     * @return au plus n lignes, éventuellement vide.
     */
    public synchronized List<String> dernières(int n) {
        int voulu = Math.max(1, n);
        int taille = lignes.size();
        if (taille <= voulu) {
            return new ArrayList<>(lignes);
        }
        return new ArrayList<>(lignes.stream()
                .skip(taille - voulu)
                .toList());
    }

    /**
     * Nombre de lignes conservées.
     *
     * @return le nombre courant.
     */
    public synchronized int taille() {
        return lignes.size();
    }
}
