# tools/ — scripts et hooks

Scripts de packaging, de publication et de contrôle local du dépôt.

| Fichier | Rôle |
|---|---|
| `setup-local.sh` | Installation après clone : hooks git + fichier local des termes interdits. |
| `check-references.sh` | Contrôle des termes interdits sur tout le dépôt (CI ou usage local). |
| `hooks/pre-commit` | Hook git : refuse un commit dont les fichiers indexés contiennent un terme interdit (hors `docs/REFERENCES.md`). |
| `hooks/commit-msg` | Hook git : refuse un commit dont le message contient un terme interdit. |

La politique : les mentions du moteur de référence (nom, auteur, URL, chemins) sont interdites
dans tout le dépôt **sauf** dans `docs/REFERENCES.md`. La liste des termes interdits vit dans
`.git/info/forbidden-terms.txt` (non versionné) ou, en CI, dans le secret `FORBIDDEN_TERMS`.

Les scripts de packaging du moteur et de publication du manifeste signé arriveront en Phase 4,
étape 12.
