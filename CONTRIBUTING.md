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
   This builds the stub bridge and runs all 50 samples in stub mode. Perfect for testing Java-layer changes.

4. **Full Build with Real PDFium** (recommended for production testing)
   ```bash
   # One command: download PDFium, build real bridge, run all tests and samples
   ./gradlew fullBuildAndTest
   ```
   
   Or step by step:
   ```bash
   ./gradlew downloadPdfium      # Download PDFium (~25 MB, one-time)
   ./gradlew buildRealBridge     # Build native bridge against real PDFium
   ./gradlew test                # Unit tests (stub mode)
   ./gradlew :jpdfium:integrationTest  # Integration tests (real PDFium)
   ./gradlew runAllSamples       # Run all 50 samples
   ```

5. **Open in IntelliJ IDEA** - import as a Gradle project. Add
   `--enable-native-access=ALL-UNNAMED` to Run Configurations -> Templates -> Application -> VM Options.

### Gradle Tasks Overview

| Task | Description |
|------|-------------|
| `./gradlew quickTry` | Quick try-out: stub bridge + unit tests + all samples (no PDFium) |
| `./gradlew downloadPdfium` | Download PDFium binaries (~25 MB) |
| `./gradlew buildRealBridge` | Build real native bridge with PDFium |
| `./gradlew buildStubBridge` | Build stub native bridge (no PDFium) |
| `./gradlew fullBuildAndTest` | Full end-to-end: PDFium + real bridge + all tests + samples |
| `./gradlew runAllSamples` | Run all 50 samples (requires real bridge for full features) |
| `./gradlew runSample -Psample=01` | Run a specific sample (01-47) |
| `./gradlew test` | Run unit tests (stub mode) |
| `./gradlew :jpdfium:integrationTest` | Run integration tests (real PDFium required) |

### Sample Numbers

| Sample | Feature | Sample | Feature |
|--------|---------|--------|---------|
| 01 | Render | 25 | Page Geometry |
| 02 | Text Extract | 26 | Header/Footer |
| 03 | Text Search | 27 | Security |
| 04 | Metadata | 28 | Doc Info |
| 05 | Bookmarks | 29 | Render Options |
| 06 | Redact Words | 30 | Form Reader |
| 07 | Annotations | 31 | Image Extract |
| 08 | Full Pipeline | 32 | Page Objects |
| 09 | Flatten | 33 | Encryption |
| 10 | Signatures | 34 | Linearizer |
| 11 | Attachments | 35 | Overlay |
| 12 | Links | 36 | Annotation Builder |
| 13 | Page Import | 37 | Path Drawer |
| 14 | Structure Tree | 38 | JavaScript Inspector |
| 15 | Thumbnails (embedded) | 39 | Web Links |
| 16 | Page Editing | 40 | Page Boxes |
| 17 | N-Up Layout | 41 | Version Converter |
| 18 | Repair | 42 | Bounded Text |
| 19 | PDF to Images | 43 | Stream Optimizer |
| 20 | Images to PDF | 44 | Flatten Rotation |
| 21 | Thumbnails (generated) | 45 | Page Interleaver |
| 22 | Merge/Split | 46 | Named Destinations |
| 23 | Watermark | 47 | Blank Page Detector |
| 24 | Table Extract | 48 | EmbedPDF Annotations |
| | | 49 | Native Encryption |
| | | 50 | Native Redaction |

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
    - setup-pdfium.sh            # Download EmbedPDF fork binaries
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
        - samples/               # Numbered manual-test classes (S01-S50)
          - RunAllSamples.java   # Execute all 50 samples
          - SampleBase.java      # Base class for samples
          - S01_Render.java ... S50_NativeRedaction.java
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

```bash
# 1. Download (~25 MB, gitignored)
bash native/setup-pdfium.sh

# 2. Build real bridge with CMake (auto-detects native libraries)
bash native/build-real.sh

# 3. Integration tests
./gradlew :jpdfium:integrationTest
```

## Manual Testing

The `samples` package provides quick 1-click runnable classes for each feature:

Right-click any `S01_Render` ... `S47_BlankPageDetector` class in IntelliJ and hit Run.
`RunAllSamples` runs all 50 samples in sequence. Output lands in `jpdfium/samples-output/`.

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
