#!/usr/bin/env bash
# Run the public API sweep over one downloaded corpus directory.
#
# The test always opens/renders/saves/verifies every PDF and runs PDF/X, CMYK,
# compression and strict crop on the first N files. The TSV report is written
# to jpdfium/build/reports/external-corpus.tsv.
#
# Usage:
#   scripts/corpus/run-corpus.sh /tmp/cc-main-1000 200
#   scripts/corpus/run-corpus.sh /Users/brios/IdeaProjects/pdfbox 50
set -euo pipefail

if (($# < 1)); then
    echo "usage: $0 <corpus-dir> [feature-limit]" >&2
    exit 64
fi

CORPUS="$1"
LIMIT="${2:-100}"
if [[ ! -d "$CORPUS" ]]; then
    echo "corpus directory not found: $CORPUS" >&2
    exit 66
fi

export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Dexternal.corpus=$CORPUS -Dexternal.featureLimit=$LIMIT"
./gradlew :jpdfium:integrationTest \
    --tests 'stirling.software.jpdfium.corpus.ExternalApiCorpusTest' \
    --rerun-tasks

echo "report: jpdfium/build/reports/external-corpus.tsv"
