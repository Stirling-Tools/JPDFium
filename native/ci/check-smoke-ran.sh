#!/usr/bin/env bash
# Verifies a JUnit test report directory contains at least one executed test.
set -euo pipefail

REPORT_DIR="${1:?usage: check-smoke-ran.sh <test-results-dir>}"

if [ ! -d "$REPORT_DIR" ]; then
    echo "FAIL: no test report directory at $REPORT_DIR" >&2
    exit 1
fi

total=0
for xml in "$REPORT_DIR"/TEST-*.xml; do
    [ -e "$xml" ] || continue
    t=$(grep -oE 'tests="[0-9]+"' "$xml" | head -1 | grep -oE '[0-9]+' || echo 0)
    total=$((total + t))
done

if [ "$total" -eq 0 ]; then
    echo "FAIL: smoke test report under $REPORT_DIR shows 0 executed tests" >&2
    exit 1
fi

echo "OK: executed $total test(s) under $REPORT_DIR"
