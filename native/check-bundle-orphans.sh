#!/usr/bin/env bash
# check-bundle-orphans.sh - verify no bundled native library is orphaned or
# JVM-hostile.
#
# Orphaned: a bundled .so/.dylib/.dll that NOTHING in the bundle imports. Such
# libraries are dead weight and are frequently a hazard - the prebuilt PDFium
# component build ships PartitionAlloc DLLs (allocator_shim, raw_ptr) that
# nothing links against, and preloading them into the JVM hard-crashes the
# process (STATUS_ENTRYPOINT_NOT_FOUND on windows-arm64).
#
# JVM-hostile: libraries that must never be loaded into a JVM (allocator
# shims / malloc hooks / PartitionAlloc raw pointers).
#
# Runs per-platform on the matching host (Linux bundle -> readelf, macOS ->
# otool, Windows -> dumpbin) from bundle-runtime-deps.sh. Bash 3.2 compatible
# (macOS ships bash 3.2: no mapfile, no declare -A).
#
# Usage: check-bundle-orphans.sh <dist-dir>
set -euo pipefail

DIST_DIR="${1:?usage: check-bundle-orphans.sh <dist-dir>}"

case "$(uname -s)" in
    Linux*)  OS=linux ;;
    Darwin*) OS=darwin ;;
    MINGW*|MSYS*|CYGWIN*) OS=windows ;;
    *)
        echo "check-bundle-orphans.sh: unsupported OS $(uname -s); skipping"
        exit 0 ;;
esac

if [ "$OS" = windows ]; then
    if ! command -v dumpbin >/dev/null 2>&1; then
        DUMPBIN=$(find "/c/Program Files/Microsoft Visual Studio" \
                  -name 'dumpbin.exe' 2>/dev/null | head -1 || true)
        [ -z "$DUMPBIN" ] && { echo "dumpbin not found - skipping orphan check"; exit 0; }
    else
        DUMPBIN=dumpbin
    fi
fi

imports_of() {
    local f="$1"
    case "$OS" in
        linux)
            readelf -d "$f" 2>/dev/null | awk '/NEEDED/{gsub(/\[|\]/,"",$NF); print $NF}' || true
            ;;
        darwin)
            otool -L "$f" 2>/dev/null | tail -n +2 | awk '{print $1}' | sed 's#.*/##' || true
            ;;
        windows)
            "$DUMPBIN" //dependents "$f" 2>/dev/null \
                | grep -oiE '[A-Za-z0-9_+.-]+\.dll' | tr 'A-Z' 'a-z' || true
            ;;
    esac
}

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# Collect every bundled library file (skip symlinks + the manifest).
find "$DIST_DIR" -maxdepth 1 -type f \
    \( -name '*.so' -o -name '*.so.*' -o -name '*.dylib' -o -name '*.dll' \) \
    > "$WORK/libs.txt" 2>/dev/null || true

if [ ! -s "$WORK/libs.txt" ]; then
    echo "check-bundle-orphans.sh: no libraries found in $DIST_DIR; skipping"
    exit 0
fi

# Union of every imported basename across the whole bundle (lowercased; Windows
# matches imports case-insensitively).
: > "$WORK/imported.txt"
while IFS= read -r f; do
    imports_of "$f" | tr 'A-Z' 'a-z' >> "$WORK/imported.txt"
done < "$WORK/libs.txt"
sort -u -o "$WORK/imported.txt" "$WORK/imported.txt"

failures=0
while IFS= read -r f; do
    base="$(basename "$f")"
    lower="$(echo "$base" | tr 'A-Z' 'a-z')"

    # JVM-hostile libraries must never ship.
    if echo "$lower" | grep -qE 'allocator_shim|raw_ptr'; then
        echo "FAIL: $base is a JVM-hostile library (allocator hook) - must never be bundled" >&2
        failures=$((failures + 1))
    fi

    # Roots (the bridge / libvips) are entry points - nothing imports them.
    case "$lower" in
        *jpdfium*|*vips*) continue ;;
    esac

    if ! grep -qx "$lower" "$WORK/imported.txt"; then
        echo "FAIL: $base is orphaned - nothing in the bundle imports it" >&2
        failures=$((failures + 1))
    fi
done < "$WORK/libs.txt"

if [ "$failures" -ne 0 ]; then
    echo "ERROR: $failures orphaned / JVM-hostile library(ies) in the bundle" >&2
    exit 1
fi

echo "OK: every bundled library is imported by something and none are JVM-hostile"
