#!/usr/bin/env bash
# Verifies a JUnit test report directory contains successful, executed tests.
set -euo pipefail

REPORT_DIR="${1:?usage: check-smoke-ran.sh <test-results-dir>}"
NO_SKIPS="${2:-}"
if [ -n "$NO_SKIPS" ] && [ "$NO_SKIPS" != "--no-skips" ]; then
    echo "ERROR: expected --no-skips as the second argument" >&2
    exit 1
fi

if [ ! -d "$REPORT_DIR" ]; then
    echo "FAIL: no test report directory at $REPORT_DIR" >&2
    exit 1
fi

total=0
skipped=0
for xml in "$REPORT_DIR"/TEST-*.xml; do
    [ -e "$xml" ] || continue
    t=$(grep -oE 'tests="[0-9]+"' "$xml" | head -1 | grep -oE '[0-9]+' || echo 0)
    s=$(grep -oE 'skipped="[0-9]+"' "$xml" | head -1 | grep -oE '[0-9]+' || echo 0)
    f=$(grep -oE 'failures="[0-9]+"' "$xml" | head -1 | grep -oE '[0-9]+' || echo 0)
    e=$(grep -oE 'errors="[0-9]+"' "$xml" | head -1 | grep -oE '[0-9]+' || echo 0)
    if [ "$f" -ne 0 ] || [ "$e" -ne 0 ] || [ "$s" -gt "$t" ]; then
        echo "FAIL: invalid or failing test report at $xml" >&2
        exit 1
    fi
    total=$((total + t - s))
    skipped=$((skipped + s))
done

if [ "$total" -eq 0 ]; then
    echo "FAIL: smoke test report under $REPORT_DIR shows 0 executed tests" >&2
    exit 1
fi

if [ "$NO_SKIPS" = "--no-skips" ] && [ "$skipped" -ne 0 ]; then
    echo "FAIL: $skipped test(s) skipped under $REPORT_DIR" >&2
    exit 1
fi

echo "OK: executed $total test(s) under $REPORT_DIR"
