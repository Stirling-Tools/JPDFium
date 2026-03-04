# JPDFium

High-performance Java 25 FFM bindings for Google PDFium.

JPDFium provides a safe, ergonomic Java API for PDF rendering, text extraction,
and true content-stripping redaction — powered by Google's PDFium engine via
Java 25's Foreign Function & Memory (FFM) API.

## Features

- **PDF Rendering** — Render pages to RGBA bitmaps at any DPI
- **Text Extraction** — Structured page → line → word → character extraction with font/position metadata
- **Text Search** — Literal string search via PDFium's native search engine
- **True Redaction** — Removes content from the PDF stream (not a cosmetic overlay); region, regex-pattern, and word-list redaction with full Unicode support
- **Secure PDF-Image** — Convert pages to rasterized images, stripping all selectable text and vector content
- **Cross-Platform** — Linux x64/arm64, macOS x64/arm64, Windows x64
- **Zero JNI** — Pure FFM (`java.lang.foreign`), no JNI boilerplate
- **Apache 2.0** — PDFium is BSD-3-Clause, this project is Apache-2.0

## Quick Start

```java
// Render a page
try (var doc = PdfDocument.open(Path.of("input.pdf"))) {
    System.out.println("Pages: " + doc.pageCount());

    try (var page = doc.page(0)) {
        // Render to PNG at 150 DPI
        ImageIO.write(page.renderAt(150).toBufferedImage(), "PNG", new File("page0.png"));

        // Redact SSNs — true content removal
        page.redactPattern("\\d{3}-\\d{2}-\\d{4}", 0xFF000000);
        page.flatten();
    }

    doc.save(Path.of("output.pdf"));
}

// Structured text extraction
try (var doc = PdfDocument.open(Path.of("input.pdf"))) {
    List<PageText> pages = PdfTextExtractor.extractAll(doc);
    for (PageText pt : pages) {
        System.out.printf("Page %d: %d lines, %d words%n",
            pt.pageIndex(), pt.lineCount(), pt.wordCount());
        System.out.println(pt.plainText());
    }
}

// High-level redaction with PdfRedactor
RedactOptions opts = RedactOptions.builder()
    .addWord("Confidential")
    .addWord("\\d{3}-\\d{2}-\\d{4}")   // SSN
    .useRegex(true)
    .padding(2.0f)
    .convertToImage(true)               // most secure: no selectable text survives
    .build();

RedactResult result = PdfRedactor.redact(Path.of("input.pdf"), opts);
try (var doc = result.document()) {
    doc.save(Path.of("output.pdf"));
}
```

JVM flag required: `--enable-native-access=ALL-UNNAMED`

## Architecture

```
┌──────────────────────────────────────────────────────────────┐
│                  Your Application (Java 25)                  │
├──────────────────────────────────────────────────────────────┤
│  jpdfium                                                     │
│    PdfDocument · PdfPage                                     │
│    PdfTextExtractor · PdfTextSearcher                        │
│    PdfRedactor · RedactOptions · PageOps                     │
│    panama/NativeLoader · panama/JpdfiumLib                   │
│    panama/JpdfiumH  (jextract-generated FFM bindings)        │
├──────────────────────────────────────────┬───────────────────┤
│                                          │ java.lang.foreign │
├──────────────────────────────────────────┼───────────────────┤
│  libjpdfium.so   C bridge (~23 fns)      │                   │
├──────────────────────────────────────────┼───────────────────┤
│  libpdfium.so    Google PDFium           │                   │
└──────────────────────────────────────────┴───────────────────┘
```

JPDFium does not bind directly to PDFium's 400+ function C API. A thin C++ bridge (`libjpdfium`) exposes exactly the operations needed, with clean error codes, handle-based lifetime management, and correct memory ownership.

## Modules

| Module | Purpose |
|--------|---------|
| `jpdfium` | All Java API: `PdfDocument`, `PdfPage`, text extraction, text search, redaction, transforms, FFM bindings, `NativeLoader` |
| `jpdfium-natives-linux-x64` | Native JARs for Linux x86-64 |
| `jpdfium-natives-linux-arm64` | Native JARs for Linux AArch64 |
| `jpdfium-natives-darwin-x64` | Native JARs for macOS Intel |
| `jpdfium-natives-darwin-arm64` | Native JARs for macOS Apple Silicon |
| `jpdfium-natives-windows-x64` | Native JARs for Windows x86-64 |
| `jpdfium-spring` | Spring Boot auto-configuration |
| `jpdfium-bom` | Maven BOM for consistent dependency versions |

## API Overview

### `PdfDocument`
```java
PdfDocument.open(Path)                       // open from file
PdfDocument.open(byte[])                     // open from bytes
PdfDocument.open(Path, String password)      // open encrypted PDF
doc.pageCount()
doc.page(int index)                          // returns PdfPage (AutoCloseable)
doc.save(Path)
doc.saveBytes()
doc.convertPageToImage(int pageIndex, int dpi) // rasterize in-place (most secure)
```

### `PdfPage`
```java
page.size()                                  // PageSize(width, height) in PDF points
page.renderAt(int dpi)                       // RenderResult → toBufferedImage()
page.extractTextJson()                       // raw char-level JSON
page.findTextJson(String query)              // match positions as JSON
page.redactRegion(Rect, int argbColor)
page.redactPattern(String regex, int argbColor)
page.redactWords(String[] words, int argb, float padding, boolean wholeWord,
                 boolean useRegex, boolean removeContent)
// Object Fission — true text removal, returns match count:
int matches = page.redactWordsEx(String[] words, int argb, float padding,
                                  boolean wholeWord, boolean useRegex,
                                  boolean removeContent, boolean caseSensitive)
page.flatten()                               // commit annotations to content stream
```

### `PdfTextExtractor`
```java
PdfTextExtractor.extractPage(doc, pageIndex) // → PageText
PdfTextExtractor.extractAll(doc)             // → List<PageText>
PdfTextExtractor.extractAll(Path)            // auto-managed document
```

`PageText` → `List<TextLine>` → `List<TextWord>` → `List<TextChar>` (with x, y, font, size)

### `PdfTextSearcher`
```java
PdfTextSearcher.search(doc, query)           // → List<SearchMatch> across all pages
PdfTextSearcher.searchPage(doc, i, query)    // → List<SearchMatch> on one page
// SearchMatch: pageIndex, startIndex, length
```

### `PdfRedactor`
```java
RedactOptions opts = RedactOptions.builder()
    .addWord("secret")
    .addWord("\\d{3}-\\d{2}-\\d{4}")
    .useRegex(true)
    .wholeWord(false)
    .caseSensitive(false)   // case-insensitive (default)
    .boxColor(0xFF000000)
    .padding(0.0f)
    .removeContent(true)    // Object Fission: true text removal (default)
    .convertToImage(true)   // most secure: rasterize after redaction
    .imageDpi(150)
    .build();

RedactResult result = PdfRedactor.redact(inputPath, opts);
// result.pagesProcessed(), result.durationMs(), result.totalMatches()
// result.pageResults() → List<PageResult(pageIndex, wordsSearched, matchesFound)>
try (var doc = result.document()) { doc.save(outputPath); }
```

### `PageOps`
```java
PageOps.flatten(doc, pageIndex)
PageOps.flattenAll(doc)
PageOps.convertToImage(doc, pageIndex, dpi)
PageOps.convertAllToImages(doc, dpi)
PageOps.renderPage(doc, pageIndex, dpi)      // → BufferedImage
PageOps.renderAll(doc, dpi)                  // → List<BufferedImage>
```

## Building

### Prerequisites

- Java 25 — [download](https://jdk.java.net/25/)
- g++ (C++17) — `apt install g++` / Xcode CLT / MSVC
- jextract 25 (optional, to regenerate FFM bindings) — [download](https://jdk.java.net/jextract/)

### Build with Stub (no PDFium — unit tests only)

```bash
g++ -std=c++17 -shared -fPIC -O2 \
    -Inative/bridge/include \
    native/bridge/src/jpdfium_stub.cpp \
    -o jpdfium-natives/jpdfium-natives-linux-x64/src/main/resources/natives/linux-x64/libjpdfium.so

./gradlew test
```

### Build with Real PDFium

```bash
# 1. Download PDFium (~25 MB, gitignored)
bash native/setup-pdfium.sh

# 2. Compile the C bridge linked against real PDFium
PDFIUM_DIR=native/pdfium
g++ -std=c++17 -shared -fPIC -O2 \
    -Inative/bridge/include -I${PDFIUM_DIR}/include \
    native/bridge/src/jpdfium_document.cpp \
    -L${PDFIUM_DIR}/lib -lpdfium \
    -Wl,-rpath,'$ORIGIN' \
    -o jpdfium-natives/jpdfium-natives-linux-x64/src/main/resources/natives/linux-x64/libjpdfium.so

# 3. Copy the PDFium shared library (needed for $ORIGIN rpath)
cp ${PDFIUM_DIR}/lib/libpdfium.so \
   jpdfium-natives/jpdfium-natives-linux-x64/src/main/resources/natives/linux-x64/

# 4. Run all tests
./gradlew test

# 5. Run integration tests (real PDFium required)
./gradlew :jpdfium:integrationTest
```

### Regenerate FFM Bindings (after changing `jpdfium.h`)

```bash
# Configure jextract location in ~/.gradle/gradle.properties:
#   jpdfium.jextractHome=/path/to/jextract-25
# OR set JEXTRACT_HOME env var. Defaults to ~/Downloads/jextract-25.

./gradlew :jpdfium:generateBindings
```

## Manual Testing in IntelliJ

The `samples` package contains numbered runnable classes for quick manual verification:

```
jpdfium/src/test/java/stirling/software/jpdfium/samples/
├── S01_Render.java          → samples-output/render/page-N.png
├── S02_TextExtract.java     → samples-output/text-extract/report.txt
├── S03_TextSearch.java      → stdout
├── S04_RedactRegion.java    → samples-output/redact-region/output.pdf
├── S05_RedactPattern.java   → samples-output/redact-pattern/output.pdf
├── S06_RedactWords.java     → samples-output/redact-words/output.pdf
├── S07_SecureRedact.java    → samples-output/secure-redact/output.pdf
├── S08_FullPipeline.java    → samples-output/full-pipeline/
└── RunAllSamples.java       → all of the above (smoke test)
```

**One-time IntelliJ setup:** Run → Edit Configurations → Templates → Application → VM Options:
```
--enable-native-access=ALL-UNNAMED
```

Then right-click any sample → Run. Or launch the visual Swing viewer:
```bash
./gradlew :jpdfium:viewer [-Ppdf=/path/to/file.pdf]
```

## Thread Safety

- `PdfDocument` and all `PdfPage` handles from it must be confined to one thread.
- Independent `PdfDocument` instances on separate threads are safe.
- `FPDF_InitLibrary` / `FPDF_DestroyLibrary` are called once globally by `JpdfiumLib`'s static initializer.

## Redaction Design — Object Fission Algorithm

True redaction requires more than painting a black rectangle. JPDFium implements the **Object Fission Algorithm** for character-level text removal with zero typographic side-effects.

### How it works

1. **Spatial correlation** — each text-page character index is mapped to its owning `FPDF_PAGEOBJECT` by comparing character bounding-box centres against page-object bounding boxes.
2. **Object classification** — for every text object that contains redacted characters:
   - *Fully contained* → the entire object is destroyed.
   - *Partially overlapping* → **Object Fission**: the object is split into two fragments:
     - **Prefix** — a new text object with the original font, size, matrix, and render mode.
     - **Suffix** — a new text object with the same font/size/renderMode but with `(e, f)` translation pinned to the absolute coordinate of the first surviving character via `FPDFText_GetCharOrigin`.
     - The original object is then destroyed.
3. **Fallback** — text objects not caught by spatial correlation (Form XObjects, degenerate bboxes) are removed if ≥70% of their area overlaps a match bbox.
4. **Visual cover** — a filled rectangle is painted over every match region.
5. **Single commit** — one `FPDFPage_GenerateContent` call bakes all modifications.

### Why this is better than bounding-box removal

| Approach | Adjacent text preserved | Match count reported | Case-insensitive |
|----------|------------------------|----------------------|-----------------|
| Old (`FPDFPage_RemoveObject` ≥70%) | ✗ over-removal possible | ✗ | ✗ |
| **Object Fission** (`redactWordsEx`) | ✓ character-level precision | ✓ | ✓ |

### Security levels (least → most secure)

```
redactWordsEx(..., removeContent=false)   visual overlay only (not secure)
redactWordsEx(..., removeContent=true)    Object Fission — text removed from stream
page.flatten()                            bakes everything — prevents annotation recovery
doc.convertPageToImage(page, dpi)         rasterize — no text, vectors, or metadata survives
```

Use `convertToImage(true)` in `RedactOptions` for the nuclear option.

## License

Apache 2.0. PDFium itself is [BSD-3-Clause](https://pdfium.googlesource.com/pdfium/+/refs/heads/main/LICENSE).
