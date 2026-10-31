#!/usr/bin/env bash
# Build libvips and libheif from source against system codecs.
# AOM is the AVIF encoder; dav1d is the AV1 decoder. Excluded: x265 (GPL),
# PDFium, ImageMagick, librsvg, OpenEXR, libraw, cfitsio, fftw. libspng and
# highway are enabled.
set -euo pipefail

echo "build-vips-full-codecs.sh: start  ($(uname -s) $(uname -m))"

VIPS_TAG="${VIPS_VERSION:-}"
LIBHEIF_TAG="${LIBHEIF_VERSION:-}"
AOM_TAG="${AOM_VERSION:-v3.15.1}"
DAV1D_TAG="${DAV1D_VERSION:-1.5.4}"
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
    FreeBSD*)
        OS=freebsd
        PREFIX=/usr/local
        SUDO=""
        ;;
    *)
        echo "build-vips-full-codecs.sh: unsupported OS $(uname -s)" >&2
        exit 1
        ;;
esac

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
    if command -v apk >/dev/null 2>&1; then
        # Alpine/musl: install everything available, disable missing codecs.
        apk add --no-cache meson ninja pkgconf cmake build-base perl \
            glib-dev expat-dev orc-dev libexif-dev lcms2-dev \
            aom-dev libde265-dev \
            libwebp-dev libpng-dev libjpeg-turbo-dev tiff-dev \
            openjpeg-dev \
            zlib-dev xz-dev zstd-dev libdeflate-dev brotli-dev nasm
        # Alpine ships no kvazaar -dev package, so build the BSD HEVC encoder
        # from source (musl) with a pkg-config file for libheif.
        local kvz_tag="${KVAZAAR_VERSION:-v2.3.2}"
        curl -fsSL --retry 3 --retry-delay 3 \
            "https://github.com/ultravideo/kvazaar/archive/refs/tags/${kvz_tag}.tar.gz" \
            -o /tmp/kvazaar.tar.gz
        tar xzf /tmp/kvazaar.tar.gz -C /tmp
        cmake -S "/tmp/kvazaar-${kvz_tag#v}" -B /tmp/kvazaar-build \
            -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=ON \
            -DCMAKE_INSTALL_PREFIX=/usr/local >/dev/null
        cmake --build /tmp/kvazaar-build --parallel "$(nproc)"
        cmake --install /tmp/kvazaar-build
        mkdir -p /usr/local/lib/pkgconfig
        cat > /usr/local/lib/pkgconfig/kvazaar.pc <<EOF
prefix=/usr/local
exec_prefix=\${prefix}
libdir=\${exec_prefix}/lib
includedir=\${prefix}/include

Name: kvazaar
Description: kvazaar HEVC encoder
Version: ${kvz_tag#v}
Libs: -L\${libdir} -lkvazaar
Cflags: -I\${includedir}
EOF
        ldconfig 2>/dev/null || true
        local disable=""
        for dep in jpeg-xl spng highway; do
            case "$dep" in
                jpeg-xl) pkg=libjxl-dev ;;
                spng)    pkg=libspng-dev ;;
                highway) pkg=highway-dev ;;
            esac
            if apk add --no-cache "$pkg" >/dev/null 2>&1; then
                :
            else
                echo "==> build-vips-full-codecs.sh: $pkg unavailable; disabling $dep"
                disable="$disable $dep"
            fi
        done
        export JPDFIUM_VIPS_DISABLE_CODECS="$disable"
    elif [ "$OS" = freebsd ]; then
        pkg install -y meson ninja pkgconf cmake autoconf automake libtool nasm \
            glib expat orc libexif lcms2 jpeg-turbo png webp tiff openjpeg \
            libjxl aom libde265 kvazaar libspng highway zstd brotli libdeflate \
            binutils patchelf python3 perl5
    elif [ "$OS" = linux ]; then
        sudo apt-get update
        sudo apt-get install -y --no-install-recommends \
            meson ninja-build pkg-config build-essential cmake perl \
            autoconf automake libtool \
            libglib2.0-dev libexpat1-dev liborc-0.4-dev \
            libexif-dev liblcms2-dev \
            libjxl-dev libaom-dev libde265-dev \
            libwebp-dev libpng-dev libjpeg-turbo8-dev libtiff-dev \
            libopenjp2-7-dev \
            libspng-dev libhwy-dev nasm \
            zlib1g-dev liblzma-dev libzstd-dev libdeflate-dev \
            icu-devtools
    else
        local pkgs=(meson ninja pkg-config cmake
            glib expat orc libexif little-cms2
            jpeg-xl aom libde265 kvazaar
            webp libpng jpeg-turbo libtiff
            openjpeg
            libspng highway nasm)
        brew install "${pkgs[@]}" || brew install "${pkgs[@]}"
    fi
}

build_aom() {
    # Windows uses the vcpkg AOM package; this source build is POSIX-only.
    case "$OS" in linux|darwin|freebsd) ;; *) return 0 ;; esac
    # MinSizeRel AOM with only the encoder. AV1 decode is provided by dav1d
    # (16-bit/HDR capable), so the AOM decoder is dead weight.
    #
    # High-bit-depth AVIF encoding is intentionally disabled for size. The
    # library is still early development, and downstream Stirling-PDF does not
    # use 10/12-bit AVIF output. dav1d keeps high-bit-depth AVIF *decoding*.
    [ "${JPDFIUM_BUILD_AOM:-0}" = "1" ] || {
        echo "==> build-vips-full-codecs.sh: using system AOM (JPDFIUM_BUILD_AOM=1 to build)"
        return 0
    }
    local tag="${AOM_TAG:-v3.15.1}"
    # macOS keeps the keg out of brew's prefix; Linux installs into /usr/local
    # so the source libaom is found before the apt copy at bundle time.
    local prefix
    if [ "$OS" = darwin ]; then prefix="$PREFIX/opt/aom-${tag#v}"; else prefix="$PREFIX"; fi
    echo "==> build-vips-full-codecs.sh: building aom ${tag} (no vmaf) -> $prefix"
    local work
    work="$(mktemp -d)"
    trap 'rm -rf "$work"' RETURN

    # Prefer Google's release tarball: it is stable and has a normal
    # libaom-<version>/ top level. aomedia.googlesource.com intermittently
    # answers 503 to shared runner IPs and its archive is flat, so it is only
    # the fallback.
    mkdir -p "$work/src"
    if curl -fsSL --retry 3 --retry-delay 3 \
        "https://storage.googleapis.com/aom-releases/libaom-${tag#v}.tar.gz" \
        -o "$work/aom.tar.gz"; then
        tar -xzf "$work/aom.tar.gz" -C "$work/src" --strip-components=1 \
            || { echo "build-vips-full-codecs.sh: aom extract failed; keeping brew's aom" >&2; return 0; }
    else
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
        tar -xzf "$work/aom.tar.gz" -C "$work/src" \
            || { echo "build-vips-full-codecs.sh: aom extract failed; keeping brew's aom" >&2; return 0; }
    fi

    local arch_args=()
    if [ -n "${CMAKE_OSX_ARCHITECTURES:-}" ]; then
        arch_args=("-DCMAKE_OSX_ARCHITECTURES=$CMAKE_OSX_ARCHITECTURES")
    fi
    cmake -S "$work/src" -B "$work/build" \
        -DCMAKE_BUILD_TYPE=MinSizeRel \
        -DCMAKE_INSTALL_PREFIX="$prefix" \
        -DCMAKE_EXE_LINKER_FLAGS="-lm" \
        -DBUILD_SHARED_LIBS=1 \
        -DENABLE_TESTS=0 -DENABLE_EXAMPLES=0 -DENABLE_TOOLS=0 -DENABLE_APPS=0 -DENABLE_DOCS=0 \
        -DCONFIG_TUNE_VMAF=0 \
        -DCONFIG_WEBM_IO=0 -DCONFIG_LIBYUV=0 -DCONFIG_INSPECTION=0 \
        -DCONFIG_AV1_DECODER=0 \
        -DCONFIG_AV1_HIGHBITDEPTH="${JPDFIUM_AOM_HIGHBITDEPTH:-0}" \
        -DCONFIG_ENTROPY_STATS=0 -DCONFIG_INTERNAL_STATS=0 \
        ${arch_args[@]+"${arch_args[@]}"} \
        || { echo "build-vips-full-codecs.sh: aom cmake configure failed; keeping brew's aom" >&2; return 0; }

    local nproc
    nproc="$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)"
    cmake --build "$work/build" --parallel "$nproc" \
        || { echo "build-vips-full-codecs.sh: aom build failed; keeping brew's aom" >&2; return 0; }
    if [ "$OS" = linux ]; then
        $SUDO cmake --install "$work/build" \
            || { echo "build-vips-full-codecs.sh: aom install failed; keeping system aom" >&2; return 0; }
    else
        cmake --install "$work/build" \
            || { echo "build-vips-full-codecs.sh: aom install failed; keeping brew's aom" >&2; return 0; }
    fi

    if [ "$OS" = darwin ]; then
        if otool -L "$prefix/lib/libaom.3.dylib" 2>/dev/null | grep -qi vmaf; then
            echo "build-vips-full-codecs.sh: aom still links vmaf; keeping brew's aom" >&2
            return 0
        fi
    fi
    export JPDFIUM_AOM_PREFIX="$prefix"
    $SUDO ldconfig 2>/dev/null || true
    # Make libheif and libvips pick this aom over the system copy.
    export PKG_CONFIG_PATH="$prefix/lib/pkgconfig:${PKG_CONFIG_PATH:-}"
    export CMAKE_PREFIX_PATH="$prefix:${CMAKE_PREFIX_PATH:-}"
    export LD_LIBRARY_PATH="$prefix/lib:${LD_LIBRARY_PATH:-}"
    echo "==> build-vips-full-codecs.sh: aom installed ($prefix)"
}

build_dav1d() {
    # AV1 decoding used to come from libaom's decoder. dav1d is smaller and
    # considerably faster, and keeps 10/12-bit AVIF decoding working while the
    # AOM build is encoder-only.
    case "$OS" in linux|darwin|freebsd) ;; *) return 0 ;; esac
    [ "${JPDFIUM_BUILD_DAV1D:-1}" = "1" ] || {
        echo "==> build-vips-full-codecs.sh: using system dav1d (JPDFIUM_BUILD_DAV1D=1 to build)"
        return 0
    }
    local tag="${DAV1D_TAG:-1.5.4}"
    local prefix
    if [ "$OS" = darwin ]; then
        prefix="$PREFIX/opt/dav1d-${tag#v}"
    else
        prefix="$PREFIX"
    fi
    echo "==> build-vips-full-codecs.sh: building dav1d ${tag} -> $prefix"
    local work
    work="$(mktemp -d)"
    trap 'rm -rf "$work"' RETURN

    curl -fsSL --retry 3 --retry-delay 3 \
        "https://github.com/videolan/dav1d/archive/refs/tags/${tag}.tar.gz" \
        -o "$work/dav1d.tar.gz"
    tar -xzf "$work/dav1d.tar.gz" -C "$work"
    local src="$work/dav1d-${tag#v}"

    meson setup "$work/build" "$src" \
        --prefix="$prefix" --libdir=lib \
        --buildtype=release --default-library=shared \
        -Denable_tools=false -Denable_tests=false \
        -Denable_examples=false -Denable_docs=false \
        || { echo "build-vips-full-codecs.sh: dav1d meson configure failed" >&2; exit 1; }
    local nproc
    nproc="$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)"
    if [ "$OS" = darwin ] || [ "$OS" = freebsd ]; then
        ninja -C "$work/build" -j"$nproc" install
    else
        $SUDO ninja -C "$work/build" -j"$nproc" install
        $SUDO ldconfig 2>/dev/null || true
    fi

    export JPDFIUM_DAV1D_PREFIX="$prefix"
    export PKG_CONFIG_PATH="$prefix/lib/pkgconfig:${PKG_CONFIG_PATH:-}"
    export CMAKE_PREFIX_PATH="$prefix:${CMAKE_PREFIX_PATH:-}"
    export LD_LIBRARY_PATH="$prefix/lib:${LD_LIBRARY_PATH:-}"
    echo "==> build-vips-full-codecs.sh: dav1d installed ($prefix)"
}

build_kvazaar() {
    # BSD-licensed HEVC encoder (the GPL x265 must not be bundled).
    # macOS takes the bottled kvazaar from the brew list above; Linux has
    # no kvazaar package on the runner distro, so build the pinned source.
    case "$OS" in linux|freebsd) ;; *) return 0 ;; esac
    # Alpine/musl: install_deps already built kvazaar from source and left a
    # pkg-config file, so reuse it instead of rebuilding via autotools.
    if pkg-config --exists kvazaar 2>/dev/null; then
        echo "==> build-vips-full-codecs.sh: kvazaar already available; skipping source build"
        return 0
    fi
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
    case "$OS" in linux|freebsd) ;; *) return 0 ;; esac
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

    # LERC is a rare TIFF codec and pulls libLerc (~0.5 MB) into every Linux
    # vips bundle; deflate/LZW/JPEG TIFFs are unaffected. libdeflate is tiny
    # and is used for the deflate path (2-3x faster than zlib on TIFF).
    cmake -S "$src" -B "$work/build" \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_INSTALL_PREFIX="$PREFIX" \
        -DCMAKE_DISABLE_FIND_PACKAGE_JBIG=TRUE \
        -Dtiff-tools=OFF -Dtiff-tests=OFF -Dtiff-contrib=OFF -Dtiff-docs=OFF \
        -Dlerc=OFF -DCMAKE_DISABLE_FIND_PACKAGE_LERC=TRUE \
        -Dlibdeflate=ON \
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

    # Put the host prefix first, then prepend the source-built AOM/dav1d.
    # Prepending the host prefix after the source pins lets Homebrew's AOM
    # win the CMake search and forces the install_name patch below.
    export PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig:${PKG_CONFIG_PATH:-}"
    export CMAKE_PREFIX_PATH="$PREFIX:${CMAKE_PREFIX_PATH:-}"
    if [ -n "${JPDFIUM_AOM_PREFIX:-}" ]; then
        export PKG_CONFIG_PATH="$JPDFIUM_AOM_PREFIX/lib/pkgconfig:${PKG_CONFIG_PATH:-}"
        export CMAKE_PREFIX_PATH="$JPDFIUM_AOM_PREFIX:${CMAKE_PREFIX_PATH:-}"
    fi
    if [ -n "${JPDFIUM_DAV1D_PREFIX:-}" ]; then
        export PKG_CONFIG_PATH="$JPDFIUM_DAV1D_PREFIX/lib/pkgconfig:${PKG_CONFIG_PATH:-}"
        export CMAKE_PREFIX_PATH="$JPDFIUM_DAV1D_PREFIX:${CMAKE_PREFIX_PATH:-}"
    fi

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
            PKG_CONFIG_PATH="${PKG_CONFIG_PATH:+$PKG_CONFIG_PATH:}$opt_pc"
        done
        export PKG_CONFIG_PATH="${PKG_CONFIG_PATH:+$PKG_CONFIG_PATH:}$bp/lib/pkgconfig:$bp/share/pkgconfig"
        if [ "${CMAKE_OSX_ARCHITECTURES:-}" = "x86_64" ]; then
            export PKG_CONFIG="$bp/bin/pkg-config"
        fi
    elif [ "$OS" = linux ]; then
        $SUDO apt-get remove -y libheif* 2>/dev/null || true
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
        -DWITH_AOM_DECODER=OFF -DWITH_AOM_ENCODER=ON \
        -DWITH_DAV1D=ON -DWITH_RAV1E=OFF -DWITH_SVT=OFF -DWITH_X264=OFF \
        -DWITH_EXAMPLES=OFF -DWITH_TESTING=OFF \
        || { echo "build-vips-full-codecs.sh: libheif cmake configure failed" >&2; exit 1; }

    # Refuse a silently brew-linked AOM. The install_name patch below is only
    # a compatibility fallback; the CMake cache must point at our source AOM.
    if [ -n "${JPDFIUM_AOM_PREFIX:-}" ]; then
        local aom_lib aom_dir
        aom_lib="$(sed -n 's/^AOM_LIBRARY:[^=]*=//p' "$work/build/CMakeCache.txt")"
        aom_dir="$(sed -n 's/^AOM_DIR:[^=]*=//p' "$work/build/CMakeCache.txt")"
        if [[ "$aom_lib" == "$JPDFIUM_AOM_PREFIX"/* || "$aom_dir" == "$JPDFIUM_AOM_PREFIX"/* ]]; then
            :
        else
            echo "build-vips-full-codecs.sh: libheif selected AOM '${aom_lib:-${aom_dir:-missing}}', not $JPDFIUM_AOM_PREFIX" >&2
            exit 1
        fi
    fi

    # Do not silently ship an AVIF encoder without an AV1 decoder. dav1d is
    # mandatory for every platform in this build.
    local dav1d_lib
    dav1d_lib="$(sed -n 's/^DAV1D_LIBRARY:[^=]*=//p' "$work/build/CMakeCache.txt")"
    if [ -z "$dav1d_lib" ] || [[ "$dav1d_lib" == *NOTFOUND ]]; then
        echo "build-vips-full-codecs.sh: libheif did not find dav1d; refusing to continue" >&2
        exit 1
    fi

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
        # musl/Alpine has no ld.so.conf.d; glibc needs the prefix registered.
        if [ -d /etc/ld.so.conf.d ]; then
            echo "$PREFIX/lib" | $SUDO tee /etc/ld.so.conf.d/00-local.conf >/dev/null
            $SUDO ldconfig 2>/dev/null || true
        fi
    elif [ "$OS" = freebsd ]; then
        cmake --install "$work/build"
    else
        cmake --install "$work/build"
        # libheif resolves AOM through @rpath, and the bundle stage would find
        # brew's libaom (which links libvmaf) before the source-built one.
        # Pin the installed libheif to the vmaf-free AOM explicitly.
        if [ -n "${JPDFIUM_AOM_PREFIX:-}" ] && [ -f "$JPDFIUM_AOM_PREFIX/lib/libaom.3.dylib" ]; then
            for f in "$PREFIX"/lib/libheif*.dylib; do
                [ -f "$f" ] || continue
                for dep in $(otool -L "$f" 2>/dev/null | awk '/libaom\.3\.dylib/ {print $1}'); do
                    install_name_tool -change "$dep" "$JPDFIUM_AOM_PREFIX/lib/libaom.3.dylib" "$f" 2>/dev/null || true
                done
            done
        fi
    fi
    echo "==> build-vips-full-codecs.sh: libheif installed to $PREFIX/lib:"
    ls -la "$PREFIX"/lib/libheif.* 2>/dev/null || true
}

build_vips() {
    echo "==> build-vips-full-codecs.sh: building libvips ${VIPS_TAG}"
    local work
    work="$(mktemp -d)"
    trap 'rm -rf "$work"' RETURN

    if [ -n "${JPDFIUM_AOM_PREFIX:-}" ]; then
        export PKG_CONFIG_PATH="$JPDFIUM_AOM_PREFIX/lib/pkgconfig:${PKG_CONFIG_PATH:-}"
    fi
    if [ -n "${JPDFIUM_DAV1D_PREFIX:-}" ]; then
        export PKG_CONFIG_PATH="$JPDFIUM_DAV1D_PREFIX/lib/pkgconfig:${PKG_CONFIG_PATH:-}"
    fi

    if [ "$OS" = darwin ]; then
        local bp
        bp="$(brew --prefix 2>/dev/null || echo /opt/homebrew)"
        # Keg-only deps (libsharpyuv, zlib, ...) keep their .pc files out of
        # lib/pkgconfig. Without them pkg-config resolves the host copy, which
        # staged an arm64 libsharpyuv into the darwin-x64 bundle.
        local opt_pc
        for opt_pc in "$bp"/opt/*/lib/pkgconfig; do
            [ -d "$opt_pc" ] || continue
            PKG_CONFIG_PATH="${PKG_CONFIG_PATH:+$PKG_CONFIG_PATH:}$opt_pc"
        done
        export PKG_CONFIG_PATH="${PKG_CONFIG_PATH:+$PKG_CONFIG_PATH:}$bp/lib/pkgconfig:$bp/share/pkgconfig"
        if [ "${CMAKE_OSX_ARCHITECTURES:-}" = "x86_64" ]; then
            export PKG_CONFIG="$bp/bin/pkg-config"
        fi
    else
        export PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig:${PKG_CONFIG_PATH:-}"
        export LD_LIBRARY_PATH="$PREFIX/lib:${LD_LIBRARY_PATH:-}"
    fi

    # PDF loading is the core bridge's job. Keeping pdfload here would drag
    # PDFium + Skia + ICU + HarfBuzz into every vips jar for an operation the
    # jpdfium-vips API never calls.
    local pdfium_flag="-Dpdfium=disabled"

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

    local link_flags=()
    if [ "$OS" = linux ]; then
        link_flags=(-Dc_link_args="-Wl,--gc-sections" -Dcpp_link_args="-Wl,--gc-sections")
    elif [ "$OS" = darwin ]; then
        link_flags=(-Dc_link_args="-Wl,-dead_strip" -Dcpp_link_args="-Wl,-dead_strip")
    fi
    local disable_flags=()
    if [ -n "${JPDFIUM_VIPS_DISABLE_CODECS:-}" ]; then
        for dep in $JPDFIUM_VIPS_DISABLE_CODECS; do
            disable_flags+=("-D$dep=disabled")
        done
    fi
    meson setup "$work/build" "$src" \
        --prefix="$PREFIX" --libdir=lib \
        --buildtype=minsize -Db_lto=true \
        -Dauto_features=disabled \
        -Dc_args="-ffunction-sections -fdata-sections" \
        -Ddeprecated=false -Dexamples=false \
        -Dmodules=disabled -Dintrospection=disabled -Dvapi=false \
        -Dcplusplus=false \
        "$pdfium_flag" "$magick_flag" \
        -Drsvg=disabled -Dopenexr=disabled -Draw=disabled \
        -Dspng=enabled -Dhighway=enabled \
        -Dheif=enabled -Djpeg-xl=enabled -Dopenjpeg=enabled \
        -Dwebp=enabled -Dpng=enabled -Djpeg=enabled -Dtiff=enabled \
        -Dexif=enabled -Dlcms=enabled -Dorc=enabled \
        -Dzlib=enabled \
        ${disable_flags[@]+"${disable_flags[@]}"} \
        ${link_flags[@]+"${link_flags[@]}"} \
        || { echo "build-vips-full-codecs.sh: meson configure failed" >&2; exit 1; }

    local nproc
    nproc="$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)"
    ninja -C "$work/build" -j"$nproc" \
        || { echo "build-vips-full-codecs.sh: ninja build failed" >&2; exit 1; }
    if [ "$OS" = darwin ] || [ "$OS" = freebsd ]; then
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
build_dav1d
build_kvazaar
build_libtiff
build_libheif
build_vips
echo "build-vips-full-codecs.sh: done (libvips ${VIPS_TAG})"
