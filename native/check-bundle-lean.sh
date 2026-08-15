#!/usr/bin/env bash
# check-bundle-lean.sh - verify the shipped native bundle carries no dead
# weight: no debug symbols, no unexpected artifact types, no duplicate
# basenames (two files that collide in one process - one of them is dead
# weight), no stray subdirectories, and report the size breakdown so bloat is
# visible.
#
# Runs per-platform from bundle-runtime-deps.sh. Accepts an explicit platform
# (so a cross-built bundle can be inspected on any host); the OS is derived
# from the platform, falling back to the host's uname. Bash 3.2 compatible
# (macOS ships bash 3.2: no mapfile, no declare -A).
#
# Usage: check-bundle-lean.sh <dist-dir> [platform]
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

# 0) The bundle must be a FLAT directory: only shared libraries + the
#    manifest, no subdirectories (an accidental include/, Headers/, .git, ...).
if find "$DIST_DIR" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | grep -q .; then
    echo "FAIL: subdirectories found in bundle (stray dirs are dead weight):" >&2
    find "$DIST_DIR" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sed 's/^/    /' >&2
    exit 1
fi

# 1) Unexpected artifact types. A lean bundle ships only shared libraries +
# the manifest: no .a / .lib import libs, no .o, no .pdb, no .dSYM, no
# executables, no stray tarballs.
find "$DIST_DIR" -maxdepth 1 -type f | while IFS= read -r f; do
    base="$(basename "$f")"
    case "$base" in
        *.so|*.so.*|*.dylib|*.dll|native-libs.txt) ;;
        *)
            echo "FAIL: unexpected artifact '$base' in bundle (dead weight)" >&2
            echo 1 > "$WORK/failed"
            ;;
    esac
done
[ ! -f "$WORK/failed" ] || exit 1

# 2) Duplicate basenames (case-insensitive). Two distinct files that share a
# basename collide in the loader (Windows binds by base name, POSIX RUNPATH
# picks the first match) - exactly one is usable, the other is dead weight.
find "$DIST_DIR" -maxdepth 1 -type f \
    \( -name '*.so' -o -name '*.so.*' -o -name '*.dylib' -o -name '*.dll' \) \
    -exec basename {} \; | tr 'A-Z' 'a-z' | sort | uniq -d > "$WORK/dups.txt"
if [ -s "$WORK/dups.txt" ]; then
    echo "FAIL: duplicate basenames in bundle (collision = dead weight):" >&2
    sed 's/^/    /' "$WORK/dups.txt" >&2
    exit 1
fi

# 3) Debug symbols. Bundled libs must be stripped; debug data is pure dead
#    weight in a shipped jar (and tripled sizes on the build runners).
case "$OS" in
    linux)
        # ELF: .symtab (the local symbol table) and any .debug_*/.zdebug_*
        # section (objcopy --compress-debug-sections produces .zdebug_info...).
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
        # Mach-O: debug data lives in the __DWARF load segment. libmagic does
        # NOT print ELF-style "with debug_info" for Mach-O, so probe otool.
        find "$DIST_DIR" -maxdepth 1 -type f -name '*.dylib' -exec sh -c '
            for f do
                if otool -l "$f" 2>/dev/null | grep -q "__DWARF"; then
                    echo "FAIL: $f carries DWARF debug info - not stripped" >&2
                    exit 1
                fi
            done
        ' sh {} + || exit 1
        # .dSYM bundles and PDBs are also dead weight.
        if find "$DIST_DIR" -maxdepth 1 \( -name '*.dSYM' -o -name '*.pdb' \) 2>/dev/null | grep -q .; then
            echo "FAIL: debug symbol artifacts (.dSYM/.pdb) found in bundle" >&2
            exit 1
        fi
        ;;
    windows)
        # PE debug info ships as separate .pdb files; .lib import libs, .exp
        # and .ilk are build leftovers, never runtime deps.
        if find "$DIST_DIR" -maxdepth 1 \
            \( -name '*.pdb' -o -name '*.lib' -o -name '*.exp' -o -name '*.ilk' \) 2>/dev/null | grep -q .; then
            echo "FAIL: debug/import artifacts (.pdb/.lib/.exp/.ilk) found in bundle" >&2
            exit 1
        fi
        ;;
esac

# 4) Size breakdown report (visibility for bloat regressions).
find "$DIST_DIR" -maxdepth 1 -type f | while IFS= read -r f; do
    size=$(stat -c%s "$f" 2>/dev/null || stat -f%z "$f" 2>/dev/null || echo 0)
    printf "%9.2f MB  %s\n" "$(awk -v s=$size 'BEGIN{printf "%.2f", s/1048576}')" "$(basename "$f")"
done | sort -rn | head -15

echo "OK: bundle is lean - no debug symbols, no stray artifacts, no duplicate basenames"
