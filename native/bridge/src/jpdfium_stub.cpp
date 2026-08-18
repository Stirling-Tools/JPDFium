#if defined(_WIN32) && !defined(_CRT_SECURE_NO_WARNINGS)
#define _CRT_SECURE_NO_WARNINGS
#endif

// Stub implementation - returns realistic data for Java-layer testing.
// Compiles without PDFium or any external library:
//   PCRE2     -> std::regex
//   FlashText -> substring matching
//   doc.save  -> byte-for-byte copy of the input
// All buffers handed to the JVM are malloc'd and released via
// jpdfium_free_string / jpdfium_free_buffer (which call free()).

#include "jpdfium.h"

#if !defined(_WIN32)
#include <fcntl.h>
#endif

#include <algorithm>
#include <array>
#include <cctype>
#include <charconv>
#include <cmath>
#include <concepts>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <regex>
#include <string>
#include <string_view>
#include <unordered_map>
#include <utility>
#include <vector>

namespace {

// Stub page text - words and PII so the pipeline has real data to chew on.
constexpr std::string_view STUB_TEXT =
    "Hello World Confidential DRAFT Dummy Redaction\n"
    "Introduction Bold item Gradient Row brown fox\n"
    "Contact: test@example.com Phone: (555) 123-4567\n"
    "SSN: 123-45-6789 987-65-4321 Size 10 Languages Rot Scale 6789\n"
    "Card: 4111-1111-1111-1111 Consider Employ VM\n"
    "John Smith works at Acme Corp custom certificat";

// RAII handles

struct FileCloser {
    void operator()(std::FILE* f) const noexcept {
        if (f) std::fclose(f);
    }
};
using FilePtr = std::unique_ptr<std::FILE, FileCloser>;

static FilePtr safe_fopen_write(const char* path) {
#if defined(_WIN32)
    return FilePtr(std::fopen(path, "wb"));
#else
    int fd = ::open(path, O_WRONLY | O_CREAT | O_TRUNC, 0600);
    if (fd < 0) return FilePtr(nullptr);
    return FilePtr(::fdopen(fd, "wb"));
#endif
}

// FFI-safe allocators
//
// The JVM frees every buffer via jpdfium_free_string / jpdfium_free_buffer
// (free()), so anything returned across the boundary MUST come from malloc.

[[nodiscard]] char* dup_cstring(std::string_view sv) noexcept {
    char* p = static_cast<char*>(std::malloc(sv.size() + 1));
    if (!p) return nullptr;
    std::memcpy(p, sv.data(), sv.size());
    p[sv.size()] = '\0';
    return p;
}

[[nodiscard]] uint8_t* dup_bytes(const void* src, std::size_t len) noexcept {
    auto* p = static_cast<uint8_t*>(std::malloc(len));
    if (!p) return nullptr;
    std::memcpy(p, src, len);
    return p;
}

[[nodiscard]] uint8_t* alloc_zeroed(std::size_t len) noexcept {
    return static_cast<uint8_t*>(std::calloc(len, 1));
}

// JsonBuf - minimal, allocation-cheap JSON writer.
//
// Backed by std::string with std::to_chars for numerics: no iostream, no
// locale, no format-string parsing. Numbers render shortest-round-trip; the
// JVM reads them through Double/Float.parseFloat (which accepts scientific
// notation), so output is safe across libstdc++/libc++/MSVC float-to-chars
// differences.

class JsonBuf {
    std::string s_;

   public:
    explicit JsonBuf(std::size_t reserve = 256) {
        s_.reserve(reserve);
    }

    JsonBuf& operator<<(std::string_view v) {
        s_ += v;
        return *this;
    }
    JsonBuf& operator<<(char c) {
        s_.push_back(c);
        return *this;
    }

    template <std::integral T>
    JsonBuf& operator<<(T v) {
        append(v);
        return *this;
    }
    template <std::floating_point T>
    JsonBuf& operator<<(T v) {
        append(v);
        return *this;
    }

    // Hand the built JSON to the JVM as a malloc'd, NUL-terminated C string.
    [[nodiscard]] char* release() {
        // std::string::data() is NUL-terminated since C++11 - copy once.
        const auto len = s_.size() + 1;
        char* out = static_cast<char*>(std::malloc(len));
        if (out) std::memcpy(out, s_.data(), len);
        return out;
    }

   private:
    template <std::integral T>
    void append(T v) {
        std::array<char, 24> buf{};
        auto [end, ec] = std::to_chars(buf.data(), buf.data() + buf.size(), v);
        if (ec == std::errc{}) s_.append(buf.data(), static_cast<std::size_t>(end - buf.data()));
    }
    template <std::floating_point T>
    void append(T v) {
        std::array<char, 32> buf{};
        auto [end, ec] = std::to_chars(buf.data(), buf.data() + buf.size(), v);
        if (ec == std::errc{}) s_.append(buf.data(), static_cast<std::size_t>(end - buf.data()));
    }
};

// Escape a string for inclusion inside a JSON string literal.
[[nodiscard]] std::string json_escape(std::string_view s) {
    std::string out;
    out.reserve(s.size());
    for (char c : s) {
        if (c == '"' || c == '\\') out.push_back('\\');
        out.push_back(c);
    }
    return out;
}

// Count non-overlapping occurrences of needle in haystack.
int count_occurrences(std::string_view haystack, std::string_view needle, bool case_sensitive) {
    if (needle.empty()) return 0;
    if (case_sensitive) {
        int count = 0;
        for (std::size_t pos = 0; (pos = haystack.find(needle, pos)) != std::string_view::npos;
             pos += needle.size())
            ++count;
        return count;
    }
    auto lower = [](std::string_view s) {
        std::string out(s);
        std::ranges::transform(out, out.begin(),
                               [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
        return out;
    };
    const std::string h = lower(haystack);
    const std::string n = lower(needle);
    int count = 0;
    for (std::size_t pos = 0; (pos = h.find(n, pos)) != std::string::npos; pos += n.size()) ++count;
    return count;
}

// Common "native feature not compiled in" sentinel for byte-out functions.
[[nodiscard]] int32_t fail_native_bytes(uint8_t** out_ptr, int64_t* out_len) noexcept {
    if (out_ptr) *out_ptr = nullptr;
    if (out_len) *out_len = 0;
    return JPDFIUM_ERR_NATIVE;
}

struct StubDoc {
    std::string path;
    std::vector<uint8_t> bytes;
    bool hasMutatedRedaction = false;
    int32_t unappliedRedactMarksCount = 0;
    std::unordered_map<int32_t, int> pagePendingMarks;
    std::string sanitizeReport;

    int32_t unappliedMarks() const {
        return unappliedRedactMarksCount;
    }
};

struct StubPattern {
    std::string regex;
};

struct StubFlashText {
    std::vector<std::pair<std::string, std::string>> keywords;
};

std::unordered_map<int64_t, StubDoc> g_docs;
std::unordered_map<int64_t, std::string> g_page_text;
std::unordered_map<int64_t, int> g_page_annots;   // page -> pending REDACT count
std::unordered_map<int64_t, int64_t> g_page_doc;  // page -> owning doc handle
std::unordered_map<int64_t, int32_t> g_page_idx;  // page -> page index in doc
std::unordered_map<int64_t, StubPattern> g_pcre;
std::unordered_map<int64_t, StubFlashText> g_flash;

int64_t g_next_doc = 12345;
int64_t g_next_page = 99000;
int64_t g_next_pcre = 77001;
int64_t g_next_flash = 88001;

}  // namespace

// Core Document Functions

int32_t jpdfium_init() {
    return JPDFIUM_OK;
}
void jpdfium_destroy() {}

int32_t jpdfium_doc_create(int64_t* handle) {
    if (!handle) return JPDFIUM_ERR_INVALID;
    *handle = g_next_doc++;
    StubDoc doc;
    g_docs[*handle] = std::move(doc);
    return JPDFIUM_OK;
}

int32_t jpdfium_doc_open(const char* path, int64_t* handle) {
    *handle = g_next_doc++;
    StubDoc doc;
    doc.path = path ? path : "";
    g_docs[*handle] = std::move(doc);
    return JPDFIUM_OK;
}

int32_t jpdfium_doc_open_bytes(const uint8_t* data, int64_t len, int64_t* handle) {
    if (!data || !handle || len <= 0) return JPDFIUM_ERR_INVALID;
    *handle = g_next_doc++;
    StubDoc doc;
    doc.bytes.assign(data, data + len);
    g_docs[*handle] = std::move(doc);
    return JPDFIUM_OK;
}

int32_t jpdfium_doc_open_protected(const char* path, const char*, int64_t* handle) {
    *handle = g_next_doc++;
    StubDoc doc;
    doc.path = path ? path : "";
    g_docs[*handle] = std::move(doc);
    return JPDFIUM_OK;
}

int32_t jpdfium_doc_page_count(int64_t, int32_t* count) {
    *count = 3;
    return JPDFIUM_OK;
}

int32_t jpdfium_doc_save(int64_t handle, const char* output_path) {
    auto it = g_docs.find(handle);
    if (it == g_docs.end()) return JPDFIUM_OK;
    const auto& doc = it->second;
    if (doc.unappliedMarks() > 0) return JPDFIUM_ERR_UNCOMMITTED_MARKS;

    if (!doc.path.empty()) {
        FilePtr in(std::fopen(doc.path.c_str(), "rb"));
        FilePtr out = safe_fopen_write(output_path);
        if (in && out) {
            std::array<char, 8192> buf{};
            while (true) {
                const std::size_t n = std::fread(buf.data(), 1, buf.size(), in.get());
                if (n == 0) break;
                std::fwrite(buf.data(), 1, n, out.get());
            }
        }
    } else if (!doc.bytes.empty()) {
        if (FilePtr out = safe_fopen_write(output_path); out)
            std::fwrite(doc.bytes.data(), 1, doc.bytes.size(), out.get());
    }
    return JPDFIUM_OK;
}

int32_t jpdfium_doc_save_bytes(int64_t handle, uint8_t** data, int64_t* len) {
    constexpr std::string_view stub = "%PDF-1.4 stub";
    auto return_stub = [&]() -> int32_t {
        *len = static_cast<int64_t>(stub.size());
        *data = dup_bytes(stub.data(), stub.size());
        return JPDFIUM_OK;
    };

    auto it = g_docs.find(handle);
    if (it == g_docs.end()) return return_stub();
    const auto& doc = it->second;
    if (doc.unappliedMarks() > 0) return JPDFIUM_ERR_UNCOMMITTED_MARKS;

    if (!doc.bytes.empty()) {
        *len = static_cast<int64_t>(doc.bytes.size());
        *data = dup_bytes(doc.bytes.data(), doc.bytes.size());
        return *data ? JPDFIUM_OK : JPDFIUM_ERR_NATIVE;
    }
    if (!doc.path.empty()) {
        if (FilePtr in(std::fopen(doc.path.c_str(), "rb")); in) {
            std::fseek(in.get(), 0, SEEK_END);
            const long sz = std::ftell(in.get());
            std::fseek(in.get(), 0, SEEK_SET);
            if (sz >= 0) {
                auto* p = static_cast<uint8_t*>(std::malloc(static_cast<std::size_t>(sz)));
                if (p) {
                    const std::size_t n = std::fread(p, 1, static_cast<std::size_t>(sz), in.get());
                    *data = p;
                    *len = static_cast<int64_t>(n);
                    return JPDFIUM_OK;
                }
            }
        }
    }
    return return_stub();
}

void jpdfium_doc_close(int64_t handle) {
    g_docs.erase(handle);
}

// Page Functions

int32_t jpdfium_page_open(int64_t doc, int32_t idx, int64_t* handle) {
    *handle = g_next_page++;
    g_page_doc[*handle] = doc;  // remember owning doc for page_doc_raw_handle
    g_page_idx[*handle] = idx;
    auto dit = g_docs.find(doc);
    if (dit != g_docs.end()) {
        auto pit = dit->second.pagePendingMarks.find(idx);
        if (pit != dit->second.pagePendingMarks.end()) {
            g_page_annots[*handle] = pit->second;
        }
    }
    return JPDFIUM_OK;
}

int32_t jpdfium_page_width(int64_t, float* w) {
    *w = 595.0f;
    return JPDFIUM_OK;
}
int32_t jpdfium_page_height(int64_t, float* h) {
    *h = 842.0f;
    return JPDFIUM_OK;
}

void jpdfium_page_close(int64_t handle) {
    if (auto dit = g_page_doc.find(handle); dit != g_page_doc.end()) {
        if (auto pit = g_page_idx.find(handle); pit != g_page_idx.end()) {
            if (auto ait = g_page_annots.find(handle); ait != g_page_annots.end()) {
                g_docs[dit->second].pagePendingMarks[pit->second] = ait->second;
            }
        }
    }
    g_page_text.erase(handle);
    g_page_annots.erase(handle);
    g_page_doc.erase(handle);
    g_page_idx.erase(handle);
}

int32_t jpdfium_render_page(int64_t, int32_t, uint8_t** rgba, int32_t* w, int32_t* h) {
    // Small fake frame: the stub only needs to exercise the bridge contract
    // (out-params, buffer ownership, free path). Keeping it small keeps the
    // JMH microbenchmarks focused on the Java/FFM call-path cost instead of
    // a large memset + byte[] copy of data that never represents real
    // rendering work.
    constexpr int dim = 16;
    *w = dim;
    *h = dim;
    *rgba = alloc_zeroed(static_cast<std::size_t>(dim) * dim * 4);
    return JPDFIUM_OK;
}

void jpdfium_free_buffer(uint8_t* buf) {
    std::free(buf);
}

// Text Extraction

// The fake text JSON depends only on STUB_TEXT, never on the page handle, so
// build it once and hand out copies. Rebuilding ~40 KB of JSON per call made
// the extractTextJson JMH benchmark measure repeated fake serialisation work
// instead of the Java call path, and added allocator-sensitive runner noise.
const std::string& stub_text_json() {
    static const std::string json = [] {
        JsonBuf j(STUB_TEXT.size() * 4);
        j << '[';
        float x = 10.0f, y = 800.0f;
        bool first = true;
        int idx = 0;
        for (unsigned char c : STUB_TEXT) {
            if (c == '\n') {
                y -= 15.0f;
                x = 10.0f;
                continue;
            }
            if (!first) j << ',';
            first = false;
            j << "{\"i\":" << idx++ << ",\"u\":" << static_cast<int>(c) << ",\"x\":" << x
              << ",\"y\":" << y << ",\"w\":7.0,\"h\":12.0,\"font\":\"Helvetica\",\"size\":12.0}";
            x += 7.0f;
        }
        j << ']';
        char* cstr = j.release();
        std::string s(cstr);
        std::free(cstr);
        return s;
    }();
    return json;
}

int32_t jpdfium_text_get_chars(int64_t page, char** json) {
    g_page_text[page] = STUB_TEXT;
    const std::string& s = stub_text_json();
    char* out = static_cast<char*>(std::malloc(s.size() + 1));
    if (!out) return JPDFIUM_ERR_NATIVE;
    std::memcpy(out, s.c_str(), s.size() + 1);
    *json = out;
    return JPDFIUM_OK;
}

int32_t jpdfium_text_find(int64_t, const char*, char** json) {
    *json = dup_cstring("[]");
    return JPDFIUM_OK;
}

void jpdfium_free_string(char* s) {
    std::free(s);
}

// Redaction

// Stub must reject the same degenerate geometry the real bridge rejects so
// native_smoke can validate the contract against either build variant.
namespace {
bool stub_rect_invalid(float x, float y, float w, float h) {
    return !std::isfinite(x) || !std::isfinite(y) || !std::isfinite(w) || !std::isfinite(h) ||
           w <= 0.0f || h <= 0.0f;
}
}  // namespace

int32_t jpdfium_redact_region(int64_t page, float x, float y, float w, float h, uint32_t,
                              int32_t remove_content) noexcept {
    if (remove_content == 0 || stub_rect_invalid(x, y, w, h))
        return JPDFIUM_ERR_INVALID;  // visual-only cover is banned
    if (auto dit = g_page_doc.find(page); dit != g_page_doc.end()) {
        g_docs[dit->second].hasMutatedRedaction = true;
    }
    return JPDFIUM_OK;
}
int32_t jpdfium_crop_remove_content(int64_t, float x, float y, float w, float h) noexcept {
    return stub_rect_invalid(x, y, w, h) ? JPDFIUM_ERR_INVALID : JPDFIUM_OK;
}
int32_t jpdfium_redact_pattern(int64_t page, const char*, uint32_t,
                               int32_t remove_content) noexcept {
    if (remove_content == 0) return JPDFIUM_ERR_INVALID;  // visual-only cover is banned
    if (auto dit = g_page_doc.find(page); dit != g_page_doc.end()) {
        g_docs[dit->second].hasMutatedRedaction = true;
    }
    return JPDFIUM_OK;
}
int32_t jpdfium_redact_words(int64_t page, const char**, int32_t, uint32_t, float, int32_t, int32_t,
                             int32_t remove_content) noexcept {
    if (remove_content == 0) return JPDFIUM_ERR_INVALID;  // visual-only cover is banned
    if (auto dit = g_page_doc.find(page); dit != g_page_doc.end()) {
        g_docs[dit->second].hasMutatedRedaction = true;
    }
    return JPDFIUM_OK;
}

int32_t jpdfium_redact_words_ex(int64_t page, const char** words, int32_t word_count, uint32_t,
                                float, int32_t, int32_t, int32_t remove_content,
                                int32_t case_sensitive, int32_t* match_count) noexcept {
    if (remove_content == 0) return JPDFIUM_ERR_INVALID;  // visual-only cover is banned
    if (auto dit = g_page_doc.find(page); dit != g_page_doc.end()) {
        g_docs[dit->second].hasMutatedRedaction = true;
    }
    int matches = 0;
    std::string_view text = STUB_TEXT;
    if (auto it = g_page_text.find(page); it != g_page_text.end()) text = it->second;
    if (words) {
        for (int i = 0; i < word_count; ++i)
            if (words[i]) matches += count_occurrences(text, words[i], case_sensitive != 0);
    }
    if (match_count) *match_count = matches;
    return JPDFIUM_OK;
}

int32_t jpdfium_page_flatten(int64_t) noexcept {
    return JPDFIUM_OK;
}
int32_t jpdfium_page_to_image(int64_t, int32_t, int32_t) {
    return JPDFIUM_OK;
}

int32_t jpdfium_text_get_char_positions(int64_t page, char** json) {
    g_page_text[page] = STUB_TEXT;
    JsonBuf j(STUB_TEXT.size() * 4);
    j << '[';
    float x = 10.0f, y = 800.0f;
    bool first = true;
    int idx = 0;
    for (unsigned char c : STUB_TEXT) {
        if (c == '\n') {
            y -= 15.0f;
            x = 10.0f;
            continue;
        }
        if (!first) j << ',';
        first = false;
        j << "{\"i\":" << idx++ << ",\"u\":" << static_cast<int>(c) << ",\"ox\":" << x
          << ",\"oy\":" << y << ",\"l\":" << x << ",\"r\":" << (x + 7.0f)
          << ",\"b\":" << (y - 12.0f) << ",\"t\":" << y << "}";
        x += 7.0f;
    }
    j << ']';
    *json = j.release();
    return JPDFIUM_OK;
}

// PCRE2 Pattern Engine (std::regex stand-in)

int32_t jpdfium_pcre2_compile(const char* pattern, uint32_t, int64_t* handle) {
    *handle = g_next_pcre++;
    g_pcre[*handle] = {pattern ? pattern : ""};
    return JPDFIUM_OK;
}

int32_t jpdfium_pcre2_match_all(int64_t handle, const char* text, char** json_result) {
    auto it = g_pcre.find(handle);
    if (it == g_pcre.end() || !text || !*text) {
        *json_result = dup_cstring("[]");
        return JPDFIUM_OK;
    }
    try {
        std::regex re(it->second.regex, std::regex_constants::ECMAScript);
        std::string input(text);
        JsonBuf j(input.size() * 2);
        j << '[';
        bool first = true;
        for (auto mi = std::sregex_iterator(input.begin(), input.end(), re);
             mi != std::sregex_iterator(); ++mi) {
            if (!first) j << ',';
            first = false;
            j << "{\"start\":" << mi->position() << ",\"end\":" << (mi->position() + mi->length())
              << ",\"match\":\"" << json_escape(mi->str()) << "\"}";
        }
        j << ']';
        *json_result = j.release();
    } catch (...) {
        *json_result = dup_cstring("[]");
    }
    return JPDFIUM_OK;
}

void jpdfium_pcre2_free(int64_t handle) {
    g_pcre.erase(handle);
}

// Luhn

int32_t jpdfium_luhn_validate(const char* number) {
    if (!number) return 0;
    std::string_view sv(number);
    // Count digits first so we can compute the doubling parity from the right.
    int n = 0;
    for (char c : sv)
        if (c >= '0' && c <= '9') ++n;
    if (n < 2) return 0;

    int sum = 0, seen = 0;
    for (char c : sv) {
        if (c < '0' || c > '9') continue;
        int d = c - '0';
        // Double every second digit counting from the rightmost (check) digit.
        if (((n - 1 - seen) & 1) != 0) {
            d *= 2;
            if (d > 9) d -= 9;
        }
        sum += d;
        ++seen;
    }
    return (sum % 10 == 0) ? 1 : 0;
}

// FlashText Dictionary NER

int32_t jpdfium_flashtext_create(int64_t* handle) {
    *handle = g_next_flash++;
    g_flash[*handle] = {};
    return JPDFIUM_OK;
}

int32_t jpdfium_flashtext_add_keyword(int64_t handle, const char* keyword, const char* label) {
    if (auto it = g_flash.find(handle); it != g_flash.end() && keyword && label)
        it->second.keywords.emplace_back(keyword, label);
    return JPDFIUM_OK;
}

int32_t jpdfium_flashtext_add_keywords_json(int64_t, const char*) {
    return JPDFIUM_OK;
}

int32_t jpdfium_flashtext_find(int64_t handle, const char* text, char** json_result) {
    if (!json_result) return JPDFIUM_ERR_INVALID;
    auto it = g_flash.find(handle);
    if (it == g_flash.end() || !text || !*text) {
        *json_result = dup_cstring("[]");
        return JPDFIUM_OK;
    }
    std::string_view input(text);
    JsonBuf j(input.size() * 2);
    j << '[';
    bool first = true;
    for (const auto& [kw, label] : it->second.keywords) {
        for (std::size_t pos = 0; (pos = input.find(kw, pos)) != std::string_view::npos;
             pos += kw.size()) {
            if (!first) j << ',';
            first = false;
            j << "{\"start\":" << static_cast<int64_t>(pos)
              << ",\"end\":" << (static_cast<int64_t>(pos) + static_cast<int64_t>(kw.size()))
              << ",\"keyword\":\"" << json_escape(kw) << "\",\"label\":\"" << json_escape(label)
              << "\"}";
        }
    }
    j << ']';
    *json_result = j.release();
    return JPDFIUM_OK;
}

void jpdfium_flashtext_free(int64_t handle) {
    g_flash.erase(handle);
}

// Font Normalization Pipeline stubs

int32_t jpdfium_font_get_data(int64_t, int32_t, uint8_t** data, int64_t* len) {
    if (!data || !len) return JPDFIUM_ERR_INVALID;
    *len = 4;
    *data = alloc_zeroed(4);
    return JPDFIUM_OK;
}

int32_t jpdfium_font_classify(const uint8_t*, int64_t, char** json) {
    if (!json) return JPDFIUM_ERR_INVALID;
    *json = dup_cstring(
        "{\"type\":\"TrueType\",\"sfnt\":true,\"has_cmap\":true,"
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
    if (!json) return JPDFIUM_ERR_INVALID;
    *json = dup_cstring(
        "{\"fonts_processed\":0,\"tounicode_fixed\":0,"
        "\"widths_repaired\":0,\"type1_converted\":0,\"resubset\":0}");
    return JPDFIUM_OK;
}

int32_t jpdfium_font_subset(const uint8_t* font_data, int64_t font_len, const uint32_t*, int32_t,
                            int32_t, uint8_t** out_data, int64_t* out_len) {
    if (!font_data || font_len <= 0) {
        if (out_data) *out_data = nullptr;
        if (out_len) *out_len = 0;
        return JPDFIUM_OK;
    }
    *out_len = font_len;
    *out_data = dup_bytes(font_data, static_cast<std::size_t>(font_len));
    return *out_data ? JPDFIUM_OK : JPDFIUM_ERR_NATIVE;
}

// Glyph-Level Redaction stub

int32_t jpdfium_redact_glyph_aware(int64_t, const char**, int32_t, uint32_t, float, uint32_t,
                                   int32_t* match_count, char** result_json) {
    if (match_count) *match_count = 0;
    *result_json = dup_cstring("[]");
    return JPDFIUM_OK;
}

// XMP Metadata Redaction stubs

int32_t jpdfium_xmp_redact_patterns(int64_t, const char**, int32_t, int32_t* fields_redacted) {
    if (fields_redacted) *fields_redacted = 0;
    return JPDFIUM_OK;
}

int32_t jpdfium_metadata_strip(int64_t, const char**, int32_t) {
    return JPDFIUM_OK;
}

int32_t jpdfium_metadata_strip_all(int64_t) {
    return JPDFIUM_OK;
}

int32_t jpdfium_strip_fonts(int64_t, int32_t* fonts_removed) {
    if (fonts_removed) *fonts_removed = 0;
    return JPDFIUM_OK;
}

// ICU4C stubs

int32_t jpdfium_icu_normalize_nfc(const char* text, char** result) {
    *result = dup_cstring(text ? text : "");
    return JPDFIUM_OK;
}

int32_t jpdfium_icu_break_sentences(const char* text, char** json_result) {
    if (!text || !*text) {
        *json_result = dup_cstring("[]");
        return JPDFIUM_OK;
    }
    std::string_view sv(text);
    JsonBuf j(sv.size() * 2 + 64);
    j << "[{\"start\":0,\"end\":" << static_cast<int64_t>(sv.size()) << ",\"text\":\""
      << json_escape(sv) << "\"}]";
    *json_result = j.release();
    return JPDFIUM_OK;
}

int32_t jpdfium_icu_bidi_reorder(const char* text, char** result) {
    *result = dup_cstring(text ? text : "");
    return JPDFIUM_OK;
}

// Annotation-Based Redaction (Mark -> Commit)

int32_t jpdfium_annot_create_redact(int64_t page, float, float, float, float, uint32_t,
                                    int32_t* annot_index) noexcept {
    int idx = g_page_annots[page]++;
    if (annot_index) *annot_index = idx;
    if (auto it = g_page_doc.find(page); it != g_page_doc.end()) {
        g_docs[it->second].unappliedRedactMarksCount++;
        if (auto pit = g_page_idx.find(page); pit != g_page_idx.end()) {
            g_docs[it->second].pagePendingMarks[pit->second] = g_page_annots[page];
        }
    }
    return JPDFIUM_OK;
}

int32_t jpdfium_redact_mark_words(int64_t page, const char** words, int32_t word_count, float,
                                  int32_t, int32_t, int32_t case_sensitive, uint32_t,
                                  int32_t* match_count) noexcept {
    int matches = 0;
    std::string_view text = STUB_TEXT;
    if (auto it = g_page_text.find(page); it != g_page_text.end()) text = it->second;
    if (words) {
        for (int i = 0; i < word_count; ++i)
            if (words[i]) matches += count_occurrences(text, words[i], case_sensitive != 0);
    }
    g_page_annots[page] += matches;
    if (match_count) *match_count = matches;
    if (auto it = g_page_doc.find(page); it != g_page_doc.end()) {
        g_docs[it->second].unappliedRedactMarksCount += matches;
        if (auto pit = g_page_idx.find(page); pit != g_page_idx.end()) {
            g_docs[it->second].pagePendingMarks[pit->second] = g_page_annots[page];
        }
    }
    return JPDFIUM_OK;
}

int32_t jpdfium_annot_count_redacts(int64_t page, int32_t* count) noexcept {
    if (count) {
        if (auto it = g_page_annots.find(page); it != g_page_annots.end())
            *count = it->second;
        else
            *count = 0;
    }
    return JPDFIUM_OK;
}

int32_t jpdfium_annot_get_redacts_json(int64_t, char** json) noexcept {
    *json = dup_cstring("[]");
    return JPDFIUM_OK;
}

int32_t jpdfium_annot_remove_redact(int64_t page, int32_t) noexcept {
    if (auto it = g_page_annots.find(page); it != g_page_annots.end() && it->second > 0) {
        --it->second;
        if (auto dit = g_page_doc.find(page); dit != g_page_doc.end()) {
            g_docs[dit->second].unappliedRedactMarksCount =
                std::max(0, g_docs[dit->second].unappliedRedactMarksCount - 1);
            if (auto pit = g_page_idx.find(page); pit != g_page_idx.end()) {
                g_docs[dit->second].pagePendingMarks[pit->second] = it->second;
            }
        }
    }
    return JPDFIUM_OK;
}

int32_t jpdfium_annot_clear_redacts(int64_t page) noexcept {
    int count = 0;
    if (auto it = g_page_annots.find(page); it != g_page_annots.end()) {
        count = it->second;
        g_page_annots.erase(it);
    }
    if (auto dit = g_page_doc.find(page); dit != g_page_doc.end()) {
        g_docs[dit->second].unappliedRedactMarksCount =
            std::max(0, g_docs[dit->second].unappliedRedactMarksCount - count);
        if (auto pit = g_page_idx.find(page); pit != g_page_idx.end()) {
            g_docs[dit->second].pagePendingMarks.erase(pit->second);
        }
        if (count > 0) {
            g_docs[dit->second].hasMutatedRedaction = true;
        }
    }
    return JPDFIUM_OK;
}

int32_t jpdfium_redact_commit(int64_t page, uint32_t, int32_t remove_content,
                              int32_t* commit_count) noexcept {
    if (remove_content == 0) return JPDFIUM_ERR_INVALID;  // visual-only cover is banned
    int pending = 0;
    if (auto it = g_page_annots.find(page); it != g_page_annots.end()) {
        pending = it->second;
        g_page_annots.erase(it);
    }
    if (auto dit = g_page_doc.find(page); dit != g_page_doc.end()) {
        g_docs[dit->second].unappliedRedactMarksCount =
            std::max(0, g_docs[dit->second].unappliedRedactMarksCount - pending);
        if (auto pit = g_page_idx.find(page); pit != g_page_idx.end()) {
            g_docs[dit->second].pagePendingMarks.erase(pit->second);
        }
        g_docs[dit->second].hasMutatedRedaction = true;
    }
    if (commit_count) *commit_count = pending;
    return JPDFIUM_OK;
}

int32_t jpdfium_doc_sanitize_report(int64_t doc, char** json) noexcept {
    auto it = g_docs.find(doc);
    if (it == g_docs.end() || !json) return JPDFIUM_ERR_INVALID;
    const std::string& rep = it->second.sanitizeReport;
    char* out = static_cast<char*>(std::malloc(rep.size() + 1));
    if (!out) return JPDFIUM_ERR_NATIVE;
    std::memcpy(out, rep.data(), rep.size());
    out[rep.size()] = 0;
    *json = out;
    return JPDFIUM_OK;
}

int32_t jpdfium_doc_save_incremental(int64_t handle, uint8_t** data, int64_t* len) noexcept {
    auto it = g_docs.find(handle);
    if (it != g_docs.end()) {
        if (it->second.unappliedMarks() > 0) return JPDFIUM_ERR_UNCOMMITTED_MARKS;
        if (it->second.hasMutatedRedaction) return JPDFIUM_ERR_REDACTED_SAVE;
    }
    return jpdfium_doc_save_bytes(handle, data, len);
}

int64_t jpdfium_doc_raw_handle(int64_t doc) {
    return doc;
}

int64_t jpdfium_page_raw_handle(int64_t page) {
    return page;
}

int64_t jpdfium_page_doc_raw_handle(int64_t page) {
    if (auto it = g_page_doc.find(page); it != g_page_doc.end()) return it->second;
    return page;
}

int32_t jpdfium_rust_compress_pdf(const uint8_t*, int64_t, uint8_t** out_ptr, int64_t* out_len,
                                  int32_t) {
    return fail_native_bytes(out_ptr, out_len);
}

int32_t jpdfium_rust_repair_lopdf(const uint8_t*, int64_t, uint8_t** out_ptr, int64_t* out_len) {
    return fail_native_bytes(out_ptr, out_len);
}

int32_t jpdfium_rust_resize_pixels(const uint8_t*, int64_t, int32_t, int32_t, int32_t, int32_t,
                                   int32_t, uint8_t** out_ptr, int64_t* out_len) {
    return fail_native_bytes(out_ptr, out_len);
}

int32_t jpdfium_rust_compress_png(const uint8_t*, int64_t, uint8_t** out_ptr, int64_t* out_len,
                                  int32_t) {
    return fail_native_bytes(out_ptr, out_len);
}

void jpdfium_rust_free(uint8_t*) {
    // No-op: the stub never allocates a Rust-owned buffer.
}

int32_t jpdfium_brotli_to_flate(const uint8_t*, int64_t, uint8_t** out_ptr, int64_t* out_len) {
    return fail_native_bytes(out_ptr, out_len);
}

int32_t jpdfium_pdfio_repair(const uint8_t*, int64_t, uint8_t** out_ptr, int64_t* out_len) {
    return fail_native_bytes(out_ptr, out_len);
}

int32_t jpdfium_pdfio_try_repair(const uint8_t*, int64_t, uint8_t** out_ptr, int64_t* out_len,
                                 int32_t* page_count) {
    if (out_ptr) *out_ptr = nullptr;
    if (out_len) *out_len = 0;
    if (page_count) *page_count = 0;
    return JPDFIUM_ERR_NATIVE;
}

int32_t jpdfium_repair_pdf(const uint8_t* in, int64_t in_len, uint8_t** out_ptr, int64_t* out_len,
                           int32_t) {
    if (out_ptr && out_len && in && in_len >= 4 && std::memcmp(in, "%PDF", 4) == 0) {
        if (auto* p = dup_bytes(in, static_cast<std::size_t>(in_len))) {
            *out_ptr = p;
            *out_len = in_len;
            return 0;
        }
    }
    if (out_ptr) *out_ptr = nullptr;
    if (out_len) *out_len = 0;
    return -1;
}

int32_t jpdfium_repair_inspect(const uint8_t*, int64_t, char** json_out) {
    if (json_out) *json_out = dup_cstring("{\"status\":\"clean\",\"issues\":[]}");
    return 0;
}
