#!/usr/bin/env bash
#
# Update every documented version reference to a released version.
#
# Usage:
#   .github/scripts/bump-release-version.sh --version 1.2.1 [--dry-run]
#
# The accepted format is MAJOR.MINOR.PATCH with an optional prerelease suffix,
# such as 1.2.1 or 1.2.1-rc.1; the suffix may contain hyphens, as in 1.3.0-rc-1.
# Build metadata and other formats are rejected: this is deliberately not a
# general Semantic Versioning implementation.
#
# The version to release is passed in. Every target is then reconciled on its
# own: a version reference in one of the fields this script owns and precedes
# the released version is rewritten to it. The fields are a
# "com.stirling:jpdfium" Gradle coordinate, a Maven <version> inside a
# com.stirling dependency block, the "current release is" sentence, and a
# version-pinned javadoc.io link. A version anywhere else, such as an unrelated
# dependency that merely shares a line with a JPDFium reference, is left alone.
# A run therefore repairs the whole set even when the installation guide was
# already updated by hand, or by an earlier run that stopped halfway, instead of
# trusting that one file and skipping the rest. No target documents an older
# release on purpose; a version that is deliberately historical lives in
# CHANGELOG.md, which is not a target.
#
# The workflow .github/workflows/version-bump.yml runs this after a v*.*.* tag
# is pushed and opens a pull request with the result. gradle.properties is
# moved to the next patch snapshot in the same run, and never backwards.
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
      sed -n '2,/^set -euo pipefail$/p' "$0" | sed '$d'
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

# The version spellings this script accepts and understands: MAJOR.MINOR.PATCH
# with an optional prerelease suffix that may itself contain hyphens. One
# definition serves the format gate, extraction, replacement boundaries and
# counting, so every spelling that is accepted behaves the same everywhere.
VERSION_TOKEN='[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?'

if ! printf '%s' "$VERSION" | grep -Eq "^${VERSION_TOKEN}\$"; then
  echo "ERROR: '$VERSION' is not a release version (expected 1.2.1 or 1.2.1-rc.1)" >&2
  exit 2
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

INSTALL="docs/installation.md"
GRADLE_PROPERTIES="gradle.properties"

# The development version is written into gradle.properties below, so a checkout
# whose property line disappeared must fail here instead of reporting a
# reconciliation that cannot have happened.
if ! grep -qE '^jpdfium\.version=' "$GRADLE_PROPERTIES"; then
  echo "ERROR: $GRADLE_PROPERTIES has no jpdfium.version line" >&2
  exit 1
fi
CURRENT="$(grep -F -m1 'current release is' "$INSTALL" | grep -oE "$VERSION_TOKEN" | head -n1 || true)"

if [ -z "$CURRENT" ]; then
  echo "ERROR: could not read the current version from $INSTALL" >&2
  exit 1
fi

# Return 0 when the first version precedes the second. A prerelease precedes
# the stable release it leads up to (1.2.1-rc.1 precedes 1.2.1), which plain
# sort -V orders the other way round, so the numeric cores are compared first.
version_precedes() {
  local left="$1" right="$2"
  # A version does not precede itself, which the prerelease branch below
  # would otherwise report for equal prereleases such as 1.3.0-rc-1.
  if [ "$left" = "$right" ]; then
    return 1
  fi
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

# Version-pinned javadoc.io links are updated wherever they appear, so every
# file outside the workflow scripts that carries one is added to the targets,
# whatever version it currently pins. `.github` is skipped because the scripts
# and workflows there mention a link as an example, not as a released version
# reference, and must not be rewritten by a release.
while IFS= read -r pinned; do
  [ -n "$pinned" ] || continue
  FILES+=("${pinned#./}")
done < <(grep -rlE "javadoc\\.io/(doc|static)/com\\.stirling/jpdfium/" \
  --exclude-dir=.git --exclude-dir=.github --exclude-dir=build --exclude-dir=.gradle . || true)

SORTED_FILES=()
while IFS= read -r file; do
  SORTED_FILES+=("$file")
done < <(printf '%s\n' "${FILES[@]}" | sort -u)
FILES=("${SORTED_FILES[@]}")

# Every version this script owns in one file, one per line and without
# duplicates. Only the four owned fields are read: a com.stirling Gradle
# coordinate, a Maven <version> inside a com.stirling dependency block, the
# "current release is" sentence, and a version-pinned javadoc.io link. A version
# in any other context, such as an unrelated dependency that shares a line with
# a JPDFium reference, is not read and is never rewritten.
owned_versions() {
  VERSION_TOKEN="$VERSION_TOKEN" perl -0777 -ne '
    my $t = qr/$ENV{VERSION_TOKEN}/;
    while (m{com\.stirling:[A-Za-z0-9._-]+:($t)}g) { print "$1\n" }
    while (m{<dependency>((?:(?!</dependency>).)*?)</dependency>}gs) {
      my $block = $1;
      next unless $block =~ /com\.stirling/;
      while ($block =~ m{<version>($t)</version>}g) { print "$1\n" }
    }
    while (m{current release is\s*\*\*($t)}g) { print "$1\n" }
    while (m{javadoc\.io/(?:doc|static)/com\.stirling/jpdfium/($t)}g) { print "$1\n" }
  ' "$1" | sort -u
}

# Replace one version, but only inside those fields. The trailing boundary keeps
# a version that merely prefixes a longer one, such as 1.2.0 inside 1.2.0-rc.1
# or 1.3.0-rc-1 inside 1.3.0-rc-11, from matching.
replace_version() {
  local old="$1" file="$2"
  OLD="$old" NEW="$VERSION" perl -0777 -pi -e '
    my $old = quotemeta $ENV{OLD};
    my $new = $ENV{NEW};
    my $end = qr/(?![0-9A-Za-z.-])/;
    s{(com\.stirling:[A-Za-z0-9._-]+:)($old)$end}{$1$new}g;
    s{<dependency>((?:(?!</dependency>).)*?)</dependency>}{
      my $block = $1;
      if ($block =~ /com\.stirling/) {
        # The closing tag is matched with a lookahead: a replacement that
        # contains "$new</" is parsed as a match against /... and truncates
        # the text it writes.
        $block =~ s{<version>($old)$end(?=</version>)}{<version>$new}g;
      }
      "<dependency>" . $block . "</dependency>";
    }gse;
    s{(current release is\s*\*\*)($old)$end}{$1$new}g;
    s{(javadoc\.io/(?:doc|static)/com\.stirling/jpdfium/)($old)$end}{$1$new}g;
  ' "$file"
}

# Every version one target documents that precedes the released version.
stale_versions() {
  local token
  while IFS= read -r token; do
    [ -n "$token" ] || continue
    [ "$token" = "$VERSION" ] && continue
    if version_precedes "$token" "$VERSION"; then
      printf '%s\n' "$token"
    fi
  done < <(owned_versions "$1")
}

echo "Reconciling ${#FILES[@]} file(s) against $VERSION"

if [ "$DRY_RUN" -eq 1 ]; then
  for file in "${FILES[@]}"; do
    while IFS= read -r token; do
      printf '%s: %s -> %s\n' "$file" "$token" "$VERSION"
    done < <(stale_versions "$file")
  done
  echo "Dry run: no files were changed."
  exit 0
fi

# Each target is reconciled on its own, so the whole set is repaired even when
# the installation guide already documents $VERSION and the other targets are
# still on an earlier release.
for file in "${FILES[@]}"; do
  while IFS= read -r token; do
    replace_version "$token" "$file"
  done < <(stale_versions "$file")
done

# Never leave a target behind: fail rather than open a pull request that still
# points at an earlier release.
for file in "${FILES[@]}"; do
  while IFS= read -r token; do
    echo "ERROR: $file still references $token, which precedes $VERSION" >&2
    exit 1
  done < <(stale_versions "$file")
done

# gradle.properties holds the development version, which moves to the next
# patch snapshot once a release is tagged, so branch builds never advertise a
# version that has already shipped. A prerelease tag does not release anything
# yet, so the development version stays on that release. It is not part of FILES
# above: the released version legitimately stays in that file's history.
#
# A release on an older line must not move the development version backwards:
# releasing 1.2.1 while development already sits on 1.3.0-SNAPSHOT keeps
# 1.3.0-SNAPSHOT, so the computed version is only written when it is ahead of
# the one in the file.
CORE="${VERSION%%-*}"
IFS='.' read -r CORE_MAJOR CORE_MINOR CORE_PATCH <<<"$CORE"
if [ "$CORE" != "$VERSION" ]; then
  DEV_VERSION="$CORE-SNAPSHOT"
else
  DEV_VERSION="$CORE_MAJOR.$CORE_MINOR.$((CORE_PATCH + 1))-SNAPSHOT"
fi
CURRENT_DEV="$(grep -E '^jpdfium\.version=' "$GRADLE_PROPERTIES" | head -n1 | cut -d= -f2- || true)"
if [ -n "$CURRENT_DEV" ] && version_precedes "$DEV_VERSION" "$CURRENT_DEV"; then
  echo "$GRADLE_PROPERTIES: development version stays at $CURRENT_DEV (ahead of $DEV_VERSION)"
elif [ "$CURRENT_DEV" = "$DEV_VERSION" ]; then
  echo "$GRADLE_PROPERTIES: development version is already $DEV_VERSION"
else
  DEV_VERSION="$DEV_VERSION" perl -pi -e 's/^jpdfium\.version=.*$/jpdfium.version=$ENV{DEV_VERSION}/' "$GRADLE_PROPERTIES"
  echo "$GRADLE_PROPERTIES: development version is $DEV_VERSION"
fi

for file in "${FILES[@]}"; do
  # Exact comparison against the extracted field values, so a version that only
  # occurs as part of a longer one is not counted twice.
  count="$(owned_versions "$file" | grep -cxF "$VERSION" || true)"
  printf '%s: %s reference(s)\n' "$file" "$count"
done
