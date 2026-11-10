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
