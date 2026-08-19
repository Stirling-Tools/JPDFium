#!/usr/bin/env bash
# Verifies no bundled ELF library has an executable stack (GNU_STACK RWE).
set -euo pipefail

DIST_DIR="${1:?usage: check-no-execstack.sh <dist-dir>}"

if ! command -v readelf >/dev/null 2>&1; then
    exit 0
fi

failures=0
lib_count=0
for f in "$DIST_DIR"/lib*.so*; do
    [ -e "$f" ] || continue
    [ -L "$f" ] && continue
    lib_count=$((lib_count + 1))
    if readelf -lW "$f" 2>/dev/null | grep "GNU_STACK" | grep -q "RWE"; then
        echo "FAIL: $f requires an executable stack (GNU_STACK RWE)" >&2
        failures=$((failures + 1))
    fi
done

if [ "$lib_count" -eq 0 ]; then
    echo "ERROR: no ELF libraries found in $DIST_DIR" >&2
    exit 1
fi

if [ "$failures" -ne 0 ]; then
    echo "ERROR: $failures bundled library(ies) in $DIST_DIR have executable stacks" >&2
    exit 1
fi

echo "OK: no bundled library in $DIST_DIR requires an executable stack"
