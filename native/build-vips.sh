#!/usr/bin/env bash
# Stage a hermetic libvips (+ glib/gobject + the codec chain: libheif, libjxl,
# libaom, libwebp, libpng, libjpeg, ...) into native/dist/vips-<platform>/ for
# the jpdfium-natives-vips-* jars.
#
# libvips is sourced from the system package manager (brew on macOS, apt on
# Linux) or a prebuilt Windows dist (libvips/build-win64-mxe, pointed at by
# VIPS_WIN_DIST), then bundle-runtime-deps.sh recursively copies its transitive
# shared deps next to it and rewrites RUNPATH / @loader_path / $ORIGIN so
# libvips resolves its sibling codecs in the same dir at runtime.
#
# The resulting natives jar is consumed on the Java side by VipsNatives, which
# extracts it and points vips-ffm at the bundled libvips (optional - the
# jpdfium-vips module also works with a system libvips when no jar is present).
#
# Usage: build-vips.sh <platform>   e.g. linux-x64, darwin-arm64, windows-x64
set -euo pipefail

# Vips natives are in bring-up (continue-on-error CI job, publishes nothing
# yet), so allow unsigned macOS dylibs. When vips graduates to a publishing
# workflow, set MACOS_SIGN_IDENTITY there instead.
export MACOS_ALLOW_UNSIGNED="${MACOS_ALLOW_UNSIGNED:-1}"

PLATFORM="${1:?platform required}"
case "$PLATFORM" in
    linux-*)
        OS=linux
        LIBVIPS_NAME="libvips.so.42"
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

# Resolve the source libvips. echoes either the lib path (POSIX) or, on
# Windows, the prebuilt dist's bin/ dir (we copy its whole DLL tree).
# Prefers the full-codec libvips built by build-vips-full-codecs.sh into
# /usr/local (HEIF/HEIC/AVIF/JXL/WebP/PNG/JPEG/TIFF) over the distro package.
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
            ;;
    esac
    return 1
}

VIPS_LOC="$(resolve_libvips || true)"
if [ -z "$VIPS_LOC" ]; then
    echo "ERROR: libvips ($LIBVIPS_NAME) not found for $PLATFORM." >&2
    echo "Install: brew install vips (macOS) / apt install libvips-dev (Linux) /" >&2
    echo "set VIPS_WIN_DIST to an extracted libvips/build-win64-mxe dist (Windows)." >&2
    exit 1
fi

if [ "$OS" = "windows" ]; then
    # The prebuilt Windows dist already ships its full hermetic DLL tree in
    # bin/ - copy it wholesale. The bundler below then walks vips.dll's import
    # table but finds every sibling already present in DIST.
    cp -v "$VIPS_LOC"/*.dll "$DIST"/ 2>/dev/null || true
else
    cp -v "$VIPS_LOC" "$DIST/"
fi

# Recursively bundle libvips's transitive deps + rewrite load paths by reusing
# the bridge bundler rooted at the staged libvips (BUNDLE_ROOT).
export BUNDLE_ROOT="$DIST/$LIBVIPS_NAME"
bash "$(dirname "$0")/bundle-runtime-deps.sh" "vips-$PLATFORM"

echo ""
echo "Staged libvips for vips-$PLATFORM into $DIST:"
ls -la "$DIST/"
