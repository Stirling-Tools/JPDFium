# Capabilities

`jpdf_capability(name, &level)` reports operation readiness, not dependency presence.

| Level | Meaning |
|---|---|
| `JPDF_CAP_ABSENT` | Not callable in this build |
| `JPDF_CAP_IMPLEMENTED` | Callable and covered by real-native tests |
| `JPDF_CAP_QUALIFIED` | Passed its parity or golden gate |

| Name | Level |
|---|---|
| `runtime.lifecycle` | implemented |
| `pdf.open` | implemented |
| `pdf.render` | implemented; qualification needs the OCRmyPDF raster parity suite |
| `image.png_write` | implemented |
| `output.transaction` | implemented |
| `legacy.guards` | implemented |
| `pdf.raster` | implemented; matches the OCRmyPDF reference on linux-x64 ([RASTER.md](RASTER.md)) |
| `image.rectify` | implemented |
| `pdf.text` | implemented (API 1.2) |
| `image.load` | implemented (API 1.3): PNG, read as OpenCV's `IMREAD_COLOR` reads it |
| `text_layer.write` | implemented (API 1.2); checked against OCRmyPDF's graft ([TEXT_LAYER.md](TEXT_LAYER.md)) |
| `text_layer.bidi` | implemented (API 1.4): right-to-left and mixed lines, qualified against four extractors |
| `text_layer.quad` | implemented (API 1.4): rotated and skewed lines |

The OCR module answers for its own names through `jpdf_ocr_capability`: `ocr.recognize` is
implemented and matches the official PaddleOCR pipeline ([OCR.md](OCR.md)); `ocr.word_geometry`
is absent.

Reserved names, absent until their phase lands: `pdf.optimize`, `svg.render`.
`fonts.shape_explicit` and `fonts.subset` stay absent: the glyphless text layer needs neither
([TEXT_LAYER.md](TEXT_LAYER.md)). Unknown names report absent.
