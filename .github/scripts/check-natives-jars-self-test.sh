#!/usr/bin/env bash
#
# Regression tests for check-natives-jars.sh.
#
# Builds small jars in a temporary directory - one per case - and asserts how the
# checker classifies each of them. Only python3 and unzip are needed, so the test
# runs on the same runners as the checker.
#
#   .github/scripts/check-natives-jars-self-test.sh
#
# The cases cover the stable, snapshot and prerelease version spellings, the core
# and vips module families, an empty core jar (which must fail), an empty musl jar
# (which is experimental and may only warn), a jar whose payload names a different
# platform than its file name, a zero-byte bridge library, a jar with no libraries
# at all, an empty checksum manifest, and an unsupported platform name.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHECK="$SCRIPT_DIR/check-natives-jars.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

PASSED=0
FAILED=0

# make_jar <file> <mode> <payload platform>
#
# mode selects the payload the jar carries:
#   core          a complete core payload (bridge library, libraries, manifest, hashes)
#   vips          a complete vips payload, which needs no bridge library
#   none          no natives/ directory at all
#   no-libs       the manifest and hashes, but no libraries
#   zero-bridge   a complete payload whose bridge library is empty
#   zero-hashes   a complete payload whose checksum manifest is empty
make_jar() {
  local out="$1" mode="$2" platform="$3"
  python3 - "$out" "$mode" "$platform" <<'PY'
import sys
import zipfile

out, mode, platform = sys.argv[1], sys.argv[2], sys.argv[3]
at = lambda name, size: (f"natives/{platform}/{name}", size)

payloads = {
    "core": [at("libjpdfium.so", 4096), at("libpdfium.so", 8192),
             at("native-libs.txt", 128), at("native-libs.sha256", 64)],
    "vips": [at("libjpdfium.so", 4096), at("libvips.so", 8192),
             at("native-libs.txt", 128), at("native-libs.sha256", 64)],
    "vips-plain": [at("libvips.so", 8192),
                   at("native-libs.txt", 128), at("native-libs.sha256", 64)],
    "none": [("META-INF/MANIFEST.MF", 64)],
    "no-libs": [at("native-libs.txt", 128), at("native-libs.sha256", 64)],
    "zero-bridge": [at("libjpdfium.so", 0), at("libpdfium.so", 8192),
                    at("native-libs.txt", 128), at("native-libs.sha256", 64)],
    "zero-hashes": [at("libjpdfium.so", 4096), at("libpdfium.so", 8192),
                    at("native-libs.txt", 128), at("native-libs.sha256", 0)],
}

with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as archive:
    for path, size in payloads[mode]:
        archive.writestr(path, b"x" * size)
PY
}

# expect <jar> <exit code> <substring> <description>
expect() {
  local jar="$1" want_exit="$2" want_text="$3" what="$4"
  local out rc
  set +e
  out="$(bash "$CHECK" "$jar" 2>&1)"
  rc=$?
  set -e
  if [ "$rc" != "$want_exit" ]; then
    echo "  [test FAIL] $what: exit $rc, expected $want_exit"
    printf '%s\n' "$out" | sed 's/^/              /'
    FAILED=$((FAILED + 1))
    return
  fi
  if ! printf '%s' "$out" | grep -qF "$want_text"; then
    echo "  [test FAIL] $what: output does not contain '$want_text'"
    printf '%s\n' "$out" | sed 's/^/              /'
    FAILED=$((FAILED + 1))
    return
  fi
  PASSED=$((PASSED + 1))
  echo "  [test ok]   $what"
}

echo "Native jar checker regression tests"

jar="$WORK/jpdfium-natives-linux-x64-1.2.1.jar"
make_jar "$jar" core linux-x64
expect "$jar" 0 "ships 2 libraries for linux-x64" "stable core jar"

jar="$WORK/jpdfium-natives-linux-x64-1.2.1-SNAPSHOT.jar"
make_jar "$jar" core linux-x64
expect "$jar" 0 "ships 2 libraries for linux-x64" "snapshot core jar"

jar="$WORK/jpdfium-natives-linux-x64-1.2.1-rc.1.jar"
make_jar "$jar" core linux-x64
expect "$jar" 0 "ships 2 libraries for linux-x64" "prerelease core jar"

jar="$WORK/jpdfium-natives-linux-x64-1.2.1+build.7.jar"
make_jar "$jar" core linux-x64
expect "$jar" 0 "ships 2 libraries for linux-x64" "core jar with build metadata"

jar="$WORK/jpdfium-natives-darwin-arm64-1.3.0.jar"
make_jar "$jar" core darwin-arm64
expect "$jar" 0 "ships 2 libraries for darwin-arm64" "second platform family"

jar="$WORK/jpdfium-natives-vips-linux-x64-1.2.1-SNAPSHOT.jar"
make_jar "$jar" vips vips-linux-x64
expect "$jar" 0 "ships 2 libraries for vips-linux-x64" "snapshot vips jar"

jar="$WORK/jpdfium-natives-linux-x64-1.2.1-SNAPSHOT.jar"
make_jar "$jar" none linux-x64
expect "$jar" 1 "carries no natives/ payload" "empty core jar with a snapshot version"

jar="$WORK/jpdfium-natives-vips-linux-x64-1.2.1.jar"
make_jar "$jar" none vips-linux-x64
expect "$jar" 1 "carries no natives/ payload" "empty vips jar"

jar="$WORK/jpdfium-natives-linux-musl-x64-1.2.1.jar"
make_jar "$jar" none linux-musl-x64
expect "$jar" 0 "carries no native libraries (experimental platform)" "empty musl jar"

jar="$WORK/jpdfium-natives-linux-x64-1.2.1.jar"
make_jar "$jar" core linux-arm64
expect "$jar" 1 "names linux-x64 but ships no natives/linux-x64/" "jar whose payload is another platform"

jar="$WORK/jpdfium-natives-linux-x64-1.2.1.jar"
make_jar "$jar" zero-bridge linux-x64
expect "$jar" 1 "no bridge library" "zero-byte bridge library"

jar="$WORK/jpdfium-natives-vips-linux-x64-1.2.1.jar"
make_jar "$jar" vips-plain vips-linux-x64
expect "$jar" 0 "ships 1 library for vips-linux-x64" "vips jar needs no bridge library"

jar="$WORK/jpdfium-natives-vips-linux-x64-1.2.1.jar"
make_jar "$jar" no-libs vips-linux-x64
expect "$jar" 1 "no libraries" "vips payload with no libraries"

jar="$WORK/jpdfium-natives-linux-x64-1.2.1.jar"
make_jar "$jar" no-libs linux-x64
expect "$jar" 1 "no libraries" "payload with no libraries"

jar="$WORK/jpdfium-natives-linux-x64-1.2.1.jar"
make_jar "$jar" zero-hashes linux-x64
expect "$jar" 1 "empty native-libs.sha256" "empty checksum manifest"

jar="$WORK/jpdfium-natives-freebsd-x64-1.2.1.jar"
make_jar "$jar" core freebsd-x64
expect "$jar" 1 "does not name a supported platform" "unsupported platform name"

echo "Native jar checker regression tests: $PASSED passed, $FAILED failed"
[ "$FAILED" -eq 0 ]
