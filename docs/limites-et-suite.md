# Limites et suite

Suivi des manques, hypothèses et travaux restants. Règle du projet : aucun TODO silencieux —
tout écart est consigné ici.

## État des phases

| Phase | État | Livrable |
|---|---|---|
| 0 — Exploration | **Fait** | Dépôt de référence cloné hors du dépôt de travail, SHA épinglé, licence vérifiée et consignée, hooks anti-référence en place. |
| 1 — Analyse | **Fait** | [`analyse-moteur-build.md`](analyse-moteur-build.md) (13 sections, cartographie avec REF-xxx). |
| 2 — Revue critique | **Fait** | [`revue-moteur-build.md`](revue-moteur-build.md) (4 axes, 46 constats numérotés, compatibilité Android, comparaison Gradle). |
| 3 — Conception | **Fait** (2026-11-20) | [`conception.md`](conception.md) (12 décisions D-01..D-12 justifiées, registre des améliorations priorisées, ordre d'implémentation) et [`distribution.md`](distribution.md) (Play exclu, GitHub Releases + F-Droid, clés, licences, guidance appareil). |
| 4 — Implémentation | **Fait** (étapes 1-13, 2026-11-20) | Détail ci-dessous ; moteur Java ET Android jusqu'à l'APK signé, chaîne de publication complète. |

### Phase 4 : détail par étape

| Étape | État | Contenu livré |
|---|---|---|
| 1. Squelette | **Fait** | Build Gradle multi-modules (`api`, `client`, `setup`, `engine`, `app`), wrapper 8.10.2, AGP 8.5.2, CI build+tests+APK, coquille minimale (cible API 34, `targetSdk` 28 assumé). |
| 2. Contrat + moteur factice | **Fait** | Trames stdio, négociation de version, messages et événements scellés ; moteur factice (ping, état, builds scriptés, annulation) ; client (lanceurs `app_process`/`java`, connexion, corrélation) ; test d'intégration avec VRAI processus séparé. |
| 3. Installateur | **Fait** | JSON strict maison, manifeste signé ECDSA P-256 (octets exacts), HTTPS exigé, téléchargeur reprenable (Range, rejet des 206 non sollicités), extracteur anti-zip-slip plafonné, installation atomique (staging + renommage + lecture seule), mise à jour/réparation/désinstallation/hors ligne. Tests avec serveurs HTTP locaux réels (coupure à mi-corps puis reprise vérifiée). |
| 4. Chargement réel | **Fait** | `EngineSession` (description de VM depuis l'état installé, refus clair si incomplet), `ForgeHostService` (service premier plan), interface branchée ; test de bout en bout : le VRAI moteur installé comme composant puis lancé et interrogé. |
| 5. Moteur de tâches | **Fait** | Graphe (Kahn par niveaux, cycle cité, ordre déterministe, dépendances explicites), contrat `Task` avec entrées/sorties déclarées. |
| 6. Exécuteur | **Fait** | File de prêtes, parallélisme plafonné calculé, échec bloquant transitif, branches saines menées à terme, annulation par interruption immédiate des threads, événements en flux. |
| 7. Empreintes et magasin | **Fait** | Empreintes par contenu (fichiers, répertoires en flux, propriétés triées, version de code), magasin atomique versionné à somme de contrôle, noms injectifs, corruption = périmé, sorties revérifiées ; incrémentalité prouvée par test (toucher un fichier ne relance que sa tâche). |
| 8. Pipeline Java | **Fait** | Modèle déclaratif `forge.json` strict (codec testé), ECJ en processus avec diagnostics par l'API (fichier/ligne/colonne/code), tâches `compileJava`/`jar` câblées par portées (fermeture `api`), jar déterministe (testé octet par octet), **vrai serveur de build** câblé dans `EngineMain` (défaut ; `--fake` garde le moteur scripté pour les tests de contrat), exemple `examples/multi-modules-java`, benchmark complet vs incrémental (première mesure : 6/6 → 4/6 tâches). Parseur JSON extrait dans le module `json/` partagé. |
| 9. Résolveur | **Fait** | Résolution Maven complète : POM effectifs (parents, propriétés, gestion, nomenclatures importées, relogements), exclusions, optionnelles, médiation trois politiques (échec-sur-conflit réel, chemins cités), comparateur aligné Maven, cache réplique à écritures atomiques + cache négatif TTL, intégrité sha1, HTTPS strict (boucle locale pour les tests), bornes avec diagnostic, hors ligne, convergence en deux passes ; tâche `resolveDependencies` câblée dans le pipeline (le classpath résolu est une entrée d'empreinte de la compilation) ; 16 tests sur un vrai dépôt HTTP local. **Bonus : correction d'une course réelle dans l'exécuteur** (double soumission d'une tâche par la boucle des racines, détectée par le benchmark incrémental, corrigée et re-vérifiée 5 fois). |
| 10. Pipeline Android | **Fait** | Chaîne `application` complète jusqu'à l'**APK signé v1+v2 vérifié par apksig**, testée avec de VRAIS outils (aapt2 officiel extrait du dépôt Maven, android.jar API 34, d8 et apksig en processus) : `processResources` (aapt2 borné 5 min, annulation par destruction, autotest, diagnostics localisés), `compileJava` (R générées au chemin des sources, `android.jar` au classpath), `dex` (d8 en processus, diagnostics par l'API, classes emballées en jar déterministe), `packageApk` (déterministe, arsc STORED, noms traversants refusés), `signApk` (clé de débogage générée par keytool borné, aucun secret en ligne de commande) ; `android-library` v1 = classes+jar contre android.jar (ressources/AAR non livrés, consigné) ; incrémentalité testée (source modifiée → ressources à jour ; ressource modifiée → chaîne aval relancée) ; exemple `examples/application-android`. |
| 11. Diagnostics et console | **Fait** | Rédaction de secrets au point de sortie (D-09, heuristique consignée), parseur générique de sortie d'outils (repli sans API), journal circulaire borné par build (P-7), trace JSON exportable par build sous `.forge/traces/` (A-13, atomique, testée), écran de build complet dans la coquille (chemin du projet, progression, journal + diagnostics en flux, annulation, installation de l'APK avec confirmation système). |
| 12. Packaging et publication | **Fait** | Chaîne complète : `PackagerComposants` (setup) assemble les six composants dexés (engine-core 93 Ko, java-compiler 1,2 Mo, dexeur 3,2 Mo, signeur 165 Ko, aapt2-arm64-v8a 2,2 Mo, platform-34 24 Mo) en zips déterministes + manifeste JSON via le codec de l'installateur ; signature ECDSA P-256 du manifeste (outil `OutilManifeste` : génération, signature, vérification — round-trip testé avec le vérificateur de la coquille) ; **clé de production générée et épinglée** (publique : `ClesForge` dans la coquille ; privée : jeton CI `FORGE_MANIFEST_KEY`, posé par l'API GitHub, jamais versionnée) ; tâche `:engine:composants` ; job CI `release` sur étiquette `vX.Y.Z` (assemblage, signature, vérification contre la clé épinglée, version GitHub). La chaîne a été exécutée en entier dans le bac à sable : signature vérifiée VALIDE. |
| 13. Import Gradle statique (optionnel) | **Fait** | Lecteur STATIQUE des scripts Groovy (aucun code évalué — même limite que la référence, assumée) : `include` du settings, plugins, dépôts (`google()`, `mavenCentral()`), dépendances (portées Gradle, notation `g:a:v` et `project(':x')`), bloc `android` (namespace, applicationId, min/target/compileSdk) ; blocs sur une ligne normalisés ; constructions inconnues ignorées AVEC avertissement ; le modèle produit passe le codec officiel (testé) ; application sans namespace refusée avec message actionnable. |

## Manques et limites constatés (Phases 3-4, étapes 1-13)

- **Écarts du pipeline Java avec le moteur de référence** (détail dans la section
  « Comparaison après implémentation » de [`REFERENCES.md`](REFERENCES.md)) :
  processeurs d'annotations désactivés (parité, à documenter comme décision), compilation
  incrémentale fine par fichier et cache d'ABI absents (améliorations A-8/A-10 non livrées —
  l'up-to-date par tâche et l'empreinte de classpath par contenu donnent déjà l'essentiel),
  variante « compilateur sans image » pour ART non câblée (repli `-source/-target`),
  `processResources` Java fusionné dans le jar (v1), chaîne de tests par module non livrée.
- **AAR et classifieurs non supportés à ce jour** : le résolveur refuse proprement tout
  empaquetage non-`jar` (message citant l'étape 10) ; les métadonnées Gradle
  `.module` (sélection de variante, contraintes), l'éviction par capacités, les
  alignements de famille, les SNAPSHOTS et la parallélisation par vagues ne sont pas
  livrés (écart consigné dans `REFERENCES.md`) ; les téléchargements d'artefacts sont
  en flux mais sans reprise (petits artefacts — la reprise existe pour les composants
  du moteur).
- **AAR et artefacts Android** : le résolveur refuse toujours les empaquetages
  non-`jar` (AAR à l'étape suivante si nécessaire) ; les dépendances natives
  (`.so`) et le multidex par buckets ne sont pas livrés.
- **Écarts du pipeline Android avec le moteur de référence** (détail dans la section
  « Comparaison après implémentation » de [`REFERENCES.md`](REFERENCES.md)) :
  ressources = granularité tâche (A-9 par fichier non livré), pas de ViewBinding,
  BuildConfig, AIDL, minification R8/L8, bundle, multidex, caches dex à trois
  niveaux, fusion de manifestes multi-modules (manifeste du module application
  seul), R générées en SOURCE et non en bytecode (REF-065), signature de production
  (débogage seul), installation sur appareil (étape 11, côté coquille).
- **Résolution des outils Android (aapt2, android.jar)** : propriétés de test ou SDK
  de la machine ; le câblage au moteur INSTALLÉ (composants par ABI et par API,
  `AndroidToolsLocator` prêt) attend l'étape 12 (packaging des composants).
- **Écran de build et installation** : l'installation par PackageInstaller et le
  lancement `app_process` du moteur ne sont PAS vérifiés sur appareil réel ; la
  rédaction de secrets est heuristique (étiquettes usuelles FR/EN, porteurs) — un
  secret sans étiquette passe (limite consignée) ; l'assistant d'installation
  complet (listes, tailles, avertissement mobile — §2.2) reste à câbler sur `setup`
  (la logique testable existe depuis l'étape 3).

- **Rien n'a été vérifié sur appareil réel (ART)**. Tout est testé sur JVM (dont des tests
  d'intégration avec de vrais processus séparés `java`). Les points spécifiquement à
  valider sur ART : lancement `app_process` avec `CLASSPATH` (mécanisme éprouvé ailleurs,
  D-01), comportement du chargeur sur jars dexés en lecture seule, restrictions
  d'exécution du binaire `aapt2` téléchargé (D-04, `targetSdk` 28), tueur de processus
  fantômes Android 12+ (D-02). Le repli `DexClassLoader` est conçu mais non câblé.
- **Watchdog des sous-processus (D-09, constat S-8)** : pas encore implémenté. Précision
  après l'étape 8 : ECJ compile **en processus** (aucun sous-processus à surveiller) ; le
  watchdog et le registre des enfants viennent donc avec l'étape 10 (aapt2 natif). L'annulation
  des threads est, elle, en place et testée.
- **Écarts assumés avec le moteur de référence** (voir la section « Comparaison après
  implémentation » de [`REFERENCES.md`](REFERENCES.md)) : pas de `mustRunAfter`/
  `mustRunBefore`, pas de statut `NO-SOURCE`, pas d'arêtes inférées producteur→consommateur
  (refus délibéré, constat C-5) — les pipelines livrés n'en ont pas eu besoin.
- **Le serveur de build réel est câblé depuis l'étape 8** : `EngineMain` lance par défaut le
  `BuildEngineServer` (modèle `forge.json` → pipeline → graphe → exécuteur → trames) ; le moteur
  factice reste accessible par l'option `--fake` (tests de contrat du canal).
- **Éditeur/gestion de projets dans la coquille** : minimal (état du moteur, bouton de
  test) ; l'assistant d'installation complet (listes, tailles, avertissement mobile,
  progression, annulation — exigences §2.2) sera câblé sur `setup` à l'étape 11 ; la
  logique testable existe déjà.
- **Licence de Forge choisie : Apache-2.0** (décision propriétaire du 2026-11-20, appliquée
  au dépôt entier — fichier `LICENSE` ; voir `distribution.md` §7 pour les notices des
  dépendances embarquées, dont la génération reste à produire à la publication).
- **Clé de signature de manifeste** : **générée et épinglée** (étape 12) — clé
  publique dans `ClesForge` (coquille, test de format), clé privée en jeton CI
  `FORGE_MANIFEST_KEY` (posé le 2026-11-20 par l'API GitHub, jamais versionnée) ;
  la rotation reste à documenter opérationnellement (`distribution.md` §4).
- **Redistribution de `android.jar` et du binaire `aapt2`** : le composant
  `platform-34` emballe le `android.jar` du SDK et `aapt2-arm64-v8a` le binaire des
  build-tools ; les conditions de redistribution (`distribution.md` §7) restent à
  valider juridiquement avant publication publique — et le binaire emballe
  aujourd'hui est le prébuilt LINUX (développement et CI) : le prébuilt ANDROID
  (arm64) exige une construction AOSP dédiée (écart consigné).

## Hypothèses retenues (Phases 0-3)

1. **`targetSdk 28`** pour la coquille (D-04) : préserve l'exécution des binaires
   téléchargés depuis le stockage privé ; cohérent avec une distribution hors magasin.
2. **Transport stdio + VM enfant** (D-01) : remplacé par `DexClassLoader` + tubes si un
   appareil bloque `app_process` — seul le lanceur change.
3. **ECDSA P-256** pour la signature du manifeste : Ed25519 n'est disponible qu'à partir
   de l'API 33, hors de notre `minSdk` 28.
4. **Le moteur est pur Java** : aucune API Android dans `engine/`, `client/`, `setup/`,
   `api/` — paramètres d'environnement injectés par la coquille.
5. **Répertoire de travail du moteur de référence** : cloné hors du dépôt, SHA épinglé
   `1b1a3052168a52f38a91ea03c87831a0d94c1fff`, jamais copié ni référencé hors de
   `docs/REFERENCES.md`.

## Prochaines étapes (après la Phase 4)

1. **Vérification sur appareil réel (ART)** — la grande inconnue restante (liste au
   début de la section « Manques ») : `app_process` + `CLASSPATH`, jars dexés en
   lecture seule, exécution du binaire aapt2 téléchargé (W^X, `targetSdk` 28),
   tueur de processus Android 12+, installation par PackageInstaller.
2. **Assistant d'installation câblé sur `setup`** dans la coquille (la logique
   testable existe ; il manque les écrans — listes, tailles, avertissement données
   mobiles, progression, reprise).
3. **Câbler `AndroidToolsLocator` au moteur installé** (composants par ABI/API) pour
   que le build sur appareil trouve aapt2 et android.jar du moteur — le localisateur
   est prêt, l'installateur pose les composants, la jonction est à faire.
4. Améliorations priorisées restantes du registre de la conception : A-8 (cache de
   sorties par contenu), A-9 (ressources incrémentales par fichier), A-10 (empreintes
   d'ABI), signature de production, AAR, multidex.
5. Premier benchmark sur projet Android complet + publication d'une étiquette `v0.2.0`
   de test (le job `release` signera le manifeste avec le jeton CI).
