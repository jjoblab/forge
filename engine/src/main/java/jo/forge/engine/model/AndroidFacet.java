package jo.forge.engine.model;

import java.util.List;
import java.util.Map;

/**
 * Facette Android d'un module ({@link ModuleType#APPLICATION} ou
 * {@link ModuleType#ANDROID_LIBRARY}) : chemins Android et paramètres de
 * plate-forme.
 *
 * <p>Les chemins sont relatifs à la racine du module (répertoire portant son
 * nom sous la racine du projet). La signature « debug » est générée par le
 * moteur à la première utilisation (clé dédiée sous {@code .forge/debug-key},
 * jamais un mot de passe en clair — la signature de production reste une
 * évolution documentée dans {@code docs/limites-et-suite.md}).</p>
 *
 * <p><b>Génération de code</b> (v0.2.18) : {@link BuildFeatures#buildConfig()}
 * déclenche la tâche {@code generateBuildConfig} (constantes
 * {@code DEBUG}/{@code APPLICATION_ID}/{@code VERSION_CODE}… + les
 * {@link #buildConfigFields()} du projet — sémantique AGP), et
 * {@link BuildFeatures#viewBinding()} la tâche {@code generateViewBinding}
 * (une classe {@code <Layout>Binding} par layout). Les deux sont DÉSACTIVÉS
 * par défaut — la position d'AGP 8 : un projet qui n'a pas demandé la
 * génération n'obtient pas de classes surprises, et l'import Gradle porte
 * l'opt-in depuis le bloc {@code buildFeatures} du script.</p>
 *
 * <p><b>Marqueurs de manifeste</b> (v0.2.18) : {@link #manifestPlaceholders()}
 * porte les substitutions {@code ${clef}} opérées sur le manifeste fusionné —
 * {@code applicationId} est toujours fourni (valant
 * {@link #applicationId()} ou, à défaut, {@link #namespace()}) : les
 * bibliothèques AndroidX en dépendent (autorisés du provider d'
 * {@code androidx.startup}, permission
 * {@code ${applicationId}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION} de core).</p>
 *
 * @param manifest       chemin du manifeste Android du module.
 * @param resourceDirs   répertoires de ressources (fusionnés dans l'ordre).
 * @param assetDirs      répertoires d'assets (fusionnés dans l'ordre).
 * @param minSdk         niveau d'API minimal du module.
 * @param targetSdk      niveau d'API cible du module.
 * @param applicationId  identifiant d'application (suffixe de package), si
 *                       différent du namespace.
 * @param namespace      espace de noms Java du module (package des classes R
 *                       et BuildConfig).
 * @param versionCode    code de version de l'application (défaut 1).
 * @param versionName    nom de version de l'application (défaut « 1.0 »).
 * @param buildFeatures  génération de code demandée (BuildConfig,
 *                       ViewBinding) — désactivée par défaut.
 * @param buildConfigFields champs personnalisés de la classe BuildConfig
 *                       (expression Java verbatim, sémantique AGP).
 * @param manifestPlaceholders substitutions ${clef} du manifeste fusionné.
 */
public record AndroidFacet(String manifest, List<String> resourceDirs,
                           List<String> assetDirs, int minSdk, int targetSdk,
                           String applicationId, String namespace,
                           int versionCode, String versionName,
                           BuildFeatures buildFeatures,
                           List<BuildConfigField> buildConfigFields,
                           Map<String, String> manifestPlaceholders) {

    /**
     * Génération de code AGP demandée pour ce module (les deux opt-in, désactivés
     * par défaut — position d'AGP 8).
     *
     * @param buildConfig  générer {@code <namespace>.BuildConfig}.
     * @param viewBinding  générer les classes {@code <Layout>Binding} des layouts.
     */
    public record BuildFeatures(boolean buildConfig, boolean viewBinding) {

        /** L'instance « rien de demandé ». */
        public static final BuildFeatures AUCUNE = new BuildFeatures(false, false);
    }

    /**
     * Un champ personnalisé de BuildConfig : {@code buildConfigField 'Type',
     * 'NOM', 'valeur'} du DSL AGP — la valeur est l'expression Java écrite
     * telle quelle dans la classe générée (une chaîne reste quotée).
     *
     * @param type  type Java du champ (String, int, boolean…).
     * @param name  nom de la constante.
     * @param value expression Java de la valeur initiale.
     */
    public record BuildConfigField(String type, String name, String value) {

        /**
         * Valide et fige le champ.
         */
        public BuildConfigField {
            if (type == null || type.isBlank()) {
                throw new ModelException("Champ BuildConfig sans type");
            }
            if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new ModelException("Nom de champ BuildConfig invalide : \""
                        + name + "\" (identifiant Java attendu)");
            }
            if (value == null || value.isBlank()) {
                throw new ModelException("Champ BuildConfig " + name
                        + " sans valeur");
            }
        }
    }

    /** Code de version par défaut. */
    public static final int VERSION_CODE_DÉFAUT = 1;

    /** Nom de version par défaut. */
    public static final String VERSION_NOM_DÉFAUT = "1.0";

    /**
     * Constructeur historique à sept champs (v0.2.15) : ni génération de code,
     * ni versions explicites, ni marqueurs — les modèles existants et les
     * tests restent valides.
     *
     * @param manifest      chemin du manifeste Android du module.
     * @param resourceDirs  répertoires de ressources.
     * @param assetDirs     répertoires d'assets.
     * @param minSdk        niveau d'API minimal.
     * @param targetSdk     niveau d'API cible.
     * @param applicationId identifiant d'application (ou null).
     * @param namespace     espace de noms Java du module.
     */
    public AndroidFacet(String manifest, List<String> resourceDirs,
                        List<String> assetDirs, int minSdk, int targetSdk,
                        String applicationId, String namespace) {
        this(manifest, resourceDirs, assetDirs, minSdk, targetSdk,
                applicationId, namespace, VERSION_CODE_DÉFAUT,
                VERSION_NOM_DÉFAUT, BuildFeatures.AUCUNE, List.of(), Map.of());
    }

    /**
     * Valide et fige la facette.
     */
    public AndroidFacet {
        resourceDirs = resourceDirs == null ? List.of() : List.copyOf(resourceDirs);
        assetDirs = assetDirs == null ? List.of() : List.copyOf(assetDirs);
        if (manifest == null || manifest.isBlank()) {
            throw new ModelException("Facette Android sans manifeste");
        }
        if (namespace == null || namespace.isBlank()) {
            throw new ModelException("Facette Android sans espace de noms");
        }
        if (minSdk <= 0) {
            throw new ModelException("minSdk invalide : " + minSdk);
        }
        if (versionCode <= 0) {
            versionCode = VERSION_CODE_DÉFAUT;
        }
        if (versionName == null || versionName.isBlank()) {
            versionName = VERSION_NOM_DÉFAUT;
        }
        buildFeatures = buildFeatures == null ? BuildFeatures.AUCUNE : buildFeatures;
        buildConfigFields = buildConfigFields == null
                ? List.of() : List.copyOf(buildConfigFields);
        manifestPlaceholders = manifestPlaceholders == null
                ? Map.of() : Map.copyOf(manifestPlaceholders);
    }

    /**
     * L'identifiant d'application EFFECTIF : {@link #applicationId()} s'il est
     * déclaré, sinon le namespace (sémantique AGP — c'est la valeur substituée
     * au marqueur {@code ${applicationId}} du manifeste et celle qui identifie
     * l'APK installé).
     *
     * @return l'identifiant d'application.
     */
    public String applicationIdEffectif() {
        return applicationId != null && !applicationId.isBlank()
                ? applicationId : namespace;
    }

    /**
     * Les marqueurs de manifeste EFFECTIFS : ceux du projet plus
     * {@code applicationId} (jamais remplaçable — une bibliothèque peut en
     * dépendre, la valeur est l'identifiant effectif).
     *
     * @return la table de substitution des {@code ${clef}}.
     */
    public Map<String, String> marqueursDeManifeste() {
        if (manifestPlaceholders.isEmpty()) {
            return Map.of("applicationId", applicationIdEffectif());
        }
        Map<String, String> marqueurs = new java.util.LinkedHashMap<>();
        marqueurs.putAll(manifestPlaceholders);
        marqueurs.put("applicationId", applicationIdEffectif());
        return marqueurs;
    }
}
