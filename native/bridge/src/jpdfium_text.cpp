// jpdfium_text.cpp - Text extraction and search.

#include <fpdf_text.h>
#include <fpdfview.h>

#include <charconv>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <sstream>
#include <string>
#include <system_error>
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
        if (!json) return JPDFIUM_ERR_INVALID;
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
            // Bounded scan of the zeroed buffer instead of a per-char
            // std::string(fontbuf) temporary; output is identical.
            const size_t fnlen = strnlen(fontbuf, sizeof(fontbuf));
            for (size_t k = 0; k < fnlen; ++k) {
                const char c = fontbuf[k];
                if (c == '"' || c == '\\') s += '\\';
                s += c;
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
