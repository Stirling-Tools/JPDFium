#!/usr/bin/env bash
# Enforces native bundle size limits defined in native/size-budgets.txt.
set -euo pipefail

DIST_DIR="${1:?usage: check-bundle-size.sh <dist-dir> <platform>}"
PLATFORM="${2:?usage: check-bundle-size.sh <dist-dir> <platform>}"

# Normalize platform for vips bundles (e.g. if passed as windows-x64 for a vips dist dir)
if [[ "$(basename "$DIST_DIR")" =~ ^vips- ]] && [[ "$PLATFORM" != vips-* ]]; then
    PLATFORM="vips-$PLATFORM"
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BUDGETS="$SCRIPT_DIR/size-budgets.txt"

if [ "${JPDFIUM_SKIP_SIZE_BUDGET:-}" = "1" ]; then
    exit 0
fi

[ -f "$BUDGETS" ] || exit 0

if find "$DIST_DIR" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | grep -q .; then
    echo "FAIL: subdirectories found in $DIST_DIR" >&2
    exit 1
fi

# The ICU data carrier must be the trimmed one; a regression to the loose keep
# list (~4 MB) or the full data (~31 MB) would balloon every core jar.
case "$PLATFORM" in
    vips-*) ;;
    *)
        for f in "$DIST_DIR"/libicudata.* "$DIST_DIR"/icudt*.dll; do
            [ -e "$f" ] || continue
            icu_bytes=$(wc -c < "$f")
            if [ "$icu_bytes" -gt 1572864 ]; then
                echo "FAIL: $(basename "$f") is $((icu_bytes / 1024)) KB - ICU data was not trimmed" >&2
                exit 1
            fi
        done
        ;;
esac

# Windows core bundles must carry the trimmed ICU DLL explicitly. If the
# trimmed-ICU artifact download fails, the build either stages the full data
# (caught above) or omits the carrier entirely - the latter would sail past
# the size budget, so require the trimmed carrier to be present.
case "$PLATFORM" in
    windows-*)
        if [ -z "$(find "$DIST_DIR" -maxdepth 1 -name 'icudt*.dll' -print -quit 2>/dev/null)" ]; then
            echo "FAIL: no trimmed icudt*.dll staged in $DIST_DIR (trimmed ICU missing)" >&2
            exit 1
        fi
        ;;
esac

BUDGET=$(grep -v '^#' "$BUDGETS" | grep -v '^$' | awk -v p="$PLATFORM" '$1==p{print $2; exit}' || true)
if [ -z "${BUDGET:-}" ]; then
    if [ "${SIZE_GATE_STRICT:-}" = "1" ]; then
        echo "FAIL: no size budget entry for '$PLATFORM' in $BUDGETS" >&2
        exit 1
    fi
    exit 0
fi

TOTAL_KB=$(du -sk "$DIST_DIR" 2>/dev/null | awk '{print $1}')
TOTAL=$((TOTAL_KB * 1024))

WARN_AT=$(awk -v b="$BUDGET" 'BEGIN{printf "%.0f", b*0.75}')
mb() { awk -v b="$1" 'BEGIN{printf "%.1f", b/1048576}'; }

echo "bundle size ($PLATFORM): $(mb "$TOTAL") MB / budget $(mb "$BUDGET") MB"
echo "SIZE_BYTES $PLATFORM $TOTAL"

if [ "$TOTAL" -gt "$BUDGET" ]; then
    echo "FAIL: $PLATFORM bundle is $(mb "$TOTAL") MB, exceeding $(mb "$BUDGET") MB budget" >&2
    exit 1
fi

if [ "$TOTAL" -gt "$WARN_AT" ]; then
    echo "WARNING: $PLATFORM bundle is past 75% of budget ($(mb "$WARN_AT") MB)" >&2
fi

echo "OK: $PLATFORM bundle is within size budget"
