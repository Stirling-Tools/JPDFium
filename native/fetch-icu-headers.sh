#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ICU_VER="${1:-${ICU_VER:-78}}"
ICU_MINOR="${2:-${ICU_MINOR:-1}}"
OUT="${SCRIPT_DIR}/build-icu-headers/icu-${ICU_VER}.${ICU_MINOR}"
PC_DIR="${OUT}/lib/pkgconfig"

if [ ! -f "${OUT}/include/unicode/uvernum.h" ]; then
    WORK="$(mktemp -d)"
    trap 'rm -rf "$WORK"' EXIT
    fetched=""
    for asset in \
        "release-${ICU_VER}.${ICU_MINOR}/icu4c-${ICU_VER}.${ICU_MINOR}-sources.tgz" \
        "release-${ICU_VER}-${ICU_MINOR}/icu4c-${ICU_VER}_${ICU_MINOR}-src.tgz"; do
        if curl -fsSL --retry 3 --retry-delay 3 \
            "https://github.com/unicode-org/icu/releases/download/${asset}" -o "${WORK}/icu.tgz"; then
            fetched="${asset}"
            break
        fi
    done
    if [ -z "${fetched}" ]; then
        echo "fetch-icu-headers.sh: could not download ICU ${ICU_VER}.${ICU_MINOR} sources" >&2
        exit 1
    fi
    tar xzf "${WORK}/icu.tgz" -C "${WORK}" icu/source/common/unicode
    rm -rf "${OUT}"
    mkdir -p "${OUT}/include" "${PC_DIR}"
    cp -R "${WORK}/icu/source/common/unicode" "${OUT}/include/"
    echo "fetch-icu-headers.sh: ICU ${ICU_VER}.${ICU_MINOR} headers from ${fetched}" >&2
fi

HEADER_MAJOR="$(sed -n 's/^#define U_ICU_VERSION_MAJOR_NUM \([0-9][0-9]*\).*/\1/p' "${OUT}/include/unicode/uvernum.h")"
if [ "${HEADER_MAJOR}" != "${ICU_VER}" ]; then
    echo "fetch-icu-headers.sh: headers are ICU ${HEADER_MAJOR}, expected ${ICU_VER}" >&2
    exit 1
fi

mkdir -p "${PC_DIR}"
cat > "${PC_DIR}/icu-uc.pc" <<EOF
prefix=${OUT}
includedir=\${prefix}/include

Name: icu-uc
Description: ICU ${ICU_VER} headers for the ICU bundled in PDFium
Version: ${ICU_VER}.${ICU_MINOR}
Cflags: -I\${includedir}
Libs:
EOF
echo "${PC_DIR}"
