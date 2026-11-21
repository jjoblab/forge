package jo.forge.engine.deps;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Cache de résolution : réplique de la disposition d'un dépôt Maven — le cache EST le
 * dépôt hors ligne.
 *
 * <p>Disposition : {@code <racine>/<groupe en chemin>/<artefact>/<version>/…}. Les
 * écritures sont atomiques (temporaire + renommage, jamais de fichier à moitié écrit).
 * Les échecs de téléchargement définitifs (404) sont mémorisés par un marqueur horodaté
 * avec une durée de vie : un dépôt qui n'a pas l'artefact n'est pas réinterrogé en
 * boucle, mais un artefact publié entre-temps finit par être vu.</p>
 *
 * <p>Différence assumée avec le moteur de référence : notre cache est conforme à la
 * disposition Maven (la leur aussi), mais chaque artefact pose un marqueur d'intégrité
 * (somme vérifiée au téléchargement) — voir {@code HttpTransport}.</p>
 */
public final class ResolverCache {

    /** Durée de vie d'un marqueur « introuvable » avant nouvel essai. */
    static final Duration TTL_NÉGATIF = Duration.ofDays(7);

    private final Path racine;

    /**
     * Crée le cache.
     *
     * @param racine répertoire racine du cache (créé s'il manque).
     */
    public ResolverCache(Path racine) {
        this.racine = racine;
    }

    /**
     * Chemin du POM d'une coordonnée.
     *
     * @param coordonnée la coordonnée.
     * @return le chemin dans le cache.
     */
    public Path cheminPom(Coordinate coordonnée) {
        return répertoire(coordonnée).resolve(
                coordonnée.artifact() + "-" + coordonnée.version() + ".pom");
    }

    /**
     * Chemin de l'artefact (jar) d'une coordonnée.
     *
     * @param coordonnée la coordonnée.
     * @return le chemin dans le cache.
     */
    public Path cheminArtefact(Coordinate coordonnée) {
        return cheminArtefact(coordonnée, ".jar");
    }

    /**
     * Chemin de l'artefact d'une coordonnée pour une extension donnée
     * ({@code .jar} ou {@code .aar}) — l'artefact est mis en cache sous son nom
     * Maven conventionnel, quelle que soit l'URL réelle du téléchargement (les
     * fichiers de variante Gradle portent d'autres noms, ex. {@code ui-release.aar}).
     *
     * @param coordonnée la coordonnée.
     * @param extension  {@code ".jar"} ou {@code ".aar"}.
     * @return le chemin dans le cache.
     */
    public Path cheminArtefact(Coordinate coordonnée, String extension) {
        return répertoire(coordonnée).resolve(
                coordonnée.artifact() + "-" + coordonnée.version() + extension);
    }

    /**
     * Indique si l'artefact (jar) est en cache.
     *
     * @param coordonnée la coordonnée.
     * @return {@code true} si présent.
     */
    public boolean aArtefact(Coordinate coordonnée) {
        return aArtefact(coordonnée, ".jar");
    }

    /**
     * Indique si l'artefact d'une extension donnée est en cache.
     *
     * @param coordonnée la coordonnée.
     * @param extension  {@code ".jar"} ou {@code ".aar"}.
     * @return {@code true} si présent.
     */
    public boolean aArtefact(Coordinate coordonnée, String extension) {
        return Files.isRegularFile(cheminArtefact(coordonnée, extension));
    }

    /**
     * Répertoire de version d'une coordonnée (public pour l'explosion AAR à
     * côté de l'archive, dans la réplique Maven du cache).
     *
     * @param coordonnée la coordonnée.
     * @return le répertoire (créé s'il manque).
     */
    public Path répertoireDe(Coordinate coordonnée) {
        return répertoire(coordonnée);
    }

    /**
     * Chemin des métadonnées de module Gradle ({@code .module}) d'une coordonnée.
     *
     * @param coordonnée la coordonnée.
     * @return le chemin dans le cache.
     */
    public Path cheminModule(Coordinate coordonnée) {
        return répertoire(coordonnée).resolve(
                coordonnée.artifact() + "-" + coordonnée.version() + ".module");
    }

    /**
     * Indique si les métadonnées de module Gradle sont en cache.
     *
     * @param coordonnée la coordonnée.
     * @return {@code true} si présentes.
     */
    public boolean aModule(Coordinate coordonnée) {
        return Files.isRegularFile(cheminModule(coordonnée));
    }

    /**
     * Écrit des métadonnées de module Gradle dans le cache de façon atomique.
     *
     * @param coordonnée la coordonnée.
     * @param contenu    le flux JSON (fermé par l'appelant).
     * @return le chemin écrit.
     * @throws IOException si l'écriture échoue.
     */
    public Path écrireModule(Coordinate coordonnée, InputStream contenu) throws IOException {
        return écritureAtomique(cheminModule(coordonnée), contenu);
    }

    /**
     * Répertoire d'explosion d'un AAR dans le cache (frère de l'archive).
     *
     * @param coordonnée la coordonnée.
     * @return le chemin du répertoire {@code <artefact>-<version>-exploded}.
     */
    public Path répertoireExplosion(Coordinate coordonnée) {
        return répertoire(coordonnée).resolve(
                coordonnée.artifact() + "-" + coordonnée.version() + "-exploded");
    }

    /**
     * Indique si le POM est en cache.
     *
     * @param coordonnée la coordonnée.
     * @return {@code true} si présent.
     */
    public boolean aPom(Coordinate coordonnée) {
        return Files.isRegularFile(cheminPom(coordonnée));
    }

    /**
     * Écrit un POM dans le cache de façon atomique.
     *
     * @param coordonnée la coordonnée.
     * @param contenu    le flux XML (fermé par l'appelant).
     * @return le chemin écrit.
     * @throws IOException si l'écriture échoue.
     */
    public Path écrirePom(Coordinate coordonnée, InputStream contenu) throws IOException {
        return écritureAtomique(cheminPom(coordonnée), contenu);
    }

    /**
     * Écrit un artefact dans le cache de façon atomique.
     *
     * @param coordonnée la coordonnée.
     * @param contenu    le flux binaire (fermé par l'appelant).
     * @return le chemin écrit.
     * @throws IOException si l'écriture échoue.
     */
    public Path écrireArtefact(Coordinate coordonnée, InputStream contenu)
            throws IOException {
        return écritureAtomique(cheminArtefact(coordonnée), contenu);
    }

    /**
     * Mémorise un échec définitif (introuvable) pour cette coordonnée.
     *
     * @param coordonnée la coordonnée.
     * @param sorte      {@code "pom"}, {@code "module"} ou {@code "jar"}
     *                   (marqueurs distincts).
     */
    public void marquerIntrouvable(Coordinate coordonnée, String sorte) {
        try {
            Files.createDirectories(répertoire(coordonnée));
            Files.writeString(répertoire(coordonnée).resolve("introuvable-" + sorte),
                    Long.toString(Instant.now().toEpochMilli()));
        } catch (IOException ignored) {
            // Le marqueur est une optimisation, pas une obligation.
        }
    }

    /**
     * Indique si la coordonnée a été marquée introuvable récemment.
     *
     * @param coordonnée la coordonnée.
     * @param sorte      {@code "pom"}, {@code "module"} ou {@code "jar"}.
     * @return {@code true} si un marqueur récent existe.
     */
    public boolean marquéIntrouvableRécemment(Coordinate coordonnée, String sorte) {
        Path marqueur = répertoire(coordonnée).resolve("introuvable-" + sorte);
        if (!Files.isRegularFile(marqueur)) {
            return false;
        }
        try {
            Instant horodatage = Instant.ofEpochMilli(
                    Long.parseLong(Files.readString(marqueur).trim()));
            return Instant.now().isBefore(horodatage.plus(TTL_NÉGATIF));
        } catch (Exception illisible) {
            return false; // Marqueur illisible : on retente.
        }
    }

    /**
     * Répertoire de version d'une coordonnée (créé s'il manque).
     */
    private Path répertoire(Coordinate coordonnée) {
        Path répertoire = racine
                .resolve(coordonnée.group().replace('.', '/'))
                .resolve(coordonnée.artifact())
                .resolve(coordonnée.version());
        try {
            Files.createDirectories(répertoire);
        } catch (IOException e) {
            throw new ResolverException("Cache de dépendances non créable : " + répertoire
                    + " (" + e.getMessage() + ")", e);
        }
        return répertoire;
    }

    /**
     * Écriture atomique : temporaire caché puis renommage ; le temporaire est détruit
     * à l'échec.
     */
    private static Path écritureAtomique(Path cible, InputStream contenu)
            throws IOException {
        Files.createDirectories(cible.getParent());
        Path temporaire = cible.resolveSibling(cible.getFileName() + ".part");
        try {
            try (var sortie = Files.newOutputStream(temporaire)) {
                contenu.transferTo(sortie);
            }
            try {
                Files.move(temporaire, cible, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException pasAtomique) {
                // Système de fichiers sans renommage atomique : repli sur move simple.
                Files.move(temporaire, cible, StandardCopyOption.REPLACE_EXISTING);
            }
            return cible;
        } catch (IOException echec) {
            Files.deleteIfExists(temporaire);
            throw echec;
        }
    }

    /**
     * Liste les jars du cache pour une coordonnée (diagnostics et tests).
     *
     * @param coordonnée la coordonnée.
     * @return les chemins (ici, un seul artefact principal).
     */
    public List<Path> jarsDe(Coordinate coordonnée) {
        return aArtefact(coordonnée) ? List.of(cheminArtefact(coordonnée)) : List.of();
    }
}
