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

if [ -n "${CMAKE_OSX_ARCHITECTURES:-}" ]; then
    CMAKE_ARGS+=(-DCMAKE_OSX_ARCHITECTURES="${CMAKE_OSX_ARCHITECTURES}")
fi

if [ "${TARGET_PLATFORM:-${JPDFIUM_TARGET_PLATFORM:-}}" = "freebsd-x64" ]; then
    FREEBSD_SYSROOT="${FREEBSD_SYSROOT:-${SCRIPT_DIR}/freebsd-sysroot}"
    # CMake derives the archiver from the Clang toolchain prefix ("llvm-ar"),
    # which GH runners only ship versioned (llvm-ar-18, ...). Resolve a tool
    # that exists; binutils ar/ranlib archive FreeBSD ELF objects fine.
    CROSS_AR=""
    for candidate in llvm-ar llvm-ar-21 llvm-ar-20 llvm-ar-19 llvm-ar-18 llvm-ar-17 llvm-ar-16 ar; do
        if command -v "${candidate}" >/dev/null 2>&1; then CROSS_AR="$(command -v "${candidate}")"; break; fi
    done
    CROSS_RANLIB=""
    for candidate in llvm-ranlib llvm-ranlib-21 llvm-ranlib-20 llvm-ranlib-19 llvm-ranlib-18 llvm-ranlib-17 llvm-ranlib-16 ranlib; do
        if command -v "${candidate}" >/dev/null 2>&1; then CROSS_RANLIB="$(command -v "${candidate}")"; break; fi
    done
    if [ -z "${CROSS_AR}" ]; then
        echo "ERROR: no archiver (llvm-ar or ar) found for the FreeBSD cross build" >&2
        exit 1
    fi
    CMAKE_ARGS+=(
        -DCMAKE_SYSTEM_NAME=FreeBSD
        -DCMAKE_SYSTEM_VERSION=14
        -DCMAKE_SYSTEM_PROCESSOR=x86_64
        -DCMAKE_SYSROOT="${FREEBSD_SYSROOT}"
        -DCMAKE_C_COMPILER=clang
        -DCMAKE_CXX_COMPILER=clang++
        -DCMAKE_C_COMPILER_TARGET=x86_64-unknown-freebsd14.0
        -DCMAKE_CXX_COMPILER_TARGET=x86_64-unknown-freebsd14.0
        -DCMAKE_AR="${CROSS_AR}"
        -DJPDFIUM_TARGET_PLATFORM=freebsd-x64
        -DCMAKE_FIND_ROOT_PATH="${FREEBSD_SYSROOT}"
        -DCMAKE_FIND_ROOT_PATH_MODE_PROGRAM=NEVER
        -DCMAKE_FIND_ROOT_PATH_MODE_LIBRARY=ONLY
        -DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE=ONLY
        -DCMAKE_FIND_ROOT_PATH_MODE_PACKAGE=ONLY
    )
    if [ -n "${CROSS_RANLIB}" ]; then
        CMAKE_ARGS+=(-DCMAKE_RANLIB="${CROSS_RANLIB}")
    fi
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

echo ""
echo "Built: $(find "${SCRIPT_DIR}/build-real" -name 'libjpdfium.*' -type f)"
echo ""
echo "Run Java tests:"
echo "  ./gradlew :jpdfium:test"
