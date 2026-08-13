#!/usr/bin/env bash
# Wrap the pre-trimmed ICU 78 .dat (produced once on Linux by
# build-minimal-icu-windows.sh - same data file, OS-agnostic) into a
# macOS libicudata.<MAJ>.dylib via a clang .incbin asm stub.
#
# Why not trim locally on macOS: brew's icu4c@78 bottle doesn't ship
# icupkg / pkgdata, and we'd otherwise have to build ICU 78 from source
# on the runner just to get those two tools. The Linux prep job has apt's
# icu-devtools already, AND downloads upstream ICU 78's pre-assembled
# .dat from the github.com/unicode-org/icu release. We let it do the
# heavy lifting; this script just wraps the bytes in a Mach-O dylib.
#
# Input  : native/dist/icu-data/icudt78l.dat  (downloaded as artifact)
# Output : native/dist/<darwin-*>/libicudata.<MAJ>.dylib  (pre-staged so
#          the bundler picks it up instead of brew's full 33 MB copy)
#
# Best-effort: any failure exits 0 - bundle falls back to brew's full
# icudata (or, on arm64 where bundle_macos's rpath resolver doesn't
# search icu4c@78's keg-only path, to current "no libicudata" state).

echo "build-minimal-icu-macos.sh: start  ($(uname -s) $(uname -m))"

case "$(uname -s)" in
    Darwin*) ;;
    *) echo "build-minimal-icu-macos.sh: skipping on $(uname -s)"; exit 0;;
esac

set -u

# Pick the right target arch + dist subdir. Same selection logic as the
# other macOS scripts (build-harfbuzz-no-glib.sh, build-qpdf-native-crypto.sh).
if [ "${CMAKE_OSX_ARCHITECTURES:-}" = "x86_64" ]; then
    TARGET_ARCH=x86_64
    PLATFORM=darwin-x64
else
    TARGET_ARCH=arm64
    PLATFORM=darwin-arm64
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SHARED_DAT_DIR="$SCRIPT_DIR/dist/icu-data"
PLATFORM_DIST="$SCRIPT_DIR/dist/$PLATFORM"

# Find the prepared .dat. The Linux prep job names it icudt<MAJ>l.dat
# under native/dist/icu-data/; the workflow downloads it before this step.
TRIMMED_DAT=""
for cand in "$SHARED_DAT_DIR"/icudt*l.dat; do
    [ -f "$cand" ] || continue
    TRIMMED_DAT="$cand"
    break
done
if [ -z "$TRIMMED_DAT" ]; then
    echo "build-minimal-icu-macos.sh: no prepared icudt<MAJ>l.dat under $SHARED_DAT_DIR; skipping" >&2
    ls -la "$SHARED_DAT_DIR" 2>/dev/null >&2 || true
    exit 0
fi

# Parse "icudt78l.dat" → MAJ.
DAT_BASE=$(basename "$TRIMMED_DAT")
ICU_VER=$(echo "$DAT_BASE" | sed -n 's/^icudt\([0-9][0-9]*\)l\.dat$/\1/p')
if [ -z "$ICU_VER" ]; then
    echo "build-minimal-icu-macos.sh: can't parse MAJOR from $DAT_BASE; skipping" >&2
    exit 0
fi
echo "Input .dat  : $TRIMMED_DAT ($(du -h "$TRIMMED_DAT" | cut -f1)) [ICU $ICU_VER, $TARGET_ARCH]"

# Sanity-check ICU header magic (bytes 2..3 = 0xda 0x27).
MAGIC_HEX=$(xxd -p -l 4 "$TRIMMED_DAT" 2>/dev/null)
if [[ ! "$MAGIC_HEX" =~ ....da27$ ]]; then
    echo "build-minimal-icu-macos.sh: $TRIMMED_DAT has bad ICU magic (got $MAGIC_HEX); skipping" >&2
    exit 0
fi

# Verify the workflow's brew icu4c is the same MAJOR. If brew is at a
# different version (e.g. icu4c@79 in the future), wrapping a 78 .dat
# would still produce a 78-named dylib but libicuuc would look for 79 -
# skip to avoid shipping a useless lib.
BREW_PREFIX=""
if [ "$TARGET_ARCH" = "x86_64" ] && [ -x /usr/local/bin/brew ]; then
    BREW_PREFIX=$(/usr/local/bin/brew --prefix icu4c 2>/dev/null || true)
elif command -v brew >/dev/null 2>&1; then
    BREW_PREFIX=$(brew --prefix icu4c 2>/dev/null || true)
fi
if [ -n "$BREW_PREFIX" ] && [ -d "$BREW_PREFIX/lib" ]; then
    BREW_SONAME=""
    for cand in "$BREW_PREFIX/lib/"libicudata.*.dylib; do
        base=$(basename "$cand")
        if echo "$base" | grep -qE '^libicudata\.[0-9]+\.dylib$'; then
            BREW_SONAME="$cand"
            break
        fi
    done
    if [ -n "$BREW_SONAME" ]; then
        BREW_VER=$(basename "$BREW_SONAME" | sed -n 's/^libicudata\.\([0-9][0-9]*\)\.dylib$/\1/p')
        if [ -n "$BREW_VER" ] && [ "$BREW_VER" != "$ICU_VER" ]; then
            echo "build-minimal-icu-macos.sh: prep ICU $ICU_VER ≠ brew ICU $BREW_VER; skipping" >&2
            exit 0
        fi
        echo "  brew icu4c  : ICU $BREW_VER ($BREW_SONAME) - matches"
    fi
fi

WORK=$(mktemp -d)
trap "rm -rf '$WORK'" EXIT

# Wrap the .dat into a Mach-O dylib via a one-liner asm stub. Apple's
# clang understands the GNU-style .incbin directive when fed a .S file.
# ICU's runtime expects 16-byte alignment on the symbol → .p2align 4.
ASM="$WORK/icudata_dat.S"
cat >"$ASM" <<EOF
.section __DATA,__const
.globl _icudt${ICU_VER}_dat
.p2align 4
_icudt${ICU_VER}_dat:
.incbin "$TRIMMED_DAT"
EOF

NEW_LIB="$WORK/libicudata.${ICU_VER}.dylib"
clang -arch "$TARGET_ARCH" -dynamiclib \
    -install_name "@loader_path/libicudata.${ICU_VER}.dylib" \
    -compatibility_version "${ICU_VER}.0.0" \
    -current_version "${ICU_VER}.1.0" \
    -o "$NEW_LIB" \
    "$ASM" \
    || { echo "build-minimal-icu-macos.sh: clang link failed; skipping" >&2; exit 0; }

echo "Built       : $NEW_LIB ($(du -h "$NEW_LIB" | cut -f1))"

echo "--- otool -D ---"
otool -D "$NEW_LIB" || true
echo "--- exported symbol ---"
if ! nm -gU "$NEW_LIB" 2>/dev/null | grep -qE "_icudt${ICU_VER}_dat"; then
    echo "build-minimal-icu-macos.sh: _icudt${ICU_VER}_dat not exported; skipping" >&2
    exit 0
fi

# Pre-stage into native/dist/<platform>/. bundle-runtime-deps.sh's
# macOS branch checks dest existence before cp'ing in its rpath/loader-path
# walk; if our trimmed copy is already there, brew's 33 MB one is left out.
# On darwin-arm64 (where bundle_macos's rpath resolver doesn't search
# /opt/homebrew/opt/icu4c@78/lib and currently SKIPS libicudata.78.dylib
# entirely), this pre-stage still gets uploaded as part of native/dist/<plat>/
# via the upload-artifact step - so the final natives jar contains a working
# libicudata that libicuuc.78.dylib's @loader_path lookup will resolve at
# runtime.
mkdir -p "$PLATFORM_DIST"
cp -v "$NEW_LIB" "$PLATFORM_DIST/libicudata.${ICU_VER}.dylib"
echo "Pre-staged  : $PLATFORM_DIST/libicudata.${ICU_VER}.dylib ($(du -h "$PLATFORM_DIST/libicudata.${ICU_VER}.dylib" | cut -f1))"
echo "  → bundler will see this file and skip copying brew's full 33 MB libicudata."
