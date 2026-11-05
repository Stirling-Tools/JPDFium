#!/usr/bin/env bash
# Regression cases for skipped, failing and successful native test reports.
set -euo pipefail

CHECK="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/check-smoke-ran.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

report() {
    printf '<testsuite tests="%s" skipped="%s" failures="%s" errors="%s"/>\n' \
        "$1" "$2" "$3" "$4" > "$WORK/TEST-native.xml"
}

expect() {
    local expected="$1"
    shift
    local actual=0
    bash "$CHECK" "$WORK" "$@" > "$WORK/output" 2>&1 || actual=$?
    if [ "$actual" -ne "$expected" ]; then
        cat "$WORK/output" >&2
        echo "FAIL: expected exit $expected, got $actual" >&2
        exit 1
    fi
}

expect 1
report 0 0 0 0
expect 1
report 1 1 0 0
expect 1
report 2 1 0 0
expect 0
expect 1 --no-skips
report 1 0 1 0
expect 1
report 1 0 0 1
expect 1
report 1 2 0 0
expect 1
report 2 0 0 0
expect 0 --no-skips
expect 1 --invalid-option
echo "OK: native smoke report regression cases passed"
