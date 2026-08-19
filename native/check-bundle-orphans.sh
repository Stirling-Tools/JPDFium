#!/usr/bin/env bash
# Verifies that every bundled shared library is imported by another library in the bundle.
set -euo pipefail

DIST_DIR="${1:?usage: check-bundle-orphans.sh <dist-dir> [platform]}"
PLATFORM="${2:-}"

case "$PLATFORM" in
    linux-*|vips-linux-*)   OS=linux ;;
    darwin-*|vips-darwin-*) OS=darwin ;;
    windows-*|vips-windows-*) OS=windows ;;
    *)
        case "$(uname -s)" in
            Linux*) OS=linux ;;
            Darwin*) OS=darwin ;;
            MINGW*|MSYS*|CYGWIN*) OS=windows ;;
            *) echo "check-bundle-orphans.sh: unsupported OS $(uname -s); skipping"; exit 0 ;;
        esac
        ;;
esac

if [ "$OS" = windows ]; then
    if ! command -v dumpbin >/dev/null 2>&1; then
        DUMPBIN=$(find "/c/Program Files/Microsoft Visual Studio" \
                  -name 'dumpbin.exe' 2>/dev/null | head -1 || true)
        [ -z "$DUMPBIN" ] && { echo "dumpbin not found; skipping orphan check"; exit 0; }
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

find "$DIST_DIR" -maxdepth 1 -type f \
    \( -name '*.so' -o -name '*.so.*' -o -name '*.dylib' -o -name '*.dll' \) \
    > "$WORK/libs.txt" 2>/dev/null || true

if [ ! -s "$WORK/libs.txt" ]; then
    echo "check-bundle-orphans.sh: no libraries found in $DIST_DIR; skipping"
    exit 0
fi

: > "$WORK/imported.txt"
while IFS= read -r f; do
    imports_of "$f" | tr 'A-Z' 'a-z' >> "$WORK/imported.txt"
done < "$WORK/libs.txt"
sort -u -o "$WORK/imported.txt" "$WORK/imported.txt"

failures=0
while IFS= read -r f; do
    base="$(basename "$f")"
    lower="$(echo "$base" | tr 'A-Z' 'a-z')"

    if [ "$OS" = windows ]; then
        if echo "$lower" | grep -qE 'allocator_shim|raw_ptr'; then
            echo "FAIL: $base is an unused allocator hook and must not be bundled on Windows" >&2
            failures=$((failures + 1))
        fi
    fi

    case "$lower" in
        *jpdfium*|*vips*) continue ;;
    esac

    if ! grep -qx "$lower" "$WORK/imported.txt"; then
        echo "FAIL: $base is orphaned (nothing in the bundle imports it)" >&2
        failures=$((failures + 1))
    fi
done < "$WORK/libs.txt"

if [ "$failures" -ne 0 ]; then
    echo "ERROR: $failures orphaned library(ies) in $DIST_DIR" >&2
    exit 1
fi

echo "OK: all bundled libraries in $DIST_DIR are imported"
