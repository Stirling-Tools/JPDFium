// jpdfium_rewriter_fonts.cpp - Recursive font enumeration, simple GID resolution,
// /W array codec, and CIDFont and simple font conformance rewrite stages.

#include "jpdfium_font_rewriter_internal.h"

#include <qpdf/QPDFPageDocumentHelper.hh>

#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <sstream>

namespace jpdfium::rewriter {

// ---- Recursive font enumeration (Stage 1) ----

void collectFontsFromResources(QPDFObjectHandle res, std::set<QPDFObjGen>& visitedRes,
                               std::vector<QPDFObjectHandle>& fonts,
                               std::set<QPDFObjGen>& seenFonts, int depth = 0) {
    if (!res.isDictionary() || depth > 16) return;
    if (res.isIndirect()) {
        if (!visitedRes.insert(res.getObjGen()).second) return;
    }
    QPDFObjectHandle fontDict = res.getKey("/Font");
    if (fontDict.isDictionary()) {
        for (const auto& k : fontDict.getKeys()) {
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
            QPDFObjectHandle xo = xobjs.getKey(k);
            if (xo.isStream()) {
                if (xo.isIndirect() && !visitedRes.insert(xo.getObjGen()).second) continue;
                QPDFObjectHandle xd = xo.getDict();
                if (xd.getKey("/Subtype").isNameAndEquals("/Form")) {
                    collectFontsFromResources(xd.getKey("/Resources"), visitedRes, fonts, seenFonts, depth + 1);
                }
            }
        }
    }
    QPDFObjectHandle pats = res.getKey("/Pattern");
    if (pats.isDictionary()) {
        for (const auto& k : pats.getKeys()) {
            QPDFObjectHandle pat = pats.getKey(k);
            if (pat.isStream()) {
                if (pat.isIndirect() && !visitedRes.insert(pat.getObjGen()).second) continue;
                collectFontsFromResources(pat.getDict().getKey("/Resources"), visitedRes, fonts, seenFonts, depth + 1);
            }
        }
    }
}

std::vector<QPDFObjectHandle> enumerateAllFonts(QPDF& qpdf) {
    std::vector<QPDFObjectHandle> fonts;
    std::set<QPDFObjGen> seenFonts;
    std::set<QPDFObjGen> visitedRes;

    QPDFPageDocumentHelper pdh(qpdf);
    for (auto& page : pdh.getAllPages()) {
        QPDFObjectHandle po = page.getObjectHandle();
        collectFontsFromResources(po.getKey("/Resources"), visitedRes, fonts, seenFonts);
        QPDFObjectHandle annots = po.getKey("/Annots");
        if (annots.isArray()) {
            for (int i = 0; i < annots.getArrayNItems(); ++i) {
                QPDFObjectHandle a = annots.getArrayItem(i);
                if (!a.isDictionary()) continue;
                QPDFObjectHandle ap = a.getKey("/AP");
                if (!ap.isDictionary()) continue;
                for (const char* apk : {"/N", "/R", "/D"}) {
                    QPDFObjectHandle st = ap.getKey(apk);
                    if (st.isStream()) {
                        collectFontsFromResources(st.getDict().getKey("/Resources"), visitedRes, fonts, seenFonts);
                    } else if (st.isDictionary()) {
                        for (const auto& sk : st.getKeys()) {
                            QPDFObjectHandle sub = st.getKey(sk);
                            if (sub.isStream()) {
                                collectFontsFromResources(sub.getDict().getKey("/Resources"), visitedRes, fonts, seenFonts);
                            }
                        }
                    }
                }
            }
        }
    }
    QPDFObjectHandle root = qpdf.getRoot();
    QPDFObjectHandle acro = root.getKey("/AcroForm");
    if (acro.isDictionary()) {
        collectFontsFromResources(acro.getKey("/DR"), visitedRes, fonts, seenFonts);
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
                         const std::string& baseEnc,
                         const std::map<int, std::string>& diffNames) {
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
            if (FT_Get_Glyph_Name(face, g, gname, sizeof(gname)) == 0 && std::strcmp(gname, ".notdef") == 0) {
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
                if (face->charmaps[ci]->encoding == FT_ENCODING_ADOBE_CUSTOM) { hasCustom = true; break; }
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
        // ISO 32000-1 Annex D.2 Note 4: unused WinAnsi codes > 40 (127, 129, 141, 143, 144, 157) map to bullet (U+2022)
        if (code == 127 || code == 129 || code == 141 || code == 143 || code == 144 || code == 157) {
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

std::map<int, int> parseWArray(QPDFObjectHandle wArr) {
    constexpr size_t kMaxWEntries = 65536;
    std::map<int, int> wmap;
    if (!wArr.isArray()) return wmap;
    int n = wArr.getArrayNItems();
    int i = 0;
    while (i + 1 < n && wmap.size() < kMaxWEntries) {
        QPDFObjectHandle c1o = wArr.getArrayItem(i);
        if (!c1o.isInteger()) {
            ++i;
            continue;
        }
        long long c1 = c1o.getIntValue();
        QPDFObjectHandle nxt = wArr.getArrayItem(i + 1);
        if (nxt.isArray()) {
            int m = nxt.getArrayNItems();
            for (int j = 0; j < m && wmap.size() < kMaxWEntries; ++j) {
                long long cid = c1 + j;
                if (cid < 0 || cid > 65535) continue;
                QPDFObjectHandle v = nxt.getArrayItem(j);
                if (v.isNumber()) {
                    wmap[static_cast<int>(cid)] = static_cast<int>(std::llround(v.getNumericValue()));
                }
            }
            i += 2;
        } else if (nxt.isInteger() && i + 2 < n && wArr.getArrayItem(i + 2).isNumber()) {
            long long c2 = nxt.getIntValue();
            int wv = static_cast<int>(std::llround(wArr.getArrayItem(i + 2).getNumericValue()));
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
    QPDFObjectHandle arr = QPDFObjectHandle::newArray();
    auto it = wmap.begin();
    while (it != wmap.end()) {
        int start = it->first;
        QPDFObjectHandle sub = QPDFObjectHandle::newArray();
        sub.appendItem(QPDFObjectHandle::newInteger(it->second));
        int prev = start;
        ++it;
        while (it != wmap.end() && it->first == prev + 1) {
            sub.appendItem(QPDFObjectHandle::newInteger(it->second));
            prev = it->first;
            ++it;
        }
        arr.appendItem(QPDFObjectHandle::newInteger(start));
        arr.appendItem(sub);
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
            try {
                auto buf = st.getStreamData();
                if (buf && buf->getSize() >= 12) ok = true;
            } catch (...) {
                ok = false;
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

QPDFObjectHandle FontRewriteContext::getDonorStream(const std::string& key, bool isCff) {
    std::string ck = key + (isCff ? "_cff" : "_ttf");
    auto it = embeddedDonorStreams.find(ck);
    if (it != embeddedDonorStreams.end()) return it->second;
    const DonorFont* d = getCachedDonor(ftLib, donorDirs, key);
    if (!d) return QPDFObjectHandle::newNull();
    const auto& bytes = isCff ? d->cff : d->raw;
    if (bytes.empty()) return QPDFObjectHandle::newNull();
    std::string s(reinterpret_cast<const char*>(bytes.data()), bytes.size());
    QPDFObjectHandle st = QPDFObjectHandle::newStream(&qpdf, s);
    if (isCff) {
        st.getDict().replaceKey("/Subtype", QPDFObjectHandle::newName("/CIDFontType0C"));
    } else {
        st.getDict().replaceKey("/Length1", QPDFObjectHandle::newInteger(static_cast<long long>(bytes.size())));
    }
    embeddedDonorStreams[ck] = st;
    return st;
}

void rewriteDocumentFonts(QPDF& qpdf, FT_Library ftLib, uint32_t flags, const char* donorDirs,
                          FontRewriteStats& stats, std::vector<std::string>& transforms) {
    // Materialize inherited /Resources onto every page before scanning or rewriting.
    try {
        QPDFPageDocumentHelper(qpdf).pushInheritedAttributesToPage();
    } catch (...) {
    }

    FontRewriteContext ctx{qpdf, ftLib, flags, donorDirs, {}, {}, {}, {}, 0, 0, 0, 0, 0};
    auto fonts = enumerateAllFonts(qpdf);

    // Stage 1b: Collect exact used character codes / CIDs per indirect font from all content streams
    // (pages after pushInheritedAttributesToPage, Form XObjects, Patterns, and Annotation /AP streams),
    // augmented by QPDF's parseContentStream usage index for Type3 / #xx-escaped font names.
    if (flags & kFlagRepairNotdef) {
        collectAllUsedFontCodes(qpdf, ctx.usedFontCodes, ctx.type0FontGens);
        try {
            auto idx = jpdfium::qpdf_detail::buildFontUsageIndexes(qpdf);
            for (const auto& [og, res] : idx.simple.byFont) {
                auto& dst = ctx.usedFontCodes[og];
                dst.insert(res.codes.begin(), res.codes.end());
            }
        } catch (...) {
        }
    }

    for (auto& f : fonts) {
        if (!f.isDictionary()) continue;
        std::string sub = f.getKey("/Subtype").isName() ? f.getKey("/Subtype").getName() : "";
        std::string bf = f.getKey("/BaseFont").isName() ? f.getKey("/BaseFont").getName() : "";

        // Stage 6: Basic font dictionary metadata cleanup
        if (flags & kFlagCleanMetadata) {
            // Sanitize non-UTF-8 /BaseFont and /FontName entries across all font/descriptor dicts.
            int utf8FontIdx = 0;
            for (auto& obj : qpdf.getAllObjects()) {
                if (!obj.isDictionary()) continue;
                for (const char* nk : {"/BaseFont", "/FontName"}) {
                    if (obj.hasKey(nk) && obj.getKey(nk).isName() && !isValidUtf8(obj.getKey(nk).getName())) {
                        obj.replaceKey(nk, QPDFObjectHandle::newName("/Utf8Font_" + std::to_string(utf8FontIdx++)));
                        ++ctx.metaFixedCount;
                    }
                }
            }
        }
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

    if (ctx.embeddedCount > 0) transforms.push_back("embed_missing:" + std::to_string(ctx.embeddedCount));
    if (ctx.widthsFixedCount > 0) transforms.push_back("reconcile_widths:" + std::to_string(ctx.widthsFixedCount));
    if (ctx.cmapFixedCount > 0) transforms.push_back("normalize_cmaps:" + std::to_string(ctx.cmapFixedCount));
    if (ctx.notdefFixedCount > 0) transforms.push_back("repair_notdef:" + std::to_string(ctx.notdefFixedCount));
    if (ctx.metaFixedCount > 0) transforms.push_back("clean_metadata:" + std::to_string(ctx.metaFixedCount));

    stats.fontsAudited = static_cast<int>(fonts.size());
    stats.embeddedCount = ctx.embeddedCount;
    stats.widthsFixedCount = ctx.widthsFixedCount;
    stats.cmapFixedCount = ctx.cmapFixedCount;
    stats.notdefFixedCount = ctx.notdefFixedCount;
    stats.metaFixedCount = ctx.metaFixedCount;
    stats.unembeddedRemaining = unembeddedRemaining;
}

}  // namespace jpdfium::rewriter
