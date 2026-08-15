#!/usr/bin/env bash
# Build libvips from source with ALL major codecs into /usr/local, so the
# jpdfium-natives-vips-* bundles can render PDF pages to every major image
# format (HEIF/HEIC, AVIF, JXL, WebP, PNG, JPEG, TIFF).
#
# The distro-package libvips (apt libvips-dev / brew vips) is frequently built
# WITHOUT libjxl / libheif, so its savers are missing (jxlsave/heifsave are
# absent and jpdfium-vips cannot render PDF to those formats). Building from
# source with -Dauto_features=disabled + the codec savers explicitly enabled
# yields a minimal libvips that links exactly the codecs we ship. build-vips.sh
# prefers this copy over the distro one.
#
# Usage: build-vips-full-codecs.sh   (Linux + macOS only)
set -euo pipefail

echo "build-vips-full-codecs.sh: start  ($(uname -s) $(uname -m))"

VIPS_VERSION="${VIPS_VERSION:-8.16.1}"
PREFIX=/usr/local
SUDO=""
case "$(uname -s)" in
    Linux*)
        OS=linux
        SUDO=sudo
        ;;
    Darwin*)
        OS=darwin
        # GitHub macOS runners allow passwordless sudo; /usr/local may be
        # root-owned even when Homebrew lives in /opt/homebrew.
        SUDO=sudo
        ;;
    *)
        echo "build-vips-full-codecs.sh: unsupported OS $(uname -s)" >&2
        exit 1
        ;;
esac

install_deps() {
    echo "==> build-vips-full-codecs.sh: installing codec + build deps"
    if [ "$OS" = linux ]; then
        # ubuntu 24.04: libjxl / libheif / libaom / libx265 all in universe.
        # --no-install-recommends keeps the image lean (avoid pulling a second
        # system libvips via recommends).
        sudo apt-get update
        sudo apt-get install -y --no-install-recommends \
            meson ninja-build pkg-config build-essential \
            libglib2.0-dev libexpat1-dev libfftw3-dev liborc-0.4-dev \
            libexif-dev liblcms2-dev \
            libheif-dev libjxl-dev libaom-dev libx265-dev libde265-dev \
            libwebp-dev libpng-dev libjpeg-turbo8-dev libtiff-dev
    else
        brew install meson ninja pkg-config \
            glib expat fftw orc libexif lcms2 \
            libheif libjxl libaom libde265 x265 \
            libwebp libpng libjpeg-turbo libtiff
    fi
}

build_vips() {
    echo "==> build-vips-full-codecs.sh: building libvips ${VIPS_VERSION}"
    local work
    work="$(mktemp -d)"
    trap 'rm -rf "$work"' EXIT

    curl -fsSL --retry 3 --retry-delay 3 \
        "https://github.com/libvips/libvips/archive/refs/tags/v${VIPS_VERSION}.tar.gz" \
        -o "$work/vips.tar.gz"
    tar -xzf "$work/vips.tar.gz" -C "$work"
    local src="$work/libvips-${VIPS_VERSION}"

    # -Dauto_features=disabled turns OFF every optional dep (magick, poppler,
    # pdfium, openexr, ...) so we don't link a pile of unrelated loaders; the
    # saver/loader deps we DO want are enabled explicitly below (heif for
    # HEIC/HEIF/AVIF via libheif, jpeg-xl for JXL, plus the standard codecs).
    # The codec names follow libvips 8.16's meson_options.txt.
    meson setup "$work/build" "$src" \
        --prefix="$PREFIX" --libdir=lib \
        --buildtype=release \
        -Dauto_features=disabled \
        -Ddeprecated=false -Dexamples=false -Ddoxygen=false \
        -Dmodules=disabled -Dintrospection=disabled -Dvapi=disabled \
        -Dcplusplus=disabled \
        -Dheif=enabled -Djpeg-xl=enabled \
        -Dwebp=enabled -Dpng=enabled -Djpeg=enabled -Dtiff=enabled \
        -Dexif=enabled -Dlcms=enabled -Dfftw=enabled -Dorc=enabled \
        -Dzlib=enabled \
        || { echo "build-vips-full-codecs.sh: meson configure failed" >&2; exit 1; }

    local nproc
    nproc="$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)"
    ninja -C "$work/build" -j"$nproc" \
        || { echo "build-vips-full-codecs.sh: ninja build failed" >&2; exit 1; }
    $SUDO ninja -C "$work/build" install
    if [ "$OS" = linux ]; then $SUDO ldconfig 2>/dev/null || true; fi

    echo "==> build-vips-full-codecs.sh: installed to $PREFIX/lib:"
    ls -la "$PREFIX"/lib/libvips.so* "$PREFIX"/lib/libvips.*.dylib 2>/dev/null || true
}

install_deps
build_vips
echo "build-vips-full-codecs.sh: done"
