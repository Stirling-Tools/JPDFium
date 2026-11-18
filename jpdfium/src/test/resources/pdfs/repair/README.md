# Repair fixture provenance (`pdfs/repair/`)

## Synthesized damage cases (original works; no third-party copyright)

The ten `*.pdf` fixtures below were written by hand for this repository to
exercise `PdfRepair` against specific damage shapes. They are tiny (≈0.4–0.7 KiB)
single-page documents with deliberately corrupted structure; all ten are driven
by `PdfRepairTest.repairsSynthesizedDefectedFixtures`.

| File | Damage |
|---|---|
| `truncated_no_eof.pdf` | File ends mid-object, before any `%%EOF` |
| `corrupt_xref_offsets.pdf` | `xref` table entries point away from their objects |
| `missing_startxref.pdf` | `startxref` command absent; `xref` table and `%%EOF` still present |
| `corrupt_stream_length.pdf` | Stream `/Length` disagrees with the actual stream bytes |
| `multiple_eof.pdf` | Duplicate `%%EOF` markers with trailing garbage after the last |
| `unclosed_stream_obj.pdf` | `stream` data never closed by `endstream` before the next object |
| `stale_linearized.pdf` | First-object `/Linearized` parameters (`/L`, `/E`, `/T`) contradict the file |
| `noncontiguous_xref_index.pdf` | `xref` subsections with gaps and out-of-order entries |
| `xref_stream_mismatch.pdf` | Object offsets in the file disagree with the declared `startxref` target |
| `flate_crlf_corrupt.pdf` | FlateDecode stream whose EOL/CRLF handling breaks stream parsing |

## External fixtures

The `pdfjs-*.pdf` files in this directory come from the mozilla/pdf.js test
corpus (`test/pdfs/`, Apache License 2.0) and predate this fixture set; see
`../external/README.md` for license details. They are kept for end-to-end repair
coverage on real-world damaged documents.
