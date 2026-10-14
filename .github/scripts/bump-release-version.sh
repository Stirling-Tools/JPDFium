#!/usr/bin/env bash
#
# Update every documented version reference to a released version.
#
# Usage:
#   .github/scripts/bump-release-version.sh --version 1.2.1 [--dry-run]
#
# The current version is documented in exactly one place, docs/installation.md,
# and is read from there. Every occurrence of it in the files below is replaced,
# so a release does not leave the README, the installation guide, the deployment
# guide, or the example project pointing at the previous version. Version-pinned
# javadoc.io links are updated wherever they appear.
#
# The workflow .github/workflows/version-bump.yml runs this after a v*.*.* tag
# is pushed and opens a pull request with the result. gradle.properties is
# moved to the next patch snapshot in the same run.
set -euo pipefail

VERSION=""
DRY_RUN=0

while [ $# -gt 0 ]; do
  case "$1" in
    --version)
      VERSION="${2:-}"
      shift 2
      ;;
    --version=*)
      VERSION="${1#--version=}"
      shift
      ;;
    --dry-run)
      DRY_RUN=1
      shift
      ;;
    -h | --help)
      sed -n '2,15p' "$0"
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

if [ -z "$VERSION" ]; then
  echo "ERROR: --version is required, for example --version 1.2.1" >&2
  exit 2
fi

if ! printf '%s' "$VERSION" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$'; then
  echo "ERROR: '$VERSION' is not a release version (expected 1.2.1 or 1.2.1-rc.1)" >&2
  exit 2
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

INSTALL="docs/installation.md"
GRADLE_PROPERTIES="gradle.properties"
CURRENT="$(grep -F -m1 'current release is' "$INSTALL" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?' | head -n1 || true)"

if [ -z "$CURRENT" ]; then
  echo "ERROR: could not read the current version from $INSTALL" >&2
  exit 1
fi

if [ "$CURRENT" = "$VERSION" ]; then
  echo "$INSTALL already documents $VERSION; nothing to update."
  exit 0
fi

# Return 0 when the first version precedes the second. A prerelease precedes
# the stable release it leads up to (1.2.1-rc.1 precedes 1.2.1), which plain
# sort -V orders the other way round, so the numeric cores are compared first.
version_precedes() {
  local left="$1" right="$2"
  local left_core="${left%%-*}" right_core="${right%%-*}"
  if [ "$left_core" != "$right_core" ]; then
    [ "$(printf '%s\n%s\n' "$left_core" "$right_core" | sort -V | head -n1)" = "$left_core" ]
    return
  fi
  local left_pre="" right_pre=""
  case "$left" in *-*) left_pre="${left#*-}" ;; esac
  case "$right" in *-*) right_pre="${right#*-}" ;; esac
  if [ -z "$left_pre" ] || [ -z "$right_pre" ]; then
    # Same numeric core: only a prerelease precedes its own stable release.
    [ -n "$left_pre" ] && [ -z "$right_pre" ]
    return
  fi
  [ "$(printf '%s\n%s\n' "$left_pre" "$right_pre" | sort -V | head -n1)" = "$left_pre" ]
}

# Reject a downgrade, so a late tag for an older release cannot rewrite the
# documentation backwards. Promoting a prerelease to its stable release is not
# a downgrade, which the cores-equal branch above allows.
if version_precedes "$VERSION" "$CURRENT"; then
  echo "ERROR: $VERSION is older than the documented release $CURRENT" >&2
  exit 2
fi

FILES=(
  "README.md"
  "$INSTALL"
  "docs/deployment.md"
  "examples/render-first-page/build.gradle.kts"
)

# Version-pinned javadoc.io links may end at the version or continue with a
# path, so the version is matched with or without a trailing slash. The
# boundary keeps a version that only prefixes another one, such as 1.2.0 inside
# 1.2.0-rc.1, from matching.
CURRENT_RE="${CURRENT//./\\.}"
while IFS= read -r pinned; do
  [ -n "$pinned" ] || continue
  FILES+=("${pinned#./}")
done < <(grep -rlE "javadoc\\.io/(doc|static)/com\\.stirling/jpdfium/$CURRENT_RE([^0-9A-Za-z.-]|$)" \
  --exclude-dir=.git --exclude-dir=build --exclude-dir=.gradle . || true)

SORTED_FILES=()
while IFS= read -r file; do
  SORTED_FILES+=("$file")
done < <(printf '%s\n' "${FILES[@]}" | sort -u)
FILES=("${SORTED_FILES[@]}")

echo "Updating $CURRENT -> $VERSION in ${#FILES[@]} file(s)"

if [ "$DRY_RUN" -eq 1 ]; then
  grep -nF "$CURRENT" "${FILES[@]}" || true
  echo "Dry run: no files were changed."
  exit 0
fi

for file in "${FILES[@]}"; do
  OLD="$CURRENT" NEW="$VERSION" perl -pi -e '
    next unless m{com\.stirling|jpdfium|current release|javadoc\.io|^\s*<version>};
    s/(?<![0-9])\Q$ENV{OLD}\E(?![0-9A-Za-z-])(?!\.[0-9])/$ENV{NEW}/g;
  ' "$file"
done

if grep -nE "(^|[^0-9])${CURRENT_RE}([^0-9A-Za-z-]|$)" "${FILES[@]}"; then
  echo "ERROR: $CURRENT is still referenced above" >&2
  exit 1
fi

# gradle.properties holds the development version, which moves to the next
# patch snapshot once a release is tagged, so branch builds never advertise a
# version that has already shipped. A prerelease tag does not release anything
# yet, so the development version stays on that release. It is not part of FILES
# above: the released version legitimately stays in that file's history.
CORE="${VERSION%%-*}"
IFS='.' read -r CORE_MAJOR CORE_MINOR CORE_PATCH <<<"$CORE"
if [ "$CORE" != "$VERSION" ]; then
  DEV_VERSION="$CORE-SNAPSHOT"
else
  DEV_VERSION="$CORE_MAJOR.$CORE_MINOR.$((CORE_PATCH + 1))-SNAPSHOT"
fi
DEV_VERSION="$DEV_VERSION" perl -pi -e 's/^jpdfium\.version=.*$/jpdfium.version=$ENV{DEV_VERSION}/' "$GRADLE_PROPERTIES"
echo "$GRADLE_PROPERTIES: development version is $DEV_VERSION"

for file in "${FILES[@]}"; do
  printf '%s: %s reference(s)\n' "$file" "$(grep -cF "$VERSION" "$file")"
done
