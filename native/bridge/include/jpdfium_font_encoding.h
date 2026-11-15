// jpdfium_font_encoding.h - Shared PDF base-encoding tables and glyph-name
// resolution helpers used by both the qpdf and non-qpdf font repair paths.
#pragma once

#include <algorithm>
#include <cctype>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

namespace jpdfium_font_encoding {

#include "../src/adobe_glyph_names.inc"

// PDF base-encoding tables for the high byte range.
constexpr uint16_t kStandardHigh[96] = {
    0,      0xA1,   0xA2,   0xA3,   0x2044, 0xA5,   0x192,  0xA7,   0xA4, 0x27,  0x201C, 0xAB,
    0x2039, 0x203A, 0xFB01, 0xFB02, 0,      0x2013, 0x2020, 0x2021, 0xB7, 0,     0xB6,   0x2022,
    0x201A, 0x201E, 0x201D, 0xBB,   0x2026, 0x2030, 0,      0xBF,   0,    0x60,  0xB4,   0x2C6,
    0x2DC,  0xAF,   0x2D8,  0x2D9,  0xA8,   0,      0x2DA,  0xB8,   0,    0x2DD, 0x2DB,  0x2C7,
    0x2014, 0,      0,      0,      0,      0,      0,      0,      0,    0,     0,      0,
    0,      0,      0,      0,      0,      0xC6,   0,      0xAA,   0,    0,     0,      0,
    0x141,  0xD8,   0x152,  0xBA,   0,      0,      0,      0,      0,    0xE6,  0,      0,
    0,      0x131,  0,      0,      0x142,  0xF8,   0x153,  0xDF,   0,    0,     0,      0};
constexpr uint16_t kMacRomanHigh[128] = {
    0xC4,   0xC5,   0xC7,   0xC9,   0xD1,   0xD6,  0xDC,   0xE1,   0xE0,   0xE2,   0xE4,   0xE3,
    0xE5,   0xE7,   0xE9,   0xE8,   0xEA,   0xEB,  0xED,   0xEC,   0xEE,   0xEF,   0xF1,   0xF3,
    0xF2,   0xF4,   0xF6,   0xF5,   0xFA,   0xF9,  0xFB,   0xFC,   0x2020, 0xB0,   0xA2,   0xA3,
    0xA7,   0x2022, 0xB6,   0xDF,   0xAE,   0xA9,  0x2122, 0xB4,   0xA8,   0x2260, 0xC6,   0xD8,
    0x221E, 0xB1,   0x2264, 0x2265, 0xA5,   0xB5,  0x2202, 0x2211, 0x220F, 0x3C0,  0x222B, 0xAA,
    0xBA,   0x3A9,  0xE6,   0xF8,   0xBF,   0xA1,  0xAC,   0x221A, 0x192,  0x2248, 0x2206, 0xAB,
    0xBB,   0x2026, 0x20,   0xC0,   0xC3,   0xD5,  0x152,  0x153,  0x2013, 0x2014, 0x201C, 0x201D,
    0x2018, 0x2019, 0xF7,   0x25CA, 0xFF,   0x178, 0x2044, 0xA4,   0x2039, 0x203A, 0xFB01, 0xFB02,
    0x2021, 0xB7,   0x201A, 0x201E, 0x2030, 0xC2,  0xCA,   0xC1,   0xCB,   0xC8,   0xCD,   0xCE,
    0xCF,   0xCC,   0xD3,   0xD4,   0xF8FF, 0xD2,  0xDA,   0xDB,   0xD9,   0x131,  0x2C6,  0x2DC,
    0xAF,   0x2D8,  0x2D9,  0x2DA,  0xB8,   0x2DD, 0x2DB,  0x2C7};
// WinAnsi 0x80..0x9F. ISO 32000-1 Annex D.2 notes 3-5 define the codes that
// have no WinAnsi glyph (0x81, 0x8D, 0x8F, 0x90, 0x9D) as bullet.
constexpr uint16_t kWinAnsiHigh[32] = {
    0x20AC, 0x2022, 0x201A, 0x192,  0x201E, 0x2026, 0x2020, 0x2021, 0x2C6,  0x2030, 0x160,
    0x2039, 0x152,  0x2022, 0x17D,  0x2022, 0x2022, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022,
    0x2013, 0x2014, 0x2DC,  0x2122, 0x161,  0x203A, 0x153,  0x2022, 0x17E,  0x178};

enum class BaseEncoding { Standard, WinAnsi, MacRoman };

inline uint32_t baseEncodingUnicode(BaseEncoding enc, int code) {
    if (code < 0x20 || code > 0xFF) return 0;
    if (code < 0x7F) {
        if (enc == BaseEncoding::Standard && code == 0x27) return 0x2019;
        if (enc == BaseEncoding::Standard && code == 0x60) return 0x2018;
        return static_cast<uint32_t>(code);
    }
    if (code == 0x7F) return enc == BaseEncoding::WinAnsi ? 0x2022 : 0;
    if (enc == BaseEncoding::MacRoman) return kMacRomanHigh[code - 0x80];
    if (enc == BaseEncoding::WinAnsi) {
        // ISO 32000-1 Annex D.2 Notes 3-5: WinAnsi maps 0xA0 to space, 0xAD to
        // hyphen, and unused codes > 0x20 to bullet (U+2022).
        if (code == 0xA0 || code == 0xAD) return static_cast<uint32_t>(code - 0x80);
        return code >= 0xA0 ? static_cast<uint32_t>(code) : kWinAnsiHigh[code - 0x80];
    }
    return code >= 0xA0 ? kStandardHigh[code - 0xA0] : 0;
}

namespace detail {
inline std::string stripGlyphVariant(std::string const& raw_name) {
    std::string name = raw_name;
    // Leave names that start with '.' alone so ".notdef" is preserved.
    if (!name.empty() && name[0] != '.') {
        auto dot = name.find('.');
        if (dot != std::string::npos) name.resize(dot);
    }
    return name;
}

inline bool allHex(std::string const& value, size_t start, size_t end) {
    for (size_t i = start; i < end; ++i)
        if (!std::isxdigit(static_cast<unsigned char>(value[i]))) return false;
    return true;
}

inline bool isSurrogate(uint32_t value) {
    return value >= 0xD800 && value <= 0xDFFF;
}
}  // namespace detail

// Resolve an Adobe glyph name to a Unicode scalar. Handles single-character
// names, the uXXXX/uniXXXX conventions, and the Adobe Glyph List. Variant
// suffixes starting at the first '.' are stripped.
inline uint32_t unicodeForGlyphName(std::string const& raw_name) {
    std::string name = detail::stripGlyphVariant(raw_name);
    if (name.size() == 1) return static_cast<uint8_t>(name[0]);
    if (name.size() >= 5 && name.size() <= 7 && name[0] == 'u') {
        if (detail::allHex(name, 1, name.size())) {
            uint32_t value = static_cast<uint32_t>(std::strtoul(name.c_str() + 1, nullptr, 16));
            if (value <= 0x10FFFF && !detail::isSurrogate(value)) return value;
        }
    }
    if (name.size() == 7 && name.compare(0, 3, "uni") == 0) {
        if (detail::allHex(name, 3, 7)) {
            uint32_t value = static_cast<uint32_t>(std::strtoul(name.c_str() + 3, nullptr, 16));
            if (value <= 0x10FFFF && !detail::isSurrogate(value)) return value;
        }
    }
    auto const* first = std::begin(kAdobeGlyphNames);
    auto const* last = std::end(kAdobeGlyphNames);
    auto it = std::lower_bound(first, last, name,
                               [](AdobeGlyphName const& entry, std::string const& value) {
                                   return std::strcmp(entry.name, value.c_str()) < 0;
                               });
    if (it != last && name == it->name) return it->unicode;
    return 0;
}

// Resolve a glyph name to the sequence of Unicode scalars it represents.
// Supports single names, '_'-separated ligature components (f_i, f_f_i) and
// multi-scalar uniXXXXYYYY... names.
inline std::vector<uint32_t> unicodeSequenceForGlyphName(std::string const& raw_name) {
    std::string name = detail::stripGlyphVariant(raw_name);
    // Multi-scalar uniXXXXYYYY... (each 4 hex digits is a non-surrogate BMP scalar).
    if (name.size() > 7 && name.compare(0, 3, "uni") == 0 && (name.size() - 3) % 4 == 0 &&
        detail::allHex(name, 3, name.size())) {
        std::vector<uint32_t> sequence;
        for (size_t i = 3; i < name.size(); i += 4) {
            uint32_t value =
                static_cast<uint32_t>(std::strtoul(name.substr(i, 4).c_str(), nullptr, 16));
            if (value > 0xFFFF || detail::isSurrogate(value)) return {};
            sequence.push_back(value);
        }
        return sequence;
    }
    // Ligature names: resolve each '_'-separated component.
    if (name.find('_') != std::string::npos) {
        std::vector<uint32_t> sequence;
        size_t start = 0;
        while (start <= name.size()) {
            auto separator = name.find('_', start);
            std::string component = name.substr(
                start, separator == std::string::npos ? std::string::npos : separator - start);
            uint32_t value = unicodeForGlyphName(component);
            if (!value) return {};
            sequence.push_back(value);
            if (separator == std::string::npos) break;
            start = separator + 1;
        }
        return sequence;
    }
    uint32_t value = unicodeForGlyphName(name);
    if (!value) return {};
    return {value};
}

}  // namespace jpdfium_font_encoding
