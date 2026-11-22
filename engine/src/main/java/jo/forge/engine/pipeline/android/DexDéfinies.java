package jo.forge.engine.pipeline.android;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Lecture des classes DÉFINIES par un fichier {@code .dex} — les descripteurs JVM
 * ({@code Ldemo/app/MainActivity;}) de la table {@code class_defs}.
 *
 * <p>Pourquoi (décision D-19) : le dexage final par bibliothèque produit des
 * {@code classes.dex} indépendants, sans lien entre nom de fichier et classe —
 * l'exactitude se vérifie donc sur les classes réellement DÉFINIES par les dex
 * produits, comme le faisait la présence de {@code <classe>.dex} du mode
 * intermédiaire (un dexeur peut « réussir » en silenciant des classes — leçon du
 * moteur de référence).</p>
 *
 * <p>Analyse minimale : lecture unique en mémoire (un dex de bibliothèque se compte
 * en mégaoctets), en-tête du format Dex (035 à 039 — la disposition des champs lus
 * est commune à toutes), tables {@code string_ids}, {@code type_ids} et
 * {@code class_defs}. Toute incohérence (magic absent, compte ou décalage hors
 * bornes) échoue NOMMÉMENT — jamais de lecture silencieusement vide.</p>
 */
final class DexDéfinies {

    /** Taille minimale d'un en-tête dex (112 octets — toutes versions). */
    private static final int TAILLE_EN_TÊTE = 112;

    /** Taille d'une entrée {@code class_def_item}. */
    private static final int TAILLE_CLASS_DEF = 32;

    private DexDéfinies() {
        // Utilitaire statique : pas d'instanciation.
    }

    /**
     * Les descripteurs des classes définies par un dex.
     *
     * @param dex le fichier {@code .dex}.
     * @return les descripteurs ({@code Lpaquet/Classe;}), dans l'ordre de la table.
     * @throws IOException si le fichier n'est pas un dex lisible et cohérent.
     */
    static List<String> lire(Path dex) throws IOException {
        byte[] octets = Files.readAllBytes(dex);
        if (octets.length < TAILLE_EN_TÊTE) {
            throw new IOException("dex trop court pour un en-tête : " + dex.getFileName());
        }
        if (octets[0] != 'd' || octets[1] != 'e' || octets[2] != 'x' || octets[3] != '\n') {
            throw new IOException("magic dex absent : " + dex.getFileName());
        }
        int nbChaînes = u4(octets, 56);
        int débChaînes = u4(octets, 60);
        int nbTypes = u4(octets, 64);
        int débTypes = u4(octets, 68);
        int nbClasses = u4(octets, 96);
        int débClasses = u4(octets, 100);
        if (nbClasses < 0 || nbClasses > 200_000) {
            throw new IOException("compte de class_defs incohérent (" + nbClasses
                    + ") : " + dex.getFileName());
        }
        List<String> descripteurs = new ArrayList<>(nbClasses);
        for (int i = 0; i < nbClasses; i++) {
            int idxClasse = u4Borné(octets, débClasses + TAILLE_CLASS_DEF * i,
                    TAILLE_EN_TÊTE, "class_def " + i + " de " + dex.getFileName());
            if (idxClasse < 0 || idxClasse >= nbTypes) {
                throw new IOException("class_idx hors table des types (" + idxClasse
                        + "/" + nbTypes + ") : " + dex.getFileName());
            }
            int idxDescripteur = u4Borné(octets, débTypes + 4L * idxClasse,
                    TAILLE_EN_TÊTE, "type_id de " + dex.getFileName());
            if (idxDescripteur < 0 || idxDescripteur >= nbChaînes) {
                throw new IOException("descriptor_idx hors table des chaînes ("
                        + idxDescripteur + "/" + nbChaînes + ") : " + dex.getFileName());
            }
            int donnéesChaîne = u4Borné(octets, débChaînes + 4L * idxDescripteur,
                    TAILLE_EN_TÊTE, "string_id de " + dex.getFileName());
            descripteurs.add(chaîneÀ(octets, donnéesChaîne, dex.getFileName().toString()));
        }
        return descripteurs;
    }

    /**
     * Chaîne MUTF-8 à un {@code string_data_off} : uleb128 de la longueur utf16
     * (ignorée), puis les octets jusqu'au NUL final. Les descripteurs de classes
     * sont ASCII — le décodage UTF-8 suffit (l'écart MUTF-8 ne touche ni l'ASCII,
     * ni le plan BMP usuel).
     */
    private static String chaîneÀ(byte[] octets, int position, String source)
            throws IOException {
        if (position < 0 || position >= octets.length) {
            throw new IOException("string_data_off hors bornes (" + position
                    + ") : " + source);
        }
        int curseur = position;
        while ((octets[curseur] & 0x80) != 0) {
            curseur++;
            if (curseur >= octets.length) {
                throw new IOException("uleb128 de longueur de chaîne tronqué : " + source);
            }
        }
        curseur++; // Dernier octet de la longueur : les octets de la chaîne suivent.
        int début = curseur;
        while (curseur < octets.length && octets[curseur] != 0) {
            curseur++;
        }
        if (curseur >= octets.length) {
            throw new IOException("chaîne MUTF-8 non terminée : " + source);
        }
        return new String(octets, début, curseur - début,
                java.nio.charset.StandardCharsets.UTF_8);
    }

    /** u4 non signé à une position (petit-boutiste). */
    private static int u4(byte[] octets, int position) {
        return (octets[position] & 0xFF)
                | (octets[position + 1] & 0xFF) << 8
                | (octets[position + 2] & 0xFF) << 16
                | (octets[position + 3] & 0xFF) << 24;
    }

    /** u4 lu et contrôlé : position et cible doivent rester dans le fichier. */
    private static int u4Borné(byte[] octets, long position, int minimal, String contexte)
            throws IOException {
        if (position < minimal || position + 4 > octets.length) {
            throw new IOException("décalage de table hors bornes (" + position
                    + ", fichier " + octets.length + " octets) : " + contexte);
        }
        return u4(octets, (int) position);
    }
}
