#!/usr/bin/env bash
# check-bundle-hermetic.sh - Verify the bundled ELF libraries are self-contained.
#
# For the natives jar to load in minimal containers / chroots (Debian LXC,
# Proxmox, Nix sandboxes) the bundled libraries must not depend on system
# packages that aren't present there: every NEEDED dependency must be bundled
# next to the bridge OR be a core system lib (libc/libm/ld-linux/...) that every
# Linux runtime ships.
#
# A library that needs, say, system libqpdf.so but doesn't bundle it would load
# fine on a fat CI runner and fail with "cannot open shared object file" inside
# an LXC container. This check makes that class of bug fail the build instead.
#
# Usage: bash native/check-bundle-hermetic.sh <dist-dir>
set -euo pipefail

DIST_DIR="${1:?usage: check-bundle-hermetic.sh <dist-dir>}"

if ! command -v readelf >/dev/null 2>&1; then
    echo "readelf not found - skipping hermeticity check (non-Linux host?)"
    exit 0
fi

# Core system libs guaranteed present on any Linux runtime. libstdc++/libgcc_s
# are shipped by the toolchain; a minimal container still has them via glibc's
# dependency. Keep this list intentionally conservative.
# ld-linux matches versioned loader names (ld-linux-x86-64.so.2,
# ld-linux-aarch64.so.1); the libc-family entries require a `.so` so they don't
# absorb lookalikes like libcrypto/libfreetype.
CORE_RE='^(libc|libm|libdl|libpthread|libgcc_s|libstdc\+\+|librt|libresolv|libutil|linux-vdso)\.so|^ld-linux'

failures=0
for f in "$DIST_DIR"/lib*.so*; do
    [ -e "$f" ] || continue
    [ -L "$f" ] && continue  # symlink aliases share the target's deps

    while IFS= read -r dep; do
        [ -z "$dep" ] && continue
        [ -e "$DIST_DIR/$dep" ] && continue        # bundled next to the bridge
        if echo "$dep" | grep -qE "$CORE_RE"; then
            continue                                # core system lib
        fi
        echo "FAIL: $f depends on '$dep' which is neither bundled nor a core system lib" >&2
        failures=$((failures + 1))
    done < <(readelf -d "$f" 2>/dev/null | awk '/NEEDED/{gsub(/\[|\]/,"",$5); print $5}')
done

if [ "$failures" -ne 0 ]; then
    echo "ERROR: $failures unresolved dependency(ies) - the bundle is not hermetic" >&2
    echo "       and will fail to load in minimal containers (LXC / Nix sandboxes)." >&2
    exit 1
fi

echo "OK: every bundled lib's dependencies are bundled or core system libs"
