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
cmake -B "${SCRIPT_DIR}/build-real" \
      -S "${SCRIPT_DIR}" \
      -DJPDFIUM_USE_PDFIUM=ON \
      -DPDFIUM_DIR="${PDFIUM_DIR}" \
      -DCMAKE_BUILD_TYPE=Release

cmake --build "${SCRIPT_DIR}/build-real" --parallel

echo ""
echo "Built: $(find "${SCRIPT_DIR}/build-real" -name 'libjpdfium.*' -type f)"
echo ""
echo "Run Java tests:"
echo "  ./gradlew :jpdfium:test"
