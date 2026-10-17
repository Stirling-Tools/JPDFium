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
# unless JPDFIUM_OCR=0. The option is passed explicitly either way: a reused build directory
# keeps the previous value in its CMake cache, so JPDFIUM_OCR=0 must be able to turn ON back off.
OCR_ENABLED=OFF
if [ "${JPDFIUM_OCR:-auto}" != "0" ] && [ -f "${SCRIPT_DIR}/ocr/onnxruntime/include/onnxruntime_c_api.h" ]; then
    OCR_ENABLED=ON
fi
CMAKE_ARGS+=(-DJPDFIUM_BUILD_OCR="${OCR_ENABLED}")
if [ -n "${CMAKE_OSX_ARCHITECTURES:-}" ]; then
    CMAKE_ARGS+=(-DCMAKE_OSX_ARCHITECTURES="${CMAKE_OSX_ARCHITECTURES}")
fi

if [ "${TARGET_PLATFORM:-${JPDFIUM_TARGET_PLATFORM:-}}" = "freebsd-x64" ]; then
    FREEBSD_SYSROOT="${FREEBSD_SYSROOT:-${SCRIPT_DIR}/freebsd-sysroot}"
    CMAKE_ARGS+=(
        -DCMAKE_SYSTEM_NAME=FreeBSD
        -DCMAKE_SYSTEM_VERSION=14
        -DCMAKE_SYSTEM_PROCESSOR=x86_64
        -DCMAKE_SYSROOT="${FREEBSD_SYSROOT}"
        -DCMAKE_C_COMPILER=clang
        -DCMAKE_CXX_COMPILER=clang++
        -DCMAKE_C_COMPILER_TARGET=x86_64-unknown-freebsd14.0
        -DCMAKE_CXX_COMPILER_TARGET=x86_64-unknown-freebsd14.0
        -DJPDFIUM_TARGET_PLATFORM=freebsd-x64
        -DCMAKE_FIND_ROOT_PATH="${FREEBSD_SYSROOT}"
        -DCMAKE_FIND_ROOT_PATH_MODE_PROGRAM=NEVER
        -DCMAKE_FIND_ROOT_PATH_MODE_LIBRARY=ONLY
        -DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE=ONLY
        -DCMAKE_FIND_ROOT_PATH_MODE_PACKAGE=ONLY
    )
    export PKG_CONFIG_DIR=""
    export PKG_CONFIG_LIBDIR="${FREEBSD_SYSROOT}/usr/libdata/pkgconfig:${FREEBSD_SYSROOT}/usr/lib/pkgconfig"
    export PKG_CONFIG_SYSROOT_DIR="${FREEBSD_SYSROOT}"
fi

cmake "${CMAKE_ARGS[@]}"
cmake --build "${BUILD_DIR}" --parallel --config Release

# Copy built library to native/dist/<platform> so Gradle stageNatives stays in sync
OS="$(uname -s | tr '[:upper:]' '[:lower:]')"
ARCH="$(uname -m)"
PLATFORM="darwin-arm64"
if [ -n "${TARGET_PLATFORM:-${JPDFIUM_TARGET_PLATFORM:-}}" ]; then
    PLATFORM="${TARGET_PLATFORM:-${JPDFIUM_TARGET_PLATFORM}}"
elif [ "$OS" = "darwin" ]; then
    if [ "$ARCH" = "arm64" ]; then PLATFORM="darwin-arm64"; else PLATFORM="darwin-x64"; fi
elif [ "$OS" = "freebsd" ]; then
    if [ "$ARCH" = "aarch64" ] || [ "$ARCH" = "arm64" ]; then PLATFORM="freebsd-arm64"; else PLATFORM="freebsd-x64"; fi
elif [ "$OS" = "openbsd" ]; then
    if [ "$ARCH" = "aarch64" ] || [ "$ARCH" = "arm64" ]; then PLATFORM="openbsd-arm64"; else PLATFORM="openbsd-x64"; fi
elif [ "$OS" = "linux" ]; then
    if [ "$ARCH" = "aarch64" ]; then PLATFORM="linux-arm64"; else PLATFORM="linux-x64"; fi
fi
mkdir -p "${SCRIPT_DIR}/dist/${PLATFORM}"
find "${BUILD_DIR}" -maxdepth 1 -name 'libjpdfium.*' -exec cp {} "${SCRIPT_DIR}/dist/${PLATFORM}/" \;

# Copy PDFium shared libraries only in component mode; in static mode all code is rolled into libjpdfium
if [ ! -f "${SCRIPT_DIR}/pdfium/lib/libpdfium.a" ] && [ ! -f "${SCRIPT_DIR}/pdfium/lib/pdfium.lib" ]; then
    find "${SCRIPT_DIR}/pdfium/lib" -maxdepth 1 -type f \( \
        -name '*.dylib' -o -name '*.so*' -o -name '*.dll' \
    \) \
    ! -name 'dbghelp.dll' ! -name 'dbgcore.dll' \
    ! -name 'msdia*.dll'   ! -name 'symsrv.dll' \
    ! -name 'vccorlib*.dll' \
    -exec cp {} "${SCRIPT_DIR}/dist/${PLATFORM}/" \;
fi
rm -f "${SCRIPT_DIR}/dist/${PLATFORM}"/*jpdfium_ocr.* "${SCRIPT_DIR}/dist/${PLATFORM}"/*onnxruntime*
if [ "${OCR_ENABLED}" = "ON" ]; then
    # The optional OCR module stays out of the core bundle: dist/ocr-<platform>, libraries only.
    # The ONNX Runtime MIT notice travels with the runtime, not the packager.
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
    # MIT requires the notice in every copy; fetch-onnxruntime.sh places both beside lib/.
    for lic in LICENSE ThirdPartyNotices.txt; do
        [ -f "${SCRIPT_DIR}/ocr/onnxruntime/${lic}" ] || {
            echo "OCR bundle is missing ${lic} from ocr/onnxruntime" >&2
            exit 1
        }
    done
    cp "${SCRIPT_DIR}/ocr/onnxruntime/LICENSE" "${OCR_DIST}/ONNXRUNTIME-LICENSE"
    cp "${SCRIPT_DIR}/ocr/onnxruntime/ThirdPartyNotices.txt" "${OCR_DIST}/ONNXRUNTIME-ThirdPartyNotices.txt"
else
    # The configuration, not a leftover artifact, decides what is staged: a stale
    # libjpdfium_ocr.* from an earlier enabled build must not be published as if current.
    rm -rf "${SCRIPT_DIR}/dist/ocr-${PLATFORM}"
    rm -f "${BUILD_DIR}"/libjpdfium_ocr.*
fi

echo ""
echo "Built: $(find "${SCRIPT_DIR}/build-real" -name 'libjpdfium.*' -type f)"
echo ""
echo "Run Java tests:"
echo "  ./gradlew :jpdfium:test"
