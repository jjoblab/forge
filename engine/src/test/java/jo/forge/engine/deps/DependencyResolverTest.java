package jo.forge.engine.deps;

import jo.forge.engine.model.DependencyScope;
import jo.forge.engine.testutil.DépôtLocal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests du résolveur sur un vrai dépôt HTTP local : transitivité, portées, médiation
 * (trois politiques), nomenclatures importées, propriétés, exclusions, optionnelles,
 * relogements, intégrité, cache négatif, hors ligne, bornes.
 */
class DependencyResolverTest {

    @TempDir
    Path temporaire;

    // --- Fabrication de POM ---------------------------------------------------------------

    private static String pom(String groupe, String artefact, String version,
                              String... blocs) {
        StringBuilder xml = new StringBuilder("<project>");
        xml.append("<groupId>").append(groupe).append("</groupId>");
        xml.append("<artifactId>").append(artefact).append("</artifactId>");
        xml.append("<version>").append(version).append("</version>");
        for (String bloc : blocs) {
            xml.append(bloc);
        }
        return xml.append("</project>").toString();
    }

    private static String dépendance(String g, String a, String v, String portée,
                                     String... extra) {
        StringBuilder xml = new StringBuilder("<dependency><groupId>").append(g)
                .append("</groupId><artifactId>").append(a).append("</artifactId>");
        if (v != null) {
            xml.append("<version>").append(v).append("</version>");
        }
        if (portée != null) {
            xml.append("<scope>").append(portée).append("</scope>");
        }
        for (String fragment : extra) {
            xml.append(fragment);
        }
        return xml.append("</dependency>").toString();
    }

    private static String gestion(String g, String a, String v, String portée) {
        return "<dependencyManagement><dependencies>" + dépendance(g, a, v, portée)
                + "</dependencies></dependencyManagement>";
    }

    private static String deps(String... dépendances) {
        return "<dependencies>" + String.join("", dépendances) + "</dependencies>";
    }

    private static byte[] jarBidon() {
        return new byte[]{0x50, 0x4B, 0x03, 0x04, 0x00, 0x01, 0x02};
    }

    private static void publier(DépôtLocal dépôt, Coordinate c, String pomXml) {
        dépôt.publierPom(c, pomXml);
        dépôt.publierJar(c, jarBidon());
    }

    private DependencyResolver résolveur(DépôtLocal dépôt, boolean horsLigne,
                                         List<String> avertissements) {
        return new DependencyResolver(List.of(dépôt.url()),
                new ResolverCache(temporaire.resolve("cache")), horsLigne,
                avertissements::add);
    }

    // --- Transitivité et portées ----------------------------------------------------------

    @Test
    void transitivitéCompileEtRuntime() throws Exception {
        try (DépôtLocal dépôt = DépôtLocal.démarrer()) {
            Coordinate racine = new Coordinate("demo", "app", "1.0");
            Coordinate a = new Coordinate("demo", "lib-a", "1.0");
            Coordinate b = new Coordinate("demo", "lib-b", "1.0");
            publier(dépôt, racine, pom("demo", "app", "1.0",
                    deps(dépendance("demo", "lib-a", "1.0", null))));
            publier(dépôt, a, pom("demo", "lib-a", "1.0",
                    deps(dépendance("demo", "lib-b", "1.0", null))));
            publier(dépôt, b, pom("demo", "lib-b", "1.0"));

            DependencyResolver.Result résultat = résolveur(dépôt, false, new ArrayList<>())
                    .resolve(List.of(new DependencyResolver.RootDependency(
                            racine, DependencyScope.API)), ConflictPolicy.NEWEST);

            assertEquals(List.of("demo:app:1.0", "demo:lib-a:1.0", "demo:lib-b:1.0"),
                    résultat.compileClasspath().stream()
                            .map(jar -> jar.coordinate().toString()).toList(),
                    "une racine api expose sa fermeture compile+runtime");
        }
    }

    @Test
    void implémentationExposeSaFermetureASonPropreModule() throws Exception {
        // Correction v0.2.15 (parité Gradle) : une dépendance implementation
        // met SA fermeture compile sur le classpath de compilation du module
        // QUI LA DÉCLARE — sans quoi le code d'une application compose ne
        // résout pas les types que sa bibliothèque expose en api
        // (@Composable vient du runtime exposé par ui). La différence avec api
        // vit à l'EXPORT (modules frères), pas dans le propre classpath du
        // module — voir ResolveDependenciesTaskTest.racinesDe.
        try (DépôtLocal dépôt = DépôtLocal.démarrer()) {
            Coordinate a = new Coordinate("demo", "lib-a", "1.0");
            Coordinate b = new Coordinate("demo", "lib-b", "1.0");
            publier(dépôt, a, pom("demo", "lib-a", "1.0",
                    deps(dépendance("demo", "lib-b", "1.0", null))));
            publier(dépôt, b, pom("demo", "lib-b", "1.0"));

            DependencyResolver.Result résultat = résolveur(dépôt, false, new ArrayList<>())
                    .resolve(List.of(new DependencyResolver.RootDependency(
                            a, DependencyScope.IMPLEMENTATION)), ConflictPolicy.NEWEST);

            assertEquals(List.of("demo:lib-a:1.0", "demo:lib-b:1.0"),
                    résultat.compileClasspath().stream()
                            .map(jar -> jar.coordinate().toString()).toList(),
                    "implementation : la fermeture au classpath de compilation "
                            + "du module déclarant (parité Gradle, v0.2.15)");
            assertEquals(List.of("demo:lib-a:1.0", "demo:lib-b:1.0"),
                    résultat.runtimeClasspath().stream()
                            .map(jar -> jar.coordinate().toString()).toList(),
                    "…et sa fermeture complète à l'exécution");
        }
    }

    @Test
    void fourniEtOptionnelleNonTransitives() throws Exception {
        try (DépôtLocal dépôt = DépôtLocal.démarrer()) {
            Coordinate a = new Coordinate("demo", "lib-a", "1.0");
            Coordinate fourni = new Coordinate("demo", "lib-fourni", "1.0");
            Coordinate optionnelle = new Coordinate("demo", "lib-opt", "1.0");
            publier(dépôt, a, pom("demo", "lib-a", "1.0", deps(
                    dépendance("demo", "lib-fourni", "1.0", "provided"),
                    dépendance("demo", "lib-opt", "1.0", null, "<optional>true</optional>"))));
            publier(dépôt, fourni, pom("demo", "lib-fourni", "1.0"));
            publier(dépôt, optionnelle, pom("demo", "lib-opt", "1.0"));

            DependencyResolver.Result résultat = résolveur(dépôt, false, new ArrayList<>())
                    .resolve(List.of(new DependencyResolver.RootDependency(
                            a, DependencyScope.API)), ConflictPolicy.NEWEST);

            assertEquals(List.of("demo:lib-a:1.0"),
                    résultat.compileClasspath().stream()
                            .map(jar -> jar.coordinate().toString()).toList(),
                    "provided et optionnelles ne traversent jamais");
        }
    }

    @Test
    void exclusionsAppliquées() throws Exception {
        try (DépôtLocal dépôt = DépôtLocal.démarrer()) {
            Coordinate a = new Coordinate("demo", "lib-a", "1.0");
            Coordinate b = new Coordinate("demo", "lib-b", "1.0");
            Coordinate c = new Coordinate("demo", "lib-c", "1.0");
            publier(dépôt, a, pom("demo", "lib-a", "1.0", deps(
                    dépendance("demo", "lib-b", "1.0", null,
                            "<exclusions><exclusion><groupId>demo</groupId>"
                                    + "<artifactId>lib-c</artifactId></exclusion>"
                                    + "</exclusions>"))));
            publier(dépôt, b, pom("demo", "lib-b", "1.0",
                    deps(dépendance("demo", "lib-c", "1.0", null))));
            publier(dépôt, c, pom("demo", "lib-c", "1.0"));

            DependencyResolver.Result résultat = résolveur(dépôt, false, new ArrayList<>())
                    .resolve(List.of(new DependencyResolver.RootDependency(
                            a, DependencyScope.API)), ConflictPolicy.NEWEST);

            assertEquals(List.of("demo:lib-a:1.0", "demo:lib-b:1.0"),
                    résultat.compileClasspath().stream()
                            .map(jar -> jar.coordinate().toString()).toList(),
                    "lib-c est exclue du sous-arbre de lib-a");
        }
    }

    // --- Médiation ------------------------------------------------------------------------

    @Test
    void médiationLaPlusRécenteParDéfaut() throws Exception {
        try (DépôtLocal dépôt = DépôtLocal.démarrer()) {
            Coordinate app = new Coordinate("demo", "app", "1.0");
            Coordinate a = new Coordinate("demo", "lib-a", "1.0");
            Coordinate c = new Coordinate("demo", "lib-c", "1.0");
            publier(dépôt, app, pom("demo", "app", "1.0", deps(
                    dépendance("demo", "lib-a", "1.0", null),
                    dépendance("demo", "lib-c", "1.0", null))));
            publier(dépôt, a, pom("demo", "lib-a", "1.0",
                    deps(dépendance("demo", "lib-b", "1.0", null))));
            publier(dépôt, c, pom("demo", "lib-c", "1.0",
                    deps(dépendance("demo", "lib-b", "2.0", null))));
            publier(dépôt, new Coordinate("demo", "lib-b", "1.0"),
                    pom("demo", "lib-b", "1.0"));
            publier(dépôt, new Coordinate("demo", "lib-b", "2.0"),
                    pom("demo", "lib-b", "2.0"));

            DependencyResolver.Result résultat = résolveur(dépôt, false, new ArrayList<>())
                    .resolve(List.of(new DependencyResolver.RootDependency(
                            app, DependencyScope.API)), ConflictPolicy.NEWEST);

            assertTrue(résultat.compileClasspath().stream()
                            .anyMatch(jar -> jar.coordinate().toString()
                                    .equals("demo:lib-b:2.0")),
                    "la plus récente gagne : " + résultat.compileClasspath());
            assertTrue(résultat.compileClasspath().stream().noneMatch(jar ->
                            jar.coordinate().toString().equals("demo:lib-b:1.0")),
                    "la version perdante n'apparaît pas");
        }
    }

    @Test
    void conflitRefuséAvecChemins() throws Exception {
        try (DépôtLocal dépôt = DépôtLocal.démarrer()) {
            Coordinate app = new Coordinate("demo", "app", "1.0");
            Coordinate a = new Coordinate("demo", "lib-a", "1.0");
            publier(dépôt, app, pom("demo", "app", "1.0", deps(
                    dépendance("demo", "lib-a", "1.0", null),
                    dépendance("demo", "lib-b", "1.0", null))));
            publier(dépôt, a, pom("demo", "lib-a", "1.0",
                    deps(dépendance("demo", "lib-b", "2.0", null))));
            publier(dépôt, new Coordinate("demo", "lib-b", "1.0"),
                    pom("demo", "lib-b", "1.0"));
            publier(dépôt, new Coordinate("demo", "lib-b", "2.0"),
                    pom("demo", "lib-b", "2.0"));

            ResolverException conflit = assertThrows(ResolverException.class, () ->
                    résolveur(dépôt, false, new ArrayList<>()).resolve(
                            List.of(new DependencyResolver.RootDependency(
                                    app, DependencyScope.API)),
                            ConflictPolicy.FAIL_ON_CONFLICT));
            assertTrue(conflit.getMessage().contains("demo:lib-b"),
                    "le message cite l'identité en conflit : " + conflit.getMessage());
            assertTrue(conflit.getMessage().contains("→"),
                    "le message cite les chemins de demande : "
                            + conflit.getMessage());
        }
    }

    @Test
    void laPlusProcheGagneEnModeNearest() throws Exception {
        try (DépôtLocal dépôt = DépôtLocal.démarrer()) {
            Coordinate app = new Coordinate("demo", "app", "1.0");
            Coordinate a = new Coordinate("demo", "lib-a", "1.0");
            publier(dépôt, app, pom("demo", "app", "1.0", deps(
                    dépendance("demo", "lib-b", "1.0", null),
                    dépendance("demo", "lib-a", "1.0", null))));
            publier(dépôt, a, pom("demo", "lib-a", "1.0",
                    deps(dépendance("demo", "lib-b", "2.0", null))));
            publier(dépôt, new Coordinate("demo", "lib-b", "1.0"),
                    pom("demo", "lib-b", "1.0"));
            publier(dépôt, new Coordinate("demo", "lib-b", "2.0"),
                    pom("demo", "lib-b", "2.0"));

            DependencyResolver.Result résultat = résolveur(dépôt, false, new ArrayList<>())
                    .resolve(List.of(new DependencyResolver.RootDependency(
                            app, DependencyScope.API)), ConflictPolicy.NEAREST);

            assertTrue(résultat.compileClasspath().stream()
                            .anyMatch(jar -> jar.coordinate().toString()
                                    .equals("demo:lib-b:1.0")),
                    "la plus proche de la racine gagne : " + résultat.compileClasspath());
            assertTrue(résultat.compileClasspath().stream().noneMatch(jar ->
                            jar.coordinate().toString().equals("demo:lib-b:2.0")));
        }
    }

    // --- Gestion, nomenclatures, propriétés, relogement ------------------------------------

    @Test
    void versionParGestionEtPropriétés() throws Exception {
        try (DépôtLocal dépôt = DépôtLocal.démarrer()) {
            Coordinate app = new Coordinate("demo", "app", "1.0");
            Coordinate lib = new Coordinate("demo", "lib-x", "9.9");
            publier(dépôt, app, pom("demo", "app", "1.0",
                    "<properties><v.lib>9.9</v.lib></properties>",
                    gestion("demo", "lib-x", "${v.lib}", null),
                    deps(dépendance("demo", "lib-x", null, null))));
            publier(dépôt, lib, pom("demo", "lib-x", "9.9"));

            DependencyResolver.Result résultat = résolveur(dépôt, false, new ArrayList<>())
                    .resolve(List.of(new DependencyResolver.RootDependency(
                            app, DependencyScope.API)), ConflictPolicy.NEWEST);

            assertTrue(résultat.compileClasspath().stream()
                            .anyMatch(jar -> jar.coordinate().toString()
                                    .equals("demo:lib-x:9.9")),
                    "la version vient de la gestion (substituée par propriété) : "
                            + résultat.compileClasspath());
        }
    }

    @Test
    void nomenclatureImportéeFournitLaVersion() throws Exception {
        try (DépôtLocal dépôt = DépôtLocal.démarrer()) {
            Coordinate app = new Coordinate("demo", "app", "1.0");
            Coordinate bom = new Coordinate("demo", "nomenclature", "1.0");
            publier(dépôt, app, pom("demo", "app", "1.0",
                    gestion("demo", "nomenclature", "1.0", "import"),
                    deps(dépendance("demo", "lib-y", null, null))));
            publier(dépôt, bom, pom("demo", "nomenclature", "1.0",
                    gestion("demo", "lib-y", "4.2", null)));
            publier(dépôt, new Coordinate("demo", "lib-y", "4.2"),
                    pom("demo", "lib-y", "4.2"));

            DependencyResolver.Result résultat = résolveur(dépôt, false, new ArrayList<>())
                    .resolve(List.of(new DependencyResolver.RootDependency(
                            app, DependencyScope.API)), ConflictPolicy.NEWEST);

            assertTrue(résultat.compileClasspath().stream()
                            .anyMatch(jar -> jar.coordinate().toString()
                                    .equals("demo:lib-y:4.2")),
                    "la version 4.2 vient de la nomenclature importée : "
                            + résultat.compileClasspath());
        }
    }

    @Test
    void relogementSuivi() throws Exception {
        try (DépôtLocal dépôt = DépôtLocal.démarrer()) {
            Coordinate vieux = new Coordinate("demo", "vieux", "1.0");
            Coordinate neuf = new Coordinate("demo", "neuf", "2.0");
            publier(dépôt, vieux, pom("demo", "vieux", "1.0",
                    "<distributionManagement><relocation><groupId>demo</groupId>"
                            + "<artifactId>neuf</artifactId><version>2.0</version>"
                            + "</relocation></distributionManagement>"));
            publier(dépôt, neuf, pom("demo", "neuf", "2.0"));

            DependencyResolver.Result résultat = résolveur(dépôt, false, new ArrayList<>())
                    .resolve(List.of(new DependencyResolver.RootDependency(
                            vieux, DependencyScope.API)), ConflictPolicy.NEWEST);

            assertTrue(résultat.compileClasspath().stream()
                            .anyMatch(jar -> jar.coordinate().toString()
                                    .equals("demo:neuf:2.0")),
                    "le relogement remplace la coordonnée : "
                            + résultat.compileClasspath());
        }
    }

    // --- Sécurité et cache ----------------------------------------------------------------

    @Test
    void sommeFausseRejetée() throws Exception {
        try (DépôtLocal dépôt = DépôtLocal.démarrer()) {
            Coordinate a = new Coordinate("demo", "lib-a", "1.0");
            publier(dépôt, a, pom("demo", "lib-a", "1.0"));
            dépôt.corrompreSommeJar(a);

            ResolverException rejet = assertThrows(ResolverException.class, () ->
                    résolveur(dépôt, false, new ArrayList<>()).resolve(
                            List.of(new DependencyResolver.RootDependency(
                                    a, DependencyScope.API)), ConflictPolicy.NEWEST));
            assertTrue(rejet.getMessage().contains("Intégrité refusée"),
                    "une somme publiée fausse doit rejeter le téléchargement : "
                            + rejet.getMessage());
        }
    }

    @Test
    void httpHorsBoucleLocaleRefusé() {
        assertThrows(ResolverException.class, () ->
                HttpTransport.vérifierUrlDépôt("http://exemple.fr/maven"));
        assertThrows(ResolverException.class, () ->
                HttpTransport.vérifierUrlDépôt("ftp://exemple.fr"));
        // Les formes autorisées ne lèvent pas.
        HttpTransport.vérifierUrlDépôt("https://repo.maven.apache.org/maven2");
        HttpTransport.vérifierUrlDépôt("http://127.0.0.1:8080/maven");
        HttpTransport.vérifierUrlDépôt("http://localhost:8080/maven");
    }

    @Test
    void introuvableMessageClairEtCacheNégatif() throws Exception {
        try (DépôtLocal dépôt = DépôtLocal.démarrer()) {
            Coordinate a = new Coordinate("demo", "fantome", "1.0"); // nom ASCII (URL)
            publier(dépôt, new Coordinate("demo", "app", "1.0"), pom("demo", "app", "1.0"));
            dépôt.publierPom(new Coordinate("demo", "app", "1.0"),
                    pom("demo", "app", "1.0", deps(
                            dépendance("demo", "fantome", "1.0", null))));

            DependencyResolver résolveur = résolveur(dépôt, false, new ArrayList<>());
            ResolverException erreur = assertThrows(ResolverException.class, () ->
                    résolveur.resolve(List.of(new DependencyResolver.RootDependency(
                            new Coordinate("demo", "app", "1.0"),
                            DependencyScope.API)), ConflictPolicy.NEWEST));
            assertTrue(erreur.getMessage().contains("demo:fantome:1.0"),
                    "le message cite la coordonnée introuvable : " + erreur.getMessage());

            int demandesPom = dépôt.demandesPour(DépôtLocal.chemin(a, ".pom"));
            assertTrue(demandesPom >= 1, "le POM introuvable a bien été demandé");
            // Seconde tentative : le cache négatif évite de réinterroger le dépôt.
            assertThrows(ResolverException.class, () ->
                    résolveur.resolve(List.of(new DependencyResolver.RootDependency(
                            new Coordinate("demo", "app", "1.0"),
                            DependencyScope.API)), ConflictPolicy.NEWEST));
            assertEquals(demandesPom, dépôt.demandesPour(DépôtLocal.chemin(a, ".pom")),
                    "le cache négatif évite une nouvelle requête POM");
        }
    }

    @Test
    void pomTéléchargéMaisInvalideNEstPasAnnoncéIntrouvable() throws Exception {
        // Honnêteté du diagnostic (constat v0.2.9 sur ART) : un POM
        // TÉLÉCHARGÉ mais rejeté (DOCTYPE ici) doit être dit INVALIDE —
        // « introuvable » envoyait sur une piste réseau erronée — et ne
        // doit pas rester en cache.
        try (DépôtLocal dépôt = DépôtLocal.démarrer()) {
            Coordinate c = new Coordinate("demo", "piege", "1.0"); // nom ASCII (URL)
            dépôt.publierPom(c, "<!DOCTYPE project><project><groupId>demo</groupId>"
                    + "<artifactId>piege</artifactId><version>1.0</version></project>");
            dépôt.publierJar(c, jarBidon());

            DependencyResolver résolveur = résolveur(dépôt, false, new ArrayList<>());
            ResolverException échec = assertThrows(ResolverException.class, () ->
                    résolveur.resolve(List.of(new DependencyResolver.RootDependency(
                            c, DependencyScope.IMPLEMENTATION)), ConflictPolicy.NEWEST));
            assertTrue(échec.getMessage().contains("POM invalide"),
                    "le POM téléchargé mais rejeté est dit invalide : "
                            + échec.getMessage());
            assertTrue(!échec.getMessage().startsWith("POM introuvable"),
                    "pas de fausse piste réseau : " + échec.getMessage());
        }
    }

    @Test
    void horsLigneCachePuisÉchecClair() throws Exception {
        try (DépôtLocal dépôt = DépôtLocal.démarrer()) {
            Coordinate a = new Coordinate("demo", "lib-a", "1.0");
            publier(dépôt, a, pom("demo", "lib-a", "1.0"));

            // Premier passage en ligne : remplit le cache.
            résolveur(dépôt, false, new ArrayList<>()).resolve(
                    List.of(new DependencyResolver.RootDependency(
                            a, DependencyScope.API)), ConflictPolicy.NEWEST);

            // Hors ligne avec cache : fonctionne.
            DependencyResolver.Result relu = résolveur(dépôt, true, new ArrayList<>())
                    .resolve(List.of(new DependencyResolver.RootDependency(
                            a, DependencyScope.API)), ConflictPolicy.NEWEST);
            assertEquals(1, relu.compileClasspath().size());
        }
        try (DépôtLocal dépôt = DépôtLocal.démarrer()) {
            Coordinate b = new Coordinate("demo", "jamais-vu", "1.0");
            // Cache vide (autre répertoire de test ? non : même temporaire — on utilise
            // une coordonnée jamais résolue, donc absente du cache).
            ResolverException erreur = assertThrows(ResolverException.class, () ->
                    résolveur(dépôt, true, new ArrayList<>()).resolve(
                            List.of(new DependencyResolver.RootDependency(
                                    b, DependencyScope.API)), ConflictPolicy.NEWEST));
            assertTrue(erreur.getMessage().contains("hors ligne"),
                    "le message cite le mode hors ligne : " + erreur.getMessage());
        }
    }

    @Test
    void borneDeProfondeurÉchoueAvecDiagnostic() throws Exception {
        try (DépôtLocal dépôt = DépôtLocal.démarrer()) {
            // Chaîne de profondeur > BORNE_PROFONDEUR (noms ASCII : les URL de
            // ressources Maven n'encodent pas les accents).
            int longueur = DependencyResolver.BORNE_PROFONDEUR + 5;
            for (int i = 0; i < longueur; i++) {
                Coordinate courant = new Coordinate("demo", "chaine-" + i, "1.0");
                String deps = i == longueur - 1 ? ""
                        : deps(dépendance("demo", "chaine-" + (i + 1), "1.0", null));
                publier(dépôt, courant, pom("demo", "chaine-" + i, "1.0", deps));
            }
            ResolverException borne = assertThrows(ResolverException.class, () ->
                    résolveur(dépôt, false, new ArrayList<>()).resolve(
                            List.of(new DependencyResolver.RootDependency(
                                    new Coordinate("demo", "chaine-0", "1.0"),
                                    DependencyScope.API)), ConflictPolicy.NEWEST));
            assertTrue(borne.getMessage().contains("profondeur"),
                    "le diagnostic cite la borne de profondeur : " + borne.getMessage());
        }
    }

    // --- AAR et métadonnées Gradle (v0.2.15, prérequis Compose) ---------------------------

    /** Fabrique un AAR minimal : manifeste (paquet), classes.jar, res/. */
    private static byte[] aar(String paquet) throws Exception {
        java.io.ByteArrayOutputStream octets = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip =
                     new java.util.zip.ZipOutputStream(octets)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("AndroidManifest.xml"));
            zip.write(("<manifest package=\"" + paquet + "\"/>")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("classes.jar"));
            java.io.ByteArrayOutputStream jarVide = new java.io.ByteArrayOutputStream();
            new java.util.zip.ZipOutputStream(jarVide).close();
            zip.write(jarVide.toByteArray());
            zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("res/values/values.xml"));
            zip.write("<resources/>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return octets.toByteArray();
    }

    @Test
    void aarRésoluEtExpliquéDansLeCache() throws Exception {
        try (DépôtLocal dépôt = DépôtLocal.démarrer()) {
            Coordinate lib = new Coordinate("demo", "lib-android", "1.0");
            dépôt.publierPom(lib, pom("demo", "lib-android", "1.0")
                    .replace("<version>1.0</version>",
                            "<version>1.0</version><packaging>aar</packaging>"));
            dépôt.publierAar(lib, aar("demo.lib"));

            DependencyResolver.Result résultat = résolveur(dépôt, false,
                            new ArrayList<>())
                    .resolve(List.of(new DependencyResolver.RootDependency(
                            lib, DependencyScope.API)), ConflictPolicy.NEWEST);

            assertEquals(1, résultat.compileClasspath().size());
            DependencyResolver.ResolvedJar résolu = résultat.compileClasspath().get(0);
            assertEquals("aar", résolu.packaging(),
                    "l'empaquetage est porté par l'artefact résolu");
            assertTrue(résolu.jar().endsWith("classes.jar"),
                    "l'entrée de classpath est le classes.jar explosé : "
                            + résolu.jar());
            assertTrue(Files.isDirectory(résolu.exploded().resolve("res")),
                    "le répertoire d'explosion porte res/ pour la chaîne Android");
            assertTrue(Files.isRegularFile(
                    résolu.exploded().resolve("AndroidManifest.xml")),
                    "…et le manifeste de la bibliothèque pour la fusion");
        }
    }

    @Test
    void aarExigeantMinCompileSdkRefuséNettement() throws Exception {
        try (DépôtLocal dépôt = DépôtLocal.démarrer()) {
            Coordinate lib = new Coordinate("demo", "lib-exigeante", "1.0");
            java.io.ByteArrayOutputStream octets = new java.io.ByteArrayOutputStream();
            try (java.util.zip.ZipOutputStream zip =
                         new java.util.zip.ZipOutputStream(octets)) {
                zip.putNextEntry(new java.util.zip.ZipEntry("AndroidManifest.xml"));
                zip.write("<manifest package=\"demo.lib\"/>".getBytes(
                        java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
                zip.putNextEntry(new java.util.zip.ZipEntry(
                        "META-INF/com/android/build/gradle/aar-metadata.properties"));
                zip.write("minCompileSdk=35\n".getBytes(
                        java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            dépôt.publierPom(lib, pom("demo", "lib-exigeante", "1.0")
                    .replace("<version>1.0</version>",
                            "<version>1.0</version><packaging>aar</packaging>"));
            dépôt.publierAar(lib, octets.toByteArray());

            DependencyResolver résolveur = new DependencyResolver(
                    List.of(dépôt.url()), new ResolverCache(temporaire.resolve("c2")),
                    false, message -> { }, true, 34);
            ResolverException refus = assertThrows(ResolverException.class, () ->
                    résolveur.resolve(List.of(new DependencyResolver.RootDependency(
                            lib, DependencyScope.API)), ConflictPolicy.NEWEST));
            assertTrue(refus.getMessage().contains("35")
                            && refus.getMessage().contains("targetSdk"),
                    "la borne minCompileSdk de l'AAR est un échec de BUILD, pas "
                            + "d'exécution : " + refus.getMessage());
        }
    }

    @Test
    void métadonnéesGradleRedirigentVersLaVarianteAndroid() throws Exception {
        try (DépôtLocal dépôt = DépôtLocal.démarrer()) {
            Coordinate racine = new Coordinate("demo", "lib", "1.0");
            Coordinate android = new Coordinate("demo", "lib-android", "1.0");
            Coordinate autre = new Coordinate("demo", "autre", "1.0");
            // Racine KMP : les POMs pont androidx portent des versions
            // placeholder — c'est le .module qui dit quoi suivre.
            dépôt.publierPom(racine, pom("demo", "lib", "1.0"));
            dépôt.publierModule(racine, """
                    { "component": { "group": "demo", "module": "lib", "version": "1.0" },
                      "variants": [
                        { "name": "releaseApiElements-published",
                          "attributes": { "org.gradle.category": "library",
                                          "org.gradle.usage": "java-api",
                                          "org.jetbrains.kotlin.platform.type": "androidJvm" },
                          "available-at": { "group": "demo", "module": "lib-android",
                                            "version": "1.0" } },
                        { "name": "jvmApiElements-published",
                          "attributes": { "org.gradle.category": "library",
                                          "org.gradle.usage": "java-api",
                                          "org.jetbrains.kotlin.platform.type": "jvm" },
                          "available-at": { "group": "demo", "module": "lib-jvm",
                                            "version": "9.9" } }
                      ] }
                    """);
            // Le module porteur : fichier de VARIANTE (nom non conventionnel),
            // dépendance api, contrainte d'alignement.
            dépôt.publierPom(android, pom("demo", "lib-android", "1.0"));
            dépôt.publierModule(android, """
                    { "component": { "group": "demo", "module": "lib-android", "version": "1.0" },
                      "variants": [
                        { "name": "releaseApiElements-published",
                          "attributes": { "org.gradle.category": "library",
                                          "org.gradle.usage": "java-api",
                                          "org.jetbrains.kotlin.platform.type": "androidJvm",
                                          "org.gradle.jvm.environment": "android" },
                          "dependencies": [
                            { "group": "demo", "module": "autre",
                              "version": { "requires": "1.0" } } ],
                          "dependencyConstraints": [
                            { "group": "demo", "module": "autre",
                              "version": { "requires": "1.0.5" } } ],
                          "files": [ { "name": "lib-release.aar", "url": "lib-release.aar" } ] }
                      ] }
                    """);
            dépôt.publierFichier("/demo/lib-android/1.0/lib-release.aar",
                    aar("demo.lib"));
            // La contrainte aligne "autre" vers 1.0.5 : les DEUX versions sont
            // publiées, NEWEST ne doit pas trancher au hasard.
            dépôt.publierPom(autre, pom("demo", "autre", "1.0"));
            dépôt.publierJar(autre, jarBidon());
            Coordinate autreRécent = new Coordinate("demo", "autre", "1.0.5");
            dépôt.publierPom(autreRécent, pom("demo", "autre", "1.0.5"));
            dépôt.publierJar(autreRécent, jarBidon());

            DependencyResolver.Result résultat = new DependencyResolver(
                    List.of(dépôt.url()), new ResolverCache(temporaire.resolve("gmm")),
                    false, message -> { }, true, -1)
                    .resolve(List.of(new DependencyResolver.RootDependency(
                            racine, DependencyScope.API)), ConflictPolicy.NEWEST);

            List<String> compile = résultat.compileClasspath().stream()
                    .map(jar -> jar.coordinate().toString()).toList();
            assertTrue(compile.contains("demo:lib-android:1.0"),
                    "la racine KMP est résolue par sa variante -android : " + compile);
            assertTrue(compile.stream().noneMatch(c -> c.contains("lib-jvm")),
                    "la variante -jvm n'est JAMAIS tirée pour une cible Android");
            assertTrue(compile.contains("demo:autre:1.0.5"),
                    "la contrainte d'alignement Gradle aligne la version : " + compile);
            assertTrue(résultat.compileClasspath().get(0).jar()
                            .endsWith("classes.jar"),
                    "l'artefact (publié SOUS LE NOM DE VARIANTE lib-release.aar) est "
                            + "téléchargé puis explosé — le classes.jar sert de "
                            + "classpath : " + résultat.compileClasspath().get(0).jar());
        }
    }

    @Test
    void conflitDeCapacitésPasserelleContreVariante() throws Exception {
        // La famille {famille, famille-jvm} : 1.2.0 demandé par un POM (sans
        // métadonnées Gradle), 1.8.0 par une variante Gradle (relogée vers
        // famille-jvm). UN SEUL artefact doit sortir : famille-jvm:1.8.0 —
        // sinon les classes en double tuent le dexage.
        try (DépôtLocal dépôt = DépôtLocal.démarrer()) {
            Coordinate racine = new Coordinate("demo", "app", "1.0");
            Coordinate pomLib = new Coordinate("demo", "pom-lib", "1.0");
            Coordinate gmmLib = new Coordinate("demo", "gmm-lib", "1.0");
            Coordinate famille = new Coordinate("demo", "famille", "1.0");
            Coordinate famille12 = new Coordinate("demo", "famille", "1.2.0");
            Coordinate famille18 = new Coordinate("demo", "famille", "1.8.0");
            Coordinate familleJvm = new Coordinate("demo", "famille-jvm", "1.8.0");

            publier(dépôt, racine, pom("demo", "app", "1.0", deps(
                    dépendance("demo", "pom-lib", "1.0", null),
                    dépendance("demo", "gmm-lib", "1.0", null))));
            publier(dépôt, pomLib, pom("demo", "pom-lib", "1.0", deps(
                    dépendance("demo", "famille", "1.2.0", null))));
            // gmm-lib : métadonnées Gradle exigeant famille 1.8.0.
            dépôt.publierPom(gmmLib, pom("demo", "gmm-lib", "1.0"));
            dépôt.publierJar(gmmLib, jarBidon());
            dépôt.publierModule(gmmLib, """
                    { "component": { "group": "demo", "module": "gmm-lib", "version": "1.0" },
                      "variants": [
                        { "name": "releaseApiElements-published",
                          "attributes": { "org.gradle.category": "library",
                                          "org.gradle.usage": "java-api",
                                          "org.jetbrains.kotlin.platform.type": "jvm" },
                          "dependencies": [
                            { "group": "demo", "module": "famille",
                              "version": { "requires": "1.8.0" } } ] }
                      ] }
                    """);
            // famille 1.2.0 : SANS métadonnées Gradle (chemin POM, comme les
            // vieux artefacts androidx).
            publier(dépôt, famille12, pom("demo", "famille", "1.2.0"));
            // famille 1.8.0 : passerelle KMP redirigeant vers famille-jvm.
            dépôt.publierPom(famille18, pom("demo", "famille", "1.8.0"));
            dépôt.publierModule(famille18, """
                    { "component": { "group": "demo", "module": "famille", "version": "1.8.0" },
                      "variants": [
                        { "name": "releaseApiElements-published",
                          "attributes": { "org.gradle.category": "library",
                                          "org.gradle.usage": "java-api",
                                          "org.jetbrains.kotlin.platform.type": "jvm" },
                          "available-at": { "group": "demo", "module": "famille-jvm",
                                            "version": "1.8.0" } }
                      ] }
                    """);
            // famille (toute version) : les coordonnées INCONNUES ne doivent pas
            // sortir — seul 1.8.0 est publié en plus de 1.2.0.
            publier(dépôt, familleJvm, pom("demo", "famille-jvm", "1.8.0"));
            dépôt.publierModule(familleJvm, """
                    { "component": { "group": "demo", "module": "famille-jvm", "version": "1.8.0" },
                      "variants": [
                        { "name": "releaseApiElements-published",
                          "attributes": { "org.gradle.category": "library",
                                          "org.gradle.usage": "java-api",
                                          "org.jetbrains.kotlin.platform.type": "jvm" },
                          "files": [ { "name": "famille-jvm-1.8.0.jar",
                                       "url": "famille-jvm-1.8.0.jar" } ] }
                      ] }
                    """);

            DependencyResolver.Result résultat = new DependencyResolver(
                    List.of(dépôt.url()), new ResolverCache(temporaire.resolve("cap")),
                    false, message -> { }, false, -1)
                    .resolve(List.of(new DependencyResolver.RootDependency(
                            racine, DependencyScope.API)), ConflictPolicy.NEWEST);

            List<String> compile = résultat.compileClasspath().stream()
                    .map(jar -> jar.coordinate().toString()).toList();
            assertTrue(compile.contains("demo:famille-jvm:1.8.0"),
                    "la version la plus récente de la FAMILLE gagne, portée par sa "
                            + "variante : " + compile);
            assertTrue(compile.stream().noneMatch(c -> c.startsWith("demo:famille:")),
                    "la passerelle ne sort JAMAIS comme artefact (sinon doublon de "
                            + "classes au dexage) : " + compile);
            assertEquals(4, compile.size(),
                    "app, pom-lib, gmm-lib, famille-jvm — rien d'autre : " + compile);
        }
    }

    @Test
    void plageExacteRéduiteEtPlageOuverteRefusée() {
        assertEquals("1.9.2", DependencyResolver.versionExacte("[1.9.2]"),
                "le pont exact des éditeurs Gradle vaut la version délimitée");
        assertEquals("1.0", DependencyResolver.versionExacte("1.0"));
        ResolverException refus = assertThrows(ResolverException.class,
                () -> DependencyResolver.versionExacte("[1.0,2.0)"));
        assertTrue(refus.getMessage().contains("[1.0,2.0)"),
                "une vraie plage est refusée nommément : " + refus.getMessage());
    }
}
