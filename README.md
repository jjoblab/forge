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
- [x] Diagnostic sur appareil (`v0.2.2`) — stderr du moteur capturé en continu par le
  client (journal borné + code de sortie), rapport copiable/partageable depuis la
  coquille (journal de build, journal du moteur, dernière trace), moteurs successifs
  arrêtés (plus de VM oisives qui s'accumulent), filet de sécurité du serveur contre
  les erreurs graves avant les tâches.
- [x] Correctif d'ouverture + journal d'écroulements (`v0.2.3`) — la `v0.2.2` plantait
  à l'ouverture (boutons de journaux branchés sans `findViewById`) ; les exceptions
  non attrapées de la coquille s'écrivent maintenant dans un journal borné, exposé
  dans le rapport copiable ; garde de câblage des vues dans les tests (le plantage
  est attrapé avant l'installation, plus après).
- [x] Correctif ECJ sur ART (`v0.2.4`) — la `v0.2.3` échouait à `core:compileJava`
  sur l'appareil (`Can't find bundle … locale fr_HT`) : d8 ne convertissant que les
  classes, les ressources d'ECJ (messages, tables du parseur) étaient perdues au
  dexage ; les jars dexés les conservent maintenant à côté du dex (servies par le
  classloader d'ART), manifeste et signatures d'origine écartés, `about.html` (notice
  EPL-2.0) conservé.
- [x] Code de sortie honnête (`v0.2.5`) — le rapport disait « code de sortie :
  inconnu » d'un moteur à l'arrêt : `codeSortie()` se guérit seul (sentinelle muette
  = `waitFor` immédiat), la mort du moteur se publie hors de la file mono-fil (elle
  retardait l'arrêt du moteur qu'elle attendait), et un canal fermé sans code
  s'affiche comme tel.
- [x] API `java.compiler` sur ART (`v0.2.6`) — la `v0.2.5` échouait à
  `core:compileJava` (`Failed resolution of: Ljavax/lang/model/SourceVersion;`) :
  Android n'embarque pas ce module ; le composant `java-compiler` embarque l'API
  extraite de la JVM d'assemblage, `SourceVersion` réécrit pour ART.
- [x] Outils Android distribués, build Java complet sur appareil (`v0.2.7`-`v0.2.8`)
  — `platform-34` (android.jar) puis `aapt2-arm64-v8a` (prébuilt ReVanced, aligné
  16 Ko) publiés ; `multi-modules-java` construit de bout en bout sur l'appareil ;
  la chaîne Android validée jusqu'à `packageApk` ; le journal du build porte son
  verdict final.
- [x] Économie de données mobiles + signature pure Java (`v0.2.9`) — cache de
  composants par empreinte de contenu (un contenu inchangé ne se re-télécharge
  JAMAIS, liens durs depuis le cache), versions d'outils dérivées du contenu, release
  GitHub pérenne « outils » ; et la clé de signature de débogage est générée en pur
  Java (`keytool` est absent d'ART — le crash `app:signApk` de la `v0.2.8` est
  corrigé).
- [x] Kotlin sur JVM, composant `kotlin-compiler` expérimental (`v0.2.9`) — champ
  `language` du modèle, tâche `compileKotlin` avant `compileJava` (même répertoire de
  classes, le Java voit le Kotlin), diagnostics structurés, exemple `kotlin-simple`
  (mixte Kotlin+Java) ; composant dexé multi-dex (~19 Mo, 6 dex : l'embarquable +
  dépendances runtime de son POM), validé sur JVM, audit ART à mener.
- [x] APK signé sur appareil (`v0.2.9`, rapport du 2026-11-21) — `application-android`
  construit de bout en bout sur ART : 9 tâches en 3155 ms, `signApk` compris (APK v1+v2,
  clé de débogage en pur Java — le crash keytool est mort).
- [x] POM sûrs sur ART (`v0.2.10`) — le parseur POM durcissait son DOM par des
  fonctionnalités Xerces absentes d'ART, et la première dépendance Maven d'un build
  appareil (`kotlin-stdlib`, exemple `kotlin-simple`) tuait `resolveDependencies` :
  DOCTYPE rejeté par scan du prologue (portable, même position que Maven),
  durcissement au mieux (motif `runCatching` de la référence), diagnostic honnête
  (invalide ≠ introuvable).
- [x] Premier `compileKotlin` sur ART corrigé + Android+Kotlin (`v0.2.11`-`v0.2.13`) — le crash
  `Resource not found: …/PathUtil.class` (rapport `v0.2.10` : le jar dexé ne porte
  plus d'entrées `.class`, le compilateur ne peut plus s'auto-localiser) est corrigé
  par arguments publics uniquement : `-kotlin-home` (répertoire existant) et
  `-Xintellij-plugin-root=` (le jar réel, qui a conservé sa configuration d'extensions
  après le dexage) — aucune passe bytecode, contrairement au moteur de référence ;
  `-no-stdlib` systématique (stdlib par Maven, absence dite par avertissement
  actionnable), `-no-jdk` sur plateforme fournie, bug de décodage d'URL jar corrigé
  (la stdlib n'était JAMAIS posée sur JVM), compilation JOINTE (le Kotlin voit le
  Java du module, `R.java` compris), Android+Kotlin admis jusqu'à l'APK signé
  (exemple `application-kotlin`, validé sur JVM avec résolution Maven réelle).
  Deux murs suivants, constatés par rapports appareil successifs et corrigés :
  le correctif `v0.2.11` n'était jamais appliqué (chemin du composant inventé —
  corrigé `v0.2.12`, la ligne « maison Kotlin » figure désormais dans le journal) ;
  puis `sun.misc.Unsafe.copyMemory`, API cachée d'ART, tuait l'initialisation du
  cœur IntelliJ embarqué (corrigé `v0.2.13` par OMBRE tolérante dans `engine.jar` —
  même paquet, dix méthodes épinglées par balayage du jar, replis CAS et tableau ;
  le composant kotlin-compiler reste octet pour octet identique, cache appareil
  préservé) ; enfin `javax.xml.stream.XMLStreamException`, StAX étant absent
  d'Android (rapport `v0.2.13`) — corrigé `v0.2.14` : le composant porte l'API
  JSR-173 standalone, son implémentation aalto, la surface javax de chargement
  (Icon, Notification*) et les shims `SwingUtilities`/`jdk.jfr` headless.
- [x] Cache de résolution, téléchargements visibles et annulables, console de build,
  sync (`v0.2.21`) — second niveau de cache à la Gradle : le RÉSULTAT de résolution
  par module est mémorisé (`.forge/résolutions/`, clé = racines + dépôts + cible +
  médiation ; les racines `-SNAPSHOT` contournent) : une résolution aux entrées
  identiques, artefacts toujours en cache, se relit SANS marche ni réseau — la tâche
  est alors rapportée `FROM_CACHE`. Chaque transfert est visible au fil de l'eau
  (événement `DownloadEvent`, avancement borné, ligne vivante dans la coquille) et
  annulable au tampon près (testé à chaque nœud de la marche). Le journal suit les
  conventions des consoles de build : une ligne par tâche avec son issue
  (`> Tâche app:compileKotlin À JOUR`), bouton de verbosité (essentiel / détaillé /
  tout) et verdict final décompté (« build réussi — 24 tâches : 5 exécutées,
  12 à jour, 2 du cache, 5 sautées »). Nouvelle trame `SYNC_BUILD` (protocole 2) et
  bouton *Synchroniser* : résolution des dépendances seule, sans compilation — un
  build suivant voit la résolution à jour. Décisions D-13 à D-15 dans
  `docs/conception.md`.
- [x] Dexage par bibliothèque, pulsation des tâches, caches partagés (`v0.2.22`) —
  le mur Compose de la `v0.2.21` refermé : la mort par signal 9 au dexage (une invocation
  d8 monolithique sur ~30 Mo de classes dans un tas de 1 024 Mo sur ART, des minutes de
  silence) devient un dexage PAR BIBLIOTHÈQUE en seaux à hash de contenu (modèle
  `DexingTransform` d'AGP) : chaque invocation d8 ne voit qu'un jar, chaque seau scellé
  SURVIT à une mort brutale (le build suivant reprend où il en était), et la fusion par
  lots produit le multidex natif. Les tâches longues pulsent (protocole 3 : nom,
  progression, temps écoulé en ligne vivante) et la réplique Maven, les résolutions et
  les seaux vivent désormais dans un cache PARTAGÉ entre projets
  (`<forgeHome>/cache/`) — réimporter un exemple ne re-télécharge plus rien.
  Décisions D-16 à D-18 dans `docs/conception.md`.
- [ ] Vérification sur appareil réel (ART) — reste : installation PackageInstaller de
  l'APK produit, validation appareil du `compileKotlin` corrigé (`kotlin-simple`,
  puis `application-kotlin`) ; procédure dans `docs/limites-et-suite.md`.

## Tester sur un appareil

Depuis la publication [`v0.2.14`](https://github.com/jjoblab/forge/releases/latest) :

1. **Coquille** : installez l'APK joint à la version (chargement latéral ; clé d'essai
   stable — les versions de test successives s'installent l'une sur l'autre).
2. **Moteur** : dans l'application, section *Installation du moteur* — le canal par
   défaut (version GitHub la plus récente) charge le manifeste signé (vérifié contre
   la clé épinglée dans la coquille), affiche les composants avec tailles, puis
   télécharge (reprise en cas de coupure) et installe le moteur en lecture seule.
   Depuis la `v0.2.9`, un contenu déjà téléchargé ne passe PLUS par le réseau (cache
   par empreinte de contenu : la mise à jour `v0.2.13` → `v0.2.14` ne re-télécharge que
   le cœur ~0,1 Mo et le composant Kotlin ~19,3 Mo — StAX et surface javax y vivent
   désormais ; compilateurs Java, plateforme et aapt2 sont liés localement) ; le
   composant `kotlin-compiler` (~19,3 Mo) est proposé, expérimental sur ART.
3. **Premier build** : bouton *Importer les projets d'exemple* puis *Construire* —
   l'exemple Java se compile sur l'appareil par ECJ dans le processus moteur
   (`app_process`) ; un second build est presque entièrement *à jour*. L'exemple
   `application-android` est VALIDÉ de bout en bout (APK signé v1+v2, rapport
   `v0.2.9`) ; l'exemple `kotlin-simple` (Kotlin+Java mélangés, `compileKotlin`
   puis `compileJava`) est corrigé sur ART en `v0.2.14` — voir l'étape suivante.
4. **Premier build Kotlin** : `kotlin-simple` (les crashs `compileKotlin` des rapports
   `v0.2.10` (auto-localisation), `v0.2.12` (API cachée `copyMemory`) et `v0.2.13`
   (StAX absent d'Android) sont corrigés
   en `v0.2.14` : rapport attendu), puis `application-kotlin`
   (Android+Kotlin, admis depuis la `v0.2.11`, validé sur JVM jusqu'à l'APK signé
   avec résolution Maven réelle de la stdlib).
5. **En cas de problème** : bouton *Copier les journaux* (presse-papiers) ou
   *Partager* — le rapport contient le journal du build, le journal du moteur
   (stderr capturé, la trace qui survit à une mort du processus), les écroulements
   de la coquille (piles d'appels écrites avant un « force close », depuis `v0.2.3`)
   et la dernière trace de build ; le code de sortie du moteur y est décrit (arrêt
   propre, signal du tueur de mémoire, échec interne).

Le pipeline Android complet est VALIDÉ sur appareil jusqu'à l'APK signé v1+v2
(`v0.2.9`, rapport du 2026-11-21) ; reste à constater l'installation PackageInstaller
de l'APK produit. Le composant `kotlin-compiler` est publié depuis la `v0.2.9` ;
le `compileKotlin` sur ART franchit ses murs un à un (auto-localisation `v0.2.11`,
maison jamais posée `v0.2.12`, API cachée `copyMemory` corrigée en `v0.2.13` par ombre
tolérante, StAX et surface javax servis par le composant en `v0.2.14`), validation
appareil attendue.
Voir `docs/distribution.md` §7 et `docs/limites-et-suite.md`.

## Licence

Forge est publié sous licence **Apache-2.0** (voir [`LICENSE`](LICENSE), décision
consignée dans `docs/distribution.md` §7). Les dépendances embarquées dans le moteur
(ECJ sous EPL-2.0, d8/R8 et apksig sous Apache-2.0) sont listées avec leurs conditions
de redistribution dans `docs/distribution.md`.
