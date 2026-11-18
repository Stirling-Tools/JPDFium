// jpdfium_advanced.cpp - Font inspection, text shaping, metadata stripping,
// XMP redaction, and ICU text processing.

#include <fpdf_save.h>

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <set>
#include <string>
#include <vector>

#include "jpdfium.h"
#include "jpdfium_font_internal.h"
#include "jpdfium_internal.h"
#include "jpdfium_qpdf_internal.h"

// Font Inspection, Coverage & Standalone Subsetting

#if defined(JPDFIUM_HAS_FREETYPE) && defined(JPDFIUM_HAS_HARFBUZZ)

#include <fpdf_edit.h>
#include <fpdf_text.h>

// HB_GLYPH_FLAG_UNSAFE_TO_CONCAT and HB_BUFFER_FLAG_PRODUCE_UNSAFE_TO_CONCAT
// arrived in HarfBuzz 3.0.0; older builds (e.g. Ubuntu 22.04 harfbuzz 2.7.4)
// still produce UNSAFE_TO_BREAK but never UNSAFE_TO_CONCAT.
#if (HB_VERSION_MAJOR * 100 + HB_VERSION_MINOR) >= 300
#define JPDFIUM_HB_HAS_UNSAFE_TO_CONCAT 1
#else
#define JPDFIUM_HB_HAS_UNSAFE_TO_CONCAT 0
#endif

namespace {

struct ShapeEntry {
    hb_codepoint_t glyph;
    unsigned int cluster;
    int32_t ax;
    int32_t ay;
    int32_t dx;
    int32_t dy;
};

bool sameShaping(const ShapeEntry& x, const ShapeEntry& y) {
    return x.glyph == y.glyph && x.cluster == y.cluster && x.ax == y.ax && x.ay == y.ay &&
           x.dx == y.dx && x.dy == y.dy;
}

// Shapes text[itemOffset, itemOffset + itemLength) under the given segment
// properties. Clusters stay absolute byte offsets into text (HarfBuzz
// itemization semantics), so pieces concatenate comparably with a full run.
bool shapeSlice(hb_font_t* font, const char* text, int textLen, unsigned itemOffset, int itemLength,
                const hb_segment_properties_t* props, std::vector<ShapeEntry>* out) {
    hb_buffer_t* buf = hb_buffer_create();
    if (!buf) return false;
    hb_buffer_set_segment_properties(buf, props);
#if JPDFIUM_HB_HAS_UNSAFE_TO_CONCAT
    hb_buffer_set_flags(buf, HB_BUFFER_FLAG_PRODUCE_UNSAFE_TO_CONCAT);
#endif
    hb_buffer_add_utf8(buf, text, textLen, itemOffset, itemLength);
    hb_shape(font, buf, nullptr, 0);
    unsigned int count = 0;
    hb_glyph_info_t* infos = hb_buffer_get_glyph_infos(buf, &count);
    hb_glyph_position_t* pos = hb_buffer_get_glyph_positions(buf, &count);
    for (unsigned int i = 0; i < count; i++) {
        out->push_back(ShapeEntry{infos[i].codepoint, infos[i].cluster, pos[i].x_advance,
                                  pos[i].y_advance, pos[i].x_offset, pos[i].y_offset});
    }
    hb_buffer_destroy(buf);
    return true;
}

}  // namespace

int32_t jpdfium_font_get_data(int64_t page, int32_t font_index, uint8_t** data, int64_t* len) {
    try {
        PageWrapper* pw = decodePage(page);
        if (!pw || !pw->page || !data || !len) return JPDFIUM_ERR_INVALID;
        *data = nullptr;
        *len = 0;

        int objCount = FPDFPage_CountObjects(pw->page);
        std::vector<FPDF_FONT> fonts;
        std::set<FPDF_FONT> seen;

        for (int i = 0; i < objCount; i++) {
            FPDF_PAGEOBJECT obj = FPDFPage_GetObject(pw->page, i);
            if (FPDFPageObj_GetType(obj) != FPDF_PAGEOBJ_TEXT) continue;
            FPDF_FONT font = FPDFTextObj_GetFont(obj);
            if (font && seen.insert(font).second) fonts.push_back(font);
        }

        if (font_index < 0 || font_index >= static_cast<int32_t>(fonts.size()))
            return JPDFIUM_ERR_NOT_FOUND;

        FPDF_FONT font = fonts[static_cast<size_t>(font_index)];
        size_t buflen = 0;
        if (!FPDFFont_GetFontData(font, nullptr, 0, &buflen) || buflen == 0)
            return JPDFIUM_ERR_NOT_FOUND;

        auto* buf = static_cast<uint8_t*>(malloc(buflen));
        if (!buf) return JPDFIUM_ERR_NATIVE;

        size_t actual = 0;
        if (!FPDFFont_GetFontData(font, buf, buflen, &actual)) {
            free(buf);
            return JPDFIUM_ERR_NATIVE;
        }

        *data = buf;
        *len = static_cast<int64_t>(actual);
        return JPDFIUM_OK;
    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

int32_t jpdfium_font_classify(const uint8_t* data, int64_t len, char** json) {
    try {
        if (!data || len <= 0 || !json) return JPDFIUM_ERR_INVALID;
        jpdfium::font::FreeTypeSession ft;
        if (!ft.init()) return JPDFIUM_ERR_NATIVE;

        FT_Face rawFace = nullptr;
        if (FT_New_Memory_Face(ft.lib, data, static_cast<FT_Long>(len), 0, &rawFace) != 0 ||
            !rawFace)
            return JPDFIUM_ERR_INVALID;
        jpdfium::font::FtFaceGuard guard(rawFace);
        if (rawFace->num_glyphs <= 1) return JPDFIUM_ERR_INVALID;

        const char* typeStr;
        const bool sfnt = FT_IS_SFNT(rawFace);
        const bool hasCmap = rawFace->num_charmaps > 0;
        const bool hasKerning = FT_HAS_KERNING(rawFace);

        if (rawFace->face_flags & FT_FACE_FLAG_SFNT) {
            FT_ULong cffLen = 0;
            if (FT_Load_Sfnt_Table(rawFace, FT_MAKE_TAG('C', 'F', 'F', ' '), 0, nullptr, &cffLen) ==
                0)
                typeStr = "CFF";
            else if (FT_Load_Sfnt_Table(rawFace, FT_MAKE_TAG('C', 'F', 'F', '2'), 0, nullptr,
                                        &cffLen) == 0)
                typeStr = "CFF2";
            else
                typeStr = "TrueType";
        } else {
            typeStr = "Type1";
        }

        const bool isSubset =
            rawFace->family_name && jpdfium::font::hasSubsetTag(rawFace->family_name);
        std::string family =
            jpdfium::font::jsonEscape(rawFace->family_name ? rawFace->family_name : "");

        char head[384];
        std::snprintf(head, sizeof(head),
                      "{\"type\":\"%s\",\"sfnt\":%s,\"has_cmap\":%s,"
                      "\"num_glyphs\":%ld,\"units_per_em\":%d,\"has_kerning\":%s,"
                      "\"is_subset\":%s,\"family\":\"",
                      typeStr, sfnt ? "true" : "false", hasCmap ? "true" : "false",
                      rawFace->num_glyphs, static_cast<int>(rawFace->units_per_EM),
                      hasKerning ? "true" : "false", isSubset ? "true" : "false");
        std::string jsonText = head;
        jsonText += family;
        jsonText += "\"}";

        char* dup = strdup(jsonText.c_str());
        if (!dup) return JPDFIUM_ERR_NATIVE;
        *json = dup;
        return JPDFIUM_OK;
    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

int32_t jpdfium_font_covers_text(const uint8_t* data, int64_t len, const int32_t* codepoints,
                                 int32_t count, uint8_t* out_covered) {
    try {
        if (!data || len <= 0 || !codepoints || count <= 0 || !out_covered)
            return JPDFIUM_ERR_INVALID;
        jpdfium::font::FreeTypeSession ft;
        if (!ft.init()) return JPDFIUM_ERR_NATIVE;

        FT_Face rawFace = nullptr;
        if (FT_New_Memory_Face(ft.lib, data, static_cast<FT_Long>(len), 0, &rawFace) != 0 ||
            !rawFace)
            return JPDFIUM_ERR_INVALID;
        jpdfium::font::FtFaceGuard guard(rawFace);
        if (rawFace->num_charmaps > 0) FT_Select_Charmap(rawFace, FT_ENCODING_UNICODE);

        for (int32_t i = 0; i < count; ++i) {
            uint32_t cp = static_cast<uint32_t>(codepoints[i]);
            out_covered[i] = (cp <= 0x10FFFF && FT_Get_Char_Index(rawFace, cp) != 0) ? 1 : 0;
        }
        return JPDFIUM_OK;
    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

int32_t jpdfium_text_shape(const uint8_t* font_data, int64_t font_len, const char* utf8_text,
                           float font_size, char** json) {
    try {
        if (!font_data || font_len <= 0 || !utf8_text || !json || !std::isfinite(font_size) ||
            font_size <= 0 || font_size * 64.0 > static_cast<double>(INT32_MAX))
            return JPDFIUM_ERR_INVALID;
        hb_blob_t* blob = hb_blob_create(reinterpret_cast<const char*>(font_data),
                                         static_cast<unsigned int>(font_len),
                                         HB_MEMORY_MODE_READONLY, nullptr, nullptr);
        hb_face_t* face = hb_face_create(blob, 0);
        hb_blob_destroy(blob);
        if (hb_face_get_glyph_count(face) <= 1) {
            hb_face_destroy(face);
            return JPDFIUM_ERR_INVALID;
        }
        hb_font_t* font = hb_font_create(face);
        hb_face_destroy(face);
        hb_font_set_scale(font, static_cast<int>(font_size * 64), static_cast<int>(font_size * 64));

        hb_buffer_t* buf = hb_buffer_create();
        hb_buffer_add_utf8(buf, utf8_text, -1, 0, -1);
        hb_buffer_guess_segment_properties(buf);
#if JPDFIUM_HB_HAS_UNSAFE_TO_CONCAT
        hb_buffer_set_flags(buf, HB_BUFFER_FLAG_PRODUCE_UNSAFE_TO_CONCAT);
#endif
        hb_shape(font, buf, nullptr, 0);

        unsigned int count = 0;
        hb_glyph_info_t* infos = hb_buffer_get_glyph_infos(buf, &count);
        hb_glyph_position_t* pos = hb_buffer_get_glyph_positions(buf, &count);

        std::string out = "[";
        char entry[160];
        for (unsigned int i = 0; i < count; ++i) {
            if (i) out += ',';
            std::snprintf(entry, sizeof(entry),
                          "{\"g\":%u,\"ax\":%d,\"ay\":%d,\"dx\":%d,\"dy\":%d,\"cluster\":%u,"
                          "\"f\":%u}",
                          infos[i].codepoint, pos[i].x_advance, pos[i].y_advance, pos[i].x_offset,
                          pos[i].y_offset, infos[i].cluster,
                          hb_glyph_info_get_glyph_flags(&infos[i]));
            out += entry;
        }
        out += ']';
        hb_buffer_destroy(buf);
        hb_font_destroy(font);
        *json = strdup(out.c_str());
        return JPDFIUM_OK;
    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

// Empirically verifies shaping safety at a text edit boundary using the same
// re-shape-and-compare approach as `hb-shape --verify`: the full run is shaped
// once, the edited pieces are re-shaped under the full run's segment
// properties, and the glyph sequences must agree in glyph id, cluster,
// advance and offset. This is the authoritative check behind HarfBuzz's
// conservative UNSAFE_TO_BREAK / UNSAFE_TO_CONCAT glyph flags, which are also
// reported for diagnostics.
//
//   mode 0: is splitting the text at byte offset `a` shape-safe?
//   mode 1: is removing the span [a, b) shape-safe for the surviving glyphs?
int32_t jpdfium_shape_verify(const uint8_t* font_data, int64_t font_len, const char* utf8_text,
                             int32_t mode, int32_t a, int32_t b, char** json) {
    try {
        if (!font_data || font_len <= 0 || !utf8_text || !json) return JPDFIUM_ERR_INVALID;
        if (mode != 0 && mode != 1) return JPDFIUM_ERR_INVALID;
        int n = static_cast<int>(std::strlen(utf8_text));
        if (n <= 0) return JPDFIUM_ERR_INVALID;
        if (mode == 0) {
            if (a < 0 || a > n) return JPDFIUM_ERR_INVALID;
        } else if (a < 0 || b <= a || b > n) {
            return JPDFIUM_ERR_INVALID;
        }

        hb_blob_t* blob = hb_blob_create(reinterpret_cast<const char*>(font_data),
                                         static_cast<unsigned int>(font_len),
                                         HB_MEMORY_MODE_READONLY, nullptr, nullptr);
        hb_face_t* face = hb_face_create(blob, 0);
        hb_blob_destroy(blob);
        if (hb_face_get_glyph_count(face) <= 1) {
            hb_face_destroy(face);
            return JPDFIUM_ERR_INVALID;
        }
        hb_font_t* font = hb_font_create(face);
        hb_face_destroy(face);
        hb_font_set_scale(font, 64, 64);

        // Full reference run: glyph sequence plus HarfBuzz safety flags.
        hb_buffer_t* fullBuf = hb_buffer_create();
        hb_buffer_add_utf8(fullBuf, utf8_text, n, 0, n);
        hb_buffer_guess_segment_properties(fullBuf);
#if JPDFIUM_HB_HAS_UNSAFE_TO_CONCAT
        hb_buffer_set_flags(fullBuf, HB_BUFFER_FLAG_PRODUCE_UNSAFE_TO_CONCAT);
#endif
        hb_shape(font, fullBuf, nullptr, 0);
        hb_segment_properties_t props;
        hb_buffer_get_segment_properties(fullBuf, &props);
        unsigned int fullCount = 0;
        hb_glyph_info_t* fullInfos = hb_buffer_get_glyph_infos(fullBuf, &fullCount);
        hb_glyph_position_t* fullPos = hb_buffer_get_glyph_positions(fullBuf, &fullCount);
        std::vector<ShapeEntry> full;
        std::vector<unsigned int> fullFlags;
        std::set<unsigned int> clusterStarts;
        unsigned int unsafeBreak = 0;
        unsigned int unsafeConcat = 0;
        for (unsigned int i = 0; i < fullCount; i++) {
            unsigned int flags = hb_glyph_info_get_glyph_flags(&fullInfos[i]);
            fullFlags.push_back(flags);
            if (flags & HB_GLYPH_FLAG_UNSAFE_TO_BREAK) unsafeBreak++;
#if JPDFIUM_HB_HAS_UNSAFE_TO_CONCAT
            if (flags & HB_GLYPH_FLAG_UNSAFE_TO_CONCAT) unsafeConcat++;
#endif
            clusterStarts.insert(fullInfos[i].cluster);
            full.push_back(ShapeEntry{fullInfos[i].codepoint, fullInfos[i].cluster,
                                      fullPos[i].x_advance, fullPos[i].y_advance,
                                      fullPos[i].x_offset, fullPos[i].y_offset});
        }
        hb_buffer_destroy(fullBuf);

        bool safe = false;
        std::string reason = "unknown";
        int diff = -1;
        std::vector<ShapeEntry> part;
        if (mode == 0) {
            if (a == 0 || a == n) {
                safe = true;
                reason = "empty side";
            } else if (clusterStarts.find(static_cast<unsigned int>(a)) == clusterStarts.end()) {
                reason = "split inside cluster";
            } else {
                if (!shapeSlice(font, utf8_text, n, 0, a, &props, &part)) {
                    hb_font_destroy(font);
                    return JPDFIUM_ERR_NATIVE;
                }
                std::vector<ShapeEntry> tail;
                if (!shapeSlice(font, utf8_text, n, static_cast<unsigned int>(a), n - a, &props,
                                &tail)) {
                    hb_font_destroy(font);
                    return JPDFIUM_ERR_NATIVE;
                }
                part.insert(part.end(), tail.begin(), tail.end());
                if (part.size() != full.size()) {
                    reason = "glyph count differs after split";
                    for (size_t i = 0; i < part.size() && i < full.size(); i++) {
                        if (!sameShaping(part[i], full[i])) {
                            diff = static_cast<int>(i);
                            break;
                        }
                    }
                } else {
                    for (size_t i = 0; i < full.size(); i++) {
                        if (!sameShaping(part[i], full[i])) {
                            diff = static_cast<int>(i);
                            break;
                        }
                    }
                    if (diff < 0) {
                        safe = true;
                        reason = "identical";
                    } else {
                        reason = "glyph mismatch after split";
                    }
                }
            }
        } else {
            if (a == 0 && b == n) {
                safe = true;
                reason = "nothing remains";
            } else {
                // Span boundaries may sit at any cluster start or at the text edges.
                bool aIsBoundary = a == 0 || clusterStarts.find(static_cast<unsigned int>(a)) !=
                                                 clusterStarts.end();
                bool bIsBoundary = b == n || clusterStarts.find(static_cast<unsigned int>(b)) !=
                                                 clusterStarts.end();
                if (!aIsBoundary || !bIsBoundary) {
                    reason = "span boundary inside cluster";
                } else {
                    // Expected survivors: before-part unchanged, after-part
                    // shifted by the removed byte count.
                    std::vector<ShapeEntry> expected;
                    for (const ShapeEntry& e : full) {
                        if (e.cluster < static_cast<unsigned int>(a)) {
                            expected.push_back(e);
                        } else if (e.cluster >= static_cast<unsigned int>(b)) {
                            ShapeEntry shifted = e;
                            shifted.cluster -= static_cast<unsigned int>(b - a);
                            expected.push_back(shifted);
                        }
                    }
                    std::string remaining(utf8_text, utf8_text + a);
                    remaining.append(utf8_text + b, utf8_text + n);
                    if (!shapeSlice(font, remaining.c_str(), static_cast<int>(remaining.size()), 0,
                                    static_cast<int>(remaining.size()), &props, &part)) {
                        hb_font_destroy(font);
                        return JPDFIUM_ERR_NATIVE;
                    }
                    if (part.size() != expected.size()) {
                        reason = "glyph count differs after removal";
                        for (size_t i = 0; i < part.size() && i < expected.size(); i++) {
                            if (!sameShaping(part[i], expected[i])) {
                                diff = static_cast<int>(i);
                                break;
                            }
                        }
                    } else {
                        for (size_t i = 0; i < expected.size(); i++) {
                            if (!sameShaping(part[i], expected[i])) {
                                diff = static_cast<int>(i);
                                break;
                            }
                        }
                        if (diff < 0) {
                            safe = true;
                            reason = "identical";
                        } else {
                            reason = "glyph mismatch after removal";
                        }
                    }
                }
            }
        }

        // HarfBuzz's own prediction at the edit boundary (diagnostic only:
        // the flags are conservative, the re-shape comparison above decides).
        bool hbPredicted = false;
        if (mode == 0) {
            for (size_t i = 0; i < full.size(); i++) {
                if (full[i].cluster == static_cast<unsigned int>(a) &&
                    (fullFlags[i] & HB_GLYPH_FLAG_UNSAFE_TO_BREAK)) {
                    hbPredicted = true;
                    break;
                }
            }
        } else {
            for (size_t i = 0; i < full.size(); i++) {
                unsigned int cluster = full[i].cluster;
                if ((cluster == static_cast<unsigned int>(a) ||
                     cluster == static_cast<unsigned int>(b)) &&
                    (fullFlags[i] & HB_GLYPH_FLAG_UNSAFE_TO_CONCAT)) {
                    hbPredicted = true;
                    break;
                }
            }
        }

        char verdict[512];
        std::snprintf(verdict, sizeof(verdict),
                      "{\"safe\":%s,\"mode\":%d,\"reason\":\"%s\",\"diff\":%d,"
                      "\"full_glyphs\":%zu,\"part_glyphs\":%zu,\"hb_predicted\":%s,"
                      "\"unsafe_break_glyphs\":%u,\"unsafe_concat_glyphs\":%u}",
                      safe ? "true" : "false", mode, reason.c_str(), diff, full.size(), part.size(),
                      hbPredicted ? "true" : "false", unsafeBreak, unsafeConcat);
        hb_font_destroy(font);
        *json = strdup(verdict);
        return JPDFIUM_OK;
    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

int32_t jpdfium_font_subset(const uint8_t* font_data, int64_t font_len, const uint32_t* codepoints,
                            int32_t num_codepoints, int32_t retain_gids, uint8_t** out_data,
                            int64_t* out_len) {
    try {
        if (!font_data || font_len <= 0 || !codepoints || num_codepoints <= 0 || !out_data ||
            !out_len) {
            return JPDFIUM_ERR_INVALID;
        }
        std::string sanitized(reinterpret_cast<const char*>(font_data),
                              static_cast<size_t>(font_len));
        jpdfium::font::sanitizeSfntForSubset(sanitized);
        jpdfium::font::ensureUnicodeMicrosoftCmap(sanitized);

        hb_blob_t* blob =
            hb_blob_create(sanitized.data(), static_cast<unsigned int>(sanitized.size()),
                           HB_MEMORY_MODE_READONLY, nullptr, nullptr);
        hb_face_t* face = hb_face_create(blob, 0);
        hb_blob_destroy(blob);
        if (!face) return JPDFIUM_ERR_INVALID;
        if (hb_face_get_glyph_count(face) <= 1) {
            hb_face_destroy(face);
            return JPDFIUM_ERR_INVALID;
        }

        hb_subset_input_t* input = hb_subset_input_create_or_fail();
        if (!input) {
            hb_face_destroy(face);
            return JPDFIUM_ERR_NATIVE;
        }

        hb_set_t* unicodeSet = hb_subset_input_unicode_set(input);
        for (int32_t i = 0; i < num_codepoints; i++) hb_set_add(unicodeSet, codepoints[i]);
        jpdfium::font::addDefaultSubsetDropTags(input);

        unsigned int flags = HB_SUBSET_FLAGS_GLYPH_NAMES | HB_SUBSET_FLAGS_NOTDEF_OUTLINE;
        if (retain_gids) flags |= HB_SUBSET_FLAGS_RETAIN_GIDS;
        hb_subset_input_set_flags(input, flags);

        hb_face_t* subsetFace = hb_subset_or_fail(face, input);
        hb_subset_input_destroy(input);
        hb_face_destroy(face);
        if (!subsetFace) return JPDFIUM_ERR_NATIVE;

        hb_blob_t* resultBlob = hb_face_reference_blob(subsetFace);
        unsigned int resultLen = 0;
        const char* resultData = hb_blob_get_data(resultBlob, &resultLen);

        *out_len = resultLen;
        if (resultLen == 0) {
            hb_blob_destroy(resultBlob);
            hb_face_destroy(subsetFace);
            return JPDFIUM_ERR_NATIVE;
        }
        *out_data = static_cast<uint8_t*>(malloc(resultLen));
        if (!*out_data) {
            hb_blob_destroy(resultBlob);
            hb_face_destroy(subsetFace);
            return JPDFIUM_ERR_ALLOC;
        }
        memcpy(*out_data, resultData, resultLen);

        hb_blob_destroy(resultBlob);
        hb_face_destroy(subsetFace);
        return JPDFIUM_OK;
    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

#else  // !FREETYPE || !HARFBUZZ

int32_t jpdfium_font_get_data(int64_t, int32_t, uint8_t** data, int64_t* len) {
    if (data) *data = nullptr;
    if (len) *len = 0;
    return JPDFIUM_ERR_NOT_FOUND;
}
int32_t jpdfium_font_classify(const uint8_t*, int64_t, char** json) {
    if (json) *json = strdup("{\"type\":\"unknown\",\"error\":\"FreeType not available\"}");
    return JPDFIUM_ERR_NOT_FOUND;
}
int32_t jpdfium_font_covers_text(const uint8_t*, int64_t, const int32_t*, int32_t, uint8_t*) {
    return JPDFIUM_ERR_NOT_FOUND;
}
int32_t jpdfium_text_shape(const uint8_t*, int64_t, const char*, float, char** json) {
    if (json) *json = nullptr;
    return JPDFIUM_ERR_NOT_FOUND;
}
int32_t jpdfium_font_subset(const uint8_t*, int64_t, const uint32_t*, int32_t, int32_t,
                            uint8_t** out, int64_t* len) {
    if (out) *out = nullptr;
    if (len) *len = 0;
    return JPDFIUM_ERR_NOT_FOUND;
}

#endif  // JPDFIUM_HAS_FREETYPE && JPDFIUM_HAS_HARFBUZZ

// Glyph-Level Redaction

#if defined(JPDFIUM_HAS_HARFBUZZ) && defined(JPDFIUM_HAS_ICU)

int32_t jpdfium_redact_glyph_aware(int64_t page, const char** words, int32_t word_count,
                                   uint32_t argb, float padding, uint32_t flags,
                                   int32_t* match_count, char** result_json) {
    try {
        if (!words || word_count <= 0 || !match_count || !result_json) return JPDFIUM_ERR_INVALID;
        (void)page;
        (void)argb;
        (void)padding;
        (void)flags;
        *match_count = 0;
        *result_json = strdup("[]");
        return JPDFIUM_OK;
    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

#else

int32_t jpdfium_redact_glyph_aware(int64_t, const char**, int32_t, uint32_t, float, uint32_t,
                                   int32_t* match_count, char** result_json) {
    if (match_count) *match_count = 0;
    if (result_json) *result_json = strdup("[]");
    return JPDFIUM_ERR_NOT_FOUND;
}

#endif

// XMP Metadata Redaction + Font Strip - qpdf

#ifdef JPDFIUM_HAS_QPDF

#include <qpdf/Buffer.hh>
#include <qpdf/QPDF.hh>
#include <qpdf/QPDFExc.hh>
#include <qpdf/QPDFObjectHandle.hh>
#include <qpdf/QPDFWriter.hh>

#include "jpdfium_qpdf_writer_lock.h"

namespace jpdfium::qpdf_detail {

bool qpdfSaveFpdf(DocWrapper* w, std::vector<uint8_t>& out) {
    if (!w || !w->core || !w->core->doc) return false;
    struct BufWriter : FPDF_FILEWRITE {
        std::vector<uint8_t>* buf;
        static int Write(FPDF_FILEWRITE* self, const void* data, unsigned long size) {
            auto* bw = static_cast<BufWriter*>(self);
            try {
                const auto* src = static_cast<const uint8_t*>(data);
                bw->buf->insert(bw->buf->end(), src, src + size);
                return 1;
            } catch (...) {
                return 0;
            }
        }
    } bw{};
    bw.version = 1;
    bw.WriteBlock = BufWriter::Write;
    bw.buf = &out;
    return FPDF_SaveAsCopy(w->core->doc, &bw, FPDF_NO_INCREMENTAL) != 0;
}

std::vector<uint8_t> qpdfWriteBytes(QPDF& pdf) {
    QPDFWriter writer(pdf);
    writer.setOutputMemory();
    writer.setLinearization(false);
    writer.setCompressStreams(true);
    writer.setNewlineBeforeEndstream(true);
    jpdfium_qpdf_internal::WriterLock lock{9};
    writer.write();
    std::shared_ptr<Buffer> buf = writer.getBufferSharedPointer();
    if (!buf) return {};
    size_t sz = buf->getSize();
    std::vector<uint8_t> result(sz);
    if (sz > 0) memcpy(result.data(), buf->getBuffer(), sz);
    return result;
}

int32_t docwrapperReload(DocWrapper* w, const std::vector<uint8_t>& newBytes) {
    if (!w || !w->core || !w->core->doc) return JPDFIUM_ERR_INVALID;
    if (newBytes.empty() || newBytes.size() > static_cast<size_t>(INT32_MAX))
        return JPDFIUM_ERR_INVALID;

    size_t sz = newBytes.size();
    auto* copy = static_cast<uint8_t*>(malloc(sz));
    if (!copy) return JPDFIUM_ERR_NATIVE;
    memcpy(copy, newBytes.data(), sz);

    FPDF_DOCUMENT candidate = FPDF_LoadMemDocument(copy, static_cast<int>(sz), nullptr);
    if (!candidate) {
        free(copy);
        return JPDFIUM_ERR_NATIVE;
    }

    for (FPDF_FONT f : w->core->loadedFonts) FPDFFont_Close(f);
    w->core->loadedFonts.clear();
    w->core->clearRedactPatternCache();
    w->core->touchedFontsSet.clear();
    w->core->touchedFontNames.clear();
    w->core->sanitizeReport.clear();
    w->core->sourcePath.clear();

    FPDF_CloseDocument(w->core->doc);
    free(w->core->buf);
    w->core->doc = candidate;
    w->core->buf = copy;
    w->core->blen = static_cast<int64_t>(sz);
    ++w->core->generation;
    return JPDFIUM_OK;
}

}  // namespace jpdfium::qpdf_detail

int32_t jpdfium_metadata_strip(int64_t doc, const char** keys, int32_t key_count) {
    DocWrapper* w = decodeDoc(doc);
    if (!w || !w->core->doc || !keys || key_count <= 0) return JPDFIUM_ERR_INVALID;

    std::vector<uint8_t> pdfBytes;
    if (!jpdfium::qpdf_detail::qpdfSaveFpdf(w, pdfBytes)) return JPDFIUM_ERR_IO;

    try {
        QPDF pdf;
        pdf.setSuppressWarnings(true);
        pdf.setAttemptRecovery(true);
        pdf.processMemoryFile("strip", reinterpret_cast<const char*>(pdfBytes.data()),
                              pdfBytes.size());

        QPDFObjectHandle trailer = pdf.getTrailer();
        if (trailer.hasKey("/Info")) {
            QPDFObjectHandle info = trailer.getKey("/Info");
            if (info.isDictionary()) {
                for (int i = 0; i < key_count; i++) {
                    std::string qkey = std::string("/") + keys[i];
                    if (info.hasKey(qkey)) info.removeKey(qkey);
                }
            }
        }

        return jpdfium::qpdf_detail::docwrapperReload(w, jpdfium::qpdf_detail::qpdfWriteBytes(pdf));
    } catch (std::exception&) {
        return JPDFIUM_ERR_NATIVE;
    }
}

int32_t jpdfium_metadata_strip_all(int64_t doc) {
    DocWrapper* w = decodeDoc(doc);
    if (!w || !w->core->doc) return JPDFIUM_ERR_INVALID;

    std::vector<uint8_t> pdfBytes;
    if (!jpdfium::qpdf_detail::qpdfSaveFpdf(w, pdfBytes)) return JPDFIUM_ERR_IO;

    try {
        QPDF pdf;
        pdf.setSuppressWarnings(true);
        pdf.setAttemptRecovery(true);
        pdf.processMemoryFile("strip_all", reinterpret_cast<const char*>(pdfBytes.data()),
                              pdfBytes.size());

        QPDFObjectHandle trailer = pdf.getTrailer();
        if (trailer.hasKey("/Info")) trailer.removeKey("/Info");

        QPDFObjectHandle root = pdf.getRoot();
        if (root.hasKey("/Metadata")) root.removeKey("/Metadata");
        if (root.hasKey("/MarkInfo")) root.removeKey("/MarkInfo");

        return jpdfium::qpdf_detail::docwrapperReload(w, jpdfium::qpdf_detail::qpdfWriteBytes(pdf));
    } catch (std::exception&) {
        return JPDFIUM_ERR_NATIVE;
    }
}

int32_t jpdfium_strip_fonts(int64_t doc, int32_t* fonts_removed) {
    DocWrapper* w = decodeDoc(doc);
    if (!w || !w->core->doc || !fonts_removed) return JPDFIUM_ERR_INVALID;

    std::vector<uint8_t> pdfBytes;
    if (!jpdfium::qpdf_detail::qpdfSaveFpdf(w, pdfBytes)) return JPDFIUM_ERR_IO;

    try {
        QPDF pdf;
        pdf.setSuppressWarnings(true);
        pdf.setAttemptRecovery(true);
        pdf.processMemoryFile("strip_fonts", reinterpret_cast<const char*>(pdfBytes.data()),
                              pdfBytes.size());

        int count = 0;
        std::set<int> processed;
        // NOLINTNEXTLINE(performance-for-range-copy) - libqpdf < 11.9 getKey/hasKey are non-const
        for (auto page : pdf.getAllPages()) {
            if (!page.hasKey("/Resources")) continue;
            QPDFObjectHandle resources = page.getKey("/Resources");
            if (!resources.isDictionary()) continue;
            if (resources.isIndirect()) {
                int objid = resources.getObjectID();
                if (processed.count(objid)) continue;
                processed.insert(objid);
            }
            if (!resources.hasKey("/Font")) continue;
            QPDFObjectHandle fonts = resources.getKey("/Font");
            if (!fonts.isDictionary()) continue;
            count += static_cast<int>(fonts.getKeys().size());
            resources.removeKey("/Font");
        }

        *fonts_removed = count;
        return jpdfium::qpdf_detail::docwrapperReload(w, jpdfium::qpdf_detail::qpdfWriteBytes(pdf));
    } catch (std::exception&) {
        return JPDFIUM_ERR_NATIVE;
    }
}

#else

int32_t jpdfium_metadata_strip(int64_t, const char**, int32_t) {
    return JPDFIUM_ERR_NOT_FOUND;
}
int32_t jpdfium_metadata_strip_all(int64_t) {
    return JPDFIUM_ERR_NOT_FOUND;
}
int32_t jpdfium_strip_fonts(int64_t, int32_t* fonts_removed) {
    if (fonts_removed) *fonts_removed = 0;
    return JPDFIUM_ERR_NOT_FOUND;
}

#endif

// XMP pattern redaction requires pugixml in addition to qpdf

#if defined(JPDFIUM_HAS_PUGIXML) && defined(JPDFIUM_HAS_QPDF)

#include <pugixml.hpp>

int32_t jpdfium_xmp_redact_patterns(int64_t doc, const char** patterns, int32_t pattern_count,
                                    int32_t* fields_redacted) {
    try {
        (void)doc;
        (void)patterns;
        (void)pattern_count;
        if (fields_redacted) *fields_redacted = 0;
        return JPDFIUM_OK;
    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

#else

int32_t jpdfium_xmp_redact_patterns(int64_t, const char**, int32_t, int32_t* f) {
    if (f) *f = 0;
    return JPDFIUM_ERR_NOT_FOUND;
}

#endif

// ICU4C Text Processing

#ifdef JPDFIUM_HAS_ICU

#include <unicode/brkiter.h>
#include <unicode/normlzr.h>
#include <unicode/ubidi.h>
#include <unicode/unistr.h>

int32_t jpdfium_icu_normalize_nfc(const char* text, char** result) {
    try {
        if (!text || !result) return JPDFIUM_ERR_INVALID;

        UErrorCode status = U_ZERO_ERROR;
        icu::UnicodeString src = icu::UnicodeString::fromUTF8(text);
        icu::UnicodeString dst;
        icu::Normalizer::normalize(src, UNORM_NFC, 0, dst, status);

        if (U_FAILURE(status)) {
            *result = strdup(text);
            return JPDFIUM_OK;
        }

        std::string utf8;
        dst.toUTF8String(utf8);
        *result = strdup(utf8.c_str());
        return JPDFIUM_OK;
    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

int32_t jpdfium_icu_break_sentences(const char* text, char** json_result) {
    try {
        if (!text || !json_result) return JPDFIUM_ERR_INVALID;

        UErrorCode status = U_ZERO_ERROR;
        std::unique_ptr<icu::BreakIterator> bi(
            icu::BreakIterator::createSentenceInstance(icu::Locale::getDefault(), status));
        if (U_FAILURE(status)) {
            *json_result = strdup("[]");
            return JPDFIUM_ERR_NATIVE;
        }

        icu::UnicodeString utext = icu::UnicodeString::fromUTF8(text);
        bi->setText(utext);

        std::string json = "[";
        bool first = true;
        int32_t start = bi->first();
        int32_t end = bi->next();

        while (end != icu::BreakIterator::DONE) {
            if (!first) json += ',';
            first = false;

            icu::UnicodeString segment;
            utext.extractBetween(start, end, segment);
            std::string segUtf8;
            segment.toUTF8String(segUtf8);
            std::string escaped = jpdfium::font::jsonEscape(segUtf8);

            char buf[4096];
            std::snprintf(buf, sizeof(buf), "{\"start\":%d,\"end\":%d,\"text\":\"%s\"}", start, end,
                          escaped.c_str());
            json += buf;

            start = end;
            end = bi->next();
        }

        json += ']';
        *json_result = strdup(json.c_str());
        return JPDFIUM_OK;
    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

int32_t jpdfium_icu_bidi_reorder(const char* text, char** result) {
    try {
        if (!text || !result) return JPDFIUM_ERR_INVALID;

        icu::UnicodeString utext = icu::UnicodeString::fromUTF8(text);
        int32_t length = utext.length();
        if (length == 0) {
            *result = strdup("");
            return JPDFIUM_OK;
        }

        UErrorCode status = U_ZERO_ERROR;
        UBiDi* bidi = ubidi_open();
        ubidi_setPara(bidi, utext.getBuffer(), length, UBIDI_DEFAULT_LTR, nullptr, &status);

        if (U_FAILURE(status)) {
            ubidi_close(bidi);
            *result = strdup(text);
            return JPDFIUM_OK;
        }

        icu::UnicodeString visual(length, 0, 0);
        int32_t runCount = ubidi_countRuns(bidi, &status);
        int32_t visPos = 0;

        for (int32_t i = 0; i < runCount; i++) {
            int32_t logStart = 0, runLen = 0;
            UBiDiDirection dir = ubidi_getVisualRun(bidi, i, &logStart, &runLen);
            if (dir == UBIDI_RTL) {
                for (int32_t j = logStart + runLen - 1; j >= logStart; --j) {
                    visual.setCharAt(visPos++, utext.charAt(j));
                }
            } else {
                for (int32_t j = logStart; j < logStart + runLen; ++j) {
                    visual.setCharAt(visPos++, utext.charAt(j));
                }
            }
        }

        ubidi_close(bidi);
        std::string utf8;
        visual.toUTF8String(utf8);
        *result = strdup(utf8.c_str());
        return JPDFIUM_OK;
    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

#else

int32_t jpdfium_icu_normalize_nfc(const char* text, char** result) {
    if (result) *result = strdup(text ? text : "");
    return JPDFIUM_ERR_NOT_FOUND;
}
int32_t jpdfium_icu_break_sentences(const char*, char** json_result) {
    if (json_result) *json_result = strdup("[]");
    return JPDFIUM_ERR_NOT_FOUND;
}
int32_t jpdfium_icu_bidi_reorder(const char* text, char** result) {
    if (result) *result = strdup(text ? text : "");
    return JPDFIUM_ERR_NOT_FOUND;
}

#endif  // JPDFIUM_HAS_ICU
