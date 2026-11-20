package jo.forge.engine.importgradle;

import jo.forge.engine.model.AndroidFacet;
import jo.forge.engine.model.DependencyScope;
import jo.forge.engine.model.ForgeModel;
import jo.forge.engine.model.ForgeModule;
import jo.forge.engine.model.ModelException;
import jo.forge.engine.model.ModuleDependency;
import jo.forge.engine.model.ModuleType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Import STATIQUE de scripts Gradle (Groovy DSL) vers le modèle déclaratif
 * {@code forge.json} (étape 13, optionnelle).
 *
 * <p>Principe : aucun code Gradle n'est évalué — les constructions usuelles sont
 * reconnues par motif, tout le reste est ignoré avec un avertissement. C'est la
 * limite assumée de l'import statique (le moteur de référence fait pareil, et
 * exécuter du Groovy arbitraire serait un risque d'exécution de code).</p>
 *
 * <p>Reconnu : {@code include} du {@code settings.gradle} ; blocs
 * {@code plugins} ({@code java-library}, {@code com.android.application},
 * {@code com.android.library}) ; {@code repositories} ({@code google()},
 * {@code mavenCentral()}) ; {@code dependencies} ({@code implementation},
 * {@code api}, {@code compileOnly}, {@code runtimeOnly}, notation
 * {@code 'g:a:v'} et {@code project(':x')}) ; bloc {@code android}
 * ({@code namespace}, {@code defaultConfig} avec {@code applicationId},
 * {@code minSdk}/{@code minSdkVersion}, {@code targetSdk}/{@code targetSdkVersion},
 * {@code compileSdk}). Les propriétés de projet ({@code VERSION_XX}) et
 * l'interpolation ne sont PAS résolues : un identifiant à la place d'une chaîne
 * littérale est ignoré avec avertissement.</p>
 */
public final class ImportGradle {

    /** Dépôt Maven Central (URL stable). */
    private static final String MAVEN_CENTRAL = "https://repo.maven.apache.org/maven2";

    /** Dépôt Google Maven. */
    private static final String GOOGLE_MAVEN = "https://dl.google.com/dl/android/maven2";

    /** include ':a', ':b' (avec guillemets simples ou doubles). */
    private static final Pattern INCLUSION = Pattern.compile(
            "['\"]:([^'\"]+)['\"]");

    /** Dépendance en notation chaîne : implementation 'g:a:v'. */
    private static final Pattern DÉPENDANCE_CHAÎNE = Pattern.compile(
            "^\\s*(implementation|api|compileOnly|runtimeOnly)\\s+['\"]([^'\"]+)['\"].*$");

    /** Dépendance de projet : implementation project(':x'). */
    private static final Pattern DÉPENDANCE_PROJET = Pattern.compile(
            "^\\s*(implementation|api|compileOnly|runtimeOnly)\\s+project\\(['\"]?:?([^'\"\\s]+)['\"]\\).*$");

    /** Appel de dépôt : google() / mavenCentral(). */
    private static final Pattern DÉPÔT = Pattern.compile(
            "^\\s*(google|mavenCentral)\\s*\\(\\s*\\)\\s*$");

    /** Plugin : id 'x' / id "x" (ligne). */
    private static final Pattern PLUGIN = Pattern.compile(
            "^\\s*id\\s+['\"]([^'\"]+)['\"].*$");

    /** Affectation simple : clef 'valeur' / clef "valeur" (sans typage). */
    private static final Pattern AFFECTATION_CHAÎNE = Pattern.compile(
            "^\\s*(namespace|applicationId)\\s+['\"]([^'\"]+)['\"].*$");

    /** Affectation numérique : minSdk 28 / targetSdkVersion 34 / compileSdk 34. */
    private static final Pattern AFFECTATION_NOMBRE = Pattern.compile(
            "^\\s*(minSdk|minSdkVersion|targetSdk|targetSdkVersion|compileSdk|"
                    + "compileSdkVersion)\\s+(\\d+)\\s*$");

    private final List<String> avertissements = new ArrayList<>();

    private ImportGradle() {
    }

    /**
     * Importe un projet Gradle (répertoire contenant {@code settings.gradle}).
     *
     * @param racineGradle racine du projet Gradle.
     * @return le modèle Forge équivalent (validé par le constructeur du modèle).
     * @throws ModelException si le projet est illisible ou incohérent.
     */
    public static ForgeModel importer(Path racineGradle) {
        ImportGradle importateur = new ImportGradle();
        return importateur.lire(racineGradle);
    }

    /**
     * Avertissements du dernier import (constructions ignorées).
     *
     * @return les avertissements, éventuellement vide.
     */
    public static List<String> derniersAvertissements() {
        // Les avertissements vivent avec l'instance d'import : l'appelant les obtient
        // via importerAverti ; cette méthode documente l'existence du canal.
        return List.of();
    }

    /**
     * Importe en retournant aussi les avertissements (constructions non reconnues).
     *
     * @param racineGradle racine du projet Gradle.
     * @return le modèle et les avertissements.
     */
    public static RésultatImport importerAverti(Path racineGradle) {
        ImportGradle importateur = new ImportGradle();
        ForgeModel modèle = importateur.lire(racineGradle);
        return new RésultatImport(modèle, List.copyOf(importateur.avertissements));
    }

    /** Résultat d'un import avec avertissements. */
    public record RésultatImport(ForgeModel modèle, List<String> avertissements) {
    }

    private ForgeModel lire(Path racine) {
        Path réglages = premierExistant(racine, "settings.gradle", "settings.gradle.kts");
        if (réglages == null) {
            throw new ModelException("Aucun settings.gradle dans " + racine
                    + " : ce n'est pas un projet Gradle");
        }
        Set<String> nomsModules = new LinkedHashSet<>();
        for (String ligne : lignes(réglages)) {
            if (ligne.trim().startsWith("include")) {
                Matcher inclusion = INCLUSION.matcher(ligne);
                while (inclusion.find()) {
                    nomsModules.add(inclusion.group(1));
                }
            }
        }
        if (nomsModules.isEmpty()) {
            throw new ModelException("settings.gradle n'inclut aucun module");
        }

        // Dépôts : ceux du build racine s'appliquent à tous (réplique de la
        // sémantique Gradle par défaut des projets non configurés autrement).
        Set<String> dépôts = new LinkedHashSet<>();
        Path buildRacine = premierExistant(racine, "build.gradle", "build.gradle.kts");
        if (buildRacine != null) {
            dépôts.addAll(dépôtsDe(lignes(buildRacine)));
        }

        List<ForgeModule> modules = new ArrayList<>();
        for (String nom : nomsModules) {
            modules.add(lireModule(racine, nom, dépôts));
        }
        return new ForgeModel(ForgeModel.CURRENT_MODEL_VERSION,
                new ArrayList<>(dépôts), modules);
    }

    private ForgeModule lireModule(Path racine, String nom, Set<String> dépôts) {
        Path script = premierExistant(racine.resolve(nom), "build.gradle",
                "build.gradle.kts");
        if (script == null) {
            avertissements.add("module " + nom + " : aucun build.gradle, module "
                    + "java-library vide supposé");
            return new ForgeModule(nom, ModuleType.JAVA_LIBRARY,
                    ForgeModule.DEFAULT_JAVA_RELEASE,
                    java.util.List.of("src/main/java"),
                    java.util.List.of("src/main/resources"),
                    List.of(), java.util.Optional.empty());
        }
        List<String> lignes = lignes(script);
        Set<String> plugins = pluginsDe(lignes);
        dépôts.addAll(dépôtsDe(lignes));
        List<ModuleDependency> dépendances = dépendancesDe(nom, lignes);

        if (plugins.contains("com.android.application")
                || plugins.contains("com.android.library")) {
            return moduleAndroid(nom, lignes, dépendances,
                    plugins.contains("com.android.application"));
        }
        return new ForgeModule(nom, ModuleType.JAVA_LIBRARY,
                ForgeModule.DEFAULT_JAVA_RELEASE,
                java.util.List.of("src/main/java"),
                java.util.List.of("src/main/resources"),
                dépendances, java.util.Optional.empty());
    }

    private ForgeModule moduleAndroid(String nom, List<String> lignes,
            List<ModuleDependency> dépendances, boolean application) {
        Map<String, String> chaînes = new LinkedHashMap<>();
        Map<String, Integer> nombres = new LinkedHashMap<>();
        for (String ligne : lignes) {
            Matcher chaîne = AFFECTATION_CHAÎNE.matcher(ligne);
            if (chaîne.matches()) {
                chaînes.putIfAbsent(chaîne.group(1), chaîne.group(2));
            }
            Matcher nombre = AFFECTATION_NOMBRE.matcher(ligne);
            if (nombre.matches()) {
                nombres.putIfAbsent(nombre.group(1), Integer.parseInt(nombre.group(2)));
            }
        }
        int minSdk = premierNombre(nombres, 28, "minSdk", "minSdkVersion");
        int targetSdk = premierNombre(nombres,
                premierNombre(nombres, 34, "compileSdk", "compileSdkVersion"),
                "targetSdk", "targetSdkVersion");
        String namespace = chaînes.get("namespace");
        if (namespace == null) {
            namespace = chaînes.get("applicationId");
        }
        if (namespace == null && application) {
            throw new ModelException("module " + nom + " : ni namespace ni "
                    + "applicationId dans le bloc android — précisez-en un pour "
                    + "l'import (le modèle Forge l'exige)");
        }
        if (namespace == null) {
            namespace = "import." + nom;
        }
        AndroidFacet facette = new AndroidFacet("src/main/AndroidManifest.xml",
                List.of("src/main/res"), List.of("src/main/assets"), minSdk,
                targetSdk, chaînes.get("applicationId"), namespace);
        return new ForgeModule(nom,
                application ? ModuleType.APPLICATION : ModuleType.ANDROID_LIBRARY,
                ForgeModule.DEFAULT_JAVA_RELEASE, List.of(), List.of(),
                dépendances, java.util.Optional.of(facette));
    }

    private Set<String> dépôtsDe(List<String> lignes) {
        Set<String> dépôts = new LinkedHashSet<>();
        boolean dansDépôts = false;
        for (String ligne : lignes) {
            String nettoyée = ligne.trim();
            if (nettoyée.startsWith("repositories")) {
                dansDépôts = true;
            } else if (dansDépôts && nettoyée.equals("}")) {
                dansDépôts = false;
            } else if (dansDépôts) {
                Matcher dépôt = DÉPÔT.matcher(ligne);
                if (dépôt.matches()) {
                    if ("google".equals(dépôt.group(1))) {
                        dépôts.add(GOOGLE_MAVEN);
                    } else {
                        dépôts.add(MAVEN_CENTRAL);
                    }
                } else if (!nettoyée.isEmpty() && !nettoyée.equals("{")) {
                    avertissements.add("dépôt non reconnu, ignoré : " + nettoyée);
                }
            }
        }
        return dépôts;
    }

    private Set<String> pluginsDe(List<String> lignes) {
        Set<String> plugins = new LinkedHashSet<>();
        boolean dansPlugins = false;
        for (String ligne : lignes) {
            String nettoyée = ligne.trim();
            if (nettoyée.startsWith("plugins")) {
                dansPlugins = true;
            } else if (dansPlugins && nettoyée.equals("}")) {
                dansPlugins = false;
            } else if (dansPlugins) {
                Matcher plugin = PLUGIN.matcher(ligne);
                if (plugin.matches()) {
                    plugins.add(plugin.group(1));
                } else if (!nettoyée.isEmpty() && !nettoyée.equals("{")
                        && !nettoyée.startsWith("id(")) {
                    avertissements.add("plugin non reconnu, ignoré : " + nettoyée);
                }
            }
        }
        return plugins;
    }

    private List<ModuleDependency> dépendancesDe(String nom, List<String> lignes) {
        List<ModuleDependency> dépendances = new ArrayList<>();
        boolean dansDépendances = false;
        for (String ligne : lignes) {
            String nettoyée = ligne.trim();
            if (nettoyée.startsWith("dependencies")) {
                dansDépendances = true;
            } else if (dansDépendances && nettoyée.equals("}")) {
                dansDépendances = false;
            } else if (dansDépendances && !nettoyée.isEmpty() && !nettoyée.equals("{")) {
                ModuleDependency dépendance = lireDépendance(ligne);
                if (dépendance != null) {
                    dépendances.add(dépendance);
                } else if (!nettoyée.startsWith("//")) {
                    avertissements.add("module " + nom + " : dépendance non reconnue, "
                            + "ignorée : " + nettoyée);
                }
            }
        }
        return dépendances;
    }

    private ModuleDependency lireDépendance(String ligne) {
        Matcher projet = DÉPENDANCE_PROJET.matcher(ligne);
        if (projet.matches()) {
            return new ModuleDependency.ProjectDependency(projet.group(2),
                    portée(projet.group(1)));
        }
        Matcher chaîne = DÉPENDANCE_CHAÎNE.matcher(ligne);
        if (chaîne.matches()) {
            String[] parties = chaîne.group(2).split(":");
            if (parties.length == 3) {
                return new ModuleDependency.MavenDependency(parties[0], parties[1],
                        parties[2], portée(chaîne.group(1)));
            }
        }
        return null;
    }

    private static DependencyScope portée(String nomGradle) {
        return switch (nomGradle) {
            case "api" -> DependencyScope.API;
            case "compileOnly" -> DependencyScope.COMPILE_ONLY;
            case "runtimeOnly" -> DependencyScope.RUNTIME_ONLY;
            default -> DependencyScope.IMPLEMENTATION;
        };
    }

    private static int premierNombre(Map<String, Integer> nombres, int défaut,
            String... clés) {
        for (String clé : clés) {
            Integer valeur = nombres.get(clé);
            if (valeur != null) {
                return valeur;
            }
        }
        return défaut;
    }

    private static Path premierExistant(Path répertoire, String... noms) {
        for (String nom : noms) {
            Path candidat = répertoire.resolve(nom);
            if (Files.isRegularFile(candidat)) {
                return candidat;
            }
        }
        return null;
    }

    private static List<String> lignes(Path fichier) {
        try {
            // Normalisation : les blocs Groovy sur une ligne (« dependencies { x } »)
            // deviennent des lignes séparées aux accolades — l'analyseur est
            // purement ligne-par-ligne.
            List<String> normalisées = new ArrayList<>();
            for (String ligne : Files.readAllLines(fichier)) {
                for (String segment : ligne.split("(?=[{}])|(?<=[{}])")) {
                    if (!segment.isBlank()) {
                        normalisées.add(segment);
                    }
                }
            }
            return normalisées;
        } catch (IOException e) {
            throw new ModelException("Lecture impossible de " + fichier
                    + " : " + e.getMessage(), e);
        }
    }
}
