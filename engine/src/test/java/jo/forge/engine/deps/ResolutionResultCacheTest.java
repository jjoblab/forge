package jo.forge.engine.deps;

import jo.forge.engine.model.DependencyScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests du cache de résultat de résolution (décision D-13) : empreinte stable et
 * sensible aux entrées, aller-retour d'écriture/lecture, invalidation par empreinte
 * différente, fichier illisible traité en échec de cache, racines SNAPSHOT
 * non mémorisables.
 */
class ResolutionResultCacheTest {

    @TempDir
    Path temporaire;

    private static List<DependencyResolver.RootDependency> racines(String version) {
        return List.of(new DependencyResolver.RootDependency(
                new Coordinate("com.exemple", "bibli", version), DependencyScope.API));
    }

    @Test
    void empreinteStableEtSensibleAuxEntrées() {
        List<String> dépôts = List.of("https://repo.exemple/maven2");
        String base = ResolutionResultCache.empreinte(racines("1.0"), dépôts, true, 34,
                ConflictPolicy.NEWEST);
        assertEquals(base, ResolutionResultCache.empreinte(racines("1.0"), dépôts, true,
                34, ConflictPolicy.NEWEST));
        // Version différente, portée différente, cible différente, dépôt différent,
        // médiation différente : autant d'empreintes distinctes.
        assertNotEquals(base, ResolutionResultCache.empreinte(racines("1.1"), dépôts,
                true, 34, ConflictPolicy.NEWEST));
        assertNotEquals(base, ResolutionResultCache.empreinte(
                List.of(new DependencyResolver.RootDependency(
                        new Coordinate("com.exemple", "bibli", "1.0"),
                        DependencyScope.IMPLEMENTATION)),
                dépôts, true, 34, ConflictPolicy.NEWEST));
        assertNotEquals(base, ResolutionResultCache.empreinte(racines("1.0"), dépôts,
                false, 34, ConflictPolicy.NEWEST));
        assertNotEquals(base, ResolutionResultCache.empreinte(racines("1.0"),
                List.of("https://autre.exemple/maven2"), true, 34,
                ConflictPolicy.NEWEST));
        assertNotEquals(base, ResolutionResultCache.empreinte(racines("1.0"), dépôts,
                true, 34, ConflictPolicy.FAIL_ON_CONFLICT));
    }

    @Test
    void allerRetourÉcritureLecture() {
        ResolutionResultCache cache = new ResolutionResultCache(temporaire);
        List<ResolutionResultCache.EntréeMémorisée> compile = List.of(
                new ResolutionResultCache.EntréeMémorisée(
                        new Coordinate("com.exemple", "a", "1.0"), "jar"),
                new ResolutionResultCache.EntréeMémorisée(
                        new Coordinate("com.exemple", "b", "2.0"), "aar"));
        cache.écrire("app", "emp", compile, compile);
        Optional<ResolutionResultCache.Mémoire> lue = cache.lire("app", "emp");
        assertTrue(lue.isPresent());
        assertEquals(compile, lue.get().compile());
        assertEquals(compile, lue.get().runtime());
    }

    @Test
    void empreinteDifférenteOuModuleDifférentInvalide() {
        ResolutionResultCache cache = new ResolutionResultCache(temporaire);
        List<ResolutionResultCache.EntréeMémorisée> entrées = List.of(
                new ResolutionResultCache.EntréeMémorisée(
                        new Coordinate("com.exemple", "a", "1.0"), "jar"));
        cache.écrire("app", "emp1", entrées, entrées);
        assertTrue(cache.lire("app", "emp1").isPresent());
        assertFalse(cache.lire("app", "emp2").isPresent(), "empreinte différente");
        assertFalse(cache.lire("autre", "emp1").isPresent(), "module différent");
    }

    @Test
    void fichierIllisibleEstUnÉchecDeCachePasDuBuild() throws Exception {
        ResolutionResultCache cache = new ResolutionResultCache(temporaire);
        Files.createDirectories(temporaire);
        Files.writeString(temporaire.resolve(
                hex("app") + ".json"), "{ceci n'est pas du json");
        assertFalse(cache.lire("app", "emp").isPresent());
    }

    @Test
    void racinesSnapshotNonMémorisables() {
        assertTrue(ResolutionResultCache.mémorisable(racines("1.0.0")));
        assertFalse(ResolutionResultCache.mémorisable(racines("1.0.0-SNAPSHOT")));
        assertFalse(ResolutionResultCache.mémorisable(List.of(
                new DependencyResolver.RootDependency(
                        new Coordinate("com.exemple", "a", "1.0.0"),
                        DependencyScope.API),
                new DependencyResolver.RootDependency(
                        new Coordinate("com.exemple", "b", "2.0-SNAPSHOT"),
                        DependencyScope.RUNTIME_ONLY))));
    }

    /** Encodage hexadécimal d'un nom de module (même règle que le cache). */
    private static String hex(String module) {
        StringBuilder texte = new StringBuilder();
        for (byte octet : module.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            texte.append(Character.forDigit((octet >> 4) & 0xF, 16));
            texte.append(Character.forDigit(octet & 0xF, 16));
        }
        return texte.toString();
    }
}
