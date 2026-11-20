// jpdfium_font_encoding.h - Shared PDF base-encoding tables and glyph-name
// resolution helpers used by both the qpdf and non-qpdf font repair paths.
#pragma once

#include <algorithm>
#include <array>
#include <cctype>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

namespace jpdfium_font_encoding {

#include "../src/adobe_glyph_names.inc"

// Sentinel representing character codes that have no mapping in a base encoding.
constexpr uint16_t kUnmapped = 0;

// PDF Base-Encoding Tables (ISO 32000-1:2008 Annex D / Adobe PDF Reference 1.7)
//
// Conceptual mapping chain:
//   PDF character code -> encoding glyph name -> Unicode interpretation
//
// These constant lookup tables map character codes directly to their canonical
// Unicode scalar values per the Adobe Glyph List (AGL 2.0).

// StandardEncoding: ISO 32000-1:2008 Table D.1 (Character codes 0xA0..0xFF)
// Low ASCII codes 0x20..0x7E map 1:1 to ASCII, except:
//   0x27 -> quoteright (U+2019)
//   0x60 -> quoteleft  (U+2018)
// Positions without standard glyph assignments are marked kUnmapped (0).
inline constexpr std::array<uint16_t, 96> kStandardHigh = {{
    // 0xA0–0xA7
    kUnmapped,
    0x00A1,
    0x00A2,
    0x00A3,
    0x2044,
    0x00A5,
    0x0192,
    0x00A7,
    // 0xA8–0xAF
    0x00A4,
    0x0027,
    0x201C,
    0x00AB,
    0x2039,
    0x203A,
    0xFB01,
    0xFB02,
    // 0xB0–0xB7
    kUnmapped,
    0x2013,
    0x2020,
    0x2021,
    0x00B7,
    kUnmapped,
    0x00B6,
    0x2022,
    // 0xB8–0xBF
    0x201A,
    0x201E,
    0x201D,
    0x00BB,
    0x2026,
    0x2030,
    kUnmapped,
    0x00BF,
    // 0xC0–0xC7
    kUnmapped,
    0x0060,
    0x00B4,
    0x02C6,
    0x02DC,
    0x00AF,
    0x02D8,
    0x02D9,
    // 0xC8–0xCF
    0x00A8,
    kUnmapped,
    0x02DA,
    0x00B8,
    kUnmapped,
    0x02DD,
    0x02DB,
    0x02C7,
    // 0xD0–0xD7
    0x2014,
    kUnmapped,
    kUnmapped,
    kUnmapped,
    kUnmapped,
    kUnmapped,
    kUnmapped,
    kUnmapped,
    // 0xD8–0xDF
    kUnmapped,
    kUnmapped,
    kUnmapped,
    kUnmapped,
    kUnmapped,
    kUnmapped,
    kUnmapped,
    kUnmapped,
    // 0xE0–0xE7
    kUnmapped,
    0x00C6,
    kUnmapped,
    0x00AA,
    kUnmapped,
    kUnmapped,
    kUnmapped,
    kUnmapped,
    // 0xE8–0xEF
    0x0141,
    0x00D8,
    0x0152,
    0x00BA,
    kUnmapped,
    kUnmapped,
    kUnmapped,
    kUnmapped,
    // 0xF0–0xF7
    kUnmapped,
    0x00E6,
    kUnmapped,
    kUnmapped,
    kUnmapped,
    0x0131,
    kUnmapped,
    kUnmapped,
    // 0xF8–0xFF
    0x0142,
    0x00F8,
    0x0153,
    0x00DF,
    kUnmapped,
    kUnmapped,
    kUnmapped,
    kUnmapped,
}};

// MacRomanEncoding: ISO 32000-1:2008 Table D.4 (Character codes 0x80..0xFF)
// Note: PDF MacRomanEncoding preserves 0xDB as 'currency' (U+00A4), distinct
// from modern Apple Mac OS Roman which reassigned 0xDB to the Euro sign (U+20AC).
// Note: 0xCA is 'nonbreakingspace' (U+0020 / U+00A0); 0xF0 is Apple logo (U+F8FF).
inline constexpr std::array<uint16_t, 128> kMacRomanHigh = {{
    // 0x80–0x87
    0x00C4,
    0x00C5,
    0x00C7,
    0x00C9,
    0x00D1,
    0x00D6,
    0x00DC,
    0x00E1,
    // 0x88–0x8F
    0x00E0,
    0x00E2,
    0x00E4,
    0x00E3,
    0x00E5,
    0x00E7,
    0x00E9,
    0x00E8,
    // 0x90–0x97
    0x00EA,
    0x00EB,
    0x00ED,
    0x00EC,
    0x00EE,
    0x00EF,
    0x00F1,
    0x00F3,
    // 0x98–0x9F
    0x00F2,
    0x00F4,
    0x00F6,
    0x00F5,
    0x00FA,
    0x00F9,
    0x00FB,
    0x00FC,
    // 0xA0–0xA7
    0x2020,
    0x00B0,
    0x00A2,
    0x00A3,
    0x00A7,
    0x2022,
    0x00B6,
    0x00DF,
    // 0xA8–0xAF
    0x00AE,
    0x00A9,
    0x2122,
    0x00B4,
    0x00A8,
    0x2260,
    0x00C6,
    0x00D8,
    // 0xB0–0xB7
    0x221E,
    0x00B1,
    0x2264,
    0x2265,
    0x00A5,
    0x00B5,
    0x2202,
    0x2211,
    // 0xB8–0xBF
    0x220F,
    0x03C0,
    0x222B,
    0x00AA,
    0x00BA,
    0x03A9,
    0x00E6,
    0x00F8,
    // 0xC0–0xC7
    0x00BF,
    0x00A1,
    0x00AC,
    0x221A,
    0x0192,
    0x2248,
    0x2206,
    0x00AB,
    // 0xC8–0xCF
    0x00BB,
    0x2026,
    0x0020,
    0x00C0,
    0x00C3,
    0x00D5,
    0x0152,
    0x0153,
    // 0xD0–0xD7
    0x2013,
    0x2014,
    0x201C,
    0x201D,
    0x2018,
    0x2019,
    0x00F7,
    0x25CA,
    // 0xD8–0xDF
    0x00FF,
    0x0178,
    0x2044,
    0x00A4,
    0x2039,
    0x203A,
    0xFB01,
    0xFB02,
    // 0xE0–0xE7
    0x2021,
    0x00B7,
    0x201A,
    0x201E,
    0x2030,
    0x00C2,
    0x00CA,
    0x00C1,
    // 0xE8–0xEF
    0x00CB,
    0x00C8,
    0x00CD,
    0x00CE,
    0x00CF,
    0x00CC,
    0x00D3,
    0x00D4,
    // 0xF0–0xF7
    0xF8FF,
    0x00D2,
    0x00DA,
    0x00DB,
    0x00D9,
    0x0131,
    0x02C6,
    0x02DC,
    // 0xF8–0xFF
    0x00AF,
    0x02D8,
    0x02D9,
    0x02DA,
    0x00B8,
    0x02DD,
    0x02DB,
    0x02C7,
}};

// WinAnsiEncoding: ISO 32000-1:2008 Annex D.2 & Table D.5 (Codes 0x80..0x9F)
// ISO 32000-1 Annex D.2 Notes 3-5:
// - Undefined codes in Windows Code Page 1252 (0x81, 0x8D, 0x8F, 0x90, 0x9D)
//   and 0x7F are defined in PDF WinAnsi as 'bullet' (U+2022).
// - Code 0xA0 maps to space (U+0020); code 0xAD maps to hyphen (U+002D).
inline constexpr std::array<uint16_t, 32> kWinAnsiHigh = {{
    // 0x80–0x87
    0x20AC,
    0x2022,
    0x201A,
    0x0192,
    0x201E,
    0x2026,
    0x2020,
    0x2021,
    // 0x88–0x8F
    0x02C6,
    0x2030,
    0x0160,
    0x2039,
    0x0152,
    0x2022,
    0x017D,
    0x2022,
    // 0x90–0x97
    0x2022,
    0x2018,
    0x2019,
    0x201C,
    0x201D,
    0x2022,
    0x2013,
    0x2014,
    // 0x98–0x9F
    0x02DC,
    0x2122,
    0x0161,
    0x203A,
    0x0153,
    0x2022,
    0x017E,
    0x0178,
}};

enum class BaseEncoding : uint8_t { Standard, WinAnsi, MacRoman };

inline uint32_t baseEncodingUnicode(BaseEncoding enc, int code) {
    if (code < 0x20 || code > 0xFF) return kUnmapped;
    if (code < 0x7F) {
        if (enc == BaseEncoding::Standard && code == 0x27) return 0x2019;  // quoteright
        if (enc == BaseEncoding::Standard && code == 0x60) return 0x2018;  // quoteleft
        return static_cast<uint32_t>(code);
    }
    if (code == 0x7F) return enc == BaseEncoding::WinAnsi ? 0x2022 : kUnmapped;
    if (enc == BaseEncoding::MacRoman) return kMacRomanHigh[code - 0x80];
    if (enc == BaseEncoding::WinAnsi) {
        if (code == 0xA0) return 0x0020;  // space alias (Annex D.2 Note 3)
        if (code == 0xAD) return 0x002D;  // hyphen alias (Annex D.2 Note 3)
        return code >= 0xA0 ? static_cast<uint32_t>(code) : kWinAnsiHigh[code - 0x80];
    }
    return code >= 0xA0 ? kStandardHigh[code - 0xA0] : kUnmapped;
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

// AGL uXXXX/uniXXXX names require uppercase hex, but older producers emitted
// lowercase names such as u00e9. Accept a lowercase escape only when it
// contains a decimal digit; that keeps u00e9 working while leaving ordinary
// all-letter names such as "uface" alone.
inline bool allUpperHex(std::string const& value, size_t start, size_t end) {
    for (size_t i = start; i < end; ++i) {
        char c = value[i];
        if (!((c >= '0' && c <= '9') || (c >= 'A' && c <= 'F'))) return false;
    }
    return true;
}

inline bool hasDecimalDigit(std::string const& value, size_t start, size_t end) {
    for (size_t i = start; i < end; ++i)
        if (value[i] >= '0' && value[i] <= '9') return true;
    return false;
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
    if (name.size() >= 5 && name.size() <= 7 && name[0] == 'u' &&
        detail::allHex(name, 1, name.size())) {
        const uint32_t value = static_cast<uint32_t>(std::strtoul(name.c_str() + 1, nullptr, 16));
        const bool unambiguous = detail::allUpperHex(name, 1, name.size()) ||
                                 detail::hasDecimalDigit(name, 1, name.size());
        if (unambiguous && value <= 0x10FFFF && !detail::isSurrogate(value)) return value;
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
    // AGL sequence names such as dalethatafpatah.
    auto const* seq_first = std::begin(kAdobeGlyphSequences);
    auto const* seq_last = std::end(kAdobeGlyphSequences);
    auto seq_it = std::lower_bound(seq_first, seq_last, name,
                                   [](AdobeGlyphSequence const& entry, std::string const& value) {
                                       return std::strcmp(entry.name, value.c_str()) < 0;
                                   });
    if (seq_it != seq_last && name == seq_it->name) {
        return std::vector<uint32_t>(seq_it->unicode.begin(),
                                     seq_it->unicode.begin() + seq_it->length);
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

inline const char* adobeGlyphNameForUnicode(uint32_t unicode) {
    if (!unicode) return nullptr;
    auto const* first = std::begin(kAdobeGlyphByUnicode);
    auto const* last = std::end(kAdobeGlyphByUnicode);
    auto it = std::lower_bound(
        first, last, unicode,
        [](AdobeGlyphName const& entry, uint32_t value) { return entry.unicode < value; });
    if (it != last && it->unicode == unicode) return it->name;
    return nullptr;
}

}  // namespace jpdfium_font_encoding
