#!/usr/bin/env bash
# check-no-execstack.sh - Verify no bundled ELF library demands an executable stack.
#
# Some container/hardened-kernel environments (Debian LXC, Proxmox, Nix
# sandboxes) refuse to load shared objects whose PT_GNU_STACK is RWE, failing
# at dlopen/System.load with:
#   "cannot enable executable stack as shared object requires: Invalid argument"
#
# The bundled natives jar ships exactly the libs in the dist dir, so this must
# hold for every lib*.so* present. A CI gate + a hard check inside
# bundle-runtime-deps.sh keep it that way.
#
# Usage: bash native/check-no-execstack.sh <dist-dir>
set -euo pipefail

DIST_DIR="${1:?usage: check-no-execstack.sh <dist-dir>}"

if ! command -v readelf >/dev/null 2>&1; then
    echo "readelf not found - skipping execstack check (non-Linux host?)"
    exit 0
fi

failures=0
lib_count=0
for f in "$DIST_DIR"/lib*.so*; do
    [ -e "$f" ] || continue
    [ -L "$f" ] && continue  # symlink aliases share the target's GNU_STACK
    lib_count=$((lib_count + 1))
    if readelf -lW "$f" 2>/dev/null | grep "GNU_STACK" | grep -q "RWE"; then
        echo "FAIL: $f requires an executable stack (GNU_STACK RWE)" >&2
        failures=$((failures + 1))
    fi
done

if [ "$lib_count" -eq 0 ]; then
    echo "ERROR: no ELF libraries found in $DIST_DIR - nothing to validate" >&2
    echo "       (empty/broken staging makes this gate a no-op; refusing to pass)" >&2
    exit 1
fi

if [ "$failures" -ne 0 ]; then
    echo "ERROR: $failures bundled lib(s) require an executable stack -" >&2
    echo "       LXC / hardened kernels refuse to load them. Clear with:" >&2
    echo "         patchelf --clear-execstack <lib>" >&2
    exit 1
fi

echo "OK: no bundled lib requires an executable stack"
