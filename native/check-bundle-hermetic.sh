#!/usr/bin/env bash
# Verifies all ELF DT_NEEDED dependencies are present in the bundle or are standard core system libraries.
set -euo pipefail

DIST_DIR="${1:?usage: check-bundle-hermetic.sh <dist-dir>}"

if ! command -v readelf >/dev/null 2>&1; then
    exit 0
fi

CORE_RE='^(libc|libc\.musl|libm|libdl|libpthread|libgcc_s|libstdc\+\+|librt|libresolv|libutil|linux-vdso)\.so|^ld-linux|^ld-musl'

failures=0
lib_count=0
for f in "$DIST_DIR"/lib*.so*; do
    [ -e "$f" ] || continue
    [ -L "$f" ] && continue
    lib_count=$((lib_count + 1))

    while IFS= read -r dep; do
        [ -z "$dep" ] && continue
        [ -e "$DIST_DIR/$dep" ] && continue
        if echo "$dep" | grep -qE "$CORE_RE"; then
            continue
        fi
        echo "FAIL: $f depends on '$dep' which is neither bundled nor a core system lib" >&2
        failures=$((failures + 1))
    done < <(readelf -d "$f" 2>/dev/null | awk '/NEEDED/{gsub(/\[|\]/,"",$5); print $5}')
done

if [ "$lib_count" -eq 0 ]; then
    echo "ERROR: no ELF libraries found in $DIST_DIR" >&2
    exit 1
fi

if [ "$failures" -ne 0 ]; then
    echo "ERROR: $failures unresolved dependencies in $DIST_DIR" >&2
    exit 1
fi

echo "OK: bundle dependencies in $DIST_DIR are hermetic"
