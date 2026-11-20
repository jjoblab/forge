# engine/ — le moteur de build

Le cœur de Forge, distribué comme **artefact téléchargeable** et installé dans le stockage privé de
l'application. Il s'exécute dans un processus séparé de l'interface.

## Contenu (état après l'étape 8 de la Phase 4)

| Package | Rôle | État |
|---|---|---|
| `jo.forge.engine.bootstrap` | `EngineMain` (point d'entrée de la VM moteur), moteur factice des tests de contrat. | Livré (étapes 2, 8) |
| `jo.forge.engine.build` | `BuildEngineServer` : le vrai serveur — modèle → pipeline → graphe → exécuteur, événements et diagnostics en trames. | Livré (étape 8) |
| `jo.forge.engine.model` | Modèle déclaratif `forge.json` (modules, types, portées, facette Android) et son codec strict. | Livré (étape 8) |
| `jo.forge.engine.pipeline` | `ProjectLayout` (disposition canonique build/ et .forge/). | Livré (étape 8) |
| `jo.forge.engine.pipeline.java` | `EcjCompiler` (ECJ en processus, diagnostics par l'API), `CompileJavaTask`, `JarTask` (jar déterministe), `JavaPipeline` (modèle → tâches). | Livré (étape 8) |
| `jo.forge.engine.pipeline.android` | Ressources, `aapt2`, dex, packaging, signature. | Étape 10 |
| `jo.forge.engine.deps` | Résolution Maven (POM/BOM, médiation, cache, intégrité). | Étape 9 |
| `jo.forge.engine.graph` / `exec` | Graphe de tâches (niveaux, cycles) et exécuteur borné annulable. | Livrés (étapes 5-6) |
| `jo.forge.engine.state` | Empreintes par contenu et magasin persistant atomique. | Livré (étape 7) |
| `jo.forge.engine.diag` | Diagnostics structurés (erreur localisée, code, outil) et puits. | Livré (étape 8) |

Dépendance embarquée : **ECJ** (compilateur Java, EPL-2.0 — voir
`docs/distribution.md` §7).

## Règles transversales

- Java 17 uniquement, équivalents Java des mécanismes concurrentiels (`ExecutorService`,
  `CompletableFuture`, jetons d'annulation) ;
- testé sur JVM (JDK 17) et conçu pour ART ; tout ce qui n'a pas pu être vérifié sur appareil
  réel est consigné dans `docs/limites-et-suite.md` ;
- sobriété mémoire (appareils à 3-4 Go de RAM) : flux plutôt que chargements complets, caches
  bornés ;
- aucune écriture non atomique de l'état persistant, aucun fichier temporaire oublié, aucun
  processus orphelin ;
- javadoc en français pour chaque classe et méthode publique.
