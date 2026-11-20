package jo.forge.setup.install;

import jo.forge.setup.download.InstallCancelledException;
import jo.forge.setup.download.InstallException;
import jo.forge.setup.manifest.ComponentManifest;
import jo.forge.setup.manifest.EngineManifest;
import jo.forge.setup.manifest.ManifestVerifier;
import jo.forge.setup.util.Files2;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Installateur du moteur : planification, téléchargement reprenable, vérification,
 * extraction protégée, installation atomique, mise à jour, réparation, désinstallation.
 *
 * <p>Ordre garanti pour l'atomicité : tout le contenu est extrait dans un répertoire de
 * préparation invisible ({@code engines/.staging-&lt;uuid&gt;}), posé en lecture seule,
 * puis <em>renommé</em> en {@code engines/&lt;version&gt;} ; l'état
 * ({@code installed.json}) n'est basculé qu'en dernier, atomiquement. Un échec à
 * n'importe quel pas laisse l'état précédent intact et supprime la préparation.</p>
 */
public final class EngineInstaller {

    private final ForgeHome home;
    private final ComponentSource source;
    private final ArchiveExtractor extractor;

    /**
     * Crée l'installateur.
     *
     * @param home    racine Forge de l'application.
     * @param source  source des archives (réseau ou répertoire local hors ligne).
     * @param extractor extracteur (plafond de taille configurable).
     */
    public EngineInstaller(ForgeHome home, ComponentSource source, ArchiveExtractor extractor) {
        this.home = home;
        this.source = source;
        this.extractor = extractor;
    }

    /**
     * État d'installation courant (ou {@code null} si aucun moteur).
     *
     * @return l'état lu, ou {@code null}.
     */
    public InstalledState currentState() {
        return InstalledState.fromFile(home.installedState());
    }

    /**
     * Installe (ou met à jour) le moteur décrit par un manifeste vérifié.
     *
     * <p>Les composants applicables à l'ABI sont installés, dépendances d'abord. Si un
     * moteur est déjà installé avec la même version, l'installation est refaite
     * (réparation complète par réinstallation).</p>
     *
     * @param manifest manifeste signé, déjà vérifié par {@link ManifestVerifier}.
     * @param abi      ABI de l'appareil (ex. {@code arm64-v8a}).
     * @param progress rapporteur de progression (peut être nul).
     * @param cancel   jeton d'annulation : le partiel est conservé pour reprise.
     * @return l'état installé.
     * @throws InstallCancelledException si annulation demandée.
     * @throws InstallException          en cas d'erreur de téléchargement, de vérification
     *                                   ou d'installation.
     */
    public InstalledState install(EngineManifest manifest, String abi,
                                  jo.forge.setup.download.Downloader.ProgressListener progress,
                                  jo.forge.setup.download.CancellationToken cancel) {
        List<ComponentManifest> ordonnes = ordonner(manifest, manifest.forAbi(abi));
        InstalledState precedent = currentState();

        Path staging = home.engines().resolve(".staging-" + UUID.randomUUID());
        try {
            Files.createDirectories(home.engines());
            Files.createDirectories(staging);

            List<InstalledState.InstalledComponent> installes = new ArrayList<>();
            java.util.Set<String> fichiersDejaVus = new java.util.HashSet<>();
            for (ComponentManifest composant : ordonnes) {
                cancel.check();
                Path archive = source.fetch(composant, progress, cancel);
                extractor.extract(archive, staging);
                // Seuls les fichiers NOUVEAUX appartiennent à ce composant (un composant
                // suivant ne doit pas hériter des fichiers du précédent).
                Map<String, String> fichiers = nouveauxFichiers(staging, fichiersDejaVus);
                installes.add(new InstalledState.InstalledComponent(composant.id(),
                        composant.version(), fichiers));
            }

            // Lecture seule sur le CONTENU (pas la racine : le renommage d'un
            // répertoire exige le droit d'écriture sur lui-même sous Linux).
            poserLectureSeule(staging);

            Path destination = home.engineDir(manifest.engineVersion());
            if (Files.exists(destination)) {
                // Réinstallation : l'écriture est rétablie avant suppression.
                retablirEcriture(destination);
                Files2.supprimerRecursivement(destination);
            }
            Files.move(staging, destination);
            // La racine du moteur installé devient elle aussi lecture seule.
            destination.toFile().setWritable(false, false);

            InstalledState nouvelEtat = new InstalledState(InstalledState.FORMAT_VERSION,
                    manifest.engineVersion(), Instant.now().toString(), installes);
            ecrireEtat(nouvelEtat);

            // Succès : purge des archives téléchargées et de l'ancien moteur.
            purgerDownloads();
            if (precedent != null
                    && !precedent.engineVersion().equals(manifest.engineVersion())) {
                Path ancien = home.engineDir(precedent.engineVersion());
                retablirEcriture(ancien);
                Files2.supprimerRecursivement(ancien);
            }
            return nouvelEtat;
        } catch (IOException ioError) {
            throw new InstallException("Installation impossible : " + ioError.getMessage(),
                    ioError);
        } finally {
            // Le staging a pu rester en lecture seule : rétablir avant suppression.
            retablirEcriture(staging);
            Files2.supprimerRecursivement(staging);
        }
    }

    /**
     * Vérifie l'intégrité du moteur installé (base de la réparation).
     *
     * @return la liste des fichiers altérés (chemin relatif) ; vide si tout est sain.
     * @throws InstallException si aucun moteur n'est installé.
     */
    public List<String> verify() {
        InstalledState etat = currentState();
        if (etat == null) {
            throw new InstallException("Aucun moteur installé à vérifier");
        }
        Path moteur = home.engineDir(etat.engineVersion());
        List<String> alteres = new ArrayList<>();
        for (InstalledState.InstalledComponent composant : etat.components()) {
            for (Map.Entry<String, String> fichier : composant.files().entrySet()) {
                Path chemin = moteur.resolve(fichier.getKey());
                if (!Files.exists(chemin)) {
                    alteres.add(fichier.getKey() + " (absent)");
                    continue;
                }
                String somme = Files2.sha256Strict(chemin);
                if (!somme.equals(fichier.getValue())) {
                    alteres.add(fichier.getKey() + " (somme invalide)");
                }
            }
        }
        return alteres;
    }

    /**
     * Désinstalle le moteur : répertoire moteur, état et téléchargements.
     *
     * @return {@code true} si un moteur a été désinstallé.
     */
    public boolean uninstall() {
        InstalledState etat = currentState();
        if (etat == null) {
            return false;
        }
        Path moteur = home.engineDir(etat.engineVersion());
        retablirEcriture(moteur);
        Files2.supprimerRecursivement(moteur);
        purgerDownloads();
        try {
            Files.deleteIfExists(home.installedState());
        } catch (IOException ioError) {
            throw new InstallException("Désinstallation incomplète : "
                    + ioError.getMessage(), ioError);
        }
        return true;
    }

    // ---- Aides -------------------------------------------------------------

    /**
     * Ordonne les composants par dépendances (les requis avant les requérants) ;
     * le cycle de dépendances est impossible car le manifeste ne référence que des
     * identifiants présents et l'ordre du manifeste brise les ambiguïtés.
     */
    private List<ComponentManifest> ordonner(EngineManifest manifest,
                                             List<ComponentManifest> composants) {
        LinkedHashSet<ComponentManifest> ordonnes = new LinkedHashSet<>();
        for (ComponentManifest composant : composants) {
            ordonnerUn(manifest, composant, ordonnes, 0);
        }
        return List.copyOf(ordonnes);
    }

    /**
     * Insère un composant après ses dépendances, récursivement borné.
     */
    private void ordonnerUn(EngineManifest manifest, ComponentManifest composant,
                            LinkedHashSet<ComponentManifest> ordonnes, int profondeur) {
        if (profondeur > 64) {
            throw new InstallException("Dépendances de composants trop profondes ou circulaires");
        }
        for (String dependance : composant.depends()) {
            ordonnerUn(manifest, manifest.component(dependance), ordonnes, profondeur + 1);
        }
        ordonnes.add(composant);
    }

    /**
     * Calcule les sommes des fichiers apparus depuis le dernier appel (chaque composant
     * reçoit exactement ses fichiers, jamais ceux d'un prédécesseur).
     */
    private Map<String, String> nouveauxFichiers(Path staging,
                                                  java.util.Set<String> dejaVus)
            throws IOException {
        Map<String, String> fichiers = new LinkedHashMap<>();
        try (var chemins = Files.walk(staging)) {
            chemins.filter(Files::isRegularFile).forEach(chemin -> {
                String relatif = staging.relativize(chemin).toString().replace('\\', '/');
                if (dejaVus.add(relatif)) {
                    fichiers.put(relatif, Files2.sha256Strict(chemin));
                }
            });
        }
        return fichiers;
    }

    /**
     * Pose le CONTENU du moteur en lecture seule (+x pour le propriétaire, exigence
     * ART pour le code chargé et les binaires exécutés) ; la racine reste inscriptible
     * pour permettre le renommage du staging. Utilise les réglages portables de
     * {@link java.io.File} : identiques sur JVM et ART.
     */
    private void poserLectureSeule(Path racine) throws IOException {
        try (var chemins = Files.walk(racine)) {
            for (Path chemin : (Iterable<Path>) chemins::iterator) {
                if (chemin.equals(racine)) {
                    continue; // Racine : inscriptible jusqu'au renommage.
                }
                java.io.File fichier = chemin.toFile();
                fichier.setReadable(true, true);
                fichier.setWritable(false, false);
                fichier.setExecutable(true, true);
            }
        }
    }

    /**
     * Rétablit le droit d'écriture sur un arbre avant suppression ou remplacement —
     * un répertoire non inscriptible refuse la suppression de ses entrées.
     */
    private void retablirEcriture(Path racine) {
        if (!Files.exists(racine)) {
            return;
        }
        try (var chemins = Files.walk(racine)) {
            for (Path chemin : (Iterable<Path>) chemins::iterator) {
                java.io.File fichier = chemin.toFile();
                fichier.setWritable(true, true);
            }
        } catch (IOException ignored) {
            // La suppression qui suit remontera l'erreur réelle.
        }
    }

    /**
     * Écrit l'état d'installation atomiquement.
     */
    private void ecrireEtat(InstalledState etat) {
        try {
            Files.createDirectories(home.installedState().getParent());
            Files2.ecrireAtomique(home.installedState(), etat.toJson().getBytes(
                    java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException ioError) {
            throw new InstallException("Écriture de l'état impossible : "
                    + ioError.getMessage(), ioError);
        }
    }

    /**
     * Purge les téléchargements (archives et partiels).
     */
    private void purgerDownloads() {
        Files2.supprimerRecursivement(home.downloads());
    }
}
