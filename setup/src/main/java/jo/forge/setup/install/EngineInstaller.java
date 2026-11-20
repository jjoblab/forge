package jo.forge.setup.install;

import jo.forge.setup.download.InstallCancelledException;
import jo.forge.setup.download.InstallException;
import jo.forge.setup.manifest.ComponentManifest;
import jo.forge.setup.manifest.EngineManifest;
import jo.forge.setup.manifest.EmpreinteContenu;
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
 *
 * <p><b>Économie de données mobiles</b> (v0.2.9) : les composants dont l'empreinte
 * de contenu est déjà dans le cache ({@code forge/cache/composants}) sont LIÉS
 * depuis le cache au lieu d'être téléchargés — une mise à jour du moteur ne
 * re-télécharge que ce qui a réellement changé. Le cache est en outre AMORCÉ depuis
 * le moteur précédemment installé : quand un composant du nouveau manifeste a la
 * même empreinte qu'un composant en place, ses fichiers sont mis en cache avant
 * d'être supprimés avec l'ancien moteur (la première mise à jour vers une version
 * à empreintes est donc elle aussi économique). Le cache survit aux mises à jour
 * et aux désinstallations ; sa purge est explicite ({@link #purgerCache()}).</p>
 */
public final class EngineInstaller {

    private final ForgeHome home;
    private final ComponentSource source;
    private final ArchiveExtractor extractor;
    private final CacheComposants cache;

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
        this.cache = new CacheComposants(home.cacheComposants());
    }

    /**
     * Résultat d'une installation : l'état posé, ce qui n'a PAS été téléchargé.
     *
     * @param état            état installé.
     * @param octetsÉvités    octets réseau économisés (composants réutilisés du cache).
     * @param composantsRéutilisés identifiants des composants servis par le cache.
     */
    public record RésultatInstallation(InstalledState état, long octetsÉvités,
                                       List<String> composantsRéutilisés) {

        /**
         * Valide le résultat.
         */
        public RésultatInstallation {
            composantsRéutilisés = composantsRéutilisés == null
                    ? List.of() : List.copyOf(composantsRéutilisés);
        }
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
     * Indique si le cache détient déjà un composant, sain et complet (affichage
     * de la coquille AVANT installation : « déjà téléchargé — aucun octet »).
     *
     * @param composant composant du manifeste chargé.
     * @return {@code true} si l'installation le servira depuis le cache.
     */
    public boolean composantEnCache(ComponentManifest composant) {
        return cache.entréeSaine(composant) != null;
    }

    /**
     * Taille du cache de composants sur disque (affichage, purge guidée).
     *
     * @return la taille en octets (0 si vide).
     */
    public long tailleCache() {
        return cache.taille();
    }

    /**
     * Purge le cache des composants (bouton explicite de la coquille). Ne touche
     * ni au moteur installé, ni à son état : la prochaine installation re-téléchargera
     * ce qu'il faut.
     *
     * @return {@code true} si le cache a été supprimé (ou n'existait pas).
     */
    public boolean purgerCache() {
        return cache.purger();
    }

    /**
     * Installe (ou met à jour) le moteur décrit par un manifeste vérifié.
     *
     * <p>Les composants applicables à l'ABI sont installés, dépendances d'abord,
     * depuis le cache de composants quand leur empreinte y est déjà (aucun octet
     * réseau), sinon par téléchargement (puis mise en cache pour les prochaines
     * fois). Si un moteur est déjà installé avec la même version, l'installation
     * est refaite (réparation complète — gratuite quand le cache couvre tout).</p>
     *
     * @param manifest manifeste signé, déjà vérifié par {@link ManifestVerifier}.
     * @param abi      ABI de l'appareil (ex. {@code arm64-v8a}).
     * @param progress rapporteur de progression (peut être nul).
     * @param cancel   jeton d'annulation : le partiel est conservé pour reprise.
     * @return l'état installé et les octets économisés.
     * @throws InstallCancelledException si annulation demandée.
     * @throws InstallException          en cas d'erreur de téléchargement, de vérification
     *                                   ou d'installation.
     */
    public RésultatInstallation install(EngineManifest manifest, String abi,
                                  jo.forge.setup.download.Downloader.ProgressListener progress,
                                  jo.forge.setup.download.CancellationToken cancel) {
        List<ComponentManifest> ordonnes = ordonner(manifest, manifest.forAbi(abi));
        InstalledState precedent = currentState();

        // Amorçage : sauver dans le cache les composants du moteur en place dont le
        // contenu correspond à ce que le nouveau manifeste réclame — AVANT que
        // l'ancien moteur ne soit supprimé (économie dès la première mise à jour
        // à empreintes, ex. v0.2.8 → v0.2.9).
        amorcerCache(ordonnes, precedent);

        Path staging = home.engines().resolve(".staging-" + UUID.randomUUID());
        try {
            Files.createDirectories(home.engines());
            Files.createDirectories(staging);

            List<InstalledState.InstalledComponent> installes = new ArrayList<>();
            List<String> réutilisés = new ArrayList<>();
            long octetsÉvités = 0;
            java.util.Set<String> fichiersDejaVus = new java.util.HashSet<>();
            for (ComponentManifest composant : ordonnes) {
                cancel.check();
                Map<String, String> fichiers;
                CacheComposants.Entrée entrée = cache.entréeSaine(composant);
                if (entrée != null
                        && cache.lierDans(composant.empreinte(), staging,
                        entrée.fichiers())) {
                    // Réutilisé : aucun octet réseau, sommes déjà vérifiées.
                    fichiers = entrée.fichiers();
                    réutilisés.add(composant.id());
                    octetsÉvités += composant.size();
                } else {
                    Path archive = source.fetch(composant, progress, cancel);
                    extractor.extract(archive, staging);
                    // Seuls les fichiers NOUVEAUX appartiennent à ce composant (un composant
                    // suivant ne doit pas hériter des fichiers du précédent).
                    fichiers = nouveauxFichiers(staging, fichiersDejaVus);
                    // Le composant rejoint le cache pour les prochaines installations
                    // (best effort — jamais au prix de l'installation).
                    cache.peupler(composant, staging, fichiers);
                }
                fichiersDejaVus.addAll(fichiers.keySet());
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
            return new RésultatInstallation(nouvelEtat, octetsÉvités, réutilisés);
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
     * Amorce le cache depuis le moteur précédent : chaque composant réclamé par le
     * nouveau manifeste dont l'empreinte de contenu correspond à un composant EN
     * PLACE (mêmes fichiers, mêmes sommes — vérifiés sur disque) rejoint le cache
     * par lien dur, avant la suppression de l'ancien moteur. Échecs silencieux :
     * sans amorçage, le composant sera simplement téléchargé.
     */
    private void amorcerCache(List<ComponentManifest> ordonnes, InstalledState precedent) {
        if (precedent == null) {
            return;
        }
        Path ancienMoteur = home.engineDir(precedent.engineVersion());
        if (!Files.isDirectory(ancienMoteur)) {
            return;
        }
        for (ComponentManifest composant : ordonnes) {
            if (composant.empreinte() == null) {
                continue;
            }
            InstalledState.InstalledComponent ancien = precedent.component(composant.id());
            if (ancien == null || ancien.files().isEmpty()) {
                continue;
            }
            if (!EmpreinteContenu.deCarte(ancien.files())
                    .equals(composant.empreinte())) {
                continue;
            }
            if (!fichiersSains(ancienMoteur, ancien.files())) {
                continue; // Altéré : pas d'amorçage sur du contenu douteux.
            }
            cache.peupler(composant, ancienMoteur, ancien.files());
        }
    }

    /**
     * Vérifie que des fichiers d'un moteur installé correspondent bien à leurs
     * sommes (base de l'amorçage : ne mettre en cache que du contenu sain).
     */
    private static boolean fichiersSains(Path moteur, Map<String, String> fichiers) {
        for (Map.Entry<String, String> fichier : fichiers.entrySet()) {
            Path chemin = moteur.resolve(fichier.getKey());
            if (!Files.isRegularFile(chemin)) {
                return false;
            }
            try {
                if (!Files2.sha256(chemin).equals(fichier.getValue())) {
                    return false;
                }
            } catch (IOException illisible) {
                return false;
            }
        }
        return true;
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
