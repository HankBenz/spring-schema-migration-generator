#!/usr/bin/env bash
set -euo pipefail

: "${VERSION:?VERSION is required}"
: "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
attempt=1
while [ "$attempt" -le 4 ]; do
  bash "$script_dir/delete-maven-version.sh"
  if mvn -B deploy -DskipTests -DaltDeploymentRepository="github::https://maven.pkg.github.com/${GITHUB_REPOSITORY}"; then
    exit 0
  fi
  echo "Publishing ${VERSION} failed on attempt ${attempt}."
  if [ "$attempt" -eq 4 ]; then
    break
  fi
  sleep $((attempt * 20))
  attempt=$((attempt + 1))
done
echo "::error::Could not publish ${VERSION} after deleting the existing GitHub Packages version"
exit 1
