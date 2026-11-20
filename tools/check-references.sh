#!/usr/bin/env bash
# Contrôle des termes interdits sur l'ensemble du dépôt.
# Usage local  : ./tools/check-references.sh
# Usage CI     : FORBIDDEN_TERMS=$'terme1\nterme2' ./tools/check-references.sh
# Sources des termes (dans l'ordre) :
#   1. la variable d'environnement FORBIDDEN_TERMS (une expression par ligne) ;
#   2. le fichier .git/info/forbidden-terms.txt ;
#   3. sinon : contrôle ignoré avec avertissement (aucune liste disponible).
# Exception : docs/REFERENCES.md est le seul fichier autorisé à contenir ces termes.
set -uo pipefail

cd "$(git rev-parse --show-toplevel 2>/dev/null || pwd)"

if [ -n "${FORBIDDEN_TERMS:-}" ]; then
  TERMS_SRC="variable FORBIDDEN_TERMS"
  TERMS_RAW="$FORBIDDEN_TERMS"
elif [ -f .git/info/forbidden-terms.txt ]; then
  TERMS_SRC=".git/info/forbidden-terms.txt"
  TERMS_RAW="$(cat .git/info/forbidden-terms.txt)"
else
  echo "check-references : aucune liste de termes fournie (FORBIDDEN_TERMS ou"
  echo "  .git/info/forbidden-terms.txt). Contrôle ignoré."
  exit 0
fi

mapfile -t TERMS < <(printf '%s\n' "$TERMS_RAW" | grep -vE '^\s*(#|$)' || true)
if [ "${#TERMS[@]}" -eq 0 ]; then
  echo "check-references : liste vide ($TERMS_SRC). Contrôle ignoré."
  exit 0
fi
echo "check-references : ${#TERMS[@]} terme(s) interdit(s), source : $TERMS_SRC"

failed=0
# Fichiers suivis uniquement (les fichiers ignorés par .gitignore sont hors du dépôt).
while IFS= read -r file; do
  [ -z "$file" ] && continue
  case "$file" in
    docs/REFERENCES.md) continue ;;
  esac
  [ -f "$file" ] || continue
  for term in "${TERMS[@]}"; do
    if grep -Finq -- "$term" "$file"; then
      echo "  TERME INTERDIT « ${term} » dans ${file} :" >&2
      grep -Fin -- "$term" "$file" | head -3 | sed 's/^/      /' >&2
      failed=1
    fi
  done
done < <(git ls-files)

# Messages de commit de toutes les branches locales.
if git rev-parse HEAD >/dev/null 2>&1; then
  for term in "${TERMS[@]}"; do
    if git log --all --format='%s%n%b' | grep -Finq -- "$term"; then
      echo "  TERME INTERDIT « ${term} » dans l'historique des messages de commit" >&2
      failed=1
    fi
  done
fi

if [ "$failed" -ne 0 ]; then
  echo "ÉCHEC : termes interdits présents dans le dépôt (exception : docs/REFERENCES.md)." >&2
  exit 1
fi
echo "check-references : OK, aucun terme interdit hors docs/REFERENCES.md."
