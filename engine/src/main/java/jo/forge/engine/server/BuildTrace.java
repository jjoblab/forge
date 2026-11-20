package jo.forge.engine.server;

import jo.forge.json.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Trace de build exportable (amélioration A-13) : un fichier JSON par build, sous
 * {@code .forge/traces/<identifiant>.json} — tâches avec états et durées, diagnostics
 * par sévérité, versions du moteur, journal borné.
 *
 * <p>Écriture atomique (temporaire + renommage) : une trace ne peut jamais être à
 * moitié écrite, même sur un arrêt brutal.</p>
 */
public final class BuildTrace {

    /** Version du format de trace. */
    private static final long VERSION_FORMAT = 1;

    /** État final d'une tâche dans la trace. */
    public record Tâche(String nom, String état, long duréeMs) {
    }

    private final String buildId;
    private final String projet;
    private final String versionMoteur;
    private final List<Tâche> tâches = new ArrayList<>();
    private final Map<String, Long> diagnosticsParSévérité = new LinkedHashMap<>();
    private final List<String> journal;

    /**
     * Crée la trace d'un build terminé.
     *
     * @param buildId        identifiant du build.
     * @param projet         racine du projet construit.
     * @param versionMoteur  version du moteur.
     * @param tâches         états finaux des tâches.
     * @param diagnosticsParSévérité nombre de diagnostics par sévérité.
     * @param journal        journal borné du build (déjà rédigé des secrets).
     */
    public BuildTrace(String buildId, String projet, String versionMoteur,
                      List<Tâche> tâches, Map<String, Long> diagnosticsParSévérité,
                      List<String> journal) {
        this.buildId = buildId;
        this.projet = projet;
        this.versionMoteur = versionMoteur;
        this.tâches.addAll(tâches);
        this.diagnosticsParSévérité.putAll(diagnosticsParSévérité);
        this.journal = List.copyOf(journal);
    }

    /**
     * Écrit la trace de façon atomique.
     *
     * @param répertoireTraces répertoire {@code .forge/traces} du projet.
     * @param duréeMs          durée totale du build.
     * @param succès           résultat global.
     * @return le chemin du fichier écrit.
     * @throws IOException si l'écriture échoue.
     */
    public Path écrire(Path répertoireTraces, long duréeMs, boolean succès)
            throws IOException {
        Map<String, Object> racine = new LinkedHashMap<>();
        racine.put("version", VERSION_FORMAT);
        racine.put("buildId", buildId);
        racine.put("projet", projet);
        racine.put("moteur", versionMoteur);
        racine.put("succès", succès);
        racine.put("duréeMs", duréeMs);
        List<Object> tâchesJson = new ArrayList<>();
        for (Tâche tâche : tâches) {
            Map<String, Object> carte = new LinkedHashMap<>();
            carte.put("nom", tâche.nom());
            carte.put("état", tâche.état());
            carte.put("duréeMs", tâche.duréeMs());
            tâchesJson.add(carte);
        }
        racine.put("tâches", tâchesJson);
        racine.put("diagnostics", diagnosticsParSévérité);
        racine.put("journal", journal);

        Files.createDirectories(répertoireTraces);
        Path cible = répertoireTraces.resolve(buildId + ".json");
        Path temporaire = cible.resolveSibling(cible.getFileName() + ".part");
        Files.writeString(temporaire, Json.write(racine));
        try {
            Files.move(temporaire, cible, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException pasAtomique) {
            Files.move(temporaire, cible, StandardCopyOption.REPLACE_EXISTING);
        }
        return cible;
    }
}
