#!/usr/bin/env bash
set -euo pipefail
PLATFORM="${1:-linux-x64}"
DIST="${2:-native/dist/${PLATFORM}}"
if [ ! -d "$DIST" ]; then
  echo "No dist dir for $PLATFORM at $DIST - skipping symbol check (maybe cross-build)."
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
  jpdfium_render_page_into
  jpdfium_render_page_form_into
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
  DUMPBIN_BIN=$(command -v dumpbin 2>/dev/null || true)
  if [ -z "$DUMPBIN_BIN" ]; then
    DUMPBIN_BIN=$(find "/c/Program Files/Microsoft Visual Studio" \
      -name 'dumpbin.exe' 2>/dev/null | head -1 || true)
  fi
  if [ -n "$DUMPBIN_BIN" ]; then
    for sym in "${EXPECTED[@]}"; do
      if ! "$DUMPBIN_BIN" //EXPORTS "$LIB" 2>/dev/null | grep -q "$sym"; then
        echo "MISSING export: $sym"
        MISSING=1
      fi
    done
  else
    echo "dumpbin not found - skipping Windows symbol check"
  fi
else
  # Read the tables once: under pipefail, grep -q closing a pipe early fails nm with SIGPIPE.
  DYNAMIC=$(nm -D "$LIB" 2>/dev/null || true)
  # Only macOS lacks the dynamic (-D) table; elsewhere the global table is not proof of a
  # dynamic export, so the fallback is restricted to the platform that needs it.
  GLOBAL=""
  if [[ "$PLATFORM" == darwin* ]]; then
    GLOBAL=$(nm -g "$LIB" 2>/dev/null || true)
  fi
  for sym in "${EXPECTED[@]}"; do
    # Anchor the end so a longer name (jpdfium_init_library for jpdfium_init) cannot satisfy it.
    if ! grep -qE " T _?${sym}$" <<<"$DYNAMIC" && ! grep -qE " T _?${sym}$" <<<"$GLOBAL"; then
      echo "MISSING export: $sym"
      MISSING=1
    fi
  done
fi
if [ "$MISSING" -ne 0 ]; then
  echo "ERROR: one or more expected symbols missing"
  exit 1
fi
echo "Symbol export check passed for $PLATFORM"
