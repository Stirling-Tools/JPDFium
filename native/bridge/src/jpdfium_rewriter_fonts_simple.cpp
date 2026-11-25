// jpdfium_rewriter_fonts_simple.cpp - Simple font (TrueType, Type1, MMType1) rewriting stages
// (styled donor embedding, cmap/encoding normalization, .notdef repair, and /Widths reconciliation).

#include "jpdfium_font_rewriter_internal.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>

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

    if (sub != "/TrueType" && sub != "/Type1" && sub != "/MMType1") return;
    QPDFObjectHandle fd = f.getKey("/FontDescriptor");
    bool isEmb = (flags & kFlagEmbedMissing)
                     ? validateOrStripCorruptFontStreams(fd)
                     : (fd.isDictionary() &&
                        (fd.hasKey("/FontFile") || fd.hasKey("/FontFile2") || fd.hasKey("/FontFile3")));

    // Fix invalid stream /Encoding on a simple font (must be Name or Dictionary; fixes 6.2.11.6-2 / 6.2.11.8-1)
    if ((flags & kFlagNormalizeCmaps) && f.hasKey("/Encoding") && f.getKey("/Encoding").isStream()) {
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

    if ((flags & kFlagCleanMetadata) && !(flags & kFlagConvertPdfA1b) && fd.isDictionary() && fd.hasKey("/CharSet")) {
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
                             ? static_cast<int>(fd.getKey("/Flags").getIntValue()) : 32;
                if (isSymbolicDonor) {
                    fd.replaceKey("/Flags", QPDFObjectHandle::newInteger((fl & ~32) | 4));
                } else if (fl & 4) {
                    fd.replaceKey("/Flags", QPDFObjectHandle::newInteger((fl & ~4) | 32));
                }
            }
            f.replaceKey("/Subtype", QPDFObjectHandle::newName("/TrueType"));
            sub = "/TrueType";
            fd.replaceKey("/FontFile2", st);
            // Preserve existing /MacRomanEncoding, /WinAnsiEncoding, or /Differences dict when present;
            // for Symbol/Wingdings/Webdings donors, embed as symbolic TrueType (no /Encoding) + /ToUnicode;
            // for ZapfDingbats (no native symbolic donor), reset to WinAnsi + 0..31 -> /space.
            std::string lbfSym = toLowerAscii(bf);
            bool isStdSym = (lbfSym.find("symbol") != std::string::npos ||
                             lbfSym.find("dingbat") != std::string::npos);
            if (isSymbolicDonor) {
                if (f.hasKey("/Encoding")) f.removeKey("/Encoding");
                if (!f.hasKey("/ToUnicode")) {
                    static const uint16_t kAdobeSymbol[224] = {
                        // 0x20..0x7F
                        0x0020,0x0021,0x2200,0x0023,0x2203,0x0025,0x0026,0x220B,
                        0x0028,0x0029,0x2217,0x002B,0x002C,0x2212,0x002E,0x002F,
                        0x0030,0x0031,0x0032,0x0033,0x0034,0x0035,0x0036,0x0037,
                        0x0038,0x0039,0x003A,0x003B,0x003C,0x003D,0x003E,0x003F,
                        0x2245,0x0391,0x0392,0x03A7,0x0394,0x0395,0x03A6,0x0393,
                        0x0397,0x0399,0x03D1,0x039A,0x039B,0x039C,0x039D,0x039F,
                        0x03A0,0x0398,0x03A1,0x03A3,0x03A4,0x03A5,0x03C2,0x03A9,
                        0x039E,0x03A8,0x0396,0x005B,0x2234,0x005D,0x22A5,0x005F,
                        0xF8E5,0x03B1,0x03B2,0x03C7,0x03B4,0x03B5,0x03C6,0x03B3,
                        0x03B7,0x03B9,0x03D5,0x03BA,0x03BB,0x03BC,0x03BD,0x03BF,
                        0x03C0,0x03B8,0x03C1,0x03C3,0x03C4,0x03C5,0x03D6,0x03C9,
                        0x03BE,0x03C8,0x03B6,0x007B,0x007C,0x007D,0x223C,0x0000,
                        // 0x80..0x9F
                        0,0,0,0,0,0,0,0, 0,0,0,0,0,0,0,0,
                        0,0,0,0,0,0,0,0, 0,0,0,0,0,0,0,0,
                        // 0xA0..0xFF
                        0x20AC,0x03D2,0x2032,0x2264,0x2044,0x221E,0x0192,0x2663,
                        0x2666,0x2665,0x2660,0x2194,0x2190,0x2191,0x2192,0x2193,
                        0x00B0,0x00B1,0x2033,0x2265,0x00D7,0x221D,0x2202,0x2022,
                        0x00F7,0x2260,0x2261,0x2248,0x2026,0xF8E6,0xF8E7,0x21B5,
                        0x2135,0x2111,0x211C,0x2118,0x2297,0x2295,0x2205,0x2229,
                        0x222A,0x2283,0x2287,0x2284,0x2282,0x2286,0x2208,0x2209,
                        0x2220,0x2207,0xF6DA,0xF6D9,0xF6DB,0x220F,0x221A,0x22C5,
                        0x00AC,0x2227,0x2228,0x21D4,0x21D0,0x21D1,0x21D2,0x21D3,
                        0x25CA,0x2329,0xF8E8,0xF8E9,0xF8EA,0x2211,0xF8EB,0xF8EC,
                        0x239D,0x23A1,0x23A2,0x23A3,0x23A7,0x23A8,0x23A9,0x23AA,
                        0x0000,0x232A,0x222B,0x2320,0x23AE,0x2321,0x239E,0x239F,
                        0x23A0,0x23A4,0x23A5,0x23A6,0x23AB,0x23AC,0x23AD,0x0000
                    };
                    std::map<uint32_t, uint32_t> symMap;
                    for (int cc = 0x20; cc <= 0xFF; ++cc) {
                        uint16_t u = (dkey == "symbol")
                                         ? kAdobeSymbol[cc - 0x20]
                                         : static_cast<uint16_t>(0xF000 + cc);
                        if (u != 0) symMap[static_cast<uint32_t>(cc)] = u;
                    }
                    f.replaceKey("/ToUnicode", QPDFObjectHandle::newStream(
                                                   &qpdf, jpdfium::font::buildToUnicodeCmap(symMap, false)));
                }
            } else if (isStdSym) {
                QPDFObjectHandle encDict = QPDFObjectHandle::newDictionary();
                encDict.replaceKey("/Type", QPDFObjectHandle::newName("/Encoding"));
                encDict.replaceKey("/BaseEncoding", QPDFObjectHandle::newName("/WinAnsiEncoding"));
                QPDFObjectHandle diffs = QPDFObjectHandle::newArray();
                diffs.appendItem(QPDFObjectHandle::newInteger(0));
                for (int cc = 0; cc < 32; ++cc) diffs.appendItem(QPDFObjectHandle::newName("/space"));
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
                    for (int di = 0; di < diffs.getArrayNItems(); ++di) {
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
                    wArr.appendItem(QPDFObjectHandle::newInteger(donor->winAnsiWidths[static_cast<size_t>(ci)]));
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
                     ? static_cast<int>(fd.getKey("/Flags").getIntValue()) : 32;
        bool isSymbolic = (fl & 4) != 0;

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
                                int cur = 0;
                                for (int di = 0; di < da.getArrayNItems(); ++di) {
                                    QPDFObjectHandle item = da.getArrayItem(di);
                                    if (item.isInteger()) {
                                        cur = static_cast<int>(item.getIntValue());
                                    } else if (item.isName()) {
                                        if (cur >= 0 && cur <= 255) {
                                            oldDiffs[cur] = item.getName().substr(1);
                                        }
                                        ++cur;
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
                            for (const auto& [code, uni] : tuEntries) tuMap[static_cast<uint32_t>(code)] = uni;
                            f.replaceKey("/ToUnicode", QPDFObjectHandle::newStream(
                                                           &qpdf, jpdfium::font::buildToUnicodeCmap(tuMap, false)));
                        }
                    }
                    f.removeKey("/Encoding");
                    ++cmapFixedCount;
                }
                if (fd.hasKey("/FontFile2") && fd.getKey("/FontFile2").isStream()) {
                    QPDFObjectHandle ff2 = fd.getKey("/FontFile2");
                    try {
                        auto buf = ff2.getStreamData(qpdf_dl_generalized);
                        if (buf && buf->getSize() > 12) {
                            std::vector<uint8_t> prog(buf->getBuffer(), buf->getBuffer() + buf->getSize());
                            if (isSafeFontBuffer(prog)) {
                                FT_Face face = nullptr;
                                FT_New_Memory_Face(ftLib, prog.data(), static_cast<FT_Long>(prog.size()), 0, &face);
                                const std::set<uint32_t>* uPtr = nullptr;
                                if (f.isIndirect()) {
                                    auto uit = usedFontCodes.find(f.getObjGen());
                                    if (uit != usedFontCodes.end()) uPtr = &uit->second;
                                }
                                if (sfntEnsureCmap30(prog, face, uPtr, true)) {
                                    std::string ns(reinterpret_cast<const char*>(prog.data()), prog.size());
                                    QPDFObjectHandle newFf = QPDFObjectHandle::newStream(&qpdf, ns);
                                    newFf.getDict().replaceKey("/Length1", QPDFObjectHandle::newInteger(static_cast<long long>(prog.size())));
                                    QPDFObjectHandle newFd = qpdf.makeIndirectObject(fd.shallowCopy());
                                    newFd.replaceKey("/FontFile2", newFf);
                                    f.replaceKey("/FontDescriptor", newFd);
                                    fd = newFd;
                                    ++cmapFixedCount;
                                }
                                if (face) FT_Done_Face(face);
                            }
                        }
                    } catch (...) {
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
                        enc.replaceKey("/BaseEncoding", QPDFObjectHandle::newName("/WinAnsiEncoding"));
                        ++cmapFixedCount;
                    }
                }
            }
        }

        reconcileSimpleFontWidths(ctx, f, fd, sub, isSymbolic, fl);
    }
}

}  // namespace jpdfium::rewriter
