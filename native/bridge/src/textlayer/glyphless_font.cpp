// glyphless_font.cpp - builds the glyphless TrueType program table by table.

#include "glyphless_font.h"

#include <array>
#include <cstddef>
#include <string_view>
#include <utility>

namespace jpdf {

namespace {

constexpr int kUnitsPerEm = 1000;
constexpr int kGlyphCount = 2;
constexpr int kCapHeight = 700;

class Bytes {
   public:
    void u8(uint8_t v) {
        data_.push_back(v);
    }
    void u16(int v) {
        u8(static_cast<uint8_t>((v >> 8) & 0xFF));
        u8(static_cast<uint8_t>(v & 0xFF));
    }
    void i16(int v) {
        u16(v);
    }
    void u32(uint32_t v) {
        u16(static_cast<int>(v >> 16));
        u16(static_cast<int>(v & 0xFFFF));
    }
    void tag(std::string_view t) {
        for (char c : t) u8(static_cast<uint8_t>(c));
    }
    void zeros(size_t n) {
        data_.insert(data_.end(), n, 0);
    }
    std::vector<uint8_t>& data() {
        return data_;
    }

   private:
    std::vector<uint8_t> data_;
};

uint32_t checksum(const std::vector<uint8_t>& d, size_t begin, size_t length) {
    uint32_t sum = 0;
    for (size_t i = 0; i < length; i += 4) {
        uint32_t word = 0;
        for (size_t k = 0; k < 4; ++k) {
            word = (word << 8) | (i + k < length ? d[begin + i + k] : 0u);
        }
        sum += word;
    }
    return sum;
}

std::vector<uint8_t> os2() {
    Bytes b;
    b.u16(4);                  // version
    b.i16(kGlyphlessAdvance);  // xAvgCharWidth
    b.u16(400);                // usWeightClass
    b.u16(5);                  // usWidthClass
    b.u16(0);                  // fsType: installable embedding
    for (int v : {650, 600, 0, 75, 650, 600, 0, 350, 50, 300})
        b.i16(v);               // sub/superscript, strikeout
    b.i16(0);                   // sFamilyClass
    b.zeros(10);                // panose
    b.zeros(16);                // ulUnicodeRange1-4
    b.tag("NONE");              // achVendID
    b.u16(0x0040);              // fsSelection: regular
    b.u16(0xFFFF);              // usFirstCharIndex: the cmap maps only U+FFFF
    b.u16(0xFFFF);              // usLastCharIndex
    b.i16(kGlyphlessAscent);    // sTypoAscender
    b.i16(kGlyphlessDescent);   // sTypoDescender
    b.i16(0);                   // sTypoLineGap
    b.u16(kGlyphlessAscent);    // usWinAscent
    b.u16(-kGlyphlessDescent);  // usWinDescent
    b.zeros(8);                 // ulCodePageRange1-2
    b.i16(500);                 // sxHeight
    b.i16(kCapHeight);          // sCapHeight
    b.u16(0);                   // usDefaultChar
    b.u16(32);                  // usBreakChar
    b.u16(0);                   // usMaxContext
    return std::move(b.data());
}

// Format 4 with only the mandatory final segment, so no character maps to a glyph.
std::vector<uint8_t> cmap() {
    Bytes b;
    b.u16(0);       // version
    b.u16(1);       // numTables
    b.u16(3);       // platform: Windows
    b.u16(1);       // encoding: Unicode BMP
    b.u32(12);      // subtable offset
    b.u16(4);       // format
    b.u16(24);      // length
    b.u16(0);       // language
    b.u16(2);       // segCountX2
    b.u16(2);       // searchRange
    b.u16(0);       // entrySelector
    b.u16(0);       // rangeShift
    b.u16(0xFFFF);  // endCode
    b.u16(0);       // reservedPad
    b.u16(0xFFFF);  // startCode
    b.u16(1);       // idDelta: 0xFFFF maps to glyph 0
    b.u16(0);       // idRangeOffset
    return std::move(b.data());
}

// Two single-point contours span the em box but enclose no area, so nothing is ever painted.
// PDFium skips text whose glyph box is empty, which a truly empty glyph would have.
std::vector<uint8_t> blank_glyph() {
    Bytes b;
    b.i16(2);  // numberOfContours
    b.i16(0);
    b.i16(kGlyphlessDescent);
    b.i16(kGlyphlessAdvance);
    b.i16(kGlyphlessAscent);
    b.u16(0);  // endPtsOfContours
    b.u16(1);
    b.u16(0);  // instructionLength
    b.u8(1);   // on-curve, 16-bit deltas
    b.u8(1);
    b.i16(0);
    b.i16(kGlyphlessAdvance);
    b.i16(kGlyphlessDescent);
    b.i16(kGlyphlessAscent - kGlyphlessDescent);
    return std::move(b.data());
}

// .notdef and glyph 1 share the blank outline.
std::vector<uint8_t> glyf() {
    std::vector<uint8_t> glyph = blank_glyph();
    std::vector<uint8_t> out = glyph;
    out.insert(out.end(), glyph.begin(), glyph.end());
    return out;
}

std::vector<uint8_t> head() {
    Bytes b;
    b.u32(0x00010000);  // version
    b.u32(0x00010000);  // fontRevision
    b.u32(0);           // checkSumAdjustment, patched after assembly
    b.u32(0x5F0F3CF5);  // magicNumber
    b.u16(0x000B);      // flags: baseline at 0, lsb at 0, integer ppem
    b.u16(kUnitsPerEm);
    b.zeros(16);  // created, modified: fixed for reproducible output
    b.i16(0);
    b.i16(kGlyphlessDescent);
    b.i16(kGlyphlessAdvance);
    b.i16(kGlyphlessAscent);
    b.u16(0);  // macStyle
    b.u16(3);  // lowestRecPPEM
    b.i16(2);  // fontDirectionHint
    b.i16(0);  // indexToLocFormat: short
    b.i16(0);  // glyphDataFormat
    return std::move(b.data());
}

std::vector<uint8_t> hhea() {
    Bytes b;
    b.u32(0x00010000);
    b.i16(kGlyphlessAscent);
    b.i16(kGlyphlessDescent);
    b.i16(0);                  // lineGap
    b.u16(kGlyphlessAdvance);  // advanceWidthMax
    b.i16(0);                  // minLeftSideBearing
    b.i16(0);                  // minRightSideBearing
    b.i16(kGlyphlessAdvance);  // xMaxExtent
    b.i16(1);                  // caretSlopeRise
    b.i16(0);                  // caretSlopeRun
    b.zeros(10);               // caretOffset, reserved
    b.i16(0);                  // metricDataFormat
    b.u16(kGlyphCount);        // numberOfHMetrics
    return std::move(b.data());
}

std::vector<uint8_t> hmtx() {
    Bytes b;
    for (int glyph = 0; glyph < kGlyphCount; ++glyph) {
        b.u16(kGlyphlessAdvance);
        b.i16(0);  // left side bearing
    }
    return std::move(b.data());
}

// Short offsets, stored halved; both glyphs are the same length.
std::vector<uint8_t> loca(size_t glyf_length) {
    Bytes b;
    for (int glyph = 0; glyph <= kGlyphCount; ++glyph) {
        b.u16(static_cast<int>(glyf_length / kGlyphCount * glyph / 2));
    }
    return std::move(b.data());
}

std::vector<uint8_t> maxp() {
    Bytes b;
    b.u32(0x00010000);
    b.u16(kGlyphCount);
    b.u16(2);     // maxPoints
    b.u16(2);     // maxContours
    b.zeros(4);   // maxCompositePoints, maxCompositeContours
    b.u16(2);     // maxZones
    b.zeros(16);  // maxTwilightPoints through maxComponentDepth
    return std::move(b.data());
}

std::vector<uint8_t> name() {
    constexpr std::array<std::pair<uint16_t, std::string_view>, 5> records = {{
        {1, "GlyphLessFont"},
        {2, "Regular"},
        {4, "GlyphLessFont"},
        {5, "Version 1.000"},
        {6, "GlyphLessFont"},
    }};
    Bytes b;
    b.u16(0);
    b.u16(static_cast<int>(records.size()));
    b.u16(static_cast<int>(6 + 12 * records.size()));
    int offset = 0;
    for (const auto& [id, text] : records) {
        b.u16(3);       // platform: Windows
        b.u16(1);       // encoding: Unicode BMP
        b.u16(0x0409);  // language: en-US
        b.u16(id);
        b.u16(static_cast<int>(text.size() * 2));
        b.u16(offset);
        offset += static_cast<int>(text.size() * 2);
    }
    for (const auto& record : records) {
        for (char c : record.second) b.u16(c);  // ASCII as UTF-16BE
    }
    return std::move(b.data());
}

std::vector<uint8_t> post() {
    Bytes b;
    b.u32(0x00030000);  // version 3: no glyph names
    b.u32(0);           // italicAngle
    b.i16(-100);        // underlinePosition
    b.i16(50);          // underlineThickness
    b.u32(1);           // isFixedPitch
    b.zeros(16);        // memory hints
    return std::move(b.data());
}

std::vector<uint8_t> build() {
    std::vector<uint8_t> glyph_data = glyf();
    const std::array<std::pair<std::string_view, std::vector<uint8_t>>, 10> tables = {{
        {"OS/2", os2()},
        {"cmap", cmap()},
        {"glyf", glyph_data},
        {"head", head()},
        {"hhea", hhea()},
        {"hmtx", hmtx()},
        {"loca", loca(glyph_data.size())},
        {"maxp", maxp()},
        {"name", name()},
        {"post", post()},
    }};
    Bytes font;
    font.u32(0x00010000);  // sfntVersion: TrueType outlines
    font.u16(10);          // numTables
    font.u16(128);         // searchRange: 16 * 2^3
    font.u16(3);           // entrySelector
    font.u16(32);          // rangeShift: 10 * 16 - 128
    size_t offset = 12 + 16 * tables.size();
    size_t head_offset = 0;
    for (const auto& [tag, data] : tables) {
        font.tag(tag);
        font.u32(checksum(data, 0, data.size()));
        font.u32(static_cast<uint32_t>(offset));
        font.u32(static_cast<uint32_t>(data.size()));
        if (tag == "head") head_offset = offset;
        offset += (data.size() + 3) & ~size_t{3};
    }
    for (const auto& table : tables) {
        const std::vector<uint8_t>& data = table.second;
        font.data().insert(font.data().end(), data.begin(), data.end());
        font.zeros(((data.size() + 3) & ~size_t{3}) - data.size());
    }
    std::vector<uint8_t> out = std::move(font.data());
    const uint32_t adjustment = 0xB1B0AFBAu - checksum(out, 0, out.size());
    for (int i = 0; i < 4; ++i) {
        out[head_offset + 8 + static_cast<size_t>(i)] =
            static_cast<uint8_t>(adjustment >> (24 - 8 * i));
    }
    return out;
}

}  // namespace

const std::vector<uint8_t>& glyphless_font() {
    static const std::vector<uint8_t> font = build();
    return font;
}

}  // namespace jpdf
