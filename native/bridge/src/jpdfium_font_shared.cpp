// jpdfium_font_shared.cpp - Shared SFNT, ToUnicode CMap, FreeType, HarfBuzz,
// and QPDF font helpers used across normalization, embedding, and subsetting.

#include <algorithm>
#include <array>
#include <cctype>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <map>
#include <set>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

#include "jpdfium_font_internal.h"
#include "jpdfium_qpdf_internal.h"

using namespace jpdfium_font_encoding;

namespace jpdfium::font {

std::string jsonEscape(std::string_view value) {
    std::string out;
    out.reserve(value.size() + 8);
    for (unsigned char c : value) {
        switch (c) {
            case '"':
                out += "\\\"";
                break;
            case '\\':
                out += "\\\\";
                break;
            case '\b':
                out += "\\b";
                break;
            case '\f':
                out += "\\f";
                break;
            case '\n':
                out += "\\n";
                break;
            case '\r':
                out += "\\r";
                break;
            case '\t':
                out += "\\t";
                break;
            default:
                if (c < 0x20) {
                    char buf[8];
                    std::snprintf(buf, sizeof(buf), "\\u%04x", c);
                    out += buf;
                } else {
                    out += static_cast<char>(c);
                }
        }
    }
    return out;
}

std::string latin1ToUtf8(std::string_view raw) {
    std::string utf8;
    utf8.reserve(raw.size());
    for (unsigned char c : raw) {
        if (c < 0x80) {
            utf8 += static_cast<char>(c);
        } else {
            utf8 += static_cast<char>(0xC0 | (c >> 6));
            utf8 += static_cast<char>(0x80 | (c & 0x3F));
        }
    }
    return utf8;
}

std::string stripPdfNamePrefix(std::string name) {
    if (!name.empty() && name.front() == '/') name.erase(name.begin());
    return name;
}

std::string normalizeFontNameKey(std::string name) {
    name = stripPdfNamePrefix(std::move(name));
    if (name.size() > 7 && name[6] == '+' &&
        std::all_of(name.begin(), name.begin() + 6,
                    [](unsigned char ch) { return std::isupper(ch) != 0; })) {
        name.erase(0, 7);
    }
    std::string key;
    key.reserve(name.size());
    for (unsigned char ch : name) {
        if (std::isalnum(ch) != 0) key += static_cast<char>(std::tolower(ch));
    }
    return key;
}

bool hasSubsetTag(std::string_view name) {
    if (name.size() <= 7 || name[6] != '+') return false;
    for (size_t i = 0; i < 6; ++i) {
        unsigned char c = static_cast<unsigned char>(name[i]);
        if (c < 'A' || c > 'Z') return false;
    }
    return true;
}

std::string stripSubsetTag(std::string_view name) {
    if (hasSubsetTag(name)) return std::string(name.substr(7));
    return std::string(name);
}

std::string buildSubsetTag(std::string_view seedName, uint64_t seedHash) {
    uint64_t h = 14695981039346656037ULL ^ seedHash;
    for (unsigned char c : seedName) {
        h ^= c;
        h *= 1099511628211ULL;
    }
    std::string tag;
    tag.reserve(6);
    for (int i = 0; i < 6; ++i) {
        tag += static_cast<char>('A' + (h % 26));
        h /= 26;
    }
    return tag;
}

std::string buildSubsetTagForGlyphs(std::string_view baseName, std::set<uint32_t> const& ids) {
    size_t h = std::hash<std::string_view>{}(baseName);
    for (uint32_t id : ids) h = h * 31 + id;
    std::string tag;
    tag.reserve(6);
    for (int i = 0; i < 6; ++i, h /= 26) tag += static_cast<char>('A' + (h % 26));
    return tag;
}

uint16_t readU16BE(const uint8_t* p) {
    return static_cast<uint16_t>((static_cast<uint16_t>(p[0]) << 8) | p[1]);
}

uint32_t readU32BE(const uint8_t* p) {
    return (static_cast<uint32_t>(p[0]) << 24) | (static_cast<uint32_t>(p[1]) << 16) |
           (static_cast<uint32_t>(p[2]) << 8) | static_cast<uint32_t>(p[3]);
}

void writeU16BE(uint8_t* p, uint16_t v) {
    p[0] = static_cast<uint8_t>((v >> 8) & 0xFF);
    p[1] = static_cast<uint8_t>(v & 0xFF);
}

void writeU32BE(uint8_t* p, uint32_t v) {
    p[0] = static_cast<uint8_t>((v >> 24) & 0xFF);
    p[1] = static_cast<uint8_t>((v >> 16) & 0xFF);
    p[2] = static_cast<uint8_t>((v >> 8) & 0xFF);
    p[3] = static_cast<uint8_t>(v & 0xFF);
}

uint32_t calcSfntTableChecksum(const uint8_t* data, size_t len) {
    uint32_t sum = 0;
    for (size_t i = 0; i < len; i += 4) {
        uint32_t word = 0;
        for (size_t b = 0; b < 4 && i + b < len; ++b) {
            word = (word << 8) | data[i + b];
        }
        if (len - i < 4) word <<= (4 - (len - i)) * 8;
        sum += word;
    }
    return sum;
}

void recomputeHeadChecksumAdjustment(std::string& sfnt, uint32_t headOffset) {
    if (!headOffset || headOffset > sfnt.size() || sfnt.size() - headOffset < 12) return;
    auto* bytes = reinterpret_cast<uint8_t*>(sfnt.data());
    writeU32BE(bytes + headOffset + 8, 0);
    const uint32_t fontSum = calcSfntTableChecksum(bytes, sfnt.size());
    writeU32BE(bytes + headOffset + 8, 0xB1B0AFBAU - fontSum);
}

void sanitizeSfntForSubset(std::string& sfnt) {
    if (sfnt.size() < 12) return;
    auto* bytes = reinterpret_cast<uint8_t*>(sfnt.data());
    uint16_t numTables = readU16BE(bytes + 4);
    if (sfnt.size() < 12 + static_cast<size_t>(numTables) * 16) return;

    size_t headEntry = 0;
    uint32_t headOff = 0, headLen = 0;
    size_t postEntry = 0;
    uint32_t postOff = 0, postLen = 0;
    uint32_t maxpOff = 0, maxpLen = 0;
    uint32_t locaLen = 0;
    bool hasLoca = false;

    for (size_t i = 0; i < numTables; ++i) {
        size_t entry = 12 + i * 16;
        const uint8_t* tag = bytes + entry;
        uint32_t off = readU32BE(bytes + entry + 8);
        uint32_t len = readU32BE(bytes + entry + 12);
        if (std::memcmp(tag, "head", 4) == 0) {
            headEntry = entry;
            headOff = off;
            headLen = len;
        } else if (std::memcmp(tag, "post", 4) == 0) {
            postEntry = entry;
            postOff = off;
            postLen = len;
        } else if (std::memcmp(tag, "maxp", 4) == 0) {
            maxpOff = off;
            maxpLen = len;
        } else if (std::memcmp(tag, "loca", 4) == 0) {
            locaLen = len;
            hasLoca = true;
        }
    }

    bool modifiedHead = false;
    // ScanSoft / Nuance PDF generators sometimes emit little-endian 0x00000003
    // instead of big-endian Fixed 0x00030000 in post.version.
    if (postEntry && postOff <= sfnt.size() && sfnt.size() - postOff >= 4 && postLen >= 4) {
        if (readU32BE(bytes + postOff) == 0x00000003U) {
            writeU32BE(bytes + postOff, 0x00030000U);
            if (postLen <= sfnt.size() - postOff) {
                writeU32BE(bytes + postEntry + 4, calcSfntTableChecksum(bytes + postOff, postLen));
            }
            modifiedHead = true;
        }
    }

    // Repair corrupt head.indexToLocFormat when TrueType loca + maxp are present.
    if (headEntry && headLen >= 54 && headOff <= sfnt.size() && sfnt.size() - headOff >= 54 &&
        hasLoca && maxpLen >= 6 && maxpOff <= sfnt.size() && sfnt.size() - maxpOff >= 6) {
        uint16_t locFmt = readU16BE(bytes + headOff + 50);
        if (locFmt > 1) {
            uint16_t numGlyphs = readU16BE(bytes + maxpOff + 4);
            size_t longNeeded = static_cast<size_t>(numGlyphs + 1) * 4;
            uint16_t inferred = (locaLen >= longNeeded) ? 1 : 0;
            writeU16BE(bytes + headOff + 50, inferred);
            modifiedHead = true;
        }
    }

    if (modifiedHead && headEntry && headOff <= sfnt.size() && sfnt.size() - headOff >= 54) {
        if (headLen <= sfnt.size() - headOff) {
            writeU32BE(bytes + headOff + 8, 0);
            writeU32BE(bytes + headEntry + 4, calcSfntTableChecksum(bytes + headOff, headLen));
        }
        recomputeHeadChecksumAdjustment(sfnt, headOff);
    }
}

void ensureUnicodeMicrosoftCmap(std::string& sfnt) {
    if (sfnt.size() < 12) return;
    auto* bytes = reinterpret_cast<uint8_t*>(sfnt.data());
    uint16_t numTables = readU16BE(bytes + 4);
    if (sfnt.size() < 12 + static_cast<size_t>(numTables) * 16) return;

    size_t cmapEntryOff = 0;
    uint32_t cmapOffset = 0;
    uint32_t cmapLength = 0;
    uint32_t headOffset = 0;

    for (size_t i = 0; i < numTables; ++i) {
        size_t entry = 12 + i * 16;
        if (std::memcmp(bytes + entry, "cmap", 4) == 0) {
            cmapEntryOff = entry;
            cmapOffset = readU32BE(bytes + entry + 8);
            cmapLength = readU32BE(bytes + entry + 12);
        } else if (std::memcmp(bytes + entry, "head", 4) == 0) {
            headOffset = readU32BE(bytes + entry + 8);
        }
    }

    if (!cmapEntryOff || cmapOffset > sfnt.size() || sfnt.size() - cmapOffset < 4 ||
        cmapLength > sfnt.size() - cmapOffset)
        return;

    uint16_t numSubtables = readU16BE(bytes + cmapOffset + 2);
    if (cmapLength < 4 || static_cast<size_t>(numSubtables) * 8 > cmapLength - 4) return;

    auto readSubtableFormat = [&](size_t subEntryOff) -> uint16_t {
        uint32_t rel = readU32BE(bytes + subEntryOff + 4);
        if (rel > cmapLength || cmapLength - rel < 2) return 0xFFFFU;
        return readU16BE(bytes + cmapOffset + rel);
    };

    // Prefer keeping BMP (3,1) Format 4 and/or Full Unicode (3,10) Format 12
    // so non-BMP codepoints (U+10000..U+10FFFF) are never lost when subsetting
    // (fontTools P0.1), while dropping conflicting MacRoman/Symbol subtables.
    int bestFmt4Idx = -1;
    bool bestFmt4IsMs = false;
    int bestFmt12Idx = -1;
    bool bestFmt12IsMs = false;

    for (size_t i = 0; i < numSubtables; ++i) {
        size_t subOff = static_cast<size_t>(cmapOffset) + 4 + i * 8;
        uint16_t plat = readU16BE(bytes + subOff);
        uint16_t enc = readU16BE(bytes + subOff + 2);
        uint16_t fmt = readSubtableFormat(subOff);
        if (fmt == 4 && ((plat == 3 && enc == 1) || (plat == 0 && (enc == 0 || enc == 3)))) {
            if (bestFmt4Idx < 0 || (plat == 3 && !bestFmt4IsMs)) {
                bestFmt4Idx = static_cast<int>(i);
                bestFmt4IsMs = (plat == 3);
            }
        } else if (fmt == 12 && ((plat == 3 && enc == 10) || (plat == 0 && enc == 4))) {
            if (bestFmt12Idx < 0 || (plat == 3 && !bestFmt12IsMs)) {
                bestFmt12Idx = static_cast<int>(i);
                bestFmt12IsMs = (plat == 3);
            }
        }
    }

    if (bestFmt4Idx < 0 && bestFmt12Idx < 0) return;

    struct SubEntry {
        uint8_t raw[8];
    };
    std::vector<SubEntry> kept;
    auto pushKept = [&](int idx, uint16_t targetPlat, uint16_t targetEnc) {
        if (idx < 0) return;
        SubEntry se{};
        std::memcpy(se.raw,
                    bytes + static_cast<size_t>(cmapOffset) + 4 + static_cast<size_t>(idx) * 8, 8);
        writeU16BE(se.raw, targetPlat);
        writeU16BE(se.raw + 2, targetEnc);
        kept.push_back(se);
    };
    pushKept(bestFmt4Idx, 3, 1);
    pushKept(bestFmt12Idx, 3, 10);

    // Already canonical: nothing else in cmap directory.
    if (numSubtables == kept.size()) {
        bool identical = true;
        for (size_t i = 0; i < kept.size(); ++i) {
            if (std::memcmp(bytes + cmapOffset + 4 + i * 8, kept[i].raw, 8) != 0) {
                identical = false;
                break;
            }
        }
        if (identical) return;
    }

    for (size_t i = 0; i < kept.size(); ++i) {
        std::memcpy(bytes + cmapOffset + 4 + i * 8, kept[i].raw, 8);
    }
    writeU16BE(bytes + cmapOffset + 2, static_cast<uint16_t>(kept.size()));

    writeU32BE(bytes + cmapEntryOff + 4, calcSfntTableChecksum(bytes + cmapOffset, cmapLength));
    recomputeHeadChecksumAdjustment(sfnt, headOffset);
}

uint32_t decodeUnicodeScalar(uint32_t packedOrScalar) {
    if (packedOrScalar <= 0xFFFF) {
        if (packedOrScalar >= 0xD800 && packedOrScalar <= 0xDFFF) return 0;
        return packedOrScalar;
    }
    if (packedOrScalar <= 0x10FFFF) return packedOrScalar;
    uint32_t high = packedOrScalar >> 16;
    uint32_t low = packedOrScalar & 0xFFFF;
    if (high < 0xD800 || high > 0xDBFF || low < 0xDC00 || low > 0xDFFF) return 0;
    return 0x10000 + ((high - 0xD800) << 10) + (low - 0xDC00);
}

std::vector<uint32_t> decodeUtf16BeHex(std::string_view digits) {
    std::vector<uint32_t> scalars;
    if (digits.empty()) return scalars;
    for (unsigned char c : digits) {
        if (std::isxdigit(c) == 0) return scalars;
    }
    std::string padded(digits);
    if (padded.size() % 2 != 0) padded += '0';
    if (padded.size() < 4 || padded.size() % 4 != 0) {
        // 1-byte destination (rare single-byte bfchar e.g. <41>): pad high byte.
        if (padded.size() == 2) {
            uint32_t v = static_cast<uint32_t>(std::strtoul(padded.c_str(), nullptr, 16));
            if (v > 0 && !(v >= 0xD800 && v <= 0xDFFF)) scalars.push_back(v);
            return scalars;
        }
        return {};
    }
    std::vector<uint16_t> units;
    units.reserve(padded.size() / 4);
    for (size_t i = 0; i < padded.size(); i += 4) {
        char unitBuf[5]{padded[i], padded[i + 1], padded[i + 2], padded[i + 3], '\0'};
        units.push_back(static_cast<uint16_t>(std::strtoul(unitBuf, nullptr, 16)));
    }
    size_t i = 0;
    while (i < units.size()) {
        uint16_t u = units[i];
        if (u >= 0xD800 && u <= 0xDBFF) {
            if (i + 1 >= units.size() || units[i + 1] < 0xDC00 || units[i + 1] > 0xDFFF) {
                return {};
            }
            uint32_t scalar =
                0x10000U + ((static_cast<uint32_t>(u - 0xD800) << 10) | (units[i + 1] - 0xDC00));
            scalars.push_back(scalar);
            i += 2;
        } else if (u >= 0xDC00 && u <= 0xDFFF) {
            return {};
        } else {
            scalars.push_back(u);
            ++i;
        }
    }
    return scalars;
}

void appendUtf16BeHex(std::string& out, uint32_t codepoint) {
    char buf[16];
    if (codepoint <= 0xFFFFU) {
        std::snprintf(buf, sizeof(buf), "%04X", codepoint);
        out += buf;
    } else {
        uint32_t v = codepoint - 0x10000U;
        uint32_t hi = 0xD800U + (v >> 10);
        uint32_t lo = 0xDC00U + (v & 0x3FFU);
        std::snprintf(buf, sizeof(buf), "%04X%04X", hi, lo);
        out += buf;
    }
}

void appendUtf16BeHex(std::string& out, std::vector<uint32_t> const& codepoints) {
    for (uint32_t cp : codepoints) appendUtf16BeHex(out, cp);
}

std::string stripCmapComments(std::string_view in) {
    std::string out;
    out.reserve(in.size());
    int hexDepth = 0;
    int parens = 0;
    bool escaped = false;
    size_t i = 0;
    while (i < in.size()) {
        char c = in[i];
        if (parens > 0) {
            if (escaped) {
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '(') {
                ++parens;
            } else if (c == ')') {
                --parens;
            }
            out += c;
            ++i;
            continue;
        }
        if (hexDepth > 0) {
            if (c == '>') --hexDepth;
            out += c;
            ++i;
            continue;
        }
        if (c == '%') {
            while (i + 1 < in.size() && in[i + 1] != '\n' && in[i + 1] != '\r') ++i;
            ++i;
            continue;
        }
        if (c == '<') {
            if (i + 1 < in.size() && in[i + 1] == '<') {
                out += "<<";
                i += 2;
                continue;
            }
            ++hexDepth;
        } else if (c == '(') {
            ++parens;
        }
        out += c;
        ++i;
    }
    return out;
}

static bool cmapSectionHasMapping(std::string_view body) {
    int hexStrings = 0;
    size_t open = 0;
    while ((open = body.find('<', open)) != std::string_view::npos) {
        size_t close = body.find('>', open + 1);
        if (close == std::string_view::npos) break;
        bool nonEmpty = false;
        for (size_t i = open + 1; i < close; ++i) {
            if (!std::isspace(static_cast<unsigned char>(body[i]))) {
                nonEmpty = true;
                break;
            }
        }
        if (nonEmpty) ++hexStrings;
        open = close + 1;
    }
    return hexStrings >= 2;
}

bool cmapHasMappings(std::string_view text) {
    for (auto const& section :
         {std::pair<const char*, const char*>("beginbfchar", "endbfchar"),
          std::pair<const char*, const char*>("beginbfrange", "endbfrange")}) {
        size_t pos = 0;
        while ((pos = text.find(section.first, pos)) != std::string_view::npos) {
            size_t end = text.find(section.second, pos);
            if (end == std::string_view::npos) break;
            if (cmapSectionHasMapping(text.substr(pos, end - pos))) return true;
            pos = end + 1;
        }
    }
    return false;
}

static bool validScalar(uint32_t u) {
    return u != 0 && u != 0xFEFFU && u != 0xFFFEU && !(u >= 0xD800U && u <= 0xDFFFU) &&
           u <= 0x10FFFFU;
}

std::string buildToUnicodeCmap(std::map<uint32_t, std::vector<uint32_t>> const& mapping,
                               bool twoByte, bool rksj, bool gbOrKsc) {
    std::vector<std::pair<uint32_t, std::vector<uint32_t>>> valid;
    valid.reserve(mapping.size());
    for (auto const& [code, seq] : mapping) {
        if (code > (twoByte ? 0xFFFFU : 0xFFU) || seq.empty()) continue;
        bool ok = true;
        for (uint32_t u : seq) {
            if (!validScalar(u)) {
                ok = false;
                break;
            }
        }
        if (ok) valid.emplace_back(code, seq);
    }
    if (valid.empty()) return {};

    // Partition into bfrange contiguous BMP runs vs single/ligature bfchar entries.
    // Adobe TechNote 5411 requires source high byte to stay constant within a
    // beginbfrange entry, and destination low byte not to wrap across 0xD800..0xDFFF.
    struct RangeEntry {
        uint32_t low;
        uint32_t high;
        uint32_t startU;
    };
    std::vector<RangeEntry> ranges;
    std::vector<std::pair<uint32_t, std::vector<uint32_t>>> chars;

    for (size_t i = 0; i < valid.size();) {
        bool singleBmp = valid[i].second.size() == 1 && valid[i].second[0] <= 0xFFFFU;
        if (!singleBmp) {
            chars.push_back(valid[i]);
            ++i;
            continue;
        }
        size_t j = i + 1;
        uint32_t startCode = valid[i].first;
        uint32_t startU = valid[i].second[0];
        while (j < valid.size() && valid[j].second.size() == 1 && valid[j].second[0] <= 0xFFFFU &&
               valid[j].first == valid[j - 1].first + 1 &&
               valid[j].second[0] == valid[j - 1].second[0] + 1 &&
               (valid[j].first >> 8) == (startCode >> 8) &&
               (valid[j].second[0] >> 8) == (startU >> 8)) {
            ++j;
        }
        if (j - i >= 2) {
            ranges.push_back({startCode, valid[j - 1].first, startU});
            i = j;
        } else {
            chars.push_back(valid[i]);
            ++i;
        }
    }

    std::string cmap =
        "/CIDInit /ProcSet findresource begin\n"
        "12 dict begin\n"
        "begincmap\n"
        "/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n"
        "/CMapName /Adobe-Identity-UCS def\n"
        "/CMapType 2 def\n";
    if (rksj) {
        cmap +=
            "4 begincodespacerange\n<00> <80>\n<A0> <DF>\n<8140> <9FFC>\n<E040> <FCFC>\n"
            "endcodespacerange\n";
    } else if (gbOrKsc) {
        cmap += "2 begincodespacerange\n<00> <80>\n<A1A1> <FEFE>\nendcodespacerange\n";
    } else if (twoByte) {
        cmap += "1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n";
    } else {
        cmap += "1 begincodespacerange\n<00> <FF>\nendcodespacerange\n";
    }

    auto formatCodeHex = [&](uint32_t code) -> std::string {
        char buf[16];
        if (!twoByte || ((rksj || gbOrKsc) && code <= 0xFFU)) {
            std::snprintf(buf, sizeof(buf), "<%02X>", code);
        } else {
            std::snprintf(buf, sizeof(buf), "<%04X>", code);
        }
        return buf;
    };

    for (size_t off = 0; off < ranges.size(); off += 100) {
        size_t chunk = std::min<size_t>(100, ranges.size() - off);
        cmap += std::to_string(chunk) + " beginbfrange\n";
        for (size_t k = 0; k < chunk; ++k) {
            auto const& r = ranges[off + k];
            cmap += formatCodeHex(r.low) + " " + formatCodeHex(r.high) + " <";
            appendUtf16BeHex(cmap, r.startU);
            cmap += ">\n";
        }
        cmap += "endbfrange\n";
    }

    for (size_t off = 0; off < chars.size(); off += 100) {
        size_t chunk = std::min<size_t>(100, chars.size() - off);
        cmap += std::to_string(chunk) + " beginbfchar\n";
        for (size_t k = 0; k < chunk; ++k) {
            auto const& entry = chars[off + k];
            cmap += formatCodeHex(entry.first) + " <";
            appendUtf16BeHex(cmap, entry.second);
            cmap += ">\n";
        }
        cmap += "endbfchar\n";
    }

    cmap += "endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n";
    return cmap;
}

std::string buildToUnicodeCmap(std::map<uint32_t, uint32_t> const& mapping, bool twoByte, bool rksj,
                               bool gbOrKsc) {
    std::map<uint32_t, std::vector<uint32_t>> widened;
    for (auto const& [code, u] : mapping) widened.emplace(code, std::vector<uint32_t>{u});
    return buildToUnicodeCmap(widened, twoByte, rksj, gbOrKsc);
}

namespace {

struct ToUnicodeTokenReader {
    std::string const& text;

    void skipSpace(size_t& pos) const {
        while (pos < text.size()) {
            unsigned char c = static_cast<unsigned char>(text[pos]);
            if (std::isspace(c) != 0) {
                ++pos;
            } else if (c == '%') {
                while (pos < text.size() && text[pos] != '\n' && text[pos] != '\r') ++pos;
            } else {
                break;
            }
        }
    }

    bool readHexDigits(size_t& pos, std::string& outDigits, size_t maxHexChars) const {
        if (pos >= text.size() || text[pos] != '<') return false;
        ++pos;
        outDigits.clear();
        while (pos < text.size() && text[pos] != '>') {
            unsigned char c = static_cast<unsigned char>(text[pos++]);
            if (std::isspace(c) != 0) continue;
            if (std::isxdigit(c) == 0 || outDigits.size() >= maxHexChars) return false;
            outDigits += static_cast<char>(c);
        }
        if (pos >= text.size() || outDigits.empty()) return false;
        ++pos;
        return true;
    }

    bool readCodeHex(size_t& pos, uint32_t& code) const {
        std::string digits;
        if (!readHexDigits(pos, digits, 4)) return false;
        if (digits.size() % 2 != 0) digits += '0';
        code = static_cast<uint32_t>(std::strtoul(digits.c_str(), nullptr, 16));
        return code <= kMaxCid;
    }

    bool readDestScalars(size_t& pos, std::vector<uint32_t>& scalars,
                         uint32_t* singleRawForRange = nullptr, size_t* rawBytes = nullptr) const {
        std::string digits;
        // Support multi-codepoint ligatures (e.g. <006600660069> = "ffi", up to 64 hex chars).
        if (!readHexDigits(pos, digits, 64)) return false;
        scalars = decodeUtf16BeHex(digits);
        if (scalars.empty()) return false;
        if (singleRawForRange && rawBytes) {
            std::string padded(digits);
            if (padded.size() % 2 != 0) padded += '0';
            *rawBytes = padded.size() / 2;
            if (padded.size() <= 8) {
                *singleRawForRange =
                    static_cast<uint32_t>(std::strtoul(padded.c_str(), nullptr, 16));
            } else {
                *singleRawForRange = 0;
            }
        }
        return true;
    }

    bool readDecimalUint(size_t& pos, uint32_t& value) const {
        size_t start = pos;
        while (pos < text.size() && std::isdigit(static_cast<unsigned char>(text[pos])) != 0) {
            ++pos;
        }
        if (pos == start) return false;
        unsigned long long parsed =
            std::strtoull(text.substr(start, pos - start).c_str(), nullptr, 10);
        if (parsed > kMaxCid) return false;
        value = static_cast<uint32_t>(parsed);
        return true;
    }

    bool parseBfChars(size_t pos, size_t end,
                      std::map<uint32_t, std::vector<uint32_t>>& result) const {
        while (pos < end) {
            skipSpace(pos);
            if (pos >= end) break;
            uint32_t code = 0;
            std::vector<uint32_t> dest;
            if (!readCodeHex(pos, code)) return false;
            skipSpace(pos);
            if (!readDestScalars(pos, dest)) return false;
            result[code] = std::move(dest);
        }
        return true;
    }

    bool parseBfRanges(size_t pos, size_t end,
                       std::map<uint32_t, std::vector<uint32_t>>& result) const {
        while (pos < end) {
            skipSpace(pos);
            if (pos >= end) break;
            uint32_t low = 0, high = 0;
            if (!readCodeHex(pos, low)) return false;
            skipSpace(pos);
            if (!readCodeHex(pos, high) || high < low) return false;
            skipSpace(pos);
            if (pos < end && text[pos] == '[') {
                ++pos;
                uint64_t expected = static_cast<uint64_t>(high) - low + 1;
                for (uint64_t i = 0; i < expected; ++i) {
                    skipSpace(pos);
                    std::vector<uint32_t> dest;
                    if (!readDestScalars(pos, dest)) return false;
                    result[low + static_cast<uint32_t>(i)] = std::move(dest);
                }
                skipSpace(pos);
                if (pos >= end || text[pos] != ']') return false;
                ++pos;
            } else {
                std::vector<uint32_t> baseScalars;
                uint32_t rawVal = 0;
                size_t rawBytes = 0;
                if (!readDestScalars(pos, baseScalars, &rawVal, &rawBytes)) return false;
                if (baseScalars.size() != 1) return false;
                // Per Adobe TN #5411, UTF-16BE surrogate pair destinations only increment the low
                // byte.
                if (rawBytes == 4) {
                    if ((rawVal & 0xFFU) + (high - low) > 0xFFU) return false;
                    for (uint32_t code = low; code <= high; ++code) {
                        uint32_t decoded = decodeUnicodeScalar(rawVal + (code - low));
                        if (!decoded) return false;
                        result[code] = {decoded};
                    }
                } else {
                    uint64_t base = baseScalars[0];
                    for (uint32_t code = low; code <= high; ++code) {
                        uint64_t v = base + (code - low);
                        if (v > 0x10FFFFU || (v >= 0xD800U && v <= 0xDFFFU)) return false;
                        result[code] = {static_cast<uint32_t>(v)};
                    }
                }
            }
        }
        return true;
    }

    void parseCidFallback(std::map<uint32_t, std::vector<uint32_t>>& result) const {
        auto validScalar = [](uint32_t cp) {
            return cp > 0 && cp <= 0x10FFFFU && !(cp >= 0xD800U && cp <= 0xDFFFU);
        };
        size_t pos = 0;
        while ((pos = text.find("begincidchar", pos)) != std::string::npos) {
            pos += std::strlen("begincidchar");
            size_t end = text.find("endcidchar", pos);
            if (end == std::string::npos) break;
            size_t cur = pos;
            while (cur < end) {
                skipSpace(cur);
                if (cur >= end) break;
                uint32_t code = 0, cid = 0;
                if (!readCodeHex(cur, code)) {
                    result.clear();
                    return;
                }
                skipSpace(cur);
                if (!readDecimalUint(cur, cid) || !validScalar(cid)) {
                    result.clear();
                    return;
                }
                result[code] = {cid};
            }
            pos = end + std::strlen("endcidchar");
        }
        pos = 0;
        while ((pos = text.find("begincidrange", pos)) != std::string::npos) {
            pos += std::strlen("begincidrange");
            size_t end = text.find("endcidrange", pos);
            if (end == std::string::npos) break;
            size_t cur = pos;
            while (cur < end) {
                skipSpace(cur);
                if (cur >= end) break;
                uint32_t low = 0, high = 0, cid = 0;
                if (!readCodeHex(cur, low)) {
                    result.clear();
                    return;
                }
                skipSpace(cur);
                if (!readCodeHex(cur, high) || high < low) {
                    result.clear();
                    return;
                }
                skipSpace(cur);
                if (!readDecimalUint(cur, cid)) {
                    result.clear();
                    return;
                }
                for (uint32_t code = low; code <= high; ++code) {
                    uint32_t v = cid + (code - low);
                    if (validScalar(v)) result[code] = {v};
                }
            }
            pos = end + std::strlen("endcidrange");
        }
    }
};

}  // namespace

std::map<uint32_t, std::vector<uint32_t>> parseToUnicodeCmap(std::string_view rawCmap,
                                                             bool allowCidFallback) {
    std::map<uint32_t, std::vector<uint32_t>> result;
    if (rawCmap.empty()) return result;
    std::string text = stripCmapComments(rawCmap);
    // Inherited mappings live outside this stream; rejecting the CMap keeps a
    // substitution from dropping inherited CIDs.
    if (text.find("usecmap") != std::string::npos) return {};
    ToUnicodeTokenReader reader{text};

    size_t pos = 0;
    while ((pos = text.find("beginbfchar", pos)) != std::string::npos) {
        pos += std::strlen("beginbfchar");
        size_t end = text.find("endbfchar", pos);
        if (end == std::string::npos || !reader.parseBfChars(pos, end, result)) return {};
        pos = end + std::strlen("endbfchar");
    }
    pos = 0;
    while ((pos = text.find("beginbfrange", pos)) != std::string::npos) {
        pos += std::strlen("beginbfrange");
        size_t end = text.find("endbfrange", pos);
        if (end == std::string::npos || !reader.parseBfRanges(pos, end, result)) return {};
        pos = end + std::strlen("endbfrange");
    }

    // Some malformed producer streams emit cidchar/cidrange sections instead of bfchar/bfrange.
    if (result.empty() && allowCidFallback) reader.parseCidFallback(result);
    return result;
}

std::string buildCidSetBits(std::set<uint32_t> const& cids) {
    if (cids.empty()) return {};
    uint32_t maxCid = *cids.rbegin();
    if (maxCid > kMaxCid) maxCid = kMaxCid;
    std::string bits(static_cast<size_t>(maxCid / 8U) + 1U, '\0');
    for (uint32_t cid : cids) {
        if (cid > kMaxCid) continue;
        size_t byteIndex = cid / 8U;
        uint8_t bitMask = static_cast<uint8_t>(0x80U >> (cid & 7U));
        bits[byteIndex] = static_cast<char>(static_cast<uint8_t>(bits[byteIndex]) | bitMask);
    }
    return bits;
}

#if defined(JPDFIUM_HAS_FREETYPE) && defined(JPDFIUM_HAS_HARFBUZZ)

FreeTypeSession::~FreeTypeSession() {
    if (lib) FT_Done_FreeType(lib);
}

bool FreeTypeSession::init() {
    if (lib) return true;
    return FT_Init_FreeType(&lib) == 0 && lib != nullptr;
}

FtFaceGuard::~FtFaceGuard() {
    if (face) FT_Done_Face(face);
}

void FtFaceGuard::reset(FT_Face f) {
    if (face) FT_Done_Face(face);
    face = f;
}

FT_UInt gidForName(FT_Face face, std::string const& name) {
    if (!face) return 0;
    if (FT_HAS_GLYPH_NAMES(face)) {
        if (FT_UInt gid = FT_Get_Name_Index(face, name.c_str())) return gid;
    }
    uint32_t u = unicodeForGlyphName(name);
    if (u && !FT_Select_Charmap(face, FT_ENCODING_UNICODE)) return FT_Get_Char_Index(face, u);
    return 0;
}

FT_UInt gidForUnicode(FT_Face face, uint32_t unicode, bool hasUnicodeCharmap) {
    if (!face || !hasUnicodeCharmap || unicode == 0) return 0;
    if (FT_Select_Charmap(face, FT_ENCODING_UNICODE) != 0) return 0;
    return FT_Get_Char_Index(face, unicode);
}

FT_UInt gidFromSymbolicCharmaps(FT_Face face, uint32_t code) {
    if (!face) return 0;
    auto tryCharmap = [&](FT_Encoding encoding) -> FT_UInt {
        if (FT_Select_Charmap(face, encoding) != 0) return 0;
        if (FT_UInt gid = FT_Get_Char_Index(face, code)) return gid;
        if (FT_UInt gid = FT_Get_Char_Index(face, 0xF000U | code)) return gid;
        return 0;
    };
    if (FT_UInt gid = tryCharmap(FT_ENCODING_MS_SYMBOL)) return gid;
    if (FT_UInt gid = tryCharmap(FT_ENCODING_APPLE_ROMAN)) return gid;
    for (int i = 0; i < face->num_charmaps; ++i) {
        FT_CharMap cmap = face->charmaps[i];
        if (!cmap || cmap->encoding == FT_ENCODING_UNICODE) continue;
        if (FT_Set_Charmap(face, cmap) != 0) continue;
        if (FT_UInt gid = FT_Get_Char_Index(face, code)) return gid;
        if (FT_UInt gid = FT_Get_Char_Index(face, 0xF000U | code)) return gid;
    }
    return 0;
}

FT_UInt gidFromBuiltInEncoding(FT_Face face, uint32_t code) {
    if (!face) return 0;
    auto tryEncoding = [&](FT_Encoding encoding) -> FT_UInt {
        if (FT_Select_Charmap(face, encoding) != 0) return 0;
        return FT_Get_Char_Index(face, code);
    };
    if (FT_UInt gid = tryEncoding(FT_ENCODING_ADOBE_CUSTOM)) return gid;
    if (FT_UInt gid = tryEncoding(FT_ENCODING_ADOBE_STANDARD)) return gid;
    return gidFromSymbolicCharmaps(face, code);
}

uint32_t unicodeFromGlyphNameOnly(FT_Face face, FT_UInt gid) {
    if (!face || !gid || !FT_HAS_GLYPH_NAMES(face)) return 0;
    char name[256];
    if (FT_Get_Glyph_Name(face, gid, name, sizeof(name)) != 0 || !name[0]) return 0;
    return unicodeForGlyphName(name);
}

uint32_t unicodeFromGid(FT_Face face, FT_UInt gid,
                        std::map<uint32_t, uint32_t> const& gidToUnicode) {
    if (!gid) return 0;
    auto found = gidToUnicode.find(gid);
    if (found != gidToUnicode.end()) return found->second;
    return unicodeFromGlyphNameOnly(face, gid);
}

FT_UInt glyphForSimpleCode(FT_Face face, int code, bool symbolic, SimpleEncoding const& enc) {
    if (auto it = enc.differences.find(code); it != enc.differences.end()) {
        FT_UInt gid = it->second == ".notdef" ? 0 : gidForName(face, it->second);
        if (gid || !symbolic) return gid;
    } else if (uint32_t u = symbolic ? 0 : baseEncodingUnicode(enc.base, code)) {
        if (!FT_Select_Charmap(face, FT_ENCODING_UNICODE)) {
            if (FT_UInt gid = FT_Get_Char_Index(face, u)) return gid;
        }
    }
    if (symbolic) {
        return gidFromSymbolicCharmaps(face, static_cast<uint32_t>(code));
    }
    return 0;
}

std::vector<SimpleGlyph> resolveSimpleChars(FT_Face face, bool hasUnicodeCharmap,
                                            std::map<uint32_t, uint32_t> const& gidToUnicode,
                                            SimpleEncoding const& enc, bool isType1Family) {
    std::vector<SimpleGlyph> resolved(256);
    const bool builtinEncoding = isType1Family && !enc.hasBaseEncoding;
    const bool nonSymbolicWithBase = !enc.symbolic && (enc.hasBaseEncoding || !isType1Family);
    for (uint32_t code = 0; code <= 0xFF; ++code) {
        SimpleGlyph entry;
        auto diff = enc.differences.find(static_cast<int>(code));
        if (diff != enc.differences.end()) {
            if (diff->second != ".notdef") {
                entry.unicode = unicodeForGlyphName(diff->second);
                entry.gid = gidForName(face, diff->second);
                if (entry.gid != 0 && entry.unicode == 0)
                    entry.unicode = unicodeFromGid(face, entry.gid, gidToUnicode);
            }
        } else if (builtinEncoding) {
            entry.gid = gidFromBuiltInEncoding(face, code);
            if (entry.gid != 0) entry.unicode = unicodeFromGid(face, entry.gid, gidToUnicode);
        } else if (!enc.symbolic || enc.hasExplicitEncoding) {
            entry.unicode = baseEncodingUnicode(enc.base, static_cast<int>(code));
            entry.gid = gidForUnicode(face, entry.unicode, hasUnicodeCharmap);
            if (entry.gid == 0 && nonSymbolicWithBase) {
                uint32_t fallback = entry.unicode == 0xA0 ? 0x20 : entry.unicode == 0xAD ? 0x2D : 0;
                if (fallback) entry.gid = gidForUnicode(face, fallback, hasUnicodeCharmap);
            } else if (entry.gid == 0) {
                entry.gid = gidFromSymbolicCharmaps(face, code);
            }
        } else {
            entry.gid = gidFromSymbolicCharmaps(face, code);
            if (entry.gid != 0) entry.unicode = unicodeFromGid(face, entry.gid, gidToUnicode);
        }
        resolved[code] = entry;
    }
    return resolved;
}

int unicodePreference(FT_ULong u, bool symbolic) {
    if (u >= 0xE000 && u <= 0xF8FF) return 0;
    if (!symbolic) return 1;
    if (u >= 0x2700 && u < 0x27C0) return 4;
    return u < 0x80 ? 1 : (u >= 0x2000 && u < 0x2C00 ? 3 : 2);
}

std::map<FT_UInt, FT_ULong> unicodeByGlyph(FT_Face face, bool symbolic) {
    std::map<FT_UInt, FT_ULong> unicodeOf;
    if (FT_Select_Charmap(face, FT_ENCODING_UNICODE)) return unicodeOf;
    FT_UInt gid = 0;
    for (FT_ULong u = FT_Get_First_Char(face, &gid); gid; u = FT_Get_Next_Char(face, u, &gid)) {
        auto it = unicodeOf.try_emplace(gid, u).first;
        if (unicodePreference(u, symbolic) > unicodePreference(it->second, symbolic))
            it->second = u;
    }
    return unicodeOf;
}

bool pdfWidthFromGlyph(FT_Face face, FT_UInt gid, int& outWidth) {
    if (!face || gid == 0 || face->units_per_EM == 0) return false;
    FT_Fixed advance = 0;
    if (FT_Get_Advance(face, gid, FT_LOAD_NO_SCALE, &advance) != 0) return false;
    const long long units = face->units_per_EM;
    outWidth = static_cast<int>((static_cast<long long>(advance) * 1000 + units / 2) / units);
    return true;
}

bool pdfVerticalMetricsFromGlyph(FT_Face face, FT_UInt gid, int& outW1y, int& outVx, int& outVy) {
    if (!face || !FT_HAS_VERTICAL(face) || face->units_per_EM == 0) return false;
    if (FT_Load_Glyph(face, gid, FT_LOAD_NO_SCALE | FT_LOAD_VERTICAL_LAYOUT) != 0) return false;
    const auto& m = face->glyph->metrics;
    const long long units = face->units_per_EM;
    auto scaleRounded = [&](FT_Pos v) -> int {
        if (v >= 0) return static_cast<int>((static_cast<long long>(v) * 1000 + units / 2) / units);
        return -static_cast<int>((static_cast<long long>(-v) * 1000 + units / 2) / units);
    };
    outW1y = -scaleRounded(m.vertAdvance);
    outVx = scaleRounded(m.horiBearingX - m.vertBearingX);
    outVy = scaleRounded(m.horiBearingY + m.vertBearingY);
    return true;
}

bool fontForbidsSubsetting(FT_Face face) {
    FT_ULong length = 0;
    if (FT_Load_Sfnt_Table(face, FT_MAKE_TAG('O', 'S', '/', '2'), 0, nullptr, &length) ||
        length < 10)
        return false;
    std::vector<uint8_t> os2(length);
    FT_ULong loaded = length;
    if (FT_Load_Sfnt_Table(face, FT_MAKE_TAG('O', 'S', '/', '2'), 0, os2.data(), &loaded) ||
        loaded < 10)
        return false;
    const uint16_t fsType = readU16BE(os2.data() + 8);
    // 0x0002 Restricted License embedding, 0x0100 No subsetting. The Java
    // embedder already refuses 0x0002; subsetting an already-embedded program
    // is also a modification, so the normalizer must refuse it too.
    return (fsType & (0x0002U | 0x0100U)) != 0;
}

bool fontHasSfntTable(FT_Face face, FT_ULong tag) {
    FT_ULong length = 0;
    return FT_Load_Sfnt_Table(face, tag, 0, nullptr, &length) == 0 && length > 0;
}

FT_Long selectCollectionFace(FT_Library lib, const uint8_t* data, size_t size,
                             std::set<std::string> const& desiredKeys) {
    if (!data || size < 12 || std::memcmp(data, "ttcf", 4) != 0 || desiredKeys.empty()) return 0;
    FT_Face probe = nullptr;
    if (FT_New_Memory_Face(lib, reinterpret_cast<const FT_Byte*>(data), static_cast<FT_Long>(size),
                           0, &probe) != 0 ||
        !probe)
        return 0;
    const FT_Long numFaces = probe->num_faces;
    FT_Done_Face(probe);
    if (numFaces <= 1) return 0;
    for (FT_Long i = 1; i < numFaces; ++i) {
        FT_Face candidate = nullptr;
        if (FT_New_Memory_Face(lib, reinterpret_cast<const FT_Byte*>(data),
                               static_cast<FT_Long>(size), i, &candidate) != 0 ||
            !candidate)
            continue;
        bool matched = false;
        if (const char* ps = FT_Get_Postscript_Name(candidate))
            matched = desiredKeys.contains(normalizeFontNameKey(ps));
        if (!matched && candidate->family_name)
            matched = desiredKeys.contains(normalizeFontNameKey(candidate->family_name));
        FT_Done_Face(candidate);
        if (matched) return i;
    }
    return 0;
}

FT_Face loadFontFace(FT_Library lib, std::string const& bytes, FT_Long preferredIndex) {
    if (!lib || bytes.empty()) return nullptr;
    FT_Face face = nullptr;
    if (FT_New_Memory_Face(lib, reinterpret_cast<const FT_Byte*>(bytes.data()),
                           static_cast<FT_Long>(bytes.size()), preferredIndex, &face) == 0 &&
        face)
        return face;
    if (preferredIndex != 0 &&
        FT_New_Memory_Face(lib, reinterpret_cast<const FT_Byte*>(bytes.data()),
                           static_cast<FT_Long>(bytes.size()), 0, &face) == 0 &&
        face)
        return face;
    return nullptr;
}

void addDefaultSubsetDropTags(hb_subset_input_t* input) {
    hb_set_t* dropTables = hb_subset_input_set(input, HB_SUBSET_SETS_DROP_TABLE_TAG);
    for (hb_tag_t tag :
         {HB_TAG('G', 'S', 'U', 'B'), HB_TAG('G', 'P', 'O', 'S'), HB_TAG('G', 'D', 'E', 'F'),
          HB_TAG('B', 'A', 'S', 'E'), HB_TAG('J', 'S', 'T', 'F'), HB_TAG('M', 'A', 'T', 'H'),
          HB_TAG('E', 'B', 'D', 'T'), HB_TAG('E', 'B', 'L', 'C'), HB_TAG('E', 'B', 'S', 'C'),
          HB_TAG('C', 'B', 'D', 'T'), HB_TAG('C', 'B', 'L', 'C'), HB_TAG('s', 'b', 'i', 'x'),
          HB_TAG('S', 'V', 'G', ' '), HB_TAG('h', 'd', 'm', 'x'), HB_TAG('V', 'D', 'M', 'X'),
          HB_TAG('L', 'T', 'S', 'H'), HB_TAG('P', 'C', 'L', 'T'), HB_TAG('D', 'S', 'I', 'G'),
          HB_TAG('k', 'e', 'r', 'n'), HB_TAG('m', 'e', 't', 'a'), HB_TAG('S', 'T', 'A', 'T')}) {
        hb_set_add(dropTables, tag);
    }
}

SubsetResult subsetSfntProgram(FT_Face face, const uint8_t* data, size_t size, FT_Long faceIndex,
                               std::set<FT_UInt> const& glyphs, bool retainGids,
                               bool keepGlyphNames) {
    SubsetResult result;
    if (!face || !data || size == 0 || size > std::numeric_limits<unsigned int>::max()) {
        return result;
    }
    std::string sfntCopy(reinterpret_cast<const char*>(data), size);
    sanitizeSfntForSubset(sfntCopy);
    ensureUnicodeMicrosoftCmap(sfntCopy);

    hb_blob_t* blob = hb_blob_create(sfntCopy.data(), static_cast<unsigned int>(sfntCopy.size()),
                                     HB_MEMORY_MODE_READONLY, nullptr, nullptr);
    hb_face_t* hbFace = hb_face_create(blob, static_cast<unsigned int>(faceIndex));
    hb_blob_destroy(blob);
    if (!hbFace) return result;

    if (hb_subset_input_t* input = hb_subset_input_create_or_fail()) {
        hb_set_t* gset = hb_subset_input_glyph_set(input);
        for (FT_UInt g : glyphs) hb_set_add(gset, g);

        FT_UInt gid = 0;
        if (!FT_Select_Charmap(face, FT_ENCODING_UNICODE)) {
            hb_set_t* uset = hb_subset_input_unicode_set(input);
            for (FT_ULong u = FT_Get_First_Char(face, &gid); gid;
                 u = FT_Get_Next_Char(face, u, &gid)) {
                if (glyphs.contains(gid)) hb_set_add(uset, static_cast<hb_codepoint_t>(u));
            }
        }
        addDefaultSubsetDropTags(input);

        unsigned int flags = HB_SUBSET_FLAGS_NOTDEF_OUTLINE;
        if (keepGlyphNames) flags |= HB_SUBSET_FLAGS_GLYPH_NAMES;
        if (retainGids) flags |= HB_SUBSET_FLAGS_RETAIN_GIDS;
        hb_subset_input_set_flags(input, flags);

#ifdef HB_VERSION_MAJOR
#if HB_VERSION_MAJOR >= 4
        if (hb_subset_plan_t* plan = hb_subset_plan_create_or_fail(hbFace, input)) {
            hb_map_t* mapping = hb_subset_plan_old_to_new_glyph_mapping(plan);
            if (mapping) {
                for (FT_UInt oldGid : glyphs) {
                    hb_codepoint_t newGid = hb_map_get(mapping, oldGid);
                    if (newGid != HB_MAP_VALUE_INVALID) result.oldToNewGid[oldGid] = newGid;
                }
            }
            if (hb_face_t* subset = hb_subset_plan_execute_or_fail(plan)) {
                hb_blob_t* outBlob = hb_face_reference_blob(subset);
                unsigned int length = 0;
                const char* outBytes = hb_blob_get_data(outBlob, &length);
                if (outBytes && length > 0) result.bytes.assign(outBytes, length);
                hb_blob_destroy(outBlob);
                hb_face_destroy(subset);
            }
            hb_subset_plan_destroy(plan);
        }
#else
        if (hb_face_t* subset = hb_subset_or_fail(hbFace, input)) {
            hb_blob_t* outBlob = hb_face_reference_blob(subset);
            unsigned int length = 0;
            const char* outBytes = hb_blob_get_data(outBlob, &length);
            if (outBytes && length > 0) result.bytes.assign(outBytes, length);
            hb_blob_destroy(outBlob);
            hb_face_destroy(subset);
        }
        for (FT_UInt g : glyphs) result.oldToNewGid[g] = g;
#endif
#else
        if (hb_face_t* subset = hb_subset_or_fail(hbFace, input)) {
            hb_blob_t* outBlob = hb_face_reference_blob(subset);
            unsigned int length = 0;
            const char* outBytes = hb_blob_get_data(outBlob, &length);
            if (outBytes && length > 0) result.bytes.assign(outBytes, length);
            hb_blob_destroy(outBlob);
            hb_face_destroy(subset);
        }
        for (FT_UInt g : glyphs) result.oldToNewGid[g] = g;
#endif
        hb_subset_input_destroy(input);
    }

    hb_face_destroy(hbFace);
    return result;
}

std::string extractCffTableFromSfnt(FT_Library lib, std::string const& sfntBytes) {
    FT_Face subsetFace = loadFontFace(lib, sfntBytes, 0);
    if (!subsetFace) return {};
    FtFaceGuard guard(subsetFace);
    FT_ULong cffLen = 0;
    if (FT_Load_Sfnt_Table(subsetFace, FT_MAKE_TAG('C', 'F', 'F', ' '), 0, nullptr, &cffLen) ||
        cffLen == 0) {
        return {};
    }
    std::string cff(cffLen, '\0');
    FT_ULong readLen = cffLen;
    if (FT_Load_Sfnt_Table(subsetFace, FT_MAKE_TAG('C', 'F', 'F', ' '), 0,
                           reinterpret_cast<FT_Byte*>(cff.data()), &readLen) ||
        readLen != cffLen) {
        return {};
    }
    return cff;
}

namespace {

void writeI16BE(uint8_t* p, int16_t v) {
    writeU16BE(p, static_cast<uint16_t>(v));
}

std::string buildFormat4CmapTable(uint16_t numGlyphs,
                                  std::map<FT_UInt, uint32_t> const* gidToUnicode) {
    if (!gidToUnicode || gidToUnicode->empty()) return {};
    std::map<uint16_t, uint16_t> bmpToGid;
    for (auto const& [gid, cp] : *gidToUnicode) {
        if (cp > 0 && cp <= 0xFFFEU && gid < numGlyphs) {
            bmpToGid.try_emplace(static_cast<uint16_t>(cp), static_cast<uint16_t>(gid));
        }
    }
    if (bmpToGid.empty()) return {};

    struct Seg {
        uint16_t start, end, startGid;
    };
    std::vector<Seg> segs;
    for (auto const& [u, gid] : bmpToGid) {
        if (!segs.empty() && u == segs.back().end + 1 &&
            static_cast<uint16_t>(segs.back().startGid + (u - segs.back().start)) == gid) {
            segs.back().end = u;
        } else {
            segs.push_back({u, u, gid});
        }
    }
    segs.push_back({0xFFFFU, 0xFFFFU, 0});

    const size_t segCount = segs.size();
    const size_t fmt4Len = 16 + segCount * 8;
    std::string cmapTable(12 + fmt4Len, '\0');
    auto* cm = reinterpret_cast<uint8_t*>(cmapTable.data());
    writeU16BE(cm + 0, 0);
    writeU16BE(cm + 2, 1);
    writeU16BE(cm + 4, 3);
    writeU16BE(cm + 6, 1);
    writeU32BE(cm + 8, 12);

    uint8_t* f4 = cm + 12;
    writeU16BE(f4 + 0, 4);
    writeU16BE(f4 + 2, static_cast<uint16_t>(fmt4Len));
    writeU16BE(f4 + 4, 0);
    writeU16BE(f4 + 6, static_cast<uint16_t>(segCount * 2));
    uint16_t sr = 1;
    uint16_t es = 0;
    while (static_cast<size_t>(sr) * 2 <= segCount) {
        sr *= 2;
        ++es;
    }
    writeU16BE(f4 + 8, static_cast<uint16_t>(static_cast<size_t>(sr) * 2));
    writeU16BE(f4 + 10, es);
    writeU16BE(f4 + 12, static_cast<uint16_t>(segCount * 2 - static_cast<size_t>(sr) * 2));

    const size_t endOff = 14;
    const size_t startOff = endOff + segCount * 2 + 2;
    const size_t deltaOff = startOff + segCount * 2;
    const size_t rangeOff = deltaOff + segCount * 2;
    for (size_t i = 0; i < segCount; ++i) {
        writeU16BE(f4 + endOff + i * 2, segs[i].end);
        writeU16BE(f4 + startOff + i * 2, segs[i].start);
        int32_t delta = (segs[i].start == 0xFFFFU) ? 1
                                                   : static_cast<int32_t>(segs[i].startGid) -
                                                         static_cast<int32_t>(segs[i].start);
        writeI16BE(f4 + deltaOff + i * 2, static_cast<int16_t>(delta & 0xFFFF));
        writeU16BE(f4 + rangeOff + i * 2, 0);
    }
    return cmapTable;
}

}  // namespace

std::string wrapCffInSfnt(FT_Face face, std::string_view cffBytes,
                          std::map<FT_UInt, uint32_t> const* gidToUnicode) {
    if (!face || cffBytes.empty() || face->num_glyphs <= 0 || face->num_glyphs > 65535) return {};
    const uint16_t numGlyphs = static_cast<uint16_t>(face->num_glyphs);
    const uint16_t upem = face->units_per_EM ? static_cast<uint16_t>(face->units_per_EM) : 1000;

    // Build sorted Format 4 BMP cmap if Unicode mappings exist so HarfBuzz can subset by GID.
    std::string cmapTable = buildFormat4CmapTable(numGlyphs, gidToUnicode);

    // Minimal tables in strictly ascending tag order: CFF , (cmap), head, hhea, hmtx, maxp, name,
    // post. OpenType requires sorted tags and 4-byte-aligned table offsets with checksums.
    std::string head(54, '\0');
    {
        auto* p = reinterpret_cast<uint8_t*>(head.data());
        writeU32BE(p + 0, 0x00010000U);
        writeU32BE(p + 4, 0x00010000U);
        writeU32BE(p + 12, 0x5F0F3CF5U);  // magicNumber required by OpenType head spec
        writeU16BE(p + 18, upem);
        writeI16BE(p + 36, static_cast<int16_t>(face->bbox.xMin));
        writeI16BE(p + 38, static_cast<int16_t>(face->bbox.yMin));
        writeI16BE(p + 40, static_cast<int16_t>(face->bbox.xMax));
        writeI16BE(p + 42, static_cast<int16_t>(face->bbox.yMax));
    }

    std::string hhea(36, '\0');
    {
        auto* p = reinterpret_cast<uint8_t*>(hhea.data());
        writeU32BE(p + 0, 0x00010000U);
        writeI16BE(p + 4, static_cast<int16_t>(face->ascender));
        writeI16BE(p + 6, static_cast<int16_t>(face->descender));
        writeU16BE(p + 34, numGlyphs);
    }

    std::string hmtx(static_cast<size_t>(numGlyphs) * 4, '\0');
    {
        auto* p = reinterpret_cast<uint8_t*>(hmtx.data());
        for (uint16_t gid = 0; gid < numGlyphs; ++gid) {
            FT_Fixed adv = 0;
            uint16_t aw = 0;
            if (FT_Get_Advance(face, gid, FT_LOAD_NO_SCALE, &adv) == 0 && adv > 0) {
                aw = static_cast<uint16_t>(std::min<FT_Fixed>(adv, 65535));
            }
            writeU16BE(p + static_cast<size_t>(gid) * 4, aw);
        }
    }

    std::string maxp(6, '\0');
    {
        auto* p = reinterpret_cast<uint8_t*>(maxp.data());
        writeU32BE(p + 0, 0x00005000U);  // version 0.5 for CFF outlines
        writeU16BE(p + 4, numGlyphs);
    }

    std::string nameTable(6, '\0');
    writeU16BE(reinterpret_cast<uint8_t*>(nameTable.data()) + 4, 6);

    std::string post(32, '\0');
    writeU32BE(reinterpret_cast<uint8_t*>(post.data()), 0x00030000U);

    struct Table {
        const char* tag = nullptr;
        std::string data;
    };
    std::vector<Table> tables;
    tables.push_back({"CFF ", std::string(cffBytes)});
    if (!cmapTable.empty()) tables.push_back({"cmap", std::move(cmapTable)});
    tables.push_back({"head", std::move(head)});
    tables.push_back({"hhea", std::move(hhea)});
    tables.push_back({"hmtx", std::move(hmtx)});
    tables.push_back({"maxp", std::move(maxp)});
    tables.push_back({"name", std::move(nameTable)});
    tables.push_back({"post", std::move(post)});

    const uint16_t n = static_cast<uint16_t>(tables.size());
    uint16_t sr = 1;
    uint16_t es = 0;
    while (sr * 2 <= n) {
        sr *= 2;
        ++es;
    }

    size_t offset = 12 + static_cast<size_t>(n) * 16;
    std::string sfnt(offset, '\0');
    auto* hdr = reinterpret_cast<uint8_t*>(sfnt.data());
    static constexpr uint8_t kOttoTag[4] = {'O', 'T', 'T', 'O'};
    std::memcpy(hdr, kOttoTag, sizeof(kOttoTag));
    writeU16BE(hdr + 4, n);
    writeU16BE(hdr + 6, static_cast<uint16_t>(sr * 16));
    writeU16BE(hdr + 8, es);
    writeU16BE(hdr + 10, static_cast<uint16_t>((n - sr) * 16));

    // Write 16-byte directory entries and append each table padded to 4-byte boundary.
    uint32_t headOff = 0;
    for (size_t i = 0; i < tables.size(); ++i) {
        const uint32_t len = static_cast<uint32_t>(tables[i].data.size());
        const uint32_t tblOff = static_cast<uint32_t>(offset);
        if (std::memcmp(tables[i].tag, "head", 4) == 0) headOff = tblOff;
        const uint32_t sum =
            calcSfntTableChecksum(reinterpret_cast<const uint8_t*>(tables[i].data.data()), len);
        uint8_t* rec = reinterpret_cast<uint8_t*>(sfnt.data()) + 12 + i * 16;
        std::memcpy(rec, tables[i].tag, 4);
        writeU32BE(rec + 4, sum);
        writeU32BE(rec + 8, tblOff);
        writeU32BE(rec + 12, len);
        sfnt.append(tables[i].data);
        size_t pad = (4 - (len % 4)) % 4;
        if (pad) sfnt.append(pad, '\0');
        offset += len + pad;
    }

    recomputeHeadChecksumAdjustment(sfnt, headOff);
    return sfnt;
}

SubsetResult subsetBareCffViaSfnt(FT_Face origFace, std::string_view cffBytes,
                                  std::set<FT_UInt> const& glyphs, bool retainGids,
                                  bool keepGlyphNames,
                                  std::map<FT_UInt, uint32_t> const* gidToUnicode) {
    SubsetResult result;
    std::string wrapped = wrapCffInSfnt(origFace, cffBytes, gidToUnicode);
    if (wrapped.empty()) return result;

    FreeTypeSession localFt;
    if (!localFt.init()) return result;
    FT_Face wrappedFace = loadFontFace(localFt.lib, wrapped, 0);
    if (!wrappedFace) return result;
    FtFaceGuard guard(wrappedFace);

    SubsetResult sfntSub =
        subsetSfntProgram(wrappedFace, reinterpret_cast<const uint8_t*>(wrapped.data()),
                          wrapped.size(), 0, glyphs, retainGids, keepGlyphNames);
    if (sfntSub.bytes.empty()) return result;

    std::string bareCff = extractCffTableFromSfnt(localFt.lib, sfntSub.bytes);
    if (bareCff.empty()) return result;
    result.bytes = std::move(bareCff);
    result.oldToNewGid = std::move(sfntSub.oldToNewGid);
    return result;
}

bool subsetKeepsAdvances(FT_Library lib, std::string const& subset, FT_Long faceIndex,
                         FT_Face original, std::set<FT_UInt> const& glyphs,
                         std::map<FT_UInt, FT_UInt> const* oldToNewGid) {
    FT_Face face = loadFontFace(lib, subset, faceIndex);
    if (!face) return false;
    FtFaceGuard guard(face);
    if (face->num_glyphs <= 0) return false;
    for (FT_UInt gid : glyphs) {
        FT_UInt newGid = gid;
        if (oldToNewGid) {
            auto it = oldToNewGid->find(gid);
            if (it == oldToNewGid->end()) return false;
            newGid = it->second;
        }
        FT_Fixed before = 0;
        FT_Fixed after = 0;
        if (FT_Get_Advance(original, gid, FT_LOAD_NO_SCALE, &before) ||
            FT_Get_Advance(face, newGid, FT_LOAD_NO_SCALE, &after) || before != after) {
            return false;
        }
    }
    return true;
}

#endif  // JPDFIUM_HAS_FREETYPE && JPDFIUM_HAS_HARFBUZZ

}  // namespace jpdfium::font

#ifdef JPDFIUM_HAS_QPDF
namespace jpdfium::qpdf_detail {

jpdfium::font::SimpleEncoding parseSimpleEncoding(QPDFObjectHandle const& font,
                                                  QPDFObjectHandle const& descriptor) {
    jpdfium::font::SimpleEncoding enc;
    QPDFObjectHandle encoding = font.getKey("/Encoding");
    QPDFObjectHandle base;
    if (encoding.isName()) {
        enc.hasExplicitEncoding = true;
        base = std::move(encoding);
    } else if (encoding.isDictionary()) {
        enc.hasExplicitEncoding = true;
        base = encoding.getKey("/BaseEncoding");
        QPDFObjectHandle differences = encoding.getKey("/Differences");
        if (differences.isArray()) {
            int code = 0;
            for (auto& item : differences.getArrayAsVector()) {
                if (item.isInteger()) {
                    const long long raw = item.getIntValue();
                    code = (raw >= 0 && raw <= 255) ? static_cast<int>(raw) : 256;
                } else if (item.isName()) {
                    if (code <= 255) enc.differences[code] = item.getName().substr(1);
                    if (code < 256) ++code;
                }
            }
        }
    }
    if (base.isName()) {
        if (base.isNameAndEquals("/WinAnsiEncoding")) {
            enc.base = BaseEncoding::WinAnsi;
            enc.hasBaseEncoding = true;
            enc.namedBase = true;
        } else if (base.isNameAndEquals("/MacRomanEncoding")) {
            enc.base = BaseEncoding::MacRoman;
            enc.hasBaseEncoding = true;
            enc.namedBase = true;
        } else if (base.isNameAndEquals("/StandardEncoding")) {
            enc.base = BaseEncoding::Standard;
            enc.hasBaseEncoding = true;
        } else {
            enc.unsupportedEncoding = true;
        }
    }
    QPDFObjectHandle flags =
        descriptor.isDictionary() ? descriptor.getKey("/Flags") : QPDFObjectHandle();
    enc.symbolic = flags.isInteger() && (flags.getIntValue() & 4) != 0;
    return enc;
}

bool hasKnownSimpleEncoding(QPDFObjectHandle const& font) {
    auto encoding = font.getKey("/Encoding");
    if (encoding.isNull()) return true;
    auto known = [](QPDFObjectHandle const& name) {
        return name.isNameAndEquals("/WinAnsiEncoding") ||
               name.isNameAndEquals("/MacRomanEncoding") ||
               name.isNameAndEquals("/StandardEncoding");
    };
    if (encoding.isName()) return known(encoding);
    if (!encoding.isDictionary()) return false;
    auto base = encoding.getKey("/BaseEncoding");
    return base.isNull() || known(base);
}

std::map<uint32_t, std::vector<uint32_t>> parseToUnicode(QPDFObjectHandle const& font) {
    auto toUnicode = font.getKey("/ToUnicode");
    if (toUnicode.isName()) {
        // /ToUnicode /Identity-H or /Identity-V in synthetic/damaged PDFs
        return {};
    }
    if (!toUnicode.isStream()) return {};
    std::shared_ptr<Buffer> data =
        readStreamBufferBounded(toUnicode, qpdf_dl_generalized, size_t{4} * 1024U * 1024U);
    if (!data || data->getSize() == 0) return {};
    std::string_view sv(reinterpret_cast<const char*>(data->getBuffer()), data->getSize());
    return jpdfium::font::parseToUnicodeCmap(sv, true);
}

std::map<uint32_t, uint32_t> parseToUnicodeSingleScalar(QPDFObjectHandle const& font) {
    auto seqMap = parseToUnicode(font);
    std::map<uint32_t, uint32_t> single;
    for (auto const& [code, seq] : seqMap) {
        if (seq.size() != 1) return {};
        single.emplace(code, seq.front());
    }
    return single;
}

bool visitStreamDepth(std::map<QPDFObjGen, int>& seenStreams, QPDFObjectHandle const& stream,
                      int depth) {
    if (!stream.isIndirect()) return true;
    auto [it, inserted] = seenStreams.emplace(stream.getObjGen(), depth);
    if (!inserted && it->second <= depth) return false;
    it->second = depth;
    return true;
}

void collectResourceFonts(QPDFObjectHandle const& resources, int depth,
                          std::set<QPDFObjGen>& seenResources, std::set<QPDFObjGen>& seenFonts,
                          std::map<QPDFObjGen, int>& seenStreams,
                          std::vector<QPDFObjectHandle>& fonts) {
    if (!resources.isDictionary() || depth > 32) return;
    if (resources.isIndirect() && !seenResources.insert(resources.getObjGen()).second) return;

    QPDFObjectHandle fontDict = resources.getKey("/Font");
    if (fontDict.isDictionary()) {
        for (auto const& name : fontDict.getKeys()) {
            QPDFObjectHandle font = fontDict.getKey(name);
            if (!font.isDictionary()) continue;
            if (font.isIndirect() && !seenFonts.insert(font.getObjGen()).second) continue;
            fonts.push_back(font);
            if (font.getKey("/Subtype").isNameAndEquals("/Type3")) {
                collectResourceFonts(font.getKey("/Resources"), depth + 1, seenResources, seenFonts,
                                     seenStreams, fonts);
                QPDFObjectHandle charProcs = font.getKey("/CharProcs");
                if (charProcs.isDictionary()) {
                    for (auto const& glyphName : charProcs.getKeys()) {
                        QPDFObjectHandle glyph = charProcs.getKey(glyphName);
                        if (!glyph.isStream() || !visitStreamDepth(seenStreams, glyph, depth + 1))
                            continue;
                        collectResourceFonts(glyph.getDict().getKey("/Resources"), depth + 1,
                                             seenResources, seenFonts, seenStreams, fonts);
                    }
                }
            }
        }
    }

    QPDFObjectHandle extGstates = resources.getKey("/ExtGState");
    if (extGstates.isDictionary()) {
        for (auto const& gsName : extGstates.getKeys()) {
            QPDFObjectHandle gs = extGstates.getKey(gsName);
            if (!gs.isDictionary()) continue;
            QPDFObjectHandle gsFonts = gs.getKey("/Font");
            if (gsFonts.isArray()) {
                const int count = gsFonts.getArrayNItems();
                for (int i = 0; i < count; ++i) {
                    QPDFObjectHandle font = gsFonts.getArrayItem(i);
                    if (!font.isDictionary()) continue;
                    if (font.isIndirect() && !seenFonts.insert(font.getObjGen()).second) continue;
                    fonts.push_back(font);
                }
            }
            QPDFObjectHandle smask = gs.getKey("/SMask");
            if (smask.isDictionary()) {
                QPDFObjectHandle group = smask.getKey("/G");
                if (group.isStream() && visitStreamDepth(seenStreams, group, depth + 1))
                    collectResourceFonts(group.getDict().getKey("/Resources"), depth + 1,
                                         seenResources, seenFonts, seenStreams, fonts);
            }
        }
    }

    QPDFObjectHandle xobjects = resources.getKey("/XObject");
    if (xobjects.isDictionary()) {
        for (auto const& name : xobjects.getKeys()) {
            QPDFObjectHandle xobject = xobjects.getKey(name);
            if (!xobject.isStream()) continue;
            if (!xobject.getDict().getKey("/Subtype").isNameAndEquals("/Form")) continue;
            if (!visitStreamDepth(seenStreams, xobject, depth + 1)) continue;
            collectResourceFonts(xobject.getDict().getKey("/Resources"), depth + 1, seenResources,
                                 seenFonts, seenStreams, fonts);
        }
    }

    QPDFObjectHandle patterns = resources.getKey("/Pattern");
    if (patterns.isDictionary()) {
        for (auto const& name : patterns.getKeys()) {
            QPDFObjectHandle pattern = patterns.getKey(name);
            if (!pattern.isStream() || !visitStreamDepth(seenStreams, pattern, depth + 1)) continue;
            collectResourceFonts(pattern.getDict().getKey("/Resources"), depth + 1, seenResources,
                                 seenFonts, seenStreams, fonts);
        }
    }
}

std::vector<QPDFObjectHandle> collectPageFonts(QPDFObjectHandle const& page,
                                               std::set<QPDFObjGen>& seenResources,
                                               std::set<QPDFObjGen>& seenFonts) {
    std::vector<QPDFObjectHandle> fonts;
    std::map<QPDFObjGen, int> seenStreams;

    QPDFObjectHandle resources = page.getKey("/Resources");
    if (!resources.isDictionary()) {
        QPDFObjectHandle parent = page.getKey("/Parent");
        for (int depth = 0; depth < 32 && parent.isDictionary() && !resources.isDictionary();
             ++depth) {
            resources = parent.getKey("/Resources");
            parent = parent.getKey("/Parent");
        }
    }
    collectResourceFonts(resources, 0, seenResources, seenFonts, seenStreams, fonts);

    QPDFObjectHandle annots = page.getKey("/Annots");
    if (annots.isArray()) {
        const int annotCount = annots.getArrayNItems();
        for (int i = 0; i < annotCount; ++i) {
            QPDFObjectHandle annot = annots.getArrayItem(i);
            if (!annot.isDictionary()) continue;
            QPDFObjectHandle ap = annot.getKey("/AP");
            if (!ap.isDictionary()) continue;
            for (auto const& key : ap.getKeys()) {
                QPDFObjectHandle appearance = ap.getKey(key);
                if (appearance.isStream()) {
                    collectResourceFonts(appearance.getDict().getKey("/Resources"), 0,
                                         seenResources, seenFonts, seenStreams, fonts);
                } else if (appearance.isDictionary()) {
                    for (auto const& stateKey : appearance.getKeys()) {
                        QPDFObjectHandle stateStream = appearance.getKey(stateKey);
                        if (!stateStream.isStream()) continue;
                        collectResourceFonts(stateStream.getDict().getKey("/Resources"), 0,
                                             seenResources, seenFonts, seenStreams, fonts);
                    }
                }
            }
        }
    }
    return fonts;
}

QPDFObjectHandle buildCidWidthsArray(std::map<uint32_t, int> const& widths, int omitDefaultDw) {
    QPDFObjectHandle array = QPDFObjectHandle::newArray();
    std::map<uint32_t, int> filtered;
    for (auto const& [cid, w] : widths) {
        if (omitDefaultDw >= 0 && w == omitDefaultDw) continue;
        filtered.emplace(cid, w);
    }

    auto it = filtered.begin();
    while (it != filtered.end()) {
        // Group contiguous CIDs, splitting constant runs >= 4 into [c1 c2 w]
        // for compact /W representation (fontTools P1.2).
        const uint32_t runStart = it->first;
        std::vector<int> run;
        uint32_t expected = runStart;
        while (it != filtered.end() && it->first == expected) {
            run.push_back(it->second);
            ++it;
            ++expected;
        }
        size_t pos = 0;
        while (pos < run.size()) {
            size_t constEnd = pos + 1;
            while (constEnd < run.size() && run[constEnd] == run[pos]) ++constEnd;
            if (constEnd - pos >= 4 || (run.size() >= 2 && constEnd - pos == run.size())) {
                array.appendItem(QPDFObjectHandle::newInteger(static_cast<long long>(runStart) +
                                                              static_cast<long long>(pos)));
                array.appendItem(QPDFObjectHandle::newInteger(
                    static_cast<long long>(runStart) + static_cast<long long>(constEnd) - 1));
                array.appendItem(QPDFObjectHandle::newInteger(run[pos]));
                pos = constEnd;
                continue;
            }
            size_t listEnd = pos + 1;
            while (listEnd < run.size()) {
                size_t nextConst = listEnd + 1;
                while (nextConst < run.size() && run[nextConst] == run[listEnd]) ++nextConst;
                if (nextConst - listEnd >= 4) break;
                listEnd = nextConst;
            }
            array.appendItem(QPDFObjectHandle::newInteger(static_cast<long long>(runStart) +
                                                          static_cast<long long>(pos)));
            QPDFObjectHandle values = QPDFObjectHandle::newArray();
            for (size_t k = pos; k < listEnd; ++k)
                values.appendItem(QPDFObjectHandle::newInteger(run[k]));
            array.appendItem(values);
            pos = listEnd;
        }
    }
    return array;
}

QPDFObjectHandle buildCidVerticalWidthsArray(std::map<uint32_t, VerticalMetric> const& metrics,
                                             int defaultW1y, int defaultVx, int defaultVy) {
    QPDFObjectHandle array = QPDFObjectHandle::newArray();
    std::map<uint32_t, VerticalMetric> filtered;
    for (auto const& [cid, m] : metrics) {
        if (m.w1y == defaultW1y && m.vx == defaultVx && m.vy == defaultVy) continue;
        filtered.emplace(cid, m);
    }
    auto it = filtered.begin();
    while (it != filtered.end()) {
        const uint32_t start = it->first;
        std::vector<VerticalMetric> run;
        uint32_t expected = start;
        while (it != filtered.end() && it->first == expected) {
            run.push_back(it->second);
            ++it;
            ++expected;
        }
        bool allSame =
            !run.empty() && std::all_of(run.begin(), run.end(), [&](VerticalMetric const& m) {
                return m.w1y == run.front().w1y && m.vx == run.front().vx && m.vy == run.front().vy;
            });
        array.appendItem(QPDFObjectHandle::newInteger(start));
        if (run.size() >= 2 && allSame) {
            array.appendItem(QPDFObjectHandle::newInteger(expected - 1));
            array.appendItem(QPDFObjectHandle::newInteger(run.front().w1y));
            array.appendItem(QPDFObjectHandle::newInteger(run.front().vx));
            array.appendItem(QPDFObjectHandle::newInteger(run.front().vy));
        } else {
            QPDFObjectHandle values = QPDFObjectHandle::newArray();
            for (auto const& m : run) {
                values.appendItem(QPDFObjectHandle::newInteger(m.w1y));
                values.appendItem(QPDFObjectHandle::newInteger(m.vx));
                values.appendItem(QPDFObjectHandle::newInteger(m.vy));
            }
            array.appendItem(values);
        }
    }
    return array;
}

std::set<uint32_t> widthCids(QPDFObjectHandle const& font) {
    std::set<uint32_t> cids;
    auto widths = font.getKey("/W");
    if (!widths.isArray()) return cids;
    auto items = widths.getArrayAsVector();
    for (size_t i = 0; i + 1 < items.size();) {
        if (!items[i].isInteger()) {
            ++i;
            continue;
        }
        long long first = items[i].getIntValue();
        if (first < 0 || first > static_cast<long long>(jpdfium::font::kMaxCid)) {
            i += items[i + 1].isArray() ? 2 : 3;
            continue;
        }
        if (items[i + 1].isArray()) {
            int n = items[i + 1].getArrayNItems();
            for (int k = 0; k < n && first + k <= static_cast<long long>(jpdfium::font::kMaxCid);
                 ++k) {
                if (first + k >= 0) cids.insert(static_cast<uint32_t>(first + k));
            }
            i += 2;
        } else if (i + 2 < items.size() && items[i + 1].isInteger() && items[i + 2].isNumber()) {
            long long last = items[i + 1].getIntValue();
            if (last >= first && last - first <= static_cast<long long>(jpdfium::font::kMaxCid)) {
                for (long long c = std::max(0LL, first);
                     c <= std::min<long long>(last, jpdfium::font::kMaxCid); ++c) {
                    cids.insert(static_cast<uint32_t>(c));
                }
            }
            i += 3;
        } else {
            ++i;
        }
    }
    return cids;
}

}  // namespace jpdfium::qpdf_detail
#endif  // JPDFIUM_HAS_QPDF
