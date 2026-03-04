#!/usr/bin/env bash
# Downloads pre-built PDFium for the current platform into native/pdfium/
# Usage: ./native/setup-pdfium.sh [linux-x64|linux-arm64|mac-arm64|mac-x64|win-x64]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TARGET_DIR="${SCRIPT_DIR}/pdfium"

detect_platform() {
    local os arch
    case "$(uname -s)" in
        Linux*)              os="linux" ;;
        Darwin*)             os="mac"   ;;
        MINGW*|CYGWIN*|MSYS*) os="win" ;;
        *) echo "Unsupported OS: $(uname -s)" >&2; exit 1 ;;
    esac
    case "$(uname -m)" in
        x86_64|amd64)  arch="x64"   ;;
        aarch64|arm64) arch="arm64" ;;
        *) echo "Unsupported arch: $(uname -m)" >&2; exit 1 ;;
    esac
    echo "${os}-${arch}"
}

PLATFORM="${1:-$(detect_platform)}"
URL="https://github.com/bblanchon/pdfium-binaries/releases/latest/download/pdfium-${PLATFORM}.tgz"

echo "Platform : ${PLATFORM}"
echo "URL      : ${URL}"
echo "Target   : ${TARGET_DIR}"

if [ -d "${TARGET_DIR}/lib" ] && [ -d "${TARGET_DIR}/include" ]; then
    echo "PDFium already present at ${TARGET_DIR}. Delete it to re-download."
    exit 0
fi

mkdir -p "${TARGET_DIR}"
echo "Downloading..."
curl -L --progress-bar "${URL}" | tar xz -C "${TARGET_DIR}"

echo ""
echo "PDFium extracted to ${TARGET_DIR}/"
echo "  Headers : ${TARGET_DIR}/include/"
echo "  Library : $(ls "${TARGET_DIR}/lib/"libpdfium.* "${TARGET_DIR}/lib/"pdfium.* 2>/dev/null || echo 'check lib/')"
echo ""
echo "Build with:"
echo "  bash native/build-real.sh"
