# Public uncommon PDF fixtures

Selected from [py-pdf/sample-files](https://github.com/py-pdf/sample-files), which
licenses its PDF fixtures under [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/).

| File | Source path | Why it is unusual |
|---|---|---|
| `imagemagick-CCITTFaxDecode.pdf` | `007-imagemagick-images/imagemagick-CCITTFaxDecode.pdf` | 3x3 page with CCITT fax image content |
| `png-predictors-low-bit-depth.pdf` | `032-png-predictors-low-bit-depth/png-predictors-low-bit-depth.pdf` | PNG predictor streams with low bit depth |
| `wrong-references.pdf` | `028-image-references-deduplication/wrong-references.pdf` | Image references that deliberately point at the wrong objects |
| `ocg_map_test.pdf` | `029-optional-content-groups/ocg_map_test.pdf` | Optional-content-group membership map |

The larger `017-unreadable-meta-data/unreadablemetadata.pdf` was inspected but
not committed: JPDFium currently rejects it with `PdfCorruptException` even
though qpdf can reconstruct its xref. It remains a useful external repair test.

## PDF.js fixtures

From [mozilla/pdf.js](https://github.com/mozilla/pdf.js), Apache License 2.0,
`test/pdfs/`:

| File | Why it is unusual |
|---|---|
| `pdfjs/simpletype3font.pdf` | Minimal Type3 glyph program |
| `pdfjs/perf_text_type3_bitmap.pdf` | Text rendered through Type3 bitmap glyphs |
| `pdfjs/perf_images_inline.pdf` | Dense inline-image content stream |
| `pdfjs/ContentStreamCycleType3insideType3.pdf` | Content-stream cycle through nested Type3 resources; kept as a fixture but not parsed by default |
| `pdfjs/xref_command_missing.pdf` | Missing `startxref` command |

## veraPDF conformance fixtures

From [veraPDF/veraPDF-corpus](https://github.com/veraPDF/veraPDF-corpus),
[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). These are atomic
PDF/A-2b and PDF/A-4 pass/fail cases, not full documents.

## OpenPreserve Cabinet of Horrors

From [openpreserve/format-corpus](https://github.com/openpreserve/format-corpus),
CC0 unless stated otherwise:

| File | Why it is unusual |
|---|---|
| `cabinet/corruptionOneByteMissing.pdf` | Deliberate one-byte corruption |
| `cabinet/pdf-17-header18.pdf` | PDF 1.7 content with a 1.8 header |
| `cabinet/text_only_fontsNotEmbedded.pdf` | Text with non-embedded fonts |

## qpdf malformed fixtures

From [qpdf/qpdf](https://github.com/qpdf/qpdf), Apache License 2.0,
`qpdf/qtest/qpdf/`:

`bad1.pdf`, `bad-xref.pdf`, `bad-token-startxref.pdf`, `append-xref-loop.pdf`.
They exercise empty input, damaged xref tables, a missing `startxref`, and a
looping incremental xref chain.

## Evil PDF library

From
[GerHobbelt/Evil-PDF-Library-for-Qiqqa](https://github.com/GerHobbelt/Evil-PDF-Library-for-Qiqqa),
GPL-3.0 repository; document copyrights remain with their original owners.
Three tiny PDFium crash-regression inputs are included under `evil/` solely as
fixtures. They are **never parsed by the default test suite**. Run them only in
an isolated process/container with resource limits:

- `bug_325_a.pdf`
- `bug_544880.pdf`
- `bug_782596.pdf`

See `MalformedPdfFixturesTest` for the disabled sandbox-only test.
