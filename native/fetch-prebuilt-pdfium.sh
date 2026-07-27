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
# Where <platform> is one of: linux-x64, linux-arm64, darwin-x64, darwin-arm64,
# windows-x64, windows-arm64.
#
# Requires:
#   - gh CLI authenticated (GH_TOKEN env var is enough in GitHub Actions)
#   - GITHUB_REPOSITORY env var OR -Prepo argument — defaults to Stirling-Tools/JPDFium
set -euo pipefail

PLATFORM="${1:-}"
if [ -z "$PLATFORM" ]; then
    echo "Usage: $0 <platform>" >&2
    echo "  platform: linux-x64 | linux-arm64 | darwin-x64 | darwin-arm64 | windows-x64" >&2
    exit 2
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PIN_FILE="${SCRIPT_DIR}/pdfium.version"
TARGET_DIR="${SCRIPT_DIR}/pdfium"
REPO="${GITHUB_REPOSITORY:-Stirling-Tools/JPDFium}"

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
#   "component" (default): pdfium-<platform>.tar.gz — multiple .so/.dylib/.dll
#       files, consumed by snapshot.yml / publish-github-packages.yml / ci.yml.
#   "static": pdfium-<platform>-static.tar.gz — single libpdfium.{a,lib},
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

gh release download "$TAG" \
    --repo "$REPO" \
    --pattern "$ASSET" \
    --dir "$TMP"

tar -xzf "$TMP/$ASSET" -C "$TARGET_DIR"

echo "Extracted into $TARGET_DIR:"
ls -la "$TARGET_DIR"
