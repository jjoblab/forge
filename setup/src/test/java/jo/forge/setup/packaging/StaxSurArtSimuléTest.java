package jo.forge.setup.packaging;

import jo.forge.setup.packaging.fixtures.ClasseQuiRéférenceStax;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * « StAX sur ART simulé » — le maillon manquant de la simulation (correctif
 * v0.2.14).
 *
 * <p>Constat v0.2.13 (rapport appareil, {@code kotlin-simple}) : le compilateur
 * Kotlin mourait par {@code NoClassDefFoundError:
 * javax/xml/stream/XMLStreamException} dans {@code PluginDescriptorLoader} — le
 * cœur IntelliJ embarqué lit ses descripteurs de plugins avec l'API StAX,
 * présente dans le JDK desktop mais absente d'Android (SAX/DOM/XmlPullParser
 * seulement). AUCUN test JVM ne pouvait l'attraper : la simulation ART du moteur
 * ne feinte que {@code java.vm.name}, le compilateur restant servi par le
 * classpath de test complet — donc par le {@code javax.xml.stream} DU JDK.</p>
 *
 * <p>Ce test bouche le trou : stax-api + aalto + stax2 (les jars exactement
 * distribués par la configuration {@code kotlinCompiler}) sont chargés dans un
 * classloader qui CACHE {@code javax.xml.stream} du JDK et revendique aalto et
 * stax2 — la situation d'un appareil où le composant dexé est la SEULE source.
 * {@code XMLInputFactory.newInstance()} doit alors rendre l'usine aalto : le
 * FactoryFinder du jar STANDALONE JSR-173 (pas celui du JDK, qui appelle des
 * classes com.sun/jdk.xml inexistantes sur ART) trouve l'implémentation par
 * {@code META-INF/services} — le mécanisme que le dexage doit conserver
 * (testé par ailleurs : les entrées services traversent
 * {@code emballerJarDexe}).</p>
 *
 * <p>Le garde-fou d'assemblage {@code vérifierRéférencesStax} est couvert
 * ici aussi : le crash v0.2.13 doit devenir impossible à PUBLIER.</p>
 */
class StaxSurArtSimuléTest {

    @TempDir
    Path temporaire;

    /**
     * Charge stax-api + aalto + stax2 dans un chargeur qui cache
     * {@code javax.xml.stream} du JDK et vérifie que
     * {@code XMLInputFactory.newInstance()} rend l'usine aalto — puis lit un
     * vrai descripteur de plugin, exactement comme
     * {@code PluginDescriptorLoader.loadDescriptorFromJar}.
     *
     * @throws Exception si le chargement réflexif échoue.
     */
    @Test
    void newInstanceRendLUscarineAaltoQuandLeJdkEstCaché() throws Exception {
        Path jarStaxApi = jarDuClasspath("stax-api");
        Path jarAalto = jarDuClasspath("aalto-xml");
        Path jarStax2 = jarDuClasspath("stax2-api");
        assumeTrue(jarStaxApi != null && jarAalto != null && jarStax2 != null,
                "stax-api + aalto-xml + stax2-api requis sur le classpath de test");

        // D'abord le piège lui-même : sur ce classpath de test (situation
        // bureau), javax.xml.stream est servi par le JDK — VOILÀ pourquoi les
        // tests JVM restaient verts pendant que l'appareil mourrait.
        Class<?> duJdk = Class.forName("javax.xml.stream.XMLInputFactory");
        assertNotNull(duJdk,
                "sanity : sur la JVM de test, le JDK sert javax.xml.stream");

        ChargeurCommeArt chargeur = new ChargeurCommeArt(new URL[] {
                jarStaxApi.toUri().toURL(),
                jarAalto.toUri().toURL(),
                jarStax2.toUri().toURL()});
        try {
            // La situation appareil : la classe vient du JAR (dexé sur
            // l'appareil), pas du JDK.
            Class<?> fabrique = Class.forName(
                    "javax.xml.stream.XMLInputFactory", true, chargeur);
            assertSame(chargeur, fabrique.getClassLoader(),
                    "javax.xml.stream doit venir des jars, pas du JDK caché");
            assertNotSame(duJdk, fabrique,
                    "deux mondes distincts — le chargeur feint l'absence du JDK, "
                            + "comme ART n'embarque pas StAX");

            // Le chargeur de contexte (comme sur l'appareil, où le moteur et
            // son classpath de composants forment le chargeur du processus) :
            // le FactoryFinder autonome du jar standalone y cherche
            // META-INF/services/javax.xml.stream.XMLInputFactory.
            Thread fil = Thread.currentThread();
            ClassLoader ancien = fil.getContextClassLoader();
            fil.setContextClassLoader(chargeur);
            Object usine;
            try {
                usine = fabrique.getMethod("newInstance").invoke(null);
            } finally {
                fil.setContextClassLoader(ancien);
            }

            // L'usine est CELLE d'aalto, chargée par le même chargeur — sa
            // hiérarchie (stax2, javax.xml.stream) y vit entièrement.
            assertEquals("com.fasterxml.aalto.stax.InputFactoryImpl",
                    usine.getClass().getName(),
                    "le FactoryFinder autonome doit trouver aalto par "
                            + "META-INF/services");
            assertSame(chargeur, usine.getClass().getClassLoader());
            assertTrue(fabrique.isInstance(usine),
                    "l'usine étend le XMLInputFactory DU JAR (identité de "
                            + "classe, pas seulement de nom)");

            // Lecture réelle d'un descripteur de plugin — le chemin exact du
            // crash : createXMLStreamReader puis parcours des événements.
            String descripteur = "<idea-plugin>"
                    + "<id>jo.forge.kotlin</id>"
                    + "<description>descripteur de test</description>"
                    + "</idea-plugin>";
            Object lecteur = fabrique
                    .getMethod("createXMLStreamReader", java.io.Reader.class)
                    .invoke(usine, new java.io.StringReader(descripteur));
            Class<?> interfaceLecteur = Class.forName(
                    "javax.xml.stream.XMLStreamReader", true, chargeur);
            List<String> identifiants = new ArrayList<>();
            // XMLStreamConstants (JSR-173, gelé) : START_ELEMENT=1, CHARACTERS=4.
            StringBuilder texte = new StringBuilder();
            String élément = "";
            while ((Boolean) interfaceLecteur.getMethod("hasNext").invoke(lecteur)) {
                int événement = (Integer) interfaceLecteur.getMethod("next")
                        .invoke(lecteur);
                if (événement == 1) {
                    élément = (String) interfaceLecteur.getMethod("getLocalName")
                            .invoke(lecteur);
                } else if (événement == 4 && "id".equals(élément)) {
                    texte.append((String) interfaceLecteur.getMethod("getText")
                            .invoke(lecteur));
                }
            }
            identifiants.add(texte.toString());
            assertEquals(List.of("jo.forge.kotlin"), identifiants,
                    "aalto doit lire le descripteur à travers le chargeur qui "
                            + "cache le JDK — le chemin exact du crash v0.2.13");
        } finally {
            chargeur.close();
        }
    }

    /**
     * Le garde-fou StAX : une classe référençant {@code javax.xml.stream} sans
     * que l'ensemble ne serve le paquet échoue l'assemblage EN NOMMANT les
     * classes ; avec stax-api dans l'ensemble, il se tait.
     *
     * @throws IOException si la fabrication des jars de test échoue.
     */
    @Test
    void gardeFouStaxÉchoueEnNommantLesClassesManquantes() throws IOException {
        Path jarRéférenceur = jarDeClasseCompilée(ClasseQuiRéférenceStax.class);

        IOException échec = assertThrows(IOException.class,
                () -> PackagerComposants.vérifierRéférencesStax(List.of(jarRéférenceur)));
        for (String attendue : List.of("javax/xml/stream/XMLInputFactory",
                "javax/xml/stream/XMLStreamException",
                "javax/xml/stream/XMLStreamReader")) {
            assertTrue(échec.getMessage().contains(attendue),
                    attendue + " doit être nommée : " + échec.getMessage());
        }

        // Avec stax-api dans l'ensemble (la situation publiée v0.2.14) : le
        // garde-fou se tait.
        Path jarStaxApi = jarDuClasspath("stax-api");
        assumeTrue(jarStaxApi != null, "stax-api requis sur le classpath de test");
        PackagerComposants.vérifierRéférencesStax(
                List.of(jarRéférenceur, jarStaxApi));
    }

    /**
     * Chargeur qui simule le point de vue d'ART sur les paquets StAX : enfant
     * d'abord pour {@code javax.xml.stream} (le JDK n'existe pas pour lui),
     * {@code com.fasterxml.aalto} et {@code org.codehaus.stax2} (l'usine et sa
     * hiérarchie doivent vivre dans le MÊME monde que l'API — sinon la preuve
     * d'identité n'en est pas une) ; parent (JDK/plate-forme) pour le reste —
     * sur l'appareil, java.util, javax.xml.transform &amp; co viennent de la
     * plate-forme Android.
     */
    private static final class ChargeurCommeArt extends URLClassLoader {

        private static final List<String> REVENDIQUÉS = List.of(
                "javax.xml.stream.", "com.fasterxml.aalto.", "org.codehaus.stax2.");

        ChargeurCommeArt(URL[] urls) {
            super(urls, ChargeurCommeArt.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String nom, boolean résoudre)
                throws ClassNotFoundException {
            for (String préfixe : REVENDIQUÉS) {
                if (!nom.startsWith(préfixe)) {
                    continue;
                }
                synchronized (getClassLoadingLock(nom)) {
                    Class<?> classe = findLoadedClass(nom);
                    if (classe == null) {
                        classe = findClass(nom);
                    }
                    if (résoudre) {
                        resolveClass(classe);
                    }
                    return classe;
                }
            }
            return super.loadClass(nom, résoudre);
        }
    }

    /** Localise un jar du classpath de test par préfixe de nom de fichier. */
    private static Path jarDuClasspath(String préfixe) {
        for (String entrée : System.getProperty("java.class.path")
                .split(java.io.File.pathSeparator)) {
            Path jar = Path.of(entrée);
            if (Files.isRegularFile(jar)
                    && jar.getFileName().toString().startsWith(préfixe + "-")) {
                return jar;
            }
        }
        return null;
    }

    /**
     * Emballe les octets compilés d'une classe de test en jar (le garde-fou
     * lit des jars, pas des classes isolées) — même motif que
     * {@code PackagerComposantsTest}.
     */
    private Path jarDeClasseCompilée(Class<?> classe) throws IOException {
        byte[] octets;
        try (java.io.InputStream flux = classe.getResourceAsStream(
                classe.getSimpleName() + ".class")) {
            assertNotNull(flux, "octets de " + classe.getName());
            octets = flux.readAllBytes();
        }
        Path jar = temporaire.resolve(classe.getSimpleName() + ".jar");
        try (ZipOutputStream zip = new ZipOutputStream(
                Files.newOutputStream(jar))) {
            ZipEntry entrée = new ZipEntry(
                    classe.getName().replace('.', '/') + ".class");
            entrée.setTime(315532800000L);
            zip.putNextEntry(entrée);
            zip.write(octets);
            zip.closeEntry();
        }
        return jar;
    }
}
