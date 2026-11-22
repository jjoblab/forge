package jo.forge.engine.deps;

import jo.forge.engine.model.DependencyScope;
import jo.forge.engine.tasks.TaskCancelledException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Résolveur de dépendances Maven : marche transitive, POM effectifs (parents,
 * propriétés, gestion de dépendances, nomenclatures importées, relogements),
 * <strong>métadonnées de module Gradle</strong> (sélection de variante — le chemin
 * obligatoire des bibliothèques androidx/compose, dont les POMs pont sont
 * inutilisables), médiation de versions, cache hors ligne, intégrité vérifiée,
 * AAR explosés (classes, res, assets, manifeste).
 *
 * <p>Sémantique des portées (miroir des bibliothèques Java Gradle) : une racine
 * {@code api} expose sa fermeture transitive au classpath de compilation des
 * modules frères ; une racine {@code implementation} l'expose aussi au classpath
 * de compilation de SON PROPRE module (correction v0.2.15 : parité Gradle — le
 * code d'une application compose référence les types que sa bibliothèque
 * déclare en api, sinon il ne compile pas) sans l'exporter aux frères ;
 * {@code compileOnly} n'est que compilation ; {@code runtimeOnly} n'est
 * qu'exécution.</p>
 *
 * <p><b>Métadonnées Gradle</b> (v0.2.15) : quand une coordonnée publie un
 * {@code .module}, la marche suit la variante SÉLECTIONNÉE pour la cible du
 * module (Android → {@code -android}, JVM → {@code -jvm}) : ses dépendances
 * (api → compilation, runtime-seulement → exécution), ses contraintes
 * d'alignement (elles participent à la médiation sans tirer de nœud), la
 * redirection {@code available-at} (portée par le mécanisme de relogement
 * existant) et le nom de FICHIER de l'artefact ({@code ui-release.aar} au lieu
 * du nom Maven conventionnel). Un {@code .module} absent ou sans variante
 * convenable retombe au POM — le chemin historique. Les plages Maven EXACTES
 * {@code [1.2.3]} (le pont des éditeurs Gradle) valent la version délimitée ;
 * toute autre plage est refusée nommément.</p>
 *
 * <p>Bornes explicites (le moteur de référence coupe silencieusement — constat
 * consigné ; nous échouons avec un diagnostic) : nombre de nœuds, profondeur, nombre
 * de relogements.</p>
 *
 * <p>Marche séquentielle en v1 (la référence parallélise par vagues — optimisation
 * future consignée) ; les choix de versions convergent en au plus deux passes
 * (re-marche forcée si la médiation change une version développée).</p>
 *
 * <p><b>Cache de résolution</b> (v0.2.21, décision D-13) : {@code resolve(module, …)}
 * consulte d'abord le résultat mémorisé ({@link ResolutionResultCache}) — une
 * résolution déjà faite, aux entrées identiques et artefacts toujours en cache, se
 * relit SANS marche ni réseau ; le résultat d'une marche complète y est mémorisé.
 * Les racines {@code -SNAPSHOT} contournent ce cache (versions volatiles).</p>
 *
 * <p><b>Annulation et progression</b> (v0.2.21) : un fournisseur d'annulation coupe
 * la marche et les téléchargements ({@link TaskCancelledException}) ; un observateur
 * ({@link ProgressionTéléchargement}) reçoit chaque transfert au fil de l'eau.</p>
 */
public final class DependencyResolver {

    /** Nombre maximal de nœuds explorés avant échec avec diagnostic. */
    public static final int BORNE_NOEUDS = 1000;

    /** Profondeur transitive maximale. */
    public static final int BORNE_PROFONDEUR = 60;

    /** Nombre maximal de relogements suivis pour une coordonnée. */
    public static final int BORNE_RELOGEMENTS = 5;

    private final List<String> dépôts;
    private final ResolverCache cache;
    private final boolean horsLigne;
    private final HttpTransport transport;
    private final Consumer<String> avertissements;
    private final boolean cibleAndroid;
    private final int apiCible;
    private final ResolutionResultCache cacheRésolution;
    private final BooleanSupplier annulation;
    private final ProgressionTéléchargement progression;

    /** La dernière résolution a-t-elle été relue du cache (sans marche ni réseau) ? */
    private boolean dernièreDepuisCache;

    /** Une dépendance racine demandée par un module. */
    public record RootDependency(Coordinate coordinate, DependencyScope scope) {
    }

    /**
     * Un artefact résolu, prêt pour un classpath : jar pur, ou AAR dont
     * {@code jar} désigne le {@code classes.jar} explosé dans le cache et
     * {@code exploded} le répertoire d'explosion (res, assets, manifeste —
     * consommés par la chaîne Android).
     *
     * @param coordinate coordonnée RÉSOLUE (après relogement/métadonnées).
     * @param jar        le jar à mettre au classpath (classes.jar pour un AAR).
     * @param sha256     somme de contenu (facultative).
     * @param packaging  {@code jar} ou {@code aar}.
     * @param exploded   répertoire d'explosion (AAR), sinon null.
     */
    public record ResolvedJar(Coordinate coordinate, Path jar, String sha256,
                              String packaging, Path exploded) {

        /** Forme jar simple (compatibilité des appelants historiques). */
        public ResolvedJar(Coordinate coordinate, Path jar, String sha256) {
            this(coordinate, jar, sha256, "jar", null);
        }
    }

    /** Résultat de la résolution d'un module. */
    public record Result(List<ResolvedJar> compileClasspath,
                         List<ResolvedJar> runtimeClasspath) {
    }

    /** Dépendance transitive retenue dans le graphe (pour la médiation et les ensembles). */
    private record Arête(Coordinate depuis, Coordinate vers, String portéeMaven,
                         boolean optionnelle) {
    }

    /** Demande en file d'attente de la marche. */
    private record Demande(Coordinate coordonnée, int distance, Set<Gav> exclusions,
                           List<String> chemin) {
    }

    /** POM effectif : gestion + dépendances prêtes à consommer (+ relogement éventuel). */
    private record PomEffectif(Coordinate coordonnée, String packaging,
                               Map<Gav, String> gérées, List<RawPom.PomDep> dépendances,
                               Coordinate relogement) {
    }

    /**
     * Crée le résolveur (cible JVM, sans borne de niveau d'API).
     *
     * @param dépôts        URLs des dépôts (HTTPS exigé, boucle locale tolérée).
     * @param cache         cache persistant (réplique Maven, hors ligne).
     * @param horsLigne     aucun accès réseau : cache seul, échec clair sinon.
     * @param avertissements consommateur d'avertissements.
     */
    public DependencyResolver(List<String> dépôts, ResolverCache cache, boolean horsLigne,
                               Consumer<String> avertissements) {
        this(dépôts, cache, horsLigne, avertissements, false, -1);
    }

    /**
     * Crée le résolveur pour la cible d'un module.
     *
     * @param dépôts        URLs des dépôts (HTTPS exigé, boucle locale tolérée).
     * @param cache         cache persistant (réplique Maven, hors ligne).
     * @param horsLigne     aucun accès réseau : cache seul, échec clair sinon.
     * @param avertissements consommateur d'avertissements.
     * @param cibleAndroid  module Android : les métadonnées Gradle sélectionnent
     *                      les variantes {@code -android} (sinon {@code -jvm}).
     * @param apiCible      niveau d'API de compilation du module (borne
     *                      {@code minCompileSdk} des AAR ; négatif : sans borne).
     */
    public DependencyResolver(List<String> dépôts, ResolverCache cache, boolean horsLigne,
                               Consumer<String> avertissements, boolean cibleAndroid,
                               int apiCible) {
        this(dépôts, cache, horsLigne, avertissements, cibleAndroid, apiCible,
                null, null, null);
    }

    /**
     * Crée le résolveur complet pour la cible d'un module.
     *
     * @param dépôts          URLs des dépôts (HTTPS exigé, boucle locale tolérée).
     * @param cache           cache persistant (réplique Maven, hors ligne).
     * @param horsLigne       aucun accès réseau : cache seul, échec clair sinon.
     * @param avertissements  consommateur d'avertissements.
     * @param cibleAndroid    module Android : les métadonnées Gradle sélectionnent
     *                        les variantes {@code -android} (sinon {@code -jvm}).
     * @param apiCible        niveau d'API de compilation du module (borne
     *                        {@code minCompileSdk} des AAR ; négatif : sans borne).
     * @param cacheRésolution cache du résultat de résolution par module (null :
     *                        désactivé — tests et usages outils).
     * @param annulation      fournisseur d'annulation (null : jamais annulé) ; testé
     *                        à chaque nœud de la marche et à chaque tampon réseau.
     * @param progression     observateur des téléchargements (null : silencieux).
     */
    public DependencyResolver(List<String> dépôts, ResolverCache cache, boolean horsLigne,
                               Consumer<String> avertissements, boolean cibleAndroid,
                               int apiCible, ResolutionResultCache cacheRésolution,
                               BooleanSupplier annulation,
                               ProgressionTéléchargement progression) {
        this.dépôts = dépôts.stream()
                .map(dépôt -> dépôt.endsWith("/") && dépôt.length() > 1
                        ? dépôt.substring(0, dépôt.length() - 1) : dépôt)
                .toList();
        this.cache = cache;
        this.horsLigne = horsLigne;
        this.avertissements = avertissements == null ? message -> { } : avertissements;
        this.cibleAndroid = cibleAndroid;
        this.apiCible = apiCible;
        this.cacheRésolution = cacheRésolution;
        this.annulation = annulation;
        this.progression = progression == null
                ? new ProgressionTéléchargement() { } : progression;
        this.transport = new HttpTransport(this.avertissements, annulation,
                this.progression);
        this.dépôts.forEach(HttpTransport::vérifierUrlDépôt);
        if (this.dépôts.isEmpty()) {
            throw new ResolverException(
                    "Aucun dépôt déclaré : ajoutez « repositories » dans forge.json");
        }
    }

    /**
     * Résout les dépendances d'un module et télécharge les jars retenus (sans cache
     * de résolution — usages outils et tests).
     *
     * @param racines dépendances directes du module (coordonnée + portée Forge).
     * @param politique médiation de versions.
     * @return les classpaths de compilation et d'exécution, ordonnés, sans doublon.
     * @throws ResolverException si une dépendance est introuvable, si un conflit est
     *                           refusé, ou si une borne est dépassée (toujours avec
     *                           diagnostic actionnable).
     */
    public Result resolve(List<RootDependency> racines, ConflictPolicy politique) {
        return resolve(null, racines, politique);
    }

    /**
     * Résout les dépendances d'un module, avec cache de résolution : une résolution
     * aux entrées identiques, dont tous les artefacts restent en cache, se relit
     * SANS marche ni réseau (décision D-13) ; sinon la marche s'exécute et son
     * résultat est mémorisé pour la prochaine fois.
     *
     * @param module    nom du module (clé du cache de résolution ; null : pas de
     *                  cache).
     * @param racines   dépendances directes du module (coordonnée + portée Forge).
     * @param politique médiation de versions.
     * @return les classpaths de compilation et d'exécution, ordonnés, sans doublon.
     * @throws ResolverException     si une dépendance est introuvable, si un conflit
     *                               est refusé, ou si une borne est dépassée.
     * @throws TaskCancelledException si l'annulation est demandée pendant la marche
     *                               ou un téléchargement.
     */
    public Result resolve(String module, List<RootDependency> racines,
                          ConflictPolicy politique) {
        dernièreDepuisCache = false;
        String empreinte = null;
        if (module != null && cacheRésolution != null
                && ResolutionResultCache.mémorisable(racines)) {
            empreinte = ResolutionResultCache.empreinte(racines, dépôts, cibleAndroid,
                    apiCible, politique);
            Optional<ResolutionResultCache.Mémoire> mémoire =
                    cacheRésolution.lire(module, empreinte);
            if (mémoire.isPresent() && artefactsPrésents(mémoire.get())) {
                try {
                    Result relue = relire(mémoire.get());
                    dernièreDepuisCache = true;
                    return relue;
                } catch (ResolverException rejouée) {
                    // Relecture impossible (AAR corrompu entre-temps…) : la marche
                    // reprend la main — un cache défaillant ne bloque jamais.
                }
            }
        }
        Result résultat = résoudreParLaMarche(racines, politique);
        if (empreinte != null) {
            cacheRésolution.écrire(module, empreinte,
                    mémoriser(résultat.compileClasspath()),
                    mémoriser(résultat.runtimeClasspath()));
        }
        return résultat;
    }

    /**
     * La dernière résolution a-t-elle été relue du cache (sans marche ni réseau) ?
     *
     * @return {@code true} si le cache de résolution a répondu.
     */
    public boolean dernièreDepuisCache() {
        return dernièreDepuisCache;
    }

    /**
     * Tous les artefacts d'un résultat mémorisé existent-ils toujours en cache ?
     * Une coordonnée peut figurer dans les DEUX classpaths (compile et runtime) :
     * le dédoublonnage n'est qu'une optimisation, jamais un échec.
     */
    private boolean artefactsPrésents(ResolutionResultCache.Mémoire mémoire) {
        Set<Coordinate> vérifiés = new HashSet<>();
        for (ResolutionResultCache.EntréeMémorisée entrée : mémoire.compile()) {
            if (vérifiés.add(entrée.coordinate())
                    && !cache.aArtefact(entrée.coordinate(),
                    "." + entrée.packaging())) {
                return false;
            }
        }
        for (ResolutionResultCache.EntréeMémorisée entrée : mémoire.runtime()) {
            if (vérifiés.add(entrée.coordinate())
                    && !cache.aArtefact(entrée.coordinate(),
                    "." + entrée.packaging())) {
                return false;
            }
        }
        return true;
    }

    /** Rejoue un résultat mémorisé : artefacts déjà en cache, explosion AAR au besoin. */
    private Result relire(ResolutionResultCache.Mémoire mémoire) {
        List<ResolvedJar> compilation = new ArrayList<>();
        for (ResolutionResultCache.EntréeMémorisée entrée : mémoire.compile()) {
            compilation.add(acheverArtefact(entrée.coordinate(), entrée.packaging()));
        }
        List<ResolvedJar> exécution = new ArrayList<>();
        for (ResolutionResultCache.EntréeMémorisée entrée : mémoire.runtime()) {
            exécution.add(acheverArtefact(entrée.coordinate(), entrée.packaging()));
        }
        return new Result(compilation, exécution);
    }

    /** Mémorise un classpath résolu (coordonnée finale + empaquetage). */
    private static List<ResolutionResultCache.EntréeMémorisée> mémoriser(
            List<ResolvedJar> classpath) {
        List<ResolutionResultCache.EntréeMémorisée> entrées = new ArrayList<>();
        for (ResolvedJar jar : classpath) {
            entrées.add(new ResolutionResultCache.EntréeMémorisée(
                    jar.coordinate(), jar.packaging()));
        }
        return entrées;
    }

    /** La marche complète : médiation, fermetures, téléchargement des retenus. */
    private Result résoudreParLaMarche(List<RootDependency> racines,
                                       ConflictPolicy politique) {
        Map<Coordinate, PomEffectif> poms = new HashMap<>();
        Map<Gav, Set<String>> versionsDemandées = new LinkedHashMap<>();
        Map<Gav, List<List<String>>> chemins = new LinkedHashMap<>();
        Set<Gav> identités = new LinkedHashSet<>();
        List<Arête> arêtes = new ArrayList<>();

        // Première passe : marche avec les versions déclarées ; médiation PAR
        // FAMILLE de capacités ; si elle change une version développée, seconde
        // passe forcée (convergence). Les familles se CUMULENT entre les passes :
        // un lien de relogement découvert en première passe reste valable quand
        // la seconde n'emprunte plus ce chemin.
        Marche marche = marcher(racines, null, poms, versionsDemandées, chemins,
                identités, arêtes);
        Map<Gav, Gav> familles = new LinkedHashMap<>(marche.familles());
        Map<Gav, String> choisies = médier(versionsDemandées, chemins, politique,
                représentants(familles));
        if (diverge(marche.développées(), choisies, représentants(familles))) {
            poms.clear();
            versionsDemandées = new LinkedHashMap<>();
            chemins = new LinkedHashMap<>();
            identités = new LinkedHashSet<>();
            arêtes = new ArrayList<>();
            // Version retenue par famille, posée sur CHAQUE gav de la famille :
            // une passerelle forcée à la version de famille suit son relogement,
            // et tous les chemins convergent vers l'artefact portant.
            Map<Gav, String> forcées = forcéesParFamille(choisies,
                    représentants(familles), marche.développées().keySet());
            marche = marcher(racines, forcées, poms, versionsDemandées, chemins,
                    identités, arêtes);
            familles.putAll(marche.familles());
            choisies = médier(versionsDemandées, chemins, politique,
                    représentants(familles));
        }

        // Ensembles de classpath par racine (sémantique des portées).
        Map<Coordinate, List<RawPom.PomDep>> transitives = new HashMap<>();
        for (Arête arête : arêtes) {
            transitives.computeIfAbsent(arête.depuis(), k -> new ArrayList<>())
                    .add(dépendanceDe(arête));
        }
        // Médiation PAR FAMILLE de capacités (v0.2.15) : les identités liées par
        // un relogement (passerelle KMP ↔ variante -android/-jvm) sont UN module
        // — Gradle appelle ça le conflit de capacités. Chaque membre pointe
        // l'artefact du REPRÉSENTANT de sa famille à la version retenue : sinon
        // collection:1.0.0 et collection-jvm:1.4.0 coexisteraient (classes en
        // double au dexage, « defined multiple times »).
        Map<Gav, Gav> représentants = représentants(familles);
        Map<Gav, Coordinate> retenues = new LinkedHashMap<>();
        for (Gav gav : identités) {
            Gav famille = représentants.getOrDefault(gav, gav);
            retenues.put(gav, new Coordinate(famille.group(), famille.artifact(),
                    choisies.get(famille)));
        }
        // Les passerelles vues par les ARÊTES (absentes d'identités quand leur
        // version a relogé) pointent l'artefact de leur famille.
        for (Map.Entry<Gav, Gav> lien : familles.entrySet()) {
            Coordinate artefactFamille = retenues.get(lien.getValue());
            if (artefactFamille != null) {
                retenues.putIfAbsent(lien.getKey(), artefactFamille);
            }
        }

        Set<Coordinate> compilation = new LinkedHashSet<>();
        Set<Coordinate> exécution = new LinkedHashSet<>();
        for (RootDependency racine : racines) {
            // La racine peut avoir été relogée pendant la marche : on prend la
            // coordonnée FINALE correspondant à son identité d'origine.
            Gav identitéRacine = marche.finales().getOrDefault(
                    racine.coordinate().gav(), racine.coordinate()).gav();
            Coordinate c = retenues.get(identitéRacine);
            if (c == null) {
                throw new ResolverException("Dépendance racine non résolue : "
                        + racine.coordinate());
            }
            switch (racine.scope()) {
                case API -> {
                    compilation.add(c);
                    fermetureCompilation(c, transitives, retenues, compilation,
                            new HashSet<>());
                    exécution.add(c);
                    fermetureExécution(c, transitives, retenues, exécution,
                            new HashSet<>());
                }
                case IMPLEMENTATION -> {
                    // Le code du module référence les types que SA dépendance
                    // déclare en api (Gradle : compileClasspath = racine + fermeture
                    // api de chaque dépendance directe) — la différence avec api
                    // est l'EXPORT aux frères, pas le propre classpath du module.
                    // Correction v0.2.15 : une application compose ne compilait
                    // pas sans elle (@Composable vient du runtime exposé par ui).
                    compilation.add(c);
                    fermetureCompilation(c, transitives, retenues, compilation,
                            new HashSet<>());
                    exécution.add(c);
                    fermetureExécution(c, transitives, retenues, exécution,
                            new HashSet<>());
                }
                case COMPILE_ONLY -> compilation.add(c);
                case RUNTIME_ONLY -> {
                    exécution.add(c);
                    fermetureExécution(c, transitives, retenues, exécution,
                            new HashSet<>());
                }
            }
        }

        List<ResolvedJar> jarsCompilation = new ArrayList<>();
        for (Coordinate c : compilation) {
            jarsCompilation.add(téléchargerArtefact(c));
        }
        List<ResolvedJar> jarsExécution = new ArrayList<>();
        for (Coordinate c : exécution) {
            jarsExécution.add(téléchargerArtefact(c));
        }
        return new Result(jarsCompilation, jarsExécution);
    }

    /** Lève l'annulation coopérative (jamais une erreur de build). */
    private void vérifierAnnulation() {
        if (annulation != null && annulation.getAsBoolean()) {
            throw new TaskCancelledException("résolution annulée");
        }
    }

    /** Traduit une annulation de transport en annulation de tâche. */
    private static TaskCancelledException annulé(HttpTransport.Annulée annulée) {
        return new TaskCancelledException(annulée.getMessage());
    }

    /** Résultat interne d'une passe de marche. */
    private record Marche(Map<Gav, String> développées, Map<Gav, Coordinate> finales,
                          Map<Gav, Gav> familles) {
    }

    /**
     * Un nœud du graphe, décrit par son POM effectif OU par sa variante Gradle
     * sélectionnée : dépendances normalisées en {@link RawPom.PomDep}, contraintes
     * d'alignement (métadonnées Gradle seulement), relogement éventuel (relocation
     * POM ou redirection {@code available-at}) et nom du fichier d'artefact quand
     * il diffère du nom Maven conventionnel (fichiers de variante).
     */
    private record Descripteur(Coordinate coordonnée, String packaging,
                               List<RawPom.PomDep> dépendances,
                               Map<Gav, String> contraintes,
                               Coordinate relogement, String fichierArtefact) {
    }

    /**
     * Marche transitive : charge les descripteurs (métadonnées Gradle
     * sélectionnées, sinon POM effectifs), suit les relogements, applique les
     * exclusions, borne le graphe.
     */
    private Marche marcher(List<RootDependency> racines, Map<Gav, String> forcées,
                           Map<Coordinate, PomEffectif> poms,
                           Map<Gav, Set<String>> versionsDemandées,
                           Map<Gav, List<List<String>>> chemins, Set<Gav> identités,
                           List<Arête> arêtes) {
        Map<Gav, String> développées = new LinkedHashMap<>();
        Map<Gav, Coordinate> finales = new LinkedHashMap<>();
        // Familles de capacités : chaque relogement lie l'identité passerelle à
        // l'identité portante (ui ↔ ui-android, collection ↔ collection-jvm) —
        // Gradle médie ces GA comme UN SEUL module ; sans cela, deux versions
        // d'une même bibliothèque coexistent sous deux GA et le dexeur refuse
        // les classes en double.
        Map<Gav, Gav> familles = new LinkedHashMap<>();
        Deque<Demande> file = new ArrayDeque<>();
        for (RootDependency racine : racines) {
            Coordinate c = racine.coordinate();
            if (forcées != null && forcées.containsKey(c.gav())) {
                c = new Coordinate(c.group(), c.artifact(), forcées.get(c.gav()));
            }
            file.add(new Demande(c, 0, new HashSet<>(), List.of(c.toString())));
        }
        int nœuds = 0;
        Set<Coordinate> vus = new HashSet<>();
        while (!file.isEmpty()) {
            vérifierAnnulation();
            Demande demande = file.poll();
            Coordinate coordonnée = demande.coordonnée();
            if (!vus.add(coordonnée)) {
                continue;
            }
            if (++nœuds > BORNE_NOEUDS) {
                throw new ResolverException("Dépendances : borne de " + BORNE_NOEUDS
                        + " nœuds dépassée (graphe trop large ou cyclique) — "
                        + "dernières coordonnées : " + extrait(file));
            }
            Descripteur descripteur = descripteurEffectif(coordonnée);
            // Relogement (relocation POM ou redirection available-at des
            // métadonnées Gradle) : la nouvelle coordonnée remplace l'ancienne,
            // et le lien d'identité est mémorisé (médiation par famille).
            int relogements = 0;
            while (descripteur.relogement() != null
                    && relogements++ < BORNE_RELOGEMENTS) {
                avertissements.accept("relogement : " + coordonnée + " -> "
                        + descripteur.relogement());
                familles.put(coordonnée.gav(), descripteur.relogement().gav());
                coordonnée = descripteur.relogement();
                descripteur = descripteurEffectif(coordonnée);
            }
            identités.add(coordonnée.gav());
            finales.put(demande.coordonnée().gav(), coordonnée);
            versionsDemandées.computeIfAbsent(coordonnée.gav(),
                    k -> new LinkedHashSet<>()).add(coordonnée.version());
            chemins.computeIfAbsent(coordonnée.gav(), k -> new ArrayList<>())
                    .add(demande.chemin());
            développées.put(coordonnée.gav(), coordonnée.version());
            // Contraintes d'alignement (métadonnées Gradle) : elles imposent une
            // version SI la dépendance est dans le graphe — elles ne tirent jamais
            // de nœud, mais la médiation doit les voir (sinon deux variantes
            // d'une même famille coexisteraient, et le dexeur refuserait les
            // classes en double).
            for (Map.Entry<Gav, String> contrainte
                    : descripteur.contraintes().entrySet()) {
                versionsDemandées.computeIfAbsent(contrainte.getKey(),
                        k -> new LinkedHashSet<>()).add(contrainte.getValue());
                List<String> cheminContrainte = new ArrayList<>(demande.chemin());
                cheminContrainte.add(contrainte.getKey() + ":" + contrainte.getValue()
                        + " (contrainte)");
                chemins.computeIfAbsent(contrainte.getKey(), k -> new ArrayList<>())
                        .add(cheminContrainte);
            }

            for (RawPom.PomDep dépendance : descripteur.dépendances()) {
                if (dépendance.optional()) {
                    continue; // Les optionnelles ne traversent jamais.
                }
                String portée = dépendance.scope() == null
                        ? "compile" : dépendance.scope().toLowerCase();
                boolean transitive = portée.equals("compile") || portée.equals("runtime");
                Gav cible = dépendance.gav();
                if (demande.exclusions().contains(cible)) {
                    continue;
                }
                // Enregistrer l'arête même pour les non-transitives (roots fournis par
                // le POM) : la fermeture d'exécution les ignore, la médiation les voit.
                String version = versionEffective(dépendance, forcées, cible);
                if (version == null) {
                    throw new ResolverException("Dépendance sans version et sans entrée"
                            + " de gestion pour " + cible + " (requise par "
                            + coordonnée + ") : ajoutez une version ou une nomenclature");
                }
                Coordinate vers = new Coordinate(cible.group(), cible.artifact(), version);
                arêtes.add(new Arête(coordonnée, vers, portée, dépendance.optional()));
                if (!transitive) {
                    continue; // provided/test : jamais transitives.
                }
                Set<Gav> exclusions = new HashSet<>(demande.exclusions());
                exclusions.addAll(dépendance.exclusions());
                List<String> chemin = new ArrayList<>(demande.chemin());
                chemin.add(vers.toString());
                if (chemin.size() > BORNE_PROFONDEUR) {
                    throw new ResolverException("Dépendances : profondeur "
                            + BORNE_PROFONDEUR + " dépassée à " + vers
                            + " — chaîne : " + chemin);
                }
                file.add(new Demande(vers, demande.distance() + 1, exclusions, chemin));
            }
        }
        return new Marche(développées, finales, familles);
    }

    /** Version effective d'une dépendance (forcée par la médiation si fournie,
     * plage exacte « [v] » réduite à v). */
    private static String versionEffective(RawPom.PomDep dépendance,
                                           Map<Gav, String> forcées, Gav cible) {
        String version = forcées != null && forcées.containsKey(cible)
                ? forcées.get(cible) : dépendance.version();
        return version == null ? null : versionExacte(version);
    }

    /**
     * Réduit une plage Maven EXACTE {@code [1.2.3]} à {@code 1.2.3} — le pont
     * que les éditeurs Gradle posent dans leurs POMs pour les consommateurs
     * Maven (androidx notamment) ; toute autre forme de plage (bornes,
     * intervalles) est refusée nommément : déclarez une version exacte.
     */
    static String versionExacte(String version) {
        if (!version.startsWith("[")) {
            return version;
        }
        if (version.endsWith("]") && version.indexOf(',') < 0
                && version.length() > 2) {
            return version.substring(1, version.length() - 1);
        }
        throw new ResolverException("plage de versions Maven non supportée : "
                + version + " — déclarez une version exacte de la dépendance");
    }

    /**
     * Le descripteur d'une coordonnée : sa variante Gradle sélectionnée quand
     * les métadonnées de module existent et conviennent, sinon son POM effectif
     * (chemin historique — un {@code .module} absent, malformé ou sans variante
     * consommable ne doit jamais empêcher de résoudre).
     */
    private Descripteur descripteurEffectif(Coordinate coordonnée) {
        MétadonnéesGradle.Module module = moduleGradle(coordonnée);
        if (module != null) {
            java.util.Optional<MétadonnéesGradle.Variante> api =
                    MétadonnéesGradle.sélectionner(module, cibleAndroid);
            if (api.isEmpty()) {
                avertissements.accept(coordonnée + " : métadonnées Gradle sans "
                        + "variante consommable pour cette cible — repli au POM");
            } else {
                MétadonnéesGradle.Variante variante = api.get();
                if (variante.disponibleÀ() != null) {
                    // Redirection available-at : les fichiers vivent ailleurs —
                    // c'est un relogement, la marche remplace la coordonnée.
                    MétadonnéesGradle.Disponibilité à = variante.disponibleÀ();
                    return new Descripteur(coordonnée, "jar", List.of(), Map.of(),
                            new Coordinate(à.groupe(), à.nom(), à.version()), null);
                }
                List<RawPom.PomDep> dépendances = new ArrayList<>();
                Map<Gav, String> contraintes = new LinkedHashMap<>();
                Set<Gav> vuesApi = new HashSet<>();
                for (MétadonnéesGradle.Dépendance dépendance : variante.dépendances()) {
                    if (estPlateForme(dépendance)) {
                        // Dépendance de PLATE-FORME (nomenclature Gradle) : elle
                        // aligne les versions, elle ne tire AUCUN artefact — la
                        // traiter comme un nœud téléchargerait un pom.
                        contrainte(contraintes, dépendance);
                        continue;
                    }
                    dépendances.add(pomDepDe(dépendance, "compile"));
                    vuesApi.add(new Gav(dépendance.groupe(), dépendance.nom()));
                }
                // Dépendances runtime-seulement : l'équivalent GMM de la portée
                // Maven runtime vit dans la variante runtime — la fermeture
                // d'exécution doit les suivre, celle de compilation les ignore.
                java.util.Optional<MétadonnéesGradle.Variante> runtime =
                        MétadonnéesGradle.varianteRuntime(module, cibleAndroid);
                if (runtime.isPresent()) {
                    for (MétadonnéesGradle.Dépendance dépendance
                            : runtime.get().dépendances()) {
                        if (estPlateForme(dépendance)) {
                            contrainte(contraintes, dépendance);
                            continue;
                        }
                        if (!vuesApi.contains(new Gav(dépendance.groupe(),
                                dépendance.nom()))) {
                            dépendances.add(pomDepDe(dépendance, "runtime"));
                        }
                    }
                }
                for (MétadonnéesGradle.Dépendance contrainte : variante.contraintes()) {
                    if (contrainte.version() != null) {
                        contraintes.putIfAbsent(new Gav(contrainte.groupe(),
                                contrainte.nom()), versionExacte(contrainte.version()));
                    }
                }
                String fichier = premierFichier(variante);
                String packaging = fichier != null && fichier.endsWith(".aar")
                        ? "aar" : "jar";
                return new Descripteur(coordonnée, packaging, dépendances,
                        contraintes, null, fichier);
            }
        }
        PomEffectif pom = pomEffectif(coordonnée, new HashSet<>(), 0);
        return new Descripteur(coordonnée, pom.packaging(), pom.dépendances(),
                Map.of(), pom.relogement(), null);
    }

    /** Une dépendance de VARIANTE est-elle une plateforme (nomenclature Gradle :
     * alignement sans artefact) ? */
    private static boolean estPlateForme(MétadonnéesGradle.Dépendance dépendance) {
        String catégorie = dépendance.attributs().get("org.gradle.category");
        return "platform".equals(catégorie) || "enforced-platform".equals(catégorie);
    }

    /** Enregistre une contrainte d'alignement (version résoluble). */
    private static void contrainte(Map<Gav, String> contraintes,
                                    MétadonnéesGradle.Dépendance dépendance) {
        if (dépendance.version() != null) {
            contraintes.putIfAbsent(new Gav(dépendance.groupe(), dépendance.nom()),
                    versionExacte(dépendance.version()));
        }
    }

    /** Une dépendance de variante Gradle, normalisée en dépendance POM. */
    private static RawPom.PomDep pomDepDe(MétadonnéesGradle.Dépendance dépendance,
                                          String portée) {
        Set<Gav> exclusions = new HashSet<>();
        for (String exclusion : dépendance.exclusions()) {
            int deuxPoints = exclusion.indexOf(':');
            if (deuxPoints > 0 && deuxPoints < exclusion.length() - 1) {
                exclusions.add(new Gav(exclusion.substring(0, deuxPoints),
                        exclusion.substring(deuxPoints + 1)));
            }
        }
        return new RawPom.PomDep(dépendance.groupe(), dépendance.nom(),
                dépendance.version(), portée, false, exclusions);
    }

    /** Le premier fichier jar/aar d'une variante (l'artefact principal). */
    private static String premierFichier(MétadonnéesGradle.Variante variante) {
        for (String fichier : variante.fichiers()) {
            if (fichier.endsWith(".jar") || fichier.endsWith(".aar")) {
                return fichier;
            }
        }
        return null;
    }

    /**
     * Les métadonnées de module Gradle d'une coordonnée : cache d'abord, dépôts
     * ensuite ; absentes ou malformées → null (repli POM, jamais d'échec).
     */
    private MétadonnéesGradle.Module moduleGradle(Coordinate coordonnée) {
        if (cache.aModule(coordonnée)) {
            return lireModule(cache.cheminModule(coordonnée));
        }
        if (horsLigne || cache.marquéIntrouvableRécemment(coordonnée, "module")) {
            return null;
        }
        for (String dépôt : dépôts) {
            String url = dépôt + cheminRessource(coordonnée, ".module");
            try {
                transport.téléchargerVersFichier(url, cache.cheminModule(coordonnée),
                        url + ".sha1");
                return lireModule(cache.cheminModule(coordonnée));
            } catch (HttpTransport.Introuvable introuvable) {
                cache.marquerIntrouvable(coordonnée, "module");
            } catch (HttpTransport.Annulée annulée) {
                throw annulé(annulée);
            } catch (IOException e) {
                avertissements.accept(url + " : " + e.getMessage());
            }
        }
        return null;
    }

    /** Lit (et analyse toléramment) des métadonnées de module en cache. */
    private static MétadonnéesGradle.Module lireModule(Path chemin) {
        try {
            return MétadonnéesGradle.analyser(Files.readAllBytes(chemin)).orElse(null);
        } catch (IOException illisible) {
            return null;
        }
    }

    /**
     * Représentant de famille de chaque identité (union-find léger sur les
     * liens de relogement observés pendant la marche : la passerelle pointe
     * l'identité portante, puis les chaînes se résolvent par compression).
     */
    private static Map<Gav, Gav> représentants(Map<Gav, Gav> familles) {
        Map<Gav, Gav> parent = new LinkedHashMap<>();
        for (Map.Entry<Gav, Gav> lien : familles.entrySet()) {
            parent.putIfAbsent(lien.getKey(), lien.getKey());
            parent.putIfAbsent(lien.getValue(), lien.getValue());
        }
        // L'UNION : chaque passerelle pointe son identité portante (les portants
        // gardent leur propre racine ; une chaîne de relogements se résout par
        // la compression ci-dessous).
        for (Map.Entry<Gav, Gav> lien : familles.entrySet()) {
            parent.put(lien.getKey(), lien.getValue());
        }
        for (Gav gav : List.copyOf(new ArrayList<>(parent.keySet()))) {
            Gav racine = gav;
            for (int pas = 0; pas < BORNE_RELOGEMENTS + 1; pas++) {
                Gav suivant = parent.get(racine);
                if (suivant == null || suivant.equals(racine)) {
                    break;
                }
                racine = suivant;
            }
            parent.put(gav, racine);
        }
        return parent;
    }

    /**
     * Applique la politique de médiation PAR FAMILLE de capacités et retourne
     * la version retenue par famille (les versions demandées des membres
     * d'une même famille — passerelle et variante — médient ensemble).
     */
    private Map<Gav, String> médier(Map<Gav, Set<String>> versionsDemandées,
                                    Map<Gav, List<List<String>>> chemins,
                                    ConflictPolicy politique,
                                    Map<Gav, Gav> représentants) {
        Map<Gav, Set<String>> parFamille = new LinkedHashMap<>();
        Map<Gav, List<List<String>>> cheminsFamille = new LinkedHashMap<>();
        for (Map.Entry<Gav, Set<String>> entrée : versionsDemandées.entrySet()) {
            Gav famille = représentants.getOrDefault(entrée.getKey(), entrée.getKey());
            parFamille.computeIfAbsent(famille, k -> new LinkedHashSet<>())
                    .addAll(entrée.getValue());
        }
        for (Map.Entry<Gav, List<List<String>>> entrée : chemins.entrySet()) {
            Gav famille = représentants.getOrDefault(entrée.getKey(), entrée.getKey());
            cheminsFamille.computeIfAbsent(famille, k -> new ArrayList<>())
                    .addAll(entrée.getValue());
        }
        Map<Gav, String> choisies = new LinkedHashMap<>();
        for (Map.Entry<Gav, Set<String>> entrée : parFamille.entrySet()) {
            Gav identité = entrée.getKey();
            Set<String> versions = entrée.getValue();
            if (versions.size() == 1) {
                choisies.put(identité, versions.iterator().next());
                continue;
            }
            if (politique == ConflictPolicy.FAIL_ON_CONFLICT) {
                StringBuilder message = new StringBuilder(
                        "Conflit de versions refusé pour " + identité + " : ");
                for (List<String> chemin : cheminsFamille.get(identité)) {
                    message.append(String.join(" → ", chemin)).append(" ; ");
                }
                throw new ResolverException(message.toString());
            }
            if (politique == ConflictPolicy.NEWEST) {
                choisies.put(identité, MavenVersion.plusRécente(versions));
            } else { // NEAREST : la plus proche de la racine, puis la première vue.
                List<List<String>> cheminsIdentité = cheminsFamille.get(identité);
                List<String> plusCourt = cheminsIdentité.get(0);
                for (List<String> chemin : cheminsIdentité) {
                    if (chemin.size() < plusCourt.size()) {
                        plusCourt = chemin;
                    }
                }
                String versionFinale = plusCourt.get(plusCourt.size() - 1)
                        .substring(plusCourt.get(plusCourt.size() - 1).lastIndexOf(':') + 1);
                choisies.put(identité, versionFinale);
            }
        }
        return choisies;
    }

    /**
     * Indique si la médiation (par famille) a changé une version que la marche
     * avait développée (comparaison au représentant de chaque identité).
     */
    private static boolean diverge(Map<Gav, String> développées,
                                   Map<Gav, String> choisies,
                                   Map<Gav, Gav> représentants) {
        for (Map.Entry<Gav, String> entrée : développées.entrySet()) {
            Gav famille = représentants.getOrDefault(entrée.getKey(), entrée.getKey());
            String choisie = choisies.get(famille);
            if (choisie != null && !choisie.equals(entrée.getValue())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Les versions forcées de la RE-MARCHE : la version retenue de sa famille
     * pour chaque identité développée (les passerelles suivront leur
     * relogement vers l'artefact portant à cette version).
     */
    private static Map<Gav, String> forcéesParFamille(Map<Gav, String> choisies,
                                                      Map<Gav, Gav> représentants,
                                                      Set<Gav> identitésDéveloppées) {
        Map<Gav, String> forcées = new LinkedHashMap<>();
        for (Gav gav : identitésDéveloppées) {
            Gav famille = représentants.getOrDefault(gav, gav);
            String version = choisies.get(famille);
            if (version != null) {
                forcées.put(gav, version);
            }
        }
        return forcées;
    }

    /**
     * Fermeture de compilation : une racine api expose ses transitives compile+runtime.
     */
    private void fermetureCompilation(Coordinate depuis,
                                      Map<Coordinate, List<RawPom.PomDep>> transitives,
                                      Map<Gav, Coordinate> retenues,
                                      Set<Coordinate> accumulation, Set<Gav> vus) {
        if (!vus.add(depuis.gav())) {
            return;
        }
        for (RawPom.PomDep dépendance
                : transitives.getOrDefault(depuis, List.of())) {
            String portée = dépendance.scope() == null
                    ? "compile" : dépendance.scope().toLowerCase();
            if (!portée.equals("compile") && !portée.equals("runtime")) {
                continue;
            }
            Coordinate cible = retenues.get(dépendance.gav());
            if (cible == null) {
                continue;
            }
            accumulation.add(cible);
            fermetureCompilation(cible, transitives, retenues, accumulation, vus);
        }
    }

    /**
     * Fermeture d'exécution : toutes les transitives compile+runtime.
     */
    private void fermetureExécution(Coordinate depuis,
                                    Map<Coordinate, List<RawPom.PomDep>> transitives,
                                    Map<Gav, Coordinate> retenues,
                                    Set<Coordinate> accumulation, Set<Gav> vus) {
        if (!vus.add(depuis.gav())) {
            return;
        }
        for (RawPom.PomDep dépendance
                : transitives.getOrDefault(depuis, List.of())) {
            String portée = dépendance.scope() == null
                    ? "compile" : dépendance.scope().toLowerCase();
            if (!portée.equals("compile") && !portée.equals("runtime")) {
                continue;
            }
            Coordinate cible = retenues.get(dépendance.gav());
            if (cible == null) {
                continue;
            }
            accumulation.add(cible);
            fermetureExécution(cible, transitives, retenues, accumulation, vus);
        }
    }

    /**
     * Dépendance POM reconstruite depuis une arête (pour les fermetures).
     */
    private static RawPom.PomDep dépendanceDe(Arête arête) {
        return new RawPom.PomDep(arête.vers().group(), arête.vers().artifact(),
                arête.vers().version(), arête.portéeMaven(), arête.optionnelle(),
                Set.of());
    }

    /**
     * Télécharge (ou relit) l'artefact d'une coordonnée retenue : jar direct, ou
     * AAR téléchargé puis explosé dans le cache (le {@code classes.jar} devient
     * l'entrée de classpath, le répertoire d'explosion porte res/assets/manifeste
     * pour la chaîne Android). Le nom du fichier suit la variante Gradle quand
     * les métadonnées en désignent un ({@code ui-release.aar}), avec repli au
     * nom Maven conventionnel.
     */
    private ResolvedJar téléchargerArtefact(Coordinate coordonnée) {
        Descripteur descripteur = descripteurEffectif(coordonnée);
        // Suivi défensif du relogement : la médiation par famille retient déjà
        // l'artefact portant, mais un cas non couvert doit télécharger le BON
        // fichier, pas échouer.
        int relogements = 0;
        while (descripteur.relogement() != null
                && relogements++ < BORNE_RELOGEMENTS) {
            coordonnée = descripteur.relogement();
            descripteur = descripteurEffectif(coordonnée);
        }
        String packaging = descripteur.packaging();
        if (!"jar".equals(packaging) && !"aar".equals(packaging)) {
            throw new ResolverException(coordonnée + " : empaquetage « " + packaging
                    + " » non supporté (jar ou aar seulement)");
        }
        String extension = "." + packaging;
        if (cache.aArtefact(coordonnée, extension)) {
            return acheverArtefact(coordonnée, packaging);
        }
        if (horsLigne) {
            throw new ResolverException(coordonnée + " absent du cache et mode hors"
                    + " ligne actif — désinstallez le mode hors ligne ou préremplissez"
                    + " le cache");
        }
        String nomConventionnel = coordonnée.artifact() + "-" + coordonnée.version()
                + extension;
        String nomVariante = descripteur.fichierArtefact();
        // Le fichier de variante d'abord, le nom conventionnel ensuite : certains
        // dépôts ne servent que l'un des deux.
        List<String> noms = nomVariante != null && !nomVariante.equals(nomConventionnel)
                ? List.of(nomVariante, nomConventionnel) : List.of(nomConventionnel);
        for (int essai = 0; essai < noms.size(); essai++) {
            String nom = noms.get(essai);
            StringBuilder erreurs = new StringBuilder();
            for (String dépôt : dépôts) {
                String url = dépôt + cheminRépertoire(coordonnée) + "/" + nom;
                try {
                    transport.téléchargerVersFichier(url,
                            cache.cheminArtefact(coordonnée, extension), url + ".sha1");
                    return acheverArtefact(coordonnée, packaging);
                } catch (HttpTransport.Introuvable introuvable) {
                    cache.marquerIntrouvable(coordonnée, packaging);
                    erreurs.append(introuvable.getMessage()).append(" ; ");
                } catch (HttpTransport.Annulée annulée) {
                    throw annulé(annulée);
                } catch (IOException e) {
                    erreurs.append(url).append(" : ").append(e.getMessage())
                            .append(" ; ");
                }
            }
            if (essai == noms.size() - 1) {
                throw new ResolverException("Artefact " + packaging + " introuvable pour "
                        + coordonnée + " — dépôts essayés : " + erreurs);
            }
        }
        throw new ResolverException("Artefact introuvable pour " + coordonnée);
    }

    /**
     * Finalise un artefact en cache : explosion (et bornes minCompileSdk) pour un
     * AAR, entrée directe pour un jar.
     */
    private ResolvedJar acheverArtefact(Coordinate coordonnée, String packaging) {
        if (!"aar".equals(packaging)) {
            return new ResolvedJar(coordonnée,
                    cache.cheminArtefact(coordonnée, ".jar"), null);
        }
        AarExplodé explosé;
        try {
            explosé = AarExplodé.exploser(
                    cache.cheminArtefact(coordonnée, ".aar"),
                    cache.répertoireExplosion(coordonnée));
        } catch (IOException e) {
            throw new ResolverException(coordonnée + " : AAR illisible — "
                    + e.getMessage(), e);
        }
        if (apiCible > 0 && explosé.minCompileSdk().isPresent()
                && explosé.minCompileSdk().getAsInt() > apiCible) {
            throw new ResolverException(coordonnée + " exige un niveau d'API de "
                    + "compilation " + explosé.minCompileSdk().getAsInt()
                    + " (minCompileSdk de ses métadonnées AAR) — le module cible "
                    + apiCible + " : montez targetSdk du module dans forge.json");
        }
        return new ResolvedJar(coordonnée, explosé.classesJar(), null, "aar",
                cache.répertoireExplosion(coordonnée));
    }

    /**
     * POM effectif d'une coordonnée : parents fusionnés, propriétés substituées,
     * gestion de dépendances (y compris nomenclatures importées) appliquée.
     *
     * @param coordonnée  la coordonnée.
     * @param gardeParents coordonnées déjà en cours de fusion (anti-cycle).
     * @param profondeurParents profondeur de la chaîne de parents.
     * @return le POM effectif (avec relogement éventuel à suivre par l'appelant).
     */
    private PomEffectif pomEffectif(Coordinate coordonnée, Set<Coordinate> gardeParents,
                                    int profondeurParents) {
        RawPom pom = chargerPom(coordonnée);
        if (pom.relocation() != null) {
            // Le relogement est géré par la marche ; ici on ne fait que le porter.
            return new PomEffectif(coordonnée, pom.packaging(), Map.of(), List.of(),
                    pom.relocation());
        }
        Map<String, String> propriétés = new LinkedHashMap<>();
        Map<Gav, String> gérées = new LinkedHashMap<>();
        List<RawPom.PomDep> dépendances = new ArrayList<>();
        Coordinate identité = coordonnée;

        // Chaîne de parents (la plus lointaine d'abord) : propriétés et gestion.
        List<RawPom> chaîne = new ArrayList<>();
        RawPom courant = pom;
        Set<Coordinate> vus = new HashSet<>(gardeParents);
        while (courant != null) {
            if (!vus.add(coordonnéeDe(courant, identité))) {
                throw new ResolverException("Cycle de parents POM à "
                        + coordonnée + " — chaîne : " + chaîne);
            }
            if (chaîne.size() > 20) {
                throw new ResolverException("Chaîne de parents PON trop longue (> 20) à "
                        + coordonnée);
            }
            chaîne.add(courant);
            if (profondeurParents++ > 20) {
                throw new ResolverException("Profondeur de parents POM trop grande à "
                        + coordonnée);
            }
            Coordinate parent = courant.parent();
            courant = parent == null ? null : chargerPom(parent);
        }
        java.util.Collections.reverse(chaîne); // du parent racine vers l'enfant
        for (RawPom niveau : chaîne) {
            propriétés.putAll(niveau.properties());
            for (RawPom.ManagedDep gérée : niveau.managed()) {
                gérées.put(gérée.gav(), gérée.version());
            }
        }
        // Nomenclatures importées (BOM) : leur gestion fusionne (premier gagne).
        for (RawPom niveau : chaîne) {
            for (RawPom.ManagedDep gérée : niveau.managed()) {
                if (gérée.version() != null && gérée.version().startsWith("import:")) {
                    Coordinate bom = new Coordinate(gérée.gav().group(),
                            gérée.gav().artifact(),
                            gérée.version().substring("import:".length()));
                    PomEffectif pomBom = pomEffectif(bom, new HashSet<>(vus), 0);
                    if (pomBom.relogement != null) {
                        throw new ResolverException("Nomenclature " + bom
                                + " relogée — non supporté");
                    }
                    for (Map.Entry<Gav, String> entrée : pomBom.gérées.entrySet()) {
                        gérées.putIfAbsent(entrée.getKey(), entrée.getValue());
                    }
                }
            }
        }
        // Les versions gérées sont substituées elles aussi (elles portent souvent des
        // propriétés).
        for (Map.Entry<Gav, String> entrée : gérées.entrySet()) {
            String version = entrée.getValue();
            if (version != null && !version.startsWith("import:")) {
                entrée.setValue(substituer(version, propriétés));
            }
        }
        // Propriétés implicites de la coordonnée.
        propriétés.putIfAbsent("project.version", coordonnée.version());
        propriétés.putIfAbsent("project.groupId", coordonnée.group());
        propriétés.putIfAbsent("project.artifactId", coordonnée.artifact());
        propriétés.putIfAbsent("version", coordonnée.version());

        for (RawPom.PomDep dépendance : pom.dependencies()) {
            String version = dépendance.version();
            if (version == null) {
                version = gérées.get(dépendance.gav());
            } else {
                version = substituer(version, propriétés);
            }
            dépendances.add(new RawPom.PomDep(
                    substituer(dépendance.group(), propriétés),
                    substituer(dépendance.artifact(), propriétés),
                    version,
                    dépendance.scope() == null ? null
                            : substituer(dépendance.scope(), propriétés),
                    dépendance.optional(), dépendance.exclusions()));
        }
        return new PomEffectif(coordonnée, substituer(pom.packaging(), propriétés),
                gérées, dépendances, null);
    }

    /** Coordonnée d'un POM brut (avec repli parent pour groupe/version). */
    private static Coordinate coordonnéeDe(RawPom pom, Coordinate parDéfaut) {
        return new Coordinate(
                pom.group() == null ? parDéfaut.group() : pom.group(),
                pom.artifact(),
                pom.version() == null ? parDéfaut.version() : pom.version());
    }

    /**
     * Charge le POM d'une coordonnée : cache d'abord, dépôts ensuite (hors ligne :
     * cache seul, échec clair). Un POM TÉLÉCHARGÉ mais rejeté (DOCTYPE, structure)
     * est dit INVALIDE — pas « introuvable », qui enverrait sur une piste réseau
     * erronée — et n'est pas laissé en cache (un nouvel essai re-téléchargera, un
     * miroir sain récupérera).
     */
    private RawPom chargerPom(Coordinate coordonnée) {
        if (cache.aPom(coordonnée)) {
            return PomParser.parse(cache.cheminPom(coordonnée), coordonnée);
        }
        if (horsLigne) {
            throw new ResolverException("POM de " + coordonnée + " absent du cache et"
                    + " mode hors ligne actif");
        }
        if (cache.marquéIntrouvableRécemment(coordonnée, "pom")) {
            throw new ResolverException(coordonnée + " : POM récemment introuvable"
                    + " (cache négatif) et toujours absent du cache");
        }
        StringBuilder erreurs = new StringBuilder();
        boolean téléchargéMaisInvalide = false;
        for (String dépôt : dépôts) {
            String url = dépôt + cheminRessource(coordonnée, ".pom");
            try {
                transport.téléchargerVersFichier(url, cache.cheminPom(coordonnée),
                        url + ".sha1");
                return PomParser.parse(cache.cheminPom(coordonnée), coordonnée);
            } catch (HttpTransport.Introuvable introuvable) {
                cache.marquerIntrouvable(coordonnée, "pom");
                erreurs.append(introuvable.getMessage()).append(" ; ");
            } catch (HttpTransport.Annulée annulée) {
                throw annulé(annulée);
            } catch (ResolverException invalide) {
                // Le dépôt a RÉPONDU (téléchargement et somme vérifiés) mais le
                // contenu est rejeté : effacer le fichier du cache et le DIRE.
                try {
                    Files.deleteIfExists(cache.cheminPom(coordonnée));
                } catch (IOException purgeImpossible) {
                    // Le fichier invalide restera jusqu'à sa prochaine
                    // réécriture — sans conséquence : le parse échouera pareil.
                }
                téléchargéMaisInvalide = true;
                erreurs.append(url).append(" : ").append(invalide.getMessage())
                        .append(" ; ");
            } catch (IOException e) {
                erreurs.append(url).append(" : ").append(e.getMessage()).append(" ; ");
            }
        }
        throw new ResolverException((téléchargéMaisInvalide
                ? "POM invalide pour " + coordonnée
                        + " (téléchargé mais son contenu est rejeté)"
                : "POM introuvable pour " + coordonnée)
                + " — dépôts essayés : " + erreurs);
    }

    /**
     * Chemin de ressource Maven d'une coordonnée.
     */
    private static String cheminRessource(Coordinate coordonnée, String suffixe) {
        return cheminRépertoire(coordonnée) + "/" + coordonnée.artifact() + "-"
                + coordonnée.version() + suffixe;
    }

    /** Chemin du RÉPERTOIRE de version d'une coordonnée (base des fichiers
     * de variante Gradle, qui ne portent pas le nom Maven conventionnel). */
    private static String cheminRépertoire(Coordinate coordonnée) {
        return "/" + coordonnée.group().replace('.', '/') + "/"
                + coordonnée.artifact() + "/" + coordonnée.version();
    }

    /**
     * Substitue les propriétés {@code ${...}} (bornée à 8 itérations ; une propriété
     * inconnue reste telle quelle avec avertissement).
     */
    private String substituer(String valeur, Map<String, String> propriétés) {
        if (valeur == null || !valeur.contains("${")) {
            return valeur;
        }
        String actuelle = valeur;
        for (int itération = 0; itération < 8; itération++) {
            if (!actuelle.contains("${")) {
                return actuelle;
            }
            StringBuilder résultat = new StringBuilder();
            int i = 0;
            boolean changé = false;
            while (i < actuelle.length()) {
                int début = actuelle.indexOf("${", i);
                if (début < 0) {
                    résultat.append(actuelle, i, actuelle.length());
                    break;
                }
                int fin = actuelle.indexOf('}', début + 2);
                if (fin < 0) {
                    résultat.append(actuelle, i, actuelle.length());
                    break;
                }
                résultat.append(actuelle, i, début);
                String clef = actuelle.substring(début + 2, fin);
                String remplacement = propriétés.get(clef);
                if (remplacement != null) {
                    résultat.append(remplacement);
                    changé = true;
                } else {
                    résultat.append(actuelle, début, fin + 1);
                }
                i = fin + 1;
            }
            actuelle = résultat.toString();
            if (!changé) {
                if (actuelle.contains("${")) {
                    avertissements.accept("propriété POM inconnue laissée telle quelle : "
                            + actuelle);
                }
                return actuelle;
            }
        }
        return actuelle;
    }

    /** Extrait quelques coordonnées de la file pour un diagnostic de borne. */
    private static List<String> extrait(Deque<Demande> file) {
        List<String> aperçu = new ArrayList<>();
        for (Demande demande : file) {
            aperçu.add(demande.coordonnée().toString());
            if (aperçu.size() >= 5) {
                break;
            }
        }
        return aperçu;
    }
}
