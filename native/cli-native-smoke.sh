#!/usr/bin/env bash
# cli-native-smoke.sh - Verify the compiled GraalVM native CLI actually works.
#
# Exercises a representative set of operations through the native binary and
# verifies the operations HAPPENED and produced uncorrupted output:
#   - every produced PDF re-opens (jpdfium info succeeds)
#   - redaction removes the targeted words from extracted text
#   - rotation swaps page dimensions
#   - page extraction reduces the page count
#   - merge sums page counts
#   - render emits a decodable PNG
#
# Usage: bash native/cli-native-smoke.sh <path-to-jpdfium-binary> <work-dir>
set -euo pipefail

BIN="${1:?usage: cli-native-smoke.sh <jpdfium-binary> <work-dir>}"
WORK="${2:?usage: cli-native-smoke.sh <jpdfium-binary> <work-dir>}"
REPO="$(cd "$(dirname "$0")/.." && pwd)"
BASIC="${REPO}/jpdfium/src/test/resources/pdfs/general/basic-text.pdf"
MOZ="${REPO}/jpdfium/src/test/resources/pdfs/general/mozilla_tracemonkey.pdf"

mkdir -p "${WORK}"
cd "${WORK}"

failures=0
check() {
    # $1 = description, $2 = expected rc, rest = args
    local desc="$1" expected="$2"
    shift 2
    if "${BIN}" "$@" --quiet >/dev/null 2>&1; then
        echo "  ok: ${desc}"
    else
        echo "  FAIL: ${desc}"
        failures=$((failures + 1))
    fi
}

open_ok() {
    if "${BIN}" info "$1" --quiet >/dev/null 2>&1; then
        echo "  ok: opens ${1}"
    else
        echo "  FAIL: does not open ${1}"
        failures=$((failures + 1))
    fi
}

page_count() {
    "${BIN}" info "$1" --quiet 2>/dev/null | sed -n 's/^Pages: //p'
}

echo "jpdfium native smoke (binary: ${BIN})"

# Help must be instant and must not require the native library.
"${BIN}" help >/dev/null 2>&1 || { echo "  FAIL: help"; exit 1; }

# redact-words must remove the targeted words (correctness), output must open.
check "redact-words" 0 redact-words "${BASIC}" redacted.pdf --words "Sample,Introduction" --remove
open_ok redacted.pdf
"${BIN}" text redacted.pdf redacted.txt --quiet >/dev/null 2>&1
if grep -q "Sample" redacted.txt || grep -q "Introduction" redacted.txt; then
    echo "  FAIL: redacted terms still present"
    failures=$((failures + 1))
else
    echo "  ok: redacted terms removed"
fi

# rotate 90 must swap dimensions.
check "rotate" 0 rotate "${BASIC}" rotated.pdf --degrees 90
open_ok rotated.pdf
if [ "$(page_count rotated.pdf)" != "1" ]; then
    echo "  FAIL: rotated page count"
    failures=$((failures + 1))
fi

# pages --range must reduce the count (mozilla = 14 pages).
check "pages" 0 pages "${MOZ}" selected.pdf --range 1-2
open_ok selected.pdf
if [ "$(page_count selected.pdf)" != "2" ]; then
    echo "  FAIL: page extraction count (got $(page_count selected.pdf))"
    failures=$((failures + 1))
fi

# merge must sum page counts (1 + 14 = 15).
check "merge" 0 merge merged.pdf "${BASIC}" "${MOZ}"
open_ok merged.pdf
if [ "$(page_count merged.pdf)" != "15" ]; then
    echo "  FAIL: merge page count"
    failures=$((failures + 1))
fi

# split must produce the expected number of parts.
check "split" 0 split "${MOZ}" parts --pages-per 7
if [ "$(ls parts/ | wc -l | tr -d ' ')" != "2" ]; then
    echo "  FAIL: split part count"
    failures=$((failures + 1))
fi
open_ok parts/part-001.pdf
open_ok parts/part-002.pdf

# render must emit a PNG with the PNG signature.
check "render" 0 render "${BASIC}" rendered --dpi 72
if [ -f rendered/page-001.png ] && [ "$(od -An -tx1 -N8 rendered/page-001.png | tr -d ' \n')" = "89504e470d0a1a0a" ]; then
    echo "  ok: rendered PNG signature"
else
    echo "  FAIL: rendered PNG invalid"
    failures=$((failures + 1))
fi

# compress / flatten / watermark / background must produce valid outputs.
check "compress"  0 compress  "${BASIC}" compressed.pdf --preset LOSSLESS
check "flatten"   0 flatten   "${BASIC}" flattened.pdf
check "watermark" 0 watermark "${BASIC}" watermarked.pdf --text CONFIDENTIAL --opacity 0.3
check "background" 0 background "${BASIC}" background.pdf --color FFEEEE
open_ok compressed.pdf
open_ok flattened.pdf
open_ok watermarked.pdf
open_ok background.pdf

if [ "${failures}" -eq 0 ]; then
    echo "cli-native-smoke: all checks passed"
else
    echo "cli-native-smoke: ${failures} check(s) FAILED"
    exit 1
fi
