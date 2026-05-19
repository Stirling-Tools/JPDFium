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
# build time via genccode. So we either extract it from the .so (fragile)
# or download the upstream ICU data archive (clean). We do the latter.
WORK=$(mktemp -d)
# trap is registered later, after WORK is committed, to avoid removing it
# under us if mktemp fails.
trap "rm -rf '$WORK'" EXIT

# First try the system's loose .dat (older Debians sometimes ship it).
DAT_FILE=$(find /usr/share/icu /usr/lib -maxdepth 5 -name "icudt${ICU_VER}l.dat" -type f 2>/dev/null | head -1)
if [ -z "$DAT_FILE" ] || [ ! -f "$DAT_FILE" ]; then
    # Map MAJ.MIN to release tag, e.g. 74.2 -> release-74-2.
    REL_TAG="release-${ICU_FULL_VER//./-}"
    DATA_URL="https://github.com/unicode-org/icu/releases/download/${REL_TAG}/icu4c-${ICU_FULL_VER//./_}-data.zip"
    echo "Downloading  : $DATA_URL"
    if ! curl -fsSL "$DATA_URL" -o "$WORK/icu-data.zip"; then
        echo "build-minimal-icu.sh: failed to download upstream ICU data; skipping" >&2
        exit 0
    fi
    # Archive layout: data/in/icudt74l.dat
    if ! command -v unzip >/dev/null 2>&1; then
        echo "build-minimal-icu.sh: unzip not installed; skipping" >&2
        exit 0
    fi
    unzip -q -o "$WORK/icu-data.zip" -d "$WORK/icu-data-extract"
    DAT_FILE=$(find "$WORK/icu-data-extract" -name "icudt${ICU_VER}l.dat" -type f 2>/dev/null | head -1)
    if [ -z "$DAT_FILE" ] || [ ! -f "$DAT_FILE" ]; then
        echo "build-minimal-icu.sh: upstream zip didn't contain icudt${ICU_VER}l.dat; skipping" >&2
        exit 0
    fi
fi

# Find the *real* libicudata.so.<MAJ>.<MIN> file (not the SONAME symlink) so
# `cp` later actually overwrites the data carrier. Group the -name predicates
# with -type f under parens; find's -o has lower precedence than implicit -a.
ORIG_LIB=$(find /usr/lib /lib -maxdepth 4 -type f \
            \( -name "libicudata.so.${ICU_VER}.*" -o -name "libicudata.so.${ICU_VER}" \) \
            2>/dev/null | head -1)
echo "Source data : $DAT_FILE ($(du -h "$DAT_FILE" | cut -f1))"
echo "Source lib  : ${ORIG_LIB:-<not found>} ($(du -h "${ORIG_LIB:-/dev/null}" 2>/dev/null | cut -f1))"

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
