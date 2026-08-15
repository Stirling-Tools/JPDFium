#!/usr/bin/env bash
# Install vcpkg packages with resilience for the windows-* native builds.
#
# 1. freetype comes from OUR overlay port (native/vcpkg-overlay/ports) which
#    fetches from the GitHub mirror (github.com/freetype/freetype) instead of
#    the chronically flaky gitlab.freedesktop.org - repeated 502/504 timeouts
#    there have been breaking the whole windows-* natives matrix. The GitHub
#    tag archive is byte-identical to gitlab's (same SHA512), so the build is
#    unchanged.
# 2. The whole install is retried: the runner's vcpkg binary cache
#    (~/.cache/vcpkg/archives on Linux, %LOCALAPPDATA%/vcpkg/archives on
#    Windows) converges across attempts AND across CI runs, so a transient
#    download failure on any other port no longer fails the build.
#
# Usage: vcpkg-install.sh <pkg>[:triplet] [<pkg>...]
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
