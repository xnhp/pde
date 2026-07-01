#!/usr/bin/env bash
set -euo pipefail

BASE_REF=${1:-}

if [[ -z "${BASE_REF}" ]]; then
  if [[ -n "${GITHUB_BASE_REF:-}" ]]; then
    BASE_REF="origin/${GITHUB_BASE_REF}"
  else
    BASE_REF="origin/knime"
  fi
fi

if ! git rev-parse --verify --quiet "$BASE_REF" >/dev/null; then
  git fetch origin "${BASE_REF#origin/}:${BASE_REF}"
fi

COMMITS=$(git rev-list "$BASE_REF"..HEAD)

if [[ -z "$COMMITS" ]]; then
  echo "No commits to lint"
  exit 0
fi

REGEX='^(build|chore|ci|docs|feat|fix|perf|refactor|revert|style|test|gradle)(\([A-Za-z0-9._/-]+\))?(!)?: .+'
STATUS=0

while IFS= read -r SHA; do
  SUBJECT=$(git show -s --format=%s "$SHA")
  if [[ ! "$SUBJECT" =~ $REGEX ]]; then
    echo "::error ::Commit $SHA uses non-conventional subject '$SUBJECT'"
    STATUS=1
  fi
done <<<"$COMMITS"

exit $STATUS
