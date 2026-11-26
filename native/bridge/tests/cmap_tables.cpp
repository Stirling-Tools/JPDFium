// cmap_tables - verifies generated Adobe CJK CMap tables against known entries.

#include <cstdint>
#include <cstdio>

#include "../include/jpdfium_font_encoding.h"
#include "../src/gb1_cmap.inc"
#include "../src/japan1_cmap.inc"
#include "../src/korea1_cmap.inc"

namespace {

int g_failures = 0;

void check(bool condition, const char* message) {
    if (!condition) {
        std::fprintf(stderr, "cmap_tables: FAILED: %s\n", message);
        ++g_failures;
    }
}

template <typename Range, size_t N>
bool lookup(Range const (&ranges)[N], uint32_t key, uint32_t& out) {
    size_t lo = 0;
    size_t hi = N;
    while (lo < hi) {
        size_t mid = lo + (hi - lo) / 2;
        if (ranges[mid].hi < key) {
            lo = mid + 1;
        } else {
            hi = mid;
        }
    }
    if (lo == N || key < ranges[lo].lo) return false;
    out = ranges[lo].cid + (key - ranges[lo].lo);
    return true;
}

template <typename Range, size_t N>
bool lookupUnicode(Range const (&ranges)[N], uint32_t cid, uint32_t& out) {
    size_t lo = 0;
    size_t hi = N;
    while (lo < hi) {
        size_t mid = lo + (hi - lo) / 2;
        if (ranges[mid].cidHi < cid) {
            lo = mid + 1;
        } else {
            hi = mid;
        }
    }
    if (lo == N || cid < ranges[lo].cidLo) return false;
    out = ranges[lo].unicode + (cid - ranges[lo].cidLo);
    return true;
}

template <typename Range, size_t N>
void checkSorted(Range const (&ranges)[N], const char* name) {
    for (size_t i = 0; i < N; ++i) {
        if (ranges[i].lo > ranges[i].hi) {
            std::fprintf(stderr, "cmap_tables: FAILED: %s range %zu is inverted\n", name, i);
            ++g_failures;
        }
        if (i > 0 && ranges[i].lo <= ranges[i - 1].hi) {
            std::fprintf(stderr, "cmap_tables: FAILED: %s range %zu overlaps\n", name, i);
            ++g_failures;
        }
    }
}

template <typename Range, size_t N>
void checkSortedUnicode(Range const (&ranges)[N], const char* name) {
    for (size_t i = 0; i < N; ++i) {
        if (ranges[i].cidLo > ranges[i].cidHi) {
            std::fprintf(stderr, "cmap_tables: FAILED: %s range %zu is inverted\n", name, i);
            ++g_failures;
        }
        if (i > 0 && ranges[i].cidLo <= ranges[i - 1].cidHi) {
            std::fprintf(stderr, "cmap_tables: FAILED: %s range %zu overlaps\n", name, i);
            ++g_failures;
        }
    }
}

}  // namespace

int main() {
    uint32_t value = 0;

    checkSorted(kJapan1CodeRanges, "Japan1");
    checkSorted(kJapan1VertCodeRanges, "Japan1 vertical");
    checkSortedUnicode(kJapan1UnicodeRanges, "Japan1 Unicode");
    checkSorted(kKorea1CodeRanges, "Korea1");
    checkSorted(kKorea1VertCodeRanges, "Korea1 vertical");
    checkSortedUnicode(kKorea1UnicodeRanges, "Korea1 Unicode");
    checkSorted(kGb1CodeRanges, "GB1");
    checkSorted(kGb1VertCodeRanges, "GB1 vertical");
    checkSortedUnicode(kGb1UnicodeRanges, "GB1 Unicode");

    check(lookup(kJapan1CodeRanges, 0x93FA, value) && value == 3284, "Japan1 0x93FA -> CID 3284");
    check(lookup(kJapan1VertCodeRanges, 0x8141, value) && value == 7887,
          "Japan1 vertical 0x8141 -> CID 7887");
    check(lookup(kKorea1CodeRanges, 0xA1A1, value) && value == 101, "Korea1 0xA1A1 -> CID 101");
    check(lookup(kGb1CodeRanges, 0xA1A1, value) && value == 96, "GB1 0xA1A1 -> CID 96");

    check(lookupUnicode(kJapan1UnicodeRanges, 0x1DD9, value) && value == 0x28CDD,
          "Japan1 CID 0x1DD9 -> U+28CDD (surrogate-pair source)");
    check(lookupUnicode(kJapan1UnicodeRanges, 0x046D, value) && value == 0x9022,
          "Japan1 CID 0x046D -> base scalar before variation selector");
    check(lookupUnicode(kKorea1UnicodeRanges, 0x2072, value) && value == 0x005B,
          "Korea1 CID 0x2072 -> first scalar of [10]");
    check(lookupUnicode(kGb1UnicodeRanges, 0x5620, value) && value == 0x20087,
          "GB1 CID 0x5620 -> U+20087 (surrogate-pair source)");

    using namespace jpdfium_font_encoding;
    check(baseEncodingUnicode(BaseEncoding::Standard, 0x27) == 0x2019,
          "StandardEncoding 0x27 -> quoteright");
    check(baseEncodingUnicode(BaseEncoding::Standard, 0x60) == 0x2018,
          "StandardEncoding 0x60 -> quoteleft");
    check(baseEncodingUnicode(BaseEncoding::Standard, 0xA4) == 0x2044,
          "StandardEncoding 0xA4 -> fraction");
    check(baseEncodingUnicode(BaseEncoding::Standard, 0xAE) == 0xFB01,
          "StandardEncoding 0xAE -> fi");
    check(baseEncodingUnicode(BaseEncoding::WinAnsi, 0x7F) == 0x2022, "WinAnsi 0x7F -> bullet");
    check(baseEncodingUnicode(BaseEncoding::WinAnsi, 0x80) == 0x20AC, "WinAnsi 0x80 -> Euro");
    check(baseEncodingUnicode(BaseEncoding::WinAnsi, 0x81) == 0x2022,
          "WinAnsi undefined 0x81 -> bullet");
    check(baseEncodingUnicode(BaseEncoding::WinAnsi, 0xA0) == 0x0020, "WinAnsi 0xA0 -> space");
    check(baseEncodingUnicode(BaseEncoding::WinAnsi, 0xAD) == 0x002D, "WinAnsi 0xAD -> hyphen");
    check(baseEncodingUnicode(BaseEncoding::MacRoman, 0xB0) == 0x221E, "MacRoman 0xB0 -> infinity");
    check(baseEncodingUnicode(BaseEncoding::MacRoman, 0xCA) == 0x0020, "MacRoman 0xCA -> space");
    check(baseEncodingUnicode(BaseEncoding::MacRoman, 0xDB) == 0x00A4, "MacRoman 0xDB -> currency");
    check(baseEncodingUnicode(BaseEncoding::MacRoman, 0xF0) == 0xF8FF,
          "MacRoman 0xF0 -> Apple logo");

    check(unicodeForGlyphName("A") == 0x41, "glyph A -> U+0041");
    check(unicodeForGlyphName("quoteright") == 0x2019, "glyph quoteright -> U+2019");
    check(unicodeForGlyphName("u00e9") == 0xE9, "lowercase u00e9 escape");
    check(unicodeForGlyphName("uface") == 0x0, "uface is a name, not an escape");
    check(unicodeForGlyphName("uni20AC") == 0x20AC, "uni20AC escape");
    check(unicodeForGlyphName("A.swash") == 0x41, "glyph variant suffix is stripped");
    check(unicodeForGlyphName("nosuchglyph") == 0x0, "unknown glyph resolves to zero");
    check(isAdobeGlyphListMember("fi"), "AGL contains fi");
    check(!isAdobeGlyphListMember("nosuchglyph"), "AGL rejects unknown names");
    check(unicodeSequenceForGlyphName("f_f_i").size() == 3 &&
              unicodeSequenceForGlyphName("f_f_i")[0] == 0x66,
          "f_f_i resolves to three scalars");
    check(unicodeSequenceForGlyphName("dalethatafpatah").size() == 2,
          "AGL sequence name expands to two scalars");
    const char* spaceName = adobeGlyphNameForUnicode(0x20);
    check(spaceName != nullptr && unicodeForGlyphName(spaceName) == 0x20,
          "U+0020 round-trips through the AGL name table");

    if (g_failures) {
        std::fprintf(stderr, "cmap_tables: %d check(s) FAILED\n", g_failures);
        return 1;
    }
    std::puts("cmap_tables: all checks passed");
    return 0;
}
