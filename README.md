# JPDFium

High-performance Java 25 FFM bindings for Google PDFium.

JPDFium provides a safe, ergonomic Java API for PDF rendering, text extraction,
and true content-stripping redaction - powered by Google's PDFium engine via
Java 25's Foreign Function & Memory (FFM) API.

## Features

- **PDF Rendering** - Render pages to RGBA bitmaps at any DPI
- **Text Extraction** - Structured page -> line -> word -> character extraction with font/position metadata
- **Text Search** - Literal string search via PDFium's native search engine
- **True Redaction** - Removes content from the PDF stream (not a cosmetic overlay); region, regex-pattern, and word-list redaction with full Unicode support
- **PII Redaction Pipeline** - PCRE2 JIT pattern engine, FlashText NER, HarfBuzz glyph-level redaction, font normalization, XMP metadata stripping, semantic coreference expansion - all native via FFM
- **Document Metadata** - Read title, author, subject, creator, dates, permissions, page labels
- **Bookmarks** - Full outline/TOC tree traversal with nested bookmarks, destinations, and URI actions
- **Annotations** - Full CRUD: list, create, modify, delete annotations with type/rect/color/flags/contents
- **Hyperlinks** - Enumerate and hit-test page links with action type and URI resolution
- **Digital Signatures** - Read-only inspection of signature metadata (sub-filter, reason, time, contents, DocMDP)
- **Embedded Attachments** - List, extract, add, and delete embedded file attachments
- **Page Thumbnails** - Extract pre-rendered decoded/raw thumbnail data from pages
- **Structure Tree** - Accessibility tagged structure (headings, paragraphs, tables) traversal
- **Page Import/Export** - Import pages between documents, copy viewer preferences, delete pages
- **Page Editing** - Create/modify page objects (text, rectangles, paths), set colors/transforms
- **Secure PDF-Image** - Convert pages to rasterized images, stripping all selectable text and vector content
- **Cross-Platform** - Linux x64/arm64, macOS x64/arm64, Windows x64
- **PDF Repair** - Multi-stage cascade pipeline: qpdf XRef reconstruction, startxref offset correction, xref type normalization, all via builder-pattern API
- **Zero JNI** - Pure FFM (`java.lang.foreign`), no JNI boilerplate
- **MIT** - PDFium is Apache 2.0, this project is MIT

## Quick Start

```java
// Render a page
try (var doc = PdfDocument.open(Path.of("input.pdf"))) {
    System.out.println("Pages: " + doc.pageCount());

    try (var page = doc.page(0)) {
        // Render to PNG at 150 DPI
        ImageIO.write(page.renderAt(150).toBufferedImage(), "PNG", new File("page0.png"));

        // Redact SSNs - true content removal
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

### Document Metadata, Bookmarks, Signatures

```java
try (var doc = PdfDocument.open(Path.of("input.pdf"))) {
    // Metadata
    Map<String, String> meta = doc.metadata();
    meta.forEach((k, v) -> System.out.printf("%s: %s%n", k, v));
    System.out.println("Permissions: 0x" + Long.toHexString(doc.permissions()));

    // Bookmarks (table of contents)
    List<Bookmark> bookmarks = doc.bookmarks();
    for (Bookmark bm : bookmarks) {
        System.out.printf("  %s -> page %d%n", bm.title(), bm.pageIndex());
    }

    // Digital signatures
    List<Signature> sigs = doc.signatures();
    for (Signature sig : sigs) {
        System.out.printf("  Signature: %s at %s%n",
            sig.subFilter().orElse("unknown"),
            sig.signingTime().orElse("unknown"));
    }

    // Attachments
    List<Attachment> atts = doc.attachments();
    for (Attachment att : atts) {
        System.out.printf("  %s (%d bytes)%n", att.name(), att.data().length);
    }
}
```

### Annotations, Links, Structure Tree

```java
try (var doc = PdfDocument.open(Path.of("input.pdf"));
     var page = doc.page(0)) {
    // Annotations
    List<Annotation> annots = page.annotations();
    for (Annotation a : annots) {
        System.out.printf("  %s at (%.0f,%.0f)%n", a.type(), a.rect()[0], a.rect()[1]);
    }

    // Hyperlinks
    List<PdfLink> links = page.links();
    for (PdfLink link : links) {
        System.out.printf("  %s -> %s%n", link.actionType(),
            link.uri().orElse("page " + link.pageIndex()));
    }

    // Tagged structure tree
    List<StructElement> tree = page.structureTree();
    for (StructElement e : tree) {
        System.out.printf("  <%s> %s%n", e.type(), e.altText().orElse(""));
    }
}
```

## Architecture

| Layer | Components | Description |
|---|---|---|
| **User Application** | Your Application (Java 25) | Consuming application code |
| **High-level Java API** | `PdfDocument`, `PdfPage`, `PdfTextExtractor`, `PdfRedactor`, `doc.*` | Safe, ergonomic abstractions within the `jpdfium` module |
| **Direct FFM Bindings** | `panama/PdfiumBindings`, `panama/FfmHelper` | Hand-crafted MethodHandle bindings directly to PDFium's C API |
| **Bridge FFM Bindings** | `panama/NativeLoader`, `panama/JpdfiumH` | Auto-generated jextract interfaces to the C++ bridge |
| **Native Bridge** | `libjpdfium.so` | C/C++ bridge for complex operations (redaction, font normalization) |
| **Core Engine** | `libpdfium.so` | Google's underlying PDFium engine |
| **Native Libraries** | PCRE2, FreeType, HarfBuzz, ICU4C, qpdf, pugixml, libunibreak, Brotli, lcms2, OpenJPEG | PII redaction pipeline + PDF repair cascade |
| **Vendored Libraries** | simdutf, utf8proc, xxHash | SIMD UTF transcoding, Unicode normalization, content hashing |

JPDFium uses a hybrid architecture: a C++ bridge (`libjpdfium`) for complex operations (redaction, font normalization) and direct FFM bindings (`PdfiumBindings`) to PDFium's C API for document inspection features (metadata, bookmarks, annotations, signatures, etc.).

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
doc.metadata()                               // -> Map<String,String> all metadata
doc.metadata(String tag)                     // -> Optional<String> specific tag
doc.permissions()                            // -> long permission flags
doc.bookmarks()                              // -> List<Bookmark> outline tree
doc.findBookmark(String title)               // -> Optional<Bookmark>
doc.signatures()                             // -> List<Signature> digital signatures
doc.attachments()                            // -> List<Attachment> embedded files
doc.addAttachment(String name, byte[] data)  // add embedded file
doc.deleteAttachment(int index)              // remove embedded file
```

### `PdfPage`
```java
page.size()                                  // PageSize(width, height) in PDF points
page.renderAt(int dpi)                       // RenderResult -> toBufferedImage()
page.extractTextJson()                       // raw char-level JSON
page.findTextJson(String query)              // match positions as JSON
page.redactRegion(Rect, int argbColor)
page.redactPattern(String regex, int argbColor)
page.redactWords(String[] words, int argb, float padding, boolean wholeWord,
                 boolean useRegex, boolean removeContent)
// Object Fission - true text removal, returns match count:
int matches = page.redactWordsEx(String[] words, int argb, float padding,
                                  boolean wholeWord, boolean useRegex,
                                  boolean removeContent, boolean caseSensitive)
page.flatten()                               // commit annotations to content stream
page.annotations()                           // -> List<Annotation>
page.links()                                 // -> List<PdfLink>
page.structureTree()                         // -> List<StructElement>
page.thumbnail()                             // -> Optional<byte[]>
```

### `PdfTextExtractor`
```java
PdfTextExtractor.extractPage(doc, pageIndex) // -> PageText
PdfTextExtractor.extractAll(doc)             // -> List<PageText>
PdfTextExtractor.extractAll(Path)            // auto-managed document
```

`PageText` -> `List<TextLine>` -> `List<TextWord>` -> `List<TextChar>` (with x, y, font, size)

### `PdfTextSearcher`
```java
PdfTextSearcher.search(doc, query)           // -> List<SearchMatch> across all pages
PdfTextSearcher.searchPage(doc, i, query)    // -> List<SearchMatch> on one page
// SearchMatch: pageIndex, startIndex, length
```

### Document Inspection APIs (`stirling.software.jpdfium.doc`)

Direct FFM bindings to PDFium's C API for document-level features. These APIs accept raw `MemorySegment` handles obtained via `doc.rawHandle()` / `page.rawHandle()`, or use the convenience methods on `PdfDocument` / `PdfPage`.

```java
// Metadata
PdfMetadata.all(rawDoc)                      // -> Map<String,String>
PdfMetadata.get(rawDoc, "Title")             // -> Optional<String>
PdfMetadata.permissions(rawDoc)              // -> long
PdfMetadata.pageLabel(rawDoc, 0)             // -> Optional<String>

// Bookmarks
PdfBookmarks.list(rawDoc)                    // -> List<Bookmark> (recursive tree)
PdfBookmarks.find(rawDoc, "Chapter 1")       // -> Optional<Bookmark>

// Annotations (full CRUD)
PdfAnnotations.list(rawPage)                 // -> List<Annotation>
PdfAnnotations.create(rawPage, type, rect)   // -> Annotation
PdfAnnotations.setContents(rawPage, idx, text)
PdfAnnotations.setColor(rawPage, idx, r, g, b, a)
PdfAnnotations.remove(rawPage, idx)

// Hyperlinks
PdfLinks.list(rawDoc, rawPage)               // -> List<PdfLink>
PdfLinks.atPoint(rawDoc, rawPage, x, y)      // -> Optional<PdfLink>

// Digital Signatures (read-only)
PdfSignatures.list(rawDoc)                   // -> List<Signature>
PdfSignatures.count(rawDoc)

// Attachments (CRUD)
PdfAttachments.list(rawDoc)                  // -> List<Attachment>
PdfAttachments.add(rawDoc, "file.txt", data)
PdfAttachments.delete(rawDoc, index)

// Thumbnails
PdfThumbnails.getDecoded(rawPage)            // -> Optional<byte[]>
PdfThumbnails.getRaw(rawPage)                // -> Optional<byte[]>

// Structure Tree
PdfStructureTree.get(rawPage)                // -> List<StructElement> (recursive)

// Page Import
PdfPageImporter.importPages(dest, src, "1-3", insertAt)
PdfPageImporter.importPagesByIndex(dest, src, indices, insertAt)
PdfPageImporter.copyViewerPreferences(dest, src)
PdfPageImporter.deletePage(doc, pageIndex)

// Page Editing
PdfPageEditor.newPage(doc, index, width, height)
PdfPageEditor.createTextObject(doc, "Helvetica", 12f)
PdfPageEditor.setText(textObj, "Hello")
PdfPageEditor.createRect(x, y, w, h)
PdfPageEditor.createPath(x, y)
PdfPageEditor.setFillColor(obj, r, g, b, a)
PdfPageEditor.insertObject(page, obj)
PdfPageEditor.generateContent(page)
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
    .removeContent(true)    // true text removal (default)
    .convertToImage(true)   // most secure: rasterize after redaction
    .imageDpi(150)
    .build();

RedactResult result = PdfRedactor.redact(inputPath, opts);
// result.pagesProcessed(), result.durationMs(), result.totalMatches()
// result.pageResults() -> List<PageResult(pageIndex, wordsSearched, matchesFound)>
try (var doc = result.document()) { doc.save(outputPath); }
```

### `PageOps`
```java
PageOps.flatten(doc, pageIndex)
PageOps.flattenAll(doc)
PageOps.convertToImage(doc, pageIndex, dpi)
PageOps.convertAllToImages(doc, dpi)
PageOps.renderPage(doc, pageIndex, dpi)      // -> BufferedImage
PageOps.renderAll(doc, dpi)                  // -> List<BufferedImage>
```

### `PdfRepair`

Builder-pattern API for repairing damaged PDFs. Uses a multi-stage cascade:
PDFium tolerant parse - qpdf XRef reconstruction - startxref offset correction - xref type normalization.

```java
// Repair from bytes
RepairResult result = PdfRepair.builder()
    .input(pdfBytes)                    // or .input(Path)
    .normalizeXref(true)                // fix mixed xref types
    .fixStartxref(true)                 // brute-force startxref offset
    .build()
    .execute();

if (result.isUsable()) {
    byte[] fixed = result.repairedPdf();
    System.out.println("Status: " + result.status());  // CLEAN, FIXED, PARTIAL, FAILED
}

// Inspect only (non-destructive diagnostic scan)
String diagnostics = PdfRepair.inspect(pdfBytes);
// -> {"status":"loaded","warning_count":2,"page_count":5,...}

// All strategies at once
RepairResult r = PdfRepair.builder()
    .input(Path.of("damaged.pdf"))
    .all()                              // enable everything
    .forceVersion14(true)               // downgrade to PDF 1.4
    .build()
    .execute();
```

### `NUpLayout`

Fluent builder for creating N-up layouts (multiple pages tiled onto one).

```java
// 4-up on A4 landscape
NUpLayout.from(doc).grid(2, 2).a4Landscape().build().save(outputPath);

// 6-up on US Letter landscape as bytes
byte[] pdf = NUpLayout.from(doc).grid(3, 2).letterLandscape().build().toBytes();

// Custom page size (A3 landscape: 1190 x 842 pt)
NUpLayout.from(doc).grid(4, 2).pageSize(1190, 842).build().save(outputPath);
```

### Advanced PII Redaction

The PII redaction pipeline orchestrates 9 stages powered by native libraries via FFM:

1. Font Normalization
2. Text Extraction
3. PCRE2 Patterns
4. FlashText NER
5. Semantic Analysis
6. Glyph Redaction
7. Object Fission
8. Metadata Redaction
9. Flatten/Image

All PII features are accessed through the unified `RedactOptions` builder:

```java
import stirling.software.jpdfium.redact.pii.PiiCategory;

RedactOptions opts = RedactOptions.builder()
    // Basic word list
    .addWord("Confidential")
    .addWord("\\d{3}-\\d{2}-\\d{4}")
    .useRegex(true)

    // PCRE2 JIT patterns for PII categories
    .enableAllPiiPatterns()           // email, SSN, phone, credit card, IBAN, ...
    .luhnValidation(true)             // reject false-positive credit card numbers

    // Or select specific categories
    // .piiPatterns(PiiCategory.select(PiiCategory.EMAIL, PiiCategory.SSN, PiiCategory.PHONE))

    // FlashText NER: O(n) dictionary entity matching
    .addEntity("John Smith", "PERSON")
    .addEntity("Acme Corp", "ORGANIZATION")

    // Font normalization (fix broken PDFs before pattern matching)
    .normalizeFonts(true)
    .fixToUnicode(true)
    .repairWidths(true)

    // HarfBuzz glyph-level redaction
    .glyphAware(true)
    .ligatureAware(true)
    .bidiAware(true)
    .graphemeSafe(true)

    // XMP metadata + /Info dictionary
    .redactMetadata(true)
    .stripAllMetadata(false)

    // Semantic coreference expansion
    .semanticRedact(true)
    .coreferenceWindow(2)

    // Security
    .boxColor(0xFF000000)
    .removeContent(true)
    .convertToImage(true)
    .build();

RedactResult result = PdfRedactor.redact(Path.of("input.pdf"), opts);
try (var doc = result.document()) {
    doc.save(Path.of("output.pdf"));
}

System.out.printf("Redacted %d total matches across %d pages%n",
    result.totalMatches(), result.pagesProcessed());
```

#### PII Redaction Components

| Component | Class | Native Library | Purpose |
|-----------|-------|----------------|----------|
| **Pattern Engine** | `PatternEngine` | PCRE2 (JIT) | Lookaheads, `\b`, Unicode `\w`, named groups, Luhn post-validation |
| **PII Categories** | `PiiCategory` | - | Enum with built-in PCRE2 regexes: EMAIL, PHONE, SSN, CREDIT_CARD, IBAN, IP, DATE |
| **NER** | `EntityRedactor` | FlashText (C++ trie) | O(n) dictionary entity matching with coreference expansion |
| **Glyph Redaction** | `GlyphRedactor` | HarfBuzz + ICU4C | Ligature/BiDi/grapheme-safe redaction with cluster mapping |
| **Font Normalization** | `FontNormalizer` | FreeType + HarfBuzz + qpdf | /ToUnicode CMap repair, /W table fix, re-subsetting, Type1->OTF |
| **Metadata Redaction** | `XmpRedactor` | pugixml + qpdf | XMP metadata + /Info dictionary pattern matching and stripping |

#### Native Library Dependencies

JPDFium links against several native libraries at build time. All are MIT, BSD, Apache, or equivalent permissive licenses - **no GPL/LGPL dependencies**.

##### System Libraries (required, installed via package manager)

| Library | License | Category | Purpose |
|---------|---------|----------|---------|
| [PCRE2](https://www.pcre.org/) | BSD-3 | PII Redaction | JIT-compiled regex engine for pattern matching |
| [FreeType](https://freetype.org/) | FTL/MIT | PII Redaction | Font parsing, classification, glyph width calculation |
| [HarfBuzz](https://harfbuzz.github.io/) | MIT | PII Redaction | Text shaping, `hb-subset` font subsetting |
| [ICU4C](https://icu.unicode.org/) | Unicode License | PII Redaction | NFC normalization, BiDi reordering, sentence segmentation |
| [qpdf](https://qpdf.sourceforge.io/) | Apache-2.0 | Repair + Redaction | PDF structure manipulation, XRef repair cascade, stream replacement |
| [pugixml](https://pugixml.org/) | MIT | PII Redaction | XMP metadata XML parsing |
| [libunibreak](https://github.com/adah1972/libunibreak) | zlib | PII Redaction | Grapheme cluster boundaries |
| [zlib](https://www.zlib.net/) | zlib | Core | FlateDecode compression (used by Brotli transcoding, qpdf) |
| [Brotli](https://github.com/google/brotli) | MIT | Repair | PDF 2.0+ `/BrotliDecode` stream transcoding to `/FlateDecode` |
| [lcms2](https://www.littlecms.com/) | MIT | Repair | ICC color profile validation, `/N` mismatch fix, profile replacement |
| [OpenJPEG](https://www.openjpeg.org/) | BSD-2 | Repair | JPEG2000 (`/JPXDecode`) partial bitstream recovery |
| [PDFio](https://www.msweet.org/pdfio/) | Apache-2.0 | Repair | Third-opinion XRef repair via independent `repair_xref()` |

##### Vendored Libraries (bundled in `native/vendor/`, no install needed)

| Library | License | Purpose |
|---------|---------|---------|
| [simdutf](https://github.com/simdutf/simdutf) | MIT/Apache-2.0 | SIMD-accelerated UTF-8/16/32 transcoding |
| [utf8proc](https://github.com/JuliaStrings/utf8proc) | MIT | NFC normalization, case folding, grapheme clusters |
| [xxHash](https://github.com/Cyan4973/xxHash) | BSD-2 | XXH3 non-cryptographic hashing for content deduplication |

## Building

### Prerequisites

- **Java 25** - [download](https://jdk.java.net/25/)
- **g++ (C++17)** - `dnf install gcc-c++` / `apt install g++` / Xcode CLT / MSVC
- **CMake 3.20+** - `dnf install cmake` / `apt install cmake`
- **pkg-config** - `dnf install pkgconf` / `apt install pkg-config`
- **jextract 25** (optional, to regenerate FFM bindings) - [download](https://jdk.java.net/jextract/)

#### Install All Required Native Libraries

**Fedora / RHEL:**
```bash
sudo dnf install -y pcre2-devel freetype-devel harfbuzz-devel \
    libicu-devel qpdf-devel pugixml-devel libunibreak-devel \
    zlib-devel brotli-devel lcms2-devel openjpeg2-devel
```

**Ubuntu / Debian:**
```bash
sudo apt install -y libpcre2-dev libfreetype-dev libharfbuzz-dev \
    libicu-dev libqpdf-dev libpugixml-dev libunibreak-dev \
    zlib1g-dev libbrotli-dev liblcms2-dev libopenjp2-7-dev
```

**macOS (Homebrew):**
```bash
brew install pcre2 freetype harfbuzz icu4c qpdf pugixml libunibreak \
    brotli little-cms2 openjpeg
```

### Build with Real PDFium (recommended)

```bash
# 1. Download PDFium (~25 MB, gitignored)
bash native/setup-pdfium.sh

# 2. Build with CMake (auto-detects all native libraries via pkg-config)
bash native/build-real.sh

# 3. Run unit tests
./gradlew test

# 4. Run integration tests (real PDFium required)
./gradlew :jpdfium:integrationTest
```

`build-real.sh` uses CMake to compile the native bridge against real PDFium and all native libraries:

- **Bridge sources**: `jpdfium_document.cpp`, `jpdfium_render.cpp`, `jpdfium_text.cpp`, `jpdfium_redact.cpp`, `jpdfium_advanced.cpp`, `jpdfium_unicode.cpp`, `jpdfium_repair.cpp`, `jpdfium_brotli.cpp`, `jpdfium_pdfio.cpp`, `jpdfium_lcms.cpp`, `jpdfium_openjpeg.cpp`
- **Vendored sources**: `simdutf.cpp`, `utf8proc.c` (compiled in-tree)
- **Required libs**: PCRE2, FreeType, HarfBuzz, ICU4C, qpdf, pugixml, libunibreak, zlib, Brotli, lcms2, OpenJPEG (auto-detected via `pkg-config`)
- **Opt-in**: PDFio (silently skipped if absent)

The output `libjpdfium.so` and `libpdfium.so` are copied to the platform-specific natives JAR.

### Regenerate FFM Bindings (after changing `jpdfium.h`)

```bash
# Configure jextract location in ~/.gradle/gradle.properties:
#   jpdfium.jextractHome=/path/to/jextract-25
# OR set JEXTRACT_HOME env var. Defaults to ~/Downloads/jextract-25.

./gradlew :jpdfium:generateBindings
```

### Build with Stub (no PDFium - unit tests only)

For development without native libraries (e.g. testing Java-layer changes):

```bash
bash native/build-stub.sh
./gradlew test
```

The stub library provides pass-through behavior: file operations save unmodified copies, text extraction returns synthetic results, and pattern matching returns empty results. This enables all Java-layer unit tests to pass without installing PDFium or native dependencies.

## Manual Testing in IntelliJ

The `samples` package contains numbered runnable classes for quick manual verification:

```
jpdfium/src/test/java/stirling/software/jpdfium/samples/
├── S01_Render.java          -> samples-output/render/page-N.png
├── S02_TextExtract.java     -> samples-output/text-extract/report.txt
├── S03_TextSearch.java      -> stdout
├── S04_Metadata.java        -> stdout (document metadata)
├── S05_Bookmarks.java       -> stdout (bookmark tree)
├── S06_RedactWords.java     -> samples-output/redact-words/output.pdf
├── S07_Annotations.java     -> stdout (annotation listing)
├── S08_FullPipeline.java    -> samples-output/full-pipeline/
├── S09_Flatten.java         -> samples-output/flatten/
├── S10_Signatures.java      -> stdout (digital signatures)
├── S11_Attachments.java     -> stdout (embedded files)
├── S12_Links.java           -> stdout (hyperlinks)
├── S13_PageImport.java      -> samples-output/import/merged.pdf
├── S14_StructureTree.java   -> stdout (accessibility tags)
├── S15_Thumbnails.java      -> samples-output/thumbnails/
├── S16_PageEditing.java     -> samples-output/editing/edited.pdf
├── S17_NUpLayout.java       -> samples-output/nup/nup.pdf
├── S18_Repair.java          -> samples-output/repair/repaired.pdf
└── RunAllSamples.java       -> all of the above (smoke test)
```

**One-time IntelliJ setup:** Run -> Edit Configurations -> Templates -> Application -> VM Options:
```
--enable-native-access=ALL-UNNAMED
```

Then right-click any sample -> Run.

## Thread Safety

- `PdfDocument` and all `PdfPage` handles from it must be confined to one thread.
- Independent `PdfDocument` instances on separate threads are safe.
- `FPDF_InitLibrary` / `FPDF_DestroyLibrary` are called once globally by `JpdfiumLib`'s static initializer.

## Redaction Design

True redaction requires more than painting a black rectangle. JPDFium implements the **Object Fission Algorithm** for character-level text removal with zero typographic side-effects.

### How it works

1. **Direct char-to-object mapping** - each text-page character index is mapped to its owning `FPDF_PAGEOBJECT` via `FPDFText_GetTextObject` (PDFium's direct API). Characters with no direct mapping (synthetic spaces) are assigned to their nearest neighbor's object.
2. **Object classification** - for every text object that contains redacted characters:
   - *Fully contained* -> the entire object is destroyed.
   - *Partially overlapping* -> **Object Fission**: the surviving (non-redacted) characters are split into per-word fragments. Each word becomes an independent text object with:
     - The original font, size, and render mode.
     - `(a, b, c, d)` from the original transformation matrix (preserving size/rotation).
     - `(e, f)` pinned to the first character's absolute page-space origin via `FPDFText_GetCharOrigin`, bypassing font advance widths entirely.
   - This per-word positioning preserves inter-word spacing exactly, regardless of mismatches between the font's advance widths and the original TJ-array positioning.
3. **Fission validation** - after creating fragment objects, their bounds are checked. If any fragment has degenerate bounds (e.g. Type 3 custom-drawn fonts that can't be recreated via `FPDFText_SetText`), the entire fission plan is aborted and the original object is left for fallback removal.
4. **Fallback** - text objects not caught by spatial correlation (Form XObjects, degenerate bboxes) are removed if 70% or more of their area overlaps a match bbox.
5. **Visual cover** - a filled rectangle is painted over every match region.
6. **Single commit** - one `FPDFPage_GenerateContent` call bakes all modifications.

### Security levels (least -> most secure)

```
redactWordsEx(..., removeContent=false)   visual overlay only (not secure)
redactWordsEx(..., removeContent=true)    Object Fission - text removed from stream
page.flatten()                            bakes everything - prevents annotation recovery
doc.convertPageToImage(page, dpi)         rasterize - no text, vectors, or metadata survives
```

Use `convertToImage(true)` in `RedactOptions` for the nuclear option.

## License

MIT. PDFium itself is [Apache 2.0](https://pdfium.googlesource.com/pdfium/+/refs/heads/main/LICENSE).
