#!/usr/bin/env bash
set -euo pipefail

branch="${GITHUB_REF_NAME//\//_}"
branch="$(printf '%s' "$branch" | sed -E 's/[^A-Za-z0-9_-]+/_/g; s/^_+//; s/_+$//')"
if [ -z "$branch" ]; then
  echo "::error::Branch name cannot be used as a version"
  exit 1
fi

version="${branch}-$(date -u +%y%m%d)"
if [ -n "${GITHUB_OUTPUT:-}" ]; then
  echo "version=$version" >> "$GITHUB_OUTPUT"
fi
echo "Release version $version"
