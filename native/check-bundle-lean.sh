#!/usr/bin/env bash
# Verifies native bundle structure: flat directory, valid artifact extensions, no duplicate basenames, and stripped binaries.
set -euo pipefail

DIST_DIR="${1:?usage: check-bundle-lean.sh <dist-dir> [platform]}"
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
            *) echo "check-bundle-lean.sh: unsupported OS $(uname -s); skipping"; exit 0 ;;
        esac
        ;;
esac

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

if find "$DIST_DIR" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | grep -q .; then
    echo "FAIL: subdirectories found in bundle:" >&2
    find "$DIST_DIR" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sed 's/^/    /' >&2
    exit 1
fi

find "$DIST_DIR" -maxdepth 1 -type f | while IFS= read -r f; do
    base="$(basename "$f")"
    case "$base" in
        *.so|*.so.*|*.dylib|*.dll|native-libs.txt) ;;
        *)
            echo "FAIL: unexpected artifact '$base' in bundle" >&2
            echo 1 > "$WORK/failed"
            ;;
    esac
done
[ ! -f "$WORK/failed" ] || exit 1

find "$DIST_DIR" -maxdepth 1 -type f \
    \( -name '*.so' -o -name '*.so.*' -o -name '*.dylib' -o -name '*.dll' \) \
    -exec basename {} \; | tr 'A-Z' 'a-z' | sort | uniq -d > "$WORK/dups.txt"
if [ -s "$WORK/dups.txt" ]; then
    echo "FAIL: duplicate basenames in bundle:" >&2
    sed 's/^/    /' "$WORK/dups.txt" >&2
    exit 1
fi

case "$OS" in
    linux)
        find "$DIST_DIR" -maxdepth 1 -type f \( -name '*.so' -o -name '*.so.*' \) \
            -exec sh -c '
                for f do
                    if readelf -S "$f" 2>/dev/null | grep -qE "\.(symtab|z?debug_)"; then
                        echo "FAIL: $f carries debug sections - not stripped" >&2
                        exit 1
                    fi
                done
            ' sh {} + || exit 1
        ;;
    darwin)
        find "$DIST_DIR" -maxdepth 1 -type f -name '*.dylib' -exec sh -c '
            for f do
                if otool -l "$f" 2>/dev/null | grep -q "__DWARF"; then
                    echo "FAIL: $f carries DWARF debug info - not stripped" >&2
                    exit 1
                fi
            done
        ' sh {} + || exit 1
        if find "$DIST_DIR" -maxdepth 1 \( -name '*.dSYM' -o -name '*.pdb' \) 2>/dev/null | grep -q .; then
            echo "FAIL: debug symbol artifacts (.dSYM/.pdb) found in bundle" >&2
            exit 1
        fi
        ;;
    windows)
        if find "$DIST_DIR" -maxdepth 1 \
            \( -name '*.pdb' -o -name '*.lib' -o -name '*.exp' -o -name '*.ilk' \) 2>/dev/null | grep -q .; then
            echo "FAIL: debug/import artifacts (.pdb/.lib/.exp/.ilk) found in bundle" >&2
            exit 1
        fi
        ;;
esac

find "$DIST_DIR" -maxdepth 1 -type f | while IFS= read -r f; do
    size=$(stat -c%s "$f" 2>/dev/null || stat -f%z "$f" 2>/dev/null || echo 0)
    printf "%9.2f MB  %s\n" "$(awk -v s=$size 'BEGIN{printf "%.2f", s/1048576}')" "$(basename "$f")"
done | sort -rn | head -15

echo "OK: bundle in $DIST_DIR is lean"
