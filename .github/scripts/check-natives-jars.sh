#!/usr/bin/env bash
# Fails when a native artifact jar ships no libraries.
#
# The published native jars carry their payload in natives/<platform>/ next to
# the native-libs.txt manifest that NativeLoader reads at runtime. A platform
# whose native build produced nothing still publishes a jar with the licenses
# and two empty manifests, so every consumer on that platform fails to load
# while the release looks healthy. This checks the zip listing only, so it runs
# anywhere without extracting anything.
#
# The musl modules are experimental: release.yml skips them when the Alpine
# PDFium prebuild is missing, so they only warn. Everything else must be real.
#
# Usage: check-natives-jars.sh <jar-or-directory>...
set -euo pipefail

# Platforms that must ship libraries, plus the bridge each one must contain.
# NativeLoader checks for the bridge first and throws NativeNotFoundException
# when it is absent, so a jar without it cannot serve that platform at all.
# A jar must also carry the platform its file name names, so a jar built for one
# platform cannot pass on another platform's libraries.
# The platform directories a natives jar can carry, longest first so that
# vips-<platform> matches before the core <platform> it is built on. The file
# name is parsed against this list rather than by stripping a trailing segment,
# which would eat a hyphenated version such as 1.2.1-SNAPSHOT or 1.2.1-rc.1.
KNOWN_PLATFORMS="linux-musl-x64 linux-musl-arm64 vips-linux-x64 vips-linux-arm64
vips-darwin-x64 vips-darwin-arm64 vips-windows-x64 vips-windows-arm64
linux-x64 linux-arm64 darwin-x64 darwin-arm64 windows-x64 windows-arm64"
BRIDGE='^(lib)?jpdfium\.(so|dylib|dll)(\.[0-9]+)*$'
LIBRARY='\.(so|dylib|dll)(\.[0-9]+)*$'

if [ "$#" -eq 0 ]; then
  echo "ERROR: pass one or more native jars or directories" >&2
  exit 2
fi

# Prints "<uncompressed size> <entry>" per line.
list_entries() {
  if command -v unzip >/dev/null 2>&1; then
    unzip -l "$1" | awk 'NF >= 4 && $1 ~ /^[0-9]+$/ { print $1, $NF }'
  elif command -v jar >/dev/null 2>&1; then
    jar tvf "$1" | awk 'NF >= 2 && $1 ~ /^[0-9]+$/ { print $1, $NF }'
  else
    echo "ERROR: neither unzip nor jar is available to read the native jars" >&2
    exit 2
  fi
}

# Prints "<payload> <manifest size> <checksum size> <bridge present>" for one
# platform directory, where payload counts non-empty native libraries and the
# bridge counts only when it is non-empty too.
summarise() {
  local entries="$1" platform="$2" dir="natives/${2}/"
  printf '%s\n' "$entries" | awk -v dir="$dir" -v bridge="$BRIDGE" -v lib="$LIBRARY" '
    index($2, dir) == 1 {
      rest = substr($2, length(dir) + 1)
      if (rest == "") next
      if (rest == "native-libs.txt")    { manifest = $1; next }
      if (rest == "native-libs.sha256") { hashes = $1; next }
      if ($1 > 0 && rest ~ lib) payload++
      if ($1 > 0 && rest ~ bridge) found = 1
    }
    END { printf "%d %d %d %d\n", payload, manifest, hashes, found }
  '
}

JARS=()
for arg in "$@"; do
  if [ -d "$arg" ]; then
    while IFS= read -r jar; do JARS+=("$jar"); done < <(
      find "$arg" -maxdepth 1 -name 'jpdfium-natives-*.jar' | sort
    )
  elif [ -f "$arg" ]; then
    JARS+=("$arg")
  else
    echo "ERROR: $arg is not a file or a directory" >&2
    exit 2
  fi
done
if [ "${#JARS[@]}" -eq 0 ]; then
  echo "ERROR: no jpdfium-natives-*.jar found" >&2
  exit 2
fi

FAILURES=0
WARNINGS=0

for jar in "${JARS[@]}"; do
  name="$(basename "$jar")"

  # The platform the file name declares, which the jar must actually carry.
  declared=""
  for candidate in $KNOWN_PLATFORMS; do
    case "$name" in
      "jpdfium-natives-$candidate-"*.jar)
        declared="$candidate"
        break
        ;;
    esac
  done
  if [ -z "$declared" ]; then
    echo "  [FAIL] $name does not name a supported platform"
    FAILURES=$((FAILURES + 1))
    continue
  fi

  entries="$(list_entries "$jar")"
  platforms="$(printf '%s\n' "$entries" |
    sed -n 's#^[0-9]*[[:space:]]\{1,\}natives/\([^/]*\)/.*#\1#p' | sort -u)"

  if [ -z "$platforms" ]; then
    # Nothing under natives/, so the module published without libraries. The
    # musl prebuild is experimental and may legitimately be missing; every
    # other platform is a packaging failure.
    if printf '%s' "$declared" | grep -Eq '^linux-musl-'; then
      echo "  [WARN] $name carries no native libraries (experimental platform)"
      WARNINGS=$((WARNINGS + 1))
    else
      echo "  [FAIL] $name carries no natives/ payload"
      FAILURES=$((FAILURES + 1))
    fi
    continue
  fi

  if ! printf '%s\n' "$platforms" | grep -qxF "$declared"; then
    echo "  [FAIL] $name names $declared but ships no natives/$declared/"
    FAILURES=$((FAILURES + 1))
    continue
  fi

  while IFS= read -r platform; do
    [ -n "$platform" ] || continue
    read -r payload manifest hashes bridge < <(summarise "$entries" "$platform")

    # Only the core modules ship the bridge; the vips modules add codecs on
    # top of a core module that already provides it.
    wants_bridge=1
    case "$platform" in vips-*) wants_bridge=0 ;; esac

    if [ "$payload" -gt 0 ] && [ "$manifest" -gt 0 ] && [ "$hashes" -gt 0 ] &&
      { [ "$wants_bridge" -eq 0 ] || [ "$bridge" = "1" ]; }; then
      if [ "$payload" -eq 1 ]; then
        echo "  [ok]   $name ships 1 library for $platform"
      else
        echo "  [ok]   $name ships $payload libraries for $platform"
      fi
      continue
    fi

    detail=""
    if [ "$payload" -eq 0 ]; then
      detail="no libraries"
    elif [ "$wants_bridge" -eq 1 ] && [ "$bridge" != "1" ]; then
      detail="no bridge library"
    elif [ "$manifest" -eq 0 ]; then
      detail="empty native-libs.txt"
    elif [ "$hashes" -eq 0 ]; then
      detail="empty native-libs.sha256"
    fi

    if printf '%s' "$platform" | grep -Eq '^linux-musl-'; then
      echo "  [WARN] $name: $platform ($detail, experimental platform)"
      WARNINGS=$((WARNINGS + 1))
    else
      echo "  [FAIL] $name: $platform ($detail)"
      FAILURES=$((FAILURES + 1))
    fi
  done <<< "$platforms"
done

echo "Checked ${#JARS[@]} native jar(s): $FAILURES failure(s), $WARNINGS warning(s)."
if [ "$FAILURES" -ne 0 ]; then
  echo "ERROR: a supported platform published a native jar without libraries." >&2
  exit 1
fi
