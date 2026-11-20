# Distribution de Forge

> Phase 3. Politique de distribution de la coquille et du moteur téléchargeable : contraintes
> des magasins, canaux retenus, chaîne de publication, licences, guidance appareil. Les
> décisions d'architecture qui motivent ce document sont dans [`conception.md`](conception.md)
> (D-01 à D-06) ; les constats `S-x`, `P-x`, `C-x` renvoient à
> [`revue-moteur-build.md`](revue-moteur-build.md).

## 1. Résumé exécutif

- **Google Play est exclu pour le modèle nominal** de Forge : sa politique « Device and Network
  Abuse » interdit à une application de télécharger du code exécutable (dex, JAR, `.so`) depuis
  une source autre que Play — c'est précisément notre architecture coquille + moteur
  téléchargeable. Le publierait-on tout embarqué que notre `targetSdk 28` (requis pour exécuter
  `aapt2` téléchargé, D-04) serait de toute façon refusé : Play exige un `targetSdk` bien plus
  récent pour toute nouvelle mise à jour.
- **Canaux retenus** : GitHub Releases (canal principal, APK + composants + manifeste signé) et
  F-Droid (sous conditions : licence libre, build depuis les sources, consentement explicite
  au téléchargement des composants — que notre assistant fournit nativement).
- **La confiance ne repose pas sur le canal** : manifeste signé ECDSA (clé épinglée dans la
  coquille), SHA-256 par composant, HTTPS strict. Un APK récupéré n'importe où installe la
  même chose, vérifiée pareil.
- **Une variante « tout embarqué »** (moteur et outil de ressources dans l'APK, D-04 point 2)
  reste possible pour un magasin restrictif, au prix de la mise à jour du moteur = mise à jour
  de l'application. Elle n'est pas la priorité.

## 2. Pourquoi pas Google Play

1. **Code exécutable téléchargé.** La politique Play (Device and Network Abuse) est explicite :
   « an app may not download executable code (e.g. dex, JAR, .so files) from a source other
   than Google Play », l'exception ne couvrant que du code exécuté dans une machine virtuelle
   aux capacités restreintes type WebView. Notre moteur est du code Java complet téléchargé
   depuis nos releases et exécuté hors WebView : hors exception. Tenter de publier la coquille
   « vide » en masquant la fonctionnalité serait une violation de politique, pas un contournement.
2. **`targetSdk 28`.** Play exige, pour toute nouvelle application ou mise à jour, un niveau de
   `targetSdk` récent (le plancher monte chaque année ; il est déjà bien au-delà de 28 et
   l'exigence est publiée par Google). Notre D-04 suppose `targetSdk 28` pour exécuter les
   binaires d'outils téléchargés depuis le stockage privé — restriction W^X d'Android 10 pour
   les applications plus récentes.
3. **Conclusion assumée.** La valeur de Forge (environnement de build embarqué, évolutif
   indépendamment de l'application) est exactement ce que Play interdit. La cohérence du
   projet est : distribution hors magasin, avec signatures de bout en bout. Ce choix est
   documenté ici pour ne pas être redébattu à chaque sortie ; seule la variante « tout
   embarqué » (§6) pourrait candidater à un magasin restrictif.

## 3. Canaux retenus

### 3.1 GitHub Releases — canal principal

- L'APK de la coquille (universel : la coquille n'embarque aucun natif, un seul APK pour
  toutes les ABI) est publié signé à chaque version.
- Les composants du moteur (D-06) sont publiés comme **artefacts de release** :
  `engine-core-<v>.zip`, `aapt2-<abi>-<v>.zip`, `platform-<api>-<v>.zip`,
  `kotlin-compiler-<v>.zip`, chacun avec sa somme SHA-256.
- Le `manifest.json` + `manifest.sig` de chaque version référence ces artefacts par URL HTTPS.
- Miroirs : le manifeste porte une liste d'URLs par composant ; ajouter un miroir (autre
  hébergeur) ne change rien pour l'utilisateur (la somme est épinglée par le manifeste signé).
- L'**installation hors ligne** (cahier des charges) est le téléchargement manuel de ces
  fichiers, puis « installer depuis un fichier local » dans l'assistant — même vérification de
  signature, zéro réseau.

### 3.2 F-Droid — canal communautaire, sous conditions

La politique d'inclusion de F-Droid impose :

| Exigence F-Droid | Réponse Forge |
|---|---|
| Logiciel libre (licence FLOSS) | **Décision requise avant toute soumission** (§7). Le code de Forge sera publiée sous licence libre ; la recommandation de l'équipe est Apache-2.0. |
| Compilable depuis les sources, chaîne 100 % libre | Oui : JDK 17 (OpenJDK), Gradle, aucune dépendance propriétaire. Les dépendances binaires autorisées (Maven Central) sont acceptées par leur politique. |
| Pas de téléchargement de binaires exécutables sans **consentement explicite opt-in** | Conforme par construction : l'assistant d'installation présente composants, tailles et avertissement réseau, et n'installe rien sans acceptation explicite (conception §4) — un refus n'est jamais plus difficile qu'une acceptation. |
| Pas de suivi, pas de pub, pas de dépendance réseau non libre | Conforme : aucun traceur, aucun compte ; les composants viennent de nos releases publiques (anti-fonctionnalité « NonFreeNet » non applicable car le contenu est le nôtre et librement redistribuable). |

Particularité F-Droid : l'APK est **signé par F-Droid**, pas par nous. Conséquence assumée et
documentée : la clé ECDSA du manifeste est **épinglée dans le code de la coquille**, pas liée à
la signature de l'APK — la vérification du moteur est donc identique quel que soit le canal
(GitHub ou F-Droid). C'est aussi pour cela que la confiance « méta » (quelles versions
existent) passe par le manifeste signé, jamais par la provenance de l'APK.

### 3.3 Installation directe (side-load)

Toujours possible : l'APK universel est un fichier ; l'utilisateur active « installer les apps
inconnues » pour son navigateur/gestionnaire de fichiers. Android 14+ n'impose aucune
confirmation supplémentaire pour un `targetSdk 28` (les blocages d'installation ne visent que
des `targetSdk` antérieurs à 23).

## 4. Chaîne de publication et gestion des clés

1. **CI** (livrée, étape 12) : à chaque étiquette `vX.Y.Z`, la CI exécute la chaîne
   complète — tests, assemblage des composants dexés (`:engine:composants` :
   engine-core, java-compiler, dexeur, signeur, platform-34 ; aapt2-arm64-v8a attend
   sa construction AOSP, voir §7),
   signature du manifeste avec le secret `FORGE_MANIFEST_KEY`, **vérification de la
   signature contre la clé publique épinglée** (prouve que le jeton CI et la coquille
   parlent de la même paire), publication d'une version GitHub avec tous les
   artefacts. La **clé privée ECDSA du manifeste ne sort jamais de la CI** (secret
   GitHub, posé le 2026-11-20, première génération) ; la clé publique correspondante
   est épinglée dans `app/src/main/java/jo/forge/app/ClesForge.java`.
2. **Rotation de clé** : prévue par la conception sans changement de format — la coquille
   embarque la clé active et peut embarquer la suivante (période de chevauchement) ; une clé
   compromise est révoquée en publiant une version de coquille qui ne l'accepte plus, et le
   `minAppVersion` du manifeste force la mise à jour des coquilles trop vieilles.
3. **Révocation de composants** : une version de moteur compromise est retirée du manifeste
   publié (le manifeste est une capture datée : les versions antérieures déjà installées
   restent fonctionnelles mais l'assistant signale la mise à jour de sécurité).
4. **Intégrité Maven** : les dépendances des projets de l'utilisateur sont vérifiées contre les
   sommes publiées par les dépôts (conception D-05) ; la liste des dépôts autorisés est dans le
   manifeste signé — un dépôt ajouté par l'utilisateur produit un avertissement explicite.

## 5. Versions et mises à jour

- **Versionnement sémantique** pour la coquille, le moteur et chaque composant ; le protocole
  `api` a sa propre version (négociation à la poignée de main, conception D-07).
- Le moteur se met à jour **sans mettre à jour l'application** (c'est le but de l'architecture)
  ; la coquille trop vieille pour un manifeste est refusée proprement (`minAppVersion`) avec le
  message « mettez à jour l'application pour installer ce moteur ».
- Politique de support : les deux dernières versions majeures de la coquille reçoivent les
  mises à jour de sécurité du moteur.

## 6. Variante « tout embarqué » (porte de sortie magasin)

Si un canal exigeant (Play ou équivalent) devenait nécessaire : coquille + moteur dexé +
`aapt2` (toutes ABI utiles) **dans l'APK**, `ResourceCompiler` basculé sur l'implémentation
Java pure (conception D-04), `targetSdk` alors libre de monter. Coûts assumés : APK de ~80 Mo,
moteur figé entre deux mises à jour de l'application, perte de l'installation hors ligne
 séparée. Cette variante n'est pas planifiée ; elle est documentée parce que la conception
(l'interface `ResourceCompiler`, le découpage composants) la rend possible sans réécriture.

## 7. Licences

- **Licence de Forge : Apache-2.0** (décision propriétaire du 2026-11-20, appliquée à tout le
  dépôt, fichier `LICENSE` à la racine). Choix conforme à la recommandation ci-dessous.
- **Recommandation (suivie)** : Apache-2.0 pour tout le dépôt — permissive, compatible avec toutes les
  dépendances du moteur, maximise les contributions et la redistribution par F-Droid.
- **Dépendances embarquées dans le moteur** (toutes FLOSS, à mentionner dans les notices) :
  - ECJ (compilateur Java) : **EPL-2.0** — redistribution dans notre artefact acceptée ;
    compatible avec Apache-2.0 pour la redistribution (mention de licence requise) ;
  - API `java.compiler` (`javax.lang.model`, `javax.annotation.processing`,
    `javax.tools`, correctif v0.2.6) : classes extraites du module de l'**OpenJDK**
    de la JVM d'assemblage — **GPLv2 avec exception de classpath** ; l'exception
    autorise expressément la liaison et la redistribution depuis un programme de
    toute licence, Apache-2.0 compris. Notice embarquée dans le composant
    (`about-openjdk-java-compiler.txt`, conservée à côté du dex comme les autres
    ressources). `javax.lang.model.SourceVersion` est une réécriture Forge
    (Apache-2.0) : l'original lit `Runtime.version()`, absent d'ART — la
    réécriture est documentée dans le dépôt
    (`setup/src/compatArt/java/javax/lang/model/SourceVersion.java`) ;
  - Tranches lambda (`java.lang.invoke.StringConcatFactory`,
    `LambdaMetafactory`, `StringConcatException`, correctif v0.2.7) : **écrites par
    Forge** (Apache-2.0, `engine/src/stubsLambda/java`) — signatures publiques
    seules, servies à la compilation depuis la ressource `/lambda-stubs.jar` du jar
    du moteur, jamais dexées, jamais exécutées (le runtime API 26+ fournit les
    vraies fabriques). Rien à redistribuer de Google ici : nos tranches remplacent
    le `core-lambda-stubs.jar` des build-tools sans le copier ;
  - d8/R8 (dexeur) : Apache-2.0 ;
  - apksig (signature) : Apache-2.0.
- **Aucun code du moteur de référence** n'est embarqué (réimplémentation indépendante, voir
  [`REFERENCES.md`](REFERENCES.md) §licence) ; sa licence GPL-3.0 ne contamine donc pas la
  nôtre.
- **`android.jar` (composant `platform-34`, publié depuis v0.2.7)** : artefact de
  définitions d'API du SDK Android, redistribué tel quel (24 Mo) comme bibliothèque
  de compilation — la redistribution validée le 2026-11-20 : c'est la pratique
  établie des IDE sur appareil (le moteur de référence l'embarque comme actif de
  son application, AIDE l'a distribué pendant des années) et le jar ne contient que
  des signatures d'API, sans code exécutable ; l'alternative documentée —
  téléchargement au moment de l'installation depuis un miroir autorisé — reste
  tracée (§9.2) si le propriétaire du dépôt préfère s'y rabattre. Sans ce
  composant, AUCUNE compilation ne résout `java.lang.Object` sur ART (ECJ ne peut
  pas lire la plate-forme dex/oat de l'appareil — correctif v0.2.7).
- **`aapt2` (omis)** : le prébuilt des build-tools est x86_64/Linux, inutilisable sur
  un appareil arm64 ; sa publication attend une construction AOSP arm64
  (évolution consignée dans `limites-et-suite.md`). Les modules `application`
  Android échouent proprement avec un message actionnable en attendant.

## 8. Guidance appareil (à inclure dans l'application et la FAQ)

- **Android 12+ (tueur de « processus fantômes »)** : un appareil peut tuer les processus
  enfants d'une application en arrière-plan (limite système ~32, ciblage des enfants gourmands
  en CPU). Forge y répond par le service premier plan, un plafond bas de VM travailleuses et
  l'extinction au repos (conception D-02) ; pour les très gros builds, la FAQ documentera
  l'option développeur « Désactiver les restrictions de processus enfants » (Android 14+) ou sa
  commande `adb` équivalente (Android 12-13). Un build tué par le système est **redémarrable**
  : l'état du moteur est reconstitué (magasin d'empreintes atomique, conception D-12) et le
  diagnostic distingue « tué par le système » d'un échec de build.
- **Espace disque** : l'assistant affiche tailles exactes et espace libre avant tout
  téléchargement ; le moteur complet (cœur + plateforme + `aapt2` + Kotlin optionnel) pèse
  ~100 Mo téléchargés.
- **Données mobiles** : avertissement systématique avant téléchargement sur réseau payant,
  avec la taille totale.
- **Batterie** : builds annulables à tout instant, extinction automatique du moteur au repos.

## 9. Checklist avant première publication

1. Licence choisie et fichiers de notices (tiers) générés (§7).
2. Validation de la redistribution de `android.jar` (§7) — **faite le 2026-11-20**
   (pratique établie des IDE sur appareil, jar de signatures sans code exécutable) ;
   le repli documenté — téléchargement depuis un miroir autorisé à l'installation —
   reste décrit §7.
3. Clé ECDSA de production créée en CI, publique épinglée, procédure de rotation écrite (§4).
4. FAQ « processus fantômes » et « données mobiles » rédigées (§8).
5. Deux canaux opérationnels : release GitHub vérifiée de bout en bout (coquille neuve →
   assistant → build d'un projet exemple), puis candidature F-Droid (§3.2).
