# Rendering

## Rendering to a file

`PdfPage.renderTo` infers the output format from the file extension and takes a DPI value.

```java
Path renderFirstPage(Path input, Path output) throws IOException {
    try (var document = PdfDocument.open(input);
         var page = document.page(0)) {
        page.renderTo(output, 150);
    }
    return output;
}
```

`renderTo` has several overloads. The two-argument form above infers the format from the file extension. Use the five-argument form when you need to set the format, encoder quality, or color type explicitly:

```java
page.renderTo(Path.of("page-1"), 150, ImageFormat.PNG, 90, ColorType.RGB);
```

The format is selected by the enum rather than by the file name, so the output path does not need a matching extension. Other overloads accept a `File` or an `OutputStream`; the `OutputStream` forms require an explicit format.

`renderTo(Path)` without a DPI value uses 150. The two-argument form defaults to quality 90 and opaque RGB.

### Recognized extensions

| Extension | Format |
|---|---|
| `png` | PNG |
| `jpg`, `jpeg`, `jpe`, `jfif` | JPEG |
| `tif`, `tiff` | TIFF |
| `webp` | WebP |
| `bmp`, `dib` | BMP |
| `heic` | HEIC, with `jpdfium-vips` |
| `heif` | HEIF, with `jpdfium-vips` |
| `avif` | AVIF, with `jpdfium-vips` |
| `jxl` | JXL, with `jpdfium-vips` |
| `jp2`, `j2k`, `j2c`, `jpeg2000`, `jpg2` | JPEG 2000, with `jpdfium-vips` |

An unrecognized extension throws `IllegalArgumentException`. There is no `gif` constant in `ImageFormat`, so `gif` is rejected even though ImageIO can decode it. Formats that require `jpdfium-vips` fall back to `javax.imageio` when that module is absent. If ImageIO also has no encoder, the write throws `IOException` naming the format.

## Rendering to an image

`PdfRenderer` provides a PDFBox-style API returning `BufferedImage`.

```java
var renderer = new PdfRenderer(document);
BufferedImage image = renderer.renderImageWithDPI(0, 150);
```

Commonly used methods:

| Method | Description |
|---|---|
| `renderImage(int pageIndex)` | Renders at 72 DPI. |
| `renderImage(int pageIndex, ColorType)` | Renders at 72 DPI with an explicit color type. |
| `renderImage(int pageIndex, float scale)` | Renders at 72 DPI multiplied by `scale`. |
| `renderImageWithDPI(int pageIndex, float dpi)` | Renders at an explicit DPI over a white background. |
| `renderImageWithDPI(int pageIndex, float dpi, boolean transparent)` | Renders with a transparent background. |
| `renderToFile(int pageIndex, Path, int dpi)` | Writes a single page; the format comes from the extension. Defaults to 150 DPI. |
| `renderToBytes(int pageIndex, int dpi, ImageFormat)` | Returns the encoded bytes. |
| `renderImages(float dpi)` | Returns one image per page. |
| `renderToMultiPageTiff(Path, float)` | Writes all pages into a single multi-page TIFF. |
| `renderCombinedImage(float)` | Returns a single image with all pages stitched vertically. |

## DPI and pixel dimensions

Pixel dimensions are computed as `page_dimension_in_points * dpi / 72`, rounded to the nearest integer with halves rounding up. The DPI value itself is rounded to an integer first, so a value of `149.6` behaves as `150`.

A DPI of zero or less is rejected with `IllegalArgumentException`, and the effective DPI is clamped to at least 1.

## Color and transparency

- The default background is opaque white. Pass `transparent = true`, or set `RenderOptions`, to render over a fully transparent background instead.
- The native pixel buffer is straight, non-premultiplied RGBA.
- `RenderResult.toBufferedImage` produces `TYPE_INT_RGB` by default and `TYPE_INT_ARGB` when `ColorType.ARGB` is requested. `ColorType` also supports `GRAY` and `BINARY`.
- `ImageFormat.supportsTransparency()` reports whether a format carries an alpha channel. It returns false for JPEG, BMP, and JPEG 2000.
- When `ColorType.ARGB` is requested for a format that does not support transparency, JPDFium renders against the opaque white background instead of producing a transparent image.

## Page rotation

Rendering honors the page's `/Rotate` entry. Rotations of 90 and 270 degrees swap the output width and height.

Use `PdfDocument.getPageRotation(pageIndex)` to read the rotation value, and `PdfPageBoxes` to inspect and adjust page boxes.

## Renderer selection

JPDFium ships with two renderers:

- **Skia**, the default. This is the renderer PDFium uses in Chromium.
- **AGG**, an alternative renderer.

Select one with `-Djpdfium.renderer=skia`, `-Djpdfium.renderer=agg`, or `-Djpdfium.renderer=auto`. The environment variable `JPDFIUM_RENDERER` is used when the property is absent. `auto` prefers Skia and falls back to AGG, and is the default when the property is unset. See [configuration.md](configuration.md).

The active renderer can be queried at runtime:

```java
JpdfiumLib.isSkiaActive();
```

The value is resolved once per JVM, during class initialization.

## Render limits

Two independent limits apply:

1. `jpdfium.maxRenderPixels`, a configurable policy limit that defaults to unlimited. Exceeding it throws `JPDFiumException`.
2. A hard native ceiling of 268,435,456 pixels per page, which always applies. Exceeding it throws `PdfCorruptException`.

A page that renders correctly at low DPI but fails at high DPI is usually hitting one of these. Reduce the DPI, or crop and render regions instead of whole pages.

