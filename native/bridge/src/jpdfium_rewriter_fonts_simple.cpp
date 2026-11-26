// jpdfium_rewriter_fonts_simple.cpp - Simple font (TrueType, Type1, MMType1) rewriting stages
// (styled donor embedding, cmap/encoding normalization, .notdef repair, and /Widths
// reconciliation).

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <limits>

#include "jpdfium_font_rewriter_internal.h"

namespace jpdfium::rewriter {

void rewriteSimpleFont(FontRewriteContext& ctx, QPDFObjectHandle& f, std::string& sub,
                       const std::string& bf) {
    QPDF& qpdf = ctx.qpdf;
    FT_Library ftLib = ctx.ftLib;
    uint32_t flags = ctx.flags;
    const char* donorDirs = ctx.donorDirs;
    auto& usedFontCodes = ctx.usedFontCodes;
    auto& codeRemaps = ctx.codeRemaps;
    int& embeddedCount = ctx.embeddedCount;
    int& cmapFixedCount = ctx.cmapFixedCount;
    int& notdefFixedCount = ctx.notdefFixedCount;
    int& metaFixedCount = ctx.metaFixedCount;
    auto getDonorStream = [&](const std::string& key, bool isCff) -> QPDFObjectHandle {
        return ctx.getDonorStream(key, isCff);
    };
    (void)cmapFixedCount;
    (void)notdefFixedCount;
    (void)codeRemaps;

    if (sub == "/Type3") {
        if (flags & kFlagRepairNotdef) repairType3Font(ctx, f);
        return;
    }
    if (sub != "/TrueType" && sub != "/Type1" && sub != "/MMType1") return;
    QPDFObjectHandle fd = f.getKey("/FontDescriptor");
    bool isEmb = (flags & kFlagEmbedMissing)
                     ? validateOrStripCorruptFontStreams(fd)
                     : (fd.isDictionary() && (fd.hasKey("/FontFile") || fd.hasKey("/FontFile2") ||
                                              fd.hasKey("/FontFile3")));

    // Fix invalid stream /Encoding on a simple font (must be Name or Dictionary; fixes 6.2.11.6-2
    // / 6.2.11.8-1)
    if ((flags & kFlagNormalizeCmaps) && f.hasKey("/Encoding") &&
        f.getKey("/Encoding").isStream()) {
        f.replaceKey("/Encoding", QPDFObjectHandle::newName("/WinAnsiEncoding"));
        ++cmapFixedCount;
    }

    // Fix mismatched /Type1 font sharing a TrueType /FontFile2 descriptor (fixes 6.2.11.4.1-1)
    if (sub == "/Type1" && isEmb && !fd.hasKey("/FontFile") && !fd.hasKey("/FontFile3") &&
        fd.hasKey("/FontFile2")) {
        f.replaceKey("/Subtype", QPDFObjectHandle::newName("/TrueType"));
        sub = "/TrueType";
        ++metaFixedCount;
    }

    // PDF/A-1 does not allow /FontFile3 /Subtype /OpenType (6.3.2).
    if ((flags & kFlagConvertPdfA1b) && fd.isDictionary() &&
        convertOpenTypeWrapperForPdfA1(qpdf, fd, ftLib, false)) {
        ++metaFixedCount;
    }
    if ((flags & kFlagConvertPdfA1b) && repairCffFontProgramForPdfA(qpdf, fd)) {
        ++metaFixedCount;
    }
    if ((flags & kFlagCleanMetadata) && !(flags & kFlagConvertPdfA1b) && fd.isDictionary() &&
        fd.hasKey("/CharSet")) {
        fd.removeKey("/CharSet");
        ++metaFixedCount;
    }

    // Stage 3: Embed missing simple font (styled donor match + preserve existing /Encoding!)
    if (!isEmb && (flags & kFlagEmbedMissing)) {
        std::string effBf = bf;
        if (effBf.empty()) {
            effBf = "/Arial";
            f.replaceKey("/BaseFont", QPDFObjectHandle::newName(effBf));
            ++metaFixedCount;
        }
        std::string dkey = selectStyledDonorKey(effBf);
        bool isSymbolicDonor = (dkey == "symbol" || dkey == "wingdings" || dkey == "webdings");
        const DonorFont* donor = getCachedDonor(ftLib, donorDirs, dkey);
        QPDFObjectHandle st = getDonorStream(dkey, false);
        if ((!donor || st.isNull()) && isSymbolicDonor) {
            isSymbolicDonor = false;
            dkey = "arial";
            donor = getCachedDonor(ftLib, donorDirs, dkey);
            st = getDonorStream(dkey, false);
        }
        if (donor && !st.isNull()) {
            if (!fd.isDictionary()) {
                fd = qpdf.makeIndirectObject(QPDFObjectHandle::newDictionary());
                fd.replaceKey("/Type", QPDFObjectHandle::newName("/FontDescriptor"));
                fd.replaceKey("/FontName", QPDFObjectHandle::newName(effBf));
                fd.replaceKey("/Flags", QPDFObjectHandle::newInteger(isSymbolicDonor ? 4 : 32));
                QPDFObjectHandle bb = QPDFObjectHandle::newArray();
                for (int b : donor->bbox) bb.appendItem(QPDFObjectHandle::newInteger(b));
                fd.replaceKey("/FontBBox", bb);
                fd.replaceKey("/ItalicAngle", QPDFObjectHandle::newInteger(0));
                fd.replaceKey("/Ascent", QPDFObjectHandle::newInteger(donor->ascent));
                fd.replaceKey("/Descent", QPDFObjectHandle::newInteger(donor->descent));
                fd.replaceKey("/CapHeight", QPDFObjectHandle::newInteger(donor->capHeight));
                fd.replaceKey("/StemV", QPDFObjectHandle::newInteger(80));
                f.replaceKey("/FontDescriptor", fd);
            } else {
                int fl = fd.hasKey("/Flags") && fd.getKey("/Flags").isInteger()
                             ? static_cast<int>(fd.getKey("/Flags").getIntValue())
                             : 32;
                if (isSymbolicDonor) {
                    fd.replaceKey("/Flags", QPDFObjectHandle::newInteger((fl & ~32) | 4));
                } else if (fl & 4) {
                    fd.replaceKey("/Flags", QPDFObjectHandle::newInteger((fl & ~4) | 32));
                }
            }
            f.replaceKey("/Subtype", QPDFObjectHandle::newName("/TrueType"));
            sub = "/TrueType";
            fd.replaceKey("/FontFile2", st);
            // Preserve existing /MacRomanEncoding, /WinAnsiEncoding, or /Differences dict when
            // present; for Symbol/Wingdings/Webdings donors, embed as symbolic TrueType (no
            // /Encoding) + /ToUnicode; for ZapfDingbats (no native symbolic donor), reset to
            // WinAnsi + 0..31 -> /space.
            std::string lbfSym = toLowerAscii(bf);
            bool isStdSym = (lbfSym.find("symbol") != std::string::npos ||
                             lbfSym.find("dingbat") != std::string::npos);
            if (isSymbolicDonor) {
                if (f.hasKey("/Encoding")) f.removeKey("/Encoding");
                if (!f.hasKey("/ToUnicode")) {
                    std::map<uint32_t, uint32_t> symMap;
                    for (int cc = 0x20; cc <= 0xFF; ++cc) {
                        uint16_t u = (dkey == "symbol") ? kAdobeSymbol[cc - 0x20]
                                                        : static_cast<uint16_t>(0xF000 + cc);
                        if (u != 0) symMap[static_cast<uint32_t>(cc)] = u;
                    }
                    f.replaceKey("/ToUnicode",
                                 QPDFObjectHandle::newStream(
                                     &qpdf, jpdfium::font::buildToUnicodeCmap(symMap, false)));
                }
            } else if (isStdSym) {
                QPDFObjectHandle encDict = QPDFObjectHandle::newDictionary();
                encDict.replaceKey("/Type", QPDFObjectHandle::newName("/Encoding"));
                encDict.replaceKey("/BaseEncoding", QPDFObjectHandle::newName("/WinAnsiEncoding"));
                QPDFObjectHandle diffs = QPDFObjectHandle::newArray();
                diffs.appendItem(QPDFObjectHandle::newInteger(0));
                for (int cc = 0; cc < 32; ++cc)
                    diffs.appendItem(QPDFObjectHandle::newName("/space"));
                encDict.replaceKey("/Differences", diffs);
                f.replaceKey("/Encoding", encDict);
            } else if (!f.hasKey("/Encoding")) {
                f.replaceKey("/Encoding", QPDFObjectHandle::newName("/WinAnsiEncoding"));
            } else if (f.getKey("/Encoding").isName()) {
                std::string en = f.getKey("/Encoding").getName();
                if (en != "/WinAnsiEncoding" && en != "/MacRomanEncoding") {
                    f.replaceKey("/Encoding", QPDFObjectHandle::newName("/WinAnsiEncoding"));
                }
            } else if (f.getKey("/Encoding").isDictionary()) {
                QPDFObjectHandle enc = f.getKey("/Encoding");
                if (!enc.hasKey("/BaseEncoding") || !enc.getKey("/BaseEncoding").isName() ||
                    (enc.getKey("/BaseEncoding").getName() != "/WinAnsiEncoding" &&
                     enc.getKey("/BaseEncoding").getName() != "/MacRomanEncoding")) {
                    enc.replaceKey("/BaseEncoding", QPDFObjectHandle::newName("/WinAnsiEncoding"));
                }
                // If /Differences has non-AGL glyph names, drop /Differences to satisfy 6.2.11.6-2
                if (enc.hasKey("/Differences") && enc.getKey("/Differences").isArray()) {
                    QPDFObjectHandle diffs = enc.getKey("/Differences");
                    bool hasNonAgl = false;
                    const int diffsN = std::min(diffs.getArrayNItems(), 4096);
                    for (int di = 0; di < diffsN; ++di) {
                        QPDFObjectHandle it = diffs.getArrayItem(di);
                        if (it.isName()) {
                            std::string gn = it.getName().substr(1);
                            if (gn == ".notdef" || aglNameToUnicode(gn) == 0) {
                                hasNonAgl = true;
                                break;
                            }
                        }
                    }
                    if (hasNonAgl) {
                        f.replaceKey("/Encoding", enc.getKey("/BaseEncoding"));
                    }
                }
            }
            if (!f.hasKey("/FirstChar") || !f.getKey("/FirstChar").isInteger() ||
                !f.hasKey("/Widths") || !f.getKey("/Widths").isArray()) {
                f.replaceKey("/FirstChar", QPDFObjectHandle::newInteger(0));
                f.replaceKey("/LastChar", QPDFObjectHandle::newInteger(255));
                QPDFObjectHandle wArr = QPDFObjectHandle::newArray();
                for (int ci = 0; ci < 256; ++ci) {
                    wArr.appendItem(QPDFObjectHandle::newInteger(
                        donor->winAnsiWidths[static_cast<size_t>(ci)]));
                }
                f.replaceKey("/Widths", wArr);
            }
            ++embeddedCount;
            isEmb = true;
        }
    }

    // Stage 3b/6: Normalize TrueType cmaps & encodings + FirstChar/LastChar/Widths consistency
    if (isEmb && fd.isDictionary()) {
        int fl = fd.hasKey("/Flags") && fd.getKey("/Flags").isInteger()
                     ? static_cast<int>(fd.getKey("/Flags").getIntValue())
                     : 32;
        bool isSymbolic = (fl & 4) != 0;
        std::vector<uint8_t> loadedProg;
        jpdfium::font::FtFaceGuard loadedFace;

        if ((flags & kFlagNormalizeCmaps) && sub == "/TrueType") {
            if (isSymbolic) {
                if (f.hasKey("/Encoding")) {
                    // Before dropping /Encoding from a symbolic TrueType font (6.2.11.6-2),
                    // preserve its Unicode mapping in /ToUnicode if none is present so PDFium
                    // text extraction stays intact!
                    if (!f.hasKey("/ToUnicode")) {
                        std::string oldBase = "/WinAnsiEncoding";
                        std::map<int, std::string> oldDiffs;
                        QPDFObjectHandle oe = f.getKey("/Encoding");
                        if (oe.isName()) {
                            oldBase = oe.getName();
                        } else if (oe.isDictionary()) {
                            if (oe.hasKey("/BaseEncoding") && oe.getKey("/BaseEncoding").isName()) {
                                oldBase = oe.getKey("/BaseEncoding").getName();
                            }
                            if (oe.hasKey("/Differences") && oe.getKey("/Differences").isArray()) {
                                QPDFObjectHandle da = oe.getKey("/Differences");
                                const int daN = std::min(da.getArrayNItems(), 4096);
                                long long cur = 0;
                                for (int di = 0; di < daN; ++di) {
                                    QPDFObjectHandle item = da.getArrayItem(di);
                                    if (item.isInteger()) {
                                        cur = item.getIntValue();
                                    } else if (item.isName()) {
                                        if (cur >= 0 && cur <= 255) {
                                            oldDiffs[static_cast<int>(cur)] =
                                                item.getName().substr(1);
                                        }
                                        if (cur <= 255) {
                                            ++cur;
                                        }
                                    }
                                }
                            }
                        }
                        std::vector<std::pair<int, uint16_t>> tuEntries;
                        for (int cc = 0; cc <= 255; ++cc) {
                            uint32_t u = 0;
                            auto dit = oldDiffs.find(cc);
                            if (dit != oldDiffs.end()) {
                                u = aglNameToUnicode(dit->second);
                            } else if (oldBase == "/MacRomanEncoding") {
                                u = macRomanToUnicode(static_cast<uint8_t>(cc));
                            } else {
                                u = winAnsiToUnicode(static_cast<uint8_t>(cc));
                            }
                            if (u > 0 && u <= 0xFFFF) {
                                tuEntries.push_back({cc, static_cast<uint16_t>(u)});
                            }
                        }
                        if (!tuEntries.empty()) {
                            std::map<uint32_t, uint32_t> tuMap;
                            for (const auto& [code, uni] : tuEntries)
                                tuMap[static_cast<uint32_t>(code)] = uni;
                            f.replaceKey("/ToUnicode", QPDFObjectHandle::newStream(
                                                           &qpdf, jpdfium::font::buildToUnicodeCmap(
                                                                      tuMap, false)));
                        }
                    }
                    f.removeKey("/Encoding");
                    ++cmapFixedCount;
                }
                if (fd.hasKey("/FontFile2") && fd.getKey("/FontFile2").isStream()) {
                    QPDFObjectHandle ff2 = fd.getKey("/FontFile2");
                    try {
                        std::vector<uint8_t> prog;
                        if (readStreamBytesBounded(ff2, prog, qpdf_dl_generalized) &&
                            prog.size() > 12 && isSafeFontBuffer(prog)) {
                            FT_Face rawFace = nullptr;
                            if (FT_New_Memory_Face(ftLib, prog.data(),
                                                   static_cast<FT_Long>(prog.size()), 0,
                                                   &rawFace) == 0 &&
                                rawFace) {
                                loadedFace.reset(rawFace);
                                const std::set<uint32_t>* uPtr = nullptr;
                                if (f.isIndirect()) {
                                    auto uit = usedFontCodes.find(f.getObjGen());
                                    if (uit != usedFontCodes.end()) uPtr = &uit->second;
                                }
                                // Patch a copy: sfntEnsureCmap30 reallocates its font buffer, so
                                // the FreeType face must be released before prog is replaced.
                                std::vector<uint8_t> patched = prog;
                                if (sfntEnsureCmap30(patched, loadedFace.face, uPtr, true) &&
                                    !patched.empty()) {
                                    loadedFace.reset();
                                    prog = std::move(patched);
                                    QPDFObjectHandle newFf = ctx.internFontStream(prog);
                                    QPDFObjectHandle newFd =
                                        qpdf.makeIndirectObject(fd.shallowCopy());
                                    newFd.replaceKey("/FontFile2", newFf);
                                    f.replaceKey("/FontDescriptor", newFd);
                                    fd = std::move(newFd);
                                    ++cmapFixedCount;
                                    if (isSafeFontBuffer(prog)) {
                                        FT_Face reopened = nullptr;
                                        if (FT_New_Memory_Face(ftLib, prog.data(),
                                                               static_cast<FT_Long>(prog.size()), 0,
                                                               &reopened) == 0 &&
                                            reopened) {
                                            loadedFace.reset(reopened);
                                        }
                                    }
                                }
                                if (loadedFace.face) {
                                    loadedProg = std::move(prog);
                                }
                            }
                        }
                    } catch (const std::exception& e) {
                        std::fputs(e.what(), stderr);
                        std::fputc('\n', stderr);
                    } catch (...) {
                        std::fputs("jpdfium: FontFile2 cmap30 repair failed\n", stderr);
                    }
                }
            } else {
                if (!f.hasKey("/Encoding")) {
                    f.replaceKey("/Encoding", QPDFObjectHandle::newName("/WinAnsiEncoding"));
                    ++cmapFixedCount;
                } else if (f.getKey("/Encoding").isName()) {
                    std::string en = f.getKey("/Encoding").getName();
                    if (en != "/WinAnsiEncoding" && en != "/MacRomanEncoding") {
                        f.replaceKey("/Encoding", QPDFObjectHandle::newName("/WinAnsiEncoding"));
                        ++cmapFixedCount;
                    }
                } else if (f.getKey("/Encoding").isDictionary()) {
                    QPDFObjectHandle enc = f.getKey("/Encoding");
                    if (!enc.hasKey("/BaseEncoding") || !enc.getKey("/BaseEncoding").isName() ||
                        (enc.getKey("/BaseEncoding").getName() != "/WinAnsiEncoding" &&
                         enc.getKey("/BaseEncoding").getName() != "/MacRomanEncoding")) {
                        enc.replaceKey("/BaseEncoding",
                                       QPDFObjectHandle::newName("/WinAnsiEncoding"));
                        ++cmapFixedCount;
                    }
                }
            }
        }

        if ((flags & kFlagNormalizeCmaps) && sub == "/TrueType" && !isSymbolic &&
            fd.hasKey("/FontFile2")) {
            QPDFObjectHandle ff2 = fd.getKey("/FontFile2");
            try {
                std::vector<uint8_t> prog;
                if (readStreamBytesBounded(ff2, prog, qpdf_dl_generalized) && prog.size() > 12 &&
                    isSafeFontBuffer(prog)) {
                    FT_Face rawFace = nullptr;
                    if (FT_New_Memory_Face(ftLib, prog.data(), static_cast<FT_Long>(prog.size()), 0,
                                           &rawFace) == 0 &&
                        rawFace) {
                        jpdfium::font::FtFaceGuard ufaceGuard(rawFace);
                        std::vector<uint8_t> patched = prog;
                        bool fixed = sfntEnsureUnicodeCmap(patched, ufaceGuard.face);
                        ufaceGuard.reset();
                        if (fixed && !patched.empty()) {
                            prog = std::move(patched);
                            QPDFObjectHandle newFf = ctx.internFontStream(prog);
                            QPDFObjectHandle newFd = qpdf.makeIndirectObject(fd.shallowCopy());
                            newFd.replaceKey("/FontFile2", newFf);
                            f.replaceKey("/FontDescriptor", newFd);
                            fd = std::move(newFd);
                            ++cmapFixedCount;
                        }
                    }
                }
            } catch (const std::exception& e) {
                std::fputs(e.what(), stderr);
                std::fputc('\n', stderr);
            } catch (...) {
                std::fputs("jpdfium: FontFile2 unicode cmap repair failed\n", stderr);
            }
        }

        reconcileSimpleFontWidths(ctx, f, fd, sub, isSymbolic, fl,
                                  loadedFace.face ? &loadedProg : nullptr,
                                  loadedFace.face ? &loadedFace : nullptr);

        if ((flags & kFlagConvertPdfA1b) && sub == "/TrueType" && isSymbolic &&
            pruneSymbolicTrueTypeProgram(qpdf, f, fd)) {
            ++cmapFixedCount;
        }
    }
}

}  // namespace jpdfium::rewriter
