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

echo ""
echo "Built: $(find "${SCRIPT_DIR}/build-real" -name 'libjpdfium.*' -type f)"
echo ""
echo "Run Java tests:"
echo "  ./gradlew :jpdfium:test"
