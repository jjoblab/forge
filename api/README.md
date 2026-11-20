# api/ — le contrat application ↔ moteur

Module minuscule, stable et versionné : la **seule** chose que l'application connaît du moteur.

Contenu prévu :

- le protocole de négociation de compatibilité (version de protocole application ↔ moteur, refus
  propre en cas d'incompatibilité) ;
- les types de requêtes et d'événements échangés : lancer un build, suivre la progression, recevoir
  journaux et diagnostics structurés en flux, annuler, interroger l'état et la version du moteur ;
- les DTO sérialisables du canal de communication.

Règles :

- aucun type interne du moteur ne doit fuiter dans ce module ;
- évolution par version de protocole, jamais par mutation silencieuse du contrat ;
- consommable par la coquille (`app/`) et par le moteur (`engine/`), sans les coupler l'un à
  l'autre.

Ce module sera un projet Gradle Java 17 pur (JVM) créé en Phase 4, étape 2, avec le moteur factice
de validation (ping, version, événements).
