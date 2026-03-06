// Stub implementation - returns realistic data for Java-layer testing.
// Compiles without PDFium or any external library.
// Text extraction returns PII-rich text; PCRE2 uses std::regex;
// FlashText does substring matching; doc.save() copies the input file.
#include "jpdfium.h"
#include <cstring>
#include <cstdlib>
#include <cstdio>
#include <algorithm>
#include <regex>
#include <sstream>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

// Stub page text - contains words and PII so the pipeline has real data
static const char* STUB_TEXT =
    "Hello World Confidential DRAFT Dummy Redaction\n"
    "Introduction Bold item Gradient Row brown fox\n"
    "Contact: test@example.com Phone: (555) 123-4567\n"
    "SSN: 123-45-6789 Size 10 Languages Rot Scale 6789\n"
    "Card: 4111-1111-1111-1111 Consider Employ VM\n"
    "John Smith works at Acme Corp custom certificat";

// Internal state

struct StubDoc {
    std::string path;
    std::vector<uint8_t> bytes;
};
static std::unordered_map<int64_t, StubDoc> g_docs;
static int64_t g_next_doc = 12345LL;

static std::unordered_map<int64_t, std::string> g_page_text;
static int64_t g_next_page = 99000LL;

struct StubPattern { std::string regex; };
static std::unordered_map<int64_t, StubPattern> g_pcre;
static int64_t g_next_pcre = 77001LL;

struct StubFlashText {
    std::vector<std::pair<std::string, std::string>> keywords;
};
static std::unordered_map<int64_t, StubFlashText> g_flash;
static int64_t g_next_flash = 88001LL;

// Helpers

static std::string json_escape(const std::string& s) {
    std::string out;
    out.reserve(s.size());
    for (char c : s) {
        if (c == '"' || c == '\\') out += '\\';
        out += c;
    }
    return out;
}

static char* generate_chars_json(const char* text) {
    std::ostringstream os;
    os << '[';
    float x = 10.0f, y = 800.0f;
    bool first = true;
    int idx = 0;
    for (int i = 0; text[i]; ++i) {
        if (text[i] == '\n') { y -= 15.0f; x = 10.0f; continue; }
        if (!first) os << ',';
        first = false;
        os << "{\"i\":" << idx++
           << ",\"u\":" << (int)(unsigned char)text[i]
           << ",\"x\":" << x << ",\"y\":" << y
           << ",\"w\":7.0,\"h\":12.0"
           << ",\"font\":\"Helvetica\",\"size\":12.0}";
        x += 7.0f;
    }
    os << ']';
    return strdup(os.str().c_str());
}

static int count_occurrences(const std::string& haystack, const std::string& needle,
                             bool case_sensitive) {
    if (needle.empty()) return 0;
    std::string h = haystack, n = needle;
    if (!case_sensitive) {
        std::transform(h.begin(), h.end(), h.begin(), ::tolower);
        std::transform(n.begin(), n.end(), n.begin(), ::tolower);
    }
    int count = 0;
    size_t pos = 0;
    while ((pos = h.find(n, pos)) != std::string::npos) {
        ++count;
        pos += n.size();
    }
    return count;
}

//  Core Document Functions

int32_t jpdfium_init()    { return JPDFIUM_OK; }
void    jpdfium_destroy() {}

int32_t jpdfium_doc_open(const char* path, int64_t* handle) {
    *handle = g_next_doc++;
    g_docs[*handle] = {path ? path : "", {}};
    return JPDFIUM_OK;
}

int32_t jpdfium_doc_open_bytes(const uint8_t* data, int64_t len, int64_t* handle) {
    *handle = g_next_doc++;
    g_docs[*handle] = {"", std::vector<uint8_t>(data, data + len)};
    return JPDFIUM_OK;
}

int32_t jpdfium_doc_open_protected(const char* path, const char*, int64_t* handle) {
    *handle = g_next_doc++;
    g_docs[*handle] = {path ? path : "", {}};
    return JPDFIUM_OK;
}

int32_t jpdfium_doc_page_count(int64_t, int32_t* count) {
    *count = 3;
    return JPDFIUM_OK;
}

int32_t jpdfium_doc_save(int64_t handle, const char* output_path) {
    auto it = g_docs.find(handle);
    if (it == g_docs.end()) return JPDFIUM_OK;

    if (!it->second.path.empty()) {
        FILE* in = fopen(it->second.path.c_str(), "rb");
        if (in) {
            FILE* out = fopen(output_path, "wb");
            if (out) {
                char buf[8192];
                size_t n;
                while ((n = fread(buf, 1, sizeof(buf), in)) > 0)
                    fwrite(buf, 1, n, out);
                fclose(out);
            }
            fclose(in);
        }
    } else if (!it->second.bytes.empty()) {
        FILE* out = fopen(output_path, "wb");
        if (out) {
            fwrite(it->second.bytes.data(), 1, it->second.bytes.size(), out);
            fclose(out);
        }
    }
    return JPDFIUM_OK;
}

int32_t jpdfium_doc_save_bytes(int64_t handle, uint8_t** data, int64_t* len) {
    auto it = g_docs.find(handle);
    if (it != g_docs.end() && !it->second.bytes.empty()) {
        *len = (int64_t)it->second.bytes.size();
        *data = (uint8_t*)malloc(*len);
        memcpy(*data, it->second.bytes.data(), *len);
        return JPDFIUM_OK;
    }
    if (it != g_docs.end() && !it->second.path.empty()) {
        FILE* f = fopen(it->second.path.c_str(), "rb");
        if (f) {
            fseek(f, 0, SEEK_END);
            long sz = ftell(f);
            fseek(f, 0, SEEK_SET);
            *data = (uint8_t*)malloc(sz);
            *len = (int64_t)fread(*data, 1, sz, f);
            fclose(f);
            return JPDFIUM_OK;
        }
    }
    const char* stub = "%PDF-1.4 stub";
    *len = (int64_t)strlen(stub);
    *data = (uint8_t*)strdup(stub);
    return JPDFIUM_OK;
}

void jpdfium_doc_close(int64_t handle) {
    g_docs.erase(handle);
}

//  Page Functions

int32_t jpdfium_page_open(int64_t, int32_t, int64_t* handle) {
    *handle = g_next_page++;
    return JPDFIUM_OK;
}

int32_t jpdfium_page_width(int64_t, float* w)  { *w = 595.0f; return JPDFIUM_OK; }
int32_t jpdfium_page_height(int64_t, float* h) { *h = 842.0f; return JPDFIUM_OK; }

void jpdfium_page_close(int64_t handle) {
    g_page_text.erase(handle);
}

int32_t jpdfium_render_page(int64_t, int32_t, uint8_t** rgba, int32_t* w, int32_t* h) {
    *w = 100; *h = 100;
    *rgba = (uint8_t*)calloc(100 * 100 * 4, 1);
    return JPDFIUM_OK;
}

void jpdfium_free_buffer(uint8_t* buf) { free(buf); }

//  Text Extraction

int32_t jpdfium_text_get_chars(int64_t page, char** json) {
    g_page_text[page] = STUB_TEXT;
    *json = generate_chars_json(STUB_TEXT);
    return JPDFIUM_OK;
}

int32_t jpdfium_text_find(int64_t, const char*, char** json) {
    *json = strdup("[]");
    return JPDFIUM_OK;
}

void jpdfium_free_string(char* s) { free(s); }

//  Redaction

int32_t jpdfium_redact_region(int64_t, float, float, float, float, uint32_t, int32_t) {
    return JPDFIUM_OK;
}

int32_t jpdfium_redact_pattern(int64_t, const char*, uint32_t, int32_t) {
    return JPDFIUM_OK;
}

int32_t jpdfium_redact_words(int64_t, const char**, int32_t, uint32_t, float,
                             int32_t, int32_t, int32_t) {
    return JPDFIUM_OK;
}

int32_t jpdfium_redact_words_ex(int64_t page, const char** words, int32_t word_count,
                                uint32_t, float, int32_t, int32_t,
                                int32_t, int32_t case_sensitive, int32_t* matchCount) {
    int matches = 0;
    // Use cached page text if available, otherwise fall back to STUB_TEXT.
    // The page handle here may differ from the one used in text_get_chars
    // because the Java pipeline opens/closes pages in separate passes.
    auto it = g_page_text.find(page);
    std::string text = (it != g_page_text.end()) ? it->second : std::string(STUB_TEXT);
    if (words) {
        for (int i = 0; i < word_count; ++i) {
            if (!words[i]) continue;
            matches += count_occurrences(text, words[i], case_sensitive != 0);
        }
    }
    if (matchCount) *matchCount = matches;
    return JPDFIUM_OK;
}

int32_t jpdfium_page_flatten(int64_t) { return JPDFIUM_OK; }
int32_t jpdfium_page_to_image(int64_t, int32_t, int32_t) { return JPDFIUM_OK; }

int32_t jpdfium_text_get_char_positions(int64_t page, char** json) {
    g_page_text[page] = STUB_TEXT;
    std::ostringstream os;
    os << '[';
    float x = 10.0f, y = 800.0f;
    bool first = true;
    int idx = 0;
    for (int i = 0; STUB_TEXT[i]; ++i) {
        if (STUB_TEXT[i] == '\n') { y -= 15.0f; x = 10.0f; continue; }
        if (!first) os << ',';
        first = false;
        os << "{\"i\":" << idx++
           << ",\"u\":" << (int)(unsigned char)STUB_TEXT[i]
           << ",\"ox\":" << x << ",\"oy\":" << y
           << ",\"l\":" << x << ",\"r\":" << (x + 7.0f)
           << ",\"b\":" << (y - 12.0f) << ",\"t\":" << y << "}";
        x += 7.0f;
    }
    os << ']';
    *json = strdup(os.str().c_str());
    return JPDFIUM_OK;
}

//  PCRE2 Pattern Engine (std::regex stand-in)

int32_t jpdfium_pcre2_compile(const char* pattern, uint32_t, int64_t* handle) {
    *handle = g_next_pcre++;
    g_pcre[*handle] = {pattern ? pattern : ""};
    return JPDFIUM_OK;
}

int32_t jpdfium_pcre2_match_all(int64_t handle, const char* text, char** json_result) {
    auto it = g_pcre.find(handle);
    if (it == g_pcre.end() || !text || !*text) {
        *json_result = strdup("[]");
        return JPDFIUM_OK;
    }

    try {
        std::regex re(it->second.regex, std::regex_constants::ECMAScript);
        std::string input(text);
        std::ostringstream os;
        os << '[';
        bool first = true;
        for (auto mi = std::sregex_iterator(input.begin(), input.end(), re);
             mi != std::sregex_iterator(); ++mi) {
            if (!first) os << ',';
            first = false;
            os << "{\"start\":" << mi->position()
               << ",\"end\":" << (mi->position() + mi->length())
               << ",\"match\":\"" << json_escape(mi->str()) << "\"}";
        }
        os << ']';
        *json_result = strdup(os.str().c_str());
    } catch (...) {
        *json_result = strdup("[]");
    }
    return JPDFIUM_OK;
}

void jpdfium_pcre2_free(int64_t handle) {
    g_pcre.erase(handle);
}

int32_t jpdfium_luhn_validate(const char* number) {
    if (!number) return 0;
    int digits[32];
    int n = 0;
    for (const char* p = number; *p && n < 32; ++p) {
        if (*p >= '0' && *p <= '9') digits[n++] = *p - '0';
    }
    if (n < 2) return 0;
    int sum = 0;
    for (int i = n - 1, alt = 0; i >= 0; --i, alt ^= 1) {
        int d = digits[i];
        if (alt) { d *= 2; if (d > 9) d -= 9; }
        sum += d;
    }
    return (sum % 10 == 0) ? 1 : 0;
}

//  FlashText Dictionary NER (substring matching)

int32_t jpdfium_flashtext_create(int64_t* handle) {
    *handle = g_next_flash++;
    g_flash[*handle] = {};
    return JPDFIUM_OK;
}

int32_t jpdfium_flashtext_add_keyword(int64_t handle, const char* keyword, const char* label) {
    auto it = g_flash.find(handle);
    if (it != g_flash.end() && keyword && label) {
        it->second.keywords.emplace_back(keyword, label);
    }
    return JPDFIUM_OK;
}

int32_t jpdfium_flashtext_add_keywords_json(int64_t, const char*) {
    return JPDFIUM_OK;
}

int32_t jpdfium_flashtext_find(int64_t handle, const char* text, char** json_result) {
    auto it = g_flash.find(handle);
    if (it == g_flash.end() || !text || !*text) {
        *json_result = strdup("[]");
        return JPDFIUM_OK;
    }
    std::string input(text);
    std::ostringstream os;
    os << '[';
    bool first = true;
    for (const auto& kw_pair : it->second.keywords) {
        size_t pos = 0;
        while ((pos = input.find(kw_pair.first, pos)) != std::string::npos) {
            if (!first) os << ',';
            first = false;
            os << "{\"start\":" << pos
               << ",\"end\":" << (pos + kw_pair.first.size())
               << ",\"keyword\":\"" << json_escape(kw_pair.first)
               << "\",\"label\":\"" << json_escape(kw_pair.second) << "\"}";
            pos += kw_pair.first.size();
        }
    }
    os << ']';
    *json_result = strdup(os.str().c_str());
    return JPDFIUM_OK;
}

void jpdfium_flashtext_free(int64_t handle) {
    g_flash.erase(handle);
}

//  Font Normalization Pipeline stubs

int32_t jpdfium_font_get_data(int64_t, int32_t, uint8_t** data, int64_t* len) {
    *len = 4;
    *data = (uint8_t*)calloc(4, 1);
    return JPDFIUM_OK;
}

int32_t jpdfium_font_classify(const uint8_t*, int64_t, char** json) {
    *json = strdup("{\"type\":\"TrueType\",\"sfnt\":true,\"has_cmap\":true,"
                   "\"num_glyphs\":245,\"units_per_em\":2048,\"has_kerning\":false,"
                   "\"is_subset\":false}");
    return JPDFIUM_OK;
}

int32_t jpdfium_font_fix_tounicode(int64_t, int32_t, int32_t* fonts_fixed) {
    if (fonts_fixed) *fonts_fixed = 0;
    return JPDFIUM_OK;
}

int32_t jpdfium_font_repair_widths(int64_t, int32_t, int32_t* fonts_fixed) {
    if (fonts_fixed) *fonts_fixed = 0;
    return JPDFIUM_OK;
}

int32_t jpdfium_font_normalize_page(int64_t, int32_t, char** json) {
    *json = strdup("{\"fonts_processed\":0,\"tounicode_fixed\":0,"
                   "\"widths_repaired\":0,\"type1_converted\":0,\"resubset\":0}");
    return JPDFIUM_OK;
}

int32_t jpdfium_font_subset(const uint8_t* font_data, int64_t font_len,
                            const uint32_t*, int32_t, int32_t,
                            uint8_t** out_data, int64_t* out_len) {
    *out_len = font_len;
    *out_data = (uint8_t*)malloc(font_len);
    memcpy(*out_data, font_data, font_len);
    return JPDFIUM_OK;
}

//  Glyph-Level Redaction stubs

int32_t jpdfium_redact_glyph_aware(int64_t, const char**, int32_t, uint32_t, float,
                                   uint32_t, int32_t* match_count, char** result_json) {
    if (match_count) *match_count = 0;
    *result_json = strdup("[]");
    return JPDFIUM_OK;
}

//  XMP Metadata Redaction stubs

int32_t jpdfium_xmp_redact_patterns(int64_t, const char**, int32_t,
                                    int32_t* fields_redacted) {
    if (fields_redacted) *fields_redacted = 0;
    return JPDFIUM_OK;
}

int32_t jpdfium_metadata_strip(int64_t, const char**, int32_t) {
    return JPDFIUM_OK;
}

int32_t jpdfium_metadata_strip_all(int64_t) {
    return JPDFIUM_OK;
}

//  ICU4C Text Processing stubs

int32_t jpdfium_icu_normalize_nfc(const char* text, char** result) {
    *result = strdup(text ? text : "");
    return JPDFIUM_OK;
}

int32_t jpdfium_icu_break_sentences(const char* text, char** json_result) {
    if (!text || !*text) {
        *json_result = strdup("[]");
        return JPDFIUM_OK;
    }
    size_t tlen = strlen(text);
    size_t buflen = tlen * 2 + 128;
    char* buf = (char*)malloc(buflen);
    snprintf(buf, buflen, "[{\"start\":0,\"end\":%zu,\"text\":\"%s\"}]", tlen, text);
    *json_result = buf;
    return JPDFIUM_OK;
}

int32_t jpdfium_icu_bidi_reorder(const char* text, char** result) {
    *result = strdup(text ? text : "");
    return JPDFIUM_OK;
}
