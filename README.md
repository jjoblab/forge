# Forge

**Forge** est un moteur de build Android/Java qui tourne entièrement sur l'appareil (ART), sans
daemon Gradle et sans ordinateur : compilation, traitement des ressources, dex, packaging et
signature d'APK se font sur le téléphone lui-même.

Forge est développé en **Java 17** (identifiants en anglais, commentaires et documents en français),
avec `minSdk 28` pour l'application hôte.

## Architecture : une coquille + un moteur téléchargeable

L'application est une **coquille** : interface, gestion des projets et des fichiers, assistant
d'installation, client du moteur. Elle ne contient **aucune** logique de build, aucun compilateur,
aucun dexeur, aucun binaire d'outil.

Le **moteur** est un paquet versionné, téléchargé puis installé dans le stockage privé de
l'application à la première utilisation, et exécuté dans un **processus séparé** : un crash ou un
manque de mémoire pendant un build ne fait jamais tomber l'interface. L'expérience visée est celle
d'un environnement de développement que l'on installe composant par composant (compilateur Java,
dexeur, signature, `aapt2`, plateformes Android), que l'on peut mettre à jour, réparer ou
désinstaller.

La conception détaillée (manifeste signé, vérification d'intégrité et d'authenticité, installation
atomique, contrat application ↔ moteur) est décrite dans la documentation.

## Structure du dépôt

| Répertoire | Rôle |
|---|---|
| `app/` | La coquille : interface Android, gestion des projets, assistant d'installation. |
| `api/` | Le contrat partagé application ↔ moteur : minimal, stable, versionné. |
| `json/` | Lecture/écriture JSON strictes, sans dépendance (manifeste, état, `forge.json`). |
| `client/` | Supervision du moteur : lancement de VM, poignée de main, reconnexion. |
| `setup/` | Logique d'installation et de mise à jour du moteur, testable sans interface. |
| `engine/` | Le moteur : cœur, pipelines Java et Android. Construit comme artefact téléchargeable. |
| `examples/` | Projets d'exemple construits par le moteur (`forge.json`). |
| `tools/` | Scripts de packaging, de publication et hooks de contrôle locaux. |
| `docs/` | Analyse, revue, conception et suivi du projet. |

## Documentation

- [`docs/analyse-moteur-build.md`](docs/analyse-moteur-build.md) — cartographie du moteur de
  référence qui a servi de point de départ (Phases 0-1).
- [`docs/revue-moteur-build.md`](docs/revue-moteur-build.md) — revue critique du moteur de
  référence : sécurité, performance, correction, maintenabilité (Phase 2).
- [`docs/conception.md`](docs/conception.md) — architecture cible et décisions justifiées
  D-01..D-12, améliorations priorisées, ordre d'implémentation (Phase 3).
- [`docs/distribution.md`](docs/distribution.md) — politiques de distribution (magasins,
  hors magasin, clés, licences) (Phase 3).
- [`docs/REFERENCES.md`](docs/REFERENCES.md) — registre des références `REF-xxx` (seul fichier du
  dépôt autorisé à citer le moteur de référence).
- [`docs/limites-et-suite.md`](docs/limites-et-suite.md) — ce qui est fait, ce qui reste, les
  hypothèses retenues.

## Mise en place pour contribuer

Après un clone :

```sh
./tools/setup-local.sh
```

Ce script installe les hooks git (`pre-commit`, `commit-msg`) via `core.hooksPath` et crée le
fichier local `.git/info/forbidden-terms.txt`. **Chaque contributeur doit peupler ce fichier avec
les expressions dont la présence est interdite dans le dépôt** (voir la politique de références
dans `docs/REFERENCES.md`) ; le fichier est volontairement non versionné pour que ces termes
n'apparaissent eux-mêmes dans aucun fichier du dépôt.

Les hooks refusent tout commit dont le contenu ou le message contient un terme interdit ailleurs
que dans `docs/REFERENCES.md`. La CI applique le même contrôle à chaque push (secret
`FORBIDDEN_TERMS`).

### Build et tests

Prérequis : JDK 17 et un SDK Android (plateforme 34, build-tools 34) — pointer `local.properties`
vers le SDK comme d'habitude.

```sh
./gradlew :api:test :client:test :setup:test :engine:test   # tests des modules purs (JVM)
./gradlew :app:testDebugUnitTest                            # tests de la coquille
./gradlew :app:assembleDebug                                # APK de la coquille
```

## État du projet

- [x] Phase 0 — exploration du moteur de référence, épinglage du SHA, vérification de la licence.
- [x] Phase 1 — analyse complète (`docs/analyse-moteur-build.md`).
- [x] Phase 2 — revue critique (`docs/revue-moteur-build.md`).
- [x] Phase 3 — conception (`docs/conception.md`, `docs/distribution.md`).
- [x] Phase 4, étapes 1-7 — squelette Gradle, contrat `api` + moteur factice en processus
  séparé, installateur vérifié (manifeste signé, reprise, installation atomique), chargement réel, moteur de
  tâches, exécuteur borné annulable, empreintes et magasin persistant.
- [x] Phase 4, étapes 8-13 — pipeline Java (ECJ, jar déterministe), résolveur Maven
  (intégrité, conflits, hors ligne), pipeline Android jusqu'à l'**APK signé v1+v2**
  (aapt2 borné, d8, packaging déterministe), diagnostics/rédaction de secrets/trace
  exportable + écran de build dans la coquille, chaîne de publication (composants dexés,
  manifeste signé, clé de production épinglée, job CI de release), import Gradle statique.
- [x] Post-4 — assistant d'installation câblé dans la coquille (canal signé, composants
  avec tailles, progression, reprise), jonction des outils Android au moteur installé
  (`FORGE_ENGINE_DIR`), layout des composants publiés conforme à la convention
  d'installation, exemples embarqués importables sans permission, publications de
  test `v0.2.0`/`v0.2.1` (sans composants redistribuables du SDK — garde juridique ;
  `v0.2.1` signe les APK de test avec une clé d'essai stable).
- [ ] Vérification sur appareil réel (ART) — procédure dans `docs/limites-et-suite.md`.

## Tester sur un appareil

Depuis la publication [`v0.2.1`](https://github.com/jjoblab/forge/releases/latest) :

1. **Coquille** : installez l'APK joint à la version (chargement latéral ; clé d'essai
   stable — les versions de test successives s'installent l'une sur l'autre).
2. **Moteur** : dans l'application, section *Installation du moteur* — le canal par
   défaut (version GitHub la plus récente) charge le manifeste signé (vérifié contre
   la clé épinglée dans la coquille), affiche les composants avec tailles, puis
   télécharge (reprise en cas de coupure) et installe le moteur en lecture seule.
3. **Premier build** : bouton *Importer les projets d'exemple* puis *Construire* —
   l'exemple Java se compile sur l'appareil par ECJ dans le processus moteur
   (`app_process`) ; un second build est presque entièrement *à jour*.

Le pipeline Android complet sur appareil (ressources → APK signé) attend la
publication des composants `aapt2` (prébuilt arm64) et `platform` — voir
`docs/distribution.md` §7 et `docs/limites-et-suite.md`.

## Licence

Forge est publié sous licence **Apache-2.0** (voir [`LICENSE`](LICENSE), décision
consignée dans `docs/distribution.md` §7). Les dépendances embarquées dans le moteur
(ECJ sous EPL-2.0, d8/R8 et apksig sous Apache-2.0) sont listées avec leurs conditions
de redistribution dans `docs/distribution.md`.
