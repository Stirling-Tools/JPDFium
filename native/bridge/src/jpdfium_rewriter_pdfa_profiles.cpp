// jpdfium_rewriter_pdfa.cpp - PDF/A-2b document structure normalization:
// XMP/Info sync, OutputIntents, ColorSpace/Group/Shading/DeviceN/ICC repair,
// AcroForm/Action/OCProperties/Annotation cleanup, and UTF-8 name sanitization.

#include "jpdfium_font_rewriter_internal.h"

#include <qpdf/QPDFAcroFormDocumentHelper.hh>
#include <qpdf/QPDFPageDocumentHelper.hh>

#include <algorithm>
#include <cmath>
#include <functional>
#include <sstream>

namespace jpdfium::rewriter {


void applyPdfAProfileExtensions(PdfAStructureContext& pctx) {
    QPDF& qpdf = pctx.qpdf;
    QPDFObjectHandle& root = pctx.root;
    int pdfaPart = pctx.pdfaPart;
    char pdfaConformance = pctx.pdfaConformance;
    bool& annotsFixed = pctx.annotsFixed;
    auto& transforms = pctx.transforms;

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
            if (gs.hasKey("/CA") && gs.getKey("/CA").isNumber() && gs.getKey("/CA").getNumericValue() < 0.9999) {
                gs.replaceKey("/CA", QPDFObjectHandle::newReal("1.0"));
                pdfa1bTransparencyStripped = true;
            }
            if (gs.hasKey("/ca") && gs.getKey("/ca").isNumber() && gs.getKey("/ca").getNumericValue() < 0.9999) {
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
        for (auto& obj : qpdf.getAllObjects()) {
            QPDFObjectHandle d = obj.isStream() ? obj.getDict() : (obj.isDictionary() ? obj : QPDFObjectHandle::newNull());
            if (d.isNull()) continue;
            if (d.hasKey("/OC")) d.removeKey("/OC");
            if (d.hasKey("/Group") && d.getKey("/Group").isDictionary() &&
                d.getKey("/Group").getKey("/S").isNameAndEquals("/Transparency")) {
                d.removeKey("/Group");
                pdfa1bTransparencyStripped = true;
            }
            if (obj.isStream() && d.getKey("/Subtype").isNameAndEquals("/Image")) {
                if (d.hasKey("/SMask")) { d.removeKey("/SMask"); pdfa1bTransparencyStripped = true; }
                if (d.hasKey("/SMaskInData")) { d.removeKey("/SMaskInData"); pdfa1bTransparencyStripped = true; }
            }
            if (d.getKey("/Type").isNameAndEquals("/ExtGState") ||
                d.hasKey("/CA") || d.hasKey("/ca") || d.hasKey("/BM") ||
                (d.hasKey("/SMask") && !obj.isStream())) {
                cleanExtGState1b(d);
            }
            // Also clean direct /ExtGState dicts inside /Resources (fixes 6.4-4 on direct GS dicts).
            if (d.hasKey("/Resources") && d.getKey("/Resources").isDictionary()) {
                QPDFObjectHandle egs = d.getKey("/Resources").getKey("/ExtGState");
                if (egs.isDictionary()) {
                    for (const auto& gk : egs.getKeys()) {
                        cleanExtGState1b(egs.getKey(gk));
                    }
                }
            }
            if (d.getKey("/Type").isNameAndEquals("/Page") && d.hasKey("/Annots") && d.getKey("/Annots").isArray()) {
                QPDFObjectHandle annots = d.getKey("/Annots");
                QPDFObjectHandle filtered = QPDFObjectHandle::newArray();
                bool removed = false;
                for (int ai = 0; ai < annots.getArrayNItems(); ++ai) {
                    QPDFObjectHandle an = annots.getArrayItem(ai);
                    if (an.isDictionary() && an.getKey("/Subtype").isName()) {
                        std::string asub = an.getKey("/Subtype").getName();
                        // ISO 19005-1 6.5.2-1: allow only PDF 1.4 permitted annotation subtypes.
                        static const std::set<std::string> kPdfA1bAllowedAnnots = {
                            "/Text", "/Link", "/FreeText", "/Line", "/Square", "/Circle",
                            "/Highlight", "/Underline", "/Squiggly", "/StrikeOut", "/Stamp",
                            "/Ink", "/Popup", "/Widget", "/PrinterMark", "/TrapNet"
                        };
                        if (!kPdfA1bAllowedAnnots.count(asub)) {
                            removed = true;
                            continue;
                        }
                    }
                    filtered.appendItem(an);
                }
                if (removed) {
                    d.replaceKey("/Annots", filtered);
                    annotsFixed = true;
                }
            }
        }
        // PDF/A-1b (ISO 19005-1 6.1.12-5): drop /StructTreeRoot when oversized arrays (> 8191) exist.
        if (pdfaConformance == 'B' && root.hasKey("/StructTreeRoot")) {
            bool hasOversizedArray = false;
            for (auto& obj : qpdf.getAllObjects()) {
                if (obj.isArray() && obj.getArrayNItems() > 8191) {
                    hasOversizedArray = true;
                    break;
                }
            }
            if (hasOversizedArray) {
                root.removeKey("/StructTreeRoot");
                root.removeKey("/MarkInfo");
            }
        }
        // Clamp out-of-range real numbers (|x| > 32767.0) in content streams for PDF/A-1b (ISO 19005-1 6.1.12-2).
        auto clampContentStreamReals1b = [](QPDFObjectHandle st) {
            if (!st.isStream()) return;
            try {
                auto buf = st.getStreamData(qpdf_dl_generalized);
                if (!buf || buf->getSize() == 0 || buf->getSize() > 16U * 1024U * 1024U) return;
                std::string_view sv(reinterpret_cast<const char*>(buf->getBuffer()), buf->getSize());
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
                        if (c == '\\' && i + 1 < n) { if (dirty) { out.push_back(c); out.push_back(sv[i+1]); } i += 2; continue; }
                        if (c == '(') ++parenDepth;
                        else if (c == ')') { if (--parenDepth <= 0) inStr = false; }
                        if (dirty) out.push_back(c);
                        ++i; continue;
                    }
                    if (inHex) {
                        if (c == '>') inHex = false;
                        if (dirty) out.push_back(c);
                        ++i; continue;
                    }
                    if (c == '(') { inStr = true; parenDepth = 1; if (dirty) out.push_back(c); ++i; continue; }
                    if (c == '<' && (i + 1 >= n || sv[i + 1] != '<')) { inHex = true; if (dirty) out.push_back(c); ++i; continue; }
                    if ((c == '-' || c == '+' || (c >= '0' && c <= '9')) &&
                        (i == 0 || std::isspace(static_cast<unsigned char>(sv[i - 1])) ||
                         sv[i - 1] == '[' || sv[i - 1] == ']' || sv[i - 1] == '/')) {
                        size_t j = i + ((c == '-' || c == '+') ? 1 : 0);
                        bool hasDot = false;
                        size_t digits = 0;
                        while (j < n && ((sv[j] >= '0' && sv[j] <= '9') || (sv[j] == '.' && !hasDot))) {
                            if (sv[j] == '.') hasDot = true;
                            else ++digits;
                            ++j;
                        }
                        if (hasDot && digits >= 5 &&
                            (j == n || std::isspace(static_cast<unsigned char>(sv[j])) ||
                             sv[j] == ']' || sv[j] == '[' || sv[j] == '/')) {
                            double val = std::strtod(std::string(sv.substr(i, j - i)).c_str(), nullptr);
                            if (std::isfinite(val) && std::abs(val) > 32767.0) {
                                if (!dirty) { out.reserve(n); out.append(sv.data(), i); dirty = true; }
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
            } catch (...) {
            }
        };
        for (auto& ph : QPDFPageDocumentHelper(qpdf).getAllPages()) {
            QPDFObjectHandle c = ph.getObjectHandle().getKey("/Contents");
            if (c.isStream()) clampContentStreamReals1b(c);
            else if (c.isArray()) {
                for (int ci = 0; ci < c.getArrayNItems(); ++ci) clampContentStreamReals1b(c.getArrayItem(ci));
            }
        }
        // Also clamp out-of-range real numbers in Form XObject streams and indirect dicts/arrays (6.1.12-2).
        std::function<void(QPDFObjectHandle, int)> clampObjReals1b = [&](QPDFObjectHandle o, int depth) {
            if (depth > 16) return;
            if (o.isArray()) {
                for (int i = 0; i < o.getArrayNItems(); ++i) {
                    QPDFObjectHandle it = o.getArrayItem(i);
                    if (it.isReal()) {
                        double v = it.getNumericValue();
                        if (std::isfinite(v) && std::abs(v) > 32767.0) {
                            o.setArrayItem(i, QPDFObjectHandle::newReal(v < 0 ? "-32767.0" : "32767.0"));
                        }
                    } else if (!it.isIndirect() && (it.isArray() || it.isDictionary())) {
                        clampObjReals1b(it, depth + 1);
                    }
                }
            } else if (o.isDictionary()) {
                for (const auto& k : o.getKeys()) {
                    QPDFObjectHandle it = o.getKey(k);
                    if (it.isReal()) {
                        double v = it.getNumericValue();
                        if (std::isfinite(v) && std::abs(v) > 32767.0) {
                            o.replaceKey(k, QPDFObjectHandle::newReal(v < 0 ? "-32767.0" : "32767.0"));
                        }
                    } else if (!it.isIndirect() && (it.isArray() || it.isDictionary())) {
                        clampObjReals1b(it, depth + 1);
                    }
                }
            }
        };
        for (auto& obj : qpdf.getAllObjects()) {
            if (obj.isStream()) {
                QPDFObjectHandle d = obj.getDict();
                clampObjReals1b(d, 0);
                if (d.getKey("/Subtype").isNameAndEquals("/Form")) {
                    clampContentStreamReals1b(obj);
                }
            } else if (obj.isDictionary() || obj.isArray()) {
                clampObjReals1b(obj, 0);
            }
        }
        if (pdfa1bTransparencyStripped) transforms.push_back("pdfa1b_strip_transparency");
    }

    // 6e. PDF/A-3b (ISO 19005-3 Section 6.8): preserve embedded file attachments while ensuring
    //     every /Filespec has /F, /UF, /AFRelationship, valid /EF stream /Subtype + /Params /ModDate,
    //     and is associated via /Catalog /AF.
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
        for (auto& obj : qpdf.getAllObjects()) {
            QPDFObjectHandle d = obj.isStream() ? obj.getDict() : (obj.isDictionary() ? obj : QPDFObjectHandle::newNull());
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
            std::string fname = d.getKey("/UF").isString() ? d.getKey("/UF").getUTF8Value()
                              : (d.getKey("/F").isString() ? d.getKey("/F").getUTF8Value() : "");
            if (d.hasKey("/EF") && d.getKey("/EF").isDictionary()) {
                QPDFObjectHandle ef = d.getKey("/EF");
                for (const char* efKey : {"/F", "/UF"}) {
                    if (!ef.hasKey(efKey)) continue;
                    QPDFObjectHandle efs = ef.getKey(efKey);
                    if (!efs.isStream()) continue;
                    QPDFObjectHandle efd = efs.getDict();
                    if (!efd.hasKey("/Type")) efd.replaceKey("/Type", QPDFObjectHandle::newName("/EmbeddedFile"));
                    if (!efd.hasKey("/Subtype") || !efd.getKey("/Subtype").isName()) {
                        efd.replaceKey("/Subtype", QPDFObjectHandle::newName(guessMime(fname)));
                    }
                    QPDFObjectHandle params = (efd.hasKey("/Params") && efd.getKey("/Params").isDictionary())
                                                  ? efd.getKey("/Params")
                                                  : QPDFObjectHandle::newDictionary();
                    if (!params.hasKey("/ModDate") || !params.getKey("/ModDate").isString()) {
                        params.replaceKey("/ModDate", QPDFObjectHandle::newString("D:20260101000000Z"));
                    }
                    if (!params.hasKey("/Size") || !params.getKey("/Size").isInteger()) {
                        try {
                            auto sb = efs.getStreamData(qpdf_dl_generalized);
                            if (sb) params.replaceKey("/Size", QPDFObjectHandle::newInteger(static_cast<long long>(sb->getSize())));
                        } catch (...) {
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
            if (u >= 0xE000 && u <= 0xF8FF) return false;  // BMP Private Use Area
            if (u >= 0xD800 && u <= 0xDFFF) return false;  // surrogates
            return u <= 0xFFFF;
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
        auto buildSimpleToUnicode = [&](QPDFObjectHandle fontObj) -> std::string {
            std::string baseEnc = "/WinAnsiEncoding";
            std::map<int, std::string> diffNames;
            QPDFObjectHandle enc = fontObj.getKey("/Encoding");
            if (enc.isName()) {
                baseEnc = enc.getName();
            } else if (enc.isDictionary()) {
                if (enc.getKey("/BaseEncoding").isName()) baseEnc = enc.getKey("/BaseEncoding").getName();
                QPDFObjectHandle diffs = enc.getKey("/Differences");
                if (diffs.isArray()) {
                    int code = 0;
                    for (int i = 0; i < diffs.getArrayNItems(); ++i) {
                        QPDFObjectHandle it = diffs.getArrayItem(i);
                        if (it.isInteger()) code = static_cast<int>(it.getIntValue());
                        else if (it.isName()) {
                            std::string gn = it.getName();
                            if (!gn.empty() && gn[0] == '/') gn.erase(0, 1);
                            if (code >= 0 && code < 256) diffNames[code] = gn;
                            ++code;
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
                    if (dit != diffNames.end()) u = aglNameToUnicode(dit->second);
                    if (u == 0) {
                        if (baseEnc == "/MacRomanEncoding") u = macRomanToUnicode(static_cast<uint8_t>(c));
                        else u = winAnsiToUnicode(static_cast<uint8_t>(c));
                    }
                    if (!isValidPdfAUnicode(u)) u = 0x0020;
                    cm << "<" << hex2(static_cast<uint32_t>(c)) << "> <" << hex4(u) << ">\n";
                }
                cm << "endbfchar\n";
            }
            cm << "endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n";
            return cm.str();
        };
        auto build2ByteFallbackBfRanges = [&]() -> std::string {
            // PDF CMap spec requires bfrange start/end to share all bytes except the last byte.
            std::ostringstream fb;
            fb << "\n% JPDFiumFallback\n";
            for (int base = 0; base < 256; base += 64) {
                fb << "64 beginbfrange\n";
                for (int hi = base; hi < base + 64; ++hi) {
                    std::string h = hex2(static_cast<uint32_t>(hi));
                    fb << "<" << h << "00> <" << h << "FF> <0020>\n";
                }
                fb << "endbfrange\n";
            }
            return fb.str();
        };
        auto buildFallbackCidToUnicode = [&]() -> std::string {
            return std::string("/CIDInit /ProcSet findresource begin\n"
                   "12 dict begin\n"
                   "begincmap\n"
                   "/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n"
                   "/CMapName /JPDFium-CID-UCS def\n"
                   "/CMapType 2 def\n"
                   "1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n")
                   + build2ByteFallbackBfRanges()
                   + "endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n";
        };
        bool unicodeFixed = false;
        for (auto& obj : qpdf.getAllObjects()) {
            if (!obj.isDictionary() || !obj.getKey("/Type").isNameAndEquals("/Font")) continue;
            QPDFObjectHandle subH = obj.getKey("/Subtype");
            if (!subH.isName()) continue;
            std::string sub = subH.getName();
            if (sub == "/CIDFontType0" || sub == "/CIDFontType2") continue;
            if (obj.hasKey("/ToUnicode") && obj.getKey("/ToUnicode").isStream()) {
                // Sanitize forbidden targets (<0000>, <FFFE>, <FFFF>, <FEFF>, <FFFD>, and PUA <E000>..<F8FF>)
                // and prepend a default fallback bfrange after endcodespacerange so sparse ToUnicode CMaps
                // never leave any used glyph unmapped (fixes 6.2.11.7.2-1, 6.2.11.7.3-1, 6.3.8-1).
                try {
                    auto buf = obj.getKey("/ToUnicode").getStreamData(qpdf_dl_generalized);
                    if (buf && buf->getSize() > 0 && buf->getSize() <= 4U * 1024U * 1024U) {
                        std::string s(reinterpret_cast<const char*>(buf->getBuffer()), buf->getSize());
                        bool dirty = false;
                        // 1. Replace forbidden/PUA 4-hex-digit Unicode targets in "> <XXXX>" or "><XXXX>"
                        auto isHex = [](char c) {
                            return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
                        };
                        auto hexVal = [](char c) -> uint32_t {
                            if (c >= '0' && c <= '9') return static_cast<uint32_t>(c - '0');
                            if (c >= 'a' && c <= 'f') return static_cast<uint32_t>(10 + (c - 'a'));
                            return static_cast<uint32_t>(10 + (c - 'A'));
                        };
                        for (size_t i = 0; i + 6 < s.size(); ++i) {
                            if (s[i] != '>') continue;
                            size_t j = i + 1;
                            while (j < s.size() && (s[j] == ' ' || s[j] == '\t')) ++j;
                            if (j + 5 < s.size() && s[j] == '<' && s[j + 5] == '>' &&
                                isHex(s[j + 1]) && isHex(s[j + 2]) && isHex(s[j + 3]) && isHex(s[j + 4])) {
                                uint32_t cp = (hexVal(s[j + 1]) << 12) | (hexVal(s[j + 2]) << 8) |
                                              (hexVal(s[j + 3]) << 4) | hexVal(s[j + 4]);
                                bool bad = !isValidPdfAUnicode(cp);
                                if (bad) {
                                    s[j + 1] = '0'; s[j + 2] = '0'; s[j + 3] = '2'; s[j + 4] = '0';
                                    dirty = true;
                                }
                            }
                        }
                        // 2. Prepend catch-all fallback bfrange immediately after endcodespacerange
                        //    (earlier entries are overridden by all subsequent real mappings).
                        size_t ecs = s.find("endcodespacerange");
                        if (ecs != std::string::npos && s.find("JPDFiumFallback") == std::string::npos) {
                            size_t ins = ecs + 17;
                            bool is2Byte = (sub == "/Type0");
                            if (!is2Byte) {
                                size_t bcs = s.find("begincodespacerange");
                                if (bcs != std::string::npos && bcs < ecs) {
                                    std::string_view csr(s.data() + bcs, ecs - bcs);
                                    if (csr.find("<0000>") != std::string_view::npos ||
                                        csr.find("<FFFF>") != std::string_view::npos ||
                                        csr.find("<ffff>") != std::string_view::npos) {
                                        is2Byte = true;
                                    }
                                }
                            }
                            std::string fb = is2Byte
                                ? build2ByteFallbackBfRanges()
                                : "\n% JPDFiumFallback\n1 beginbfrange\n<00> <FF> <0020>\nendbfrange\n";
                            s.insert(ins, fb);
                            dirty = true;
                        }
                        if (dirty) {
                            obj.getKey("/ToUnicode").replaceStreamData(
                                s, QPDFObjectHandle::newNull(), QPDFObjectHandle::newNull());
                            unicodeFixed = true;
                        }
                    }
                } catch (...) {
                }
            } else {
                std::string cmap = (sub == "/Type0") ? buildFallbackCidToUnicode() : buildSimpleToUnicode(obj);
                obj.replaceKey("/ToUnicode", QPDFObjectHandle::newStream(&qpdf, cmap));
                unicodeFixed = true;
            }
        }
        if (unicodeFixed) transforms.push_back("pdfa_ensure_unicode");
    }

    // 6g. PDF/A Level A (1a/2a/3a): ensure /MarkInfo /Marked true, /Lang, and valid /StructTreeRoot.
    if (pdfaConformance == 'A') {
        QPDFObjectHandle markInfo = (root.hasKey("/MarkInfo") && root.getKey("/MarkInfo").isDictionary())
                                        ? root.getKey("/MarkInfo")
                                        : QPDFObjectHandle::newDictionary();
        markInfo.replaceKey("/Marked", QPDFObjectHandle::newBool(true));
        root.replaceKey("/MarkInfo", markInfo);
        if (!root.hasKey("/Lang") || !root.getKey("/Lang").isString() || root.getKey("/Lang").getStringValue().empty()) {
            root.replaceKey("/Lang", QPDFObjectHandle::newString("en-US"));
        }

        if (root.hasKey("/StructTreeRoot") && root.getKey("/StructTreeRoot").isDictionary()) {
            // Preserve existing logical structure tree; repair /Type, missing /Figure /Alt, and /RoleMap.
            QPDFObjectHandle str = root.getKey("/StructTreeRoot");
            if (!str.hasKey("/Type")) str.replaceKey("/Type", QPDFObjectHandle::newName("/StructTreeRoot"));
            static const std::set<std::string> kStdRoles14 = {
                "/Document", "/Part", "/Art", "/Sect", "/Div", "/BlockQuote", "/Caption", "/TOC",
                "/TOCI", "/Index", "/NonStruct", "/Private", "/P", "/H", "/H1", "/H2", "/H3", "/H4",
                "/H5", "/H6", "/L", "/LI", "/Lbl", "/LBody", "/Table", "/TR", "/TH", "/TD",
                "/Span", "/Quote", "/Note", "/Reference", "/BibEntry", "/Code",
                "/Link", "/Figure", "/Formula", "/Form"
            };
            static const std::set<std::string> kStdRoles17 = {
                "/Document", "/Part", "/Art", "/Sect", "/Div", "/BlockQuote", "/Caption", "/TOC",
                "/TOCI", "/Index", "/NonStruct", "/Private", "/P", "/H", "/H1", "/H2", "/H3", "/H4",
                "/H5", "/H6", "/L", "/LI", "/Lbl", "/LBody", "/Table", "/TR", "/TH", "/TD", "/THead",
                "/TBody", "/TFoot", "/Span", "/Quote", "/Note", "/Reference", "/BibEntry", "/Code",
                "/Link", "/Annot", "/Ruby", "/RB", "/RT", "/RP", "/Warichu", "/WT", "/WP", "/Figure",
                "/Formula", "/Form"
            };
            const auto& kStdRoles = (pdfaPart == 1) ? kStdRoles14 : kStdRoles17;
            QPDFObjectHandle roleMap = (str.hasKey("/RoleMap") && str.getKey("/RoleMap").isDictionary())
                                           ? str.getKey("/RoleMap")
                                           : QPDFObjectHandle::newDictionary();
            bool roleMapDirty = false;
            for (auto& obj : qpdf.getAllObjects()) {
                if (!obj.isDictionary()) continue;
                if (!obj.getKey("/Type").isNameAndEquals("/StructElem") && !obj.hasKey("/P")) continue;
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
            QPDFObjectHandle openStream = QPDFObjectHandle::newStream(&qpdf, "/P <</MCID 0>> BDC\n");
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
            QPDFObjectHandle parentTree = qpdf.makeIndirectObject(QPDFObjectHandle::newDictionary());
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

void applyPdfAStructure(QPDF& qpdf, int pdfaPart, char pdfaConformance,
                        const uint8_t* srgbIcc, int64_t srgbIccLen,
                        const uint8_t* cmykIcc, int64_t cmykIccLen,
                        int unembeddedFontsRemaining,
                        std::vector<std::string>& transforms) {
    PdfAStructureContext pctx{qpdf, pdfaPart, pdfaConformance, srgbIcc, srgbIccLen, cmykIcc, cmykIccLen, transforms, {}, false, {}, {}, false, false, false, false, false};
    applyPdfAXmpAndOutputIntent(pctx, srgbIcc, srgbIccLen, cmykIcc, cmykIccLen, unembeddedFontsRemaining);
    applyPdfAObjectAndPageNormalization(pctx);
    applyPdfAProfileExtensions(pctx);
}

}  // namespace jpdfium::rewriter
