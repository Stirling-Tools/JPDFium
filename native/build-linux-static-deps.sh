#!/usr/bin/env bash
set -euo pipefail

JPEG_TURBO_TAG="${JPEG_TURBO_TAG:-3.1.4.1}"
UNIBREAK_VERSION="${UNIBREAK_VERSION:-8.0}"
PREFIX="${PREFIX:-/usr/local}"
if [ -z "${SUDO+x}" ]; then
    if [ "$(id -u)" -eq 0 ]; then SUDO=""; else SUDO="sudo"; fi
fi
NPROC="$(nproc 2>/dev/null || echo 4)"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

curl -fsSL --retry 3 --retry-delay 3 \
    "https://github.com/libjpeg-turbo/libjpeg-turbo/releases/download/${JPEG_TURBO_TAG}/libjpeg-turbo-${JPEG_TURBO_TAG}.tar.gz" \
    -o "$WORK/jpeg.tar.gz"
tar xzf "$WORK/jpeg.tar.gz" -C "$WORK"
cmake -S "$WORK/libjpeg-turbo-${JPEG_TURBO_TAG}" -B "$WORK/jpeg-build" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="$PREFIX" \
    -DCMAKE_INSTALL_LIBDIR=lib \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    -DENABLE_SHARED=OFF \
    -DENABLE_STATIC=ON \
    -DWITH_JPEG8=ON \
    -DWITH_TURBOJPEG=OFF \
    -DWITH_TOOLS=OFF \
    -DWITH_TESTS=OFF
cmake --build "$WORK/jpeg-build" -j"$NPROC"
$SUDO cmake --install "$WORK/jpeg-build"

UNIBREAK_TAG="libunibreak_${UNIBREAK_VERSION//./_}"
curl -fsSL --retry 3 --retry-delay 3 \
    "https://github.com/adah1972/libunibreak/releases/download/${UNIBREAK_TAG}/libunibreak-${UNIBREAK_VERSION}.tar.gz" \
    -o "$WORK/unibreak.tar.gz"
tar xzf "$WORK/unibreak.tar.gz" -C "$WORK"
(
    cd "$WORK/libunibreak-${UNIBREAK_VERSION}"
    ./configure --prefix="$PREFIX" --enable-static --disable-shared --with-pic CFLAGS="-O2 -fPIC"
    make -j"$NPROC"
    $SUDO make install
)
echo "Installed PIC static libjpeg-turbo ${JPEG_TURBO_TAG} and libunibreak ${UNIBREAK_VERSION} into ${PREFIX}"
