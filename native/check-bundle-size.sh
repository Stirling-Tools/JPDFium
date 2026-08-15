#!/usr/bin/env bash
# check-bundle-size.sh - hard size budget for each platform's native bundle, so
# a bloat regression (debug symbols sneaking back in, the full 30 MB
# libicudata being bundled instead of the trimmed one, a new huge dep, ...)
# fails the build instead of silently inflating the shipped jars.
#
# Budgets live in native/size-budgets.txt (one `<platform> <max-bytes>` per
# line, # comments allowed). Warns at 75% of the budget, fails when exceeded.
# Emits a machine-readable `SIZE_BYTES <platform> <bytes>` line for size
# trending. Runs per-platform from bundle-runtime-deps.sh.
#
# The budget lookup is fail-CLOSED when SIZE_GATE_STRICT=1 (set by the shipped
# jar workflows): a platform with no budget entry then fails instead of
# silently disabling the gate (a typo'd platform must never turn the gate off).
#
# Usage: check-bundle-size.sh <dist-dir> <platform>
set -euo pipefail

DIST_DIR="${1:?usage: check-bundle-size.sh <dist-dir> <platform>}"
PLATFORM="${2:?usage: check-bundle-size.sh <dist-dir> <platform>}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BUDGETS="$SCRIPT_DIR/size-budgets.txt"

# The budget governs the SHIPPED natives jars. Test-only workflows that build a
# bundle for local runners / the CLI (corpus.yml, cli.yml, bundling.yml) bundle
# the full system ICU and are not gated on shipped-jar size.
if [ "${JPDFIUM_SKIP_SIZE_BUDGET:-}" = "1" ]; then
    echo "check-bundle-size.sh: JPDFIUM_SKIP_SIZE_BUDGET=1 - skipping (test-only bundle, not a shipped jar)"
    exit 0
fi

[ -f "$BUDGETS" ] || { echo "check-bundle-size.sh: no $BUDGETS - skipping"; exit 0; }

# A lean bundle is a flat directory (check-bundle-lean.sh enforces this);
# measuring the whole tree is only meaningful when there are no subdirectories.
if find "$DIST_DIR" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | grep -q .; then
    echo "FAIL: subdirectories in $DIST_DIR - a lean bundle must be flat (see check-bundle-lean.sh)" >&2
    exit 1
fi

BUDGET=$(grep -v '^#' "$BUDGETS" | grep -v '^$' | awk -v p="$PLATFORM" '$1==p{print $2; exit}' || true)
if [ -z "${BUDGET:-}" ]; then
    if [ "${SIZE_GATE_STRICT:-}" = "1" ]; then
        echo "FAIL: no size budget entry for '$PLATFORM' in $BUDGETS and SIZE_GATE_STRICT=1" >&2
        echo "      - the size gate must not silently disable on a typo'd platform." >&2
        echo "      Add '<platform> <max-bytes>' to native/size-budgets.txt." >&2
        exit 1
    fi
    echo "check-bundle-size.sh: no budget entry for $PLATFORM in $BUDGETS - skipping"
    exit 0
fi

TOTAL_KB=$(du -sk "$DIST_DIR" 2>/dev/null | awk '{print $1}')
TOTAL=$((TOTAL_KB * 1024))

WARN_AT=$(awk -v b="$BUDGET" 'BEGIN{printf "%.0f", b*0.75}')
mb() { awk -v b="$1" 'BEGIN{printf "%.1f", b/1048576}'; }

echo "bundle size ($PLATFORM): $(mb "$TOTAL") MB / budget $(mb "$BUDGET") MB"
echo "SIZE_BYTES $PLATFORM $TOTAL"

if [ "$TOTAL" -gt "$BUDGET" ]; then
    echo "FAIL: $PLATFORM bundle is $(mb "$TOTAL") MB, over the $(mb "$BUDGET") MB budget." >&2
    echo "      Check for dead weight (debug symbols, full libicudata, a new large dep)." >&2
    echo "      If the growth is legitimate, bump native/size-budgets.txt deliberately." >&2
    exit 1
fi

if [ "$TOTAL" -gt "$WARN_AT" ]; then
    echo "WARNING: $PLATFORM bundle is within budget but past 75% ($(mb "$WARN_AT") MB) - watch the trend." >&2
fi

echo "OK: $PLATFORM bundle is within its size budget"
