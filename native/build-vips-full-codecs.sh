#!/usr/bin/env bash
# Build libvips from source with ALL major codecs into /usr/local, so the
# jpdfium-natives-vips-* bundles can render PDF pages to every major image
# format (HEIF/HEIC, AVIF, JXL, WebP, PNG, JPEG, TIFF).
#
# ALWAYS TRACKS LATEST: libvips and libheif are resolved to their newest
# GitHub release tag on every run (override with VIPS_VERSION / LIBHEIF_VERSION,
# e.g. VIPS_VERSION=8.16.1). The apt/brew codec deps are likewise the latest
# the package manager provides. This is deliberate - the vips bundles must not
# drift behind upstream codec fixes.
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
        # GitHub macOS runners allow passwordless sudo; /usr/local may be
        # root-owned even when Homebrew lives in /opt/homebrew.
        SUDO=sudo
        ;;
    *)
        echo "build-vips-full-codecs.sh: unsupported OS $(uname -s)" >&2
        exit 1
        ;;
esac

# Resolve the latest release tag for a GitHub repo, e.g. "v8.18.5".
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
        # ubuntu 24.04: libjxl / libaom / libx265 / libde265 all in universe.
        # --no-install-recommends keeps the image lean (avoid pulling a second
        # system libvips via recommends).
        # NOTE: libheif is NOT installed from apt - Ubuntu's libheif ships the
        # HEVC/AV1 encoders as separate dlopened plugins (libheif-plugin-x265/
        # aom) that the bundler can't ship, so we build it from source with the
        # codecs statically linked (see build_libheif_linux).
        sudo apt-get update
        sudo apt-get install -y --no-install-recommends \
            meson ninja-build pkg-config build-essential cmake \
            libglib2.0-dev libexpat1-dev libfftw3-dev liborc-0.4-dev \
            libexif-dev liblcms2-dev \
            libjxl-dev libaom-dev libx265-dev libde265-dev \
            libwebp-dev libpng-dev libjpeg-turbo8-dev libtiff-dev
    else
        brew install meson ninja pkg-config cmake \
            glib expat fftw orc libexif lcms2 \
            libheif libjxl libaom libde265 x265 \
            libwebp libpng libjpeg-turbo libtiff
    fi
}

# Ubuntu's libheif is plugin-based: the HEVC (x265) / AV1 (aom) encoders are
# separate .so plugins that libheif dlopens at runtime. The bundler only ships
# ldd-referenced libraries, so the plugins would never reach the bundle and
# heifsave would fail with "Unsupported compression". Build libheif from source
# with ENABLE_PLUGIN_LOADING=OFF so the codecs are linked into libheif itself.
# Linux only - brew's libheif already links x265/aom statically.
build_libheif_linux() {
    echo "==> build-vips-full-codecs.sh: building libheif ${LIBHEIF_TAG} (static codecs)"
    local work
    work="$(mktemp -d)"
    trap 'rm -rf "$work"' EXIT

    curl -fsSL --retry 3 --retry-delay 3 \
        "https://github.com/strukturag/libheif/archive/refs/tags/${LIBHEIF_TAG}.tar.gz" \
        -o "$work/heif.tar.gz"
    tar -xzf "$work/heif.tar.gz" -C "$work"
    local src="$work/libheif-${LIBHEIF_TAG#v}"

    cmake -S "$src" -B "$work/build" \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_INSTALL_PREFIX="$PREFIX" \
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
    $SUDO ldconfig 2>/dev/null || true
    echo "==> build-vips-full-codecs.sh: libheif installed to $PREFIX/lib:"
    ls -la "$PREFIX"/lib/libheif.so* 2>/dev/null || true
}

build_vips() {
    echo "==> build-vips-full-codecs.sh: building libvips ${VIPS_TAG}"
    local work
    work="$(mktemp -d)"
    trap 'rm -rf "$work"' EXIT

    curl -fsSL --retry 3 --retry-delay 3 \
        "https://github.com/libvips/libvips/archive/refs/tags/${VIPS_TAG}.tar.gz" \
        -o "$work/vips.tar.gz"
    tar -xzf "$work/vips.tar.gz" -C "$work"
    local src="$work/libvips-${VIPS_TAG#v}"

    # -Dauto_features=disabled turns OFF every optional dep (magick, poppler,
    # pdfium, openexr, ...) so we don't link a pile of unrelated loaders; the
    # saver/loader deps we DO want are enabled explicitly below (heif for
    # HEIC/HEIF/AVIF via libheif, jpeg-xl for JXL, plus the standard codecs).
    # Option names track the latest libvips meson_options.txt (8.18+ renamed
    # the docs option; docs/cpp-docs default off, so no flag needed).
    meson setup "$work/build" "$src" \
        --prefix="$PREFIX" --libdir=lib \
        --buildtype=release \
        -Dauto_features=disabled \
        -Ddeprecated=false -Dexamples=false \
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

resolve_versions
install_deps
if [ "$OS" = linux ]; then
    build_libheif_linux
fi
build_vips
echo "build-vips-full-codecs.sh: done (libvips ${VIPS_TAG})"
