#!/usr/bin/env bash
# Downloads the prebuilt PDFium archive for the requested platform and extracts it into native/pdfium/.
set -euo pipefail

PLATFORM="${1:-}"
if [ -z "$PLATFORM" ]; then
    echo "Usage: $0 <platform>" >&2
    echo "Platforms: linux-x64 | linux-arm64 | linux-musl-x64 | linux-musl-arm64 | darwin-x64 | darwin-arm64 | windows-x64 | windows-arm64 | freebsd-x64" >&2
    exit 2
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PIN_FILE="${SCRIPT_DIR}/pdfium.version"
TARGET_DIR="${SCRIPT_DIR}/pdfium"
UPSTREAM_REPO="Stirling-Tools/JPDFium"

if [ ! -f "$PIN_FILE" ]; then
    echo "ERROR: pin file not found at $PIN_FILE" >&2
    exit 1
fi

TAG="$(grep -v '^#' "$PIN_FILE" | grep -v '^$' | head -n1 || true)"
if [ -z "$TAG" ]; then
    echo "ERROR: $PIN_FILE contains no version tag." >&2
    exit 1
fi

JPDFIUM_BUILD_MODE="${JPDFIUM_BUILD_MODE:-component}"
case "$JPDFIUM_BUILD_MODE" in
    component) SUFFIX="" ;;
    static)    SUFFIX="-static" ;;
    v8)        SUFFIX="-v8" ;;
    xfa)       SUFFIX="-xfa" ;;
    *)
        echo "ERROR: unknown JPDFIUM_BUILD_MODE=$JPDFIUM_BUILD_MODE (expected: component, static, v8, xfa)" >&2
        exit 1
        ;;
esac
ASSET="pdfium-${PLATFORM}${SUFFIX}.tar.gz"
# Prefer the fork's release so a branch can validate a new prebuild before it
# is merged upstream. GITHUB_REPOSITORY is set automatically in Actions.
REPOS=()
if [ -n "${GITHUB_REPOSITORY:-}" ] && [ "$GITHUB_REPOSITORY" != "$UPSTREAM_REPO" ]; then
    REPOS+=("$GITHUB_REPOSITORY")
fi
REPOS+=("$UPSTREAM_REPO")
echo "Fetching $ASSET (mode=$JPDFIUM_BUILD_MODE) from release $TAG (${REPOS[*]})..."

mkdir -p "$TARGET_DIR"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# Download $ASSET from release $TAG in $UPSTREAM_REPO into $TMP.
#
# Primary path: gh CLI (honours GH_TOKEN).
# Fallback: plain curl to the browser-download URL.
download_from_repo() {
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

asset_exists() {
    local repo="$1" assets
    if command -v gh >/dev/null 2>&1; then
        assets=$(gh release view "$TAG" --repo "$repo" --json assets --jq '.assets[].name' 2>/dev/null || true)
        if grep -Fx "$ASSET" <<<"$assets" >/dev/null; then
            return 0
        fi
    fi
    curl -fsI --retry 2 --retry-delay 2 \
        "https://github.com/${repo}/releases/download/${TAG}/${ASSET}" >/dev/null
}

for repo in "${REPOS[@]}"; do
    if ! asset_exists "$repo"; then
        continue
    fi
    if ! download_from_repo "$repo" || [ ! -f "${TMP}/${ASSET}" ]; then
        echo "ERROR: $ASSET exists in $repo release $TAG but could not be downloaded." >&2
        exit 1
    fi
    tar -xzf "$TMP/$ASSET" -C "$TARGET_DIR"
    echo "Extracted prebuilt PDFium into $TARGET_DIR:"
    ls -la "$TARGET_DIR"
    exit 0
done

echo "Prebuilt $ASSET not available in release $TAG (${REPOS[*]})."
if [ "${JPDFIUM_ALLOW_SOURCE_FALLBACK:-0}" != "1" ]; then
    echo "ERROR: set JPDFIUM_ALLOW_SOURCE_FALLBACK=1 to build PDFium from source." >&2
    exit 1
fi
echo "Falling back to building PDFium from source via setup-pdfium.sh..."

case "$PLATFORM" in
    linux-x64)
        TARGET_OS="linux"
        TARGET_CPU="x64"
        ;;
    linux-arm64)
        TARGET_OS="linux"
        TARGET_CPU="arm64"
        ;;
    linux-musl-x64)
        TARGET_OS="linux"
        TARGET_CPU="x64"
        export JPDFIUM_LIBC="musl"
        ;;
    linux-musl-arm64)
        TARGET_OS="linux"
        TARGET_CPU="arm64"
        export JPDFIUM_LIBC="musl"
        ;;
    darwin-x64)
        TARGET_OS="mac"
        TARGET_CPU="x64"
        ;;
    darwin-arm64)
        TARGET_OS="mac"
        TARGET_CPU="arm64"
        ;;
    windows-x64)
        TARGET_OS="win"
        TARGET_CPU="x64"
        ;;
    windows-arm64)
        TARGET_OS="win"
        TARGET_CPU="arm64"
        ;;
    freebsd-x64)
        TARGET_OS="freebsd"
        TARGET_CPU="x64"
        ;;
    *)
        echo "ERROR: unknown platform '$PLATFORM' for source build" >&2
        exit 1
        ;;
esac

export TARGET_OS
export TARGET_CPU
export JPDFIUM_BUILD_MODE

bash "${SCRIPT_DIR}/setup-pdfium.sh"

echo "Source build completed into $TARGET_DIR:"
ls -la "$TARGET_DIR"
