// jpdfium_rewriter_pdfa.cpp - PDF/A-2b document structure normalization:
// XMP/Info sync, OutputIntents, ColorSpace/Group/Shading/DeviceN/ICC repair,
// AcroForm/Action/OCProperties/Annotation cleanup, and UTF-8 name sanitization.

#include <algorithm>
#include <array>
#include <cctype>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <functional>
#include <qpdf/QPDFAcroFormDocumentHelper.hh>
#include <qpdf/QPDFPageDocumentHelper.hh>
#include <sstream>

#include "jpdfium_font_rewriter_internal.h"

namespace jpdfium::rewriter {

namespace {

bool isStreamSpaceOrDelim(char c) {
    return std::isspace(static_cast<unsigned char>(c)) != 0 || c == '[' || c == ']' || c == '<' ||
           c == '>' || c == '(' || c == ')' || c == '/' || c == '%';
}

size_t skipInlineImagePayload(std::string_view sv, size_t biPos) {
    // biPos points to 'B' of "BI". Advance to "ID" followed by single whitespace,
    // then scan for whitespace + "EI" + (whitespace|delim|EOF).
    const size_t n = sv.size();
    size_t i = biPos + 2;
    while (i + 1 < n) {
        if (sv[i] == 'I' && sv[i + 1] == 'D' && isStreamSpaceOrDelim(sv[i - 1]) &&
            (i + 2 == n || std::isspace(static_cast<unsigned char>(sv[i + 2])) != 0)) {
            i += 2;
            if (i < n && std::isspace(static_cast<unsigned char>(sv[i])) != 0) {
                ++i;
            }
            while (i + 2 <= n) {
                if (sv[i] == 'E' && sv[i + 1] == 'I' && i > biPos + 2 &&
                    std::isspace(static_cast<unsigned char>(sv[i - 1])) != 0 &&
                    (i + 2 == n || isStreamSpaceOrDelim(sv[i + 2]))) {
                    return i + 2;
                }
                ++i;
            }
            return n;
        }
        ++i;
    }
    return n;
}

constexpr std::array<uint16_t, 207> kZapfDingbatsA = {
    0x0000, 0x2701, 0x2702, 0x2704, 0x260E, 0x2706, 0x271D, 0x271E, 0x271F, 0x2720, 0x2721, 0x261B,
    0x261E, 0x270C, 0x270D, 0x270E, 0x270F, 0x2711, 0x2712, 0x2713, 0x2714, 0x2715, 0x2716, 0x2717,
    0x2718, 0x2719, 0x271A, 0x271B, 0x271C, 0x2722, 0x2723, 0x2724, 0x2725, 0x2726, 0x2727, 0x2605,
    0x2729, 0x272A, 0x272B, 0x272C, 0x272D, 0x272E, 0x272F, 0x2730, 0x2731, 0x2732, 0x2733, 0x2734,
    0x2735, 0x2736, 0x2737, 0x2738, 0x2739, 0x273A, 0x273B, 0x273C, 0x273D, 0x273E, 0x273F, 0x2740,
    0x2741, 0x2742, 0x2743, 0x2744, 0x2745, 0x2746, 0x2747, 0x2748, 0x2749, 0x274A, 0x274B, 0x25CF,
    0x274D, 0x25A0, 0x274F, 0x2751, 0x25B2, 0x25BC, 0x25C6, 0x2756, 0x0000, 0x25D7, 0x2758, 0x2759,
    0x275A, 0x276F, 0x2771, 0x2772, 0x2773, 0x2768, 0x2769, 0x276C, 0x276D, 0x276A, 0x276B, 0x2774,
    0x2775, 0x275B, 0x275C, 0x275D, 0x275E, 0x2761, 0x2762, 0x2763, 0x2764, 0x2710, 0x2765, 0x2766,
    0x2767, 0x2660, 0x2665, 0x2666, 0x2663, 0x0000, 0x0000, 0x0000, 0x0000, 0x2709, 0x2708, 0x2707,
    0x2460, 0x2461, 0x2462, 0x2463, 0x2464, 0x2465, 0x2466, 0x2467, 0x2468, 0x2469, 0x2776, 0x2777,
    0x2778, 0x2779, 0x277A, 0x277B, 0x277C, 0x277D, 0x277E, 0x277F, 0x2780, 0x2781, 0x2782, 0x2783,
    0x2784, 0x2785, 0x2786, 0x2787, 0x2788, 0x2789, 0x278A, 0x278B, 0x278C, 0x278D, 0x278E, 0x278F,
    0x2790, 0x2791, 0x2792, 0x2793, 0x2794, 0x2192, 0x27A3, 0x2194, 0x2195, 0x2799, 0x279B, 0x279C,
    0x279D, 0x279E, 0x279F, 0x27A0, 0x27A1, 0x27A2, 0x27A4, 0x27A5, 0x27A6, 0x27A7, 0x27A8, 0x27A9,
    0x27AB, 0x27AD, 0x27AF, 0x27B2, 0x27B3, 0x27B5, 0x27B8, 0x27BA, 0x27BB, 0x27BC, 0x27BD, 0x27BE,
    0x279A, 0x27AA, 0x27B6, 0x27B9, 0x2798, 0x27B4, 0x27B7, 0x27AC, 0x27AE, 0x27B1, 0x2703, 0x2750,
    0x2752, 0x276E, 0x2770};

constexpr std::array<uint16_t, 256> kZapfDingbatsCode = {
    0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000,
    0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000,
    0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0020, 0x2701, 0x2702, 0x2703,
    0x2704, 0x260E, 0x2706, 0x2707, 0x2708, 0x2709, 0x261B, 0x261E, 0x270C, 0x270D, 0x270E, 0x270F,
    0x2710, 0x2711, 0x2712, 0x2713, 0x2714, 0x2715, 0x2716, 0x2717, 0x2718, 0x2719, 0x271A, 0x271B,
    0x271C, 0x271D, 0x271E, 0x271F, 0x2720, 0x2721, 0x2722, 0x2723, 0x2724, 0x2725, 0x2726, 0x2727,
    0x2605, 0x2729, 0x272A, 0x272B, 0x272C, 0x272D, 0x272E, 0x272F, 0x2730, 0x2731, 0x2732, 0x2733,
    0x2734, 0x2735, 0x2736, 0x2737, 0x2738, 0x2739, 0x273A, 0x273B, 0x273C, 0x273D, 0x273E, 0x273F,
    0x2740, 0x2741, 0x2742, 0x2743, 0x2744, 0x2745, 0x2746, 0x2747, 0x2748, 0x2749, 0x274A, 0x274B,
    0x25CF, 0x274D, 0x25A0, 0x274F, 0x2750, 0x2751, 0x2752, 0x25B2, 0x25BC, 0x25C6, 0x2756, 0x25D7,
    0x2758, 0x2759, 0x275A, 0x275B, 0x275C, 0x275D, 0x275E, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000,
    0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000,
    0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000,
    0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x2761, 0x2762, 0x2763, 0x2764, 0x2765, 0x2766, 0x2767,
    0x2663, 0x2666, 0x2665, 0x2660, 0x2460, 0x2461, 0x2462, 0x2463, 0x2464, 0x2465, 0x2466, 0x2467,
    0x2468, 0x2469, 0x2776, 0x2777, 0x2778, 0x2779, 0x277A, 0x277B, 0x277C, 0x277D, 0x277E, 0x277F,
    0x2780, 0x2781, 0x2782, 0x2783, 0x2784, 0x2785, 0x2786, 0x2787, 0x2788, 0x2789, 0x278A, 0x278B,
    0x278C, 0x278D, 0x278E, 0x278F, 0x2790, 0x2791, 0x2792, 0x2793, 0x2794, 0x2192, 0x2194, 0x2195,
    0x2798, 0x2799, 0x279A, 0x279B, 0x279C, 0x279D, 0x279E, 0x279F, 0x27A0, 0x27A1, 0x27A2, 0x27A3,
    0x27A4, 0x27A5, 0x27A6, 0x27A7, 0x27A8, 0x27A9, 0x27AA, 0x27AB, 0x27AC, 0x27AD, 0x27AE, 0x27AF,
    0x0000, 0x27B1, 0x27B2, 0x27B3, 0x27B4, 0x27B5, 0x27B6, 0x27B7, 0x27B8, 0x27B9, 0x27BA, 0x27BB,
    0x27BC, 0x27BD, 0x27BE, 0x0000};

uint32_t zapfDingbatsNameToUnicode(std::string_view name) {
    if (name == "space") return 0x0020U;
    if (name.size() < 2 || name.size() > 4 || name[0] != 'a') return 0U;
    int idx = 0;
    for (size_t i = 1; i < name.size(); ++i) {
        if (name[i] < '0' || name[i] > '9') return 0U;
        idx = idx * 10 + (name[i] - '0');
    }
    if (idx < 1 || idx >= static_cast<int>(kZapfDingbatsA.size())) return 0U;
    return kZapfDingbatsA[static_cast<size_t>(idx)];
}

bool isAdobeSymbolBaseFont(const std::string& bf) {
    std::string s = bf;
    if (!s.empty() && s[0] == '/') s.erase(0, 1);
    if (s.size() > 7 && s[6] == '+') {
        bool allUpper = true;
        for (size_t i = 0; i < 6; ++i) {
            if (s[i] < 'A' || s[i] > 'Z') {
                allUpper = false;
                break;
            }
        }
        if (allUpper) s.erase(0, 7);
    }
    std::string lbf = toLowerAscii(s);
    return lbf == "symbol" || lbf == "symbolmt" || lbf.rfind("symbol,", 0) == 0 ||
           lbf.rfind("symbol-", 0) == 0 || lbf.rfind("symbolmt,", 0) == 0 ||
           lbf.rfind("symbolmt-", 0) == 0 || lbf.rfind("symbolps", 0) == 0;
}

bool isZapfDingbatsBaseFont(const std::string& bf) {
    return toLowerAscii(bf).find("dingbat") != std::string::npos;
}

bool isGidEmptyOutline(FT_Face face, FT_UInt gid) {
    if (!face || gid >= static_cast<FT_UInt>(face->num_glyphs)) return false;
    if (FT_Load_Glyph(face, gid, FT_LOAD_NO_SCALE | FT_LOAD_NO_BITMAP) != 0) return false;
    return face->glyph->format == FT_GLYPH_FORMAT_OUTLINE && face->glyph->outline.n_contours == 0;
}

}  // namespace

void applyPdfAProfileExtensions(PdfAStructureContext& pctx) {
    QPDF& qpdf = pctx.qpdf;
    QPDFObjectHandle& root = pctx.root;
    int pdfaPart = pctx.pdfaPart;
    char pdfaConformance = pctx.pdfaConformance;
    bool& annotsFixed = pctx.annotsFixed;
    auto& transforms = pctx.transforms;
    pctx.docIndex.build(qpdf);
    const auto& allObjects = pctx.docIndex.allObjects;

    // 6d. PDF/A-1b (ISO 19005-1) additional restrictions:
    //     forbid transparency (SMask, CA/ca < 1, non-Normal BM, Transparency /Group),
    //     forbid Optional Content (/OC), and strip forbidden/post-1.4 annotation subtypes.
    if (pdfaPart == 1) {
        bool pdfa1bTransparencyStripped = false;
        auto cleanExtGState1b = [&](QPDFObjectHandle gs) {
            if (!gs.isDictionary()) return;
            if (gs.hasKey("/SMask") && !gs.getKey("/SMask").isNameAndEquals("/None")) {
                gs.replaceKey("/SMask", QPDFObjectHandle::newName("/None"));
                pdfa1bTransparencyStripped = true;
            }
            if (gs.hasKey("/CA") && gs.getKey("/CA").isNumber() &&
                gs.getKey("/CA").getNumericValue() < 0.9999) {
                gs.replaceKey("/CA", QPDFObjectHandle::newReal("1.0"));
                pdfa1bTransparencyStripped = true;
            }
            if (gs.hasKey("/ca") && gs.getKey("/ca").isNumber() &&
                gs.getKey("/ca").getNumericValue() < 0.9999) {
                gs.replaceKey("/ca", QPDFObjectHandle::newReal("1.0"));
                pdfa1bTransparencyStripped = true;
            }
            if (gs.hasKey("/BM")) {
                QPDFObjectHandle bm = gs.getKey("/BM");
                if (!bm.isNameAndEquals("/Normal") && !bm.isNameAndEquals("/Compatible")) {
                    gs.replaceKey("/BM", QPDFObjectHandle::newName("/Normal"));
                    pdfa1bTransparencyStripped = true;
                }
            }
        };
        for (const auto& obj : allObjects) {
            QPDFObjectHandle d = obj.isStream()
                                     ? obj.getDict()
                                     : (obj.isDictionary() ? obj : QPDFObjectHandle::newNull());
            if (d.isNull()) continue;
            if (d.hasKey("/OC")) d.removeKey("/OC");
            if (d.hasKey("/Group") && d.getKey("/Group").isDictionary() &&
                d.getKey("/Group").getKey("/S").isNameAndEquals("/Transparency")) {
                d.removeKey("/Group");
                pdfa1bTransparencyStripped = true;
            }
            if (obj.isStream() && d.getKey("/Subtype").isNameAndEquals("/Image")) {
                if (d.hasKey("/SMask")) {
                    d.removeKey("/SMask");
                    pdfa1bTransparencyStripped = true;
                }
                if (d.hasKey("/SMaskInData")) {
                    d.removeKey("/SMaskInData");
                    pdfa1bTransparencyStripped = true;
                }
            }
            if (d.getKey("/Type").isNameAndEquals("/ExtGState") || d.hasKey("/CA") ||
                d.hasKey("/ca") || d.hasKey("/BM") || (d.hasKey("/SMask") && !obj.isStream())) {
                cleanExtGState1b(d);
            }
            // Also clean direct /ExtGState dicts inside /Resources (fixes 6.4-4 on direct GS
            // dicts).
            if (d.hasKey("/Resources") && d.getKey("/Resources").isDictionary()) {
                QPDFObjectHandle egs = d.getKey("/Resources").getKey("/ExtGState");
                if (egs.isDictionary()) {
                    for (const auto& gk : egs.getKeys()) {
                        cleanExtGState1b(egs.getKey(gk));
                    }
                }
            }
            if (d.getKey("/Type").isNameAndEquals("/Page") && d.hasKey("/Annots") &&
                d.getKey("/Annots").isArray()) {
                QPDFObjectHandle annots = d.getKey("/Annots");
                QPDFObjectHandle filtered = QPDFObjectHandle::newArray();
                bool removed = false;
                const int nAnnots = std::min(annots.getArrayNItems(), 4096);
                for (int ai = 0; ai < nAnnots; ++ai) {
                    QPDFObjectHandle an = annots.getArrayItem(ai);
                    if (an.isDictionary() && an.getKey("/Subtype").isName()) {
                        std::string asub = an.getKey("/Subtype").getName();
                        // ISO 19005-1 6.5.2-1: allow only PDF 1.4 permitted annotation subtypes.
                        static const std::set<std::string> kPdfA1bAllowedAnnots = {
                            "/Text",     "/Link",      "/FreeText",    "/Line",
                            "/Square",   "/Circle",    "/Highlight",   "/Underline",
                            "/Squiggly", "/StrikeOut", "/Stamp",       "/Ink",
                            "/Popup",    "/Widget",    "/PrinterMark", "/TrapNet"};
                        if (!kPdfA1bAllowedAnnots.count(asub)) {
                            removed = true;
                            continue;
                        }
                    }
                    filtered.appendItem(an);
                }
                if (removed || nAnnots < annots.getArrayNItems()) {
                    d.replaceKey("/Annots", filtered);
                    annotsFixed = true;
                }
            }
        }
        // PDF/A-1b (ISO 19005-1 6.1.12-5): drop /StructTreeRoot when oversized arrays (> 8191)
        // exist.
        if (pdfaConformance == 'B' && root.hasKey("/StructTreeRoot") &&
            pctx.docIndex.hasOversizedArray) {
            root.removeKey("/StructTreeRoot");
            root.removeKey("/MarkInfo");
        }
        // Clamp out-of-range real numbers (|x| > 32767.0) in content streams for PDF/A-1b (ISO
        // 19005-1 6.1.12-2).
        auto clampContentStreamReals1b = [](QPDFObjectHandle st) {
            if (!st.isStream()) return;
            std::string bufStr;
            if (!readStreamBounded(st, bufStr, qpdf_dl_generalized, size_t{16} * 1024U * 1024U) ||
                bufStr.empty()) {
                return;
            }
            std::string_view sv(bufStr);
            std::string out;
            bool dirty = false;
            size_t i = 0;
            const size_t n = sv.size();
            bool inStr = false;
            int parenDepth = 0;
            bool inHex = false;
            while (i < n) {
                char c = sv[i];
                if (inStr) {
                    if (c == '\\' && i + 1 < n) {
                        if (dirty) {
                            out.push_back(c);
                            out.push_back(sv[i + 1]);
                        }
                        i += 2;
                        continue;
                    }
                    if (c == '(')
                        ++parenDepth;
                    else if (c == ')') {
                        if (--parenDepth <= 0) inStr = false;
                    }
                    if (dirty) out.push_back(c);
                    ++i;
                    continue;
                }
                if (inHex) {
                    if (c == '>') inHex = false;
                    if (dirty) out.push_back(c);
                    ++i;
                    continue;
                }
                if (c == '%') {
                    size_t commentEnd = i;
                    while (commentEnd < n && sv[commentEnd] != '\r' && sv[commentEnd] != '\n') {
                        ++commentEnd;
                    }
                    if (dirty) out.append(sv.substr(i, commentEnd - i));
                    i = commentEnd;
                    continue;
                }
                if (c == '(') {
                    inStr = true;
                    parenDepth = 1;
                    if (dirty) out.push_back(c);
                    ++i;
                    continue;
                }
                if (c == '<' && (i + 1 >= n || sv[i + 1] != '<')) {
                    inHex = true;
                    if (dirty) out.push_back(c);
                    ++i;
                    continue;
                }
                if (c == 'B' && i + 1 < n && sv[i + 1] == 'I' &&
                    (i == 0 || isStreamSpaceOrDelim(sv[i - 1])) &&
                    (i + 2 == n || isStreamSpaceOrDelim(sv[i + 2]))) {
                    size_t biEnd = skipInlineImagePayload(sv, i);
                    if (dirty) out.append(sv.substr(i, biEnd - i));
                    i = biEnd;
                    continue;
                }
                if ((c == '-' || c == '+' || (c >= '0' && c <= '9')) &&
                    (i == 0 || std::isspace(static_cast<unsigned char>(sv[i - 1])) ||
                     sv[i - 1] == '[' || sv[i - 1] == ']' || sv[i - 1] == '/')) {
                    size_t j = i + ((c == '-' || c == '+') ? 1 : 0);
                    bool hasDot = false;
                    size_t digits = 0;
                    while (j < n && ((sv[j] >= '0' && sv[j] <= '9') || (sv[j] == '.' && !hasDot))) {
                        if (sv[j] == '.')
                            hasDot = true;
                        else
                            ++digits;
                        ++j;
                    }
                    if (hasDot && digits >= 5 &&
                        (j == n || std::isspace(static_cast<unsigned char>(sv[j])) ||
                         sv[j] == ']' || sv[j] == '[' || sv[j] == '/')) {
                        double val = std::strtod(std::string(sv.substr(i, j - i)).c_str(), nullptr);
                        if (std::isfinite(val) && std::abs(val) > 32767.0) {
                            if (!dirty) {
                                out.reserve(n);
                                out.append(sv.substr(0, i));
                                dirty = true;
                            }
                            out.append(val < 0 ? "-32767.0" : "32767.0");
                            i = j;
                            continue;
                        }
                    }
                }
                if (dirty) out.push_back(c);
                ++i;
            }
            if (dirty) {
                st.replaceStreamData(out, QPDFObjectHandle::newNull(), QPDFObjectHandle::newNull());
            }
        };
        for (auto& ph : QPDFPageDocumentHelper(qpdf).getAllPages()) {
            QPDFObjectHandle c = ph.getObjectHandle().getKey("/Contents");
            if (c.isStream())
                clampContentStreamReals1b(c);
            else if (c.isArray()) {
                const int nContents = std::min(c.getArrayNItems(), 4096);
                for (int ci = 0; ci < nContents; ++ci)
                    clampContentStreamReals1b(c.getArrayItem(ci));
            }
        }
        // Also clamp out-of-range real numbers in Form XObject streams and indirect dicts/arrays
        // (6.1.12-2).
        size_t clampNodeCount = 0;
        std::function<void(QPDFObjectHandle, int)> clampObjReals1b = [&](QPDFObjectHandle o,
                                                                         int depth) {
            if (depth > 16 || clampNodeCount >= kMaxRewriterGraphNodes) return;
            ++clampNodeCount;
            if (o.isArray()) {
                const int nArr = std::min(o.getArrayNItems(), 8192);
                for (int i = 0; i < nArr && clampNodeCount < kMaxRewriterGraphNodes; ++i) {
                    QPDFObjectHandle it = o.getArrayItem(i);
                    if (it.isReal()) {
                        double v = it.getNumericValue();
                        if (std::isfinite(v) && std::abs(v) > 32767.0) {
                            o.setArrayItem(
                                i, QPDFObjectHandle::newReal(v < 0 ? "-32767.0" : "32767.0"));
                        }
                    } else if (!it.isIndirect() && (it.isArray() || it.isDictionary())) {
                        clampObjReals1b(it, depth + 1);
                    }
                }
            } else if (o.isDictionary()) {
                for (const auto& k : o.getKeys()) {
                    if (clampNodeCount >= kMaxRewriterGraphNodes) return;
                    QPDFObjectHandle it = o.getKey(k);
                    if (it.isReal()) {
                        double v = it.getNumericValue();
                        if (std::isfinite(v) && std::abs(v) > 32767.0) {
                            o.replaceKey(k,
                                         QPDFObjectHandle::newReal(v < 0 ? "-32767.0" : "32767.0"));
                        }
                    } else if (!it.isIndirect() && (it.isArray() || it.isDictionary())) {
                        clampObjReals1b(it, depth + 1);
                    }
                }
            }
        };
        for (const auto& obj : allObjects) {
            if (clampNodeCount >= kMaxRewriterGraphNodes) break;
            if (obj.isStream()) {
                QPDFObjectHandle d = obj.getDict();
                clampObjReals1b(d, 0);
                if (d.getKey("/Subtype").isNameAndEquals("/Form") ||
                    (d.hasKey("/PatternType") && d.getKey("/PatternType").isInteger() &&
                     d.getKey("/PatternType").getIntValue() == 1)) {
                    clampContentStreamReals1b(obj);
                }
            } else if (obj.isDictionary() || obj.isArray()) {
                clampObjReals1b(obj, 0);
            }
        }
        // Type 3 glyph procedures are content streams too, but they are not Form
        // XObjects and therefore are not covered by the loop above.
        for (auto& obj : qpdf.getAllObjects()) {
            if (!obj.isDictionary() || !obj.getKey("/Type").isNameAndEquals("/Font") ||
                !obj.getKey("/Subtype").isNameAndEquals("/Type3")) {
                continue;
            }
            QPDFObjectHandle charProcs = obj.getKey("/CharProcs");
            if (!charProcs.isDictionary()) continue;
            for (const auto& cpk : charProcs.getKeys()) {
                clampContentStreamReals1b(charProcs.getKey(cpk));
            }
        }
        if (pdfa1bTransparencyStripped) transforms.push_back("pdfa1b_strip_transparency");
    }

    // 6e. PDF/A-3b (ISO 19005-3 Section 6.8): preserve embedded file attachments while ensuring
    //     every /Filespec has /F, /UF, /AFRelationship, valid /EF stream /Subtype + /Params
    //     /ModDate, and is associated via /Catalog /AF.
    if (pdfaPart == 3) {
        auto guessMime = [](const std::string& fname) -> std::string {
            std::string lo = toLowerAscii(fname);
            auto endsWith = [&](const char* sfx) {
                size_t sl = std::strlen(sfx);
                return lo.size() >= sl && lo.compare(lo.size() - sl, sl, sfx) == 0;
            };
            if (endsWith(".xml")) return "/text/xml";
            if (endsWith(".pdf")) return "/application/pdf";
            if (endsWith(".txt") || endsWith(".csv")) return "/text/plain";
            if (endsWith(".json")) return "/application/json";
            if (endsWith(".html") || endsWith(".htm")) return "/text/html";
            if (endsWith(".png")) return "/image/png";
            if (endsWith(".jpg") || endsWith(".jpeg")) return "/image/jpeg";
            return "/application/octet-stream";
        };
        QPDFObjectHandle afArr = (root.hasKey("/AF") && root.getKey("/AF").isArray())
                                     ? root.getKey("/AF")
                                     : QPDFObjectHandle::newArray();
        std::set<QPDFObjGen> inAf;
        for (int i = 0; i < afArr.getArrayNItems(); ++i) {
            QPDFObjectHandle it = afArr.getArrayItem(i);
            if (it.isIndirect()) inAf.insert(it.getObjGen());
        }
        for (const auto& obj : allObjects) {
            QPDFObjectHandle d = obj.isStream()
                                     ? obj.getDict()
                                     : (obj.isDictionary() ? obj : QPDFObjectHandle::newNull());
            if (d.isNull()) continue;
            bool isFilespec = d.getKey("/Type").isNameAndEquals("/Filespec") ||
                              (d.hasKey("/EF") && d.getKey("/EF").isDictionary());
            if (!isFilespec) continue;
            if (!d.hasKey("/Type")) d.replaceKey("/Type", QPDFObjectHandle::newName("/Filespec"));
            if (!d.hasKey("/AFRelationship") || !d.getKey("/AFRelationship").isName()) {
                d.replaceKey("/AFRelationship", QPDFObjectHandle::newName("/Unspecified"));
            }
            if (!d.hasKey("/F") && d.hasKey("/UF")) d.replaceKey("/F", d.getKey("/UF"));
            if (!d.hasKey("/UF") && d.hasKey("/F")) d.replaceKey("/UF", d.getKey("/F"));
            if (!d.hasKey("/F") && !d.hasKey("/UF")) {
                d.replaceKey("/F", QPDFObjectHandle::newString("attachment.bin"));
                d.replaceKey("/UF", QPDFObjectHandle::newUnicodeString("attachment.bin"));
            }
            std::string fname =
                d.getKey("/UF").isString()
                    ? d.getKey("/UF").getUTF8Value()
                    : (d.getKey("/F").isString() ? d.getKey("/F").getUTF8Value() : "");
            if (d.hasKey("/EF") && d.getKey("/EF").isDictionary()) {
                QPDFObjectHandle ef = d.getKey("/EF");
                for (const char* efKey : {"/F", "/UF"}) {
                    if (!ef.hasKey(efKey)) continue;
                    QPDFObjectHandle efs = ef.getKey(efKey);
                    if (!efs.isStream()) continue;
                    QPDFObjectHandle efd = efs.getDict();
                    if (!efd.hasKey("/Type"))
                        efd.replaceKey("/Type", QPDFObjectHandle::newName("/EmbeddedFile"));
                    auto isValidMimeName = [](const std::string& name) -> bool {
                        if (name.size() < 4 || name[0] != '/') return false;
                        std::string_view sv(name.data() + 1, name.size() - 1);
                        size_t slash = sv.find('/');
                        if (slash == std::string_view::npos || slash == 0 ||
                            slash + 1 >= sv.size()) {
                            return false;
                        }
                        if (sv.find('/', slash + 1) != std::string_view::npos) return false;
                        for (unsigned char ch : sv) {
                            if (ch <= 0x20 || ch >= 0x7F || ch == '#' || ch == '(' || ch == ')' ||
                                ch == '<' || ch == '>' || ch == '[' || ch == ']' || ch == '{' ||
                                ch == '}' || ch == '%') {
                                return false;
                            }
                        }
                        return true;
                    };
                    std::string curSub = (efd.hasKey("/Subtype") && efd.getKey("/Subtype").isName())
                                             ? efd.getKey("/Subtype").getName()
                                             : "";
                    if (!isValidMimeName(curSub)) {
                        std::string unesc = curSub;
                        for (size_t p = 0; (p = unesc.find("#2F", p)) != std::string::npos;) {
                            unesc.replace(p, 3, "/");
                        }
                        for (size_t p = 0; (p = unesc.find("#2f", p)) != std::string::npos;) {
                            unesc.replace(p, 3, "/");
                        }
                        std::string fixedMime = isValidMimeName(unesc) ? unesc : guessMime(fname);
                        efd.replaceKey("/Subtype", QPDFObjectHandle::newName(fixedMime));
                    }
                    QPDFObjectHandle params =
                        (efd.hasKey("/Params") && efd.getKey("/Params").isDictionary())
                            ? efd.getKey("/Params")
                            : QPDFObjectHandle::newDictionary();
                    if (!params.hasKey("/ModDate") || !params.getKey("/ModDate").isString()) {
                        params.replaceKey("/ModDate",
                                          QPDFObjectHandle::newString("D:20260101000000Z"));
                    }
                    if (!params.hasKey("/Size") || !params.getKey("/Size").isInteger()) {
                        std::vector<uint8_t> sb;
                        if (readStreamBytesBounded(efs, sb, qpdf_dl_generalized)) {
                            params.replaceKey("/Size", QPDFObjectHandle::newInteger(
                                                           static_cast<long long>(sb.size())));
                        }
                    }
                    efd.replaceKey("/Params", params);
                }
            }
            if (obj.isIndirect() && inAf.insert(obj.getObjGen()).second) {
                afArr.appendItem(obj);
            }
        }
        if (afArr.getArrayNItems() > 0) {
            root.replaceKey("/AF", afArr);
            transforms.push_back("pdfa3b_preserve_attachments");
        }
    }

    // 6f. PDF/A Level U / A: ensure every font has valid, non-PUA Unicode mapping (/ToUnicode)
    if (pdfaConformance == 'U' || pdfaConformance == 'A') {
        auto isValidPdfAUnicode = [](uint32_t u) -> bool {
            if (u == 0 || u == 0xFEFF || u == 0xFFFE || u == 0xFFFF || u == 0xFFFD) return false;
            if (u >= 0xE000 && u <= 0xF8FF) return false;     // BMP Private Use Area
            if (u >= 0xD800 && u <= 0xDFFF) return false;     // surrogates
            if (u >= 0xF0000 && u <= 0x10FFFF) return false;  // supplementary PUA planes 15-16
            if ((u & 0xFFFFU) == 0xFFFEU || (u & 0xFFFFU) == 0xFFFFU) return false;
            return u <= 0x10FFFFU;
        };
        auto hex4 = [](uint32_t v) -> std::string {
            char buf[8];
            std::snprintf(buf, sizeof(buf), "%04X", v & 0xFFFFU);
            return std::string(buf);
        };
        auto hex2 = [](uint32_t v) -> std::string {
            char buf[8];
            std::snprintf(buf, sizeof(buf), "%02X", v & 0xFFU);
            return std::string(buf);
        };
        auto parseSimpleEncoding = [&](const QPDFObjectHandle& fontObj, bool& hasEnc,
                                       bool& hasExplicitBase, std::string& baseEnc,
                                       std::map<int, std::string>& diffNames) {
            hasEnc = false;
            hasExplicitBase = false;
            baseEnc.clear();
            diffNames.clear();
            QPDFObjectHandle enc = fontObj.getKey("/Encoding");
            if (enc.isName()) {
                hasEnc = true;
                hasExplicitBase = true;
                baseEnc = enc.getName();
            } else if (enc.isDictionary()) {
                hasEnc = true;
                if (enc.getKey("/BaseEncoding").isName()) {
                    hasExplicitBase = true;
                    baseEnc = enc.getKey("/BaseEncoding").getName();
                }
                QPDFObjectHandle diffs = enc.getKey("/Differences");
                if (diffs.isArray()) {
                    long long code = 0;
                    const int nDiffs = std::min(diffs.getArrayNItems(), 4096);
                    for (int i = 0; i < nDiffs; ++i) {
                        QPDFObjectHandle it = diffs.getArrayItem(i);
                        if (it.isInteger())
                            code = it.getIntValue();
                        else if (it.isName()) {
                            std::string gn = it.getName();
                            if (!gn.empty() && gn[0] == '/') gn.erase(0, 1);
                            if (code >= 0 && code < 256) diffNames[static_cast<int>(code)] = gn;
                            if (code <= 255) ++code;
                        }
                    }
                }
            }
        };
        auto buildSimpleToUnicode = [&](const QPDFObjectHandle& fontObj) -> std::string {
            std::string sub =
                fontObj.getKey("/Subtype").isName() ? fontObj.getKey("/Subtype").getName() : "";
            std::string bf =
                fontObj.getKey("/BaseFont").isName() ? fontObj.getKey("/BaseFont").getName() : "";
            const bool isType1Family = (sub == "/Type1" || sub == "/MMType1");
            const bool isSymbolFont = isAdobeSymbolBaseFont(bf);
            const bool isDingbats = isZapfDingbatsBaseFont(bf);

            bool hasEnc = false;
            bool hasExplicitBase = false;
            std::string baseEnc;
            std::map<int, std::string> diffNames;
            parseSimpleEncoding(fontObj, hasEnc, hasExplicitBase, baseEnc, diffNames);

            bool hasNonAglDiff = false;
            for (const auto& [dc, gn] : diffNames) {
                (void)dc;
                if (aglNameToUnicode(gn) == 0) {
                    hasNonAglDiff = true;
                    break;
                }
            }

            const bool useBuiltInEncoding =
                !hasEnc && (isType1Family || isSymbolFont || isDingbats);
            const bool needFace = pctx.ftLib && (useBuiltInEncoding || hasNonAglDiff);

            std::vector<uint8_t> prog;
            jpdfium::font::FtFaceGuard fg;
            std::map<uint32_t, uint32_t> gidToUni;
            FT_CharMap customCharmap = nullptr;
            FT_CharMap stdCharmap = nullptr;
            if (needFace && fontObj.hasKey("/FontDescriptor") &&
                fontObj.getKey("/FontDescriptor").isDictionary()) {
                QPDFObjectHandle fd = fontObj.getKey("/FontDescriptor");
                QPDFObjectHandle ff = QPDFObjectHandle::newNull();
                if (isType1Family) {
                    if (fd.getKey("/FontFile").isStream())
                        ff = fd.getKey("/FontFile");
                    else if (fd.getKey("/FontFile3").isStream())
                        ff = fd.getKey("/FontFile3");
                    else if (fd.getKey("/FontFile2").isStream())
                        ff = fd.getKey("/FontFile2");
                } else {
                    if (fd.getKey("/FontFile2").isStream())
                        ff = fd.getKey("/FontFile2");
                    else if (fd.getKey("/FontFile3").isStream())
                        ff = fd.getKey("/FontFile3");
                    else if (fd.getKey("/FontFile").isStream())
                        ff = fd.getKey("/FontFile");
                }
                if (ff.isStream() && readStreamBytesBounded(ff, prog, qpdf_dl_generalized) &&
                    isSafeFontBuffer(prog)) {
                    FT_Face rawFace = nullptr;
                    if (FT_New_Memory_Face(pctx.ftLib, prog.data(),
                                           static_cast<FT_Long>(prog.size()), 0, &rawFace) == 0 &&
                        rawFace) {
                        fg.reset(rawFace);
                        for (int ci = 0; ci < rawFace->num_charmaps; ++ci) {
                            FT_CharMap cm = rawFace->charmaps[ci];
                            if (!cm) continue;
                            if (cm->encoding == FT_ENCODING_ADOBE_CUSTOM && !customCharmap) {
                                customCharmap = cm;
                            } else if (cm->encoding == FT_ENCODING_ADOBE_STANDARD && !stdCharmap) {
                                stdCharmap = cm;
                            }
                        }
                        if (FT_Select_Charmap(rawFace, FT_ENCODING_UNICODE) == 0) {
                            FT_UInt gIdx = 0;
                            FT_ULong cc = FT_Get_First_Char(rawFace, &gIdx);
                            while (gIdx != 0) {
                                if (isValidPdfAUnicode(static_cast<uint32_t>(cc))) {
                                    gidToUni.emplace(gIdx, static_cast<uint32_t>(cc));
                                }
                                cc = FT_Get_Next_Char(rawFace, cc, &gIdx);
                            }
                        }
                    }
                }
            }

            std::ostringstream cm;
            cm << "/CIDInit /ProcSet findresource begin\n"
               << "12 dict begin\n"
               << "begincmap\n"
               << "/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n"
               << "/CMapName /JPDFium-Simple-UCS def\n"
               << "/CMapType 2 def\n"
               << "1 begincodespacerange\n<00> <FF>\nendcodespacerange\n";
            for (int base = 0; base < 256; base += 64) {
                cm << "64 beginbfchar\n";
                for (int c = base; c < base + 64; ++c) {
                    uint32_t u = 0;
                    auto dit = diffNames.find(c);
                    if (dit != diffNames.end()) {
                        u = aglNameToUnicode(dit->second);
                        if (u == 0 && fg.face && !gidToUni.empty() && FT_HAS_GLYPH_NAMES(fg.face)) {
                            FT_UInt gid = FT_Get_Name_Index(fg.face, dit->second.c_str());
                            if (gid != 0) {
                                auto git = gidToUni.find(gid);
                                if (git != gidToUni.end()) u = git->second;
                            }
                        }
                        if (u == 0 && isDingbats) {
                            u = zapfDingbatsNameToUnicode(dit->second);
                        }
                        if (u == 0) {
                            u = (c >= 1 && isValidPdfAUnicode(static_cast<uint32_t>(c)))
                                    ? static_cast<uint32_t>(c)
                                    : 0x0020U;
                        }
                    } else if (useBuiltInEncoding) {
                        if (fg.face && (customCharmap || stdCharmap)) {
                            FT_Set_Charmap(fg.face, customCharmap ? customCharmap : stdCharmap);
                            FT_UInt gid = FT_Get_Char_Index(fg.face, static_cast<FT_ULong>(c));
                            if (gid != 0 && FT_HAS_GLYPH_NAMES(fg.face)) {
                                char gname[256] = {};
                                if (FT_Get_Glyph_Name(fg.face, gid, gname, sizeof(gname)) == 0 &&
                                    gname[0] != '\0') {
                                    std::string gn(gname);
                                    u = aglNameToUnicode(gn);
                                    if (u == 0 && isDingbats) {
                                        u = zapfDingbatsNameToUnicode(gn);
                                    }
                                    if (u == 0 && !gidToUni.empty()) {
                                        auto git = gidToUni.find(gid);
                                        if (git != gidToUni.end()) u = git->second;
                                    }
                                }
                            }
                        }
                        if (u == 0 && (!fg.face || (!customCharmap && !stdCharmap))) {
                            if (isSymbolFont && c >= 0x20) {
                                u = kAdobeSymbol[c - 0x20];
                            } else if (isDingbats) {
                                u = kZapfDingbatsCode[static_cast<size_t>(c)];
                            } else if (isType1Family) {
                                u = jpdfium_font_encoding::baseEncodingUnicode(
                                    jpdfium_font_encoding::BaseEncoding::Standard, c);
                            }
                        }
                        if (u == 0) {
                            u = (c >= 1 && isValidPdfAUnicode(static_cast<uint32_t>(c)))
                                    ? static_cast<uint32_t>(c)
                                    : 0x0020U;
                        }
                    } else if (baseEnc == "/StandardEncoding" ||
                               (!hasExplicitBase && isType1Family)) {
                        u = jpdfium_font_encoding::baseEncodingUnicode(
                            jpdfium_font_encoding::BaseEncoding::Standard, c);
                        if (u == 0) {
                            u = (c >= 1 && isValidPdfAUnicode(static_cast<uint32_t>(c)))
                                    ? static_cast<uint32_t>(c)
                                    : 0x0020U;
                        }
                    } else if (baseEnc == "/MacRomanEncoding") {
                        u = macRomanToUnicode(static_cast<uint8_t>(c));
                    } else {
                        u = winAnsiToUnicode(static_cast<uint8_t>(c));
                    }
                    if (!isValidPdfAUnicode(u)) u = 0x0020;
                    cm << "<" << hex2(static_cast<uint32_t>(c)) << "> <" << hex4(u) << ">\n";
                }
                cm << "endbfchar\n";
            }
            cm << "endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n";
            return cm.str();
        };
        auto appendBfCharToCmap = [&](std::string& cmapStr,
                                      const std::vector<std::pair<uint32_t, uint32_t>>& pairs,
                                      int codeHexDigits) {
            if (pairs.empty()) return;
            size_t ins = cmapStr.rfind("endcmap");
            if (ins == std::string::npos) return;
            std::ostringstream oss;
            oss << "\n";
            for (size_t i = 0; i < pairs.size(); i += 100) {
                size_t cnt = std::min<size_t>(100, pairs.size() - i);
                oss << cnt << " beginbfchar\n";
                for (size_t k = 0; k < cnt; ++k) {
                    uint32_t src = pairs[i + k].first;
                    uint32_t dst = pairs[i + k].second;
                    if (!isValidPdfAUnicode(dst)) dst = 0x0020;
                    oss << "<" << (codeHexDigits == 2 ? hex2(src) : hex4(src)) << "> <";
                    if (dst <= 0xFFFFU) {
                        oss << hex4(dst);
                    } else {
                        uint32_t adj = dst - 0x10000U;
                        oss << hex4(0xD800U + (adj >> 10)) << hex4(0xDC00U + (adj & 0x3FFU));
                    }
                    oss << ">\n";
                }
                oss << "endbfchar\n";
            }
            cmapStr.insert(ins, oss.str());
        };
        auto buildValidIdentityUcsCmap = [&]() -> std::string {
            std::ostringstream tss;
            tss << "/CIDInit /ProcSet findresource begin\n"
                << "12 dict begin\n"
                << "begincmap\n"
                << "/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n"
                << "/CMapName /JPDFium-Identity-UCS def\n"
                << "/CMapType 2 def\n"
                << "1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n";
            std::vector<std::string> ranges;
            ranges.reserve(230);
            ranges.emplace_back("<0001> <00FF> <0001>\n");
            for (int hi = 0x01; hi <= 0xD7; ++hi) {
                char lineBuf[32];
                std::snprintf(lineBuf, sizeof(lineBuf), "<%02X00> <%02XFF> <%02X00>\n", hi, hi, hi);
                ranges.emplace_back(lineBuf);
            }
            for (int hi = 0xF9; hi <= 0xFD; ++hi) {
                char lineBuf[32];
                std::snprintf(lineBuf, sizeof(lineBuf), "<%02X00> <%02XFF> <%02X00>\n", hi, hi, hi);
                ranges.emplace_back(lineBuf);
            }
            ranges.emplace_back("<FE00> <FEFE> <FE00>\n");
            ranges.emplace_back("<FF00> <FFFC> <FF00>\n");
            for (size_t blk = 0; blk < ranges.size(); blk += 100) {
                size_t cnt = std::min<size_t>(100, ranges.size() - blk);
                tss << cnt << " beginbfrange\n";
                for (size_t ri = 0; ri < cnt; ++ri) tss << ranges[blk + ri];
                tss << "endbfrange\n";
            }
            tss << "endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n";
            return tss.str();
        };
        auto buildType0ToUnicode = [&](QPDFObjectHandle& fontObj) -> std::string {
            QPDFObjectHandle encObj = fontObj.getKey("/Encoding");
            QPDFObjectHandle encNameObj =
                encObj.isName() ? encObj
                                : (encObj.isStream() ? encObj.getDict().getKey("/CMapName")
                                                     : QPDFObjectHandle::newNull());
            std::string encName = encNameObj.isName() ? encNameObj.getName() : "";
            if (encName.find("UCS2") != std::string::npos ||
                encName.find("UTF16") != std::string::npos) {
                return buildValidIdentityUcsCmap();
            }
            QPDFObjectHandle df = fontObj.getKey("/DescendantFonts");
            QPDFObjectHandle d =
                (df.isArray() && df.getArrayNItems() >= 1 && df.getArrayItem(0).isDictionary())
                    ? df.getArrayItem(0)
                    : QPDFObjectHandle::newNull();
            std::string ordering;
            if (!d.isNull() && d.getKey("/CIDSystemInfo").isDictionary()) {
                QPDFObjectHandle ord = d.getKey("/CIDSystemInfo").getKey("/Ordering");
                if (ord.isString()) ordering = ord.getUTF8Value();
            }
            const std::set<uint32_t>* usedPtr = nullptr;
            if (fontObj.isIndirect() && pctx.usedFontCodes) {
                auto uit = pctx.usedFontCodes->find(fontObj.getObjGen());
                if (uit != pctx.usedFontCodes->end()) usedPtr = &uit->second;
            }
            bool isPredefCjk = false;
            jpdfium::qpdf_detail::CidEncoding cidEnc =
                jpdfium::qpdf_detail::cidEncodingFromName(encNameObj);
            std::set<uint32_t> predefCodes;
            if (cidEnc != jpdfium::qpdf_detail::CidEncoding::TwoByte) {
                isPredefCjk = true;
                auto scan = jpdfium::qpdf_detail::usedCidsForFont(qpdf, fontObj, cidEnc);
                predefCodes = std::move(scan.codes);
            }
            const std::set<uint32_t>* codeSet =
                (isPredefCjk && !predefCodes.empty()) ? &predefCodes : usedPtr;
            std::map<uint32_t, uint32_t> codeToUni;
            bool isDonorSub = fontObj.isIndirect() && pctx.donorSubstitutedType0 &&
                              pctx.donorSubstitutedType0->count(fontObj.getObjGen()) > 0;
            bool glyph3Empty = false;
            if (isPredefCjk && codeSet) {
                bool prop = jpdfium::qpdf_detail::isRksjPropCidEncoding(cidEnc);
                bool vert = jpdfium::qpdf_detail::isVerticalCidEncoding(cidEnc);
                bool rksj = jpdfium::qpdf_detail::isRksjCidEncoding(cidEnc);
                bool ksc = jpdfium::qpdf_detail::isKscEucCidEncoding(cidEnc);
                bool gbe = jpdfium::qpdf_detail::isGbEucCidEncoding(cidEnc);
                for (uint32_t code : *codeSet) {
                    uint32_t cid = 0;
                    uint32_t uni = 0;
                    if (rksj && jpdfium::qpdf_detail::japan1CidForCode(static_cast<uint16_t>(code),
                                                                       cid, prop, vert)) {
                        if (cid >= 1 && cid <= 95)
                            uni = cid + 31;
                        else
                            jpdfium::qpdf_detail::japan1UnicodeForCid(cid, uni);
                    } else if (ksc && jpdfium::qpdf_detail::korea1CidForCode(
                                          static_cast<uint16_t>(code), cid, vert)) {
                        if (cid >= 1 && cid <= 95)
                            uni = cid + 31;
                        else
                            jpdfium::qpdf_detail::korea1UnicodeForCid(cid, uni);
                    } else if (gbe && jpdfium::qpdf_detail::gb1CidForCode(
                                          static_cast<uint16_t>(code), cid, vert)) {
                        if (cid >= 1 && cid <= 95)
                            uni = cid + 31;
                        else
                            jpdfium::qpdf_detail::gb1UnicodeForCid(cid, uni);
                    }
                    codeToUni[code] = isValidPdfAUnicode(uni) ? uni : 0x0020U;
                }
            } else if ((ordering == "Japan1" || ordering == "Korea1" || ordering == "GB1") &&
                       (encName == "/Identity-H" || encName == "/Identity-V")) {
                std::set<uint32_t> targetCids;
                if (codeSet && !codeSet->empty()) {
                    targetCids = *codeSet;
                } else if (!d.isNull() && d.hasKey("/W")) {
                    for (const auto& [wc, _] : parseWArray(d.getKey("/W"))) {
                        if (wc >= 0 && wc <= 65535) targetCids.insert(static_cast<uint32_t>(wc));
                    }
                }
                for (uint32_t cid : targetCids) {
                    uint32_t uni = 0;
                    if (cid >= 1 && cid <= 95) {
                        uni = cid + 31;
                    } else {
                        jpdfium::qpdf_detail::adobeCollectionUnicodeForCid(ordering, cid, uni);
                    }
                    codeToUni[cid] = isValidPdfAUnicode(uni) ? uni : 0x0020U;
                }
            } else if (!isDonorSub && !d.isNull() && pctx.ftLib && d.hasKey("/FontDescriptor") &&
                       d.getKey("/FontDescriptor").isDictionary()) {
                QPDFObjectHandle fd = d.getKey("/FontDescriptor");
                QPDFObjectHandle ff2 = fd.getKey("/FontFile2");
                if (ff2.isStream()) {
                    std::vector<uint8_t> prog;
                    if (readStreamBytesBounded(ff2, prog, qpdf_dl_generalized) &&
                        isSafeFontBuffer(prog)) {
                        FT_Face rawFace = nullptr;
                        if (FT_New_Memory_Face(pctx.ftLib, prog.data(),
                                               static_cast<FT_Long>(prog.size()), 0,
                                               &rawFace) == 0 &&
                            rawFace) {
                            jpdfium::font::FtFaceGuard fg(rawFace);
                            FT_Face face = fg.face;
                            std::vector<uint16_t> c2gVec;
                            bool idMap = true;
                            QPDFObjectHandle c2g = d.getKey("/CIDToGIDMap");
                            if (c2g.isStream()) {
                                std::vector<uint8_t> mb;
                                if (readStreamBytesBounded(c2g, mb, qpdf_dl_generalized) &&
                                    mb.size() >= 2) {
                                    idMap = false;
                                    size_t cnt = std::min<size_t>(mb.size() / 2, 65536U);
                                    c2gVec.resize(cnt);
                                    for (size_t ci = 0; ci < cnt; ++ci) {
                                        c2gVec[ci] = readU16(mb.data() + ci * 2);
                                    }
                                }
                            }
                            FT_UInt g3 = idMap ? 3U : (3U < c2gVec.size() ? c2gVec[3] : 0U);
                            if (g3 != 0 && isGidEmptyOutline(face, g3)) {
                                glyph3Empty = true;
                            }
                            if (FT_Select_Charmap(face, FT_ENCODING_UNICODE) == 0) {
                                std::map<uint32_t, uint32_t> gidToUni;
                                FT_UInt gIdx = 0;
                                FT_ULong cc = FT_Get_First_Char(face, &gIdx);
                                while (gIdx != 0) {
                                    if (isValidPdfAUnicode(static_cast<uint32_t>(cc))) {
                                        gidToUni.emplace(gIdx, static_cast<uint32_t>(cc));
                                    }
                                    cc = FT_Get_Next_Char(face, cc, &gIdx);
                                }
                                if (codeSet && !codeSet->empty()) {
                                    for (uint32_t cid : *codeSet) {
                                        uint32_t g =
                                            idMap ? cid : (cid < c2gVec.size() ? c2gVec[cid] : 0);
                                        auto git = gidToUni.find(g);
                                        uint32_t fallback =
                                            (cid == 0x0003U && glyph3Empty)
                                                ? 0x0020U
                                                : ((cid >= 0x0001U && isValidPdfAUnicode(cid))
                                                       ? cid
                                                       : 0x0020U);
                                        codeToUni[cid] =
                                            (git != gidToUni.end()) ? git->second : fallback;
                                    }
                                } else if (!idMap) {
                                    for (size_t cid = 0; cid < c2gVec.size(); ++cid) {
                                        auto git = gidToUni.find(c2gVec[cid]);
                                        if (git != gidToUni.end()) {
                                            codeToUni[static_cast<uint32_t>(cid)] = git->second;
                                        }
                                    }
                                } else {
                                    for (const auto& [g, u] : gidToUni) {
                                        if (g <= 65535U) codeToUni[g] = u;
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (codeToUni.empty() && codeSet && !codeSet->empty()) {
                for (uint32_t code : *codeSet) {
                    codeToUni[code] =
                        (code == 0x0003U && glyph3Empty)
                            ? 0x0020U
                            : ((code >= 0x0001U && isValidPdfAUnicode(code)) ? code : 0x0020U);
                }
            }
            if (codeToUni.empty()) {
                codeToUni[0] = 0x0020U;
            }
            if (isPredefCjk) {
                std::ostringstream cm;
                cm << "/CIDInit /ProcSet findresource begin\n"
                   << "12 dict begin\n"
                   << "begincmap\n"
                   << "/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n"
                   << "/CMapName /JPDFium-CJK-UCS def\n"
                   << "/CMapType 2 def\n"
                   << "2 begincodespacerange\n<00> <FF>\n<0000> <FFFF>\nendcodespacerange\n";
                std::vector<std::pair<uint32_t, uint32_t>> b1;
                std::vector<std::pair<uint32_t, uint32_t>> b2;
                for (const auto& [c, u] : codeToUni) {
                    if (c <= 0xFFU)
                        b1.emplace_back(c, u);
                    else
                        b2.emplace_back(c, u);
                }
                std::string s = cm.str() +
                                "endcmap\nCMapName currentdict /CMap defineresource pop\n"
                                "end\nend\n";
                appendBfCharToCmap(s, b1, 2);
                appendBfCharToCmap(s, b2, 4);
                return s;
            }
            std::string s =
                "/CIDInit /ProcSet findresource begin\n"
                "12 dict begin\n"
                "begincmap\n"
                "/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n"
                "/CMapName /JPDFium-CID-UCS def\n"
                "/CMapType 2 def\n"
                "1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n"
                "endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n";
            std::vector<std::pair<uint32_t, uint32_t>> pairs(codeToUni.begin(), codeToUni.end());
            appendBfCharToCmap(s, pairs, 4);
            return s;
        };
        bool unicodeFixed = false;
        for (auto obj : allObjects) {
            if (!obj.isDictionary() || !obj.getKey("/Type").isNameAndEquals("/Font")) continue;
            QPDFObjectHandle subH = obj.getKey("/Subtype");
            if (!subH.isName()) continue;
            std::string sub = subH.getName();
            if (sub == "/CIDFontType0" || sub == "/CIDFontType2") continue;
            if (obj.hasKey("/ToUnicode") && obj.getKey("/ToUnicode").isStream()) {
                const std::string fontBf =
                    obj.getKey("/BaseFont").isName() ? obj.getKey("/BaseFont").getName() : "";
                const bool isSymbolFont = isAdobeSymbolBaseFont(fontBf);
                const bool isDingbatsFont = isZapfDingbatsBaseFont(fontBf);
                // Repair declared bfchar/bfrange entry counts, sanitize forbidden targets
                // (<0000>, <FFFE>, <FFFF>, <FEFF>, <FFFD>, and PUA <E000>..<F8FF>) strictly within
                // bfchar/bfrange destination tokens, and append fallback mappings for used codes
                // (6.2.11.7.2-1 / 6.2.11.7.3-1).
                std::string s;
                if (readStreamBounded(obj.getKey("/ToUnicode"), s, qpdf_dl_generalized,
                                      size_t{4} * 1024U * 1024U) &&
                    !s.empty()) {
                    bool dirty = false;
                    auto isHex = [](char c) {
                        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') ||
                               (c >= 'A' && c <= 'F');
                    };
                    auto hexVal = [](char c) -> uint32_t {
                        if (c >= '0' && c <= '9') return static_cast<uint32_t>(c - '0');
                        if (c >= 'a' && c <= 'f') return static_cast<uint32_t>(10 + (c - 'a'));
                        return static_cast<uint32_t>(10 + (c - 'A'));
                    };
                    auto skipCmSpace = [&](size_t& idx) {
                        while (idx < s.size()) {
                            if (std::isspace(static_cast<unsigned char>(s[idx]))) {
                                ++idx;
                            } else if (s[idx] == '%') {
                                while (idx < s.size() && s[idx] != '\n' && s[idx] != '\r') ++idx;
                            } else {
                                break;
                            }
                        }
                    };
                    auto consumeCmHex = [&](size_t& idx) {
                        if (idx >= s.size() || s[idx] != '<') return false;
                        size_t gt = s.find('>', idx + 1);
                        if (gt == std::string::npos) return false;
                        bool any = false;
                        for (size_t k = idx + 1; k < gt; ++k) {
                            if (isHex(s[k])) {
                                any = true;
                            } else if (!std::isspace(static_cast<unsigned char>(s[k]))) {
                                return false;
                            }
                        }
                        if (!any) return false;
                        idx = gt + 1;
                        return true;
                    };
                    auto repairListCount = [&](const std::string& beginKw, const std::string& endKw,
                                               bool isRange) {
                        bool changed = false;
                        size_t search = 0;
                        while ((search = s.find(beginKw, search)) != std::string::npos) {
                            size_t e = search;
                            while (e > 0 && std::isspace(static_cast<unsigned char>(s[e - 1]))) --e;
                            size_t b = e;
                            while (b > 0 && std::isdigit(static_cast<unsigned char>(s[b - 1]))) --b;
                            if (b == e) {
                                search += beginKw.size();
                                continue;
                            }
                            long declared = std::strtol(s.substr(b, e - b).c_str(), nullptr, 10);
                            size_t idx = search + beginKw.size();
                            long actual = 0;
                            bool ok = true;
                            for (;;) {
                                skipCmSpace(idx);
                                if (idx >= s.size()) {
                                    ok = false;
                                    break;
                                }
                                if (s.compare(idx, endKw.size(), endKw) == 0) break;
                                if (s.compare(idx, 7, "endcmap") == 0) {
                                    ok = false;
                                    break;
                                }
                                if (!consumeCmHex(idx)) {
                                    ok = false;
                                    break;
                                }
                                skipCmSpace(idx);
                                if (isRange && !consumeCmHex(idx)) {
                                    ok = false;
                                    break;
                                }
                                skipCmSpace(idx);
                                if (idx < s.size() && s[idx] == '[') {
                                    ++idx;
                                    bool closed = false;
                                    while (idx < s.size()) {
                                        skipCmSpace(idx);
                                        if (idx < s.size() && s[idx] == ']') {
                                            ++idx;
                                            closed = true;
                                            break;
                                        }
                                        if (!consumeCmHex(idx)) break;
                                    }
                                    if (!closed) {
                                        ok = false;
                                        break;
                                    }
                                } else if (!consumeCmHex(idx)) {
                                    ok = false;
                                    break;
                                }
                                ++actual;
                            }
                            if (ok && actual != declared) {
                                std::string repl = std::to_string(actual);
                                s.replace(b, e - b, repl);
                                changed = true;
                                search = b + repl.size();
                            } else {
                                search = search + beginKw.size();
                            }
                        }
                        return changed;
                    };
                    dirty |= repairListCount("beginbfchar", "endbfchar", false);
                    dirty |= repairListCount("beginbfrange", "endbfrange", true);
                    size_t n = s.size();
                    std::vector<std::pair<uint32_t, uint32_t>> validMappedRanges;
                    std::vector<std::tuple<uint32_t, uint32_t, uint32_t>> extraBfRanges;
                    std::vector<std::pair<uint32_t, uint32_t>> extraBfChars;
                    int defaultCodeHexDigits = (sub == "/Type0") ? 4 : 2;
                    auto parseHexScalar = [&](size_t ltPos, size_t gtPos, uint32_t& outVal,
                                              size_t& digitCount) -> bool {
                        std::string digits;
                        digits.reserve(gtPos > ltPos ? gtPos - ltPos - 1 : 0);
                        for (size_t k = ltPos + 1; k < gtPos; ++k) {
                            char ch = s[k];
                            if (std::isspace(static_cast<unsigned char>(ch)) != 0 || ch == '\0') {
                                continue;
                            }
                            if (!isHex(ch)) return false;
                            digits.push_back(ch);
                        }
                        if (digits.empty() || digits.size() > 8) return false;
                        digitCount = digits.size();
                        uint32_t v = 0;
                        for (char ch : digits) {
                            v = (v << 4) | hexVal(ch);
                        }
                        outVal = v;
                        return true;
                    };
                    auto parseDestCodepoint = [&](size_t ltPos, size_t gtPos, uint32_t& outCp,
                                                  bool& isSingleScalar) -> bool {
                        isSingleScalar = false;
                        std::string digits;
                        digits.reserve(gtPos > ltPos ? gtPos - ltPos - 1 : 0);
                        for (size_t k = ltPos + 1; k < gtPos; ++k) {
                            char ch = s[k];
                            if (std::isspace(static_cast<unsigned char>(ch)) != 0 || ch == '\0') {
                                continue;
                            }
                            if (!isHex(ch)) return false;
                            digits.push_back(ch);
                        }
                        if (digits.size() == 2) {
                            outCp = (hexVal(digits[0]) << 4) | hexVal(digits[1]);
                            isSingleScalar = true;
                            return true;
                        }
                        if (digits.size() == 4) {
                            outCp = (hexVal(digits[0]) << 12) | (hexVal(digits[1]) << 8) |
                                    (hexVal(digits[2]) << 4) | hexVal(digits[3]);
                            isSingleScalar = true;
                            return true;
                        }
                        return false;
                    };
                    auto sanitizeDestHex = [&](size_t ltPos, size_t& gtPos) {
                        std::string digits;
                        digits.reserve(gtPos > ltPos ? gtPos - ltPos - 1 : 0);
                        for (size_t k = ltPos + 1; k < gtPos; ++k) {
                            char ch = s[k];
                            if (std::isspace(static_cast<unsigned char>(ch)) != 0 || ch == '\0') {
                                continue;
                            }
                            if (!isHex(ch)) return;
                            digits.push_back(ch);
                        }
                        auto replaceToken = [&](std::string const& repl) {
                            s.replace(ltPos, gtPos - ltPos + 1, repl);
                            gtPos = ltPos + repl.size() - 1;
                            n = s.size();
                            dirty = true;
                        };
                        if (digits.empty()) {
                            replaceToken("<0020>");
                            return;
                        }
                        if (digits.size() % 2 != 0) digits.push_back('0');
                        if (digits.size() == 2) {
                            uint32_t cp = (hexVal(digits[0]) << 4) | hexVal(digits[1]);
                            if (!isValidPdfAUnicode(cp)) replaceToken("<0020>");
                            return;
                        }
                        if (digits.size() % 4 != 0) {
                            replaceToken("<0020>");
                            return;
                        }
                        std::vector<uint16_t> units;
                        units.reserve(digits.size() / 4);
                        for (size_t k = 0; k < digits.size(); k += 4) {
                            uint32_t u = (hexVal(digits[k]) << 12) | (hexVal(digits[k + 1]) << 8) |
                                         (hexVal(digits[k + 2]) << 4) | hexVal(digits[k + 3]);
                            units.push_back(static_cast<uint16_t>(u));
                        }
                        std::vector<uint16_t> outUnits;
                        outUnits.reserve(units.size());
                        bool tokenDirty = false;
                        for (size_t idx = 0; idx < units.size();) {
                            uint16_t u = units[idx];
                            if (u >= 0xD800U && u <= 0xDBFFU) {
                                if (idx + 1 < units.size() && units[idx + 1] >= 0xDC00U &&
                                    units[idx + 1] <= 0xDFFFU) {
                                    uint32_t cp = 0x10000U +
                                                  ((static_cast<uint32_t>(u - 0xD800U) << 10) |
                                                   static_cast<uint32_t>(units[idx + 1] - 0xDC00U));
                                    if (isValidPdfAUnicode(cp)) {
                                        outUnits.push_back(u);
                                        outUnits.push_back(units[idx + 1]);
                                    } else {
                                        outUnits.push_back(0x0020U);
                                        tokenDirty = true;
                                    }
                                    idx += 2;
                                } else {
                                    outUnits.push_back(0x0020U);
                                    tokenDirty = true;
                                    idx += 1;
                                }
                            } else if (u >= 0xDC00U && u <= 0xDFFFU) {
                                outUnits.push_back(0x0020U);
                                tokenDirty = true;
                                idx += 1;
                            } else {
                                if (isValidPdfAUnicode(u)) {
                                    outUnits.push_back(u);
                                } else if (isSymbolFont && u >= 0xF020U && u <= 0xF0FFU) {
                                    uint16_t mapped = kAdobeSymbol[u - 0xF020U];
                                    outUnits.push_back((mapped != 0 && isValidPdfAUnicode(mapped))
                                                           ? mapped
                                                           : 0x0020U);
                                    tokenDirty = true;
                                } else {
                                    outUnits.push_back(0x0020U);
                                    tokenDirty = true;
                                }
                                idx += 1;
                            }
                        }
                        if (tokenDirty) {
                            std::string repl = "<";
                            if (outUnits.empty()) {
                                repl += "0020";
                            } else {
                                for (uint16_t u : outUnits) repl += hex4(u);
                            }
                            repl += '>';
                            replaceToken(repl);
                        }
                    };
                    enum class CmapSection : uint8_t { kNone, kCodespace, kBfChar, kBfRange };
                    CmapSection sec = CmapSection::kNone;
                    int tuplePos = 0;
                    int bracketDepth = 0;
                    uint32_t curRangeLo = 0;
                    uint32_t curRangeHi = 0;
                    size_t curRangeLoStart = 0;
                    size_t curRangeLoDigits = 4;
                    size_t curRangeHiDigits = 4;
                    uint32_t curBfCharSrc = 0;
                    for (size_t i = 0; i < n;) {
                        char c = s[i];
                        if (std::isspace(static_cast<unsigned char>(c)) != 0) {
                            ++i;
                            continue;
                        }
                        if (c == '%') {
                            while (i < n && s[i] != '\r' && s[i] != '\n') ++i;
                            continue;
                        }
                        if (c == '(') {
                            int pdepth = 1;
                            ++i;
                            while (i < n && pdepth > 0) {
                                if (s[i] == '\\' && i + 1 < n) {
                                    i += 2;
                                    continue;
                                }
                                if (s[i] == '(')
                                    ++pdepth;
                                else if (s[i] == ')')
                                    --pdepth;
                                ++i;
                            }
                            continue;
                        }
                        if (c == '[') {
                            if (sec == CmapSection::kBfRange) ++bracketDepth;
                            ++i;
                            continue;
                        }
                        if (c == ']') {
                            if (sec == CmapSection::kBfRange && bracketDepth > 0) {
                                --bracketDepth;
                                if (bracketDepth == 0) {
                                    if (tuplePos == 2 && curRangeLo <= curRangeHi) {
                                        validMappedRanges.emplace_back(curRangeLo, curRangeHi);
                                    }
                                    tuplePos = 0;
                                }
                            }
                            ++i;
                            continue;
                        }
                        if (c == '<') {
                            if (i + 1 < n && s[i + 1] == '<') {
                                i += 2;
                                continue;
                            }
                            size_t closePos = s.find('>', i + 1);
                            if (closePos == std::string::npos) break;
                            if (sec == CmapSection::kCodespace) {
                                uint32_t csVal = 0;
                                size_t csDigits = 0;
                                if (parseHexScalar(i, closePos, csVal, csDigits) && csDigits >= 4) {
                                    defaultCodeHexDigits = 4;
                                }
                            } else if (sec == CmapSection::kBfChar) {
                                ++tuplePos;
                                if (tuplePos == 1) {
                                    size_t dLen = 0;
                                    if (parseHexScalar(i, closePos, curBfCharSrc, dLen)) {
                                        validMappedRanges.emplace_back(curBfCharSrc, curBfCharSrc);
                                    }
                                } else if (tuplePos == 2) {
                                    sanitizeDestHex(i, closePos);
                                    tuplePos = 0;
                                }
                            } else if (sec == CmapSection::kBfRange) {
                                if (bracketDepth > 0) {
                                    sanitizeDestHex(i, closePos);
                                } else {
                                    ++tuplePos;
                                    if (tuplePos == 1) {
                                        curRangeLoStart = i;
                                        parseHexScalar(i, closePos, curRangeLo, curRangeLoDigits);
                                    } else if (tuplePos == 2) {
                                        parseHexScalar(i, closePos, curRangeHi, curRangeHiDigits);
                                    } else if (tuplePos == 3) {
                                        uint32_t dstStart = 0;
                                        bool isSingle = false;
                                        if (curRangeLo < curRangeHi &&
                                            (curRangeHi - curRangeLo) <= 65535U &&
                                            parseDestCodepoint(i, closePos, dstStart, isSingle) &&
                                            isSingle) {
                                            uint32_t span = curRangeHi - curRangeLo;
                                            bool allValid =
                                                ((curRangeLo & ~0xFFU) == (curRangeHi & ~0xFFU)) &&
                                                (dstStart + span <= 0xFFFFU);
                                            if (allValid) {
                                                for (uint32_t k = 0; k <= span; ++k) {
                                                    if (!isValidPdfAUnicode(dstStart + k)) {
                                                        allValid = false;
                                                        break;
                                                    }
                                                }
                                            }
                                            if (allValid) {
                                                validMappedRanges.emplace_back(curRangeLo,
                                                                               curRangeHi);
                                            } else {
                                                std::vector<
                                                    std::tuple<uint32_t, uint32_t, uint32_t>>
                                                    splitRanges;
                                                std::vector<std::pair<uint32_t, uint32_t>>
                                                    symBfChars;
                                                uint32_t cur = curRangeLo;
                                                while (cur <= curRangeHi) {
                                                    uint32_t hiBound = std::min(
                                                        curRangeHi, (cur & ~0xFFU) | 0xFFU);
                                                    uint32_t segStart = 0;
                                                    bool inRun = false;
                                                    for (uint32_t code = cur; code <= hiBound;
                                                         ++code) {
                                                        uint32_t off = code - curRangeLo;
                                                        uint32_t dst = dstStart + off;
                                                        if (isSymbolFont && dst >= 0xF020U &&
                                                            dst <= 0xF0FFU) {
                                                            if (inRun) {
                                                                splitRanges.emplace_back(
                                                                    segStart, code - 1,
                                                                    dstStart +
                                                                        (segStart - curRangeLo));
                                                                inRun = false;
                                                            }
                                                            uint16_t mapped =
                                                                kAdobeSymbol[dst - 0xF020U];
                                                            uint32_t resolved =
                                                                (mapped != 0 &&
                                                                 isValidPdfAUnicode(mapped))
                                                                    ? mapped
                                                                    : 0x0020U;
                                                            symBfChars.emplace_back(code, resolved);
                                                            continue;
                                                        }
                                                        bool ok = (dst <= 0xFFFFU) &&
                                                                  isValidPdfAUnicode(dst);
                                                        if (ok) {
                                                            if (!inRun) {
                                                                segStart = code;
                                                                inRun = true;
                                                            }
                                                        } else {
                                                            if (inRun) {
                                                                splitRanges.emplace_back(
                                                                    segStart, code - 1,
                                                                    dstStart +
                                                                        (segStart - curRangeLo));
                                                                inRun = false;
                                                            }
                                                            if (code == 0) {
                                                                extraBfChars.emplace_back(code,
                                                                                          0x0020U);
                                                            }
                                                        }
                                                    }
                                                    if (inRun) {
                                                        splitRanges.emplace_back(
                                                            segStart, hiBound,
                                                            dstStart + (segStart - curRangeLo));
                                                    }
                                                    if (hiBound == 0xFFFFU) break;
                                                    cur = hiBound + 1;
                                                }
                                                auto fmtCode = [&](uint32_t v, size_t dg) {
                                                    return "<" +
                                                           ((dg <= 2 && v <= 0xFFU) ? hex2(v)
                                                                                    : hex4(v)) +
                                                           ">";
                                                };
                                                if (!splitRanges.empty()) {
                                                    auto [r0Lo, r0Hi, r0Dst] = splitRanges[0];
                                                    std::string repl =
                                                        fmtCode(r0Lo, curRangeLoDigits) + " " +
                                                        fmtCode(r0Hi, curRangeHiDigits) + " " +
                                                        fmtCode(r0Dst, 4);
                                                    s.replace(curRangeLoStart,
                                                              closePos - curRangeLoStart + 1, repl);
                                                    closePos = curRangeLoStart + repl.size() - 1;
                                                    n = s.size();
                                                    dirty = true;
                                                    validMappedRanges.emplace_back(r0Lo, r0Hi);
                                                    for (size_t si = 1; si < splitRanges.size();
                                                         ++si) {
                                                        extraBfRanges.push_back(splitRanges[si]);
                                                        validMappedRanges.emplace_back(
                                                            std::get<0>(splitRanges[si]),
                                                            std::get<1>(splitRanges[si]));
                                                    }
                                                    for (const auto& [sc, sd] : symBfChars) {
                                                        extraBfChars.emplace_back(sc, sd);
                                                        validMappedRanges.emplace_back(sc, sc);
                                                    }
                                                } else if (!symBfChars.empty()) {
                                                    auto [s0Code, s0Dst] = symBfChars[0];
                                                    std::string repl =
                                                        fmtCode(s0Code, curRangeLoDigits) + " " +
                                                        fmtCode(s0Code, curRangeHiDigits) + " " +
                                                        fmtCode(s0Dst, 4);
                                                    s.replace(curRangeLoStart,
                                                              closePos - curRangeLoStart + 1, repl);
                                                    closePos = curRangeLoStart + repl.size() - 1;
                                                    n = s.size();
                                                    dirty = true;
                                                    validMappedRanges.emplace_back(s0Code, s0Code);
                                                    for (size_t si = 1; si < symBfChars.size();
                                                         ++si) {
                                                        extraBfChars.push_back(symBfChars[si]);
                                                        validMappedRanges.emplace_back(
                                                            symBfChars[si].first,
                                                            symBfChars[si].first);
                                                    }
                                                } else {
                                                    std::string repl =
                                                        fmtCode(curRangeLo, curRangeLoDigits) +
                                                        " " +
                                                        fmtCode(curRangeLo, curRangeHiDigits) +
                                                        " <0020>";
                                                    s.replace(curRangeLoStart,
                                                              closePos - curRangeLoStart + 1, repl);
                                                    closePos = curRangeLoStart + repl.size() - 1;
                                                    n = s.size();
                                                    dirty = true;
                                                    validMappedRanges.emplace_back(curRangeLo,
                                                                                   curRangeLo);
                                                }
                                            }
                                        } else {
                                            if (curRangeLo <= curRangeHi) {
                                                validMappedRanges.emplace_back(curRangeLo,
                                                                               curRangeHi);
                                            }
                                            sanitizeDestHex(i, closePos);
                                        }
                                        tuplePos = 0;
                                    }
                                }
                            }
                            i = closePos + 1;
                            continue;
                        }
                        if (c == '>' && i + 1 < n && s[i + 1] == '>') {
                            i += 2;
                            continue;
                        }
                        size_t tokEnd = i + 1;
                        while (tokEnd < n &&
                               std::isspace(static_cast<unsigned char>(s[tokEnd])) == 0 &&
                               s[tokEnd] != '<' && s[tokEnd] != '>' && s[tokEnd] != '[' &&
                               s[tokEnd] != ']' && s[tokEnd] != '(' && s[tokEnd] != ')' &&
                               s[tokEnd] != '%') {
                            ++tokEnd;
                        }
                        std::string_view kw(s.data() + i, tokEnd - i);
                        if (kw == "begincodespacerange") {
                            sec = CmapSection::kCodespace;
                            tuplePos = 0;
                        } else if (kw == "beginbfchar") {
                            sec = CmapSection::kBfChar;
                            tuplePos = 0;
                        } else if (kw == "beginbfrange") {
                            sec = CmapSection::kBfRange;
                            tuplePos = 0;
                            bracketDepth = 0;
                        } else if (kw == "endcodespacerange" || kw == "endbfchar" ||
                                   kw == "endbfrange") {
                            sec = CmapSection::kNone;
                            tuplePos = 0;
                            bracketDepth = 0;
                        }
                        i = tokEnd;
                    }
                    if (!extraBfRanges.empty()) {
                        size_t ins = s.rfind("endcmap");
                        if (ins != std::string::npos) {
                            std::ostringstream rbs;
                            rbs << "\n";
                            for (size_t bi = 0; bi < extraBfRanges.size(); bi += 100) {
                                size_t cnt = std::min<size_t>(100, extraBfRanges.size() - bi);
                                rbs << cnt << " beginbfrange\n";
                                for (size_t rk = 0; rk < cnt; ++rk) {
                                    auto [lo, hi, dst] = extraBfRanges[bi + rk];
                                    rbs << "<" << (defaultCodeHexDigits == 2 ? hex2(lo) : hex4(lo))
                                        << "> <"
                                        << (defaultCodeHexDigits == 2 ? hex2(hi) : hex4(hi))
                                        << "> <" << hex4(dst) << ">\n";
                                }
                                rbs << "endbfrange\n";
                            }
                            s.insert(ins, rbs.str());
                            dirty = true;
                        }
                    }
                    // Widen a single-pair narrow 1- or 2-byte codespacerange (for example
                    // <0000> <0020>) to the full range so appended mappings remain reachable.
                    size_t bcs = s.find("begincodespacerange");
                    size_t ecs = (bcs == std::string::npos) ? std::string::npos
                                                            : s.find("endcodespacerange", bcs);
                    if (ecs != std::string::npos) {
                        int pairHexTokens = 0;
                        int codeBytes = 0;
                        for (size_t t = bcs; t + 1 < ecs; ++t) {
                            if (s[t] != '<') continue;
                            size_t gt = s.find('>', t + 1);
                            if (gt == std::string::npos || gt >= ecs) break;
                            size_t digits = gt - t - 1;
                            bool hex = (digits == 2 || digits == 4 || digits == 6 || digits == 8);
                            for (size_t h = t + 1; hex && h < gt; ++h) {
                                if (!isHex(s[h])) hex = false;
                            }
                            if (hex) {
                                ++pairHexTokens;
                                if (codeBytes == 0) codeBytes = static_cast<int>(digits / 2);
                            }
                            t = gt;
                        }
                        if (pairHexTokens == 2 && (codeBytes == 1 || codeBytes == 2)) {
                            std::string_view block(s.data() + bcs, ecs - bcs);
                            bool fullRange =
                                (codeBytes == 1)
                                    ? (block.find("<00>") != std::string_view::npos &&
                                       block.find("<FF>") != std::string_view::npos)
                                    : (block.find("<0000>") != std::string_view::npos &&
                                       block.find("<FFFF>") != std::string_view::npos);
                            if (!fullRange) {
                                size_t blockStart = bcs;
                                size_t digitsStart = blockStart;
                                while (digitsStart > 0 && std::isspace(static_cast<unsigned char>(
                                                              s[digitsStart - 1]))) {
                                    --digitsStart;
                                }
                                size_t scan = digitsStart;
                                while (scan > 0 &&
                                       std::isdigit(static_cast<unsigned char>(s[scan - 1]))) {
                                    --scan;
                                }
                                if (scan < digitsStart) blockStart = scan;
                                std::string canonical =
                                    "1 begincodespacerange\n" +
                                    std::string(codeBytes == 2 ? "<0000> <FFFF>" : "<00> <FF>") +
                                    "\nendcodespacerange\n";
                                s.replace(blockStart, ecs + 17 - blockStart, canonical);
                                dirty = true;
                            }
                        }
                    }
                    if (obj.isIndirect() && pctx.usedFontCodes) {
                        auto uit = pctx.usedFontCodes->find(obj.getObjGen());
                        if (uit != pctx.usedFontCodes->end() && !uit->second.empty()) {
                            auto isMappedCode = [&](uint32_t code) -> bool {
                                for (const auto& [lo, hi] : validMappedRanges) {
                                    if (code >= lo && code <= hi) return true;
                                }
                                return false;
                            };
                            bool hasEnc = false;
                            bool hasExplicitBase = false;
                            std::string baseEnc;
                            std::map<int, std::string> diffNames;
                            if (sub != "/Type0") {
                                parseSimpleEncoding(obj, hasEnc, hasExplicitBase, baseEnc,
                                                    diffNames);
                            }
                            const bool isType1Family = (sub == "/Type1" || sub == "/MMType1");
                            auto resolveUnmappedCode = [&](uint32_t uc) -> uint32_t {
                                if (sub != "/Type0" && hasEnc && uc <= 0xFFU) {
                                    uint32_t u = 0;
                                    auto dit = diffNames.find(static_cast<int>(uc));
                                    if (dit != diffNames.end()) {
                                        u = aglNameToUnicode(dit->second);
                                        if (u == 0 && isDingbatsFont) {
                                            u = zapfDingbatsNameToUnicode(dit->second);
                                        }
                                    } else if (baseEnc == "/StandardEncoding" ||
                                               (!hasExplicitBase && isType1Family)) {
                                        u = jpdfium_font_encoding::baseEncodingUnicode(
                                            jpdfium_font_encoding::BaseEncoding::Standard,
                                            static_cast<int>(uc));
                                    } else if (baseEnc == "/MacRomanEncoding") {
                                        u = macRomanToUnicode(static_cast<uint8_t>(uc));
                                    } else {
                                        u = winAnsiToUnicode(static_cast<uint8_t>(uc));
                                    }
                                    if (isValidPdfAUnicode(u)) return u;
                                }
                                if (isSymbolFont && uc >= 0xF020U && uc <= 0xF0FFU) {
                                    uint16_t mapped = kAdobeSymbol[uc - 0xF020U];
                                    if (mapped != 0 && isValidPdfAUnicode(mapped)) return mapped;
                                }
                                return (uc >= 0x0001U && isValidPdfAUnicode(uc)) ? uc : 0x0020U;
                            };
                            for (uint32_t uc : uit->second) {
                                if (!isMappedCode(uc)) {
                                    bool alreadyQueued = false;
                                    for (const auto& [ec, _] : extraBfChars) {
                                        if (ec == uc) {
                                            alreadyQueued = true;
                                            break;
                                        }
                                    }
                                    if (!alreadyQueued) {
                                        extraBfChars.emplace_back(uc, resolveUnmappedCode(uc));
                                    }
                                }
                            }
                        }
                    }
                    if (!extraBfChars.empty()) {
                        appendBfCharToCmap(s, extraBfChars, defaultCodeHexDigits);
                        dirty = true;
                    }
                    if (dirty) {
                        obj.getKey("/ToUnicode")
                            .replaceStreamData(s, QPDFObjectHandle::newNull(),
                                               QPDFObjectHandle::newNull());
                        unicodeFixed = true;
                    }
                }
            } else {
                std::string cmap =
                    (sub == "/Type0") ? buildType0ToUnicode(obj) : buildSimpleToUnicode(obj);
                obj.replaceKey("/ToUnicode", QPDFObjectHandle::newStream(&qpdf, cmap));
                unicodeFixed = true;
            }
        }
        if (unicodeFixed) transforms.push_back("pdfa_ensure_unicode");
    }

    // 6g. PDF/A Level A (1a/2a/3a): ensure /MarkInfo /Marked true, /Lang, and valid
    // /StructTreeRoot.
    if (pdfaConformance == 'A') {
        auto isValidPdfALang = [&](const std::string& lang) -> bool {
            if (lang.empty() || lang.size() > 64) return false;
            std::vector<std::string_view> parts;
            size_t pos = 0;
            while (pos <= lang.size()) {
                size_t dash = lang.find('-', pos);
                if (dash == std::string::npos) {
                    parts.emplace_back(lang.data() + pos, lang.size() - pos);
                    break;
                }
                parts.emplace_back(lang.data() + pos, dash - pos);
                pos = dash + 1;
            }
            if (parts.empty()) return false;
            auto isAlpha = [](char c) { return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'); };
            auto isAlnum = [](char c) {
                return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
            };
            std::string_view p0 = parts[0];
            if (p0.size() == 1) {
                if ((p0[0] != 'i' && p0[0] != 'I' && p0[0] != 'x' && p0[0] != 'X') ||
                    parts.size() < 2) {
                    return false;
                }
            } else if (pdfaPart == 1) {
                if (p0.size() != 2 || !isAlpha(p0[0]) || !isAlpha(p0[1])) return false;
            } else {
                if (p0.size() < 2 || p0.size() > 3) return false;
                for (char ch : p0) {
                    if (!isAlpha(ch)) return false;
                }
            }
            for (size_t i = 1; i < parts.size(); ++i) {
                std::string_view sub = parts[i];
                if (sub.empty() || sub.size() > 8) return false;
                for (char ch : sub) {
                    if (pdfaPart == 1 ? !isAlpha(ch) : !isAlnum(ch)) return false;
                }
            }
            return true;
        };

        QPDFObjectHandle markInfo =
            (root.hasKey("/MarkInfo") && root.getKey("/MarkInfo").isDictionary())
                ? root.getKey("/MarkInfo")
                : QPDFObjectHandle::newDictionary();
        markInfo.replaceKey("/Marked", QPDFObjectHandle::newBool(true));
        root.replaceKey("/MarkInfo", markInfo);
        if (!root.hasKey("/Lang") || !root.getKey("/Lang").isString() ||
            !isValidPdfALang(root.getKey("/Lang").getUTF8Value())) {
            root.replaceKey("/Lang", QPDFObjectHandle::newString("en-US"));
        }
        // Sanitize invalid /Lang on all non-root objects (StructElem, Properties dicts)
        for (auto obj : allObjects) {
            if (!obj.isDictionary() || obj.getObjGen() == root.getObjGen()) continue;
            if (obj.hasKey("/Lang")) {
                QPDFObjectHandle lv = obj.getKey("/Lang");
                if (!lv.isString() || !isValidPdfALang(lv.getUTF8Value())) {
                    obj.removeKey("/Lang");
                }
            }
        }
        // Sanitize invalid /Span << /Lang (...) >> or /Lang <...> inside Page/Form streams
        auto sanitizeStreamLang = [&](QPDFObjectHandle st) {
            if (!st.isStream()) return;
            std::string buf;
            if (!readStreamBounded(st, buf, qpdf_dl_generalized, size_t{16} * 1024U * 1024U) ||
                buf.find("/Lang") == std::string::npos) {
                return;
            }
            bool dirty = false;
            size_t pos = 0;
            while ((pos = buf.find("/Lang", pos)) != std::string::npos) {
                size_t after = pos + 5;
                if (after < buf.size() && !isStreamSpaceOrDelim(buf[after])) {
                    pos = after;
                    continue;
                }
                while (after < buf.size() && std::isspace(static_cast<unsigned char>(buf[after]))) {
                    ++after;
                }
                if (after < buf.size() && buf[after] == '(') {
                    size_t endP = after + 1;
                    int depth = 1;
                    while (endP < buf.size() && depth > 0) {
                        if (buf[endP] == '\\' && endP + 1 < buf.size()) {
                            endP += 2;
                            continue;
                        }
                        if (buf[endP] == '(')
                            ++depth;
                        else if (buf[endP] == ')')
                            --depth;
                        ++endP;
                    }
                    std::string raw =
                        buf.substr(after + 1, (endP > after + 1) ? endP - after - 2 : 0);
                    if (!isValidPdfALang(raw)) {
                        buf.replace(after, endP - after, "(en-US)");
                        dirty = true;
                        pos = after + 7;
                        continue;
                    }
                } else if (after < buf.size() && buf[after] == '<' &&
                           (after + 1 >= buf.size() || buf[after + 1] != '<')) {
                    size_t endH = buf.find('>', after + 1);
                    if (endH != std::string::npos) {
                        buf.replace(after, endH - after + 1, "(en-US)");
                        dirty = true;
                        pos = after + 7;
                        continue;
                    }
                }
                pos = after;
            }
            if (dirty) {
                st.replaceStreamData(buf, QPDFObjectHandle::newNull(), QPDFObjectHandle::newNull());
            }
        };
        for (auto& ph : QPDFPageDocumentHelper(qpdf).getAllPages()) {
            QPDFObjectHandle c = ph.getObjectHandle().getKey("/Contents");
            if (c.isStream()) {
                sanitizeStreamLang(c);
            } else if (c.isArray()) {
                const int nc = std::min(c.getArrayNItems(), 4096);
                for (int ci = 0; ci < nc; ++ci) sanitizeStreamLang(c.getArrayItem(ci));
            }
        }
        for (const auto& obj : allObjects) {
            if (obj.isStream() && obj.getDict().getKey("/Subtype").isNameAndEquals("/Form")) {
                sanitizeStreamLang(obj);
            }
        }

        if (root.hasKey("/StructTreeRoot") && root.getKey("/StructTreeRoot").isDictionary()) {
            // Preserve existing logical structure tree; repair /Type, missing /Figure /Alt, and
            // /RoleMap.
            QPDFObjectHandle str = root.getKey("/StructTreeRoot");
            if (!str.hasKey("/Type"))
                str.replaceKey("/Type", QPDFObjectHandle::newName("/StructTreeRoot"));
            static const std::set<std::string> kStdRoles14 = {
                "/Document", "/Part",      "/Art",      "/Sect",  "/Div",       "/BlockQuote",
                "/Caption",  "/TOC",       "/TOCI",     "/Index", "/NonStruct", "/Private",
                "/P",        "/H",         "/H1",       "/H2",    "/H3",        "/H4",
                "/H5",       "/H6",        "/L",        "/LI",    "/Lbl",       "/LBody",
                "/Table",    "/TR",        "/TH",       "/TD",    "/Span",      "/Quote",
                "/Note",     "/Reference", "/BibEntry", "/Code",  "/Link",      "/Figure",
                "/Formula",  "/Form"};
            static const std::set<std::string> kStdRoles17 = {
                "/Document", "/Part",    "/Art",   "/Sect",  "/Div",       "/BlockQuote",
                "/Caption",  "/TOC",     "/TOCI",  "/Index", "/NonStruct", "/Private",
                "/P",        "/H",       "/H1",    "/H2",    "/H3",        "/H4",
                "/H5",       "/H6",      "/L",     "/LI",    "/Lbl",       "/LBody",
                "/Table",    "/TR",      "/TH",    "/TD",    "/THead",     "/TBody",
                "/TFoot",    "/Span",    "/Quote", "/Note",  "/Reference", "/BibEntry",
                "/Code",     "/Link",    "/Annot", "/Ruby",  "/RB",        "/RT",
                "/RP",       "/Warichu", "/WT",    "/WP",    "/Figure",    "/Formula",
                "/Form"};
            const auto& kStdRoles = (pdfaPart == 1) ? kStdRoles14 : kStdRoles17;
            QPDFObjectHandle roleMap =
                (str.hasKey("/RoleMap") && str.getKey("/RoleMap").isDictionary())
                    ? str.getKey("/RoleMap")
                    : QPDFObjectHandle::newDictionary();
            bool roleMapDirty = false;
            // 1. Remove any standard role keys from /RoleMap (6.7.3.4:3).
            for (const auto& rk : roleMap.getKeys()) {
                if (kStdRoles.count(rk)) {
                    roleMap.removeKey(rk);
                    roleMapDirty = true;
                }
            }
            // 2. Break cycles and repair chains that fail to reach a standard role (6.8.3.4:1,2).
            for (const auto& rk : roleMap.getKeys()) {
                std::set<std::string> visitedRoles;
                visitedRoles.insert(rk);
                std::string cur = rk;
                bool resolvesStd = false;
                for (int depth = 0; depth < 32; ++depth) {
                    QPDFObjectHandle target = roleMap.getKey(cur);
                    if (!target.isName()) break;
                    std::string next = target.getName();
                    if (kStdRoles.count(next)) {
                        resolvesStd = true;
                        break;
                    }
                    if (!roleMap.hasKey(next) || !visitedRoles.insert(next).second) {
                        break;
                    }
                    cur = next;
                }
                if (!resolvesStd) {
                    roleMap.replaceKey(rk, QPDFObjectHandle::newName("/P"));
                    roleMapDirty = true;
                }
            }
            for (auto obj : allObjects) {
                if (!obj.isDictionary()) continue;
                if (!obj.getKey("/Type").isNameAndEquals("/StructElem") && !obj.hasKey("/P"))
                    continue;
                QPDFObjectHandle sKey = obj.getKey("/S");
                if (!sKey.isName()) continue;
                std::string role = sKey.getName();
                if (role == "/Figure" && (!obj.hasKey("/Alt") || !obj.getKey("/Alt").isString() ||
                                          obj.getKey("/Alt").getStringValue().empty())) {
                    obj.replaceKey("/Alt", QPDFObjectHandle::newUnicodeString("Figure"));
                }
                if (!kStdRoles.count(role) && !roleMap.hasKey(role)) {
                    roleMap.replaceKey(role, QPDFObjectHandle::newName("/P"));
                    roleMapDirty = true;
                }
            }
            if (roleMapDirty) str.replaceKey("/RoleMap", roleMap);
            transforms.push_back("pdfa_repair_struct_tree");
        } else {
            // Auto-tag untagged document: wrap each page in /P <</MCID 0>> BDC ... EMC
            // linked to a valid /StructTreeRoot -> /Document -> /P hierarchy and /ParentTree.
            QPDFObjectHandle str = qpdf.makeIndirectObject(QPDFObjectHandle::newDictionary());
            str.replaceKey("/Type", QPDFObjectHandle::newName("/StructTreeRoot"));

            QPDFObjectHandle docElem = qpdf.makeIndirectObject(QPDFObjectHandle::newDictionary());
            docElem.replaceKey("/Type", QPDFObjectHandle::newName("/StructElem"));
            docElem.replaceKey("/S", QPDFObjectHandle::newName("/Document"));
            docElem.replaceKey("/P", str);
            QPDFObjectHandle docKids = QPDFObjectHandle::newArray();

            QPDFObjectHandle nums = QPDFObjectHandle::newArray();
            QPDFPageDocumentHelper pageHelper(qpdf);
            auto allPages = pageHelper.getAllPages();
            int pageIdx = 0;
            QPDFObjectHandle openStream =
                QPDFObjectHandle::newStream(&qpdf, "/P <</MCID 0>> BDC\n");
            QPDFObjectHandle closeStream = QPDFObjectHandle::newStream(&qpdf, "\nEMC\n");
            for (auto& ph : allPages) {
                QPDFObjectHandle po = ph.getObjectHandle();
                po.replaceKey("/StructParents", QPDFObjectHandle::newInteger(pageIdx));
                QPDFObjectHandle pElem = qpdf.makeIndirectObject(QPDFObjectHandle::newDictionary());
                pElem.replaceKey("/Type", QPDFObjectHandle::newName("/StructElem"));
                pElem.replaceKey("/S", QPDFObjectHandle::newName("/P"));
                pElem.replaceKey("/P", docElem);
                pElem.replaceKey("/Pg", po);
                pElem.replaceKey("/K", QPDFObjectHandle::newInteger(0));
                docKids.appendItem(pElem);

                QPDFObjectHandle pageParents = QPDFObjectHandle::newArray();
                pageParents.appendItem(pElem);
                nums.appendItem(QPDFObjectHandle::newInteger(pageIdx));
                nums.appendItem(pageParents);

                // Wrap page /Contents array cleanly without decompressing existing streams.
                QPDFObjectHandle newContents = QPDFObjectHandle::newArray();
                newContents.appendItem(openStream);
                QPDFObjectHandle oldC = po.getKey("/Contents");
                if (oldC.isStream()) {
                    newContents.appendItem(oldC);
                } else if (oldC.isArray()) {
                    for (int ci = 0; ci < oldC.getArrayNItems(); ++ci) {
                        newContents.appendItem(oldC.getArrayItem(ci));
                    }
                }
                newContents.appendItem(closeStream);
                po.replaceKey("/Contents", newContents);
                ++pageIdx;
            }
            docElem.replaceKey("/K", docKids);
            str.replaceKey("/K", docElem);
            QPDFObjectHandle parentTree =
                qpdf.makeIndirectObject(QPDFObjectHandle::newDictionary());
            parentTree.replaceKey("/Nums", nums);
            str.replaceKey("/ParentTree", parentTree);
            str.replaceKey("/ParentTreeNextKey", QPDFObjectHandle::newInteger(pageIdx));
            root.replaceKey("/StructTreeRoot", str);
            transforms.push_back("pdfa_autotag_struct_tree");
        }
    }

    if (pctx.cmykOpsFixed) transforms.push_back("convert_cmyk_ops_to_rgb");
    if (pctx.annotsFixed) transforms.push_back("normalize_annotations");
    if (pctx.extGStateFixed) transforms.push_back("clean_extgstate");
    if (pctx.interpolateFixed) transforms.push_back("disable_image_interpolate");
    if (pctx.groupFixed) transforms.push_back("fix_transparency_group_cs");
}

void applyPdfAStructure(QPDF& qpdf, int pdfaPart, char pdfaConformance, const uint8_t* srgbIcc,
                        int64_t srgbIccLen, const uint8_t* cmykIcc, int64_t cmykIccLen,
                        int unembeddedFontsRemaining, std::vector<std::string>& transforms,
                        const std::map<QPDFObjGen, std::set<uint32_t>>* usedFontCodes,
                        FT_Library ftLib, const std::set<QPDFObjGen>* donorSubstitutedType0) {
    PdfAStructureContext pctx(qpdf, pdfaPart, pdfaConformance, srgbIcc, srgbIccLen, cmykIcc,
                              cmykIccLen, transforms);
    pctx.usedFontCodes = usedFontCodes;
    pctx.ftLib = ftLib;
    pctx.donorSubstitutedType0 = donorSubstitutedType0;
    applyPdfAXmpAndOutputIntent(pctx, srgbIcc, srgbIccLen, cmykIcc, cmykIccLen,
                                unembeddedFontsRemaining);
    applyPdfAObjectAndPageNormalization(pctx);
    applyPdfAProfileExtensions(pctx);
}

}  // namespace jpdfium::rewriter
