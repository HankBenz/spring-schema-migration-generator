#!/usr/bin/env bash
set -euo pipefail

: "${VERSION:?VERSION is required}"
: "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"

if gh release view "$VERSION" >/dev/null 2>&1; then
  gh release delete "$VERSION" --yes --cleanup-tag
fi
gh api --method DELETE "/repos/${GITHUB_REPOSITORY}/git/refs/tags/${VERSION}" >/dev/null 2>&1 || true
