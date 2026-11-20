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
        return répertoire(coordonnée).resolve(
                coordonnée.artifact() + "-" + coordonnée.version() + ".jar");
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
     * Indique si l'artefact est en cache.
     *
     * @param coordonnée la coordonnée.
     * @return {@code true} si présent.
     */
    public boolean aArtefact(Coordinate coordonnée) {
        return Files.isRegularFile(cheminArtefact(coordonnée));
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
     * @param sorte      {@code "pom"} ou {@code "jar"} (marqueurs distincts).
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
     * @param sorte      {@code "pom"} ou {@code "jar"}.
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
