package jo.forge.setup.install;

import jo.forge.json.Json;
import jo.forge.setup.manifest.ComponentManifest;
import jo.forge.setup.util.Files2;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Cache des composants déjà installés, partagé entre toutes les versions du moteur —
 * le mécanisme d'économie de données mobiles (v0.2.9).
 *
 * <p>Problème : chaque publication du moteur re-distribuait TOUT ses composants, y
 * compris {@code platform-34} (~50 Mo) et {@code aapt2-&lt;abi&gt;} dont le contenu
 * n'avait pas changé — l'appareil re-téléchargeait l'intégralité à chaque mise à
 * jour. Solution : les fichiers extraits d'un composant sont conservés sous
 * {@code forge/cache/composants/&lt;empreinte&gt;/contenu}, la clé étant l'EMPREINTE
 * DE CONTENU du composant (identité des fichiers extraits, indépendante de la
 * version et de l'archive — voir {@code EmpreinteContenu}). Une installation
 * ultérieure qui réclame un composant de même empreinte lie ces fichiers par lien
 * dur (repli : copie locale) au lieu de télécharger : zéro octet réseau.</p>
 *
 * <p>Règles de sécurité :
 * <ul>
 *   <li>l'empreinte vient du manifeste SIGNÉ — un contenu différent a une empreinte
 *       différente, le cache ne peut jamais servir un contenu que le manifeste
 *       n'annonce pas ;</li>
 *   <li>chaque fichier est vérifié par sa somme SHA-256 avant réutilisation
 *       (intégrité locale — un cache altéré redevient un simple échec de
 *       réutilisation, jamais une installation corrompue) ;</li>
 *   <li>le marqueur {@code entree.json} est écrit EN DERNIER et atomiquement : une
 *       entrée sans marqueur ou au marqueur illisible est invalide ;</li>
 *   <li>toute erreur de cache est silencieusement dégradée : le composant est
 *       téléchargé comme si le cache n'existait pas.</li>
 * </ul></p>
 *
 * <p>Le cache survit aux mises à jour ET aux désinstallations du moteur (c'est son
 * but) ; la purge est explicite ({@link #purger()}, via le bouton dédié de la
 * coquille). Les fichiers du cache sont partagés par lien dur avec les moteurs
 * installés : supprimer un moteur ne supprime pas le cache, et réciproquement.</p>
 */
final class CacheComposants {

    /** Nom du marqueur d'entrée (sa présence valide l'entrée). */
    private static final String MARQUEUR = "entree.json";

    /** Répertoire des fichiers extraits dans une entrée. */
    private static final String CONTENU = "contenu";

    /** Racine du cache ({@code forge/cache/composants}). */
    private final Path racine;

    /**
     * Crée le gestionnaire du cache d'un ForgeHome.
     *
     * @param racine répertoire racine du cache.
     */
    CacheComposants(Path racine) {
        this.racine = racine;
    }

    /**
     * Une entrée de cache valide : les fichiers du composant, prêts à lier.
     *
     * @param id         identifiant du composant peuplant l'entrée (information).
     * @param version    version du composant (information).
     * @param empreinte  empreinte de contenu (clé du cache).
     * @param fichiers   chemins relatifs vers sommes SHA-256.
     */
    record Entrée(String id, String version, String empreinte,
                  Map<String, String> fichiers) {

        /**
         * Valide l'entrée.
         */
        Entrée {
            fichiers = fichiers == null ? Map.of() : Map.copyOf(fichiers);
        }
    }

    /**
     * Indique si le cache détient ce composant, sain et complet : marqueur présent,
     * empreinte identique, chaque fichier présent avec sa somme. C'est la frontière
     * de confiance de la réutilisation — un cache altéré rend {@code null}, jamais
     * une entrée douteuse.
     *
     * @param composant composant voulu (l'empreinte vient du manifeste signé).
     * @return l'entrée saine, ou {@code null} (absente, sans empreinte ou altérée).
     */
    Entrée entréeSaine(ComponentManifest composant) {
        if (composant.empreinte() == null) {
            return null; // Manifeste antérieur à v0.2.9 : pas d'identité de contenu.
        }
        Entrée entrée = lireMarqueur(composant.empreinte());
        if (entrée == null || !entrée.empreinte().equals(composant.empreinte())
                || entrée.fichiers().isEmpty()) {
            return null;
        }
        for (Map.Entry<String, String> fichier : entrée.fichiers().entrySet()) {
            Path chemin = racine.resolve(composant.empreinte()).resolve(CONTENU)
                    .resolve(fichier.getKey());
            if (!Files.isRegularFile(chemin)) {
                return null;
            }
            try {
                if (!Files2.sha256(chemin).equals(fichier.getValue())) {
                    return null;
                }
            } catch (IOException illisible) {
                return null;
            }
        }
        return entrée;
    }

    /**
     * Lie les fichiers d'une entrée dans le staging d'installation. Repli copie
     * locale si le système de fichiers refuse les liens durs ; tout échec rend
     * {@code false} — l'appelant retombe sur le téléchargement (l'extraction
     * écrase les fichiers déjà liés, un échec à mi-chemin est donc sans effet).
     *
     * @param empreinte empreinte de l'entrée (d'une {@link #entréeSaine} vérifiée).
     * @param staging   répertoire de préparation de l'installation.
     * @param fichiers  chemins relatifs vers sommes (ceux de l'entrée).
     * @return {@code true} si tous les fichiers sont en place.
     */
    boolean lierDans(String empreinte, Path staging, Map<String, String> fichiers) {
        Path contenu = racine.resolve(empreinte).resolve(CONTENU);
        for (String chemin : fichiers.keySet()) {
            Path source = contenu.resolve(chemin);
            Path destination = staging.resolve(chemin);
            try {
                Files.createDirectories(destination.getParent());
                if (!copierOuLier(source, destination)) {
                    return false;
                }
            } catch (IOException echec) {
                return false;
            }
        }
        return true;
    }

    /**
     * Peuple le cache depuis les fichiers d'un composant fraîchement extrait (ou
     * d'un moteur précédent, pour l'amorçage). Best effort : toute erreur nettoie
     * l'entrée et rend silencieusement la main — le cache est une optimisation,
     * jamais une dépendance. Une entrée existante est RECONSTRUITE (et non
     * conservée) : une entrée altérée — marqueur présent, fichier falsifié — doit
     * pouvoir être réparée par le passage suivant, et reconstruire ne coûte que
     * des liens durs locaux.
     *
     * @param composant composant dont les fichiers viennent d'être posés.
     * @param source    répertoire où lisent les fichiers ({@code chemin} relatifs).
     * @param fichiers  chemins relatifs vers sommes (fichiers du composant).
     */
    void peupler(ComponentManifest composant, Path source, Map<String, String> fichiers) {
        if (composant.empreinte() == null || fichiers.isEmpty()) {
            return;
        }
        Path entrée = racine.resolve(composant.empreinte());
        try {
            rétablirÉcriture(entrée);
            Files2.supprimerRecursivement(entrée);
            Path contenu = entrée.resolve(CONTENU);
            for (String chemin : fichiers.keySet()) {
                Path origin = source.resolve(chemin);
                Path cible = contenu.resolve(chemin);
                Files.createDirectories(cible.getParent());
                if (!copierOuLier(origin, cible)) {
                    throw new IOException("copie impossible : " + chemin);
                }
            }
            Files2.ecrireAtomique(entrée.resolve(MARQUEUR), jo.forge.json.Json.write(
                    java.util.Map.of(
                            "id", composant.id(),
                            "version", composant.version(),
                            "empreinte", composant.empreinte(),
                            "fichiers", new java.util.LinkedHashMap<>(fichiers)))
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception echec) {
            // Optimisation échouée : nettoyer et continuer sans cache.
            rétablirÉcriture(entrée);
            Files2.supprimerRecursivement(entrée);
        }
    }

    /**
     * Purge tout le cache (bouton explicite de la coquille).
     *
     * @return {@code true} si le cache a été supprimé (ou n'existait pas).
     */
    boolean purger() {
        rétablirÉcriture(racine);
        return Files2.supprimerRecursivement(racine);
    }

    /**
     * Taille totale du cache sur disque (affichage de la coquille).
     *
     * @return la taille en octets, 0 si le cache est vide.
     */
    long taille() {
        if (!Files.isDirectory(racine)) {
            return 0;
        }
        long total = 0;
        try (var chemins = Files.walk(racine)) {
            total = chemins.filter(Files::isRegularFile)
                    .mapToLong(chemin -> {
                        try {
                            return Files.size(chemin);
                        } catch (IOException ignorée) {
                            return 0;
                        }
                    }).sum();
        } catch (IOException ignorée) {
            // Taille indicative : une erreur de parcours vaut zéro.
        }
        return total;
    }

    // ---- Aides -------------------------------------------------------------

    /**
     * Lit le marqueur d'une entrée ; toute anomalie rend {@code null} (entrée
     * inexistante ou incomplète — jamais un état douteux).
     */
    private Entrée lireMarqueur(String empreinte) {
        Path marqueur = racine.resolve(empreinte).resolve(MARQUEUR);
        if (!Files.isRegularFile(marqueur)) {
            return null;
        }
        try {
            Object racine = Json.parse(Files.readString(marqueur));
            if (!(racine instanceof Map<?, ?> objet)
                    || !(objet.get("empreinte") instanceof String lue)
                    || !lue.equals(empreinte)) {
                return null;
            }
            java.util.Map<String, String> fichiers = new java.util.LinkedHashMap<>();
            if (objet.get("fichiers") instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> fichier : map.entrySet()) {
                    fichiers.put(String.valueOf(fichier.getKey()),
                            String.valueOf(fichier.getValue()));
                }
            }
            return new Entrée(String.valueOf(objet.get("id")),
                    String.valueOf(objet.get("version")), lue, fichiers);
        } catch (Exception illisible) {
            return null;
        }
    }

    /**
     * Pose un fichier par lien dur (aucun octet dupliqué sur disque), avec repli
     * copie si le système de fichiers refuse.
     *
     * @return {@code true} si le fichier est en place.
     */
    private static boolean copierOuLier(Path source, Path destination) throws IOException {
        if (!Files.isRegularFile(source)) {
            return false;
        }
        try {
            Files.createLink(destination, source);
            return true;
        } catch (IOException | UnsupportedOperationException sansLien) {
            Files.copy(source, destination,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return true;
        }
    }

    /**
     * Rétablit l'écriture sur un arbre du cache avant remplacement ou suppression
     * (les fichiers liés dans un moteur installé sont en lecture seule, et le lien
     * dur partage le mode avec le cache).
     */
    private static void rétablirÉcriture(Path racine) {
        if (!Files.exists(racine)) {
            return;
        }
        try (var chemins = Files.walk(racine)) {
            for (Path chemin : (Iterable<Path>) chemins::iterator) {
                chemin.toFile().setWritable(true, true);
            }
        } catch (IOException ignorée) {
            // La suppression qui suit remontera l'erreur réelle.
        }
    }
}
