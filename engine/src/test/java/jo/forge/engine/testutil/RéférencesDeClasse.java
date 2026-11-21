package jo.forge.engine.testutil;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;

/**
 * Lecteur des références de classes d'un fichier {@code .class} : parcourt le
 * pool de constantes et collecte les noms internes des entrées
 * {@code CONSTANT_Class} (super-types, signatures, {@code new},
 * {@code instanceof}, fabriques d'invocation dynamique…).
 *
 * <p>Même lecture que l'assemblage des composants ({@code AnalyseurReferences}
 * du module setup, correctif v0.2.6) — reproduite ici parce que le moteur ne
 * dépend pas du setup et que le format du pool de constantes est stable depuis
 * Java 1.0 : aucune bibliothèque d'analyse de bytecode n'est nécessaire. Les
 * types tableaux ({@code [Ljava/lang/String;}) sont réduits à leur type
 * d'élément, les primitifs ignorés.</p>
 *
 * <p>Depuis la v0.2.17, {@link #lireChaînes} expose aussi les CHAÎNES du pool
 * ({@code CONSTANT_Utf8}) — la garde structurelle XML/ART
 * ({@code SurfaceXmlArtTest}) y traque les URI de fonctionnalités Xerces posées
 * hors de {@code DomSûr}.</p>
 */
public final class RéférencesDeClasse {

    private RéférencesDeClasse() {
    }

    /** Un pool lu : références de classes et chaînes, triées. */
    private record PoolLu(Set<String> références, Set<String> chaînes) {
    }

    /**
     * Collecte les noms internes des classes référencées par un fichier de
     * classe.
     *
     * @param flux les octets du fichier {@code .class}.
     * @return noms internes normalisés, triés.
     * @throws IOException si le format est inattendu.
     */
    public static Set<String> lire(InputStream flux) throws IOException {
        return lirePool(flux).références();
    }

    /**
     * Collecte les chaînes ({@code CONSTANT_Utf8}) du pool de constantes d'un
     * fichier de classe : noms, descripteurs… et littéraux de code (les URI de
     * fonctionnalités XML, par exemple).
     *
     * @param flux les octets du fichier {@code .class}.
     * @return chaînes distinctes, triées.
     * @throws IOException si le format est inattendu.
     */
    public static Set<String> lireChaînes(InputStream flux) throws IOException {
        return lirePool(flux).chaînes();
    }

    /**
     * Lit le pool de constantes d'un fichier de classe : chaque entrée est
     * étiquetée, les long/double en occupent deux.
     *
     * @param flux les octets du fichier {@code .class}.
     * @return le pool réduit (références de classes, chaînes).
     * @throws IOException si le format est inattendu.
     */
    private static PoolLu lirePool(InputStream flux) throws IOException {
        DataInputStream entrée = new DataInputStream(flux);
        if (entrée.readInt() != 0xCAFEBABE) {
            throw new IOException("n'est pas un fichier de classe (magie absente)");
        }
        entrée.skipBytes(4); // versions mineure et majeure
        int nombre = entrée.readUnsignedShort();
        // Pool 1-indexé : les entrées long/double en occupent deux.
        String[] chaînes = new String[nombre];
        int[] classes = new int[nombre];
        Set<String> toutesChaînes = new TreeSet<>();
        for (int index = 1; index < nombre; index++) {
            int étiquette = entrée.readUnsignedByte();
            switch (étiquette) {
                case 1 -> { // Utf8
                    int longueur = entrée.readUnsignedShort();
                    byte[] octets = new byte[longueur];
                    entrée.readFully(octets);
                    chaînes[index] = new String(octets, StandardCharsets.UTF_8);
                    toutesChaînes.add(chaînes[index]);
                }
                case 7 -> classes[index] = entrée.readUnsignedShort(); // Class
                case 3, 4 -> entrée.skipBytes(4);   // Integer, Float
                case 5, 6 -> {                      // Long, Double : deux emplacements
                    entrée.skipBytes(8);
                    index++;
                }
                case 8, 16, 19, 20 -> entrée.skipBytes(2); // String, MethodType, Module, Package
                case 9, 10, 11, 12, 17, 18 -> entrée.skipBytes(4); // refs, NameAndType, Dynamic
                case 15 -> entrée.skipBytes(3);    // MethodHandle
                default -> throw new IOException(
                        "étiquette de pool inconnue : " + étiquette);
            }
        }
        Set<String> références = new TreeSet<>();
        for (int index = 1; index < nombre; index++) {
            if (classes[index] > 0 && classes[index] < nombre
                    && chaînes[classes[index]] != null) {
                String nom = normaliser(chaînes[classes[index]]);
                if (nom != null) {
                    références.add(nom);
                }
            }
        }
        return new PoolLu(références, toutesChaînes);
    }

    /**
     * Normalise un nom référencé : les types tableaux ({@code [Lpkg/Classe;},
     * {@code [[L…}) sont réduits à leur type d'élément ; les types primitifs
     * ({@code I}, {@code V}…) sont ignorés.
     */
    private static String normaliser(String nom) {
        while (nom.startsWith("[")) {
            nom = nom.substring(1);
        }
        if (nom.startsWith("L") && nom.endsWith(";")) {
            return nom.substring(1, nom.length() - 1);
        }
        return nom.contains("/") ? nom : null;
    }
}
