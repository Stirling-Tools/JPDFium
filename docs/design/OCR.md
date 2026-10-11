# OCR module

`libjpdfium_ocr` recognizes text with PaddleOCR's PP-OCR models on ONNX Runtime. It is optional:
the core never depends on it, and it links to the core instead of compiling its own copy of the
domain or the handle table. Recognition works on detached pixels, so it never enters the PDFium
domain. The C API is `native/ocr/include/jpdfium_ocr.h`.

## Pinned inputs

| Input | Pin |
|---|---|
| ONNX Runtime | 1.30.0 CPU packages from the GitHub release, by the digests GitHub publishes (`native/ocr/onnxruntime.version`) |
| Profile `pp-ocrv6-small` | PaddlePaddle's own ONNX exports of PP-OCRv6 small, at fixed Hugging Face revisions, by SHA-256 (`native/ocr/profiles/pp-ocrv6-small.profile`) |
| Dictionary | Extracted from the recognizer's config by `fetch_profile.py`, with a pinned digest |

```bash
bash native/ocr/fetch-onnxruntime.sh linux-x64
python3 native/ocr/tools/fetch_profile.py native/ocr/profiles/pp-ocrv6-small.profile DEST
```

`build-real.sh` builds the module once ONNX Runtime has been fetched (`JPDFIUM_OCR=0` turns it
off) and stages it with the runtime in `native/dist/<platform>`.

Opening an engine reads every profile file once and refuses it unless its digest matches. The
models are handed to ONNX Runtime from those same bytes. The profile also fixes the pipeline
parameters, and unknown keys are refused.

## Pipeline

The module reproduces PaddleOCR 3.7's general OCR pipeline with orientation classification,
unwarping and text-line orientation turned off, which is its default ONNX Runtime configuration:

1. **Detection input.** DetResizeForTest (minimum side 64, at most 4000, multiples of 32), then
   NormalizeImage on BGR channels.
2. **Boxes.** DBPostProcess with threshold 0.3, box threshold 0.6, unclip ratio 1.5 and at most
   3000 candidates.
3. **Order.** SortQuadBoxes.
4. **Crops.** CropByPolys(quad): the minimum-area rectangle warped upright, then turned 90 degrees
   when tall.
5. **Recognition.** Crops are sorted by aspect ratio and read in batches of six, each batch padded
   to its widest crop, then decoded with CTCLabelDecode over the profile's dictionary.

PaddleOCR's results depend on OpenCV and pyclipper arithmetic. `cv_compat.cpp` ports the OpenCV
4.10 operations involved, and `paddle.cpp` ports Clipper 6.4.2's polygon offset:

- `resize` (`INTER_LINEAR` fixed point);
- `warpPerspective` (`INTER_CUBIC`, `BORDER_REPLICATE`, with its block-relative coordinates);
- `getPerspectiveTransform`, `findContours`, `convexHull`, `minAreaRect`, `boxPoints` and
  `fillPoly`.

Python rounding, float32 arithmetic and numpy's pairwise mean are matched where results depend
on them.

Each line carries a polygon in source pixels, its text, the mean probability of its characters and
its detection score. The models report lines only, so word geometry is marked absent; words are
never invented by splitting a line.

## Handles and errors

Engines and results are module objects in the core's generation-checked table
(`jpdfium_module.h`). A lease keeps an engine alive while a recognition runs, even if it is closed
meanwhile, and a handle of the other kind is `JPDF_E_WRONG_HANDLE_TYPE`. Errors are read with
`jpdf_last_error_message`, as for the core. The module must stay loaded while its objects exist.

Engines are immutable after opening; concurrent recognitions on one engine are supported.

## Parity

PyJPDFium's `tests/parity/test_ocr_parity.py` runs the official `paddleocr` 3.7.0 package on
ONNX Runtime 1.30.0, offline and with the same model files, on eight rendered pages:

- 72 to 600 dpi; gray, RGB and 1-bit;
- several fonts, accented Latin text and a small table;
- a skewed line and a rotated heading;
- a page small enough to be upscaled, and one large enough to be scaled down.

Every polygon and every text is identical. Confidences differ by at most 1.2e-7.

`native/ocr/tests/ocr_geometry.cpp` checks each ported step bit for bit against outputs recorded
from the reference stack by `make_golden.py`.

Vertical text is misread exactly as the reference misreads it: reading it needs the text-line
orientation model, which this profile does not include.

## Limits

| Input | Status |
|---|---|
| Profile file with a different digest, unknown profile key | `FORMAT` / `UNSUPPORTED` |
| ONNX Runtime older than the C API the module was built for | `UNSUPPORTED` |
| More pixels than `max_pixels` (256 MiP by default) | `LIMIT` |

## Licensing

The PP-OCRv6 models are Apache-2.0, and ONNX Runtime is MIT. The ported OpenCV code is Apache-2.0
and the ported Clipper code is under the Boost Software License 1.0; see `NOTICE`.
