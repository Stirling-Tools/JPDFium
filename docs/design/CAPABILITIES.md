# Capabilities

`jpdf_capability(name, &level)` reports operation readiness, not dependency presence.

| Level | Meaning |
|---|---|
| `JPDF_CAP_ABSENT` | Not callable in this build |
| `JPDF_CAP_IMPLEMENTED` | Callable and covered by real-native tests |
| `JPDF_CAP_QUALIFIED` | Passed its parity or golden gate |

| Name | Level in 1.0 |
|---|---|
| `runtime.lifecycle` | implemented |
| `pdf.open` | implemented |
| `pdf.render` | implemented; qualification needs the OCRmyPDF raster parity suite |
| `image.png_write` | implemented |
| `output.transaction` | implemented |
| `legacy.guards` | implemented |
| `pdf.raster` | implemented; matches the OCRmyPDF reference on linux-x64 ([RASTER.md](RASTER.md)) |
| `image.rectify` | implemented |

Reserved names, absent until their phase lands: `fonts.shape_explicit`, `fonts.subset`,
`ocr.recognize`, `ocr.word_geometry`, `text_layer.write`, `pdf.optimize`,
`svg.render`. Unknown names report absent.
