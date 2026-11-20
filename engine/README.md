# engine/ — le moteur de build

Le cœur de Forge, distribué comme **artefact téléchargeable** et installé dans le stockage privé de
l'application. Il s'exécute dans un processus séparé de l'interface.

Contenu prévu (découpage affiné en Phase 3) :

- le **cœur** : graphe de tâches, empreintes (up-to-date), magasin persistant, exécuteur à
  parallélisme borné, annulation coopérative, diagnostics structurés ;
- le **pipeline Java** : compilation (compilateur Java embarqué), classpath, packaging jar ;
- le **pipeline Android** : ressources, `aapt2`, compilation, dex et désugarage, multidex, caches
  de dex des bibliothèques, packaging, signature, installation, fusion de manifestes, AAR ;
- la **résolution de dépendances** Maven (POM/BOM, conflits, cache, mode hors ligne, intégrité) ;
- la **compilation Kotlin** en option.

Règles transversales :

- Java 17 uniquement, équivalents Java des mécanismes concurrentiels (`ExecutorService`,
  `CompletableFuture`, jetons d'annulation) ;
- testé sur JVM (JDK 17) et conçu pour ART ; tout ce qui n'a pas pu être vérifié sur appareil
  réel est consigné dans `docs/limites-et-suite.md` ;
- sobriété mémoire (appareils à 3-4 Go de RAM) : flux plutôt que chargements complets, caches
  bornés ;
- aucune écriture non atomique de l'état persistant, aucun fichier temporaire oublié, aucun
  processus orphelin ;
- javadoc en français pour chaque classe et méthode publique.

Ce module sera créé en Phase 4 (étapes 5 à 10) à partir de la conception de la Phase 3.
