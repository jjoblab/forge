package jo.forge.engine;

import jo.forge.engine.testutil.OutilsAndroidDeTest;
import jo.forge.engine.testutil.RéférencesDeClasse;
import org.junit.jupiter.api.Test;

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
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Audit de SURFACE ART du code du moteur (correctif v0.2.16) : les classes des
 * modules qui s'exécutent sur l'appareil ({@code engine}, {@code api},
 * {@code json}, {@code client} — le composant engine-core) ne peuvent
 * référencer, côté plate-forme, que des classes qu'Android porte réellement
 * (android.jar de compilation) ou les tranches lambda embarquées
 * ({@code /lambda-stubs.jar}).
 *
 * <p><b>Constat à l'origine</b> (rapport appareil v0.2.15, premier build
 * Compose) : {@code app:resolveDependencies} est mort de
 * {@code Failed resolution of: Ljava/lang/ProcessHandle;} — le nommage du
 * répertoire provisoire d'explosion d'AAR utilisait l'API de bureau
 * {@code java.lang.ProcessHandle} (Java 9), présente dans le JDK de test,
 * absente d'ART. Même famille que le crash StAX de v0.2.13
 * ({@code javax.xml.stream}, correctif v0.2.14) : <b>les tests JVM ne voient
 * jamais ces manques, la JVM de bureau porte tout</b>. La référence compile
 * tout son moteur CONTRE android.jar (application Android) — la garde y est
 * structurelle ; notre moteur est compilé contre le JDK de bureau, sa surface
 * doit donc être AUDITÉE.</p>
 *
 * <p><b>Portée honnête</b> : l'audit lit les entrées {@code CONSTANT_Class}
 * des pools de constantes — il attrape toute CLASSE de plate-forme absente
 * d'ART, pas les MÉTHODES manquantes d'une classe présente (l'expérience
 * appareil reste le juge final de celles-là). Les classes servies par les
 * composants dexés (ECJ, d8, apksig, compilateur Kotlin, StAX) ne sont pas
 * concernées : le code du moteur ne les référence pas de compilation.</p>
 */
class SurfaceArtTest {

    /** Les quatre modules du composant engine-core, avec leur classe-marqueur. */
    private static final List<Unité> UNITÉS = List.of(
            new Unité("engine", EngineVersion.class),
            new Unité("api", jo.forge.api.Frame.class),
            new Unité("json", jo.forge.json.Json.class),
            new Unité("client", jo.forge.client.EngineClient.class));

    private record Unité(String nom, Class<?> marqueur) {
    }

    @Test
    void leCodeDuMoteurNeRéférenceQueLaSurfaceDeART() throws IOException {
        Path androidJar = OutilsAndroidDeTest.androidJar();
        assumeTrue(androidJar != null, "android.jar réel indisponible — test ignoré");
        Set<String> surface = surfaceAndroid(androidJar);
        // Sonde de fraîcheur : java.lang.Record (API 33+) marque une plate-forme
        // moderne ; un android.jar plus ancien fausserait l'audit par faux positifs.
        assumeTrue(surface.contains("java/lang/Record"),
                "android.jar trop ancien pour l'audit (java.lang.Record absent) — test ignoré");
        surface.addAll(tranchesLambda());

        Map<String, Set<String>> contrevenants = new TreeMap<>();
        for (Unité unité : UNITÉS) {
            Path emplacement = emplacement(unité);
            if (Files.isDirectory(emplacement)) {
                auditerRépertoire(unité, emplacement, surface, contrevenants);
            } else {
                auditerJar(unité, emplacement, surface, contrevenants);
            }
        }
        assertTrue(contrevenants.isEmpty(), () -> message(contrevenants));
    }

    /**
     * La surface de la plate-forme : les classes définies par android.jar
     * (l'API publique, {@code java.*} et {@code javax.*} compris — c'est la
     * surface que le chargeur d'ART garantit).
     */
    private static Set<String> surfaceAndroid(Path androidJar) throws IOException {
        Set<String> noms = new TreeSet<>();
        try (ZipFile zip = new ZipFile(androidJar.toFile())) {
            for (ZipEntry entrée : zip.stream()
                    .filter(e -> !e.isDirectory())
                    .filter(e -> e.getName().endsWith(".class"))
                    .sorted(java.util.Comparator.comparing(ZipEntry::getName))
                    .toList()) {
                noms.add(interne(entrée.getName()));
            }
        }
        return noms;
    }

    /**
     * Les classes des tranches lambda embarquées ({@code /lambda-stubs.jar},
     * ressource du jar du moteur) : {@code StringConcatFactory} et
     * {@code LambdaMetafactory} sont absentes d'android.jar — le compilateur
     * Java 9+ les exige pourtant, le moteur les embarque donc et le chargeur
     * d'ART les sert.
     */
    private static Set<String> tranchesLambda() throws IOException {
        Set<String> noms = new TreeSet<>();
        try (InputStream ressource =
                     SurfaceArtTest.class.getResourceAsStream("/lambda-stubs.jar")) {
            assertNotNull(ressource, "/lambda-stubs.jar doit être au classpath "
                    + "(tâche tranchesLambdaJar du moteur) — l'audit ne peut pas "
                    + "vérifier la surface sans ses classes autorisées");
            try (ZipInputStream zip = new ZipInputStream(ressource)) {
                for (ZipEntry entrée; (entrée = zip.getNextEntry()) != null; ) {
                    if (!entrée.isDirectory() && entrée.getName().endsWith(".class")) {
                        noms.add(interne(entrée.getName()));
                    }
                }
            }
        }
        return noms;
    }

    /** Audit d'une racine de classes compilées (exécution Gradle usuelle). */
    private static void auditerRépertoire(Unité unité, Path racine,
                                          Set<String> surface,
                                          Map<String, Set<String>> contrevenants)
            throws IOException {
        try (var parcours = Files.walk(racine)) {
            for (Path classe : parcours
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".class"))
                    .sorted().toList()) {
                auditerFichier(unité, racine.relativize(classe).toString(),
                        Files.newInputStream(classe), surface, contrevenants);
            }
        }
    }

    /** Audit d'un jar de classes (exécution depuis un artefact empaqueté). */
    private static void auditerJar(Unité unité, Path jar, Set<String> surface,
                                   Map<String, Set<String>> contrevenants)
            throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (ZipEntry entrée : zip.stream()
                    .filter(e -> !e.isDirectory())
                    .filter(e -> e.getName().endsWith(".class"))
                    .sorted(java.util.Comparator.comparing(ZipEntry::getName))
                    .toList()) {
                auditerFichier(unité, entrée.getName(), zip.getInputStream(entrée),
                        surface, contrevenants);
            }
        }
    }

    /** Audit d'un fichier de classe : chaque référence de plate-forme absente
     * de la surface devient un contrevenant nommé. */
    private static void auditerFichier(Unité unité, String chemin,
                                       InputStream flux, Set<String> surface,
                                       Map<String, Set<String>> contrevenants)
            throws IOException {
        Set<String> références;
        try (flux) {
            références = RéférencesDeClasse.lire(flux);
        }
        for (String référence : références) {
            if (estApiPlateforme(référence) && !surface.contains(référence)) {
                contrevenants.computeIfAbsent(référence, r -> new TreeSet<>())
                        .add(unité.nom() + " (" + chemin + ")");
            }
        }
    }

    /** Les préfixes de la plate-forme Java : absents d'android.jar, ils sont
     * des API de bureau (ou des internes de JDK) qu'ART ne sert jamais. */
    private static boolean estApiPlateforme(String nomInterne) {
        return nomInterne.startsWith("java/") || nomInterne.startsWith("javax/")
                || nomInterne.startsWith("jdk/") || nomInterne.startsWith("sun/")
                || nomInterne.startsWith("com/sun/");
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

    /** Nom interne d'une entrée de zip ({@code a/b/C.class} → {@code a/b/C}). */
    private static String interne(String entrée) {
        return entrée.substring(0, entrée.length() - ".class".length());
    }

    /** Message d'échec : chaque contrevenant, sa provenance, et le remède. */
    private static String message(Map<String, Set<String>> contrevenants) {
        StringBuilder texte = new StringBuilder("Le code du moteur référence ")
                .append(contrevenants.size())
                .append(" classe(s) de la plate-forme absentes d'ART (android.jar)")
                .append(" et des tranches lambda — un build appareil mourrait de")
                .append(" « Failed resolution of: L…; » au premier chargement")
                .append(" (v0.2.15 : java.lang.ProcessHandle tuait resolveDependencies")
                .append(" au premier AAR explosé, rapport appareil). Remplacez chaque")
                .append(" contrevenant par une API portée par ART, ou servez-la dans un")
                .append(" composant dexé — jamais de liste blanche muette :\n");
        contrevenants.forEach((référence, provenances) -> texte
                .append("  ").append(référence.replace('/', '.'))
                .append(" ← ").append(String.join(", ", provenances)).append('\n'));
        return texte.toString();
    }
}
