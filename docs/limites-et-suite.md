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
| Post-4 — Assistant, jonction, exemples | **Fait** (2026-11-20) | Étape 14 du tableau ci-dessous : assistant d'installation câblé, jonction des outils, exemples embarqués, publications `v0.2.0` puis `v0.2.1` (clé d'essai stable). |
| Post-4 — Diagnostic sur appareil | **Fait** (2026-11-20) | Étape 15 du tableau ci-dessous : stderr du moteur capturé, journaux copiables, moteurs successifs arrêtés, publication `v0.2.2`. |
| Post-4 — Correctif d'ouverture | **Fait** (2026-11-21) | La `v0.2.2` plantait à l'ouverture (boutons de journaux non branchés à leurs vues) ; correctif + journal d'écroulements de la coquille dans le rapport + garde de câblage des vues dans les tests + appels limités à l'API 28 (`List.of`/`String.lines`/`Files.readString` écartés), publication `v0.2.3`. |
| Post-4 — Correctif ECJ sur ART | **Fait** (2026-11-21) | Constat `v0.2.3` (rapport de diagnostic copié depuis l'appareil) : `core:compileJava` échoue par `Can't find bundle for base name …batch.messages, locale fr_HT` — d8 ne convertit que les classes, les ressources d'ECJ (bundles de messages, tables `.rsc` du parseur et du scanner, ~1,04 Mo) étaient perdues au dexage, et sur JVM les tests ne le voyaient pas (jar complet au classpath). Correctif : les jars dexés conservent toute entrée non-classe des jars sources (manifeste et signatures du jar d'origine écartés — ils ne signent plus rien une fois fusionnés ; `about.html` d'ECJ conservé, notice EPL-2.0 exigée à la redistribution §7), car le classloader d'ART sert les entrées zip d'un jar du classpath (`DexPathList`/`ClassPathURLStreamHandler`, API 28+). Doublons identiques dédupliqués, conflits de contenu refusés, ordre et horodatages fixes (déterminisme des composants). Publication `v0.2.4`. |
| Post-4 — Code de sortie honnête | **Fait** (2026-11-21) | Constat `v0.2.3` : le rapport disait « code de sortie : inconnu (processus encore vivant ou code non encore publié) » d'un moteur pourtant à l'arrêt. Deux causes : (1) `codeSortie()` ne publiait que ce que la sentinelle rapportait — une sentinelle muette (interrompue) laissait le code à `null` pour toujours ; il se guérit désormais seul (processus fini sans publication = `waitFor` immédiat, testé avec connexion sans aucun fil) ; (2) le sondage de mort (2,5 s) tournait sur la file mono-fil de la coquille et retardait l'arrêt du moteur mis en file derrière lui — le moteur restait vivant pendant qu'on l'attendait. La mort est publiée sur son propre fil, et un canal fermé sans code s'affiche désormais comme tel (« canal du moteur fermé, processus encore vivant ») au lieu de prétendre à un arrêt. Publication `v0.2.5`. |

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
| 14. Assistant, jonction, exemples (post-4) | **Fait** (2026-11-20) | Canal signé (`CanalMoteur` : manifeste + signature vérifiés contre la clé épinglée, HTTPS strict, plafonds) ; assistant d'installation dans la coquille (composants par ABI avec tailles, avertissement données mobiles, progression globale, annulation avec reprise, réparation, désinstallation) ; `FORGE_ENGINE_DIR` transmis au moteur (outils Android localisés dans le moteur installé) ; layout des composants publiés conforme à la convention d'installation (engine.jar, lib/, components/ — l'ancien layout à plat aurait écrasé les classes.dex à l'extraction) ; exemples embarqués en assets et importables sans permission ; publications `v0.2.0` puis `v0.2.1` SANS composants redistribuables du SDK (garde juridique §7), APK de test signé de la clé d'essai STABLE joint (jetons CI `FORGE_APK_KEYSTORE_B64`/`FORGE_APK_KEYSTORE_MDP` — sans elle, chaque exécutant éphémère signerait différemment et aucune version ne s'installerait sur la précédente) ; déclencheur CI des étiquettes corrigé (le job release ne se serait jamais exécuté). |
| 15. Diagnostic sur appareil (post-4) | **Fait** (2026-11-20) | Constat de départ : premier test sur appareil (`v0.2.1`) — le moteur meurt au milieu de `compileJava` sans laisser de trace, et aucun journal ne sort de l'appareil. Livré : **capture continue du stderr du moteur** par le client (`JournalMoteur` borné 500 lignes, consommateur en flux, sentinelle du **code de sortie** — la trace qui survit à une mort brutale du processus, et le drainage évite qu'un tube non lu bloque un moteur bavard) ; **rapport copiable/partageable** dans la coquille (contexte versions/appareil, journal de build, journal du moteur, dernière trace JSON, description du code de sortie : propre / signal du tueur de mémoire / explicite) ; à la mort inattendue du moteur, code de sortie et dernières lignes de stderr sont affichés dans le journal (la génération du moteur distingue mort attendue et mort à signaler) ; **moteurs successifs arrêtés** (avant chaque build, à la fin du build, et dans le service hôte — des VM oisives s'accumulaient : un `app_process` de 512 Mo par build/test, mémoire saturée, tueur de processus déclenché au pire moment) ; **filet de sécurité du serveur** contre les `Error` avant les tâches (échec propre au lieu d'un fil mort en silence). Tests : capture de stderr de bout en bout dans un vrai processus (code 7), bornage du journal, moteur factice (code 0), rapport complet/vides/troncature/codes (JVM). |

## Manques et limites constatés (Phases 3-4, étapes 1-14)

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
- **Résolution des outils Android (aapt2, android.jar)** : la jonction est FAITE
  (post-4, étape 14) — le moteur installé localise ses outils via `FORGE_ENGINE_DIR`
  (composants par ABI et par API, localisateur testé sur JVM). En revanche, les
  composants `aapt2`/`platform` ne sont PAS publiés dans les versions de test
  `v0.2.x` (garde juridique §7) : sur appareil, un module Android échoue proprement
  avec le message « installez le composant… » tant que le prébuilt aapt2 ANDROID
  (arm64, construction AOSP dédiée) n'est pas produit et les conditions de
  redistribution validées.
- **Écran de build et installation** : l'assistant d'installation est CÂBLÉ depuis
  l'étape 14 (canal, composants, tailles, avertissement données mobiles, progression,
  reprise, réparation, désinstallation). Restent non vérifiés sur appareil réel :
  l'installation par PackageInstaller de l'APK construit et le lancement
  `app_process` du moteur. La rédaction de secrets reste heuristique (étiquettes
  usuelles FR/EN, porteurs) — un secret sans étiquette passe (limite consignée).

- **Rien n'a encore été vérifié sur appareil réel (ART)** — la procédure de test
  ci-dessous est en place depuis la `v0.2.0`, elle attend son premier passage. Tout est
  testé sur JVM (dont des tests d'intégration avec de vrais processus séparés `java`). Les points spécifiquement à
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
- **Éditeur/gestion de projets dans la coquille** : minimal — état du moteur,
  assistant d'installation, import des exemples embarqués, écran de build. Un
  véritable éditeur (création de projets, import depuis le stockage partagé,
  arborescence) reste à construire ; les exemples embarqués rendent le premier build
  immédiat sans aucune permission.
- **Licence de Forge choisie : Apache-2.0** (décision propriétaire du 2026-11-20, appliquée
  au dépôt entier — fichier `LICENSE` ; voir `distribution.md` §7 pour les notices des
  dépendances embarquées, dont la génération reste à produire à la publication).
- **Clé de signature de manifeste** : **générée et épinglée** (étape 12) — clé
  publique dans `ClesForge` (coquille, test de format), clé privée en jeton CI
  `FORGE_MANIFEST_KEY` (posé le 2026-11-20 par l'API GitHub, jamais versionnée) ;
  la rotation reste à documenter opérationnellement (`distribution.md` §4).
- **Redistribution de `android.jar` et du binaire `aapt2`** : les composants
  `platform-34` (android.jar du SDK) et `aapt2-arm64-v8a` (binaire des build-tools)
  NE SONT PAS publiés dans les versions de test `v0.2.x` — les conditions de redistribution
  (`distribution.md` §7) restent à valider juridiquement, et le binaire aujourd'hui
  constructible est le prébuilt LINUX (utile au développement et à la CI seulement) :
  le prébuilt ANDROID (arm64) exige une construction AOSP dédiée. La publication les
  omet explicitement (`FORGE_SANS_OUTILS_ANDROID`) ; `android.jar` reste utilisé au
  DEXAGE côté publication, jamais redistribué.
- **Stabilité CI — flocons résolus le 2026-11-20** (échecs intermittents des étapes
  8-13 sur GitHub Actions, reproduits localement en boucle ~1/25) : deux causes
  racines, toutes deux corrigées. (1) Le serveur de build écrivait ses trames depuis
  plusieurs threads dont les files de tâches, éphémères : `PipedInputStream` lève
  « Write end dead » dès que le dernier écrivain du tube meurt — tout le canal passe
  désormais par un fil rédacteur unique et durable (file FIFO, ordre d'émission
  préservé, vidange à l'arrêt). (2) L'exécuteur laissait mourir silencieusement un
  runnable écroulé hors de ses zones protégées (le `Future` avalait l'exception :
  comptage rompu, sous-graphe jamais soumis, build fini sans tâche fautive) — un
  filet de sécurité convertit désormais tout throwable résiduel en échec propre
  (état FAILED, tâche fautive, dépendants bloqués) et `terminer` est idempotent ;
  les enregistreurs des tests, appelés depuis les threads du pool, utilisent des
  collections concurrentes. Validation : 85 exécutions de la suite complète sans
  échec (contre ~1 échec sur 20 avant correctifs).

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

## Procédure de test sur appareil (v0.2.5)

La chaîne est testable de bout en bout depuis la publication `v0.2.5` (la `v0.2.3`
compilait encore son premier projet Java en échec : ressources d'ECJ perdues au
dexage, `Can't find bundle … locale fr_HT` — corrigé en `v0.2.4` ; le code de
sortie du moteur est honnête depuis la `v0.2.5`) :

1. **Installer la coquille** : APK signé de la clé d'essai joint à la version GitHub
   `v0.2.5` (`app-debug.apk`), installation par chargement latéral (autoriser les
   sources inconnues). Les versions de test successives s'installent l'une sur
   l'autre (clé d'essai stable, jetons CI) ; une installation antérieure signée
   autrement (clé de débogage éphémère d'un exécutant, comme l'APK d'artefact des
   builds de branche) doit d'abord être désinstallée. Une `v0.2.1`-`v0.2.4` déjà
   installée se met à jour directement par-dessus.
2. **Mettre à jour le moteur** : section *Installation du moteur* — *Charger le
   manifeste* puis *Réinstaller (mise à jour ou réparation)* : les composants Java
   de la `v0.2.5` remplacent ceux des versions antérieures — ceux de la `v0.2.4`
   portent le correctif ECJ (ressources conservées à côté du dex ; le composant
   `java-compiler` passe d'environ 1,2 à 2,3 Mo décompressés), et ceux de la
   `v0.2.5` embarquent aussi le client au code de sortie auto-guéri.
3. **Construire l'exemple Java** : bouton *Importer les projets d'exemple* (stockage
   privé, aucune permission), puis *Construire* — le moteur démarre dans son propre
   processus (`app_process`, tas dédié), ECJ compile sur ART, les jar sortent dans
   `<projet>/build/`. Un second build doit être presque entièrement *à jour*
   (incrémentalité par empreintes). Depuis la `v0.2.2`, le moteur du build
   précédent est arrêté avant chaque lancement et à la fin du build (plus de VM
   oisives qui s'accumulent).
4. **Limites attendues sur cette version** : l'exemple `application-android` échoue
   proprement (composants aapt2/plate-forme non publiés, message actionnable) ; la
   production d'un APK installable sur appareil attend le prébuilt aapt2 arm64.
5. **En cas de problème (débogage)** : bouton *Copier les journaux* (presse-papiers)
   ou *Partager* (courriel, notes…) — le rapport contient le contexte (versions,
   appareil, projet), le journal du build, le **journal du moteur** (stderr capturé
   en continu : la seule trace qui survit à une mort brutale du processus), les
   **écroulements de la coquille** (depuis `v0.2.3` : piles d'appels des exceptions
   non attrapées, écrites dans un journal borné avant la fermeture du processus —
   un « force close » laisse donc sa trace) et la dernière trace JSON. Si le moteur
   meurt en cours de build, le journal affiché reçoit automatiquement le **code de
   sortie** (0 = propre ; -9 ou 137 = tué par le signal 9, typiquement le tueur de
   mémoire du système face à des VM oisives — corrigé en `v0.2.2` ; positif = échec
   interne, la trace stderr dit lequel) et les dernières lignes de stderr. C'est ce
   rapport qu'il faut joindre à tout ticket.

Points observés par ce test : lancement `app_process` avec `CLASSPATH` (D-01),
jars dexés en lecture seule, tueur de processus Android 12+ (D-02). Le binaire
aapt2 téléchargé (W^X, D-04) et l'installation par PackageInstaller restent sans
objet tant que les composants Android ne sont pas publiés.

## Prochaines étapes (après la Phase 4)

1. **Vérification sur appareil réel (ART)** — la grande inconnue restante, désormais
   testable par la procédure ci-dessus : `app_process` + `CLASSPATH`, jars dexés en
   lecture seule, ECJ et d8 sur ART, tueur de processus Android 12+. Le maillon ECJ
   (chargement des classes ET des ressources sur ART) est déverrouillé par la
   `v0.2.4` ; le premier build complet sur appareil reste à constater, de même que
   les maillons aval (jar, dex) sur ART.
2. **Prébuilt `aapt2` ANDROID (arm64)** — construction AOSP dédiée — puis publication
   des composants `aapt2`/`platform` après validation juridique de leur
   redistribution (`distribution.md` §7) : c'est ce qui débloque le pipeline Android
   complet sur appareil.
3. **Signature de production** de la coquille (clé d'essai stable aujourd'hui,
   jetons CI — les APK de test successifs s'installent l'un sur l'autre ; la clé de
   production et sa rotation restent à produire) et génération des notices de
   licences des dépendances embarquées (§7).
4. Améliorations priorisées restantes du registre de la conception : A-8 (cache de
   sorties par contenu), A-9 (ressources incrémentales par fichier), A-10 (empreintes
   d'ABI), AAR, multidex.
5. Éditeur/gestion de projets dans la coquille (création, import, arborescence).
6. Premier benchmark sur projet Android complet sur appareil.
