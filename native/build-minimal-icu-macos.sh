#!/usr/bin/env bash
# Mach-O equivalent of build-minimal-icu.sh: shrink libicudata.<MAJ>.dylib
# from ~33 MB → ~4 MB by extracting brew's embedded icudt<MAJ>_dat blob,
# dropping items the bridge doesn't use via icupkg, and repacking via clang
# into a new dylib that exports the same symbol.
#
# JPDFium's ICU usage (verified by grep over native/bridge/src/):
#   u_strFromUTF8           → cnvalias.icu
#   icu::Normalizer NFC     → nfc.nrm
#   icu::BreakIterator      → brkitr/* + root.res
#   ubidi_*                 → ubidi.icu + ucase.icu + uchar.icu
#
# Pre-stages the trimmed .dylib into native/dist/<platform>/ so the bundler's
# "skip if dest already exists" check picks it up; brew's icu4c@78 install is
# left untouched, and on darwin-arm64 (where bundle_macos's rpath resolver
# currently misses keg-only /opt/homebrew/opt/icu4c@78/lib) this script also
# fills in the missing libicudata that libicuuc.78.dylib expects at runtime.
#
# Best-effort: any failure exits 0 — bundler falls back to brew's full 33 MB
# icudata (or, on arm64 with the resolver bug, no icudata at all, i.e. the
# pre-script status quo).

echo "build-minimal-icu-macos.sh: start  ($(uname -s) $(uname -m))"

case "$(uname -s)" in
    Darwin*) ;;
    *) echo "build-minimal-icu-macos.sh: skipping on $(uname -s)"; exit 0;;
esac

set -u

# Pick brew prefix + target arch the same way the other macOS scripts do
# (build-harfbuzz-no-glib.sh / build-qpdf-native-crypto.sh). macos-14 hosts
# both jobs: native arm64 uses /opt/homebrew, x86_64 Rosetta cross-compile
# uses a second brew at /usr/local. CMAKE_OSX_ARCHITECTURES from the workflow
# tells us which job we're in.
if [ "${CMAKE_OSX_ARCHITECTURES:-}" = "x86_64" ]; then
    TARGET_ARCH=x86_64
    PLATFORM=darwin-x64
    BREW=/usr/local/bin/brew
    if [ ! -x "$BREW" ]; then
        echo "build-minimal-icu-macos.sh: x86_64 brew (Rosetta) not installed; skipping" >&2
        exit 0
    fi
else
    TARGET_ARCH=arm64
    PLATFORM=darwin-arm64
    if ! command -v brew >/dev/null 2>&1; then
        echo "build-minimal-icu-macos.sh: brew not on PATH; skipping" >&2
        exit 0
    fi
    BREW=$(command -v brew)
fi

ICU_PREFIX=$("$BREW" --prefix icu4c 2>/dev/null || true)
if [ -z "$ICU_PREFIX" ] || [ ! -d "$ICU_PREFIX/lib" ]; then
    echo "build-minimal-icu-macos.sh: brew icu4c not installed; skipping" >&2
    exit 0
fi

# Resolve the real libicudata.<MAJ>.dylib (not the unversioned symlink).
ORIG_LIB=""
for cand in "$ICU_PREFIX/lib/"libicudata.*.dylib; do
    [ -L "$cand" ] && continue
    [ -f "$cand" ] || continue
    ORIG_LIB="$cand"
    break
done
if [ -z "$ORIG_LIB" ]; then
    echo "build-minimal-icu-macos.sh: no real libicudata.*.dylib under $ICU_PREFIX/lib; skipping" >&2
    exit 0
fi

# Parse "libicudata.<MAJ>.dylib" → MAJ.
ORIG_BASE=$(basename "$ORIG_LIB")
ICU_VER=$(echo "$ORIG_BASE" | sed -n 's/^libicudata\.\([0-9][0-9]*\)\.dylib$/\1/p')
if [ -z "$ICU_VER" ]; then
    echo "build-minimal-icu-macos.sh: can't parse ICU version from $ORIG_BASE; skipping" >&2
    exit 0
fi
echo "Source lib    : $ORIG_LIB ($(du -h "$ORIG_LIB" | cut -f1)) [ICU $ICU_VER, $TARGET_ARCH]"

ICUPKG="$ICU_PREFIX/bin/icupkg"
PKGDATA="$ICU_PREFIX/bin/pkgdata"
for t in "$ICUPKG" "$PKGDATA"; do
    if [ ! -x "$t" ]; then
        echo "build-minimal-icu-macos.sh: $t not found; skipping" >&2
        exit 0
    fi
done

WORK=$(mktemp -d)
trap "rm -rf '$WORK'" EXIT

# Find the icudt<MAJ>_dat symbol in the dylib. Mach-O exported C symbols
# carry a leading underscore (so we look up _icudt78_dat for the v78 lib).
# `nm -m -arch <arch>` prints lines like:
#   0000000000004000 (__DATA_CONST,__const) external _icudt78_dat
SYM_LINE=$(nm -m -arch "$TARGET_ARCH" "$ORIG_LIB" 2>/dev/null \
           | awk -v sym="_icudt${ICU_VER}_dat" '$NF==sym' | head -1)
if [ -z "$SYM_LINE" ]; then
    echo "build-minimal-icu-macos.sh: _icudt${ICU_VER}_dat symbol not in $ORIG_LIB; skipping" >&2
    exit 0
fi
SYM_VADDR_HEX=$(echo "$SYM_LINE" | awk '{print $1}')
# Parse "(__DATA_CONST,__const)" → segname + sectname.
SECT_PAIR=$(echo "$SYM_LINE" | grep -oE '\([^)]+\)' | head -1 | tr -d '()')
SEG_NAME=$(echo "$SECT_PAIR" | cut -d, -f1)
SECT_NAME=$(echo "$SECT_PAIR" | cut -d, -f2)
echo "  symbol vaddr: 0x$SYM_VADDR_HEX  in $SEG_NAME,$SECT_NAME"

# Walk otool -l (Mach-O load commands) for the matching segment + section
# and pull addr / offset / size. otool -l output is line-oriented with
# `Section` headers, then nested fields.
SECT_INFO=$(otool -l -arch "$TARGET_ARCH" "$ORIG_LIB" 2>/dev/null | awk -v seg="$SEG_NAME" -v sect="$SECT_NAME" '
    /^Section$/ {
        sname=""; segname=""; addr=""; off=""; sz=""
        in_sect=1
        next
    }
    in_sect && $1=="sectname" { sname=$2 }
    in_sect && $1=="segname"  { segname=$2 }
    in_sect && $1=="addr"     { addr=$2 }
    in_sect && $1=="size"     { sz=$2 }
    in_sect && $1=="offset"   { off=$2 }
    in_sect && sname==sect && segname==seg && addr!="" && off!="" && sz!="" {
        print addr, off, sz
        exit
    }
')
if [ -z "$SECT_INFO" ]; then
    echo "build-minimal-icu-macos.sh: section $SEG_NAME,$SECT_NAME not in otool -l; skipping" >&2
    exit 0
fi
SEC_VADDR_HEX=$(echo "$SECT_INFO" | awk '{print $1}')
SEC_FOFF_DEC=$(echo "$SECT_INFO" | awk '{print $2}')
SEC_SIZE_HEX=$(echo "$SECT_INFO" | awk '{print $3}')
echo "  section addr=0x${SEC_VADDR_HEX#0x} off=${SEC_FOFF_DEC} size=0x${SEC_SIZE_HEX#0x}"

DAT_FILE="$WORK/icudt${ICU_VER}l.dat"
# brew's libicudata is built by ICU's genccode → a single .S that puts the
# whole .dat blob into __DATA(_CONST),__const as one symbol. So the
# symbol's data runs from (vaddr - sec_vaddr + sec_foff) to the end of the
# section. We extract that range and sanity-check the ICU header magic
# (offset 2..3 = 0xda 0x27).
python3 - <<EOF || { echo "build-minimal-icu-macos.sh: extraction failed; skipping" >&2; exit 0; }
import sys
sec_foff  = int("$SEC_FOFF_DEC", 0)
sec_vaddr = int("$SEC_VADDR_HEX", 16)
sym_vaddr = int("$SYM_VADDR_HEX", 16)
sec_size  = int("$SEC_SIZE_HEX", 16)
in_sect_off = sym_vaddr - sec_vaddr
if in_sect_off < 0 or in_sect_off >= sec_size:
    sys.exit(f"symbol offset {in_sect_off} out of section size {sec_size}")
file_off  = sec_foff + in_sect_off
remaining = sec_size - in_sect_off
with open("$ORIG_LIB", "rb") as f:
    f.seek(file_off)
    blob = f.read(remaining)
if len(blob) < 16 or blob[2:4] != b"\xda\x27":
    head = blob[:8].hex() if blob else "(empty)"
    sys.exit(f"bad ICU magic at file offset {file_off}: head={head}, len={len(blob)}")
with open("$DAT_FILE", "wb") as f:
    f.write(blob)
print(f"extracted {len(blob)} bytes -> $DAT_FILE")
EOF
echo "Extracted     : $DAT_FILE ($(du -h "$DAT_FILE" | cut -f1))"

# Items to KEEP — same list as the Linux script. See build-minimal-icu.sh
# for the rationale comment.
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

"$ICUPKG" -l "$DAT_FILE" > "$WORK/all.lst" 2>"$WORK/icupkg.err" \
    || { cat "$WORK/icupkg.err" >&2; echo "build-minimal-icu-macos.sh: icupkg -l failed; skipping" >&2; exit 0; }
TOTAL=$(wc -l < "$WORK/all.lst")
echo "Total items   : $TOTAL"

EXTRACT="$WORK/extract"
mkdir -p "$EXTRACT"
"$ICUPKG" -x '*' -d "$EXTRACT" "$DAT_FILE" \
    || { echo "build-minimal-icu-macos.sh: icupkg extract failed; skipping" >&2; exit 0; }

: > "$WORK/keep.lst"
echo "pool.res" >> "$WORK/keep.lst"
while IFS= read -r item; do
    matched=0
    for pat in "${KEEP[@]}"; do
        if echo "$item" | grep -qE "$pat"; then matched=1; break; fi
    done
    if [ "$matched" = 1 ] && ! grep -qFx "$item" "$WORK/keep.lst"; then
        echo "$item" >> "$WORK/keep.lst"
    fi
done < "$WORK/all.lst"
echo "Keeping       : $(wc -l < "$WORK/keep.lst") items"

# Find pkgdata.inc. brew icu4c@78 ships it under the keg's
# lib/icu/<full-ver>/pkgdata.inc, sometimes also at config/pkgdata.inc.
ICUPKG_INC=$(find "$ICU_PREFIX" -maxdepth 6 -type f -name "pkgdata.inc" 2>/dev/null | head -1)
if [ -z "$ICUPKG_INC" ]; then
    echo "build-minimal-icu-macos.sh: pkgdata.inc not found under $ICU_PREFIX; skipping" >&2
    exit 0
fi
echo "pkgdata.inc   : $ICUPKG_INC"

OUT_DIR="$WORK/out"
mkdir -p "$OUT_DIR" "$WORK/pkg-tmp"
"$PKGDATA" -m archive -p icudata -e "icudt${ICU_VER}_dat" \
        -O "$ICUPKG_INC" \
        -T "$WORK/pkg-tmp" \
        -d "$OUT_DIR" \
        -s "$EXTRACT" \
        "$WORK/keep.lst" \
    || { echo "build-minimal-icu-macos.sh: pkgdata archive failed; skipping" >&2; exit 0; }
TRIMMED_DAT=$(find "$OUT_DIR" -maxdepth 2 -name "*.dat" -type f | head -1)
if [ -z "$TRIMMED_DAT" ]; then
    echo "build-minimal-icu-macos.sh: pkgdata produced no .dat; skipping" >&2
    exit 0
fi
echo "Trimmed .dat  : $TRIMMED_DAT ($(du -h "$TRIMMED_DAT" | cut -f1))"

# Wrap the trimmed .dat into a dylib via a one-liner asm stub that .incbin's
# the data and exports it as _icudt<MAJ>_dat. Apple's clang understands the
# GNU-style .incbin directive when fed a .S file. Section choice matters:
# ICU's runtime expects 16-byte alignment, hence .p2align 4.
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
    -Wl,-no_warn_duplicate_libraries \
    -o "$NEW_LIB" \
    "$ASM" \
    || { echo "build-minimal-icu-macos.sh: clang link failed; skipping" >&2; exit 0; }

echo "Built         : $NEW_LIB ($(du -h "$NEW_LIB" | cut -f1))"

echo "--- otool -D ---"
otool -D "$NEW_LIB" || true
echo "--- exported symbol ---"
if ! nm -gU "$NEW_LIB" 2>/dev/null | grep -qE "_icudt${ICU_VER}_dat"; then
    echo "build-minimal-icu-macos.sh: _icudt${ICU_VER}_dat not exported by new dylib; skipping" >&2
    exit 0
fi

# Pre-stage directly into native/dist/<platform>/. bundle-runtime-deps.sh
# checks file existence before cp'ing in its rpath/loader-path walk; if our
# trimmed copy is already there, brew's 33 MB one is left out of the bundle.
# On darwin-arm64 (where the bundler's rpath resolver doesn't search
# /opt/homebrew/opt/icu4c@78/lib and therefore SKIPS libicudata.78.dylib
# entirely), this pre-stage also fixes the latent "ICU advanced features
# silently broken" bug because the trimmed lib still gets uploaded with the
# native artifact via the workflow's actions/upload-artifact step on
# native/dist/<platform>/ regardless of whether bundle_macos visits it.
PLATFORM_DIST="$(dirname "$0")/dist/$PLATFORM"
mkdir -p "$PLATFORM_DIST"
cp -v "$NEW_LIB" "$PLATFORM_DIST/libicudata.${ICU_VER}.dylib"
echo "Pre-staged    : $PLATFORM_DIST/libicudata.${ICU_VER}.dylib ($(du -h "$PLATFORM_DIST/libicudata.${ICU_VER}.dylib" | cut -f1))"
echo "  → bundler will see this file and skip copying brew's full $(du -h "$ORIG_LIB" | cut -f1) libicudata."
