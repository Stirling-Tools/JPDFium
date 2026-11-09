# Changelog

All notable changes to JPDFium are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

This project does not follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
Within the 1.x line a minor release may remove or change public API, and patch
releases keep the public API of the minor release they follow, so the removal
below ships in 1.3.0. Every incompatible change is listed here under "Removed"
or "Changed", and [compatibility.md](docs/compatibility.md) states the policy in
full.

This file covers library releases on Maven Central (the `v*.*.*` tags).
Infrastructure tags such as `pdfium-*` and `vips-*` are prebuild inputs for the
publishing workflows, not library releases, so they are not listed here.

## [Unreleased]

### Added

- `PdfXOptions.neutralsToBlack(boolean)`, on by default, maps neutral RGB
  vector colours to K only instead of four-colour black.

- `PdfXOptions.embedMissingFonts(boolean)`, on by default, and
  `fontDirectories(Path...)`: PDF/X conversion embeds fonts used without a
  program through `PdfFontEmbedder.embedMissing`.

- `PdfSplit.extractPageRangesToFiles` writes many page ranges of one file from a single qpdf
  parse instead of one parse per range. Ranges qpdf cannot write, and `MEMORY` storage, go
  through `extractPageRangeToFile` one at a time. 400 single-page parts of a 4.3 MB PDF take 0.12 s instead of
  7.3 s, and 400 parts of a 28.7 MB PDF 0.5 s instead of 45 s. Parts match
  `extractPageRangeToFile` page for page, form fields and page labels included.
- Native qpdf font-stream replacement. `PdfFontEmbedder.embed(...)` replaces
  `/FontFile2` or `/FontFile3` streams by exact `/BaseFont`, updating
  `/Length1`; Type 1 `/FontFile` streams are rejected. `PdfXOptions.fontData(...)`
  applies the same replacement before PDF/X validation.
- Font embedding. `PdfFontEmbedder.embed(...)` embeds TrueType, OpenType, CFF
  and Type 1 programs by exact `/BaseFont` in one qpdf rewrite, into fonts that
  have no program as well as ones that do, building a missing descriptor and
  widths from the program. `PdfFontEmbedder.embedMissing(...)` embeds every
  non-embedded font from the system (or given) font directories, with
  metric-compatible substitutes for the standard 14. `PdfXOptions.fontData(...)`
  applies `embed(...)` before PDF/X validation.
- Document-wide font inventory. `PdfFontAuditor.auditAll(doc)` and
  `PdfFontAuditor.inventory(doc)` report every font dictionary, including fonts
  that only exist in unused resource dictionaries or AcroForm `/DR`.
  `PdfFontEmbedder.embedMissing(...)` now embeds those resource-only fonts and
  resolves unresolved names from the same inventory, so a font that no page
  draws with can no longer fail a PDF/A font check.
- CID TrueType substitution subsets the replacement program to the glyphs used by
  the remapped CIDs, keeping glyph IDs stable so `/CIDToGIDMap` stays valid. The
  two-glyph Identity-H CJK fixture drops from 6.3 MiB to 3.2 KiB. CFF CID
  programs remain whole.
- Deterministic fallback fonts. The jpdfium jar now ships Liberation, Carlito,
  Caladea, DejaVu, Noto Sans Arabic and Noto Sans SC under `fonts/` and uses
  them before host fonts for metric-compatible substitution. Family/style
  aliases cover Calibri, Cambria, Verdana, Tahoma, traditional CJK names and
  legacy GBK byte spellings. `PdfFontEmbedder.diagnose(name)` reports the
  selected source and OS/2 `fsType`, and `Result` carries one `FontStatus` per
  requested font, including the reason a restricted or unmatched face was not
  embedded.
- PDF/X-1a, PDF/X-3, PDF/X-4, and PDF/X-6 metadata bootstrap. `PdfXConverter`
  now sets the standard base version, writes a missing `/TrimBox`, and
  optionally fails on non-embedded fonts. `PdfXOptions` gains
  `requireEmbeddedFonts(...)`; `checkMetadata(Path)` adds a file-backed
  metadata check.
- `PdfPageGeometry.cropAndRemoveContentStrict(...)`, which removes a word that
  crosses the crop edge instead of leaving its outside glyphs clipped and
  extractable.
- `StorageOptions.Builder.durable(false)` and `PdfSecurity.copyEncryption(..., SaveOptions)`
  skip the fsync for file outputs the caller reads back and deletes, such as
  split parts streamed into a response.
- `PdfPage.extractTextCharsPacked()` and the native `jpdfium_text_get_chars_packed`
  return a page's characters in a packed binary layout. `PdfTextExtractor` uses it
  when the native bundle exports it, instead of formatting and parsing JSON, which
  was most of the cost of text extraction.

### Changed

- `PdfFontAuditor.hasNonEmbeddedFonts` now includes fonts that only exist in unused
  resource dictionaries or AcroForm `/DR`, matching `auditAll`. Documents that
  previously passed the check can now fail it; the stricter result is the one a
  PDF/A font check needs.
- `PdfMerge.mergeFilesToFile` and `PdfSplit.extractPageRangeToFile` write their
  staging file without fsync and flush once when they publish the output, which
  now gets the destination's permissions as other saves do. They previously
  flushed the staging file and then renamed it without flushing the rename.
- `PdfSplit.extractPageRange` and `PdfSplit.extractPages` honour
  `StorageOptions.reuseSourceFile`: an untouched file-backed document is read
  from its file instead of being saved through PDFium before every extract.
  `SplitCounters.extractSerializations` counts the snapshots they still take.
- `PdfRedactor.redact` with fewer than eight literal words no longer reads every
  page's text in Java before the native redaction reads it again; the native
  redaction already skips pages without a match.
- qpdf merge, page extract and encryption copy (`PdfMerge.mergeFilesToFile`,
  `PdfSplit.extractPageRangeToFile`, the qpdf byte-array merge and extract, and
  `PdfSecurity.copyEncryption`) keep every stream's existing encoding instead of
  decoding and deflating it. Image-heavy inputs with raw image data merge several
  times faster; such outputs are as large as their inputs rather than compressed.
  The PDFium fallback, used when qpdf is unavailable or rejects an input, still
  compresses unfiltered streams, and `PdfOptimizer` still compresses on request.

- Word and regex redaction, and committing redaction marks, apply a page's
  boxes to PDFium in groups of 16 instead of one call per box, so the page is
  rewritten once per group rather than once per match. A 200-page document with
  99,000 matches redacts in about 4 s instead of 16 s; output text is unchanged.

### Fixed

- PDF/X-1a and PDF/X-3 no longer accept images with soft masks, which the page
  transparency probe missed, and drop transparency groups from pages that have
  no transparent content.

- PDF/X no longer leaves soft-masked or lossless RGB images, Indexed RGB
  lookups or RGB Separation alternates unconverted.

- PDF/X-4 and PDF/X-6 XMP now carries `xmp:CreateDate`, `xmp:MetadataDate`,
  `xmpMM:DocumentID`, `xmpMM:InstanceID`, `xmpMM:VersionID` and
  `xmpMM:RenditionClass`; an existing document ID is kept.

- PDF/X output removes JavaScript, flattens printable annotations, drops an
  `/ArtBox` that sits beside a `/TrimBox`, replaces PostScript XObjects, and is
  never encrypted.

- PDF/X conversion fails instead of writing a document with fewer pages when
  the source page tree cannot be rewritten, and refuses signed documents
  instead of silently invalidating the signature.

- Word and regex redaction finds words PDFium reports hyphenated across a line
  break (`Trace-` / `Monkey`), and removes the hyphen with them; it previously
  missed them, and with eight or more words the keyword prefilter skipped such
  pages too. Redacting the word after a line-end hyphen no longer fails as
  incomplete when that hyphen stops reading as a word break.
- Word and pattern redaction now removes text drawn with glyphs the embedded
  font has no outline for, such as text using a truncated font subset. These
  pages previously stopped with `RedactIncompleteException`.
- `commitRedactions` now verifies that marked text was removed, as the other
  redaction paths do, and raises `RedactIncompleteException` otherwise.
- A page whose redaction did not finish stays unsavable until a later commit
  removes the text that redaction left, not merely until any commit on the page
  succeeds.
- Redacting a word on rotated, mirrored or skewed text no longer removes
  glyphs of the neighbouring words or paints over them: the redaction marks
  and covers the word's own quad instead of the box around it.
- `PdfVersionConverter` now writes a PDF 2.0 header for `PdfVersion.V2_0`.
  PDFium's `FPDF_SaveWithVersion` rejects version codes above 17 (PDF 1.7) and
  falls back to keeping the source document's version rather than writing a
  PDF 2.0 header.
- Refuse incremental saves after `PdfPage.redactInRect` modifies content, failing
  closed with `RedactedSaveException` to protect redacted pages.

## [1.3.0] - 2026-11-05

### Added

- In-process qpdf parity for the operations that previously required the
  `qpdf` executable: QDF repair (`PdfOptimizer.repair`), content normalization,
  unreferenced page-resource cleanup, full compression-level control, and
  qpdf's image optimization pass with explicit JPEG quality. The low-level
  bridge adds `jpdfium_qpdf_optimize_ex` and `jpdfium_qpdf_optimize_ex_file`;
  the existing optimize entry points remain source- and binary-compatible.
  Explicit compression levels are now honoured and coordinated across
  concurrent writers instead of being ignored.
- Structural inspection (`PdfStructuralInspector`, qpdf-backed JSON reports for
  page/object counts, linearization validity, encryption, attachments, forms,
  signatures, and warnings), lossless `/Info` editing (`PdfMetadataEditor`), and
  `PdfRepair.repairQdf` for QDF repair with PDFium/PDFio/Rust fallbacks. The
  bridge adds `jpdfium_qpdf_inspect`, `jpdfium_qpdf_inspect_file`,
  `jpdfium_qpdf_set_docinfo`, and `jpdfium_qpdf_set_docinfo_file`.
- File-backed qpdf inspection and metadata editing now parse and write straight
  from/to disk, avoiding document-sized Java/native buffers. Measured on a
  14.5 MB fixture: path inspection is 4.65x faster (1.743 to 0.375 ms/op) and
  drops Java allocation from 15.2 MB/op to 1.0 KB/op; file metadata editing is
  15.6% faster (19.388 to 16.359 ms/op).
- `Automatic-Module-Name` in the `jpdfium-spring`, `jpdfium-vips`, and
  `jpdfium-natives-*` jars, which were previously named after the artifact file.
- `PdfPageGeometry.AUTO_CONTENT_MARGIN` and the `cropAndRemoveContent`
  overloads that accept a content-removal margin.

### Fixed

- The UTF-8 pattern engine and UTF-32 redaction engine used different C++ structs
  with the same name, violating the one-definition rule in optimized builds.
  Their native pattern types now have distinct names.
- Applying annotation redactions produced by Adobe Acrobat (`commitRedactions`,
  `applyRedaction`, and `applyRedactions`) unexpectedly drew a duplicate black
  box near the bottom-left corner of the page: Acrobat's pre-baked `/RO`
  (Redact Overlay) Form XObject uses an offset matrix translation that the
  native overlay flattening ignored. Existing `/RO` streams are now stripped
  before applying redactions, preventing misaligned overlays from being
  flattened into the page. This also drops any overlay text or custom colour
  baked into Acrobat's `/RO` stream.
- Whole-word redaction skipped pages where words are separated by control
  characters (some fonts map the space glyph to U+0002): the keyword prefilter
  reads text without those characters and saw the words joined. The prefilter
  now screens pages by substring and leaves the whole-word test to the native
  matcher.
- Region and text redaction left an image nested in a Form XObject intact under
  the painted cover when the redaction covered only part of it. The covered
  pixels are now erased and the image is moved to the page, as crop already did.
- Redacting or cropping part of an image that is drawn more than once on a page
  also erased the same pixels in the other placements, which nothing covered.
  Such a request now fails with `RedactIncompleteException` before the page
  changes, and the document can still be saved.
- Redacting part of a JPEG XL, HEIF or AVIF image (encodings PDFium cannot
  decode) dropped the whole picture. With an image codec registered (the
  `jpdfium-vips` module) the image is now decoded, only the covered pixels are
  erased, and the rest is kept. Without one it still fails closed. The bridge
  adds `jpdfium_set_image_decoder`, which `JpdfiumLib` registers at startup.
- A partly redacted image grew on save: the erased image was always stored as
  8-bit RGB with a soft mask, even when it was opaque. It now gets a soft mask
  only when it has transparency. Gray images are rewritten as `/DeviceGray`
  by a qpdf pass at save, and 1-bit scans (JBIG2, CCITT) stay 1-bit. On an A4
  300 dpi scan the redacted page drops from about 256 KB to 96 KB. If that pass
  cannot run, the image keeps PDFium's 8-bit `/Indexed` gray form, which is
  still fully redacted. The pass is file-to-file, so a large save does not hold
  the whole document in memory.
- Content redaction crashed on Windows inside HarfBuzz: the shaping cache handed
  it font bytes it had marked read-only from a buffer that died with the function,
  so shaping read freed memory. HarfBuzz now owns a copy of the font data.
- Native file saves on Windows wrote every LF as CRLF because the staging file
  was opened without `_O_BINARY`. The shifted offsets made PDFium's own reader
  mis-resolve objects and reload page content as empty; every file-based save
  overload was affected.
- The linux-x64 natives required glibc 2.43 because the build ran on an
  unpinned `ubuntu-latest` runner, so they failed to load on Ubuntu 24.04 and
  any other distribution with an older glibc, with
  "version `GLIBC_2.43' not found". linux-x64 now builds on Ubuntu 24.04 like
  linux-arm64. PDFium and vips prebuild producers use the same baseline,
  and publication gates reject shared libraries and final bundles requiring
  glibc newer than 2.39, `GLIBCXX_3.4.33` or `CXXABI_1.3.15`.
  Snapshot CI runs the staged Linux natives on Ubuntu 24.04 and 26.04,
  including merge/extract preservation and vips codec tests, and rejects
  skipped compatibility tests.
- Merging and page extraction through qpdf kept only the pages and dropped the
  rest of the document: form fields, page labels, named destinations, viewer
  preferences, XMP metadata, the document language and the document
  information dictionary. The first input now keeps its document-level data,
  form fields from every input are carried over (a clashing field name is
  renamed), and page labels are rebuilt for the new page order. Bookmarks, the
  structure tree, XFA form and usage-rights signatures are still dropped,
  because they describe the source document and are wrong once its pages are
  rearranged, and the output is still written unencrypted.
- `NOTICE.txt` in the natives jars now credits Skia, PCRE2 and its SLJIT JIT
  compiler, pugixml, libunibreak, Expat, Brotli, bzip2 and LLVM libc++, which
  were bundled without a notice, and the natives POMs name the bundled
  libraries' licenses alongside MIT.

### Changed

- Hard crop retains the outside glyphs of words touching the crop edge. The
  page boxes clip those glyphs. A content-removal margin also retains content
  within the margin outside the crop, clipped by the page boxes.
- The published `jpdfium` jar no longer carries the GraalVM smoke-test PDFs.
  Those fixtures stay on the native-image build classpath, so the smoke test
  still reads them.
- Build and CI: POM names and descriptions that describe the project rather than
  its build, a checksum-pinned Gradle distribution, a committed Rust
  `Cargo.lock` built with `--locked`, Javadoc validation with
  `-Xdoclint:all,-missing` and warnings as errors, regression tests for the
  natives jar checker and the version bump script, and a version bump that
  reconciles every documented version reference on every run, never moves the
  development version backwards, and updates its own branch in place instead of
  deleting it.
- The `jpdfium` artifact no longer depends on TwelveMonkeys `imageio-tiff`.
  Baseline TIFF, including multi-page output, uses the JDK ImageIO implementation;
  `jpdfium-vips` handles advanced TIFF variants and multi-page TIFF through
  libvips. Add TwelveMonkeys explicitly if you need BigTIFF, CMYK, or compression
  variants without libvips.
- The `jpdfium` artifact no longer publishes a transitive
  `jpdfium-natives-linux-x64` dependency. Add the native module for your target
  platform explicitly; this keeps unrelated platform binaries out of the
  dependency graph and image.

### Removed

- 10/12-bit AVIF **encoding**. The source-built AOM is encoder-only and
  high-bit-depth support is disabled for payload size; 8-bit AVIF remains, and
  dav1d still decodes high-bit-depth AVIF.
- `stirling.software.jpdfium.text.edit` and its `TextEditor` placeholder, which
  had no members and could not be instantiated, and which nothing in the API, the
  tests, or the documentation referred to. The package and the class were public
  in 1.2.0, so this is a deliberate source and binary break: code that imports the
  package or refers to `TextEditor.class` no longer compiles or links. The break
  is recorded in `.github/workflows/compatibility.yml`, which excludes the class
  from the japicmp gate by name, and no other public type or member of 1.2.0 was
  removed.

- `stirling.software.jpdfium.panama.NativeGuard`, a deprecated facade whose
  documented contract could no longer hold because it could not admit the
  execution domain it advertised. Nothing in the repository called it; code that
  used it compiles against `PdfiumRuntime` instead.
- `stirling.software.jpdfium.doc.PdfAConverter`, a Ghostscript-backed helper that
  was removed together with the Ghostscript dependency. PDF/A conformance handling
  now runs through the in-process compression pipeline (`PdfCompressor`).

Both were public in 1.2.0, so they are deliberate source and binary breaks like
the one above, and `.github/workflows/compatibility.yml` excludes each class by
name from the japicmp gate.

- Four `MethodHandle` constants in the otherwise surviving Panama binding
  holders, because the new PDFium pin no longer exports the native symbols they
  mirrored: `EmbedPdfAnnotationBindings.EPDFAnnot_ApplyRedaction` (the fork
  replaced it with `EPDFPage_ApplyRedactions`),
  `EmbedPdfDocumentBindings.EPDF_GetPageRotationByIndex` (renamed to
  `EPDF_GetPageRotateByIndex`, which reports rotation through an out parameter),
  and the dead `EmbedPdfAnnotationBindings.EPDFAnnot_SetRectangleDifferences`
  and `EmbedPdfDocumentBindings.EPDF_GetPageSizeByIndexNormalized` bindings,
  which nothing called. Retargeting those fields at the surviving symbols would
  only ship handles that fail at invoke time, so dropping them is the deliberate
  break; each is excluded individually in
  `.github/workflows/compatibility.yml`, and every other member of the two
  binding classes keeps its signature.

## [1.2.0] - 2026-10-11

A large architectural release. Parts of the calling, lifetime, and save paths
were reworked, so some instability is expected; pin 1.1.5 if you need the
previous behavior while you validate 1.2.0.

### Added

- `PdfRenderer`, a PDFBox-style renderer (`renderImageWithDPI`, batch rendering,
  combined and stitched output, multi-page TIFF).
- `PdfImageIO`, an ImageIO-style facade over the active codec, with the
  `ColorType`, `RenderFlags`, and `RenderQuality` types.
- `ImageCodec` SPI and an `ImageCodecs` facade that prefers libvips when
  `jpdfium-vips` is present and falls back to ImageIO otherwise.
- `PdfProcessPool`, a bounded pool of warm worker JVMs for using more than one
  core while a single process stays serialized.
- `StorageOptions` to keep document bytes on the heap or on disk; merge, split,
  and pipeline reuse file-backed snapshots, and several saves can be staged to
  disk through a shared `FileWriteSink`.
- Hard page cropping that removes content outside the crop area through object
  fission.
- `PdfColorAdjuster`, and `SvgConverter` for in-process SVG rasterization through
  the Rust `resvg` rasterizer.
- Opt-in runtime download of a missing platform native
  (`jpdfium.native.download`) through a checksum-verified, repository-bound cache.
- Rust acceleration bindings and native handle-safety tests.

### Changed

- Every PDFium call now enters one process-wide execution domain
  (`PdfiumRuntime`) with an explicit lifecycle
  (`RUNNING -> QUIESCING -> DESTROYING -> STOPPED`) and a single reentrant
  serializer. Document, save, font, repair, and metadata bindings moved onto it,
  replacing the scattered per-call guards.
- Saves publish transactionally through `OutputTransaction`, staging beside the
  destination and committing with an atomic rename so a failed save leaves the
  destination untouched.
- Documents track a source and a structural epoch; pages opened before a
  structural operation fail fast with `IllegalStateException` instead of using
  stale native state.
- QPDF structural operations run through a per-call confined FFM context
  (`QpdfCall`), so merge, extract, optimize, encrypt, decrypt, and sanitize can
  run concurrently with each other.
- Compression is rebuilt around `PreservationMode`, with monotonic results
  (never larger than the input), in-process image downsampling via
  `PdfImageOptimizer`, and single-pass marker location. In-memory edits now
  survive compression.
- The C++ redaction bridge is split into fission, pattern, text, and audit units.
- Documentation overhaul: new installation, native-loading, deployment,
  configuration, rendering, images, editing, concurrency, compatibility, and
  troubleshooting guides, plus a rewritten README.
- Build and CI: libvips prebuild and drift workflows, reworked release and
  snapshot workflows, and a hardened musl detection job.
- PDFium pin bumped to `pdfium-20260921-f5ca813`.

## [1.1.5] - 2026-09-25

### Added

- `jpdfium.native.cacheDir`, `jpdfium.native.cache`, `jpdfium.native.verify`,
  and `jpdfium.native.sweep` to control native cache location, fallback,
  verification, and cleanup.

### Changed

- Native libraries are extracted once per content revision into a per-user cache
  and reused by every JVM, which removes the `jpdfium-*` temp directories that
  accumulated on Windows and avoids extracting the bundle on each start.
- Cache entries are content-addressed and verified against a `native-libs.sha256`
  manifest; extraction is atomic and concurrent JVMs coordinate with file locks.
  A cache path that is not private to the user falls back to a temp extraction.
- `jpdfium.maxRenderPixels` is read per render, so the bound can be changed at
  runtime.

## [1.1.4] - 2026-09-11

### Added

- `getPageBoxes`, `getPageRotation`, `getPageUserUnit`, and `cropPage`, which work
  from a page index without loading the page.
- `PdfPage.redactInRect` with stream regeneration, plus `applyRedaction` and
  `applyRedactions` for redact annotations.
- `mergeDocuments` and `merge`, which try QPDF first and fall back to PDFium
  import, keeping bookmarks.

### Fixed

- Bound native strings and fixed critical leaf downcalls and rect layout.
- macOS bundle: dylib dedupe, kept icudata shadow, and fixed Homebrew permissions
  for darwin-x64 builds.

### Changed

- PDFium pin bumped to `pdfium-20260909-dde15ab`.

## [1.1.3] - 2026-08-22

### Added

- Automatic generation and insertion of solid redaction cover rectangles
  (`FPDFPageObj_CreateNewRect`) when `EPDFPage_ApplyRedactions` succeeds, so the
  black boxes render and flatten directly into the PDF.
- ICU initialization and system font fallback for CJK and multilingual
  rendering.

## [1.1.2] - 2026-08-21

### Added

- Regression suites for PDFBox Standard 14 and Type 0 font redactions.
- `jpdfium.*` system properties are now forwarded to test workers.

### Fixed

- Text normalization fallback in `buildNormalizedText` when the ICU NFKC
  normalizer data is uninitialized or unavailable in embedded container
  environments.

### Changed

- `jpdfium.maxRenderPixels` is evaluated at render time and accepts `0` to
  disable the maximum pixel bound.

## [1.1.1] - 2026-08-21

### Added

- Preloading support for bundled native dependencies across supported platforms,
  with HarfBuzz linked statically.

### Fixed

- Restricted font program subsetting during post-sanitization to `/Type0`
  composite fonts, preventing character code corruption in embedded 1-byte
  TrueType subset fonts.
- Improved text object fission bounds validation for direct Unicode text objects.

### Changed

- Post-redaction sanitization and page flattening are now opt-in in
  `RedactOptions` (default `false`).

## [1.1.0] - 2026-08-20

### Added

- `PdfDocument.open` with password support, and native QPDF for structural
  manipulation, linearizing, and repair.
- `jpdfium-vips` for image conversion, encoding, decoding, and PDF generation.
- Hard cropping that permanently removes content and paths outside the crop
  boundaries.
- Alpine Linux and musl libc (`linux-musl-x64`, `linux-musl-arm64`) and Windows
  ARM64 runtimes.
- GraalVM reachability metadata for ahead-of-time (AOT) Native Image
  compilation.

### Changed

- Redaction hardening: mandatory content removal, automated sanitization on save,
  Unicode NFKC/UCP matching, nested Form XObject support, and ligature glyph
  safety.
- PDFium calls are serialized for global thread safety, the C++ bridge is
  hardened against invalid input, and native LibFuzzer boundary testing was added.
- Gradle wrapper upgraded to 9.7.0; PDFium base pin bumped to
  `pdfium-20260803-cb29e78`.

## Earlier releases

Versions 1.0.0 through 1.0.4 (May to July 2026) established the initial
PDFium-backed document, page, text, image, and redaction APIs. Per-version notes
were not maintained; see the [Releases](https://github.com/Stirling-Tools/JPDFium/releases)
page and the `v1.0.x` tags.

[Unreleased]: https://github.com/Stirling-Tools/JPDFium/compare/v1.3.0...HEAD
[1.3.0]: https://github.com/Stirling-Tools/JPDFium/releases/tag/v1.3.0
[1.2.0]: https://github.com/Stirling-Tools/JPDFium/releases/tag/v1.2.0
[1.1.5]: https://github.com/Stirling-Tools/JPDFium/releases/tag/v1.1.5
[1.1.4]: https://github.com/Stirling-Tools/JPDFium/releases/tag/v1.1.4
[1.1.3]: https://github.com/Stirling-Tools/JPDFium/releases/tag/v1.1.3
[1.1.2]: https://github.com/Stirling-Tools/JPDFium/releases/tag/v1.1.2
[1.1.1]: https://github.com/Stirling-Tools/JPDFium/releases/tag/v1.1.1
[1.1.0]: https://github.com/Stirling-Tools/JPDFium/releases/tag/v1.1.0
