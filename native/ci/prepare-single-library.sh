#!/usr/bin/env bash
set -euo pipefail

PLATFORM="${1:?usage: prepare-single-library.sh <platform>}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
NATIVE_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

ARCHIVE=""
for candidate in "${NATIVE_DIR}/pdfium/lib/libpdfium.a" "${NATIVE_DIR}/pdfium/lib/pdfium.lib"; do
    if [ -f "${candidate}" ]; then
        ARCHIVE="${candidate}"
        break
    fi
done
if [ -z "${ARCHIVE}" ]; then
    echo "prepare-single-library.sh: no static PDFium archive under ${NATIVE_DIR}/pdfium/lib" >&2
    exit 1
fi

ICU_MAJOR="$(LC_ALL=C grep -a -o -m1 -E 'u_init_[0-9]+' "${ARCHIVE}" | head -1 | sed 's/^u_init_//')"
if [ -z "${ICU_MAJOR}" ]; then
    echo "prepare-single-library.sh: cannot read the ICU major from ${ARCHIVE}" >&2
    exit 1
fi
DATA="${NATIVE_DIR}/dist/icu-data/icudt${ICU_MAJOR}l.dat"
if [ ! -f "${DATA}" ]; then
    echo "prepare-single-library.sh: trimmed ICU data ${DATA} is missing" >&2
    exit 1
fi
PC_DIR="$(bash "${NATIVE_DIR}/fetch-icu-headers.sh" "${ICU_MAJOR}" 1)"
echo "${PLATFORM}: PDFium bundles ICU ${ICU_MAJOR}; headers ${PC_DIR}; data ${DATA}"

if [ -n "${GITHUB_ENV:-}" ]; then
    echo "PKG_CONFIG_PATH=${PC_DIR}${PKG_CONFIG_PATH:+:${PKG_CONFIG_PATH}}" >> "${GITHUB_ENV}"
fi

case "${PLATFORM}" in
    linux-*)
        LLVM_VERSION="${LLVM_VERSION:-19}"
        if ! command -v "clang++-${LLVM_VERSION}" >/dev/null 2>&1 \
            || ! command -v "ld.lld-${LLVM_VERSION}" >/dev/null 2>&1 \
            || ! command -v nasm >/dev/null 2>&1; then
            sudo apt-get install -y "clang-${LLVM_VERSION}" "lld-${LLVM_VERSION}" nasm
        fi
        bash "${NATIVE_DIR}/build-linux-static-deps.sh"
        if [ -n "${GITHUB_ENV:-}" ]; then
            {
                echo "CC=clang-${LLVM_VERSION}"
                echo "CXX=clang++-${LLVM_VERSION}"
            } >> "${GITHUB_ENV}"
        fi
        ;;
esac
