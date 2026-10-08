# Images

JPDFium reads and writes images through `PdfImageIO`, whose method signatures mirror `javax.imageio`.

## Reading and writing

```java
PdfImageIO.write(image, "PNG", Path.of("page-1.png"));
BufferedImage photo = PdfImageIO.read(Path.of("scan.png"));
```

| Method | Description |
|---|---|
| `read(Path)`, `read(File)`, `read(byte[])`, `read(InputStream)` | Reads the first frame. Throws `IOException` when the format cannot be decoded. |
| `readAllFrames(Path)` and the other `readAllFrames` overloads | Reads every frame, for animated or multi-page inputs. |
| `write(BufferedImage, String formatName, Path)` | Writes a single frame. See the note below on the return value. |
| `writeToBytes(BufferedImage, ImageFormat)` | Encodes to a byte array. Default quality is 90. |
| `writeMultiPageTiff(List<BufferedImage>, Path)` | Writes several images into one multi-page TIFF. |
| `combineVertically(List<BufferedImage>)` | Stacks images into one. |
| `canWrite(String formatName)` | Reports whether an encoder is available, without writing. |
| `thumbnailImage(Path, int maxDimension)` | Produces a thumbnail. |

`PdfImageIO.write` returns `true` on success and throws `IOException` if no encoder is available or the write fails. `ImageIO.write` returns `false` in the no-encoder case instead of throwing, so check `canWrite` first if you need to test availability.

Format names are case-insensitive and match the `ImageFormat` enum.

## Formats without `jpdfium-vips`

By default JPDFium delegates to `javax.imageio`, so the available formats are the ones your JDK supports. A stock JDK 25 typically provides PNG, JPEG, and BMP. Additional formats require ImageIO plugins on the classpath.

## Formats with `jpdfium-vips`

The optional `com.stirling:jpdfium-vips` module registers a libvips-backed codec through the `java.util.ServiceLoader`. No configuration is needed. Once the module and its native artifact are on the classpath, the codec handles the formats below, and JPDFium falls back to ImageIO for everything else.

```kotlin
dependencies {
    implementation("com.stirling:jpdfium-vips")
    runtimeOnly("com.stirling:jpdfium-natives-vips-linux-x64")
}
```

Verified against a stock Temurin 25 runtime:

| Format | Stock JDK read | Stock JDK write | Bundled libvips read | Bundled libvips write |
|---|---|---|---|---|
| PNG | yes | yes | yes | yes |
| JPEG | yes | yes | yes | yes |
| TIFF | yes | yes | yes | yes |
| BMP | yes | yes | not handled | not handled |
| WebP | no | no | yes | yes |
| HEIC | no | no | yes | yes |
| HEIF | no | no | yes | yes |
| AVIF | no | no | yes | yes |
| JXL | no | no | yes | yes |
| JPEG 2000 | no | no | yes | yes |

Two things the table does not show:

A third-party ImageIO plugin on your classpath can add formats JPDFium does not bundle. WebP is the common case, and `PdfImageIO` names it in the `IOException` it throws when no writer is found.

GIF is readable and writable through ImageIO on a stock JDK, but `ImageFormat` has no `GIF` constant, so `PdfImageIO` and `renderTo` cannot be asked for it by name.

Reading and writing are probed independently, so a libvips build without a given encoder still decodes that format. Where the codec is unavailable, JPDFium falls back to ImageIO.

### Platform availability

`jpdfium-natives-vips-<platform>` is published for Linux x64 and arm64, macOS x64 and arm64, and Windows x64 and arm64. There is no musl build, so Alpine and other musl runtimes rely on ImageIO formats.

## Codec precedence

When several encoders can produce a format, JPDFium tries the registered codec first and falls back to ImageIO. For decoding, it tries the codec, then a multi-frame ImageIO reader, then plain `ImageIO.read`.

A format with neither a codec nor an ImageIO writer throws `IOException` naming the format.

## Extracting images from a PDF

`PdfImageExtractor` reads the images embedded in a page. It takes the document and page handles:

```java
void extractImages(Path input) throws IOException {
    try (var document = PdfDocument.open(input);
         var page = document.page(0)) {
        for (var image : PdfImageExtractor.extract(document.rawHandle(), page.rawHandle(), 0)) {
            System.out.printf("%dx%d %s%n", image.width(), image.height(), image.suggestedExtension());
            image.save(Path.of("figure-" + image.index() + image.suggestedExtension()));
        }
    }
}
```

`ExtractedImage` exposes the raw bytes, dimensions, color space, and filter. `save(Path)` infers the format from the suggested extension; `PdfImageIO.writeToBytes` encodes an already-decoded frame instead. Use `readAllFrames` when the embedded image is animated.

Extraction is per page and read-only. It returns images as they are stored, without re-encoding, so a JPEG comes out as JPEG. `stats` reports counts and byte totals without decoding, which is the cheaper way to inventory a document.

## Converting between PDFs and images

`PdfImageConverter` handles both directions.

```java
// PDF to image files. Returns paths, not a document.
PdfToImageOptions options = PdfToImageOptions.builder().build();
List<Path> pages = PdfImageConverter.pdfToImages(document, options);

// Images to PDF. The returned document is owned by the caller.
ImageToPdfOptions imageOptions = ImageToPdfOptions.builder().build();
try (var combined = PdfImageConverter.imagesToPdf(imagePaths, imageOptions)) {
    combined.save(Path.of("combined.pdf"));
}
```

| Method | Direction |
|---|---|
| `pdfToImages(PdfDocument, PdfToImageOptions)` | PDF to a list of image files |
| `pageToImage(PdfDocument, int pageIndex, int dpi)` | One page to a `BufferedImage` |
| `pageToBytes(PdfDocument, int pageIndex, int dpi, ImageFormat)` | One page to encoded bytes |
| `thumbnail(PdfDocument, int pageIndex, int maxSize, ImageFormat)` | One page to a thumbnail |
| `convertFromPdf(PdfDocument, ImageFormat, ColorType, boolean, int)` | PDF to a single combined image, or to bytes |
| `imagesToPdf(List<Path>, ImageToPdfOptions)` | Image files to a `PdfDocument` |
| `imagesToPdfFromImages(List<BufferedImage>, ImageToPdfOptions)` | In-memory images to a `PdfDocument` |
| `imagesToPdfFromBytes`, `imagesToPdfFromStreams` | Byte arrays and streams to a `PdfDocument` |

Methods returning a `PdfDocument` transfer ownership to the caller, who must close it. The `imagesToPdf` family returns an open document; closing it discards the in-memory result unless you saved it first.

`ImageToPdfOptions` covers page size, scaling, and fit mode.

