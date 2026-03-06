#!/usr/bin/env bash
# Build stub libjpdfium.so (no PDFium dependency, for Java unit tests).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

cmake -B "${SCRIPT_DIR}/build-stub" -S "${SCRIPT_DIR}" -DCMAKE_BUILD_TYPE=Release
cmake --build "${SCRIPT_DIR}/build-stub" --parallel

echo "Stub built: $(find "${SCRIPT_DIR}/build-stub" -name 'libjpdfium*' -type f)"
