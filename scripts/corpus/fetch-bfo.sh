#!/usr/bin/env bash
# Fetch the BFO PDF/A-2 test suite (33 files) used as a print/archive preflight
# corpus alongside veraPDF, PDF.js, PDFium, PDFBox and Ghent.
set -euo pipefail

OUT="${1:-$PWD/build/corpus/bfo}"
if [[ -e "$OUT" ]]; then
    echo "destination already exists: $OUT" >&2
    exit 64
fi

git clone --depth 1 https://github.com/bfosupport/pdfa-testsuite.git "$OUT"
echo "wrote $(find "$OUT" -type f -iname '*.pdf' | wc -l | tr -d ' ') PDFs to $OUT"
