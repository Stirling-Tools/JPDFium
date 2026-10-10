# Raster contract for OCR pipelines

`jpdf_rasterize_page_file` implements OCRmyPDF's `rasterize_pdf_page` hook natively. The reference
is OCRmyPDF 17.13.0's pypdfium rasterizer (`builtin_plugins/pypdfium.py`) on pypdfium2 5.14.0,
with Ghostscript's behaviour as the tie-breaker where the two disagree.

## Matched behaviour

- Raster DPI is rounded to 6 decimals (Python `round(x, 6)`).
- The expected size is `round(points * dpi / 72)` with Python's half-to-even rounding.
- The page is rendered at `ceil(points * dpi / 72)`, then a 1-2 px overshoot is trimmed and the
  image is cropped or white-padded to the expected size when either axis is within 2 px. Nothing
  is resampled.
- Gray and mono devices render into an 8-bit gray PDFium bitmap with `FPDF_GRAYSCALE`; others
  render packed BGR. Annotations are drawn (`FPDF_ANNOT`); form fields are not separately drawn,
  because the reference never initializes a form environment.
- By default the CropBox is widened to the MediaBox in memory before rendering.
- Mono output uses Pillow's Floyd-Steinberg integer arithmetic (`Image.convert("1")`), bit for
  bit.
- DPI metadata is the output DPI if given, otherwise the raster DPI, written as Pillow writes
  pHYs: `int(dpi / 0.0254 + 0.5)` pixels per metre.
- Output is published through an output transaction: a failure never leaves a partial PNG.

## Devices

| OCRmyPDF device | Output |
|---|---|
| `pngmono`, `pngmonod` | 1-bit gray PNG |
| `pnggray` | 8-bit gray PNG |
| `png16m` | 24-bit RGB PNG |
| `pngalpha` | 24-bit RGB PNG; the reference renders on opaque white, so it never carries alpha |
| `png256` | 24-bit RGB PNG (deviation below) |
| `jpeg`, `jpeggray`, `tiff` | Rejected: the core has no JPEG or TIFF encoder |

## Intentional deviations

Each is pinned by a parity test that asserts both the reference's output and ours.

| Case | Reference | Ours, and why |
|---|---|---|
| Rotation on a page with its own `/Rotate` | Overwrites `/Rotate` with the correction | Adds the correction to `/Rotate`, as Ghostscript does |
| Non-square raster DPI | Renders both axes at the harmonic mean, so the image and its DPI disagree | Renders each axis at its own DPI, as Ghostscript does |
| CropBox smaller than MediaBox on one axis only | Crops the MediaBox render to the CropBox size from its top-left corner | Keeps the full MediaBox render; the expected size comes from the box actually rendered |
| Quarter-turn correction | Keeps the DPI axes | Swaps the DPI axes with the image, as Ghostscript does |
| `png256` | Median-cut palette quantization | RGB; quantization only loses information OCR could use |
| `filter_vector` | Silently ignored | Rejected with `JPDF_E_UNSUPPORTED` |

With square DPI, no page `/Rotate` and matching boxes (the usual case), sizes, modes and DPI are
identical to the reference.

## Measured parity

Linux x86-64, pinned PDFium against pypdfium2 5.14.0's PDFium, for a vector page, a Helvetica
text page and a 256-step gray ramp at 72, 150 and 300 dpi, unrotated and with a quarter turn:
sizes, modes and DPI are identical; pixels are identical for every case except RGB text, where at
most 0.27% of samples differ (mean difference 0.003 of 255) because the two PDFium builds
anti-alias glyph edges slightly differently. Gray and 1-bit text are identical.

The parity suite lives in PyJPDFium (`tests/parity`); it runs the reference in a separate process
from hash-pinned sources.

## Rectification

`jpdf_bitmap_rectify` perspective-corrects a quad from a bitmap: corners in any order are reordered
top-left, top-right, bottom-right, bottom-left; collinear, concave, non-finite and sub-pixel quads
are rejected; the default output size is PaddleOCR's truncated length of the longer opposite
edges; sampling follows OpenCV's `warpPerspective` geometry (pixel centres on integers) with
nearest, bilinear or OpenCV-cubic interpolation and a white or replicated border. The result
carries the homography from output pixels back to source pixels. Interpolation is computed in
floating point, so it is close to but not bit-identical with OpenCV's fixed-point tables.
