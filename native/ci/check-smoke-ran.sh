#!/usr/bin/env bash
# check-smoke-ran.sh - fail if a JUnit smoke-test report shows ZERO executed
# tests.
#
# Guards against a "smoke gate that silently does nothing": a broken system
# property, an over-narrow test filter, or a class renamed past the filter
# would otherwise let the per-platform native/vips smoke step "succeed" while
# running zero tests. Any per-platform INIT/load gate must genuinely execute
# its test.
#
# Usage: check-smoke-ran.sh <test-results-dir>
#   e.g. check-smoke-ran.sh jpdfium/build/test-results/nativeSmokeTest
set -euo pipefail

REPORT_DIR="${1:?usage: check-smoke-ran.sh <test-results-dir>}"

if [ ! -d "$REPORT_DIR" ]; then
    echo "FAIL: no test report at $REPORT_DIR - the smoke test did not run" >&2
    exit 1
fi

total=0
for xml in "$REPORT_DIR"/TEST-*.xml; do
    [ -e "$xml" ] || continue
    t=$(grep -oE 'tests="[0-9]+"' "$xml" | head -1 | grep -oE '[0-9]+' || echo 0)
    total=$((total + t))
done

if [ "$total" -eq 0 ]; then
    echo "FAIL: smoke test report under $REPORT_DIR shows 0 executed tests -" >&2
    echo "      the per-platform gate was silently skipped/disabled. Fix the" >&2
    echo "      smoke task wiring (property gate / test filter) before merging." >&2
    exit 1
fi

echo "OK: smoke test actually executed $total test(s) under $REPORT_DIR"
