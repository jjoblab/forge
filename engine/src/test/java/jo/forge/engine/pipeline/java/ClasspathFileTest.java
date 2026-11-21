package jo.forge.engine.pipeline.java;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests du format v2 du fichier de classpath résolu (v0.2.15) : entrées AAR
 * (packaging + répertoire d'explosion), jars d'un AAR = classes.jar +
 * libs/*.jar, rétrocompatibilité du format v1 (entrées sans packaging).
 */
class ClasspathFileTest {

    @TempDir
    Path temporaire;

    @Test
    void allerRetourV2AvecAarEtLibsImbriquées() throws Exception {
        Path exploded = temporaire.resolve("lib-1.0-exploded");
        Files.createDirectories(exploded.resolve("libs"));
        Files.createDirectories(exploded.resolve("res/values"));
        byte[] jarVide = new byte[22];
        Files.write(exploded.resolve("classes.jar"), jarVide);
        Files.write(exploded.resolve("libs").resolve("b.jar"), jarVide);
        Files.write(exploded.resolve("libs").resolve("a.jar"), jarVide);

        Path fichier = temporaire.resolve("classpath.json");
        ClasspathFile.écrire(fichier,
                List.of(new ClasspathFile.Entrée("demo:lib:1.0",
                        exploded.resolve("classes.jar"), null, "aar", exploded)),
                List.of(new ClasspathFile.Entrée("demo:lib:1.0",
                        exploded.resolve("classes.jar"), null, "aar", exploded)));

        List<ClasspathFile.Entrée> entrées = ClasspathFile.lireEntréesCompilation(fichier);
        assertEquals(1, entrées.size());
        assertEquals("aar", entrées.get(0).packaging());
        assertEquals(exploded, entrées.get(0).exploded());
        assertEquals(true, entrées.get(0).estAar());

        // Les jars d'un AAR : classes.jar puis libs/*.jar TRIÉS.
        List<Path> jars = ClasspathFile.lireJarsCompilation(fichier);
        assertEquals(List.of(exploded.resolve("classes.jar"),
                        exploded.resolve("libs").resolve("a.jar"),
                        exploded.resolve("libs").resolve("b.jar")),
                jars, "un AAR contribue par classes.jar + libs/*.jar triés");
    }

    @Test
    void formatV1LuCommeDesJars() throws Exception {
        Path fichier = temporaire.resolve("classpath-v1.json");
        Files.writeString(fichier, """
                { "version": 1,
                  "compile": [ { "coordinate": "demo:a:1.0", "jar": "/ailleurs/a-1.0.jar" } ],
                  "runtime": [ { "coordinate": "demo:a:1.0", "jar": "/ailleurs/a-1.0.jar",
                                 "sha256": "abc" } ] }
                """);

        assertEquals(List.of(Path.of("/ailleurs/a-1.0.jar")),
                ClasspathFile.lireJarsCompilation(fichier),
                "un fichier v1 (sans packaging) se lit comme avant");
        List<ClasspathFile.Entrée> entrées = ClasspathFile.lireEntréesExécution(fichier);
        assertEquals("jar", entrées.get(0).packaging(),
                "l'absence de packaging vaut jar");
        assertEquals(null, entrées.get(0).exploded());
        assertEquals("abc", entrées.get(0).sha256());
    }
}
