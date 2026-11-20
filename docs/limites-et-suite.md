# Limites et suite

Suivi des manques, hypothèses et travaux restants. Règle du projet : aucun TODO silencieux —
tout écart est consigné ici.

## État des phases

| Phase | État | Livrable |
|---|---|---|
| 0 — Exploration | **Fait** | Dépôt de référence cloné hors du dépôt de travail, SHA épinglé, licence vérifiée et consignée, hooks anti-référence en place. |
| 1 — Analyse | **Fait** | [`analyse-moteur-build.md`](analyse-moteur-build.md) (13 sections, cartographie avec REF-xxx). |
| 2 — Revue critique | **Fait** | [`revue-moteur-build.md`](revue-moteur-build.md) (4 axes, 46 constats numérotés, compatibilité Android, comparaison Gradle). |
| 3 — Conception | **Fait** (2026-11-20) | [`conception.md`](conception.md) (12 décisions D-01..D-12 justifiées, registre des améliorations priorisées, ordre d'implémentation) et [`distribution.md`](distribution.md) (Play exclu, GitHub Releases + F-Droid, clés, licences, guidance appareil). |
| 4 — Implémentation | **En cours** | Étapes 1-2 amorcées (squelette Gradle multi-modules, module `api` + moteur factice) ; suivi détaillé ci-dessous. |

## Hypothèses retenues (Phases 0-2)

1. **Portée de la session** : Phases 0 à 2 seulement (décision du 2026-11-20) ; la conception
   attend la validation de l'analyse par le propriétaire du projet.
2. **Squelette sans projets Gradle** : les répertoires `app/`, `api/`, `setup/`, `engine/`
   contiennent leur charte (README) mais pas encore de build Gradle — la CI a une étape de
   tests conditionnelle qui s'activera avec l'étape 1 de la Phase 4 (décision du 2026-11-20).
3. **Répertoire de travail du moteur de référence** : cloné hors du dépôt (répertoire local de
   l'environnement de travail), jamais en sous-module, jamais copié.
4. **Vérification d'acyclicité** : faite par script sur les dépendances de production déclarées
   dans les fichiers de build du dépôt de référence (84 modules, 259 arêtes, 0 cycle) ; les
   cycles passant par le harnais de test ont été exclus explicitement (documentés comme bénins
   par le dépôt lui-même). Limite : le script lit les déclarations `project(...)` des
   configurations de production connues ; une configuration custom échapperait à l'analyse.

## Manques et limites constatés (Phases 0-2)

- **Aucun code de production** : pas encore de module Gradle, pas de test, pas de démonstration
  exécutable — attendu à la Phase 4.
- **`docs/distribution.md` non écrit** : la question des politiques de magasin (téléchargement
  de code exécutable) est seulement amorcée dans la revue (section Compatibilité Android) ; le
  document complet arrive en Phase 3.
- **Licence de Forge non choisie** : le moteur de référence est GPL-3.0-or-lister avec exception
  de linkage sur ses modules SPI (voir `REFERENCES.md`). Notre réimplémentation est indépendante
  (aucun code copié, noms distincts), mais le choix de notre licence (permissive, copyleft,
  exception pour `api/` ?) appartient au propriétaire du projet. **Décision requise avant toute
  publication.**
- **Secret `FORBIDDEN_TERMS` de la CI** : défini sur le dépôt GitHub (2026-11-20) — la CI
  applique le même contrôle que les hooks locaux à chaque push.
- **CI bloquée par la facturation du compte GitHub** : les jobs Actions ne démarrent pas
  (« The job was not started because recent account payments have failed or your spending
  limit needs to be increased »). Le workflow est valide (vérifié : YAML conforme, les deux
  jobs sont créés) et toutes ses étapes ont été validées localement (contrôle des termes avec
  la valeur exacte du secret, garde de structure, syntaxe des scripts). **Action requise côté
  compte** : régler un moyen de paiement ou augmenter le plafond de dépense dans
  *Settings → Billing & plans* (un dépôt privé consomme des minutes Actions ; un dépôt public
  en bénéficierait gratuitement).
- **Vérifications sur appareil réel** : rien n'a encore été vérifié sur ART (aucun code
  exécutable) ; les constats « sur appareil » cités dans l'analyse proviennent du code et des
  commentaires du moteur de référence, pas de nos propres mesures.

## Prochaines étapes (ordre proposé)

1. Valider l'analyse et la revue (retours sur `analyse-moteur-build.md` et
   `revue-moteur-build.md`).
2. Phase 3 : rédiger `conception.md` (trancher : mécanisme de communication application ↔
   moteur, exécution des binaires natifs téléchargés — voir revue §5.1, découpage des composants
   du manifeste, améliorations retenues) puis `distribution.md`.
3. Phase 4, étapes 1-2 : squelette Gradle multi-modules + module `api` avec moteur factice.
4. Définir le secret `FORBIDDEN_TERMS` sur le dépôt GitHub et la licence.
