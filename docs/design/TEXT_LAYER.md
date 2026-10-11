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
- Since API 1.4 a line or word may carry a quad instead of a box (`JPDF_TEXT_LINE_QUAD`,
  `JPDF_TEXT_WORD_QUAD`): corners top-left, top-right, bottom-right, bottom-left as read. Text
  runs along the top-left to top-right edge, so OCR polygons of rotated or skewed lines keep
  their slope. Callers built against 1.3 pass the shorter structs and get the 1.3 behaviour.
- A line's base direction comes from its first strong character, or from `JPDF_TEXT_LINE_LTR`
  or `JPDF_TEXT_LINE_RTL`. Words stay in logical order whatever the direction.

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

Left-to-right lines with boxes are written exactly as above. Other lines take the general path
below.

### Right-to-left and mixed text

Each word is laid out with ICU's implementation of the Unicode bidi algorithm (UAX #9), at the
line's base level. Characters stay in logical order in the content stream, and each directional
run gets its own text matrix: left-to-right runs advance rightwards, right-to-left runs are
mirrored (`-1` horizontal scale) and advance leftwards from their right edge. Each character
lands in the cell the bidi algorithm gives it, so:

- extractors that read stream order (pdfminer.six) get logical text directly;
- extractors that rebuild order from positions (PDFium, poppler) see an ordinary right-to-left
  line and reverse it back to logical text.

Tesseract's PDF renderer uses the same mirrored layout for right-to-left words. Two other
layouts were measured and rejected: logical order placed left to right came back reversed from
PDFium and poppler, and visual order came back reversed from pdfminer.six.

In a right-to-left line the space after a word sits to its left, and words are spaced when the
next word starts further left.

### Rotated and skewed lines

A quad line's text matrix runs along the quad's baseline, with its vertical axis along the
quad's sides, so skew is kept as well as rotation. The font size is the height of the quad
along those sides. Word positions are their quads, or boxes, projected onto the baseline.

### Widths

Every code point advances by one cell, combining marks and joiners included. Grapheme-cluster
widths (zero-width marks through `/W`) were measured and rejected: pdfminer.six then split
Vietnamese, Devanagari and decomposed Hangul words, and PDFium dropped a joiner inside an emoji
sequence. With equal cells all four extractors returned the exact text. Nothing is drawn, so
shaping and font fallback would change no pixels; they are not needed for this font.

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
| Bidi embeddings, overrides and isolates (U+202A to U+202E, U+2066 to U+2069) | `UNSUPPORTED`: they nest across words, which per-word layout cannot honour |
| Unknown flags, both direction flags, a quad flag on a 1.3 struct | `UNSUPPORTED` or `INVALID_ARGUMENT` |
| Quads with no length or height, corners running anticlockwise, sides within about 6 degrees of the baseline, non-finite corners | `INVALID_ARGUMENT` |
| Invalid UTF-8, surrogates, control characters, noncharacters, empty words | `INVALID_ARGUMENT` |
| Zero-height lines, inverted boxes, lines that do not use every word exactly once | `INVALID_ARGUMENT` |
| More than 1,048,576 lines, 2,097,152 words, 16 MiB of text or 65,535 distinct characters | `LIMIT` |
| `image_format` or `png_compression` set in `jpdf_write_options` | `UNSUPPORTED` |

## Evidence

- `api_text_layer` reads layers back through JPDFium's own PDFium. It checks:
  - the page size;
  - text in logical order: accents, combining marks, CJK without separators, and code points
    outside the BMP;
  - blank rendering, and byte-identical output for equal input;
  - 200 repeated writes under LeakSanitizer;
  - rejections, larger caller structs, and a failed write leaving the destination untouched;
  - concurrent writers beside a reader, under TSan;
  - Hebrew, Arabic with harakat, and a Hebrew word in a Latin line read back in logical order;
  - rotated (15 and 90 degrees) and skewed quad lines read back, drawing nothing;
  - 1.3-sized structs still accepted.
- PyJPDFium's `tests/parity/test_multilingual_parity.py` qualifies the general path. Twenty-three
  samples are each written flat, rotated 15, 90 and 180 degrees, and skewed. They cover Latin,
  Greek, Cyrillic, Chinese, Hangul, kana, Devanagari, Thai, combining marks, emoji sequences,
  Hebrew with and without points, Arabic with harakat, Persian with a ZWNJ, and mixed-direction
  lines. Each layer is read by four extractors: JPDFium's PDFium, pypdfium2's newer PDFium,
  pdfminer.six 20260107 and poppler 24.02's `pdftotext`. Every character's box must land in the
  cell the writer contract gives it, and the text must reach the level each extractor showed in
  the qualification run, recorded in PyJPDFium's `docs/COMPATIBILITY.md`. Nine mutations of the
  layout code, among them an unmirrored right-to-left run, a wrong bidi level bound and a
  left-to-right-only line direction, each fail this suite.
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
