#!/usr/bin/env bash
set -euo pipefail
PLATFORM="${1:-linux-x64}"
DIST="native/dist/${PLATFORM}"
if [ ! -d "$DIST" ]; then
  echo "No dist dir for $PLATFORM at $DIST — skipping symbol check (maybe cross-build)."
  exit 0
fi

LIB=$(find "$DIST" -maxdepth 1 -type f \( -name 'libjpdfium.*' -o -name 'jpdfium.dll' \) | head -n1 || true)
if [ -z "${LIB:-}" ]; then
  echo "No bridge library found in $DIST — skipping"
  exit 0
fi
echo "Checking exports in $LIB"

EXPECTED=(
  jpdfium_init
  jpdfium_doc_open
  jpdfium_doc_page_count
  jpdfium_render_page
  jpdfium_text_get_chars
  jpdfium_redact_words_ex
  jpdfium_pcre2_compile
  jpdfium_flashtext_create
  jpdfium_font_fix_tounicode
  jpdfium_repair_pdf
  jpdfium_brotli_decode
)
MISSING=0
if [[ "$LIB" == *.dll ]]; then
  # Windows: use dumpbin if available, else skip
  if command -v dumpbin >/dev/null 2>&1; then
    for sym in "${EXPECTED[@]}"; do
      if ! dumpbin /EXPORTS "$LIB" 2>/dev/null | grep -q "$sym"; then
        echo "MISSING export: $sym"
        MISSING=1
      fi
    done
  else
    echo "dumpbin not found — skipping Windows symbol check"
  fi
else
  for sym in "${EXPECTED[@]}"; do
    if ! nm -D "$LIB" 2>/dev/null | grep -q " T $sym"; then
      # Also try without -D for macOS
      if ! nm -g "$LIB" 2>/dev/null | grep -q " T _\?$sym"; then
        echo "MISSING export: $sym"
        MISSING=1
      fi
    fi
  done
fi
if [ "$MISSING" -ne 0 ]; then
  echo "ERROR: one or more expected symbols missing"
  exit 1
fi
echo "Symbol export check passed for $PLATFORM"
