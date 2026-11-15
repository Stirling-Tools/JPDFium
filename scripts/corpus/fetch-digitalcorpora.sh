#!/usr/bin/env bash
# Fetch PDFs from SAFEDOCS CC-MAIN-2021-31-PDF-UNTRUNCATED.
#
# The source is served as ~1 GB ZIPs of 1,000 PDFs. This script downloads one
# ZIP at a time into a temporary directory, extracts only PDFs, and removes the
# ZIP immediately, so a 50,000-PDF run does not leave 60 GB of archives behind.
#
# Usage:
#   scripts/corpus/fetch-digitalcorpora.sh --pdfs 50000 --out "$PWD/build/corpus/cc-main"
#   scripts/corpus/fetch-digitalcorpora.sh --pdfs 2000 --zip-start 0
#
# This is intentionally network-heavy. Check free disk before a 50k run:
# each 1,000-PDF ZIP is roughly 1.0-2.8 GB and extraction expands it.

set -euo pipefail

BASE="https://downloads.digitalcorpora.org/corpora/files/CC-MAIN-2021-31-PDF-UNTRUNCATED/zipfiles"
PDFS=1000
ZIP_START=0
OUT=""
KEEP_PDFS=1

usage() {
    sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//'
}

while (($#)); do
    case "$1" in
        --pdfs) PDFS="${2:?missing value}"; shift 2 ;;
        --zip-start) ZIP_START="${2:?missing value}"; shift 2 ;;
        --out) OUT="${2:?missing value}"; shift 2 ;;
        --discard-pdfs) KEEP_PDFS=0; shift ;;
        -h|--help) usage; exit 0 ;;
        *) echo "unknown argument: $1" >&2; usage >&2; exit 64 ;;
    esac
done

if ! [[ "$PDFS" =~ ^[1-9][0-9]*$ ]] || ! [[ "$ZIP_START" =~ ^[0-9]+$ ]]; then
    echo "PDFS must be a positive integer and ZIP_START must be a non-negative integer" >&2
    exit 64
fi

if [[ -z "$OUT" ]]; then
    OUT="$PWD/build/corpus/cc-main"
fi
mkdir -p "$OUT"

TMP="$(mktemp -d "${TMPDIR:-/tmp}/jpdfium-cc-main.XXXXXX")"
cleanup() {
    rm -rf "$TMP"
    if ((KEEP_PDFS == 0)); then
        rm -rf "$OUT"
    fi
}
trap cleanup EXIT

have=0
zip_index=$ZIP_START
while ((have < PDFS)); do
    group=$(( (zip_index / 1000) * 1000 ))
    group_end=$(( group + 999 ))
    zip_name="$(printf '%04d' "$zip_index")"
    group_name="$(printf '%04d-%04d' "$group" "$group_end")"
    url="$BASE/$group_name/$zip_name.zip"
    archive="$TMP/$zip_name.zip"
    extract="$TMP/$zip_name"

    echo "fetching $url" >&2
    curl -fL --retry 3 --retry-delay 2 -o "$archive" "$url"

    mkdir -p "$extract"
    unzip -q "$archive" -d "$extract"
    rm -f "$archive"

    while IFS= read -r -d '' pdf; do
        if ((have >= PDFS)); then
            break
        fi
        cp "$pdf" "$OUT/$(printf '%07d' "$have").pdf"
        have=$((have + 1))
    done < <(find "$extract" -type f -iname '*.pdf' -print0 | sort -z)

    rm -rf "$extract"
    zip_index=$((zip_index + 1))
done

echo "wrote $have PDFs to $OUT"
echo "run: scripts/corpus/run-corpus.sh \"$OUT\""
