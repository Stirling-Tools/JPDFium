// jpdfium_rewriter_fonts_cid.cpp - Composite Type0 / CIDFont rewriting stages
// (missing font embedding, CID-0/GID-0/.notdef repair, and /W width reconciliation).

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <limits>
#include <set>
#include <sstream>
#include <string>
#include <utility>
#include <vector>

#include "jpdfium_font_rewriter_internal.h"
#include "jpdfium_qpdf_internal.h"

namespace jpdfium::rewriter {

namespace {

// Build a small embedded CMap for a predefined CJK encoding.  PDF/A requires every
// non-Identity CMap to be embedded; mapping only the codes actually used keeps the
// stream small while preserving the original character codes in the content stream.
std::string buildUsedCodeCMap(const std::string& name, const std::string& ordering, int supplement,
                              bool vertical, qpdf_detail::CidEncoding encoding,
                              const std::set<uint32_t>& codes) {
    const bool rksj = qpdf_detail::isRksjCidEncoding(encoding);
    const bool ksc = qpdf_detail::isKscEucCidEncoding(encoding);
    const bool proportional = qpdf_detail::isRksjPropCidEncoding(encoding);
    std::ostringstream out;
    out << "/CIDInit /ProcSet findresource begin\n"
           "12 dict begin\n"
           "begincmap\n"
           "/CIDSystemInfo << /Registry (Adobe) /Ordering ("
        << ordering << ") /Supplement " << supplement
        << " >> def\n"
           "/CMapName /"
        << name
        << " def\n"
           "/CMapVersion 1.0 def\n"
           "/CMapType 1 def\n"
           "/WMode "
        << (vertical ? 1 : 0) << " def\n";
    if (rksj) {
        out << "4 begincodespacerange\n<00> <80>\n<8140> <9FFC>\n<A0> <DF>\n<E040> <FCFC>\n"
               "endcodespacerange\n";
    } else {
        out << "2 begincodespacerange\n<00> <80>\n<A1A1> <FEFE>\nendcodespacerange\n";
    }

    std::vector<std::pair<uint32_t, uint32_t>> entries;
    entries.reserve(codes.size());
    for (uint32_t code : codes) {
        if (code > 0xFFFF) continue;
        uint32_t cid = 0;
        bool found = false;
        if (rksj) {
            found = qpdf_detail::japan1CidForCode(static_cast<uint16_t>(code), cid, proportional,
                                                  vertical);
        } else if (ksc) {
            found = qpdf_detail::korea1CidForCode(static_cast<uint16_t>(code), cid, vertical);
        } else {
            found = qpdf_detail::gb1CidForCode(static_cast<uint16_t>(code), cid, vertical);
        }
        if (found) entries.emplace_back(code, cid);
    }
    for (size_t off = 0; off < entries.size(); off += 100) {
        size_t n = std::min<size_t>(100, entries.size() - off);
        out << n << " begincidchar\n";
        for (size_t k = 0; k < n; ++k) {
            uint32_t code = entries[off + k].first;
            uint32_t cid = entries[off + k].second;
            char line[48];
            if (code <= 0xFF) {
                std::snprintf(line, sizeof(line), "<%02X> %u\n", code, cid);
            } else {
                std::snprintf(line, sizeof(line), "<%04X> %u\n", code, cid);
            }
            out << line;
        }
        out << "endcidchar\n";
    }
    out << "endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n";
    return out.str();
}

int safeRoundCidWidth(double v) {
    if (!std::isfinite(v)) return 0;
    const double clamped = std::clamp(v, -1000000.0, 1000000.0);
    return static_cast<int>(std::llround(clamped));
}

}  // namespace

void rewriteType0Font(FontRewriteContext& ctx, QPDFObjectHandle& f, const std::string& bf) {
    QPDF& qpdf = ctx.qpdf;
    FT_Library ftLib = ctx.ftLib;
    uint32_t flags = ctx.flags;
    const char* donorDirs = ctx.donorDirs;
    auto& usedFontCodes = ctx.usedFontCodes;
    const auto& usedFontCids = ctx.usedFontCids;
    auto& codeRemaps = ctx.codeRemaps;
    int& embeddedCount = ctx.embeddedCount;
    int& widthsFixedCount = ctx.widthsFixedCount;
    int& cmapFixedCount = ctx.cmapFixedCount;
    int& notdefFixedCount = ctx.notdefFixedCount;
    int& metaFixedCount = ctx.metaFixedCount;
    auto getDonorStream = [&](const std::string& key, bool isCff) -> QPDFObjectHandle {
        return ctx.getDonorStream(key, isCff);
    };
    (void)notdefFixedCount;
    (void)codeRemaps;

    QPDFObjectHandle df = f.getKey("/DescendantFonts");
    if (!df.isArray() || df.getArrayNItems() < 1 || !df.getArrayItem(0).isDictionary()) return;
    QPDFObjectHandle d = df.getArrayItem(0);
    std::string dsub = d.getKey("/Subtype").isName() ? d.getKey("/Subtype").getName() : "";
    QPDFObjectHandle fd = d.getKey("/FontDescriptor");
    bool isEmb = (flags & kFlagEmbedMissing)
                     ? validateOrStripCorruptFontStreams(fd)
                     : (fd.isDictionary() && (fd.hasKey("/FontFile") || fd.hasKey("/FontFile2") ||
                                              fd.hasKey("/FontFile3")));
    // PDF/A-1 does not allow /FontFile3 /Subtype /OpenType (6.3.2).
    if ((flags & kFlagConvertPdfA1b) && dsub == "/CIDFontType0" && fd.isDictionary() &&
        convertOpenTypeWrapperForPdfA1(qpdf, fd, ftLib, true)) {
        ++metaFixedCount;
    }
    if ((flags & kFlagConvertPdfA1b) && repairCffFontProgramForPdfA(qpdf, fd)) {
        ++metaFixedCount;
    }
    // Symbolic TrueType programs must expose exactly one cmap encoding (6.3.7).
    if ((flags & kFlagConvertPdfA1b) && dsub == "/CIDFontType2" &&
        pruneSymbolicTrueTypeProgram(qpdf, d, fd)) {
        ++cmapFixedCount;
    }

    if ((flags & kFlagCleanMetadata) && fd.isDictionary()) {
        if (!(flags & kFlagConvertPdfA1b) && fd.hasKey("/CIDSet")) {
            fd.removeKey("/CIDSet");
            ++metaFixedCount;
        }
        if (dsub == "/CIDFontType2") {
            QPDFObjectHandle ctg = d.getKey("/CIDToGIDMap");
            if (!(ctg.isStream() || (ctg.isName() && ctg.getName() == "/Identity"))) {
                d.replaceKey("/CIDToGIDMap", QPDFObjectHandle::newName("/Identity"));
                ++metaFixedCount;
            }
        }
        QPDFObjectHandle enc = f.getKey("/Encoding");
        const bool isPdfA =
            (flags & (kFlagConvertPdfA1b | kFlagConvertPdfA2b | kFlagConvertPdfA3b)) != 0;
        const int pdfaPart = (flags & kFlagConvertPdfA1b)   ? 1
                             : (flags & kFlagConvertPdfA3b) ? 3
                                                            : 2;
        static const std::set<std::string> kTable118CMaps = {"/Identity-H",
                                                             "/Identity-V",
                                                             "/GB-EUC-H",
                                                             "/GB-EUC-V",
                                                             "/GBpc-EUC-H",
                                                             "/GBpc-EUC-V",
                                                             "/GBK-EUC-H",
                                                             "/GBK-EUC-V",
                                                             "/GBKp-EUC-H",
                                                             "/GBKp-EUC-V",
                                                             "/GBK2K-H",
                                                             "/GBK2K-V",
                                                             "/UniGB-UCS2-H",
                                                             "/UniGB-UCS2-V",
                                                             "/UniGB-UTF16-H",
                                                             "/UniGB-UTF16-V",
                                                             "/B5pc-H",
                                                             "/B5pc-V",
                                                             "/HKscs-B5-H",
                                                             "/HKscs-B5-V",
                                                             "/ETen-B5-H",
                                                             "/ETen-B5-V",
                                                             "/ETenms-B5-H",
                                                             "/ETenms-B5-V",
                                                             "/CNS-EUC-H",
                                                             "/CNS-EUC-V",
                                                             "/UniCNS-UCS2-H",
                                                             "/UniCNS-UCS2-V",
                                                             "/UniCNS-UTF16-H",
                                                             "/UniCNS-UTF16-V",
                                                             "/83pv-RKSJ-H",
                                                             "/90ms-RKSJ-H",
                                                             "/90ms-RKSJ-V",
                                                             "/90msp-RKSJ-H",
                                                             "/90msp-RKSJ-V",
                                                             "/90pv-RKSJ-H",
                                                             "/Add-RKSJ-H",
                                                             "/Add-RKSJ-V",
                                                             "/EUC-H",
                                                             "/EUC-V",
                                                             "/Ext-RKSJ-H",
                                                             "/Ext-RKSJ-V",
                                                             "/H",
                                                             "/V",
                                                             "/UniJIS-UCS2-H",
                                                             "/UniJIS-UCS2-V",
                                                             "/UniJIS-UCS2-HW-H",
                                                             "/UniJIS-UCS2-HW-V",
                                                             "/UniJIS-UTF16-H",
                                                             "/UniJIS-UTF16-V",
                                                             "/KSC-EUC-H",
                                                             "/KSC-EUC-V",
                                                             "/KSCms-UHC-H",
                                                             "/KSCms-UHC-V",
                                                             "/KSCms-UHC-HW-H",
                                                             "/KSCms-UHC-HW-V",
                                                             "/KSCpc-EUC-H",
                                                             "/UniKS-UCS2-H",
                                                             "/UniKS-UCS2-V",
                                                             "/UniKS-UTF16-H",
                                                             "/UniKS-UTF16-V"};
        if (enc.isStream()) {
            QPDFObjectHandle encDict = enc.getDict();
            if (isPdfA) {
                if (!encDict.getKey("/Type").isNameAndEquals("/CMap")) {
                    encDict.replaceKey("/Type", QPDFObjectHandle::newName("/CMap"));
                    ++metaFixedCount;
                }
                if (encDict.hasKey("/UseCMap")) {
                    QPDFObjectHandle uc = encDict.getKey("/UseCMap");
                    bool stripUseCMap = (pdfaPart == 1);
                    if (!stripUseCMap && uc.isName() && !kTable118CMaps.count(uc.getName())) {
                        stripUseCMap = true;
                    }
                    if (stripUseCMap) {
                        encDict.removeKey("/UseCMap");
                        ++metaFixedCount;
                    }
                }
            }
            if (encDict.hasKey("/CIDSystemInfo") &&
                encDict.getKey("/CIDSystemInfo").isDictionary()) {
                d.replaceKey("/CIDSystemInfo", encDict.getKey("/CIDSystemInfo").shallowCopy());
                ++metaFixedCount;
            } else if (d.hasKey("/CIDSystemInfo") && d.getKey("/CIDSystemInfo").isDictionary()) {
                encDict.replaceKey("/CIDSystemInfo", d.getKey("/CIDSystemInfo").shallowCopy());
                ++metaFixedCount;
            }
            // Sync CMap stream dictionary /WMode with embedded CMap body "/WMode <0|1> def"
            // (fixes 6.2.11.3.3-2)
            try {
                std::string cbuf;
                if (readStreamBounded(enc, cbuf, qpdf_dl_generalized, size_t{2} * 1024U * 1024U) &&
                    !cbuf.empty()) {
                    std::string_view sv(cbuf);
                    size_t wp = sv.find("/WMode");
                    if (wp != std::string_view::npos) {
                        size_t p = wp + 6;
                        while (p < sv.size() &&
                               (sv[p] == ' ' || sv[p] == '\t' || sv[p] == '\r' || sv[p] == '\n'))
                            ++p;
                        if (p < sv.size() && (sv[p] == '0' || sv[p] == '1')) {
                            int bodyWMode = sv[p] - '0';
                            int dictWMode =
                                (encDict.hasKey("/WMode") && encDict.getKey("/WMode").isInteger())
                                    ? static_cast<int>(encDict.getKey("/WMode").getIntValue())
                                    : 0;
                            if (dictWMode != bodyWMode) {
                                encDict.replaceKey("/WMode",
                                                   QPDFObjectHandle::newInteger(bodyWMode));
                                ++metaFixedCount;
                            }
                        }
                    }
                }
            } catch (const std::exception& e) {
                std::fputs(e.what(), stderr);
                std::fputc('\n', stderr);
            } catch (...) {
                std::fputs("jpdfium: WMode sync failed\n", stderr);
            }
        } else if (enc.isName()) {
            std::string ename = enc.getName();
            if (isPdfA && ename.rfind("/Adobe-", 0) == 0 && !kTable118CMaps.count(ename)) {
                bool isVert = (ename.size() >= 2 && ename.compare(ename.size() - 2, 2, "-V") == 0);
                ename = isVert ? "/Identity-V" : "/Identity-H";
                f.replaceKey("/Encoding", QPDFObjectHandle::newName(ename));
                ++metaFixedCount;
            }
            if (ename != "/Identity-H" && ename != "/Identity-V") {
                QPDFObjectHandle csi = d.getKey("/CIDSystemInfo");
                if (csi.isDictionary() && csi.hasKey("/Supplement") &&
                    csi.getKey("/Supplement").isInteger() &&
                    csi.getKey("/Supplement").getIntValue() > 0) {
                    QPDFObjectHandle newCsi = csi.shallowCopy();
                    newCsi.replaceKey("/Supplement", QPDFObjectHandle::newInteger(0));
                    d.replaceKey("/CIDSystemInfo", newCsi);
                    ++metaFixedCount;
                }
            }
        }
    }

    // Stage 3: Embed missing CIDFont (both CIDFontType0 and CIDFontType2 -> CIDFontType2 +
    // styled/Unicode donor)
    if (!isEmb && (flags & kFlagEmbedMissing)) {
        if (dsub == "/CIDFontType0" || dsub == "/CIDFontType2") {
            std::string encName =
                f.getKey("/Encoding").isName() ? f.getKey("/Encoding").getName() : "";
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
                    if (u >= 0x2E80U) {
                        anyCjk = true;
                        break;
                    }
                }
                if (!anyCjk) {
                    std::string lbf = toLowerAscii(bf);
                    bool isSerif = (lbf.find("mincho") != std::string::npos ||
                                    lbf.find("kozmin") != std::string::npos ||
                                    lbf.find("song") != std::string::npos ||
                                    lbf.find("times") != std::string::npos ||
                                    lbf.find("serif") != std::string::npos);
                    bool isBold = (lbf.find("bold") != std::string::npos ||
                                   lbf.find("heavy") != std::string::npos);
                    bool isItal = (lbf.find("italic") != std::string::npos ||
                                   lbf.find("oblique") != std::string::npos);
                    std::string suf =
                        (isBold && isItal) ? "_bi" : (isBold ? "_bold" : (isItal ? "_italic" : ""));
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
                    fd.replaceKey("/FontName",
                                  QPDFObjectHandle::newName(bf.empty() ? "/ArialUnicodeMS" : bf));
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
                // switch to /Identity-H and build a Unicode->GID /CIDToGIDMap from the chosen
                // donor!
                if (isUcs2CMap) {
                    std::vector<uint8_t> dProg = donor->raw;
                    uint16_t blankGid = sfntAppendBlankGlyph(dProg, 0);
                    FT_Face uRawFace = nullptr;
                    if (blankGid > 0 && isSafeFontBuffer(dProg) &&
                        FT_New_Memory_Face(ftLib, dProg.data(), static_cast<FT_Long>(dProg.size()),
                                           0, &uRawFace) == 0 &&
                        uRawFace) {
                        jpdfium::font::FtFaceGuard uFaceGuard(uRawFace);
                        FT_Face uFace = uFaceGuard.face;
                        FT_Select_Charmap(uFace, FT_ENCODING_UNICODE);
                        int uUpem = uFace->units_per_EM > 0 ? uFace->units_per_EM : 1000;
                        constexpr size_t kMapSz = 65535;
                        std::vector<uint8_t> mapBytes(kMapSz * 2, 0);
                        std::map<int, int> uWmap;
                        const std::set<uint32_t>* uUsed = nullptr;
                        if (f.isIndirect()) {
                            auto uit = usedFontCodes.find(f.getObjGen());
                            if (uit != usedFontCodes.end()) uUsed = &uit->second;
                        }
                        // Map original collection-CID /W widths to Unicode via the Adobe CJK
                        // collection table so hmtx patching preserves exact original character
                        // advance positions!
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
                        auto origWmap =
                            d.hasKey("/W") ? parseWArray(d.getKey("/W")) : std::map<int, int>{};
                        std::map<uint32_t, int> origWidthByUni;
                        if (!ordering.empty()) {
                            for (const auto& [cid, w] : origWmap) {
                                if (cid < 0 || w <= 0 || w > 10000) continue;
                                uint32_t uni = 0;
                                if (cid >= 1 && cid <= 95) {
                                    // Adobe-Japan1/GB1/CNS1/Korea1 all map CID 1..95 -> ASCII
                                    // U+0020..U+007E.
                                    uni = static_cast<uint32_t>(cid + 31);
                                } else {
                                    jpdfium::qpdf_detail::adobeCollectionUnicodeForCid(
                                        ordering, static_cast<uint32_t>(cid), uni);
                                }
                                if (uni > 0 && uni < kMapSz) {
                                    // Prefer lowest CID (proportional Latin 1..95 over half-width
                                    // 231..325).
                                    origWidthByUni.emplace(uni, w);
                                }
                            }
                        }
                        std::map<uint16_t, uint16_t> cjkHmtxPatch;
                        const bool hasDonorBmp = donor->bmpUnicodeToGid.size() >= kMapSz;
                        std::vector<int> donorWidthCache(65536, -2);
                        for (uint32_t u = 1; u < kMapSz; ++u) {
                            uint16_t g = 0;
                            if (hasDonorBmp) {
                                g = donor->bmpUnicodeToGid[u];
                            } else {
                                FT_UInt fg = FT_Get_Char_Index(uFace, u);
                                if (fg > 0 && fg < 65535) g = static_cast<uint16_t>(fg);
                            }
                            uint16_t mg = (g > 0 && g < 65535) ? g : blankGid;
                            writeU16(mapBytes, static_cast<size_t>(u) * 2, mg);
                            if (!uUsed || uUsed->count(u)) {
                                auto owit = origWidthByUni.find(u);
                                if (owit != origWidthByUni.end() && mg != blankGid) {
                                    uWmap[static_cast<int>(u)] = owit->second;
                                    long long advU = safeRoundCidWidth(
                                        static_cast<double>(owit->second) * uUpem / 1000.0);
                                    if (advU >= 0 && advU <= 65535) {
                                        cjkHmtxPatch[mg] = static_cast<uint16_t>(advU);
                                    }
                                } else {
                                    int pw = donorWidthCache[mg];
                                    if (pw == -2) {
                                        pw = ftGlyphWidth1000(uFace, mg, uUpem, false);
                                        donorWidthCache[mg] = pw;
                                    }
                                    if (pw >= 0) uWmap[static_cast<int>(u)] = pw;
                                }
                            }
                        }
                        uFaceGuard.reset();
                        if (!cjkHmtxPatch.empty()) {
                            std::vector<uint8_t> patched = dProg;
                            if (sfntPatchHmtx(patched, cjkHmtxPatch) && !patched.empty()) {
                                dProg = std::move(patched);
                            }
                        }
                        QPDFObjectHandle newFf = ctx.internFontStream(dProg);
                        fd.replaceKey("/FontFile2", newFf);
                        std::string mapStr(reinterpret_cast<const char*>(mapBytes.data()),
                                           mapBytes.size());
                        d.replaceKey("/CIDToGIDMap", QPDFObjectHandle::newStream(&qpdf, mapStr));
                        f.replaceKey("/Encoding", QPDFObjectHandle::newName("/Identity-H"));
                        if (!f.hasKey("/ToUnicode")) {
                            // Spec-compliant 2-byte Identity ToUnicode CMap (ISO 32000-1 §9.10.3:
                            // each bfrange entry may only increment the last byte, <= 100
                            // entries/block, skipping forbidden PDF/A codepoints 0x0000,
                            // surrogates D800..DFFF, PUA E000..F8FF, FEFF, FFFD..FFFF).
                            std::ostringstream tss;
                            tss << "/CIDInit /ProcSet findresource begin\n"
                                << "12 dict begin\n"
                                << "begincmap\n"
                                << "/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) "
                                   "/Supplement 0 >> def\n"
                                << "/CMapName /Adobe-Identity-UCS def\n"
                                << "/CMapType 2 def\n"
                                << "1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n";
                            std::vector<std::string> ranges;
                            ranges.reserve(230);
                            ranges.emplace_back("<0001> <00FF> <0001>\n");
                            for (int hi = 0x01; hi <= 0xD7; ++hi) {
                                char lineBuf[32];
                                std::snprintf(lineBuf, sizeof(lineBuf),
                                              "<%02X00> <%02XFF> <%02X00>\n", hi, hi, hi);
                                ranges.emplace_back(lineBuf);
                            }
                            for (int hi = 0xF9; hi <= 0xFD; ++hi) {
                                char lineBuf[32];
                                std::snprintf(lineBuf, sizeof(lineBuf),
                                              "<%02X00> <%02XFF> <%02X00>\n", hi, hi, hi);
                                ranges.emplace_back(lineBuf);
                            }
                            ranges.emplace_back("<FE00> <FEFE> <FE00>\n");
                            ranges.emplace_back("<FF00> <FFFC> <FF00>\n");
                            for (size_t blk = 0; blk < ranges.size(); blk += 100) {
                                size_t cnt = std::min<size_t>(100, ranges.size() - blk);
                                tss << cnt << " beginbfrange\n";
                                for (size_t ri = 0; ri < cnt; ++ri) {
                                    tss << ranges[blk + ri];
                                }
                                tss << "endbfrange\n";
                            }
                            tss << "endcmap\nCMapName currentdict /CMap defineresource "
                                   "pop\nend\nend\n";
                            f.replaceKey("/ToUnicode",
                                         QPDFObjectHandle::newStream(&qpdf, tss.str()));
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
                    std::string cjkOrdering;
                    QPDFObjectHandle origCsi = d.getKey("/CIDSystemInfo");
                    if (origCsi.isDictionary() && origCsi.hasKey("/Ordering") &&
                        origCsi.getKey("/Ordering").isString()) {
                        cjkOrdering = origCsi.getKey("/Ordering").getUTF8Value();
                    } else if (encName.find("RKSJ") != std::string::npos ||
                               encName.find("JIS") != std::string::npos) {
                        cjkOrdering = "Japan1";
                    } else if (encName.find("KSC") != std::string::npos) {
                        cjkOrdering = "Korea1";
                    } else if (encName.find("GB") != std::string::npos) {
                        cjkOrdering = "GB1";
                    }
                    bool builtCjkMap = false;
                    if (cjkOrdering == "Japan1" || cjkOrdering == "Korea1" ||
                        cjkOrdering == "GB1") {
                        std::vector<uint8_t> dProg = donor->raw;
                        uint16_t blankGid = sfntAppendBlankGlyph(dProg, 0);
                        FT_Face cFaceRaw = nullptr;
                        if (blankGid > 0 && isSafeFontBuffer(dProg) &&
                            FT_New_Memory_Face(ftLib, dProg.data(),
                                               static_cast<FT_Long>(dProg.size()), 0,
                                               &cFaceRaw) == 0 &&
                            cFaceRaw) {
                            jpdfium::font::FtFaceGuard cFaceGuard(cFaceRaw);
                            FT_Face cFace = cFaceGuard.face;
                            FT_Select_Charmap(cFace, FT_ENCODING_UNICODE);
                            size_t mapSz = (cjkOrdering == "GB1")      ? 30285U
                                           : (cjkOrdering == "Japan1") ? 23060U
                                                                       : 18355U;
                            auto origWmap =
                                d.hasKey("/W") ? parseWArray(d.getKey("/W")) : std::map<int, int>{};
                            for (const auto& [cid, _] : origWmap) {
                                if (cid >= 0 && static_cast<size_t>(cid) + 1 > mapSz &&
                                    cid < 65534) {
                                    mapSz = static_cast<size_t>(cid) + 1;
                                }
                            }
                            if (f.isIndirect()) {
                                auto uit = usedFontCids.find(f.getObjGen());
                                if (uit != usedFontCids.end()) {
                                    for (uint32_t cid : uit->second) {
                                        if (static_cast<size_t>(cid) + 1 > mapSz && cid < 65534) {
                                            mapSz = static_cast<size_t>(cid) + 1;
                                        }
                                    }
                                }
                            }
                            std::vector<uint8_t> mapBytes(mapSz * 2, 0);
                            const bool hasDonorBmp = donor->bmpUnicodeToGid.size() >= 65535;
                            std::map<int, int> cjkWmap;
                            int cUpem = cFace->units_per_EM > 0 ? cFace->units_per_EM : 1000;
                            std::vector<int> cDonorWidthCache(65536, -2);
                            for (size_t cid = 1; cid < mapSz; ++cid) {
                                uint32_t uni = 0;
                                if (cid <= 95) {
                                    uni = static_cast<uint32_t>(cid + 31);
                                } else {
                                    jpdfium::qpdf_detail::adobeCollectionUnicodeForCid(
                                        cjkOrdering, static_cast<uint32_t>(cid), uni);
                                }
                                uint16_t g = 0;
                                if (uni > 0) {
                                    if (uni < 65535 && hasDonorBmp) {
                                        g = donor->bmpUnicodeToGid[uni];
                                    } else {
                                        FT_UInt fg = FT_Get_Char_Index(cFace, uni);
                                        if (fg > 0 && fg < 65535) g = static_cast<uint16_t>(fg);
                                    }
                                }
                                uint16_t mg = (g > 0 && g < 65535) ? g : blankGid;
                                writeU16(mapBytes, cid * 2, mg);
                                if (!d.hasKey("/W") && mg != blankGid) {
                                    int pw = cDonorWidthCache[mg];
                                    if (pw == -2) {
                                        pw = ftGlyphWidth1000(cFace, mg, cUpem, false);
                                        cDonorWidthCache[mg] = pw;
                                    }
                                    if (pw >= 0) cjkWmap[static_cast<int>(cid)] = pw;
                                }
                            }
                            cFaceGuard.reset();
                            QPDFObjectHandle newFf = ctx.internFontStream(dProg);
                            fd.replaceKey("/FontFile2", newFf);
                            std::string mapStr(reinterpret_cast<const char*>(mapBytes.data()),
                                               mapBytes.size());
                            d.replaceKey("/CIDToGIDMap",
                                         QPDFObjectHandle::newStream(&qpdf, mapStr));
                            if (!d.hasKey("/W")) {
                                d.replaceKey("/DW", QPDFObjectHandle::newInteger(1000));
                                d.replaceKey("/W", serializeWMap(cjkWmap));
                            }
                            if (!f.hasKey("/ToUnicode")) {
                                QPDFObjectHandle encObj2 = f.getKey("/Encoding");
                                QPDFObjectHandle encName2 =
                                    encObj2.isName() ? encObj2
                                                     : (encObj2.isStream()
                                                            ? encObj2.getDict().getKey("/CMapName")
                                                            : QPDFObjectHandle::newNull());
                                auto cidEnc2 = jpdfium::qpdf_detail::cidEncodingFromName(encName2);
                                const bool isPredef2 =
                                    (cidEnc2 != jpdfium::qpdf_detail::CidEncoding::TwoByte);
                                std::set<uint32_t> codeSetStorage;
                                if (isPredef2) {
                                    auto scan2 =
                                        jpdfium::qpdf_detail::usedCidsForFont(qpdf, f, cidEnc2);
                                    codeSetStorage = std::move(scan2.codes);
                                }
                                if (codeSetStorage.empty() && f.isIndirect()) {
                                    auto uit2 = usedFontCodes.find(f.getObjGen());
                                    if (uit2 != usedFontCodes.end()) codeSetStorage = uit2->second;
                                }
                                if (codeSetStorage.empty() && !isPredef2) {
                                    for (const auto& [wc, _] : origWmap) {
                                        if (wc >= 0 && wc <= 65535) {
                                            codeSetStorage.insert(static_cast<uint32_t>(wc));
                                        }
                                    }
                                }
                                std::map<uint32_t, uint32_t> c2u;
                                auto validU = [](uint32_t u) {
                                    return u != 0 && u != 0xFEFF && u != 0xFFFE && u != 0xFFFF &&
                                           u != 0xFFFD && !(u >= 0xE000 && u <= 0xF8FF) &&
                                           !(u >= 0xD800 && u <= 0xDFFF) && u <= 0x10FFFFU &&
                                           !(u >= 0xF0000 && u <= 0x10FFFF);
                                };
                                const bool prop2 =
                                    jpdfium::qpdf_detail::isRksjPropCidEncoding(cidEnc2);
                                const bool vert2 =
                                    jpdfium::qpdf_detail::isVerticalCidEncoding(cidEnc2);
                                const bool rksj2 = jpdfium::qpdf_detail::isRksjCidEncoding(cidEnc2);
                                const bool ksc2 =
                                    jpdfium::qpdf_detail::isKscEucCidEncoding(cidEnc2);
                                const bool gbe2 = jpdfium::qpdf_detail::isGbEucCidEncoding(cidEnc2);
                                for (uint32_t code : codeSetStorage) {
                                    uint32_t cid = code;
                                    uint32_t uni = 0;
                                    if (isPredef2) {
                                        cid = 0;
                                        if (rksj2) {
                                            jpdfium::qpdf_detail::japan1CidForCode(
                                                static_cast<uint16_t>(code), cid, prop2, vert2);
                                        } else if (ksc2) {
                                            jpdfium::qpdf_detail::korea1CidForCode(
                                                static_cast<uint16_t>(code), cid, vert2);
                                        } else if (gbe2) {
                                            jpdfium::qpdf_detail::gb1CidForCode(
                                                static_cast<uint16_t>(code), cid, vert2);
                                        }
                                    }
                                    if (cid >= 1 && cid <= 95) {
                                        uni = cid + 31;
                                    } else if (cid > 0) {
                                        jpdfium::qpdf_detail::adobeCollectionUnicodeForCid(
                                            cjkOrdering, cid, uni);
                                    }
                                    if (validU(uni)) c2u[code] = uni;
                                }
                                if (!c2u.empty()) {
                                    std::ostringstream cm2;
                                    cm2 << "/CIDInit /ProcSet findresource begin\n"
                                        << "12 dict begin\nbegincmap\n"
                                        << "/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) "
                                           "/Supplement 0 >> def\n"
                                        << "/CMapName /JPDFium-CJK-UCS def\n/CMapType 2 def\n";
                                    if (isPredef2) {
                                        cm2 << "2 begincodespacerange\n<00> <FF>\n"
                                            << "<0000> <FFFF>\nendcodespacerange\n";
                                    } else {
                                        cm2 << "1 begincodespacerange\n<0000> <FFFF>\n"
                                            << "endcodespacerange\n";
                                    }
                                    std::vector<std::pair<uint32_t, uint32_t>> b1;
                                    std::vector<std::pair<uint32_t, uint32_t>> b2;
                                    for (const auto& [code, uni] : c2u) {
                                        if (isPredef2 && code <= 0xFFU)
                                            b1.emplace_back(code, uni);
                                        else
                                            b2.emplace_back(code, uni);
                                    }
                                    auto writePairs =
                                        [&](const std::vector<std::pair<uint32_t, uint32_t>>& vp,
                                            bool twoHex) {
                                            char hb[48];
                                            for (size_t pi = 0; pi < vp.size(); pi += 100) {
                                                size_t cnt = std::min<size_t>(100, vp.size() - pi);
                                                cm2 << cnt << " beginbfchar\n";
                                                for (size_t pk = 0; pk < cnt; ++pk) {
                                                    uint32_t sc = vp[pi + pk].first;
                                                    uint32_t du = vp[pi + pk].second;
                                                    if (du <= 0xFFFFU) {
                                                        std::snprintf(hb, sizeof(hb),
                                                                      twoHex ? "<%02X> <%04X>\n"
                                                                             : "<%04X> <%04X>\n",
                                                                      sc, du);
                                                    } else {
                                                        uint32_t adj = du - 0x10000U;
                                                        std::snprintf(hb, sizeof(hb),
                                                                      twoHex
                                                                          ? "<%02X> <%04X%04X>\n"
                                                                          : "<%04X> <%04X%04X>\n",
                                                                      sc, 0xD800U + (adj >> 10),
                                                                      0xDC00U + (adj & 0x3FFU));
                                                    }
                                                    cm2 << hb;
                                                }
                                                cm2 << "endbfchar\n";
                                            }
                                        };
                                    writePairs(b1, true);
                                    writePairs(b2, false);
                                    cm2 << "endcmap\nCMapName currentdict /CMap defineresource "
                                           "pop\nend\nend\n";
                                    f.replaceKey("/ToUnicode",
                                                 QPDFObjectHandle::newStream(&qpdf, cm2.str()));
                                }
                            }
                            builtCjkMap = true;
                        }
                    }
                    if (!builtCjkMap) {
                        if (f.isIndirect()) {
                            ctx.donorSubstitutedType0.insert(f.getObjGen());
                        }
                        d.replaceKey("/CIDToGIDMap", QPDFObjectHandle::newName("/Identity"));
                        // Preserve existing /W and /DW if already present (Stage 4 sfntPatchHmtx
                        // will reconcile hmtx to match them, preserving exact layout & spacing!).
                        if (!d.hasKey("/W")) {
                            d.replaceKey("/DW", QPDFObjectHandle::newInteger(0));
                            std::map<int, int> gidWmap;
                            for (size_t gi = 0; gi < donor->gidWidths.size(); ++gi) {
                                gidWmap[static_cast<int>(gi)] = donor->gidWidths[gi];
                            }
                            d.replaceKey("/W", serializeWMap(gidWmap));
                        }
                        if (!d.hasKey("/CIDSystemInfo")) {
                            QPDFObjectHandle csi = QPDFObjectHandle::newDictionary();
                            csi.replaceKey("/Registry", QPDFObjectHandle::newString("Adobe"));
                            csi.replaceKey("/Ordering", QPDFObjectHandle::newString("Identity"));
                            csi.replaceKey("/Supplement", QPDFObjectHandle::newInteger(0));
                            d.replaceKey("/CIDSystemInfo", csi);
                        }
                    }
                }
                ++embeddedCount;
                isEmb = true;
            }
        }
    }

    // Stage 4 & 5: Reconcile CIDFont widths & repair GID-0 / CID-0 / OOB mappings
    if (isEmb && fd.isDictionary() &&
        ((flags & kFlagReconcileWidths) || (flags & kFlagRepairNotdef))) {
        QPDFObjectHandle ff =
            fd.hasKey("/FontFile2")
                ? fd.getKey("/FontFile2")
                : (fd.hasKey("/FontFile3") ? fd.getKey("/FontFile3") : QPDFObjectHandle::newNull());
        if (ff.isStream()) {
            try {
                std::vector<uint8_t> prog;
                if (readStreamBytesBounded(ff, prog, qpdf_dl_generalized) && !prog.empty() &&
                    isSafeFontBuffer(prog)) {
                    FT_Face rawFace = nullptr;
                    if (FT_New_Memory_Face(ftLib, prog.data(), static_cast<FT_Long>(prog.size()), 0,
                                           &rawFace) == 0 &&
                        rawFace) {
                        jpdfium::font::FtFaceGuard faceGuard(rawFace);
                        FT_Face face = faceGuard.face;
                        int upem = face->units_per_EM > 0 ? face->units_per_EM : 1000;
                        FT_UInt ng =
                            static_cast<FT_UInt>(std::clamp<FT_Long>(face->num_glyphs, 0, 65535));

                        // Parse CIDToGIDMap if stream (capped at 65536 2-byte entries = 131072
                        // bytes)
                        std::vector<uint16_t> cidToGid;
                        bool isIdentityMap = true;
                        QPDFObjectHandle c2g = d.getKey("/CIDToGIDMap");
                        if (c2g.isStream()) {
                            std::vector<uint8_t> mb;
                            if (readStreamBytesBounded(c2g, mb, qpdf_dl_generalized) &&
                                mb.size() >= 2) {
                                isIdentityMap = false;
                                size_t cnt = std::min<size_t>(mb.size() / 2, 65536U);
                                cidToGid.resize(cnt);
                                for (size_t ci = 0; ci < cnt; ++ci) {
                                    cidToGid[ci] = readU16(mb.data() + ci * 2);
                                }
                            }
                        }

                        QPDFObjectHandle fEncStage5 = f.getKey("/Encoding");
                        bool isIdentityEncStage5 =
                            fEncStage5.isName() && (fEncStage5.getName() == "/Identity-H" ||
                                                    fEncStage5.getName() == "/Identity-V");
                        qpdf_detail::CidEncoding predefinedEnc =
                            qpdf_detail::cidEncodingFromName(fEncStage5);
                        bool isPredefinedCjk =
                            predefinedEnc != qpdf_detail::CidEncoding::TwoByte &&
                            predefinedEnc != qpdf_detail::CidEncoding::SingleByte;
                        const std::set<uint32_t>* usedPtr = nullptr;
                        std::set<uint32_t> predefinedCids;
                        if (isIdentityEncStage5 && f.isIndirect()) {
                            auto uit = usedFontCodes.find(f.getObjGen());
                            if (uit != usedFontCodes.end()) usedPtr = &uit->second;
                        } else if (isPredefinedCjk && f.isIndirect()) {
                            // Predefined CJK CMaps address CIDs, but the content stream stores
                            // character codes.  Use the CJK-aware scanner so width filtering
                            // and notdef repair see real CIDs.
                            auto scan = qpdf_detail::usedCidsForFont(qpdf, f, predefinedEnc);
                            if (scan.complete && !scan.cids.empty()) {
                                predefinedCids = scan.cids;
                                usedPtr = &predefinedCids;
                            }
                        }
                        const std::set<uint32_t>* usedCidPtr = usedPtr;
                        if (!isIdentityEncStage5 && f.isIndirect()) {
                            auto cit = usedFontCids.find(f.getObjGen());
                            if (cit != usedFontCids.end() && !cit->second.empty()) {
                                usedCidPtr = &cit->second;
                            }
                        }

                        // Stage 5: Repair CID-0 and GID-0/OOB CIDs on CIDFontType2
                        if ((flags & kFlagRepairNotdef) && dsub == "/CIDFontType2") {
                            bool hasUsedCid0 = usedPtr && usedPtr->count(0) > 0;
                            bool anyBadCid = hasUsedCid0;
                            uint32_t maxCid = 0;
                            if (usedPtr) {
                                for (uint32_t cid : *usedPtr) {
                                    if (cid > 65535U) {
                                        anyBadCid = true;
                                        continue;
                                    }
                                    if (cid > maxCid) maxCid = cid;
                                    if (cid > 0) {
                                        uint32_t g =
                                            isIdentityMap
                                                ? cid
                                                : (cid < cidToGid.size() ? cidToGid[cid] : 0);
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
                                std::vector<uint8_t> patched = prog;
                                uint16_t blankGid = sfntAppendBlankGlyph(patched, 0);
                                if (blankGid > 0 && !patched.empty() && isSafeFontBuffer(patched)) {
                                    // Convert Identity map to explicit stream if needed
                                    if (isIdentityMap) {
                                        size_t mapLen = std::max<size_t>(ng, maxCid + 2);
                                        if (mapLen > 65535) mapLen = 65535;
                                        cidToGid.resize(mapLen);
                                        for (size_t ci = 0; ci < mapLen; ++ci) {
                                            cidToGid[ci] =
                                                (ci < ng) ? static_cast<uint16_t>(ci) : blankGid;
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
                                    // Remap CID 0 in content streams to an unused high blankCid
                                    // (0xF000+) so it never collides with existing ToUnicode
                                    // bfranges, and map it to space in /ToUnicode.
                                    if (hasUsedCid0 && f.isIndirect()) {
                                        uint32_t blankCid = 0xF000;
                                        for (uint32_t cand = std::max<uint32_t>(0xF000, maxCid + 1);
                                             cand < 0xFFFE; ++cand) {
                                            if (!usedPtr->count(cand)) {
                                                blankCid = cand;
                                                break;
                                            }
                                        }
                                        if (blankCid >= cidToGid.size()) {
                                            cidToGid.resize(blankCid + 1, blankGid);
                                        }
                                        cidToGid[blankCid] = blankGid;
                                        codeRemaps[f.getObjGen()][0] = blankCid;
                                        if (f.isIndirect()) {
                                            usedFontCodes[f.getObjGen()].insert(blankCid);
                                        }
                                        if (f.hasKey("/ToUnicode") &&
                                            f.getKey("/ToUnicode").isStream()) {
                                            try {
                                                QPDFObjectHandle tu = f.getKey("/ToUnicode");
                                                std::string tus;
                                                if (readStreamBounded(tu, tus, qpdf_dl_generalized,
                                                                      size_t{4} * 1024U * 1024U) &&
                                                    !tus.empty()) {
                                                    size_t ep = tus.rfind("endcmap");
                                                    if (ep != std::string::npos) {
                                                        char entry[96];
                                                        std::snprintf(entry, sizeof(entry),
                                                                      "\n1 beginbfchar\n<%04X> "
                                                                      "<0020>\nendbfchar\n",
                                                                      blankCid & 0xFFFF);
                                                        tus.insert(ep, entry);
                                                        QPDFObjectHandle newTu =
                                                            QPDFObjectHandle::newStream(&qpdf, tus);
                                                        f.replaceKey("/ToUnicode", newTu);
                                                    }
                                                }
                                            } catch (const std::exception& e) {
                                                std::fputs(e.what(), stderr);
                                                std::fputc('\n', stderr);
                                            } catch (...) {
                                                std::fputs(
                                                    "jpdfium: ToUnicode blankCid patch failed\n",
                                                    stderr);
                                            }
                                        }
                                    }
                                    // Close FT_Face before mutating prog!
                                    faceGuard.reset();
                                    face = nullptr;
                                    prog = std::move(patched);
                                    QPDFObjectHandle newFf = ctx.internFontStream(prog);
                                    fd.replaceKey("/FontFile2", newFf);
                                    std::vector<uint8_t> newMap(cidToGid.size() * 2);
                                    for (size_t ci = 0; ci < cidToGid.size(); ++ci) {
                                        writeU16(newMap, ci * 2, cidToGid[ci]);
                                    }
                                    std::string mapStr(reinterpret_cast<const char*>(newMap.data()),
                                                       newMap.size());
                                    QPDFObjectHandle newC2g =
                                        QPDFObjectHandle::newStream(&qpdf, mapStr);
                                    d.replaceKey("/CIDToGIDMap", newC2g);
                                    FT_Face reopened = nullptr;
                                    if (FT_New_Memory_Face(ftLib, prog.data(),
                                                           static_cast<FT_Long>(prog.size()), 0,
                                                           &reopened) == 0 &&
                                        reopened) {
                                        faceGuard.reset(reopened);
                                        face = reopened;
                                        ng = static_cast<FT_UInt>(
                                            std::clamp<FT_Long>(face->num_glyphs, 0, 65535));
                                    }
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
                                bool isOcrOrDummy =
                                    (ng <= 2 || lbf.find("glyphless") != std::string::npos);
                                if (isOcrOrDummy) {
                                    // Replace dummy/OCR CIDFontType0 with CIDFontType2 + blank
                                    // glyph (preserves ToUnicode & 0% pixel diff!)
                                    const DonorFont* donor =
                                        getCachedDonor(ftLib, donorDirs, "arial");
                                    if (donor) {
                                        std::vector<uint8_t> dProg = donor->raw;
                                        uint16_t advU =
                                            static_cast<uint16_t>(std::max(1, donor->upem / 2));
                                        uint16_t blankGid = sfntAppendBlankGlyph(dProg, advU);
                                        if (blankGid > 0 && !dProg.empty()) {
                                            uint32_t maxCid = 1;
                                            for (uint32_t c : *usedPtr) {
                                                if (c <= 65535U && c > maxCid) maxCid = c;
                                            }
                                            uint32_t blankCid = 0;
                                            if (usedPtr->count(0)) {
                                                blankCid = std::min<uint32_t>(
                                                    std::max<uint32_t>(256U, maxCid + 1U), 65530U);
                                                while (usedPtr->count(blankCid) &&
                                                       blankCid < 65534U)
                                                    ++blankCid;
                                                if (usedPtr->count(blankCid)) {
                                                    blankCid = 0;
                                                    for (uint32_t cand = 256U; cand < 65535U;
                                                         ++cand) {
                                                        if (!usedPtr->count(cand)) {
                                                            blankCid = cand;
                                                            break;
                                                        }
                                                    }
                                                }
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
                                                if (blankCid < mapSz) {
                                                    writeU16(mapBytes,
                                                             static_cast<size_t>(blankCid) * 2,
                                                             blankGid);
                                                }
                                                codeRemaps[f.getObjGen()][0] = blankCid;
                                                if (f.isIndirect()) {
                                                    usedFontCodes[f.getObjGen()].insert(blankCid);
                                                }
                                            }
                                            d.replaceKey("/Subtype", QPDFObjectHandle::newName(
                                                                         "/CIDFontType2"));
                                            dsub = "/CIDFontType2";
                                            if (fd.hasKey("/FontFile3")) fd.removeKey("/FontFile3");
                                            QPDFObjectHandle newFf = ctx.internFontStream(dProg);
                                            fd.replaceKey("/FontFile2", newFf);
                                            std::string mapStr(
                                                reinterpret_cast<const char*>(mapBytes.data()),
                                                mapBytes.size());
                                            d.replaceKey(
                                                "/CIDToGIDMap",
                                                QPDFObjectHandle::newStream(&qpdf, mapStr));
                                            d.replaceKey("/DW", QPDFObjectHandle::newInteger(500));
                                            if (d.hasKey("/W")) d.removeKey("/W");
                                            faceGuard.reset();
                                            face = nullptr;
                                            prog = std::move(dProg);
                                            FT_New_Memory_Face(ftLib, prog.data(),
                                                               static_cast<FT_Long>(prog.size()), 0,
                                                               &face);
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
                                        if (f.isIndirect()) {
                                            usedFontCodes[f.getObjGen()].insert(bestG);
                                        }
                                    }
                                    ++notdefFixedCount;
                                }
                            }
                        }

                        // Stage 4: Reconcile /W against FreeType advances.
                        // For CIDFontType2 (TrueType), prefer patching hmtx to match /W (preserves
                        // exact PDF layout & PDFium text extraction word spacing!); fall back to
                        // updating /W on collisions/CFF.
                        if ((flags & kFlagReconcileWidths) && face) {
                            bool isCff = (dsub == "/CIDFontType0");
                            // FreeType's CFF loader already normalizes FDArray FontMatrix
                            // advances into units_per_EM; applying cffFdScaleFactor here
                            // would scale twice.
                            double fdScale = 1.0;
                            int dw = d.hasKey("/DW") && d.getKey("/DW").isNumber()
                                         ? safeRoundCidWidth(d.getKey("/DW").getNumericValue())
                                         : 1000;
                            auto wmap =
                                d.hasKey("/W") ? parseWArray(d.getKey("/W")) : std::map<int, int>{};
                            bool wChanged = false;
                            std::map<uint16_t, uint16_t> hmtxPatch;
                            std::map<uint16_t, int> gidTargetW;
                            std::set<uint16_t> gidCollision;
                            if (!isCff && upem >= 500 && prog.size() <= size_t{4} * 1024U * 1024U) {
                                // Pre-check which GIDs have unique target widths in /W (only for
                                // used or mapped CIDs)
                                auto recordGidW = [&](int cid, FT_UInt gid) {
                                    if (gid == 0 || gid >= ng || gid >= 65535) return;
                                    auto it = wmap.find(cid);
                                    int dictW = (it != wmap.end()) ? it->second : dw;
                                    if (dictW < 0 || dictW > 10000) return;
                                    uint16_t g16 = static_cast<uint16_t>(gid);
                                    auto git = gidTargetW.find(g16);
                                    if (git == gidTargetW.end())
                                        gidTargetW[g16] = dictW;
                                    else if (git->second != dictW)
                                        gidCollision.insert(g16);
                                };
                                if (dsub == "/CIDFontType2" && !isIdentityMap &&
                                    !cidToGid.empty()) {
                                    if (usedCidPtr && !usedCidPtr->empty()) {
                                        for (uint32_t uc : *usedCidPtr) {
                                            if (uc < cidToGid.size()) {
                                                recordGidW(static_cast<int>(uc), cidToGid[uc]);
                                            }
                                        }
                                    } else {
                                        for (size_t ci = 0; ci < cidToGid.size(); ++ci)
                                            recordGidW(static_cast<int>(ci), cidToGid[ci]);
                                    }
                                } else if (ng <= 2048 || !usedCidPtr || usedCidPtr->empty()) {
                                    for (FT_UInt g = 0; g < std::min<FT_UInt>(ng, 65535); ++g)
                                        recordGidW(static_cast<int>(g), g);
                                } else {
                                    for (uint32_t uc : *usedCidPtr)
                                        if (uc < ng) recordGidW(static_cast<int>(uc), uc);
                                }
                            }
                            std::vector<double> gidWidthCache(
                                ng, std::numeric_limits<double>::quiet_NaN());
                            std::vector<uint8_t> gidWidthComputed(ng, 0);
                            auto getCachedGidWidth = [&](FT_UInt gid) -> double {
                                if (gid >= ng) return std::numeric_limits<double>::quiet_NaN();
                                if (!gidWidthComputed[gid]) {
                                    gidWidthCache[gid] =
                                        ftGlyphWidth1000Exact(face, gid, upem, isCff, fdScale);
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
                                    if (!isCff && prog.size() <= size_t{4} * 1024U * 1024U &&
                                        gid > 0 && gid < 65535 && dictW > 0 && dictW <= 10000 &&
                                        upem >= 500 &&
                                        !gidCollision.count(static_cast<uint16_t>(gid))) {
                                        long long advU = safeRoundCidWidth(
                                            static_cast<double>(dictW) * upem / 1000.0);
                                        if (advU >= 0 && advU <= 65535 &&
                                            std::abs(static_cast<double>(advU) * 1000.0 / upem -
                                                     dictW) <= 0.95) {
                                            hmtxPatch[static_cast<uint16_t>(gid)] =
                                                static_cast<uint16_t>(advU);
                                            return;
                                        }
                                    }
                                    wmap[cid] = safeRoundCidWidth(exactW);
                                    wChanged = true;
                                }
                            };
                            QPDFObjectHandle fEnc = f.getKey("/Encoding");
                            bool isIdentityEnc =
                                fEnc.isName() && (fEnc.getName() == "/Identity-H" ||
                                                  fEnc.getName() == "/Identity-V");
                            bool useSparseCheck =
                                ((ng > 4096 || (!isIdentityMap && cidToGid.size() > 4096)) &&
                                 ((isIdentityEnc && usedPtr && !usedPtr->empty()) ||
                                  (usedCidPtr && !usedCidPtr->empty())));
                            if (dsub == "/CIDFontType2" && !isIdentityMap && !cidToGid.empty()) {
                                if (useSparseCheck) {
                                    std::set<uint32_t> cidsToCheck(usedCidPtr->begin(),
                                                                   usedCidPtr->end());
                                    if (f.isIndirect()) {
                                        auto rit = codeRemaps.find(f.getObjGen());
                                        if (rit != codeRemaps.end()) {
                                            for (const auto& [origC, newC] : rit->second)
                                                cidsToCheck.insert(newC);
                                        }
                                    }
                                    for (const auto& [wc, _] : wmap) {
                                        if (wc >= 0 && static_cast<size_t>(wc) < cidToGid.size()) {
                                            cidsToCheck.insert(static_cast<uint32_t>(wc));
                                        }
                                    }
                                    for (uint32_t uc : cidsToCheck) {
                                        if (uc < cidToGid.size())
                                            checkCid(static_cast<int>(uc), cidToGid[uc]);
                                    }
                                } else {
                                    for (size_t ci = 0; ci < cidToGid.size(); ++ci) {
                                        checkCid(static_cast<int>(ci), cidToGid[ci]);
                                    }
                                }
                            } else if (useSparseCheck) {
                                std::set<uint32_t> cidsToCheck(usedCidPtr->begin(),
                                                               usedCidPtr->end());
                                if (f.isIndirect()) {
                                    auto rit = codeRemaps.find(f.getObjGen());
                                    if (rit != codeRemaps.end()) {
                                        for (const auto& [origC, newC] : rit->second)
                                            cidsToCheck.insert(newC);
                                    }
                                }
                                for (const auto& [wc, _] : wmap) {
                                    if (wc >= 0 && static_cast<uint32_t>(wc) < ng)
                                        cidsToCheck.insert(static_cast<uint32_t>(wc));
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
                                std::vector<uint8_t> patched = prog;
                                if (sfntPatchHmtx(patched, hmtxPatch) && !patched.empty() &&
                                    isSafeFontBuffer(patched)) {
                                    faceGuard.reset();
                                    face = nullptr;
                                    prog = std::move(patched);
                                    QPDFObjectHandle newFf = ctx.internFontStream(prog);
                                    // Clone FontDescriptor so shared descriptors never collide
                                    QPDFObjectHandle newFd =
                                        qpdf.makeIndirectObject(fd.shallowCopy());
                                    newFd.replaceKey("/FontFile2", newFf);
                                    d.replaceKey("/FontDescriptor", newFd);
                                    fd = std::move(newFd);
                                    ++widthsFixedCount;
                                    FT_New_Memory_Face(ftLib, prog.data(),
                                                       static_cast<FT_Long>(prog.size()), 0, &face);
                                    if (face)
                                        ng = static_cast<FT_UInt>(
                                            std::max<FT_Long>(0, face->num_glyphs));
                                } else {
                                    // Fallback: update /W for any patched entries
                                    for (const auto& [g16, advU] : hmtxPatch) {
                                        double exactW =
                                            ftGlyphWidth1000Exact(face, g16, upem, false, 1.0);
                                        if (exactW >= 0.0 && std::isfinite(exactW)) {
                                            wmap[g16] = safeRoundCidWidth(exactW);
                                            wChanged = true;
                                        }
                                    }
                                }
                            }
                            // PDF/A-1 limits every array to 8191 elements.  A /W array
                            // carrying an entry for every CID in a large subset can exceed
                            // that even though only a small set of CIDs is actually used.
                            // Keep the used CIDs; unused widths have no rendering effect.
                            auto hasOversizedWArray = [](QPDFObjectHandle const& w) -> bool {
                                if (!w.isArray()) return false;
                                const int wn = w.getArrayNItems();
                                if (wn > 8191) return true;
                                for (int wi = 0; wi < wn; ++wi) {
                                    QPDFObjectHandle item = w.getArrayItem(wi);
                                    if (item.isArray() && item.getArrayNItems() > 8191) return true;
                                }
                                return false;
                            };
                            const bool wTooLarge = hasOversizedWArray(d.getKey("/W"));
                            if ((flags & kFlagConvertPdfA1b) && usedPtr && !usedPtr->empty() &&
                                d.hasKey("/W") && d.getKey("/W").isArray() &&
                                (wTooLarge || wmap.size() > 8191)) {
                                std::map<int, int> usedW;
                                for (uint32_t cid : *usedPtr) {
                                    auto wit = wmap.find(static_cast<int>(cid));
                                    if (wit != wmap.end()) usedW[wit->first] = wit->second;
                                }
                                if (!usedW.empty() && usedW.size() < wmap.size()) {
                                    wmap = std::move(usedW);
                                    wChanged = true;
                                }
                            }
                            if (wChanged || wTooLarge) {
                                d.replaceKey("/W", serializeWMap(wmap));
                                ++widthsFixedCount;
                            }
                            // PDF/A-1b (ISO 19005-1 6.3.5-3) requires a complete /CIDSet stream on
                            // CIDFont subsets.
                            if ((flags & kFlagConvertPdfA1b) && fd.isDictionary()) {
                                size_t maxCid = 0;
                                std::vector<uint32_t> presentCids;
                                presentCids.push_back(0);
                                if (dsub == "/CIDFontType2" && !isIdentityMap &&
                                    !cidToGid.empty()) {
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
                                    bits[c / 8] =
                                        static_cast<char>(static_cast<unsigned char>(bits[c / 8]) |
                                                          (0x80U >> (c % 8)));
                                }
                                fd.replaceKey("/CIDSet", QPDFObjectHandle::newStream(&qpdf, bits));
                                ++metaFixedCount;
                            }
                        }
                    }
                }
            } catch (const std::exception& e) {
                std::fputs(e.what(), stderr);
                std::fputc('\n', stderr);
            } catch (...) {
                std::fputs("jpdfium: rewriteType0Font failed\n", stderr);
            }
        }
    }

    // PDF/A requires every CMap other than Identity-H/Identity-V to be embedded
    // (ISO 19005-1 §6.3.3.3).  Replacing a predefined CJK encoding name with a stream
    // that maps only the used codes keeps the stream compact; otherwise (PDF/A-1b with
    // unembedded font or empty scan) fall back to the full predefined CMap ranges.
    const uint32_t pdfaFlags = kFlagConvertPdfA1b | kFlagConvertPdfA2b | kFlagConvertPdfA3b;
    if ((flags & pdfaFlags) && f.getKey("/Encoding").isName()) {
        QPDFObjectHandle encHandle = f.getKey("/Encoding");
        const std::string ename = encHandle.getName();
        if (ename != "/Identity-H" && ename != "/Identity-V") {
            using jpdfium::qpdf_detail::CidEncoding;
            CidEncoding ce = jpdfium::qpdf_detail::cidEncodingFromName(encHandle);
            const bool isRksj = jpdfium::qpdf_detail::isRksjCidEncoding(ce);
            const bool isKsc = jpdfium::qpdf_detail::isKscEucCidEncoding(ce);
            const bool isGb = jpdfium::qpdf_detail::isGbEucCidEncoding(ce);
            if (isRksj || isKsc || isGb) {
                bool embeddedUsedCmap = false;
                if (isEmb) {
                    auto scan = qpdf_detail::usedCidsForFont(qpdf, f, ce);
                    if (scan.complete && !scan.codes.empty()) {
                        std::string cmapName = ename;
                        if (!cmapName.empty() && cmapName[0] == '/') cmapName.erase(0, 1);
                        std::string ordering = isRksj ? "Japan1" : (isKsc ? "Korea1" : "GB1");
                        int supplement = 0;
                        QPDFObjectHandle csi = d.getKey("/CIDSystemInfo");
                        if (csi.isDictionary()) {
                            QPDFObjectHandle ord = csi.getKey("/Ordering");
                            if (ord.isString() && !ord.getStringValue().empty()) {
                                ordering = ord.getStringValue();
                            } else if (ord.isName() && ord.getName().size() > 1) {
                                ordering = ord.getName().substr(1);
                            }
                            QPDFObjectHandle supp = csi.getKey("/Supplement");
                            if (supp.isInteger()) supplement = static_cast<int>(supp.getIntValue());
                        }
                        const bool vertical = qpdf_detail::isVerticalCidEncoding(ce);
                        std::string cmapText = buildUsedCodeCMap(cmapName, ordering, supplement,
                                                                 vertical, ce, scan.codes);
                        QPDFObjectHandle cmapStream = QPDFObjectHandle::newStream(&qpdf, cmapText);
                        QPDFObjectHandle cmapDict = cmapStream.getDict();
                        cmapDict.replaceKey("/Type", QPDFObjectHandle::newName("/CMap"));
                        cmapDict.replaceKey("/CMapName", QPDFObjectHandle::newName("/" + cmapName));
                        QPDFObjectHandle csiOut = QPDFObjectHandle::newDictionary();
                        csiOut.replaceKey("/Registry", QPDFObjectHandle::newString("Adobe"));
                        csiOut.replaceKey("/Ordering", QPDFObjectHandle::newString(ordering));
                        csiOut.replaceKey("/Supplement", QPDFObjectHandle::newInteger(supplement));
                        cmapDict.replaceKey("/CIDSystemInfo", csiOut);
                        cmapDict.replaceKey("/WMode",
                                            QPDFObjectHandle::newInteger(vertical ? 1 : 0));
                        f.replaceKey("/Encoding", cmapStream);
                        if (!d.hasKey("/CIDSystemInfo")) {
                            d.replaceKey("/CIDSystemInfo", csiOut.shallowCopy());
                        }
                        ++cmapFixedCount;
                        embeddedUsedCmap = true;
                    }
                }
                if (!embeddedUsedCmap && (flags & kFlagConvertPdfA1b)) {
                    const bool prop = jpdfium::qpdf_detail::isRksjPropCidEncoding(ce);
                    const bool vert = jpdfium::qpdf_detail::isVerticalCidEncoding(ce);
                    const char* ordering = isRksj ? "Japan1" : (isKsc ? "Korea1" : "GB1");

                    struct CidRange {
                        uint16_t lo;
                        uint16_t hi;
                        uint32_t cid;
                        int bytes;
                    };
                    std::vector<CidRange> ranges;
                    auto lookupCode = [&](uint16_t code, uint32_t& cidOut) -> bool {
                        if (isRksj)
                            return jpdfium::qpdf_detail::japan1CidForCode(code, cidOut, prop, vert);
                        if (isKsc)
                            return jpdfium::qpdf_detail::korea1CidForCode(code, cidOut, vert);
                        return jpdfium::qpdf_detail::gb1CidForCode(code, cidOut, vert);
                    };
                    auto scanSpan = [&](uint16_t startCode, uint16_t endCode, int bytes) {
                        bool inRun = false;
                        uint16_t runLo = 0;
                        uint16_t runHi = 0;
                        uint32_t runCid = 0;
                        for (uint32_t c = startCode; c <= endCode; ++c) {
                            uint32_t cid = 0;
                            const uint16_t code = static_cast<uint16_t>(c);
                            if (lookupCode(code, cid)) {
                                if (inRun && code == static_cast<uint16_t>(runHi + 1) &&
                                    cid == runCid + static_cast<uint32_t>(runHi - runLo) + 1U &&
                                    (bytes == 1 || (code >> 8) == (runHi >> 8))) {
                                    runHi = code;
                                } else {
                                    if (inRun) ranges.push_back({runLo, runHi, runCid, bytes});
                                    inRun = true;
                                    runLo = runHi = code;
                                    runCid = cid;
                                }
                            } else if (inRun) {
                                ranges.push_back({runLo, runHi, runCid, bytes});
                                inRun = false;
                            }
                        }
                        if (inRun) ranges.push_back({runLo, runHi, runCid, bytes});
                    };

                    if (isRksj) {
                        scanSpan(0x00, 0x80, 1);
                        scanSpan(0xA0, 0xDF, 1);
                        scanSpan(0x8140, 0x9FFC, 2);
                        scanSpan(0xE040, 0xFCFC, 2);
                    } else {
                        scanSpan(0x00, 0x7F, 1);
                        scanSpan(0x8080, 0xFFFF, 2);
                    }

                    if (!ranges.empty()) {
                        const std::string cmapName = ename.substr(1);
                        int supp = 0;
                        QPDFObjectHandle dCsi = d.getKey("/CIDSystemInfo");
                        if (dCsi.isDictionary() && dCsi.getKey("/Supplement").isInteger()) {
                            supp = static_cast<int>(dCsi.getKey("/Supplement").getIntValue());
                        }
                        std::ostringstream cm;
                        cm << "/CIDInit /ProcSet findresource begin\n"
                           << "12 dict begin\n"
                           << "begincmap\n"
                           << "/CIDSystemInfo 3 dict dup begin\n"
                           << "  /Registry (Adobe) def\n"
                           << "  /Ordering (" << ordering << ") def\n"
                           << "  /Supplement " << supp << " def\n"
                           << "end def\n"
                           << "/CMapName /" << cmapName << " def\n"
                           << "/CMapVersion 1.0 def\n"
                           << "/CMapType 1 def\n"
                           << "/WMode " << (vert ? 1 : 0) << " def\n";
                        if (isRksj) {
                            cm << "4 begincodespacerange\n"
                               << "<00> <80>\n<8140> <9FFC>\n<A0> <DF>\n<E040> <FCFC>\n"
                               << "endcodespacerange\n";
                        } else {
                            cm << "2 begincodespacerange\n"
                               << "<00> <7F>\n<8080> <FFFF>\n"
                               << "endcodespacerange\n";
                        }
                        char hexBuf[64];
                        for (size_t i = 0; i < ranges.size(); i += 100) {
                            const size_t end = std::min(ranges.size(), i + 100);
                            cm << (end - i) << " begincidrange\n";
                            for (size_t k = i; k < end; ++k) {
                                const auto& rg = ranges[k];
                                if (rg.bytes == 1) {
                                    std::snprintf(hexBuf, sizeof(hexBuf), "<%02X> <%02X> %u\n",
                                                  rg.lo, rg.hi, rg.cid);
                                } else {
                                    std::snprintf(hexBuf, sizeof(hexBuf), "<%04X> <%04X> %u\n",
                                                  rg.lo, rg.hi, rg.cid);
                                }
                                cm << hexBuf;
                            }
                            cm << "endcidrange\n";
                        }
                        cm << "endcmap\n"
                           << "CMapName currentdict /CMap defineresource pop\n"
                           << "end\nend\n";

                        QPDFObjectHandle cmapStream = QPDFObjectHandle::newStream(&qpdf, cm.str());
                        QPDFObjectHandle cd = cmapStream.getDict();
                        cd.replaceKey("/Type", QPDFObjectHandle::newName("/CMap"));
                        cd.replaceKey("/CMapName", QPDFObjectHandle::newName(ename));
                        cd.replaceKey("/WMode", QPDFObjectHandle::newInteger(vert ? 1 : 0));
                        QPDFObjectHandle csi = QPDFObjectHandle::newDictionary();
                        csi.replaceKey("/Registry", QPDFObjectHandle::newString("Adobe"));
                        csi.replaceKey("/Ordering", QPDFObjectHandle::newString(ordering));
                        csi.replaceKey("/Supplement", QPDFObjectHandle::newInteger(supp));
                        cd.replaceKey("/CIDSystemInfo", csi);
                        f.replaceKey("/Encoding", cmapStream);
                        ++cmapFixedCount;
                    }
                }
            }
        }
    }
}

}  // namespace jpdfium::rewriter
