# Native C++ quality and security pass

This pass used the project `.clang-tidy`, cppcheck, and ASan/UBSan to harden the
C++ bridge. It targeted ownership, exception boundaries, integer conversions,
and non-deterministic output; no functional PDF behaviour was changed.

## Tools

```sh
cppcheck --project=native/build-real/compile_commands.json \
  --enable=warning,performance,portability --inline-suppr \
  --suppress=missingIncludeSystem --suppress=missingInclude -j 8

run-clang-tidy -p native/build-real -j 8 \
  -extra-arg=-isystem -extra-arg="$(xcrun --show-sdk-path)/usr/include/c++/v1" \
  -extra-arg=-isystem -extra-arg="$(xcrun --show-sdk-path)/usr/include" \
  -source-filter='.*/bridge/src/.*'

ASAN_OPTIONS=detect_container_overflow=0 ctest --test-dir native/build-asan
```

## Fixes

- **C ABI ownership under exceptions.** Document and page creation now build
  their owning `shared_ptr`/`unique_ptr` before registering the raw handle, so a
  registry allocation failure cannot leak a PDFium document or page. Page close
  frees through `unique_ptr`.
- **qpdf output stream.** `QPDFWriter` is told not to close the output `FILE*`;
  a `unique_ptr` owns it for the whole write, including exception paths.
- **`noexcept` exception escapes.** Annotation JSON serialization and the stub
  backend now convert allocation failures to `JPDFIUM_ERR_NATIVE` instead of
  terminating at the C ABI boundary.
- **Staging-file collision reporting.** `safe_fopen_write` reports `EEXIST` via
  an explicit out-parameter rather than relying on a caller-side `errno` read.
- **Integer and conversion safety.** Pixel loops use `size_t` totals, stencil
  bit writes use `std::bit_cast` (the standard-defined object-representation
  conversion, https://en.cppreference.com/w/cpp/numeric/bit_cast), size limits
  use `size_t`/`long long`, and image/pattern geometry uses explicit
  floating-point conversions.
- **Checked numeric input.** `JPDFIUM_FLATTEN_JPEG_QUALITY` and forced PDF
  version strings use `strtol` with full-consumption and range checks instead of
  `atoi`/`sscanf`.
- **Initialization.** PDF/A and font rewrite contexts have constructors and
  default member values; shape/text/SFNT records no longer depend on aggregate
  zero-initialization.
- **Deterministic redaction.** Match object positions and promotion candidates
  are visited in document order rather than pointer-hash order; destruction is
  ordered by depth and document index.
- **Dead logic.** Removed an unused `requiresFission` flag whose only remaining
  effect was to stop a scan.
- **Page-tree reconstruction.** Rebuilt `/Pages` now preserves page and catalog
  object generations, follows the root `/Kids` traversal instead of raw
  definition order, skips CR-only stream payloads, and does not treat a
  `/stream` name as the stream keyword.
- **Review-feedback batch.** Corrected finite tiling-pattern steps (ISO 32000-1
  §8.7.3.1), CFF header `offSize` validation, trailer `/Size` counting for the
  rebuilt page tree, and unsigned `/ByteRange` text handling. DeviceN `/Process`
  rebinding now preserves the original process mapping when its `/Components`
  array cannot follow the new component count (ISO 32000-1 Table 72); a
  regression checks both `/ColorSpace` and `/Components`. Unresolved CodeRabbit
  feedback was audited across 48 PRs (129 open threads); fixes were limited to
  confirmed current-tip defects.
- **Catalog selection from object dictionaries.** Page-tree reconstruction and
  truncated-file salvage selected the last raw `/Type /Catalog` substring,
  including text inside a later Info object's `/Subject (...)` value. The
  scanner now walks top-level object headers, extracts each object's first
  dictionary, and accepts `/Type` only at dictionary depth 1 outside strings,
  comments, hex strings, arrays, and nested dictionaries. The real catalog and
  its generation are retained instead of pointing `/Root` at Info. Regression:
  `PdfRepairStructuralDamageTest.catalogSelectionIgnoresTypeTextInsideLaterInfoObject`
  (pre-fix selected object 4 and wrote `/Root 4 0 R`).
- **PDF/A part after a conformance-only marker.** A raw Info string such as
  `pdfaid:conformance='B'` made the scanner treat the document as a PDF/A
  claim, but without a raw `pdfaid:part` it defaulted to part 2 and skipped the
  catalog XMP that actually said part 1. Compression then generated object
  streams and an xref stream, both forbidden by PDF/A-1. A conformance-only
  match now decodes the catalog metadata before choosing the structural policy;
  an explicit raw part marker remains authoritative. Regressions:
  `PdfCompressorDetectionTest.conformanceOnlyInfoResolvesPartFromCompressedXmp`
  (failed with `expected <1> but was <2>`) and
  `PdfCompressorTest.conformanceOnlyInfoDoesNotHideCompressedPdfA1Claim`, which
  compresses the document and asserts no `/ObjStm`, no `/XRef`, and uncompressed
  metadata.
- **Type 2 colour functions.** Rewriting only `/C0` and `/C1` preserved the
  gradient endpoints but shifted every intermediate colour because the
  RGB-to-CMYK LUT is nonlinear. Type 2 functions are now sampled at 257 points
  across `/Domain` (16-bit, four outputs) and each sample is converted; an odd
  count keeps the midpoint exact. Outputs are clipped to the optional `/Range`
  before colour conversion (ISO 32000-1 Table 40), stitching subfunctions
  correctly receive one input (Table 41), and converted functions are memoized
  so shared indirect functions cannot remain unconverted. Regressions:
  `PdfXConverterTest.type2ShadingFunctionPreservesConvertedMidpoint` checks
  x=0.25 and x=0.5 against the same ICC profile used by the converter, and
  `type2FunctionRangeClipsBeforeLabConversion` proves a Lab `a=80` input is
  clipped to `/Range` `[-20 20]` before conversion (verified to fail with
  clipping disabled).
- **Incomplete font-usage scans.** `PdfFontAuditor.visibleFontKeys` used to keep
  whatever a failed child scan had collected, so a page that could not be
  walked completely made an unvisited font look invisible and best-effort
  embedding skipped it. Usage classification now returns an explicit
  `FontUsageScan` with a `complete` flag; a failed page, form, or annotation
  walk yields an incomplete result and never suppresses embedding. Unused page
  resources are also no longer treated as invisible text: suppression requires
  a complete scan that observed the font only under rendering mode 3. Regressions:
  `PdfFontEmbedderTest.embedsUnusedPageResourceFontWhenAnotherFontIsVisible`,
  `incompleteUsageScanNeverClassifiesFontsAsInvisibleOnly`,
  `annotationPageReportsIncompleteUsageScan`, and
  `bestEffortEmbedsSoleInvisibleFont`.
- **Corrupt CID font replacement.** Replacing a corrupt CID font program with a
  donor kept `/CIDToGIDMap /Identity`, so CID 1 rendered donor glyph 1 instead
  of the donor glyph for `A`. Corrupt replacements now request a verified
  CID-to-GID mapping derived from `/ToUnicode` (ISO 32000-1 9.7.5.3); when the
  mapping cannot be established the font stays unresolved instead of being
  reported as embedded. Regressions:
  `PdfFontEmbedderTest.replacesCorruptCidProgramWithVerifiedCidToGidMapping`
  and `corruptCidWithoutToUnicodeStaysUnresolved` (the latter failed before the
  mapping flag was passed).
- **Font stream metadata after cmap surgery.** Replacing an embedded program
  creates a new stream dictionary, so carrying the descriptor key forward is
  not enough. The normalizer now rebuilds `/FontFile2` with `/Length1` equal to
  the new decoded size and copies `/FontFile3 /Subtype` (`/OpenType` or
  `/Type1C`); both stream-replacement paths share one helper instead of
  duplicating the metadata logic. Regression:
  `FontNormalizerTest.cmapRepairPreservesFontFile2Length1`; removing the helper's
  metadata writes reproduces `rewritten /FontFile2 must keep /Length1`.
- **Nonzero CID selecting GID 0.** A CID whose `/ToUnicode` maps to a control or
  replacement scalar is pinned to GID 0. `cidGlyphOrderMatches` previously
  skipped those scalars, accepted `/CIDToGIDMap /Identity`, and then kept
  Identity in the fallback, so CID 1 selected donor GID 1. Identity is now
  accepted only when the CID itself is 0; otherwise a remap stream is written.
  Regression: `PdfFontEmbedderTest.controlCodepointsDoNotBlockSubstitution`
  now asserts CID 1 → GID 0 (failed before the fix with a name-valued map).
- **ToUnicode codespaces.** Rebuilt CMaps now declare one codespace range for
  every retained source width and emit each code at its full width. A
  resource-limited parse clears its partial entries and leaves the original
  `/ToUnicode` untouched instead of publishing a CMap that omits valid
  mappings. Regressions: `ToUnicodeSanitizerTest` covers mixed one/two-byte
  mappings and a range that exceeds the destination-byte ceiling.
- **Repair sanitization contract.** `PdfRepair` now reports a signature
  conflict when `sanitize(true)` is requested for a clean signed document
  instead of returning the original bytes as `CLEAN`. JavaScript and embedded
  files are always scrubbed by the qpdf structural pass; PDFium-level font,
  comment, metadata, and hidden-text hardening remains best effort on salvage
  repairs whose page tree PDFium cannot enumerate. The qpdf JavaScript scrub
  walks the raw `/Pages` tree rather than `QPDFPageDocumentHelper`, which
  previously made salvaged page trees unreadable. Regressions:
  `PdfRepairTest.sanitizeOptionRemovesCatalogJavaScript`,
  `PdfRepairFileBackedTest.sanitizeRequestOnCleanSignedDocumentReturnsConflict`,
  and the existing truncated-page-dictionary repair test.

## CodeRabbit review batch

- **Tiling-pattern steps.** The PDF/X and qpdf passes previously rewrote any
  finite step below 1 to 1 and capped large steps at 32767. ISO 32000-1 8.7.3.1
  allows any finite nonzero step, and changing it alters tile spacing. Both
  passes now replace only missing, non-finite, or zero steps. Regression:
  `PdfXPatternClampTest` verifies fractional `0.5`, large `90000`, and the
  qpdf optimizer path. Reference:
  https://opensource.adobe.com/dc-acrobat-sdk-docs/pdfstandards/pdfreference1.7old.pdf
- **CFF header validation.** `PdfFontAuditor.looksLikeFontProgram` and the native
  font-inventory validator required `offSize == 4`; the CFF header permits
  1..4. Both now validate `major=1, minor=0, hdrSize>=4, offSize=1..4`.
  Regression: `PdfFontEmbedderTest.acceptsBareCffProgramMagic`. Reference:
  https://adobe-type-tools.github.io/font-tech-notes/pdfs/5176.CFF.pdf
- **Trailer `/Size` after page-tree rebuild.** The rebuilt `/Pages` object was
  not counted in `/Size`; it is now one greater than the highest object
  (`maxObj + 2` or new catalog + 1). Regression:
  `PdfRepairStructuralDamageTest.rebuildsMissingPageTreeFromPageDictionaries`.
- **Unsigned `/ByteRange` text.** `PdfRepair` used a raw substring scan, so an
  unsigned PDF whose metadata contained `/ByteRange` skipped repair and
  sanitization. It now uses the structural `PdfDocument.containsByteRangeArray`
  check; bare text no longer suppresses repair. Regression:
  `PdfRepairTest.unsignedByteRangeTextDoesNotSuppressRepair`; the real signed
  fixture still passes through untouched.

## Residual diagnostics

After this pass, the project `.clang-tidy` checks and cppcheck report no
findings for `native/bridge/src`. The test-only `hardening_regression` table
tag initializer was also fixed, so cppcheck covers the whole project cleanly.
There are **zero** `clang-analyzer-*`, `bugprone-exception-escape`,
`bugprone-unused-return-value`, `bugprone-narrowing-conversions`,
`bugprone-implicit-widening-of-multiplication-result`,
`bugprone-nondeterministic-pointer-iteration-order`, or
`bugprone-empty-catch` findings.

## Verification

- Integration suite: `4917` tests, `0` failures, `0` errors, `314` skipped.
- Release ctest: `6/6` passed.
- ASan+UBSan ctest: `6/6` passed with
  `ASAN_OPTIONS=detect_container_overflow=0`. Default ASan still reports the
  external Homebrew qpdf `getAllObjects()` container-overflow described in the
  earlier audit; it is not reproduced by standalone qpdf probes and is not
  claimed green here.
