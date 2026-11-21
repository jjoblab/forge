package jo.forge.setup.packaging.fixtures;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Fixture du garde-fou StAX (correctif v0.2.14) : référence les trois classes
 * {@code javax.xml.stream} du crash appareil v0.2.13 ({@code XMLStreamException}
 * dans {@code PluginDescriptorLoader}) — compilée par javac, elle alimente le
 * jar dont {@code PackagerComposants.vérifierRéférencesStax} lit les pools de
 * constantes.
 */
public final class ClasseQuiRéférenceStax {

    private ClasseQuiRéférenceStax() {
    }

    /**
     * Méthode fausse mais référençante : l'usine, l'exception levée et le
     * lecteur peuplent le pool de constantes (le type de retour seul n'y
     * crée PAS d'entrée Class — l'instanceof, si).
     *
     * @param source ignoré.
     * @return un lecteur jamais créé (méthode jamais appelée).
     * @throws XMLStreamException déclarée pour la référence.
     */
    public static XMLStreamReader lire(Object source) throws XMLStreamException {
        if (source instanceof XMLStreamReader lecteur) {
            return lecteur;
        }
        XMLInputFactory usine = XMLInputFactory.newInstance();
        return usine.createXMLStreamReader(new java.io.StringReader("<a/>"));
    }
}
