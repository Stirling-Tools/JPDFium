# JPDFium

High-performance Java 25 FFM bindings for PDFium (EmbedPDF fork).

- Pure FFM (`java.lang.foreign`), zero JNI
- Linux x64/arm64, macOS x64/arm64, Windows x64
- MIT licensed (PDFium is Apache 2.0)

Requires the JVM flag `--enable-native-access=ALL-UNNAMED`.

## Quick Start

```java
// Render + redact
try (var doc = PdfDocument.open(Path.of("input.pdf"))) {
    try (var page = doc.page(0)) {
        ImageIO.write(page.renderAt(150).toBufferedImage(), "PNG", new File("page0.png"));
        page.redactPattern("\\d{3}-\\d{2}-\\d{4}", 0xFF000000);
        page.flatten();
    }
    doc.save(Path.of("output.pdf"));
}

// Structured text extraction
try (var doc = PdfDocument.open(Path.of("input.pdf"))) {
    for (PageText pt : PdfTextExtractor.extractAll(doc))
        System.out.printf("Page %d: %d words%n%s%n", pt.pageIndex(), pt.wordCount(), pt.plainText());
}

// High-level redaction
RedactResult result = PdfRedactor.redact(Path.of("input.pdf"), RedactOptions.builder()
    .addWord("Confidential")
    .addWord("\\d{3}-\\d{2}-\\d{4}")
    .useRegex(true)
    .padding(2.0f)
    .convertToImage(true)          // most secure: no selectable text survives
    .build());
try (var doc = result.document()) { doc.save(Path.of("output.pdf")); }
```

Runnable examples for every feature live in `jpdfium/src/test/java/stirling/software/jpdfium/samples/` (`S01_Render` through `S92_RustCompress`). Full API reference is in the Javadoc.

## Core API

```java
PdfDocument.open(Path)                    // also open(byte[]), open(Path, String password)
doc.pageCount(); doc.page(i); doc.save(Path); doc.saveBytes()
doc.metadata(); doc.permissions(); doc.bookmarks(); doc.signatures(); doc.attachments()
doc.addAttachment(String, byte[]); doc.deleteAttachment(int)

page.size(); page.renderAt(dpi)           // -> RenderResult.toBufferedImage()
page.redactRegion(Rect, int); page.redactPattern(String, int)
page.redactWordsEx(String[], int, ...)    // Object Fission: true text removal
page.flatten(); page.annotations(); page.links(); page.structureTree()

PdfTextExtractor.extractPage(doc, i)      // -> PageText (lines -> words -> chars)
PdfTextExtractor.extractAll(doc)
PdfTextSearcher.search(doc, query)        // -> List<SearchMatch>
```

## Project Structure

```
native/bridge/          C++ bridge: document, render, text, redact, PII pipeline,
                        repair, image, Brotli, OpenJPEG, PDFio, ICC, Unicode
native/build-real.sh    build the real PDFium bridge
native/build-stub.sh    build a stub bridge (unit tests without PDFium)
native/setup-pdfium.sh  download and build the EmbedPDF PDFium fork
native/rust/            optional Rust modules (lopdf + zopfli compression, repair, resize)
jpdfium/                Java API (stirling.software.jpdfium): core API, panama/ FFM
                        bindings, doc/ inspection & editing, text/, redact/, transform/,
                        fonts/, model/, util/, plus runnable samples under src/test
jpdfium-natives-<platform>/  native JARs: linux/darwin/windows × x64/arm64
jpdfium-spring/         Spring Boot auto-configuration
jpdfium-bom/            Maven BOM for dependency management
```

## Building

### Prerequisites

- Java 25, https://jdk.java.net/25/
- C++23 compiler (`gcc-c++` / `g++` / Xcode CLT / MSVC)
- CMake 3.20+
- Gradle 9.7 (via wrapper)
- jextract 25 (optional, to regenerate FFM bindings)

Native libraries for the PII pipeline (all MIT/Apache-2.0-compatible):

| Library | License | Purpose |
|---------|---------|---------|
| PCRE2 | BSD-3 | JIT regex engine |
| FreeType | FTL/MIT | Font parsing, classification, widths |
| HarfBuzz | MIT | Shaping, glyph-safe redaction |
| ICU4C | Unicode | Normalization, BiDi, segmentation |
| qpdf | Apache-2.0 | Structure manipulation, repair |
| pugixml | MIT | XMP metadata parsing |
| libunibreak | zlib | Grapheme cluster boundaries |

Fedora / RHEL:
```bash
sudo dnf install -y pcre2-devel freetype-devel harfbuzz-devel \
    libicu-devel qpdf-devel pugixml-devel libunibreak-devel
```

Ubuntu / Debian:
```bash
sudo apt install -y libpcre2-dev libfreetype-dev libharfbuzz-dev \
    libicu-dev libqpdf-dev libpugixml-dev libunibreak-dev
```

Missing libraries are auto-detected via pkg-config and silently skipped at runtime.

### Build via Gradle

```bash
./gradlew quickTry            # stub bridge, runs all samples, no PDFium needed
./gradlew fullBuildAndTest    # real PDFium: download, build, test, run samples
./gradlew test                # unit tests
./gradlew :jpdfium:integrationTest
./gradlew runAllSamples
./gradlew runSample -Psample=01   # run a specific sample (01..92)
./gradlew :jpdfium:generateBindings  # regenerate FFM bindings from jpdfium.h
```

Set `jpdfium.jextractHome` in `~/.gradle/gradle.properties` or `JEXTRACT_HOME` before regenerating bindings (defaults to `~/Downloads/jextract-25`).

### Manual build (real PDFium)

```bash
./gradlew buildPdfium       # build EmbedPDF PDFium fork (~15 GB, first build 15-60 min)
./gradlew buildRealBridge   # compile native bridge with CMake
./gradlew test
./gradlew :jpdfium:integrationTest
```

For Java-only development, `./gradlew buildStubBridge` provides a pass-through stub.

## Thread Safety

- A `PdfDocument` and all handles derived from it must stay on one thread.
- Independent `PdfDocument` instances on separate threads are safe.
- `FPDF_InitLibrary` / `FPDF_DestroyLibrary` run once globally.

## Redaction Design

Object Fission removes text from the content stream rather than painting over it:

1. Map each character to its owning page object via `FPDFText_GetTextObject`.
2. Destroy objects fully covered by matches; for partial overlaps, split surviving characters into per-word fragments, each pinned to its original char origin so inter-word spacing is preserved.
3. Validate fragment bounds; abort to fallback removal for degenerate cases (e.g. Type 3 fonts).
4. Fallback: remove objects with >= 70% area overlap; paint a filled rectangle over every match; commit with a single `FPDFPage_GenerateContent`.

Security levels (least to most secure):

```
removeContent=false       visual overlay only (not secure)
removeContent=true        Object Fission, text removed from stream
page.flatten()            bakes changes, prevents annotation recovery
convertToImage(true)      rasterize, no text, vectors, or metadata survives
```

## Testing

```bash
./gradlew :jpdfium:integrationTest
./gradlew :jpdfium:integrationTest --tests "stirling.software.jpdfium.CorpusRedactTest"
./gradlew :jpdfium:integrationTest --tests "stirling.software.jpdfium.redact.ObjectFissionCoordinateTest"
```

HTML reports are written to `samples-output/`. See `TESTING.md` for details.

## License

MIT. PDFium is Apache 2.0. Other included binaries may be licensed otherwise.
