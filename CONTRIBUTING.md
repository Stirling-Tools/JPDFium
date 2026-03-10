# Contributing to JPDFium

## Development Setup

1. **Clone**
   ```bash
   git clone https://github.com/Stirling-Tools/JPDFium.git
   cd JPDFium
   ```

2. **Install native dependencies** (for full feature set)

   **Fedora / RHEL:**
   ```bash
   sudo dnf install -y gcc-c++ cmake pcre2-devel freetype-devel harfbuzz-devel \
       libicu-devel qpdf-devel pugixml-devel libunibreak-devel
   ```

   **Ubuntu / Debian:**
   ```bash
   sudo apt install -y g++ cmake libpcre2-dev libfreetype-dev libharfbuzz-dev \
       libicu-dev libqpdf-dev libpugixml-dev libunibreak-dev
   ```

3. **Quick Try-Out** (no PDFium or native dependencies needed)
   ```bash
   ./gradlew quickTry
   ```
   This builds the stub bridge and runs all 59 samples in stub mode. Perfect for testing Java-layer changes.

4. **Full Build with Real PDFium** (recommended for production testing)
   ```bash
   # One command: build PDFium from source, build real bridge, run all tests and samples
   ./gradlew fullBuildAndTest
   ```
   
   Or step by step:
   ```bash
   ./gradlew buildPdfium         # Build PDFium from EmbedPDF fork (~15 GB, first time takes 15-60 min)
   ./gradlew buildRealBridge     # Build native bridge against real PDFium
   ./gradlew test                # Unit tests (stub mode)
   ./gradlew :jpdfium:integrationTest  # Integration tests (real PDFium)
   ./gradlew runAllSamples       # Run all 59 samples
   ```

   > **Note:** The PDFium build requires `git`, `python3`, and ~15 GB disk space.
   > On Fedora, also install: `sudo dnf install clang lld pkg-config ninja-build`.
   > The build script installs `depot_tools` (gclient/gn/ninja) automatically.
   > Subsequent builds with `--rebuild` are much faster (incremental).

### PDFium Build Details

JPDFium uses the [EmbedPDF fork](https://github.com/embedpdf/pdfium) (branch `embedpdf/main`) which adds
`EPDF_*` APIs for native encryption, annotation control, and redaction.

The build uses a **component build** (`is_component_build=true`) with `use_allocator_shim=false`:
- Component build produces `libpdfium.so` plus dependency `.so` files (PartitionAlloc, ICU, zlib, abseil)
- `use_allocator_shim=false` is **required** — without it, PartitionAlloc replaces the system allocator
  (malloc/free), causing crashes when loaded into a JVM that manages its own heap
- `COMPONENT_BUILD` + `FPDF_IMPLEMENTATION` defines are set automatically, giving FPDF_EXPORT symbols
  `__attribute__((visibility("default")))` — no manual header patching needed

The build script applies these source patches:
- **`base/BUILD.gn`**: Stub file (standalone PDFium lacks full `//base`)
- **`third_party/libpng/visibility.gni`**: Adds fpdfsdk to libpng visibility (EmbedPDF adds PNG export)
- **`cpdf_pagecontentgenerator.cpp`**: Fixes two null pointer crashes in PDFium's resource dict handling

The `NativeLoader` reads a `native-libs.txt` manifest to extract all component `.so` files to a temp
directory before loading. The dynamic linker resolves dependencies via `RUNPATH=$ORIGIN`.

5. **Open in IntelliJ IDEA** - import as a Gradle project. Add
   `--enable-native-access=ALL-UNNAMED` to Run Configurations -> Templates -> Application -> VM Options.

### Gradle Tasks Overview

| Task | Description |
|------|-------------|
| `./gradlew quickTry` | Quick try-out: stub bridge + unit tests + all samples (no PDFium) |
| `./gradlew buildPdfium` | Build PDFium from EmbedPDF fork source (~15 GB, first build 15-60 min) |
| `./gradlew buildRealBridge` | Build real native bridge with PDFium |
| `./gradlew buildStubBridge` | Build stub native bridge (no PDFium) |
| `./gradlew fullBuildAndTest` | Full end-to-end: PDFium + real bridge + all tests + samples |
| `./gradlew runAllSamples` | Run all 59 samples (requires real bridge for full features) |
| `./gradlew runSample -Psample=01` | Run a specific sample (01-59) |
| `./gradlew test` | Run unit tests (stub mode) |
| `./gradlew :jpdfium:integrationTest` | Run integration tests (real PDFium required) |

### Sample Numbers

| Sample | Feature | Sample | Feature |
|--------|---------|--------|---------|
| 01 | Render | 31 | Image Extract |
| 02 | Text Extract | 32 | Page Objects |
| 03 | Text Search | 33 | Encryption |
| 04 | Metadata | 34 | Linearizer |
| 05 | Bookmarks | 35 | Overlay |
| 06 | Redact Words | 36 | Annotation Builder |
| 07 | Annotations | 37 | Path Drawer |
| 08 | Full Pipeline | 38 | JavaScript Inspector |
| 09 | Flatten | 39 | Web Links |
| 10 | Signatures | 40 | Page Boxes |
| 11 | Attachments | 41 | Version Converter |
| 12 | Links | 42 | Bounded Text |
| 13 | Page Import | 43 | Stream Optimizer |
| 14 | Structure Tree | 44 | Flatten Rotation |
| 15 | Thumbnails (embedded) | 45 | Page Interleaver |
| 16 | Page Editing | 46 | Named Destinations |
| 17 | N-Up Layout | 47 | Blank Page Detector |
| 18 | Repair | 48 | EmbedPDF Annotations |
| 19 | PDF to Images | 49 | Native Encryption |
| 20 | Images to PDF | 50 | Native Redaction |
| 21 | Thumbnails (generated) | 51 | Compress |
| 22 | Merge/Split | 52 | Bookmark Editor |
| 23 | Watermark | 53 | Barcode Generate |
| 24 | Table Extract | 54 | Page Reorder |
| 25 | Page Geometry | 55 | Color Convert |
| 26 | Header/Footer | 56 | Booklet |
| 27 | Security | 58 | Analytics |
| 28 | Doc Info | 59 | Form Fill |
| 29 | Render Options | | |
| 30 | Form Reader | | |

**Stub-only development** (no PDFium or native libraries needed):
```bash
bash native/build-stub.sh
./gradlew test
```
The stub provides pass-through behavior for Java-layer testing only.

## Project Structure

```
JPDFium/
  native/
    bridge/
      include/
        - jpdfium.h              # Public C API (consumed by jextract)
        - jpdfium_internal.h     # DocWrapper, PageWrapper, helpers
      src/
        - jpdfium_document.cpp   # Core document operations
        - jpdfium_render.cpp     # Page rendering
        - jpdfium_text.cpp       # Text extraction and search
        - jpdfium_redact.cpp     # Redaction (Object Fission)
        - jpdfium_advanced.cpp   # PII pipeline: PCRE2, FreeType, HarfBuzz, ICU, qpdf, pugixml
        - jpdfium_repair.cpp     # PDF repair cascade
        - jpdfium_image.cpp      # PDF to image conversion
        - jpdfium_brotli.cpp     # Brotli transcoding
        - jpdfium_lcms.cpp       # ICC color profile validation
        - jpdfium_openjpeg.cpp   # JPEG2000 validation
        - jpdfium_pdfio.cpp      # PDFio fallback repair
        - jpdfium_unicode.cpp    # Unicode text processing
        - jpdfium_stub.cpp       # Stub for testing without PDFium
    - setup-pdfium.sh            # Build PDFium from EmbedPDF fork source
    - build-real.sh              # Build bridge against real PDFium
    - build-stub.sh              # Build stub only

  jpdfium/                       # All Java source (main module)
    src/
      main/java/stirling/software/jpdfium/
        - PdfDocument.java       # Main document API
        - PdfPage.java           # Page API
        - PdfImageConverter.java # PDF <-> Image conversion
        - PdfMerge.java          # Merge multiple PDFs
        - PdfSplit.java          # Split PDFs by strategy
        - Watermark.java         # Watermark builder
        - WatermarkApplier.java  # Apply watermarks
        - HeaderFooter.java      # Header/footer builder
        - HeaderFooterApplier.java # Apply headers/footers
        - doc/                   # Document inspection APIs
          - PdfMetadata.java
          - PdfBookmarks.java
          - PdfAnnotations.java
          - PdfLinks.java
          - PdfSignatures.java
          - PdfAttachments.java
          - PdfThumbnails.java
          - PdfStructureTree.java
          - PdfPageImporter.java
          - PdfPageEditor.java
          - PdfRepair.java
          - PdfSecurity.java     # Security hardening
          - PdfTableExtractor.java
          - PdfOverlay.java      # Page overlay/stamp
          - PdfLinearizer.java   # Fast web view
          - PdfStreamOptimizer.java # File size reduction
          - PdfVersionConverter.java # PDF version
          - PdfEncryption.java   # AES-256 encrypt/decrypt (native + qpdf file-to-file)
          - EmbedPdfAnnotations.java # Extended annotation operations (opacity, rotation, redaction, etc.)
          - PdfPageInterleaver.java # Duplex scan merge
          - PdfNamedDestinations.java # Named destination lookup
          - PdfWebLinks.java     # Web link enumeration
          - PdfPageBoxes.java    # All five page boxes
          - PdfAnnotationBuilder.java # Create annotations
          - PdfPathDrawer.java   # Vector path drawing
          - PdfFlattenRotation.java # Apply rotation transform
          - PdfFormReader.java   # Form field extraction
          - PdfImageExtractor.java # Embedded image extraction
          - PdfJavaScriptInspector.java # JS audit
          - PdfBoundedText.java  # Bounded text blocks
          - BlankPageDetector.java # Blank page detection
          - DocInfo.java         # Comprehensive PDF audit
          - RenderOptions.java   # Advanced render options
          - NUpLayout.java
          - QpdfHelper.java
          - PdfCompressor.java   # PDF compression
          - PdfBarcode.java      # QR code generation
          - PdfBookmarkEditor.java # Bookmark editing
          - PdfPageReorder.java  # Page reordering
          - PdfColorConverter.java # Color space conversion
          - PdfPrint.java        # Booklet imposition
          - PdfAnalytics.java    # Document analytics
          - PdfFormFiller.java   # Form field filling
          - ...
        - exception/             # JPDFiumException hierarchy
        - fonts/                 # FontInfo, FontNormalizer
        - model/                 # Rect, PageSize, RenderResult, ImageFormat, PdfVersion, ColorScheme, FlattenMode, Position, ImageToPdfOptions, PdfToImageOptions
        - panama/                # NativeLoader, JpdfiumLib, JpdfiumH (generated), DocBindings, PageEditBindings, AnnotationBindings, EmbedPdfAnnotationBindings, EmbedPdfDocumentBindings, TextPageBindings, RenderBindings, JavaScriptBindings, ActionBindings, FontLib, RepairLib, FfmHelper
        - redact/                # PdfRedactor, RedactOptions, RedactResult, RedactionSession
          - pii/                 # PatternEngine, PiiCategory, EntityRedactor, GlyphRedactor, XmpRedactor
        - text/                  # PdfTextExtractor, PdfTextSearcher, PageText, TextLine, TextWord, TextChar, Table
          - edit/                # TextEditor
        - transform/             # PageOps, PdfPageGeometry, PdfPageBoxes
        - convert/               # (PDF <-> Image conversion utilities)
        - util/                  # NativeJsonParser
      test/java/stirling/software/jpdfium/
        - PdfDocumentTest.java   # Unit tests (stub native)
        - RealPdfIntegrationTest.java  # Integration tests (real PDFium)
        - ManualTest.java        # Quick smoke-test (right-click -> Run)
        - samples/               # Numbered manual-test classes (S01-S59)
          - RunAllSamples.java   # Execute all 59 samples
          - SampleBase.java      # Base class for samples
          - S01_Render.java ... S59_FormFill.java
        - ...

  jpdfium-natives/               # Platform-specific native JARs
    - jpdfium-natives-linux-x64/
    - ...
  jpdfium-spring/                # Spring Boot auto-configuration
  jpdfium-bom/                   # Maven BOM
  buildSrc/                      # Gradle convention plugins
```

## Adding a New Native Function

Adding a feature follows a consistent pattern across all layers:

**Step 1 - C header** (`native/bridge/include/jpdfium.h`):
```c
JPDFIUM_EXPORT int32_t jpdfium_page_rotate(int64_t page, int32_t rotation);
```
Rules:
- Return `int32_t` error codes (`0` = OK, negative = error)
- Opaque handles use `int64_t`
- Output params via pointer (`float* width`)
- Caller-freed buffers via double pointer + length (`uint8_t** data, int64_t* len`)
- Strings are UTF-8 `const char*` - convert to UTF-16LE internally for PDFium

**Step 2 - Real implementation** (`native/bridge/src/jpdfium_document.cpp`):
```cpp
int32_t jpdfium_page_rotate(int64_t page, int32_t rotation) {
    PageWrapper* pw = decodePage(page);
    if (!pw || !pw->page) return JPDFIUM_ERR_INVALID;
    FPDFPage_SetRotation(pw->page, rotation);
    return JPDFIUM_OK;
}
```

**Step 3 - Stub** (`native/bridge/src/jpdfium_stub.cpp`):
```cpp
int32_t jpdfium_page_rotate(int64_t, int32_t) { return JPDFIUM_OK; }
```

**Step 4 - jextract function list** (`jpdfium/build.gradle.kts`):
```kotlin
val jpdfiumFunctions = listOf(
    ...,
    "jpdfium_page_rotate"   // add here
)
```
Regenerate: `./gradlew :jpdfium:generateBindings`

> **Note on redaction functions:** `jpdfium_redact_words` delegates to `jpdfium_redact_words_ex`
> internally. New redaction features should be added to `jpdfium_redact_words_ex` (and its stub)
> rather than creating a third variant.

**Step 5 - Java wrapper** (`panama/JpdfiumLib.java`):
```java
public static void pageRotate(long page, int rotation) {
    check(JpdfiumH.jpdfium_page_rotate(page, rotation), "pageRotate");
}
```

**Step 6 - High-level API** (`PdfPage.java`):
```java
public void rotate(int rotation) {
    ensureOpen();
    JpdfiumLib.pageRotate(handle, rotation);
}
```

**Step 7 - Tests** - a unit test (stub, always runs in `./gradlew test`) and an
integration test tagged with `@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")`.

**Step 8 - Rebuild and verify:**
```bash
# Rebuild stub (for unit tests)
bash native/build-stub.sh
./gradlew test                       # unit tests (stub)

# Rebuild real (for integration tests)
bash native/build-real.sh
./gradlew :jpdfium:integrationTest   # integration tests (real PDFium)
```

## Adding Native Library Features

Features using PCRE2, FreeType, HarfBuzz, ICU, qpdf, pugixml live in `jpdfium_advanced.cpp`
and are conditionally compiled with `#ifdef JPDFIUM_HAS_<LIB>` preprocessor guards.

**Architecture:**
```
jpdfium.h (API) -> jpdfium_stub.cpp (stub) + jpdfium_advanced.cpp (real impl)
                                              |
                                     #ifdef JPDFIUM_HAS_PCRE2  -> pcre2.h
                                     #ifdef JPDFIUM_HAS_FREETYPE -> ft2build.h
                                     #ifdef JPDFIUM_HAS_HARFBUZZ -> hb/hb.h, hb-subset.h
                                     #ifdef JPDFIUM_HAS_ICU -> unicode/unorm2.h, ubrk.h, ubidi.h
                                     #ifdef JPDFIUM_HAS_QPDF -> qpdf/QPDF.hh
                                     #ifdef JPDFIUM_HAS_PUGIXML -> pugixml.hpp
```

**Steps to add a new native feature:**

1. Declare function in `jpdfium.h`
2. Add stub in `jpdfium_stub.cpp` (must compile without any external libraries)
3. Add real implementation in `jpdfium_advanced.cpp` guarded by `#ifdef JPDFIUM_HAS_<LIB>`
4. Add pkg-config detection in `CMakeLists.txt`
5. Add Java FFM wrapper in `JpdfiumLib.java`
6. Add function name to jextract list in `jpdfium/build.gradle.kts`
7. Create high-level Java API class in the appropriate package
8. Write tests (unit tests run against stub, integration against real libs)

**Building with native libraries:**
```bash
# CMake detects all libraries via pkg-config
bash native/build-real.sh
```

**Java-side pattern for native features:**
```java
// High-level class in redact/pii/ or fonts/
public final class MyFeature {
    private MyFeature() {}  // static utility or AutoCloseable

    public static Result doSomething(PdfDocument doc, ...) {
        // Call JpdfiumLib.myNativeFunction(...)
        // Parse JSON result from native layer
        // Return typed Java record
    }
}
```

All native features must work gracefully in stub mode (return empty results,
not throw) so Java-layer tests pass without installing native dependencies.

## Build with Real PDFium

JPDFium uses the [EmbedPDF fork](https://github.com/embedpdf/pdfium) of PDFium, which adds
native encryption, annotation, and redaction APIs (the `EPDF_*` / `EPDFAnnot_*` symbols).
No pre-built binaries are available — the library must be built from source.

```bash
# 1. Build PDFium from EmbedPDF fork source (first run: ~15 GB download + 15-60 min build)
bash native/setup-pdfium.sh

# Subsequent builds (incremental, much faster):
bash native/setup-pdfium.sh --rebuild

# Full clean rebuild:
bash native/setup-pdfium.sh --clean

# 2. Build real bridge with CMake (auto-detects native libraries)
bash native/build-real.sh

# 3. Integration tests
./gradlew :jpdfium:integrationTest
```

### PDFium Build Prerequisites

| Requirement | Notes |
|-------------|-------|
| `git`, `python3` | Used by depot_tools (gclient/gn/ninja) |
| `clang`, `lld` | C++ compiler and linker (Fedora: `dnf install clang lld`) |
| ~15 GB disk space | Source checkout + build artifacts |
| Network access | First build downloads Chromium build toolchain |

The script installs `depot_tools` automatically. After a successful build, the
resulting `libpdfium.so` is placed in `native/pdfium/lib/` with headers in
`native/pdfium/include/`. The build verifies that `EPDF_*` symbols are exported.

## Manual Testing

The `samples` package provides quick 1-click runnable classes for each feature:

Right-click any `S01_Render` ... `S59_FormFill` class in IntelliJ and hit Run.
`RunAllSamples` runs all 59 samples in sequence. Output lands in `jpdfium/samples-output/`.

See `jpdfium/src/test/java/stirling/software/jpdfium/samples/` for details.

## Key Design Decisions

- **C bridge, not raw jextract** - PDFium's 400+ function API has platform-specific wide
  strings and complex lifetime rules. The bridge owns all of this.
- **Handles as `int64_t`** - Opaque, no structs across the FFM boundary.
- **Bridge copies byte buffers** - `jpdfium_doc_open_bytes` copies data because the Java
  `Arena` frees it before the document is closed.
- **BGRA -> RGBA in C** - PDFium renders BGRA; the bridge swaps channels so Java always
  receives consistent RGBA.
- **No `FPDF_ApplyRedactions`** - Does not exist in the public PDFium API.
- **Object Fission Algorithm** - True text removal via `jpdfium_redact_words_ex`:
  1. Map text-page char indices -> page objects (spatial correlation, bounding-box centres).
     Characters with degenerate bounding boxes (synthetic spaces) are assigned to their
     nearest neighbor's object.
  2. Fully-contained objects -> `FPDFPage_RemoveObject` + `FPDFPageObj_Destroy`.
  3. Partially-overlapping objects -> split surviving characters into per-word text objects.
     Each word gets original `a,b,c,d` + `e,f` from `FPDFText_GetCharOrigin` of its first
     character, preserving exact inter-word positioning regardless of font advance widths.
  4. Fission validation: if any fragment has degenerate bounds (Type 3 fonts), the plan is
     aborted and the original object is left for fallback removal.
  5. Fallback: objects unmapped by spatial correlation removed if >= 70% within match bbox.
  6. Paint filled rectangles at all match bboxes, then single `FPDFPage_GenerateContent`.
- **UTF-16LE for search** - `FPDFText_FindStart` expects 2-byte UTF-16LE, not 4-byte
  `wchar_t`. Use `utf8_to_utf16le()` helper.
- **Wide regex for pattern redaction** - `std::wregex` on a `std::wstring` built from
  PDFium codepoints handles accented, CJK, and Cyrillic characters correctly.
- **NativeLoader extracts to a temp directory** - Both `libpdfium.so` and `libjpdfium.so`
  are extracted to the same temp dir so the `$ORIGIN` rpath resolves at runtime.
- **Single `jpdfium` module** - All Java API lives in one module. No internal
  module split (core/bindings/document) to simplify dependency management for consumers.

## Coding Standards

- **Java** - Follow existing style. No Lombok. Records for value types. Javadoc on all public API.
- **C++** - C++17. All exported functions prefixed `jpdfium_`. RAII via
  `DocWrapper`/`PageWrapper` destructors. `extern "C"` in `jpdfium.h` is mandatory.
- **Commits** - Conventional commits: `feat:`, `fix:`, `refactor:`, `docs:`, `test:`, `build:`.
- **Tests** - Every Java API method needs a unit test (stub) and an integration test (real PDFium).

## Pull Request Checklist

- [ ] Stub implementation added for any new native function
- [ ] Implementation guarded by `#ifdef JPDFIUM_HAS_*` (if using native libs)
- [ ] Function added to jextract list in `jpdfium/build.gradle.kts`
- [ ] Unit tests pass: `./gradlew test`
- [ ] Integration tests pass: `./gradlew :jpdfium:integrationTest`
- [ ] New public API has Javadoc
- [ ] No memory leaks in C++ (check with valgrind when modifying native code)
- [ ] README updated if adding user-visible features
