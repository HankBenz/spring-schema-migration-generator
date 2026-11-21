#!/usr/bin/env bash
set -euo pipefail

: "${VERSION:?VERSION is required}"
: "${GITHUB_TOKEN:?GITHUB_TOKEN is required}"
: "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"
: "${GITHUB_REPOSITORY_OWNER:?GITHUB_REPOSITORY_OWNER is required}"

owner="${GITHUB_REPOSITORY_OWNER}"
owner_type="$(gh api "users/${owner}" --jq .type)"
if [ "$owner_type" = "Organization" ]; then
  api_root="orgs/${owner}"
else
  api_root="users/${owner}"
fi

read -r group artifact < <(python3 - <<'PY'
import xml.etree.ElementTree as ET
ns = {"m": "http://maven.apache.org/POM/4.0.0"}
root = ET.parse("pom.xml").getroot()
group = root.findtext("m:groupId", default="", namespaces=ns)
artifact = root.findtext("m:artifactId", default="", namespaces=ns)
print(f"{group} {artifact}")
PY
)
if [ -z "$group" ] || [ -z "$artifact" ]; then
  echo "::error::Could not read Maven coordinates from pom.xml"
  exit 1
fi

if ! listed="$(gh api --paginate "/${api_root}/packages?package_type=maven" \
  --jq ".[] | select(.repository.full_name==\"${GITHUB_REPOSITORY}\" or .name==\"${artifact}\" or .name==\"${group}.${artifact}\" or .name==\"${group}/${artifact}\") | .name")"; then
  echo "Could not list Maven packages under ${api_root}; trying this project's coordinates."
  listed=""
fi

names=()
while IFS= read -r package_name; do
  [ -n "$package_name" ] || continue
  names+=("$package_name")
done < <(
  {
    printf '%s\n' "$listed"
    printf '%s\n' "$artifact" "${group}.${artifact}" "${group}/${artifact}"
  } | awk 'NF && !seen[$0]++'
)

body="$(mktemp)"
trap 'rm -f "$body"' EXIT
deleted=0
for package_name in "${names[@]}"; do
  encoded="$(printf '%s' "$package_name" | jq -sRr @uri)"
  page=1
  while [ "$page" -le 20 ]; do
    echo "Looking for ${VERSION} in ${api_root}/packages/maven/${encoded}"
    code="$(curl -sS -o "$body" -w '%{http_code}' \
      -H "Authorization: Bearer ${GITHUB_TOKEN}" \
      -H "Accept: application/vnd.github+json" \
      -H "X-GitHub-Api-Version: 2022-11-28" \
      "https://api.github.com/${api_root}/packages/maven/${encoded}/versions?per_page=100&page=${page}")"
    if [ "$code" = "404" ]; then
      break
    fi
    if [ "$code" != "200" ]; then
      echo "::error::Listing ${package_name} versions returned HTTP ${code}"
      cat "$body"
      exit 1
    fi
    if [ "$(jq 'length' "$body")" = "0" ]; then
      break
    fi
    ids="$(jq -r --arg version "$VERSION" '.[] | select(.name==$version) | .id' "$body")"
    for id in $ids; do
      case "$id" in
        ''|*[!0-9]*) continue ;;
      esac
      echo "Deleting Maven package ${package_name} version ${VERSION} (id ${id})"
      del="$(curl -sS -o "$body" -w '%{http_code}' -X DELETE \
        -H "Authorization: Bearer ${GITHUB_TOKEN}" \
        -H "Accept: application/vnd.github+json" \
        -H "X-GitHub-Api-Version: 2022-11-28" \
        "https://api.github.com/${api_root}/packages/maven/${encoded}/versions/${id}")"
      if [ "$del" = "204" ]; then
        deleted=$((deleted + 1))
      elif [ "$del" = "400" ] && jq -e '.message | test("delete the package instead")' "$body" >/dev/null; then
        echo "Version ${VERSION} is the only version of ${package_name}; deleting the package"
        del="$(curl -sS -o "$body" -w '%{http_code}' -X DELETE \
          -H "Authorization: Bearer ${GITHUB_TOKEN}" \
          -H "Accept: application/vnd.github+json" \
          -H "X-GitHub-Api-Version: 2022-11-28" \
          "https://api.github.com/${api_root}/packages/maven/${encoded}")"
        if [ "$del" != "204" ] && [ "$del" != "404" ]; then
          echo "::error::Deleting package ${package_name} returned HTTP ${del}"
          cat "$body"
          exit 1
        fi
        deleted=$((deleted + 1))
        break 2
      elif [ "$del" != "404" ]; then
        echo "::error::Deleting ${package_name} version ${id} returned HTTP ${del}"
        cat "$body"
        exit 1
      fi
    done
    page=$((page + 1))
  done
done
echo "Removed ${deleted} GitHub Packages version(s) named ${VERSION}"
