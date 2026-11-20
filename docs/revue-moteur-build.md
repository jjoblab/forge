# Revue critique du moteur de build de référence

> Phase 2 du plan de travail. Chaque constat est justifié par un `REF-xxx` (détail dans
> [`REFERENCES.md`](REFERENCES.md)). Gravités : **critique** (exploitable ou perte de données),
> **élevée** (dégradation forte, sécurité significative), **moyenne** (défaut réel, contournable),
> **faible** (hygiène, cas limite). Les correctifs proposés sont des engagements de conception
> pour Forge, à affiner en Phase 3.

## 1. Sécurité

| # | REF | Problème | Gravité | Correctif proposé |
|---|---|---|---|---|
| S-1 | REF-125 | **Aucune vérification d'intégrité des artefacts téléchargés** : les jars/AAR/POM sont écrits et utilisés (classpath de compilation **et** APK signé) sans comparaison à une somme, quelle qu'elle soit. Un téléchargement altéré (homme au milieu, proxy, portail captif, CDN compromis) injecte du code arbitraire. | **Critique** | Vérification SHA-256 systématique avant tout usage (somme du manifeste pour nos composants, `.sha1`/`.sha256` publiés par les dépôts Maven pour les dépendances) ; échec d'intégrité = rejet + re-téléchargement + diagnostic explicite. |
| S-2 | REF-126 | **HTTP accepté et downgrade suivi** : dépôts `http://` autorisés, redirections suivies vers n'importe quel schéma `http*`. Combiné à S-1, compromission complète de la chaîne d'approvisionnement par un attaquant réseau. | **Élevée** | HTTPS obligatoire (refus net de `http://`), refus des redirections qui dégradent le schéma, liste de dépôts signée par nos soins. |
| S-3 | REF-068 | **Mots de passe de keystore en clair sur la ligne de commande** des sous-processus de signature (chemin desktop) : lisibles dans la table des processus pendant toute la durée de la signature. | **Moyenne** | Signature toujours en processus (jamais de secret en argument) ; si un outil externe est inévitable, passer le secret par fichier ou variable d'environnement. |
| S-4 | REF-069 | **Mots de passe des keystores release en clair au repos** (registre JSON du home applicatif). | **Moyenne** | Chiffrement au repos via Android Keystore (clé non exportable), ou refus de persister les mots de passe (redemander à chaque build release). |
| S-5 | REF-063 | **Prebuilt de l'outil de ressources téléchargé sans somme de contrôle** (seuls en-tête ELF et taille minimale vérifiés) au moment de la construction de l'IDE : risque de chaîne d'approvisionnement en amont de nous-mêmes. | **Moyenne** | Notre chaîne : manifeste signé + SHA-256 épinglé pour chaque composant, y compris les binaires natifs ; reconstruction documentée des prebuilts. |
| S-6 | REF-061, REF-074 | **Parseurs XML non durcis** pour les `values` fusionnés et les layouts (entités externes non désactivées), alors que le parseur de manifestes l'est : un `values.xml` malveillant dans une AAR peut tenter une lecture de fichiers locaux (XXE). | **Moyenne** | Une seule fabrique XML durcie (entités générales/paramétriques + DTD désactivées) pour **tous** les parseurs du moteur. |
| S-7 | REF-017 | **Contournement du bac à sable** : le service de contrôle de build expose une exécution capturée avec permissions toutes accordées ; toute extension résolvant ce service exécute un `main` arbitraire sans médiation. | **Moyenne** | Notre contrat `api/` n'expose pas d'exécution non médiée ; tout mode « capturé » garde le broker normal avec une politique headless explicite. |
| S-8 | REF-063 | **Aucun timeout sur les sous-processus** (outil de ressources, aligneur, keytool) : un processus bloqué suspend la tâche indéfiniment (seul un tueur d'OOM ciblé existe). | **Moyenne** | Watchdog par appel (durée par défaut configurable), `destroyForcibly` + échec propre à l'expiration. |
| S-9 | REF-041 | **Aucune rédaction de secrets** dans le pipeline de journaux : tout ce qu'un outil imprime (ligne de commande, exception) part dans le transcript et le rapport copié. | **Faible** | Point unique de rédaction (motifs : mots de passe, jetons, en-têtes) à l'entrée du journal. |
| S-10 | REF-029, REF-057 | **Bac à sable « curated », assumé non durci** : liste d'API médiées, le reste (canaux NIO, defineClass, JNI…) passe ; les sous-processus engendrés par le programme ne sont jamais détruits. | **Faible** | Hors périmètre initial de Forge (pas d'exécution de programmes) ; si ajouté : registre des processus engendrés + destruction au teardown, et affichage honnête des limites. |
| S-11 | REF-058 | **Protocole de rapport de tests forgeable** : un programme peut imprimer le marqueur de contrôle et fabriquer des résultats dans l'interface. | **Faible** | Si nous reprenons l'idée : nonce de run injecté dans le runner généré. |

**Défenses existantes à conserver** (le moteur de référence fait mieux que beaucoup d'outils sur
ces points) : garde zip-slip dans les deux extracteurs d'archives (REF-071, REF-128) ;
DOCTYPE rejeté dans le parseur POM (REF-121) ; parseur de manifestes durci XXE (REF-064) ;
registre des APK signés empêchant l'installation d'un APK étranger (REF-070) ; keystores jamais
référencés dans les fichiers de projet (REF-069) ; confirmation utilisateur obligatoire à
l'installation (REF-070) ; écritures atomiques de la persistance modèle (REF-107).

## 2. Performance

| # | REF | Problème | Gravité | Correctif proposé |
|---|---|---|---|---|
| P-1 | REF-061 | **Fusions entièrement reconstruites** à chaque exécution (ressources, bibliothèques natives, ressources Java) : suppression récursive de la sortie puis refusion complète — éditer une chaîne de caractères re-merge et re-compile toutes les ressources du projet. | **Élevée** | Fusion incrémentale par fichier (index source→sortie + empreintes), à l'image des archives dex. |
| P-2 | REF-092, REF-124 | **Caches disque sans quota ni éviction** (cache dex inter-projets, cache du résolveur) : croissance non bornée sur un appareil multi-projets ; seule la suppression manuelle existe. | **Élevée** | Quota par cache avec éviction LRU (paramètre utilisateur + espace signalé par l'assistant). |
| P-3 | REF-022 | **Parallélisme du moteur codé en dur à 2**, indépendant des cœurs et de la mémoire ; desktop multi-cœur sous-exploité. | **Moyenne** | Limite calculée : `min(cœurs, budget mémoire)` plafonnée, exposée dans les événements de build. |
| P-4 | REF-127 | **Téléchargements sans reprise** : un échec à 90 % d'une AAR de 30 Mo repart de zéro. | **Moyenne** | Reprise `Range` sur le partiel (exigence de notre cahier des charges), avec validation de cohérence avant assemblage. |
| P-5 | REF-031, REF-036 | **Caches de digests vidés intégralement** au plafond (100 000 et 200 000 entrées) : dépassement = re-hachage massif de tout le projet au build suivant. | **Moyenne** | Éviction par entrée (LRU) au lieu du vidage. |
| P-6 | REF-054, REF-058 | **Chargements complets en mémoire** : la découverte de tests lit tous les `.class` de la sortie ; la variante ART du compilateur ré-ouvre et indexe tous les jars à chaque compilation (pas de cache inter-compilations). | **Moyenne** | Streaming + cache d'index par (chemin, mtime, taille). |
| P-7 | REF-043 | **État de console recopié à chaque ligne** (liste immuable) : coût quadratique sur un build bavard, sans plafond contrairement au transcript de run. | **Faible** | Tampon circulaire borné + instantané à la demande. |
| P-8 | REF-113 | **Vues et facettes du modèle ré-allouées/décodées à chaque lecture** ; compensé par mémoïsation côté hôte uniquement. | **Faible** | Cache de vues par génération dans le modèle lui-même. |
| P-9 | REF-020 | **Validation anti-cycle quadratique** (DFS complet par arête inférée candidate) sur les gros graphes à forte inférence. | **Faible** | Détection incrémentale (union-find) ou fermeture mémoïsée. |
| P-10 | REF-063, REF-065 | Détails : double `link` de ressources en cas de conflit d'ids stables (relance complète non comptée) ; AAR sans `R.txt` → R avec la table entière de l'app (retour du coût que les R non-transitifs évitent). | **Faible** | Compter les relances dans les métriques ; avertissement build quand la table entière est utilisée. |
| P-11 | REF-134 | **Environnement du compilateur Kotlin maintenu chaud sans libération possible** (durée de vie = session, mémoire non rendue). | **Faible** | Notre moteur a un cycle de vie explicite (arrêt = vidage) ; re-préchauffage après coup si besoin. |

## 3. Correction

| # | REF | Problème | Gravité | Correctif proposé |
|---|---|---|---|---|
| C-1 | REF-088, REF-057 | **Sous-processus orphelins à l'annulation** : les VM forkées (dex/R8) ne sont pas tuées à l'annulation — elles continuent jusqu'à leur fin naturelle en tenant leur permis de concurrence ; les processus engendrés par les programmes exécutés non plus. | **Moyenne** | `onCancel` → `destroyForcibly` systématique ; registre des processus engendrés détruits au teardown. (Exigence explicite de notre cahier des charges : « aucun processus orphelin ».) |
| C-2 | REF-025 | **Écritures non atomiques du magasin d'empreintes** (fichier par tâche écrit en direct, sans version de format) : un crash à mi-écriture laisse un enregistrement tronqué. La tolérance à la lecture en fait un re-run inutile, jamais un faux up-to-date — mais c'est de la chance, pas un invariant. | **Moyenne** | Temp + renommage atomique, en-tête de version. |
| C-3 | REF-026 | **Sanitisation non injective des noms de tâches** : deux tâches distinctes peuvent partager leur fichier d'empreintes → décisions up-to-date croisées. | **Moyenne** | Encodage injectif (hex/Base64url du nom). |
| C-4 | REF-032 | **Digest vide mis en cache sur erreur d'E/S** : un fichier momentanément illisible est empreinté « vide » tant que ses attributs ne changent pas, sans trace. | **Moyenne** | Erreur de lecture ⇒ exclusion + invalidation (re-run sûr) + journal. |
| C-5 | REF-020 | **Inférence de dépendances par préfixe de chaîne non borné aux segments** : `/x/build` « chevauche » `/x/build-classes` → dépendance dure induite entre tâches sans relation (sur-sérialisation, blocage indu). | **Moyenne** | Comparaison par segments (`==` ou préfixe + séparateur). |
| C-6 | REF-034 | **Empreinte de classpath sans contenu** (kind + chemin seulement) : un jar remplacé au même chemin ne change pas l'empreinte ; l'entrée classpath du contrat n'est par ailleurs utilisée par aucune tâche (API morte). | **Moyenne** | Empreinte incluant un digest de contenu (via le cache de digests) ; pas d'API morte dans notre contrat. |
| C-7 | REF-122 | **`FAIL_ON_CONFLICT` ne fait pas échouer** : la politique « échec sur conflit » se comporte comme « la plus récente gagne » (conflits seulement rapportés) ; de plus le comparateur de versions diverge de la sémantique Maven (qualifier inconnu classé « release », `+` mal traité). | **Moyenne** | La politique échoue réellement ; comparateur aligné sur Maven, testé contre la suite de cas connus. |
| C-8 | REF-110 | **Fenêtre de lost-update sur le modèle** : deux transactions concurrentes (ou transaction + création de bibliothèque) s'écrasent sans détection (pas de contrôle de génération au commit). | **Moyenne** | Contrôle optimiste : la génération capturée à l'ouverture doit être celle du commit, sinon rejeu/rebasing explicite. |
| C-9 | REF-112 | **Événements publiés en synchrone sous le verrou d'écriture** : un abonné lent bloque tous les commits ; un abonné qui lève fait échouer un commit déjà installé. La discipline de lectures documentée n'est appliquée nulle part. | **Moyenne** | Publication hors verrou (file bornée), contrat de concurrence documenté sur chaque rappel. |
| C-10 | REF-109 | **Drapeau `exported` silencieusement réécrit** en pure fonction du scope au commit : le paramètre de constructeur est un piège (une valeur explicite est écrasée sans avertissement). | **Moyenne** | Rendre la dérivation explicite dans l'API (pas de paramètre trompeur). |
| C-11 | REF-081 | **Bump de version de format de cache incomplet** : le tampon de version ne namespasse que le cache partagé ; les buckets par module ne sont pas protégés — un changement d'outil peut réadmettre des buckets dexés par une version plus ancienne. | **Moyenne** | Inclure le format dans l'identité de **tous** les artefacts de cache. |
| C-12 | REF-033 | **Liens symboliques invisibles aux empreintes** (non suivis, non hachés) : remplacer la cible d'un lien dans une entrée de répertoire est invisible pour l'up-to-date. | **Faible** | Hacher les cibles ou interdire les symlinks dans les racines déclarées (diagnostic). |
| C-13 | REF-083 | **Manifestes de fusion dex écrits non atomiquement** (contrairement aux autres sidecars) : crash = re-fusion complète (auto-guérison coûteuse). | **Faible** | Généraliser temp + renommage atomique. |
| C-14 | REF-111 | **Chemins absolus persistés dans la table SDK** : restauration sur un autre appareil/répertoire casse la table ; la re-détection ne remplace pas une entrée existante de même nom. | **Faible** | Persister une identité (nom + type) et re-localiser à l'ouverture. |
| C-15 | REF-056 | **Redirection process-globale des flux standards sans verrou** : la sûreté repose sur un invariant (« les runs sont séquentiels ») garanti un étage au-dessus, pas dans le module. | **Faible** | Si nous exécutons du code : verrou d'exécution explicite dans le module concerné. |
| C-16 | REF-120 | **Bornes de résolution silencieuses** (nœuds max atteints sans diagnostic) ; scope de dépendance inconnu rendu visible partout par défaut. | **Faible** | Diagnostic aux bornes ; scope inconnu = erreur à l'ouverture. |
| C-17 | REF-067 | **Noms d'entrées de jars non sanitizés** : une entrée `../x` d'un jar malveillant transite jusqu'à l'APK. | **Faible** | Normalisation/refus des entrées relatives au moment de l'offre. |
| C-18 | REF-131 | **Empreinte de classpath de la compilation Kotlin par stat** (chemin+taille+mtime) : fenêtre étroite de faux up-to-date sur des sorties de modules sœurs réécrites. | **Faible** | Digest de contenu pour les entrées qui sont des sorties de tâches. |

## 4. Maintenabilité

| # | REF | Problème | Gravité | Correctif proposé |
|---|---|---|---|---|
| M-1 | REF-059, REF-104, REF-124 | **Divergences doc/code majeures** : la doc décrit un pipeline d'exécution dexé supprimé du code, une orchestration multi-projets inexistante, un LRU de cache jamais implémenté, une facette Java absente en production. Toute personne construisant sur la doc décrit des composants fantômes. | **Élevée** | Chez nous : la conception vit dans le même commit que le code qui l'implémente ; un document de conception non reflété par le code est un bug de documentation. |
| M-2 | REF-060 | **Fichiers monolithiques** (planificateur du pipeline Android de ~2053 lignes avec une fonction de ~800 lignes ; fichier de tâches de ~2734 lignes) : le graphe est construit impérativement avec des listes de dépendances recalculées à plusieurs endroits. | **Élevée** | Découpage par type de module (app/lib) et par famille de tâches ; structures de plan immuables. |
| M-3 | REF-034, REF-104, REF-105 | **API mortes publiées** : entrée de classpath jamais utilisée, politique de coordination et substitution sans consommateur — la surface publiée ment sur les capacités réelles. | **Moyenne** | Règle : pas d'API sans consommateur ni test dans notre dépôt. |
| M-4 | REF-154 | **Zones sans tests** : annulation en cours de build, corruption du magasin d'empreintes, concurrence réelle de commits du modèle, annulation de la résolution, replis d'écriture non atomiques. | **Moyenne** | Ces scénarios sont exigés par notre cahier des charges : couverts dès la Phase 4 (tests d'installation interrompue puis reprise, manifeste falsifié refusé, incompatibilité de versions, annulation, corruption de cache, concurrence). |
| M-5 | REF-128 | **Deux extracteurs d'archives dupliqués** (même logique sensible — zip-slip — maintenue deux fois, layouts distincts). | **Faible** | Un seul utilitaire partagé installateur/moteur. |
| M-6 | REF-012 | **Asymétrie d'API** : l'entrée « répertoires » n'existe que sur l'implémentation, pas dans l'interface publiée. | **Faible** | Interface unique complète dès la conception. |
| M-7 | REF-057 | **Couplage par noms exacts d'internals externes** (champs et descripteurs d'une bibliothèque tierce sondés par réflexion, échecs silencieux). | **Faible** | Chez nous : sondes versionnées avec journal d'échec explicite. |

## 5. Compatibilité Android

Ce que le fonctionnement du moteur de référence sur un appareil réel enseigne, et ce que cela
impose à notre architecture coquille + moteur téléchargeable :

1. **Exécution de binaires natifs (`aapt2`)**. Le moteur de référence embarque l'outil de
   ressources comme bibliothèque `.so` dans son propre APK et ne l'exécute que depuis
   `nativeLibraryDir` — « le seul endroit où ART permet `exec` » (REF-063). C'est la contrainte
   décisive pour nous : depuis Android 10 (API 29), la politique W^X de SELinux interdit à une
   application d'exécuter un fichier qu'elle a téléchargé dans son stockage accessible en
   écriture (`filesDir`, cache). **Un composant `aapt2` téléchargé puis installé dans le stockage
   privé ne peut donc pas être exécuté tel quel.** Options à trancher en Phase 3 :
   (a) livrer le composant `aapt2` comme paquet installable dont les `.so` atterrissent dans un
   `nativeLibraryDir` (APK séparé ou module dynamique — sous réserve des politiques de
   distribution, voir notre futur `docs/distribution.md`) ; (b) réimplémenter en Java pur la
   partie nécessaire de la chaîne de ressources (compile/link) — le moteur de référence démontre
   la viabilité de cette approche pour AIDL et le mergeur (REF-073, REF-064) ; (c) hybride :
   Java pur par défaut, binaire natif quand disponible. Notre `minSdk 28` ne change rien à cette
   contrainte (elle dépend du `targetSdk` et de l'API appareil, pas du `minSdk`).
2. **Chargement de code téléchargé (dex/jar)**. Le chargement dynamique depuis le stockage privé
   reste permis (c'est le mécanisme standard des plugins), avec des exigences : fichier
   **en lecture seule** au moment du chargement sur les versions récentes, jamais un fichier
   ouvert en écriture ; notre installateur posera donc les artefacts de code en lecture seule
   (et les vérifiera avant, S-1). Le code chargé vit dans le processus qui le charge — d'où
   l'exigence de notre cahier des charges de l'isoler dans un processus dédié.
3. **Processus séparé**. Le moteur de référence en est resté au design (REF-008) mais sa
   feuille de route confirme les invariants que nous devrons implémenter d'emblée : un service
   en premier plan avec notification (obligatoire pour un travail long en arrière-plan),
   la notification de mort du binder (un OOM du moteur remonte « le processus de build est mort
   de mémoire » et l'interface survit), les données volumineuses transitent par le système de
   fichiers partagé (même UID, même APK) et l'IPC ne porte que du contrôle et des deltas
   d'événements.
4. **Mémoire**. Sur ART, un processus applicatif est plafonné par `dalvik.vm.heapsize`
   (≈ 576 Mo mesurés sur l'appareil de référence), quel que soit le RAM physique ; le moteur de
   référence demande `largeHeap` et **forke des VM hors plafond** (avec un tas jusqu'à ~1,5 Go)
   pour les passes lourdes, avec un budget de fourches dérivé de la RAM et une porte de
   concurrence globale (REF-087, REF-088, REF-089). Notre moteur, déjà dans son propre
   processus, héritera de son propre plafond — la question du fork des passes lourdes (dex
   merge, R8) restera pertinente et sera tranchée en Phase 3.
5. **Stockage des projets**. Le moteur de référence loge les projets dans le stockage externe
   spécifique à l'application (visible par l'utilisateur), d'où sa politique de chargement
   tolérant aux pannes et son rechargement à la demande (REF-108). Le stockage scoped (API 29+)
   impose le même choix : projets visibles (SAF) + caches et état du moteur dans le stockage
   privé ; notre installateur écrira exclusivement dans le stockage privé de l'application.
6. **API absentes ou restreintes selon le niveau**. Le moteur de référence gère explicitement :
   `PackageInstaller` avec confirmation utilisateur obligatoire sous Android 12+ (REF-070) ;
   notification de service premier plan (API 26+) ; contournement d'un bug de JIT des versions
   Android ≤ 11 dans le compilateur forké (REF-133) ; signature v1 inutile au-delà d'un certain
   niveau d'API (REF-068). Notre `minSdk 28` élimine les plus anciennes de ces contraintes mais
   pas les plus récentes (confirmations d'installation, restrictions d'exécution, notification
   de service) — chaque appel récent sera vérifié contre l'API 28 lors de l'implémentation,
   conformément à nos contraintes techniques.

## 6. Comparaison avec ce que Gradle offre

Fonctionnalités du « concurrent » de référence (exécution sur poste de travail) et état du
moteur de référence embarqué — pour identifier ce qui vaut la peine d'être ajouté dans Forge :

| Fonctionnalité | Moteur de référence | Enjeu pour Forge |
|---|---|---|
| Vérification up-to-date par empreintes | Oui, par tâche, entrées + sorties live (REF-023) | Parité visée, avec magasin atomique et versionné (C-2). |
| Cache de build (sorties par clé d'entrées, partage inter-projets/variantes, restauration après clean) | **Non** — seuls les caches spécialisés dex existent (REF-081, REF-092) ; la doc annonce un étage générique qui n'existe pas | **Amélioration prioritaire** : cache de sorties générique, adressé par contenu, borné, avec restauration — c'est la fonctionnalité Gradle la plus rentable à portée d'un moteur embarqué. |
| Compile avoidance (ne pas recompiler les dépendants quand seule l'implémentation change) | Partiel — l'incrémental Java et Kotlin comparent des ABI (REF-035, REF-131, REF-132) | Parité visée ; corriger l'empreinte de classpath par contenu (C-6, C-18). |
| Cache de configuration (ne pas reconfigurer le graphe à chaque build) | Non — le graphe est reconstruit à chaque demande, en synchrone sur le thread appelant (REF-060) | Moyenne priorité : mémoïser la forme du graphe par empreinte du modèle (le nôtre est déclaratif, c'est plus simple). |
| Exécution parallèle inter-projets / inter-modules coordonnée | Non — l'API de coordination existe mais n'est pas branchée (REF-104) ; le parallélisme réel est intra-graphe de tâches, plafonné à 2 (REF-022) | Notre moteur exécutera la fermeture des modules par niveaux topologiques avec parallélisme calculé (P-3). |
| Surveillance de fichiers (invalidation continue) | Non — les tampons de synchro sont comparés à la demande (REF-016) | Basse priorité (coût batterie) ; point d'entrée prévu mais pas engagé. |
| Worker API (pools bornés par ressource) | Partiel — pools spécialisés dex avec dimensionnement mémoire (REF-089) | Généraliser le dimensionnement cœurs/mémoire à l'exécuteur de tâches. |
| Cache distant d'artefacts | Partiel — bibliothèques pré-dexées signées téléchargeables (REF-129) | Idée à garder pour notre distribution (composants optionnels), avec notre chaîne de signature. |
| Builds reproductibles | Partiel — zips déterministes (dates fixes) (REF-066) | Traçabilité de la chaîne d'outils (versions de composants dans la trace de build exportable). |
| Incrémentalité des ressources | Non — fusions intégrales (REF-061, P-1) | **Amélioration prioritaire** (le cas « material-you » : éditer une couleur ne doit re-traiter que cette ressource). |
| Diagnostics structurés exploitables par l'éditeur | **Oui, au-delà de Gradle** (REF-040, REF-053) | Parité — et traverseront notre frontière de processus via `api/`. |
| Reprise de téléchargement | Non (REF-127) | Exigence de notre cahier des charges (installateur). |
| Annulation fiable de bout en bout | Partiel — coopérative côté tâches, sous-processus orphelins (C-1) | **Exigence de notre cahier des charges** : annulation en cascade jusqu'aux sous-processus. |

## 7. Synthèse des priorités pour Forge

Par ordre de rentabilité (impact × faisabilité sur appareil), à valider en Phase 3 :

1. **Chaîne d'approvisionnement vérifiée** (S-1, S-2, S-5, P-4) : sommes de contrôle obligatoires,
   HTTPS strict, reprise — c'est le cœur de notre installateur, inexistant côté référence.
2. **Cache de sorties générique borné** (comparaison Gradle) + **quotas sur tous les caches**
   (P-2) : la plus grande fonctionnalité manquante face à Gradle.
3. **Fusion de ressources incrémentale** (P-1) : le plus grand goulot de performance du pipeline
   Android côté référence.
4. **Annulation de bout en bout** (C-1) et **écritures atomiques généralisées** (C-2, C-11,
   C-13) : exigences de notre cahier des charges, faiblesses réelles du moteur de référence.
5. **Parallélisme calculé** (P-3) et ordonnancement réel par niveaux de modules (REF-104) :
   gain direct sur les projets multi-modules.
6. **Conserver les points forts** constatés : diagnostics structurés « demander à l'outil »,
   vérification post-dex de complétude, caches dex à trois niveaux, repli OOM R8, registre des
   APK signés, chargement de modèle tolérant aux pannes, oracle up-to-date recalculant les
   sorties live.
