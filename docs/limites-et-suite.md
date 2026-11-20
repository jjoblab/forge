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
| 4 — Implémentation | **En cours** (étapes 1-7 faites, 8-13 restantes) | Détail ci-dessous. |

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
| 9. Résolveur | À faire | POM/BOM XML durci, médiation nearest-wins, échec-sur-conflit réel, cache, intégrité, hors ligne. |
| 10. Pipeline Android | À faire | aapt2 natif (D-04), dex (d8), packaging, signature, installation, fusion de manifestes, ressources incrémentales. |
| 11. Diagnostics et console | À faire | Rédaction de secrets, tampon borné, trace exportable, écran de build dans la coquille. |
| 12. Packaging et publication | À faire | CI de construction des composants, dexage, manifeste signé, artefacts de release. |
| 13. Import Gradle statique (optionnel) | À faire | Lecteur statique vers `forge.json`. |

## Manques et limites constatés (Phases 3-4, étapes 1-8)

- **Écarts du pipeline Java avec le moteur de référence** (détail dans la section
  « Comparaison après implémentation » de [`REFERENCES.md`](REFERENCES.md)) :
  processeurs d'annotations désactivés (parité, à documenter comme décision), compilation
  incrémentale fine par fichier et cache d'ABI absents (améliorations A-8/A-10 non livrées —
  l'up-to-date par tâche et l'empreinte de classpath par contenu donnent déjà l'essentiel),
  variante « compilateur sans image » pour ART non câblée (repli `-source/-target`),
  `processResources` Java fusionné dans le jar (v1), chaîne de tests par module non livrée.
- **Aucune dépendance Maven résolue à ce jour** : le pipeline refuse proprement les
  coordonnées Maven (message citant l'étape 9) — le résolveur arrive à l'étape suivante.

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
  (refus délibéré, constat C-5). À combler selon les besoins des pipelines (étapes 8-10).
- **Le serveur de build réel est câblé depuis l'étape 8** : `EngineMain` lance par défaut le
  `BuildEngineServer` (modèle `forge.json` → pipeline → graphe → exécuteur → trames) ; le moteur
  factice reste accessible par l'option `--fake` (tests de contrat du canal).
- **Éditeur/gestion de projets dans la coquille** : minimal (état du moteur, bouton de
  test) ; l'assistant d'installation complet (listes, tailles, avertissement mobile,
  progression, annulation — exigences §2.2) sera câblé sur `setup` à l'étape 11 ; la
  logique testable existe déjà.
- **Licence de Forge choisie : Apache-2.0** (décision propriétaire du 2026-11-20, appliquée
  au dépôt entier — fichier `LICENSE` ; voir `distribution.md` §7 pour les notices des
  dépendances embarquées, à générer à l'étape 12).
- **Clé publique ECDSA de production** : pas encore générée ; une clé de test est créée à
  la volée dans les tests. À épingler à l'étape 12 (procédure `distribution.md` §4).

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

## Prochaines étapes (ordre proposé)

1. **Étape 8 — pipeline Java** : intégrer ECJ (compilation en travailleur, `--release`
   configurable, processeurs d'annotation), normalisation du classpath pour l'empreinte,
   Jar déterministe ; câbler le premier build réel dans un `BuildEngineServer` remplaçant
   le moteur factice.
2. **Étape 9 — résolveur** de dépendances (POM durci, médiation, intégrité, hors ligne).
3. **Étape 10 — pipeline Android** (aapt2 via `NativeResourceCompiler`, dex d8, signature
   apksig, packaging, installation) + registre des processus enfants + watchdogs.
4. **Assistant d'installation complet** et écran de build (étape 11), puis packaging et
   publication CI (étape 12).
5. Vérification sur appareil réel (liste au début de cette section) et benchmark
   complet vs incrémental sur un projet multi-modules (modèle REF-152).
