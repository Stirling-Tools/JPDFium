// jpdfium_font_normalizer.cpp - Per-page and whole-document font normalization:
// ToUnicode synthesis/repair, Simple/CID horizontal & vertical widths, OTTO
// subsetting + CFF extraction, and /CIDSet / /CharSet conformance updates.

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <map>
#include <memory>
#include <set>
#include <string>
#include <string_view>
#include <vector>

#include "jpdfium.h"
#include "jpdfium_font_encoding.h"
#include "jpdfium_font_internal.h"
#include "jpdfium_internal.h"
#include "jpdfium_qpdf_internal.h"

#if defined(JPDFIUM_HAS_FREETYPE) && defined(JPDFIUM_HAS_HARFBUZZ) && defined(JPDFIUM_HAS_QPDF)

using namespace jpdfium_font_encoding;
using namespace jpdfium::font;
using namespace jpdfium::qpdf_detail;

namespace {

struct FontUsageCounts {
    std::map<QPDFObjGen, int> descriptors;
    std::map<QPDFObjGen, int> streams;
};

struct FontNormalizeState {
    int32_t fontsProcessed = 0;
    int32_t tucFixed = 0;
    int32_t wFixed = 0;
    int32_t type1Converted = 0;
    int32_t resubset = 0;
    bool changed = false;
    FontUsageIndexes usageIndexes;
    bool usageIndexesReady = false;
};

static FontUsageIndexes const& ensureUsageIndexes(QPDF& pdf, FontNormalizeState& state) {
    if (!state.usageIndexesReady) {
        state.usageIndexes = buildFontUsageIndexes(pdf);
        state.usageIndexesReady = true;
    }
    return state.usageIndexes;
}

static QPDFObjectHandle embeddedFontProgram(QPDFObjectHandle const& descriptor) {
    for (char const* key : {"/FontFile2", "/FontFile3", "/FontFile"}) {
        QPDFObjectHandle candidate = descriptor.getKey(key);
        if (candidate.isStream()) return candidate;
    }
    return QPDFObjectHandle();
}

static void markFormFontNotSubsettable(QPDFObjectHandle const& font, FontUsageCounts& usage) {
    if (!font.isDictionary()) return;
    QPDFObjectHandle target = font;
    QPDFObjectHandle descendants = font.getKey("/DescendantFonts");
    if (descendants.isArray() && descendants.getArrayNItems() > 0 &&
        descendants.getArrayItem(0).isDictionary())
        target = descendants.getArrayItem(0);
    QPDFObjectHandle descriptor = target.getKey("/FontDescriptor");
    if (!descriptor.isDictionary()) return;
    if (descriptor.isIndirect()) usage.descriptors[descriptor.getObjGen()] = 999999;
    for (char const* key : {"/FontFile", "/FontFile2", "/FontFile3"}) {
        auto stream = descriptor.getKey(key);
        if (stream.isStream() && stream.isIndirect()) {
            usage.streams[stream.getObjGen()] = 999999;
        }
    }
}

static void countFontUsage(QPDFObjectHandle const& font, std::set<QPDFObjGen>& counted,
                           FontUsageCounts& usage) {
    if (font.isIndirect() && !counted.insert(font.getObjGen()).second) return;
    QPDFObjectHandle target = font;
    QPDFObjectHandle descendants = font.getKey("/DescendantFonts");
    if (descendants.isArray() && descendants.getArrayNItems() > 0 &&
        descendants.getArrayItem(0).isDictionary())
        target = descendants.getArrayItem(0);
    QPDFObjectHandle descriptor = target.getKey("/FontDescriptor");
    if (!descriptor.isDictionary()) return;
    if (descriptor.isIndirect()) ++usage.descriptors[descriptor.getObjGen()];
    QPDFObjectHandle program = embeddedFontProgram(descriptor);
    if (program.isIndirect()) ++usage.streams[program.getObjGen()];
}

static std::string adobeOrderingForCidFont(QPDFObjectHandle const& target) {
    QPDFObjectHandle sysInfo = target.getKey("/CIDSystemInfo");
    if (!sysInfo.isDictionary()) return {};
    QPDFObjectHandle reg = sysInfo.getKey("/Registry");
    QPDFObjectHandle ord = sysInfo.getKey("/Ordering");
    if (!reg.isString() || reg.getStringValue() != "Adobe" || !ord.isString()) return {};
    return ord.getStringValue();
}

static void repairFontToUnicode(QPDF& pdf, QPDFObjectHandle& font, QPDFObjectHandle const& target,
                                FT_Face face, bool cid, QPDFObjectHandle& cidToGid,
                                bool cidIdentity, std::map<uint32_t, uint32_t> const& gidToCid,
                                std::map<uint32_t, uint32_t> const& gidToUnicode,
                                std::set<uint32_t> const& ambiguousGids, bool simpleAnyGid,
                                std::vector<SimpleGlyph> const& simpleGlyphs,
                                FontNormalizeState& state) {
    // Skip fonts that already carry a non-empty, syntactically populated /ToUnicode CMap.
    bool hasToUnicode = false;
    QPDFObjectHandle toUnicode = font.getKey("/ToUnicode");
    if (toUnicode.isStream()) {
        try {
            std::shared_ptr<Buffer> existing = toUnicode.getStreamData(qpdf_dl_generalized);
            if (existing && existing->getSize() > 0) {
                std::string_view cmap(reinterpret_cast<const char*>(existing->getBuffer()),
                                      existing->getSize());
                hasToUnicode = cmapHasMappings(cmap);
            }
        } catch (...) {
            hasToUnicode = false;
        }
    }
    if (hasToUnicode) return;

    const std::string adobeOrdering = cid ? adobeOrderingForCidFont(target) : std::string();

    // Prefer glyph-name disambiguation on shared GIDs before cmap or Adobe CID collection fallback.
    auto reverseCidUnicode = [&](uint32_t cidVal, uint32_t gid) -> uint32_t {
        if (ambiguousGids.contains(gid)) {
            if (uint32_t u = unicodeFromGlyphNameOnly(face, gid)) return u;
        }
        if (auto found = gidToUnicode.find(gid); found != gidToUnicode.end() && found->second)
            return found->second;
        if (!adobeOrdering.empty()) {
            uint32_t cjkU = 0;
            if (adobeCollectionUnicodeForCid(adobeOrdering, cidVal, cjkU) && cjkU) return cjkU;
        }
        return 0;
    };

    std::map<uint32_t, std::vector<uint32_t>> codeToUnicode;
    if (cid) {
        if (cidToGid.isStream()) {
            try {
                std::shared_ptr<Buffer> map = cidToGid.getStreamData(qpdf_dl_generalized);
                if (map) {
                    const auto* bytes = static_cast<const uint8_t*>(map->getBuffer());
                    const size_t entries = map->getSize() / 2;
                    for (size_t c = 0; c < entries && c <= kMaxCid; ++c) {
                        const uint32_t g =
                            (static_cast<uint32_t>(bytes[2 * c]) << 8) | bytes[2 * c + 1];
                        uint32_t unicode = reverseCidUnicode(static_cast<uint32_t>(c), g);
                        if (unicode)
                            codeToUnicode.emplace(static_cast<uint32_t>(c),
                                                  std::vector<uint32_t>{unicode});
                    }
                }
            } catch (...) {
                codeToUnicode.clear();
            }
        } else if (cidIdentity) {
            if (FT_IS_CID_KEYED(face)) {
                for (auto const& [gid, cidVal] : gidToCid) {
                    uint32_t unicode = reverseCidUnicode(cidVal, gid);
                    if (unicode) codeToUnicode.emplace(cidVal, std::vector<uint32_t>{unicode});
                }
            } else {
                for (auto const& [gid, _] : gidToUnicode) {
                    if (gid > kMaxCid) continue;
                    uint32_t unicode = reverseCidUnicode(gid, gid);
                    if (unicode) codeToUnicode.emplace(gid, std::vector<uint32_t>{unicode});
                }
                if (codeToUnicode.empty() && !adobeOrdering.empty()) {
                    CidScanResult scan = usedCidsForFont(pdf, font, CidEncoding::TwoByte,
                                                         &ensureUsageIndexes(pdf, state));
                    for (uint32_t c : scan.cids) {
                        uint32_t u = 0;
                        if (adobeCollectionUnicodeForCid(adobeOrdering, c, u) && u) {
                            codeToUnicode.emplace(c, std::vector<uint32_t>{u});
                        }
                    }
                }
            }
        }
    } else if (simpleAnyGid) {
        // For simple fonts, resolve glyph names first before falling back to resolved Unicode
        // values.
        for (uint32_t code = 0; code <= 0xFF; ++code) {
            uint32_t gid = simpleGlyphs[code].gid;
            std::vector<uint32_t> sequence;
            if (gid != 0 && FT_HAS_GLYPH_NAMES(face)) {
                char glyphName[256];
                if (FT_Get_Glyph_Name(face, gid, glyphName, sizeof(glyphName)) == 0 && glyphName[0])
                    sequence = unicodeSequenceForGlyphName(glyphName);
            }
            if (sequence.empty() && simpleGlyphs[code].unicode != 0)
                sequence.push_back(simpleGlyphs[code].unicode);
            if (!sequence.empty()) codeToUnicode.emplace(code, std::move(sequence));
        }
    }

    if (!codeToUnicode.empty()) {
        std::string cmap = buildToUnicodeCmap(codeToUnicode, cid);
        if (!cmap.empty()) {
            font.replaceKey("/ToUnicode", QPDFObjectHandle::newStream(&pdf, cmap));
            ++state.tucFixed;
            state.changed = true;
        }
    }
}

static void repairSimpleFontWidths(QPDFObjectHandle& target, FT_Face face,
                                   SimpleEncoding const& simpleEncoding, bool simpleAnyGid,
                                   std::vector<SimpleGlyph> const& simpleGlyphs,
                                   FontNormalizeState& state, bool full) {
    if (simpleEncoding.unsupportedEncoding || !simpleAnyGid) return;
    int firstChar = 0;
    int lastChar = 255;
    QPDFObjectHandle existingFirst = target.getKey("/FirstChar");
    QPDFObjectHandle existingLast = target.getKey("/LastChar");
    if (existingFirst.isInteger()) {
        firstChar = static_cast<int>(std::clamp(existingFirst.getIntValue(), 0LL, 255LL));
    }
    if (existingLast.isInteger()) {
        lastChar = static_cast<int>(
            std::clamp(existingLast.getIntValue(), static_cast<long long>(firstChar), 255LL));
    }

    std::vector<int> widths;
    widths.reserve(static_cast<size_t>(lastChar) - static_cast<size_t>(firstChar) + 1);
    bool anyPositive = false;
    QPDFObjectHandle existingWidths = target.getKey("/Widths");
    const bool hasWidthsArray = existingWidths.isArray();
    bool needsRewrite =
        !hasWidthsArray || existingWidths.getArrayNItems() != (lastChar - firstChar + 1);
    for (int code = firstChar; code <= lastChar; ++code) {
        const int index = code - firstChar;
        QPDFObjectHandle item;
        if (hasWidthsArray && index >= 0 && index < existingWidths.getArrayNItems())
            item = existingWidths.getArrayItem(index);
        const bool existingNumber = item.isNumber();
        const bool existingNonnegative = existingNumber && item.getNumericValue() >= 0.0;
        const int preserved =
            existingNumber ? std::max(0, static_cast<int>(std::lround(item.getNumericValue()))) : 0;
        int width = preserved;
        int computed = 0;
        const bool resolved =
            simpleGlyphs[static_cast<size_t>(code)].gid != 0 &&
            pdfWidthFromGlyph(face, simpleGlyphs[static_cast<size_t>(code)].gid, computed);
        if (resolved) {
            const bool withinTolerance = existingNumber && std::abs(preserved - computed) <= 1;
            const bool keepNonFull = !full && existingNumber &&
                                     (preserved > 0 || (computed == 0 && existingNonnegative));
            if (withinTolerance || keepNonFull) {
                width = preserved;
            } else {
                width = std::max(0, computed);
                needsRewrite = true;
            }
        } else if (!existingNumber) {
            needsRewrite = true;
            width = 0;
        }
        if (width > 0) anyPositive = true;
        widths.push_back(width);
    }

    if (needsRewrite && anyPositive) {
        QPDFObjectHandle array = QPDFObjectHandle::newArray();
        for (int width : widths) array.appendItem(QPDFObjectHandle::newInteger(width));
        target.replaceKey("/FirstChar", QPDFObjectHandle::newInteger(firstChar));
        target.replaceKey("/LastChar", QPDFObjectHandle::newInteger(lastChar));
        target.replaceKey("/Widths", array);
        ++state.wFixed;
        state.changed = true;
    }
}

static std::map<uint32_t, int> parseCidWidths(QPDFObjectHandle const& w, bool& complete) {
    complete = true;
    std::map<uint32_t, int> widths;
    if (!w.isArray()) return widths;
    const auto items = w.getArrayAsVector();
    size_t i = 0;
    while (i < items.size()) {
        if (!items[i].isInteger()) {
            complete = false;
            break;
        }
        const long long firstRaw = items[i].getIntValue();
        ++i;
        if (i >= items.size()) {
            complete = false;
            break;
        }
        if (firstRaw < 0 || firstRaw > static_cast<long long>(kMaxCid)) {
            complete = false;
            if (items[i].isArray()) {
                ++i;
            } else if (i + 1 < items.size()) {
                i += 2;
            } else {
                break;
            }
            continue;
        }
        const uint32_t first = static_cast<uint32_t>(firstRaw);
        if (items[i].isArray()) {
            const auto run = items[i].getArrayAsVector();
            ++i;
            for (size_t j = 0; j < run.size() && first + j <= kMaxCid; ++j) {
                if (run[j].isNumber()) {
                    widths[first + static_cast<uint32_t>(j)] =
                        static_cast<int>(std::lround(run[j].getNumericValue()));
                } else {
                    complete = false;
                }
            }
            continue;
        }
        if (i + 1 >= items.size() || !items[i].isInteger() || !items[i + 1].isNumber()) {
            complete = false;
            break;
        }
        const long long lastRaw = items[i].getIntValue();
        const int width = static_cast<int>(std::lround(items[i + 1].getNumericValue()));
        i += 2;
        if (lastRaw < firstRaw || lastRaw > static_cast<long long>(kMaxCid)) {
            complete = false;
            continue;
        }
        const uint32_t last = static_cast<uint32_t>(lastRaw);
        for (uint32_t cid = first; cid <= last; ++cid) widths[cid] = width;
    }
    return widths;
}

static std::map<uint32_t, VerticalMetric> parseCidVerticalWidths(QPDFObjectHandle const& w2,
                                                                 bool& complete) {
    complete = true;
    std::map<uint32_t, VerticalMetric> metrics;
    if (!w2.isArray()) return metrics;
    const auto items = w2.getArrayAsVector();
    size_t i = 0;
    while (i < items.size()) {
        if (!items[i].isInteger()) {
            complete = false;
            break;
        }
        const long long firstRaw = items[i].getIntValue();
        ++i;
        if (i >= items.size()) {
            complete = false;
            break;
        }
        if (firstRaw < 0 || firstRaw > static_cast<long long>(kMaxCid)) {
            complete = false;
            if (items[i].isArray()) {
                ++i;
            } else if (i + 3 < items.size()) {
                i += 4;
            } else {
                break;
            }
            continue;
        }
        const uint32_t first = static_cast<uint32_t>(firstRaw);
        if (items[i].isArray()) {
            const auto run = items[i].getArrayAsVector();
            ++i;
            if (run.size() % 3 != 0) complete = false;
            for (size_t j = 0; j + 2 < run.size() && first + j / 3 <= kMaxCid; j += 3) {
                if (run[j].isNumber() && run[j + 1].isNumber() && run[j + 2].isNumber()) {
                    VerticalMetric vm{static_cast<int>(std::lround(run[j].getNumericValue())),
                                      static_cast<int>(std::lround(run[j + 1].getNumericValue())),
                                      static_cast<int>(std::lround(run[j + 2].getNumericValue()))};
                    metrics[first + static_cast<uint32_t>(j / 3)] = vm;
                } else {
                    complete = false;
                }
            }
            continue;
        }
        if (i + 3 >= items.size() || !items[i].isInteger() || !items[i + 1].isNumber() ||
            !items[i + 2].isNumber() || !items[i + 3].isNumber()) {
            complete = false;
            break;
        }
        const long long lastRaw = items[i].getIntValue();
        VerticalMetric vm{static_cast<int>(std::lround(items[i + 1].getNumericValue())),
                          static_cast<int>(std::lround(items[i + 2].getNumericValue())),
                          static_cast<int>(std::lround(items[i + 3].getNumericValue()))};
        i += 4;
        if (lastRaw < firstRaw || lastRaw > static_cast<long long>(kMaxCid)) {
            complete = false;
            continue;
        }
        const uint32_t last = static_cast<uint32_t>(lastRaw);
        for (uint32_t cid = first; cid <= last; ++cid) metrics[cid] = vm;
    }
    return metrics;
}

static std::map<uint32_t, FT_UInt> buildCidToGidTable(
    FT_Face face, QPDFObjectHandle& cidToGid, bool cidIdentity,
    std::map<uint32_t, uint32_t> const& gidToCid) {
    std::map<uint32_t, FT_UInt> cidGid;
    if (cidToGid.isStream()) {
        try {
            std::shared_ptr<Buffer> map = cidToGid.getStreamData(qpdf_dl_generalized);
            if (map) {
                const auto* bytes = static_cast<const uint8_t*>(map->getBuffer());
                const size_t entries = map->getSize() / 2;
                for (size_t c = 0; c < entries && c <= kMaxCid; ++c) {
                    const uint32_t gid =
                        (static_cast<uint32_t>(bytes[2 * c]) << 8) | bytes[2 * c + 1];
                    if (gid != 0) cidGid[static_cast<uint32_t>(c)] = gid;
                }
            }
        } catch (...) {
            cidGid.clear();
        }
    } else if (cidIdentity) {
        if (FT_IS_CID_KEYED(face)) {
            for (auto const& [gid, cidVal] : gidToCid) cidGid[cidVal] = gid;
        } else {
            const int maxGid = static_cast<int>(
                std::min<FT_Long>(face->num_glyphs - 1, static_cast<FT_Long>(kMaxCid)));
            for (int gid = 1; gid <= maxGid; ++gid)
                cidGid[static_cast<uint32_t>(gid)] = static_cast<FT_UInt>(gid);
        }
    }
    return cidGid;
}

static void repairVerticalCidFontWidths(QPDF& pdf, QPDFObjectHandle& font, QPDFObjectHandle& target,
                                        FT_Face face, std::map<uint32_t, FT_UInt> const& cidGid,
                                        FontNormalizeState& state) {
    if (!FT_HAS_VERTICAL(face)) return;
    CidScanResult scan =
        usedCidsForFont(pdf, font, CidEncoding::TwoByte, &ensureUsageIndexes(pdf, state));
    if (!scan.complete || scan.cids.empty()) return;

    int defaultVy = 880;
    int defaultW1y = -1000;
    bool wroteDw2 = false;
    QPDFObjectHandle dw2 = target.getKey("/DW2");
    if (dw2.isArray() && dw2.getArrayNItems() >= 2 && dw2.getArrayItem(0).isNumber() &&
        dw2.getArrayItem(1).isNumber()) {
        defaultVy = static_cast<int>(std::lround(dw2.getArrayItem(0).getNumericValue()));
        defaultW1y = static_cast<int>(std::lround(dw2.getArrayItem(1).getNumericValue()));
    } else if (!dw2.isArray()) {
        auto arr = QPDFObjectHandle::newArray();
        arr.appendItem(QPDFObjectHandle::newInteger(defaultVy));
        arr.appendItem(QPDFObjectHandle::newInteger(defaultW1y));
        target.replaceKey("/DW2", arr);
        state.changed = true;
        wroteDw2 = true;
    }

    QPDFObjectHandle existingW2 = target.getKey("/W2");
    bool w2Complete = true;
    auto metrics = parseCidVerticalWidths(existingW2, w2Complete);
    if (existingW2.isArray() && !w2Complete) {
        if (wroteDw2) ++state.wFixed;
        return;
    }

    bool changed = false;
    for (uint32_t cid : scan.cids) {
        auto glyph = cidGid.find(cid);
        if (glyph == cidGid.end()) continue;
        VerticalMetric vm;
        if (!pdfVerticalMetricsFromGlyph(face, glyph->second, vm.w1y, vm.vx, vm.vy)) continue;
        int horizW = 1000;
        (void)pdfWidthFromGlyph(face, glyph->second, horizW);
        // Only write an entry if it differs from existing /W2 or differs from /DW2 defaults
        // when synthesizing a brand-new /W2.
        auto existing = metrics.find(cid);
        if (existing != metrics.end()) {
            if (std::abs(existing->second.w1y - vm.w1y) > 2 ||
                std::abs(existing->second.vx - vm.vx) > 2 ||
                std::abs(existing->second.vy - vm.vy) > 2) {
                metrics[cid] = vm;
                changed = true;
            }
        } else if (vm.w1y != defaultW1y || vm.vy != defaultVy || std::abs(vm.vx - horizW / 2) > 2) {
            metrics[cid] = vm;
            changed = true;
        }
    }
    if (!changed) {
        if (wroteDw2) ++state.wFixed;
        return;
    }

    QPDFObjectHandle arr = buildCidVerticalWidthsArray(metrics, defaultW1y, 500, defaultVy);
    if (arr.getArrayNItems() > 0) {
        target.replaceKey("/W2", arr);
        ++state.wFixed;
        state.changed = true;
    }
}

static void repairCidFontWidths(QPDF& pdf, QPDFObjectHandle& font, QPDFObjectHandle& target,
                                FT_Face face, QPDFObjectHandle& cidToGid, bool cidIdentity,
                                std::map<uint32_t, uint32_t> const& gidToCid,
                                FontNormalizeState& state) {
    auto cidGid = buildCidToGidTable(face, cidToGid, cidIdentity, gidToCid);
    if (cidGid.empty()) return;

    if (font.getKey("/Encoding").isNameAndEquals("/Identity-V")) {
        repairVerticalCidFontWidths(pdf, font, target, face, cidGid, state);
        return;
    }

    QPDFObjectHandle existingW = target.getKey("/W");
    bool widthsComplete = true;
    std::map<uint32_t, int> widths = parseCidWidths(existingW, widthsComplete);
    if (existingW.isArray() && !widthsComplete) return;

    CidScanResult scan =
        usedCidsForFont(pdf, font, CidEncoding::TwoByte, &ensureUsageIndexes(pdf, state));
    if (!scan.complete || scan.cids.empty()) return;

    std::set<uint32_t> check = scan.cids;
    if (widths.empty()) {
        for (auto const& [cid, gid] : cidGid) {
            int width = 0;
            if (pdfWidthFromGlyph(face, gid, width) && width == 0) check.insert(cid);
        }
    }

    bool changed = false;
    for (uint32_t cid : check) {
        auto glyph = cidGid.find(cid);
        if (glyph == cidGid.end()) continue;
        int width = 0;
        if (!pdfWidthFromGlyph(face, glyph->second, width)) continue;
        auto existing = widths.find(cid);
        if (existing == widths.end() || existing->second != width) {
            widths[cid] = width;
            changed = true;
        }
    }
    if (!changed) return;

    int dw = -1;
    QPDFObjectHandle dwObj = target.getKey("/DW");
    if (dwObj.isInteger()) dw = static_cast<int>(dwObj.getIntValue());
    QPDFObjectHandle array = buildCidWidthsArray(widths, dw);
    if (array.getArrayNItems() > 0) {
        target.replaceKey("/W", array);
        ++state.wFixed;
        state.changed = true;
    }
}

static bool resubsetEmbeddedSfnt(QPDF& pdf, QPDFObjectHandle& font, QPDFObjectHandle& target,
                                 QPDFObjectHandle& descriptor, QPDFObjectHandle const& stream,
                                 std::shared_ptr<Buffer> const& data, FT_Long faceIndex,
                                 FT_Library ftLib, FT_Face face, bool cid,
                                 QPDFObjectHandle& cidToGid, bool cidIdentity, bool simpleSymbolic,
                                 std::vector<SimpleGlyph> const& simpleGlyphs,
                                 FontUsageCounts const& usage, FontNormalizeState& state,
                                 std::set<FT_UInt>* retainedGlyphs = nullptr) {
    if (face->num_glyphs <= 1 || !FT_IS_SFNT(face) ||
        fontHasSfntTable(face, FT_MAKE_TAG('C', 'F', 'F', '2')) || fontForbidsSubsetting(face))
        return false;

    QPDFObjectHandle baseFont = target.getKey("/BaseFont");
    if (!baseFont.isName()) baseFont = font.getKey("/BaseFont");
    std::string baseName = stripPdfNamePrefix(baseFont.isName() ? baseFont.getName() : "");

    QPDFObjectHandle descName = descriptor.getKey("/FontName");
    std::string descriptorName = stripPdfNamePrefix(descName.isName() ? descName.getName() : "");

    const bool isTtc = data->getSize() >= 4 && std::memcmp(data->getBuffer(), "ttcf", 4) == 0;
    const bool tagged = hasSubsetTag(baseName) || hasSubsetTag(descriptorName);
    baseName = stripSubsetTag(baseName);

    // Skip shared descriptors/streams and non-TTC subset fonts so sibling pages remain intact.
    const bool shared =
        (descriptor.isIndirect() && usage.descriptors.contains(descriptor.getObjGen()) &&
         usage.descriptors.at(descriptor.getObjGen()) > 1) ||
        (stream.isIndirect() && usage.streams.contains(stream.getObjGen()) &&
         usage.streams.at(stream.getObjGen()) > 1);
    if ((!isTtc && tagged) || shared || baseName.empty()) return false;

    std::set<FT_UInt> targetGlyphs{0};
    if (cid) {
        CidScanResult scan =
            usedCidsForFont(pdf, font, CidEncoding::TwoByte, &ensureUsageIndexes(pdf, state));
        if (!scan.complete || scan.cids.empty()) return false;
        std::shared_ptr<Buffer> cidMap;
        if (!cidIdentity) {
            if (!cidToGid.isStream()) return false;
            try {
                cidMap = cidToGid.getStreamData(qpdf_dl_generalized);
            } catch (...) {
                return false;
            }
            if (!cidMap) return false;
        }
        const auto* mapBytes = cidMap ? static_cast<const uint8_t*>(cidMap->getBuffer()) : nullptr;
        for (uint32_t c : scan.cids) {
            uint32_t gid = c;
            if (!cidIdentity) {
                const size_t mapOff = static_cast<size_t>(c) * 2;
                if (!mapBytes || cidMap->getSize() < mapOff + 2) return false;
                gid = (static_cast<uint32_t>(mapBytes[mapOff]) << 8) | mapBytes[mapOff + 1];
            }
            if (gid >= static_cast<uint32_t>(face->num_glyphs)) return false;
            targetGlyphs.insert(static_cast<FT_UInt>(gid));
        }
    } else {
        if (simpleGlyphs.size() < 256) return false;
        CidScanResult scan =
            usedCidsForFont(pdf, font, CidEncoding::SingleByte, &ensureUsageIndexes(pdf, state));
        if (!scan.complete || scan.cids.empty()) return false;
        // Include standard single-byte encoding candidates so differences lookups never break.
        for (uint32_t code : scan.cids) {
            if (code >= simpleGlyphs.size() || simpleGlyphs[code].gid == 0) return false;
            targetGlyphs.insert(simpleGlyphs[code].gid);
            if (code < 256) {
                for (BaseEncoding encoding :
                     {BaseEncoding::Standard, BaseEncoding::WinAnsi, BaseEncoding::MacRoman}) {
                    FT_UInt glyph = FT_Get_Char_Index(
                        face, baseEncodingUnicode(encoding, static_cast<int>(code)));
                    if (glyph) targetGlyphs.insert(glyph);
                }
            }
        }
    }

    if (targetGlyphs.size() <= 1 || targetGlyphs.size() >= static_cast<size_t>(face->num_glyphs))
        return false;

    std::set<uint32_t> glyphIds(targetGlyphs.begin(), targetGlyphs.end());
    // Simple non-symbolic CFF fonts convert to bare /Type1C resolved by glyph name,
    // so compacting GIDs avoids leaving zero-advance ghost slots that break idempotency.
    const bool compactSimpleCff = !cid && !simpleSymbolic &&
                                  fontHasSfntTable(face, FT_MAKE_TAG('C', 'F', 'F', ' ')) &&
                                  !fontHasSfntTable(face, FT_MAKE_TAG('g', 'l', 'y', 'f'));
    const bool retainGids = !compactSimpleCff;
    SubsetResult subset = subsetSfntProgram(face, data->getBuffer(), data->getSize(), faceIndex,
                                            targetGlyphs, retainGids, /*keepGlyphNames=*/!cid);
    if (subset.bytes.empty() || subset.bytes.size() >= data->getSize() ||
        !subsetKeepsAdvances(ftLib, subset.bytes, faceIndex, face, targetGlyphs,
                             retainGids ? nullptr : &subset.oldToNewGid))
        return false;

    const std::string tag = buildSubsetTagForGlyphs(baseName, glyphIds);
    QPDFObjectHandle taggedName = QPDFObjectHandle::newName("/" + tag + "+" + baseName);
    font.replaceKey("/BaseFont", taggedName);
    target.replaceKey("/BaseFont", taggedName);
    descriptor.replaceKey("/FontName", taggedName);

    const bool hasGlyf = fontHasSfntTable(face, FT_MAKE_TAG('g', 'l', 'y', 'f'));
    for (char const* key : {"/FontFile", "/FontFile2", "/FontFile3"}) descriptor.removeKey(key);
    QPDFObjectHandle replacement = QPDFObjectHandle::newStream(&pdf, subset.bytes);
    auto replacementDict = replacement.getDict();
    if (hasGlyf) {
        replacementDict.replaceKey(
            "/Length1", QPDFObjectHandle::newInteger(static_cast<long long>(subset.bytes.size())));
        descriptor.replaceKey("/FontFile2", replacement);
    } else {
        replacementDict.replaceKey("/Subtype", QPDFObjectHandle::newName("/OpenType"));
        descriptor.replaceKey("/FontFile3", replacement);
    }

    ++state.resubset;
    state.changed = true;
    if (retainedGlyphs) *retainedGlyphs = std::move(targetGlyphs);
    return true;
}

static void convertCffInSfnt(QPDF& pdf, QPDFObjectHandle& descriptor, FT_Library ftLib,
                             FT_Face face, bool reloadFromDescriptor, bool cid,
                             std::string const& subtypeName,
                             std::vector<SimpleGlyph> const& simpleGlyphs,
                             std::set<FT_UInt> const* retainedGlyphs, FontNormalizeState& state) {
    const bool simpleType1 = !cid && (subtypeName == "/Type1" || subtypeName == "/MMType1");
    if (!simpleType1) return;

    // Reload from the freshly subsetted stream when resubsetEmbeddedSfnt just
    // replaced the SFNT so we extract the subset CFF table rather than the full one.
    std::string ownedBytes;
    FT_Face sourceFace = face;
    FtFaceGuard localGuard;
    if (reloadFromDescriptor) {
        QPDFObjectHandle ff3 = descriptor.getKey("/FontFile3");
        if (!ff3.isStream()) return;
        try {
            std::shared_ptr<Buffer> buf = ff3.getStreamData(qpdf_dl_generalized);
            if (!buf || buf->getSize() == 0) return;
            ownedBytes.assign(reinterpret_cast<const char*>(buf->getBuffer()), buf->getSize());
        } catch (...) {
            return;
        }
        FT_Face reloaded = loadFontFace(ftLib, ownedBytes, 0);
        if (!reloaded) return;
        localGuard.reset(reloaded);
        sourceFace = reloaded;
    }

    if (!FT_IS_SFNT(sourceFace) || !fontHasSfntTable(sourceFace, FT_MAKE_TAG('C', 'F', 'F', ' ')) ||
        fontHasSfntTable(sourceFace, FT_MAKE_TAG('g', 'l', 'y', 'f')))
        return;

    FT_ULong tableLen = 0;
    if (FT_Load_Sfnt_Table(sourceFace, FT_MAKE_TAG('C', 'F', 'F', ' '), 0, nullptr, &tableLen) !=
            0 ||
        tableLen == 0)
        return;
    std::string cff(static_cast<size_t>(tableLen), '\0');
    FT_ULong loaded = tableLen;
    if (FT_Load_Sfnt_Table(sourceFace, FT_MAKE_TAG('C', 'F', 'F', ' '), 0,
                           reinterpret_cast<FT_Byte*>(cff.data()), &loaded) != 0 ||
        loaded != tableLen)
        return;

    FT_Face cffRaw = loadFontFace(ftLib, cff, 0);
    if (!cffRaw) return;
    FtFaceGuard cffGuard(cffRaw);
    bool valid = FT_HAS_GLYPH_NAMES(cffRaw) != 0;
    for (size_t code = 0; valid && code < simpleGlyphs.size(); ++code) {
        FT_UInt gid = simpleGlyphs[code].gid;
        if (!gid) continue;
        if (retainedGlyphs && !retainedGlyphs->empty() && !retainedGlyphs->contains(gid)) continue;
        char name[256] = "";
        if (FT_Get_Glyph_Name(face, gid, name, sizeof(name)) != 0 || !name[0] ||
            std::strcmp(name, ".notdef") == 0)
            continue;
        if (FT_Get_Name_Index(cffRaw, name) == 0) valid = false;
    }
    if (!valid) return;

    for (char const* key : {"/FontFile", "/FontFile2", "/FontFile3"}) descriptor.removeKey(key);
    QPDFObjectHandle cffStream = QPDFObjectHandle::newStream(&pdf, cff);
    cffStream.getDict().replaceKey("/Subtype", QPDFObjectHandle::newName("/Type1C"));
    descriptor.replaceKey("/FontFile3", cffStream);
    ++state.type1Converted;
    state.changed = true;
}

static std::set<uint32_t> cidsPresentInProgram(FT_Face face, QPDFObjectHandle& cidToGid,
                                               bool cidIdentity) {
    std::set<uint32_t> cids;
    if (cidToGid.isStream()) {
        try {
            std::shared_ptr<Buffer> data = cidToGid.getStreamData(qpdf_dl_generalized);
            if (data) {
                const size_t entries = data->getSize() / 2;
                auto const* bytes = static_cast<uint8_t const*>(data->getBuffer());
                for (size_t i = 0; i < entries && i <= kMaxCid; ++i) {
                    uint32_t gid = (static_cast<uint32_t>(bytes[2 * i]) << 8) | bytes[2 * i + 1];
                    if (!face || gid < static_cast<uint32_t>(face->num_glyphs))
                        cids.insert(static_cast<uint32_t>(i));
                }
            }
        } catch (...) {
            cids.clear();
        }
        return cids;
    }
    if (face && FT_IS_CID_KEYED(face)) {
        for (FT_UInt gid = 0; gid < static_cast<FT_UInt>(face->num_glyphs); ++gid) {
            FT_UInt cidVal = 0;
            if (FT_Get_CID_From_Glyph_Index(face, gid, &cidVal) == 0 && cidVal <= kMaxCid)
                cids.insert(static_cast<uint32_t>(cidVal));
        }
        return cids;
    }
    if (face && cidIdentity && face->num_glyphs > 0) {
        const long maxGid = std::min<long>(face->num_glyphs - 1, kMaxCid);
        for (long gid = 0; gid <= maxGid; ++gid) cids.insert(static_cast<uint32_t>(gid));
    }
    return cids;
}

static void writeCidSetIfDifferent(QPDF& pdf, QPDFObjectHandle& descriptor, std::set<uint32_t> cids,
                                   FontNormalizeState& state) {
    if (cids.empty()) return;
    cids.insert(0);
    const std::string bits = buildCidSetBits(cids);
    bool differs = true;
    QPDFObjectHandle existing = descriptor.getKey("/CIDSet");
    if (existing.isStream()) {
        try {
            std::shared_ptr<Buffer> old = existing.getStreamData(qpdf_dl_generalized);
            if (old)
                differs = std::string_view(reinterpret_cast<const char*>(old->getBuffer()),
                                           old->getSize()) != std::string_view(bits);
        } catch (...) {
            differs = true;
        }
    }
    if (differs) {
        descriptor.replaceKey("/CIDSet", QPDFObjectHandle::newStream(&pdf, bits));
        state.changed = true;
    }
}

static bool hasMsSymbolCmap(FT_Face face) {
    if (!face) return false;
    for (int i = 0; i < face->num_charmaps; ++i) {
        FT_CharMap cm = face->charmaps[i];
        if (cm && cm->platform_id == 3 && cm->encoding_id == 0) return true;
    }
    return false;
}

static bool hasMicrosoftOrMacRomanCmap(FT_Face face) {
    if (!face) return false;
    for (int i = 0; i < face->num_charmaps; ++i) {
        FT_CharMap cm = face->charmaps[i];
        if (!cm) continue;
        if ((cm->platform_id == 3 && cm->encoding_id == 1) ||
            (cm->platform_id == 1 && cm->encoding_id == 0)) {
            return true;
        }
    }
    return false;
}

static void seedDifferencesAgainstWinAnsi(bool fromStandard,
                                          std::vector<SimpleGlyph> const& simpleGlyphs,
                                          std::map<int, std::string>& cleanDiffs) {
    for (int code = 0x20; code <= 0xFF; ++code) {
        const uint32_t winU = baseEncodingUnicode(BaseEncoding::WinAnsi, code);
        uint32_t oldU = 0;
        if (fromStandard) {
            oldU = baseEncodingUnicode(BaseEncoding::Standard, code);
        } else if (static_cast<size_t>(code) < simpleGlyphs.size()) {
            oldU = simpleGlyphs[static_cast<size_t>(code)].unicode;
        }
        if (oldU != 0 && oldU != winU) {
            if (const char* agl = adobeGlyphNameForUnicode(oldU)) {
                cleanDiffs[code] = std::string("/") + agl;
            } else if (oldU <= 0xFFFF) {
                char buf[16];
                std::snprintf(buf, sizeof(buf), "/uni%04X", static_cast<unsigned int>(oldU));
                cleanDiffs[code] = buf;
            }
        }
    }
}

static QPDFObjectHandle buildDifferencesArray(std::map<int, std::string> const& cleanDiffs) {
    QPDFObjectHandle newDiffs = QPDFObjectHandle::newArray();
    int lastCode = -2;
    for (auto const& [code, gname] : cleanDiffs) {
        if (code != lastCode + 1) {
            newDiffs.appendItem(QPDFObjectHandle::newInteger(code));
        }
        newDiffs.appendItem(QPDFObjectHandle::newName(gname));
        lastCode = code;
    }
    return newDiffs;
}

static void normalizeSimpleTrueTypeEncoding(QPDFObjectHandle& font, QPDFObjectHandle& descriptor,
                                            FT_Face face, bool hasUnicodeCharmap,
                                            bool isType1Family,
                                            std::map<uint32_t, uint32_t> const& gidToUnicode,
                                            SimpleEncoding& simpleEncoding,
                                            std::vector<SimpleGlyph>& simpleGlyphs,
                                            bool& simpleAnyGid, FontNormalizeState& state) {
    bool encodingMutated = false;
    QPDFObjectHandle flagsObj = descriptor.getKey("/Flags");
    long long flags = flagsObj.isInteger() ? flagsObj.getIntValue() : 0LL;
    if ((flags & 4) != 0) {
        // ISO 19005-1 6.3.6 / ISO 19005-2 6.2.11.6: non-symbolic TrueType fonts must
        // carry a Microsoft Unicode (3,1) or Mac Roman (1,0) cmap subtable; keep fonts
        // with an MS Symbol (3,0) subtable or without (3,1)/(1,0) symbolic and strip /Encoding.
        if (!hasMsSymbolCmap(face) && hasMicrosoftOrMacRomanCmap(face)) {
            flags = (flags & ~4LL) | 32LL;
            descriptor.replaceKey("/Flags", QPDFObjectHandle::newInteger(flags));
            state.changed = true;
        } else {
            font.removeKey("/Encoding");
            state.changed = true;
            encodingMutated = true;
        }
    }
    if ((flags & 4) == 0 && font.hasKey("/Encoding")) {
        QPDFObjectHandle encObj = font.getKey("/Encoding");
        if (encObj.isName()) {
            std::string const encName = encObj.getName();
            if (encName != "/WinAnsiEncoding" && encName != "/MacRomanEncoding") {
                std::map<int, std::string> cleanDiffs;
                const bool fromStandard = !isType1Family && encName == "/StandardEncoding";
                seedDifferencesAgainstWinAnsi(fromStandard, simpleGlyphs, cleanDiffs);
                if (cleanDiffs.empty()) {
                    font.replaceKey("/Encoding", QPDFObjectHandle::newName("/WinAnsiEncoding"));
                } else {
                    QPDFObjectHandle newEnc = QPDFObjectHandle::newDictionary({
                        {"/Type", QPDFObjectHandle::newName("/Encoding")},
                        {"/BaseEncoding", QPDFObjectHandle::newName("/WinAnsiEncoding")},
                        {"/Differences", buildDifferencesArray(cleanDiffs)},
                    });
                    font.replaceKey("/Encoding", newEnc);
                }
                state.changed = true;
                encodingMutated = true;
            }
        } else if (encObj.isDictionary()) {
            QPDFObjectHandle baseObj = encObj.getKey("/BaseEncoding");
            std::string baseName = baseObj.isName() ? baseObj.getName() : "/WinAnsiEncoding";
            const bool replacingBase = !baseObj.isName() || (baseName != "/WinAnsiEncoding" &&
                                                             baseName != "/MacRomanEncoding");
            std::map<int, std::string> cleanDiffs;
            if (replacingBase) {
                const bool fromStandard =
                    !isType1Family && (!baseObj.isName() || baseName == "/StandardEncoding");
                seedDifferencesAgainstWinAnsi(fromStandard, simpleGlyphs, cleanDiffs);
                baseName = "/WinAnsiEncoding";
                encObj.replaceKey("/BaseEncoding", QPDFObjectHandle::newName("/WinAnsiEncoding"));
                state.changed = true;
                encodingMutated = true;
            }
            const bool hadDifferences = encObj.hasKey("/Differences");
            if (hadDifferences) {
                QPDFObjectHandle diffObj = encObj.getKey("/Differences");
                if (diffObj.isArray()) {
                    int curCode = 0;
                    for (auto& item : diffObj.getArrayAsVector()) {
                        if (item.isInteger()) {
                            const long long raw = item.getIntValue();
                            curCode = (raw >= 0 && raw <= 255) ? static_cast<int>(raw) : 256;
                        } else if (item.isName()) {
                            std::string gname = item.getName();
                            if (gname == "/.null") gname = "/.notdef";
                            const bool redundantControl = curCode < 32 && gname == "/.notdef";
                            std::string const rawName = gname.size() > 1 ? gname.substr(1) : "";
                            const bool validGlyph =
                                !rawName.empty() && (isAdobeGlyphListMember(rawName) ||
                                                     !unicodeSequenceForGlyphName(rawName).empty());
                            if (curCode <= 255) {
                                if (!redundantControl && validGlyph) {
                                    cleanDiffs[curCode] = gname;
                                } else {
                                    cleanDiffs.erase(curCode);
                                }
                            }
                            if (curCode < 256) ++curCode;
                        }
                    }
                }
            }
            if (hadDifferences || !cleanDiffs.empty()) {
                if (cleanDiffs.empty()) {
                    font.replaceKey("/Encoding", QPDFObjectHandle::newName(baseName));
                    state.changed = true;
                    encodingMutated = true;
                } else {
                    QPDFObjectHandle newDiffs = buildDifferencesArray(cleanDiffs);
                    QPDFObjectHandle prevDiffs = encObj.getKey("/Differences");
                    if (!hadDifferences || !prevDiffs.isArray() ||
                        newDiffs.unparse() != prevDiffs.unparse()) {
                        encObj.replaceKey("/Differences", newDiffs);
                        state.changed = true;
                        encodingMutated = true;
                    }
                }
            }
        }
    }
    if (encodingMutated) {
        simpleEncoding = parseSimpleEncoding(font, descriptor);
        if (!simpleEncoding.unsupportedEncoding) {
            simpleGlyphs = resolveSimpleChars(face, hasUnicodeCharmap, gidToUnicode, simpleEncoding,
                                              isType1Family);
            simpleAnyGid = std::any_of(simpleGlyphs.begin(), simpleGlyphs.end(),
                                       [](SimpleGlyph const& g) { return g.gid != 0; });
        }
    }
}

static void processFont(QPDF& pdf, FT_Library ftLib, QPDFObjectHandle font, bool doFixToUnicode,
                        bool doRepairWidths, FontUsageCounts const& usage,
                        FontNormalizeState& state) {
    // Reject unsupported subtypes up front before parsing font descriptors or programs.
    QPDFObjectHandle subtype = font.getKey("/Subtype");
    if (!subtype.isName()) return;
    const std::string subtypeName = subtype.getName();
    const bool cid = subtypeName == "/Type0";
    if (!cid && subtypeName != "/TrueType" && subtypeName != "/Type1" && subtypeName != "/MMType1")
        return;

    QPDFObjectHandle target = font;
    if (cid) {
        QPDFObjectHandle descendants = font.getKey("/DescendantFonts");
        if (!descendants.isArray() || descendants.getArrayNItems() < 1) return;
        target = descendants.getArrayItem(0);
        if (!target.isDictionary()) return;
        QPDFObjectHandle cidEncoding = font.getKey("/Encoding");
        if (!cidEncoding.isName() || (!cidEncoding.isNameAndEquals("/Identity-H") &&
                                      !cidEncoding.isNameAndEquals("/Identity-V")))
            return;
    }

    QPDFObjectHandle descriptor = target.getKey("/FontDescriptor");
    if (!descriptor.isDictionary()) return;

    QPDFObjectHandle stream = embeddedFontProgram(descriptor);
    if (!stream.isStream()) return;

    std::shared_ptr<Buffer> data;
    try {
        data = stream.getStreamData(qpdf_dl_generalized);
    } catch (...) {
        return;
    }
    if (!data || data->getSize() == 0) return;

    // Collect /BaseFont and /FontName candidates to disambiguate TTC font collection faces.
    std::set<std::string> desiredFaces;
    for (char const* key : {"/BaseFont", "/FontName"}) {
        QPDFObjectHandle candidate = font.getKey(key);
        if (!candidate.isName()) candidate = target.getKey(key);
        if (!candidate.isName()) candidate = descriptor.getKey(key);
        if (candidate.isName()) {
            std::string faceKey = normalizeFontNameKey(candidate.getName());
            if (!faceKey.empty()) desiredFaces.insert(faceKey);
        }
    }

    const FT_Long faceIndex =
        selectCollectionFace(ftLib, data->getBuffer(), data->getSize(), desiredFaces);
    FT_Face rawFace = nullptr;
    if (FT_New_Memory_Face(ftLib, data->getBuffer(), static_cast<FT_Long>(data->getSize()),
                           faceIndex, &rawFace) != 0 ||
        !rawFace)
        return;
    FtFaceGuard faceGuard(rawFace);
    if (rawFace->num_glyphs <= 0) return;
    ++state.fontsProcessed;

    const bool hasUnicodeCharmap = FT_Select_Charmap(rawFace, FT_ENCODING_UNICODE) == 0;
    std::map<uint32_t, uint32_t> gidToUnicode;
    std::set<uint32_t> ambiguousGids;
    auto isSpaceScalar = [](uint32_t value) {
        return value == 0x20 || value == 0xA0 || value == 0xAD || value == 0x2D;
    };
    if (hasUnicodeCharmap) {
        FT_UInt gid = 0;
        FT_ULong codepoint = FT_Get_First_Char(rawFace, &gid);
        while (gid != 0) {
            uint32_t unicode = static_cast<uint32_t>(codepoint);
            auto [it, inserted] = gidToUnicode.emplace(static_cast<uint32_t>(gid), unicode);
            if (!inserted && it->second != unicode && !isSpaceScalar(it->second) &&
                !isSpaceScalar(unicode))
                ambiguousGids.insert(static_cast<uint32_t>(gid));
            codepoint = FT_Get_Next_Char(rawFace, codepoint, &gid);
        }
    }

    QPDFObjectHandle cidToGid;
    bool cidIdentity = false;
    std::map<uint32_t, uint32_t> gidToCid;
    if (cid) {
        QPDFObjectHandle cidSubtype = target.getKey("/Subtype");
        // Stream /CIDToGIDMap is already self-contained; validate null or /Identity defaults.
        cidToGid = target.getKey("/CIDToGIDMap");
        if (!cidToGid.isStream()) {
            if (cidToGid.isNull() || cidToGid.isNameAndEquals("/Identity")) {
                if (FT_IS_CID_KEYED(rawFace)) {
                    if (!cidSubtype.isNameAndEquals("/CIDFontType0")) return;
                    for (FT_UInt gid = 0; gid < static_cast<FT_UInt>(rawFace->num_glyphs); ++gid) {
                        FT_UInt cidValue = 0;
                        if (FT_Get_CID_From_Glyph_Index(rawFace, gid, &cidValue) == 0 &&
                            cidValue <= kMaxCid)
                            gidToCid[static_cast<uint32_t>(gid)] = static_cast<uint32_t>(cidValue);
                    }
                    cidIdentity = true;
                } else {
                    if (!cidSubtype.isNameAndEquals("/CIDFontType2")) return;
                    cidIdentity = true;
                    if (cidToGid.isNull()) {
                        target.replaceKey("/CIDToGIDMap", QPDFObjectHandle::newName("/Identity"));
                        state.changed = true;
                    }
                }
            } else {
                return;
            }
        }
    }

    const char* fontFormat = FT_Get_Font_Format(rawFace);
    const bool isType1Family = subtypeName == "/Type1" || subtypeName == "/MMType1" ||
                               (fontFormat && (std::strcmp(fontFormat, "Type 1") == 0 ||
                                               std::strcmp(fontFormat, "CFF") == 0));

    SimpleEncoding simpleEncoding;
    std::vector<SimpleGlyph> simpleGlyphs;
    bool simpleAnyGid = false;
    if (!cid) {
        simpleEncoding = parseSimpleEncoding(font, descriptor);
        if (!simpleEncoding.unsupportedEncoding) {
            simpleGlyphs = resolveSimpleChars(rawFace, hasUnicodeCharmap, gidToUnicode,
                                              simpleEncoding, isType1Family);
            simpleAnyGid = std::any_of(simpleGlyphs.begin(), simpleGlyphs.end(),
                                       [](SimpleGlyph const& g) { return g.gid != 0; });
        }
        if (subtypeName == "/TrueType" && font.hasKey("/Encoding")) {
            normalizeSimpleTrueTypeEncoding(font, descriptor, rawFace, hasUnicodeCharmap,
                                            isType1Family, gidToUnicode, simpleEncoding,
                                            simpleGlyphs, simpleAnyGid, state);
        }
    }

    // Repair /ToUnicode before widths and resubsetting so encoding fixes drive glyph discovery.
    if (doFixToUnicode) {
        repairFontToUnicode(pdf, font, target, rawFace, cid, cidToGid, cidIdentity, gidToCid,
                            gidToUnicode, ambiguousGids, simpleAnyGid, simpleGlyphs, state);
    }
    const bool full = doFixToUnicode && doRepairWidths;
    if (doRepairWidths && rawFace->units_per_EM > 0) {
        if (!cid) {
            repairSimpleFontWidths(target, rawFace, simpleEncoding, simpleAnyGid, simpleGlyphs,
                                   state, full);
        } else {
            repairCidFontWidths(pdf, font, target, rawFace, cidToGid, cidIdentity, gidToCid, state);
        }
    }

    // Order matters (fontTools P0.2): resubset SFNT before extracting bare CFF so
    // OpenType-CFF (/Type1) streams get pruned prior to /Type1C conversion.
    bool didResubset = false;
    std::set<FT_UInt> retainedGlyphs;
    if (full) {
        didResubset = resubsetEmbeddedSfnt(
            pdf, font, target, descriptor, stream, data, faceIndex, ftLib, rawFace, cid, cidToGid,
            cidIdentity, simpleEncoding.symbolic, simpleGlyphs, usage, state, &retainedGlyphs);
        convertCffInSfnt(pdf, descriptor, ftLib, rawFace, didResubset, cid, subtypeName,
                         simpleGlyphs, didResubset ? &retainedGlyphs : nullptr, state);
    }

    if (cid && full && descriptor.hasKey("/CIDSet")) {
        FT_Face cidSetFace = rawFace;
        FtFaceGuard cidSetGuard;
        std::string cidSetOwned;
        QPDFObjectHandle finalStream = embeddedFontProgram(descriptor);
        if (finalStream.isStream()) {
            try {
                std::shared_ptr<Buffer> finalData = finalStream.getStreamData(qpdf_dl_generalized);
                if (finalData && finalData->getSize() > 0 &&
                    (finalData->getSize() != data->getSize() ||
                     std::memcmp(finalData->getBuffer(), data->getBuffer(), finalData->getSize()) !=
                         0)) {
                    cidSetOwned.assign(reinterpret_cast<const char*>(finalData->getBuffer()),
                                       finalData->getSize());
                    if (FT_Face reloaded = loadFontFace(ftLib, cidSetOwned, 0)) {
                        cidSetGuard.reset(reloaded);
                        cidSetFace = reloaded;
                    }
                }
            } catch (...) {
            }
        }
        writeCidSetIfDifferent(pdf, descriptor,
                               cidsPresentInProgram(cidSetFace, cidToGid, cidIdentity), state);
    }

    // Rebuild /CharSet for Type 1/CFF subsets when every retained glyph has a PostScript name.
    if (!cid && isType1Family && descriptor.hasKey("/CharSet")) {
        std::string charsetStr;
        char glyphName[256];
        bool allNamed = true;
        auto appendGlyphName = [&](FT_UInt gid) {
            if (gid == 0) return;
            if (FT_HAS_GLYPH_NAMES(rawFace) &&
                FT_Get_Glyph_Name(rawFace, gid, glyphName, sizeof(glyphName)) == 0 &&
                glyphName[0] != '\0') {
                if (std::strcmp(glyphName, ".notdef") != 0) {
                    charsetStr += '/';
                    charsetStr += glyphName;
                }
            } else {
                allNamed = false;
            }
        };
        if (didResubset) {
            for (FT_UInt gid : retainedGlyphs) appendGlyphName(gid);
        } else {
            for (FT_UInt gid = 1; gid < static_cast<FT_UInt>(rawFace->num_glyphs); ++gid) {
                appendGlyphName(gid);
            }
        }
        if (allNamed && !charsetStr.empty()) {
            descriptor.replaceKey("/CharSet", QPDFObjectHandle::newString(charsetStr));
            state.changed = true;
        } else {
            descriptor.removeKey("/CharSet");
            state.changed = true;
        }
    }
}

}  // namespace

namespace jpdfium::qpdf_detail {

int32_t fontNormalizeQpdf(DocWrapper* w, int32_t pageIndex, bool doFixToUnicode,
                          bool doRepairWidths, int32_t* fontsProcessed, int32_t* tucFixed,
                          int32_t* wFixed, int32_t* type1Converted, int32_t* resubset) {
    if (!w || !w->core || !w->core->doc || !fontsProcessed || !tucFixed || !wFixed ||
        !type1Converted || !resubset)
        return JPDFIUM_ERR_INVALID;
    *fontsProcessed = 0;
    *tucFixed = 0;
    *wFixed = 0;
    *type1Converted = 0;
    *resubset = 0;

    const int pageCount = FPDF_GetPageCount(w->core->doc);
    if (pageIndex < -1 || pageIndex >= pageCount) return JPDFIUM_ERR_INVALID;

    FreeTypeSession ft;
    if (!ft.init()) return JPDFIUM_ERR_NATIVE;

    // Snapshot PDFium's current bytes because QPDF drives font parsing and stream rewriting.
    std::vector<uint8_t> pdfBytes;
    if (!qpdfSaveFpdf(w, pdfBytes)) return JPDFIUM_ERR_IO;

    try {
        QPDF pdf;
        pdf.setSuppressWarnings(true);
        pdf.setAttemptRecovery(true);
        pdf.processMemoryFile("font_normalize", reinterpret_cast<const char*>(pdfBytes.data()),
                              pdfBytes.size());

        std::vector<QPDFObjectHandle> pages = pdf.getAllPages();
        if (pageIndex >= static_cast<int>(pages.size())) return JPDFIUM_ERR_INVALID;

        FontUsageCounts usage;
        {
            std::set<QPDFObjGen> counted;
            for (auto& page : pages) {
                std::set<QPDFObjGen> seenResources;
                std::set<QPDFObjGen> seenFonts;
                for (auto& font : collectPageFonts(page, seenResources, seenFonts))
                    countFontUsage(font, counted, usage);
            }
            // AcroForm /DR fonts serve dynamic field text at view time, so never subset them.
            auto acroForm = pdf.getRoot().getKey("/AcroForm");
            if (acroForm.isDictionary() && acroForm.hasKey("/DR")) {
                std::set<QPDFObjGen> seenDrResources;
                std::set<QPDFObjGen> seenDrFonts;
                std::map<QPDFObjGen, int> drStreams;
                std::vector<QPDFObjectHandle> drFonts;
                collectResourceFonts(acroForm.getKey("/DR"), 0, seenDrResources, seenDrFonts,
                                     drStreams, drFonts);
                for (auto& drFont : drFonts) {
                    markFormFontNotSubsettable(drFont, usage);
                }
            }
        }

        FontNormalizeState state;
        auto runFont = [&](QPDFObjectHandle& font) {
            try {
                processFont(pdf, ft.lib, font, doFixToUnicode, doRepairWidths, usage, state);
            } catch (...) {
                return;
            }
        };

        if (pageIndex == -1) {
            std::set<QPDFObjGen> seenResources;
            std::set<QPDFObjGen> seenFonts;
            for (auto& page : pages) {
                for (auto& font : collectPageFonts(page, seenResources, seenFonts)) runFont(font);
            }
            std::vector<QPDFObjectHandle> formFonts;
            std::map<QPDFObjGen, int> formStreams;
            collectResourceFonts(pdf.getRoot().getKey("/AcroForm").getKey("/DR"), 0, seenResources,
                                 seenFonts, formStreams, formFonts);
            for (auto& font : formFonts) runFont(font);
        } else {
            std::set<QPDFObjGen> seenResources;
            std::set<QPDFObjGen> seenFonts;
            for (auto& font : collectPageFonts(pages[pageIndex], seenResources, seenFonts))
                runFont(font);
        }

        // Ensure every subset gets a unique 6-letter prefix before reloading into PDFium.
        if (state.resubset > 0 && disambiguateSubsetFontTags(pdf) > 0) {
            state.changed = true;
        }

        *fontsProcessed = state.fontsProcessed;
        *tucFixed = state.tucFixed;
        *wFixed = state.wFixed;
        *type1Converted = state.type1Converted;
        *resubset = state.resubset;

        if (state.changed) return docwrapperReload(w, qpdfWriteBytes(pdf));
        return JPDFIUM_OK;
    } catch (QPDFExc const& e) {
        return e.getErrorCode() == qpdf_e_password ? JPDFIUM_ERR_PASSWORD : JPDFIUM_ERR_INVALID;
    } catch (std::exception&) {
        return JPDFIUM_ERR_INVALID;
    }
}

}  // namespace jpdfium::qpdf_detail

int32_t jpdfium_font_fix_tounicode(int64_t doc, int32_t page_index, int32_t* fonts_fixed) {
    try {
        int32_t ignored = 0;
        int32_t* fixed = fonts_fixed ? fonts_fixed : &ignored;
        *fixed = 0;
        DocWrapper* w = decodeDoc(doc);
        if (!w || !w->core || !w->core->doc) return JPDFIUM_ERR_INVALID;
        int32_t processed = 0, widths = 0, type1 = 0, resubset = 0;
        return fontNormalizeQpdf(w, page_index, true, false, &processed, fixed, &widths, &type1,
                                 &resubset);
    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

int32_t jpdfium_font_repair_widths(int64_t doc, int32_t page_index, int32_t* fonts_fixed) {
    try {
        int32_t ignored = 0;
        int32_t* fixed = fonts_fixed ? fonts_fixed : &ignored;
        *fixed = 0;
        DocWrapper* w = decodeDoc(doc);
        if (!w || !w->core || !w->core->doc) return JPDFIUM_ERR_INVALID;
        int32_t processed = 0, tounicode = 0, type1 = 0, resubset = 0;
        return fontNormalizeQpdf(w, page_index, false, true, &processed, &tounicode, fixed, &type1,
                                 &resubset);
    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

int32_t jpdfium_font_normalize_page(int64_t doc, int32_t page_index, char** json) {
    try {
        if (!json) return JPDFIUM_ERR_INVALID;
        *json = nullptr;
        DocWrapper* w = decodeDoc(doc);
        if (!w || !w->core || !w->core->doc) return JPDFIUM_ERR_INVALID;

        int32_t processed = 0, tucFixed = 0, wFixed = 0, type1Converted = 0, resubset = 0;
        int32_t rc = fontNormalizeQpdf(w, page_index, true, true, &processed, &tucFixed, &wFixed,
                                       &type1Converted, &resubset);
        if (rc != JPDFIUM_OK) return rc;

        char buf[256];
        std::snprintf(buf, sizeof(buf),
                      "{\"fonts_processed\":%d,\"tounicode_fixed\":%d,"
                      "\"widths_repaired\":%d,\"type1_converted\":%d,\"resubset\":%d}",
                      processed, tucFixed, wFixed, type1Converted, resubset);
        *json = strdup(buf);
        return *json ? JPDFIUM_OK : JPDFIUM_ERR_NATIVE;
    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

#elif defined(JPDFIUM_HAS_FREETYPE) && defined(JPDFIUM_HAS_HARFBUZZ)

int32_t jpdfium_font_fix_tounicode(int64_t doc, int32_t, int32_t* fonts_fixed) {
    if (fonts_fixed) *fonts_fixed = 0;
    DocWrapper* w = decodeDoc(doc);
    if (!w || !w->core || !w->core->doc) return JPDFIUM_ERR_INVALID;
    return JPDFIUM_ERR_NOT_FOUND;
}

int32_t jpdfium_font_repair_widths(int64_t doc, int32_t, int32_t* fonts_fixed) {
    if (fonts_fixed) *fonts_fixed = 0;
    DocWrapper* w = decodeDoc(doc);
    if (!w || !w->core || !w->core->doc) return JPDFIUM_ERR_INVALID;
    return JPDFIUM_ERR_NOT_FOUND;
}

int32_t jpdfium_font_normalize_page(int64_t doc, int32_t, char** json) {
    if (!json) return JPDFIUM_ERR_INVALID;
    *json = nullptr;
    DocWrapper* w = decodeDoc(doc);
    if (!w || !w->core || !w->core->doc) return JPDFIUM_ERR_INVALID;
    return JPDFIUM_ERR_NOT_FOUND;
}

#else

int32_t jpdfium_font_fix_tounicode(int64_t, int32_t, int32_t* f) {
    if (f) *f = 0;
    return JPDFIUM_ERR_NOT_FOUND;
}

int32_t jpdfium_font_repair_widths(int64_t, int32_t, int32_t* f) {
    if (f) *f = 0;
    return JPDFIUM_ERR_NOT_FOUND;
}

int32_t jpdfium_font_normalize_page(int64_t, int32_t, char** json) {
    if (json) *json = strdup("{\"error\":\"FreeType/HarfBuzz not available\"}");
    return JPDFIUM_ERR_NOT_FOUND;
}

#endif
