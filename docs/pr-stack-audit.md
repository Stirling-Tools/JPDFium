# PR stack hunk audit record

This record inventories every production file changed by PRs #243–#287 and the number of
diff hunks in each file. Hunk counts come from `git diff --unified=0 <base> <head>`.
The associated test evidence is the full integration suite, the PDF/A corpus runs, static
analysis, and the targeted font/normalizer/repair tests listed in the project verification
notes. Temporary probes were used for the streaming `/ByteRange` marker scan and repeated
resource-key deduplication; both passed.

Generated from the PR stack; generated data/ICC/CMap tables are included because they are
production artifacts even when their hunks are large.

| PR | Title | Production files (hunks) | Test files in PR |
|---|---|---|---|
| #243 | Audit and embed every font resource | `jpdfium/src/main/java/stirling/software/jpdfium/doc/PdfFontAuditor.java` (5h); `jpdfium/src/main/java/stirling/software/jpdfium/doc/PdfFontEmbedder.java` (15h); `jpdfium/src/main/java/stirling/software/jpdfium/doc/PdfXConverter.java` (3h); `jpdfium/src/main/java/stirling/software/jpdfium/panama/QpdfLib.java` (2h); `jpdfium/src/main/java/stirling/software/jpdfium/util/NativeJsonParser.java` (1h); `native/bridge/include/jpdfium.h` (1h); `native/bridge/src/jpdfium_qpdf.cpp` (2h) | 11 |
| #244 | Add bundled fallback fonts and font diagnostics | `jpdfium/src/main/java/stirling/software/jpdfium/doc/PdfFontAuditor.java` (4h); `jpdfium/src/main/java/stirling/software/jpdfium/doc/PdfFontEmbedder.java` (48h) | 1 |
| #246 | Check CID width coverage against content-stream CIDs | `jpdfium/src/main/java/stirling/software/jpdfium/doc/PdfFontAuditor.java` (5h); `jpdfium/src/main/java/stirling/software/jpdfium/doc/PdfFontEmbedder.java` (13h); `jpdfium/src/main/java/stirling/software/jpdfium/panama/QpdfLib.java` (3h); `native/bridge/include/jpdfium.h` (1h); `native/bridge/src/jpdfium_qpdf.cpp` (8h) | 4 |
| #248 | Keep qpdf font output PDF/A stream-EOL compliant | `jpdfium/src/main/java/stirling/software/jpdfium/doc/PdfFontEmbedder.java` (1h); `native/bridge/src/jpdfium_advanced.cpp` (8h); `native/bridge/src/jpdfium_qpdf.cpp` (40h) | 3 |
| #249 | Wire FontNormalizer ToUnicode repair to qpdf and FreeType | `native/bridge/include/jpdfium_font_encoding.h` (1h); `native/bridge/src/jpdfium_advanced.cpp` (16h) | 0 |
| #251 | Scan used CIDs, select TTC faces, and repair CIDSet streams | `native/bridge/src/jpdfium_advanced.cpp` (5h) | 0 |
| #252 | Harden QPDF content-stream scanning and font cache bounds | `jpdfium/src/main/java/stirling/software/jpdfium/doc/PdfFontAuditor.java` (3h); `jpdfium/src/main/java/stirling/software/jpdfium/doc/PdfFontEmbedder.java` (24h); `native/bridge/src/jpdfium_qpdf.cpp` (57h) | 2 |
| #253 | Subset already-embedded fonts during PDF compression | `jpdfium/src/main/java/stirling/software/jpdfium/doc/CompressOptions.java` (6h); `jpdfium/src/main/java/stirling/software/jpdfium/doc/CompressPreset.java` (9h); `jpdfium/src/main/java/stirling/software/jpdfium/doc/PdfCompressor.java` (4h); `jpdfium/src/main/java/stirling/software/jpdfium/doc/PdfFontAuditor.java` (3h); `jpdfium/src/main/java/stirling/software/jpdfium/doc/PdfFontEmbedder.java` (10h); `jpdfium/src/main/java/stirling/software/jpdfium/doc/PdfOptimizer.java` (1h); `jpdfium/src/main/java/stirling/software/jpdfium/panama/QpdfLib.java` (1h); `native/bridge/include/jpdfium.h` (2h); `native/bridge/src/jpdfium_qpdf.cpp` (17h) | 2 |
| #254 | Add complete Adobe Glyph List 2.0 table | `native/bridge/include/jpdfium_font_encoding.h` (6h) | 1 |
| #256 | Add Adobe Glyph List resolution helpers | `jpdfium/src/main/java/stirling/software/jpdfium/doc/PdfFontEmbedder.java` (2h); `native/bridge/src/jpdfium_qpdf.cpp` (8h) | 1 |
| #257 | Harden fallback selection and diagnostics | `jpdfium/src/main/java/stirling/software/jpdfium/doc/PdfFontEmbedder.java` (9h); `jpdfium/src/main/java/stirling/software/jpdfium/panama/QpdfLib.java` (2h); `native/bridge/include/jpdfium.h` (1h); `native/bridge/src/jpdfium_qpdf.cpp` (15h) | 2 |
| #258 | Cover FontNormalizer ToUnicode and width repair | `native/bridge/src/jpdfium_advanced.cpp` (3h) | 1 |
| #259 | Harden font normalization and CIDSet preservation | `native/bridge/src/jpdfium_advanced.cpp` (37h) | 1 |
| #260 | Verify and gate font compression | `native/bridge/include/jpdfium_font_encoding.h` (6h) | 1 |
| #261 | Repair CIDSet, widths, and CFF/PDF/X font handling in native bridge | `jpdfium/src/main/java/stirling/software/jpdfium/panama/QpdfLib.java` (1h); `native/bridge/src/jpdfium_advanced.cpp` (28h); `native/bridge/src/jpdfium_pdfx.cpp` (2h); `native/bridge/src/jpdfium_qpdf.cpp` (45h) | 0 |
| #262 | Address the remaining font-stack review findings | `jpdfium/src/main/java/stirling/software/jpdfium/doc/CompressOptions.java` (1h); `jpdfium/src/main/java/stirling/software/jpdfium/doc/PdfFontAuditor.java` (1h); `jpdfium/src/main/java/stirling/software/jpdfium/doc/PdfFontEmbedder.java` (26h); `jpdfium/src/main/java/stirling/software/jpdfium/fonts/FontNormalizer.java` (3h); `native/bridge/src/jpdfium_advanced.cpp` (31h); `native/bridge/src/jpdfium_pdfx.cpp` (2h); `native/bridge/src/jpdfium_qpdf.cpp` (23h) | 4 |
| #263 | Add opt-in re-subsetting for tagged fonts | `jpdfium/src/main/java/stirling/software/jpdfium/doc/CompressOptions.java` (5h); `jpdfium/src/main/java/stirling/software/jpdfium/doc/PdfCompressor.java` (2h); `jpdfium/src/main/java/stirling/software/jpdfium/panama/JpdfiumLib.java` (1h); `jpdfium/src/main/java/stirling/software/jpdfium/panama/QpdfLib.java` (3h); `native/bridge/include/jpdfium.h` (2h); `native/bridge/src/jpdfium_qpdf.cpp` (8h) | 1 |
| #265 | Embed 90ms-RKSJ-H CID fonts through a verified Japan1 mapping | `jpdfium/src/main/java/stirling/software/jpdfium/doc/PdfFontEmbedder.java` (4h); `native/bridge/src/jpdfium_qpdf.cpp` (27h) | 1 |
| #267 | Scan font usage once per document and close review gaps | `native/bridge/src/jpdfium_advanced.cpp` (1h); `native/bridge/src/jpdfium_pdfx.cpp` (5h); `native/bridge/src/jpdfium_qpdf.cpp` (15h) | 2 |
| #271 | Embed CJK CID fonts and fix PDF/A font output | `jpdfium/src/main/java/stirling/software/jpdfium/doc/PdfFontEmbedder.java` (11h); `jpdfium/src/main/java/stirling/software/jpdfium/doc/PdfOptimizer.java` (1h); `jpdfium/src/main/java/stirling/software/jpdfium/doc/PdfXOptions.java` (1h); `jpdfium/src/main/java/stirling/software/jpdfium/fonts/FontNormalizer.java` (4h); `jpdfium/src/main/java/stirling/software/jpdfium/panama/QpdfLib.java` (1h); `native/bridge/src/jpdfium_advanced.cpp` (5h); `native/bridge/src/jpdfium_qpdf.cpp` (54h) | 7 |
| #275 | Accelerate CJK CMap lookups and reduce font scan allocations | `jpdfium/src/main/java/stirling/software/jpdfium/doc/PdfFontEmbedder.java` (7h); `jpdfium/src/main/java/stirling/software/jpdfium/doc/PdfImageOptimizer.java` (9h); `jpdfium/src/main/java/stirling/software/jpdfium/text/PdfTextExtractor.java` (5h); `jpdfium/src/main/java/stirling/software/jpdfium/text/PdfTextSearcher.java` (4h); `jpdfium/src/main/java/stirling/software/jpdfium/util/NativeJsonParser.java` (18h); `native/bridge/src/jpdfium_advanced.cpp` (5h); `native/bridge/src/jpdfium_qpdf.cpp` (71h) | 1 |
| #278 | Optimize font scanning, JSON parsing, image optimization, and 2-up splitting | `jpdfium/src/main/java/stirling/software/jpdfium/doc/PdfFontEmbedder.java` (7h); `jpdfium/src/main/java/stirling/software/jpdfium/doc/PdfImageOptimizer.java` (9h); `jpdfium/src/main/java/stirling/software/jpdfium/doc/PdfPageSplitter.java` (4h); `jpdfium/src/main/java/stirling/software/jpdfium/text/PdfTextExtractor.java` (5h); `jpdfium/src/main/java/stirling/software/jpdfium/text/PdfTextSearcher.java` (4h); `jpdfium/src/main/java/stirling/software/jpdfium/util/NativeJsonParser.java` (18h); `native/bridge/src/jpdfium_advanced.cpp` (5h); `native/bridge/src/jpdfium_qpdf.cpp` (87h) | 3 |
| #282 | Add FreeType width repair and whole-document FontNormalizer API | `jpdfium/src/main/java/stirling/software/jpdfium/fonts/FontNormalizer.java` (20h); `jpdfium/src/main/java/stirling/software/jpdfium/redact/PdfRedactor.java` (1h); `native/bridge/src/jpdfium_advanced.cpp` (3h) | 0 |
| #283 | Re-subset embedded SFNT and CFF font programs in FontNormalizer | `native/bridge/src/jpdfium_advanced.cpp` (14h) | 0 |
| #284 | Update compression options, strict embed verification, and font repair tests | `jpdfium/src/main/java/stirling/software/jpdfium/doc/CompressOptions.java` (2h); `jpdfium/src/main/java/stirling/software/jpdfium/doc/CompressPreset.java` (5h); `jpdfium/src/main/java/stirling/software/jpdfium/doc/PdfCompressor.java` (3h); `jpdfium/src/main/java/stirling/software/jpdfium/doc/PdfFontAuditor.java` (1h); `jpdfium/src/main/java/stirling/software/jpdfium/doc/PdfFontEmbedder.java` (5h) | 5 |
| #285 | Optimize font compression, CJK/Japan1 CMaps, and SFNT subsetting | `jpdfium/src/main/java/stirling/software/jpdfium/doc/PdfFontEmbedder.java` (17h); `jpdfium/src/main/java/stirling/software/jpdfium/text/PdfTextSearcher.java` (3h); `jpdfium/src/main/java/stirling/software/jpdfium/util/NativeJsonParser.java` (5h); `native/bridge/src/jpdfium_advanced.cpp` (14h); `native/bridge/src/jpdfium_qpdf.cpp` (38h); `native/bridge/src/jpdfium_qpdf_writer_lock.h` (1h) | 5 |
| #286 | Harden PDF/A normalizer, TrueType Differences/widths, signatures, and target-size compression | `jpdfium/src/main/java/stirling/software/jpdfium/doc/PdfCompressor.java` (6h); `jpdfium/src/main/java/stirling/software/jpdfium/doc/PdfFontEmbedder.java` (12h); `jpdfium/src/main/java/stirling/software/jpdfium/panama/QpdfLib.java` (8h); `native/bridge/include/jpdfium.h` (5h); `native/bridge/include/jpdfium_font_encoding.h` (1h); `native/bridge/src/jpdfium_advanced.cpp` (10h); `native/bridge/src/jpdfium_qpdf.cpp` (11h) | 3 |
| #287 | Fix CJK CMap edge cases, font embedding, normalizer, and JSON parsing | `jpdfium/src/main/java/stirling/software/jpdfium/doc/PdfFontEmbedder.java` (3h); `jpdfium/src/main/java/stirling/software/jpdfium/util/NativeJsonParser.java` (3h); `native/bridge/src/jpdfium_advanced.cpp` (12h); `native/bridge/src/jpdfium_qpdf.cpp` (23h) | 3 |

Total production files: 112; total hunks: 1205.

## Reviewed batches

- `PdfCompressor.java`, `CompressOptions.java`, `CompressPreset.java` — #253, #284, #286:
  subsetting before the qpdf pass, subset validation via text/render comparison,
  object-stream PDF/A fallback, progressive target-size ladder, and preset wiring.
- `PdfPageSplitter.java`, `PdfMerger.java`, `PdfAutoCrop.java` — #278, #288:
  page-index accounting, repair/import fallbacks, detection failure handling, and
  fatal-error propagation.
- `PdfFontAuditor.java`, `PdfFontEmbedder.java` — #243, #244, #246, #252, #257:
  resource inventory, Type 3 handling, invisible-text policy, fallback matching,
  and CID width coverage.
- `NativeJsonParser.java` — #243, #278, #287: string-aware object scanning,
  escaped braces/quotes, colon handling, and integer/long overflow checks.
- `PdfRepair.java` — #288, #294, #295: token normalization, page-tree rebuild,
  streaming marker scan, repeated-resource deduplication, and signature/password
  handling.
- `jpdfium_signature.cpp` — #295: out-parameter initialization, null-core guards,
  and digest-length validation.
- `jpdfium_sanitize.cpp` — #295: operand-counted ToUnicode bfchar/bfrange parsing,
  array-form ranges, surrogate/UTF-16 advancement, entry/byte caps, two-byte
  codespace emission, and 100-entry bfchar blocks.
- `PdfImageOptimizer.java`, `PdfTextExtractor.java`, `PdfTextSearcher.java` —
  #275/#278: stride-aware image fast paths, packed-record text extraction,
  decimal parsing, and in-place search-match integer parsing. Targeted suites
  `PdfImageOptimizerTest`, `PdfImageEdgeAndReencodeTest`,
  `PdfTextExtractorTest`, `PdfTextExtractorPackedTest`, and
  `PdfTextSearcherTest` all pass.
- `PdfXOptions.java`, `JpdfiumLib.java`, `PdfRedactor.java`,
  `jpdfium_qpdf_writer_lock.h`, `PdfOptimizer.java`, `PdfXConverter.java`,
  `jpdfium_pdfx.cpp` — #243, #253, #261, #262, #263, #267, #271, #282, #285:
  ABI handshake version, subset-fonts flag parity, whole-document
  `fixToUnicodeAll`/`repairWidthsAll` redaction path, writer-lock contract, and
  the tiling-pattern step clamp. A defect was found and fixed: the `Path` and
  `PdfDocument` PDF/X entry points skipped the raw `/ByteRange` guard that the
  byte[] entry point applied, so an orphan signature dictionary could be
  silently dropped by the rewrite. Both entry points now scan the retained
  open-time bytes (or source file) before the snapshot is rewritten, and
  `PdfXConverterTest` covers all three entry points. `PdfXPatternClampTest`
  covers the native clamp for huge, negative, zero, and sub-unit steps.
- `QpdfLib.java`, `jpdfium.h`, `jpdfium_font_encoding.h`, `FontNormalizer.java`
  — #243, #246, #249, #253, #254, #257, #260, #262, #263, #271, #282, #286,
  #288: font inventory/subset/tag flags and their error mapping, ABI version 3
  on both sides, shared Standard/WinAnsi/MacRoman tables, AGL name and sequence
  lookup, and the whole-document `fixToUnicodeAll`/`repairWidthsAll` path with
  native-handle refresh. The encoding tables were checked against PDF.js
  `encodings.js`; the generated AGL tables are sorted and unique; new C++
  checks in `cmap_tables.cpp` pin representative code points, escapes, variant
  suffixes, and sequence expansion. No defect found in this batch.
- `jpdfium_advanced.cpp` — #248, #249, #251, #258, #259: JSON escaping of font
  family names, empty-program rejection, base-encoding/Differences resolution,
  ToUnicode synthesis from cmap and glyph names, multiple bfchar/bfrange
  sections, TTC face selection, content-stream CID scanning with array-depth
  and budget bounds, CIDSet repair from subset results, CFF-in-SFNT conversion,
  and AcroForm `/DR` subset protection. The intermediate scanner was later
  replaced by the current normalizer; its empty-CMap and dangling-face issues
  are fixed at the tip (`jpdfium_font_shared.cpp` filters empty CMaps and
  `jpdfium_font_normalizer.cpp` guards the stream replacement).
- `jpdfium_advanced.cpp` — #261, #262, #267, #271, #275, #278, #282, #283,
  #285, #286, #287: CID `/W` parsing/synthesis with zero-advance preservation,
  CFF-in-SFNT conversion validation, TTC and shared-program usage counting,
  CharSet rebuild from retained glyphs, TrueType symbolic/encoding
  normalization with WinAnsi Differences preservation, HarfBuzz layout-table
  drops, UTF-16 ToUnicode CMaps, SFNT cmap canonicalization, and WriterLock
  level 9. A defect was found and fixed: `fontForbidsSubsetting` checked only
  OS/2 fsType bit 0x0100 and ignored 0x0002 Restricted License embedding, while
  the Java embedder already refuses 0x0002; subsetting an embedded restricted
  font is also a modification. The native check now rejects both bits. Microsoft
  OpenType OS/2 `fsType` defines 0x0002 as Restricted License (must not be
  modified, embedded, or exchanged) and 0x0100 as No subsetting.
  `jpdfium_advanced.cpp` has no unreviewed hunks remaining.
- `jpdfium_qpdf.cpp` — #243, #246, #248, #252, #253, #256, #257: font
  inventory for indirect, direct, appearance, and Type 3 resources with page
  ranges and JSON escaping; content-stream CID scanning with a bounded,
  fail-closed completeness flag; CIDToGIDMap remapping and GID-preserving
  subsets; ToUnicode parsing with surrogate-pair ranges and `UseCMap`
  rejection; shared descriptor/program counting; symbolic/non-symbolic
  encoding pinning; CIDSet synthesis; and EOL-before-`endstream` writer
  settings. The review noted issues that later refactors already fixed at the
  tip: per-`/Contents`-stream font selection state, the scanner array-depth
  bound, and relabeling a format-12 cmap as (3,1). No new defect found in this
  batch.
- `jpdfium_qpdf.cpp` — #261, #262, #263, #265, #267, #271, #285, #286,
  #287: stream-pattern step clamping with signed values; Microsoft Unicode cmap
  normalization; width synthesis; predefined CJK CMaps (RKSJ, KSC-EUC, GB-EUC,
  proportional and vertical variants) with code-versus-CID ToUnicode maps; font
  usage indexing; CIDSet from retained GIDs; signature stripping with field,
  annotation, `/Perms`, and `/DSS` cleanup; native PDF/A part detection; and
  writer lock level 9. No new actionable defect found. The review noted issues
  that later refactors already fixed at the tip: dictionary-only pattern
  clamping, per-`/Contents` font-selection state, scanner array-depth bound,
  format-12 cmap labeling, and CIDSet handling for non-Identity maps.
- `jpdfium_qpdf.cpp` — #275, #278: escaped content-name lookup with a leading
  slash, thread-local CMap range caches, Japan1/Korea1/GB1 ASCII fast paths,
  single-pass CID and simple-font usage indexes, form-inherited font state,
  typed stream-visit keys, optional code-set collection, and single-content-
  stream fast paths. The existing CJK embedding tests cover RKSJ, proportional
  RKSJ, vertical RKSJ, GB-EUC, KSC-EUC, and their vertical variants. A
  temporary probe compared the Japan1/Korea1/GB1 ASCII fast paths with a
  table-only binary search for all 65,536 codes and found zero mismatches. No
  new defect found.

All 1,205 production hunks from #243–#287 are now marked reviewed in
`docs/pr-stack-hunk-status.tsv`.
