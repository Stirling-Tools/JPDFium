#pragma once
#include <stdint.h>

#ifdef _WIN32
  #define JPDFIUM_EXPORT __declspec(dllexport)
#else
  #define JPDFIUM_EXPORT __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

// Return codes. 0 = success; all negative values are errors.
#define JPDFIUM_OK              0
#define JPDFIUM_ERR_INVALID    -1
#define JPDFIUM_ERR_IO         -2
#define JPDFIUM_ERR_PASSWORD   -3
#define JPDFIUM_ERR_NOT_FOUND  -4
#define JPDFIUM_ERR_NATIVE     -99

JPDFIUM_EXPORT int32_t jpdfium_init(void);
JPDFIUM_EXPORT void    jpdfium_destroy(void);

JPDFIUM_EXPORT int32_t jpdfium_doc_open(const char* path, int64_t* handle);
JPDFIUM_EXPORT int32_t jpdfium_doc_open_bytes(const uint8_t* data, int64_t len, int64_t* handle);
JPDFIUM_EXPORT int32_t jpdfium_doc_open_protected(const char* path, const char* password, int64_t* handle);
JPDFIUM_EXPORT int32_t jpdfium_doc_page_count(int64_t doc, int32_t* count);
JPDFIUM_EXPORT int32_t jpdfium_doc_save(int64_t doc, const char* path);
JPDFIUM_EXPORT int32_t jpdfium_doc_save_bytes(int64_t doc, uint8_t** data, int64_t* len);
JPDFIUM_EXPORT void    jpdfium_doc_close(int64_t doc);

JPDFIUM_EXPORT int32_t jpdfium_page_open(int64_t doc, int32_t idx, int64_t* handle);
JPDFIUM_EXPORT int32_t jpdfium_page_width(int64_t page, float* width);
JPDFIUM_EXPORT int32_t jpdfium_page_height(int64_t page, float* height);
JPDFIUM_EXPORT void    jpdfium_page_close(int64_t page);

JPDFIUM_EXPORT int32_t jpdfium_render_page(int64_t page, int32_t dpi, uint8_t** rgba, int32_t* width, int32_t* height);
JPDFIUM_EXPORT void    jpdfium_free_buffer(uint8_t* buffer);

// Returns per-character data as a compact JSON array: [{i,u,x,y,w,h,font,size}, ...]
// The caller owns the returned string and must free it with jpdfium_free_string.
JPDFIUM_EXPORT int32_t jpdfium_text_get_chars(int64_t page, char** json);
JPDFIUM_EXPORT int32_t jpdfium_text_find(int64_t page, const char* query, char** json);
JPDFIUM_EXPORT void    jpdfium_free_string(char* str);

JPDFIUM_EXPORT int32_t jpdfium_redact_region(int64_t page, float x, float y, float w, float h, uint32_t argb, int32_t remove_content);
JPDFIUM_EXPORT int32_t jpdfium_redact_pattern(int64_t page, const char* pattern, uint32_t argb, int32_t remove_content);
JPDFIUM_EXPORT int32_t jpdfium_redact_words(int64_t page, const char** words, int32_t wordCount, uint32_t argb, float padding, int32_t wholeWord, int32_t useRegex, int32_t remove_content);
JPDFIUM_EXPORT int32_t jpdfium_redact_words_ex(int64_t page, const char** words, int32_t wordCount, uint32_t argb, float padding, int32_t wholeWord, int32_t useRegex, int32_t remove_content, int32_t caseSensitive, int32_t* matchCount);
JPDFIUM_EXPORT int32_t jpdfium_page_flatten(int64_t page);
JPDFIUM_EXPORT int32_t jpdfium_page_to_image(int64_t doc, int32_t pageIndex, int32_t dpi);

// Returns JSON: [{"i":0,"ox":10.1,"oy":20.2,"l":10.0,"r":18.0,"b":15.0,"t":27.0}, ...]
// Each element contains the character index, origin (ox,oy), and bounding box (l,r,b,t).
// Used by tests to verify that text positions are preserved after redaction.
JPDFIUM_EXPORT int32_t jpdfium_text_get_char_positions(int64_t page, char** json);

// Annotation-Based Redaction (Mark / Commit pattern)
//
// Two-phase redaction modeled after EmbedPDF's architecture:
//   Mark phase:  Create FPDF_ANNOT_REDACT annotations (zero content mutation).
//   Commit phase: Burn all REDACT annotations via Object Fission (destructive).
//
// The document stays alive between phases - no close/reload required.

// Mark phase: create a single REDACT annotation at the given rectangle.
// The annotation is stored in the page's annotation dictionary; the content
// stream is NOT modified.  Returns the annotation index on success.
JPDFIUM_EXPORT int32_t jpdfium_annot_create_redact(int64_t page,
                                                    float x, float y, float w, float h,
                                                    uint32_t argb, int32_t* annot_index);

// Mark phase: find all word matches and create REDACT annotations for each.
// Equivalent to jpdfium_redact_words_ex but ONLY creates annotations,
// without modifying the content stream.  Returns the number of annotations
// created in *matchCount.
JPDFIUM_EXPORT int32_t jpdfium_redact_mark_words(int64_t page,
                                                  const char** words, int32_t wordCount,
                                                  float padding, int32_t wholeWord,
                                                  int32_t useRegex, int32_t caseSensitive,
                                                  uint32_t argb, int32_t* matchCount);

// Query: return the number of REDACT annotations on the page.
JPDFIUM_EXPORT int32_t jpdfium_annot_count_redacts(int64_t page, int32_t* count);

// Query: return all REDACT annotation rects as JSON.
// [{"idx":0,"x":10.0,"y":20.0,"w":50.0,"h":12.0}, ...]
JPDFIUM_EXPORT int32_t jpdfium_annot_get_redacts_json(int64_t page, char** json);

// Remove a specific REDACT annotation by its annotation index.
JPDFIUM_EXPORT int32_t jpdfium_annot_remove_redact(int64_t page, int32_t annot_index);

// Remove all REDACT annotations from the page (undo all marks).
JPDFIUM_EXPORT int32_t jpdfium_annot_clear_redacts(int64_t page);

// Commit phase: burn all REDACT annotations on the page using Object Fission.
// This permanently removes text/images under each REDACT rect, paints filled
// rectangles, removes the consumed annotations, and regenerates the content
// stream.  The document handle remains valid - no reload required.
// Returns the number of REDACT annotations that were committed in *commitCount.
JPDFIUM_EXPORT int32_t jpdfium_redact_commit(int64_t page, uint32_t argb,
                                              int32_t remove_content,
                                              int32_t* commitCount);

// Incremental save: writes only changed objects, document stays live.
JPDFIUM_EXPORT int32_t jpdfium_doc_save_incremental(int64_t doc, uint8_t** data, int64_t* len);

// Advanced Pattern Engine (PCRE2 JIT)
//
// Compiles redact patterns once to native machine code. Supports lookaheads,
// Unicode word boundaries (\b), and script-aware \w for multilingual PDFs.

// PCRE2 compile flags (combinable via bitwise OR)
#define JPDFIUM_PCRE2_CASELESS   0x00000001
#define JPDFIUM_PCRE2_MULTILINE  0x00000002
#define JPDFIUM_PCRE2_DOTALL     0x00000004
#define JPDFIUM_PCRE2_UTF        0x00000008
#define JPDFIUM_PCRE2_UCP        0x00000010  // Unicode character properties for \w, \d, \b

// Compile a PCRE2 pattern with optional JIT optimization.
// flags: combination of JPDFIUM_PCRE2_* flags (UTF|UCP recommended for multilingual).
// Returns compiled pattern handle via *handle.
JPDFIUM_EXPORT int32_t jpdfium_pcre2_compile(const char* pattern, uint32_t flags, int64_t* handle);

// Find all non-overlapping matches of a compiled pattern in text.
// Returns JSON: [{"start":0,"end":5,"match":"Hello"}, ...]
JPDFIUM_EXPORT int32_t jpdfium_pcre2_match_all(int64_t pattern_handle, const char* text, char** json_result);

// Free a compiled PCRE2 pattern.
JPDFIUM_EXPORT void jpdfium_pcre2_free(int64_t pattern_handle);

// Validate a string as a credit card number using the Luhn algorithm.
// Strips spaces/dashes before validation. Returns 1 if valid, 0 if invalid.
JPDFIUM_EXPORT int32_t jpdfium_luhn_validate(const char* number);

// FlashText Dictionary NER - O(n) keyword matching
//
// Trie-based keyword processor for named-entity dictionary matching.
// Runs in O(n) time regardless of dictionary size (vs. O(n×m) for regex).

// Create a FlashText keyword processor.
JPDFIUM_EXPORT int32_t jpdfium_flashtext_create(int64_t* handle);

// Add a single keyword with its entity label (e.g., keyword="John Smith", label="PERSON").
JPDFIUM_EXPORT int32_t jpdfium_flashtext_add_keyword(int64_t handle, const char* keyword, const char* label);

// Add multiple keywords from JSON: [{"keyword":"John Smith","label":"PERSON"}, ...]
JPDFIUM_EXPORT int32_t jpdfium_flashtext_add_keywords_json(int64_t handle, const char* json);

// Find all keyword matches in text. Returns JSON:
// [{"start":0,"end":10,"keyword":"John Smith","label":"PERSON"}, ...]
JPDFIUM_EXPORT int32_t jpdfium_flashtext_find(int64_t handle, const char* text, char** json_result);

// Free the keyword processor and all associated memory.
JPDFIUM_EXPORT void jpdfium_flashtext_free(int64_t handle);

// Font Normalization Pipeline (FreeType + HarfBuzz hb-subset)
//
// Stages: Extract -> Classify -> Fix ToUnicode -> Repair /W -> Re-subset -> Re-embed
// All operations use MIT/Apache-2.0 libraries only.

// Font type classification constants
#define JPDFIUM_FONT_TYPE_UNKNOWN    0
#define JPDFIUM_FONT_TYPE_TRUETYPE   1
#define JPDFIUM_FONT_TYPE_CFF        2
#define JPDFIUM_FONT_TYPE_TYPE1      3
#define JPDFIUM_FONT_TYPE_CID        4
#define JPDFIUM_FONT_TYPE_OPENTYPE   5

// Extract raw font data from a text object on a page.
// font_index: zero-based index among distinct fonts on this page.
// Caller must free returned data with jpdfium_free_buffer.
JPDFIUM_EXPORT int32_t jpdfium_font_get_data(int64_t page, int32_t font_index,
                                              uint8_t** data, int64_t* len);

// Classify a font from its raw bytes using FreeType.
// Returns JSON: {"type":"TrueType","sfnt":true,"has_cmap":true,"num_glyphs":245,
//                "units_per_em":2048,"has_kerning":true,"is_subset":true}
JPDFIUM_EXPORT int32_t jpdfium_font_classify(const uint8_t* data, int64_t len, char** json);

// Fix /ToUnicode CMap for all fonts on a page using the font's internal cmap table.
// This is the most critical step for reliable auto-redact: wrong ToUnicode -> wrong text
// extraction -> patterns miss -> redact silently fails.
// Returns the number of fonts repaired via *fonts_fixed.
JPDFIUM_EXPORT int32_t jpdfium_font_fix_tounicode(int64_t doc, int32_t page_index,
                                                    int32_t* fonts_fixed);

// Repair /W (glyph width) tables using FreeType's authoritative advance widths.
// Wrong widths cause text drift after editing and inaccurate redact box coordinates.
// Returns the number of fonts repaired via *fonts_fixed.
JPDFIUM_EXPORT int32_t jpdfium_font_repair_widths(int64_t doc, int32_t page_index,
                                                    int32_t* fonts_fixed);

// Run the full font normalization pipeline on all fonts on a page:
// 1. Fix /ToUnicode  2. Repair /W  3. Normalize Type1->OTF  4. Re-subset
// Returns JSON summary: {"fonts_processed":3,"tounicode_fixed":2,"widths_repaired":1,...}
JPDFIUM_EXPORT int32_t jpdfium_font_normalize_page(int64_t doc, int32_t page_index, char** json);

// Re-subset a font using HarfBuzz hb-subset.
// Retains only the given Unicode codepoints. If retain_gids is non-zero,
// uses HB_SUBSET_FLAGS_RETAIN_GIDS to keep original GIDs (critical for
// existing content streams to continue referencing correct glyphs).
// Caller must free out_data with jpdfium_free_buffer.
JPDFIUM_EXPORT int32_t jpdfium_font_subset(const uint8_t* font_data, int64_t font_len,
                                            const uint32_t* codepoints, int32_t num_codepoints,
                                            int32_t retain_gids,
                                            uint8_t** out_data, int64_t* out_len);

// Glyph-Level Redaction (HarfBuzz + ICU4C + libunibreak)
//
// Character-precise redaction that handles ligatures, RTL text, and
// combining characters correctly.
//
// Pipeline: FPDFText_GetCharBox -> hb_shape (ligature clusters) ->
//           ICU BiDi (visual order) -> libunibreak (grapheme clusters) ->
//           FPDFPageObj_CreateNewRect -> qpdf stream rebuild

// Glyph-aware redaction configuration flags (combinable via bitwise OR)
#define JPDFIUM_GLYPH_LIGATURE_AWARE  0x0001  // HarfBuzz cluster mapping for ligatures
#define JPDFIUM_GLYPH_BIDI_AWARE      0x0002  // ICU BiDi visual order resolution
#define JPDFIUM_GLYPH_GRAPHEME_SAFE   0x0004  // libunibreak grapheme cluster boundaries
#define JPDFIUM_GLYPH_REMOVE_STREAM   0x0008  // qpdf structural content removal
#define JPDFIUM_GLYPH_ALL             0x000F  // all features enabled

// Advanced glyph-aware redaction.
// flags: combination of JPDFIUM_GLYPH_* flags.
// Returns match count via *match_count and detailed JSON result via *result_json.
// JSON: [{"word":"secret","page_matches":[{"start":10,"end":16,
//         "glyphs":[{"gid":42,"cluster":0,"x":10.0,"y":20.0,"advance":8.5},...],
//         "rect":{"x":10.0,"y":15.0,"w":48.0,"h":14.0}},...]}]
JPDFIUM_EXPORT int32_t jpdfium_redact_glyph_aware(int64_t page,
                                                    const char** words, int32_t word_count,
                                                    uint32_t argb, float padding,
                                                    uint32_t flags,
                                                    int32_t* match_count, char** result_json);

// XMP Metadata Redaction (pugixml + qpdf)
//
// Redacting visible text is not enough - PDF XMP metadata and /Info dictionary
// often echo the same PII (author, creator, producer, subject, keywords).

// Redact XMP metadata fields whose values match any of the given PCRE2 patterns.
// Scans <xmp:Author>, <dc:creator>, <pdf:Producer>, <dc:description>, etc.
// Returns the number of fields blanked via *fields_redacted.
JPDFIUM_EXPORT int32_t jpdfium_xmp_redact_patterns(int64_t doc,
                                                     const char** patterns, int32_t pattern_count,
                                                     int32_t* fields_redacted);

// Strip specific /Info dictionary entries by key (e.g., "Author", "Creator", "Producer").
JPDFIUM_EXPORT int32_t jpdfium_metadata_strip(int64_t doc,
                                                const char** keys, int32_t key_count);

// Strip ALL metadata from the document: /Info dictionary + XMP stream + /MarkInfo.
// The nuclear option for metadata-clean redaction.
JPDFIUM_EXPORT int32_t jpdfium_metadata_strip_all(int64_t doc);

// ICU4C Text Processing
//
// Unicode-aware text processing primitives needed for semantic redaction:
// normalization (NFC/NFD), sentence segmentation, and BiDi reordering.

// Unicode NFC normalization (canonical decomposition + canonical composition).
// Essential before CMap generation and before pattern matching on extracted text.
// Caller must free *result with jpdfium_free_string.
JPDFIUM_EXPORT int32_t jpdfium_icu_normalize_nfc(const char* text, char** result);

// Segment text into sentences using ICU BreakIterator.
// Returns JSON array of sentence boundaries: [{"start":0,"end":42,"text":"First sentence."},...]
// Caller must free *json_result with jpdfium_free_string.
JPDFIUM_EXPORT int32_t jpdfium_icu_break_sentences(const char* text, char** json_result);

// Resolve BiDi text to visual display order using ICU UBiDi.
// Needed to compute correct redact box coordinates for RTL/mixed-direction text.
// Caller must free *result with jpdfium_free_string.
JPDFIUM_EXPORT int32_t jpdfium_icu_bidi_reorder(const char* text, char** result);

#ifdef __cplusplus
}
#endif
