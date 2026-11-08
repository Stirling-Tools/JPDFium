#!/usr/bin/env bash
set -euo pipefail

PLATFORM="${1:?usage: fetch-pdfium-for-natives.sh <platform>}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
NATIVE_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
REQUESTED="${JPDFIUM_BUILD_MODE:-static}"

single_library_platform() {
    case "$1" in
        darwin-arm64|darwin-x64|linux-x64) return 0 ;;
        linux-arm64) [ -f "${NATIVE_DIR}/pdfium/lib/pdfium-bundles-libcxx" ] ;;
        *) return 1 ;;
    esac
}

MODE="${REQUESTED}"
if [ "${REQUESTED}" = "static" ]; then
    case "${PLATFORM}" in
        darwin-*|linux-x64|linux-arm64)
            JPDFIUM_BUILD_MODE=static bash "${NATIVE_DIR}/fetch-prebuilt-pdfium.sh" "${PLATFORM}"
            if ! single_library_platform "${PLATFORM}"; then
                echo "::warning::${PLATFORM}: the pinned static PDFium does not bundle Chromium libc++; building the component layout"
                rm -rf "${NATIVE_DIR}/pdfium"
                MODE=component
            fi
            ;;
        *)
            MODE=component
            ;;
    esac
fi
if [ "${MODE}" != "static" ]; then
    JPDFIUM_BUILD_MODE="${MODE}" bash "${NATIVE_DIR}/fetch-prebuilt-pdfium.sh" "${PLATFORM}"
fi

echo "${PLATFORM}: PDFium build mode ${MODE}"
if [ -n "${GITHUB_ENV:-}" ]; then
    echo "JPDFIUM_BUILD_MODE=${MODE}" >> "${GITHUB_ENV}"
fi
