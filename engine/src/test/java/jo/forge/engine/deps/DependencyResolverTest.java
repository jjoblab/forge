package jo.forge.engine.deps;

import jo.forge.engine.model.DependencyScope;
import jo.forge.engine.testutil.DépôtLocal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
    void implémentationCacheSesTransitives() throws Exception {
        try (DépôtLocal dépôt = DépôtLocal.démarrer()) {
            Coordinate a = new Coordinate("demo", "lib-a", "1.0");
            Coordinate b = new Coordinate("demo", "lib-b", "1.0");
            publier(dépôt, a, pom("demo", "lib-a", "1.0",
                    deps(dépendance("demo", "lib-b", "1.0", null))));
            publier(dépôt, b, pom("demo", "lib-b", "1.0"));

            DependencyResolver.Result résultat = résolveur(dépôt, false, new ArrayList<>())
                    .resolve(List.of(new DependencyResolver.RootDependency(
                            a, DependencyScope.IMPLEMENTATION)), ConflictPolicy.NEWEST);

            assertEquals(List.of("demo:lib-a:1.0"),
                    résultat.compileClasspath().stream()
                            .map(jar -> jar.coordinate().toString()).toList(),
                    "implementation : la racine seule au classpath de compilation");
            assertEquals(List.of("demo:lib-a:1.0", "demo:lib-b:1.0"),
                    résultat.runtimeClasspath().stream()
                            .map(jar -> jar.coordinate().toString()).toList(),
                    "…mais sa fermeture complète à l'exécution");
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

    @Test
    void empaquetageNonJarRefuséPourLInstant() throws Exception {
        try (DépôtLocal dépôt = DépôtLocal.démarrer()) {
            Coordinate a = new Coordinate("demo", "lib-android", "1.0");
            dépôt.publierPom(a, pom("demo", "lib-android", "1.0")
                    .replace("<version>1.0</version>",
                            "<version>1.0</version><packaging>aar</packaging>"));
            ResolverException refus = assertThrows(ResolverException.class, () ->
                    résolveur(dépôt, false, new ArrayList<>()).resolve(
                            List.of(new DependencyResolver.RootDependency(
                                    a, DependencyScope.API)), ConflictPolicy.NEWEST));
            assertTrue(refus.getMessage().contains("aar"),
                    "le message cite l'empaquetage non supporté : " + refus.getMessage());
        }
    }
}
