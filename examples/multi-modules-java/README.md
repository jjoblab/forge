# Exemple multi-modules Java

Projet minimal à deux modules pour le moteur Forge :

- `core` — bibliothèque Java (utilitaires de texte, une ressource) ;
- `app` — bibliothèque Java dépendant de `core` en portée `api`.

## Construire avec le moteur

Depuis une coquille Forge installée : pointer un build sur ce répertoire (le moteur y
lit `forge.json`). En test JVM, ce projet sert de support au benchmark du moteur
(build complet puis incrémental — voir `engine/src/test/.../benchmark/`).

## Structure produite par le build

```
build/<module>/classes/    classes compilées
build/<module>/libs/<module>.jar   artefact déterministe
.forge/state/              empreintes (incrémentalité)
```
