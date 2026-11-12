// jpdfium_advanced.cpp - Pattern matching, font analysis, metadata and text utilities.

#include <fpdf_save.h>

#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <iterator>
#include <limits>
#include <map>
#include <memory>
#include <set>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#include "jpdfium.h"
#include "jpdfium_font_encoding.h"
#include "jpdfium_internal.h"

// PCRE2 JIT Pattern Engine

#ifdef JPDFIUM_HAS_PCRE2
#define PCRE2_CODE_UNIT_WIDTH 8
#include <pcre2.h>

struct Pcre2Pattern8 {
    pcre2_code* code;
    pcre2_match_data* match_data;
};

int32_t jpdfium_pcre2_compile(const char* pattern, uint32_t flags, int64_t* handle) {
    try {
        if (!pattern || !handle) return JPDFIUM_ERR_INVALID;

        uint32_t pcre2_opts = 0;
        if (flags & JPDFIUM_PCRE2_CASELESS) pcre2_opts |= PCRE2_CASELESS;
        if (flags & JPDFIUM_PCRE2_MULTILINE) pcre2_opts |= PCRE2_MULTILINE;
        if (flags & JPDFIUM_PCRE2_DOTALL) pcre2_opts |= PCRE2_DOTALL;
        if (flags & JPDFIUM_PCRE2_UTF) pcre2_opts |= PCRE2_UTF;
        if (flags & JPDFIUM_PCRE2_UCP) pcre2_opts |= PCRE2_UCP;

        int errcode;
        PCRE2_SIZE erroffset;
        pcre2_code* code = pcre2_compile((PCRE2_SPTR)pattern, PCRE2_ZERO_TERMINATED, pcre2_opts,
                                         &errcode, &erroffset, nullptr);

        if (!code) return JPDFIUM_ERR_INVALID;

        // JIT compile for speed - failure is non-fatal (falls back to interpreter)
        pcre2_jit_compile(code, PCRE2_JIT_COMPLETE);

        auto* pw = new Pcre2Pattern8();
        pw->code = code;
        pw->match_data = pcre2_match_data_create_from_pattern(code, nullptr);

        Pcre2HandleRegistry::instance().add(pw);
        *handle = reinterpret_cast<int64_t>(pw);
        return JPDFIUM_OK;

    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

int32_t jpdfium_pcre2_match_all(int64_t pattern_handle, const char* text, char** json_result) {
    try {
        if (!text || !json_result) return JPDFIUM_ERR_INVALID;
        auto* pw = reinterpret_cast<Pcre2Pattern8*>(pattern_handle);
        if (!pw || !Pcre2HandleRegistry::instance().contains(pw)) return JPDFIUM_ERR_INVALID;
        if (!pw->code) return JPDFIUM_ERR_INVALID;

        PCRE2_SIZE subject_len = strlen(text);
        std::string json = "[";
        bool first = true;
        PCRE2_SIZE offset = 0;

        while (offset < subject_len) {
            int rc = pcre2_match(pw->code, (PCRE2_SPTR)text, subject_len, offset, 0, pw->match_data,
                                 nullptr);
            if (rc < 0) break;

            PCRE2_SIZE* ovector = pcre2_get_ovector_pointer(pw->match_data);
            PCRE2_SIZE start = ovector[0];
            PCRE2_SIZE end = ovector[1];

            if (!first) json += ",";
            first = false;

            std::string match_text(text + start, end - start);
            // Escape JSON special characters in the match text
            std::string escaped;
            for (char c : match_text) {
                switch (c) {
                    case '"':
                        escaped += "\\\"";
                        break;
                    case '\\':
                        escaped += "\\\\";
                        break;
                    case '\n':
                        escaped += "\\n";
                        break;
                    case '\r':
                        escaped += "\\r";
                        break;
                    case '\t':
                        escaped += "\\t";
                        break;
                    default:
                        escaped += c;
                }
            }

            char buf[256];
            snprintf(buf, sizeof(buf), "{\"start\":%zu,\"end\":%zu,\"match\":\"%s\"}",
                     (size_t)start, (size_t)end, escaped.c_str());
            json += buf;

            offset = (end > start) ? end : end + 1;  // advance past zero-length matches
        }

        json += "]";
        *json_result = strdup(json.c_str());
        return JPDFIUM_OK;

    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

void jpdfium_pcre2_free(int64_t pattern_handle) noexcept {
    // Membership is checked before any dereference, so a fabricated handle is
    // rejected rather than read. See HandleRegistry for the lifetime contract.
    auto* pw = reinterpret_cast<Pcre2Pattern8*>(pattern_handle);
    if (!pw || !Pcre2HandleRegistry::instance().contains(pw)) return;
    Pcre2HandleRegistry::instance().remove(pw);
    if (pw->match_data) pcre2_match_data_free(pw->match_data);
    if (pw->code) pcre2_code_free(pw->code);
    delete pw;
}

#else  // !JPDFIUM_HAS_PCRE2

int32_t jpdfium_pcre2_compile(const char*, uint32_t, int64_t* handle) {
    if (handle) *handle = 0;
    return JPDFIUM_ERR_NOT_FOUND;
}
int32_t jpdfium_pcre2_match_all(int64_t, const char*, char** json_result) {
    if (json_result) *json_result = strdup("[]");
    return JPDFIUM_ERR_NOT_FOUND;
}
void jpdfium_pcre2_free(int64_t) noexcept {}

#endif  // JPDFIUM_HAS_PCRE2

// Luhn Algorithm - pure C, no dependencies

int32_t jpdfium_luhn_validate(const char* number) {
    try {
        if (!number) return 0;

        int digits[32];
        int n = 0;
        for (const char* p = number; *p && n < 32; ++p) {
            if (*p >= '0' && *p <= '9')
                digits[n++] = *p - '0';
            else if (*p != ' ' && *p != '-')
                return 0;  // invalid character
        }

        // Credit cards: 13-19 digits (Visa 13/16/19, MC/Amex/Discover 15-16)
        if (n < 13 || n > 19) return 0;

        int sum = 0;
        for (int i = n - 1, alt = 0; i >= 0; --i, alt ^= 1) {
            int d = digits[i];
            if (alt) {
                d *= 2;
                if (d > 9) d -= 9;
            }
            sum += d;
        }
        return (sum % 10 == 0) ? 1 : 0;

    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

// FlashText Trie-based Keyword NER - O(n) matching

namespace {

struct TrieNode {
    std::unordered_map<char, std::unique_ptr<TrieNode>> children;
    std::string keyword;  // non-empty at terminal nodes
    std::string label;    // entity label at terminal nodes
};

struct FlashTextProcessor {
    std::unique_ptr<TrieNode> root;
    bool case_sensitive;

    FlashTextProcessor() : root(std::make_unique<TrieNode>()), case_sensitive(false) {}

    void addKeyword(const char* keyword, const char* label) {
        if (!keyword) return;
        TrieNode* node = root.get();
        for (const char* p = keyword; *p; ++p) {
            char c = case_sensitive
                         ? *p
                         : static_cast<char>(std::tolower(static_cast<unsigned char>(*p)));
            auto it = node->children.find(c);
            if (it == node->children.end()) {
                node->children[c] = std::make_unique<TrieNode>();
            }
            node = node->children[c].get();
        }
        node->keyword = keyword;
        node->label = label ? label : "";
    }

    std::string findAll(const char* text) {
        if (!text) return "[]";
        std::string result = "[";
        bool first = true;
        size_t textLen = strlen(text);

        for (size_t i = 0; i < textLen;) {
            TrieNode* node = root.get();
            size_t matchEnd = 0;
            std::string matchKeyword, matchLabel;
            bool found = false;

            for (size_t j = i; j < textLen; ++j) {
                char c = case_sensitive
                             ? text[j]
                             : static_cast<char>(std::tolower(static_cast<unsigned char>(text[j])));
                auto it = node->children.find(c);
                if (it == node->children.end()) break;
                node = it->second.get();
                if (!node->keyword.empty()) {
                    // Check word boundary: must be at start/end or adjacent to non-alphanumeric
                    bool leftBound = (j - (j - i) == 0) || !isalnum(text[j - (j - i) - 1]);
                    bool rightBound = (j + 1 >= textLen) || !isalnum(text[j + 1]);
                    if (leftBound && rightBound) {
                        matchEnd = j + 1;
                        matchKeyword = node->keyword;
                        matchLabel = node->label;
                        found = true;
                    }
                }
            }

            if (found) {
                if (!first) result += ",";
                first = false;
                char buf[512];
                snprintf(buf, sizeof(buf),
                         "{\"start\":%zu,\"end\":%zu,\"keyword\":\"%s\",\"label\":\"%s\"}", i,
                         matchEnd, matchKeyword.c_str(), matchLabel.c_str());
                result += buf;
                i = matchEnd;
            } else {
                ++i;
            }
        }

        result += "]";
        return result;
    }
};

}  // anonymous namespace

int32_t jpdfium_flashtext_create(int64_t* handle) {
    try {
        if (!handle) return JPDFIUM_ERR_INVALID;
        auto* ft = new FlashTextProcessor();
        FlashTextHandleRegistry::instance().add(ft);
        *handle = reinterpret_cast<int64_t>(ft);
        return JPDFIUM_OK;

    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

int32_t jpdfium_flashtext_add_keyword(int64_t handle, const char* keyword, const char* label) {
    try {
        auto* ft = reinterpret_cast<FlashTextProcessor*>(handle);
        if (!ft || !FlashTextHandleRegistry::instance().contains(ft)) return JPDFIUM_ERR_INVALID;
        if (!keyword) return JPDFIUM_ERR_INVALID;
        ft->addKeyword(keyword, label);
        return JPDFIUM_OK;

    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

int32_t jpdfium_flashtext_add_keywords_json(int64_t handle, const char* json) {
    try {
        auto* ft = reinterpret_cast<FlashTextProcessor*>(handle);
        if (!ft || !FlashTextHandleRegistry::instance().contains(ft)) return JPDFIUM_ERR_INVALID;
        if (!json) return JPDFIUM_ERR_INVALID;

        // Simple JSON parser for: [{"keyword":"...","label":"..."}, ...]
        const char* p = json;
        while (*p) {
            const char* kw_start = strstr(p, "\"keyword\":\"");
            if (!kw_start) break;
            kw_start += 11;  // skip "keyword":"
            const char* kw_end = strchr(kw_start, '"');
            if (!kw_end) break;
            std::string keyword(kw_start, kw_end - kw_start);

            std::string label;
            const char* lb_start = strstr(kw_end, "\"label\":\"");
            if (lb_start) {
                lb_start += 9;
                const char* lb_end = strchr(lb_start, '"');
                if (lb_end) label.assign(lb_start, lb_end - lb_start);
            }

            ft->addKeyword(keyword.c_str(), label.c_str());
            p = kw_end + 1;
        }
        return JPDFIUM_OK;

    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

int32_t jpdfium_flashtext_find(int64_t handle, const char* text, char** json_result) {
    try {
        auto* ft = reinterpret_cast<FlashTextProcessor*>(handle);
        if (!ft || !FlashTextHandleRegistry::instance().contains(ft)) return JPDFIUM_ERR_INVALID;
        if (!json_result) return JPDFIUM_ERR_INVALID;
        std::string result = ft->findAll(text);
        *json_result = strdup(result.c_str());
        return JPDFIUM_OK;

    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

void jpdfium_flashtext_free(int64_t handle) noexcept {
    // Identity checked before the delete; a fabricated handle is a no-op.
    auto* ft = reinterpret_cast<FlashTextProcessor*>(handle);
    if (!ft || !FlashTextHandleRegistry::instance().contains(ft)) return;
    FlashTextHandleRegistry::instance().remove(ft);
    delete ft;
}

// Font Normalization Pipeline - FreeType + HarfBuzz hb-subset

#if defined(JPDFIUM_HAS_FREETYPE) && defined(JPDFIUM_HAS_HARFBUZZ)

#include <ft2build.h>
#include FT_ADVANCES_H
#include FT_CID_H
#include FT_FONT_FORMATS_H
#include FT_FREETYPE_H
#include FT_SFNT_NAMES_H
#include FT_TRUETYPE_IDS_H
#include FT_TRUETYPE_TABLES_H

#include <fpdf_edit.h>
#include <fpdf_text.h>
#include <hb-subset.h>
#include <hb.h>

#include <set>

#include "jpdfium_internal.h"

#ifdef JPDFIUM_HAS_QPDF
#include <qpdf/Buffer.hh>
#include <qpdf/QPDF.hh>
#include <qpdf/QPDFExc.hh>
#include <qpdf/QPDFObjectHandle.hh>
#include <qpdf/QPDFPageDocumentHelper.hh>

// Defined further down in this translation unit's qpdf section. Declared here
// so the font pipeline below can reuse the same save/reload machinery.
static bool qpdf_save_fpdf(FPDF_DOCUMENT doc, std::vector<uint8_t>& out);
static std::vector<uint8_t> qpdf_write(QPDF& pdf);
static int32_t docwrapper_reload(DocWrapper* w, const std::vector<uint8_t>& newBytes);
static int32_t font_normalize_qpdf(DocWrapper* w, int32_t page_index, bool do_fix_tounicode,
                                   bool do_repair_widths, int32_t* fonts_processed,
                                   int32_t* tuc_fixed, int32_t* w_fixed, int32_t* type1_converted,
                                   int32_t* resubset);
#endif

static FT_Library ft_lib = nullptr;

static void ensure_freetype_init() {
    if (!ft_lib) FT_Init_FreeType(&ft_lib);
}

static std::string json_escape_fonts(std::string const& value) {
    std::string out;
    out.reserve(value.size());
    for (unsigned char c : value) {
        switch (c) {
            case '"':
                out += "\\\"";
                break;
            case '\\':
                out += "\\\\";
                break;
            case '\b':
                out += "\\b";
                break;
            case '\f':
                out += "\\f";
                break;
            case '\n':
                out += "\\n";
                break;
            case '\r':
                out += "\\r";
                break;
            case '\t':
                out += "\\t";
                break;
            default:
                if (c < 0x20) {
                    char escaped[7];
                    std::snprintf(escaped, sizeof escaped, "\\u%04x", c);
                    out += escaped;
                } else {
                    out += static_cast<char>(c);
                }
        }
    }
    return out;
}

int32_t jpdfium_font_get_data(int64_t page, int32_t font_index, uint8_t** data, int64_t* len) {
    try {
        PageWrapper* pw = decodePage(page);
        if (!pw || !pw->page || !data || !len) return JPDFIUM_ERR_INVALID;
        *data = nullptr;
        *len = 0;

        // Enumerate unique fonts on the page by walking text objects.
        int objCount = FPDFPage_CountObjects(pw->page);
        std::vector<FPDF_FONT> fonts;  // ordered list of unique fonts
        std::set<FPDF_FONT> seen;

        for (int i = 0; i < objCount; i++) {
            FPDF_PAGEOBJECT obj = FPDFPage_GetObject(pw->page, i);
            if (FPDFPageObj_GetType(obj) != FPDF_PAGEOBJ_TEXT) continue;
            FPDF_FONT font = FPDFTextObj_GetFont(obj);
            if (font && seen.insert(font).second) {
                fonts.push_back(font);
            }
        }

        if (font_index < 0 || font_index >= static_cast<int32_t>(fonts.size()))
            return JPDFIUM_ERR_NOT_FOUND;

        FPDF_FONT font = fonts[font_index];

        // Query required buffer size
        size_t buflen = 0;
        if (!FPDFFont_GetFontData(font, nullptr, 0, &buflen) || buflen == 0)
            return JPDFIUM_ERR_NOT_FOUND;

        // Allocate and fill
        uint8_t* buf = static_cast<uint8_t*>(malloc(buflen));
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
        ensure_freetype_init();

        FT_Face face;
        FT_Error err = FT_New_Memory_Face(ft_lib, data, (FT_Long)len, 0, &face);
        if (err) return JPDFIUM_ERR_INVALID;
        if (face->num_glyphs <= 1) {
            FT_Done_Face(face);
            return JPDFIUM_ERR_INVALID;
        }

        const char* type_str;
        bool sfnt = FT_IS_SFNT(face);
        bool has_cmap = (face->num_charmaps > 0);
        bool has_kerning = FT_HAS_KERNING(face);

        // Detect font type
        if (face->face_flags & FT_FACE_FLAG_SFNT) {
            // Check for CFF: presence of CFF table in sfnt container
            FT_ULong cff_len = 0;
            if (FT_Load_Sfnt_Table(face, FT_MAKE_TAG('C', 'F', 'F', ' '), 0, nullptr, &cff_len) ==
                0)
                type_str = "CFF";
            else if (FT_Load_Sfnt_Table(face, FT_MAKE_TAG('C', 'F', 'F', '2'), 0, nullptr,
                                        &cff_len) == 0)
                type_str = "CFF2";
            else
                type_str = "TrueType";
        } else {
            type_str = "Type1";
        }

        // Detect subset prefix (e.g. "ABCDEF+Arial")
        bool is_subset = false;
        if (face->family_name) {
            const char* plus = strchr(face->family_name, '+');
            if (plus && (plus - face->family_name) == 6) {
                is_subset = true;
                for (const char* c = face->family_name; c < plus; ++c) {
                    if (*c < 'A' || *c > 'Z') {
                        is_subset = false;
                        break;
                    }
                }
            }
        }

        std::string family = json_escape_fonts(face->family_name ? face->family_name : "");
        char head[384];
        snprintf(head, sizeof(head),
                 "{\"type\":\"%s\",\"sfnt\":%s,\"has_cmap\":%s,"
                 "\"num_glyphs\":%ld,\"units_per_em\":%d,\"has_kerning\":%s,"
                 "\"is_subset\":%s,\"family\":\"",
                 type_str, sfnt ? "true" : "false", has_cmap ? "true" : "false", face->num_glyphs,
                 (int)face->units_per_EM, has_kerning ? "true" : "false",
                 is_subset ? "true" : "false");
        std::string jsonText = head;
        jsonText += family;
        jsonText += "\"}";

        FT_Done_Face(face);
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
        ensure_freetype_init();

        FT_Face face;
        if (FT_New_Memory_Face(ft_lib, data, (FT_Long)len, 0, &face)) return JPDFIUM_ERR_INVALID;
        // Map codepoints, not glyph ids: without this a symbol charmap misreports.
        if (face->num_charmaps > 0) FT_Select_Charmap(face, FT_ENCODING_UNICODE);

        for (int32_t i = 0; i < count; ++i) {
            uint32_t cp = (uint32_t)codepoints[i];
            // Index 0 is the .notdef placeholder, never a real glyph.
            out_covered[i] = (cp <= 0x10FFFF && FT_Get_Char_Index(face, cp) != 0) ? 1 : 0;
        }
        FT_Done_Face(face);
        return JPDFIUM_OK;

    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

int32_t jpdfium_text_shape(const uint8_t* font_data, int64_t font_len, const char* utf8_text,
                           float font_size, char** json) {
    try {
        // 26.6 scale: reject sizes whose scaled value overflows the int cast.
        if (!font_data || font_len <= 0 || !utf8_text || !json || !std::isfinite(font_size) ||
            font_size <= 0 || font_size * 64.0 > (double)INT32_MAX)
            return JPDFIUM_ERR_INVALID;
        hb_blob_t* blob = hb_blob_create((const char*)font_data, (unsigned int)font_len,
                                         HB_MEMORY_MODE_READONLY, nullptr, nullptr);
        hb_face_t* face = hb_face_create(blob, 0);
        hb_blob_destroy(blob);
        // hb_face_create never fails: corrupt bytes yield an empty face whose
        // every codepoint maps to glyph 0. Reject those, as font_covers_text does.
        if (hb_face_get_glyph_count(face) <= 1) {
            hb_face_destroy(face);
            return JPDFIUM_ERR_INVALID;
        }
        hb_font_t* font = hb_font_create(face);
        hb_face_destroy(face);
        // Advances come out in 26.6 fractional points at this scale.
        hb_font_set_scale(font, (int)(font_size * 64), (int)(font_size * 64));

        hb_buffer_t* buf = hb_buffer_create();
        hb_buffer_add_utf8(buf, utf8_text, -1, 0, -1);
        // Script/direction/language default from content; same as plain hb-shape.
        // One buffer is one run: mixed direction/script must be split by the caller.
        hb_buffer_guess_segment_properties(buf);
        hb_shape(font, buf, nullptr, 0);

        unsigned int count = 0;
        hb_glyph_info_t* infos = hb_buffer_get_glyph_infos(buf, &count);
        hb_glyph_position_t* pos = hb_buffer_get_glyph_positions(buf, &count);

        std::string out = "[";
        char entry[128];
        for (unsigned int i = 0; i < count; ++i) {
            if (i) out += ",";
            snprintf(entry, sizeof(entry),
                     "{\"g\":%u,\"ax\":%d,\"ay\":%d,\"dx\":%d,\"dy\":%d,\"cluster\":%u}",
                     infos[i].codepoint, pos[i].x_advance, pos[i].y_advance, pos[i].x_offset,
                     pos[i].y_offset, infos[i].cluster);
            out += entry;
        }
        out += "]";
        hb_buffer_destroy(buf);
        hb_font_destroy(font);
        *json = strdup(out.c_str());
        return JPDFIUM_OK;

    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

#ifdef JPDFIUM_HAS_QPDF

// UTF-16BE hex for a PDF /ToUnicode destination. Non-BMP codepoints become a
// surrogate pair, which is what a UCS-2 CMap consumer expects.
static std::string pdf_utf16be_hex(uint32_t codepoint) {
    char buf[16];
    if (codepoint <= 0xFFFF) {
        std::snprintf(buf, sizeof(buf), "%04X", codepoint);
        return buf;
    }
    uint32_t v = codepoint - 0x10000;
    std::snprintf(buf, sizeof(buf), "%04X%04X", 0xD800 + (v >> 10), 0xDC00 + (v & 0x3FF));
    return buf;
}

// A /ToUnicode destination can be a sequence of scalars (ligatures).
static std::string pdf_utf16be_hex(std::vector<uint32_t> const& codepoints) {
    std::string out;
    for (uint32_t codepoint : codepoints) out += pdf_utf16be_hex(codepoint);
    return out;
}

namespace {

using namespace jpdfium_font_encoding;

FT_UInt glyphForName(FT_Face face, std::string const& name) {
    if (!face) return 0;
    if (FT_HAS_GLYPH_NAMES(face)) {
        if (FT_UInt gid = FT_Get_Name_Index(face, name.c_str())) return gid;
    }
    uint32_t u = unicodeForGlyphName(name);
    if (u && !FT_Select_Charmap(face, FT_ENCODING_UNICODE)) return FT_Get_Char_Index(face, u);
    return 0;
}

FT_UInt gidForUnicode(FT_Face face, uint32_t unicode, bool has_unicode_charmap) {
    if (!face || !has_unicode_charmap || unicode == 0) return 0;
    if (FT_Select_Charmap(face, FT_ENCODING_UNICODE) != 0) return 0;
    return FT_Get_Char_Index(face, unicode);
}

// TrueType symbolic fonts are normally mapped through cmap (3,0), which
// expects byte codes in the 0xF000 private-use range; Apple Roman is the other
// common fallback for simple fonts without a usable Unicode cmap.
FT_UInt gidFromSymbolicCharmaps(FT_Face face, uint32_t code) {
    if (!face) return 0;
    auto tryCharmap = [&](FT_Encoding encoding) -> FT_UInt {
        if (FT_Select_Charmap(face, encoding) != 0) return 0;
        if (FT_UInt gid = FT_Get_Char_Index(face, code)) return gid;
        if (FT_UInt gid = FT_Get_Char_Index(face, 0xF000 | code)) return gid;
        return 0;
    };
    if (FT_UInt gid = tryCharmap(FT_ENCODING_MS_SYMBOL)) return gid;
    if (FT_UInt gid = tryCharmap(FT_ENCODING_APPLE_ROMAN)) return gid;
    for (int i = 0; i < face->num_charmaps; ++i) {
        FT_CharMap cmap = face->charmaps[i];
        if (!cmap || cmap->encoding == FT_ENCODING_UNICODE) continue;
        if (FT_Set_Charmap(face, cmap) != 0) continue;
        if (FT_UInt gid = FT_Get_Char_Index(face, code)) return gid;
        if (FT_UInt gid = FT_Get_Char_Index(face, 0xF000 | code)) return gid;
    }
    return 0;
}

struct SimpleEncoding {
    BaseEncoding base = BaseEncoding::Standard;
    bool hasExplicitEncoding = false;
    bool hasBaseEncoding = false;
    bool unsupportedEncoding = false;
    bool symbolic = false;
    std::map<int, std::string> differences;
};

SimpleEncoding parseSimpleEncoding(QPDFObjectHandle const& font,
                                   QPDFObjectHandle const& descriptor) {
    SimpleEncoding enc;
    QPDFObjectHandle encoding = font.getKey("/Encoding");
    QPDFObjectHandle base;
    if (encoding.isName()) {
        enc.hasExplicitEncoding = true;
        base = encoding;
    } else if (encoding.isDictionary()) {
        enc.hasExplicitEncoding = true;
        base = encoding.getKey("/BaseEncoding");
        QPDFObjectHandle differences = encoding.getKey("/Differences");
        if (differences.isArray()) {
            int code = 0;
            for (auto& item : differences.getArrayAsVector()) {
                if (item.isInteger()) {
                    code = item.getIntValueAsInt();
                } else if (item.isName()) {
                    if (code >= 0 && code <= 255) enc.differences[code] = item.getName().substr(1);
                    // Keep advancing only inside the single-byte range. A
                    // malformed /Differences can contain INT_MAX.
                    if (code < 256) ++code;
                }
            }
        }
    }
    if (base.isName()) {
        if (base.isNameAndEquals("/WinAnsiEncoding")) {
            enc.base = BaseEncoding::WinAnsi;
            enc.hasBaseEncoding = true;
        } else if (base.isNameAndEquals("/MacRomanEncoding")) {
            enc.base = BaseEncoding::MacRoman;
            enc.hasBaseEncoding = true;
        } else if (base.isNameAndEquals("/StandardEncoding")) {
            enc.base = BaseEncoding::Standard;
            enc.hasBaseEncoding = true;
        } else {
            // MacExpertEncoding and other vendor encodings need a name table we
            // do not have; never guess glyph mappings for those fonts.
            enc.unsupportedEncoding = true;
        }
    }
    QPDFObjectHandle flags = descriptor.getKey("/Flags");
    enc.symbolic = flags.isInteger() && (flags.getIntValue() & 4) != 0;
    return enc;
}

struct SimpleGlyph {
    FT_UInt gid = 0;
    uint32_t unicode = 0;
};

// Resolve a byte code through the font program's built-in text encoding.
// Type 1 fonts without a PDF /BaseEncoding keep their glyph order in the
// Adobe custom/standard charmap, or expose it only through glyph names.
FT_UInt gidFromBuiltInEncoding(FT_Face face, uint32_t code) {
    if (!face) return 0;
    auto tryEncoding = [&](FT_Encoding encoding) -> FT_UInt {
        if (FT_Select_Charmap(face, encoding) != 0) return 0;
        return FT_Get_Char_Index(face, code);
    };
    if (FT_UInt gid = tryEncoding(FT_ENCODING_ADOBE_CUSTOM)) return gid;
    if (FT_UInt gid = tryEncoding(FT_ENCODING_ADOBE_STANDARD)) return gid;
    return gidFromSymbolicCharmaps(face, code);
}

uint32_t unicodeFromGlyphNameOnly(FT_Face face, FT_UInt gid) {
    if (!face || !gid || !FT_HAS_GLYPH_NAMES(face)) return 0;
    char name[256];
    if (FT_Get_Glyph_Name(face, gid, name, sizeof(name)) != 0 || !name[0]) return 0;
    return unicodeForGlyphName(name);
}

uint32_t unicodeFromGid(FT_Face face, FT_UInt gid,
                        std::map<uint32_t, uint32_t> const& gid_to_unicode) {
    if (!gid) return 0;
    auto found = gid_to_unicode.find(gid);
    if (found != gid_to_unicode.end()) return found->second;
    return unicodeFromGlyphNameOnly(face, gid);
}

std::vector<SimpleGlyph> resolveSimpleChars(FT_Face face, bool has_unicode_charmap,
                                            std::map<uint32_t, uint32_t> const& gid_to_unicode,
                                            SimpleEncoding const& enc, bool is_type1_family) {
    std::vector<SimpleGlyph> resolved(256);
    // A Type 1 font with no /BaseEncoding must be mapped through its built-in
    // encoding: StandardEncoding is only a PDF fallback, not the program order.
    const bool builtin_encoding = is_type1_family && !enc.hasBaseEncoding;
    const bool non_symbolic_with_base = !enc.symbolic && (enc.hasBaseEncoding || !is_type1_family);
    for (uint32_t code = 0; code <= 0xFF; ++code) {
        SimpleGlyph entry;
        auto diff = enc.differences.find(static_cast<int>(code));
        if (diff != enc.differences.end()) {
            if (diff->second != ".notdef") {
                entry.unicode = unicodeForGlyphName(diff->second);
                entry.gid = glyphForName(face, diff->second);
                if (entry.gid != 0 && entry.unicode == 0)
                    entry.unicode = unicodeFromGid(face, entry.gid, gid_to_unicode);
            }
        } else if (builtin_encoding) {
            entry.gid = gidFromBuiltInEncoding(face, code);
            if (entry.gid != 0) entry.unicode = unicodeFromGid(face, entry.gid, gid_to_unicode);
        } else if (!enc.symbolic || enc.hasExplicitEncoding) {
            entry.unicode = baseEncodingUnicode(enc.base, static_cast<int>(code));
            entry.gid = gidForUnicode(face, entry.unicode, has_unicode_charmap);
            if (entry.gid == 0 && non_symbolic_with_base) {
                // The font may lack the non-breaking space/hyphen scalars but
                // still carry the ASCII glyph. Keep the Unicode value intact.
                uint32_t fallback = entry.unicode == 0xA0 ? 0x20 : entry.unicode == 0xAD ? 0x2D : 0;
                if (fallback) entry.gid = gidForUnicode(face, fallback, has_unicode_charmap);
            } else if (entry.gid == 0) {
                // Symbolic (or Type 1 without a base encoding) fonts commonly
                // expose byte codes through a non-Unicode cmap.
                entry.gid = gidFromSymbolicCharmaps(face, code);
            }
        } else {
            entry.gid = gidFromSymbolicCharmaps(face, code);
            if (entry.gid != 0) entry.unicode = unicodeFromGid(face, entry.gid, gid_to_unicode);
        }
        resolved[code] = entry;
    }
    return resolved;
}

}  // namespace

static bool visit_stream(std::map<QPDFObjGen, int>& seen_streams, QPDFObjectHandle const& stream,
                         int depth) {
    if (!stream.isIndirect()) return true;
    auto [it, inserted] = seen_streams.emplace(stream.getObjGen(), depth);
    if (!inserted && it->second <= depth) return false;
    it->second = depth;
    return true;
}

static void collect_resource_fonts(QPDFObjectHandle resources, int depth,
                                   std::set<QPDFObjGen>& seen_resources,
                                   std::set<QPDFObjGen>& seen_fonts,
                                   std::map<QPDFObjGen, int>& seen_streams,
                                   std::vector<QPDFObjectHandle>& fonts) {
    if (!resources.isDictionary() || depth > 32) return;
    if (resources.isIndirect() && !seen_resources.insert(resources.getObjGen()).second) return;

    QPDFObjectHandle font_dict = resources.getKey("/Font");
    if (font_dict.isDictionary()) {
        for (auto const& name : font_dict.getKeys()) {
            QPDFObjectHandle font = font_dict.getKey(name);
            if (!font.isDictionary()) continue;
            // Shared fonts are reached through several pages/resources; repair
            // each dictionary once.
            if (font.isIndirect() && !seen_fonts.insert(font.getObjGen()).second) continue;
            fonts.push_back(font);
            // Type 3 glyph procedures carry their own resources and can embed
            // further fonts (including Type 3 fonts).
            if (font.getKey("/Subtype").isNameAndEquals("/Type3")) {
                collect_resource_fonts(font.getKey("/Resources"), depth + 1, seen_resources,
                                       seen_fonts, seen_streams, fonts);
                QPDFObjectHandle char_procs = font.getKey("/CharProcs");
                if (char_procs.isDictionary()) {
                    for (auto const& glyph_name : char_procs.getKeys()) {
                        QPDFObjectHandle glyph = char_procs.getKey(glyph_name);
                        if (!glyph.isStream()) continue;
                        if (!visit_stream(seen_streams, glyph, depth + 1)) continue;
                        collect_resource_fonts(glyph.getDict().getKey("/Resources"), depth + 1,
                                               seen_resources, seen_fonts, seen_streams, fonts);
                    }
                }
            }
        }
    }

    // /ExtGState can reference fonts directly (soft-mask groups and the
    // non-stroking transparency group's /Font array).
    QPDFObjectHandle ext_gstates = resources.getKey("/ExtGState");
    if (ext_gstates.isDictionary()) {
        for (auto const& gs_name : ext_gstates.getKeys()) {
            QPDFObjectHandle gs = ext_gstates.getKey(gs_name);
            if (!gs.isDictionary()) continue;
            QPDFObjectHandle gs_fonts = gs.getKey("/Font");
            if (gs_fonts.isArray()) {
                const int count = gs_fonts.getArrayNItems();
                for (int i = 0; i < count; ++i) {
                    QPDFObjectHandle font = gs_fonts.getArrayItem(i);
                    if (!font.isDictionary()) continue;
                    if (font.isIndirect() && !seen_fonts.insert(font.getObjGen()).second) continue;
                    fonts.push_back(font);
                }
            }
            QPDFObjectHandle smask = gs.getKey("/SMask");
            if (smask.isDictionary()) {
                QPDFObjectHandle group = smask.getKey("/G");
                if (group.isStream() && visit_stream(seen_streams, group, depth + 1))
                    collect_resource_fonts(group.getDict().getKey("/Resources"), depth + 1,
                                           seen_resources, seen_fonts, seen_streams, fonts);
            }
        }
    }

    QPDFObjectHandle xobjects = resources.getKey("/XObject");
    if (xobjects.isDictionary()) {
        for (auto const& name : xobjects.getKeys()) {
            QPDFObjectHandle xobject = xobjects.getKey(name);
            if (!xobject.isStream()) continue;
            QPDFObjectHandle subtype = xobject.getDict().getKey("/Subtype");
            if (!subtype.isName() || subtype.getName() != "/Form") continue;
            if (!visit_stream(seen_streams, xobject, depth + 1)) continue;
            collect_resource_fonts(xobject.getDict().getKey("/Resources"), depth + 1,
                                   seen_resources, seen_fonts, seen_streams, fonts);
        }
    }

    QPDFObjectHandle patterns = resources.getKey("/Pattern");
    if (patterns.isDictionary()) {
        for (auto const& name : patterns.getKeys()) {
            QPDFObjectHandle pattern = patterns.getKey(name);
            if (!pattern.isStream()) continue;
            if (!visit_stream(seen_streams, pattern, depth + 1)) continue;
            collect_resource_fonts(pattern.getDict().getKey("/Resources"), depth + 1,
                                   seen_resources, seen_fonts, seen_streams, fonts);
        }
    }
}

// Page /Resources inherit from the /Pages tree. Nested form XObjects,
// annotation appearance streams, and patterns carry their own /Resources.
static std::vector<QPDFObjectHandle> collect_page_fonts(QPDFObjectHandle page,
                                                        std::set<QPDFObjGen>& seen_resources,
                                                        std::set<QPDFObjGen>& seen_fonts) {
    std::vector<QPDFObjectHandle> fonts;
    std::map<QPDFObjGen, int> seen_streams;

    QPDFObjectHandle resources = page.getKey("/Resources");
    if (!resources.isDictionary()) {
        QPDFObjectHandle parent = page.getKey("/Parent");
        for (int depth = 0; depth < 32 && parent.isDictionary() && !resources.isDictionary();
             ++depth) {
            resources = parent.getKey("/Resources");
            parent = parent.getKey("/Parent");
        }
    }
    collect_resource_fonts(resources, 0, seen_resources, seen_fonts, seen_streams, fonts);

    QPDFObjectHandle annots = page.getKey("/Annots");
    if (annots.isArray()) {
        const int annot_count = annots.getArrayNItems();
        for (int i = 0; i < annot_count; ++i) {
            QPDFObjectHandle annot = annots.getArrayItem(i);
            if (!annot.isDictionary()) continue;
            QPDFObjectHandle ap = annot.getKey("/AP");
            if (!ap.isDictionary()) continue;
            for (auto const& key : ap.getKeys()) {
                QPDFObjectHandle appearance = ap.getKey(key);
                if (appearance.isStream()) {
                    collect_resource_fonts(appearance.getDict().getKey("/Resources"), 0,
                                           seen_resources, seen_fonts, seen_streams, fonts);
                } else if (appearance.isDictionary()) {
                    for (auto const& state_key : appearance.getKeys()) {
                        QPDFObjectHandle state_stream = appearance.getKey(state_key);
                        if (!state_stream.isStream()) continue;
                        collect_resource_fonts(state_stream.getDict().getKey("/Resources"), 0,
                                               seen_resources, seen_fonts, seen_streams, fonts);
                    }
                }
            }
        }
    }
    return fonts;
}

static std::string build_tounicode_cmap(std::map<uint32_t, std::vector<uint32_t>> const& mapping,
                                        bool cid) {
    std::vector<std::pair<uint32_t, std::vector<uint32_t>>> valid;
    valid.reserve(mapping.size());
    for (auto const& [code, seq] : mapping) {
        if (seq.empty()) continue;
        bool ok = true;
        for (uint32_t u : seq) {
            if (u == 0 || u == 0xFEFF || u == 0xFFFE || (u >= 0xD800 && u <= 0xDFFF) ||
                u > 0x10FFFF) {
                ok = false;
                break;
            }
        }
        if (ok) valid.emplace_back(code, seq);
    }
    if (valid.empty()) return {};

    std::string out;
    out += "/CIDInit /ProcSet findresource begin\n";
    out += "12 dict begin\n";
    out += "begincmap\n";
    out += "/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n";
    out += "/CMapName /Adobe-Identity-UCS def\n";
    out += "/CMapType 2 def\n";
    out += "1 begincodespacerange\n";
    out += cid ? "<0000> <FFFF>\n" : "<00> <FF>\n";
    out += "endcodespacerange\n";

    size_t remaining = valid.size();
    auto it = valid.begin();
    while (remaining > 0) {
        size_t chunk = remaining > 100 ? 100 : remaining;
        out += std::to_string(chunk) + " beginbfchar\n";
        for (size_t i = 0; i < chunk; ++i, ++it) {
            char code[16];
            if (cid) {
                std::snprintf(code, sizeof(code), "%04X", it->first);
            } else {
                std::snprintf(code, sizeof(code), "%02X", it->first);
            }
            out += "<";
            out += code;
            out += "> <";
            out += pdf_utf16be_hex(it->second);
            out += ">\n";
        }
        out += "endbfchar\n";
        remaining -= chunk;
    }

    out += "endcmap\n";
    out += "CMapName currentdict /CMap defineresource pop\n";
    out += "end\n";
    out += "end\n";
    return out;
}

static bool pdf_width_from_glyph(FT_Face face, FT_UInt gid, int& out_width) {
    if (!face || gid == 0 || face->units_per_EM == 0) return false;
    FT_Fixed advance = 0;
    if (FT_Get_Advance(face, gid, FT_LOAD_NO_SCALE, &advance) != 0) return false;
    const long long units = face->units_per_EM;
    out_width = static_cast<int>((static_cast<long long>(advance) * 1000 + units / 2) / units);
    return true;
}

// A /ToUnicode CMap counts as present only when it actually maps a code. Some
// producers emit the boilerplate prologue with no bfchar/bfrange entries.
static bool cmapSectionHasMapping(std::string const& body) {
    int hexStrings = 0;
    size_t open = 0;
    while ((open = body.find('<', open)) != std::string::npos) {
        auto close = body.find('>', open + 1);
        if (close == std::string::npos) break;
        bool non_empty = false;
        for (size_t i = open + 1; i < close; ++i)
            if (!std::isspace(static_cast<unsigned char>(body[i]))) {
                non_empty = true;
                break;
            }
        if (non_empty) ++hexStrings;
        open = close + 1;
    }
    return hexStrings >= 2;
}

static bool cmapHasMappings(std::string const& text) {
    for (auto const& section :
         {std::pair<char const*, char const*>("beginbfchar", "endbfchar"),
          std::pair<char const*, char const*>("beginbfrange", "endbfrange")}) {
        size_t pos = 0;
        while ((pos = text.find(section.first, pos)) != std::string::npos) {
            size_t end = text.find(section.second, pos);
            if (end == std::string::npos) break;
            if (cmapSectionHasMapping(text.substr(pos, end - pos))) return true;
            pos = end + 1;
        }
    }
    return false;
}

// ---------------------------------------------------------------------------
// Helpers for embedded-font scanning, collection face selection and rewriting.

static constexpr uint32_t kFontMaxCid = 0xFFFF;

// Normalize a PDF font name for cross-source comparison: drop the leading '/',
// strip a six-letter subset prefix (e.g. ABCDEF+), and ignore spaces, hyphens
// and case so PostScript names and family names can be compared.
static std::string font_name_key(std::string name) {
    if (!name.empty() && name.front() == '/') name.erase(name.begin());
    if (name.size() > 7 && name[6] == '+' &&
        std::all_of(name.begin(), name.begin() + 6,
                    [](unsigned char c) { return c >= 'A' && c <= 'Z'; }))
        name.erase(0, 7);
    std::string key;
    key.reserve(name.size());
    for (unsigned char c : name) {
        if (c == ' ' || c == '-') continue;
        key += static_cast<char>(std::tolower(c));
    }
    return key;
}

static bool font_name_is_subset_tagged(std::string name) {
    if (!name.empty() && name.front() == '/') name.erase(name.begin());
    return name.size() > 7 && name[6] == '+' &&
           std::all_of(name.begin(), name.begin() + 6,
                       [](unsigned char c) { return c >= 'A' && c <= 'Z'; });
}

static std::string make_font_subset_tag(std::string const& name, std::set<FT_UInt> const& glyphs) {
    size_t hash = std::hash<std::string>{}(name);
    for (FT_UInt g : glyphs) hash = hash * 31 + g;
    std::string tag;
    for (int i = 0; i < 6; ++i, hash /= 26) tag += static_cast<char>('A' + hash % 26);
    return tag;
}

static QPDFObjectHandle embedded_font_program(QPDFObjectHandle const& descriptor) {
    for (char const* key : {"/FontFile2", "/FontFile3", "/FontFile"}) {
        QPDFObjectHandle candidate = descriptor.getKey(key);
        if (candidate.isStream()) return candidate;
    }
    return QPDFObjectHandle();
}

static bool font_forbids_subsetting(FT_Face face) {
    FT_ULong length = 0;
    if (FT_Load_Sfnt_Table(face, FT_MAKE_TAG('O', 'S', '/', '2'), 0, nullptr, &length) ||
        length < 10)
        return false;
    std::vector<uint8_t> os2(length);
    FT_ULong loaded = length;
    if (FT_Load_Sfnt_Table(face, FT_MAKE_TAG('O', 'S', '/', '2'), 0, os2.data(), &loaded) ||
        loaded < 10)
        return false;
    const uint16_t fs_type = static_cast<uint16_t>((os2[8] << 8) | os2[9]);
    return (fs_type & 0x0100) != 0;
}

static bool font_has_sfnt_table(FT_Face face, FT_ULong tag) {
    FT_ULong length = 0;
    return FT_Load_Sfnt_Table(face, tag, 0, nullptr, &length) == 0 && length > 0;
}

// A TrueType/OpenType collection exposes several faces behind one stream. Pick
// the face whose PostScript or family name matches the PDF font name; callers
// keep the returned index for hb_face_create.
static FT_Long select_collection_face(FT_Library lib, const uint8_t* data, size_t size,
                                      std::set<std::string> const& desired) {
    if (!data || size < 12 || std::memcmp(data, "ttcf", 4) != 0 || desired.empty()) return 0;
    FT_Face probe = nullptr;
    if (FT_New_Memory_Face(lib, reinterpret_cast<const FT_Byte*>(data), static_cast<FT_Long>(size),
                           0, &probe) != 0 ||
        !probe)
        return 0;
    const FT_Long num_faces = probe->num_faces;
    FT_Done_Face(probe);
    if (num_faces <= 1) return 0;
    for (FT_Long i = 1; i < num_faces; ++i) {
        FT_Face candidate = nullptr;
        if (FT_New_Memory_Face(lib, reinterpret_cast<const FT_Byte*>(data),
                               static_cast<FT_Long>(size), i, &candidate) != 0 ||
            !candidate)
            continue;
        bool matched = false;
        if (const char* ps = FT_Get_Postscript_Name(candidate))
            matched = desired.contains(font_name_key(ps));
        if (!matched && candidate->family_name)
            matched = desired.contains(font_name_key(candidate->family_name));
        FT_Done_Face(candidate);
        if (matched) return i;
    }
    return 0;
}

static FT_Face load_font_face(FT_Library lib, std::string const& bytes, FT_Long preferred_index) {
    if (bytes.empty()) return nullptr;
    FT_Face face = nullptr;
    if (FT_New_Memory_Face(lib, reinterpret_cast<const FT_Byte*>(bytes.data()),
                           static_cast<FT_Long>(bytes.size()), preferred_index, &face) == 0 &&
        face)
        return face;
    if (preferred_index != 0 &&
        FT_New_Memory_Face(lib, reinterpret_cast<const FT_Byte*>(bytes.data()),
                           static_cast<FT_Long>(bytes.size()), 0, &face) == 0 &&
        face)
        return face;
    return nullptr;
}

// Minimal content-stream scanner: track the selected font across Tf/q/Q and
// collect the character codes or CIDs drawn by text operators.
static bool fn_is_pdf_delimiter(unsigned char c) {
    return c == '(' || c == ')' || c == '<' || c == '>' || c == '[' || c == ']' || c == '{' ||
           c == '}' || c == '/' || c == '%';
}

static bool fn_is_pdf_whitespace(unsigned char c) {
    // ISO 32000-1 7.2.2: NUL is whitespace in content streams. std::isspace
    // does not classify it, which would desynchronize the tokenizer on
    // producers that pad operands with NUL.
    return c == 0x00 || c == 0x09 || c == 0x0A || c == 0x0C || c == 0x0D || c == 0x20;
}

struct FnContentOperand {
    enum class Kind { Name, String, Array, Other };
    Kind kind = Kind::Other;
    std::string text;
    std::vector<std::string> strings;
};

struct FnContentToken {
    bool word = false;
    std::string text;
    FnContentOperand operand;
};

struct FnContentLexer {
    std::string_view data;
    size_t pos = 0;
    bool malformed = false;
    int array_depth = 0;

    void skipSpace() {
        while (pos < data.size()) {
            unsigned char c = static_cast<unsigned char>(data[pos]);
            if (fn_is_pdf_whitespace(c)) {
                ++pos;
            } else if (c == '%') {
                while (pos < data.size() && data[pos] != '\n' && data[pos] != '\r') ++pos;
            } else {
                break;
            }
        }
    }

    bool literal(std::string& out) {
        if (pos >= data.size() || data[pos] != '(') return false;
        ++pos;
        int depth = 1;
        while (pos < data.size() && depth > 0) {
            char c = data[pos++];
            if (c == '\\') {
                if (pos >= data.size()) break;
                char e = data[pos++];
                switch (e) {
                    case 'n':
                        out += '\n';
                        break;
                    case 'r':
                        out += '\r';
                        break;
                    case 't':
                        out += '\t';
                        break;
                    case 'b':
                        out += '\b';
                        break;
                    case 'f':
                        out += '\f';
                        break;
                    case '\n':
                        break;
                    case '\r':
                        if (pos < data.size() && data[pos] == '\n') ++pos;
                        break;
                    default:
                        if (e >= '0' && e <= '7') {
                            int value = e - '0';
                            for (int i = 0;
                                 i < 2 && pos < data.size() && data[pos] >= '0' && data[pos] <= '7';
                                 ++i) {
                                value = value * 8 + (data[pos++] - '0');
                            }
                            out += static_cast<char>(value & 0xFF);
                        } else {
                            out += e;
                        }
                }
            } else if (c == '(') {
                ++depth;
                out += c;
            } else if (c == ')') {
                --depth;
                if (depth > 0) out += c;
            } else {
                out += c;
            }
        }
        return depth == 0;
    }

    bool hex(std::string& out) {
        if (pos >= data.size() || data[pos] != '<') return false;
        ++pos;
        int first_nibble = -1;
        while (pos < data.size() && data[pos] != '>') {
            unsigned char c = static_cast<unsigned char>(data[pos++]);
            if (fn_is_pdf_whitespace(c)) continue;
            int val = -1;
            if (c >= '0' && c <= '9') {
                val = c - '0';
            } else if (c >= 'a' && c <= 'f') {
                val = c - 'a' + 10;
            } else if (c >= 'A' && c <= 'F') {
                val = c - 'A' + 10;
            } else {
                return false;
            }
            if (first_nibble < 0) {
                first_nibble = val;
            } else {
                out += static_cast<char>((first_nibble << 4) | val);
                first_nibble = -1;
            }
        }
        if (pos >= data.size()) return false;
        ++pos;
        if (first_nibble >= 0) {
            out += static_cast<char>(first_nibble << 4);
        }
        return true;
    }

    bool name(std::string& out) {
        if (pos >= data.size() || data[pos] != '/') return false;
        ++pos;
        auto hex_val = [](char ch) -> int {
            if (ch >= '0' && ch <= '9') return ch - '0';
            if (ch >= 'a' && ch <= 'f') return ch - 'a' + 10;
            if (ch >= 'A' && ch <= 'F') return ch - 'A' + 10;
            return 0;
        };
        while (pos < data.size()) {
            unsigned char c = static_cast<unsigned char>(data[pos]);
            if (fn_is_pdf_whitespace(c) || fn_is_pdf_delimiter(c)) {
                break;
            }
            if (c == '#' && pos + 2 < data.size() &&
                std::isxdigit(static_cast<unsigned char>(data[pos + 1])) &&
                std::isxdigit(static_cast<unsigned char>(data[pos + 2]))) {
                out += static_cast<char>((hex_val(data[pos + 1]) << 4) | hex_val(data[pos + 2]));
                pos += 3;
            } else {
                out += static_cast<char>(c);
                ++pos;
            }
        }
        return true;
    }

    bool dictionary() {
        if (pos + 1 >= data.size() || data[pos] != '<' || data[pos + 1] != '<') return false;
        pos += 2;
        int depth = 1;
        while (pos < data.size() && depth > 0) {
            if (data[pos] == '(') {
                std::string ignored;
                if (!literal(ignored)) return false;
            } else if (data[pos] == '<' && pos + 1 < data.size() && data[pos + 1] == '<') {
                pos += 2;
                ++depth;
            } else if (data[pos] == '>' && pos + 1 < data.size() && data[pos + 1] == '>') {
                pos += 2;
                --depth;
            } else if (data[pos] == '<') {
                std::string ignored;
                if (!hex(ignored)) return false;
            } else {
                ++pos;
            }
        }
        return depth == 0;
    }

    bool array(FnContentOperand& out) {
        if (pos >= data.size() || data[pos] != '[') return false;
        ++pos;
        if (++array_depth > 32) {
            malformed = true;
            --array_depth;
            return false;
        }
        out.kind = FnContentOperand::Kind::Array;
        while (pos < data.size()) {
            skipSpace();
            if (pos >= data.size()) {
                --array_depth;
                return false;
            }
            if (data[pos] == ']') {
                ++pos;
                --array_depth;
                return true;
            }
            FnContentToken token;
            if (!next(token)) {
                --array_depth;
                return false;
            }
            if (!token.word) {
                if (token.operand.kind == FnContentOperand::Kind::String) {
                    out.strings.push_back(token.operand.text);
                } else if (token.operand.kind == FnContentOperand::Kind::Array) {
                    out.strings.insert(out.strings.end(), token.operand.strings.begin(),
                                       token.operand.strings.end());
                }
            }
        }
        --array_depth;
        return false;
    }

    bool next(FnContentToken& out) {
        skipSpace();
        if (pos >= data.size()) return false;
        out = FnContentToken{};
        char c = data[pos];
        if (c == '(') {
            out.operand.kind = FnContentOperand::Kind::String;
            if (!literal(out.operand.text)) {
                malformed = true;
                return false;
            }
            return true;
        }
        if (c == '<') {
            if (pos + 1 < data.size() && data[pos + 1] == '<') {
                out.operand.kind = FnContentOperand::Kind::Other;
                if (!dictionary()) {
                    malformed = true;
                    return false;
                }
                return true;
            }
            out.operand.kind = FnContentOperand::Kind::String;
            if (!hex(out.operand.text)) {
                malformed = true;
                return false;
            }
            return true;
        }
        if (c == '[') {
            if (!array(out.operand)) {
                malformed = true;
                return false;
            }
            return true;
        }
        if (c == ']' || c == '>' || c == ')' || c == '}' || c == '{') {
            ++pos;
            out.operand.kind = FnContentOperand::Kind::Other;
            return true;
        }
        if (c == '/') {
            out.operand.kind = FnContentOperand::Kind::Name;
            return name(out.operand.text);
        }
        size_t start = pos;
        while (pos < data.size()) {
            unsigned char ch = static_cast<unsigned char>(data[pos]);
            if (fn_is_pdf_whitespace(ch) || fn_is_pdf_delimiter(ch)) {
                break;
            }
            ++pos;
        }
        if (pos == start) return false;
        std::string value(data.substr(start, pos - start));
        bool numeric = value.find_first_not_of("0123456789+-.") == std::string::npos;
        if (numeric) {
            out.word = false;
            out.operand.kind = FnContentOperand::Kind::Other;
            out.operand.text = std::move(value);
        } else {
            out.word = true;
            out.text = std::move(value);
        }
        return true;
    }

    void skipInlineImage() {
        // ISO 32000-1 7.2.2: EI is a token, so it must be separated from the
        // image bytes by whitespace and from the next token by whitespace or a
        // delimiter. Requiring both boundaries avoids treating a binary "EI"
        // sequence inside the image data as the end marker. A stream that
        // violates the delimiter rule is reported malformed so callers fail
        // closed instead of trusting a partial scan.
        FnContentToken token;
        while (next(token)) {
            if (token.word && token.text == "ID") {
                if (pos < data.size() &&
                    fn_is_pdf_whitespace(static_cast<unsigned char>(data[pos])))
                    ++pos;
                while (pos + 1 < data.size()) {
                    if (data[pos] == 'E' && data[pos + 1] == 'I' &&
                        (pos == 0 ||
                         fn_is_pdf_whitespace(static_cast<unsigned char>(data[pos - 1])))) {
                        const size_t after = pos + 2;
                        if (after >= data.size() ||
                            fn_is_pdf_whitespace(static_cast<unsigned char>(data[after])) ||
                            fn_is_pdf_delimiter(static_cast<unsigned char>(data[after]))) {
                            pos = after;
                            return;
                        }
                    }
                    ++pos;
                }
                malformed = true;
                return;
            }
        }
        malformed = true;
    }
};

static bool fn_same_font(QPDFObjectHandle const& a, QPDFObjectHandle const& b) {
    if (!a.isDictionary() || !b.isDictionary()) return false;
    if (a.isIndirect() && b.isIndirect() && a.getObjGen() == b.getObjGen()) return true;
    // A resource dictionary may point at a distinct dictionary object for the
    // same font. Matching /BaseFont keeps the content scan correct in that case.
    QPDFObjectHandle a_base = a.getKey("/BaseFont");
    QPDFObjectHandle b_base = b.getKey("/BaseFont");
    if (a_base.isName() && b_base.isName() && a_base.getName() == b_base.getName()) return true;
    try {
        return a.unparse() == b.unparse();
    } catch (...) {
        return false;
    }
}

static void fn_collect_cids(std::string const& bytes, std::set<uint32_t>& cids, bool& complete,
                            bool two_byte) {
    if (!two_byte) {
        for (unsigned char ch : bytes) cids.insert(static_cast<uint32_t>(ch));
        return;
    }
    if ((bytes.size() % 2) != 0) complete = false;
    for (size_t i = 0; i + 1 < bytes.size(); i += 2) {
        uint32_t cid = (static_cast<uint32_t>(static_cast<unsigned char>(bytes[i])) << 8) |
                       static_cast<unsigned char>(bytes[i + 1]);
        if (cid) cids.insert(cid);
    }
}

struct FnCidScan {
    std::set<uint32_t> cids;
    bool complete = true;
};

static void fn_scan_resource_streams(QPDFObjectHandle const& resources,
                                     QPDFObjectHandle const& font, std::set<uint32_t>& cids,
                                     int depth, std::set<std::pair<QPDFObjGen, bool>>& seen_streams,
                                     int& budget, bool& complete, bool two_byte);

static void fn_scan_content_stream(std::string_view data, QPDFObjectHandle const& resources,
                                   QPDFObjectHandle const& font, std::set<uint32_t>& cids,
                                   int depth, std::set<std::pair<QPDFObjGen, bool>>& seen_streams,
                                   int& budget, bool& complete, bool two_byte,
                                   bool initial_selected = false) {
    if (depth > 16 || budget-- <= 0) {
        complete = false;
        return;
    }
    FnContentLexer lexer{data};
    std::vector<FnContentOperand> operands;
    std::vector<bool> font_stack;
    bool selected = initial_selected;
    FnContentToken token;
    while (lexer.next(token)) {
        if (!token.word) {
            operands.push_back(std::move(token.operand));
            if (operands.size() > 64) operands.erase(operands.begin());
            continue;
        }
        std::string const& op = token.text;
        if (op == "q") {
            font_stack.push_back(selected);
        } else if (op == "Q") {
            if (!font_stack.empty()) {
                selected = font_stack.back();
                font_stack.pop_back();
            }
        } else if (op == "Tf" && operands.size() >= 2) {
            auto const& font_name = operands[operands.size() - 2];
            selected = false;
            if (font_name.kind == FnContentOperand::Kind::Name) {
                auto fonts =
                    resources.isDictionary() ? resources.getKey("/Font") : QPDFObjectHandle();
                if (fonts.isDictionary()) {
                    auto candidate = fonts.getKey("/" + font_name.text);
                    selected = fn_same_font(candidate, font);
                }
            }
        } else if (op == "gs" && !operands.empty() &&
                   operands.back().kind == FnContentOperand::Kind::Name) {
            if (resources.isDictionary()) {
                auto extgstate = resources.getKey("/ExtGState");
                if (extgstate.isDictionary()) {
                    auto gs_dict = extgstate.getKey("/" + operands.back().text);
                    if (gs_dict.isDictionary() && gs_dict.hasKey("/Font")) {
                        auto font_entry = gs_dict.getKey("/Font");
                        if (font_entry.isArray() && font_entry.getArrayNItems() > 0) {
                            selected = fn_same_font(font_entry.getArrayItem(0), font);
                        } else {
                            complete = false;
                        }
                    }
                }
            }
        } else if (op == "Tj" && !operands.empty() &&
                   operands.back().kind == FnContentOperand::Kind::String) {
            if (selected) fn_collect_cids(operands.back().text, cids, complete, two_byte);
        } else if (op == "TJ" && !operands.empty() &&
                   operands.back().kind == FnContentOperand::Kind::Array) {
            if (selected)
                for (auto const& text : operands.back().strings)
                    fn_collect_cids(text, cids, complete, two_byte);
        } else if ((op == "'" || op == "\"") && !operands.empty() &&
                   operands.back().kind == FnContentOperand::Kind::String) {
            if (selected) fn_collect_cids(operands.back().text, cids, complete, two_byte);
        } else if (op == "Do" && !operands.empty() &&
                   operands.back().kind == FnContentOperand::Kind::Name) {
            if (resources.isDictionary()) {
                auto xobjects = resources.getKey("/XObject");
                if (xobjects.isDictionary()) {
                    auto form = xobjects.getKey("/" + operands.back().text);
                    if (form.isStream() &&
                        form.getDict().getKey("/Subtype").isNameAndEquals("/Form")) {
                        bool seen = false;
                        QPDFObjGen form_id;
                        if (form.isIndirect()) {
                            form_id = form.getObjGen();
                            seen = !seen_streams.insert({form_id, selected}).second;
                        }
                        if (!seen) {
                            auto form_resources = form.getDict().getKey("/Resources");
                            if (!form_resources.isDictionary()) form_resources = resources;
                            try {
                                auto stream_data = form.getStreamData(qpdf_dl_generalized);
                                if (stream_data && stream_data->getSize()) {
                                    std::string_view content(
                                        reinterpret_cast<char const*>(stream_data->getBuffer()),
                                        stream_data->getSize());
                                    fn_scan_content_stream(content, form_resources, font, cids,
                                                           depth + 1, seen_streams, budget,
                                                           complete, two_byte, selected);
                                    fn_scan_resource_streams(form_resources, font, cids, depth + 1,
                                                             seen_streams, budget, complete,
                                                             two_byte);
                                }
                            } catch (...) {
                                complete = false;
                            }
                            if (form.isIndirect()) seen_streams.erase({form_id, selected});
                        }
                    }
                }
            }
        } else if (op == "BI") {
            lexer.skipInlineImage();
        }
        operands.clear();
    }
    lexer.skipSpace();
    if (lexer.malformed || lexer.pos < lexer.data.size()) complete = false;
}

static void fn_scan_resource_streams(QPDFObjectHandle const& resources,
                                     QPDFObjectHandle const& font, std::set<uint32_t>& cids,
                                     int depth, std::set<std::pair<QPDFObjGen, bool>>& seen_streams,
                                     int& budget, bool& complete, bool two_byte) {
    if (depth > 16 || budget <= 0 || !resources.isDictionary()) {
        if (budget <= 0) complete = false;
        return;
    }
    auto scan_stream = [&](QPDFObjectHandle stream, QPDFObjectHandle const& fallback_resources) {
        if (!stream.isStream() || budget <= 0) {
            if (budget <= 0) complete = false;
            return;
        }
        bool seen = false;
        QPDFObjGen id;
        if (stream.isIndirect()) {
            id = stream.getObjGen();
            seen = !seen_streams.insert({id, false}).second;
        }
        if (seen) return;
        auto stream_resources = stream.getDict().getKey("/Resources");
        if (!stream_resources.isDictionary()) stream_resources = fallback_resources;
        try {
            auto data = stream.getStreamData(qpdf_dl_generalized);
            if (data && data->getSize()) {
                std::string content(reinterpret_cast<char const*>(data->getBuffer()),
                                    data->getSize());
                fn_scan_content_stream(content, stream_resources, font, cids, depth + 1,
                                       seen_streams, budget, complete, two_byte);
                fn_scan_resource_streams(stream_resources, font, cids, depth + 1, seen_streams,
                                         budget, complete, two_byte);
            }
        } catch (...) {
            complete = false;
        }
        if (stream.isIndirect()) seen_streams.erase({id, false});
    };

    auto patterns = resources.getKey("/Pattern");
    if (patterns.isDictionary()) {
        for (auto const& entry : patterns.getDictAsMap()) scan_stream(entry.second, resources);
    }

    auto extgstates = resources.getKey("/ExtGState");
    if (extgstates.isDictionary()) {
        for (auto const& entry : extgstates.getDictAsMap()) {
            auto const& gs = entry.second;
            if (!gs.isDictionary()) continue;
            auto smask = gs.getKey("/SMask");
            if (!smask.isDictionary()) continue;
            auto group = smask.getKey("/G");
            if (!group.isStream()) continue;
            auto group_resources = group.getDict().getKey("/Resources");
            if (!group_resources.isDictionary()) group_resources = resources;
            scan_stream(group, group_resources);
        }
    }

    auto fonts = resources.getKey("/Font");
    if (fonts.isDictionary()) {
        for (auto const& entry : fonts.getDictAsMap()) {
            auto type3 = entry.second;
            if (!type3.isDictionary() || !type3.getKey("/Subtype").isNameAndEquals("/Type3"))
                continue;
            auto char_procs = type3.getKey("/CharProcs");
            if (!char_procs.isDictionary()) continue;
            auto font_resources = type3.getKey("/Resources");
            if (!font_resources.isDictionary()) font_resources = resources;
            for (auto const& glyph : char_procs.getDictAsMap())
                scan_stream(glyph.second, font_resources);
        }
    }
}

struct FontNormalizeState {
    int32_t fonts_processed = 0;
    int32_t tuc_fixed = 0;
    int32_t w_fixed = 0;
    int32_t type1_converted = 0;
    int32_t resubset = 0;
    bool changed = false;
    std::map<std::pair<QPDFObjGen, bool>, FnCidScan> scan_cache;
};

static FnCidScan fn_used_cids_for_font(QPDF& pdf, QPDFObjectHandle const& font, bool two_byte,
                                       FontNormalizeState* state = nullptr) {
    if (state && font.isIndirect()) {
        auto it = state->scan_cache.find({font.getObjGen(), two_byte});
        if (it != state->scan_cache.end()) return it->second;
    }
    FnCidScan result;
    int budget = 2048;
    std::set<std::pair<QPDFObjGen, bool>> seen_streams;
    try {
        for (auto& page : QPDFPageDocumentHelper{pdf}.getAllPages()) {
            auto page_obj = page.getObjectHandle();
            auto resources = page_obj.getKey("/Resources");
            if (!resources.isDictionary()) resources = page.getAttribute("/Resources", false);
            auto contents = page_obj.getKey("/Contents");
            std::vector<QPDFObjectHandle> streams;
            if (contents.isArray()) {
                for (auto& item : contents.getArrayAsVector()) streams.push_back(item);
            } else if (contents.isStream()) {
                streams.push_back(contents);
            }
            auto scan_stream_data = [&](QPDFObjectHandle stream,
                                        QPDFObjectHandle const& fallback_resources) {
                if (!stream.isStream()) return;
                auto stream_resources = stream.getDict().getKey("/Resources");
                if (!stream_resources.isDictionary()) stream_resources = fallback_resources;
                try {
                    auto data = stream.getStreamData(qpdf_dl_generalized);
                    if (!data || !data->getSize()) return;
                    std::string_view content(reinterpret_cast<char const*>(data->getBuffer()),
                                             data->getSize());
                    fn_scan_content_stream(content, stream_resources, font, result.cids, 0,
                                           seen_streams, budget, result.complete, two_byte);
                    fn_scan_resource_streams(stream_resources, font, result.cids, 0, seen_streams,
                                             budget, result.complete, two_byte);
                } catch (...) {
                    result.complete = false;
                }
            };
            std::string page_content;
            bool contents_complete = true;
            for (auto& stream : streams) {
                if (!stream.isStream()) {
                    contents_complete = false;
                    break;
                }
                try {
                    auto data = stream.getStreamData(qpdf_dl_generalized);
                    if (data && data->getSize()) {
                        if (!page_content.empty()) page_content.push_back('\n');
                        page_content.append(reinterpret_cast<char const*>(data->getBuffer()),
                                            data->getSize());
                    }
                } catch (...) {
                    contents_complete = false;
                    break;
                }
            }
            if (!contents_complete) result.complete = false;
            if (!page_content.empty()) {
                fn_scan_content_stream(page_content, resources, font, result.cids, 0, seen_streams,
                                       budget, result.complete, two_byte);
            }

            // Annotation appearance streams can draw glyphs that no page content
            // stream references.
            auto annots = page_obj.getKey("/Annots");
            if (annots.isArray()) {
                for (auto& annot : annots.getArrayAsVector()) {
                    if (!annot.isDictionary()) continue;
                    auto ap = annot.getKey("/AP");
                    if (!ap.isDictionary()) continue;
                    for (char const* key : {"/N", "/R", "/D"}) {
                        auto appearance = ap.getKey(key);
                        if (appearance.isStream()) {
                            scan_stream_data(appearance, resources);
                        } else if (appearance.isDictionary()) {
                            for (auto const& entry : appearance.getDictAsMap())
                                scan_stream_data(entry.second, resources);
                        }
                    }
                }
            }

            if (resources.isDictionary())
                fn_scan_resource_streams(resources, font, result.cids, 0, seen_streams, budget,
                                         result.complete, two_byte);
        }
    } catch (...) {
        result.complete = false;
    }
    if (state && font.isIndirect()) {
        state->scan_cache[{font.getObjGen(), two_byte}] = result;
    }
    return result;
}

static std::string fn_subset_sfnt(FT_Library lib, const uint8_t* data, size_t size,
                                  FT_Long face_index, std::set<FT_UInt> const& glyphs,
                                  FT_Face face) {
    (void)lib;
    if (!data || size == 0 || size > std::numeric_limits<unsigned int>::max()) return {};
    hb_blob_t* blob =
        hb_blob_create(reinterpret_cast<const char*>(data), static_cast<unsigned int>(size),
                       HB_MEMORY_MODE_READONLY, nullptr, nullptr);
    hb_face_t* hb_face = hb_face_create(blob, static_cast<unsigned int>(face_index));
    hb_blob_destroy(blob);
    std::string out;
    if (hb_subset_input_t* input = hb_subset_input_create_or_fail()) {
        for (FT_UInt g : glyphs) hb_set_add(hb_subset_input_glyph_set(input), g);
        FT_UInt gid = 0;
        if (!FT_Select_Charmap(face, FT_ENCODING_UNICODE)) {
            for (FT_ULong u = FT_Get_First_Char(face, &gid); gid;
                 u = FT_Get_Next_Char(face, u, &gid))
                if (glyphs.contains(gid)) hb_set_add(hb_subset_input_unicode_set(input), u);
        }
        hb_subset_input_set_flags(input, HB_SUBSET_FLAGS_RETAIN_GIDS | HB_SUBSET_FLAGS_GLYPH_NAMES |
                                             HB_SUBSET_FLAGS_NOTDEF_OUTLINE);
        if (hb_face_t* subset = hb_subset_or_fail(hb_face, input)) {
            hb_blob_t* result = hb_face_reference_blob(subset);
            unsigned int length = 0;
            const char* bytes = hb_blob_get_data(result, &length);
            if (bytes) out.assign(bytes, length);
            hb_blob_destroy(result);
            hb_face_destroy(subset);
        }
        hb_subset_input_destroy(input);
    }
    hb_face_destroy(hb_face);
    return out;
}

static bool fn_subset_keeps_advances(FT_Library lib, std::string const& subset, FT_Long face_index,
                                     FT_Face original, std::set<FT_UInt> const& glyphs) {
    FT_Face face = load_font_face(lib, subset, face_index);
    if (!face) return false;
    bool same = face->num_glyphs > 0;
    for (FT_UInt gid : glyphs) {
        if (!same) break;
        FT_Fixed before = 0;
        FT_Fixed after = 0;
        if (FT_Get_Advance(original, gid, FT_LOAD_NO_SCALE, &before) ||
            FT_Get_Advance(face, gid, FT_LOAD_NO_SCALE, &after) || before != after)
            same = false;
    }
    FT_Done_Face(face);
    return same;
}

static std::string build_cid_set_bits(std::set<uint32_t> const& cids) {
    uint32_t max_cid = 0;
    for (uint32_t c : cids)
        if (c <= kFontMaxCid) max_cid = std::max(max_cid, c);
    std::string bits((max_cid / 8) + 1, '\0');
    bits[0] |= static_cast<char>(0x80U);
    for (uint32_t c : cids)
        if (c <= kFontMaxCid) bits[c / 8] |= static_cast<char>(0x80U >> (c & 7));
    return bits;
}

struct FontUsageCounts {
    std::map<QPDFObjGen, int> descriptors;
    std::map<QPDFObjGen, int> streams;
};

static void mark_form_font_not_subsettable(QPDFObjectHandle const& font, FontUsageCounts& usage) {
    if (!font.isDictionary()) return;
    QPDFObjectHandle target = font;
    QPDFObjectHandle descendants = font.getKey("/DescendantFonts");
    if (descendants.isArray() && descendants.getArrayNItems() > 0 &&
        descendants.getArrayItem(0).isDictionary())
        target = descendants.getArrayItem(0);
    QPDFObjectHandle descriptor = target.getKey("/FontDescriptor");
    if (!descriptor.isDictionary()) return;
    if (descriptor.isIndirect()) usage.descriptors[descriptor.getObjGen()] = 999999;
    for (char const* key : {"/FontFile", "/FontFile2", "/FontFile3"}) {
        auto stream = descriptor.getKey(key);
        if (stream.isStream() && stream.isIndirect()) {
            usage.streams[stream.getObjGen()] = 999999;
        }
    }
}

static void count_font_usage(QPDFObjectHandle const& font, std::set<QPDFObjGen>& counted,
                             FontUsageCounts& usage) {
    if (font.isIndirect() && !counted.insert(font.getObjGen()).second) return;
    QPDFObjectHandle target = font;
    QPDFObjectHandle descendants = font.getKey("/DescendantFonts");
    if (descendants.isArray() && descendants.getArrayNItems() > 0 &&
        descendants.getArrayItem(0).isDictionary())
        target = descendants.getArrayItem(0);
    QPDFObjectHandle descriptor = target.getKey("/FontDescriptor");
    if (!descriptor.isDictionary()) return;
    if (descriptor.isIndirect()) ++usage.descriptors[descriptor.getObjGen()];
    QPDFObjectHandle program = embedded_font_program(descriptor);
    if (program.isIndirect()) ++usage.streams[program.getObjGen()];
}

// Synthesize a missing or boilerplate-only /ToUnicode CMap from the resolved
// glyph-to-Unicode mapping when the mapping is unambiguous.
static void repair_font_tounicode(QPDF& pdf, QPDFObjectHandle& font, FT_Face face, bool cid,
                                  QPDFObjectHandle& cid_to_gid, bool cid_identity,
                                  std::map<uint32_t, uint32_t> const& gid_to_cid,
                                  std::map<uint32_t, uint32_t> const& gid_to_unicode,
                                  std::set<uint32_t> const& ambiguous_gids, bool simple_any_gid,
                                  std::vector<SimpleGlyph> const& simple_glyphs,
                                  FontNormalizeState& state) {
    bool has_tounicode = false;
    QPDFObjectHandle to_unicode = font.getKey("/ToUnicode");
    if (to_unicode.isStream()) {
        try {
            std::shared_ptr<Buffer> existing = to_unicode.getStreamData(qpdf_dl_generalized);
            if (existing && existing->getSize() > 0) {
                std::string cmap(reinterpret_cast<char const*>(existing->getBuffer()),
                                 existing->getSize());
                has_tounicode = cmapHasMappings(cmap);
            }
        } catch (...) {
            has_tounicode = false;
        }
    }
    if (has_tounicode) return;

    auto reverse_cid_unicode = [&](uint32_t gid) -> uint32_t {
        if (ambiguous_gids.contains(gid)) return unicodeFromGlyphNameOnly(face, gid);
        auto found = gid_to_unicode.find(gid);
        return found != gid_to_unicode.end() ? found->second : 0;
    };

    std::map<uint32_t, std::vector<uint32_t>> code_to_unicode;
    if (cid) {
        if (cid_to_gid.isStream()) {
            try {
                std::shared_ptr<Buffer> map = cid_to_gid.getStreamData(qpdf_dl_generalized);
                if (map) {
                    const auto* bytes = static_cast<const uint8_t*>(map->getBuffer());
                    const size_t entries = map->getSize() / 2;
                    for (size_t c = 0; c < entries && c <= 0xFFFF; ++c) {
                        const uint32_t g =
                            (static_cast<uint32_t>(bytes[2 * c]) << 8) | bytes[2 * c + 1];
                        uint32_t unicode = reverse_cid_unicode(g);
                        if (unicode)
                            code_to_unicode.emplace(static_cast<uint32_t>(c),
                                                    std::vector<uint32_t>{unicode});
                    }
                }
            } catch (...) {
                code_to_unicode.clear();
            }
        } else if (cid_identity) {
            if (FT_IS_CID_KEYED(face)) {
                for (auto const& entry : gid_to_cid) {
                    uint32_t unicode = reverse_cid_unicode(entry.first);
                    if (unicode)
                        code_to_unicode.emplace(entry.second, std::vector<uint32_t>{unicode});
                }
            } else {
                for (auto const& entry : gid_to_unicode) {
                    if (entry.first > 0xFFFF) continue;
                    uint32_t unicode = reverse_cid_unicode(entry.first);
                    if (unicode || !ambiguous_gids.contains(entry.first))
                        code_to_unicode.emplace(entry.first, std::vector<uint32_t>{unicode});
                }
            }
        }
    } else if (simple_any_gid) {
        // Only synthesize when the encoding actually resolves glyphs;
        // never fall back to raw code == glyph id for simple fonts.
        for (uint32_t code = 0; code <= 0xFF; ++code) {
            uint32_t gid = simple_glyphs[code].gid;
            std::vector<uint32_t> sequence;
            if (gid != 0 && FT_HAS_GLYPH_NAMES(face)) {
                char glyph_name[256];
                if (FT_Get_Glyph_Name(face, gid, glyph_name, sizeof(glyph_name)) == 0 &&
                    glyph_name[0])
                    sequence = unicodeSequenceForGlyphName(glyph_name);
            }
            if (sequence.empty() && simple_glyphs[code].unicode != 0)
                sequence.push_back(simple_glyphs[code].unicode);
            if (!sequence.empty()) code_to_unicode.emplace(code, std::move(sequence));
        }
    }

    if (!code_to_unicode.empty()) {
        font.replaceKey("/ToUnicode", QPDFObjectHandle::newStream(
                                          &pdf, build_tounicode_cmap(code_to_unicode, cid)));
        ++state.tuc_fixed;
        state.changed = true;
    }
}

// Recompute and repair /Widths on a simple (8-bit) font from FreeType glyph advances,
// preserving existing producer widths for unmapped codes in subsetted faces.
static void repair_simple_font_widths(QPDFObjectHandle& target, FT_Face face,
                                      SimpleEncoding const& simple_encoding, bool simple_any_gid,
                                      std::vector<SimpleGlyph> const& simple_glyphs,
                                      FontNormalizeState& state) {
    if (simple_encoding.unsupportedEncoding || !simple_any_gid) return;
    int first_char = 0;
    int last_char = 255;
    QPDFObjectHandle existing_first = target.getKey("/FirstChar");
    QPDFObjectHandle existing_last = target.getKey("/LastChar");
    if (existing_first.isInteger()) {
        long long value = std::clamp(existing_first.getIntValue(), 0LL, 255LL);
        first_char = static_cast<int>(value);
    }
    if (existing_last.isInteger()) {
        long long value =
            std::clamp(existing_last.getIntValue(), static_cast<long long>(first_char), 255LL);
        last_char = static_cast<int>(value);
    }

    std::vector<int> widths;
    widths.reserve(static_cast<size_t>(last_char - first_char + 1));
    bool any_positive = false;
    QPDFObjectHandle existing_widths = target.getKey("/Widths");
    const bool has_widths_array = existing_widths.isArray();
    bool needs_rewrite =
        !has_widths_array || existing_widths.getArrayNItems() != (last_char - first_char + 1);
    for (int code = first_char; code <= last_char; ++code) {
        const int index = code - first_char;
        QPDFObjectHandle item;
        if (has_widths_array && index >= 0 && index < existing_widths.getArrayNItems())
            item = existing_widths.getArrayItem(index);
        const bool existing_number = item.isNumber();
        const int preserved =
            existing_number ? std::max(0, static_cast<int>(std::lround(item.getNumericValue())))
                            : 0;
        int width = preserved;
        int computed = 0;
        const bool resolved = simple_glyphs[code].gid != 0 &&
                              pdf_width_from_glyph(face, simple_glyphs[code].gid, computed);
        if (resolved) {
            if (existing_number && (preserved > 0 || computed == 0)) {
                // Keep a positive producer width even when it differs from the
                // program advance; keep a legitimate zero-advance glyph too.
                width = preserved;
            } else {
                // Only a missing, non-numeric, or non-positive width that
                // disagrees with the program is demonstrably inconsistent.
                width = std::max(0, computed);
                needs_rewrite = true;
            }
        } else if (!existing_number) {
            needs_rewrite = true;
            width = 0;
        }
        if (width > 0) any_positive = true;
        widths.push_back(width);
    }

    if (needs_rewrite && any_positive) {
        QPDFObjectHandle array = QPDFObjectHandle::newArray();
        for (int width : widths) array.appendItem(QPDFObjectHandle::newInteger(width));
        target.replaceKey("/FirstChar", QPDFObjectHandle::newInteger(first_char));
        target.replaceKey("/LastChar", QPDFObjectHandle::newInteger(last_char));
        target.replaceKey("/Widths", array);
        ++state.w_fixed;
        state.changed = true;
    }
}

// Synthesize a missing /W array on a horizontal CIDFont from FreeType glyph advances.
static std::map<uint32_t, int> parse_cid_widths(QPDFObjectHandle const& w) {
    std::map<uint32_t, int> widths;
    if (!w.isArray()) return widths;
    const auto items = w.getArrayAsVector();
    size_t i = 0;
    while (i < items.size()) {
        if (!items[i].isInteger()) break;
        const uint32_t first = static_cast<uint32_t>(items[i].getIntValueAsInt());
        ++i;
        if (i >= items.size()) break;
        if (items[i].isArray()) {
            const auto run = items[i].getArrayAsVector();
            ++i;
            for (size_t j = 0; j < run.size() && first + j <= kFontMaxCid; ++j) {
                if (run[j].isNumber())
                    widths[first + static_cast<uint32_t>(j)] =
                        static_cast<int>(std::lround(run[j].getNumericValue()));
            }
            continue;
        }
        if (i + 1 >= items.size() || !items[i].isInteger() || !items[i + 1].isNumber()) break;
        const uint32_t last = static_cast<uint32_t>(items[i].getIntValueAsInt());
        const int width = static_cast<int>(std::lround(items[i + 1].getNumericValue()));
        i += 2;
        if (last < first || last > kFontMaxCid) continue;
        for (uint32_t cid = first; cid <= last; ++cid) widths[cid] = width;
    }
    return widths;
}

static QPDFObjectHandle build_cid_widths_array(std::map<uint32_t, int> const& widths) {
    QPDFObjectHandle array = QPDFObjectHandle::newArray();
    auto it = widths.begin();
    while (it != widths.end()) {
        const uint32_t start = it->first;
        std::vector<int> run;
        uint32_t expected = start;
        while (it != widths.end() && it->first == expected) {
            run.push_back(it->second);
            ++it;
            ++expected;
        }
        const bool same = std::ranges::all_of(run, [&](int value) { return value == run.front(); });
        array.appendItem(QPDFObjectHandle::newInteger(start));
        if (run.size() >= 2 && same) {
            array.appendItem(QPDFObjectHandle::newInteger(expected - 1));
            array.appendItem(QPDFObjectHandle::newInteger(run.front()));
        } else {
            QPDFObjectHandle values = QPDFObjectHandle::newArray();
            for (int value : run) values.appendItem(QPDFObjectHandle::newInteger(value));
            array.appendItem(values);
        }
    }
    return array;
}

static void repair_cid_font_widths(QPDF& pdf, QPDFObjectHandle& font, QPDFObjectHandle& target,
                                   FT_Face face, QPDFObjectHandle& cid_to_gid, bool cid_identity,
                                   std::map<uint32_t, uint32_t> const& gid_to_cid,
                                   FontNormalizeState& state) {
    // Vertical writing uses /DW2 and /W2, not horizontal /W.
    if (font.getKey("/Encoding").isNameAndEquals("/Identity-V")) return;

    // /W is keyed by CID, not GID.
    std::map<uint32_t, FT_UInt> cid_gid;
    if (cid_to_gid.isStream()) {
        try {
            std::shared_ptr<Buffer> map = cid_to_gid.getStreamData(qpdf_dl_generalized);
            if (map) {
                const auto* bytes = static_cast<const uint8_t*>(map->getBuffer());
                const size_t entries = map->getSize() / 2;
                for (size_t c = 0; c < entries && c <= kFontMaxCid; ++c) {
                    const uint32_t gid =
                        (static_cast<uint32_t>(bytes[2 * c]) << 8) | bytes[2 * c + 1];
                    if (gid != 0) cid_gid[static_cast<uint32_t>(c)] = gid;
                }
            }
        } catch (...) {
            cid_gid.clear();
        }
    } else if (cid_identity) {
        if (FT_IS_CID_KEYED(face)) {
            for (auto const& [gid, cid] : gid_to_cid) cid_gid[cid] = gid;
        } else {
            const int max_gid = static_cast<int>(
                std::min<FT_Long>(face->num_glyphs - 1, static_cast<FT_Long>(kFontMaxCid)));
            for (int gid = 1; gid <= max_gid; ++gid)
                cid_gid[static_cast<uint32_t>(gid)] = static_cast<FT_UInt>(gid);
        }
    }
    if (cid_gid.empty()) return;

    QPDFObjectHandle existing_w = target.getKey("/W");
    std::map<uint32_t, int> widths = parse_cid_widths(existing_w);

    // With no /W at all, synthesize entries for the CIDs that content draws.
    // An absent /W means every CID falls back to /DW; writing an entry for
    // every mapped glyph in a large program adds size without changing layout.
    // With an existing array, repair only the CIDs the content draws: unused
    // entries may be deliberate producer metadata and are not checked by the
    // validator.
    FnCidScan scan = fn_used_cids_for_font(pdf, font, true, &state);
    if (!scan.complete || scan.cids.empty()) return;
    std::set<uint32_t> check = scan.cids;
    if (widths.empty()) {
        // A synthesized /W must spell out zero-advance glyphs even when the
        // current page does not draw them, because /DW would otherwise give
        // them a false advance in text extraction or later edits.
        for (auto const& [cid, gid] : cid_gid) {
            int width = 0;
            if (pdf_width_from_glyph(face, gid, width) && width == 0) check.insert(cid);
        }
    }

    bool changed = false;
    for (uint32_t cid : check) {
        auto glyph = cid_gid.find(cid);
        if (glyph == cid_gid.end()) continue;
        int width = 0;
        if (!pdf_width_from_glyph(face, glyph->second, width)) continue;
        auto existing = widths.find(cid);
        if (existing == widths.end() || existing->second != width) {
            widths[cid] = width;
            changed = true;
        }
    }
    if (!changed) return;

    QPDFObjectHandle array = build_cid_widths_array(widths);
    if (array.getArrayNItems() > 0) {
        target.replaceKey("/W", array);
        ++state.w_fixed;
        state.changed = true;
    }
}

// Convert a CFF-in-SFNT (OTTO) program into a bare CFF stream (/Type1C or /CIDFontType0C)
// when attached to a /Type1, /MMType1, or /CIDFontType0 dictionary.
static void convert_cff_in_sfnt(QPDF& pdf, QPDFObjectHandle& descriptor,
                                std::unique_ptr<FT_FaceRec, decltype(&FT_Done_Face)>& face,
                                bool cid, std::string const& subtype_name,
                                std::vector<SimpleGlyph> const& simple_glyphs,
                                FontNormalizeState& state) {
    if (!FT_IS_SFNT(face.get()) ||
        !font_has_sfnt_table(face.get(), FT_MAKE_TAG('C', 'F', 'F', ' ')) ||
        font_has_sfnt_table(face.get(), FT_MAKE_TAG('g', 'l', 'y', 'f')))
        return;
    const bool simple_type1 = !cid && (subtype_name == "/Type1" || subtype_name == "/MMType1");
    // CID-keyed CFF is deliberately left in its OpenType wrapper: converting it
    // to /CIDFontType0C changes PDFium's glyph rasterization. Simple Type 1
    // fonts are converted only when every resolved glyph name survives.
    if (!simple_type1) return;

    FT_ULong table_length = 0;
    if (FT_Load_Sfnt_Table(face.get(), FT_MAKE_TAG('C', 'F', 'F', ' '), 0, nullptr,
                           &table_length) != 0 ||
        table_length == 0)
        return;
    std::string cff(static_cast<size_t>(table_length), '\0');
    FT_ULong loaded = table_length;
    if (FT_Load_Sfnt_Table(face.get(), FT_MAKE_TAG('C', 'F', 'F', ' '), 0,
                           reinterpret_cast<FT_Byte*>(cff.data()), &loaded) != 0 ||
        loaded != table_length)
        return;

    FT_Face cff_face = nullptr;
    bool valid = FT_New_Memory_Face(ft_lib, reinterpret_cast<const FT_Byte*>(cff.data()),
                                    static_cast<FT_Long>(cff.size()), 0, &cff_face) == 0 &&
                 cff_face;
    if (valid && !cid) {
        // A bare CFF reaches glyphs by name for a simple font. Every code the
        // PDF encoding resolves through the OpenType wrapper must keep the same
        // glyph name, or the converted stream would draw .notdef where the
        // wrapper drew a real glyph.
        valid = FT_HAS_GLYPH_NAMES(cff_face) != 0;
        for (size_t code = 0; valid && code < simple_glyphs.size(); ++code) {
            FT_UInt gid = simple_glyphs[code].gid;
            if (!gid) continue;
            char name[256] = "";
            if (FT_Get_Glyph_Name(face.get(), gid, name, sizeof(name)) != 0 || !name[0] ||
                std::strcmp(name, ".notdef") == 0)
                continue;
            if (FT_Get_Name_Index(cff_face, name) == 0) valid = false;
        }
    }
    if (cff_face) FT_Done_Face(cff_face);
    if (!valid) return;

    for (char const* key : {"/FontFile", "/FontFile2", "/FontFile3"}) descriptor.removeKey(key);
    QPDFObjectHandle cff_stream = QPDFObjectHandle::newStream(&pdf, cff);
    cff_stream.getDict().replaceKey("/Subtype",
                                    QPDFObjectHandle::newName(cid ? "/CIDFontType0C" : "/Type1C"));
    descriptor.replaceKey("/FontFile3", cff_stream);
    // Do not reload the FreeType face from `cff`: the buffer is destroyed when this
    // function returns, which would leave the face pointing at freed memory.
    ++state.type1_converted;
    state.changed = true;
}

/**
 * Re-subsets an embedded SFNT (TrueType or OpenType/CFF) font program or extracts
 * a single face from a multi-face TrueType Collection (TTC) container to retain
 * strictly those glyphs drawn by referencing content streams across the document.
 *
 * Algorithm overview:
 * 1. Precondition checks:
 *    - Rejects fonts with <= 1 glyph, non-SFNT fonts, CFF2 tables, or fonts whose
 *      OS/2 fsType flags forbid subsetting (e.g., Restricted License embedding 0x0002).
 *    - Extracts BaseFont name and descriptor FontName; rejects fonts missing BaseFont.
 *    - Checks subset tag (pattern 'XXXXXX+'); skips fonts already tagged as subsets
 *      unless extracting a face from a TrueType Collection (.ttc / 'ttcf').
 *    - Checks sharing: skips programs or descriptors referenced by multiple font
 *      dictionaries across pages, or referenced by /AcroForm /DR.
 * 2. Usage discovery (fail-closed):
 *    - For CID fonts (Type 0 / CIDFontType2): scans all content streams for 2-byte CIDs,
 *      mapping each CID through /CIDToGIDMap (stream or /Identity) to a glyph index (GID).
 *    - For simple fonts (TrueType): scans content streams for 1-byte character codes.
 *      Validates that every referenced code resolves to a valid non-zero GID in
 *      simple_glyphs. If any drawn code is unmapped or maps to GID 0, subsetting is rejected
 *      to avoid dropping glyphs that PDFium's engine-level heuristics might render.
 * 3. Subset generation:
 *    - Invokes the HarfBuzz subsetter (hb_subset) to retain targeted GIDs while
 *      preserving original glyph advances, hinting metrics, and required tables.
 *    - Verifies that glyph advances in the subsetted program exactly match the original.
 * 4. PDF dictionary updates:
 *    - Generates a deterministic 6-character uppercase tag (e.g., 'ABCDEF+FontName').
 *    - Updates /BaseFont in font and descendant dictionaries, and /FontName in descriptor.
 *    - Replaces /FontFile2 (TrueType) or /FontFile3 (OpenType/CFF) stream data and Length.
 *    - Keeps the original FreeType face, whose backing buffer outlives this call, and updates
 *      active CID tracking.
 *
 * @param pdf Document handle.
 * @param font Referring font dictionary handle.
 * @param target Descendant CIDFont or simple font dictionary handle.
 * @param descriptor /FontDescriptor dictionary handle.
 * @param stream Embedded font program stream handle.
 * @param data Decompressed raw bytes of the original font program.
 * @param face_index Face index in TTC collection, or 0 for standalone fonts.
 * @param face FreeType face pointer; not reloaded because the subset buffer is temporary.
 * @param cid True for 2-byte composite fonts; false for 1-byte simple fonts.
 * @param cid_to_gid /CIDToGIDMap stream handle (or /Identity).
 * @param cid_identity True if /CIDToGIDMap is /Identity.
 * @param simple_glyphs Character-code-to-GID mapping array for simple fonts.
 * @param usage Shared reference counts for descriptors and font streams.
 * @param state Normalization statistics and scan cache.
 * @return True if the font program was successfully subsetted and replaced; false otherwise.
 */
static bool resubset_embedded_sfnt(QPDF& pdf, QPDFObjectHandle& font, QPDFObjectHandle& target,
                                   QPDFObjectHandle& descriptor, QPDFObjectHandle const& stream,
                                   std::shared_ptr<Buffer> const& data, FT_Long face_index,
                                   std::unique_ptr<FT_FaceRec, decltype(&FT_Done_Face)>& face,
                                   bool cid, QPDFObjectHandle& cid_to_gid, bool cid_identity,
                                   std::vector<SimpleGlyph> const& simple_glyphs,
                                   FontUsageCounts const& usage, FontNormalizeState& state,
                                   std::set<FT_UInt>* retained_glyphs = nullptr) {
    if (face->num_glyphs <= 1 || !FT_IS_SFNT(face.get()) ||
        font_has_sfnt_table(face.get(), FT_MAKE_TAG('C', 'F', 'F', '2')) ||
        font_forbids_subsetting(face.get()))
        return false;

    QPDFObjectHandle base_font = target.getKey("/BaseFont");
    if (!base_font.isName()) base_font = font.getKey("/BaseFont");
    std::string base_name = base_font.isName() ? base_font.getName() : std::string();
    if (!base_name.empty() && base_name.front() == '/') base_name.erase(base_name.begin());

    QPDFObjectHandle desc_name = descriptor.getKey("/FontName");
    std::string descriptor_name = desc_name.isName() ? desc_name.getName() : std::string();
    if (!descriptor_name.empty() && descriptor_name.front() == '/')
        descriptor_name.erase(descriptor_name.begin());

    const bool is_ttc = data->getSize() >= 4 && std::memcmp(data->getBuffer(), "ttcf", 4) == 0;
    const bool tagged =
        font_name_is_subset_tagged(base_name) || font_name_is_subset_tagged(descriptor_name);
    if (font_name_is_subset_tagged(base_name)) base_name.erase(0, 7);
    const bool shared =
        (descriptor.isIndirect() && usage.descriptors.contains(descriptor.getObjGen()) &&
         usage.descriptors.at(descriptor.getObjGen()) > 1) ||
        (stream.isIndirect() && usage.streams.contains(stream.getObjGen()) &&
         usage.streams.at(stream.getObjGen()) > 1);
    if ((!is_ttc && tagged) || shared || base_name.empty()) return false;

    std::set<FT_UInt> target_glyphs{0};
    if (cid) {
        FnCidScan scan = fn_used_cids_for_font(pdf, font, true, &state);
        if (!scan.complete || scan.cids.empty()) return false;
        std::shared_ptr<Buffer> cid_map;
        if (!cid_identity) {
            if (!cid_to_gid.isStream()) return false;
            try {
                cid_map = cid_to_gid.getStreamData(qpdf_dl_generalized);
            } catch (...) {
                return false;
            }
            if (!cid_map) return false;
        }
        const auto* map_bytes =
            cid_map ? static_cast<const uint8_t*>(cid_map->getBuffer()) : nullptr;
        for (uint32_t c : scan.cids) {
            uint32_t gid = c;
            if (!cid_identity) {
                if (!map_bytes || cid_map->getSize() < 2 * (static_cast<size_t>(c) + 1))
                    return false;
                gid = (static_cast<uint32_t>(map_bytes[2 * c]) << 8) | map_bytes[2 * c + 1];
            }
            if (gid >= static_cast<uint32_t>(face->num_glyphs)) return false;
            target_glyphs.insert(static_cast<FT_UInt>(gid));
        }
    } else {
        if (simple_glyphs.size() < 256) return false;
        FnCidScan scan = fn_used_cids_for_font(pdf, font, false, &state);
        if (!scan.complete || scan.cids.empty()) return false;
        for (uint32_t code : scan.cids) {
            if (code >= simple_glyphs.size() || simple_glyphs[code].gid == 0) return false;
            target_glyphs.insert(simple_glyphs[code].gid);
            if (code < 256) {
                for (BaseEncoding encoding :
                     {BaseEncoding::Standard, BaseEncoding::WinAnsi, BaseEncoding::MacRoman}) {
                    FT_UInt glyph = FT_Get_Char_Index(
                        face.get(), baseEncodingUnicode(encoding, static_cast<int>(code)));
                    if (glyph) target_glyphs.insert(glyph);
                }
            }
        }
    }

    if (target_glyphs.size() <= 1 || target_glyphs.size() >= static_cast<size_t>(face->num_glyphs))
        return false;

    std::string subset = fn_subset_sfnt(ft_lib, data->getBuffer(), data->getSize(), face_index,
                                        target_glyphs, face.get());
    if (subset.empty() || subset.size() >= data->getSize() ||
        !fn_subset_keeps_advances(ft_lib, subset, face_index, face.get(), target_glyphs))
        return false;

    const std::string tag = make_font_subset_tag(base_name, target_glyphs);
    QPDFObjectHandle tagged_name = QPDFObjectHandle::newName("/" + tag + "+" + base_name);
    font.replaceKey("/BaseFont", tagged_name);
    target.replaceKey("/BaseFont", tagged_name);
    descriptor.replaceKey("/FontName", tagged_name);

    const bool has_glyf = font_has_sfnt_table(face.get(), FT_MAKE_TAG('g', 'l', 'y', 'f'));
    for (char const* key : {"/FontFile", "/FontFile2", "/FontFile3"}) descriptor.removeKey(key);
    QPDFObjectHandle replacement = QPDFObjectHandle::newStream(&pdf, subset);
    auto replacement_dict = replacement.getDict();
    if (has_glyf) {
        replacement_dict.replaceKey(
            "/Length1", QPDFObjectHandle::newInteger(static_cast<long long>(subset.size())));
        descriptor.replaceKey("/FontFile2", replacement);
    } else {
        replacement_dict.replaceKey("/Subtype", QPDFObjectHandle::newName("/OpenType"));
        descriptor.replaceKey("/FontFile3", replacement);
    }
    // Keep the original face: `subset` is destroyed when this function returns, so
    // reloading from it would leave a dangling face buffer. The original face stays
    // alive for the remainder of process_font() and still describes the glyphs.
    if (!cid) {
        std::string charset_str;
        char glyph_name[256];
        for (FT_UInt gid : target_glyphs) {
            if (FT_HAS_GLYPH_NAMES(face.get()) &&
                FT_Get_Glyph_Name(face.get(), gid, glyph_name, sizeof(glyph_name)) == 0) {
                charset_str += "/";
                charset_str += glyph_name;
            }
        }
        if (!charset_str.empty()) {
            descriptor.replaceKey("/CharSet", QPDFObjectHandle::newString(charset_str));
        }
    }
    ++state.resubset;
    state.changed = true;
    if (retained_glyphs) *retained_glyphs = target_glyphs;
    return true;
}

// CIDs that exist in the embedded CID font program. PDF/A-2 6.2.11.4 test 4
// requires every mapped CID, including CIDs mapped to GID 0; a bitmap built
// only from drawn CIDs is too short.
static std::set<uint32_t> cids_present_in_program(FT_Face face, QPDFObjectHandle& cid_to_gid,
                                                  bool cid_identity,
                                                  std::map<uint32_t, uint32_t> const& gid_to_cid) {
    std::set<uint32_t> cids;
    if (cid_to_gid.isStream()) {
        try {
            std::shared_ptr<Buffer> data = cid_to_gid.getStreamData(qpdf_dl_generalized);
            if (data) {
                const size_t entries = data->getSize() / 2;
                for (size_t i = 0; i < entries && i <= kFontMaxCid; ++i)
                    cids.insert(static_cast<uint32_t>(i));
            }
        } catch (...) {
            cids.clear();
        }
        return cids;
    }
    if (face && FT_IS_CID_KEYED(face)) {
        for (auto const& [gid, cid] : gid_to_cid) {
            (void)gid;
            if (cid <= kFontMaxCid) cids.insert(cid);
        }
        return cids;
    }
    if (face && cid_identity && face->num_glyphs > 0) {
        const long max_gid = std::min<long>(face->num_glyphs - 1, kFontMaxCid);
        for (long gid = 0; gid <= max_gid; ++gid) cids.insert(static_cast<uint32_t>(gid));
    }
    return cids;
}

// CIDs whose GID is present in a re-subset program. With RETAIN_GIDS the
// program keeps the original glyph indices, so CIDToGIDMap and the CID-keyed
// CFF charset still describe which CIDs survived.
static std::set<uint32_t> cids_for_retained_glyphs(QPDFObjectHandle& cid_to_gid, bool cid_identity,
                                                   std::map<uint32_t, uint32_t> const& gid_to_cid,
                                                   std::set<FT_UInt> const& glyphs) {
    std::set<uint32_t> cids{0};
    if (cid_to_gid.isStream()) {
        try {
            std::shared_ptr<Buffer> data = cid_to_gid.getStreamData(qpdf_dl_generalized);
            if (data) {
                const auto* bytes = static_cast<const uint8_t*>(data->getBuffer());
                const size_t entries = data->getSize() / 2;
                for (size_t c = 0; c < entries && c <= kFontMaxCid; ++c) {
                    const uint32_t gid =
                        (static_cast<uint32_t>(bytes[2 * c]) << 8) | bytes[2 * c + 1];
                    if (glyphs.contains(static_cast<FT_UInt>(gid)))
                        cids.insert(static_cast<uint32_t>(c));
                }
            }
        } catch (...) {
            return {0};
        }
        return cids;
    }
    if (cid_identity && !gid_to_cid.empty()) {
        for (FT_UInt gid : glyphs) {
            auto found = gid_to_cid.find(static_cast<uint32_t>(gid));
            if (found != gid_to_cid.end() && found->second <= kFontMaxCid)
                cids.insert(found->second);
        }
        return cids;
    }
    if (cid_identity) {
        for (FT_UInt gid : glyphs)
            if (gid <= kFontMaxCid) cids.insert(static_cast<uint32_t>(gid));
    }
    return cids;
}

// Repair or synthesize /CIDSet so its bitset matches the embedded program.
// ISO 19005-2 6.2.11.4 test 4 counts every CID present in the program,
// regardless of whether content draws it.
static void repair_cidset_stream(QPDF& pdf, QPDFObjectHandle& descriptor, FT_Face face,
                                 QPDFObjectHandle& cid_to_gid, bool cid_identity,
                                 std::map<uint32_t, uint32_t> const& gid_to_cid,
                                 FontNormalizeState& state) {
    std::set<uint32_t> cids = cids_present_in_program(face, cid_to_gid, cid_identity, gid_to_cid);
    if (cids.empty()) return;
    cids.insert(0);

    const std::string bits = build_cid_set_bits(cids);
    bool differs = true;
    QPDFObjectHandle existing = descriptor.getKey("/CIDSet");
    if (existing.isStream()) {
        try {
            std::shared_ptr<Buffer> old = existing.getStreamData(qpdf_dl_generalized);
            if (old)
                differs = std::string(reinterpret_cast<const char*>(old->getBuffer()),
                                      old->getSize()) != bits;
        } catch (...) {
            differs = true;
        }
    }
    if (differs) {
        descriptor.replaceKey("/CIDSet", QPDFObjectHandle::newStream(&pdf, bits));
        state.changed = true;
    }
}

// Normalize a single PDF font dictionary across ToUnicode, widths, CFF packaging, and subsetting.
static void process_font(QPDF& pdf, QPDFObjectHandle font, bool do_fix_tounicode,
                         bool do_repair_widths, FontUsageCounts const& usage,
                         FontNormalizeState& state) {
    QPDFObjectHandle subtype = font.getKey("/Subtype");
    if (!subtype.isName()) return;
    const std::string subtype_name = subtype.getName();
    const bool cid = subtype_name == "/Type0";
    if (!cid && subtype_name != "/TrueType" && subtype_name != "/Type1" &&
        subtype_name != "/MMType1")
        return;  // Type3 and anything unknown is left untouched.

    QPDFObjectHandle target = font;
    if (cid) {
        QPDFObjectHandle descendants = font.getKey("/DescendantFonts");
        if (!descendants.isArray() || descendants.getArrayNItems() < 1) return;
        target = descendants.getArrayItem(0);
        if (!target.isDictionary()) return;
        // Only Identity-H/V uses two-byte Identity CIDs. A predefined or
        // embedded CMap changes the byte-to-CID mapping, so never assume
        // identity for those fonts.
        QPDFObjectHandle cid_encoding = font.getKey("/Encoding");
        if (!cid_encoding.isName() || (!cid_encoding.isNameAndEquals("/Identity-H") &&
                                       !cid_encoding.isNameAndEquals("/Identity-V")))
            return;
    }

    QPDFObjectHandle descriptor = target.getKey("/FontDescriptor");
    if (!descriptor.isDictionary()) return;

    QPDFObjectHandle stream;
    for (char const* key : {"/FontFile2", "/FontFile3", "/FontFile"}) {
        QPDFObjectHandle candidate = descriptor.getKey(key);
        if (candidate.isStream()) {
            stream = candidate;
            break;
        }
    }
    if (!stream.isStream()) return;  // not embedded: PdfFontEmbedder handles that case

    std::shared_ptr<Buffer> data;
    try {
        data = stream.getStreamData(qpdf_dl_generalized);
    } catch (...) {
        return;
    }
    if (!data || data->getSize() == 0) return;

    // A TrueType collection stores several faces in one stream. Match /BaseFont
    // or /FontName against each face so the rest of the pipeline reads the
    // face the PDF actually references.
    std::set<std::string> desired_faces;
    for (char const* key : {"/BaseFont", "/FontName"}) {
        QPDFObjectHandle candidate = font.getKey(key);
        if (!candidate.isName()) candidate = target.getKey(key);
        if (!candidate.isName()) candidate = descriptor.getKey(key);
        if (candidate.isName()) {
            std::string face_key = font_name_key(candidate.getName());
            if (!face_key.empty()) desired_faces.insert(face_key);
        }
    }

    ensure_freetype_init();
    if (!ft_lib) return;
    const FT_Long face_index =
        select_collection_face(ft_lib, data->getBuffer(), data->getSize(), desired_faces);
    FT_Face raw = nullptr;
    if (FT_New_Memory_Face(ft_lib, data->getBuffer(), (FT_Long)data->getSize(), face_index, &raw) !=
            0 ||
        !raw)
        return;
    std::unique_ptr<FT_FaceRec, decltype(&FT_Done_Face)> face(raw, FT_Done_Face);
    if (face->num_glyphs <= 0) return;
    ++state.fonts_processed;

    const bool has_unicode_charmap = FT_Select_Charmap(face.get(), FT_ENCODING_UNICODE) == 0;
    std::map<uint32_t, uint32_t> gid_to_unicode;
    std::set<uint32_t> ambiguous_gids;
    auto is_space_scalar = [](uint32_t value) {
        return value == 0x20 || value == 0xA0 || value == 0xAD || value == 0x2D;
    };
    if (has_unicode_charmap) {
        FT_UInt gid = 0;
        FT_ULong codepoint = FT_Get_First_Char(face.get(), &gid);
        while (gid != 0) {
            uint32_t unicode = static_cast<uint32_t>(codepoint);
            auto [it, inserted] = gid_to_unicode.emplace(static_cast<uint32_t>(gid), unicode);
            if (!inserted && it->second != unicode && !is_space_scalar(it->second) &&
                !is_space_scalar(unicode))
                ambiguous_gids.insert(static_cast<uint32_t>(gid));
            codepoint = FT_Get_Next_Char(face.get(), codepoint, &gid);
        }
    }

    // Verify how CIDs map to glyphs before trusting CID == GID.
    QPDFObjectHandle cid_to_gid;
    bool cid_identity = false;
    std::map<uint32_t, uint32_t> gid_to_cid;
    if (cid) {
        QPDFObjectHandle cid_subtype = target.getKey("/Subtype");
        cid_to_gid = target.getKey("/CIDToGIDMap");
        if (cid_to_gid.isStream()) {
            // Explicit mapping stream; decoded by the ToUnicode/width helpers.
        } else if (cid_to_gid.isNull() || cid_to_gid.isNameAndEquals("/Identity")) {
            if (FT_IS_CID_KEYED(face.get())) {
                if (!cid_subtype.isNameAndEquals("/CIDFontType0")) return;
                for (FT_UInt gid = 0; gid < static_cast<FT_UInt>(face->num_glyphs); ++gid) {
                    FT_UInt cid_value = 0;
                    if (FT_Get_CID_From_Glyph_Index(face.get(), gid, &cid_value) == 0 &&
                        cid_value <= 0xFFFF)
                        gid_to_cid[static_cast<uint32_t>(gid)] = static_cast<uint32_t>(cid_value);
                }
                cid_identity = true;
            } else {
                if (!cid_subtype.isNameAndEquals("/CIDFontType2")) return;
                cid_identity = true;
            }
        } else {
            return;  // Unsupported /CIDToGIDMap value.
        }
    }

    const char* font_format = FT_Get_Font_Format(face.get());
    const bool is_type1_family = subtype_name == "/Type1" || subtype_name == "/MMType1" ||
                                 (font_format && (std::strcmp(font_format, "Type 1") == 0 ||
                                                  std::strcmp(font_format, "CFF") == 0));

    SimpleEncoding simple_encoding;
    std::vector<SimpleGlyph> simple_glyphs;
    bool simple_any_gid = false;
    if (!cid) {
        simple_encoding = parseSimpleEncoding(font, descriptor);
        if (!simple_encoding.unsupportedEncoding) {
            simple_glyphs = resolveSimpleChars(face.get(), has_unicode_charmap, gid_to_unicode,
                                               simple_encoding, is_type1_family);
            for (auto const& glyph : simple_glyphs) {
                if (glyph.gid != 0) {
                    simple_any_gid = true;
                    break;
                }
            }
        }
    }

    if (do_fix_tounicode) {
        repair_font_tounicode(pdf, font, face.get(), cid, cid_to_gid, cid_identity, gid_to_cid,
                              gid_to_unicode, ambiguous_gids, simple_any_gid, simple_glyphs, state);
    }
    if (do_repair_widths && face->units_per_EM > 0) {
        if (!cid) {
            repair_simple_font_widths(target, face.get(), simple_encoding, simple_any_gid,
                                      simple_glyphs, state);
        } else {
            repair_cid_font_widths(pdf, font, target, face.get(), cid_to_gid, cid_identity,
                                   gid_to_cid, state);
        }
    }

    const bool full = do_fix_tounicode && do_repair_widths;
    bool did_convert = false;
    if (full) {
        const int32_t converted_before = state.type1_converted;
        convert_cff_in_sfnt(pdf, descriptor, face, cid, subtype_name, simple_glyphs, state);
        did_convert = state.type1_converted != converted_before;
    }
    bool did_resubset = false;
    std::set<FT_UInt> retained_glyphs;
    if (full && !did_convert) {
        did_resubset = resubset_embedded_sfnt(pdf, font, target, descriptor, stream, data,
                                              face_index, face, cid, cid_to_gid, cid_identity,
                                              simple_glyphs, usage, state, &retained_glyphs);
    }
    if (cid && full) {
        if (did_resubset) {
            // The FreeType face still describes the original program. Derive
            // CIDSet from the glyphs the subset actually retained, not from
            // the pre-subset program or from drawn CIDs alone.
            std::set<uint32_t> cids =
                cids_for_retained_glyphs(cid_to_gid, cid_identity, gid_to_cid, retained_glyphs);
            std::string bits = build_cid_set_bits(cids);
            QPDFObjectHandle existing = descriptor.getKey("/CIDSet");
            bool differs = true;
            if (existing.isStream()) {
                try {
                    std::shared_ptr<Buffer> old = existing.getStreamData(qpdf_dl_generalized);
                    if (old)
                        differs = std::string(reinterpret_cast<const char*>(old->getBuffer()),
                                              old->getSize()) != bits;
                } catch (...) {
                    differs = true;
                }
            }
            if (differs) {
                descriptor.replaceKey("/CIDSet", QPDFObjectHandle::newStream(&pdf, bits));
                state.changed = true;
            }
        } else {
            repair_cidset_stream(pdf, descriptor, face.get(), cid_to_gid, cid_identity, gid_to_cid,
                                 state);
        }
    }
}

static int32_t font_normalize_qpdf(DocWrapper* w, int32_t page_index, bool do_fix_tounicode,
                                   bool do_repair_widths, int32_t* fonts_processed,
                                   int32_t* tuc_fixed, int32_t* w_fixed, int32_t* type1_converted,
                                   int32_t* resubset) {
    if (!w || !w->core || !w->core->doc || !fonts_processed || !tuc_fixed || !w_fixed ||
        !type1_converted || !resubset)
        return JPDFIUM_ERR_INVALID;
    *fonts_processed = 0;
    *tuc_fixed = 0;
    *w_fixed = 0;
    *type1_converted = 0;
    *resubset = 0;

    const int page_count = FPDF_GetPageCount(w->core->doc);
    if (page_index < -1 || page_index >= page_count) return JPDFIUM_ERR_INVALID;

    std::vector<uint8_t> pdf_bytes;
    if (!qpdf_save_fpdf(w->core->doc, pdf_bytes)) return JPDFIUM_ERR_IO;

    try {
        QPDF pdf;
        pdf.setSuppressWarnings(true);
        pdf.setAttemptRecovery(true);
        pdf.processMemoryFile("font_normalize", reinterpret_cast<const char*>(pdf_bytes.data()),
                              pdf_bytes.size());

        std::vector<QPDFObjectHandle> pages = pdf.getAllPages();
        if (page_index >= static_cast<int>(pages.size())) return JPDFIUM_ERR_INVALID;

        // Count how often each descriptor and embedded program is referenced
        // before touching anything. A program shared by several font
        // dictionaries is never rewritten, because the other dictionaries
        // would keep pointing at the stale glyph set.
        FontUsageCounts usage;
        {
            std::set<QPDFObjGen> counted;
            for (auto& page : pages) {
                std::set<QPDFObjGen> seen_resources;
                std::set<QPDFObjGen> seen_fonts;
                for (auto& font : collect_page_fonts(page, seen_resources, seen_fonts))
                    count_font_usage(font, counted, usage);
            }
            // Mark any fonts, descriptors, and streams referenced by /AcroForm /DR
            // as heavily shared (sentinel count 999999) so they are never subsetted.
            // Subsetting form fonts would break interactive text entry for undrawn characters.
            auto acro_form = pdf.getRoot().getKey("/AcroForm");
            if (acro_form.isDictionary() && acro_form.hasKey("/DR")) {
                std::set<QPDFObjGen> seen_dr_resources;
                std::set<QPDFObjGen> seen_dr_fonts;
                std::map<QPDFObjGen, int> dr_streams;
                std::vector<QPDFObjectHandle> dr_fonts;
                collect_resource_fonts(acro_form.getKey("/DR"), 0, seen_dr_resources, seen_dr_fonts,
                                       dr_streams, dr_fonts);
                for (auto& dr_font : dr_fonts) {
                    mark_form_font_not_subsettable(dr_font, usage);
                }
            }
        }

        FontNormalizeState state;

        // page_index == -1 means every page in one qpdf pass. Shared resources
        // and fonts are visited once, and the document is written back once.
        if (page_index == -1) {
            std::set<QPDFObjGen> seen_resources;
            std::set<QPDFObjGen> seen_fonts;
            for (auto& page : pages) {
                std::vector<QPDFObjectHandle> fonts =
                    collect_page_fonts(page, seen_resources, seen_fonts);
                for (auto& font : fonts) {
                    try {
                        process_font(pdf, font, do_fix_tounicode, do_repair_widths, usage, state);
                    } catch (...) {
                        // One malformed font must not abort the rest of the document.
                    }
                }
            }
            // AcroForm default resources can carry fonts used by form fields
            // that never appear in a page's /Resources.
            std::vector<QPDFObjectHandle> form_fonts;
            std::map<QPDFObjGen, int> form_streams;
            collect_resource_fonts(pdf.getRoot().getKey("/AcroForm").getKey("/DR"), 0,
                                   seen_resources, seen_fonts, form_streams, form_fonts);
            for (auto& font : form_fonts) {
                try {
                    process_font(pdf, font, do_fix_tounicode, do_repair_widths, usage, state);
                } catch (...) {
                    // One malformed font must not abort the rest of the document.
                }
            }
        } else {
            std::set<QPDFObjGen> seen_resources;
            std::set<QPDFObjGen> seen_fonts;
            std::vector<QPDFObjectHandle> fonts =
                collect_page_fonts(pages[page_index], seen_resources, seen_fonts);
            for (auto& font : fonts) {
                try {
                    process_font(pdf, font, do_fix_tounicode, do_repair_widths, usage, state);
                } catch (...) {
                    // One malformed font must not abort the rest of the page.
                }
            }
        }

        *fonts_processed = state.fonts_processed;
        *tuc_fixed = state.tuc_fixed;
        *w_fixed = state.w_fixed;
        *type1_converted = state.type1_converted;
        *resubset = state.resubset;

        if (state.changed) return docwrapper_reload(w, qpdf_write(pdf));
        return JPDFIUM_OK;
    } catch (QPDFExc const& e) {
        return e.getErrorCode() == qpdf_e_password ? JPDFIUM_ERR_PASSWORD : JPDFIUM_ERR_INVALID;
    } catch (std::exception&) {
        return JPDFIUM_ERR_INVALID;
    }
}

#endif  // JPDFIUM_HAS_QPDF

int32_t jpdfium_font_fix_tounicode(int64_t doc, int32_t page_index, int32_t* fonts_fixed) {
    try {
        int32_t ignored = 0;
        int32_t* fixed = fonts_fixed ? fonts_fixed : &ignored;
        *fixed = 0;
        DocWrapper* w = decodeDoc(doc);
        if (!w || !w->core || !w->core->doc) return JPDFIUM_ERR_INVALID;
#ifdef JPDFIUM_HAS_QPDF
        int32_t processed = 0;
        int32_t widths = 0;
        int32_t type1 = 0;
        int32_t resubset = 0;
        return font_normalize_qpdf(w, page_index, true, false, &processed, fixed, &widths, &type1,
                                   &resubset);
#else
        (void)page_index;
        return JPDFIUM_ERR_NOT_FOUND;
#endif
    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

int32_t jpdfium_font_repair_widths(int64_t doc, int32_t page_index, int32_t* fonts_fixed) {
    try {
        int32_t ignored = 0;
        int32_t* fixed = fonts_fixed ? fonts_fixed : &ignored;
        *fixed = 0;
        DocWrapper* w = decodeDoc(doc);
        if (!w || !w->core || !w->core->doc) return JPDFIUM_ERR_INVALID;
#ifdef JPDFIUM_HAS_QPDF
        int32_t processed = 0;
        int32_t tounicode = 0;
        int32_t type1 = 0;
        int32_t resubset = 0;
        return font_normalize_qpdf(w, page_index, false, true, &processed, &tounicode, fixed,
                                   &type1, &resubset);
#else
        (void)page_index;
        return JPDFIUM_ERR_NOT_FOUND;
#endif
    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

int32_t jpdfium_font_normalize_page(int64_t doc, int32_t page_index, char** json) {
    try {
        if (!json) return JPDFIUM_ERR_INVALID;
        *json = nullptr;

        DocWrapper* w = decodeDoc(doc);
        if (!w || !w->core || !w->core->doc) return JPDFIUM_ERR_INVALID;

        int32_t processed = 0, tuc_fixed = 0, w_fixed = 0;
        int32_t type1_converted = 0, resubset = 0;
#ifdef JPDFIUM_HAS_QPDF
        int32_t rc = font_normalize_qpdf(w, page_index, true, true, &processed, &tuc_fixed,
                                         &w_fixed, &type1_converted, &resubset);
        if (rc != JPDFIUM_OK) return rc;
#else
        (void)page_index;
        return JPDFIUM_ERR_NOT_FOUND;
#endif

        char buf[256];
        snprintf(buf, sizeof(buf),
                 "{\"fonts_processed\":%d,\"tounicode_fixed\":%d,"
                 "\"widths_repaired\":%d,\"type1_converted\":%d,\"resubset\":%d}",
                 processed, tuc_fixed, w_fixed, type1_converted, resubset);
        *json = strdup(buf);
        return *json ? JPDFIUM_OK : JPDFIUM_ERR_NATIVE;

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

        // Create HarfBuzz blob from font data
        hb_blob_t* blob = hb_blob_create((const char*)font_data, (unsigned int)font_len,
                                         HB_MEMORY_MODE_READONLY, nullptr, nullptr);
        hb_face_t* face = hb_face_create(blob, 0);
        hb_blob_destroy(blob);

        if (!face) return JPDFIUM_ERR_INVALID;
        if (hb_face_get_glyph_count(face) <= 1) {
            hb_face_destroy(face);
            return JPDFIUM_ERR_INVALID;
        }

        // Create subset input
        hb_subset_input_t* input = hb_subset_input_create_or_fail();
        if (!input) {
            hb_face_destroy(face);
            return JPDFIUM_ERR_NATIVE;
        }

        // Add requested codepoints
        hb_set_t* unicode_set = hb_subset_input_unicode_set(input);
        for (int32_t i = 0; i < num_codepoints; i++) {
            hb_set_add(unicode_set, codepoints[i]);
        }

        // Keep glyph names and the .notdef outline so Java AWT and PDFBox can
        // parse and render the subset (PDFBOX-490).
        unsigned int flags = HB_SUBSET_FLAGS_GLYPH_NAMES | HB_SUBSET_FLAGS_NOTDEF_OUTLINE;
        if (retain_gids) flags |= HB_SUBSET_FLAGS_RETAIN_GIDS;
        hb_subset_input_set_flags(input, flags);

        // Perform subsetting
        hb_face_t* subset_face = hb_subset_or_fail(face, input);
        hb_subset_input_destroy(input);
        hb_face_destroy(face);

        if (!subset_face) return JPDFIUM_ERR_NATIVE;

        // Extract result
        hb_blob_t* result_blob = hb_face_reference_blob(subset_face);
        unsigned int result_len;
        const char* result_data = hb_blob_get_data(result_blob, &result_len);

        *out_len = result_len;
        if (result_len == 0) {
            hb_blob_destroy(result_blob);
            hb_face_destroy(subset_face);
            return JPDFIUM_ERR_NATIVE;
        }
        *out_data = (uint8_t*)malloc(result_len);
        if (!*out_data) {
            hb_blob_destroy(result_blob);
            hb_face_destroy(subset_face);
            return JPDFIUM_ERR_NATIVE;
        }
        memcpy(*out_data, result_data, result_len);

        hb_blob_destroy(result_blob);
        hb_face_destroy(subset_face);

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
int32_t jpdfium_font_fix_tounicode(int64_t, int32_t, int32_t* f) {
    if (f) *f = 0;
    return JPDFIUM_ERR_NOT_FOUND;
}
int32_t jpdfium_font_repair_widths(int64_t, int32_t, int32_t* f) {
    if (f) *f = 0;
    return JPDFIUM_ERR_NOT_FOUND;
}
int32_t jpdfium_font_normalize_page(int64_t, int32_t, char** json) {
    if (json) *json = strdup("{\"error\":\"FreeType/HarfBuzz not available\"}");
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

#include <hb.h>
#include <unicode/ubidi.h>
#include <unicode/unistr.h>

int32_t jpdfium_redact_glyph_aware(int64_t page, const char** words, int32_t word_count,
                                   uint32_t argb, float padding, uint32_t flags,
                                   int32_t* match_count, char** result_json) {
    try {
        if (!words || word_count <= 0 || !match_count || !result_json) return JPDFIUM_ERR_INVALID;

        // Real implementation pipeline:
        // 1. Extract text with FPDFText_GetCharBox() for per-character bounding boxes
        // 2. If JPDFIUM_GLYPH_LIGATURE_AWARE: use HarfBuzz hb_shape() for cluster mapping
        // 3. If JPDFIUM_GLYPH_BIDI_AWARE: ICU BiDi for visual order resolution
        // 4. If JPDFIUM_GLYPH_GRAPHEME_SAFE: grapheme cluster boundaries
        // 5. Match words against extracted text
        // 6. Compute precise redact rectangles from glyph advances
        // 7. If JPDFIUM_GLYPH_REMOVE_STREAM: qpdf structural content removal
        // 8. Paint filled rectangles

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

// Save an FPDF_DOCUMENT to a byte vector.
static bool qpdf_save_fpdf(FPDF_DOCUMENT doc, std::vector<uint8_t>& out) {
    struct BufWriter : FPDF_FILEWRITE {
        std::vector<uint8_t>* buf;
        static int Write(FPDF_FILEWRITE* self, const void* data, unsigned long size) {
            auto* bw = static_cast<BufWriter*>(self);
            try {
                const auto* src = static_cast<const uint8_t*>(data);
                bw->buf->insert(bw->buf->end(), src, src + size);
                return 1;
            } catch (...) {
                return 0;  // never let exceptions cross the C callback
            }
        }
    } bw;
    bw.version = 1;
    bw.WriteBlock = BufWriter::Write;
    bw.buf = &out;
    return FPDF_SaveAsCopy(doc, &bw, FPDF_NO_INCREMENTAL) != 0;
}

// Write a QPDF document to a byte vector.
static std::vector<uint8_t> qpdf_write(QPDF& pdf) {
    QPDFWriter writer(pdf);
    writer.setOutputMemory();
    writer.setLinearization(false);
    writer.setCompressStreams(true);
    // PDF/A validators expect the endstream keyword on its own line. Write it
    // explicitly instead of relying on the qpdf default.
    writer.setNewlineBeforeEndstream(true);
    jpdfium_qpdf_internal::WriterLock lock{-1};
    writer.write();
    std::shared_ptr<Buffer> buf = writer.getBufferSharedPointer();
    std::vector<uint8_t> result(buf->getSize());
    memcpy(result.data(), buf->getBuffer(), buf->getSize());
    return result;
}

// Replace the FPDF_DOCUMENT inside a DocWrapper with a document loaded from new
// bytes. The DocWrapper pointer itself is unchanged, so the Java-side handle
// remains valid.
//
// Strong failure contract: the candidate is fully prepared, loaded, and
// validated before anything is retired. If any step fails, the original
// document is left untouched and usable. Only after a valid candidate exists
// are the old resources retired and the replacement committed.
static int32_t docwrapper_reload(DocWrapper* w, const std::vector<uint8_t>& newBytes) {
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

    // Retire native state tied to the old document before committing the
    // replacement: loaded FPDF_FONT handles belong to the old FPDF_DOCUMENT
    // and would otherwise dangle. The redaction audit state is deliberately
    // preserved: reload (metadata/font stripping) round-trips the same pages
    // through QPDF, so committed redactions, unapplied-mark counts, and the
    // literal/zone audit trail stay valid for the replacement bytes. Dropping
    // them here would silently disable the save-time sanitize pass (which
    // requires contentRedacted + sanitizeOnSave and scrubs XMP against
    // redactedLiterals) and the uncommitted-marks save refusal on the very
    // documents that need them. Only per-document resources are retired:
    // font handles, the pattern cache, the stale sanitize report, and the
    // source path (the replacement is memory-backed, so alias protection
    // against the old backing file no longer applies).
    for (FPDF_FONT f : w->core->loadedFonts) {
        FPDFFont_Close(f);
    }
    w->core->loadedFonts.clear();
    w->core->clearRedactPatternCache();
    w->core->touchedFontsSet.clear();
    w->core->touchedFontNames.clear();
    w->core->sanitizeReport.clear();
    w->core->sourcePath.clear();

    FPDF_CloseDocument(w->core->doc);
    free(w->core->buf);  // free() ignores NULL
    w->core->doc = candidate;
    w->core->buf = copy;
    w->core->blen = static_cast<int64_t>(sz);
    // Replacement committed: previously opened pages now point into the freed
    // document. Their wrappers detect the mismatch and refuse use and release.
    ++w->core->generation;
    return JPDFIUM_OK;
}

int32_t jpdfium_metadata_strip(int64_t doc, const char** keys, int32_t key_count) {
    DocWrapper* w = decodeDoc(doc);
    if (!w || !w->core->doc || !keys || key_count <= 0) return JPDFIUM_ERR_INVALID;

    std::vector<uint8_t> pdfBytes;
    if (!qpdf_save_fpdf(w->core->doc, pdfBytes)) return JPDFIUM_ERR_IO;

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

        return docwrapper_reload(w, qpdf_write(pdf));
    } catch (std::exception&) {
        return JPDFIUM_ERR_NATIVE;
    }
}

int32_t jpdfium_metadata_strip_all(int64_t doc) {
    DocWrapper* w = decodeDoc(doc);
    if (!w || !w->core->doc) return JPDFIUM_ERR_INVALID;

    std::vector<uint8_t> pdfBytes;
    if (!qpdf_save_fpdf(w->core->doc, pdfBytes)) return JPDFIUM_ERR_IO;

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

        return docwrapper_reload(w, qpdf_write(pdf));
    } catch (std::exception&) {
        return JPDFIUM_ERR_NATIVE;
    }
}

int32_t jpdfium_strip_fonts(int64_t doc, int32_t* fonts_removed) {
    DocWrapper* w = decodeDoc(doc);
    if (!w || !w->core->doc || !fonts_removed) return JPDFIUM_ERR_INVALID;

    std::vector<uint8_t> pdfBytes;
    if (!qpdf_save_fpdf(w->core->doc, pdfBytes)) return JPDFIUM_ERR_IO;

    try {
        QPDF pdf;
        pdf.setSuppressWarnings(true);
        pdf.setAttemptRecovery(true);
        pdf.processMemoryFile("strip_fonts", reinterpret_cast<const char*>(pdfBytes.data()),
                              pdfBytes.size());

        int count = 0;
        std::set<int> processed;  // object IDs of shared resource dicts already stripped

        // NOLINTNEXTLINE(performance-for-range-copy) - copy is intentional; hasKey/getKey are
        // non-const in libqpdf on Ubuntu 24.04 (qpdf < 11.9 in apt)
        for (auto page : pdf.getAllPages()) {
            if (!page.hasKey("/Resources")) continue;
            QPDFObjectHandle resources = page.getKey("/Resources");
            if (!resources.isDictionary()) continue;

            // Shared indirect resource dicts appear on multiple pages; strip only once.
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
        return docwrapper_reload(w, qpdf_write(pdf));
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
            *result = strdup(text);  // fallback to original
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
            if (!first) json += ",";
            first = false;

            icu::UnicodeString segment;
            utext.extractBetween(start, end, segment);
            std::string segUtf8;
            segment.toUTF8String(segUtf8);

            // Escape for JSON
            std::string escaped;
            for (char c : segUtf8) {
                switch (c) {
                    case '"':
                        escaped += "\\\"";
                        break;
                    case '\\':
                        escaped += "\\\\";
                        break;
                    case '\n':
                        escaped += "\\n";
                        break;
                    case '\r':
                        escaped += "\\r";
                        break;
                    case '\t':
                        escaped += "\\t";
                        break;
                    default:
                        escaped += c;
                }
            }

            char buf[4096];
            snprintf(buf, sizeof(buf), "{\"start\":%d,\"end\":%d,\"text\":\"%s\"}", start, end,
                     escaped.c_str());
            json += buf;

            start = end;
            end = bi->next();
        }

        json += "]";
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

        // Get visual ordering
        icu::UnicodeString visual(length, 0, 0);
        int32_t runCount = ubidi_countRuns(bidi, &status);
        int32_t visPos = 0;

        for (int32_t i = 0; i < runCount; i++) {
            int32_t logStart, runLen;
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

#else  // !JPDFIUM_HAS_ICU

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
