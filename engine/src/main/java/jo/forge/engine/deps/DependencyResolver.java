package jo.forge.engine.deps;

import jo.forge.engine.model.DependencyScope;

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
import java.util.Set;
import java.util.function.Consumer;

/**
 * Résolveur de dépendances Maven : marche transitive, POM effectifs (parents,
 * propriétés, gestion de dépendances, nomenclatures importées, relogements),
 * médiation de versions, cache hors ligne, intégrité vérifiée.
 *
 * <p>Sémantique des portées (miroir des bibliothèques Java Gradle) : une racine
 * {@code api} expose sa fermeture transitive au classpath de compilation du module ;
 * une racine {@code implementation} n'expose qu'elle-même ; {@code compileOnly} n'est
 * que compilation ; {@code runtimeOnly} n'est qu'exécution.</p>
 *
 * <p>Bornes explicites (le moteur de référence coupe silencieusement — constat
 * consigné ; nous échouons avec un diagnostic) : nombre de nœuds, profondeur, nombre
 * de relogements.</p>
 *
 * <p>Marche séquentielle en v1 (la référence parallélise par vagues — optimisation
 * future consignée) ; les choix de versions convergent en au plus deux passes
 * (re-marche forcée si la médiation change une version développée).</p>
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

    /** Une dépendance racine demandée par un module. */
    public record RootDependency(Coordinate coordinate, DependencyScope scope) {
    }

    /** Un jar résolu, prêt pour un classpath. */
    public record ResolvedJar(Coordinate coordinate, Path jar, String sha256) {
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
     * Crée le résolveur.
     *
     * @param dépôts        URLs des dépôts (HTTPS exigé, boucle locale tolérée).
     * @param cache         cache persistant (réplique Maven, hors ligne).
     * @param horsLigne     aucun accès réseau : cache seul, échec clair sinon.
     * @param avertissements consommateur d'avertissements.
     */
    public DependencyResolver(List<String> dépôts, ResolverCache cache, boolean horsLigne,
                               Consumer<String> avertissements) {
        this.dépôts = dépôts.stream()
                .map(dépôt -> dépôt.endsWith("/") && dépôt.length() > 1
                        ? dépôt.substring(0, dépôt.length() - 1) : dépôt)
                .toList();
        this.cache = cache;
        this.horsLigne = horsLigne;
        this.avertissements = avertissements == null ? message -> { } : avertissements;
        this.transport = new HttpTransport(this.avertissements);
        this.dépôts.forEach(HttpTransport::vérifierUrlDépôt);
        if (this.dépôts.isEmpty()) {
            throw new ResolverException(
                    "Aucun dépôt déclaré : ajoutez « repositories » dans forge.json");
        }
    }

    /**
     * Résout les dépendances d'un module et télécharge les jars retenus.
     *
     * @param racines dépendances directes du module (coordonnée + portée Forge).
     * @param politique médiation de versions.
     * @return les classpaths de compilation et d'exécution, ordonnés, sans doublon.
     * @throws ResolverException si une dépendance est introuvable, si un conflit est
     *                           refusé, ou si une borne est dépassée (toujours avec
     *                           diagnostic actionnable).
     */
    public Result resolve(List<RootDependency> racines, ConflictPolicy politique) {
        Map<Coordinate, PomEffectif> poms = new HashMap<>();
        Map<Gav, Set<String>> versionsDemandées = new LinkedHashMap<>();
        Map<Gav, List<List<String>>> chemins = new LinkedHashMap<>();
        Set<Gav> identités = new LinkedHashSet<>();
        List<Arête> arêtes = new ArrayList<>();

        // Première passe : marche avec les versions déclarées ; médiation ; si la
        // médiation change une version développée, seconde passe forcée (convergence).
        Marche marche = marcher(racines, null, poms, versionsDemandées, chemins,
                identités, arêtes);
        Map<Gav, String> choisies = médier(versionsDemandées, chemins, politique);
        if (diverge(marche.développées(), choisies)) {
            poms.clear();
            versionsDemandées = new LinkedHashMap<>();
            chemins = new LinkedHashMap<>();
            identités = new LinkedHashSet<>();
            arêtes = new ArrayList<>();
            marche = marcher(racines, choisies, poms, versionsDemandées, chemins,
                    identités, arêtes);
            choisies = médier(versionsDemandées, chemins, politique);
        }

        // Ensembles de classpath par racine (sémantique des portées).
        Map<Coordinate, List<RawPom.PomDep>> transitives = new HashMap<>();
        for (Arête arête : arêtes) {
            transitives.computeIfAbsent(arête.depuis(), k -> new ArrayList<>())
                    .add(dépendanceDe(arête));
        }
        Map<Gav, Coordinate> retenues = new LinkedHashMap<>();
        for (Gav gav : identités) {
            retenues.put(gav, new Coordinate(gav.group(), gav.artifact(),
                    choisies.get(gav)));
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
                    compilation.add(c);
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
            jarsCompilation.add(téléchargerJar(c));
        }
        List<ResolvedJar> jarsExécution = new ArrayList<>();
        for (Coordinate c : exécution) {
            jarsExécution.add(téléchargerJar(c));
        }
        return new Result(jarsCompilation, jarsExécution);
    }

    /** Résultat interne d'une passe de marche. */
    private record Marche(Map<Gav, String> développées, Map<Gav, Coordinate> finales) {
    }

    /**
     * Marche transitive : charge les POM effectifs, suit les relogements, applique les
     * exclusions, borne le graphe.
     */
    private Marche marcher(List<RootDependency> racines, Map<Gav, String> forcées,
                           Map<Coordinate, PomEffectif> poms,
                           Map<Gav, Set<String>> versionsDemandées,
                           Map<Gav, List<List<String>>> chemins, Set<Gav> identités,
                           List<Arête> arêtes) {
        Map<Gav, String> développées = new LinkedHashMap<>();
        Map<Gav, Coordinate> finales = new LinkedHashMap<>();
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
            PomEffectif pom = pomEffectif(coordonnée, new HashSet<>(), 0);
            // Relogement : la nouvelle coordonnée remplace l'ancienne.
            int relogements = 0;
            while (pom.relogement != null && relogements++ < BORNE_RELOGEMENTS) {
                avertissements.accept("relogement : " + coordonnée + " -> "
                        + pom.relogement);
                coordonnée = pom.relogement;
                pom = pomEffectif(coordonnée, new HashSet<>(), 0);
            }
            poms.put(coordonnée, pom);
            identités.add(coordonnée.gav());
            finales.put(demande.coordonnée().gav(), coordonnée);
            versionsDemandées.computeIfAbsent(coordonnée.gav(),
                    k -> new LinkedHashSet<>()).add(coordonnée.version());
            chemins.computeIfAbsent(coordonnée.gav(), k -> new ArrayList<>())
                    .add(demande.chemin());
            développées.put(coordonnée.gav(), coordonnée.version());

            for (RawPom.PomDep dépendance : pom.dépendances()) {
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
        return new Marche(développées, finales);
    }

    /** Version effective d'une dépendance (forcée par la médiation si fournie). */
    private static String versionEffective(RawPom.PomDep dépendance,
                                           Map<Gav, String> forcées, Gav cible) {
        if (forcées != null && forcées.containsKey(cible)) {
            return forcées.get(cible);
        }
        return dépendance.version();
    }

    /**
     * Applique la politique de médiation et retourne la version retenue par identité.
     */
    private Map<Gav, String> médier(Map<Gav, Set<String>> versionsDemandées,
                                    Map<Gav, List<List<String>>> chemins,
                                    ConflictPolicy politique) {
        Map<Gav, String> choisies = new LinkedHashMap<>();
        for (Map.Entry<Gav, Set<String>> entrée : versionsDemandées.entrySet()) {
            Gav identité = entrée.getKey();
            Set<String> versions = entrée.getValue();
            if (versions.size() == 1) {
                choisies.put(identité, versions.iterator().next());
                continue;
            }
            if (politique == ConflictPolicy.FAIL_ON_CONFLICT) {
                StringBuilder message = new StringBuilder(
                        "Conflit de versions refusé pour " + identité + " : ");
                for (List<String> chemin : chemins.get(identité)) {
                    message.append(String.join(" → ", chemin)).append(" ; ");
                }
                throw new ResolverException(message.toString());
            }
            if (politique == ConflictPolicy.NEWEST) {
                choisies.put(identité, MavenVersion.plusRécente(versions));
            } else { // NEAREST : la plus proche de la racine, puis la première vue.
                List<List<String>> cheminsIdentité = chemins.get(identité);
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
     * Indique si la médiation a changé une version que la marche avait développée.
     */
    private static boolean diverge(Map<Gav, String> développées,
                                   Map<Gav, String> choisies) {
        for (Map.Entry<Gav, String> entrée : développées.entrySet()) {
            String choisie = choisies.get(entrée.getKey());
            if (choisie != null && !choisie.equals(entrée.getValue())) {
                return true;
            }
        }
        return false;
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
     * Télécharge (ou relit) le jar d'une coordonnée retenue.
     */
    private ResolvedJar téléchargerJar(Coordinate coordonnée) {
        PomEffectif pom = pomEffectif(coordonnée, new HashSet<>(), 0);
        if (!"jar".equals(pom.packaging)) {
            throw new ResolverException(coordonnée + " : empaquetage « "
                    + pom.packaging + " » non supporté (jar seulement ; AAR à l'étape 10)");
        }
        if (cache.aArtefact(coordonnée)) {
            return new ResolvedJar(coordonnée, cache.cheminArtefact(coordonnée), null);
        }
        if (horsLigne) {
            throw new ResolverException(coordonnée + " absent du cache et mode hors"
                    + " ligne actif — désinstallez le mode hors ligne ou préremplissez"
                    + " le cache");
        }
        StringBuilder erreurs = new StringBuilder();
        for (String dépôt : dépôts) {
            String url = dépôt + cheminRessource(coordonnée, ".jar");
            try {
                transport.téléchargerVersFichier(url, cache.cheminArtefact(coordonnée),
                        url + ".sha1");
                return new ResolvedJar(coordonnée, cache.cheminArtefact(coordonnée),
                        null);
            } catch (HttpTransport.Introuvable introuvable) {
                cache.marquerIntrouvable(coordonnée, "jar");
                erreurs.append(introuvable.getMessage()).append(" ; ");
            } catch (IOException e) {
                erreurs.append(url).append(" : ").append(e.getMessage()).append(" ; ");
            }
        }
        throw new ResolverException("Jar introuvable pour " + coordonnée
                + " — dépôts essayés : " + erreurs);
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
        return "/" + coordonnée.group().replace('.', '/') + "/"
                + coordonnée.artifact() + "/" + coordonnée.version() + "/"
                + coordonnée.artifact() + "-" + coordonnée.version() + suffixe;
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
