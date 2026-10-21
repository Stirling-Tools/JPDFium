#!/usr/bin/env bash
# Guard the libvips codec contract:
#   - AV1 decode is provided by dav1d;
#   - the AOM library is encoder-only (except Windows arm64, where vcpkg's
#     AOM package is still used);
#   - libdeflate backs TIFF deflate on platforms where we build libtiff.
set -euo pipefail

DIST_DIR="${1:?usage: check-vips-codecs.sh <dist-dir> [platform]}"
PLATFORM="${2:-}"

fail=0
fail_msg() {
    echo "FAIL: $*" >&2
    fail=1
}

imports_symbol() {
    local file="$1" sym="$2" tool
    case "$file" in
        *.dll)
            tool=$(command -v dumpbin 2>/dev/null || true)
            if [ -z "$tool" ]; then
                tool=$(find "/c/Program Files/Microsoft Visual Studio" \
                    -name 'dumpbin.exe' 2>/dev/null | head -1 || true)
            fi
            [ -n "$tool" ] || return 1
            "$tool" //IMPORTS "$file" 2>/dev/null | grep -E "[[:space:]]_?${sym}[[:space:]]*\$" >/dev/null
            ;;
        *)
            nm -D "$file" 2>/dev/null | grep -E " U _?${sym}\$" >/dev/null \
                || nm -g "$file" 2>/dev/null | grep -E " U _?${sym}\$" >/dev/null
            ;;
    esac
}

has_symbol() {
    local file="$1" sym="$2" tool
    case "$file" in
        *.dll)
            tool=$(command -v dumpbin 2>/dev/null || true)
            if [ -z "$tool" ]; then
                tool=$(find "/c/Program Files/Microsoft Visual Studio" \
                    -name 'dumpbin.exe' 2>/dev/null | head -1 || true)
            fi
            if [ -z "$tool" ]; then
                echo "WARNING: dumpbin not found; skipping symbol check for $file" >&2
                return 1
            fi
            "$tool" //EXPORTS "$file" 2>/dev/null | grep -E "[[:space:]]_?${sym}[[:space:]]*\$" >/dev/null
            ;;
        *)
            nm -D "$file" 2>/dev/null | grep -E " T _?${sym}\$" >/dev/null \
                || nm -g "$file" 2>/dev/null | grep -E " T _?${sym}\$" >/dev/null
            ;;
    esac
}

dav1d=$(find "$DIST_DIR" -maxdepth 1 \( -type f -o -type l \) \
    \( -name 'libdav1d.*' -o -name 'dav1d.dll' \) -print -quit 2>/dev/null || true)
if [ -z "$dav1d" ]; then
    fail_msg "dav1d is missing from $DIST_DIR (AV1 decode must not depend on AOM)"
elif ! has_symbol "$dav1d" dav1d_open; then
    fail_msg "$(basename "$dav1d") does not export dav1d_open"
fi

heif=$(find "$DIST_DIR" -maxdepth 1 \( -type f -o -type l \) \
    \( -name 'libheif.*' -o -name 'heif.dll' \) -print -quit 2>/dev/null || true)
if [ -n "$heif" ] && ! imports_symbol "$heif" dav1d_open; then
    fail_msg "$(basename "$heif") does not import dav1d_open"
fi

aom=$(find "$DIST_DIR" -maxdepth 1 \( -type f -o -type l \) \
    \( -name 'libaom.*' -o -name 'aom.dll' \) -print -quit 2>/dev/null || true)
if [ -n "$aom" ] && has_symbol "$aom" aom_codec_av1_dx; then
    case "$PLATFORM" in
        vips-windows-arm64)
            echo "WARNING: Windows arm64 still uses vcpkg AOM with its decoder" >&2
            ;;
        *)
            fail_msg "$(basename "$aom") still contains the AOM AV1 decoder"
            ;;
    esac
fi

case "$PLATFORM" in
    vips-darwin-*|darwin-*)
        # macOS uses Homebrew's libtiff; the libtiff build with libdeflate is
        # Linux/FreeBSD-only.
        ;;
    vips-windows-*|windows-*)
        # The vcpkg tiff port uses static libdeflate, so there is no separate
        # DLL to stage. The manifest feature is the contract on Windows.
        ;;
    *)
        if ! find "$DIST_DIR" -maxdepth 1 -type f -name 'libdeflate*' -print -quit 2>/dev/null | grep . >/dev/null; then
            fail_msg "libdeflate is missing from $DIST_DIR"
        fi
        ;;
esac

if [ "$fail" -ne 0 ]; then
    echo "ERROR: libvips codec contract failed for $PLATFORM" >&2
    exit 1
fi
echo "OK: libvips codec contract ($PLATFORM)"
