package jo.forge.engine.deps;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests du parseur POM durci : champs, parents, gestion, exclusions, relogement —
 * et défenses (DOCTYPE rejeté — ABSOLUMENT et sur tout runtime, XML invalide
 * rejeté). Depuis la v0.2.17, le durcissement et son double ART (fabrique sans
 * fonctionnalités) vivent dans {@link jo.forge.engine.util.DomSûr} — testés
 * par {@code DomSûrTest}, PARTAGÉS avec la fusion de manifestes.
 */
class PomParserTest {

    @TempDir
    Path temporaire;

    private static final Coordinate COORD = new Coordinate("com.exemple", "outil", "1.0");

    @Test
    void litUnPomComplet() {
        RawPom pom = PomParser.parse(new ByteArrayInputStream("""
                <?xml version="1.0"?>
                <project>
                  <groupId>com.exemple</groupId>
                  <artifactId>outil</artifactId>
                  <version>1.0</version>
                  <packaging>jar</packaging>
                  <properties>
                    <version.cible>2.1</version.cible>
                  </properties>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>com.exemple</groupId>
                        <artifactId>lib-standard</artifactId>
                        <version>5.0</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                  <dependencies>
                    <dependency>
                      <groupId>com.exemple</groupId>
                      <artifactId>lib-standard</artifactId>
                      <scope>runtime</scope>
                    </dependency>
                    <dependency>
                      <groupId>org.autre</groupId>
                      <artifactId>chose</artifactId>
                      <version>1.5</version>
                      <optional>true</optional>
                      <exclusions>
                        <exclusion>
                          <groupId>org.banni</groupId>
                          <artifactId>vieux</artifactId>
                        </exclusion>
                      </exclusions>
                    </dependency>
                  </dependencies>
                </project>
                """.getBytes(StandardCharsets.UTF_8)), COORD);

        assertEquals("com.exemple", pom.group());
        assertEquals("jar", pom.packaging());
        assertEquals(1, pom.managed().size());
        assertEquals("5.0", pom.managed().get(0).version());
        assertEquals(2, pom.dependencies().size());
        RawPom.PomDep sansVersion = pom.dependencies().get(0);
        assertEquals("runtime", sansVersion.scope());
        assertEquals(null, sansVersion.version());
        RawPom.PomDep optionnelle = pom.dependencies().get(1);
        assertTrue(optionnelle.optional());
        assertEquals(1, optionnelle.exclusions().size());
        assertEquals(new Gav("org.banni", "vieux"),
                optionnelle.exclusions().iterator().next());
    }

    @Test
    void litParentPropriétésEtRelogement() {
        RawPom pom = PomParser.parse(new ByteArrayInputStream("""
                <project>
                  <parent>
                    <groupId>com.exemple</groupId>
                    <artifactId>parent</artifactId>
                    <version>3.0</version>
                  </parent>
                  <artifactId>enfant</artifactId>
                  <properties><a>b</a></properties>
                  <distributionManagement>
                    <relocation>
                      <groupId>com.nouveau</groupId>
                      <artifactId>outil-neuf</artifactId>
                    </relocation>
                  </distributionManagement>
                </project>
                """.getBytes(StandardCharsets.UTF_8)), COORD);

        assertEquals(new Coordinate("com.exemple", "parent", "3.0"), pom.parent());
        assertEquals("com.exemple", pom.group(), "le groupe vient du parent");
        assertEquals("3.0", pom.version(), "la version vient du parent");
        assertEquals("b", pom.properties().get("a"));
        assertNotNull(pom.relocation());
        assertEquals("com.nouveau", pom.relocation().group());
        assertEquals("1.0", pom.relocation().version(),
                "la version de relogement garde celle de la cible");
    }

    @Test
    void doctypeRejeté() {
        ResolverException rejet = assertThrows(ResolverException.class, () ->
                PomParser.parse(new ByteArrayInputStream("""
                        <!DOCTYPE project SYSTEM "http://attaquant/xxe.dtd">
                        <project><groupId>g</groupId><artifactId>a</artifactId>
                        <version>1</version></project>
                        """.getBytes(StandardCharsets.UTF_8)), COORD));
        assertTrue(rejet.getMessage().contains("POM invalide"),
                "le DOCTYPE doit être refusé : " + rejet.getMessage());
    }

    @Test
    void doctypeRejetéAprèsDéclarationXmlEtCommentaire() {
        // Le scan du prologue traverse déclaration XML et commentaire : un
        // DOCTYPE y est toujours refusé, avec le même message sur TOUT runtime
        // (sur ART, la fonctionnalité Xerces qui portait cette garde n'existe
        // pas — c'est le scan qui garantit le refus).
        ResolverException rejet = assertThrows(ResolverException.class, () ->
                PomParser.parse(("""
                        <?xml version="1.0" encoding="UTF-8"?>
                        <!-- un commentaire honnête -->
                        <!DOCTYPE project>
                        <project/>
                        """).getBytes(StandardCharsets.UTF_8), COORD));
        assertTrue(rejet.getMessage().contains("DOCTYPE interdit"),
                "le DOCTYPE du prologue est refusé : " + rejet.getMessage());
    }

    @Test
    void doctypeCitéDansUneCdataDuContenuPasRejeté() {
        // Une CDATA du CONTENU peut citer « <!DOCTYPE » sans danger : le scan ne
        // lit que le prologue (avant la racine) — pas de faux positif.
        RawPom pom = PomParser.parse(("""
                <project>
                  <groupId>g</groupId><artifactId>a</artifactId><version>1</version>
                  <properties><doc><![CDATA[ <!DOCTYPE piège> ]]></doc></properties>
                </project>
                """).getBytes(StandardCharsets.UTF_8), COORD);
        assertTrue(pom.properties().get("doc").contains("DOCTYPE"),
                "la CDATA du contenu passe le scan du prologue");
    }

    @Test
    void bomUtf8NeMasquePasUnDoctype() {
        // Un BOM UTF-8 ne doit pas masquer un DOCTYPE au scan du prologue
        // (les octets du BOM, décodés autrement, précéderaient la déclaration).
        byte[] attaque = octetsAvecBom(("<!DOCTYPE project>"
                + "<project><groupId>g</groupId><artifactId>a</artifactId>"
                + "<version>1</version></project>").getBytes(StandardCharsets.UTF_8));
        ResolverException rejet = assertThrows(ResolverException.class, () ->
                PomParser.parse(attaque, COORD));
        assertTrue(rejet.getMessage().contains("DOCTYPE interdit"),
                "BOM + DOCTYPE : refusé quand même : " + rejet.getMessage());
    }

    @Test
    void bomUtf8PuisPomValideAccepté() {
        assertEquals("g", PomParser.parse(octetsAvecBom((
                "<project><groupId>g</groupId><artifactId>a</artifactId>"
                + "<version>1</version></project>")
                .getBytes(StandardCharsets.UTF_8)), COORD).group());
    }

    @Test
    void racineInattendueRejetée() {
        assertThrows(ResolverException.class, () ->
                PomParser.parse(new ByteArrayInputStream(
                        "<pasUnProjet/>".getBytes(StandardCharsets.UTF_8)), COORD));
    }

    /** Préfixe les octets d'un BOM UTF-8. */
    private static byte[] octetsAvecBom(byte[] corps) {
        byte[] avecBom = new byte[3 + corps.length];
        avecBom[0] = (byte) 0xEF;
        avecBom[1] = (byte) 0xBB;
        avecBom[2] = (byte) 0xBF;
        System.arraycopy(corps, 0, avecBom, 3, corps.length);
        return avecBom;
    }
}
