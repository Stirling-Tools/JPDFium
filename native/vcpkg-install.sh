#!/usr/bin/env bash
# Installs vcpkg packages with retry support for CI resilience.
set -euo pipefail

OVERLAY_DIR="$(cd "$(dirname "$0")" && pwd)/vcpkg-overlay/ports"

for attempt in 1 2 3 4 5; do
    if vcpkg install --overlay-ports="$OVERLAY_DIR" "$@"; then
        echo "vcpkg install OK (attempt $attempt)"
        exit 0
    fi
    echo "vcpkg install attempt $attempt failed - retrying in 30s..." >&2
    sleep 30
done

echo "vcpkg install failed after 5 attempts" >&2
exit 1
