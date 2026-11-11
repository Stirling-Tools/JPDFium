# Font stack review — verification and repair status

Worktree: `/Users/brios/IdeaProjects/JPDFium-stack-fix`
Base: `a99a2a64` (`feat/font-compress-subset`, top of the #243–#254 stack)
Purpose: independently re-check the stack review findings and the expanded
PDFium/PDF.js/PDFBox/pypdf/pdfcpu/pdf-lib/PDFKit corpus, then fix what was still
wrong.

This file records what was **verified**, not what a previous tracker claimed.

## Verification summary

| Check | Result |
|---|---|
| `./gradlew buildRealBridge` | PASS |
| `native/build-real/native_smoke` | PASS |
| `PdfCompressorTest` | 6 tests, 0 failures |
| `PdfFontEmbedderTest` | 45 tests, 0 failures |
| `FontNormalizerTest` | 28 tests, 0 failures |
| `PdfFontInventoryTest` | 7 tests, 0 failures |
| `PdfFontEmbedderRoundTripTest` local corpus | 368 tests, 0 failures, 201 assumption skips |
| `PdfFontEmbedderRoundTripTest` PDF.js corpus | 184 tests, 0 failures, 125 assumption skips |
| `PdfFontEmbedderRoundTripTest` veraPDF font corpus | 520 tests, 0 failures, 271 assumption skips |
| `PdfFontEmbedderRoundTripTest` PDFBox corpus | 352 tests, 0 failures, 184 assumption skips |
| `compileJava` / `compileTestJava` | PASS |
| `clang-format --dry-run --Werror` | PASS |
| AGL table vs official `glyphlist.txt` | 4,200 single-scalar + 81 sequences, 0 missing |
| veraPDF 2b on repaired `issue20065.pdf` | no font-rule failures; only `6.6.4-1`, `6.2.4.3-4` |
| Simple TrueType compression (`LiberationSans`, 11 glyphs) | 212,243 B → 6,591 B, text and render identical |

The round-trip suite is the important one: it re-embeds stripped programs across
the local PDF corpus, requires exact pixel equality, and now also requires text
preservation even when a font gains `/ToUnicode`.

## What was wrong and what changed

### 1. False “all resolved” claims in the old tracker

The previous tracker marked several items resolved that were still present in
`a99a2a64`:

- Simple-font width repair still overwrote every positive producer width that
  differed from the program advance.
- CID `/W` synthesis still wrote an entry for every mapped glyph, not only the
  drawn CIDs (plus zero-advance glyphs).
- `PdfFontAuditor.audit` skipped unopenable pages silently (fixed on this stack
  already, re-verified).
- Simple Type 1 PFB/PFA programs were rejected by the fsType pre-check.
- `subsetKeepsSimpleGlyphs` compared glyph indices, so any compact subset without
  `RETAIN_GIDS` was rejected.
- CID-keyed OpenType/CFF was rewritten as bare `/CIDFontType0C`, which changed
  PDFium rasterization on `pdfjs-issue5334.pdf` (738 pixels).
- Built-in Symbol/ZapfDingbats substitution changed extracted text on
  `pdfjs_ZapfDingbats.pdf`.
- The round-trip test skipped text comparison whenever a font gained `/ToUnicode`
  and skipped style checks when a bold/italic face fell back to regular.

### 2. Production fixes

| Area | Change |
|---|---|
| `PdfFontEmbedder` | Added Type 1 PFB/PFA detection before the SFNT fsType probe. Type 1 programs have no OS/2 table and are now embeddable. |
| `PdfFontEmbedder` | Bounded TTC face loops in both `singleFace` and the file index; corrupt collections are rejected with `IllegalArgumentException` instead of `IndexOutOfBoundsException`. |
| `PdfFontEmbedder` | Reuses oversized programs within one operation without retaining them in the global cache. |
| `PdfFontEmbedder` / native | Unknown named simple-font encodings (for example `/Custom`) are not remapped or subset; missing fonts with such encodings stay unresolved with a diagnostic. |
| `PdfFontEmbedder` | Built-in Symbol/ZapfDingbats masks are left unresolved instead of substituting a different glyph order. Reports now name the blocker (predefined CMap, CIDToGIDMap, legacy non-ASCII name, symbolic encoding). |
| `PdfFontAuditor` | File-backed inventory uses the native file reader and no longer serializes the document to the Java heap. |
| `FontNormalizer` / native | Simple `/Widths` repair preserves positive producer widths; only missing, non-numeric, or non-positive entries are repaired. |
| `FontNormalizer` / native | CID `/W` synthesis covers drawn CIDs plus zero-advance glyphs instead of every mapped glyph. |
| `FontNormalizer` / native | `/CIDSet` after a re-subset is derived from the retained glyph set mapped back through `/CIDToGIDMap`; it no longer describes the pre-subset program or only the drawn CIDs. |
| `FontNormalizer` / native | CID-keyed CFF is kept in its OpenType wrapper. Simple CFF-in-SFNT conversion verifies every resolved glyph name exists in the bare CFF first. |
| Native lexer | Both content lexers treat NUL as PDF whitespace. |
| Native lexer | Inline-image `EI` detection requires a token boundary on both sides and fails closed when the stream is malformed. |
| Native subsetter | Simple non-symbolic fonts subset without `RETAIN_GIDS`; verification compares glyph names and advances, not renumbered indices. Symbolic fonts keep GIDs. |
| Native embedder | Missing simple fonts are embedded whole, not subset, so a scanner miss cannot drop a glyph and truncate extraction. CID replacements still remap. |
| Native embedder | Explicit re-embedding preserves `/Flags`, `/Encoding`, and the original cmap; `(3,1)` cmap normalization is applied only when substituting a missing font. This fixed `survey.pdf` and `sample_fonts_solidconvertor.pdf`. |
| Native embedder | Before substituting a missing simple font, every drawn code must resolve to a glyph in the replacement; otherwise the font is left unresolved. |
| Native pattern clamp | `XStep`/`YStep` bound check no longer uses floating-point equality (CodeQL warning removed). |
| `CompressOptions` | Javadoc now matches the opt-in default (`subsetFonts=false`). |
| Tests | Added PFB embedding, truncated TTC, symbolic-font refusal, plausible-width preservation, retained-subset CIDSet, two-page normalization, simple-font compression, and strict text/style round-trip checks. |

## Cross-project corpus mapping

| Finding | Status in JPDFium |
|---|---|
| PDFium `CPDF_StreamParser::ReadInlineStream` drops Type3 glyphs when CCITT data abuts `EI` | Our scanners now fail closed on the ambiguous boundary, but the renderer is upstream PDFium. No JPDFium-side rewrite is attempted; see “Remaining limits”. |
| Type3 pattern `XStep/YStep = INT32_MAX` | Clamped to `[1, 32767]` in both optimizer and PDF/X paths. |
| HTML-to-PDF Type3 is not editable text | Informational; no edit API claims otherwise. |
| PDF.js embedded/system font mode | Not applicable to PDFium; JPDFium embeds each font resource independently. |
| PDF.js canvas printing | JPDFium exposes rendering, not browser printing; subset programs are checked with Java AWT. |
| PDFBox TrueType subset `name`/`post` tables | HarfBuzz flags retain glyph names and `.notdef`; `FontCoverageTest.subsetFontCompatibleWithJavaAwt` loads the result with AWT. |
| PDFBox hardcoded WinAnsi | JPDFium parses the PDF encoding and writes explicit `/Differences`/`/ToUnicode` through the native path. |
| Missing predefined CMaps | Left unresolved with a specific diagnostic; `embedMissingStrict` throws. No crash. |
| pypdf `Tm` / annotation appearance | Content scanner tracks annotation `/AP` streams and form state. |
| pdf-lib StandardFonts not embedded | JPDFium reports unresolved Standard 14 resources rather than claiming they are embedded. |
| PDFKit duplicate family faces / no Unicode cmap | Font directories are sorted; every face name is indexed; a program without a usable mapping fails closed. |
| pdfcpu nondeterministic user fonts | Font directories are sorted before indexing and the bundled manifest is stable. |

## Remaining limits

1. **Predefined-CMap CJK CID fonts are still fail-closed.** `90ms-RKSJ-H` with
   `HeiseiMin-W3` remains unresolved. The API reports it; `embedMissingStrict`
   throws. A full code-to-Unicode-to-GID rewrite for every predefined CMap is
   not implemented.
2. **Type3 inline CCITT rendering** remains an upstream PDFium parser bug. Our
   scanners do not trust an ambiguous boundary, so subsetting fails closed, but
   PDFium can still render fewer glyphs than Acrobat/Poppler/MuPDF.
3. **Single-pass document CID scan** is still per font. The existing per-font scan
   cache avoids repeats within one font, but a document with many fonts still
   decodes page content once per font. Correctness is unaffected; this is the
   remaining performance item.
4. **The corpus round-trip suite** skips cases through assumptions (no
   unambiguous embedded program, signed/encrypted, unreadable input). Skips are
   reported, not hidden.
5. **veraPDF on `issue20065`** still fails unrelated rules `6.6.4-1` and
   `6.2.4.3-4`. Font rules pass; a complete PDF/A conversion still needs XMP,
   output intent, and colour handling.
