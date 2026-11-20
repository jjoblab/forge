# Module `json/`

Lecture et écriture JSON **minimales et strictes**, sans aucune dépendance.

## Rôle

Un seul parseur/écriteur JSON pour tout le dépôt, volontairement restreint (≈ 400 lignes
testées) : maîtrise du comportement sur ART, surface d'audit réduite. Il ne sert jamais sur
le canal de contrôle application ↔ moteur (trames binaires, décision D-07), uniquement pour
des fichiers :

- **de confiance vérifiés** : manifeste signé des composants (module `setup/`) ;
- **d'état interne** : état installé (`setup/`), empreintes annexes, trace de build ;
- **descriptifs de projet** : `forge.json` (module `engine/`).

Historique : né dans `setup/` (Phases 0-3), extrait dans son propre module à l'étape 8 de
la Phase 4 quand le moteur a eu besoin de lire `forge.json` — éviter de dupliquer le
parseur ou de faire dépendre le moteur de l'installateur.

## Contenu

| Package | Rôle |
|---|---|
| `jo.forge.json` | `Json` (lecteur strict : objets ordonnés, tableaux, scalaires ; rejets : profondeur > 64, échappements inconnus, contenu résiduel), `JsonException`. |

## Tests

`json/src/test/` : round-trip, rejets des entrées malformées, limites de profondeur.
