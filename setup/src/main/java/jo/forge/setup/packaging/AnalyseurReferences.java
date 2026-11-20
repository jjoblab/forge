package jo.forge.setup.packaging;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Lecteur des références de classes d'un jar : parcourt le pool de constantes de
 * chaque fichier {@code .class} et collecte les noms internes des entrées
 * {@code CONSTANT_Class} (super-types, signatures, {@code new}, {@code instanceof}…).
 *
 * <p>Utilisé par l'assemblage du composant {@code java-compiler} (correctif
 * v0.2.6) pour VÉRIFIER que toute classe de l'API {@code java.compiler} référencée
 * par ECJ est bien servie par le jar d'API embarqué — l'assemblage échoue
 * nommément sinon. C'est le garde-fou qui transforme « un ECJ futur référence une
 * classe de plus » en échec lisible de la chaîne de publication, plutôt qu'en
 * {@code NoClassDefFoundError} sur l'appareil de l'utilisateur.</p>
 *
 * <p>Les noms sont normalisés : les types tableaux ({@code [Ljava/lang/String;})
 * sont réduits à leur type d'élément. Ne lit AUCUNE bibliothèque d'analyse
 * bytecode — le format du pool de constantes est stable depuis Java 1.0 et se
 * lit en une passe.</p>
 */
final class AnalyseurReferences {

    private AnalyseurReferences() {
    }

    /**
     * Collecte les noms internes de toutes les classes référencées par les
     * fichiers {@code .class} du jar.
     *
     * @param jar le jar à analyser.
     * @return noms internes normalisés, triés.
     * @throws IOException si le jar ou un fichier de classe est illisible.
     */
    static Set<String> classesRéférencées(Path jar) throws IOException {
        Set<String> références = new TreeSet<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (ZipEntry entrée : zip.stream()
                    .filter(e -> !e.isDirectory())
                    .filter(e -> e.getName().endsWith(".class"))
                    .sorted(java.util.Comparator.comparing(ZipEntry::getName))
                    .toList()) {
                références.addAll(classesRéférencées(zip.getInputStream(entrée)));
            }
        }
        return références;
    }

    /**
     * Collecte les références d'un unique fichier de classe.
     *
     * @param flux les octets du fichier {@code .class}.
     * @return noms internes normalisés.
     * @throws IOException si le format est inattendu.
     */
    static Set<String> classesRéférencées(InputStream flux) throws IOException {
        DataInputStream entrée = new DataInputStream(flux);
        if (entrée.readInt() != 0xCAFEBABE) {
            throw new IOException("n'est pas un fichier de classe (magie absente)");
        }
        entrée.skipBytes(4); // versions mineure et majeure
        int nombre = entrée.readUnsignedShort();
        // Pool 1-indexé : les entrées long/double en occupent deux.
        String[] chaînes = new String[nombre];
        int[] classes = new int[nombre];
        for (int index = 1; index < nombre; index++) {
            int étiquette = entrée.readUnsignedByte();
            switch (étiquette) {
                case 1 -> { // Utf8
                    int longueur = entrée.readUnsignedShort();
                    byte[] octets = new byte[longueur];
                    entrée.readFully(octets);
                    chaînes[index] = new String(octets, java.nio.charset.StandardCharsets.UTF_8);
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
                default -> throw new IOException("étiquette de pool inconnue : " + étiquette);
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
        return références;
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
