#!/usr/bin/env bash
# Compare process-ink coverage of the Ghent conversion gate outputs with their
# sources. This complements the rendered MAE check: rendering can hide a spot or
# process-channel change when the RGB preview is unchanged. It is still a
# regression check, not a print-conformance certification.
set -euo pipefail

fixture_dir="${1:?fixture directory is required}"
output_dir="${2:-/tmp}"

coverage() {
    gs -q -dNOPAUSE -dBATCH -sDEVICE=inkcov -o - "$1" \
        | awk '/CMYK OK/ { printf "%.6f %.6f %.6f %.6f\n", $1, $2, $3, $4 }'
}

status=0
for source_pdf in "${fixture_dir}"/Ghent_PDF-Output-Test-*.pdf; do
    name="$(basename "${source_pdf}")"
    converted_pdf="${output_dir}/jpdfium-ghent-${name}"
    if [[ ! -f "${converted_pdf}" ]]; then
        echo "missing converted fixture: ${converted_pdf}" >&2
        status=1
        continue
    fi
    before="$(coverage "${source_pdf}")"
    after="$(coverage "${converted_pdf}")"
    if [[ -z "${before}" || -z "${after}" ]]; then
        echo "no ink coverage data for ${name}" >&2
        status=1
        continue
    fi
    if [[ "${before}" != "${after}" ]]; then
        echo "ink coverage changed for ${name}" >&2
        diff -u <(printf '%s\n' "${before}") <(printf '%s\n' "${after}") >&2 || true
        status=1
    else
        echo "ink coverage unchanged: ${name}"
    fi
done

exit "${status}"
