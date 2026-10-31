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

- `Automatic-Module-Name` in the `jpdfium-spring`, `jpdfium-vips`, and
  `jpdfium-natives-*` jars, which were previously named after the artifact file.

### Fixed

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
  linux-arm64, and a build gate fails any Linux natives jar that needs a glibc
  newer than 2.39 or a libstdc++ newer than `GLIBCXX_3.4.33`.
- Merging and page extraction through qpdf kept only the pages and dropped the
  rest of the document: form fields, page labels, named destinations, viewer
  preferences, XMP metadata, the document language and the document
  information dictionary. The first input now keeps its document-level data,
  form fields from every input are carried over (a clashing field name is
  renamed), and page labels are rebuilt for the new page order. The structure
  tree, XFA form and usage-rights signatures are still dropped, because they
  describe the source document and are wrong once its pages are rearranged.
- `NOTICE.txt` in the natives jars now credits Skia, PCRE2 and its SLJIT JIT
  compiler, pugixml, libunibreak, Expat, Brotli, bzip2 and LLVM libc++, which
  were bundled without a notice, and the natives POMs name the bundled
  libraries' licenses alongside MIT.

### Changed

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

[Unreleased]: https://github.com/Stirling-Tools/JPDFium/compare/v1.2.0...HEAD
[1.2.0]: https://github.com/Stirling-Tools/JPDFium/releases/tag/v1.2.0
[1.1.5]: https://github.com/Stirling-Tools/JPDFium/releases/tag/v1.1.5
[1.1.4]: https://github.com/Stirling-Tools/JPDFium/releases/tag/v1.1.4
[1.1.3]: https://github.com/Stirling-Tools/JPDFium/releases/tag/v1.1.3
[1.1.2]: https://github.com/Stirling-Tools/JPDFium/releases/tag/v1.1.2
[1.1.1]: https://github.com/Stirling-Tools/JPDFium/releases/tag/v1.1.1
[1.1.0]: https://github.com/Stirling-Tools/JPDFium/releases/tag/v1.1.0
