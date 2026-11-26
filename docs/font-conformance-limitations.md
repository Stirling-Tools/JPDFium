# Font conformance limitations

This page records notable malformed-font fixtures and how they are handled after PDF/A
conversion. All ordinary font fixtures and the 94-document conformance corpus pass
PDF/A-1b, PDF/A-2u, PDF/A-2a, and PDF/A-3b with veraPDF 1.30.

## Resolved: `repair/pdfjs-issue5334.pdf` — non-CID-keyed CFF in a CIDFontType0

- Rule: ISO 19005-1:2005 6.3.2 (unsupported `/FontFile3 /Subtype /OpenType`).
- Font: `/CIDFontType0` whose `/FontFile3` is an `OTTO` OpenType wrapper around a CFF
  program with no `ROS` operator. FreeType reports `FT_IS_CID_KEYED == 0` for both the
  wrapper and the extracted CFF.
- Resolution: the converter now rebuilds the CFF as a CID-keyed program without changing
  its CharStrings or local subrs. It writes an identity charset (CID = GID, matching the
  document's Identity-H codes), moves the Private DICT into a single-entry FDArray, adds a
  format-0 FDSelect, and sets `ROS` to Adobe-Identity 0. The output is emitted as
  `/CIDFontType0C`.
- Why identity CIDs are correct here: the content stream uses glyph IDs as character codes
  (`0x34` -> GID 52 -> `S`), and the original CFF charset maps the same GID to `S`.
  Rendering the source and converted PDFs at 120 dpi produces pixel-identical images, and
  `pdftotext` extracts the same text.
- Verification: converted output passes veraPDF PDF/A-1b; FontBox parses one `CFFCIDFont`
  with 848 CharStrings and glyph 52 remains loadable; FreeType reports
  `FT_IS_CID_KEYED == 1` and CID 52 for GID 52.
- Regression test: `PdfFontRewriterTest#convertsNonCidCffToCidKeyedForPdfA1b`.

## Resolved: `repair/pdfjs-bug1068432.pdf` — malformed Type1C subset

The 725-byte Type1C program contained two empty real-number operands (`1e ff`) in its CFF
Top DICT. FreeType and FontBox tolerated them, but FontTools and veraPDF rejected the
dictionary, so PDF/A reported 6.3.4 and 6.3.5. The converter now rewrites those operands to
`1e 0f` (real `0`) without changing the token length; the converted document passes
PDF/A-1b and the repaired program parses in FontBox with all four glyphs.
Regression test: `PdfFontRewriterTest#repairsMalformedType1cSubsetForPdfA1b`.

## Validation evidence

```bash
# Convert the fixture with the native PDF/A-1b profile, then validate:
verapdf --flavour 1b --format text <converted>.pdf

# Regression tests cover the CFF conversion and malformed-real repair:
./gradlew :jpdfium:integrationTest \
  --tests 'stirling.software.jpdfium.fonts.PdfFontRewriterTest'
```

Broad fixture sweep (207 convertible files from `jpdfium/src/test/resources/pdfs`): 207/207
pass PDF/A-1b. The previously failing orphan widget, ICC v4, and undefined-operator
fixtures now pass; regression tests cover each repair in `PdfAObjectNormalizationTest`.

## References

- PDF.js commit `f81fc9091aa853f8bd8f9ce06ef91fab2016a14b`, "Correctly detect OpenType font
  files with CFF data" (fixes issue 5334).
- Mozilla bug 1068432, "Math symbols ⇈ and ⇊ are not rendered"; fixed upstream by PDF.js
  PR 7300 after the CFF parser rejected the reduced testcase.
- `pdf-association/arlington-pdf-model#98` and `pdf-association/pdf-issues#473` discuss the
  allowed `FontFile3 /Subtype /OpenType` combinations for CIDFontType0.
