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

- Integration suite: `4901` tests, `0` failures, `0` errors, `315` skipped.
- Release ctest: `6/6` passed.
- ASan+UBSan ctest: `6/6` passed with
  `ASAN_OPTIONS=detect_container_overflow=0`. Default ASan still reports the
  external Homebrew qpdf `getAllObjects()` container-overflow described in the
  earlier audit; it is not reproduced by standalone qpdf probes and is not
  claimed green here.
