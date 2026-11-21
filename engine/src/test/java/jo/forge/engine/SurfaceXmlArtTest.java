package jo.forge.engine;

import jo.forge.engine.testutil.RéférencesDeClasse;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Garde structurelle XML/ART du composant engine-core (correctif v0.2.17).
 *
 * <p><b>Constat à l'origine</b> (rapport appareil v0.2.16, application-compose) :
 * {@code app:processResources} est mort de « manifeste illisible :
 * http://apache.org/xml/features/disallow-doctype-decl » — la fusion de
 * manifestes posait un garde-fou Xerces EN DUR, et le parseur DOM d'ART (KXml)
 * lève {@code ParserConfigurationException(URI)} sur les fonctionnalités qu'il
 * ne connaît pas. RÉCIDIVE exacte du crash POM de v0.2.9 (correctif v0.2.10) :
 * le piège est invisible des tests JVM (Xerces reconnaît tout) ET de l'audit
 * de classes ({@link SurfaceArtTest}) — {@code DocumentBuilderFactory} existe
 * bien sur ART, c'est son COMPORTEMENT qui tue. D'où cette garde sur les
 * POOLS DE CONSTANTES (références de classes et chaînes) des quatre modules
 * du composant engine-core :</p>
 * <ul>
 *   <li>seul {@code DomSûr} peut référencer les FABRIQUES DOM/SAX
 *       ({@code DocumentBuilderFactory}, {@code SAXParserFactory}) ou porter
 *       les URI de FONCTIONNALITÉS Xerces/SAX — tout nouveau site d'analyse
 *       XML passe par lui (garde-fous au mieux, DOCTYPE refusé par scan) ;</li>
 *   <li>PERSONNE ne peut référencer {@code javax.xml.transform} : le
 *       Transformer n'est pas fiable sur ART, le moteur de référence écrit son
 *       manifeste fusionné à la main pour cette raison (REF-163) — la fusion
 *       de manifestes a son écrivain maison.</li>
 * </ul>
 *
 * <p><b>Portée honnête</b> : la garde voit le code du moteur, pas celui des
 * composants dexés (ECJ, d8, apksig, compilateur Kotlin — servis au classpath,
 * jamais compilés contre lui).</p>
 */
class SurfaceXmlArtTest {

    /** Les quatre modules du composant engine-core, avec leur classe-marqueur. */
    private static final List<Unité> UNITÉS = List.of(
            new Unité("engine", EngineVersion.class),
            new Unité("api", jo.forge.api.Frame.class),
            new Unité("json", jo.forge.json.Json.class),
            new Unité("client", jo.forge.client.EngineClient.class));

    private record Unité(String nom, Class<?> marqueur) {
    }

    /** La seule classe autorisée à toucher les fabriques XML et leurs fonctionnalités. */
    private static final String DOM_SÛR = "jo/forge/engine/util/DomSûr.class";

    /** Les fabriques DOM/SAX : leur setFeature est le piège ART. */
    private static final Set<String> FABRIQUES = Set.of(
            "javax/xml/parsers/DocumentBuilderFactory",
            "javax/xml/parsers/SAXParserFactory");

    @Test
    void seulDomSûrToucheAuxFabriquesEtFonctionnalitésXML() throws IOException {
        Map<String, Set<String>> contrevenants = new TreeMap<>();
        for (Unité unité : UNITÉS) {
            Path emplacement = emplacement(unité);
            if (Files.isDirectory(emplacement)) {
                auditerRépertoire(unité, emplacement, contrevenants);
            } else {
                auditerJar(unité, emplacement, contrevenants);
            }
        }
        assertTrue(contrevenants.isEmpty(), () -> message(contrevenants));
    }

    /** Audit d'une racine de classes compilées (exécution Gradle usuelle). */
    private static void auditerRépertoire(Unité unité, Path racine,
                                          Map<String, Set<String>> contrevenants)
            throws IOException {
        try (var parcours = Files.walk(racine)) {
            for (Path classe : parcours
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".class"))
                    .sorted().toList()) {
                auditerFichier(unité, racine.relativize(classe).toString(),
                        Files.readAllBytes(classe), contrevenants);
            }
        }
    }

    /** Audit d'un jar de classes (exécution depuis un artefact empaqueté). */
    private static void auditerJar(Unité unité, Path jar,
                                   Map<String, Set<String>> contrevenants)
            throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (ZipEntry entrée : zip.stream()
                    .filter(e -> !e.isDirectory())
                    .filter(e -> e.getName().endsWith(".class"))
                    .sorted(java.util.Comparator.comparing(ZipEntry::getName))
                    .toList()) {
                try (InputStream flux = zip.getInputStream(entrée)) {
                    auditerFichier(unité, entrée.getName(),
                            flux.readAllBytes(), contrevenants);
                }
            }
        }
    }

    /**
     * Audit d'un fichier de classe : fabriques hors de DomSûr, URI de
     * fonctionnalités hors de DomSûr, et Transformer NULLE PART.
     */
    private static void auditerFichier(Unité unité, String chemin,
                                       byte[] octets,
                                       Map<String, Set<String>> contrevenants) {
        try {
            Set<String> références = RéférencesDeClasse.lire(
                    new ByteArrayInputStream(octets));
            Set<String> chaînes = RéférencesDeClasse.lireChaînes(
                    new ByteArrayInputStream(octets));
            boolean domSûr = DOM_SÛR.equals(chemin);
            for (String référence : références) {
                if (FABRIQUES.contains(référence) && !domSûr) {
                    contrevenants.computeIfAbsent(
                                    "fabrique " + référence.replace('/', '.'),
                                    r -> new TreeSet<>())
                            .add(unité.nom() + " (" + chemin + ")");
                }
                if (référence.startsWith("javax/xml/transform/")) {
                    contrevenants.computeIfAbsent(
                                    "Transformer interdit " + référence.replace('/', '.'),
                                    r -> new TreeSet<>())
                            .add(unité.nom() + " (" + chemin + ")");
                }
            }
            for (String chaîne : chaînes) {
                if (!domSûr && (chaîne.contains("apache.org/xml/features")
                        || chaîne.contains("xml.org/sax/features"))) {
                    contrevenants.computeIfAbsent(
                                    "fonctionnalité XML « " + chaîne + " »",
                                    r -> new TreeSet<>())
                            .add(unité.nom() + " (" + chemin + ")");
                }
            }
        } catch (IOException illisible) {
            contrevenants.computeIfAbsent("classe illisible " + chemin,
                    r -> new TreeSet<>()).add(unité.nom() + " (" + chemin + ")");
        }
    }

    /** L'emplacement des classes compilées d'un module (répertoire ou jar). */
    private static Path emplacement(Unité unité) throws IOException {
        try {
            return Path.of(unité.marqueur().getProtectionDomain().getCodeSource()
                    .getLocation().toURI());
        } catch (Exception introuvable) {
            throw new IOException("emplacement des classes de " + unité.nom()
                    + " introuvable (marqueur " + unité.marqueur().getName() + ")",
                    introuvable);
        }
    }

    /** Message d'échec : chaque contrevenant, sa provenance, et le remède. */
    private static String message(Map<String, Set<String>> contrevenants) {
        StringBuilder texte = new StringBuilder("Le code du moteur touche ")
                .append(contrevenants.size()).append(" surface(s) XML interdite")
                .append(contrevenants.size() > 1 ? "s" : "")
                .append(" — un build appareil mourrait du même piège que le")
                .append(" rapport v0.2.16 (« manifeste illisible :")
                .append(" …disallow-doctype-decl » à processResources,")
                .append(" récidive du crash POM v0.2.9) : le parseur DOM d'ART")
                .append(" ne reconnaît PAS les fonctionnalités Xerces et le")
                .append(" Transformer n'y est pas fiable. Les fabriques et les")
                .append(" fonctionnalités ne se touchent QUE par jo.forge.engine")
                .append(".util.DomSûr (garde-fous au mieux, DOCTYPE refusé par")
                .append(" scan du prologue) et la sérialisation sans Transformer")
                .append(" (écrivain maison, motif REF-163).\n");
        contrevenants.forEach((interdit, provenances) -> texte
                .append("  ").append(interdit)
                .append(" ← ").append(String.join(", ", provenances)).append('\n'));
        return texte.toString();
    }
}
