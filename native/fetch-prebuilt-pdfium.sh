#!/usr/bin/env bash
# Downloads the prebuilt PDFium tarball for the current platform from the
# GitHub Release tag pinned in native/pdfium.version, and extracts it into
# native/pdfium/{include,lib}.
#
# Replaces the expensive `setup-pdfium.sh` path in CI publishing workflows.
# The prebuild itself is produced by .github/workflows/prebuild-pdfium.yml.
#
# Usage:
#   native/fetch-prebuilt-pdfium.sh <platform>
#
# Where <platform> is one of: linux-x64, linux-arm64, linux-musl-x64,
# linux-musl-arm64, darwin-x64, darwin-arm64, windows-x64, windows-arm64.
#
# Requires:
#   - gh CLI authenticated (GH_TOKEN env var is enough in GitHub Actions)
#   - GITHUB_REPOSITORY env var OR -Prepo argument - defaults to Stirling-Tools/JPDFium
set -euo pipefail

PLATFORM="${1:-}"
if [ -z "$PLATFORM" ]; then
    echo "Usage: $0 <platform>" >&2
    echo "  platform: linux-x64 | linux-arm64 | linux-musl-x64 | linux-musl-arm64 | darwin-x64 | darwin-arm64 | windows-x64 | windows-arm64" >&2
    exit 2
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PIN_FILE="${SCRIPT_DIR}/pdfium.version"
TARGET_DIR="${SCRIPT_DIR}/pdfium"
REPO="${GITHUB_REPOSITORY:-Stirling-Tools/JPDFium}"
# PDFium prebuilds are produced by the 'Prebuild PDFium' workflow and published
# to Stirling-Tools/JPDFium (the canonical upstream). Forks run this script with
# GITHUB_REPOSITORY pointing at themselves but don't carry the release tarballs,
# so when the pinned tag is missing in the current repo we fall back to upstream.
UPSTREAM_REPO="Stirling-Tools/JPDFium"

if [ ! -f "$PIN_FILE" ]; then
    echo "ERROR: pin file not found at $PIN_FILE" >&2
    exit 1
fi

TAG="$(grep -v '^#' "$PIN_FILE" | grep -v '^$' | head -n1 || true)"
if [ -z "$TAG" ]; then
    echo "ERROR: $PIN_FILE contains no pin tag." >&2
    echo "       Run the 'Prebuild PDFium' workflow first and merge its auto-PR." >&2
    exit 1
fi

# Pick the prebuilt variant based on JPDFIUM_BUILD_MODE:
#   "component" (default): pdfium-<platform>.tar.gz - multiple .so/.dylib/.dll
#       files, consumed by snapshot.yml / publish-github-packages.yml / ci.yml.
#   "static": pdfium-<platform>-static.tar.gz - single libpdfium.{a,lib},
#       consumed by release.yml. Picked up by build-real.sh + CMakeLists.txt
#       which whole-archive-link the .a into libjpdfium.so.
JPDFIUM_BUILD_MODE="${JPDFIUM_BUILD_MODE:-component}"
case "$JPDFIUM_BUILD_MODE" in
    component) SUFFIX="" ;;
    static)    SUFFIX="-static" ;;
    *)
        echo "ERROR: unknown JPDFIUM_BUILD_MODE=$JPDFIUM_BUILD_MODE (expected: component, static)" >&2
        exit 1
        ;;
esac
ASSET="pdfium-${PLATFORM}${SUFFIX}.tar.gz"
echo "Fetching $ASSET (mode=$JPDFIUM_BUILD_MODE) from release $TAG ($REPO)..."

mkdir -p "$TARGET_DIR"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# Download $ASSET from release $TAG in $repo into $TMP.
#
# Primary path: gh CLI (honours GH_TOKEN). GitHub occasionally returns a
# transient 401 for workflow GITHUB_TOKENs, so retry a few times.
# Fallback: plain curl to the browser-download URL. Release assets on a public
# repo need no authentication, so this sidesteps any token hiccup entirely.
download_from() {
    local repo="$1"
    local attempt
    for attempt in 1 2 3; do
        if gh release download "$TAG" \
            --repo "$repo" \
            --pattern "$ASSET" \
            --dir "$TMP" 2>/dev/null; then
            return 0
        fi
        echo "  gh release download attempt $attempt/3 failed for $repo; retrying..." >&2
        sleep 5
    done
    for attempt in 1 2 3; do
        if curl -fL --retry 3 --retry-delay 3 \
            "https://github.com/${repo}/releases/download/${TAG}/${ASSET}" \
            -o "${TMP}/${ASSET}" 2>/dev/null; then
            return 0
        fi
        echo "  direct download attempt $attempt/3 failed for $repo; retrying..." >&2
        sleep 5
    done
    return 1
}

# Try the current repo first. Forks don't carry the prebuild releases, so if
# the pinned tag can't be downloaded there fall back to the upstream repo where
# the canonical prebuilds live.
if ! download_from "$REPO"; then
    if [ "$REPO" != "$UPSTREAM_REPO" ]; then
        echo "Release $TAG not found in $REPO - falling back to $UPSTREAM_REPO" >&2
        download_from "$UPSTREAM_REPO"
    else
        echo "ERROR: failed to download $ASSET from release $TAG in $REPO." >&2
        echo "       Run the 'Prebuild PDFium' workflow first and merge its auto-PR." >&2
        exit 1
    fi
fi

if [ ! -f "${TMP}/${ASSET}" ]; then
    echo "ERROR: $ASSET not downloaded from release $TAG." >&2
    exit 1
fi

tar -xzf "$TMP/$ASSET" -C "$TARGET_DIR"

echo "Extracted into $TARGET_DIR:"
ls -la "$TARGET_DIR"
