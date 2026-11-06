// jpdfium_text.cpp - Text extraction and search.

#include <fpdf_text.h>
#include <fpdfview.h>

#include <charconv>
#include <cmath>
#include <cstddef>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <sstream>
#include <string>
#include <system_error>
#include <unordered_map>
#include <vector>

#include "jpdfium.h"
#include "jpdfium_internal.h"

// UTF-8 -> UTF-16LE for FPDFText_FindStart (PDFium expects UTF-16LE, not wchar_t).
// Truncated sequences yield U+FFFD instead of reading past the terminator: a
// lead byte near the end of the buffer must not consume the NUL and keep
// scanning arbitrary adjacent memory.
static std::vector<uint16_t> utf8_to_utf16le(const char* utf8) {
    std::vector<uint16_t> result;
    const size_t len = std::strlen(utf8);
    const auto* s = reinterpret_cast<const uint8_t*>(utf8);
    size_t i = 0;
    while (i < len) {
        uint32_t cp;
        size_t extra;
        // Smallest value each length may legally encode. Without this an
        // overlong sequence such as C0 80 / E0 80 80 / F0 80 80 80 decodes to
        // U+0000, and the embedded NUL would terminate the search string that
        // FPDFText_FindStart receives.
        uint32_t minCp;
        uint8_t lead = s[i];
        if (lead < 0x80) {
            cp = lead;
            extra = 0;
            minCp = 0;
        } else if ((lead & 0xE0) == 0xC0) {
            cp = lead & 0x1Fu;
            extra = 1;
            minCp = 0x80;
        } else if ((lead & 0xF0) == 0xE0) {
            cp = lead & 0x0Fu;
            extra = 2;
            minCp = 0x800;
        } else if ((lead & 0xF8) == 0xF0) {
            cp = lead & 0x07u;
            extra = 3;
            minCp = 0x10000;
        } else {
            // Continuation byte or invalid lead: resynchronise on U+FFFD.
            ++i;
            result.push_back(0xFFFD);
            continue;
        }
        if (i + extra >= len) {  // truncated sequence at the end of the buffer
            ++i;
            result.push_back(0xFFFD);
            continue;
        }
        bool ok = true;
        for (size_t k = 1; k <= extra; ++k) {
            uint8_t cont = s[i + k];
            if ((cont & 0xC0) != 0x80) {
                ok = false;
                break;
            }
            cp = (cp << 6) | (cont & 0x3Fu);
        }
        if (!ok) {
            ++i;
            result.push_back(0xFFFD);
            continue;
        }
        i += extra + 1;
        if (cp < minCp || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) cp = 0xFFFD;
        if (cp <= 0xFFFF) {
            result.push_back(static_cast<uint16_t>(cp));
        } else {
            cp -= 0x10000;
            result.push_back(static_cast<uint16_t>(0xD800 | (cp >> 10)));
            result.push_back(static_cast<uint16_t>(0xDC00 | (cp & 0x3FF)));
        }
    }
    result.push_back(0);
    return result;
}

// Fast, allocation-free JSON number writers for the text getters below.
// Output contract: byte-identical to the ostringstream default formatting
// they replace (0, -0.0, coords, 1e-5, 1e21, 123456.5, 1/3 all match; the
// hand-rolled decimal writers match `os << int/unsigned` exactly). Non-finite
// values are written as 0 because JSON has no infinities or NaN.
static void append_g(std::string& s, double v) {
    if (!std::isfinite(v)) {
        s += '0';
        return;
    }
    // to_chars is locale-independent; snprintf("%.6g") follows the C locale
    // set by setlocale(), so a comma-decimal host locale would emit "1,5".
    char buf[64];
    const auto res = std::to_chars(buf, buf + sizeof(buf), v, std::chars_format::general, 6);
    if (res.ec == std::errc()) {
        s.append(buf, static_cast<size_t>(res.ptr - buf));
    } else {
        // Unreachable for a bounded double in a 64-byte buffer, but a dropped
        // field would silently corrupt the record's column alignment; degrade
        // to 0 like the non-finite case instead.
        s += '0';
    }
}

static void append_uint(std::string& s, unsigned int v) {
    char buf[16];
    char* p = buf + sizeof(buf);
    do {
        *--p = static_cast<char>('0' + v % 10);
        v /= 10;
    } while (v != 0);
    s.append(p, static_cast<size_t>((buf + sizeof(buf)) - p));
}

static void append_int(std::string& s, int v) {
    if (v < 0) {
        s += '-';
        // Negate in unsigned domain so INT_MIN is exact.
        append_uint(s, static_cast<unsigned int>(-(static_cast<long long>(v))));
    } else {
        append_uint(s, static_cast<unsigned int>(v));
    }
}

int32_t jpdfium_text_get_char_positions(int64_t page, char** json) {
    try {
        PageWrapper* pw = decodePage(page);
        if (!pw || !pw->page) return JPDFIUM_ERR_INVALID;
        if (!json) return JPDFIUM_ERR_INVALID;

        UniqueTextPage tp(FPDFText_LoadPage(pw->page));
        if (!tp) return JPDFIUM_ERR_NATIVE;

        int count = FPDFText_CountChars(tp.get());
        std::string s;
        s.reserve(static_cast<size_t>(count > 0 ? count : 0) * 88 + 2);
        s += '[';
        bool first = true;

        for (int i = 0; i < count; ++i) {
            unsigned int uni = FPDFText_GetUnicode(tp.get(), i);
            if (uni == 0) continue;

            double l, r, b, t;
            if (!FPDFText_GetCharBox(tp.get(), i, &l, &r, &b, &t)) {
                l = r = b = t = 0.0;
            }

            double ox, oy;
            if (!FPDFText_GetCharOrigin(tp.get(), i, &ox, &oy)) {
                ox = l;
                oy = b;
            }

            if (!first) s += ',';
            first = false;
            s += "{\"i\":";
            append_int(s, i);
            s += ",\"u\":";
            append_uint(s, uni);
            s += ",\"ox\":";
            append_g(s, ox);
            s += ",\"oy\":";
            append_g(s, oy);
            s += ",\"l\":";
            append_g(s, l);
            s += ",\"r\":";
            append_g(s, r);
            s += ",\"b\":";
            append_g(s, b);
            s += ",\"t\":";
            append_g(s, t);
            s += '}';
        }
        s += ']';

        char* out = static_cast<char*>(malloc(s.size() + 1));
        if (!out) return JPDFIUM_ERR_NATIVE;
        memcpy(out, s.c_str(), s.size() + 1);
        *json = out;
        return JPDFIUM_OK;

    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

int32_t jpdfium_text_get_chars(int64_t page, char** json) {
    try {
        PageWrapper* pw = decodePage(page);
        // decodePage() rejects a stale page (one whose document was freed and
        // replaced) instead of letting the caller dereference freed PDFium state.
        if (!pw || !pw->page) return JPDFIUM_ERR_INVALID;
        // Validate the out-pointer before doing any work: it is dereferenced on
        // the way out, so a null value would be a crash, not an error return.
        if (!json) return JPDFIUM_ERR_INVALID;

        UniqueTextPage tp(FPDFText_LoadPage(pw->page));
        if (!tp) return JPDFIUM_ERR_NATIVE;

        int count = FPDFText_CountChars(tp.get());
        std::string s;
        s.reserve(static_cast<size_t>(count > 0 ? count : 0) * 112 + 2);
        s += '[';
        bool first = true;

        char fontbuf[256];
        char last_font[256];
        size_t last_font_len = 0;
        std::string escaped_font;
        for (int i = 0; i < count; ++i) {
            unsigned int uni = FPDFText_GetUnicode(tp.get(), i);
            if (uni == 0) continue;

            double l = 0, r = 0, b = 0, t = 0;
            // FPDFText_GetCharBox leaves the outputs untouched when it fails,
            // so report a zero box rather than indeterminate coordinates.
            if (!FPDFText_GetCharBox(tp.get(), i, &l, &r, &b, &t)) {
                l = r = b = t = 0.0;
            }

            unsigned long fnlen =
                FPDFText_GetFontInfo(tp.get(), i, fontbuf, sizeof(fontbuf), nullptr);
            if (fnlen == 0 || fnlen > sizeof(fontbuf)) {
                fontbuf[0] = '\0';
                fnlen = 0;
            } else {
                fontbuf[fnlen - 1] = '\0';
                fnlen--;
            }
            float size = static_cast<float>(FPDFText_GetFontSize(tp.get(), i));

            if (!first) s += ',';
            first = false;
            s += "{\"i\":";
            append_int(s, i);
            s += ",\"u\":";
            append_uint(s, uni);
            s += ",\"x\":";
            append_g(s, l);
            s += ",\"y\":";
            append_g(s, b);
            s += ",\"w\":";
            append_g(s, r - l);
            s += ",\"h\":";
            append_g(s, t - b);
            s += ",\"font\":\"";
            if (fnlen == last_font_len &&
                (fnlen == 0 || std::memcmp(fontbuf, last_font, fnlen) == 0)) {
                s += escaped_font;
            } else {
                escaped_font.clear();
                for (size_t k = 0; k < fnlen; ++k) {
                    const char c = fontbuf[k];
                    switch (c) {
                        case '"':
                            escaped_font += "\\\"";
                            break;
                        case '\\':
                            escaped_font += "\\\\";
                            break;
                        case '\b':
                            escaped_font += "\\b";
                            break;
                        case '\f':
                            escaped_font += "\\f";
                            break;
                        case '\n':
                            escaped_font += "\\n";
                            break;
                        case '\r':
                            escaped_font += "\\r";
                            break;
                        case '\t':
                            escaped_font += "\\t";
                            break;
                        default:
                            if (static_cast<unsigned char>(c) < 0x20) {
                                char ubuf[8];
                                std::snprintf(ubuf, sizeof(ubuf), "\\u%04x",
                                              static_cast<unsigned char>(c));
                                escaped_font += ubuf;
                            } else {
                                escaped_font += c;
                            }
                            break;
                    }
                }
                std::memcpy(last_font, fontbuf, fnlen + 1);
                last_font_len = fnlen;
                s += escaped_font;
            }
            s += "\",\"size\":";
            append_g(s, size);
            s += '}';
        }
        s += ']';

        char* out = static_cast<char*>(malloc(s.size() + 1));
        if (!out) return JPDFIUM_ERR_NATIVE;
        memcpy(out, s.c_str(), s.size() + 1);
        *json = out;
        return JPDFIUM_OK;

    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

namespace {

// One packed character record, laid out exactly as the documented little-endian
// wire format. Keeping it a plain 32-byte value type lets the per-character loop
// append with a single push_back instead of eight byte-wise appends.
struct PackedCharRecord {
    int32_t index;
    int32_t unicode;
    float x;
    float y;
    float w;
    float h;
    float size;
    int32_t fontId;
};

static_assert(sizeof(PackedCharRecord) == 32, "packed char record must stay 32 bytes");
static_assert(offsetof(PackedCharRecord, index) == 0, "wire layout");
static_assert(offsetof(PackedCharRecord, unicode) == 4, "wire layout");
static_assert(offsetof(PackedCharRecord, x) == 8, "wire layout");
static_assert(offsetof(PackedCharRecord, y) == 12, "wire layout");
static_assert(offsetof(PackedCharRecord, w) == 16, "wire layout");
static_assert(offsetof(PackedCharRecord, h) == 20, "wire layout");
static_assert(offsetof(PackedCharRecord, size) == 24, "wire layout");
static_assert(offsetof(PackedCharRecord, fontId) == 28, "wire layout");

}  // namespace

int32_t jpdfium_text_get_chars_packed(int64_t page, uint8_t** data, int64_t* len) {
    try {
        PageWrapper* pw = decodePage(page);
        if (!pw || !pw->page) return JPDFIUM_ERR_INVALID;
        if (!data || !len) return JPDFIUM_ERR_INVALID;

        UniqueTextPage tp(FPDFText_LoadPage(pw->page));
        if (!tp) return JPDFIUM_ERR_NATIVE;

        int count = FPDFText_CountChars(tp.get());
        std::vector<PackedCharRecord> records;
        records.reserve(static_cast<size_t>(count > 0 ? count : 0));
        std::unordered_map<std::string, int32_t> fontIds;
        std::vector<std::string> fonts;

        char fontbuf[256];
        char lastFont[256];
        size_t lastFontLen = 0;
        int32_t lastFontId = -1;

        for (int i = 0; i < count; ++i) {
            unsigned int uni = FPDFText_GetUnicode(tp.get(), i);
            if (uni == 0) continue;

            double l = 0, r = 0, b = 0, t = 0;
            if (!FPDFText_GetCharBox(tp.get(), i, &l, &r, &b, &t)) {
                l = r = b = t = 0.0;
            }

            // FPDFText_GetFontInfo returns the length including the trailing NUL
            // and leaves the buffer untouched when the name does not fit, so a
            // preset first byte is enough: no 256-byte clear per character.
            fontbuf[0] = '\0';
            const unsigned long fontInfoLen =
                FPDFText_GetFontInfo(tp.get(), i, fontbuf, sizeof(fontbuf), nullptr);
            const size_t nameLen =
                (fontInfoLen > 0 && fontInfoLen <= sizeof(fontbuf)) ? fontInfoLen - 1 : 0;

            int32_t fontId;
            if (lastFontId >= 0 && nameLen == lastFontLen &&
                (nameLen == 0 || std::memcmp(fontbuf, lastFont, nameLen) == 0)) {
                // Consecutive glyphs almost always share a font: reuse the id
                // without hashing a std::string or touching the map.
                fontId = lastFontId;
            } else {
                // Same key the old map built from the NUL-terminated buffer, so a
                // font name carrying an embedded NUL still interns identically.
                std::string key(fontbuf);
                const size_t keyLen = key.size();
                auto [it, inserted] =
                    fontIds.try_emplace(std::move(key), static_cast<int32_t>(fonts.size()));
                if (inserted) fonts.push_back(it->first);
                fontId = it->second;
                std::memcpy(lastFont, it->first.data(), keyLen);
                lastFontLen = keyLen;
                lastFontId = fontId;
            }

            records.push_back(PackedCharRecord{
                i, static_cast<int32_t>(uni), static_cast<float>(l), static_cast<float>(b),
                static_cast<float>(r - l), static_cast<float>(t - b),
                static_cast<float>(FPDFText_GetFontSize(tp.get(), i)), fontId});
        }

        size_t bodyBytes = records.size() * sizeof(PackedCharRecord);
        for (const std::string& f : fonts) {
            bodyBytes += 4 + f.size();
        }

        const size_t total = 8 + bodyBytes;
        auto* buf = static_cast<uint8_t*>(malloc(total));
        if (!buf) return JPDFIUM_ERR_NATIVE;

        // Single header + records + font-table copy into the caller-owned buffer;
        // the byte layout matches the appendI32/appendF32 sequence it replaces.
        uint8_t* p = buf;
        const int32_t emitted = static_cast<int32_t>(records.size());
        const int32_t fontCount = static_cast<int32_t>(fonts.size());
        std::memcpy(p, &emitted, 4);
        p += 4;
        std::memcpy(p, &fontCount, 4);
        p += 4;
        if (!records.empty()) {
            std::memcpy(p, records.data(), records.size() * sizeof(PackedCharRecord));
            p += records.size() * sizeof(PackedCharRecord);
        }
        for (const std::string& f : fonts) {
            const int32_t fontLen = static_cast<int32_t>(f.size());
            std::memcpy(p, &fontLen, 4);
            p += 4;
            std::memcpy(p, f.data(), f.size());
            p += f.size();
        }

        *data = buf;
        *len = static_cast<int64_t>(total);
        return JPDFIUM_OK;
    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

int32_t jpdfium_text_find(int64_t page, const char* query, char** json) {
    try {
        PageWrapper* pw = decodePage(page);
        if (!pw || !pw->page) return JPDFIUM_ERR_INVALID;
        // utf8_to_utf16le calls strlen(), so a null query must be rejected here.
        if (!query || !json) return JPDFIUM_ERR_INVALID;

        UniqueTextPage tp(FPDFText_LoadPage(pw->page));
        if (!tp) return JPDFIUM_ERR_NATIVE;

        auto wq = utf8_to_utf16le(query);
        UniqueSch sch(
            FPDFText_FindStart(tp.get(), reinterpret_cast<FPDF_WIDESTRING>(wq.data()), 0, 0));

        std::string s;
        s.reserve(128);
        s += '[';
        bool first = true;
        while (sch && FPDFText_FindNext(sch.get())) {
            int start = FPDFText_GetSchResultIndex(sch.get());
            int cnt = FPDFText_GetSchCount(sch.get());
            if (!first) s += ',';
            first = false;
            s += "{\"start\":";
            append_int(s, start);
            s += ",\"len\":";
            append_int(s, cnt);
            s += '}';
        }
        s += ']';

        // sch and tp release themselves; the search handle must go first, so it
        // is declared after the text page and destroyed before it.

        char* out = static_cast<char*>(malloc(s.size() + 1));
        if (!out) return JPDFIUM_ERR_NATIVE;
        memcpy(out, s.c_str(), s.size() + 1);
        *json = out;
        return JPDFIUM_OK;

    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

void jpdfium_free_string(char* str) noexcept {
    free(str);
}
