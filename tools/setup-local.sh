#!/usr/bin/env bash
# Mise en place locale du dépôt après un clone :
#   1. installe les hooks git (core.hooksPath = tools/hooks) ;
#   2. crée .git/info/forbidden-terms.txt s'il n'existe pas (À PEUPLER MANUELLEMENT) ;
#   3. rappelle la configuration git attendue (user.name / user.email).
# Les termes interdits ne sont volontairement PAS écrits ici : ils n'ont le droit
# d'apparaître dans aucun fichier du dépôt.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

git config core.hooksPath tools/hooks
echo "Hooks installés (core.hooksPath = tools/hooks) : pre-commit, commit-msg."

TERMS_FILE="$(git rev-parse --git-dir)/info/forbidden-terms.txt"
if [ ! -f "$TERMS_FILE" ]; then
  cat > "$TERMS_FILE" <<'EOF'
# Termes interdits dans ce dépôt (insensible à la casse), une expression par ligne.
# Politique : les mentions du moteur de référence (nom, auteur, URL, chemins) sont
# interdites partout sauf dans docs/REFERENCES.md. Ce fichier est NON versionné pour
# que ces termes n'apparaissent eux-mêmes dans aucun fichier du dépôt.
# PEUPLEZ cette liste avec les expressions concernées, puis vérifiez avec :
#   ./tools/check-references.sh
EOF
  echo "Fichier créé : $TERMS_FILE — À PEUPLER MANUELLEMENT (voir l'en-tête)."
else
  echo "Fichier déjà présent : $TERMS_FILE"
fi

if [ -z "$(git config user.name)" ] || [ -z "$(git config user.email)" ]; then
  echo "Attention : user.name / user.email ne sont pas configurés." >&2
  echo "  git config user.name  <votre nom>" >&2
  echo "  git config user.email <votre courriel>" >&2
fi

echo "Terminé. Vérification du dépôt : ./tools/check-references.sh"
