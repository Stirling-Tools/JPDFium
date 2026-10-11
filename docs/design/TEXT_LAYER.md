# Invisible text layers

`jpdf_text_layer_write` writes the text-only PDF that OCRmyPDF's `generate_pdf` hook returns for
its sandwich renderer: one page, sized to the OCR image, holding invisible text and nothing else.
It never touches PDFium, so it runs outside the domain and in any runtime state.

## Input

- The page is `image_width / dpi_x * 72` by `image_height / dpi_y * 72` points.
- Lines come in reading order. Each has a box and takes the next `word_count` words from the word
  array. Words carry UTF-8 text in logical order and a box. Boxes are image pixels, origin
  top-left.
- A line without word geometry is passed as one word holding the whole line, spaces included.
  The writer never invents word boxes by dividing a line.

## Layout

The layout follows OCRmyPDF 17.13's renderer (`fpdf_renderer/renderer.py`):

- One `BT ... ET` block per line in render mode 3 (invisible), with a `Td` and a `Tz` per word.
- The font size is the line height. The font's ascent and descent (800 and -200) add up to one
  em, so glyph boxes cover the line box exactly; the baseline sits a fifth of the line height
  above its bottom.
- `Tz` stretches each word to its box width.
- Every word is followed by a space character, line-final words included, so extractors without
  layout analysis still separate words. Between two CJK words no space is written; the first is
  stretched to meet the second when the gap is at most two ems.

`jpdf_page_get_text` returns PDFium's extracted text for a page as UTF-8, so bindings can read a
layer back.

## File structure

- One page with a single `/Contents` stream and a single font resource, `/F1`. OCRmyPDF's graft
  copies the content stream and one font found under `/f-0-0` or `/F1`, and nothing else.
- A Type0 font with Identity-H encoding over a CIDFontType2 whose TrueType program is built in
  code, so no font file is shipped. CIDs are numbered in order of first use, and `/ToUnicode`
  maps each to its code point, including code points outside the BMP. A page can use up to
  65,535 distinct characters.
- Both glyphs are blank: two single-point contours spanning the em box, the shape OCRmyPDF's
  Occulta font uses. They paint nothing in any render mode, and their glyph box is not empty.
  Newer PDFium builds skip text whose glyph box is empty; with a truly empty glyph, a
  one-character CJK word vanished from extraction.
- Streams are Flate-compressed and numbers are written in fixed point, independent of the C
  locale. Equal input gives byte-identical files: there are no timestamps, and `/ID` is a hash of
  the body.
- The file is built in memory, its cross-reference offsets are checked, and it is published
  through an output transaction, so a failure leaves the destination untouched.

## Refused input

| Input | Status |
|---|---|
| Right-to-left scripts and RTL controls | `UNSUPPORTED`: extractors reorder RTL runs, so laying them out like LTR text would reverse them |
| Invalid UTF-8, surrogates, control characters, noncharacters, empty words | `INVALID_ARGUMENT` |
| Zero-height lines, inverted boxes, lines that do not use every word exactly once | `INVALID_ARGUMENT` |
| More than 1,048,576 lines, 2,097,152 words, 16 MiB of text or 65,535 distinct characters | `LIMIT` |
| `image_format` or `png_compression` set in `jpdf_write_options` | `UNSUPPORTED` |

Right-to-left text, shaping and font fallback belong to the full multilingual writer.

## Evidence

- `api_text_layer` reads layers back through JPDFium's own PDFium. It checks:
  - the page size;
  - text in logical order: accents, combining marks, CJK without separators, and code points
    outside the BMP;
  - blank rendering, and byte-identical output for equal input;
  - 200 repeated writes under LeakSanitizer;
  - rejections, larger caller structs, and a failed write leaving the destination untouched;
  - concurrent writers beside a reader, under TSan.
- PyJPDFium's `tests/parity/test_text_layer_parity.py` uses independent tools:
  - pypdfium2 5.14.0, which bundles a newer PDFium than JPDFium's pin. It checks the text,
    search across words, and that word spaces are real characters rather than generated ones.
    Character boxes must lie inside their word box and span it edge to edge within 0.05 pt
    horizontally. Vertically the bound is 2% of the font size, because that PDFium rounds glyph
    tops up by about 0.012 em.
  - pikepdf 10.15.0's qpdf syntax check and fontTools 4.64.0, which verifies the table and
    whole-font checksums.
  - OCRmyPDF 17.13.0's own `_graft.py`. After grafting, the page renders pixel-identical to the
    original, and the text, search and placement carry over, including on a `/Rotate 90` page.
    Its `strip_invisible_text`, used by `--redo-ocr`, removes the layer completely.
