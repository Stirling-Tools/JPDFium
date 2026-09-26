#!/usr/bin/env bash
# Stage a hermetic libvips (+ glib/gobject + the codec chain: libheif, libjxl,
# libaom, libwebp, libpng, libjpeg, ...) into native/dist/vips-<platform>/ for
# the jpdfium-natives-vips-* jars.
set -euo pipefail

export MACOS_ALLOW_UNSIGNED="${MACOS_ALLOW_UNSIGNED:-1}"

PLATFORM="${1:?platform required}"
case "$PLATFORM" in
    linux-*)
        OS=linux
        LIBVIPS_NAME="libvips.so.42"
        # native/pdfium/lib holds the component PDFium the vips build links;
        # ldd needs it on the search path to stage libpdfium + its deps.
        PDFIUM_LIB_DIR="$(dirname "$0")/pdfium/lib"
        export LD_LIBRARY_PATH="/usr/local/lib:${PDFIUM_LIB_DIR}:${LD_LIBRARY_PATH:-}"
        ;;
    darwin-*)
        OS=darwin
        LIBVIPS_NAME="libvips.42.dylib"
        ;;
    windows-*)
        OS=windows
        LIBVIPS_NAME="vips.dll"
        ;;
    *)
        echo "ERROR: unknown platform: $PLATFORM" >&2
        exit 1
        ;;
esac

DIST="native/dist/vips-$PLATFORM"
mkdir -p "$DIST"

resolve_libvips() {
    case "$OS" in
        linux)
            local p
            [ -f /usr/local/lib/"$LIBVIPS_NAME" ] && { echo /usr/local/lib/"$LIBVIPS_NAME"; return 0; }
            p=$(ldconfig -p 2>/dev/null | awk -v n="$LIBVIPS_NAME" '$1==n{print $NF; exit}' || true)
            [ -n "$p" ] && [ -f "$p" ] && { echo "$p"; return 0; }
            p=$(find /usr/lib /usr/local/lib -name "$LIBVIPS_NAME" 2>/dev/null | head -n1 || true)
            [ -n "$p" ] && { echo "$p"; return 0; }
            ;;
        darwin)
            local prefix p
            [ -f /usr/local/lib/"$LIBVIPS_NAME" ] && { echo /usr/local/lib/"$LIBVIPS_NAME"; return 0; }
            prefix="$(brew --prefix vips 2>/dev/null || true)"
            [ -n "$prefix" ] && [ -f "$prefix/lib/$LIBVIPS_NAME" ] && { echo "$prefix/lib/$LIBVIPS_NAME"; return 0; }
            p=$(find /opt/homebrew/lib /usr/local/lib -name "$LIBVIPS_NAME" 2>/dev/null | head -n1 || true)
            [ -n "$p" ] && { echo "$p"; return 0; }
            ;;
        windows)
            local d="${VIPS_WIN_DIST:-}"
            [ -n "$d" ] && [ -d "$d/bin" ] && { echo "$d/bin"; return 0; }
            [ -n "$d" ] && [ -f "$d/bin/vips.dll" ] && { echo "$d/bin"; return 0; }
            ;;
    esac
    return 1
}

# In CI the source build must have produced the libvips we bundle: falling
# back to a system/brew libvips silently ships its whole dependency set
# (librsvg/X11/...) and a different feature set. JPDFIUM_REQUIRE_SOURCE_VIPS=1
# turns that fallback into an error.
if [ "${JPDFIUM_REQUIRE_SOURCE_VIPS:-}" = "1" ]; then
    case "$OS" in
        linux) REQUIRED_VIPS="/usr/local/lib/$LIBVIPS_NAME" ;;
        darwin) REQUIRED_VIPS="$(brew --prefix 2>/dev/null || echo /opt/homebrew)/lib/$LIBVIPS_NAME" ;;
        *) REQUIRED_VIPS="" ;;
    esac
    if [ -n "$REQUIRED_VIPS" ] && [ ! -f "$REQUIRED_VIPS" ]; then
        echo "ERROR: source-built libvips not found at $REQUIRED_VIPS." >&2
        echo "The full-codecs build must succeed; refusing to bundle a system libvips." >&2
        exit 1
    fi
fi

VIPS_LOC="$(resolve_libvips || true)"
if [ -z "$VIPS_LOC" ]; then
    echo "ERROR: libvips ($LIBVIPS_NAME) not found for $PLATFORM." >&2
    echo "Install: brew install vips (macOS) / apt install libvips-dev (Linux) /" >&2
    echo "set VIPS_WIN_DIST to an extracted libvips/build-win64-mxe dist (Windows)." >&2
    exit 1
fi

if [ "$OS" = "windows" ]; then
    cp -v "$VIPS_LOC"/*.dll "$DIST"/ 2>/dev/null || true
    # The upstream MXE zip is GPL-contaminated: libpoppler (unused) and
    # libfftw3 (unused) can be dropped, but libimagequant is linked into
    # libvips-42.dll. Windows vips must come from the GPL-free source
    # prebuild (prebuild-vips.yml); this path is a hard error now.
    rm -f "$DIST"/libpoppler*.dll "$DIST"/libfftw3*.dll
    if ls "$DIST"/libimagequant*.dll >/dev/null 2>&1; then
        echo "ERROR: upstream vips zip links GPL-3 libimagequant." >&2
        echo "Use the pinned GPL-free Windows prebuild (native/vips.version)." >&2
        exit 1
    fi
    # The JXL codec ships as a loadable module, not linked into libvips.
    if [ -f "$VIPS_LOC/vips-modules-8.18/vips-jxl.dll" ]; then
        cp -v "$VIPS_LOC/vips-modules-8.18/vips-jxl.dll" "$DIST"/
    else
        echo "WARNING: vips-jxl.dll plugin not found under $VIPS_LOC" >&2
    fi
    # Both bundles ship their own LLVM libc++ under the same file name and
    # Windows binds loaded DLLs by name process-wide, so rename ours (plus
    # every import reference to it) to coexist with the core bundle's copy.
    "$(command -v python3 || command -v python)" "$(dirname "$0")/patch-windows-libcxx.py" "$DIST"
else
    cp -v "$VIPS_LOC" "$DIST/"
    # If libvips has dynamic modules (e.g. Homebrew vips-modules-*/vips-heif.dylib, vips-jxl.dylib),
    # copy them directly into DIST so their symbols & codecs are bundled and resolved alongside libvips
    VIPS_LIB_DIR="$(dirname "$VIPS_LOC")"
    find "$VIPS_LIB_DIR"/vips-modules* -name "*.dylib" -o -name "*.so" 2>/dev/null | while read -r mod; do
        [ -f "$mod" ] && cp -v "$mod" "$DIST/"
    done || true
fi

if [ "$OS" = "windows" ]; then
    export BUNDLE_ROOT="$DIST/libvips-42.dll"
else
    export BUNDLE_ROOT="$DIST/$LIBVIPS_NAME"
fi
bash "$(dirname "$0")/bundle-runtime-deps.sh" "vips-$PLATFORM"

echo ""
echo "Staged libvips for vips-$PLATFORM into $DIST:"
ls -la "$DIST/"
