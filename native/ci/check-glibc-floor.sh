#!/usr/bin/env bash
# Verifies no bundled ELF library needs a newer glibc or libstdc++ than the oldest
# supported distribution provides. A library binds the symbol versions of the
# host it was linked on, so a newer runner image raises this floor silently and
# the natives then fail to load with "version `GLIBC_2.xx' not found".
# The defaults are Ubuntu 24.04's (glibc 2.39, libstdc++ 6.0.33).
set -euo pipefail

DIST_DIR="${1:?usage: check-glibc-floor.sh <dist-dir> [max-glibc] [max-glibcxx] [max-cxxabi]}"
MAX_GLIBC="${2:-2.39}"
MAX_GLIBCXX="${3:-3.4.33}"
MAX_CXXABI="${4:-1.3.15}"

if ! command -v readelf >/dev/null 2>&1; then
    echo "ERROR: readelf is required to check symbol versions" >&2
    exit 1
fi

newer_than() {
    [ "$1" != "$2" ] && [ "$(printf '%s\n%s\n' "$1" "$2" | sort -V | tail -1)" = "$1" ]
}

failures=0
lib_count=0
for f in "$DIST_DIR"/*.so*; do
    [ -e "$f" ] || continue
    [ -L "$f" ] && continue
    readelf -h "$f" >/dev/null 2>&1 || continue
    lib_count=$((lib_count + 1))
    while read -r need; do
        name="${need%%_[0-9]*}"
        version="${need#*_}"
        case "$name" in
            GLIBC) limit="$MAX_GLIBC" ;;
            GLIBCXX) limit="$MAX_GLIBCXX" ;;
            CXXABI) limit="$MAX_CXXABI" ;;
            *) continue ;;
        esac
        if newer_than "$version" "$limit"; then
            echo "FAIL: $(basename "$f") needs ${need}, above the ${name}_${limit} floor" >&2
            failures=$((failures + 1))
        fi
    done < <(readelf -VW "$f" 2>/dev/null | grep -oE 'Name: (GLIBC|GLIBCXX|CXXABI)_[0-9]+(\.[0-9]+)*' | sed 's/^Name: //' | sort -u)
done

if [ "$lib_count" -eq 0 ]; then
    echo "ERROR: no ELF libraries found in $DIST_DIR" >&2
    exit 1
fi

if [ "$failures" -ne 0 ]; then
    echo "ERROR: $failures symbol version(s) in $DIST_DIR exceed the glibc ${MAX_GLIBC} / libstdc++ GLIBCXX_${MAX_GLIBCXX} / CXXABI_${MAX_CXXABI} floor" >&2
    exit 1
fi

echo "OK: $lib_count ELF librar(ies) in $DIST_DIR stay within glibc ${MAX_GLIBC} / GLIBCXX_${MAX_GLIBCXX} / CXXABI_${MAX_CXXABI}"
