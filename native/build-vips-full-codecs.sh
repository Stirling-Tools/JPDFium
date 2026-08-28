#!/usr/bin/env bash
# Build libvips (+ libheif with static codecs) from source against whatever
# codec libraries (libjxl, libaom, libx265, libde265, libwebp, libpng, libjpeg,
# libtiff) the package manager provides.
set -euo pipefail

echo "build-vips-full-codecs.sh: start  ($(uname -s) $(uname -m))"

VIPS_TAG="${VIPS_VERSION:-}"
LIBHEIF_TAG="${LIBHEIF_VERSION:-}"
PREFIX=/usr/local
SUDO=""
case "$(uname -s)" in
    Linux*)
        OS=linux
        SUDO=sudo
        ;;
    Darwin*)
        OS=darwin
        SUDO=sudo
        ;;
    *)
        echo "build-vips-full-codecs.sh: unsupported OS $(uname -s)" >&2
        exit 1
        ;;
esac

resolve_latest_tag() {
    local repo="$1"
    curl -fsSL --retry 3 --retry-delay 3 \
        "https://api.github.com/repos/$repo/releases/latest" 2>/dev/null \
        | grep -oE '"tag_name": *"[^"]+"' | head -1 \
        | sed -E 's/.*"([^"]+)"$/\1/' || true
}

resolve_versions() {
    if [ -z "$VIPS_TAG" ]; then
        VIPS_TAG="$(resolve_latest_tag libvips/libvips)"
        VIPS_TAG="${VIPS_TAG:-v8.18.5}"
        echo "==> build-vips-full-codecs.sh: libvips resolved to latest: $VIPS_TAG"
    else
        case "$VIPS_TAG" in
            v*) ;;
            *) VIPS_TAG="v$VIPS_TAG" ;;
        esac
        echo "==> build-vips-full-codecs.sh: libvips pinned by env: $VIPS_TAG"
    fi
    if [ "$OS" = linux ] && [ -z "$LIBHEIF_TAG" ]; then
        LIBHEIF_TAG="$(resolve_latest_tag strukturag/libheif)"
        LIBHEIF_TAG="${LIBHEIF_TAG:-v1.19.5}"
        echo "==> build-vips-full-codecs.sh: libheif resolved to latest: $LIBHEIF_TAG"
    elif [ "$OS" = linux ]; then
        case "$LIBHEIF_TAG" in
            v*) ;;
            *) LIBHEIF_TAG="v$LIBHEIF_TAG" ;;
        esac
        echo "==> build-vips-full-codecs.sh: libheif pinned by env: $LIBHEIF_TAG"
    fi
}

install_deps() {
    echo "==> build-vips-full-codecs.sh: installing codec + build deps"
    if [ "$OS" = linux ]; then
        sudo apt-get update
        sudo apt-get install -y --no-install-recommends \
            meson ninja-build pkg-config build-essential cmake \
            libglib2.0-dev libexpat1-dev libfftw3-dev liborc-0.4-dev \
            libexif-dev liblcms2-dev \
            libjxl-dev libaom-dev libx265-dev libde265-dev \
            libwebp-dev libpng-dev libjpeg-turbo8-dev libtiff-dev
    else
        brew install meson ninja pkg-config cmake \
            glib expat fftw orc libexif little-cms2 \
            libheif jpeg-xl aom libde265 x265 \
            webp libpng jpeg-turbo libtiff
    fi
}

build_libheif_linux() {
    echo "==> build-vips-full-codecs.sh: building libheif ${LIBHEIF_TAG} (static codecs)"
    local work
    work="$(mktemp -d)"
    trap 'rm -rf "$work"' EXIT

    $SUDO apt-get remove -y libheif* 2>/dev/null || true

    curl -fsSL --retry 3 --retry-delay 3 \
        "https://github.com/strukturag/libheif/archive/refs/tags/${LIBHEIF_TAG}.tar.gz" \
        -o "$work/heif.tar.gz"
    tar -xzf "$work/heif.tar.gz" -C "$work"
    local src="$work/libheif-${LIBHEIF_TAG#v}"

    cmake -S "$src" -B "$work/build" \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_INSTALL_PREFIX="$PREFIX" \
        -DCMAKE_INSTALL_LIBDIR=lib \
        -DBUILD_SHARED_LIBS=ON \
        -DENABLE_PLUGIN_LOADING=OFF \
        -DWITH_LIBDE265=ON -DWITH_X265=ON \
        -DWITH_AOM_DECODER=ON -DWITH_AOM_ENCODER=ON \
        -DWITH_DAV1D=OFF -DWITH_RAV1E=OFF -DWITH_SVT=OFF -DWITH_KVAZAAR=OFF \
        -DWITH_EXAMPLES=OFF -DWITH_TESTING=OFF \
        || { echo "build-vips-full-codecs.sh: libheif cmake configure failed" >&2; exit 1; }

    local nproc
    nproc="$(nproc 2>/dev/null || echo 4)"
    cmake --build "$work/build" --parallel "$nproc" \
        || { echo "build-vips-full-codecs.sh: libheif build failed" >&2; exit 1; }
    $SUDO cmake --install "$work/build"
    echo "$PREFIX/lib" | $SUDO tee /etc/ld.so.conf.d/00-local.conf >/dev/null
    $SUDO ldconfig 2>/dev/null || true
    echo "==> build-vips-full-codecs.sh: libheif installed to $PREFIX/lib:"
    ls -la "$PREFIX"/lib/libheif.so* 2>/dev/null || true
}

build_vips() {
    echo "==> build-vips-full-codecs.sh: building libvips ${VIPS_TAG}"
    local work
    work="$(mktemp -d)"
    trap 'rm -rf "$work"' EXIT

    if [ "$OS" = darwin ]; then
        local bp
        bp="$(brew --prefix 2>/dev/null || echo /opt/homebrew)"
        export PKG_CONFIG_PATH="$bp/lib/pkgconfig:$bp/share/pkgconfig:${PKG_CONFIG_PATH:-}"
    else
        export PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig:${PKG_CONFIG_PATH:-}"
        export LD_LIBRARY_PATH="$PREFIX/lib:${LD_LIBRARY_PATH:-}"
    fi

    curl -fsSL --retry 3 --retry-delay 3 \
        "https://github.com/libvips/libvips/archive/refs/tags/${VIPS_TAG}.tar.gz" \
        -o "$work/vips.tar.gz"
    tar -xzf "$work/vips.tar.gz" -C "$work"
    local src="$work/libvips-${VIPS_TAG#v}"

    meson setup "$work/build" "$src" \
        --prefix="$PREFIX" --libdir=lib \
        --buildtype=release \
        -Dauto_features=disabled \
        -Ddeprecated=false -Dexamples=false \
        -Dmodules=disabled -Dintrospection=disabled -Dvapi=false \
        -Dcplusplus=false \
        -Dheif=enabled -Djpeg-xl=enabled \
        -Dwebp=enabled -Dpng=enabled -Djpeg=enabled -Dtiff=enabled \
        -Dexif=enabled -Dlcms=enabled -Dfftw=enabled -Dorc=enabled \
        -Dzlib=enabled \
        || { echo "build-vips-full-codecs.sh: meson configure failed" >&2; exit 1; }

    local nproc
    nproc="$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)"
    ninja -C "$work/build" -j"$nproc" \
        || { echo "build-vips-full-codecs.sh: ninja build failed" >&2; exit 1; }
    if [ "$OS" = darwin ]; then
        ninja -C "$work/build" install
    else
        $SUDO ninja -C "$work/build" install
        $SUDO ldconfig 2>/dev/null || true
    fi

    echo "==> build-vips-full-codecs.sh: installed to $PREFIX/lib:"
    ls -la "$PREFIX"/lib/libvips.so* "$PREFIX"/lib/libvips.*.dylib 2>/dev/null || true
}

resolve_versions
install_deps
if [ "$OS" = linux ]; then
    build_libheif_linux
fi
build_vips
echo "build-vips-full-codecs.sh: done (libvips ${VIPS_TAG})"
