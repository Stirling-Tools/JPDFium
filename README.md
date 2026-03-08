# JPDFium

High-performance Java 25 FFM bindings for PDFium (EmbedPDF fork).

JPDFium provides a safe, ergonomic Java API for PDF rendering, text extraction,
and true content-stripping redaction - powered by the [EmbedPDF PDFium fork](https://github.com/embedpdf/pdfium)
via Java 25's Foreign Function & Memory (FFM) API.

## Features

- **PDF Rendering** - Render pages to RGBA bitmaps at any DPI
- **Text Extraction** - Structured page -> line -> word -> character extraction with font/position metadata
- **Text Search** - Literal string search via PDFium's native search engine
- **True Redaction** - Removes content from the PDF stream (not a cosmetic overlay); region, regex-pattern, and word-list redaction with full Unicode support
- **PII Redaction Pipeline** - PCRE2 JIT pattern engine, FlashText NER, HarfBuzz glyph-level redaction, font normalization, XMP metadata stripping, semantic coreference expansion - all native via FFM
- **Native Redaction** - Built-in `EPDFAnnot_ApplyRedaction` for shading, JBIG2, transparent PNG, Form XObject redaction (complementary to Object Fission)
- **Document Metadata** - Read title, author, subject, creator, dates, permissions, page labels
- **Bookmarks** - Full outline/TOC tree traversal with nested bookmarks, destinations, and URI actions
- **Annotations** - Full CRUD: list, create, modify, delete annotations with type/rect/color/flags/contents
- **EmbedPDF Annotation Extensions** - Opacity, rotation, appearance generation, border style/dash patterns, reply types, icons, overlay text, per-annotation flattening
- **Hyperlinks** - Enumerate and hit-test page links with action type and URI resolution
- **Digital Signatures** - Read-only inspection of signature metadata (sub-filter, reason, time, contents, DocMDP)
- **Embedded Attachments** - List, extract, add, and delete embedded file attachments
- **Page Thumbnails** - Extract pre-rendered decoded/raw thumbnail data from pages
- **Structure Tree** - Accessibility tagged structure (headings, paragraphs, tables) traversal
- **Page Import/Export** - Import pages between documents, copy viewer preferences, delete pages
- **Page Editing** - Create/modify page objects (text, rectangles, paths), set colors/transforms
- **PDF to Images** - Convert pages to PNG, JPEG, TIFF, WEBP, BMP with configurable DPI and quality
- **Images to PDF** - Combine images into PDFs (scanner workflow, photo albums) with page size and positioning options
- **N-up Layout** - Tile multiple pages onto one sheet (2-up, 4-up, 6-up, 9-up) for booklet printing
- **PDF Merge** - Merge multiple PDFs from open documents or file paths into one, with proper internal reference handling
- **PDF Split** - Split PDFs by strategy: every N pages, by bookmark boundaries, single pages, extract specific pages or ranges
- **Watermarking** - Text and image watermarks with builder pattern: position, rotation, opacity, font, size, color
- **Page Geometry** - Crop (CropBox), rotate, resize (MediaBox), and read page geometry at the page level
- **Header/Footer/Bates** - Add headers, footers, and Bates numbering with template variables ({page}, {pages}, {date})
- **Security Hardening** - Remove JavaScript, embedded files, and action annotations (builder pattern); sanitize integration with PdfRepair
- **Table Extraction** - Geometric table detection from text positions with CSV/JSON export
- **PDF Repair** - Multi-stage cascade repair: Brotli transcoding, qpdf recovery, PDFio fallback, ICC/JPX validation, optional security sanitization
- **Secure PDF-Image** - Convert pages to rasterized images, stripping all selectable text and vector content
- **Document Info Audit** - One-call comprehensive PDF analysis: version, tagged status, encryption, page count, images, form fields, blank pages
- **Form Field Reading** - Extract form fields (text, button, combo, list, signature) with values, options, and checked state
- **Image Extraction** - Extract embedded images from PDF pages with metadata (dimensions, color space, compression filter)
- **Page Objects Enumeration** - List all page objects (text, image, path, shading, form) with bounds, colors, transform matrix, transparency
- **Encryption/Decryption** - Native AES-256 in-memory encryption + qpdf file-to-file operations, with user/owner passwords and permission control
- **PDF Linearization** - Fast web view optimization for browser loading (requires qpdf)
- **Stream Optimization** - Object stream compression and cross-reference streams for file size reduction (requires qpdf)
- **Version Conversion** - Read and set PDF file version (1.4, 1.5, 1.6, 1.7, 2.0)
- **Page Overlay** - Stamp pages from one PDF onto another (watermark, letterhead)
- **Page Interleaving** - Merge front/back scans into proper page order (duplex scanning workflow)
- **Named Destinations** - Lookup bookmark-like named destinations with view type and coordinates
- **Web Links** - Enumerate URIs and link annotations for web crawling
- **Page Boxes** - Read/set all five PDF boxes: MediaBox, CropBox, BleedBox, TrimBox, ArtBox
- **Blank Page Detection** - Detect blank pages via text content and visual uniformity analysis
- **JavaScript Inspection** - Audit document-level and annotation-level JavaScript for security
- **Bounded Text Extraction** - Extract text blocks with bounding boxes, font name, and size
- **Advanced Render Options** - Grayscale, print mode, dark mode color scheme, custom DPI
- **Vector Path Drawing** - Draw rectangles, lines, bezier curves with fill/stroke colors and line styles
- **Rotation Flattening** - Apply rotation transform to page content, removing rotation metadata
- **Page Normalization** - Load pages with rotation normalized, get unrotated page sizes
- **Annotation Rendering** - Render individual annotations to bitmaps
- **Cross-Platform** - Linux x64/arm64, macOS x64/arm64, Windows x64
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
| **Direct FFM Bindings** | `panama/PdfiumBindings`, `panama/FfmHelper`, `panama/EmbedPdf*Bindings` | Hand-crafted MethodHandle bindings to PDFium's C API and EmbedPDF extensions |
| **Bridge FFM Bindings** | `panama/NativeLoader`, `panama/JpdfiumH` | Auto-generated jextract interfaces to the C++ bridge |
| **Native Bridge** | `libjpdfium.so` | C/C++ bridge for complex operations (redaction, font normalization) |
| **Core Engine** | `libpdfium.so` | [EmbedPDF PDFium fork](https://github.com/embedpdf/pdfium) (based on Google PDFium) |

JPDFium uses a hybrid architecture: a C++ bridge (`libjpdfium`) for complex operations (redaction, font normalization) and direct FFM bindings (`PdfiumBindings`, `EmbedPdfAnnotationBindings`, `EmbedPdfDocumentBindings`) to PDFium's C API for document inspection features (metadata, bookmarks, annotations, signatures, encryption, etc.).

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

// Additional Document Inspection APIs (stirling.software.jpdfium.doc)

// Document Info Audit
DocInfo.analyze(doc, fileSize)             // -> DocInfo (version, tagged, encrypted, pages, images, forms, blank pages)
info.summary()                             // -> String human-readable summary
info.toJson()                              // -> JSON string

// Render Options (advanced rendering)
RenderOptions.builder()
    .dpi(150)
    .grayscale(true)
    .printing(true)
    .colorScheme(new ColorScheme(...))     // dark mode colors
    .build()
    .render(rawPage, width, height)        // -> BufferedImage

// Form Fields
PdfFormReader.readAll(rawDoc, pages)       // -> List<FormField>
FormField.type()                           // -> FormFieldType (text, button, combo, list, signature)
FormField.name()                           // -> String
FormField.value()                          // -> String
FormField.checked()                        // -> boolean
FormField.options()                        // -> List<String>

// Image Extraction
PdfImageExtractor.stats(rawDoc, rawPage)   // -> ImageStats (totalImages, totalRawBytes, formatBreakdown)
PdfImageExtractor.extract(rawDoc, rawPage, pageIndex) // -> List<ExtractedImage>
ExtractedImage.save(Path)                  // save to file
ExtractedImage.suggestedExtension()        // -> ".png", ".jpg", etc.
ExtractedImage.width()                     // -> int
ExtractedImage.height()                    // -> int
ExtractedImage.colorSpace()                // -> String
ExtractedImage.filter()                    // -> compression filter name

// Page Objects Enumeration
PdfPageObjects.list(rawPage)               // -> List<PageObject>
PdfPageObjects.summarize(rawPage)          // -> PageContentSummary
PageObject.type()                          // -> PageObjectType (text, image, path, shading, form)
PageObject.bounds()                        // -> Rect
PageObject.fillR/G/B/A()                   // -> int color components
PageObject.transform()                     // -> double[6] matrix
PageObject.hasTransparency()               // -> boolean

// Encryption / Decryption
PdfEncryption.isEncrypted(rawDoc)          // -> boolean
PdfEncryption.securityRevision(rawDoc)     // -> int
PdfEncryption.permissions(rawDoc)          // -> long

// Native in-memory encryption
PdfEncryption.setEncryption(rawDoc, userPass, ownerPass, permissions)
PdfEncryption.removeEncryption(rawDoc)
PdfEncryption.unlockOwner(rawDoc, ownerPass) // -> boolean
PdfEncryption.isOwnerUnlocked(rawDoc)      // -> boolean

// qpdf file-to-file encryption (alternative for file-based workflows)
PdfEncryption.encrypt(input, output, userPass, ownerPass)
PdfEncryption.decrypt(input, output, userPass)
PdfEncryption.isQpdfAvailable()            // -> boolean

// Linearization (fast web view, requires qpdf)
PdfLinearizer.linearize(input, output)
PdfLinearizer.isLinearized(pdfBytes)       // -> boolean
PdfLinearizer.isSupported()                // -> boolean (qpdf available)

// Stream Optimization (requires qpdf)
PdfStreamOptimizer.optimize(input, output) // full: object streams, xref streams
PdfStreamOptimizer.compact(input, output)  // basic: remove unreferenced objects
PdfStreamOptimizer.isSupported()           // -> boolean

// Version Converter
PdfVersionConverter.getVersion(rawDoc)     // -> PdfVersion (V1_4, V1_5, V1_6, V1_7, V2_0)
PdfVersionConverter.saveWithVersion(rawDoc, version, path)
PdfVersionConverter.saveWithVersionToBytes(rawDoc, version) // -> byte[]

// Overlay (stamp pages from one PDF onto another)
PdfOverlay.overlayPage(rawDest, rawOverlay, overlayPageNum, insertIndex) // -> boolean
PdfOverlay.overlayAll(rawDest, rawOverlay, destPageCount, overlayPageCount) // -> int count
PdfOverlay.isSupported()                   // -> boolean

// Page Interleaver (duplex scan merge)
PdfPageInterleaver.interleave(rawDest, rawDoc1, rawDoc2, reverseSecond) // -> int pages
PdfPageInterleaver.interleave(rawDest, rawDoc1, rawDoc2) // -> int (no reverse)

// Named Destinations
PdfNamedDestinations.list(rawDoc)          // -> List<NamedDestination>
NamedDestination.name()                    // -> String
NamedDestination.pageIndex()               // -> int
NamedDestination.viewType()                // -> ViewType (XYZ, Fit, FitH, FitV, FitR, FitB, FitBH, FitBV)

// Web Links
PdfWebLinks.list(rawDoc, rawPage)          // -> List<WebLink>
WebLink.uri()                              // -> String
WebLink.rect()                             // -> Rect

// Page Boxes (all five PDF boxes)
PdfPageBoxes.getAll(rawPage)               // -> PageBoxes (mediaBox, cropBox, bleedBox, trimBox, artBox)
PdfPageBoxes.setCropBox(rawPage, Rect)
PdfPageBoxes.setMediaBox(rawPage, Rect)
// PageBoxes getters return Optional<Rect>

// Blank Page Detector
BlankPageDetector.isBlankText(rawPage)     // -> boolean (no text chars)
BlankPageDetector.isBlank(rawPage, width, height, threshold) // -> boolean (visual + text)

// JavaScript Inspector
PdfJavaScriptInspector.inspect(rawDoc, pages) // -> JavaScriptReport
JavaScriptReport.documentScripts()         // -> List<JsAction>
JavaScriptReport.annotationScripts()       // -> List<JsAction>
JsAction.name()                            // -> String
JsAction.script()                          // -> String (JavaScript source)
JsAction.location()                        // -> JsLocation (DOCUMENT, ANNOTATION)

// Annotation Builder
PdfAnnotationBuilder.on(rawPage)
    .type(AnnotationType.HIGHLIGHT)        // HIGHLIGHT, UNDERLINE, STRIKEOUT, INK, SQUARE, CIRCLE, FREETEXT, LINE, STAMP, LINK, REDACT
    .rect(x, y, w, h)
    .color(r, g, b, a)
    .contents("note text")
    .uri("https://...")                    // for LINK type
    .borderWidth(1f)
    .opacity(128)                          // 0-255
    .rotation(45f)                         // degrees
    .borderStyle(6)                        // 0=unknown..6=cloudy
    .textAlignment(1)                      // 0=left,1=center,2=right
    .icon(2)                               // icon code
    .overlayText("REDACTED")               // for REDACT type
    .generateAppearance()                  // auto-generate AP
    .build()                               // -> int annotation index

// EmbedPDF Annotation Extensions
EmbedPdfAnnotations.setOpacity(rawPage, idx, 128)
EmbedPdfAnnotations.setRotation(rawPage, idx, 45f)
EmbedPdfAnnotations.generateAppearance(rawPage, idx)
EmbedPdfAnnotations.setOverlayText(rawPage, idx, "REDACTED")
EmbedPdfAnnotations.applyRedaction(rawPage, idx)     // native redact: shading, JBIG2, etc.
EmbedPdfAnnotations.applyAllRedactions(rawPage)       // apply all redacts on page
EmbedPdfAnnotations.flatten(rawPage, idx)             // single-annotation flatten
EmbedPdfAnnotations.setBorderStyle(rawPage, idx, 1, 2f) // solid, 2pt wide
EmbedPdfAnnotations.setIcon(rawPage, idx, 2)
EmbedPdfAnnotations.setIntent(rawPage, idx, "FreeTextCallout")

// Path Drawer (vector graphics)
PdfPathDrawer.on(rawDoc, rawPage)
    .beginPath(x, y)
    .moveTo(x, y)
    .lineTo(x, y)
    .bezierTo(x1, y1, x2, y2, x3, y3)
    .closePath()
    .rect(x, y, w, h)
    .fillColor(r, g, b, a)
    .strokeColor(r, g, b, a)
    .strokeWidth(w)
    .fillAlternate()                       // or fillWinding()
    .stroke(true)
    .lineCap(0)                            // 0=butt, 1=round, 2=square
    .lineJoin(0)                           // 0=miter, 1=round, 2=bevel
    .draw()                                // commit path to page

// Flatten Rotation (apply rotation transform to content)
PdfFlattenRotation.flatten(rawPage)        // -> int original rotation degrees

// Bounded Text Extraction
PdfBoundedText.extract(rawPage)            // -> List<PdfBoundedText.BoundedTextBlock>
BoundedTextBlock.text()                    // -> String
BoundedTextBlock.bounds()                  // -> Rect
BoundedTextBlock.fontName()                // -> String
BoundedTextBlock.fontSize()                // -> float
```

### `PdfMerge`
```java
// Merge open documents
PdfDocument merged = PdfMerge.merge(List.of(doc1, doc2, doc3));
merged.save(Path.of("merged.pdf"));
merged.close();

// Merge from file paths (handles open/close internally)
PdfDocument merged = PdfMerge.mergeFiles(List.of(
    Path.of("a.pdf"), Path.of("b.pdf"), Path.of("c.pdf")));
merged.save(Path.of("merged.pdf"));
merged.close();
```

### `PdfSplit`
```java
// Split every N pages
List<PdfDocument> parts = PdfSplit.split(doc, PdfSplit.SplitStrategy.everyNPages(5));

// Split into single pages
List<PdfDocument> pages = PdfSplit.split(doc, PdfSplit.SplitStrategy.singlePages());

// Split by bookmark boundaries
List<PdfDocument> chapters = PdfSplit.split(doc, PdfSplit.SplitStrategy.byBookmarks());

// Extract specific pages (zero-based)
PdfDocument extracted = PdfSplit.extractPages(doc, Set.of(0, 3, 7));

// Extract a contiguous range (inclusive, zero-based)
PdfDocument range = PdfSplit.extractPageRange(doc, 2, 5);
```

### Watermarking
```java
import stirling.software.jpdfium.transform.Watermark;
import stirling.software.jpdfium.transform.WatermarkApplier;

// Text watermark
Watermark wm = Watermark.text()
    .text("CONFIDENTIAL")
    .fontSize(48)
    .fontName("Helvetica")
    .opacity(0.3f)
    .color(0xCC0000)     // RGB
    .rotation(45)
    .position(Watermark.Position.CENTER)
    .build();
WatermarkApplier.apply(doc, wm);
doc.save(Path.of("watermarked.pdf"));

// Image watermark
Watermark imgWm = Watermark.image()
    .imagePath(Path.of("logo.png"))
    .opacity(0.5f)
    .position(Watermark.Position.BOTTOM_RIGHT)
    .build();
WatermarkApplier.apply(doc, imgWm);
```

### `PdfPageGeometry`
```java
import stirling.software.jpdfium.transform.PdfPageGeometry;
import stirling.software.jpdfium.model.Rect;
import stirling.software.jpdfium.model.PageSize;

// Crop (set visible area with 1-inch margins)
PdfPageGeometry.setCropBox(doc, 0, new Rect(72, 72, 468, 648));
Rect crop = PdfPageGeometry.getCropBox(doc, 0);

// Rotate
PdfPageGeometry.setRotation(doc, 0, 90);  // 0, 90, 180, 270
int rotation = PdfPageGeometry.getRotation(doc, 0);

// Resize all pages to A4
PdfPageGeometry.resizeAll(doc, PageSize.A4);

// Rotate all pages
PdfPageGeometry.rotateAll(doc, 180);
```

### Header/Footer/Bates Numbering
```java
import stirling.software.jpdfium.transform.HeaderFooter;
import stirling.software.jpdfium.transform.HeaderFooterApplier;

// Add header and footer with template variables
HeaderFooter hf = HeaderFooter.builder()
    .header("My Document - Page {page} of {pages}")
    .footer("{date}")
    .fontSize(10)
    .fontName("Helvetica")
    .margin(36)
    .build();
HeaderFooterApplier.apply(doc, hf);

// Bates numbering
HeaderFooter bates = HeaderFooter.builder()
    .footer("BATES-{bates}")
    .batesStart(1000)
    .batesDigits(6)   // -> BATES-001000, BATES-001001, ...
    .fontSize(8)
    .build();
HeaderFooterApplier.apply(doc, bates);
```

### `PdfSecurity`
```java
import stirling.software.jpdfium.doc.PdfSecurity;

// Builder pattern: select what to remove
PdfSecurity.Result result = PdfSecurity.builder()
    .removeJavaScript(true)
    .removeEmbeddedFiles(true)
    .removeActions(true)
    .build()
    .execute(doc);
System.out.println(result.summary());
// "Removed: 3 JS annotations, 1 embedded files, 5 action annotations"

// Or remove everything at once
PdfSecurity.Result r = PdfSecurity.builder().all().build().execute(doc);

// Static convenience method
String summary = PdfSecurity.sanitize(doc);

// Integrated with PdfRepair
RepairResult repaired = PdfRepair.builder()
    .input(pdfBytes)
    .all()
    .sanitize(true)     // security sanitization after repair
    .build()
    .execute();
```

### `PdfTableExtractor`
```java
import stirling.software.jpdfium.text.PdfTableExtractor;
import stirling.software.jpdfium.text.Table;

// Extract tables from a page
List<Table> tables = PdfTableExtractor.extract(doc, pageIndex);
for (Table table : tables) {
    System.out.printf("Table: %d rows x %d cols%n", table.rowCount(), table.colCount());
    System.out.println(table.toCsv());
    System.out.println(table.toJson());
    String[][] grid = table.asGrid();
}
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

### `PdfImageConverter`
```java
// PDF to images (PNG, JPEG, TIFF, WEBP, BMP)
PdfToImageOptions opts = PdfToImageOptions.builder()
    .format(ImageFormat.PNG)
    .dpi(300)
    .quality(90)
    .transparent(false)
    .pages(Set.of(0, 1, 2))   // specific pages
    .build();
List<Path> files = PdfImageConverter.pdfToImages(doc, opts, outputDir);

// Single page to bytes (for web APIs)
byte[] jpeg = PdfImageConverter.pageToBytes(doc, 0, 150, ImageFormat.JPEG, 85, false);

// Thumbnail generation
byte[] thumb = PdfImageConverter.thumbnail(doc, 0, 200, ImageFormat.JPEG);

// Images to PDF (scanner workflow, photo albums)
ImageToPdfOptions imgOpts = ImageToPdfOptions.builder()
    .pageSize(PageSize.A4)
    .position(Position.CENTER)
    .margin(36)
    .compress(true)
    .imageQuality(85)
    .autoRotate(true)
    .build();
PdfDocument doc = PdfImageConverter.imagesToPdfFromImages(images, imgOpts);
```

### `PdfRepair`
```java
// Inspect PDF for damage (non-destructive)
String diagnostics = PdfRepair.inspect(pdfBytes);

// Full repair cascade: Brotli -> qpdf -> PDFio fallback -> ICC/JPX validation
RepairResult result = PdfRepair.builder()
    .input(pdfBytes)
    .all()                          // enable all repair stages
    .writeDiagnostics(true)         // include JSON diagnostics
    .build()
    .execute();

if (result.isUsable()) {
    byte[] repaired = result.repairedPdf();
    System.out.println("Status: " + result.status());
    System.out.println("Diagnostics: " + result.diagnosticJson());
}
```

### `NUpLayout`
```java
// Four-up on A4 landscape
NUpLayout.from(doc).grid(2, 2).a4Landscape().build().save(outputPath);

// Six-up on US Letter landscape
NUpLayout.from(doc).grid(3, 2).letterLandscape().build().save(outputPath);

// Custom page size (A3 landscape: 1190 x 842 pt)
NUpLayout.from(doc).grid(4, 2).pageSize(1190, 842).build().save(outputPath);

// Get as bytes instead of saving
byte[] pdf = NUpLayout.from(doc).grid(2, 2).a4Landscape().build().toBytes();
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

All libraries are MIT or Apache-2.0 compatible:

| Library | License | Purpose |
|---------|---------|---------|
| [PCRE2](https://www.pcre.org/) | BSD-3 | JIT-compiled regex engine |
| [FreeType](https://freetype.org/) | FTL/MIT | Font parsing, classification, glyph width calculation |
| [HarfBuzz](https://harfbuzz.github.io/) | MIT | Text shaping, `hb-subset` font subsetting |
| [ICU4C](https://icu.unicode.org/) | Unicode License | NFC normalization, BiDi reordering, sentence segmentation |
| [qpdf](https://qpdf.sourceforge.io/) | Apache-2.0 | PDF structure manipulation, stream replacement |
| [pugixml](https://pugixml.org/) | MIT | XMP metadata XML parsing |
| [libunibreak](https://github.com/adah1972/libunibreak) | zlib | Grapheme cluster boundaries |

## Building

### Prerequisites

- **Java 25** - [download](https://jdk.java.net/25/)
- **g++ (C++17)** - `dnf install gcc-c++` / `apt install g++` / Xcode CLT / MSVC
- **CMake 3.16+** - `dnf install cmake` / `apt install cmake`
- **jextract 25** (optional, to regenerate FFM bindings) - [download](https://jdk.java.net/jextract/)

#### Native Library Dependencies

For full PII redaction pipeline support, install these development packages:

**Fedora / RHEL:**
```bash
sudo dnf install -y pcre2-devel freetype-devel harfbuzz-devel \
    libicu-devel qpdf-devel pugixml-devel libunibreak-devel
```

**Ubuntu / Debian:**
```bash
sudo apt install -y libpcre2-dev libfreetype-dev libharfbuzz-dev \
    libicu-dev libqpdf-dev libpugixml-dev libunibreak-dev
```

### Quick Start with Gradle (Recommended)

**Quick Try-Out** (no PDFium or native dependencies needed):
```bash
./gradlew quickTry
```
This builds the stub bridge and runs all 50 samples. Perfect for first-time testing.

**Full Build with Real PDFium** (one command):
```bash
./gradlew fullBuildAndTest
```
This downloads PDFium, builds the real native bridge, runs all tests, and executes all 50 samples.

### Manual Build Steps

#### Build with Real PDFium (recommended for production)

```bash
# 1. Download PDFium (~25 MB, gitignored)
./gradlew downloadPdfium

# 2. Build with CMake (auto-detects all native libraries via pkg-config)
./gradlew buildRealBridge

# 3. Run unit tests
./gradlew test

# 4. Run integration tests (real PDFium required)
./gradlew :jpdfium:integrationTest

# 5. Run all 50 samples
./gradlew runAllSamples
```

`build-real.sh` uses CMake to compile the native bridge against real PDFium and all available native libraries, then copies `libjpdfium.so` and `libpdfium.so` to the platform-specific natives JAR. The bridge consists of multiple source files: `jpdfium_document.cpp` (core document operations), `jpdfium_render.cpp` (rendering), `jpdfium_text.cpp` (text extraction), `jpdfium_redact.cpp` (redaction), `jpdfium_advanced.cpp` (PII pipeline), `jpdfium_repair.cpp` (PDF repair), `jpdfium_image.cpp` (image conversion), plus supporting modules for Brotli, ICC, OpenJPEG, PDFio, and Unicode handling. Native libraries are auto-detected via `pkg-config`; any missing libraries are silently skipped (the corresponding features return empty results at runtime).

#### Build with Stub (no PDFium - unit tests only)

For development without native libraries (e.g. testing Java-layer changes):

```bash
./gradlew buildStubBridge
./gradlew test
```

The stub provides pass-through behavior for Java-layer testing only.

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
  - S01_Render.java          -> samples-output/render/page-N.png
  - S02_TextExtract.java     -> samples-output/text-extract/report.txt
  - S03_TextSearch.java      -> stdout
  - S04_Metadata.java        -> stdout (document metadata)
  - S05_Bookmarks.java       -> stdout (outline tree)
  - S06_RedactWords.java     -> samples-output/redact-words/output.pdf
  - S07_Annotations.java     -> stdout (annotation list)
  - S08_FullPipeline.java    -> samples-output/full-pipeline/
  - S09_Flatten.java         -> samples-output/flatten/
  - S10_Signatures.java      -> stdout (signature metadata)
  - S11_Attachments.java     -> samples-output/attachments/
  - S12_Links.java           -> stdout (hyperlinks)
  - S13_PageImport.java      -> samples-output/page-import/
  - S14_StructureTree.java   -> stdout (accessibility tree)
  - S15_Thumbnails.java      -> samples-output/thumbnails/ (embedded)
  - S16_PageEditing.java     -> samples-output/page-editing/
  - S17_NUpLayout.java       -> samples-output/nup-layout/
  - S18_Repair.java          -> samples-output/repair/
  - S19_PdfToImages.java     -> samples-output/pdf-to-images/
  - S20_ImagesToPdf.java     -> samples-output/images-to-pdf/
  - S21_Thumbnails.java      -> samples-output/thumbnails/ (generated)
  - S22_MergeSplit.java      -> samples-output/merge-split/
  - S23_Watermark.java       -> samples-output/watermark/
  - S24_TableExtract.java    -> samples-output/tables/ (stdout)
  - S25_PageGeometry.java    -> samples-output/page-geometry/
  - S26_HeaderFooter.java    -> samples-output/header-footer/
  - S27_Security.java        -> samples-output/security/
  - S28_DocInfo.java         -> stdout (comprehensive PDF audit)
  - S29_RenderOptions.java   -> samples-output/render-options/ (grayscale, print, dark mode)
  - S30_FormReader.java      -> stdout (form field extraction)
  - S31_ImageExtract.java    -> samples-output/images-extract/ (embedded images)
  - S32_PageObjects.java     -> stdout (page object enumeration)
  - S33_Encryption.java      -> samples-output/security/ (encrypt/decrypt)
  - S34_Linearizer.java      -> samples-output/linearize/ (fast web view)
  - S35_Overlay.java         -> samples-output/overlay/ (stamp pages)
  - S36_AnnotationBuilder.java -> samples-output/annotation-builder/ (create annotations)
  - S37_PathDrawer.java      -> samples-output/path-drawer/ (vector graphics)
  - S38_JavaScriptInspector.java -> stdout (JS audit)
  - S39_WebLinks.java        -> stdout (web link enumeration)
  - S40_PageBoxes.java       -> samples-output/page-boxes/ (MediaBox, CropBox, etc.)
  - S41_VersionConverter.java -> samples-output/version-converter/ (PDF version)
  - S42_BoundedText.java     -> stdout (bounded text extraction)
  - S43_StreamOptimizer.java -> samples-output/stream-optimizer/ (file size reduction)
  - S44_FlattenRotation.java -> samples-output/flatten-rotation/ (apply rotation)
  - S45_PageInterleaver.java -> samples-output/page-interleaver/ (duplex scan merge)
  - S46_NamedDestinations.java -> stdout (named destination lookup)
  - S47_BlankPageDetector.java -> stdout (blank page detection)
  - S48_EmbedPdfAnnotations.java -> samples-output/embedpdf-annotations/ (EmbedPDF fork extensions)
  - S49_NativeEncryption.java -> samples-output/native-encryption/ (in-memory AES-256)
  - S50_NativeRedaction.java -> samples-output/native-redaction/ (EmbedPDF fork redaction)
  - RunAllSamples.java       -> all 50 samples (smoke test)
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
4. **Fallback** - text objects not caught by spatial correlation (Form XObjects, degenerate bboxes) are removed if >= 70% of their area overlaps a match bbox.
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
