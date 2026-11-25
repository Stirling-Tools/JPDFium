// jpdfium_rewriter_fonts_cid.cpp - Composite Type0 / CIDFont rewriting stages
// (missing font embedding, CID-0/GID-0/.notdef repair, and /W width reconciliation).

#include "jpdfium_font_rewriter_internal.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>
#include <sstream>

namespace jpdfium::rewriter {

void rewriteType0Font(FontRewriteContext& ctx, QPDFObjectHandle& f, const std::string& bf) {
    QPDF& qpdf = ctx.qpdf;
    FT_Library ftLib = ctx.ftLib;
    uint32_t flags = ctx.flags;
    const char* donorDirs = ctx.donorDirs;
    auto& usedFontCodes = ctx.usedFontCodes;
    auto& codeRemaps = ctx.codeRemaps;
    int& embeddedCount = ctx.embeddedCount;
    int& widthsFixedCount = ctx.widthsFixedCount;
    int& cmapFixedCount = ctx.cmapFixedCount;
    int& notdefFixedCount = ctx.notdefFixedCount;
    int& metaFixedCount = ctx.metaFixedCount;
    auto getDonorStream = [&](const std::string& key, bool isCff) -> QPDFObjectHandle {
        return ctx.getDonorStream(key, isCff);
    };
    (void)cmapFixedCount;
    (void)notdefFixedCount;
    (void)codeRemaps;

    QPDFObjectHandle df = f.getKey("/DescendantFonts");
    if (!df.isArray() || df.getArrayNItems() < 1 || !df.getArrayItem(0).isDictionary()) return;
    QPDFObjectHandle d = df.getArrayItem(0);
    std::string dsub = d.getKey("/Subtype").isName() ? d.getKey("/Subtype").getName() : "";
    QPDFObjectHandle fd = d.getKey("/FontDescriptor");
    bool isEmb = (flags & kFlagEmbedMissing)
                     ? validateOrStripCorruptFontStreams(fd)
                     : (fd.isDictionary() &&
                        (fd.hasKey("/FontFile") || fd.hasKey("/FontFile2") || fd.hasKey("/FontFile3")));

    if ((flags & kFlagCleanMetadata) && fd.isDictionary()) {
        if (!(flags & kFlagConvertPdfA1b) && fd.hasKey("/CIDSet")) { fd.removeKey("/CIDSet"); ++metaFixedCount; }
        if (dsub == "/CIDFontType2") {
            QPDFObjectHandle ctg = d.getKey("/CIDToGIDMap");
            if (!(ctg.isStream() || (ctg.isName() && ctg.getName() == "/Identity"))) {
                d.replaceKey("/CIDToGIDMap", QPDFObjectHandle::newName("/Identity"));
                ++metaFixedCount;
            }
        }
        QPDFObjectHandle enc = f.getKey("/Encoding");
        if (enc.isStream()) {
            QPDFObjectHandle encDict = enc.getDict();
            if (encDict.hasKey("/CIDSystemInfo") && encDict.getKey("/CIDSystemInfo").isDictionary()) {
                d.replaceKey("/CIDSystemInfo", encDict.getKey("/CIDSystemInfo").shallowCopy());
                ++metaFixedCount;
            }
            // Sync CMap stream dictionary /WMode with embedded CMap body "/WMode <0|1> def" (fixes 6.2.11.3.3-2)
            try {
                auto cbuf = enc.getStreamData();
                if (cbuf && cbuf->getSize() > 0 && cbuf->getSize() <= 2U * 1024U * 1024U) {
                    std::string_view sv(reinterpret_cast<const char*>(cbuf->getBuffer()), cbuf->getSize());
                    size_t wp = sv.find("/WMode");
                    if (wp != std::string_view::npos) {
                        size_t p = wp + 6;
                        while (p < sv.size() && (sv[p] == ' ' || sv[p] == '\t' || sv[p] == '\r' || sv[p] == '\n')) ++p;
                        if (p < sv.size() && (sv[p] == '0' || sv[p] == '1')) {
                            int bodyWMode = sv[p] - '0';
                            int dictWMode = (encDict.hasKey("/WMode") && encDict.getKey("/WMode").isInteger())
                                                ? static_cast<int>(encDict.getKey("/WMode").getIntValue())
                                                : 0;
                            if (dictWMode != bodyWMode) {
                                encDict.replaceKey("/WMode", QPDFObjectHandle::newInteger(bodyWMode));
                                ++metaFixedCount;
                            }
                        }
                    }
                }
            } catch (...) {
            }
        } else if (enc.isName()) {
            std::string ename = enc.getName();
            if (ename != "/Identity-H" && ename != "/Identity-V") {
                QPDFObjectHandle csi = d.getKey("/CIDSystemInfo");
                if (csi.isDictionary() && csi.hasKey("/Supplement") && csi.getKey("/Supplement").isInteger() &&
                    csi.getKey("/Supplement").getIntValue() > 0) {
                    QPDFObjectHandle newCsi = csi.shallowCopy();
                    newCsi.replaceKey("/Supplement", QPDFObjectHandle::newInteger(0));
                    d.replaceKey("/CIDSystemInfo", newCsi);
                    ++metaFixedCount;
                }
            }
        }
    }

    // Stage 3: Embed missing CIDFont (both CIDFontType0 and CIDFontType2 -> CIDFontType2 + styled/Unicode donor)
    if (!isEmb && (flags & kFlagEmbedMissing)) {
        if (dsub == "/CIDFontType0" || dsub == "/CIDFontType2") {
            std::string encName = f.getKey("/Encoding").isName() ? f.getKey("/Encoding").getName() : "";
            bool isUcs2CMap = (encName.find("UCS2") != std::string::npos ||
                               encName.find("UTF16") != std::string::npos);
            const std::set<uint32_t>* uUsedPre = nullptr;
            if (f.isIndirect()) {
                auto uit = usedFontCodes.find(f.getObjGen());
                if (uit != usedFontCodes.end()) uUsedPre = &uit->second;
            }
            std::string donorKey = "arial_uni";
            if (isUcs2CMap && uUsedPre && !uUsedPre->empty()) {
                bool anyCjk = false;
                for (uint32_t u : *uUsedPre) {
                    if (u >= 0x2E80U) { anyCjk = true; break; }
                }
                if (!anyCjk) {
                    std::string lbf = toLowerAscii(bf);
                    bool isSerif = (lbf.find("mincho") != std::string::npos ||
                                    lbf.find("kozmin") != std::string::npos ||
                                    lbf.find("song") != std::string::npos ||
                                    lbf.find("times") != std::string::npos ||
                                    lbf.find("serif") != std::string::npos);
                    bool isBold = (lbf.find("bold") != std::string::npos || lbf.find("heavy") != std::string::npos);
                    bool isItal = (lbf.find("italic") != std::string::npos || lbf.find("oblique") != std::string::npos);
                    std::string suf = (isBold && isItal) ? "_bi" : (isBold ? "_bold" : (isItal ? "_italic" : ""));
                    std::string candKey = (isSerif ? "times" : "arial") + suf;
                    if (getCachedDonor(ftLib, donorDirs, candKey)) {
                        donorKey = candKey;
                    }
                }
            }
            const DonorFont* donor = getCachedDonor(ftLib, donorDirs, donorKey);
            QPDFObjectHandle st = getDonorStream(donorKey, false);
            if (!donor || st.isNull()) {
                donor = getCachedDonor(ftLib, donorDirs, "arial_uni");
                st = getDonorStream("arial_uni", false);
            }
            if (donor && !st.isNull()) {
                if (dsub == "/CIDFontType0") {
                    d.replaceKey("/Subtype", QPDFObjectHandle::newName("/CIDFontType2"));
                    dsub = "/CIDFontType2";
                }
                if (!fd.isDictionary()) {
                    fd = qpdf.makeIndirectObject(QPDFObjectHandle::newDictionary());
                    fd.replaceKey("/Type", QPDFObjectHandle::newName("/FontDescriptor"));
                    fd.replaceKey("/FontName", QPDFObjectHandle::newName(bf.empty() ? "/ArialUnicodeMS" : bf));
                    fd.replaceKey("/Flags", QPDFObjectHandle::newInteger(4));
                    QPDFObjectHandle bb = QPDFObjectHandle::newArray();
                    for (int b : donor->bbox) bb.appendItem(QPDFObjectHandle::newInteger(b));
                    fd.replaceKey("/FontBBox", bb);
                    fd.replaceKey("/ItalicAngle", QPDFObjectHandle::newInteger(0));
                    fd.replaceKey("/Ascent", QPDFObjectHandle::newInteger(donor->ascent));
                    fd.replaceKey("/Descent", QPDFObjectHandle::newInteger(donor->descent));
                    fd.replaceKey("/CapHeight", QPDFObjectHandle::newInteger(donor->capHeight));
                    fd.replaceKey("/StemV", QPDFObjectHandle::newInteger(80));
                    d.replaceKey("/FontDescriptor", fd);
                }
                if (fd.hasKey("/FontFile3")) fd.removeKey("/FontFile3");
                fd.replaceKey("/FontFile2", st);
                // If Type0 uses a UCS2/UTF16 predefined CMap (e.g. UniCNS-UCS2-H, UniJIS-UCS2-H),
                // switch to /Identity-H and build a Unicode->GID /CIDToGIDMap from the chosen donor!
                if (isUcs2CMap) {
                    std::vector<uint8_t> dProg = donor->raw;
                    uint16_t blankGid = sfntAppendBlankGlyph(dProg, 0);
                    FT_Face uFace = nullptr;
                    if (blankGid > 0 && FT_New_Memory_Face(ftLib, dProg.data(), static_cast<FT_Long>(dProg.size()), 0, &uFace) == 0 && uFace) {
                        FT_Select_Charmap(uFace, FT_ENCODING_UNICODE);
                        int uUpem = uFace->units_per_EM > 0 ? uFace->units_per_EM : 1000;
                        size_t mapSz = 65535;
                        std::vector<uint8_t> mapBytes(mapSz * 2, 0);
                        std::map<int, int> uWmap;
                        const std::set<uint32_t>* uUsed = nullptr;
                        if (f.isIndirect()) {
                            auto uit = usedFontCodes.find(f.getObjGen());
                            if (uit != usedFontCodes.end()) uUsed = &uit->second;
                        }
                        // Map original collection-CID /W widths to Unicode via the Adobe CJK collection
                        // table so hmtx patching preserves exact original character advance positions!
                        std::string ordering;
                        QPDFObjectHandle origCsi = d.getKey("/CIDSystemInfo");
                        if (origCsi.isDictionary() && origCsi.hasKey("/Ordering") &&
                            origCsi.getKey("/Ordering").isString()) {
                            ordering = origCsi.getKey("/Ordering").getUTF8Value();
                        } else if (encName.find("JIS") != std::string::npos) {
                            ordering = "Japan1";
                        } else if (encName.find("GB") != std::string::npos) {
                            ordering = "GB1";
                        } else if (encName.find("CNS") != std::string::npos) {
                            ordering = "CNS1";
                        } else if (encName.find("KS") != std::string::npos) {
                            ordering = "Korea1";
                        }
                        auto origWmap = d.hasKey("/W") ? parseWArray(d.getKey("/W")) : std::map<int, int>{};
                        std::map<uint32_t, int> origWidthByUni;
                        if (!ordering.empty()) {
                            for (const auto& [cid, w] : origWmap) {
                                if (cid < 0 || w <= 0 || w > 10000) continue;
                                uint32_t uni = 0;
                                if (cid >= 1 && cid <= 95) {
                                    // Adobe-Japan1/GB1/CNS1/Korea1 all map CID 1..95 -> ASCII U+0020..U+007E.
                                    uni = static_cast<uint32_t>(cid + 31);
                                } else {
                                    jpdfium::qpdf_detail::adobeCollectionUnicodeForCid(
                                        ordering, static_cast<uint32_t>(cid), uni);
                                }
                                if (uni > 0 && uni < mapSz) {
                                    // Prefer lowest CID (proportional Latin 1..95 over half-width 231..325).
                                    origWidthByUni.emplace(uni, w);
                                }
                            }
                        }
                        std::map<uint16_t, uint16_t> cjkHmtxPatch;
                        for (uint32_t u = 1; u < mapSz; ++u) {
                            FT_UInt g = FT_Get_Char_Index(uFace, u);
                            uint16_t mg = (g > 0 && g < 65535) ? static_cast<uint16_t>(g) : blankGid;
                            writeU16(mapBytes, u * 2, mg);
                            if (!uUsed || uUsed->count(u)) {
                                auto owit = origWidthByUni.find(u);
                                if (owit != origWidthByUni.end() && mg != blankGid) {
                                    uWmap[static_cast<int>(u)] = owit->second;
                                    long long advU = std::llround(static_cast<double>(owit->second) * uUpem / 1000.0);
                                    if (advU >= 0 && advU <= 65535) {
                                        cjkHmtxPatch[mg] = static_cast<uint16_t>(advU);
                                    }
                                } else {
                                    int pw = ftGlyphWidth1000(uFace, mg, uUpem, false);
                                    if (pw >= 0) uWmap[static_cast<int>(u)] = pw;
                                }
                            }
                        }
                        FT_Done_Face(uFace);
                        if (!cjkHmtxPatch.empty()) {
                            sfntPatchHmtx(dProg, cjkHmtxPatch);
                        }
                        std::string newP(reinterpret_cast<const char*>(dProg.data()), dProg.size());
                        QPDFObjectHandle newFf = QPDFObjectHandle::newStream(&qpdf, newP);
                        newFf.getDict().replaceKey("/Length1", QPDFObjectHandle::newInteger(static_cast<long long>(dProg.size())));
                        fd.replaceKey("/FontFile2", newFf);
                        std::string mapStr(reinterpret_cast<const char*>(mapBytes.data()), mapBytes.size());
                        d.replaceKey("/CIDToGIDMap", QPDFObjectHandle::newStream(&qpdf, mapStr));
                        f.replaceKey("/Encoding", QPDFObjectHandle::newName("/Identity-H"));
                        if (!f.hasKey("/ToUnicode")) {
                            // Spec-compliant 2-byte Identity ToUnicode CMap (ISO 32000-1 §9.10.3:
                            // each bfrange entry may only increment the last byte, <= 100 entries/block).
                            std::ostringstream tss;
                            tss << "/CIDInit /ProcSet findresource begin\n"
                                << "12 dict begin\n"
                                << "begincmap\n"
                                << "/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n"
                                << "/CMapName /Adobe-Identity-UCS def\n"
                                << "/CMapType 2 def\n"
                                << "1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n";
                            for (int blk = 0; blk < 256; blk += 100) {
                                int cnt = std::min(100, 256 - blk);
                                tss << cnt << " beginbfrange\n";
                                for (int hi = blk; hi < blk + cnt; ++hi) {
                                    char lineBuf[32];
                                    std::snprintf(lineBuf, sizeof(lineBuf), "<%02X00> <%02XFF> <%02X00>\n", hi, hi, hi);
                                    tss << lineBuf;
                                }
                                tss << "endbfrange\n";
                            }
                            tss << "endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n";
                            f.replaceKey("/ToUnicode", QPDFObjectHandle::newStream(&qpdf, tss.str()));
                        }
                        QPDFObjectHandle csi = QPDFObjectHandle::newDictionary();
                        csi.replaceKey("/Registry", QPDFObjectHandle::newString("Adobe"));
                        csi.replaceKey("/Ordering", QPDFObjectHandle::newString("Identity"));
                        csi.replaceKey("/Supplement", QPDFObjectHandle::newInteger(0));
                        d.replaceKey("/CIDSystemInfo", csi);
                        d.replaceKey("/DW", QPDFObjectHandle::newInteger(0));
                        d.replaceKey("/W", serializeWMap(uWmap));
                    }
                } else {
                    d.replaceKey("/CIDToGIDMap", QPDFObjectHandle::newName("/Identity"));
                    // Preserve existing /W and /DW if already present (Stage 4 sfntPatchHmtx will
                    // reconcile hmtx to match them, preserving exact layout & text spacing!).
                    if (!d.hasKey("/W")) {
                        d.replaceKey("/DW", QPDFObjectHandle::newInteger(0));
                        QPDFObjectHandle wArr = QPDFObjectHandle::newArray();
                        size_t ngD = donor->gidWidths.size();
                        size_t gi = 0;
                        while (gi < ngD) {
                            int wv = donor->gidWidths[gi];
                            size_t gj = gi + 1;
                            while (gj < ngD && donor->gidWidths[gj] == wv) ++gj;
                            if (gj - gi >= 3) {
                                wArr.appendItem(QPDFObjectHandle::newInteger(static_cast<long long>(gi)));
                                wArr.appendItem(QPDFObjectHandle::newInteger(static_cast<long long>(gj - 1)));
                                wArr.appendItem(QPDFObjectHandle::newInteger(wv));
                            } else {
                                wArr.appendItem(QPDFObjectHandle::newInteger(static_cast<long long>(gi)));
                                QPDFObjectHandle subW = QPDFObjectHandle::newArray();
                                for (size_t gk = gi; gk < gj; ++gk) {
                                    subW.appendItem(QPDFObjectHandle::newInteger(donor->gidWidths[gk]));
                                }
                                wArr.appendItem(subW);
                            }
                            gi = gj;
                        }
                        d.replaceKey("/W", wArr);
                    }
                    if (!d.hasKey("/CIDSystemInfo")) {
                        QPDFObjectHandle csi = QPDFObjectHandle::newDictionary();
                        csi.replaceKey("/Registry", QPDFObjectHandle::newString("Adobe"));
                        csi.replaceKey("/Ordering", QPDFObjectHandle::newString("Identity"));
                        csi.replaceKey("/Supplement", QPDFObjectHandle::newInteger(0));
                        d.replaceKey("/CIDSystemInfo", csi);
                    }
                }
                ++embeddedCount;
                isEmb = true;
            }
        }
    }

    // Stage 4 & 5: Reconcile CIDFont widths & repair GID-0 / CID-0 / OOB mappings
    if (isEmb && fd.isDictionary() && ((flags & kFlagReconcileWidths) || (flags & kFlagRepairNotdef))) {
        QPDFObjectHandle ff = fd.hasKey("/FontFile2") ? fd.getKey("/FontFile2")
                            : (fd.hasKey("/FontFile3") ? fd.getKey("/FontFile3") : QPDFObjectHandle::newNull());
        if (ff.isStream()) {
            try {
                auto buf = ff.getStreamData(qpdf_dl_generalized);
                if (buf && buf->getSize() > 0) {
                    std::vector<uint8_t> prog(buf->getBuffer(), buf->getBuffer() + buf->getSize());
                    FT_Face face = nullptr;
                    if (isSafeFontBuffer(prog) &&
                        FT_New_Memory_Face(ftLib, prog.data(), static_cast<FT_Long>(prog.size()), 0, &face) == 0 && face) {
                        int upem = face->units_per_EM > 0 ? face->units_per_EM : 1000;
                        FT_UInt ng = static_cast<FT_UInt>(std::max<FT_Long>(0, face->num_glyphs));

                        // Parse CIDToGIDMap if stream (capped at 65536 2-byte entries = 131072 bytes)
                        std::vector<uint16_t> cidToGid;
                        bool isIdentityMap = true;
                        QPDFObjectHandle c2g = d.getKey("/CIDToGIDMap");
                        if (c2g.isStream()) {
                            auto mb = c2g.getStreamData(qpdf_dl_generalized);
                            if (mb && mb->getSize() >= 2) {
                                isIdentityMap = false;
                                size_t cnt = std::min<size_t>(mb->getSize() / 2, 65536U);
                                cidToGid.resize(cnt);
                                for (size_t ci = 0; ci < cnt; ++ci) {
                                    cidToGid[ci] = readU16(mb->getBuffer() + ci * 2);
                                }
                            }
                        }

                        QPDFObjectHandle fEncStage5 = f.getKey("/Encoding");
                        bool isIdentityEncStage5 = fEncStage5.isName() &&
                                                   (fEncStage5.getName() == "/Identity-H" ||
                                                    fEncStage5.getName() == "/Identity-V");
                        const std::set<uint32_t>* usedPtr = nullptr;
                        if (isIdentityEncStage5 && f.isIndirect()) {
                            auto uit = usedFontCodes.find(f.getObjGen());
                            if (uit != usedFontCodes.end()) usedPtr = &uit->second;
                        }

                        // Stage 5: Repair CID-0 and GID-0/OOB CIDs on CIDFontType2
                        if ((flags & kFlagRepairNotdef) && dsub == "/CIDFontType2") {
                            bool hasUsedCid0 = usedPtr && usedPtr->count(0) > 0;
                            bool anyBadCid = hasUsedCid0;
                            uint32_t maxCid = 0;
                            if (usedPtr) {
                                for (uint32_t cid : *usedPtr) {
                                    if (cid > maxCid) maxCid = cid;
                                    if (cid > 0) {
                                        uint32_t g = isIdentityMap ? cid : (cid < cidToGid.size() ? cidToGid[cid] : 0);
                                        if (g == 0 || g >= ng) anyBadCid = true;
                                    }
                                }
                            }
                            if (!isIdentityMap && !cidToGid.empty()) {
                                for (size_t ci = 1; ci < cidToGid.size(); ++ci) {
                                    if (cidToGid[ci] == 0 || cidToGid[ci] >= ng) {
                                        anyBadCid = true;
                                        break;
                                    }
                                }
                            }
                            if (anyBadCid) {
                                uint16_t blankGid = sfntAppendBlankGlyph(prog, 0);
                                if (blankGid > 0) {
                                    // Convert Identity map to explicit stream if needed
                                    if (isIdentityMap) {
                                        size_t mapLen = std::max<size_t>(ng, maxCid + 2);
                                        if (mapLen > 65535) mapLen = 65535;
                                        cidToGid.resize(mapLen);
                                        for (size_t ci = 0; ci < mapLen; ++ci) {
                                            cidToGid[ci] = (ci < ng) ? static_cast<uint16_t>(ci) : blankGid;
                                        }
                                        isIdentityMap = false;
                                    }
                                    if (maxCid + 2 > cidToGid.size() && maxCid + 2 <= 65535) {
                                        cidToGid.resize(maxCid + 2, blankGid);
                                    }
                                    for (size_t ci = 1; ci < cidToGid.size(); ++ci) {
                                        if (cidToGid[ci] == 0 || cidToGid[ci] >= ng) {
                                            cidToGid[ci] = blankGid;
                                        }
                                    }
                                    // Remap CID 0 in content streams to an unused high blankCid (0xF000+) so it never
                                    // collides with existing ToUnicode bfranges, and map it to space in /ToUnicode.
                                    if (hasUsedCid0 && f.isIndirect()) {
                                        uint32_t blankCid = 0xF000;
                                        for (uint32_t cand = std::max<uint32_t>(0xF000, maxCid + 1); cand < 0xFFFE; ++cand) {
                                            if (!usedPtr->count(cand)) { blankCid = cand; break; }
                                        }
                                        if (blankCid >= cidToGid.size()) {
                                            cidToGid.resize(blankCid + 1, blankGid);
                                        }
                                        cidToGid[blankCid] = blankGid;
                                        codeRemaps[f.getObjGen()][0] = blankCid;
                                        if (f.hasKey("/ToUnicode") && f.getKey("/ToUnicode").isStream()) {
                                            try {
                                                QPDFObjectHandle tu = f.getKey("/ToUnicode");
                                                auto tub = tu.getStreamData(qpdf_dl_generalized);
                                                if (tub && tub->getSize() > 0) {
                                                    std::string tus(reinterpret_cast<const char*>(tub->getBuffer()), tub->getSize());
                                                    size_t ep = tus.rfind("endcmap");
                                                    if (ep != std::string::npos) {
                                                        char entry[96];
                                                        std::snprintf(entry, sizeof(entry),
                                                                      "\n1 beginbfchar\n<%04X> <0020>\nendbfchar\n",
                                                                      blankCid & 0xFFFF);
                                                        tus.insert(ep, entry);
                                                        QPDFObjectHandle newTu = QPDFObjectHandle::newStream(&qpdf, tus);
                                                        f.replaceKey("/ToUnicode", newTu);
                                                    }
                                                }
                                            } catch (...) {}
                                        }
                                    }
                                    // Write back updated TTF (make a fresh indirect stream so shared donor isn't mutated)
                                    std::string newProg(reinterpret_cast<const char*>(prog.data()), prog.size());
                                    QPDFObjectHandle newFf = QPDFObjectHandle::newStream(&qpdf, newProg);
                                    newFf.getDict().replaceKey("/Length1", QPDFObjectHandle::newInteger(static_cast<long long>(prog.size())));
                                    fd.replaceKey("/FontFile2", newFf);
                                    std::vector<uint8_t> newMap(cidToGid.size() * 2);
                                    for (size_t ci = 0; ci < cidToGid.size(); ++ci) {
                                        writeU16(newMap, ci * 2, cidToGid[ci]);
                                    }
                                    std::string mapStr(reinterpret_cast<const char*>(newMap.data()), newMap.size());
                                    QPDFObjectHandle newC2g = QPDFObjectHandle::newStream(&qpdf, mapStr);
                                    d.replaceKey("/CIDToGIDMap", newC2g);
                                    FT_Done_Face(face);
                                    face = nullptr;
                                    FT_New_Memory_Face(ftLib, prog.data(), static_cast<FT_Long>(prog.size()), 0, &face);
                                    if (face) ng = static_cast<FT_UInt>(std::max<FT_Long>(0, face->num_glyphs));
                                    ++notdefFixedCount;
                                }
                            }
                        } else if ((flags & kFlagRepairNotdef) && dsub == "/CIDFontType0" &&
                                   usedPtr && !usedPtr->empty() && f.isIndirect()) {
                            std::vector<uint32_t> badCids;
                            for (uint32_t cid : *usedPtr) {
                                if (cid == 0 || cid >= ng) badCids.push_back(cid);
                            }
                            if (!badCids.empty()) {
                                std::string lbf = toLowerAscii(bf);
                                bool isOcrOrDummy = (ng <= 2 || lbf.find("glyphless") != std::string::npos);
                                if (isOcrOrDummy) {
                                    // Replace dummy/OCR CIDFontType0 with CIDFontType2 + blank glyph (preserves ToUnicode & 0% pixel diff!)
                                    const DonorFont* donor = getCachedDonor(ftLib, donorDirs, "arial");
                                    if (donor) {
                                        std::vector<uint8_t> dProg = donor->raw;
                                        uint16_t advU = static_cast<uint16_t>(std::max(1, donor->upem / 2));
                                        uint16_t blankGid = sfntAppendBlankGlyph(dProg, advU);
                                        if (blankGid > 0) {
                                            uint32_t maxCid = 1;
                                            for (uint32_t c : *usedPtr) if (c > maxCid) maxCid = c;
                                            uint32_t blankCid = 0;
                                            if (usedPtr->count(0)) {
                                                blankCid = std::min<uint32_t>(std::max<uint32_t>(256, maxCid + 1), 65530);
                                                while (usedPtr->count(blankCid) && blankCid < 65534) ++blankCid;
                                            }
                                            size_t mapSz = std::min<size_t>(
                                                std::max<size_t>({static_cast<size_t>(maxCid) + 2,
                                                                  static_cast<size_t>(blankCid) + 1,
                                                                  static_cast<size_t>(258)}),
                                                65535);
                                            std::vector<uint8_t> mapBytes(mapSz * 2, 0);
                                            std::map<int, int> wmap;
                                            for (size_t ci = 1; ci < mapSz; ++ci) {
                                                writeU16(mapBytes, ci * 2, blankGid);
                                            }
                                            if (blankCid > 0) {
                                                if (blankCid < mapSz) writeU16(mapBytes, blankCid * 2, blankGid);
                                                codeRemaps[f.getObjGen()][0] = blankCid;
                                            }
                                            d.replaceKey("/Subtype", QPDFObjectHandle::newName("/CIDFontType2"));
                                            dsub = "/CIDFontType2";
                                            if (fd.hasKey("/FontFile3")) fd.removeKey("/FontFile3");
                                            std::string newP(reinterpret_cast<const char*>(dProg.data()), dProg.size());
                                            QPDFObjectHandle newFf = QPDFObjectHandle::newStream(&qpdf, newP);
                                            newFf.getDict().replaceKey("/Length1", QPDFObjectHandle::newInteger(static_cast<long long>(dProg.size())));
                                            fd.replaceKey("/FontFile2", newFf);
                                            std::string mapStr(reinterpret_cast<const char*>(mapBytes.data()), mapBytes.size());
                                            d.replaceKey("/CIDToGIDMap", QPDFObjectHandle::newStream(&qpdf, mapStr));
                                            d.replaceKey("/DW", QPDFObjectHandle::newInteger(500));
                                            if (d.hasKey("/W")) d.removeKey("/W");
                                            FT_Done_Face(face);
                                            face = nullptr;
                                            ++notdefFixedCount;
                                        }
                                    }
                                } else if (ng > 1) {
                                    FT_UInt bestG = 1;
                                    int bestW = 100000;
                                    for (FT_UInt g = 1; g < std::min<FT_UInt>(ng, 256); ++g) {
                                        int pw = ftGlyphWidth1000(face, g, upem, true);
                                        if (pw >= 0 && pw < bestW) {
                                            bestW = pw;
                                            bestG = g;
                                            if (pw == 0) break;
                                        }
                                    }
                                    for (uint32_t bc : badCids) {
                                        codeRemaps[f.getObjGen()][bc] = bestG;
                                    }
                                    ++notdefFixedCount;
                                }
                            }
                        }

                        // Stage 4: Reconcile /W against FreeType advances.
                        // For CIDFontType2 (TrueType), prefer patching hmtx to match /W (preserves exact PDF
                        // layout & PDFium text extraction word spacing!); fall back to updating /W on collisions/CFF.
                        if ((flags & kFlagReconcileWidths) && face) {
                            bool isCff = (dsub == "/CIDFontType0");
                            double fdScale = isCff ? cffFdScaleFactor(prog) : 1.0;
                            int dw = d.hasKey("/DW") && d.getKey("/DW").isNumber()
                                         ? static_cast<int>(std::llround(d.getKey("/DW").getNumericValue()))
                                         : 1000;
                            auto wmap = d.hasKey("/W") ? parseWArray(d.getKey("/W")) : std::map<int, int>{};
                            bool wChanged = false;
                            std::map<uint16_t, uint16_t> hmtxPatch;
                            std::map<uint16_t, int> gidTargetW;
                            std::set<uint16_t> gidCollision;
                            if (!isCff && upem >= 500 && prog.size() <= 4U * 1024U * 1024U) {
                                // Pre-check which GIDs have unique target widths in /W (only for used or mapped CIDs)
                                auto recordGidW = [&](int cid, FT_UInt gid) {
                                    if (gid == 0 || gid >= ng || gid >= 65535) return;
                                    auto it = wmap.find(cid);
                                    int dictW = (it != wmap.end()) ? it->second : dw;
                                    if (dictW < 0 || dictW > 10000) return;
                                    uint16_t g16 = static_cast<uint16_t>(gid);
                                    auto git = gidTargetW.find(g16);
                                    if (git == gidTargetW.end()) gidTargetW[g16] = dictW;
                                    else if (git->second != dictW) gidCollision.insert(g16);
                                };
                                if (dsub == "/CIDFontType2" && !isIdentityMap && !cidToGid.empty()) {
                                    for (size_t ci = 0; ci < cidToGid.size(); ++ci) recordGidW(static_cast<int>(ci), cidToGid[ci]);
                                } else if (ng <= 2048 || !usedPtr || usedPtr->empty()) {
                                    for (FT_UInt g = 0; g < std::min<FT_UInt>(ng, 65535); ++g) recordGidW(static_cast<int>(g), g);
                                } else {
                                    for (uint32_t uc : *usedPtr) if (uc < ng) recordGidW(static_cast<int>(uc), uc);
                                }
                            }
                            std::vector<double> gidWidthCache(ng, std::numeric_limits<double>::quiet_NaN());
                            std::vector<uint8_t> gidWidthComputed(ng, 0);
                            auto getCachedGidWidth = [&](FT_UInt gid) -> double {
                                if (gid >= ng) return std::numeric_limits<double>::quiet_NaN();
                                if (!gidWidthComputed[gid]) {
                                    gidWidthCache[gid] = ftGlyphWidth1000Exact(face, gid, upem, isCff, fdScale);
                                    gidWidthComputed[gid] = 1;
                                }
                                return gidWidthCache[gid];
                            };
                            auto checkCid = [&](int cid, FT_UInt gid) {
                                if (gid >= ng) return;
                                double exactW = getCachedGidWidth(gid);
                                if (!std::isfinite(exactW)) return;
                                if (isCff && exactW < 0.0) exactW = 1000.0;
                                auto it = wmap.find(cid);
                                int dictW = (it != wmap.end()) ? it->second : dw;
                                if (std::abs(exactW - static_cast<double>(dictW)) > 0.95) {
                                    if (!isCff && prog.size() <= 4U * 1024U * 1024U && gid > 0 && gid < 65535 &&
                                        dictW > 0 && dictW <= 10000 &&
                                        upem >= 500 && !gidCollision.count(static_cast<uint16_t>(gid))) {
                                        long long advU = std::llround(static_cast<double>(dictW) * upem / 1000.0);
                                        if (advU >= 0 && advU <= 65535 &&
                                            std::abs(static_cast<double>(advU) * 1000.0 / upem - dictW) <= 0.95) {
                                            hmtxPatch[static_cast<uint16_t>(gid)] = static_cast<uint16_t>(advU);
                                            return;
                                        }
                                    }
                                    wmap[cid] = static_cast<int>(std::llround(exactW));
                                    wChanged = true;
                                }
                            };
                            QPDFObjectHandle fEnc = f.getKey("/Encoding");
                            bool isIdentityEnc = fEnc.isName() &&
                                                 (fEnc.getName() == "/Identity-H" || fEnc.getName() == "/Identity-V");
                            bool useSparseCheck = (ng > 4096 && isIdentityEnc && usedPtr && !usedPtr->empty());
                            if (dsub == "/CIDFontType2" && !isIdentityMap && !cidToGid.empty()) {
                                if (useSparseCheck) {
                                    std::set<uint32_t> cidsToCheck(usedPtr->begin(), usedPtr->end());
                                    if (f.isIndirect()) {
                                        auto rit = codeRemaps.find(f.getObjGen());
                                        if (rit != codeRemaps.end()) {
                                            for (const auto& [origC, newC] : rit->second) cidsToCheck.insert(newC);
                                        }
                                    }
                                    for (const auto& [wc, _] : wmap) {
                                        if (wc >= 0 && static_cast<size_t>(wc) < cidToGid.size()) {
                                            cidsToCheck.insert(static_cast<uint32_t>(wc));
                                        }
                                    }
                                    for (uint32_t uc : cidsToCheck) {
                                        if (uc < cidToGid.size()) checkCid(static_cast<int>(uc), cidToGid[uc]);
                                    }
                                } else {
                                    for (size_t ci = 0; ci < cidToGid.size(); ++ci) {
                                        checkCid(static_cast<int>(ci), cidToGid[ci]);
                                    }
                                }
                            } else if (useSparseCheck) {
                                std::set<uint32_t> cidsToCheck(usedPtr->begin(), usedPtr->end());
                                if (f.isIndirect()) {
                                    auto rit = codeRemaps.find(f.getObjGen());
                                    if (rit != codeRemaps.end()) {
                                        for (const auto& [origC, newC] : rit->second) cidsToCheck.insert(newC);
                                    }
                                }
                                for (const auto& [wc, _] : wmap) {
                                    if (wc >= 0 && static_cast<uint32_t>(wc) < ng) cidsToCheck.insert(static_cast<uint32_t>(wc));
                                }
                                for (uint32_t cid : cidsToCheck) {
                                    if (cid < ng) checkCid(static_cast<int>(cid), cid);
                                }
                            } else {
                                for (FT_UInt g = 0; g < std::min<FT_UInt>(ng, 65535); ++g) {
                                    checkCid(static_cast<int>(g), g);
                                }
                            }
                            if (!hmtxPatch.empty()) {
                                if (sfntPatchHmtx(prog, hmtxPatch)) {
                                    std::string newP(reinterpret_cast<const char*>(prog.data()), prog.size());
                                    QPDFObjectHandle newFf = QPDFObjectHandle::newStream(&qpdf, newP);
                                    newFf.getDict().replaceKey("/Length1", QPDFObjectHandle::newInteger(static_cast<long long>(prog.size())));
                                    // Clone FontDescriptor so shared descriptors never collide
                                    QPDFObjectHandle newFd = qpdf.makeIndirectObject(fd.shallowCopy());
                                    newFd.replaceKey("/FontFile2", newFf);
                                    d.replaceKey("/FontDescriptor", newFd);
                                    fd = newFd;
                                    ++widthsFixedCount;
                                } else {
                                    // Fallback: update /W for any patched entries
                                    for (const auto& [g16, advU] : hmtxPatch) {
                                        double exactW = ftGlyphWidth1000Exact(face, g16, upem, false, 1.0);
                                        if (exactW >= 0.0) { wmap[g16] = static_cast<int>(std::llround(exactW)); wChanged = true; }
                                    }
                                }
                            }
                            if (wChanged) {
                                d.replaceKey("/W", serializeWMap(wmap));
                                ++widthsFixedCount;
                            }
                            // PDF/A-1b (ISO 19005-1 6.3.5-3) requires a complete /CIDSet stream on CIDFont subsets.
                            if ((flags & kFlagConvertPdfA1b) && fd.isDictionary()) {
                                size_t maxCid = 0;
                                std::vector<uint32_t> presentCids;
                                presentCids.push_back(0);
                                if (dsub == "/CIDFontType2" && !isIdentityMap && !cidToGid.empty()) {
                                    for (size_t ci = 1; ci < cidToGid.size(); ++ci) {
                                        if (cidToGid[ci] > 0 && cidToGid[ci] < ng) {
                                            presentCids.push_back(static_cast<uint32_t>(ci));
                                            if (ci > maxCid) maxCid = ci;
                                        }
                                    }
                                } else {
                                    FT_UInt cap = std::min<FT_UInt>(ng, 65535);
                                    for (FT_UInt g = 1; g < cap; ++g) {
                                        presentCids.push_back(g);
                                        if (g > maxCid) maxCid = g;
                                    }
                                }
                                if (usedPtr) {
                                    for (uint32_t uc : *usedPtr) {
                                        if (uc < 65536) {
                                            presentCids.push_back(uc);
                                            if (uc > maxCid) maxCid = uc;
                                        }
                                    }
                                }
                                std::string bits((maxCid / 8) + 1, '\0');
                                for (uint32_t c : presentCids) {
                                    bits[c / 8] = static_cast<char>(
                                        static_cast<unsigned char>(bits[c / 8]) | (0x80U >> (c % 8)));
                                }
                                fd.replaceKey("/CIDSet", QPDFObjectHandle::newStream(&qpdf, bits));
                                ++metaFixedCount;
                            }
                        }
                        if (face) FT_Done_Face(face);
                    }
                }
            } catch (...) {
            }
        }
    }
}

}  // namespace jpdfium::rewriter
