# Font embedding

`PdfFontEmbedder` embeds font programs in one qpdf rewrite.

- `embed(pdf, fonts)` takes programs keyed by exact `/BaseFont` and embeds each
  into every matching font, replacing any program already there.
- `embedMissing(pdf, fontDirs...)` finds the non-embedded fonts in use, looks
  each up by PostScript or full name in the given directories (the system font
  directories by default), and embeds what it finds. Fonts that already carry a
  program are left alone, and fonts whose OS/2 `fsType` forbids embedding are
  never picked. The result lists what was embedded and what is still missing,
  audited on the output.

## What changes in the PDF

- The program goes in `/FontFile2` (TrueType), `/FontFile3 /OpenType`,
  `/FontFile3 /Type1C` or `/CIDFontType0C` (bare CFF), or `/FontFile` with
  `/Length1`-`/Length3` (Type 1, PFB or PFA). A collection (`.ttc`) is narrowed
  to one face.
- A font with no `/FontDescriptor` (the standard 14) gains one built from the
  program's metrics, and a simple font with no `/Widths` gains them, read from
  the program through its `/Encoding` and `/Differences` (ISO 32000-1 9.6.6).
- `/Subtype` follows the outlines (ISO 32000-2 9.9): glyf outlines make
  `/TrueType` or `/CIDFontType2`, CFF and Type 1 make `/Type1` or
  `/CIDFontType0`, including inside an OpenType wrapper.
- A simple font that becomes `/TrueType` gets its mapping pinned: an explicit
  `/Encoding` (WinAnsi plus `/Differences`) when it relied on the implicit
  StandardEncoding, and a `/ToUnicode` when it had none. Without these,
  viewers read the codes differently once the font is TrueType.
- A simple non-symbolic font that had no program is embedded as a subset of
  the glyphs its 256 codes can reach, with glyph IDs retained and a subset tag
  added to its name. Type 1 and bare CFF programs, programs whose OS/2
  `fsType` forbids subsetting, symbolic fonts, CIDFonts, and fonts with
  `/Differences` names the program cannot resolve are embedded whole.
- Existing `/Widths`, `/ToUnicode`, CMaps and `/CIDToGIDMap` are kept, so text
  never moves.

## Substitutes

When no installed font has the requested name, the standard 14 and their
Microsoft equivalents resolve to a metric-compatible family: Helvetica, Arial,
Liberation Sans, Nimbus Sans, Arimo; Times, Times New Roman, Liberation Serif,
Nimbus Roman, Tinos; Courier, Courier New, Liberation Mono, Nimbus Mono,
Cousine; plus Symbol and ZapfDingbats equivalents. The URW base 35 Type 1 set
(`gsfonts`) is recognised by its own style spellings. A missing style is not
synthesised: `Arial,Bold` with no bold face installed stays unresolved.

## Limits

- Signed documents are refused: the rewrite invalidates the signature.
- A CIDFont keeps its CID-to-glyph mapping, so the program must have the
  original font's glyph order.
- Type3 fonts are skipped: their glyphs are page content, not a font program.
- `/BaseFont` names containing a NUL byte cannot be matched.
- A code that `/Differences` maps to `.notdef` gets no `/ToUnicode` entry, so a
  viewer may extract the base encoding's character for it.

## Verification

`PdfFontEmbedderRoundTripTest` strips the embedded programs it can re-embed
from each PDF (skipping fonts whose program is missing, unreadable or differs
between fonts of the same name, and programs the embedder rejects), embeds the
same programs again and requires PDFium to render the first eight renderable
pages pixel for pixel like the original. It also runs `embedMissing` on each
PDF and requires the extracted text of the first eight pages to be unchanged,
except for characters that were unreadable before; that text check is skipped
when a Type 0 font gains a `/ToUnicode`. Rendering differences after
`embedMissing` are reported, not asserted. Point it at any directory with
`-Djpdfium.test.fontCorpus=<dir>`.
