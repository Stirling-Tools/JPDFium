#!/usr/bin/env bash
# Build libjpdfium linked against real PDFium.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PDFIUM_DIR="${SCRIPT_DIR}/pdfium"

if [ ! -d "${PDFIUM_DIR}/include" ]; then
    echo "ERROR: PDFium not found at ${PDFIUM_DIR}."
    echo "       Run: bash native/setup-pdfium.sh"
    exit 1
fi

echo "Building Rust dependencies..."
bash "${SCRIPT_DIR}/build-rust.sh"

BUILD_DIR="${BUILD_DIR:-${SCRIPT_DIR}/build-real}"

echo "Building libjpdfium..."
CMAKE_ARGS=(
    -B "${BUILD_DIR}"
    -S "${SCRIPT_DIR}"
    -DJPDFIUM_USE_PDFIUM=ON
    -DPDFIUM_DIR="${PDFIUM_DIR}"
    -DCMAKE_BUILD_TYPE=Release
)

if [ -n "${CMAKE_GENERATOR_PLATFORM:-}" ]; then
    CMAKE_ARGS+=(-A "${CMAKE_GENERATOR_PLATFORM}")
elif [ "${TRIPLET:-}" = "arm64-windows" ] || [ "${PROCESSOR_ARCHITECTURE:-}" = "ARM64" ]; then
    OS_NAME="$(uname -s | tr '[:upper:]' '[:lower:]')"
    if [ "$OS_NAME" != "darwin" ] && [ "$OS_NAME" != "linux" ]; then
        CMAKE_ARGS+=(-A "ARM64")
    fi
fi

# The OCR module builds when ONNX Runtime has been fetched (native/ocr/fetch-onnxruntime.sh),
# unless JPDFIUM_OCR=0.
if [ "${JPDFIUM_OCR:-auto}" != "0" ] && [ -f "${SCRIPT_DIR}/ocr/onnxruntime/include/onnxruntime_c_api.h" ]; then
    CMAKE_ARGS+=(-DJPDFIUM_BUILD_OCR=ON)
fi
if [ -n "${CMAKE_OSX_ARCHITECTURES:-}" ]; then
    CMAKE_ARGS+=(-DCMAKE_OSX_ARCHITECTURES="${CMAKE_OSX_ARCHITECTURES}")
fi

cmake "${CMAKE_ARGS[@]}"
cmake --build "${BUILD_DIR}" --parallel --config Release

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
find "${BUILD_DIR}" -maxdepth 1 -name 'libjpdfium.*' -exec cp {} "${SCRIPT_DIR}/dist/${PLATFORM}/" \;
find "${SCRIPT_DIR}/pdfium/lib" -maxdepth 1 \( -name '*.dylib' -o -name '*.so*' -o -name '*.dll' \) -exec cp {} "${SCRIPT_DIR}/dist/${PLATFORM}/" \;
rm -f "${SCRIPT_DIR}/dist/${PLATFORM}"/*jpdfium_ocr.* "${SCRIPT_DIR}/dist/${PLATFORM}"/*onnxruntime*
if [ -n "$(find "${BUILD_DIR}" -maxdepth 1 -name 'libjpdfium_ocr.*' -print -quit)" ]; then
    # The optional OCR module stays out of the core bundle: dist/ocr-<platform>, libraries only.
    # Packagers ship ocr/onnxruntime/LICENSE and ThirdPartyNotices.txt with it.
    OCR_DIST="${SCRIPT_DIR}/dist/ocr-${PLATFORM}"
    rm -rf "${OCR_DIST}"
    mkdir -p "${OCR_DIST}"
    find "${BUILD_DIR}" -maxdepth 1 -name 'libjpdfium_ocr.*' -exec cp {} "${OCR_DIST}/" \;
    # Only the runtime, under the name the OCR module links: CPU inference loads no provider library.
    ORT_LIB="${SCRIPT_DIR}/ocr/onnxruntime/lib"
    case "$OS" in
        linux) cp -L "${ORT_LIB}/libonnxruntime.so.1" "${OCR_DIST}/" ;;
        darwin) find "${ORT_LIB}" -maxdepth 1 -type f -name 'libonnxruntime.*.dylib' -exec cp {} "${OCR_DIST}/" \; ;;
        *) cp "${ORT_LIB}/onnxruntime.dll" "${OCR_DIST}/" ;;
    esac
fi

echo ""
echo "Built: $(find "${SCRIPT_DIR}/build-real" -name 'libjpdfium.*' -type f)"
echo ""
echo "Run Java tests:"
echo "  ./gradlew :jpdfium:test"
