#!/usr/bin/env bash
# Build libvips (+ libheif with static codecs) from source against whatever
# codec libraries (libjxl, libaom, libde265, kvazaar, libwebp, libpng, libjpeg,
# libtiff) the package manager provides. x265 is intentionally excluded (GPL).
#
# The pinned PDFium prebuild (native/pdfium) is wired in via a generated
# pdfium.pc so libvips' pdfload renders PDFs with the same BSD-licensed
# renderer as the core bridge instead of GPL poppler. The permissive loaders
# (OpenEXR, libraw, libspng, highway) are enabled to match the upstream
# Windows "all" bundle. cfitsio is skipped: the distro build drags in the
# whole curl/gnutls/krb5/ldap closure. ImageMagick stays off: apt's IM6 links
# GPL-3 liblqr and brew's IM7 loads its coders from module files that are not
# relocatable into the bundle without MAGICK_CODER_MODULE_PATH; a from-source
# IM7 built --without-modules is the follow-up. librsvg is dropped: its cairo/
# gdk-pixbuf chain hard-links X11 (4-5 MB on a headless server).
set -euo pipefail

echo "build-vips-full-codecs.sh: start  ($(uname -s) $(uname -m))"

VIPS_TAG="${VIPS_VERSION:-}"
LIBHEIF_TAG="${LIBHEIF_VERSION:-}"
AOM_TAG="${AOM_VERSION:-v3.15.1}"
case "$(uname -s)" in
    Linux*)
        OS=linux
        PREFIX=/usr/local
        SUDO=sudo
        ;;
    Darwin*)
        OS=darwin
        PREFIX="$(brew --prefix 2>/dev/null || echo /opt/homebrew)"
        SUDO=""
        ;;
    *)
        echo "build-vips-full-codecs.sh: unsupported OS $(uname -s)" >&2
        exit 1
        ;;
esac

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

resolve_latest_tag() {
    local repo="$1"
    # Authenticated API calls get 1000 req/hour instead of 60 for shared
    # runner IPs; GITHUB_TOKEN is always present in Actions, absent locally.
    # shellcheck disable=SC2086
    curl -fsSL --retry 3 --retry-delay 3 \
        ${GITHUB_TOKEN:+-H} ${GITHUB_TOKEN:+"Authorization: Bearer $GITHUB_TOKEN"} \
        "https://api.github.com/repos/$repo/releases/latest" 2>/dev/null \
        | grep -oE '"tag_name": *"[^"]+"' | head -1 \
        | sed -E 's/.*"([^"]+)"$/\1/' || true
}

resolve_versions() {
    if [ -z "$VIPS_TAG" ]; then
        VIPS_TAG="$(resolve_latest_tag libvips/libvips)"
        VIPS_TAG="${VIPS_TAG:-v8.18.6}"
        echo "==> build-vips-full-codecs.sh: libvips resolved to latest: $VIPS_TAG"
    else
        case "$VIPS_TAG" in
            v*) ;;
            *) VIPS_TAG="v$VIPS_TAG" ;;
        esac
        echo "==> build-vips-full-codecs.sh: libvips pinned by env: $VIPS_TAG"
    fi
    if [ -z "$LIBHEIF_TAG" ]; then
        LIBHEIF_TAG="$(resolve_latest_tag strukturag/libheif)"
        LIBHEIF_TAG="${LIBHEIF_TAG:-v1.23.4}"
        echo "==> build-vips-full-codecs.sh: libheif resolved to latest: $LIBHEIF_TAG"
    else
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
            autoconf automake libtool \
            libglib2.0-dev libexpat1-dev liborc-0.4-dev \
            libexif-dev liblcms2-dev \
            libjxl-dev libaom-dev libde265-dev \
            libwebp-dev libpng-dev libjpeg-turbo8-dev libtiff-dev \
            libopenjp2-7-dev \
            libopenexr-dev libraw-dev \
            libspng-dev libhwy-dev lld \
            zlib1g-dev liblzma-dev libzstd-dev libdeflate-dev
    else
        brew install meson ninja pkg-config cmake \
            glib expat orc libexif little-cms2 \
            jpeg-xl aom libde265 kvazaar \
            webp libpng jpeg-turbo libtiff \
            openjpeg \
            openexr libraw libspng highway nasm
    fi
}

build_aom() {
    # macOS only: brew's aom links libvmaf (~1 MB of video-quality code that
    # AVIF never uses). The apt build is vmaf-free already. Install under
    # PREFIX/opt so the bundler's /opt/homebrew/opt/*/lib (or /usr/local/opt)
    # rpath search finds the @rpath install name, and brew's aom is untouched.
    [ "$OS" = darwin ] || return 0
    local tag="${AOM_TAG:-v3.15.1}"
    local prefix="$PREFIX/opt/aom-${tag#v}"
    echo "==> build-vips-full-codecs.sh: building aom ${tag} (no vmaf) -> $prefix"
    local work
    work="$(mktemp -d)"
    trap 'rm -rf "$work"' RETURN

    # aomedia.googlesource.com intermittently answers 503 to shared runner
    # IPs; retry with backoff and fall back to brew's aom (which links the
    # permissive BSD libvmaf) rather than failing the whole vips leg.
    local attempt ok=0
    for attempt in 1 2 3 4 5; do
        if curl -fsSL --retry 3 --retry-delay 3 \
            "https://aomedia.googlesource.com/aom/+archive/${tag}.tar.gz" \
            -o "$work/aom.tar.gz"; then
            ok=1
            break
        fi
        echo "build-vips-full-codecs.sh: aom download attempt $attempt failed; retrying..." >&2
        sleep $((attempt * 15))
    done
    if [ "$ok" != "1" ]; then
        echo "build-vips-full-codecs.sh: aom download failed; keeping brew's aom (with libvmaf)" >&2
        return 0
    fi
    mkdir -p "$work/src"
    tar -xzf "$work/aom.tar.gz" -C "$work/src" \
        || { echo "build-vips-full-codecs.sh: aom extract failed; keeping brew's aom" >&2; return 0; }

    local arch_args=()
    if [ -n "${CMAKE_OSX_ARCHITECTURES:-}" ]; then
        arch_args=("-DCMAKE_OSX_ARCHITECTURES=$CMAKE_OSX_ARCHITECTURES")
    fi
    cmake -S "$work/src" -B "$work/build" \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_INSTALL_PREFIX="$prefix" \
        -DBUILD_SHARED_LIBS=1 \
        -DENABLE_TESTS=0 -DENABLE_EXAMPLES=0 -DENABLE_TOOLS=0 -DENABLE_DOCS=0 \
        -DCONFIG_TUNE_VMAF=0 \
        ${arch_args[@]+"${arch_args[@]}"} \
        || { echo "build-vips-full-codecs.sh: aom cmake configure failed; keeping brew's aom" >&2; return 0; }

    local nproc
    nproc="$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)"
    cmake --build "$work/build" --parallel "$nproc" \
        || { echo "build-vips-full-codecs.sh: aom build failed; keeping brew's aom" >&2; return 0; }
    cmake --install "$work/build"

    if otool -L "$prefix/lib/libaom.3.dylib" 2>/dev/null | grep -qi vmaf; then
        echo "build-vips-full-codecs.sh: aom still links vmaf; keeping brew's aom" >&2
        return 0
    fi
    # Make libheif and libvips pick this aom over brew's.
    export PKG_CONFIG_PATH="$prefix/lib/pkgconfig:${PKG_CONFIG_PATH:-}"
    export CMAKE_PREFIX_PATH="$prefix:${CMAKE_PREFIX_PATH:-}"
    echo "==> build-vips-full-codecs.sh: aom installed (vmaf-free)"
}

build_kvazaar() {
    # BSD-licensed HEVC encoder (the GPL x265 must not be bundled).
    # macOS takes the bottled kvazaar from the brew list above; Linux has
    # no kvazaar package on the runner distro, so build the pinned source.
    [ "$OS" = linux ] || return 0
    local tag="${KVAZAAR_TAG:-v2.3.2}"
    echo "==> build-vips-full-codecs.sh: building kvazaar ${tag}"
    local work
    work="$(mktemp -d)"
    trap 'rm -rf "$work"' RETURN

    curl -fsSL --retry 3 --retry-delay 3 \
        "https://github.com/ultravideo/kvazaar/archive/refs/tags/${tag}.tar.gz" \
        -o "$work/kvazaar.tar.gz"
    tar -xzf "$work/kvazaar.tar.gz" -C "$work"
    local src="$work/kvazaar-${tag#v}"

    (cd "$src" && ./autogen.sh && ./configure --prefix="$PREFIX")
    make -C "$src" -j"$(nproc 2>/dev/null || echo 4)" \
        || { echo "build-vips-full-codecs.sh: kvazaar build failed" >&2; exit 1; }
    $SUDO make -C "$src" install
    $SUDO ldconfig 2>/dev/null || true
    echo "==> build-vips-full-codecs.sh: kvazaar installed to $PREFIX/lib:"
    ls -la "$PREFIX"/lib/libkvazaar.* 2>/dev/null || true
}

build_libtiff() {
    # libjbig (GPL-2.0) must not be bundled: the distro libtiff links it, so
    # build our own without JBIG support. JBIG-compressed TIFFs then fail
    # with a clean error instead of shipping GPL code. Other platforms do
    # not bundle jbig (brew mxe builds omit it), so this is Linux-only.
    [ "$OS" = linux ] || return 0
    local tag="${TIFF_TAG:-v4.7.2}"
    echo "==> build-vips-full-codecs.sh: building libtiff ${tag} (no jbig)"
    local work
    work="$(mktemp -d)"
    trap 'rm -rf "$work"' RETURN

    curl -fsSL --retry 3 --retry-delay 3 \
        "https://download.osgeo.org/libtiff/tiff-${tag#v}.tar.gz" \
        -o "$work/tiff.tar.gz"
    tar -xzf "$work/tiff.tar.gz" -C "$work"
    local src="$work/tiff-${tag#v}"

    cmake -S "$src" -B "$work/build" \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_INSTALL_PREFIX="$PREFIX" \
        -DCMAKE_DISABLE_FIND_PACKAGE_JBIG=TRUE \
        -Dtiff-tools=OFF -Dtiff-tests=OFF -Dtiff-contrib=OFF -Dtiff-docs=OFF \
        || { echo "build-vips-full-codecs.sh: libtiff cmake configure failed" >&2; exit 1; }

    local nproc
    nproc="$(nproc 2>/dev/null || echo 4)"
    cmake --build "$work/build" --parallel "$nproc" \
        || { echo "build-vips-full-codecs.sh: libtiff build failed" >&2; exit 1; }
    $SUDO cmake --install "$work/build"
    $SUDO ldconfig 2>/dev/null || true
    echo "==> build-vips-full-codecs.sh: libtiff installed to $PREFIX/lib:"
    ls -la "$PREFIX"/lib/libtiff.* 2>/dev/null || true
}

build_libheif() {
    echo "==> build-vips-full-codecs.sh: building libheif ${LIBHEIF_TAG} (no x265)"
    local work
    work="$(mktemp -d)"
    trap 'rm -rf "$work"' RETURN

    # x265 is GPL-2.0-only and must not be bundled, so libheif is built
    # without HEVC encoding everywhere. de265 (LGPL) stays for decoding.
    if [ "$OS" = darwin ]; then
        local bp
        bp="$(brew --prefix 2>/dev/null || echo /opt/homebrew)"
        # Keg-only deps (libsharpyuv, zlib, ...) keep their .pc files out of
        # lib/pkgconfig. Without them pkg-config resolves the host copy, which
        # staged an arm64 libsharpyuv into the darwin-x64 bundle.
        local opt_pc
        for opt_pc in "$bp"/opt/*/lib/pkgconfig; do
            [ -d "$opt_pc" ] || continue
            PKG_CONFIG_PATH="$opt_pc:${PKG_CONFIG_PATH:-}"
        done
        export PKG_CONFIG_PATH="$bp/lib/pkgconfig:$bp/share/pkgconfig:${PKG_CONFIG_PATH:-}"
        if [ "${CMAKE_OSX_ARCHITECTURES:-}" = "x86_64" ]; then
            export PKG_CONFIG="$bp/bin/pkg-config"
        fi
    else
        $SUDO apt-get remove -y libheif* 2>/dev/null || true
        export PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig:${PKG_CONFIG_PATH:-}"
    fi

    curl -fsSL --retry 3 --retry-delay 3 \
        "https://github.com/strukturag/libheif/archive/refs/tags/${LIBHEIF_TAG}.tar.gz" \
        -o "$work/heif.tar.gz"
    tar -xzf "$work/heif.tar.gz" -C "$work"
    local src="$work/libheif-${LIBHEIF_TAG#v}"

    # JPEG-in-HEIF differs per OS: Linux turbo is old and stable so the
    # codec builds as-is, but the two Homebrew prefixes ship different
    # turbo generations and libheif 1.23.4's copy of jpeg_write_icc_profile
    # collides with 3.2 headers. Keep the codec (and its helper sources)
    # out on macOS entirely; nothing we ship reads JPEG through libheif.
    local jpeg_flags=(-DWITH_JPEG_DECODER=ON -DWITH_JPEG_ENCODER=ON)
    if [ "$OS" = darwin ]; then
        jpeg_flags=(-DWITH_JPEG_DECODER=OFF -DWITH_JPEG_ENCODER=OFF -DCMAKE_DISABLE_FIND_PACKAGE_JPEG=TRUE)
    fi
    cmake -S "$src" -B "$work/build" \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_INSTALL_PREFIX="$PREFIX" \
        -DCMAKE_INSTALL_LIBDIR=lib \
        -DBUILD_SHARED_LIBS=ON \
        -DENABLE_PLUGIN_LOADING=OFF \
        "${jpeg_flags[@]}" \
        -DWITH_LIBDE265=ON -DWITH_X265=OFF -DWITH_KVAZAAR=ON \
        -DWITH_OpenJPEG_DECODER=ON -DWITH_OpenJPEG_ENCODER=ON \
        -DWITH_AOM_DECODER=ON -DWITH_AOM_ENCODER=ON \
        -DWITH_DAV1D=OFF -DWITH_RAV1E=OFF -DWITH_SVT=OFF -DWITH_X264=OFF \
        -DWITH_EXAMPLES=OFF -DWITH_TESTING=OFF \
        || { echo "build-vips-full-codecs.sh: libheif cmake configure failed" >&2; exit 1; }

    if [ "$OS" = darwin ]; then
        echo "--- libheif JPEG wiring (diagnostic) ---"
        grep -iE "^JPEG_(LIBRARY|INCLUDE_DIR)" "$work/build/CMakeCache.txt" || echo "(no JPEG cache entries)"
        if grep -rl "encoder_jpeg" "$work/build/heifio/CMakeFiles/heifio.dir/build.make" 2>/dev/null; then
            echo "heifio WILL compile encoder_jpeg"
        else
            echo "heifio SKIPS encoder_jpeg"
        fi
    fi

    local nproc
    nproc="$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)"
    cmake --build "$work/build" --parallel "$nproc" \
        || { echo "build-vips-full-codecs.sh: libheif build failed" >&2; exit 1; }
    if [ "$OS" = linux ]; then
        $SUDO cmake --install "$work/build"
        echo "$PREFIX/lib" | $SUDO tee /etc/ld.so.conf.d/00-local.conf >/dev/null
        $SUDO ldconfig 2>/dev/null || true
    else
        cmake --install "$work/build"
    fi
    echo "==> build-vips-full-codecs.sh: libheif installed to $PREFIX/lib:"
    ls -la "$PREFIX"/lib/libheif.* 2>/dev/null || true
}

build_vips() {
    echo "==> build-vips-full-codecs.sh: building libvips ${VIPS_TAG}"
    local work
    work="$(mktemp -d)"
    trap 'rm -rf "$work"' RETURN

    if [ "$OS" = darwin ]; then
        local bp
        bp="$(brew --prefix 2>/dev/null || echo /opt/homebrew)"
        # Keg-only deps (libsharpyuv, zlib, ...) keep their .pc files out of
        # lib/pkgconfig. Without them pkg-config resolves the host copy, which
        # staged an arm64 libsharpyuv into the darwin-x64 bundle.
        local opt_pc
        for opt_pc in "$bp"/opt/*/lib/pkgconfig; do
            [ -d "$opt_pc" ] || continue
            PKG_CONFIG_PATH="$opt_pc:${PKG_CONFIG_PATH:-}"
        done
        export PKG_CONFIG_PATH="$bp/lib/pkgconfig:$bp/share/pkgconfig:${PKG_CONFIG_PATH:-}"
        if [ "${CMAKE_OSX_ARCHITECTURES:-}" = "x86_64" ]; then
            export PKG_CONFIG="$bp/bin/pkg-config"
        fi
    else
        export PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig:${PKG_CONFIG_PATH:-}"
        export LD_LIBRARY_PATH="$PREFIX/lib:${LD_LIBRARY_PATH:-}"
    fi

    # Wire the pinned PDFium prebuild (fetch-prebuilt-pdfium.sh extracts it
    # into native/pdfium) into libvips' pdfload. libvips only checks
    # pdfium >= 4200, so the placeholder version just has to clear that gate.
    # The static archive is preferred: linking it whole-archive with
    # gc-sections/dead-strip keeps only the used PDFium code and avoids the
    # component libs (icuuc/harfbuzz/abseil), which duplicate the ones the
    # rest of the stack links.
    local pdfium_flag="-Dpdfium=disabled"
    if [ -f "$SCRIPT_DIR/pdfium/include/fpdfview.h" ]; then
        local pdfium_pc="$work/pdfium-pc"
        local pdfium_static="$SCRIPT_DIR/pdfium/lib/libpdfium.a"
        mkdir -p "$pdfium_pc"
        {
            echo "prefix=$SCRIPT_DIR/pdfium"
            echo 'exec_prefix=${prefix}'
            echo 'libdir=${exec_prefix}/lib'
            echo 'includedir=${prefix}/include'
            echo
            echo "Name: pdfium"
            echo "Description: pdfium"
            echo "Version: 9999"
            echo "Requires:"
            if [ -f "$pdfium_static" ]; then
                case "$OS" in
                    darwin)
                        echo "Libs: -L\${libdir} -Wl,-dead_strip -Wl,-force_load,$pdfium_static -framework CoreFoundation -framework CoreGraphics -framework CoreText -framework AppKit -framework Security -framework SystemConfiguration"
                        ;;
                    *)
                        echo "Libs: -L\${libdir} -fuse-ld=lld -Wl,--gc-sections -Wl,--whole-archive,$pdfium_static,--no-whole-archive -lpthread -ldl -lm -lstdc++"
                        ;;
                esac
                echo "==> build-vips-full-codecs.sh: linking the static PDFium archive" >&2
            else
                echo "Libs: -L\${libdir} -lpdfium"
            fi
            echo "Cflags: -I\${includedir}"
        } > "$pdfium_pc/pdfium.pc"
        export PKG_CONFIG_PATH="$pdfium_pc:${PKG_CONFIG_PATH:-}"
        pdfium_flag="-Dpdfium=enabled"
        echo "==> build-vips-full-codecs.sh: libvips will load PDFs with PDFium ($SCRIPT_DIR/pdfium)"
    else
        echo "==> build-vips-full-codecs.sh: no PDFium tree; libvips built without PDF loading" >&2
    fi

    # magick stays disabled: apt IM6 links GPL-3 liblqr, and brew IM7 loads
    # its coders from module .so files that need MAGICK_CODER_MODULE_PATH at
    # runtime (not relocatable into the bundle). Follow-up: build IM7 from
    # source with --without-modules.
    local magick_flag="-Dmagick=disabled"

    curl -fsSL --retry 3 --retry-delay 3 \
        "https://github.com/libvips/libvips/archive/refs/tags/${VIPS_TAG}.tar.gz" \
        -o "$work/vips.tar.gz"
    tar -xzf "$work/vips.tar.gz" -C "$work"
    local src="$work/libvips-${VIPS_TAG#v}"

    meson setup "$work/build" "$src" \
        --prefix="$PREFIX" --libdir=lib \
        --buildtype=release -Db_lto=true \
        -Dauto_features=disabled \
        -Dc_args="-ffunction-sections -fdata-sections" \
        -Ddeprecated=false -Dexamples=false \
        -Dmodules=disabled -Dintrospection=disabled -Dvapi=false \
        -Dcplusplus=false \
        "$pdfium_flag" "$magick_flag" \
        -Drsvg=disabled -Dopenexr=enabled -Draw=enabled \
        -Dspng=enabled -Dhighway=enabled \
        -Dheif=enabled -Djpeg-xl=enabled -Dopenjpeg=enabled \
        -Dwebp=enabled -Dpng=enabled -Djpeg=enabled -Dtiff=enabled \
        -Dexif=enabled -Dlcms=enabled -Dorc=enabled \
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
build_aom
build_kvazaar
build_libtiff
build_libheif
build_vips
echo "build-vips-full-codecs.sh: done (libvips ${VIPS_TAG})"
