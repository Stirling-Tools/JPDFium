// jpdfium_text.cpp - Text extraction and search.

#include "jpdfium.h"
#include "jpdfium_internal.h"

#include <fpdfview.h>
#include <fpdf_text.h>

#include <cstdlib>
#include <cstring>
#include <string>
#include <sstream>
#include <vector>

// UTF-8 -> UTF-16LE for FPDFText_FindStart (PDFium expects UTF-16LE, not wchar_t)
static std::vector<uint16_t> utf8_to_utf16le(const char* utf8) {
    std::vector<uint16_t> result;
    const auto* s = reinterpret_cast<const uint8_t*>(utf8);
    while (*s) {
        uint32_t cp;
        if      (*s < 0x80) { cp = *s++; }
        else if (*s < 0xE0) { cp  = (*s++ & 0x1F) << 6;  cp |= (*s++ & 0x3F); }
        else if (*s < 0xF0) { cp  = (*s++ & 0x0F) << 12; cp |= (*s++ & 0x3F) << 6;
                               cp |= (*s++ & 0x3F); }
        else                { cp  = (*s++ & 0x07) << 18; cp |= (*s++ & 0x3F) << 12;
                               cp |= (*s++ & 0x3F) << 6; cp |= (*s++ & 0x3F); }
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
    PageWrapper* pw = decodePage(page);
    if (!pw || !pw->page) return JPDFIUM_ERR_INVALID;

    FPDF_TEXTPAGE tp = FPDFText_LoadPage(pw->page);
    if (!tp) return JPDFIUM_ERR_NATIVE;

    int count = FPDFText_CountChars(tp);
    std::ostringstream os;
    os << '[';
    bool first = true;

    for (int i = 0; i < count; ++i) {
        unsigned int uni = FPDFText_GetUnicode(tp, i);
        if (uni == 0) continue;

        double l, r, b, t;
        if (!FPDFText_GetCharBox(tp, i, &l, &r, &b, &t)) {
            l = r = b = t = 0.0;
        }

        double ox, oy;
        if (!FPDFText_GetCharOrigin(tp, i, &ox, &oy)) {
            ox = l;
            oy = b;
        }

        if (!first) os << ',';
        first = false;
        os << "{\"i\":" << i
           << ",\"u\":" << uni
           << ",\"ox\":" << ox
           << ",\"oy\":" << oy
           << ",\"l\":" << l
           << ",\"r\":" << r
           << ",\"b\":" << b
           << ",\"t\":" << t << '}';
    }
    os << ']';

    FPDFText_ClosePage(tp);

    std::string s = os.str();
    char* out = static_cast<char*>(malloc(s.size() + 1));
    if (!out) return JPDFIUM_ERR_NATIVE;
    memcpy(out, s.c_str(), s.size() + 1);
    *json = out;
    return JPDFIUM_OK;
}

int32_t jpdfium_text_get_chars(int64_t page, char** json) {
    PageWrapper* pw = decodePage(page);
    if (!pw || !pw->page) return JPDFIUM_ERR_INVALID;

    FPDF_TEXTPAGE tp = FPDFText_LoadPage(pw->page);
    if (!tp) return JPDFIUM_ERR_NATIVE;

    int count = FPDFText_CountChars(tp);
    std::ostringstream os;
    os << '[';
    bool first = true;

    char fontbuf[256];
    for (int i = 0; i < count; ++i) {
        unsigned int uni = FPDFText_GetUnicode(tp, i);
        if (uni == 0) continue;

        double l, r, b, t;
        FPDFText_GetCharBox(tp, i, &l, &r, &b, &t);

        FPDFText_GetFontInfo(tp, i, fontbuf, sizeof(fontbuf), nullptr);
        float size = FPDFText_GetFontSize(tp, i);

        if (!first) os << ',';
        first = false;
        os << "{\"i\":" << i
           << ",\"u\":" << uni
           << ",\"x\":" << l
           << ",\"y\":" << b
           << ",\"w\":" << (r - l)
           << ",\"h\":" << (t - b)
           << ",\"font\":\"";
        for (char c : std::string(fontbuf)) {
            if (c == '"' || c == '\\') os << '\\';
            os << c;
        }
        os << "\",\"size\":" << size << '}';
    }
    os << ']';

    FPDFText_ClosePage(tp);

    std::string s = os.str();
    char* out = static_cast<char*>(malloc(s.size() + 1));
    if (!out) return JPDFIUM_ERR_NATIVE;
    memcpy(out, s.c_str(), s.size() + 1);
    *json = out;
    return JPDFIUM_OK;
}

int32_t jpdfium_text_find(int64_t page, const char* query, char** json) {
    PageWrapper* pw = decodePage(page);
    if (!pw || !pw->page) return JPDFIUM_ERR_INVALID;

    FPDF_TEXTPAGE tp = FPDFText_LoadPage(pw->page);
    if (!tp) return JPDFIUM_ERR_NATIVE;

    auto wq = utf8_to_utf16le(query);
    FPDF_SCHHANDLE sch = FPDFText_FindStart(
        tp, reinterpret_cast<FPDF_WIDESTRING>(wq.data()), 0, 0);

    std::ostringstream os;
    os << '[';
    bool first = true;
    while (sch && FPDFText_FindNext(sch)) {
        int start = FPDFText_GetSchResultIndex(sch);
        int cnt   = FPDFText_GetSchCount(sch);
        if (!first) os << ',';
        first = false;
        os << "{\"start\":" << start << ",\"len\":" << cnt << '}';
    }
    os << ']';

    if (sch) FPDFText_FindClose(sch);
    FPDFText_ClosePage(tp);

    std::string s = os.str();
    char* out = static_cast<char*>(malloc(s.size() + 1));
    if (!out) return JPDFIUM_ERR_NATIVE;
    memcpy(out, s.c_str(), s.size() + 1);
    *json = out;
    return JPDFIUM_OK;
}

void jpdfium_free_string(char* str) {
    free(str);
}
