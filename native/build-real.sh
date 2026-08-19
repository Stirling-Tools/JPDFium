#!/usr/bin/env bash
# Build libjpdfium.so linked against real PDFium.
# Prerequisites: run ./native/setup-pdfium.sh first.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PDFIUM_DIR="${SCRIPT_DIR}/pdfium"

if [ ! -d "${PDFIUM_DIR}/include" ]; then
    echo "ERROR: PDFium not found at ${PDFIUM_DIR}."
    echo "       Run: bash native/setup-pdfium.sh"
    exit 1
fi

# Build the Rust static library first (required).
echo "Building Rust dependencies..."
bash "${SCRIPT_DIR}/build-rust.sh"

echo ""
echo "Building libjpdfium.so with real PDFium..."
CMAKE_ARGS=(
    -B "${SCRIPT_DIR}/build-real"
    -S "${SCRIPT_DIR}"
    -DJPDFIUM_USE_PDFIUM=ON
    -DPDFIUM_DIR="${PDFIUM_DIR}"
    -DCMAKE_BUILD_TYPE=Release
)
# Forward generator platform for multi-config generators (Visual Studio on Windows)
if [ -n "${CMAKE_GENERATOR_PLATFORM:-}" ]; then
    CMAKE_ARGS+=(-A "${CMAKE_GENERATOR_PLATFORM}")
    echo "  Generator platform: ${CMAKE_GENERATOR_PLATFORM}"
elif [ "${TRIPLET:-}" = "arm64-windows" ] || [ "${PROCESSOR_ARCHITECTURE:-}" = "ARM64" ]; then
    OS_NAME="$(uname -s | tr '[:upper:]' '[:lower:]')"
    if [ "$OS_NAME" != "darwin" ] && [ "$OS_NAME" != "linux" ]; then
        CMAKE_ARGS+=(-A "ARM64")
        echo "  Generator platform: ARM64"
    fi
fi

# darwin-x64 cross-compile from arm64 host: CMAKE_OSX_ARCHITECTURES is set
# by the publish workflow's macOS x86_64 install step. Forward to cmake.
if [ -n "${CMAKE_OSX_ARCHITECTURES:-}" ]; then
    CMAKE_ARGS+=(-DCMAKE_OSX_ARCHITECTURES="${CMAKE_OSX_ARCHITECTURES}")
    echo "  Cross-compile arch: ${CMAKE_OSX_ARCHITECTURES}"
fi
cmake "${CMAKE_ARGS[@]}"

# --config Release is required for multi-config generators (Visual Studio on
# Windows). Single-config generators (Ninja, Make) honor CMAKE_BUILD_TYPE
# at configure time and ignore --config, so this is safe across platforms.
# Without this the Windows bridge linked against debug runtimes (MSVCP140D.dll,
# VCRUNTIME140D.dll, ucrtbased.dll, freetyped.dll) which don't ship on user
# machines and cause `Can't find dependent libraries` at System.load time.
cmake --build "${SCRIPT_DIR}/build-real" --parallel --config Release

# Copy built library to native/dist/<platform> so Gradle stageNatives stays in sync
OS="$(uname -s | tr '[:upper:]' '[:lower:]')"
ARCH="$(uname -m)"
PLATFORM="darwin-arm64"
if [ "$OS" = "darwin" ]; then
    if [ "$ARCH" = "arm64" ]; then PLATFORM="darwin-arm64"; else PLATFORM="darwin-x64"; fi
elif [ "$OS" = "linux" ]; then
    if [ "$ARCH" = "aarch64" ]; then PLATFORM="linux-arm64"; else PLATFORM="linux-x64"; fi
fi
mkdir -p "${SCRIPT_DIR}/dist/${PLATFORM}"
find "${SCRIPT_DIR}/build-real" -maxdepth 1 -name 'libjpdfium.*' -exec cp {} "${SCRIPT_DIR}/dist/${PLATFORM}/" \;

echo ""
echo "Built: $(find "${SCRIPT_DIR}/build-real" -name 'libjpdfium.*' -type f)"
echo ""
echo "Run Java tests:"
echo "  ./gradlew :jpdfium:test"
