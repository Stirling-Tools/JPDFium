// jpdfium_qpdf_fonts_subset.cpp - Document-wide font inventory and verified
// lossless subsetting for embedded TrueType, OpenType, and bare CFF streams.
#include "jpdfium_qpdf_internal.h"
#include "jpdfium_qpdf_writer_lock.h"

#ifdef JPDFIUM_HAS_QPDF

#include <algorithm>
#include <cstring>
#include <iterator>
#include <map>
#include <qpdf/QPDFPageDocumentHelper.hh>
#include <qpdf/QPDFPageObjectHelper.hh>
#include <set>
#include <string>
#include <vector>

namespace jpdfium::qpdf_detail {

using jpdfium_font_encoding::BaseEncoding;
using jpdfium_font_encoding::baseEncodingUnicode;
using jpdfium_qpdf_internal::WriterLock;

#if defined(JPDFIUM_HAS_FREETYPE) && defined(JPDFIUM_HAS_HARFBUZZ)

void updateCidSet(QPDF& qpdf, QPDFObjectHandle descriptor, std::set<uint32_t> const& activeCids) {
    if (!descriptor.isDictionary()) return;
    std::string bits = jpdfium::font::buildCidSetBits(activeCids);
    if (!bits.empty())
        descriptor.replaceKey("/CIDSet", QPDFObjectHandle::newStream(&qpdf, bits));
    else
        descriptor.removeKey("/CIDSet");
}

std::set<uint32_t> cidsPresentInProgram(QPDFObjectHandle const& target, FT_Face face) {
    std::set<uint32_t> cids;
    auto map = target.getKey("/CIDToGIDMap");
    if (map.isStream()) {
        try {
            std::shared_ptr<Buffer> data = map.getStreamData(qpdf_dl_generalized);
            if (data) {
                const size_t entries = data->getSize() / 2;
                auto const* bytes = reinterpret_cast<uint8_t const*>(data->getBuffer());
                for (size_t i = 0; i < entries && i <= jpdfium::font::kMaxCid; ++i) {
                    uint32_t gid = (uint32_t(bytes[2 * i]) << 8) | bytes[2 * i + 1];
                    if (!face || gid < static_cast<uint32_t>(face->num_glyphs)) {
                        cids.insert(static_cast<uint32_t>(i));
                    }
                }
            }
        } catch (...) {
            cids.clear();
        }
        return cids;
    }
    if (face && FT_IS_CID_KEYED(face)) {
        for (FT_UInt gid = 0; gid < static_cast<FT_UInt>(face->num_glyphs); ++gid) {
            FT_UInt cid = 0;
            if (FT_Get_CID_From_Glyph_Index(face, gid, &cid) == 0 &&
                cid <= jpdfium::font::kMaxCid) {
                cids.insert(static_cast<uint32_t>(cid));
            }
        }
        return cids;
    }
    if (face && face->num_glyphs > 0) {
        const long maxGid = std::min<long>(face->num_glyphs - 1, jpdfium::font::kMaxCid);
        for (long gid = 0; gid <= maxGid; ++gid) cids.insert(static_cast<uint32_t>(gid));
    }
    return cids;
}

bool subsetKeepsSimpleGlyphs(FT_Library lib, std::string const& subsetBytes, FT_Face original,
                             bool symbolic, jpdfium::font::SimpleEncoding const& enc,
                             std::set<uint32_t> const& codes, bool retainGids) {
    FT_Face face = nullptr;
    if (subsetBytes.empty() ||
        FT_New_Memory_Face(lib, reinterpret_cast<const FT_Byte*>(subsetBytes.data()),
                           FT_Long(subsetBytes.size()), 0, &face)) {
        return false;
    }
    jpdfium::font::FtFaceGuard guard(face);
    for (uint32_t code : codes) {
        if (code >= 256) continue;
        FT_UInt before =
            jpdfium::font::glyphForSimpleCode(original, static_cast<int>(code), symbolic, enc);
        FT_UInt after =
            jpdfium::font::glyphForSimpleCode(face, static_cast<int>(code), symbolic, enc);
        bool same = true;
        if (retainGids || before == 0 || after == 0) {
            same = (before == after);
        } else {
            char beforeName[256] = "";
            char afterName[256] = "";
            if (FT_HAS_GLYPH_NAMES(original) && FT_HAS_GLYPH_NAMES(face) &&
                FT_Get_Glyph_Name(original, before, beforeName, sizeof(beforeName)) == 0 &&
                FT_Get_Glyph_Name(face, after, afterName, sizeof(afterName)) == 0) {
                same = (std::strcmp(beforeName, afterName) == 0);
            } else {
                same = false;
            }
        }
        if (!same) return false;
        FT_Fixed beforeAdvance = 0;
        FT_Fixed afterAdvance = 0;
        if (before && after &&
            (FT_Get_Advance(original, before, FT_LOAD_NO_SCALE, &beforeAdvance) ||
             FT_Get_Advance(face, after, FT_LOAD_NO_SCALE, &afterAdvance) ||
             beforeAdvance != afterAdvance)) {
            return false;
        }
    }
    return true;
}

namespace {

bool subsetKeepsCidKeyedCff(FT_Library lib, std::string const& subsetBytes, FT_Face original,
                            std::set<uint32_t> const& usedCids,
                            std::map<uint32_t, FT_UInt> const& cidToOrigGid) {
    FT_Face face = nullptr;
    if (subsetBytes.empty() ||
        FT_New_Memory_Face(lib, reinterpret_cast<const FT_Byte*>(subsetBytes.data()),
                           FT_Long(subsetBytes.size()), 0, &face)) {
        return false;
    }
    jpdfium::font::FtFaceGuard guard(face);
    if (!FT_IS_CID_KEYED(face)) return false;

    std::map<uint32_t, FT_UInt> cidToNewGid;
    for (FT_UInt g = 0; g < static_cast<FT_UInt>(face->num_glyphs); ++g) {
        FT_UInt cid = 0;
        if (FT_Get_CID_From_Glyph_Index(face, g, &cid) == 0) {
            cidToNewGid.try_emplace(cid, g);
        }
    }
    for (uint32_t cid : usedCids) {
        auto origIt = cidToOrigGid.find(cid);
        auto newIt = cidToNewGid.find(cid);
        if (origIt == cidToOrigGid.end() || newIt == cidToNewGid.end()) return false;
        FT_Fixed before = 0;
        FT_Fixed after = 0;
        if (FT_Get_Advance(original, origIt->second, FT_LOAD_NO_SCALE, &before) ||
            FT_Get_Advance(face, newIt->second, FT_LOAD_NO_SCALE, &after) || before != after) {
            return false;
        }
    }
    return true;
}

std::string buildTag(std::string const& baseName, std::set<FT_UInt> const& glyphs) {
    std::set<uint32_t> ids(glyphs.begin(), glyphs.end());
    return jpdfium::font::buildSubsetTagForGlyphs(baseName, ids);
}

bool subsetEmbeddedCidFont(QPDF& qpdf, FT_Library lib, QPDFObjectHandle& font,
                           QPDFObjectHandle& target, QPDFObjectHandle& descriptor,
                           FontProgram const& program, std::string const& baseName,
                           FontUsageIndexes const& usage, bool targetShared) {
    // Only Identity-H/V 2-byte encodings can safely re-index CIDs without custom CMap tables.
    if (!font.getKey("/Encoding").isNameAndEquals("/Identity-H") &&
        !font.getKey("/Encoding").isNameAndEquals("/Identity-V")) {
        return false;
    }

    auto scan = usedCidsForFont(qpdf, font, CidEncoding::TwoByte, &usage);
    if (!scan.complete || scan.cids.empty()) return false;

    // Bare CID-keyed CFF (/CIDFontType0C) path: retain GIDs & verify CID mapping via FreeType.
    if (program.kind == ProgramKind::BareCff) {
        if (!FT_IS_CID_KEYED(program.face)) return false;
        std::map<uint32_t, FT_UInt> cidToGid;
        for (FT_UInt g = 0; g < static_cast<FT_UInt>(program.face->num_glyphs); ++g) {
            FT_UInt cid = 0;
            if (FT_Get_CID_From_Glyph_Index(program.face, g, &cid) == 0)
                cidToGid.try_emplace(cid, g);
        }
        std::set<FT_UInt> targetGlyphs{0};
        for (uint32_t cid : scan.cids) {
            auto it = cidToGid.find(cid);
            if (it == cidToGid.end()) return false;
            targetGlyphs.insert(it->second);
        }
        if (targetGlyphs.size() >= static_cast<size_t>(program.face->num_glyphs)) return false;

        auto sub = jpdfium::font::subsetBareCffViaSfnt(program.face, program.bytes, targetGlyphs,
                                                       /*retainGids=*/true,
                                                       /*keepGlyphNames=*/false);
        if (sub.bytes.empty() || sub.bytes.size() + 64 >= program.bytes.size()) return false;
        if (!subsetKeepsCidKeyedCff(lib, sub.bytes, program.face, scan.cids, cidToGid)) {
            return false;
        }

        std::string tag = buildTag(baseName, targetGlyphs);
        auto tagged = QPDFObjectHandle::newName("/" + tag + "+" + baseName);
        font.replaceKey("/BaseFont", tagged);
        target.replaceKey("/BaseFont", tagged);
        descriptor.replaceKey("/FontName", tagged);
        for (char const* key : {"/FontFile", "/FontFile2", "/FontFile3"}) descriptor.removeKey(key);
        descriptor.replaceKey(
            "/FontFile3", makeProgramStream(qpdf, program, ProgramKind::BareCff, true, sub.bytes));
        if (descriptor.hasKey("/CIDSet")) {
            std::set<uint32_t> retainedCids{0};
            retainedCids.insert(scan.cids.begin(), scan.cids.end());
            updateCidSet(qpdf, descriptor, retainedCids);
        }
        return true;
    }

    if (FT_IS_CID_KEYED(program.face)) return false;

    // Load any non-identity /CIDToGIDMap stream up front so CID->GID lookups stay bounded.
    auto cidMap = target.getKey("/CIDToGIDMap");
    bool identity = cidMap.isNull() || cidMap.isNameAndEquals("/Identity");
    std::shared_ptr<Buffer> mapBytes;
    if (!identity) {
        if (!cidMap.isStream()) return false;
        try {
            mapBytes = cidMap.getStreamData(qpdf_dl_generalized);
        } catch (...) {
            return false;
        }
        if (!mapBytes) return false;
    }

    std::set<FT_UInt> targetGlyphs{0};
    std::map<uint32_t, FT_UInt> cidToOldGid;
    uint32_t maxUsedCid = 0;
    for (uint32_t c : scan.cids) {
        if (c > jpdfium::font::kMaxCid) return false;
        maxUsedCid = std::max(maxUsedCid, c);
        uint32_t gid = c;
        if (!identity) {
            const size_t mapOff = static_cast<size_t>(c) * 2;
            if (mapBytes->getSize() < mapOff + 2) return false;
            auto const* bytes = mapBytes->getBuffer();
            gid = (static_cast<uint32_t>(bytes[mapOff]) << 8) | bytes[mapOff + 1];
        }
        if (gid >= static_cast<uint32_t>(program.face->num_glyphs)) return false;
        targetGlyphs.insert(gid);
        cidToOldGid[c] = static_cast<FT_UInt>(gid);
    }
    if (targetGlyphs.size() >= static_cast<size_t>(program.face->num_glyphs)) return false;

    // For CID fonts, PDF maps CID -> GID without PostScript names; strip post glyph names.
    auto retainRes = jpdfium::font::subsetSfntProgram(
        program.face, reinterpret_cast<const uint8_t*>(program.bytes.data()), program.bytes.size(),
        0, targetGlyphs, /*retainGids=*/true, /*keepGlyphNames=*/false);

    bool useCompactedMap = false;
    std::string chosenSubset;
    std::string compactedMapBytes;
    const size_t origTotalBytes = program.bytes.size() + (mapBytes ? mapBytes->getSize() : 0);

    // Compact sparse GIDs into dense GIDs + explicit /CIDToGIDMap when num_glyphs is large.
    if (!targetShared && program.kind == ProgramKind::TrueType && maxUsedCid < 4096 &&
        program.face->num_glyphs > static_cast<long>(targetGlyphs.size()) * 2 + 256) {
        auto compactRes = jpdfium::font::subsetSfntProgram(
            program.face, reinterpret_cast<const uint8_t*>(program.bytes.data()),
            program.bytes.size(), 0, targetGlyphs, /*retainGids=*/false,
            /*keepGlyphNames=*/false);
        if (!compactRes.bytes.empty() &&
            jpdfium::font::subsetKeepsAdvances(lib, compactRes.bytes, 0, program.face, targetGlyphs,
                                               &compactRes.oldToNewGid)) {
            std::string newMap(2 * (static_cast<size_t>(maxUsedCid) + 1), '\0');
            bool mapOk = true;
            if (auto it0 = compactRes.oldToNewGid.find(0);
                it0 != compactRes.oldToNewGid.end() && it0->second <= 0xFFFFU) {
                newMap[0] = static_cast<char>((it0->second >> 8) & 0xFFU);
                newMap[1] = static_cast<char>(it0->second & 0xFFU);
            } else {
                mapOk = false;
            }
            for (auto const& [cid, oldGid] : cidToOldGid) {
                auto it = compactRes.oldToNewGid.find(oldGid);
                if (it == compactRes.oldToNewGid.end() || it->second > 0xFFFFU) {
                    mapOk = false;
                    break;
                }
                const size_t mapOff = static_cast<size_t>(cid) * 2;
                newMap[mapOff] = static_cast<char>((it->second >> 8) & 0xFFU);
                newMap[mapOff + 1] = static_cast<char>(it->second & 0xFFU);
            }
            const size_t compactTotal = compactRes.bytes.size() + newMap.size();
            const size_t retainBaseline =
                (!retainRes.bytes.empty()
                     ? retainRes.bytes.size() + (mapBytes ? mapBytes->getSize() : 0)
                     : origTotalBytes);
            if (mapOk && compactTotal + 64 < origTotalBytes && compactTotal + 64 < retainBaseline) {
                useCompactedMap = true;
                chosenSubset = std::move(compactRes.bytes);
                compactedMapBytes = std::move(newMap);
            }
        }
    }

    if (!useCompactedMap) {
        if (retainRes.bytes.empty() || retainRes.bytes.size() + 64 >= program.bytes.size()) {
            return false;
        }
        if (!jpdfium::font::subsetKeepsAdvances(lib, retainRes.bytes, 0, program.face,
                                                targetGlyphs)) {
            return false;
        }
        chosenSubset = std::move(retainRes.bytes);
    }

    std::string tag = buildTag(baseName, targetGlyphs);
    auto tagged = QPDFObjectHandle::newName("/" + tag + "+" + baseName);
    font.replaceKey("/BaseFont", tagged);
    target.replaceKey("/BaseFont", tagged);
    descriptor.replaceKey("/FontName", tagged);

    if (useCompactedMap) {
        target.replaceKey("/CIDToGIDMap", QPDFObjectHandle::newStream(&qpdf, compactedMapBytes));
    }
    for (char const* key : {"/FontFile", "/FontFile2", "/FontFile3"}) descriptor.removeKey(key);
    descriptor.replaceKey(program.kind == ProgramKind::TrueType ? "/FontFile2" : "/FontFile3",
                          makeProgramStream(qpdf, program, program.kind, true, chosenSubset));

    // Recompute /CIDSet when present so every bit matches all CIDs present in the embedded
    // subset program (ISO 19005-2 §6.2.11.4.2).
    if (descriptor.hasKey("/CIDSet")) {
        FT_Face subFace = nullptr;
        jpdfium::font::FtFaceGuard subGuard;
        std::set<uint32_t> presentCids;
        if (!chosenSubset.empty() &&
            FT_New_Memory_Face(lib, reinterpret_cast<const FT_Byte*>(chosenSubset.data()),
                               FT_Long(chosenSubset.size()), 0, &subFace) == 0 &&
            subFace) {
            subGuard.reset(subFace);
            presentCids = cidsPresentInProgram(target, subFace);
        }
        if (!presentCids.empty()) {
            updateCidSet(qpdf, descriptor, presentCids);
        } else {
            descriptor.removeKey("/CIDSet");
        }
    }
    return true;
}

bool subsetEmbeddedSimpleFont(QPDF& qpdf, FT_Library lib, QPDFObjectHandle& font,
                              QPDFObjectHandle& target, QPDFObjectHandle& descriptor,
                              FontProgram const& program, std::string const& baseName,
                              FontUsageIndexes const& usage) {
    auto scan = usedCidsForFont(qpdf, font, CidEncoding::SingleByte, &usage);
    if (!scan.complete || scan.cids.empty()) return false;
    if (!hasKnownSimpleEncoding(target)) return false;

    auto flags = descriptor.getKey("/Flags");
    bool symbolic = flags.isInteger() ? (flags.getIntValue() & 4) != 0
                                      : (baseName == "Symbol" || baseName == "ZapfDingbats");
    auto enc = parseSimpleEncoding(target, descriptor);

    // Always retain .notdef (GID 0) plus both symbolic and non-symbolic encoding lookups.
    std::set<FT_UInt> targetGlyphs{0};
    for (uint32_t code : scan.cids) {
        if (code >= 256) continue;
        targetGlyphs.insert(
            jpdfium::font::glyphForSimpleCode(program.face, static_cast<int>(code), false, enc));
        targetGlyphs.insert(
            jpdfium::font::glyphForSimpleCode(program.face, static_cast<int>(code), true, enc));
        if (code < static_cast<uint32_t>(program.face->num_glyphs)) {
            targetGlyphs.insert(static_cast<FT_UInt>(code));
        }
        if (!FT_Select_Charmap(program.face, FT_ENCODING_UNICODE)) {
            for (auto base :
                 {BaseEncoding::Standard, BaseEncoding::WinAnsi, BaseEncoding::MacRoman}) {
                targetGlyphs.insert(
                    FT_Get_Char_Index(program.face, baseEncodingUnicode(base, int(code))));
            }
        }
    }
    if (targetGlyphs.size() >= static_cast<size_t>(program.face->num_glyphs)) return false;

    // Symbolic simple fonts may index raw GIDs directly, so keep GID numbers intact.
    bool retainGids = symbolic;
    std::string subset;
    if (program.kind == ProgramKind::BareCff) {
        std::map<FT_UInt, uint32_t> gidToUni;
        for (auto const& [gid, u] : jpdfium::font::unicodeByGlyph(program.face, symbolic)) {
            gidToUni[gid] = static_cast<uint32_t>(u);
        }
        subset = jpdfium::font::subsetBareCffViaSfnt(program.face, program.bytes, targetGlyphs,
                                                     retainGids, /*keepGlyphNames=*/true, &gidToUni)
                     .bytes;
    } else {
        subset = jpdfium::font::subsetSfntProgram(
                     program.face, reinterpret_cast<const uint8_t*>(program.bytes.data()),
                     program.bytes.size(), 0, targetGlyphs, retainGids, /*keepGlyphNames=*/true)
                     .bytes;
    }
    if (subset.empty() || subset.size() + 64 >= program.bytes.size()) return false;
    if (!subsetKeepsSimpleGlyphs(lib, subset, program.face, symbolic, enc, scan.cids, retainGids)) {
        return false;
    }

    std::string tag = buildTag(baseName, targetGlyphs);
    auto tagged = QPDFObjectHandle::newName("/" + tag + "+" + baseName);
    target.replaceKey("/BaseFont", tagged);
    descriptor.replaceKey("/FontName", tagged);

    for (char const* key : {"/FontFile", "/FontFile2", "/FontFile3"}) descriptor.removeKey(key);
    descriptor.replaceKey(program.kind == ProgramKind::TrueType ? "/FontFile2" : "/FontFile3",
                          makeProgramStream(qpdf, program, program.kind, false, subset));

    // Refresh Type 1/CFF /CharSet from retained glyphs; remove it if any glyph lacks a name.
    if (program.kind != ProgramKind::TrueType) {
        std::string charsetStr;
        bool allNamed = FT_HAS_GLYPH_NAMES(program.face);
        char glyphName[256];
        for (FT_UInt gid : targetGlyphs) {
            if (gid == 0) continue;
            if (!allNamed ||
                FT_Get_Glyph_Name(program.face, gid, glyphName, sizeof(glyphName)) != 0 ||
                !glyphName[0]) {
                allNamed = false;
                break;
            }
            if (std::strcmp(glyphName, ".notdef") != 0) {
                charsetStr += '/';
                charsetStr += glyphName;
            }
        }
        if (allNamed && !charsetStr.empty()) {
            descriptor.replaceKey("/CharSet", QPDFObjectHandle::newString(charsetStr));
        } else {
            descriptor.removeKey("/CharSet");
        }
    }
    return true;
}

bool subsetEmbeddedFontResource(QPDF& qpdf, FT_Library lib, QPDFObjectHandle& font,
                                std::map<QPDFObjGen, int> const& descriptorUseCount,
                                std::map<QPDFObjGen, int> const& streamUseCount,
                                std::map<QPDFObjGen, int> const& descendantUseCount,
                                FontUsageIndexes const& usage, bool subsetTagged) {
    auto descendants = font.getKey("/DescendantFonts");
    bool cid = descendants.isArray() && descendants.getArrayNItems() > 0 &&
               descendants.getArrayItem(0).isDictionary();
    auto target = cid ? descendants.getArrayItem(0) : font;
    auto descriptor = target.getKey("/FontDescriptor");
    if (!descriptor.isDictionary()) return false;

    auto baseFont = target.getKey("/BaseFont");
    if (!baseFont.isName()) baseFont = font.getKey("/BaseFont");
    auto fontBaseFont = font.getKey("/BaseFont");
    std::string baseName =
        baseFont.isName() ? jpdfium::font::stripPdfNamePrefix(baseFont.getName()) : std::string();
    std::string parentName = fontBaseFont.isName()
                                 ? jpdfium::font::stripPdfNamePrefix(fontBaseFont.getName())
                                 : std::string();
    if (!subsetTagged) {
        if (jpdfium::font::hasSubsetTag(baseName) || jpdfium::font::hasSubsetTag(parentName)) {
            return false;
        }
    } else {
        baseName = jpdfium::font::stripSubsetTag(baseName);
    }
    if (descriptor.isIndirect()) {
        auto it = descriptorUseCount.find(descriptor.getObjGen());
        if (it != descriptorUseCount.end() && it->second > 1) return false;
    }

    auto stream = descriptor.getKey("/FontFile2");
    bool supportedProgram = stream.isStream();
    if (!supportedProgram) {
        auto ff3 = descriptor.getKey("/FontFile3");
        if (ff3.isStream()) {
            auto ff3Sub = ff3.getDict().getKey("/Subtype");
            if (ff3Sub.isNameAndEquals("/OpenType") || ff3Sub.isNameAndEquals("/Type1C") ||
                ff3Sub.isNameAndEquals("/CIDFontType0C")) {
                stream = std::move(ff3);
                supportedProgram = true;
            }
        }
    }
    if (!supportedProgram || !stream.isStream()) return false;
    if (stream.isIndirect()) {
        auto it = streamUseCount.find(stream.getObjGen());
        if (it != streamUseCount.end() && it->second > 1) return false;
    }

    std::shared_ptr<Buffer> data;
    try {
        data = stream.getStreamData(qpdf_dl_generalized);
    } catch (...) {
        return false;
    }
    if (!data || data->getSize() < 16) return false;

    FontProgram program;
    jpdfium::font::FtFaceGuard guard;
    if (!loadFontProgram(lib, {data->getBuffer(), data->getSize()}, program)) {
        if (program.face) FT_Done_Face(program.face);
        return false;
    }
    guard.reset(program.face);
    // Honour OS/2 fsType bits and skip trivial single-.notdef programs to keep valid font binaries.
    if (program.noSubsetting || program.face->num_glyphs <= 1) return false;
    if (program.kind != ProgramKind::TrueType && program.kind != ProgramKind::OpenType &&
        program.kind != ProgramKind::BareCff) {
        return false;
    }

    bool targetShared = false;
    if (cid && target.isIndirect()) {
        auto it = descendantUseCount.find(target.getObjGen());
        targetShared = it != descendantUseCount.end() && it->second > 1;
    }
    // CID fonts subset by CID-to-GID mapping while simple fonts retain 8-bit encoding slots.
    // Sharing tracks whether other resource dicts still depend on the unsubsetted font stream.
    return cid ? subsetEmbeddedCidFont(qpdf, lib, font, target, descriptor, program, baseName,
                                       usage, targetShared)
               : subsetEmbeddedSimpleFont(qpdf, lib, font, target, descriptor, program, baseName,
                                          usage);
}

void collectDocumentAndFormFonts(QPDF& qpdf, std::vector<QPDFObjectHandle>& fonts,
                                 std::vector<QPDFObjectHandle>& formFonts) {
    auto collectFromResources = [&](QPDFObjectHandle const& res) {
        auto fontDict = res.isDictionary() ? res.getKey("/Font") : QPDFObjectHandle();
        if (!fontDict.isDictionary()) return;
        for (auto const& [name, entry] : fontDict.getDictAsMap()) {
            (void)name;
            if (entry.isDictionary()) fonts.push_back(entry);
        }
    };
    for (auto& object : qpdf.getAllObjects()) {
        auto dict = object.isStream() ? object.getDict() : object;
        if (!dict.isDictionary()) continue;
        auto type = dict.getKey("/Type");
        auto subtypeName = dict.getKey("/Subtype");
        bool cidDescendant = subtypeName.isName() && (subtypeName.getName() == "/CIDFontType0" ||
                                                      subtypeName.getName() == "/CIDFontType2");
        if (type.isNameAndEquals("/Font") && !cidDescendant) fonts.push_back(dict);
        collectFromResources(dict.getKey("/Resources"));
    }

    // Walk /AcroForm /DR and nested Form XObjects so shared form resources are never subsetted.
    auto acroForm = qpdf.getRoot().getKey("/AcroForm");
    std::set<QPDFObjGen> visitedFormResources;
    std::set<QPDFObjGen> visitedFormStreams;
    std::vector<QPDFObjectHandle> pendingFormResources{
        acroForm.isDictionary() ? acroForm.getKey("/DR") : QPDFObjectHandle()};
    while (!pendingFormResources.empty()) {
        QPDFObjectHandle res = pendingFormResources.back();
        pendingFormResources.pop_back();
        if (!res.isDictionary()) continue;
        if (res.isIndirect() && !visitedFormResources.insert(res.getObjGen()).second) continue;
        auto fontDict = res.getKey("/Font");
        if (fontDict.isDictionary()) {
            for (auto const& [name, entry] : fontDict.getDictAsMap()) {
                (void)name;
                if (!entry.isDictionary()) continue;
                formFonts.push_back(entry);
                auto descendants = entry.getKey("/DescendantFonts");
                if (!descendants.isArray()) continue;
                for (auto& descendant : descendants.getArrayAsVector()) {
                    if (descendant.isDictionary()) formFonts.push_back(descendant);
                }
            }
        }
        auto xobjects = res.getKey("/XObject");
        if (!xobjects.isDictionary()) continue;
        for (auto const& [name, entry] : xobjects.getDictAsMap()) {
            (void)name;
            if (!entry.isStream()) continue;
            if (entry.isIndirect() && !visitedFormStreams.insert(entry.getObjGen()).second)
                continue;
            pendingFormResources.push_back(entry.getDict().getKey("/Resources"));
        }
    }
}

void tallyFontResourceSharing(std::vector<QPDFObjectHandle>& uniqueFonts,
                              std::vector<QPDFObjectHandle>& formFonts,
                              std::map<QPDFObjGen, int>& descriptorUseCount,
                              std::map<QPDFObjGen, int>& streamUseCount,
                              std::map<QPDFObjGen, int>& descendantUseCount) {
    for (auto& font : uniqueFonts) {
        auto descendants = font.getKey("/DescendantFonts");
        bool cid = descendants.isArray() && descendants.getArrayNItems() > 0 &&
                   descendants.getArrayItem(0).isDictionary();
        auto target = cid ? descendants.getArrayItem(0) : font;
        if (cid && target.isIndirect()) ++descendantUseCount[target.getObjGen()];
        auto descriptor = target.getKey("/FontDescriptor");
        if (descriptor.isIndirect()) ++descriptorUseCount[descriptor.getObjGen()];
        if (!descriptor.isDictionary()) continue;
        for (char const* key : {"/FontFile", "/FontFile2", "/FontFile3"}) {
            auto stream = descriptor.getKey(key);
            if (stream.isStream() && stream.isIndirect()) ++streamUseCount[stream.getObjGen()];
        }
    }

    // Mark /AcroForm /DR descriptors and streams as shared so form typing keeps all glyphs.
    for (auto& font : formFonts) {
        if (font.isIndirect()) descendantUseCount[font.getObjGen()] = 999999;
        auto descriptor = font.getKey("/FontDescriptor");
        if (!descriptor.isDictionary()) continue;
        if (descriptor.isIndirect()) descriptorUseCount[descriptor.getObjGen()] = 999999;
        for (char const* key : {"/FontFile", "/FontFile2", "/FontFile3"}) {
            auto stream = descriptor.getKey(key);
            if (stream.isStream() && stream.isIndirect())
                streamUseCount[stream.getObjGen()] = 999999;
        }
    }
}

}  // namespace

int subsetEmbeddedFonts(QPDF& qpdf, bool subsetTagged) {
    // Signed documents cannot have font streams or dictionaries rewritten without invalidating
    // signatures.
    if (pdfHasDigitalSignature(qpdf)) return 0;

    std::vector<QPDFObjectHandle> fonts;
    std::vector<QPDFObjectHandle> formFonts;
    collectDocumentAndFormFonts(qpdf, fonts, formFonts);

    std::set<QPDFObjGen> formFontObjects;
    for (auto const& font : formFonts) {
        if (font.isIndirect()) formFontObjects.insert(font.getObjGen());
    }
    std::set<QPDFObjGen> seenFonts;
    std::vector<QPDFObjectHandle> uniqueFonts;
    for (auto const& font : fonts) {
        if (font.isIndirect() && !seenFonts.insert(font.getObjGen()).second) continue;
        if (font.isIndirect() && formFontObjects.contains(font.getObjGen())) continue;
        uniqueFonts.push_back(font);
    }
    if (uniqueFonts.empty()) return 0;

    std::map<QPDFObjGen, int> descriptorUseCount;
    std::map<QPDFObjGen, int> streamUseCount;
    std::map<QPDFObjGen, int> descendantUseCount;
    tallyFontResourceSharing(uniqueFonts, formFonts, descriptorUseCount, streamUseCount,
                             descendantUseCount);

    jpdfium::font::FreeTypeSession ft;
    if (!ft.init()) return 0;

    // Single-pass scan records used CIDs/codes across all page, annotation, and Form XObject
    // streams.
    FontUsageIndexes usage = buildFontUsageIndexes(qpdf);
    int subsetCount = 0;
    for (auto& font : uniqueFonts) {
        try {
            if (subsetEmbeddedFontResource(qpdf, ft.lib, font, descriptorUseCount, streamUseCount,
                                           descendantUseCount, usage, subsetTagged)) {
                ++subsetCount;
            }
        } catch (...) {
            continue;
        }
    }
    return subsetCount;
}

#else

int subsetEmbeddedFonts(QPDF&, bool) {
    return 0;
}

#endif  // JPDFIUM_HAS_FREETYPE && JPDFIUM_HAS_HARFBUZZ

namespace {

struct FontInventoryEntry {
    QPDFObjectHandle font;
    QPDFObjGen objgen;
    std::string directKey;
    std::set<int> pages;
};

struct FontUsageCollector {
    std::map<QPDFObjGen, std::set<int>>& indirectUse;
    std::map<std::string, std::set<int>>& directUse;
    std::set<QPDFObjGen>& reachableIndirect;
    std::set<std::string>& reachableDirect;
    std::vector<QPDFObjectHandle>& directFonts;
    std::set<QPDFObjGen>& seenForms;

    void noteFont(QPDFObjectHandle const& font, int page) const {
        if (!font.isDictionary()) return;
        if (font.isIndirect()) {
            reachableIndirect.insert(font.getObjGen());
            if (page >= 0) indirectUse[font.getObjGen()].insert(page);
            return;
        }
        try {
            std::string key = font.unparse();
            reachableDirect.insert(key);
            directFonts.push_back(font);
            if (page >= 0) directUse[key].insert(page);
        } catch (...) {
            return;
        }
    }

    void collectDictFonts(QPDFObjectHandle const& resources, int page) const {
        try {
            auto fonts = resources.getKey("/Font");
            if (!fonts.isDictionary()) return;
            for (auto const& [name, entry] : fonts.getDictAsMap()) {
                (void)name;
                noteFont(entry, page);
            }
        } catch (...) {
            return;
        }
    }

    void collectExtGStateFonts(QPDFObjectHandle const& resources, int page, int depth) const {
        try {
            auto extgstates = resources.getKey("/ExtGState");
            if (!extgstates.isDictionary()) return;
            for (auto const& [name, entry] : extgstates.getDictAsMap()) {
                (void)name;
                QPDFObjectHandle gs = entry;
                if (!gs.isDictionary()) continue;
                auto gsFonts = gs.getKey("/Font");
                if (gsFonts.isArray()) {
                    for (auto const& font : gsFonts.getArrayAsVector()) noteFont(font, page);
                }
                auto smask = gs.getKey("/SMask");
                if (!smask.isDictionary()) continue;
                auto group = smask.getKey("/G");
                if (!group.isStream()) continue;
                if (group.isIndirect() && !seenForms.insert(group.getObjGen()).second) continue;
                collectFromResources(group.getDict().getKey("/Resources"), page, depth + 1);
            }
        } catch (...) {
            return;
        }
    }

    void collectNestedStreams(QPDFObjectHandle const& resources, char const* key, int page,
                              int depth) const {
        try {
            auto dict = resources.getKey(key);
            if (!dict.isDictionary()) return;
            for (auto const& [name, entry] : dict.getDictAsMap()) {
                (void)name;
                QPDFObjectHandle item = entry;
                if (!item.isStream()) continue;
                if (item.isIndirect() && !seenForms.insert(item.getObjGen()).second) continue;
                collectFromResources(item.getDict().getKey("/Resources"), page, depth + 1);
            }
        } catch (...) {
            return;
        }
    }

    void collectFromResources(QPDFObjectHandle const& resources, int page, int depth) const {
        if (!resources.isDictionary() || depth > 32) return;
        collectDictFonts(resources, page);
        collectExtGStateFonts(resources, page, depth);
        collectNestedStreams(resources, "/XObject", page, depth);
        collectNestedStreams(resources, "/Pattern", page, depth);
    }

    void collectAppearance(QPDFObjectHandle const& appearance, int page, int depth) const {
        if (depth > 8) return;
        if (appearance.isStream()) {
            seenForms.clear();
            collectFromResources(appearance.getDict().getKey("/Resources"), page, 0);
        } else if (appearance.isDictionary()) {
            for (auto const& [state, entry] : appearance.getDictAsMap()) {
                (void)state;
                collectAppearance(entry, page, depth + 1);
            }
        }
    }
};

std::vector<FontInventoryEntry> collectReachableFontEntries(QPDF& qpdf) {
    std::map<QPDFObjGen, std::set<int>> indirectUse;
    std::map<std::string, std::set<int>> directUse;
    std::set<QPDFObjGen> reachableIndirect;
    std::set<std::string> reachableDirect;
    std::vector<QPDFObjectHandle> directFonts;
    std::set<QPDFObjGen> seenForms;
    FontUsageCollector collector{indirectUse,     directUse,   reachableIndirect,
                                 reachableDirect, directFonts, seenForms};

    std::vector<QPDFPageObjectHelper> pages;
    try {
        pages = QPDFPageDocumentHelper{qpdf}.getAllPages();
    } catch (...) {
        pages.clear();
    }
    for (size_t i = 0; i < pages.size(); ++i) {
        auto pageObj = pages[i].getObjectHandle();
        // Look up inherited page /Resources (ISO 32000-1 §7.7.3.4) before falling back to local.
        QPDFObjectHandle resources;
        try {
            resources = pages[i].getAttribute("/Resources", false);
        } catch (...) {
            resources = pageObj.getKey("/Resources");
        }
        seenForms.clear();
        collector.collectFromResources(resources, static_cast<int>(i), 0);
        // Annotation /AP appearance streams may embed form XObjects referencing extra fonts.
        try {
            auto annots = pageObj.getKey("/Annots");
            if (!annots.isArray()) continue;
            for (auto& annot : annots.getArrayAsVector()) {
                if (!annot.isDictionary()) continue;
                auto ap = annot.getKey("/AP");
                if (!ap.isDictionary()) continue;
                for (auto const& [name, entry] : ap.getDictAsMap()) {
                    (void)name;
                    collector.collectAppearance(entry, static_cast<int>(i), 0);
                }
            }
        } catch (...) {
            continue;
        }
    }

    // Default Resources inside AcroForm define fallback fonts for dynamically generated widgets.
    try {
        auto acroForm = qpdf.getRoot().getKey("/AcroForm");
        if (acroForm.isDictionary()) {
            seenForms.clear();
            collector.collectFromResources(acroForm.getKey("/DR"), -1, 0);
        }
    } catch (...) {
        seenForms.clear();
    }

    // Type3 /CharProcs can host nested /Resources referencing further fonts.
    std::set<QPDFObjGen> scannedType3Indirect;
    std::set<std::string> scannedType3Direct;
    for (;;) {
        size_t before = reachableIndirect.size() + reachableDirect.size();
        for (auto& object : qpdf.getAllObjects()) {
            if (!object.isIndirect() || !reachableIndirect.contains(object.getObjGen())) continue;
            auto subtype = object.getKey("/Subtype");
            if (!subtype.isNameAndEquals("/Type3")) continue;
            if (!scannedType3Indirect.insert(object.getObjGen()).second) continue;
            seenForms.clear();
            auto const& p = indirectUse[object.getObjGen()];
            if (p.empty()) {
                collector.collectFromResources(object.getKey("/Resources"), -1, 0);
            } else {
                for (int page : p)
                    collector.collectFromResources(object.getKey("/Resources"), page, 0);
            }
        }
        for (size_t directIndex = 0; directIndex < directFonts.size(); ++directIndex) {
            QPDFObjectHandle directFont = directFonts[directIndex];
            std::string key = directFont.unparse();
            if (!reachableDirect.contains(key)) continue;
            auto subtype = directFont.getKey("/Subtype");
            if (!subtype.isNameAndEquals("/Type3")) continue;
            if (!scannedType3Direct.insert(key).second) continue;
            seenForms.clear();
            auto const& p = directUse[key];
            if (p.empty()) {
                collector.collectFromResources(directFont.getKey("/Resources"), -1, 0);
            } else {
                for (int page : p) {
                    collector.collectFromResources(directFont.getKey("/Resources"), page, 0);
                }
            }
        }
        if (reachableIndirect.size() + reachableDirect.size() == before) break;
    }

    std::vector<FontInventoryEntry> fonts;
    std::set<QPDFObjGen> seenFonts;
    std::set<std::string> seenDirect;
    auto eligible = [](QPDFObjectHandle const& object) {
        auto dict = object.isStream() ? object.getDict() : object;
        if (!dict.isDictionary()) return false;
        auto type = dict.getKey("/Type");
        auto subtype = dict.getKey("/Subtype");
        if (type.isName() && type.getName() != "/Font") return false;
        return !subtype.isName() ||
               (subtype.getName() != "/CIDFontType0" && subtype.getName() != "/CIDFontType2");
    };
    for (auto& object : qpdf.getAllObjects()) {
        if (!object.isIndirect() || !eligible(object)) continue;
        auto objgen = object.getObjGen();
        if (!reachableIndirect.contains(objgen) || !seenFonts.insert(objgen).second) continue;
        FontInventoryEntry entry{object, objgen, {}, {}};
        if (auto it = indirectUse.find(objgen); it != indirectUse.end()) entry.pages = it->second;
        fonts.push_back(std::move(entry));
    }
    for (auto const& object : directFonts) {
        if (!eligible(object)) continue;
        std::string key;
        try {
            key = object.unparse();
        } catch (...) {
            QPDFObjectHandle copy = object;
            key = copy.getKey("/BaseFont").isName() ? copy.getKey("/BaseFont").getName()
                                                    : "direct-font";
        }
        if (!seenDirect.insert(key).second) continue;
        FontInventoryEntry entry{object, {}, key, {}};
        if (auto it = directUse.find(key); it != directUse.end()) entry.pages = it->second;
        fonts.push_back(std::move(entry));
    }
    return fonts;
}

// Serializes a discovered font entry into the JSON array returned to Java auditors.
void appendFontInventoryEntryJson(FontInventoryEntry const& entry, std::string& json) {
    QPDFObjectHandle font = entry.font;
    auto base = font.getKey("/BaseFont");
    auto subtype = font.getKey("/Subtype");
    auto encoding = font.getKey("/Encoding");
    auto descriptor = font.getKey("/FontDescriptor");
    auto descendants = font.getKey("/DescendantFonts");
    bool cid = descendants.isArray() && descendants.getArrayNItems() > 0 &&
               descendants.getArrayItem(0).isDictionary();
    auto target = cid ? descendants.getArrayItem(0) : font;
    auto targetDescriptor = target.getKey("/FontDescriptor");

    auto tryReadStream = [](QPDFObjectHandle stream) -> std::shared_ptr<Buffer> {
        if (!stream.isStream()) return nullptr;
        try {
            return stream.getStreamData(qpdf_dl_generalized);
        } catch (...) {
            return nullptr;
        }
    };
    auto validProgram = [](std::shared_ptr<Buffer> const& data) {
        if (!data || data->getSize() < 4) return false;
        auto const* b = reinterpret_cast<uint8_t const*>(data->getBuffer());
        if (b[0] == 0x00 && b[1] == 0x01 && b[2] == 0x00 && b[3] == 0x00) return true;
        if (b[0] == 0x01 && b[1] == 0x00 && b[2] == 0x04 && b[3] == 0x04) return true;
        if ((b[0] == 'O' && b[1] == 'T' && b[2] == 'T' && b[3] == 'O') ||
            (b[0] == 't' && b[1] == 'r' && b[2] == 'u' && b[3] == 'e') ||
            (b[0] == 't' && b[1] == 't' && b[2] == 'c' && b[3] == 'f'))
            return true;
        if (b[0] == '%' && b[1] == '!') return true;
        return (b[0] & 0xFF) == 0x80 && b[1] == 0x01;
    };
    auto programState = [&](QPDFObjectHandle const& desc) {
        bool present = false;
        bool valid = false;
        if (!desc.isDictionary()) return std::pair<bool, bool>{false, false};
        for (char const* key : {"/FontFile", "/FontFile2", "/FontFile3"}) {
            auto data = tryReadStream(desc.getKey(key));
            if (!data || data->getSize() == 0) continue;
            present = true;
            if (validProgram(data)) valid = true;
        }
        return std::pair<bool, bool>{present, valid};
    };
    // Inspect both wrapper and descendant descriptors for embedded /FontFile* and /ToUnicode
    // streams.
    auto wrapperProgram = programState(descriptor);
    auto descendantProgram = programState(targetDescriptor);
    bool hasProgram = wrapperProgram.first || descendantProgram.first;
    bool programValid = wrapperProgram.second || descendantProgram.second;
    auto toUnicodeData = tryReadStream(font.getKey("/ToUnicode"));
    bool hasToUnicode = toUnicodeData && toUnicodeData->getSize() > 0;

    std::string encodingName;
    if (encoding.isName()) {
        encodingName = encoding.getName();
    } else if (encoding.isDictionary()) {
        auto baseEncoding = encoding.getKey("/BaseEncoding");
        encodingName = baseEncoding.isName() ? baseEncoding.getName() : "custom";
    }
    std::string cidToGid;
    auto gidMap = target.getKey("/CIDToGIDMap");
    if (gidMap.isName()) {
        cidToGid = gidMap.getName();
    } else if (!gidMap.isNull()) {
        cidToGid = "stream";
    }

    // Emit flat JSON keys matching Java's PdfFontInfo parser order without external JSON overhead.
    json += "{\"object\":\"";
    if (entry.objgen.getObj() != 0) {
        json += std::to_string(entry.objgen.getObj()) + " " + std::to_string(entry.objgen.getGen());
    }
    json += "\",\"baseFont\":\"";
    json += jpdfium::font::jsonEscape(jpdfium::font::latin1ToUtf8(
        base.isName() ? jpdfium::font::stripPdfNamePrefix(base.getName()) : ""));
    json += "\",\"subtype\":\"";
    json += jpdfium::font::jsonEscape(
        subtype.isName() ? jpdfium::font::stripPdfNamePrefix(subtype.getName()) : "");
    json += "\",\"encoding\":\"";
    json += jpdfium::font::jsonEscape(
        jpdfium::font::latin1ToUtf8(encodingName.empty() || encodingName.front() != '/'
                                        ? encodingName
                                        : jpdfium::font::stripPdfNamePrefix(encodingName)));
    json += "\",\"descriptor\":";
    json += boolJson(descriptor.isDictionary() || targetDescriptor.isDictionary());
    json += ",\"program\":";
    json += boolJson(hasProgram);
    json += ",\"programValid\":";
    json += boolJson(programValid);
    json += ",\"cid\":";
    json += boolJson(cid);
    json += ",\"toUnicode\":";
    json += boolJson(hasToUnicode);
    json += ",\"cidToGid\":\"";
    json += jpdfium::font::jsonEscape(cidToGid.empty() || cidToGid.front() != '/'
                                          ? cidToGid
                                          : jpdfium::font::stripPdfNamePrefix(cidToGid));
    json += "\",\"pages\":\"";
    bool firstPage = true;
    for (auto it = entry.pages.begin(); it != entry.pages.end();) {
        int start = *it;
        int end = start;
        auto next = std::next(it);
        while (next != entry.pages.end() && *next == end + 1) {
            end = *next;
            ++next;
        }
        if (!firstPage) json += ',';
        firstPage = false;
        json += std::to_string(start);
        if (end != start) json += "-" + std::to_string(end);
        it = next;
    }
    json += "\"}";
}

}  // namespace

std::string inspectFonts(QPDF& qpdf) {
    // Collect reachable page, annotation, Form XObject, AcroForm, and Type3 fonts.
    std::vector<FontInventoryEntry> fonts = collectReachableFontEntries(qpdf);
    std::string json;
    json.reserve(fonts.size() * 160 + 2);
    json += '[';
    for (size_t i = 0; i < fonts.size(); ++i) {
        if (i > 0) json += ',';
        appendFontInventoryEntryJson(fonts[i], json);
    }
    json += ']';
    return json;
}

}  // namespace jpdfium::qpdf_detail

extern "C" {

using jpdfium::qpdf_detail::inspectFonts;
using jpdfium::qpdf_detail::subsetEmbeddedFonts;

JPDFIUM_EXPORT int32_t jpdfium_qpdf_inspect_fonts(const uint8_t* input, int64_t inputLen,
                                                  char** json) {
    if (!input || inputLen <= 0 || !json) return -1;
    *json = nullptr;
    try {
        auto qpdf = QPDF::create();
        qpdf->processMemoryFile("font-in", reinterpret_cast<const char*>(input),
                                static_cast<size_t>(inputLen));
        auto result = inspectFonts(*qpdf);
        *json = strdup(result.c_str());
        return *json ? 0 : -1;
    } catch (...) {
        return -1;
    }
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_inspect_fonts_file(const char* in_path, char** json) {
    if (!in_path || !*in_path || !json) return -1;
    *json = nullptr;
    try {
        QPDF qpdf;
        qpdf.processFile(in_path);
        auto result = inspectFonts(qpdf);
        *json = strdup(result.c_str());
        return *json ? 0 : -1;
    } catch (...) {
        return -1;
    }
}

namespace {

// Substring presence for ASCII markers inside decoded XMP bytes.
bool xmpContains(std::string_view hay, char const* needle) {
    return std::search(hay.begin(), hay.end(), needle, needle + std::strlen(needle)) != hay.end();
}

}  // namespace

JPDFIUM_EXPORT int32_t jpdfium_qpdf_pdfa_part(const uint8_t* input, int64_t inputLen,
                                              int32_t* outPart) {
    if (!input || inputLen <= 0 || !outPart) return -1;
    *outPart = -1;
    try {
        auto qpdf = QPDF::create();
        qpdf->processMemoryFile("pdfa-part-in", reinterpret_cast<const char*>(input),
                                static_cast<size_t>(inputLen));
        auto metadata = qpdf->getRoot().getKey("/Metadata");
        if (!metadata.isStream()) return 0;
        auto data = metadata.getStreamData(qpdf_dl_generalized);
        if (!data || !data->getSize()) return 0;
        std::string_view xmp(reinterpret_cast<char const*>(data->getBuffer()), data->getSize());
        // Claim markers mirror the JVM-side PDFA_MARKERS: an actual
        // pdfaid:part/conformance claim, not the generic XMP schema
        // description every Adobe XMP block embeds.
        bool claim = xmpContains(xmp, "pdfaid:part>") || xmpContains(xmp, "pdfaid:part=\"") ||
                     xmpContains(xmp, "pdfaid:conformance>") ||
                     xmpContains(xmp, "pdfaid:conformance=\"") || xmpContains(xmp, "GTS_PDFA1");
        if (!claim) return 0;
        bool part1 = xmpContains(xmp, "pdfaid:part>1<") || xmpContains(xmp, "pdfaid:part=\"1\"") ||
                     xmpContains(xmp, "GTS_PDFA1");
        *outPart = part1 ? 1 : 2;
        return 0;
    } catch (...) {
        // Unparseable input: the claim is unknown, not absent.
        return -1;
    }
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_subset_fonts(const uint8_t* input, int64_t inputLen,
                                                 int32_t subsetTagged, uint8_t** output,
                                                 int64_t* outputLen, int32_t* fontsSubset) {
    if (!input || inputLen <= 0 || !output || !outputLen || !fontsSubset) return -1;
    *output = nullptr;
    *outputLen = 0;
    *fontsSubset = 0;
    try {
        auto qpdf = QPDF::create();
        qpdf->processMemoryFile("subset-in", reinterpret_cast<const char*>(input),
                                static_cast<size_t>(inputLen));
        int count = subsetEmbeddedFonts(*qpdf, subsetTagged != 0);
        *fontsSubset = count;
        if (count <= 0) return count == 0 ? 1 : -1;

        QPDFWriter writer{*qpdf};
        writer.setCompressStreams(true);
        writer.setDecodeLevel(qpdf_dl_none);
        writer.setNewlineBeforeEndstream(true);
        writer.setOutputMemory();
        jpdfium_qpdf_internal::WriterLock lock{9};
        writer.write();
        auto buf = writer.getBufferSharedPointer();
        if (!buf || buf->getSize() == 0) return -1;
        *outputLen = static_cast<int64_t>(buf->getSize());
        *output = static_cast<uint8_t*>(malloc(static_cast<size_t>(*outputLen)));
        if (!*output) {
            *outputLen = 0;
            return -1;
        }
        std::memcpy(*output, buf->getBuffer(), static_cast<size_t>(*outputLen));
        return 0;
    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

}  // extern "C"

#endif  // JPDFIUM_HAS_QPDF
