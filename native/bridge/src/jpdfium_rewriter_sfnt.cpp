// jpdfium_rewriter_sfnt.cpp - SFNT/cmap/hmtx patching, CFF metric helpers,
// font buffer validation, and thread-safe system donor font cache.

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <limits>
#include <mutex>
#include <sstream>

#include "jpdfium_font_rewriter_internal.h"

namespace jpdfium::rewriter {

bool SfntFont::parse(const uint8_t* data, size_t len, SfntFont& out) {
    constexpr uint16_t kMaxSfntTables = 64;
    constexpr uint64_t kMaxTotalTableBytes = 64ULL * 1024ULL * 1024ULL;
    if (!data || len < 12) return false;
    uint32_t ver = readU32(data);
    if (ver != 0x00010000 && ver != 0x74727565 && ver != 0x4F54544F) return false;
    uint16_t numTables = readU16(data + 4);
    if (numTables == 0 || numTables > kMaxSfntTables) return false;
    if (12ULL + static_cast<uint64_t>(numTables) * 16ULL > len) return false;
    out.sfntVersion = ver;
    out.tables.clear();
    std::vector<std::pair<uint64_t, uint64_t>> ranges;
    ranges.reserve(numTables);
    uint64_t totalBytes = 0;
    for (uint16_t i = 0; i < numTables; ++i) {
        size_t rec = 12 + static_cast<size_t>(i) * 16;
        std::string tag(reinterpret_cast<const char*>(data + rec), 4);
        if (out.tables.count(tag)) return false;  // reject duplicate table tags
        uint32_t off = readU32(data + rec + 8);
        uint32_t tlen = readU32(data + rec + 12);
        uint64_t end = static_cast<uint64_t>(off) + static_cast<uint64_t>(tlen);
        if (end > len) return false;
        totalBytes += tlen;
        if (totalBytes > kMaxTotalTableBytes) return false;
        if (tlen > 0) ranges.push_back({static_cast<uint64_t>(off), end});
        out.tables[tag] = std::vector<uint8_t>(data + off, data + off + tlen);
    }
    // Reject overlapping table byte ranges (prevents N*size amplification)
    std::sort(ranges.begin(), ranges.end());
    for (size_t i = 1; i < ranges.size(); ++i) {
        if (ranges[i].first < ranges[i - 1].second) {
            out.tables.clear();
            return false;
        }
    }
    return true;
}

std::vector<uint8_t> SfntFont::serialize() {
    constexpr size_t kMaxSfntTables = 64;
    constexpr uint64_t kMaxTotalTableBytes = 64ULL * 1024ULL * 1024ULL;
    if (tables.empty() || tables.size() > kMaxSfntTables) return {};
    if (tables.count("head") && tables["head"].size() >= 12) {
        writeU32(tables["head"], 8, 0);
    }
    for (const auto& [tag, tdata] : tables) {
        if (tag.size() != 4) return {};
        if (tdata.size() > std::numeric_limits<uint32_t>::max()) return {};
    }
    uint16_t numTables = static_cast<uint16_t>(tables.size());
    uint32_t entrySelector = 0;
    uint32_t searchRange = 16;
    uint32_t tableDirBytes = static_cast<uint32_t>(numTables) * 16U;
    while (searchRange * 2U <= tableDirBytes) {
        searchRange *= 2U;
        entrySelector++;
    }
    uint16_t rangeShift = static_cast<uint16_t>(tableDirBytes - searchRange);

    std::vector<uint8_t> out;
    appendU32(out, sfntVersion);
    appendU16(out, numTables);
    appendU16(out, static_cast<uint16_t>(searchRange));
    appendU16(out, static_cast<uint16_t>(entrySelector));
    appendU16(out, rangeShift);

    size_t dirStart = out.size();
    out.resize(dirStart + static_cast<size_t>(numTables) * 16, 0);

    size_t headOffset = 0;
    size_t headLen = 0;
    size_t idx = 0;
    for (const auto& [tag, tdata] : tables) {
        while (out.size() % 4 != 0) out.push_back(0);
        if (static_cast<uint64_t>(out.size()) + tdata.size() + 4ULL > kMaxTotalTableBytes) {
            return {};
        }
        uint32_t off = static_cast<uint32_t>(out.size());
        if (tag == "head") {
            headOffset = off;
            headLen = tdata.size();
        }
        uint32_t csum = sfntChecksum(tdata.data(), tdata.size());
        uint32_t tlen = static_cast<uint32_t>(tdata.size());
        size_t rec = dirStart + idx * 16;
        std::memcpy(out.data() + rec, tag.data(), 4);
        writeU32(out, rec + 4, csum);
        writeU32(out, rec + 8, off);
        writeU32(out, rec + 12, tlen);
        out.insert(out.end(), tdata.begin(), tdata.end());
        ++idx;
    }
    while (out.size() % 4 != 0) out.push_back(0);

    if (headOffset > 0 && headLen >= 12 && headOffset + 12 <= out.size()) {
        uint32_t totalSum = sfntChecksum(out.data(), out.size());
        uint32_t adj = 0xB1B0AFBAU - totalSum;
        writeU32(out, headOffset + 8, adj);
    }
    return out;
}

std::vector<uint8_t> buildCmapFormat4(const std::map<uint16_t, uint16_t>& mapping) {
    struct Seg {
        uint16_t start;
        uint16_t end;
        int16_t delta;
    };
    std::vector<Seg> segs;
    for (const auto& [code, gid] : mapping) {
        if (code == 0xFFFF) continue;
        int16_t delta = static_cast<int16_t>((gid - code) & 0xFFFF);
        if (!segs.empty() && segs.back().end + 1 == code && segs.back().delta == delta) {
            segs.back().end = code;
        } else {
            segs.push_back({code, code, delta});
        }
    }
    segs.push_back({0xFFFF, 0xFFFF, 1});
    if (segs.size() >= 8190 || 16ULL + 8ULL * segs.size() > 0xFFFFULL) return {};

    // Format 4 stores its length in a uint16 and each segment costs 8 bytes.
    // Refuse mappings that cannot be represented instead of truncating the length.
    constexpr size_t kMaxFormat4Segments = (0xFFFFU - 14U) / 8U;
    if (segs.size() > kMaxFormat4Segments) return {};

    uint16_t segCount = static_cast<uint16_t>(segs.size());
    uint16_t segCountX2 = static_cast<uint16_t>(segCount * 2);
    uint16_t entrySelector = 0;
    uint16_t searchRange = 2;
    while (searchRange * 2 <= segCountX2) {
        searchRange *= 2;
        entrySelector++;
    }
    uint16_t rangeShift = static_cast<uint16_t>(segCountX2 - searchRange);

    std::vector<uint8_t> sub;
    appendU16(sub, 4);  // format
    appendU16(sub, 0);  // length placeholder
    appendU16(sub, 0);  // language
    appendU16(sub, segCountX2);
    appendU16(sub, searchRange);
    appendU16(sub, entrySelector);
    appendU16(sub, rangeShift);
    for (const auto& s : segs) appendU16(sub, s.end);
    appendU16(sub, 0);  // reservedPad
    for (const auto& s : segs) appendU16(sub, s.start);
    for (const auto& s : segs) appendU16(sub, static_cast<uint16_t>(s.delta));
    for (size_t i = 0; i < segs.size(); ++i) appendU16(sub, 0);  // idRangeOffset
    if (sub.size() > 0xFFFFU) return {};
    writeU16(sub, 2, static_cast<uint16_t>(sub.size()));
    return sub;
}

std::vector<CmapSubtable> parseCmapSubtables(const std::vector<uint8_t>& cmap) {
    constexpr uint16_t kMaxCmapSubtables = 64;
    constexpr uint64_t kMaxTotalCmapBytes = 32ULL * 1024ULL * 1024ULL;
    std::vector<CmapSubtable> subs;
    if (cmap.size() < 4) return subs;
    uint16_t num = readU16(cmap.data() + 2);
    if (num > kMaxCmapSubtables) num = kMaxCmapSubtables;
    if (4ULL + static_cast<uint64_t>(num) * 8ULL > cmap.size()) return subs;
    uint64_t totalCopied = 0;
    for (uint16_t i = 0; i < num; ++i) {
        size_t rec = 4 + static_cast<size_t>(i) * 8;
        uint16_t plat = readU16(cmap.data() + rec);
        uint16_t enc = readU16(cmap.data() + rec + 2);
        uint64_t off = static_cast<uint64_t>(readU32(cmap.data() + rec + 4));
        if (off + 4ULL > cmap.size()) continue;
        uint16_t fmt = readU16(cmap.data() + off);
        uint64_t slen = 0;
        if (fmt == 0 || fmt == 2 || fmt == 4 || fmt == 6) {
            slen = readU16(cmap.data() + off + 2);
        } else if ((fmt == 8 || fmt == 10 || fmt == 12 || fmt == 13) && off + 8ULL <= cmap.size()) {
            slen = readU32(cmap.data() + off + 4);
        } else if (fmt == 14 && off + 6ULL <= cmap.size()) {
            slen = readU32(cmap.data() + off + 2);
        }
        if (slen == 0 || off + slen > cmap.size()) continue;
        if (totalCopied + slen > kMaxTotalCmapBytes) break;
        totalCopied += slen;
        subs.push_back({plat, enc,
                        std::vector<uint8_t>(cmap.begin() + static_cast<ptrdiff_t>(off),
                                             cmap.begin() + static_cast<ptrdiff_t>(off + slen))});
    }
    return subs;
}

std::vector<uint8_t> serializeCmapTable(std::vector<CmapSubtable> subs) {
    std::sort(subs.begin(), subs.end(), [](const CmapSubtable& a, const CmapSubtable& b) {
        return std::tie(a.platformId, a.encodingId) < std::tie(b.platformId, b.encodingId);
    });
    // An encoding record's offset is uint32 and an empty subtable contributes no
    // valid cmap entry; drop both rather than emitting a malformed table.
    subs.erase(std::remove_if(subs.begin(), subs.end(),
                              [](const CmapSubtable& s) { return s.data.empty(); }),
               subs.end());
    if (subs.size() > 0xFFFFU) return {};
    std::vector<uint8_t> out;
    appendU16(out, 0);  // version
    appendU16(out, static_cast<uint16_t>(subs.size()));
    size_t dirStart = out.size();
    out.resize(dirStart + subs.size() * 8, 0);
    for (size_t i = 0; i < subs.size(); ++i) {
        uint32_t off = static_cast<uint32_t>(out.size());
        size_t rec = dirStart + i * 8;
        writeU16(out, rec, subs[i].platformId);
        writeU16(out, rec + 2, subs[i].encodingId);
        writeU32(out, rec + 4, off);
        out.insert(out.end(), subs[i].data.begin(), subs[i].data.end());
    }
    return out;
}

bool sfntEnsureUnicodeCmap(std::vector<uint8_t>& fontBytes, FT_Face face) {
    if (!face) return false;
    SfntFont sf;
    if (!SfntFont::parse(fontBytes.data(), fontBytes.size(), sf)) return false;

    std::map<uint16_t, uint16_t> unicodeMap;
    FT_UInt ng = static_cast<FT_UInt>(std::min<FT_Long>(face->num_glyphs, 65535));
    if (!FT_HAS_GLYPH_NAMES(face)) return false;
    for (FT_UInt gid = 1; gid < ng; ++gid) {
        char gname[128] = {};
        if (FT_Get_Glyph_Name(face, gid, gname, sizeof(gname)) != 0 || gname[0] == '\0') continue;
        uint32_t u = aglNameToUnicode(gname);
        if (u == 0 || u > 0xFFFF || unicodeMap.count(static_cast<uint16_t>(u))) continue;
        unicodeMap[static_cast<uint16_t>(u)] = static_cast<uint16_t>(gid);
    }
    if (unicodeMap.empty()) return false;

    std::vector<CmapSubtable> subs;
    if (sf.tables.count("cmap")) {
        subs = parseCmapSubtables(sf.tables["cmap"]);
        subs.erase(std::remove_if(subs.begin(), subs.end(),
                                  [](const CmapSubtable& s) {
                                      return (s.platformId == 3 && s.encodingId == 1) ||
                                             (s.platformId == 0 && s.encodingId == 3);
                                  }),
                   subs.end());
    }
    auto unicodeSubtable = buildCmapFormat4(unicodeMap);
    if (unicodeSubtable.empty()) return false;
    subs.push_back({3, 1, std::move(unicodeSubtable)});
    sf.tables["cmap"] = serializeCmapTable(std::move(subs));
    if (sf.tables["cmap"].empty()) return false;
    fontBytes = sf.serialize();
    return !fontBytes.empty();
}

bool sfntPruneSymbolicCmap(std::vector<uint8_t>& fontBytes) {
    SfntFont sf;
    if (!SfntFont::parse(fontBytes.data(), fontBytes.size(), sf)) return false;
    auto it = sf.tables.find("cmap");
    if (it == sf.tables.end()) return false;
    std::vector<CmapSubtable> subs = parseCmapSubtables(it->second);
    if (subs.size() <= 1) return false;

    // Prefer the Microsoft symbolic subtable PDF renderers use for symbolic fonts,
    // then Apple Roman, then whatever the font provides.
    size_t keep = 0;
    bool found30 = false;
    for (size_t i = 0; i < subs.size(); ++i) {
        if (subs[i].platformId == 3 && subs[i].encodingId == 0) {
            keep = i;
            found30 = true;
            break;
        }
    }
    if (!found30) {
        for (size_t i = 0; i < subs.size(); ++i) {
            if (subs[i].platformId == 1 && subs[i].encodingId == 0) {
                keep = i;
                break;
            }
        }
    }
    CmapSubtable chosen = std::move(subs[keep]);
    subs.clear();
    subs.push_back(std::move(chosen));
    it->second = serializeCmapTable(std::move(subs));
    if (it->second.empty()) return false;
    fontBytes = sf.serialize();
    return !fontBytes.empty();
}

bool sfntEnsureCmap30(std::vector<uint8_t>& fontBytes, FT_Face face,
                      const std::set<uint32_t>* usedCodes, bool singleSubtableOnly) {
    SfntFont sf;
    if (!SfntFont::parse(fontBytes.data(), fontBytes.size(), sf)) return false;
    std::vector<CmapSubtable> subs;
    bool has30 = false;
    if (sf.tables.count("cmap")) {
        subs = parseCmapSubtables(sf.tables["cmap"]);
        for (const auto& s : subs) {
            if (s.platformId == 3 && s.encodingId == 0) has30 = true;
        }
    }
    uint16_t ng = face ? static_cast<uint16_t>(std::min<FT_Long>(face->num_glyphs, 65535)) : 256;
    std::map<uint16_t, uint16_t> map30;
    bool mergedAny = false;
    if (has30 && face) {
        // Read existing (3,0) entries via FreeType
        if (FT_Select_Charmap(face, FT_ENCODING_MS_SYMBOL) == 0) {
            for (uint16_t code = 0; code < 256; ++code) {
                FT_UInt g = FT_Get_Char_Index(face, 0xF000U + code);
                if (g == 0) g = FT_Get_Char_Index(face, code);
                if (g > 0 && g < ng)
                    map30[static_cast<uint16_t>(0xF000U + code)] = static_cast<uint16_t>(g);
            }
        }
        // Merge any missing codes from (1,0) Apple Roman into (3,0) at 0xF000+code
        if (FT_Select_Charmap(face, FT_ENCODING_APPLE_ROMAN) == 0) {
            for (uint16_t code = 0; code < 256; ++code) {
                uint16_t key = static_cast<uint16_t>(0xF000U + code);
                if (map30.count(key)) continue;
                FT_UInt g = FT_Get_Char_Index(face, code);
                if (g > 0 && g < ng) {
                    map30[key] = static_cast<uint16_t>(g);
                    mergedAny = true;
                }
            }
        }
        // Also ensure any used code missing from (3,0) maps to a valid glyph
        if (usedCodes) {
            for (uint32_t uc : *usedCodes) {
                if (uc == 0 || uc > 255) continue;
                uint16_t key = static_cast<uint16_t>(0xF000U + uc);
                if (!map30.count(key) && ng > 1) {
                    uint16_t g = (uc < ng) ? static_cast<uint16_t>(uc) : 1;
                    map30[key] = g;
                    mergedAny = true;
                }
            }
        }
        if (!mergedAny) {
            if (singleSubtableOnly && subs.size() > 1) {
                subs.erase(std::remove_if(subs.begin(), subs.end(),
                                          [](const CmapSubtable& s) {
                                              return !(s.platformId == 3 && s.encodingId == 0);
                                          }),
                           subs.end());
                sf.tables["cmap"] = serializeCmapTable(std::move(subs));
                std::vector<uint8_t> serialized = sf.serialize();
                if (serialized.empty()) return false;
                fontBytes = std::move(serialized);
                return true;
            }
            return false;
        }
        // Replace existing (3,0) subtable with merged version
        subs.erase(std::remove_if(subs.begin(), subs.end(),
                                  [](const CmapSubtable& s) {
                                      return s.platformId == 3 && s.encodingId == 0;
                                  }),
                   subs.end());
    } else if (has30) {
        if (singleSubtableOnly && subs.size() > 1) {
            subs.erase(std::remove_if(subs.begin(), subs.end(),
                                      [](const CmapSubtable& s) {
                                          return !(s.platformId == 3 && s.encodingId == 0);
                                      }),
                       subs.end());
            sf.tables["cmap"] = serializeCmapTable(std::move(subs));
            std::vector<uint8_t> serialized = sf.serialize();
            if (serialized.empty()) return false;
            fontBytes = std::move(serialized);
            return true;
        }
        return false;
    } else {
        for (uint16_t code = 0; code < 256; ++code) {
            uint16_t gid = 0;
            if (face) {
                if (FT_Select_Charmap(face, FT_ENCODING_APPLE_ROMAN) == 0) {
                    gid = static_cast<uint16_t>(FT_Get_Char_Index(face, code));
                }
                if (gid == 0 && FT_Select_Charmap(face, FT_ENCODING_UNICODE) == 0) {
                    gid = static_cast<uint16_t>(FT_Get_Char_Index(face, 0xF000U + code));
                    if (gid == 0) gid = static_cast<uint16_t>(FT_Get_Char_Index(face, code));
                }
            }
            if (gid == 0 && code < ng) gid = code;
            if (gid > 0 && gid < ng) {
                map30[static_cast<uint16_t>(0xF000U + code)] = gid;
            }
        }
        if (map30.empty() && ng > 1) map30[0xF020] = 1;
    }
    std::vector<uint8_t> fmt4 = buildCmapFormat4(map30);
    if (fmt4.empty()) return false;
    if (singleSubtableOnly) subs.clear();
    subs.push_back({3, 0, std::move(fmt4)});
    sf.tables["cmap"] = serializeCmapTable(std::move(subs));
    std::vector<uint8_t> serialized = sf.serialize();
    if (serialized.empty()) return false;
    fontBytes = std::move(serialized);
    return true;
}

// Patch advance widths in a TrueType SFNT hmtx table so the embedded font program matches
// the PDF /Widths or /W dictionary without altering PDF glyph positioning or text extraction.
bool sfntPatchHmtx(std::vector<uint8_t>& fontBytes,
                   const std::map<uint16_t, uint16_t>& gidAdvances) {
    if (gidAdvances.empty()) return false;
    SfntFont sf;
    if (!SfntFont::parse(fontBytes.data(), fontBytes.size(), sf)) return false;
    if (!sf.tables.count("maxp") || !sf.tables.count("hhea") || !sf.tables.count("hmtx"))
        return false;
    auto& maxp = sf.tables["maxp"];
    auto& hhea = sf.tables["hhea"];
    auto& hmtx = sf.tables["hmtx"];
    if (maxp.size() < 6 || hhea.size() < 36) return false;

    uint16_t numGlyphs = readU16(maxp.data() + 4);
    uint16_t numHMetrics = readU16(hhea.data() + 34);
    if (numGlyphs == 0 || hmtx.size() < static_cast<size_t>(numHMetrics) * 4) return false;

    uint16_t lastAdv =
        numHMetrics > 0 ? readU16(hmtx.data() + (static_cast<size_t>(numHMetrics) - 1) * 4) : 0;
    std::vector<uint8_t> newHmtx;
    newHmtx.reserve(static_cast<size_t>(numGlyphs) * 4);
    for (uint16_t g = 0; g < numGlyphs; ++g) {
        if (g < numHMetrics) {
            const auto base = static_cast<std::ptrdiff_t>(g) * 4;
            newHmtx.insert(newHmtx.end(), hmtx.begin() + base, hmtx.begin() + base + 4);
        } else {
            appendU16(newHmtx, lastAdv);
            size_t lsbOff =
                static_cast<size_t>(numHMetrics) * 4 + static_cast<size_t>(g - numHMetrics) * 2;
            uint16_t lsb = (lsbOff + 2 <= hmtx.size()) ? readU16(hmtx.data() + lsbOff) : 0;
            appendU16(newHmtx, lsb);
        }
    }
    bool patched = false;
    for (const auto& [gid, adv] : gidAdvances) {
        if (gid < numGlyphs) {
            writeU16(newHmtx, static_cast<size_t>(gid) * 4, adv);
            patched = true;
        }
    }
    if (!patched) return false;
    hmtx = std::move(newHmtx);
    writeU16(hhea, 34, numGlyphs);
    std::vector<uint8_t> serialized = sf.serialize();
    if (serialized.empty()) return false;
    fontBytes = std::move(serialized);
    return true;
}

// Append a zero-outline blank glyph to a TrueType SFNT at newGid = old numGlyphs.
// Returns newGid on success, or 0 on failure.
uint16_t sfntAppendBlankGlyph(std::vector<uint8_t>& fontBytes, uint16_t advanceUnits) {
    SfntFont sf;
    if (!SfntFont::parse(fontBytes.data(), fontBytes.size(), sf)) return 0;
    if (!sf.tables.count("maxp") || !sf.tables.count("hhea") || !sf.tables.count("hmtx") ||
        !sf.tables.count("head") || !sf.tables.count("loca") || !sf.tables.count("glyf")) {
        return 0;
    }
    auto& maxp = sf.tables["maxp"];
    auto& hhea = sf.tables["hhea"];
    auto& hmtx = sf.tables["hmtx"];
    auto& head = sf.tables["head"];
    auto& loca = sf.tables["loca"];
    if (maxp.size() < 6 || hhea.size() < 36 || head.size() < 52) return 0;

    uint16_t numGlyphs = readU16(maxp.data() + 4);
    if (numGlyphs == 0 || numGlyphs >= 65534) return 0;
    uint16_t newGid = numGlyphs;
    uint16_t numHMetrics = readU16(hhea.data() + 34);
    uint16_t indexToLocFormat = readU16(head.data() + 50);

    // Rebuild hmtx with full longHorMetric for every glyph + newGid
    if (hmtx.size() < static_cast<size_t>(numHMetrics) * 4) return 0;
    uint16_t lastAdv =
        numHMetrics > 0 ? readU16(hmtx.data() + (static_cast<size_t>(numHMetrics) - 1) * 4) : 0;
    std::vector<uint8_t> newHmtx;
    newHmtx.reserve((static_cast<size_t>(numGlyphs) + 1) * 4);
    for (uint16_t g = 0; g < numGlyphs; ++g) {
        if (g < numHMetrics) {
            const auto base = static_cast<std::ptrdiff_t>(g) * 4;
            newHmtx.insert(newHmtx.end(), hmtx.begin() + base, hmtx.begin() + base + 4);
        } else {
            appendU16(newHmtx, lastAdv);
            size_t lsbOff =
                static_cast<size_t>(numHMetrics) * 4 + static_cast<size_t>(g - numHMetrics) * 2;
            uint16_t lsb = (lsbOff + 2 <= hmtx.size()) ? readU16(hmtx.data() + lsbOff) : 0;
            appendU16(newHmtx, lsb);
        }
    }
    appendU16(newHmtx, advanceUnits);
    appendU16(newHmtx, 0);

    // Extend loca by 1 empty glyph entry (same offset as last entry -> 0 bytes in glyf)
    if (indexToLocFormat == 0) {
        if (loca.size() < (static_cast<size_t>(numGlyphs) + 1) * 2) return 0;
        uint16_t lastOff = readU16(loca.data() + static_cast<size_t>(numGlyphs) * 2);
        appendU16(loca, lastOff);
    } else if (indexToLocFormat == 1) {
        if (loca.size() < (static_cast<size_t>(numGlyphs) + 1) * 4) return 0;
        uint32_t lastOff = readU32(loca.data() + static_cast<size_t>(numGlyphs) * 4);
        appendU32(loca, lastOff);
    } else {
        return 0;
    }

    writeU16(maxp, 4, static_cast<uint16_t>(numGlyphs + 1));
    writeU16(hhea, 34, static_cast<uint16_t>(numGlyphs + 1));
    hmtx = std::move(newHmtx);

    // Remove post format 2.0 glyph-name count mismatch by setting format 3.0
    if (sf.tables.count("post") && sf.tables["post"].size() >= 32) {
        std::vector<uint8_t> post3(sf.tables["post"].begin(), sf.tables["post"].begin() + 32);
        writeU32(post3, 0, 0x00030000);
        sf.tables["post"] = std::move(post3);
    }

    std::vector<uint8_t> serialized = sf.serialize();
    if (serialized.empty()) return 0;
    fontBytes = std::move(serialized);
    return newGid;
}

std::string toLowerAscii(std::string s) {
    for (char& c : s) {
        if (c >= 'A' && c <= 'Z') c = static_cast<char>(c - 'A' + 'a');
    }
    return s;
}

std::string xmlEscape(std::string_view s) {
    std::string out;
    out.reserve(s.size() + 16);
    for (unsigned char c : s) {
        if (c == '&')
            out += "&amp;";
        else if (c == '<')
            out += "&lt;";
        else if (c == '>')
            out += "&gt;";
        else if (c == '"')
            out += "&quot;";
        else if (c == '\'')
            out += "&apos;";
        else if (c >= 0x20 || c == '\n' || c == '\r' || c == '\t')
            out.push_back(static_cast<char>(c));
    }
    return out;
}

bool loadFileBytes(const std::string& path, std::vector<uint8_t>& out) {
    std::ifstream ifs(path, std::ios::binary);
    if (!ifs) return false;
    ifs.seekg(0, std::ios::end);
    auto sz = ifs.tellg();
    constexpr std::streamoff kMaxDonorBytes = 64LL * 1024LL * 1024LL;
    if (sz <= 0 || sz > kMaxDonorBytes) return false;
    ifs.seekg(0, std::ios::beg);
    out.resize(static_cast<size_t>(sz));
    return static_cast<bool>(
        ifs.read(reinterpret_cast<char*>(out.data()), static_cast<std::streamsize>(sz)));
}

bool loadDonorFont(FT_Library ftLib, const std::vector<std::string>& searchDirs,
                   const std::vector<std::string>& filenames, DonorFont& out) {
    if (!ftLib) return false;
    for (const auto& dir : searchDirs) {
        for (const auto& fn : filenames) {
            std::string p = dir;
            p.push_back('/');
            p += fn;
            std::vector<uint8_t> raw;
            if (!loadFileBytes(p, raw) || !isSafeFontBuffer(raw)) continue;
            FT_Face rawFace = nullptr;
            if (FT_New_Memory_Face(ftLib, raw.data(), static_cast<FT_Long>(raw.size()), 0,
                                   &rawFace) != 0 ||
                !rawFace) {
                continue;
            }
            jpdfium::font::FtFaceGuard faceGuard(rawFace);
            FT_Face face = faceGuard.face;
            // Enforce OS/2 fsType licensing: never embed Restricted License fonts (fsType & 0x000F
            // == 0x0002).
            {
                FT_ULong os2Len = 0;
                if (FT_Load_Sfnt_Table(face, FT_MAKE_TAG('O', 'S', '/', '2'), 0, nullptr,
                                       &os2Len) == 0 &&
                    os2Len >= 10) {
                    std::vector<uint8_t> os2(10);
                    FT_ULong toRead = 10;
                    if (FT_Load_Sfnt_Table(face, FT_MAKE_TAG('O', 'S', '/', '2'), 0, os2.data(),
                                           &toRead) == 0 &&
                        toRead >= 10) {
                        uint16_t fsType = readU16(os2.data() + 8);
                        if ((fsType & 0x000FU) == 0x0002U) {
                            continue;
                        }
                    }
                }
            }
            out.path = p;
            out.upem = face->units_per_EM > 0 ? face->units_per_EM : 1000;
            auto scale = [&](FT_Long v) -> int {
                return static_cast<int>(std::llround(static_cast<double>(v) * 1000.0 / out.upem));
            };
            out.ascent = scale(face->ascender);
            out.descent = scale(face->descender);
            out.capHeight = out.ascent;
            out.bbox[0] = scale(face->bbox.xMin);
            out.bbox[1] = scale(face->bbox.yMin);
            out.bbox[2] = scale(face->bbox.xMax);
            out.bbox[3] = scale(face->bbox.yMax);

            const FT_Long clampedNg = std::clamp<FT_Long>(face->num_glyphs, 0, 65536);
            const FT_ULong ng = static_cast<FT_ULong>(clampedNg);
            out.gidWidths.resize(ng, 0);
            for (FT_ULong g = 0; g < ng; ++g) {
                FT_Fixed adv = 0;
                if (FT_Get_Advance(face, static_cast<FT_UInt>(g),
                                   FT_LOAD_NO_SCALE | FT_LOAD_NO_HINTING, &adv) == 0) {
                    out.gidWidths[g] = scale(adv);
                }
            }
            out.winAnsiWidths.resize(256, 0);
            FT_Fixed notdefAdv = 0;
            int notdefW = 0;
            if (FT_Get_Advance(face, 0, FT_LOAD_NO_SCALE | FT_LOAD_NO_HINTING, &notdefAdv) == 0) {
                notdefW = scale(notdefAdv);
            }
            for (int c = 0; c < 256; ++c) {
                FT_UInt gid = FT_Get_Char_Index(face, winAnsiToUnicode(static_cast<uint8_t>(c)));
                if (gid > 0 && gid < ng) {
                    out.winAnsiWidths[static_cast<size_t>(c)] = out.gidWidths[gid];
                } else {
                    out.winAnsiWidths[static_cast<size_t>(c)] = notdefW;
                }
            }
            // Pre-cache BMP Unicode -> GID table for O(1) UCS2 CIDFont lookups.
            if (FT_Select_Charmap(face, FT_ENCODING_UNICODE) == 0) {
                out.bmpUnicodeToGid.assign(65536, 0);
                FT_UInt g = 0;
                FT_ULong cp = FT_Get_First_Char(face, &g);
                while (g != 0) {
                    if (cp < 65536 && g < 65535) {
                        out.bmpUnicodeToGid[static_cast<size_t>(cp)] = static_cast<uint16_t>(g);
                    }
                    cp = FT_Get_Next_Char(face, cp, &g);
                }
            }
            // Extract CFF table directly without copying all SFNT tables.
            FT_ULong cffLen = 0;
            if (FT_Load_Sfnt_Table(face, FT_MAKE_TAG('C', 'F', 'F', ' '), 0, nullptr, &cffLen) ==
                    0 &&
                cffLen > 0 && cffLen <= 64UL * 1024UL * 1024UL) {
                out.cff.resize(cffLen);
                FT_ULong readLen = cffLen;
                if (FT_Load_Sfnt_Table(face, FT_MAKE_TAG('C', 'F', 'F', ' '), 0, out.cff.data(),
                                       &readLen) != 0 ||
                    readLen != cffLen) {
                    out.cff.clear();
                }
            }
            faceGuard.reset();
            out.raw = std::move(raw);
            out.loaded = true;
            return true;
        }
    }
    return false;
}

bool isValidUtf8(const std::string& s) {
    size_t i = 0;
    size_t n = s.size();
    while (i < n) {
        unsigned char c = static_cast<unsigned char>(s[i]);
        if (c < 0x80) {
            ++i;
            continue;
        }
        int need = 0;
        uint32_t cp = 0;
        if ((c & 0xE0) == 0xC0) {
            need = 1;
            cp = c & 0x1F;
        } else if ((c & 0xF0) == 0xE0) {
            need = 2;
            cp = c & 0x0F;
        } else if ((c & 0xF8) == 0xF0) {
            need = 3;
            cp = c & 0x07;
        } else
            return false;
        if (i + need >= n) return false;
        for (int k = 1; k <= need; ++k) {
            unsigned char b = static_cast<unsigned char>(s[i + k]);
            if ((b & 0xC0) != 0x80) return false;
            cp = (cp << 6) | (b & 0x3F);
        }
        if ((need == 1 && cp < 0x80) || (need == 2 && cp < 0x800) ||
            (need == 3 && (cp < 0x10000 || cp > 0x10FFFF)) || (cp >= 0xD800 && cp <= 0xDFFF)) {
            return false;
        }
        i += need + 1;
    }
    return true;
}

// Decode a CFF real-number operand starting after 0x1E; returns parsed double or 0 on failure.
double decodeCffReal(const std::vector<uint8_t>& cff, size_t pos) {
    std::string num;
    for (size_t i = pos; i < cff.size() && num.size() < 32; ++i) {
        uint8_t b = cff[i];
        for (int shift = 4; shift >= 0; shift -= 4) {
            uint8_t nib = (b >> shift) & 0x0F;
            if (nib <= 9)
                num.push_back(static_cast<char>('0' + nib));
            else if (nib == 0x0A)
                num.push_back('.');
            else if (nib == 0x0B)
                num.push_back('e');
            else if (nib == 0x0C)
                num += "e-";
            else if (nib == 0x0E)
                num.push_back('-');
            else if (nib == 0x0F) {
                char* end = nullptr;
                double v = std::strtod(num.c_str(), &end);
                return (end && end != num.c_str() && std::isfinite(v)) ? v : 0.0;
            }
        }
    }
    return 0.0;
}

// Detect a CFF FDArray FontMatrix [sx 0 0 sy 0 0] with non-0.001 scale (e.g. 0.00048828 for 2048
// upem) where FreeType's TopDict scale stayed 0.001. Require trailing 0 0 (0x8B 0x8B) before 0x0C
// 0x07 so random CharString bytes are never mistaken for a FontMatrix.
double cffFdScaleFactor(const std::vector<uint8_t>& cff) {
    auto scanRange = [&](size_t start, size_t end) -> double {
        for (size_t i = std::max<size_t>(start, 6); i + 1 < end; ++i) {
            if (cff[i] == 0x0C && cff[i + 1] == 0x07 && cff[i - 1] == 0x8B && cff[i - 2] == 0x8B) {
                size_t lo = i > 48 ? i - 48 : 0;
                for (size_t j = lo; j < i; ++j) {
                    if (cff[j] == 0x1E) {
                        double sx = decodeCffReal(cff, j + 1);
                        if (sx >= 0.0001 && sx <= 0.005 && std::abs(sx - 0.001) > 1e-5) {
                            return sx * 1000.0;
                        }
                        break;
                    }
                }
            }
        }
        return 1.0;
    };
    // Scan Top DICT near start and FDArray near end (in CFF layout, FDArray follows CharStrings)
    double s = scanRange(6, std::min<size_t>(cff.size(), 4096));
    if (std::abs(s - 1.0) > 1e-5) return s;
    if (cff.size() > 4096) {
        size_t tailStart = cff.size() > 16384 ? cff.size() - 16384 : 4096;
        s = scanRange(tailStart, cff.size());
        if (std::abs(s - 1.0) > 1e-5) return s;
    }
    return 1.0;
}

namespace {

bool isSafeSfntAt(const uint8_t* p, size_t n, size_t base) {
    if (base > n || n - base < 12) return false;
    const uint16_t numTables = static_cast<uint16_t>((p[base + 4] << 8) | p[base + 5]);
    if (numTables == 0 || numTables > 256) return false;
    if (12U + static_cast<size_t>(numTables) * 16U > n - base) return false;
    for (uint16_t i = 0; i < numTables; ++i) {
        const size_t rec = base + 12U + static_cast<size_t>(i) * 16U;
        const uint32_t off =
            (static_cast<uint32_t>(p[rec + 8]) << 24) | (static_cast<uint32_t>(p[rec + 9]) << 16) |
            (static_cast<uint32_t>(p[rec + 10]) << 8) | static_cast<uint32_t>(p[rec + 11]);
        const uint32_t len = (static_cast<uint32_t>(p[rec + 12]) << 24) |
                             (static_cast<uint32_t>(p[rec + 13]) << 16) |
                             (static_cast<uint32_t>(p[rec + 14]) << 8) |
                             static_cast<uint32_t>(p[rec + 15]);
        if (static_cast<uint64_t>(off) + static_cast<uint64_t>(len) > n) return false;
        if (p[rec] == 'h' && p[rec + 1] == 'e' && p[rec + 2] == 'a' && p[rec + 3] == 'd' &&
            len >= 20) {
            const uint16_t upem = static_cast<uint16_t>((p[off + 18] << 8) | p[off + 19]);
            if (upem == 0) return false;
        }
    }
    return true;
}

bool isSafeCffIndex(const uint8_t* p, size_t n, size_t pos, bool isCff2, size_t& nextPos) {
    const size_t countBytes = isCff2 ? 4U : 2U;
    if (pos > n || n - pos < countBytes) return false;
    uint32_t count = 0;
    if (isCff2) {
        count = (static_cast<uint32_t>(p[pos]) << 24) | (static_cast<uint32_t>(p[pos + 1]) << 16) |
                (static_cast<uint32_t>(p[pos + 2]) << 8) | static_cast<uint32_t>(p[pos + 3]);
    } else {
        count = (static_cast<uint32_t>(p[pos]) << 8) | static_cast<uint32_t>(p[pos + 1]);
    }
    if (count == 0) {
        nextPos = pos + countBytes;
        return true;
    }
    if (count > 65536U) return false;
    if (n - pos - countBytes < 1) return false;
    const uint8_t offSize = p[pos + countBytes];
    if (offSize < 1 || offSize > 4) return false;
    const uint64_t offArrayBytes = static_cast<uint64_t>(count + 1U) * offSize;
    const size_t hdrEnd = pos + countBytes + 1U;
    if (offArrayBytes > n - hdrEnd) return false;
    auto readOff = [&](uint32_t idx) -> uint32_t {
        const size_t q = hdrEnd + static_cast<size_t>(idx) * offSize;
        uint32_t v = 0;
        for (uint8_t k = 0; k < offSize; ++k) v = (v << 8) | p[q + k];
        return v;
    };
    const uint32_t firstOff = readOff(0);
    const uint32_t lastOff = readOff(count);
    if (firstOff < 1 || lastOff < firstOff) return false;
    const size_t dataOrigin = hdrEnd + static_cast<size_t>(offArrayBytes) - 1U;
    if (lastOff > n - dataOrigin) return false;
    nextPos = dataOrigin + lastOff;
    return true;
}

}  // namespace

// Pre-validate untrusted PDF font stream buffers before passing to FreeType so
// truncated/malformed headers or out-of-bounds SFNT table directories cannot crash FT.
bool isSafeFontBuffer(const std::vector<uint8_t>& buf) {
    const size_t n = buf.size();
    if (n < 12 || n > size_t{128} * 1024U * 1024U) return false;
    const uint8_t* p = buf.data();
    uint32_t tag = (static_cast<uint32_t>(p[0]) << 24) | (static_cast<uint32_t>(p[1]) << 16) |
                   (static_cast<uint32_t>(p[2]) << 8) | static_cast<uint32_t>(p[3]);
    // SFNT (TrueType 0x00010000 / 'true' / 'typ1' / OpenType 'OTTO')
    if (tag == 0x00010000U || tag == 0x4F54544FU || tag == 0x74727565U || tag == 0x74797031U) {
        return isSafeSfntAt(p, n, 0);
    }
    // TrueType Collection ('ttcf')
    if (tag == 0x74746366U) {
        if (n < 16) return false;
        const uint32_t numFonts =
            (static_cast<uint32_t>(p[8]) << 24) | (static_cast<uint32_t>(p[9]) << 16) |
            (static_cast<uint32_t>(p[10]) << 8) | static_cast<uint32_t>(p[11]);
        if (numFonts == 0 || numFonts > 256) return false;
        if (12ULL + static_cast<uint64_t>(numFonts) * 4ULL > n) return false;
        for (uint32_t fi = 0; fi < numFonts; ++fi) {
            const size_t o = 12U + static_cast<size_t>(fi) * 4U;
            const uint32_t faceOff =
                (static_cast<uint32_t>(p[o]) << 24) | (static_cast<uint32_t>(p[o + 1]) << 16) |
                (static_cast<uint32_t>(p[o + 2]) << 8) | static_cast<uint32_t>(p[o + 3]);
            if (!isSafeSfntAt(p, n, faceOff)) return false;
        }
        return true;
    }
    // Type 1 PFB (0x80 0x01) or PFA ('%!')
    if ((p[0] == 0x80 && p[1] == 0x01) || (p[0] == '%' && p[1] == '!')) return true;
    // Bare CFF (major=1, minor=0, hdrSize >= 4 && hdrSize < n, offSize in 1..4)
    if (p[0] == 1 && p[1] == 0 && p[2] >= 4 && p[2] < n && p[3] >= 1 && p[3] <= 4) {
        size_t pos = p[2];
        // Validate Name INDEX, Top DICT INDEX, String INDEX, Global Subr INDEX
        for (int idx = 0; idx < 4; ++idx) {
            if (!isSafeCffIndex(p, n, pos, false, pos)) return false;
        }
        return true;
    }
    // Bare CFF2 (major=2, minor=0, hdrSize >= 5 && hdrSize < n)
    if (p[0] == 2 && p[1] == 0 && p[2] >= 5 && p[2] < n) {
        const uint16_t topDictLen = static_cast<uint16_t>((p[3] << 8) | p[4]);
        size_t pos = static_cast<size_t>(p[2]) + topDictLen;
        if (pos > n) return false;
        // Validate Global Subr INDEX
        return isSafeCffIndex(p, n, pos, true, pos);
    }
    return false;
}

// Compute exact glyph advance width in PDF 1000-unit glyph space as floating-point.
// Returning exact double lets callers apply veraPDF's 1.0-unit tolerance without perturbing
// already-compliant widths (preserving PDFium text extraction word spacing).
double ftGlyphWidth1000Exact(FT_Face face, FT_UInt gid, int upem, bool isCffOrType1,
                             double extraScale) {
    constexpr double kNaN = std::numeric_limits<double>::quiet_NaN();
    if (!face) return kNaN;
    if (isCffOrType1) {
        if (FT_Load_Glyph(face, gid, FT_LOAD_NO_SCALE | FT_LOAD_NO_HINTING) == 0 &&
            face->glyph->metrics.horiAdvance >= 0) {
            double w = static_cast<double>(face->glyph->metrics.horiAdvance) * 1000.0 * extraScale /
                       (upem > 0 ? upem : 1000);
            if (std::isfinite(w) && w >= 0.0 && w <= 100000.0) return w;
            return kNaN;
        }
        if (FT_Set_Char_Size(face, FT_F26Dot6{1000} * 64, 0, 72, 72) == 0 &&
            FT_Load_Glyph(face, gid, FT_LOAD_NO_HINTING | FT_LOAD_NO_BITMAP) == 0) {
            double w = (static_cast<double>(face->glyph->linearHoriAdvance) / 65536.0) * extraScale;
            if (std::isfinite(w) && w >= 0.0 && w <= 100000.0) {
                return w;
            }
        }
    }
    FT_Fixed adv = 0;
    if (FT_Get_Advance(face, gid, FT_LOAD_NO_SCALE | FT_LOAD_NO_HINTING, &adv) == 0) {
        double w = static_cast<double>(adv) * 1000.0 * extraScale / (upem > 0 ? upem : 1000);
        if (std::isfinite(w) && w >= 0.0 && w <= 100000.0) return w;
    }
    return kNaN;
}

int ftGlyphWidth1000(FT_Face face, FT_UInt gid, int upem, bool isCffOrType1, double extraScale) {
    double w = ftGlyphWidth1000Exact(face, gid, upem, isCffOrType1, extraScale);
    return (std::isfinite(w) && w >= 0.0) ? static_cast<int>(std::llround(w)) : -1;
}

const DonorFont* getCachedDonor(FT_Library ftLib, const char* donorDirs, const std::string& key) {
    struct DonorSlot {
        std::mutex initMutex;
        const DonorFont* font = nullptr;
    };
    static std::mutex gDonorMutex;
    static std::map<std::string, std::unique_ptr<DonorSlot>> gDonorSlots;
    static std::map<std::string, std::unique_ptr<DonorFont>> gDonorsByPath;
    constexpr size_t kMaxCachedDonors = 48;

    static const std::map<std::string, std::vector<std::string>> kDonorFiles = {
        {"arial", {"Arial.ttf", "arial.ttf", "LiberationSans-Regular.ttf", "DejaVuSans.ttf"}},
        {"arial_bold",
         {"Arial Bold.ttf", "arialbd.ttf", "LiberationSans-Bold.ttf", "DejaVuSans-Bold.ttf",
          "Arial.ttf", "arial.ttf"}},
        {"arial_italic",
         {"Arial Italic.ttf", "ariali.ttf", "LiberationSans-Italic.ttf", "DejaVuSans-Oblique.ttf",
          "Arial.ttf", "arial.ttf"}},
        {"arial_bi",
         {"Arial Bold Italic.ttf", "arialbi.ttf", "LiberationSans-BoldItalic.ttf",
          "DejaVuSans-BoldOblique.ttf", "Arial Bold.ttf", "Arial.ttf", "arial.ttf"}},
        {"narrow", {"Arial Narrow.ttf", "ARIALN.TTF", "Arial.ttf", "arial.ttf"}},
        {"narrow_bold",
         {"Arial Narrow Bold.ttf", "ARIALNB.TTF", "Arial Bold.ttf", "Arial.ttf", "arial.ttf"}},
        {"narrow_italic",
         {"Arial Narrow Italic.ttf", "ARIALNI.TTF", "Arial Italic.ttf", "Arial.ttf", "arial.ttf"}},
        {"narrow_bi",
         {"Arial Narrow Bold Italic.ttf", "ARIALNBI.TTF", "Arial Bold Italic.ttf", "Arial.ttf",
          "arial.ttf"}},
        {"times",
         {"Times New Roman.ttf", "times.ttf", "LiberationSerif-Regular.ttf", "DejaVuSerif.ttf"}},
        {"times_bold",
         {"Times New Roman Bold.ttf", "timesbd.ttf", "LiberationSerif-Bold.ttf",
          "DejaVuSerif-Bold.ttf", "Times New Roman.ttf", "times.ttf"}},
        {"times_italic",
         {"Times New Roman Italic.ttf", "timesi.ttf", "LiberationSerif-Italic.ttf",
          "DejaVuSerif-Italic.ttf", "Times New Roman.ttf", "times.ttf"}},
        {"times_bi",
         {"Times New Roman Bold Italic.ttf", "timesbi.ttf", "LiberationSerif-BoldItalic.ttf",
          "DejaVuSerif-BoldItalic.ttf", "Times New Roman Bold.ttf", "Times New Roman.ttf",
          "times.ttf"}},
        {"courier",
         {"Courier New.ttf", "cour.ttf", "LiberationMono-Regular.ttf", "DejaVuSansMono.ttf"}},
        {"courier_bold",
         {"Courier New Bold.ttf", "courbd.ttf", "LiberationMono-Bold.ttf",
          "DejaVuSansMono-Bold.ttf", "Courier New.ttf", "cour.ttf"}},
        {"courier_italic",
         {"Courier New Italic.ttf", "couri.ttf", "LiberationMono-Italic.ttf",
          "DejaVuSansMono-Oblique.ttf", "Courier New.ttf", "cour.ttf"}},
        {"courier_bi",
         {"Courier New Bold Italic.ttf", "courbi.ttf", "LiberationMono-BoldItalic.ttf",
          "DejaVuSansMono-BoldOblique.ttf", "Courier New Bold.ttf", "Courier New.ttf", "cour.ttf"}},
        {"georgia", {"Georgia.ttf", "georgia.ttf", "Times New Roman.ttf", "times.ttf"}},
        {"georgia_bold",
         {"Georgia Bold.ttf", "georgiab.ttf", "Times New Roman Bold.ttf", "timesbd.ttf"}},
        {"georgia_italic",
         {"Georgia Italic.ttf", "georgiai.ttf", "Times New Roman Italic.ttf", "timesi.ttf"}},
        {"georgia_bi",
         {"Georgia Bold Italic.ttf", "georgiaz.ttf", "Times New Roman Bold Italic.ttf",
          "timesbi.ttf"}},
        {"verdana", {"Verdana.ttf", "verdana.ttf", "Arial.ttf", "arial.ttf"}},
        {"verdana_bold", {"Verdana Bold.ttf", "verdanab.ttf", "Arial Bold.ttf", "arialbd.ttf"}},
        {"verdana_italic",
         {"Verdana Italic.ttf", "verdanai.ttf", "Arial Italic.ttf", "ariali.ttf"}},
        {"verdana_bi",
         {"Verdana Bold Italic.ttf", "verdanaz.ttf", "Arial Bold Italic.ttf", "arialbi.ttf"}},
        {"tahoma", {"Tahoma.ttf", "tahoma.ttf", "Arial.ttf", "arial.ttf"}},
        {"tahoma_bold", {"Tahoma Bold.ttf", "tahomabd.ttf", "Arial Bold.ttf", "arialbd.ttf"}},
        {"trebuchet", {"Trebuchet MS.ttf", "trebuc.ttf", "Arial.ttf", "arial.ttf"}},
        {"trebuchet_bold",
         {"Trebuchet MS Bold.ttf", "trebucbd.ttf", "Arial Bold.ttf", "arialbd.ttf"}},
        {"trebuchet_italic",
         {"Trebuchet MS Italic.ttf", "trebucit.ttf", "Arial Italic.ttf", "ariali.ttf"}},
        {"trebuchet_bi",
         {"Trebuchet MS Bold Italic.ttf", "trebucbi.ttf", "Arial Bold Italic.ttf", "arialbi.ttf"}},
        {"arial_uni",
         {"Arial Unicode.ttf", "ARIALUNI.TTF", "Arial.ttf", "arial.ttf", "DejaVuSans.ttf"}},
        {"stix", {"STIXGeneral.otf"}},
        {"symbol", {"Symbol.ttf", "symbol.ttf"}},
        {"wingdings", {"Wingdings.ttf", "wingding.ttf"}},
        {"webdings", {"Webdings.ttf", "webdings.ttf"}},
    };
    auto fit = kDonorFiles.find(key);
    if (fit == kDonorFiles.end()) return nullptr;

    std::string cacheKey = key + "|" + (donorDirs ? std::string(donorDirs) : std::string());
    DonorSlot* slot = nullptr;
    {
        std::lock_guard<std::mutex> lk(gDonorMutex);
        auto& ptr = gDonorSlots[cacheKey];
        if (!ptr) ptr = std::make_unique<DonorSlot>();
        slot = ptr.get();
    }

    std::lock_guard<std::mutex> slotLk(slot->initMutex);
    if (slot->font && slot->font->loaded) return slot->font;
    if (!ftLib) return nullptr;

    std::vector<std::string> searchDirs;
    if (donorDirs && donorDirs[0] != '\0') {
        std::istringstream ss(donorDirs);
        std::string line;
        while (std::getline(ss, line)) {
            if (!line.empty()) searchDirs.push_back(line);
        }
    }
    searchDirs.push_back("/System/Library/Fonts/Supplemental");
    searchDirs.push_back("/System/Library/Fonts");
    searchDirs.push_back("/usr/share/fonts/truetype/liberation");
    searchDirs.push_back("/usr/share/fonts/truetype/dejavu");
#ifdef _WIN32
    searchDirs.push_back("C:/Windows/Fonts");
    if (const char* windir = std::getenv("WINDIR"); windir && windir[0] != '\0') {
        searchDirs.push_back(std::string(windir) + "/Fonts");
    }
#endif

    // Check canonical path cache first so identical donor files across donorDirs share one buffer.
    for (const auto& dir : searchDirs) {
        for (const auto& fn : fit->second) {
            std::string candidate = dir;
            candidate.push_back('/');
            candidate += fn;
            std::error_code ec;
            if (!std::filesystem::exists(candidate, ec) || ec) continue;
            auto canon = std::filesystem::weakly_canonical(candidate, ec);
            std::string canonStr = ec ? candidate : canon.string();
            {
                std::lock_guard<std::mutex> lk(gDonorMutex);
                auto pit = gDonorsByPath.find(canonStr);
                if (pit != gDonorsByPath.end() && pit->second->loaded) {
                    slot->font = pit->second.get();
                    return slot->font;
                }
            }
            auto loadedDf = std::make_unique<DonorFont>();
            if (loadDonorFont(ftLib, {dir}, {fn}, *loadedDf) && loadedDf->loaded) {
                std::lock_guard<std::mutex> lk(gDonorMutex);
                auto pit = gDonorsByPath.find(canonStr);
                if (pit != gDonorsByPath.end() && pit->second->loaded) {
                    slot->font = pit->second.get();
                    return slot->font;
                }
                if (gDonorsByPath.size() >= kMaxCachedDonors) {
                    slot->font = nullptr;
                    return nullptr;
                }
                DonorFont* rawPtr = loadedDf.get();
                gDonorsByPath[canonStr] = std::move(loadedDf);
                slot->font = rawPtr;
                return rawPtr;
            }
        }
    }
    return nullptr;
}

std::string selectStyledDonorKey(const std::string& baseFont) {
    std::string lbf = toLowerAscii(baseFont);
    if (lbf.find("wingding") != std::string::npos) return "wingdings";
    if (lbf.find("webding") != std::string::npos) return "webdings";
    if (lbf.find("symbol") != std::string::npos) return "symbol";
    auto hasToken = [&](std::string_view tok) -> bool {
        size_t pos = lbf.find(tok);
        while (pos != std::string::npos) {
            bool leftOk = (pos == 0 || !std::isalpha(static_cast<unsigned char>(lbf[pos - 1])));
            size_t end = pos + tok.size();
            bool rightOk =
                (end == lbf.size() || !std::isalpha(static_cast<unsigned char>(lbf[end])));
            if (leftOk && rightOk) return true;
            pos = lbf.find(tok, pos + 1);
        }
        return false;
    };
    bool isBold =
        (lbf.find("bold") != std::string::npos || lbf.find("heavy") != std::string::npos ||
         lbf.find("demi") != std::string::npos || hasToken("black"));
    bool isItal =
        (lbf.find("italic") != std::string::npos || lbf.find("oblique") != std::string::npos ||
         lbf.find("slant") != std::string::npos || hasToken("ital") || hasToken("obli"));
    std::string suf = (isBold && isItal) ? "_bi" : (isBold ? "_bold" : (isItal ? "_italic" : ""));
    if (lbf.find("courier") != std::string::npos || lbf.find("consola") != std::string::npos ||
        lbf.find("menlo") != std::string::npos ||
        (lbf.find("mono") != std::string::npos && lbf.find("monotype") == std::string::npos)) {
        return "courier" + suf;
    }
    if (lbf.find("georgia") != std::string::npos) return "georgia" + suf;
    if (lbf.find("verdana") != std::string::npos) return "verdana" + suf;
    if (lbf.find("trebuchet") != std::string::npos) return "trebuchet" + suf;
    if (lbf.find("tahoma") != std::string::npos) return isBold ? "tahoma_bold" : "tahoma";
    if (lbf.find("narrow") != std::string::npos || lbf.find("condens") != std::string::npos) {
        return "narrow" + suf;
    }
    bool isSans =
        (lbf.find("sans") != std::string::npos || lbf.find("arial") != std::string::npos ||
         lbf.find("helvetica") != std::string::npos || lbf.find("calibri") != std::string::npos);
    if (!isSans &&
        (lbf.find("times") != std::string::npos || lbf.find("serif") != std::string::npos ||
         lbf.find("garamond") != std::string::npos || lbf.find("cambria") != std::string::npos ||
         lbf.find("palatino") != std::string::npos || lbf.find("minion") != std::string::npos)) {
        return "times" + suf;
    }
    return "arial" + suf;
}

}  // namespace jpdfium::rewriter
