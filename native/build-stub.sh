#!/usr/bin/env bash
# Build stub libjpdfium.so (no PDFium dependency, for Java unit tests).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TARGET_PLATFORM="${1:-${JPDFIUM_TARGET_PLATFORM:-}}"

CMAKE_ARGS=("-B" "${SCRIPT_DIR}/build-stub" "-S" "${SCRIPT_DIR}" "-DCMAKE_BUILD_TYPE=Release")
if [ -n "${TARGET_PLATFORM}" ]; then
  CMAKE_ARGS+=("-DJPDFIUM_TARGET_PLATFORM=${TARGET_PLATFORM}")
fi

cmake "${CMAKE_ARGS[@]}"
cmake --build "${SCRIPT_DIR}/build-stub" --parallel

echo "Stub built for ${TARGET_PLATFORM:-default}: $(find "${SCRIPT_DIR}/build-stub" -name '*jpdfium*' -type f)"
