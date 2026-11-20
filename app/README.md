# app/ — la coquille

L'application Android hôte de Forge. Elle fournit :

- l'interface utilisateur (projets, fichiers, console de build, diagnostics) ;
- la gestion des projets et de l'espace de travail ;
- l'assistant d'installation du moteur (premier lancement) ;
- le client du moteur (lancement d'un build, suivi de progression, flux de journaux et de
  diagnostics, annulation) ;
- la mise à jour, la réparation et la désinstallation du moteur.

**La coquille ne contient aucune logique de build** : ni compilateur, ni dexeur, ni signature, ni
binaire d'outil. Tout le savoir-faire de build vit dans le moteur téléchargeable (`engine/`),
accessible uniquement à travers le contrat `api/`.

Tant que le moteur n'est pas installé, les fonctions de build sont désactivées et un message clair
l'explique à l'utilisateur.

Ce module sera un projet Gradle Android (`minSdk 28`, Java 17) créé en Phase 4, étape 1.
