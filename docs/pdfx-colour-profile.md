# PDF/X neutral black conversion

`PdfXOptions.neutralsToBlack(false)` uses a conventional rich black for neutral vector
colours:

```text
C = 0.6 * K
M = 0.4 * K
Y = 0.4 * K
K = 1 - grey
```

Solid black therefore becomes `0.6 0.4 0.4 1 k` (240% total area coverage, below the
bundled profile's 245% cap). With `neutralsToBlack(true)` the existing K-only path is
unchanged (`0 0 0 1 k`).

The bundled `JPDFiumCompactCmyk.icc` uses strong black generation and maps neutral black
back to K only. A pure profile lookup would make `neutralsToBlack(false)` indistinguishable
from the enabled option, so neutral vectors use the explicit rich-black mix above; images
and non-neutral colours still use the ICC lookup table.

## Evidence

- `PdfXColourConversionTest.neutralsKeepProcessColourWhenDisabled` passes.
- Emitted content contains `0.6 0.4 0.4 1 k` for the black rectangle.
- A 300 dpi render of the converted page has a neutral black patch:
  `mean RGB = (13, 13, 13)` with no cyan/magenta/yellow cast.
- `PdfXColourConversionTest.rgbVectorColoursBecomeCmykAndNeutralsBecomeBlackOnly` still
  asserts `[0, 0, 0, 1]` for the default `neutralsToBlack(true)` path.
- `qpdf --check` reports no syntax or stream errors.
