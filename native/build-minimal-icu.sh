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
# icupkg item names look like:
#   - uchar.icu, ubidi.icu, unames.icu, ulayout.icu, ucase.icu
#   - nfc.nrm, nfkc.nrm, nfkc_cf.nrm
#   - brkitr/sent.brk, brkitr/word.brk, brkitr/line.brk, brkitr/char.brk
#   - cnvalias.icu
#   - root.res, en.res, ...
#   - LOTS more we don't need (coll/, curr/, lang/, region/, rbnf/, ...)
KEEP=(
    '^cnvalias\.icu$'
    '^uchar\.icu$'
    '^ubidi\.icu$'
    '^unames\.icu$'
    '^ulayout\.icu$'
    '^ucase\.icu$'
    '^uemoji\.icu$'
    '^nfc\.nrm$'
    '^nfkc\.nrm$'
    '^nfkc_cf\.nrm$'
    '^brkitr/'
    # Minimum locale data — root provides defaults, en for English-language pdfs.
    # Without ANY locale data, ICU's Locale("en") falls back to root which works
    # for our usage (we never display localized strings to users).
    '^root\.res$'
    '^en\.res$'
    '^en_US\.res$'
)

# List all items in the source .dat
icupkg -l "$DAT_FILE" > "$WORK/all.lst"
TOTAL=$(wc -l < "$WORK/all.lst")
echo "Total items in source : $TOTAL"

# Compute keep/remove sets
> "$WORK/keep.lst"
> "$WORK/remove.lst"
while IFS= read -r item; do
    matched=0
    for pat in "${KEEP[@]}"; do
        if echo "$item" | grep -qE "$pat"; then
            matched=1; break
        fi
    done
    if [ "$matched" = 1 ]; then
        echo "$item" >> "$WORK/keep.lst"
    else
        echo "$item" >> "$WORK/remove.lst"
    fi
done < "$WORK/all.lst"

KEPT=$(wc -l < "$WORK/keep.lst")
REMOVED=$(wc -l < "$WORK/remove.lst")
echo "Keeping  : $KEPT items"
echo "Removing : $REMOVED items"

# Stage and trim. icupkg --remove takes a file list; pkgdata wants the same.
cp "$DAT_FILE" "$WORK/icudt${ICU_VER}l.dat"
icupkg --remove "$WORK/remove.lst" "$WORK/icudt${ICU_VER}l.dat"
echo "Trimmed size : $(du -h "$WORK/icudt${ICU_VER}l.dat" | cut -f1)"

# Now repackage the trimmed .dat into a shared library. pkgdata needs the
# pkgdata.inc / Makefile.inc that ships with libicu-dev. Ubuntu installs it
# under /usr/lib/<triplet>/icu/<ver>/ as pkgdata.inc (or a "current" symlink).
ICUPKG_INC=$(find /usr/lib /usr/share -maxdepth 6 -type f \
              \( -name "pkgdata.inc" -o -name "icupkg.inc" -o -name "Makefile.inc" \) \
              2>/dev/null | grep -iE '/icu(/|$)' | head -1)
if [ -z "$ICUPKG_INC" ]; then
    echo "build-minimal-icu.sh: pkgdata config not found (pkgdata.inc); skipping repackage" >&2
    exit 0
fi
echo "pkgdata inc  : $ICUPKG_INC"

# Build the shared library
OUT_DIR="$WORK/out"
mkdir -p "$OUT_DIR"
pkgdata -m dll -p icudata -e "icudt${ICU_VER}_dat" \
        -O "$ICUPKG_INC" \
        -T "$WORK/pkg-tmp" \
        -d "$OUT_DIR" \
        "$WORK/icudt${ICU_VER}l.dat"

# pkgdata's exact output name varies — find what was produced.
NEW_LIB=$(find "$OUT_DIR" -maxdepth 2 -name "libicudata.so*" -type f | head -1)
if [ -z "$NEW_LIB" ]; then
    echo "build-minimal-icu.sh: pkgdata didn't produce a libicudata.so; skipping" >&2
    exit 0
fi
echo "Built        : $NEW_LIB ($(du -h "$NEW_LIB" | cut -f1))"

# Publish a copy under native/build-real/ as a fallback the bundler can find
# even if /usr/lib was read-only.
PUBLISH_DIR="$(dirname "$0")/build-real/minimal-icu"
mkdir -p "$PUBLISH_DIR"
cp -v "$NEW_LIB" "$PUBLISH_DIR/"

# Replace the system one. The bundling step copies whatever the linker
# resolves against, so swapping the file under /usr/lib means the bundle
# picks up the small one.
if [ -n "$ORIG_LIB" ] && [ -f "$ORIG_LIB" ]; then
    sudo cp "$NEW_LIB" "$ORIG_LIB"
    echo "Replaced     : $ORIG_LIB ($(du -h "$ORIG_LIB" | cut -f1))"
fi
