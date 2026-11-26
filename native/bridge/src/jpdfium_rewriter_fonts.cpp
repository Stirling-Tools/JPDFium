// jpdfium_rewriter_fonts.cpp - Recursive font enumeration, simple GID resolution,
// /W array codec, and CIDFont and simple font conformance rewrite stages.

#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <qpdf/QPDFPageDocumentHelper.hh>
#include <sstream>

#include "jpdfium_font_rewriter_internal.h"

namespace jpdfium::rewriter {

namespace {

// CFF DICT real numbers are nibble-encoded and terminated by 0xF. A few
// producers emit an immediate terminator ("1e ff"), which parses as an empty
// real: FreeType and FontBox tolerate it, but FontTools and veraPDF reject
// the whole Top DICT, so the font looks unembedded. Replacing the
// terminator byte with 0x0F keeps the token length identical and turns it
// into the real number 0.
bool repairCffDictEmptyReals(uint8_t* data, size_t start, size_t end) {
    bool changed = false;
    size_t i = start;
    while (i < end) {
        uint8_t c = data[i];
        if (c <= 21) {
            i += (c == 12) ? 2 : 1;
            continue;
        }
        if (c == 28) {
            i += 3;
            continue;
        }
        if (c == 29) {
            i += 5;
            continue;
        }
        if (c == 30) {
            size_t j = i + 1;
            if (j < end && (data[j] >> 4) == 0x0F) {
                data[j] = 0x0F;
                changed = true;
                i = j + 1;
                continue;
            }
            while (j < end) {
                uint8_t b = data[j++];
                if ((b >> 4) == 0x0F || (b & 0x0F) == 0x0F) break;
            }
            i = j;
            continue;
        }
        if (c >= 32 && c <= 246) {
            ++i;
            continue;
        }
        if (c >= 247 && c <= 254) {
            i += 2;
            continue;
        }
        ++i;
    }
    return changed;
}

bool repairCffEmptyRealOperands(std::vector<uint8_t>& cff) {
    if (cff.size() < 4 || cff[0] != 1) return false;
    size_t pos = cff[2];
    if (pos < 4 || pos >= cff.size()) return false;

    auto readIndex = [&](size_t& at, std::vector<std::pair<size_t, size_t>>* ranges) -> bool {
        if (at + 2 > cff.size()) return false;
        const size_t count = (static_cast<size_t>(cff[at]) << 8) | cff[at + 1];
        if (count == 0) {
            at += 2;
            return true;
        }
        if (at + 3 > cff.size()) return false;
        const size_t offSize = cff[at + 2];
        if (offSize < 1 || offSize > 4) return false;
        const size_t offsAt = at + 3;
        if (count + 1 > (cff.size() - offsAt) / offSize) return false;
        std::vector<uint32_t> offsets(count + 1);
        size_t p = offsAt;
        for (size_t i = 0; i <= count; ++i) {
            uint32_t v = 0;
            for (size_t b = 0; b < offSize; ++b) v = (v << 8) | cff[p++];
            offsets[i] = v;
        }
        const size_t dataBase = p - 1;
        if (offsets[0] != 1) return false;
        const size_t last = offsets[count];
        if (last > (cff.size() - dataBase)) return false;
        if (ranges) {
            ranges->clear();
            ranges->reserve(count);
            for (size_t i = 0; i < count; ++i) {
                if (offsets[i] > offsets[i + 1]) return false;
                ranges->emplace_back(dataBase + offsets[i], dataBase + offsets[i + 1]);
            }
        }
        at = dataBase + last;
        return true;
    };

    size_t nameAt = pos;
    if (!readIndex(nameAt, nullptr)) return false;
    size_t topAt = nameAt;
    std::vector<std::pair<size_t, size_t>> tops;
    if (!readIndex(topAt, &tops)) return false;

    bool changed = false;
    for (auto const& [begin, end] : tops) {
        if (begin > end || end > cff.size()) return false;
        changed |= repairCffDictEmptyReals(cff.data(), begin, end);
    }
    return changed;
}

struct CffOperand {
    bool isInt = true;
    int32_t intValue = 0;
    double realValue = 0.0;
};

struct CffDictEntry {
    uint16_t op = 0;
    std::vector<CffOperand> operands;
};

bool cffReadIndex(const std::vector<uint8_t>& cff, size_t at, size_t& end,
                  std::vector<std::pair<size_t, size_t>>* items) {
    if (at + 2 > cff.size()) return false;
    const size_t count = (static_cast<size_t>(cff[at]) << 8) | cff[at + 1];
    if (count == 0) {
        end = at + 2;
        if (items) items->clear();
        return true;
    }
    if (at + 3 > cff.size()) return false;
    const size_t offSize = cff[at + 2];
    if (offSize < 1 || offSize > 4) return false;
    const size_t offsetsAt = at + 3;
    if (count + 1 > (cff.size() - offsetsAt) / offSize) return false;
    std::vector<uint32_t> offsets(count + 1);
    size_t p = offsetsAt;
    for (size_t i = 0; i <= count; ++i) {
        uint32_t v = 0;
        for (size_t b = 0; b < offSize; ++b) v = (v << 8) | cff[p++];
        offsets[i] = v;
    }
    const size_t dataBase = p - 1;
    if (offsets[0] != 1) return false;
    for (size_t i = 0; i < count; ++i) {
        if (offsets[i] > offsets[i + 1]) return false;
    }
    const size_t last = offsets[count];
    if (last > cff.size() - dataBase) return false;
    if (items) {
        items->clear();
        items->reserve(count);
        for (size_t i = 0; i < count; ++i) {
            items->emplace_back(dataBase + offsets[i], dataBase + offsets[i + 1]);
        }
    }
    end = dataBase + last;
    return true;
}

bool cffParseDict(const uint8_t* p, size_t n, std::vector<CffDictEntry>& out) {
    size_t i = 0;
    while (i < n) {
        std::vector<CffOperand> operands;
        while (i < n && p[i] > 21) {
            const uint8_t c = p[i];
            if (c >= 32 && c <= 246) {
                operands.push_back({true, static_cast<int32_t>(c) - 139, 0.0});
                ++i;
            } else if (c >= 247 && c <= 250) {
                if (i + 1 >= n) return false;
                const int32_t v =
                    (static_cast<int32_t>(c) - 247) * 256 + static_cast<int32_t>(p[i + 1]) + 108;
                operands.push_back({true, v, 0.0});
                i += 2;
            } else if (c >= 251 && c <= 254) {
                if (i + 1 >= n) return false;
                const int32_t v =
                    -(static_cast<int32_t>(c) - 251) * 256 - static_cast<int32_t>(p[i + 1]) - 108;
                operands.push_back({true, v, 0.0});
                i += 2;
            } else if (c == 28) {
                if (i + 3 > n) return false;
                const int32_t v =
                    static_cast<int16_t>((static_cast<uint16_t>(p[i + 1]) << 8) | p[i + 2]);
                operands.push_back({true, v, 0.0});
                i += 3;
            } else if (c == 29) {
                if (i + 5 > n) return false;
                const uint32_t u = (static_cast<uint32_t>(p[i + 1]) << 24) |
                                   (static_cast<uint32_t>(p[i + 2]) << 16) |
                                   (static_cast<uint32_t>(p[i + 3]) << 8) | p[i + 4];
                operands.push_back({true, static_cast<int32_t>(u), 0.0});
                i += 5;
            } else if (c == 30) {
                ++i;
                std::string text;
                bool terminated = false;
                while (i < n && !terminated) {
                    const uint8_t b = p[i++];
                    for (int half = 0; half < 2; ++half) {
                        const uint8_t nibble = half == 0 ? (b >> 4) : (b & 0x0F);
                        if (nibble == 0x0F) {
                            terminated = true;
                            break;
                        }
                        if (nibble <= 9) {
                            text.push_back(static_cast<char>('0' + nibble));
                        } else if (nibble == 0x0A) {
                            text.push_back('.');
                        } else if (nibble == 0x0B) {
                            text.push_back('E');
                        } else if (nibble == 0x0C) {
                            text += "E-";
                        } else if (nibble == 0x0E) {
                            text.push_back('-');
                        } else {
                            return false;
                        }
                    }
                }
                if (!terminated) return false;
                operands.push_back({false, 0, std::strtod(text.c_str(), nullptr)});
            } else {
                return false;
            }
        }
        if (i >= n) return false;
        uint16_t op = p[i++];
        if (op == 12) {
            if (i >= n) return false;
            op = static_cast<uint16_t>(0x0C00 | p[i++]);
        }
        out.push_back({op, std::move(operands)});
    }
    return true;
}

void cffAppendInt(std::vector<uint8_t>& out, int32_t v) {
    if (v >= -32768 && v <= 32767) {
        out.push_back(28);
        out.push_back(static_cast<uint8_t>((static_cast<uint32_t>(v) >> 8) & 0xFF));
        out.push_back(static_cast<uint8_t>(static_cast<uint32_t>(v) & 0xFF));
    } else {
        out.push_back(29);
        appendU32(out, static_cast<uint32_t>(v));
    }
}

void cffAppendOffset(std::vector<uint8_t>& out, uint32_t v) {
    out.push_back(29);
    appendU32(out, v);
}

void cffAppendReal(std::vector<uint8_t>& out, double v) {
    char text[64];
    std::snprintf(text, sizeof(text), "%.8g", v);
    out.push_back(30);
    uint8_t byte = 0;
    bool high = true;
    auto put = [&](uint8_t nibble) {
        if (high) {
            byte = static_cast<uint8_t>(nibble << 4);
        } else {
            out.push_back(static_cast<uint8_t>(byte | nibble));
        }
        high = !high;
    };
    for (const char* s = text; *s; ++s) {
        if (*s >= '0' && *s <= '9') {
            put(static_cast<uint8_t>(*s - '0'));
        } else if (*s == 'e' || *s == 'E') {
            put(0x0B);
        } else if (*s == '-') {
            put(0x0E);
        } else if (*s == '+') {
            continue;
        } else {
            put(0x0A);  // Includes '.'; the CFF real alphabet starts at nibble 0xA.
        }
    }
    put(0x0F);
    if (!high) out.push_back(byte);
}

void cffAppendOperand(std::vector<uint8_t>& out, const CffOperand& operand) {
    if (operand.isInt) {
        cffAppendInt(out, operand.intValue);
    } else {
        cffAppendReal(out, operand.realValue);
    }
}

std::vector<uint8_t> cffBuildIndex(const std::vector<std::vector<uint8_t>>& items) {
    std::vector<uint8_t> out;
    if (items.size() > 0xFFFF) return out;
    appendU16(out, static_cast<uint16_t>(items.size()));
    if (items.empty()) return out;
    size_t dataSize = 0;
    for (const auto& item : items) dataSize += item.size();
    uint8_t offSize = 1;
    if (dataSize + 1 > 0xFFFFFF) {
        offSize = 4;
    } else if (dataSize + 1 > 0xFFFF) {
        offSize = 3;
    } else if (dataSize + 1 > 0xFF) {
        offSize = 2;
    }
    out.push_back(offSize);
    uint32_t offset = 1;
    std::vector<uint32_t> offsets;
    offsets.reserve(items.size() + 1);
    offsets.push_back(offset);
    for (const auto& item : items) {
        offset += static_cast<uint32_t>(item.size());
        offsets.push_back(offset);
    }
    for (uint32_t v : offsets) {
        for (int b = offSize - 1; b >= 0; --b) {
            out.push_back(static_cast<uint8_t>((v >> (8 * b)) & 0xFF));
        }
    }
    for (const auto& item : items) out.insert(out.end(), item.begin(), item.end());
    return out;
}

std::vector<uint8_t> convertCffToCidKeyed(const std::vector<uint8_t>& cff) {
    if (cff.size() < 8 || cff[0] != 1) return {};
    const size_t hdrSize = cff[2];
    if (hdrSize < 4 || hdrSize >= cff.size()) return {};

    size_t nameEnd = 0;
    if (!cffReadIndex(cff, hdrSize, nameEnd, nullptr) || nameEnd > cff.size()) return {};
    size_t topEnd = 0;
    std::vector<std::pair<size_t, size_t>> topItems;
    if (!cffReadIndex(cff, nameEnd, topEnd, &topItems) || topItems.size() != 1) return {};
    size_t stringEnd = 0;
    std::vector<std::pair<size_t, size_t>> stringItems;
    if (!cffReadIndex(cff, topEnd, stringEnd, &stringItems)) return {};
    size_t gsubrEnd = 0;
    if (!cffReadIndex(cff, stringEnd, gsubrEnd, nullptr)) return {};

    std::vector<CffDictEntry> topEntries;
    if (!cffParseDict(cff.data() + topItems[0].first, topItems[0].second - topItems[0].first,
                      topEntries)) {
        return {};
    }
    const auto findEntry = [&](uint16_t op) -> const CffDictEntry* {
        for (const auto& entry : topEntries) {
            if (entry.op == op) return &entry;
        }
        return nullptr;
    };
    // Already CID-keyed (ROS present): this conversion is only for name-keyed CFF.
    if (findEntry(0x0C1E)) return {};
    const CffDictEntry* charStringsEntry = findEntry(17);
    const CffDictEntry* privateEntry = findEntry(18);
    if (!charStringsEntry || charStringsEntry->operands.size() != 1 ||
        !charStringsEntry->operands[0].isInt || !privateEntry ||
        privateEntry->operands.size() != 2 || !privateEntry->operands[0].isInt ||
        !privateEntry->operands[1].isInt) {
        return {};
    }
    const uint32_t charStringsStart = static_cast<uint32_t>(charStringsEntry->operands[0].intValue);
    const uint32_t privateSize = static_cast<uint32_t>(privateEntry->operands[0].intValue);
    const uint32_t privateOffset = static_cast<uint32_t>(privateEntry->operands[1].intValue);
    if (charStringsStart >= cff.size() || privateOffset >= cff.size() ||
        privateSize > cff.size() - privateOffset) {
        return {};
    }
    size_t charStringsEnd = 0;
    std::vector<std::pair<size_t, size_t>> charStrings;
    if (!cffReadIndex(cff, charStringsStart, charStringsEnd, &charStrings) || charStrings.empty() ||
        charStrings.size() > 0xFFFF) {
        return {};
    }

    size_t privateEnd = privateOffset + privateSize;
    std::vector<CffDictEntry> privateDict;
    if (!cffParseDict(cff.data() + privateOffset, privateSize, privateDict)) return {};
    for (const auto& entry : privateDict) {
        if (entry.op != 19 || entry.operands.empty() || !entry.operands[0].isInt) continue;
        const uint32_t subrsOffset =
            privateOffset + static_cast<uint32_t>(entry.operands[0].intValue);
        if (subrsOffset >= cff.size()) return {};
        size_t subrsEnd = 0;
        if (!cffReadIndex(cff, subrsOffset, subrsEnd, nullptr)) return {};
        privateEnd = std::max(privateEnd, subrsEnd);
    }
    if (privateEnd > cff.size()) return {};

    std::vector<std::vector<uint8_t>> strings;
    strings.reserve(stringItems.size() + 2);
    for (const auto& [begin, end] : stringItems) {
        strings.emplace_back(cff.data() + begin, cff.data() + end);
    }
    auto sidFor = [&](const char* name) -> int32_t {
        for (size_t i = 0; i < strings.size(); ++i) {
            if (strings[i].size() == std::strlen(name) &&
                std::memcmp(strings[i].data(), name, strings[i].size()) == 0) {
                return static_cast<int32_t>(391 + i);
            }
        }
        strings.emplace_back(name, name + std::strlen(name));
        return static_cast<int32_t>(391 + strings.size() - 1);
    };
    const int32_t adobeSid = sidFor("Adobe");
    const int32_t identitySid = sidFor("Identity");
    const uint32_t numGlyphs = static_cast<uint32_t>(charStrings.size());

    std::vector<uint8_t> charset;
    charset.push_back(0);
    for (uint32_t gid = 1; gid < numGlyphs; ++gid) {
        charset.push_back(static_cast<uint8_t>((gid >> 8) & 0xFF));
        charset.push_back(static_cast<uint8_t>(gid & 0xFF));
    }
    std::vector<uint8_t> fdSelect(numGlyphs + 1, 0);
    fdSelect[0] = 0;

    auto slice = [&](size_t begin, size_t end) {
        return std::vector<uint8_t>(cff.data() + begin, cff.data() + end);
    };
    const std::vector<uint8_t> nameBytes = slice(hdrSize, nameEnd);
    const std::vector<uint8_t> gsubrBytes = slice(stringEnd, gsubrEnd);
    const std::vector<uint8_t> charStringsBytes = slice(charStringsStart, charStringsEnd);
    const std::vector<uint8_t> privateBytes = slice(privateOffset, privateEnd);
    const std::vector<uint8_t> stringIndex = cffBuildIndex(strings);
    if (stringIndex.empty()) return {};

    const CffDictEntry* fontMatrixEntry = findEntry(0x0C07);
    const CffDictEntry* fontBBoxEntry = findEntry(5);
    const CffDictEntry* charstringTypeEntry = findEntry(0x0C06);

    auto buildFontDict = [&](uint32_t privateOff) {
        std::vector<uint8_t> dict;
        if (fontMatrixEntry && fontMatrixEntry->operands.size() == 6) {
            for (const auto& operand : fontMatrixEntry->operands) cffAppendOperand(dict, operand);
        } else {
            const CffOperand defaultMatrix[6] = {{false, 0, 0.001}, {true, 0, 0}, {true, 0, 0},
                                                 {false, 0, 0.001}, {true, 0, 0}, {true, 0, 0}};
            for (const auto& operand : defaultMatrix) cffAppendOperand(dict, operand);
        }
        dict.push_back(12);
        dict.push_back(7);  // FontMatrix
        cffAppendInt(dict, static_cast<int32_t>(privateSize));
        cffAppendOffset(dict, privateOff);
        dict.push_back(18);  // Private
        return dict;
    };
    const std::vector<uint8_t> fontDictPlaceholder = buildFontDict(0);
    const std::vector<uint8_t> fdArrayPlaceholder = cffBuildIndex({fontDictPlaceholder});
    if (fdArrayPlaceholder.empty()) return {};

    auto buildTopDict = [&](uint32_t charsetOff, uint32_t charStringsOff, uint32_t fdArrayOff,
                            uint32_t fdSelectOff) {
        std::vector<uint8_t> dict;
        cffAppendInt(dict, adobeSid);
        cffAppendInt(dict, identitySid);
        cffAppendInt(dict, 0);
        dict.push_back(12);
        dict.push_back(30);  // ROS
        cffAppendInt(dict, static_cast<int32_t>(numGlyphs));
        dict.push_back(12);
        dict.push_back(34);  // CIDCount
        if (fontBBoxEntry && fontBBoxEntry->operands.size() == 4) {
            for (const auto& operand : fontBBoxEntry->operands) cffAppendOperand(dict, operand);
            dict.push_back(5);  // FontBBox
        }
        if (charstringTypeEntry && charstringTypeEntry->operands.size() == 1) {
            for (const auto& operand : charstringTypeEntry->operands)
                cffAppendOperand(dict, operand);
            dict.push_back(12);
            dict.push_back(6);  // CharstringType
        }
        cffAppendOffset(dict, charsetOff);
        dict.push_back(15);  // charset
        cffAppendOffset(dict, charStringsOff);
        dict.push_back(17);  // CharStrings
        cffAppendOffset(dict, fdArrayOff);
        dict.push_back(12);
        dict.push_back(36);  // FDArray
        cffAppendOffset(dict, fdSelectOff);
        dict.push_back(12);
        dict.push_back(37);  // FDSelect
        return dict;
    };
    const std::vector<uint8_t> topPlaceholder = cffBuildIndex({buildTopDict(0, 0, 0, 0)});
    if (topPlaceholder.empty()) return {};

    const size_t base =
        hdrSize + nameBytes.size() + topPlaceholder.size() + stringIndex.size() + gsubrBytes.size();
    const size_t charsetOffset = base;
    const size_t fdSelectOffset = charsetOffset + charset.size();
    const size_t charStringsNewOffset = fdSelectOffset + fdSelect.size();
    const size_t fdArrayOffset = charStringsNewOffset + charStringsBytes.size();
    const uint32_t privateOffsetNew =
        static_cast<uint32_t>(fdArrayOffset + fdArrayPlaceholder.size());
    if (privateOffsetNew > 0x7FFFFFFF) return {};
    const std::vector<uint8_t> fontDict = buildFontDict(privateOffsetNew);
    const std::vector<uint8_t> fdArray = cffBuildIndex({fontDict});
    if (fdArray.size() != fdArrayPlaceholder.size()) return {};
    const std::vector<uint8_t> topDictBytes = buildTopDict(
        static_cast<uint32_t>(charsetOffset), static_cast<uint32_t>(charStringsNewOffset),
        static_cast<uint32_t>(fdArrayOffset), static_cast<uint32_t>(fdSelectOffset));
    const std::vector<uint8_t> topIndex = cffBuildIndex({topDictBytes});
    if (topIndex.size() != topPlaceholder.size()) return {};

    std::vector<uint8_t> out;
    out.reserve(privateOffsetNew + privateBytes.size());
    out.insert(out.end(), cff.data(), cff.data() + hdrSize);
    out.insert(out.end(), nameBytes.begin(), nameBytes.end());
    out.insert(out.end(), topIndex.begin(), topIndex.end());
    out.insert(out.end(), stringIndex.begin(), stringIndex.end());
    out.insert(out.end(), gsubrBytes.begin(), gsubrBytes.end());
    out.insert(out.end(), charset.begin(), charset.end());
    out.insert(out.end(), fdSelect.begin(), fdSelect.end());
    out.insert(out.end(), charStringsBytes.begin(), charStringsBytes.end());
    out.insert(out.end(), fdArray.begin(), fdArray.end());
    out.insert(out.end(), privateBytes.begin(), privateBytes.end());
    return out;
}

}  // namespace

bool repairCffFontProgramForPdfA(QPDF& qpdf, QPDFObjectHandle& fd) {
    if (!fd.isDictionary()) return false;
    QPDFObjectHandle ff3 = fd.getKey("/FontFile3");
    if (!ff3.isStream()) return false;
    QPDFObjectHandle subtype = ff3.getDict().getKey("/Subtype");
    if (!subtype.isName()) return false;
    const std::string kind = subtype.getName();
    if (kind != "/Type1C" && kind != "/CIDFontType0C") return false;
    try {
        std::vector<uint8_t> cff;
        if (!readStreamBytesBounded(ff3, cff, qpdf_dl_generalized, size_t{32} * 1024U * 1024U) ||
            cff.size() < 8) {
            return false;
        }
        bool changed = repairCffEmptyRealOperands(cff);
        if (kind == "/CIDFontType0C") {
            std::vector<uint8_t> converted = convertCffToCidKeyed(cff);
            if (!converted.empty()) {
                cff = std::move(converted);
                changed = true;
            }
        }
        if (!changed) return false;
        QPDFObjectHandle out = QPDFObjectHandle::newStream(
            &qpdf, std::string(reinterpret_cast<const char*>(cff.data()), cff.size()));
        out.getDict().replaceKey("/Subtype", QPDFObjectHandle::newName(kind));
        fd.replaceKey("/FontFile3", out);
        return true;
    } catch (const std::exception& e) {
        std::fputs(e.what(), stderr);
        std::fputc('\n', stderr);
        return false;
    } catch (...) {
        std::fputs("jpdfium: repairCffFontProgramForPdfA failed\n", stderr);
        return false;
    }
}

bool convertOpenTypeWrapperForPdfA1(QPDF& qpdf, QPDFObjectHandle& fd, FT_Library ftLib, bool cid) {
    if (!fd.isDictionary()) return false;
    QPDFObjectHandle ff3 = fd.getKey("/FontFile3");
    if (!ff3.isStream()) return false;
    QPDFObjectHandle subtype = ff3.getDict().getKey("/Subtype");
    if (!subtype.isName() || subtype.getName() != "/OpenType") return false;
    std::string bytes;
    if (!readStreamBounded(ff3, bytes, qpdf_dl_generalized, size_t{64} * 1024U * 1024U) ||
        bytes.size() < 4) {
        return false;
    }

    std::string cff = jpdfium::font::extractCffTableFromSfnt(ftLib, bytes);
    if (!cff.empty()) {
        std::vector<uint8_t> cffBytes(cff.begin(), cff.end());
        if (repairCffEmptyRealOperands(cffBytes)) {
            cff.assign(reinterpret_cast<const char*>(cffBytes.data()), cffBytes.size());
        }
        bool hasGlyphNames = false;
        bool cidKeyed = false;
        auto inspectFace = [&](const std::vector<uint8_t>& data, bool loadAllGlyphs) -> bool {
            FT_Face rawFace = nullptr;
            if (FT_New_Memory_Face(ftLib, reinterpret_cast<const FT_Byte*>(data.data()),
                                   static_cast<FT_Long>(data.size()), 0, &rawFace) != 0 ||
                !rawFace) {
                return false;
            }
            jpdfium::font::FtFaceGuard guard(rawFace);
            hasGlyphNames = FT_HAS_GLYPH_NAMES(guard.face) != 0;
            cidKeyed = FT_IS_CID_KEYED(guard.face) != 0;
            if (loadAllGlyphs) {
                for (FT_UInt gid = 0; gid < static_cast<FT_UInt>(guard.face->num_glyphs); ++gid) {
                    if (FT_Load_Glyph(guard.face, gid, FT_LOAD_NO_SCALE | FT_LOAD_NO_HINTING) !=
                        0) {
                        return false;
                    }
                }
            }
            return true;
        };
        if (!inspectFace(cffBytes, false)) return false;
        if (cid && !cidKeyed) {
            std::vector<uint8_t> converted = convertCffToCidKeyed(cffBytes);
            if (converted.empty() || !inspectFace(converted, true) || !cidKeyed) return false;
            cffBytes = std::move(converted);
            cff.assign(reinterpret_cast<const char*>(cffBytes.data()), cffBytes.size());
        }
        if (cid ? !cidKeyed : !hasGlyphNames) return false;
        QPDFObjectHandle out = QPDFObjectHandle::newStream(&qpdf, cff);
        out.getDict().replaceKey("/Subtype",
                                 QPDFObjectHandle::newName(cid ? "/CIDFontType0C" : "/Type1C"));
        fd.replaceKey("/FontFile3", out);
        return true;
    }

    uint32_t tag = readU32(reinterpret_cast<const uint8_t*>(bytes.data()));
    if (tag == 0x00010000U || tag == 0x74727565U) {
        QPDFObjectHandle out = QPDFObjectHandle::newStream(&qpdf, bytes);
        out.getDict().replaceKey(
            "/Length1", QPDFObjectHandle::newInteger(static_cast<long long>(bytes.size())));
        fd.removeKey("/FontFile3");
        fd.replaceKey("/FontFile2", out);
        return true;
    }
    return false;
}

bool pruneSymbolicTrueTypeProgram(QPDF& qpdf, QPDFObjectHandle& fontObj, QPDFObjectHandle& fd) {
    if (!fd.isDictionary()) return false;
    QPDFObjectHandle flags = fd.getKey("/Flags");
    if (!flags.isInteger() || (flags.getIntValue() & 4) == 0) return false;
    QPDFObjectHandle ff2 = fd.getKey("/FontFile2");
    if (!ff2.isStream()) return false;
    try {
        std::vector<uint8_t> prog;
        if (!readStreamBytesBounded(ff2, prog, qpdf_dl_generalized) || prog.size() < 12 ||
            !isSafeFontBuffer(prog)) {
            return false;
        }
        std::vector<uint8_t> pruned = prog;
        if (!sfntPruneSymbolicCmap(pruned) || pruned.empty()) return false;
        QPDFObjectHandle newFf = QPDFObjectHandle::newStream(
            &qpdf, std::string(reinterpret_cast<const char*>(pruned.data()), pruned.size()));
        newFf.getDict().replaceKey(
            "/Length1", QPDFObjectHandle::newInteger(static_cast<long long>(pruned.size())));
        QPDFObjectHandle newFd = qpdf.makeIndirectObject(fd.shallowCopy());
        newFd.replaceKey("/FontFile2", newFf);
        fontObj.replaceKey("/FontDescriptor", newFd);
        fd = std::move(newFd);
        return true;
    } catch (const std::exception& e) {
        std::fputs(e.what(), stderr);
        std::fputc('\n', stderr);
        return false;
    } catch (...) {
        std::fputs("jpdfium: pruneSymbolicTrueTypeProgram failed\n", stderr);
        return false;
    }
}

// ---- Recursive font enumeration (Stage 1) ----

void collectFontsFromResources(const QPDFObjectHandle& res, std::set<QPDFObjGen>& visitedRes,
                               std::vector<QPDFObjectHandle>& fonts,
                               std::set<QPDFObjGen>& seenFonts, size_t& nodeCount, int depth = 0) {
    if (!res.isDictionary() || depth > 16 || nodeCount >= kMaxRewriterGraphNodes) return;
    ++nodeCount;
    if (res.isIndirect()) {
        if (!visitedRes.insert(res.getObjGen()).second) return;
    }
    QPDFObjectHandle fontDict = res.getKey("/Font");
    if (fontDict.isDictionary()) {
        for (const auto& k : fontDict.getKeys()) {
            if (nodeCount >= kMaxRewriterGraphNodes) return;
            ++nodeCount;
            QPDFObjectHandle f = fontDict.getKey(k);
            if (f.isDictionary()) {
                if (!f.isIndirect() || seenFonts.insert(f.getObjGen()).second) {
                    fonts.push_back(f);
                }
            }
        }
    }
    QPDFObjectHandle xobjs = res.getKey("/XObject");
    if (xobjs.isDictionary()) {
        for (const auto& k : xobjs.getKeys()) {
            if (nodeCount >= kMaxRewriterGraphNodes) return;
            QPDFObjectHandle xo = xobjs.getKey(k);
            if (xo.isStream()) {
                if (xo.isIndirect() && !visitedRes.insert(xo.getObjGen()).second) continue;
                QPDFObjectHandle xd = xo.getDict();
                if (xd.getKey("/Subtype").isNameAndEquals("/Form")) {
                    collectFontsFromResources(xd.getKey("/Resources"), visitedRes, fonts, seenFonts,
                                              nodeCount, depth + 1);
                }
            }
        }
    }
    QPDFObjectHandle pats = res.getKey("/Pattern");
    if (pats.isDictionary()) {
        for (const auto& k : pats.getKeys()) {
            if (nodeCount >= kMaxRewriterGraphNodes) return;
            QPDFObjectHandle pat = pats.getKey(k);
            if (pat.isStream()) {
                if (pat.isIndirect() && !visitedRes.insert(pat.getObjGen()).second) continue;
                collectFontsFromResources(pat.getDict().getKey("/Resources"), visitedRes, fonts,
                                          seenFonts, nodeCount, depth + 1);
            }
        }
    }
}

std::vector<QPDFObjectHandle> enumerateAllFonts(QPDF& qpdf) {
    std::vector<QPDFObjectHandle> fonts;
    std::set<QPDFObjGen> seenFonts;
    std::set<QPDFObjGen> visitedRes;
    size_t nodeCount = 0;

    QPDFPageDocumentHelper pdh(qpdf);
    for (auto& page : pdh.getAllPages()) {
        if (nodeCount >= kMaxRewriterGraphNodes) break;
        QPDFObjectHandle po = page.getObjectHandle();
        collectFontsFromResources(po.getKey("/Resources"), visitedRes, fonts, seenFonts, nodeCount);
        QPDFObjectHandle annots = po.getKey("/Annots");
        if (annots.isArray()) {
            const int nAnnots = std::min(annots.getArrayNItems(), 4096);
            for (int i = 0; i < nAnnots && nodeCount < kMaxRewriterGraphNodes; ++i) {
                QPDFObjectHandle a = annots.getArrayItem(i);
                if (!a.isDictionary()) continue;
                QPDFObjectHandle ap = a.getKey("/AP");
                if (!ap.isDictionary()) continue;
                for (const char* apk : {"/N", "/R", "/D"}) {
                    QPDFObjectHandle st = ap.getKey(apk);
                    if (st.isStream()) {
                        if (!st.isIndirect() || visitedRes.insert(st.getObjGen()).second) {
                            collectFontsFromResources(st.getDict().getKey("/Resources"), visitedRes,
                                                      fonts, seenFonts, nodeCount);
                        }
                    } else if (st.isDictionary()) {
                        for (const auto& sk : st.getKeys()) {
                            if (nodeCount >= kMaxRewriterGraphNodes) break;
                            QPDFObjectHandle sub = st.getKey(sk);
                            if (sub.isStream() &&
                                (!sub.isIndirect() || visitedRes.insert(sub.getObjGen()).second)) {
                                collectFontsFromResources(sub.getDict().getKey("/Resources"),
                                                          visitedRes, fonts, seenFonts, nodeCount);
                            }
                        }
                    }
                }
            }
        }
    }
    QPDFObjectHandle root = qpdf.getRoot();
    QPDFObjectHandle acro = root.getKey("/AcroForm");
    if (acro.isDictionary() && nodeCount < kMaxRewriterGraphNodes) {
        collectFontsFromResources(acro.getKey("/DR"), visitedRes, fonts, seenFonts, nodeCount);
    }
    // Also include any indirect font objects not yet visited
    for (auto& obj : qpdf.getAllObjects()) {
        if (!obj.isDictionary()) continue;
        QPDFObjectHandle tp = obj.getKey("/Type");
        QPDFObjectHandle st = obj.getKey("/Subtype");
        if (tp.isNameAndEquals("/Font") ||
            (st.isName() && (st.getName() == "/Type0" || st.getName() == "/TrueType" ||
                             st.getName() == "/Type1" || st.getName() == "/MMType1" ||
                             st.getName() == "/CIDFontType0" || st.getName() == "/CIDFontType2"))) {
            if (seenFonts.insert(obj.getObjGen()).second) {
                fonts.push_back(obj);
            }
        }
    }
    return fonts;
}

FT_UInt resolveSimpleGid(FT_Face face, int code, bool isSymbolic, bool isTrueType,
                         const std::string& baseEnc, const std::map<int, std::string>& diffNames) {
    if (!face || code < 0 || code > 255) return 0;
    // 1. Check /Encoding /Differences first
    auto dit = diffNames.find(code);
    if (dit != diffNames.end()) {
        if (dit->second == ".notdef") return 0;
        if (FT_HAS_GLYPH_NAMES(face)) {
            FT_UInt g = FT_Get_Name_Index(face, const_cast<char*>(dit->second.c_str()));
            if (g > 0) return g;
        }
        uint32_t u = aglNameToUnicode(dit->second);
        if (u > 0 && FT_Select_Charmap(face, FT_ENCODING_UNICODE) == 0) {
            FT_UInt g = FT_Get_Char_Index(face, u);
            if (g > 0) return g;
        }
        return 0;
    }
    auto rejectNotdef = [&](FT_UInt g) -> FT_UInt {
        if (g == 0) return 0;
        if (!isTrueType && FT_HAS_GLYPH_NAMES(face)) {
            char gname[64] = {};
            if (FT_Get_Glyph_Name(face, g, gname, sizeof(gname)) == 0 &&
                std::strcmp(gname, ".notdef") == 0) {
                return 0;
            }
        }
        return g;
    };
    // 2. Symbolic (or no encoding)
    if (isSymbolic) {
        if (!isTrueType) {
            if (FT_Select_Charmap(face, FT_ENCODING_ADOBE_CUSTOM) == 0) {
                FT_UInt g = rejectNotdef(FT_Get_Char_Index(face, static_cast<unsigned>(code)));
                if (g > 0) return g;
            }
            // Only fall back to ADOBE_STANDARD if the font has no custom encoding charmap
            bool hasCustom = false;
            for (int ci = 0; ci < face->num_charmaps; ++ci) {
                if (face->charmaps[ci]->encoding == FT_ENCODING_ADOBE_CUSTOM) {
                    hasCustom = true;
                    break;
                }
            }
            if (!hasCustom && FT_Select_Charmap(face, FT_ENCODING_ADOBE_STANDARD) == 0) {
                FT_UInt g = rejectNotdef(FT_Get_Char_Index(face, static_cast<unsigned>(code)));
                if (g > 0) return g;
            }
            return 0;
        }
        if (FT_Select_Charmap(face, FT_ENCODING_MS_SYMBOL) == 0) {
            FT_UInt g = FT_Get_Char_Index(face, 0xF000U + static_cast<unsigned>(code));
            if (g == 0) g = FT_Get_Char_Index(face, static_cast<unsigned>(code));
            if (g > 0) return g;
        }
        if (FT_Select_Charmap(face, FT_ENCODING_APPLE_ROMAN) == 0) {
            FT_UInt g = FT_Get_Char_Index(face, static_cast<unsigned>(code));
            if (g > 0) return g;
        }
        return 0;
    }
    // 3. Non-symbolic (WinAnsi or MacRoman)
    uint32_t uni = 0;
    if (baseEnc == "/MacRomanEncoding") {
        uni = macRomanToUnicode(static_cast<uint8_t>(code));
    } else {
        // ISO 32000-1 Annex D.2 Note 4: unused WinAnsi codes > 40 (127, 129, 141, 143, 144, 157)
        // map to bullet (U+2022)
        if (code == 127 || code == 129 || code == 141 || code == 143 || code == 144 ||
            code == 157) {
            uni = 0x2022;
        } else {
            uni = winAnsiToUnicode(static_cast<uint8_t>(code));
        }
    }
    if (FT_Select_Charmap(face, FT_ENCODING_UNICODE) == 0) {
        FT_UInt g = FT_Get_Char_Index(face, uni);
        if (g > 0) return g;
    }
    if (baseEnc == "/MacRomanEncoding" && FT_Select_Charmap(face, FT_ENCODING_APPLE_ROMAN) == 0) {
        FT_UInt g = FT_Get_Char_Index(face, static_cast<unsigned>(code));
        if (g > 0) return g;
    }
    if (FT_Select_Charmap(face, FT_ENCODING_ADOBE_STANDARD) == 0 ||
        FT_Select_Charmap(face, FT_ENCODING_ADOBE_CUSTOM) == 0) {
        FT_UInt g = FT_Get_Char_Index(face, static_cast<unsigned>(code));
        if (g > 0) return g;
    }
    return 0;
}

// ---- /W array parser & serializer ----

static int safeRoundWidth(double v) {
    if (!std::isfinite(v)) return 0;
    const double clamped = std::clamp(v, -1000000.0, 1000000.0);
    return static_cast<int>(std::llround(clamped));
}

std::map<int, int> parseWArray(const QPDFObjectHandle& wArr) {
    constexpr size_t kMaxWEntries = 65536;
    constexpr int kMaxWArrayIters = 65536;
    std::map<int, int> wmap;
    if (!wArr.isArray()) return wmap;
    const int n = wArr.getArrayNItems();
    int i = 0;
    int iters = 0;
    while (i + 1 < n && wmap.size() < kMaxWEntries && iters++ < kMaxWArrayIters) {
        QPDFObjectHandle c1o = wArr.getArrayItem(i);
        if (!c1o.isInteger()) {
            ++i;
            continue;
        }
        long long c1 = c1o.getIntValue();
        QPDFObjectHandle nxt = wArr.getArrayItem(i + 1);
        if (nxt.isArray()) {
            const int m = std::min(nxt.getArrayNItems(), 65536);
            for (int j = 0; j < m && wmap.size() < kMaxWEntries; ++j) {
                long long cid = c1 + j;
                if (cid < 0 || cid > 65535) continue;
                QPDFObjectHandle v = nxt.getArrayItem(j);
                if (v.isNumber()) {
                    wmap[static_cast<int>(cid)] = safeRoundWidth(v.getNumericValue());
                }
            }
            i += 2;
        } else if (nxt.isInteger() && i + 2 < n && wArr.getArrayItem(i + 2).isNumber()) {
            long long c2 = nxt.getIntValue();
            int wv = safeRoundWidth(wArr.getArrayItem(i + 2).getNumericValue());
            if (c1 >= 0 && c2 >= c1 && c2 <= 65535 && (c2 - c1) <= 65535) {
                for (long long cid = c1; cid <= c2 && wmap.size() < kMaxWEntries; ++cid) {
                    wmap[static_cast<int>(cid)] = wv;
                }
            }
            i += 3;
        } else {
            break;
        }
    }
    return wmap;
}

QPDFObjectHandle serializeWMap(const std::map<int, int>& wmap) {
    constexpr size_t kMaxWSubArray = 4096;
    QPDFObjectHandle arr = QPDFObjectHandle::newArray();
    auto it = wmap.begin();
    while (it != wmap.end()) {
        std::vector<std::pair<int, int>> run;
        int prev = it->first - 1;
        while (it != wmap.end() && it->first == prev + 1) {
            run.push_back({it->first, it->second});
            prev = it->first;
            ++it;
        }
        size_t pos = 0;
        while (pos < run.size()) {
            size_t rleEnd = pos + 1;
            while (rleEnd < run.size() && run[rleEnd].second == run[pos].second) {
                ++rleEnd;
            }
            if (rleEnd - pos >= 3) {
                arr.appendItem(QPDFObjectHandle::newInteger(run[pos].first));
                arr.appendItem(QPDFObjectHandle::newInteger(run[rleEnd - 1].first));
                arr.appendItem(QPDFObjectHandle::newInteger(run[pos].second));
                pos = rleEnd;
            } else {
                size_t segEnd = pos;
                while (segEnd < run.size() && (segEnd - pos) < kMaxWSubArray) {
                    size_t same = segEnd + 1;
                    while (same < run.size() && run[same].second == run[segEnd].second) {
                        ++same;
                    }
                    if (same - segEnd >= 3) {
                        break;
                    }
                    segEnd = std::min(same, pos + kMaxWSubArray);
                }
                if (segEnd == pos) segEnd = std::min(run.size(), pos + kMaxWSubArray);
                QPDFObjectHandle sub = QPDFObjectHandle::newArray();
                for (size_t k = pos; k < segEnd; ++k) {
                    sub.appendItem(QPDFObjectHandle::newInteger(run[k].second));
                }
                arr.appendItem(QPDFObjectHandle::newInteger(run[pos].first));
                arr.appendItem(sub);
                pos = segEnd;
            }
        }
    }
    return arr;
}

bool validateOrStripCorruptFontStreams(QPDFObjectHandle fd) {
    if (!fd.isDictionary()) return false;
    static const char* kKeys[] = {"/FontFile2", "/FontFile3", "/FontFile"};
    bool anyPresent = false;
    bool anyValid = false;
    for (const char* key : kKeys) {
        if (!fd.hasKey(key)) continue;
        anyPresent = true;
        QPDFObjectHandle st = fd.getKey(key);
        bool ok = false;
        if (st.isStream()) {
            std::vector<uint8_t> raw;
            if (readStreamBytesBounded(st, raw, qpdf_dl_generalized) && raw.size() >= 12) {
                ok = true;
            }
        }
        if (ok) {
            anyValid = true;
        } else {
            fd.removeKey(key);
        }
    }
    return anyPresent && anyValid;
}

QPDFObjectHandle FontRewriteContext::internFontStream(const std::vector<uint8_t>& bytes,
                                                      bool isCff) {
    if (bytes.empty()) return QPDFObjectHandle::newNull();
    uint64_t h = 14695981039346656037ULL ^ (isCff ? 0x9E3779B97F4A7C15ULL : 0ULL);
    for (uint8_t b : bytes) {
        h ^= b;
        h *= 1099511628211ULL;
    }
    auto& bucket = internedFontStreams[{bytes.size(), h}];
    for (const auto& entry : bucket) {
        if (entry.bytes == bytes) {
            return entry.stream;
        }
    }
    std::string s(reinterpret_cast<const char*>(bytes.data()), bytes.size());
    QPDFObjectHandle st = QPDFObjectHandle::newStream(&qpdf, s);
    if (isCff) {
        st.getDict().replaceKey("/Subtype", QPDFObjectHandle::newName("/CIDFontType0C"));
    } else {
        st.getDict().replaceKey("/Length1",
                                QPDFObjectHandle::newInteger(static_cast<long long>(bytes.size())));
    }
    bucket.push_back({bytes, st});
    return st;
}

QPDFObjectHandle FontRewriteContext::getDonorStream(const std::string& key, bool isCff) {
    std::string ck = key + (isCff ? "_cff" : "_ttf");
    auto it = embeddedDonorStreams.find(ck);
    if (it != embeddedDonorStreams.end()) return it->second;
    const DonorFont* d = getCachedDonor(ftLib, donorDirs, key);
    if (!d) return QPDFObjectHandle::newNull();
    const auto& bytes = isCff ? d->cff : d->raw;
    if (bytes.empty()) return QPDFObjectHandle::newNull();
    QPDFObjectHandle st = internFontStream(bytes, isCff);
    embeddedDonorStreams[ck] = st;
    return st;
}

void rewriteDocumentFonts(QPDF& qpdf, FT_Library ftLib, uint32_t flags, const char* donorDirs,
                          FontRewriteStats& stats, std::vector<std::string>& transforms,
                          std::map<QPDFObjGen, std::set<uint32_t>>* usedFontCodesOut,
                          std::set<QPDFObjGen>* donorSubstitutedType0Out) {
    // Materialize inherited /Resources onto every page before scanning or rewriting.
    try {
        QPDFPageDocumentHelper(qpdf).pushInheritedAttributesToPage();
    } catch (const std::exception& e) {
        std::fputs(e.what(), stderr);
        std::fputc('\n', stderr);
    } catch (...) {
        std::fputs("jpdfium: pushInheritedAttributesToPage failed\n", stderr);
    }

    FontRewriteContext ctx(qpdf, ftLib, flags, donorDirs);
    auto fonts = enumerateAllFonts(qpdf);

    // Stage 1b: Collect exact used character codes / CIDs per indirect font from all content
    // streams (pages after pushInheritedAttributesToPage, Form XObjects, Patterns, and Annotation
    // /AP streams), augmented by QPDF's parseContentStream usage index for Type3 / #xx-escaped font
    // names and variable-length CJK encodings (RKSJ/KSC-EUC/GB-EUC).
    const bool needUsedCodes =
        (flags & kFlagRepairNotdef) || (flags & (kFlagPdfALevelU | kFlagPdfALevelA));
    if (needUsedCodes) {
        collectAllUsedFontCodes(qpdf, ctx.usedFontCodes, ctx.type0FontGens);
        try {
            auto idx = jpdfium::qpdf_detail::buildFontUsageIndexes(qpdf);
            for (const auto& [og, res] : idx.simple.byFont) {
                auto& dst = ctx.usedFontCodes[og];
                dst.insert(res.codes.begin(), res.codes.end());
            }
            for (const auto& [og, res] : idx.cid.byFont) {
                if (!res.cids.empty()) {
                    auto& cidsDst = ctx.usedFontCids[og];
                    cidsDst.insert(res.cids.begin(), res.cids.end());
                }
                if (!res.codes.empty()) {
                    auto& codesDst = ctx.usedFontCodes[og];
                    codesDst.insert(res.codes.begin(), res.codes.end());
                }
            }
        } catch (const std::exception& e) {
            std::fputs(e.what(), stderr);
            std::fputc('\n', stderr);
        } catch (...) {
            std::fputs("jpdfium: buildFontUsageIndexes failed\n", stderr);
        }
    }

    // Stage 6 (global pass): Sanitize non-UTF-8 /BaseFont and /FontName entries across all dicts
    // once.
    if (flags & kFlagCleanMetadata) {
        int utf8FontIdx = 0;
        for (auto& obj : qpdf.getAllObjects()) {
            if (!obj.isDictionary()) continue;
            for (const char* nk : {"/BaseFont", "/FontName"}) {
                if (obj.hasKey(nk) && obj.getKey(nk).isName() &&
                    !isValidUtf8(obj.getKey(nk).getName())) {
                    obj.replaceKey(nk, QPDFObjectHandle::newName("/Utf8Font_" +
                                                                 std::to_string(utf8FontIdx++)));
                    ++ctx.metaFixedCount;
                }
            }
        }
    }

    for (auto& f : fonts) {
        if (!f.isDictionary()) continue;
        std::string sub = f.getKey("/Subtype").isName() ? f.getKey("/Subtype").getName() : "";
        std::string bf = f.getKey("/BaseFont").isName() ? f.getKey("/BaseFont").getName() : "";

        if (flags & kFlagCleanMetadata) {
            if (!f.getKey("/Type").isNameAndEquals("/Font") &&
                (sub == "/Type0" || sub == "/TrueType" || sub == "/Type1" || sub == "/MMType1" ||
                 sub == "/Type3" || sub == "/CIDFontType0" || sub == "/CIDFontType2")) {
                f.replaceKey("/Type", QPDFObjectHandle::newName("/Font"));
                ++ctx.metaFixedCount;
            }
        }

        if (sub == "/Type0") {
            rewriteType0Font(ctx, f, bf);
        } else {
            rewriteSimpleFont(ctx, f, sub, bf);
        }
    }

    // Count any fonts that still lack an embedded font program (used to gate pdfaid stamping).
    int unembeddedRemaining = 0;
    for (auto& f : fonts) {
        if (!f.isDictionary()) continue;
        std::string sub = f.getKey("/Subtype").isName() ? f.getKey("/Subtype").getName() : "";
        if (sub != "/Type0" && sub != "/TrueType" && sub != "/Type1" && sub != "/MMType1") continue;
        QPDFObjectHandle fd = f.getKey("/FontDescriptor");
        if (sub == "/Type0") {
            QPDFObjectHandle df = f.getKey("/DescendantFonts");
            if (df.isArray() && df.getArrayNItems() >= 1 && df.getArrayItem(0).isDictionary()) {
                fd = df.getArrayItem(0).getKey("/FontDescriptor");
            }
        }
        bool emb = fd.isDictionary() &&
                   (fd.hasKey("/FontFile") || fd.hasKey("/FontFile2") || fd.hasKey("/FontFile3"));
        if (!emb) ++unembeddedRemaining;
    }

    // Apply any content-stream character code remappings (e.g. CID 0 -> blankCid)
    if (!ctx.codeRemaps.empty()) {
        rewriteAllContentStreamCodes(qpdf, ctx.codeRemaps, ctx.type0FontGens);
    }

    if (ctx.embeddedCount > 0)
        transforms.push_back("embed_missing:" + std::to_string(ctx.embeddedCount));
    if (ctx.widthsFixedCount > 0)
        transforms.push_back("reconcile_widths:" + std::to_string(ctx.widthsFixedCount));
    if (ctx.cmapFixedCount > 0)
        transforms.push_back("normalize_cmaps:" + std::to_string(ctx.cmapFixedCount));
    if (ctx.notdefFixedCount > 0)
        transforms.push_back("repair_notdef:" + std::to_string(ctx.notdefFixedCount));
    if (ctx.metaFixedCount > 0)
        transforms.push_back("clean_metadata:" + std::to_string(ctx.metaFixedCount));

    stats.fontsAudited = static_cast<int>(fonts.size());
    stats.embeddedCount = ctx.embeddedCount;
    stats.widthsFixedCount = ctx.widthsFixedCount;
    stats.cmapFixedCount = ctx.cmapFixedCount;
    stats.notdefFixedCount = ctx.notdefFixedCount;
    stats.metaFixedCount = ctx.metaFixedCount;
    stats.unembeddedRemaining = unembeddedRemaining;
    if (usedFontCodesOut) {
        *usedFontCodesOut = std::move(ctx.usedFontCodes);
    }
    if (donorSubstitutedType0Out) {
        *donorSubstitutedType0Out = std::move(ctx.donorSubstitutedType0);
    }
}

}  // namespace jpdfium::rewriter
