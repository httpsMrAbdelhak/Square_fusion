#!/usr/bin/env bash
# Prints the release notes for the build of commit $2, given $1, the commit
# of the previous release (may be empty).
#
# .github/release-notes.md describes one version. It is used only when it was
# edited since the previous release; otherwise it describes an older version,
# and the notes are the subjects of the commits since that release instead, so
# a build never ships the notes of another one.
set -euo pipefail
prev="${1:-}"
sha="$2"
file=.github/release-notes.md

if [ -z "$prev" ] || ! git cat-file -e "$prev^{commit}" 2>/dev/null; then
  cat "$file"
  exit 0
fi
if ! git diff --quiet "$prev" "$sha" -- "$file"; then
  cat "$file"
  exit 0
fi
changes=$(git log --no-merges --format='- %s' "$prev..$sha")
if [ -z "$changes" ]; then
  echo "Nouvelle compilation, sans changement par rapport à la version précédente."
else
  printf '## Changements\n\n%s\n' "$changes"
fi
