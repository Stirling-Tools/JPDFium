#!/usr/bin/env bash
# Cross-build a trimmed icudt78.dll for Windows from a Linux runner.
#
# Why on Linux: the Windows runner doesn't have icupkg.exe / pkgdata.exe
# (vcpkg's icu port skips installing the tools dir on Windows targets), but
# Linux ubuntu-latest does via icu-devtools. The .dat file format itself is
# platform-portable — items inside are byte-for-byte the same whether held
# in a Linux .so or a Windows .dll, what differs is the binary wrapper.
#
# Pipeline:
#   1. Download upstream ICU 78 Win64 prebuilt + extract icudt78.dll
#   2. mingw-w64 objcopy --dump-section .rdata=icudt78l.dat icudt78.dll
#      (the .rdata IS the .dat, with our specific PE layout the section
#       header offset matches the symbol)
#   3. icupkg -x '*' → loose items (ICU 74 icupkg reads ICU 78 .dat fine;
#      .dat archive format has been stable since ICU 4.x and items inside
#      keep their original ICU 78 byte content untouched)
#   4. Build keep-list (same patterns as the Linux trim — cnvalias, uchar,
#      ubidi, unames, ulayout, ucase, uemoji, nfc/nfkc/nfkc_cf, brkitr/,
#      root.res + en.res + en_US.res + pool.res)
#   5. pkgdata -m archive packages the kept items into a fresh .dat
#   6. mingw-w64 objcopy + gcc -shared wraps the trimmed .dat into a new
#      icudt78.dll exporting icudt78_dat
#   7. Pre-stage into native/dist/windows-x64/ for upload-artifact / pickup
#      by the Windows job's Stage binaries step.
#
# Best-effort: every failure path is exit 0 with a message — the Windows
# job will fall back to vcpkg's full icudt78.dll (~33 MB) if anything here
# misfires. No size regression vs current state.

echo "build-minimal-icu-windows.sh: start ($(uname -s) $(uname -m))"

case "$(uname -s)" in
    Linux*) ;;
    *) echo "build-minimal-icu-windows.sh: not Linux; skipping"; exit 0;;
esac

set -u

# Prereqs
for tool in icupkg pkgdata x86_64-w64-mingw32-gcc x86_64-w64-mingw32-objcopy python3 unzip curl; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "build-minimal-icu-windows.sh: $tool not found; skipping" >&2
        exit 0
    fi
done

ICU_VER=78
ICU_MINOR=1
# ICU's release-tag and asset-name conventions vary across ICU major
# versions: ICU 74 uses release-74-2 + icu4c-74_2-..., ICU 78 uses
# release-78.1 + icu4c-78.1-.... Try the newer format first, fall back
# to the legacy one if the modern asset 404s.
ICU_TAG_NEW="release-${ICU_VER}.${ICU_MINOR}"
ICU_TAG_OLD="release-${ICU_VER}-${ICU_MINOR}"
WORK=$(mktemp -d)
trap "rm -rf '$WORK'" EXIT

# Step 1: Download upstream ICU's pre-assembled little-endian .dat. This is
# the SAME byte sequence that ends up baked into icudt<MAJ>.dll on Windows,
# packaged as a loose .dat by the ICU release process, so we don't have to
# parse PE format to extract it.
echo "Downloading ICU $ICU_VER.$ICU_MINOR data-bin-l..."
ZIP="$WORK/icu-data-bin-l.zip"
candidates=(
    "${ICU_TAG_NEW}/icu4c-${ICU_VER}.${ICU_MINOR}-data-bin-l.zip"
    "${ICU_TAG_OLD}/icu4c-${ICU_VER}_${ICU_MINOR}-data-bin-l.zip"
)
for path in "${candidates[@]}"; do
    URL="https://github.com/unicode-org/icu/releases/download/${path}"
    if curl -fsSL "$URL" -o "$ZIP" 2>/dev/null; then
        echo "  got $URL"
        break
    fi
done
if [ ! -s "$ZIP" ]; then
    echo "build-minimal-icu-windows.sh: couldn't fetch upstream ICU $ICU_VER data-bin-l zip; skipping" >&2
    exit 0
fi

unzip -q "$ZIP" -d "$WORK/data-bin-extract"
DAT_FILE=$(find "$WORK/data-bin-extract" -name "icudt${ICU_VER}l.dat" -type f 2>/dev/null | head -1)
if [ -z "$DAT_FILE" ] || [ ! -f "$DAT_FILE" ]; then
    echo "build-minimal-icu-windows.sh: icudt${ICU_VER}l.dat not found in upstream zip; skipping" >&2
    exit 0
fi

# Sanity-check ICU header magic (bytes 2..3 = 0xda 0x27).
MAGIC=$(xxd -p -l 4 "$DAT_FILE" 2>/dev/null)
if [[ ! "$MAGIC" =~ ....da27$ ]]; then
    echo "build-minimal-icu-windows.sh: .dat magic check failed (got $MAGIC); skipping" >&2
    exit 0
fi
echo "Source .dat : $DAT_FILE ($(du -h "$DAT_FILE" | cut -f1))"

# Step 3: Extract items. ICU 74's icupkg can read ICU 78 .dat because the
# archive format itself has been stable since ICU 4.x — only the items
# inside have their own (independent) format versions which we don't touch.
EXTRACT="$WORK/extract"
mkdir -p "$EXTRACT"
icupkg -x '*' -d "$EXTRACT" "$DAT_FILE" \
    || { echo "build-minimal-icu-windows.sh: icupkg -x failed; skipping" >&2; exit 0; }

icupkg -l "$DAT_FILE" > "$WORK/all.lst"
TOTAL=$(wc -l < "$WORK/all.lst")
echo "Total items in source : $TOTAL"

# Step 4: Build keep list — same patterns as the Linux trim. See
# build-minimal-icu.sh for the full rationale; bridge doesn't use
# unames/uemoji/nfkc/nfkc_cf/en_US so they're dropped.
KEEP=(
    '^cnvalias\.icu$' '^uchar\.icu$' '^ubidi\.icu$'
    '^ulayout\.icu$' '^ucase\.icu$'
    '^nfc\.nrm$'
    '^brkitr/' '^root\.res$' '^en\.res$'
)

> "$WORK/keep.lst"
declare -A SEEN_KEEP=()
echo "pool.res" >> "$WORK/keep.lst"
SEEN_KEEP[pool.res]=1
while IFS= read -r item; do
    matched=0
    for pat in "${KEEP[@]}"; do
        if echo "$item" | grep -qE "$pat"; then
            matched=1; break
        fi
    done
    if [ "$matched" = 1 ] && [ -z "${SEEN_KEEP[$item]:-}" ]; then
        echo "$item" >> "$WORK/keep.lst"
        SEEN_KEEP[$item]=1
    fi
done < "$WORK/all.lst"
KEPT=$(wc -l < "$WORK/keep.lst")
echo "Keeping  : $KEPT items (out of $TOTAL)"

# Step 5: pkgdata -m archive — no compiler needed, just data layout.
ICUPKG_INC=$(find /usr/lib /usr/share -maxdepth 6 -type f \
              \( -name "pkgdata.inc" -o -name "icupkg.inc" -o -name "Makefile.inc" \) \
              2>/dev/null | grep -iE '/icu(/|$)' | head -1)
if [ -z "$ICUPKG_INC" ]; then
    echo "build-minimal-icu-windows.sh: pkgdata.inc not found; skipping" >&2
    exit 0
fi
OUT_DIR="$WORK/out"
mkdir -p "$OUT_DIR" "$WORK/pkg-tmp"
pkgdata -m archive -p icudata -e "icudt${ICU_VER}_dat" \
        -O "$ICUPKG_INC" -T "$WORK/pkg-tmp" -d "$OUT_DIR" \
        -s "$EXTRACT" "$WORK/keep.lst" \
    || { echo "build-minimal-icu-windows.sh: pkgdata archive failed; skipping" >&2; exit 0; }

TRIMMED_DAT="$OUT_DIR/icudata.dat"
if [ ! -f "$TRIMMED_DAT" ]; then
    TRIMMED_DAT=$(find "$OUT_DIR" -maxdepth 2 -name "*.dat" -type f | head -1)
fi
if [ -z "$TRIMMED_DAT" ] || [ ! -f "$TRIMMED_DAT" ]; then
    echo "build-minimal-icu-windows.sh: no trimmed .dat produced; skipping" >&2
    exit 0
fi
echo "Trimmed .dat : $TRIMMED_DAT ($(du -h "$TRIMMED_DAT" | cut -f1))"

# Step 6: mingw-w64 objcopy turns the raw .dat into a PE .o exporting the
# icudt<MAJ>_dat symbol; then mingw-w64 gcc -shared links into a Windows DLL.
TRIMMED_BASENAME=$(basename "$TRIMMED_DAT")
TRIMMED_DIR=$(dirname "$TRIMMED_DAT")
OBJ="$WORK/icudata_dat.o"
( cd "$TRIMMED_DIR" && \
  x86_64-w64-mingw32-objcopy -I binary -O pe-x86-64 -B i386:x86-64 \
      --redefine-sym "_binary_${TRIMMED_BASENAME//./_}_start=icudt${ICU_VER}_dat" \
      --rename-section .data=.rdata,alloc,load,readonly,data,contents \
      --set-section-alignment .rdata=16 \
      "$TRIMMED_BASENAME" "$OBJ" ) \
    || { echo "build-minimal-icu-windows.sh: mingw objcopy failed; skipping" >&2; exit 0; }

# DEF file declares the data export so the linker emits an .edata entry.
DEF="$WORK/icudata.def"
cat > "$DEF" <<EOF
LIBRARY icudt${ICU_VER}.dll
EXPORTS
icudt${ICU_VER}_dat DATA
EOF

NEW_DLL="$OUT_DIR/icudt${ICU_VER}.dll"
x86_64-w64-mingw32-gcc -shared -nostartfiles \
    -Wl,--enable-stdcall-fixup \
    -o "$NEW_DLL" \
    "$OBJ" "$DEF" \
    || { echo "build-minimal-icu-windows.sh: mingw gcc -shared failed; skipping" >&2; exit 0; }

# Sanity check: verify icudt78_dat is actually exported.
if ! x86_64-w64-mingw32-objdump -p "$NEW_DLL" 2>/dev/null | grep -qE "icudt${ICU_VER}_dat"; then
    echo "build-minimal-icu-windows.sh: icudt${ICU_VER}_dat NOT exported by new DLL; skipping" >&2
    exit 0
fi
echo "Built       : $NEW_DLL ($(du -h "$NEW_DLL" | cut -f1))"

# Pre-stage into native/dist/windows-x64/. On the Linux runner the directory
# may not yet exist; the bundling job on the Windows runner will create it
# and (because we upload the .dll as a separate artifact below) download it
# before its own Stage binaries step runs.
PLATFORM_DIST="$(dirname "$0")/dist/windows-x64"
mkdir -p "$PLATFORM_DIST"
cp -v "$NEW_DLL" "$PLATFORM_DIST/icudt${ICU_VER}.dll"
echo "Pre-staged  : $PLATFORM_DIST/icudt${ICU_VER}.dll"
