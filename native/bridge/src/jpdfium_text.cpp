// jpdfium_text.cpp - Text extraction and search.

#include <fpdf_text.h>
#include <fpdfview.h>

#include <bit>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <sstream>
#include <string>
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

int32_t jpdfium_text_get_char_positions(int64_t page, char** json) {
    try {
        PageWrapper* pw = decodePage(page);
        if (!pw || !pw->page) return JPDFIUM_ERR_INVALID;
        if (!json) return JPDFIUM_ERR_INVALID;

        UniqueTextPage tp(FPDFText_LoadPage(pw->page));
        if (!tp) return JPDFIUM_ERR_NATIVE;

        int count = FPDFText_CountChars(tp.get());
        std::ostringstream os;
        os << '[';
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

            if (!first) os << ',';
            first = false;
            os << "{\"i\":" << i << ",\"u\":" << uni << ",\"ox\":" << ox << ",\"oy\":" << oy
               << ",\"l\":" << l << ",\"r\":" << r << ",\"b\":" << b << ",\"t\":" << t << '}';
        }
        os << ']';

        std::string s = os.str();
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
        if (!json) return JPDFIUM_ERR_INVALID;
        // Validate the out-pointer before doing any work: it is dereferenced on
        // the way out, so a null value would be a crash, not an error return.
        if (!json) return JPDFIUM_ERR_INVALID;

        UniqueTextPage tp(FPDFText_LoadPage(pw->page));
        if (!tp) return JPDFIUM_ERR_NATIVE;

        int count = FPDFText_CountChars(tp.get());
        std::ostringstream os;
        os << '[';
        bool first = true;

        char fontbuf[256];
        for (int i = 0; i < count; ++i) {
            unsigned int uni = FPDFText_GetUnicode(tp.get(), i);
            if (uni == 0) continue;

            double l = 0, r = 0, b = 0, t = 0;
            // FPDFText_GetCharBox leaves the outputs untouched when it fails,
            // so report a zero box rather than indeterminate coordinates.
            if (!FPDFText_GetCharBox(tp.get(), i, &l, &r, &b, &t)) {
                l = r = b = t = 0.0;
            }

            // FPDFText_GetFontInfo returns 0 and leaves the buffer untouched when
            // the name does not fit or the page has no font info; zero it so the
            // JSON never carries uninitialised stack bytes.
            std::memset(fontbuf, 0, sizeof(fontbuf));
            FPDFText_GetFontInfo(tp.get(), i, fontbuf, sizeof(fontbuf), nullptr);
            float size = static_cast<float>(FPDFText_GetFontSize(tp.get(), i));

            if (!first) os << ',';
            first = false;
            os << "{\"i\":" << i << ",\"u\":" << uni << ",\"x\":" << l << ",\"y\":" << b
               << ",\"w\":" << (r - l) << ",\"h\":" << (t - b) << ",\"font\":\"";
            for (char c : std::string(fontbuf)) {
                if (c == '"' || c == '\\') os << '\\';
                os << c;
            }
            os << "\",\"size\":" << size << '}';
        }
        os << ']';

        std::string s = os.str();
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

static_assert(std::endian::native == std::endian::little,
              "jpdfium_text_get_chars_packed writes host byte order and documents little-endian");

constexpr int64_t kPackedRecordBytes = 32;
constexpr int64_t kPackedMaxBytes = std::numeric_limits<int32_t>::max();

void appendI32(std::vector<uint8_t>& out, int32_t v) {
    uint8_t b[4];
    std::memcpy(b, &v, 4);
    out.insert(out.end(), b, b + 4);
}

void appendF32(std::vector<uint8_t>& out, float v) {
    uint8_t b[4];
    std::memcpy(b, &v, 4);
    out.insert(out.end(), b, b + 4);
}

}  // namespace

int32_t jpdfium_text_get_chars_packed(int64_t page, uint8_t** data, int64_t* len) {
    try {
        PageWrapper* pw = decodePage(page);
        if (!pw || !pw->page) return JPDFIUM_ERR_INVALID;
        if (!data || !len) return JPDFIUM_ERR_INVALID;

        UniqueTextPage tp(FPDFText_LoadPage(pw->page));
        if (!tp) return JPDFIUM_ERR_NATIVE;

        int count = FPDFText_CountChars(tp.get());
        if (8 + static_cast<int64_t>(count > 0 ? count : 0) * kPackedRecordBytes >
            kPackedMaxBytes) {
            return JPDFIUM_ERR_TOO_LARGE;
        }
        std::vector<uint8_t> records;
        records.reserve(static_cast<size_t>(count > 0 ? count : 0) * kPackedRecordBytes);
        std::unordered_map<std::string, int32_t> fontIds;
        std::vector<std::string> fonts;
        int32_t emitted = 0;

        char fontbuf[256];
        for (int i = 0; i < count; ++i) {
            unsigned int uni = FPDFText_GetUnicode(tp.get(), i);
            if (uni == 0) continue;

            double l = 0, r = 0, b = 0, t = 0;
            if (!FPDFText_GetCharBox(tp.get(), i, &l, &r, &b, &t)) {
                l = r = b = t = 0.0;
            }
            std::memset(fontbuf, 0, sizeof(fontbuf));
            FPDFText_GetFontInfo(tp.get(), i, fontbuf, sizeof(fontbuf), nullptr);
            fontbuf[sizeof(fontbuf) - 1] = '\0';
            float size = static_cast<float>(FPDFText_GetFontSize(tp.get(), i));

            auto [it, inserted] = fontIds.try_emplace(fontbuf, static_cast<int32_t>(fonts.size()));
            if (inserted) fonts.emplace_back(fontbuf);

            appendI32(records, i);
            appendI32(records, static_cast<int32_t>(uni));
            appendF32(records, static_cast<float>(l));
            appendF32(records, static_cast<float>(b));
            appendF32(records, static_cast<float>(r - l));
            appendF32(records, static_cast<float>(t - b));
            appendF32(records, size);
            appendI32(records, it->second);
            ++emitted;
        }

        int64_t total = 8 + static_cast<int64_t>(records.size());
        for (const std::string& f : fonts) total += 4 + static_cast<int64_t>(f.size());
        if (total > kPackedMaxBytes) return JPDFIUM_ERR_TOO_LARGE;

        std::vector<uint8_t> header;
        appendI32(header, emitted);
        appendI32(header, static_cast<int32_t>(fonts.size()));
        auto* buf = static_cast<uint8_t*>(malloc(static_cast<size_t>(total)));
        if (!buf) return JPDFIUM_ERR_NATIVE;
        uint8_t* cursor = buf;
        std::memcpy(cursor, header.data(), header.size());
        cursor += header.size();
        if (!records.empty()) std::memcpy(cursor, records.data(), records.size());
        cursor += records.size();
        for (const std::string& f : fonts) {
            int32_t length = static_cast<int32_t>(f.size());
            std::memcpy(cursor, &length, 4);
            cursor += 4;
            std::memcpy(cursor, f.data(), f.size());
            cursor += f.size();
        }
        *data = buf;
        *len = total;
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

        std::ostringstream os;
        os << '[';
        bool first = true;
        while (sch && FPDFText_FindNext(sch.get())) {
            int start = FPDFText_GetSchResultIndex(sch.get());
            int cnt = FPDFText_GetSchCount(sch.get());
            if (!first) os << ',';
            first = false;
            os << "{\"start\":" << start << ",\"len\":" << cnt << '}';
        }
        os << ']';

        // sch and tp release themselves; the search handle must go first, so it
        // is declared after the text page and destroyed before it.

        std::string s = os.str();
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
