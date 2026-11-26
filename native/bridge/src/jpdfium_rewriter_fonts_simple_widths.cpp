// jpdfium_rewriter_fonts_simple_widths.cpp - Simple font /Widths reconciliation,
// .notdef repair, symbolic (3,0) /Differences conversion, and PDF/A-1b /CharSet synthesis.

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <map>
#include <optional>

#include "jpdfium_font_rewriter_internal.h"

namespace jpdfium::rewriter {

namespace {

int safeRoundFontInt(double v) {
    if (!std::isfinite(v)) return 0;
    const double clamped = std::clamp(v, -1000000.0, 1000000.0);
    return static_cast<int>(std::llround(clamped));
}

}  // namespace

void repairType3Font(FontRewriteContext& ctx, QPDFObjectHandle& f) {
    if (!f.isIndirect()) return;
    auto uit = ctx.usedFontCodes.find(f.getObjGen());
    if (uit == ctx.usedFontCodes.end() || uit->second.empty()) return;

    QPDF& qpdf = ctx.qpdf;
    QPDFObjectHandle charProcs = f.getKey("/CharProcs");
    if (!charProcs.isDictionary()) {
        charProcs = QPDFObjectHandle::newDictionary();
        f.replaceKey("/CharProcs", charProcs);
    }

    std::map<int, std::string> names;
    QPDFObjectHandle encoding = f.getKey("/Encoding");
    std::string baseEncoding;
    auto parseDifferences = [&](const QPDFObjectHandle& diffs) {
        if (!diffs.isArray()) return;
        long long code = 0;
        const int diffsN = std::min(diffs.getArrayNItems(), 4096);
        for (int i = 0; i < diffsN; ++i) {
            QPDFObjectHandle it = diffs.getArrayItem(i);
            if (it.isInteger()) {
                code = it.getIntValue();
            } else if (it.isName()) {
                if (code >= 0 && code <= 255) {
                    names[static_cast<int>(code)] = it.getName().substr(1);
                }
                if (code <= 255) ++code;
            }
        }
    };
    if (encoding.isName()) {
        baseEncoding = encoding.getName();
    } else if (encoding.isDictionary()) {
        if (encoding.getKey("/BaseEncoding").isName()) {
            baseEncoding = encoding.getKey("/BaseEncoding").getName();
        }
        parseDifferences(encoding.getKey("/Differences"));
    }

    bool changed = false;
    std::set<int> touched;
    for (uint32_t rawCode : uit->second) {
        if (rawCode > 255) continue;
        int code = static_cast<int>(rawCode);
        std::string name;
        auto nit = names.find(code);
        if (nit != names.end()) {
            name = nit->second;
        } else if (code == 32) {
            // The space code has a well-defined name and can always be repaired.
            name = "space";
            names[code] = name;
            changed = true;
        } else {
            // Without an explicit /Differences entry there is no reliable way
            // to name the missing Type 3 glyph; leave it untouched.
            continue;
        }
        touched.insert(code);
        if (name.empty()) continue;
        std::string unique = name;
        int suffix = 1;
        while (charProcs.hasKey("/" + unique)) {
            if (names[code] == unique) break;
            unique = name + "_" + std::to_string(suffix++);
        }
        if (!charProcs.hasKey("/" + unique)) {
            // A Type 3 glyph procedure needs a d0/d1 width declaration.  A
            // zero-advance blank preserves the existing text positions.
            charProcs.replaceKey("/" + unique, QPDFObjectHandle::newStream(&qpdf, "0 0 d0\n"));
            changed = true;
        }
        if (names[code] != unique) {
            names[code] = unique;
            changed = true;
        }
    }
    if (changed) {
        QPDFObjectHandle enc =
            encoding.isDictionary() ? encoding : QPDFObjectHandle::newDictionary();
        enc.replaceKey("/Type", QPDFObjectHandle::newName("/Encoding"));
        if (!baseEncoding.empty()) {
            enc.replaceKey("/BaseEncoding", QPDFObjectHandle::newName(baseEncoding));
        }
        QPDFObjectHandle diffs = QPDFObjectHandle::newArray();
        int previous = -2;
        for (const auto& [code, name] : names) {
            if (code != previous + 1) diffs.appendItem(QPDFObjectHandle::newInteger(code));
            diffs.appendItem(QPDFObjectHandle::newName("/" + name));
            previous = code;
        }
        enc.replaceKey("/Differences", diffs);
        f.replaceKey("/Encoding", enc);
        ++ctx.notdefFixedCount;
    }

    // PDF/A-1 6.3.6 requires /Widths to match the advance declared by each Type 3
    // CharProc's d0/d1 operator.  Producers frequently leave them out of sync.
    auto parseCharProcWidth = [&](const QPDFObjectHandle& cp) -> std::optional<double> {
        std::string sv;
        if (!readStreamBounded(cp, sv, qpdf_dl_generalized, size_t{65536}) || sv.empty()) {
            return std::nullopt;
        }
        std::vector<double> numbers;
        for (size_t i = 0; i < sv.size();) {
            unsigned char c = static_cast<unsigned char>(sv[i]);
            if (std::isspace(c)) {
                ++i;
                continue;
            }
            if (c == '%') {
                while (i < sv.size() && sv[i] != '\n' && sv[i] != '\r') ++i;
                continue;
            }
            if (c == '-' || c == '+' || c == '.' || std::isdigit(static_cast<unsigned char>(c))) {
                size_t start = i++;
                while (i < sv.size()) {
                    unsigned char d = static_cast<unsigned char>(sv[i]);
                    if (std::isdigit(d) || d == '.' || d == 'e' || d == 'E' || d == '+' ||
                        d == '-') {
                        ++i;
                    } else {
                        break;
                    }
                }
                std::string token(sv.substr(start, i - start));
                char* end = nullptr;
                double value = std::strtod(token.c_str(), &end);
                if (end && *end == '\0' && std::isfinite(value)) numbers.push_back(value);
                continue;
            }
            if (std::isalpha(c)) {
                size_t start = i++;
                while (i < sv.size() &&
                       (std::isalnum(static_cast<unsigned char>(sv[i])) || sv[i] == '*')) {
                    ++i;
                }
                std::string_view token(sv.data() + start, i - start);
                if (token == "d0" || token == "d1") {
                    if (numbers.size() >= 2) return numbers[0];
                    return std::nullopt;
                }
                continue;
            }
            ++i;
        }
        return std::nullopt;
    };

    std::map<int, double> procWidths;
    for (const auto& [code, name] : names) {
        if (name.empty() || !charProcs.hasKey("/" + name)) continue;
        if (auto w = parseCharProcWidth(charProcs.getKey("/" + name))) {
            procWidths[code] = *w;
        }
    }
    if (procWidths.empty()) return;

    long long rawFirst = f.hasKey("/FirstChar") && f.getKey("/FirstChar").isInteger()
                             ? f.getKey("/FirstChar").getIntValue()
                             : 0;
    QPDFObjectHandle oldWidths = f.getKey("/Widths");
    int oldLen = oldWidths.isArray() ? oldWidths.getArrayNItems() : 0;
    int first = static_cast<int>(std::clamp<long long>(rawFirst, 0, 255));
    int last = std::clamp(first + std::max(oldLen, 1) - 1, 0, 255);
    for (const auto& [code, w] : procWidths) {
        (void)w;
        first = std::min(first, code);
        last = std::max(last, code);
    }
    for (int code : touched) {
        first = std::min(first, code);
        last = std::max(last, code);
    }
    std::vector<double> widths(static_cast<size_t>(last - first + 1), 0.0);
    for (int i = 0; i < oldLen; ++i) {
        long long code = rawFirst + i;
        if (code >= first && code <= last && oldWidths.getArrayItem(i).isNumber()) {
            widths[static_cast<size_t>(code - first)] = oldWidths.getArrayItem(i).getNumericValue();
        }
    }
    bool widthChanged = static_cast<int>(rawFirst) != first || oldLen != last - first + 1;
    for (const auto& [code, width] : procWidths) {
        double& slot = widths[static_cast<size_t>(code - first)];
        if (std::abs(slot - width) > 1e-6) widthChanged = true;
        slot = width;
    }
    if (!widthChanged) return;

    QPDFObjectHandle newWidths = QPDFObjectHandle::newArray();
    for (double w : widths) {
        if (std::abs(w - std::round(w)) < 1e-9) {
            newWidths.appendItem(QPDFObjectHandle::newInteger(safeRoundFontInt(w)));
        } else {
            newWidths.appendItem(QPDFObjectHandle::newReal(w, 4));
        }
    }
    f.replaceKey("/FirstChar", QPDFObjectHandle::newInteger(first));
    f.replaceKey("/LastChar", QPDFObjectHandle::newInteger(last));
    f.replaceKey("/Widths", newWidths);
    ++ctx.widthsFixedCount;
}
void reconcileSimpleFontWidths(FontRewriteContext& ctx, QPDFObjectHandle& f, QPDFObjectHandle& fd,
                               const std::string& sub, bool& isSymbolic, int& fl,
                               std::vector<uint8_t>* preloadedProg,
                               jpdfium::font::FtFaceGuard* preloadedFace) {
    QPDF& qpdf = ctx.qpdf;
    FT_Library ftLib = ctx.ftLib;
    uint32_t flags = ctx.flags;
    auto& usedFontCodes = ctx.usedFontCodes;
    auto& codeRemaps = ctx.codeRemaps;
    int& widthsFixedCount = ctx.widthsFixedCount;
    int& cmapFixedCount = ctx.cmapFixedCount;
    int& notdefFixedCount = ctx.notdefFixedCount;
    int& metaFixedCount = ctx.metaFixedCount;
    (void)cmapFixedCount;
    (void)notdefFixedCount;
    (void)codeRemaps;

    // Stage 4 & 5: Reconcile simple font /Widths & repair missing/.notdef used codes
    if ((flags & kFlagReconcileWidths) || (flags & kFlagRepairNotdef)) {
        QPDFObjectHandle ff = fd.hasKey("/FontFile2")
                                  ? fd.getKey("/FontFile2")
                                  : (fd.hasKey("/FontFile3")
                                         ? fd.getKey("/FontFile3")
                                         : (fd.hasKey("/FontFile") ? fd.getKey("/FontFile")
                                                                   : QPDFObjectHandle::newNull()));
        if (ff.isStream()) {
            try {
                std::vector<uint8_t> localProg;
                jpdfium::font::FtFaceGuard localFace;
                std::vector<uint8_t>* progPtr = nullptr;
                jpdfium::font::FtFaceGuard* faceGuardPtr = nullptr;
                if (preloadedProg && preloadedFace && !preloadedProg->empty() &&
                    preloadedFace->face && fd.hasKey("/FontFile2")) {
                    progPtr = preloadedProg;
                    faceGuardPtr = preloadedFace;
                } else if (readStreamBytesBounded(ff, localProg, qpdf_dl_generalized) &&
                           !localProg.empty() && isSafeFontBuffer(localProg)) {
                    FT_Face rawFace = nullptr;
                    if (FT_New_Memory_Face(ftLib, localProg.data(),
                                           static_cast<FT_Long>(localProg.size()), 0,
                                           &rawFace) == 0 &&
                        rawFace) {
                        localFace.reset(rawFace);
                        progPtr = &localProg;
                        faceGuardPtr = &localFace;
                    }
                }
                if (progPtr && faceGuardPtr && faceGuardPtr->face) {
                    std::vector<uint8_t>& prog = *progPtr;
                    jpdfium::font::FtFaceGuard& faceGuard = *faceGuardPtr;
                    FT_Face face = faceGuard.face;
                    int upem = face->units_per_EM > 0 ? face->units_per_EM : 1000;

                    std::string baseEnc = "/WinAnsiEncoding";
                    std::map<int, std::string> diffNames;
                    if (f.hasKey("/Encoding")) {
                        QPDFObjectHandle encObj = f.getKey("/Encoding");
                        if (encObj.isName()) {
                            baseEnc = encObj.getName();
                        } else if (encObj.isDictionary()) {
                            if (encObj.hasKey("/BaseEncoding") &&
                                encObj.getKey("/BaseEncoding").isName()) {
                                baseEnc = encObj.getKey("/BaseEncoding").getName();
                            }
                            QPDFObjectHandle diffs = encObj.getKey("/Differences");
                            if (diffs.isArray()) {
                                long long cur = 0;
                                bool diffsDirty = false;
                                const int diffsN = std::min(diffs.getArrayNItems(), 4096);
                                for (int di = 0; di < diffsN; ++di) {
                                    QPDFObjectHandle item = diffs.getArrayItem(di);
                                    if (item.isInteger()) {
                                        cur = item.getIntValue();
                                    } else if (item.isName()) {
                                        if (cur >= 0 && cur <= 255) {
                                            std::string gn = item.getName().substr(1);
                                            // Fix non-AGL / .notdef names in Differences
                                            // (6.2.11.6-2 / 6.2.11.8-1)
                                            const bool nonAglTrueType =
                                                sub == "/TrueType" && !isSymbolic &&
                                                aglNameToUnicode(gn) == 0 && !gn.empty() &&
                                                (gn[0] == 'G' || gn[0] == 'g' || gn[0] == 'C' ||
                                                 gn[0] == 'c' ||
                                                 gn.find("cid") != std::string::npos ||
                                                 gn.find("CID") != std::string::npos ||
                                                 std::isdigit(static_cast<unsigned char>(gn[0])));
                                            if (gn == ".notdef" || nonAglTrueType) {
                                                gn = "space";
                                                diffsDirty = true;
                                            }
                                            diffNames[static_cast<int>(cur)] = std::move(gn);
                                        }
                                        if (cur <= 255) {
                                            ++cur;
                                        }
                                    }
                                }
                                if (diffsDirty) {
                                    QPDFObjectHandle newDiffs = QPDFObjectHandle::newArray();
                                    int prev = -2;
                                    for (const auto& [dc, dgn] : diffNames) {
                                        if (dc != prev + 1)
                                            newDiffs.appendItem(QPDFObjectHandle::newInteger(dc));
                                        newDiffs.appendItem(QPDFObjectHandle::newName("/" + dgn));
                                        prev = dc;
                                    }
                                    encObj.replaceKey("/Differences", newDiffs);
                                    ++notdefFixedCount;
                                }
                                // If a non-symbolic TrueType font has non-AGL or uniXXXX glyph
                                // names in /Differences (which fail veraPDF 6.2.11.6-2), convert it
                                // cleanly to Symbolic TrueType: preserve Unicode in /ToUnicode,
                                // build a (3,0) cmap from the exact old encoding, set Flags |= 4
                                // (clear bit 32), and remove /Encoding. Zero pixel or text loss!
                                if (sub == "/TrueType" && !isSymbolic && !diffNames.empty() &&
                                    fd.hasKey("/FontFile2")) {
                                    bool hasBadAgl = false;
                                    for (const auto& [dc, dgn] : diffNames) {
                                        if (aglNameToUnicode(dgn) == 0 ||
                                            (dgn.size() >= 7 && dgn.compare(0, 3, "uni") == 0)) {
                                            hasBadAgl = true;
                                            break;
                                        }
                                    }
                                    if (hasBadAgl) {
                                        std::map<uint16_t, uint16_t> map30;
                                        uint16_t ngTt = static_cast<uint16_t>(
                                            std::min<FT_Long>(face->num_glyphs, 65535));
                                        std::vector<std::pair<int, uint16_t>> tuEntries;
                                        for (int cc = 0; cc <= 255; ++cc) {
                                            FT_UInt g = resolveSimpleGid(face, cc, false, true,
                                                                         baseEnc, diffNames);
                                            if (g > 0 && g < ngTt) {
                                                map30[static_cast<uint16_t>(0xF000U + cc)] =
                                                    static_cast<uint16_t>(g);
                                            }
                                            uint32_t u = 0;
                                            auto dit = diffNames.find(cc);
                                            if (dit != diffNames.end())
                                                u = aglNameToUnicode(dit->second);
                                            else if (baseEnc == "/MacRomanEncoding")
                                                u = macRomanToUnicode(static_cast<uint8_t>(cc));
                                            else
                                                u = winAnsiToUnicode(static_cast<uint8_t>(cc));
                                            if (u > 0 && u <= 0xFFFF)
                                                tuEntries.push_back({cc, static_cast<uint16_t>(u)});
                                        }
                                        if (f.isIndirect()) {
                                            auto uit = usedFontCodes.find(f.getObjGen());
                                            if (uit != usedFontCodes.end()) {
                                                uint16_t fbGid = map30.count(0xF020)
                                                                     ? map30[0xF020]
                                                                     : (ngTt > 1 ? 1 : 0);
                                                for (uint32_t uc : uit->second) {
                                                    if (uc <= 255 && fbGid > 0 &&
                                                        !map30.count(
                                                            static_cast<uint16_t>(0xF000U + uc))) {
                                                        map30[static_cast<uint16_t>(0xF000U + uc)] =
                                                            fbGid;
                                                    }
                                                }
                                            }
                                        }
                                        SfntFont sf;
                                        if (!map30.empty() &&
                                            SfntFont::parse(prog.data(), prog.size(), sf)) {
                                            std::vector<CmapSubtable> subs;
                                            if (sf.tables.count("cmap")) {
                                                subs = parseCmapSubtables(sf.tables["cmap"]);
                                                subs.erase(
                                                    std::remove_if(subs.begin(), subs.end(),
                                                                   [](const CmapSubtable& s) {
                                                                       return s.platformId == 3 &&
                                                                              s.encodingId == 0;
                                                                   }),
                                                    subs.end());
                                            }
                                            subs.push_back({3, 0, buildCmapFormat4(map30)});
                                            sf.tables["cmap"] = serializeCmapTable(std::move(subs));
                                            std::vector<uint8_t> serialized = sf.serialize();
                                            if (!serialized.empty() &&
                                                isSafeFontBuffer(serialized)) {
                                                faceGuard.reset();
                                                face = nullptr;
                                                prog = std::move(serialized);
                                                QPDFObjectHandle newFf = ctx.internFontStream(prog);
                                                QPDFObjectHandle newFd =
                                                    qpdf.makeIndirectObject(fd.shallowCopy());
                                                newFd.replaceKey("/FontFile2", newFf);
                                                newFd.replaceKey(
                                                    "/Flags",
                                                    QPDFObjectHandle::newInteger((fl & ~32) | 4));
                                                f.replaceKey("/FontDescriptor", newFd);
                                                fd = std::move(newFd);
                                                fl = (fl & ~32) | 4;
                                                isSymbolic = true;
                                                if (!f.hasKey("/ToUnicode") && !tuEntries.empty()) {
                                                    std::map<uint32_t, uint32_t> tuMap;
                                                    for (const auto& [code, uni] : tuEntries) {
                                                        tuMap[static_cast<uint32_t>(code)] = uni;
                                                    }
                                                    f.replaceKey(
                                                        "/ToUnicode",
                                                        QPDFObjectHandle::newStream(
                                                            &qpdf,
                                                            jpdfium::font::buildToUnicodeCmap(
                                                                tuMap, false)));
                                                }
                                                f.removeKey("/Encoding");
                                                diffNames.clear();
                                                FT_Face reopened = nullptr;
                                                if (FT_New_Memory_Face(
                                                        ftLib, prog.data(),
                                                        static_cast<FT_Long>(prog.size()), 0,
                                                        &reopened) == 0 &&
                                                    reopened) {
                                                    faceGuard.reset(reopened);
                                                    face = reopened;
                                                }
                                                ++cmapFixedCount;
                                            }
                                        }
                                    }
                                }
                                // If a non-symbolic TrueType font has a /Differences dict whose
                                // entries match its BaseEncoding (MacRoman or WinAnsi), drop
                                // /Differences and use the BaseEncoding name directly
                                // (fixes 6.2.11.6-2 without changing unmentioned codes).
                                if (sub == "/TrueType" && !isSymbolic && !diffNames.empty()) {
                                    bool allMatchMac = (baseEnc == "/MacRomanEncoding");
                                    bool allMatchWin =
                                        (baseEnc == "/WinAnsiEncoding" || baseEnc.empty());
                                    for (const auto& [dc, dgn] : diffNames) {
                                        if (dc < 0 || dc > 255) {
                                            allMatchMac = allMatchWin = false;
                                            break;
                                        }
                                        uint32_t u = aglNameToUnicode(dgn);
                                        if (u == 0) {
                                            allMatchMac = allMatchWin = false;
                                            break;
                                        }
                                        if (allMatchMac &&
                                            macRomanToUnicode(static_cast<uint8_t>(dc)) != u)
                                            allMatchMac = false;
                                        if (allMatchWin &&
                                            winAnsiToUnicode(static_cast<uint8_t>(dc)) != u)
                                            allMatchWin = false;
                                    }
                                    if (allMatchMac) {
                                        f.replaceKey("/Encoding", QPDFObjectHandle::newName(
                                                                      "/MacRomanEncoding"));
                                        baseEnc = "/MacRomanEncoding";
                                        diffNames.clear();
                                        ++cmapFixedCount;
                                    } else if (allMatchWin) {
                                        f.replaceKey("/Encoding",
                                                     QPDFObjectHandle::newName("/WinAnsiEncoding"));
                                        baseEnc = "/WinAnsiEncoding";
                                        diffNames.clear();
                                        ++cmapFixedCount;
                                    }
                                }
                            }
                        }
                    }

                    bool isTt = (sub == "/TrueType");
                    // For symbolic Type1C (FontFile3) without /Encoding, materialize its custom CFF
                    // Encoding into /Encoding /Differences so PDF/A validators resolve exact glyph
                    // names (e.g. code 173 -> /hyphen).
                    if (!isTt && isSymbolic && !f.hasKey("/Encoding") && fd.hasKey("/FontFile3") &&
                        FT_HAS_GLYPH_NAMES(face) &&
                        FT_Select_Charmap(face, FT_ENCODING_ADOBE_CUSTOM) == 0) {
                        std::map<int, std::string> cffDiffs;
                        for (int cc = 0; cc < 256; ++cc) {
                            FT_UInt g = FT_Get_Char_Index(face, static_cast<unsigned>(cc));
                            if (g > 0) {
                                char gn[64] = {};
                                if (FT_Get_Glyph_Name(face, g, gn, sizeof(gn)) == 0 &&
                                    gn[0] != '\0' && std::strcmp(gn, ".notdef") != 0) {
                                    cffDiffs[cc] = gn;
                                }
                            }
                        }
                        if (!cffDiffs.empty()) {
                            QPDFObjectHandle encDict = QPDFObjectHandle::newDictionary();
                            encDict.replaceKey("/Type", QPDFObjectHandle::newName("/Encoding"));
                            QPDFObjectHandle newDiffs = QPDFObjectHandle::newArray();
                            int prev = -2;
                            for (const auto& [dc, dgn] : cffDiffs) {
                                if (dc != prev + 1)
                                    newDiffs.appendItem(QPDFObjectHandle::newInteger(dc));
                                newDiffs.appendItem(QPDFObjectHandle::newName("/" + dgn));
                                prev = dc;
                            }
                            encDict.replaceKey("/Differences", newDiffs);
                            f.replaceKey("/Encoding", encDict);
                            diffNames = std::move(cffDiffs);
                            ++cmapFixedCount;
                        }
                    }
                    // Stage 5: Check used codes in content streams for GID 0 (.notdef / missing
                    // glyph)
                    if ((flags & kFlagRepairNotdef) && f.isIndirect()) {
                        auto uit = usedFontCodes.find(f.getObjGen());
                        if (uit != usedFontCodes.end() && !uit->second.empty()) {
                            std::vector<int> badCodes;
                            for (uint32_t uc : uit->second) {
                                if (uc > 255) continue;
                                FT_UInt g = resolveSimpleGid(face, static_cast<int>(uc), isSymbolic,
                                                             isTt, baseEnc, diffNames);
                                if (g == 0) badCodes.push_back(static_cast<int>(uc));
                            }
                            if (!badCodes.empty()) {
                                int replCode = -1;
                                if (!isSymbolic) {
                                    FT_UInt spGid =
                                        resolveSimpleGid(face, 32, false, isTt, baseEnc, diffNames);
                                    if (spGid > 0) {
                                        for (int bc : badCodes) diffNames[bc] = "space";
                                        QPDFObjectHandle encDict =
                                            QPDFObjectHandle::newDictionary();
                                        encDict.replaceKey("/Type",
                                                           QPDFObjectHandle::newName("/Encoding"));
                                        encDict.replaceKey("/BaseEncoding",
                                                           QPDFObjectHandle::newName(
                                                               (baseEnc == "/MacRomanEncoding")
                                                                   ? "/MacRomanEncoding"
                                                                   : "/WinAnsiEncoding"));
                                        QPDFObjectHandle newDiffs = QPDFObjectHandle::newArray();
                                        int prev = -2;
                                        for (const auto& [dc, dgn] : diffNames) {
                                            if (dc != prev + 1)
                                                newDiffs.appendItem(
                                                    QPDFObjectHandle::newInteger(dc));
                                            newDiffs.appendItem(
                                                QPDFObjectHandle::newName("/" + dgn));
                                            prev = dc;
                                        }
                                        encDict.replaceKey("/Differences", newDiffs);
                                        f.replaceKey("/Encoding", encDict);
                                        ++notdefFixedCount;
                                    } else {
                                        for (int cand = 32; cand < 127; ++cand) {
                                            if (resolveSimpleGid(face, cand, false, isTt, baseEnc,
                                                                 diffNames) > 0) {
                                                replCode = cand;
                                                break;
                                            }
                                        }
                                    }
                                } else {
                                    int bestW = 100000;
                                    for (int cand = 1; cand < 256; ++cand) {
                                        FT_UInt cg = resolveSimpleGid(face, cand, true, isTt,
                                                                      baseEnc, diffNames);
                                        if (cg > 0) {
                                            int pw = ftGlyphWidth1000(face, cg, upem, !isTt);
                                            if (pw >= 0 && pw < bestW) {
                                                bestW = pw;
                                                replCode = cand;
                                                if (pw == 0) break;
                                            }
                                        }
                                    }
                                }
                                if (replCode >= 0) {
                                    for (int bc : badCodes) {
                                        codeRemaps[f.getObjGen()][static_cast<uint32_t>(bc)] =
                                            static_cast<uint32_t>(replCode);
                                    }
                                    ++notdefFixedCount;
                                }
                            }
                        }
                    }

                    // Stage 4: Reconcile simple font /Widths against FreeType.
                    std::array<FT_UInt, 256> gidCache{};
                    bool gidCacheValid = false;
                    std::map<FT_UInt, double> exactWidthCache;
                    auto getCachedGid = [&](int code) -> FT_UInt {
                        if (!face || code < 0 || code > 255) return 0;
                        if (!gidCacheValid) {
                            for (int cc = 0; cc < 256; ++cc) {
                                gidCache[static_cast<size_t>(cc)] = resolveSimpleGid(
                                    face, cc, isSymbolic, isTt, baseEnc, diffNames);
                            }
                            gidCacheValid = true;
                        }
                        return gidCache[static_cast<size_t>(code)];
                    };
                    auto getCachedExactWidth = [&](FT_UInt gid) -> double {
                        if (!face || gid == 0) return -1.0;
                        auto it = exactWidthCache.find(gid);
                        if (it != exactWidthCache.end()) return it->second;
                        double ew = ftGlyphWidth1000Exact(face, gid, upem, !isTt);
                        exactWidthCache[gid] = ew;
                        return ew;
                    };

                    // Synthesize /FirstChar, /LastChar, /Widths if missing or non-array
                    // (fixes 6.2.11.2-6 / 6.2.11.5-1).
                    if (face && (flags & kFlagReconcileWidths) &&
                        (!f.hasKey("/FirstChar") || !f.getKey("/FirstChar").isInteger() ||
                         !f.hasKey("/Widths") || !f.getKey("/Widths").isArray())) {
                        QPDFObjectHandle wArr = QPDFObjectHandle::newArray();
                        for (int code = 0; code <= 255; ++code) {
                            FT_UInt gid = getCachedGid(code);
                            double ew = getCachedExactWidth(gid);
                            int w = safeRoundFontInt(ew);
                            wArr.appendItem(QPDFObjectHandle::newInteger(w));
                        }
                        f.replaceKey("/FirstChar", QPDFObjectHandle::newInteger(0));
                        f.replaceKey("/LastChar", QPDFObjectHandle::newInteger(255));
                        f.replaceKey("/Widths", wArr);
                        ++widthsFixedCount;
                    }
                    // For TrueType (FontFile2), prefer patching hmtx to match /Widths (preserves
                    // exact PDF layout & text extraction word spacing!); fall back to updating
                    // /Widths on collisions/CFF.
                    if (face && (flags & kFlagReconcileWidths) && f.hasKey("/FirstChar") &&
                        f.getKey("/FirstChar").isInteger() && f.hasKey("/Widths") &&
                        f.getKey("/Widths").isArray()) {
                        long long rawFirst = f.getKey("/FirstChar").getIntValue();
                        QPDFObjectHandle ws = f.getKey("/Widths");
                        int rawWlen = ws.getArrayNItems();
                        int first = static_cast<int>(std::clamp<long long>(rawFirst, 0, 255));
                        int wlen = std::clamp(rawWlen, 0, 256 - first);
                        // Expand /FirstChar../LastChar and /Widths if any used code (e.g. code 31
                        // or 127 mapped to /space in Stage 5) lies outside [first, first+wlen-1]
                        // (fixes 6.2.11.5-1).
                        int newFirst = (wlen > 0) ? first : 0;
                        int newLast = (wlen > 0) ? (first + wlen - 1) : 255;
                        if (f.isIndirect()) {
                            auto uit = usedFontCodes.find(f.getObjGen());
                            if (uit != usedFontCodes.end()) {
                                for (uint32_t uc : uit->second) {
                                    if (uc <= 255) {
                                        if (static_cast<int>(uc) < newFirst)
                                            newFirst = static_cast<int>(uc);
                                        if (static_cast<int>(uc) > newLast)
                                            newLast = static_cast<int>(uc);
                                    }
                                }
                            }
                        }
                        newFirst = std::clamp(newFirst, 0, 255);
                        newLast = std::clamp(newLast, newFirst, 255);
                        bool expandedWs =
                            (wlen == 0 || newFirst < first || newLast > first + wlen - 1 ||
                             rawFirst != first || rawWlen != wlen);
                        std::vector<int> dictWidths(static_cast<size_t>(newLast - newFirst + 1), 0);
                        for (int j = 0; j < wlen; ++j) {
                            size_t dstIdx =
                                static_cast<size_t>(first - newFirst) + static_cast<size_t>(j);
                            if (dstIdx < dictWidths.size()) {
                                dictWidths[dstIdx] =
                                    ws.getArrayItem(j).isNumber()
                                        ? safeRoundFontInt(ws.getArrayItem(j).getNumericValue())
                                        : 0;
                            }
                        }
                        if (expandedWs) {
                            for (int c = newFirst; c <= newLast; ++c) {
                                if (c < first || c >= first + wlen) {
                                    FT_UInt g = getCachedGid(c);
                                    if (g > 0) {
                                        double ew = getCachedExactWidth(g);
                                        if (ew >= 0.0 && std::isfinite(ew)) {
                                            dictWidths[static_cast<size_t>(c - newFirst)] =
                                                safeRoundFontInt(ew);
                                        }
                                    }
                                }
                            }
                            first = newFirst;
                            wlen = static_cast<int>(dictWidths.size());
                        }
                        std::map<uint16_t, int> gidTargetW;
                        std::set<uint16_t> gidCollision;
                        if (isTt && fd.hasKey("/FontFile2") && upem >= 500) {
                            for (int j = 0; j < wlen; ++j) {
                                int code = first + j;
                                int dictW = dictWidths[static_cast<size_t>(j)];
                                FT_UInt gid = getCachedGid(code);
                                if (gid > 0 && gid < 65535 && dictW >= 0 && dictW <= 10000) {
                                    uint16_t g16 = static_cast<uint16_t>(gid);
                                    auto git = gidTargetW.find(g16);
                                    if (git == gidTargetW.end())
                                        gidTargetW[g16] = dictW;
                                    else if (git->second != dictW)
                                        gidCollision.insert(g16);
                                }
                            }
                        }
                        bool wChanged = expandedWs;
                        std::map<uint16_t, uint16_t> hmtxPatch;
                        QPDFObjectHandle newWs = QPDFObjectHandle::newArray();
                        for (int j = 0; j < wlen; ++j) {
                            int code = first + j;
                            int dictW = dictWidths[static_cast<size_t>(j)];
                            FT_UInt gid = getCachedGid(code);
                            int finalW = dictW;
                            if (gid > 0) {
                                double exactW = getCachedExactWidth(gid);
                                if (exactW >= 0.0 && std::isfinite(exactW) &&
                                    std::abs(exactW - static_cast<double>(dictW)) > 0.95) {
                                    if (isTt && fd.hasKey("/FontFile2") && gid < 65535 &&
                                        dictW > 0 && dictW <= 10000 && upem >= 500 &&
                                        !gidCollision.count(static_cast<uint16_t>(gid))) {
                                        long long advU = safeRoundFontInt(
                                            static_cast<double>(dictW) * upem / 1000.0);
                                        if (advU >= 0 && advU <= 65535 &&
                                            std::abs(static_cast<double>(advU) * 1000.0 / upem -
                                                     dictW) <= 0.95) {
                                            hmtxPatch[static_cast<uint16_t>(gid)] =
                                                static_cast<uint16_t>(advU);
                                            newWs.appendItem(QPDFObjectHandle::newInteger(finalW));
                                            continue;
                                        }
                                    }
                                    finalW = safeRoundFontInt(exactW);
                                    wChanged = true;
                                }
                            }
                            newWs.appendItem(QPDFObjectHandle::newInteger(finalW));
                        }
                        if (!hmtxPatch.empty()) {
                            std::vector<uint8_t> patched = prog;
                            if (sfntPatchHmtx(patched, hmtxPatch) && !patched.empty() &&
                                isSafeFontBuffer(patched)) {
                                faceGuard.reset();
                                face = nullptr;
                                gidCacheValid = false;
                                exactWidthCache.clear();
                                prog = std::move(patched);
                                QPDFObjectHandle newFf = ctx.internFontStream(prog);
                                QPDFObjectHandle newFd = qpdf.makeIndirectObject(fd.shallowCopy());
                                newFd.replaceKey("/FontFile2", newFf);
                                f.replaceKey("/FontDescriptor", newFd);
                                fd = std::move(newFd);
                                ++widthsFixedCount;
                                FT_Face reopened = nullptr;
                                if (FT_New_Memory_Face(ftLib, prog.data(),
                                                       static_cast<FT_Long>(prog.size()), 0,
                                                       &reopened) == 0 &&
                                    reopened) {
                                    faceGuard.reset(reopened);
                                    face = reopened;
                                }
                            } else {
                                QPDFObjectHandle fbWs = QPDFObjectHandle::newArray();
                                for (int j = 0; j < wlen; ++j) {
                                    int code = first + j;
                                    int dictW = dictWidths[static_cast<size_t>(j)];
                                    FT_UInt gid = getCachedGid(code);
                                    int fw = dictW;
                                    if (gid > 0) {
                                        double ew = getCachedExactWidth(gid);
                                        if (ew >= 0.0 && std::isfinite(ew) &&
                                            std::abs(ew - dictW) > 0.95) {
                                            fw = safeRoundFontInt(ew);
                                        }
                                    }
                                    fbWs.appendItem(QPDFObjectHandle::newInteger(fw));
                                }
                                newWs = fbWs;
                                wChanged = true;
                            }
                        }
                        if (wChanged) {
                            f.replaceKey("/FirstChar", QPDFObjectHandle::newInteger(first));
                            f.replaceKey("/Widths", newWs);
                            f.replaceKey("/LastChar",
                                         QPDFObjectHandle::newInteger(first + wlen - 1));
                            ++widthsFixedCount;
                        } else if (!f.hasKey("/LastChar") || !f.getKey("/LastChar").isInteger() ||
                                   f.getKey("/LastChar").getIntValue() != first + wlen - 1) {
                            // Always ensure LastChar == FirstChar + Widths.size() - 1
                            // (fixes 6.2.11.2-6)
                            f.replaceKey("/LastChar",
                                         QPDFObjectHandle::newInteger(first + wlen - 1));
                            ++metaFixedCount;
                        }
                    }
                    // PDF/A-1b (ISO 19005-1 6.3.7-1) and PDF/X forbid /Differences on non-symbolic
                    // TrueType fonts. Convert such fonts to symbolic with a single exact (3,0) cmap
                    // preserving every glyph!
                    if (face && !(flags & (kFlagConvertPdfA2b | kFlagConvertPdfA3b)) && isTt &&
                        !isSymbolic && f.hasKey("/Encoding") &&
                        f.getKey("/Encoding").isDictionary() &&
                        f.getKey("/Encoding").hasKey("/Differences") && fd.isDictionary() &&
                        fd.hasKey("/FontFile2")) {
                        SfntFont sf;
                        if (SfntFont::parse(prog.data(), prog.size(), sf)) {
                            std::map<uint16_t, uint16_t> map30;
                            FT_UInt ng = static_cast<FT_UInt>(
                                std::clamp<FT_Long>(face->num_glyphs, 1, 65536));
                            for (int code = 0; code < 256; ++code) {
                                FT_UInt gid = getCachedGid(code);
                                if (gid > 0 && gid < ng) {
                                    map30[static_cast<uint16_t>(0xF000U + code)] =
                                        static_cast<uint16_t>(gid);
                                }
                            }
                            std::vector<uint8_t> f4 =
                                map30.empty() ? std::vector<uint8_t>{} : buildCmapFormat4(map30);
                            if (!f4.empty()) {
                                std::vector<CmapSubtable> only30;
                                only30.push_back({3, 0, std::move(f4)});
                                sf.tables["cmap"] = serializeCmapTable(std::move(only30));
                                std::vector<uint8_t> newProg = sf.serialize();
                                if (!newProg.empty()) {
                                    QPDFObjectHandle newFf = ctx.internFontStream(newProg);
                                    QPDFObjectHandle newFd =
                                        qpdf.makeIndirectObject(fd.shallowCopy());
                                    newFd.replaceKey("/FontFile2", newFf);
                                    int curF =
                                        newFd.getKey("/Flags").isInteger()
                                            ? static_cast<int>(newFd.getKey("/Flags").getIntValue())
                                            : 4;
                                    newFd.replaceKey(
                                        "/Flags", QPDFObjectHandle::newInteger((curF & ~32) | 4));
                                    f.replaceKey("/FontDescriptor", newFd);
                                    if (!f.hasKey("/ToUnicode")) {
                                        std::map<uint32_t, uint32_t> tuMap;
                                        for (int code = 0; code < 256; ++code) {
                                            if (map30.find(static_cast<uint16_t>(0xF000U + code)) ==
                                                map30.end()) {
                                                continue;
                                            }
                                            uint32_t uni = 0;
                                            auto dit = diffNames.find(code);
                                            if (dit != diffNames.end()) {
                                                uni = aglNameToUnicode(dit->second);
                                            } else if (baseEnc == "/MacRomanEncoding") {
                                                uni = macRomanToUnicode(static_cast<uint8_t>(code));
                                            } else {
                                                uni = winAnsiToUnicode(static_cast<uint8_t>(code));
                                            }
                                            if (uni > 0 && uni <= 0x10FFFFU) {
                                                tuMap[static_cast<uint32_t>(code)] = uni;
                                            }
                                        }
                                        if (!tuMap.empty()) {
                                            std::string cmap =
                                                jpdfium::font::buildToUnicodeCmap(tuMap, false);
                                            if (!cmap.empty()) {
                                                f.replaceKey(
                                                    "/ToUnicode",
                                                    QPDFObjectHandle::newStream(&qpdf, cmap));
                                            }
                                        }
                                        f.removeKey("/Encoding");
                                        fd = newFd;
                                        ++cmapFixedCount;
                                    }
                                    f.removeKey("/Encoding");
                                    fd = newFd;
                                    ++cmapFixedCount;
                                }
                            }
                        }
                    }
                    // PDF/A-1b (ISO 19005-1 6.3.5-2) requires a complete /CharSet on Type 1 / CFF
                    // subsets.
                    if (face && (flags & kFlagConvertPdfA1b) && sub != "/TrueType" &&
                        fd.isDictionary() && FT_HAS_GLYPH_NAMES(face)) {
                        std::string cs;
                        FT_Long ng = face->num_glyphs;
                        char gname[128];
                        for (FT_Long gi = 0; gi < ng && gi < 4096; ++gi) {
                            gname[0] = '\0';
                            if (FT_Get_Glyph_Name(face, static_cast<FT_UInt>(gi), gname,
                                                  sizeof(gname)) == 0 &&
                                gname[0] != '\0' && std::strcmp(gname, ".notdef") != 0) {
                                cs.push_back('/');
                                cs.append(gname);
                            }
                        }
                        if (!cs.empty()) {
                            fd.replaceKey("/CharSet", QPDFObjectHandle::newString(cs));
                            ++metaFixedCount;
                        }
                    }
                    if (!face) {
                        gidCacheValid = false;
                    }
                }
            } catch (const std::exception& e) {
                std::fputs(e.what(), stderr);
                std::fputc('\n', stderr);
            } catch (...) {
                std::fputs("jpdfium: reconcileSimpleFontWidths failed\n", stderr);
            }
        }
    }
}

}  // namespace jpdfium::rewriter
