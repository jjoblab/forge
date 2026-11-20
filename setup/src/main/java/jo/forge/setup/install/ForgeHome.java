package jo.forge.setup.install;

import jo.forge.setup.download.InstallException;

import java.nio.file.Path;

/**
 * Disposition du répertoire privé de Forge dans le stockage de l'application :
 * {@code forge/} à la racine de {@code filesDir}.
 *
 * <pre>
 * forge/
 *   engines/&lt;version&gt;/          moteur installé (lecture seule)
 *   engines/.staging-&lt;uuid&gt;/     installation en cours (renommée à la fin)
 *   downloads/                    .part et archives complètes
 *   state/installed.json          pointeur d'état (écriture atomique)
 * </pre>
 *
 * @param racine répertoire racine {@code forge}.
 */
public record ForgeHome(Path racine) {

    /**
     * Valide la racine.
     *
     * @throws InstallException si la racine est nulle.
     */
    public ForgeHome {
        if (racine == null) {
            throw new InstallException("Racine Forge manquante");
        }
    }

    /**
     * Répertoire des moteurs installés.
     *
     * @return le chemin de {@code engines/}.
     */
    public Path engines() {
        return racine.resolve("engines");
    }

    /**
     * Répertoire d'un moteur versionné installé.
     *
     * @param engineVersion version du moteur.
     * @return le chemin {@code engines/&lt;version&gt;}.
     */
    public Path engineDir(String engineVersion) {
        return engines().resolve(engineVersion);
    }

    /**
     * Répertoire des téléchargements (partiels et archives).
     *
     * @return le chemin {@code downloads/}.
     */
    public Path downloads() {
        return racine.resolve("downloads");
    }

    /**
     * Fichier d'état d'installation.
     *
     * @return le chemin {@code state/installed.json}.
     */
    public Path installedState() {
        return racine.resolve("state").resolve("installed.json");
    }
}
