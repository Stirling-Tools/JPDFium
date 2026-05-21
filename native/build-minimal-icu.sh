#!/usr/bin/env bash
# Build a smaller libicudata replacement, stripping items the JPDFium bridge
# doesn't use. Run on Linux CI before bundle-runtime-deps.sh; the trimmed
# libicudata.so.<ver> replaces the apt-installed one in the bundled natives.
#
# JPDFium's ICU usage (verified by grep over native/bridge/src/):
#   - u_strFromUTF8                  (UTF-8 <-> UTF-16; needs cnvalias)
#   - icu::Normalizer / unorm_*      (NFC / NFKC; needs nfc, nfkc, nfkc_cf)
#   - icu::BreakIterator             (sentence/word/line boundaries; needs brkitr/*)
#   - ubidi_*                        (BiDi text; needs ubidi data)
#   - basic uchar properties         (always needed)
#
# We DON'T need: full locale data, region data, currency data, transliterations,
# collations (sorting), RBNF (spell-out numbers), units, or non-essential
# converters. That's ~25 MB of the 30 MB default data file.
#
# Usage: build-minimal-icu.sh        # Linux only; macOS embeds data; Windows TBD

# Best-effort: never break the build because of ICU trimming. The bundle step
# falls back to the full apt-installed data if we skip out here.
echo "build-minimal-icu.sh: start  ($(uname -s) $(uname -m))"

case "$(uname -s)" in
    Linux*) ;;
    *) echo "build-minimal-icu.sh: skipping on $(uname -s)"; exit 0;;
esac

# From here on, use set -u (unset vars are errors) but DO NOT use set -e:
# any individual command failure should produce a helpful message and exit 0,
# not exit-1-with-no-output (which we've hit at least once on CI under
# `bash --noprofile --norc -e -o pipefail`).
set -u

if ! command -v icupkg >/dev/null 2>&1; then
    echo "build-minimal-icu.sh: icupkg not found (apt install icu-devtools)" >&2
    exit 0
fi
if ! command -v pkgdata >/dev/null 2>&1; then
    echo "build-minimal-icu.sh: pkgdata not found (apt install icu-devtools)" >&2
    exit 0
fi

# Auto-detect ICU major.minor version. icupkg --version prints e.g.
# "icupkg version 74.2 ..." so we grab the first M.N.
ICU_FULL_VER=$(icupkg --version 2>&1 | grep -oE '[0-9]+\.[0-9]+' | head -1)
ICU_FULL_VER=${ICU_FULL_VER:-74.2}
ICU_VER=$(echo "$ICU_FULL_VER" | cut -d. -f1)
echo "ICU detected : v${ICU_FULL_VER} (major ${ICU_VER})"

# The Ubuntu binary packages (libicu-dev, icu-devtools) DO NOT ship the
# source icudt<MAJ>l.dat file — it's baked into libicudata.so.<MAJ>.<MIN> at
# build time via genccode. The upstream icu4c-*-data.zip contains the loose
# source items (.icu, .nrm) but no pre-assembled .dat either. So we extract
# the data straight out of the system .so by reading the icudt<MAJ>_dat ELF
# symbol's bytes — that symbol IS the .dat file, byte-for-byte.
WORK=$(mktemp -d)
trap "rm -rf '$WORK'" EXIT

# Find the *real* libicudata.so.<MAJ>.<MIN> file (not the SONAME symlink) so
# `cp` later actually overwrites the data carrier. Group the -name predicates
# with -type f under parens; find's -o has lower precedence than implicit -a.
ORIG_LIB=$(find /usr/lib /lib -maxdepth 4 -type f \
            \( -name "libicudata.so.${ICU_VER}.*" -o -name "libicudata.so.${ICU_VER}" \) \
            2>/dev/null | head -1)
if [ -z "$ORIG_LIB" ] || [ ! -f "$ORIG_LIB" ]; then
    echo "build-minimal-icu.sh: libicudata.so not found under /usr/lib; skipping" >&2
    exit 0
fi
echo "Source lib  : $ORIG_LIB ($(du -h "$ORIG_LIB" | cut -f1))"

# Extract the embedded .dat. readelf -s prints lines like:
#   "    11: 0000000000043000 31543216 OBJECT  GLOBAL DEFAULT   13 icudt74_dat"
# Columns: Num  Value  Size  Type  Bind  Vis  Ndx  Name
SYM_LINE=$(readelf -W -s "$ORIG_LIB" 2>/dev/null | awk -v sym="icudt${ICU_VER}_dat" '$NF==sym' | head -1)
if [ -z "$SYM_LINE" ]; then
    echo "build-minimal-icu.sh: icudt${ICU_VER}_dat symbol not found in $ORIG_LIB; skipping" >&2
    exit 0
fi
SYM_VADDR_HEX=$(echo "$SYM_LINE" | awk '{print $2}')
SYM_SIZE=$(echo "$SYM_LINE" | awk '{print $3}')
SYM_SECTION_IDX=$(echo "$SYM_LINE" | awk '{print $7}')

# readelf -S prints sections like:
#   "  [13] .rodata           PROGBITS         0000000000041280  00041280  ..."
# Columns: [Idx] Name  Type  Addr  Off  Size  ...
SEC_LINE=$(readelf -W -S "$ORIG_LIB" 2>/dev/null | awk -v idx="[${SYM_SECTION_IDX}]" '$1==idx' | head -1)
if [ -z "$SEC_LINE" ]; then
    echo "build-minimal-icu.sh: couldn't find section [${SYM_SECTION_IDX}]; skipping" >&2
    exit 0
fi
SEC_VADDR_HEX=$(echo "$SEC_LINE" | awk '{print $4}')
SEC_FOFF_HEX=$(echo "$SEC_LINE" | awk '{print $5}')

DAT_FILE="$WORK/icudt${ICU_VER}l.dat"
# File offset = section file offset + (sym vaddr - section vaddr).
# Extract via Python (way faster than dd bs=1 for 30 MB) and sanity-check
# the ICU header magic (bytes 2..3 = 0xda 0x27) before proceeding.
#
# Address columns from readelf -W are hex without a "0x" prefix (literal
# hex VMA strings like "0000000000043000"). The Size column on binutils
# 2.42+ is decimal *or* "0x"-prefixed hex depending on build config — use
# Python's int(s, 0) auto-detect.
python3 - <<EOF || { echo "build-minimal-icu.sh: extraction failed; skipping" >&2; exit 0; }
import sys
sec_foff  = int("${SEC_FOFF_HEX}", 16)
sec_vaddr = int("${SEC_VADDR_HEX}", 16)
sym_vaddr = int("${SYM_VADDR_HEX}", 16)
size_str  = "${SYM_SIZE}".strip()
size      = int(size_str, 0) if size_str.startswith(("0x", "0X")) else int(size_str)
offset    = sec_foff + (sym_vaddr - sec_vaddr)
with open("${ORIG_LIB}", "rb") as f:
    f.seek(offset)
    blob = f.read(size)
if len(blob) != size or blob[2:4] != b"\xda\x27":
    sys.exit(f"bad ICU magic at offset {offset}: got {blob[:4].hex()}, size {len(blob)} (want {size})")
with open("${DAT_FILE}", "wb") as f:
    f.write(blob)
print(f"extracted {len(blob)} bytes -> ${DAT_FILE}")
EOF
echo "Extracted   : $DAT_FILE ($(du -h "$DAT_FILE" | cut -f1)) from icudt${ICU_VER}_dat symbol"

# Items to KEEP — patterns matching item names in the .dat.
# What the JPDFium bridge actually uses (verified by grep over
# native/bridge/src/):
#   u_strFromUTF8           → cnvalias.icu (converter alias table)
#   icu::Normalizer NFC     → nfc.nrm  (NFC normalization data — bridge
#                                       only uses UNORM_NFC at
#                                       jpdfium_advanced.cpp:828)
#   icu::BreakIterator      → brkitr/* (sentence/word/line/char boundaries)
#   ubidi_*                 → ubidi.icu + ucase.icu + uchar.icu (BiDi
#                                       + case folding + character props)
#   icu::Locale::getDefault → root.res + en.res (default locale fallback)
#
# Deliberately NOT included (verified unused by bridge AND not loaded
# eagerly by libicuuc on init — ICU data loading is lazy):
#   unames.icu      ~300 KB — u_charName / u_charFromName, bridge doesn't
#   uemoji.icu      ~200 KB — UCHAR_EMOJI property, bridge doesn't
#   nfkc.nrm         ~50 KB — NFKC normalization, bridge uses only NFC
#   nfkc_cf.nrm      ~50 KB — NFKC casefold, same
#   en_US.res        ~50 KB — bridge has no US-specific locale need;
#                             root + en cover the default chain
KEEP=(
    '^cnvalias\.icu$'
    '^uchar\.icu$'
    '^ubidi\.icu$'
    '^ulayout\.icu$'
    '^ucase\.icu$'
    '^nfc\.nrm$'
    '^brkitr/'
    '^root\.res$'
    '^en\.res$'
)

# List all items in the source .dat
icupkg -l "$DAT_FILE" > "$WORK/all.lst"
TOTAL=$(wc -l < "$WORK/all.lst")
echo "Total items in source : $TOTAL"

# Extract every item to loose files. icupkg -x '*' extracts everything that
# matches the glob; -d sets the destination directory. This gives us a tree
# like $EXTRACT/uchar.icu, $EXTRACT/nfc.nrm, $EXTRACT/brkitr/sent.brk, etc.
EXTRACT="$WORK/extract"
mkdir -p "$EXTRACT"
icupkg -x '*' -d "$EXTRACT" "$DAT_FILE" \
    || { echo "build-minimal-icu.sh: icupkg extract failed; skipping" >&2; exit 0; }

# Compute the keep list (item names, no path prefix — relative to EXTRACT).
# pkgdata wants line-separated items. Add pool.res implicitly because the
# locale .res files reference it and icupkg warns when it's missing.
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

# Two-step build:
#   (1) `pkgdata -m archive` assembles the kept items into a single trimmed
#       .dat — no compiler/linker invocations, just data shuffling.
#   (2) `objcopy -I binary` wraps that .dat in an ELF object exporting the
#       icudt<MAJ>_dat symbol, then `gcc -shared` links it into a .so.
#
# We avoid `pkgdata -m dll` because it reads CC/LD/LDFLAGS/etc out of
# pkgdata.inc, and Ubuntu's installed inc files don't set those (they're
# only meaningful at libicu build time, not at install time). Net result on
# `-m dll`: "sh: 1: oma.c: not found" + empty compile/link commands.

ICUPKG_INC=$(find /usr/lib /usr/share -maxdepth 6 -type f \
              \( -name "pkgdata.inc" -o -name "icupkg.inc" -o -name "Makefile.inc" \) \
              2>/dev/null | grep -iE '/icu(/|$)' | head -1)
if [ -z "$ICUPKG_INC" ]; then
    echo "build-minimal-icu.sh: pkgdata config not found (pkgdata.inc); skipping" >&2
    exit 0
fi
echo "pkgdata inc  : $ICUPKG_INC"

OUT_DIR="$WORK/out"
mkdir -p "$OUT_DIR" "$WORK/pkg-tmp"
pkgdata -m archive -p icudata -e "icudt${ICU_VER}_dat" \
        -O "$ICUPKG_INC" \
        -T "$WORK/pkg-tmp" \
        -d "$OUT_DIR" \
        -s "$EXTRACT" \
        "$WORK/keep.lst" \
    || { echo "build-minimal-icu.sh: pkgdata archive failed; skipping" >&2; exit 0; }

TRIMMED_DAT="$OUT_DIR/icudata.dat"
if [ ! -f "$TRIMMED_DAT" ]; then
    # pkgdata names the output icudt<MAJ>l.dat or icudata.dat depending on
    # version; check both.
    TRIMMED_DAT=$(find "$OUT_DIR" -maxdepth 2 -name "*.dat" -type f | head -1)
fi
if [ -z "$TRIMMED_DAT" ] || [ ! -f "$TRIMMED_DAT" ]; then
    echo "build-minimal-icu.sh: no trimmed .dat produced; skipping" >&2
    exit 0
fi
echo "Trimmed .dat : $TRIMMED_DAT ($(du -h "$TRIMMED_DAT" | cut -f1))"

# Wrap the .dat in a shared library by hand.
HOST_ARCH=$(uname -m)
case "$HOST_ARCH" in
    x86_64)
        OBJCOPY_OUTPUT="elf64-x86-64"
        OBJCOPY_TARGET="i386:x86-64"
        ;;
    aarch64|arm64)
        OBJCOPY_OUTPUT="elf64-littleaarch64"
        OBJCOPY_TARGET="aarch64"
        ;;
    *)
        echo "build-minimal-icu.sh: unsupported arch $HOST_ARCH; skipping" >&2
        exit 0
        ;;
esac

# objcopy turns the raw .dat into an .o with auto-generated
# `_binary_<name>_{start,end,size}` symbols. The symbol name is derived from
# the source file PATH passed to objcopy with non-alphanumeric chars replaced
# by underscores — so `/tmp/work/out/icudata.dat` becomes
# `_binary__tmp_work_out_icudata_dat_start`. To keep that predictable, run
# objcopy from the file's directory with a relative basename: that gives
# `_binary_icudata_dat_start`. Then --redefine-sym renames it to the symbol
# ICU's runtime expects (icudt<MAJ>_dat). We also bump the section to 16-byte
# alignment which ICU requires for the data header.
DAT_BASENAME=$(basename "$TRIMMED_DAT")
DAT_DIR=$(dirname "$TRIMMED_DAT")
OBJ_FILE="$WORK/icudata_dat.o"
( cd "$DAT_DIR" && \
  objcopy -I binary -O "$OBJCOPY_OUTPUT" -B "$OBJCOPY_TARGET" \
          --redefine-sym "_binary_${DAT_BASENAME//./_}_start=icudt${ICU_VER}_dat" \
          --rename-section .data=.rodata,alloc,load,readonly,data,contents \
          --set-section-alignment .rodata=16 \
          "$DAT_BASENAME" "$OBJ_FILE" ) \
    || { echo "build-minimal-icu.sh: objcopy failed; skipping" >&2; exit 0; }

# Match the original lib's full versioning so the SONAME chain is consistent
# (libicudata.so -> libicudata.so.74 -> libicudata.so.74.2).
ICU_MINOR=$(basename "$ORIG_LIB" | sed -n "s/^libicudata\\.so\\.${ICU_VER}\\.\\(.*\\)$/\\1/p")
ICU_MINOR=${ICU_MINOR:-2}
NEW_LIB="$OUT_DIR/libicudata.so.${ICU_VER}.${ICU_MINOR}"
gcc -shared -fPIC \
    -Wl,-soname,libicudata.so.${ICU_VER} \
    -o "$NEW_LIB" \
    "$OBJ_FILE" \
    || { echo "build-minimal-icu.sh: gcc -shared failed; skipping" >&2; exit 0; }
echo "Built        : $NEW_LIB ($(du -h "$NEW_LIB" | cut -f1))"

# Sanity check the new .so:
#   1. file says it's an ELF shared object (not a stripped archive or junk)
#   2. readelf -d reports our SONAME (so the loader's NEEDED matches)
#   3. nm -D reports icudt<MAJ>_dat as an exported global object
#   4. ldd doesn't error out (sanity-loads the dyn linker would do)
echo "--- new lib sanity check ---"
file "$NEW_LIB"
readelf -d "$NEW_LIB" 2>/dev/null | grep -E "SONAME|NEEDED" || true
echo "--- exported icudt symbol ---"
if ! nm -D "$NEW_LIB" 2>/dev/null | grep -E "icudt${ICU_VER}_dat" ; then
    echo "build-minimal-icu.sh: icudt${ICU_VER}_dat not exported by new .so; skipping replace" >&2
    exit 0
fi
echo "--- ldd new ---"
ldd "$NEW_LIB" 2>&1 | head -5

# Pre-stage the trimmed .so directly into native/dist/<platform>/ so the
# bundler's "skip if dest already exists" check picks it up instead of
# copying the 30 MB system one. This avoids replacing /usr/lib's file
# (and the runner-breaking risk that introduces) entirely.
case "$(uname -m)" in
    x86_64)  PLATFORM="linux-x64";;
    aarch64|arm64) PLATFORM="linux-arm64";;
    *) echo "build-minimal-icu.sh: unknown arch $(uname -m); skipping pre-stage" >&2; exit 0;;
esac
PLATFORM_DIST="$(dirname "$0")/dist/$PLATFORM"
mkdir -p "$PLATFORM_DIST"
# Stage as libicudata.so.<MAJ> (the SONAME the bundler's ldd walk resolves to).
cp -v "$NEW_LIB" "$PLATFORM_DIST/libicudata.so.${ICU_VER}"
echo "Pre-staged   : $PLATFORM_DIST/libicudata.so.${ICU_VER} ($(du -h "$PLATFORM_DIST/libicudata.so.${ICU_VER}" | cut -f1))"
echo "  -> bundler will see this file and skip copying the 30 MB system one."
