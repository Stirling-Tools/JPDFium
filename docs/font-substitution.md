# Font substitution roadmap

`PdfFontEmbedder` currently performs exact stream replacement only. It never
changes a font dictionary, character codes, widths or ToUnicode.

That is the safe boundary: the caller supplies a font program for an existing
`/BaseFont`, qpdf replaces the matching `/FontFile*` stream, and a missing
descriptor is an error.

Automatic substitution is a separate feature. FreeType and HarfBuzz already
provide the font-program half:

- FreeType: program validation, metrics, glyph outlines, cmap inspection
- HarfBuzz: shaping and subsetting
- `FontNormalizer`: classification and subsetting

The hard part is the PDF object model:

- simple fonts use character codes in content streams, `/Widths`, `/Encoding`
  and `/Differences`
- composite fonts use CIDs, `/CIDToGIDMap`, `/CIDSystemInfo` and a CMap
- `/ToUnicode` controls extraction and copy/paste, independently of the glyph
  IDs in the font program
- Type3 fonts embed glyph procedures as PDF content streams
- replacing a program changes glyph IDs and widths unless the substitute is
  metric- and encoding-compatible

## Proposed API shape

Do not make `PdfFontEmbedder` guess. Introduce a plan:

```text
PdfFontSubstitution.plan(doc)
  -> List<FontSlot>          // /BaseFont, encoding, widths, embedded program
PdfFontResolver.resolve(slot)
  -> Optional<FontCandidate> // program + metrics + encoding compatibility
PdfFontSubstitution.apply(doc, plan)
  -> result with replaced / skipped / unsupported slots
```

`PdfFontResolver` can use FreeType/HarfBuzz and a caller-supplied font directory.
It must be explicit about licensing and must never silently choose a font.

## Phases

1. Exact stream replacement (implemented).
2. Standard 14 and metric-compatible simple fonts: rewrite `/BaseFont`,
   `/Widths` and the font program; reject unsupported encodings.
3. Simple TrueType fonts with identical character-code mapping and compatible
   widths.
4. Type0/CID fonts: build a new CMap and `/CIDToGIDMap`, rewrite content-stream
   CIDs, and regenerate `/ToUnicode`.
5. Type3: reject; glyph procedures are page content, not a font program.

Every phase needs a preflight result and a post-conversion audit. A substitution
that cannot preserve text extraction or visible line breaks must fail rather
than produce a PDF/X-labelled file.
