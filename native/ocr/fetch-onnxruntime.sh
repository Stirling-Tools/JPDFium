#!/usr/bin/env bash
# Downloads the pinned ONNX Runtime CPU package for one platform into native/ocr/onnxruntime,
# refusing it unless its SHA-256 matches onnxruntime.version.
#   bash native/ocr/fetch-onnxruntime.sh linux-x64
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PLATFORM="${1:?usage: fetch-onnxruntime.sh <linux-x64|linux-aarch64|osx-arm64|win-x64|win-arm64>}"
VERSION="$(awk '$1 == "version" { print $2 }' "${SCRIPT_DIR}/onnxruntime.version")"
EXPECTED="$(awk -v p="${PLATFORM}" '$1 == "sha256" && $2 == p { print $3 }' "${SCRIPT_DIR}/onnxruntime.version")"
if [ -z "${EXPECTED}" ]; then
    echo "No pinned digest for ${PLATFORM}" >&2
    exit 1
fi

case "${PLATFORM}" in
    win-*) EXT=zip ;;
    *) EXT=tgz ;;
esac
ASSET="onnxruntime-${PLATFORM}-${VERSION}.${EXT}"
URL="https://github.com/microsoft/onnxruntime/releases/download/v${VERSION}/${ASSET}"
DEST="${SCRIPT_DIR}/onnxruntime"
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

curl -fsSL --proto '=https' -o "${WORK}/${ASSET}" "${URL}"
ACTUAL="$( (sha256sum "${WORK}/${ASSET}" 2>/dev/null || shasum -a 256 "${WORK}/${ASSET}") | awk '{ print $1 }')"
if [ "${ACTUAL}" != "${EXPECTED}" ]; then
    echo "Digest mismatch for ${ASSET}: ${ACTUAL}" >&2
    exit 1
fi

rm -rf "${DEST}"
mkdir -p "${DEST}"
if [ "${EXT}" = zip ]; then
    unzip -q "${WORK}/${ASSET}" -d "${WORK}/x"
else
    mkdir -p "${WORK}/x"
    tar -xzf "${WORK}/${ASSET}" -C "${WORK}/x"
fi
# The archive holds one top-level directory with include/ and lib/.
cp -R "${WORK}/x"/*/include "${WORK}/x"/*/lib "${DEST}/"
cp "${WORK}/x"/*/LICENSE "${DEST}/LICENSE"
cp "${WORK}/x"/*/ThirdPartyNotices.txt "${DEST}/ThirdPartyNotices.txt"
echo "${VERSION} ${PLATFORM}" > "${DEST}/VERSION"
echo "ONNX Runtime ${VERSION} (${PLATFORM}) in ${DEST}"
