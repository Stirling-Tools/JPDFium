// jpdfium_rewriter_pdfa.cpp - PDF/A-2b document structure normalization:
// XMP/Info sync, OutputIntents, ColorSpace/Group/Shading/DeviceN/ICC repair,
// AcroForm/Action/OCProperties/Annotation cleanup, and UTF-8 name sanitization.

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <functional>
#include <qpdf/QPDFAcroFormDocumentHelper.hh>
#include <qpdf/QPDFPageDocumentHelper.hh>
#include <sstream>
#include <utility>

#include "jpdfium_font_rewriter_internal.h"
namespace jpdfium::rewriter {

namespace {

// Remove a bare "undefined" operator token from a content stream. The
// tokenizer skips literal/hex strings, names, comments, and dictionaries so
// only an actual operator is removed; streams containing inline images are
// left untouched because their binary ID...EI payload is not tokenized here.
bool stripUndefinedContentOperator(std::string& data) {
    if (data.find("undefined") == std::string::npos) return false;
    if (data.find("BI") != std::string::npos) return false;
    const auto isDelimiter = [](char c) {
        return std::isspace(static_cast<unsigned char>(c)) || c == '(' || c == ')' || c == '<' ||
               c == '>' || c == '[' || c == ']' || c == '{' || c == '}' || c == '/' || c == '%';
    };
    std::string out;
    out.reserve(data.size());
    bool changed = false;
    size_t i = 0;
    while (i < data.size()) {
        const char c = data[i];
        if (c == '%') {
            size_t end = data.find('\n', i);
            if (end == std::string::npos) end = data.size();
            out.append(data, i, end - i);
            i = end;
            continue;
        }
        if (c == '(') {
            int depth = 1;
            out.push_back(data[i++]);
            while (i < data.size() && depth > 0) {
                const char ch = data[i++];
                out.push_back(ch);
                if (ch == '\\' && i < data.size()) {
                    out.push_back(data[i++]);
                } else if (ch == '(') {
                    ++depth;
                } else if (ch == ')') {
                    --depth;
                }
            }
            continue;
        }
        if (c == '<' && i + 1 < data.size() && data[i + 1] == '<') {
            out += "<<";
            i += 2;
            continue;
        }
        if (c == '<') {
            const size_t end = data.find('>', i + 1);
            const size_t stop = end == std::string::npos ? data.size() : end + 1;
            out.append(data, i, stop - i);
            i = stop;
            continue;
        }
        if (c == '/') {
            size_t end = i + 1;
            while (end < data.size() && !isDelimiter(data[end])) ++end;
            out.append(data, i, end - i);
            i = end;
            continue;
        }
        if (c == ')' || c == '>' || c == ']' || c == '}' || c == '[' || c == '{') {
            out.push_back(c);
            ++i;
            continue;
        }
        if (std::isspace(static_cast<unsigned char>(c))) {
            out.push_back(c);
            ++i;
            continue;
        }
        size_t end = i;
        while (end < data.size() && !isDelimiter(data[end])) ++end;
        const std::string token = data.substr(i, end - i);
        if (token == "undefined") {
            changed = true;
            i = end;
            while (i < data.size() && std::isspace(static_cast<unsigned char>(data[i]))) ++i;
            continue;
        }
        out.append(data, i, end - i);
        i = end;
    }
    if (changed) data = std::move(out);
    return changed;
}

}  // namespace

void applyPdfAObjectAndPageNormalization(PdfAStructureContext& pctx) {
    QPDF& qpdf = pctx.qpdf;
    QPDFObjectHandle& root = pctx.root;
    int pdfaPart = pctx.pdfaPart;
    bool useCmykOutputIntent = pctx.useCmykIntent;
    const uint8_t* srgbIcc = pctx.srgbIcc;
    int64_t srgbIccLen = pctx.srgbIccLen;
    const uint8_t* cmykIcc = pctx.cmykIcc;
    int64_t cmykIccLen = pctx.cmykIccLen;
    auto& transforms = pctx.transforms;
    (void)transforms;

    pctx.docIndex.build(qpdf);
    const auto& allObjects = pctx.docIndex.allObjects;

    auto getIccRgb = [&]() -> QPDFObjectHandle {
        return useCmykOutputIntent ? pctx.ensureIccRgbArr() : QPDFObjectHandle::newNull();
    };
    auto getIccCmyk = [&]() -> QPDFObjectHandle {
        return (!useCmykOutputIntent && pctx.docIndex.usesDeviceCmyk) ? pctx.ensureIccCmykArr()
                                                                      : QPDFObjectHandle::newNull();
    };

    // 5. Walk all objects for PDF/A-2b restrictions (ExtGState, Images, Annots, Groups, Actions,
    // LZW, Metadata)
    bool cmykOpsFixed = false;
    bool annotsFixed = false;
    bool extGStateFixed = false;
    bool interpolateFixed = false;
    bool groupFixed = false;

    // Recursively strip /Metadata from all non-root objects (including nested direct dicts,
    // fixes 6.6.2.3.1-1/2)
    {
        std::set<QPDFObjGen> metaVisited;
        QPDFObjGen rootGen = root.getObjGen();
        for (const auto& obj : allObjects) {
            stripNonRootMetadataRecursive(obj, rootGen, metaVisited);
        }
    }

    for (auto obj : allObjects) {
        if (obj.isStream()) {
            QPDFObjectHandle d = obj.getDict();
            // Strip forbidden external stream keys (/F, /FFilter, /FDecodeParms) per ISO
            // 19005 6.1.7.1.
            if (d.hasKey("/F")) d.removeKey("/F");
            if (d.hasKey("/FFilter")) d.removeKey("/FFilter");
            if (d.hasKey("/FDecodeParms")) d.removeKey("/FDecodeParms");
            // Repair corrupt/invalid ICCBased profile streams (fixes 6.2.4.2-1 & 6.2.3.2-1)
            if (d.hasKey("/N") && d.getKey("/N").isInteger() && !d.hasKey("/Subtype")) {
                long long iccN = d.getKey("/N").getIntValue();
                if (iccN == 3 || iccN == 4) {
                    std::vector<uint8_t> ib;
                    bool validIcc = false;
                    if (readStreamBytesBounded(obj, ib, qpdf_dl_generalized) && ib.size() >= 128) {
                        const bool versionOk =
                            (pdfaPart == 1) ? (ib[8] == 2) : (ib[8] == 2 || ib[8] == 4);
                        if (ib[36] == 'a' && ib[37] == 'c' && ib[38] == 's' && ib[39] == 'p' &&
                            versionOk) {
                            validIcc = true;
                        }
                    }
                    if (!validIcc) {
                        if (iccN == 3 && srgbIcc && srgbIccLen >= 128) {
                            std::string rep(reinterpret_cast<const char*>(srgbIcc),
                                            static_cast<size_t>(srgbIccLen));
                            obj.replaceStreamData(rep, QPDFObjectHandle::newNull(),
                                                  QPDFObjectHandle::newNull());
                            cmykOpsFixed = true;
                        } else if (iccN == 4 && cmykIcc && cmykIccLen >= 128) {
                            std::string rep(reinterpret_cast<const char*>(cmykIcc),
                                            static_cast<size_t>(cmykIccLen));
                            obj.replaceStreamData(rep, QPDFObjectHandle::newNull(),
                                                  QPDFObjectHandle::newNull());
                            cmykOpsFixed = true;
                        }
                    }
                }
            }
            // Recompress LZWDecode streams (prohibited by PDF/A-2b 6.1.8-1)
            if (d.hasKey("/Filter")) {
                QPDFObjectHandle flt = d.getKey("/Filter");
                bool hasLzw = flt.isNameAndEquals("/LZWDecode");
                if (!hasLzw && flt.isArray()) {
                    const int fn = std::min(flt.getArrayNItems(), 64);
                    for (int fi = 0; fi < fn; ++fi) {
                        if (flt.getArrayItem(fi).isNameAndEquals("/LZWDecode")) {
                            hasLzw = true;
                            break;
                        }
                    }
                }
                if (hasLzw) {
                    std::string raw;
                    if (readStreamBounded(obj, raw, qpdf_dl_generalized)) {
                        obj.replaceStreamData(raw, QPDFObjectHandle::newNull(),
                                              QPDFObjectHandle::newNull());
                    }
                }
            }
            // Image /Interpolate must be false (6.2.5-1)
            if (d.getKey("/Subtype").isNameAndEquals("/Image")) {
                if (d.hasKey("/Interpolate") && d.getKey("/Interpolate").isBool() &&
                    d.getKey("/Interpolate").getBoolValue()) {
                    d.replaceKey("/Interpolate", QPDFObjectHandle::newBool(false));
                    interpolateFixed = true;
                }
                if (d.hasKey("/OPI")) d.removeKey("/OPI");
                if (d.hasKey("/Alternates")) d.removeKey("/Alternates");
                if (d.hasKey("/Intent")) d.removeKey("/Intent");
            }
            // Transparency Group /CS (6.2.10-2, 6.2.4.3-2, 6.2.4.3-3)
            if (d.hasKey("/Group") && d.getKey("/Group").isDictionary()) {
                QPDFObjectHandle grp = d.getKey("/Group");
                if (grp.getKey("/S").isNameAndEquals("/Transparency")) {
                    if (!grp.hasKey("/CS")) {
                        grp.replaceKey(
                            "/CS", QPDFObjectHandle::newName(useCmykOutputIntent ? "/DeviceCMYK"
                                                                                 : "/DeviceRGB"));
                        groupFixed = true;
                    } else if (useCmykOutputIntent &&
                               grp.getKey("/CS").isNameAndEquals("/DeviceRGB")) {
                        QPDFObjectHandle iccRgb = getIccRgb();
                        if (!iccRgb.isNull()) {
                            grp.replaceKey("/CS", iccRgb);
                            groupFixed = true;
                        }
                    } else if (!useCmykOutputIntent &&
                               grp.getKey("/CS").isNameAndEquals("/DeviceCMYK")) {
                        QPDFObjectHandle iccCmyk = getIccCmyk();
                        grp.replaceKey("/CS", !iccCmyk.isNull()
                                                  ? iccCmyk
                                                  : QPDFObjectHandle::newName("/DeviceRGB"));
                        groupFixed = true;
                    }
                }
            }
            // Form XObject prohibited keys (6.2.9-1/2, 6.2.6-1: PS, OPI, Subtype2=PS, Ref)
            if (d.getKey("/Subtype").isNameAndEquals("/Form")) {
                if (d.hasKey("/PS")) d.removeKey("/PS");
                if (d.hasKey("/OPI")) d.removeKey("/OPI");
                if (d.hasKey("/Ref")) d.removeKey("/Ref");
                if (d.hasKey("/Subtype2") && d.getKey("/Subtype2").isNameAndEquals("/PS")) {
                    d.removeKey("/Subtype2");
                }
            }
            // Convert DeviceRGB -> ICCBased sRGB (when using CMYK OI) or DeviceCMYK -> ICCBased
            // CMYK
            if (d.getKey("/Subtype").isNameAndEquals("/Form") ||
                (d.hasKey("/PatternType") && d.getKey("/PatternType").isInteger() &&
                 d.getKey("/PatternType").getIntValue() == 1)) {
                std::string s;
                if (readStreamBounded(obj, s, qpdf_dl_generalized) && !s.empty()) {
                    if (disableInlineImageInterpolate(s)) {
                        obj.replaceStreamData(s, QPDFObjectHandle::newNull(),
                                              QPDFObjectHandle::newNull());
                        interpolateFixed = true;
                    }
                    if (useCmykOutputIntent) {
                        if (convertRgbOpsToIcc(s, "/JPDFRgb")) {
                            QPDFObjectHandle iccRgb = getIccRgb();
                            if (!iccRgb.isNull()) {
                                obj.replaceStreamData(s, QPDFObjectHandle::newNull(),
                                                      QPDFObjectHandle::newNull());
                                QPDFObjectHandle res = d.getKey("/Resources");
                                if (!res.isDictionary()) {
                                    res = QPDFObjectHandle::newDictionary();
                                    d.replaceKey("/Resources", res);
                                }
                                QPDFObjectHandle csDict = res.getKey("/ColorSpace");
                                if (!csDict.isDictionary()) {
                                    csDict = QPDFObjectHandle::newDictionary();
                                    res.replaceKey("/ColorSpace", csDict);
                                }
                                csDict.replaceKey("/JPDFRgb", iccRgb);
                                csDict.replaceKey("/DefaultRGB", iccRgb);
                                cmykOpsFixed = true;
                            }
                        } else if (d.hasKey("/Resources") &&
                                   d.getKey("/Resources").isDictionary()) {
                            QPDFObjectHandle res = d.getKey("/Resources");
                            QPDFObjectHandle csDict = res.getKey("/ColorSpace");
                            if (csDict.isDictionary() && !csDict.hasKey("/DefaultRGB")) {
                                QPDFObjectHandle iccRgb = getIccRgb();
                                if (!iccRgb.isNull()) csDict.replaceKey("/DefaultRGB", iccRgb);
                            }
                        }
                    } else {
                        if (convertCmykOpsToRgb(s, "/JPDFCmyk")) {
                            QPDFObjectHandle iccCmyk = getIccCmyk();
                            if (!iccCmyk.isNull()) {
                                obj.replaceStreamData(s, QPDFObjectHandle::newNull(),
                                                      QPDFObjectHandle::newNull());
                                QPDFObjectHandle res = d.getKey("/Resources");
                                if (!res.isDictionary()) {
                                    res = QPDFObjectHandle::newDictionary();
                                    d.replaceKey("/Resources", res);
                                }
                                QPDFObjectHandle csDict = res.getKey("/ColorSpace");
                                if (!csDict.isDictionary()) {
                                    csDict = QPDFObjectHandle::newDictionary();
                                    res.replaceKey("/ColorSpace", csDict);
                                }
                                csDict.replaceKey("/JPDFCmyk", iccCmyk);
                                csDict.replaceKey("/DefaultCMYK", iccCmyk);
                                cmykOpsFixed = true;
                            }
                        }
                    }
                }
            }
            // Replace mismatched device /ColorSpace on Images/Shadings/XObjects
            if (useCmykOutputIntent && d.hasKey("/ColorSpace")) {
                QPDFObjectHandle cs = d.getKey("/ColorSpace");
                if (cs.isNameAndEquals("/DeviceRGB")) {
                    QPDFObjectHandle iccRgb = getIccRgb();
                    if (!iccRgb.isNull()) {
                        d.replaceKey("/ColorSpace", iccRgb);
                        cmykOpsFixed = true;
                    }
                } else if (cs.isArray() && cs.getArrayNItems() >= 2 &&
                           cs.getArrayItem(0).isNameAndEquals("/Indexed") &&
                           cs.getArrayItem(1).isNameAndEquals("/DeviceRGB")) {
                    QPDFObjectHandle iccRgb = getIccRgb();
                    if (!iccRgb.isNull()) {
                        cs.setArrayItem(1, iccRgb);
                        cmykOpsFixed = true;
                    }
                }
            }
            if (!useCmykOutputIntent && d.hasKey("/ColorSpace") &&
                d.getKey("/ColorSpace").isNameAndEquals("/DeviceCMYK")) {
                QPDFObjectHandle iccCmyk = getIccCmyk();
                if (!iccCmyk.isNull()) {
                    d.replaceKey("/ColorSpace", iccCmyk);
                    cmykOpsFixed = true;
                }
            }
        }
        if (obj.isDictionary()) {
            // Replace mismatched device /ColorSpace on Shading/Group/Resources dicts
            if (useCmykOutputIntent && obj.hasKey("/ColorSpace")) {
                QPDFObjectHandle cs = obj.getKey("/ColorSpace");
                if (cs.isNameAndEquals("/DeviceRGB")) {
                    QPDFObjectHandle iccRgb = getIccRgb();
                    if (!iccRgb.isNull()) {
                        obj.replaceKey("/ColorSpace", iccRgb);
                        cmykOpsFixed = true;
                    }
                } else if (cs.isDictionary()) {
                    for (const auto& ck : cs.getKeys()) {
                        QPDFObjectHandle csv = cs.getKey(ck);
                        if (csv.isNameAndEquals("/DeviceRGB")) {
                            QPDFObjectHandle iccRgb = getIccRgb();
                            if (!iccRgb.isNull()) {
                                cs.replaceKey(ck, iccRgb);
                                cmykOpsFixed = true;
                            }
                        } else if (csv.isArray() && csv.getArrayNItems() >= 3) {
                            QPDFObjectHandle csType = csv.getArrayItem(0);
                            if ((csType.isNameAndEquals("/Separation") ||
                                 csType.isNameAndEquals("/DeviceN")) &&
                                csv.getArrayItem(2).isNameAndEquals("/DeviceRGB")) {
                                QPDFObjectHandle iccRgb = getIccRgb();
                                if (!iccRgb.isNull()) {
                                    csv.setArrayItem(2, iccRgb);
                                    cmykOpsFixed = true;
                                }
                            } else if (csType.isNameAndEquals("/Indexed") &&
                                       csv.getArrayItem(1).isNameAndEquals("/DeviceRGB")) {
                                QPDFObjectHandle iccRgb = getIccRgb();
                                if (!iccRgb.isNull()) {
                                    csv.setArrayItem(1, iccRgb);
                                    cmykOpsFixed = true;
                                }
                            }
                        }
                    }
                }
            }
            if (!useCmykOutputIntent && obj.hasKey("/ColorSpace") &&
                obj.getKey("/ColorSpace").isNameAndEquals("/DeviceCMYK")) {
                QPDFObjectHandle iccCmyk = getIccCmyk();
                if (!iccCmyk.isNull()) {
                    obj.replaceKey("/ColorSpace", iccCmyk);
                    cmykOpsFixed = true;
                }
            }
            // Replace /DeviceCMYK alternate space inside [/Separation ...] or [/DeviceN ...] arrays
            if (!useCmykOutputIntent && obj.hasKey("/ColorSpace") &&
                obj.getKey("/ColorSpace").isDictionary()) {
                QPDFObjectHandle csDict = obj.getKey("/ColorSpace");
                for (const auto& ck : csDict.getKeys()) {
                    QPDFObjectHandle csv = csDict.getKey(ck);
                    if (csv.isNameAndEquals("/DeviceCMYK")) {
                        QPDFObjectHandle iccCmyk = getIccCmyk();
                        if (!iccCmyk.isNull()) {
                            csDict.replaceKey(ck, iccCmyk);
                            cmykOpsFixed = true;
                        }
                    } else if (csv.isArray() && csv.getArrayNItems() >= 3) {
                        QPDFObjectHandle csType = csv.getArrayItem(0);
                        if ((csType.isNameAndEquals("/Separation") ||
                             csType.isNameAndEquals("/DeviceN")) &&
                            csv.getArrayItem(2).isNameAndEquals("/DeviceCMYK")) {
                            QPDFObjectHandle iccCmyk = getIccCmyk();
                            if (!iccCmyk.isNull()) {
                                csv.setArrayItem(2, iccCmyk);
                                cmykOpsFixed = true;
                            }
                        } else if (csType.isNameAndEquals("/Indexed") &&
                                   csv.getArrayItem(1).isNameAndEquals("/DeviceCMYK")) {
                            QPDFObjectHandle iccCmyk = getIccCmyk();
                            if (!iccCmyk.isNull()) {
                                csv.setArrayItem(1, iccCmyk);
                                cmykOpsFixed = true;
                            }
                        }
                    }
                }
            }
            // ExtGState prohibited entries (6.2.8-3: TR, TR2, HT; 6.2.4.2-2: OPM must be 0 with
            // ICCBased CMYK)
            if (obj.getKey("/Type").isNameAndEquals("/ExtGState") || obj.hasKey("/TR") ||
                obj.hasKey("/TR2") || obj.hasKey("/HT") || obj.hasKey("/OPM")) {
                if (obj.hasKey("/TR")) {
                    obj.removeKey("/TR");
                    extGStateFixed = true;
                }
                if (obj.hasKey("/TR2") && !obj.getKey("/TR2").isNameAndEquals("/Default")) {
                    obj.removeKey("/TR2");
                    extGStateFixed = true;
                }
                if (obj.hasKey("/HT")) {
                    obj.removeKey("/HT");
                    extGStateFixed = true;
                }
                if (obj.hasKey("/OPM") && obj.getKey("/OPM").isInteger() &&
                    obj.getKey("/OPM").getIntValue() != 0) {
                    obj.replaceKey("/OPM", QPDFObjectHandle::newInteger(0));
                    extGStateFixed = true;
                }
            }
            // Direct /Shading sub-dictionary inside ShadingPattern (fixes 6.2.4.3-2 / 6.2.4.3-3)
            if (obj.hasKey("/Shading") && obj.getKey("/Shading").isDictionary()) {
                QPDFObjectHandle sh = obj.getKey("/Shading");
                if (sh.hasKey("/ColorSpace")) {
                    QPDFObjectHandle scs = sh.getKey("/ColorSpace");
                    if (useCmykOutputIntent && scs.isNameAndEquals("/DeviceRGB")) {
                        QPDFObjectHandle iccRgb = getIccRgb();
                        if (!iccRgb.isNull()) {
                            sh.replaceKey("/ColorSpace", iccRgb);
                            cmykOpsFixed = true;
                        }
                    } else if (!useCmykOutputIntent && scs.isNameAndEquals("/DeviceCMYK")) {
                        QPDFObjectHandle iccCmyk = getIccCmyk();
                        if (!iccCmyk.isNull()) {
                            sh.replaceKey("/ColorSpace", iccCmyk);
                            cmykOpsFixed = true;
                        }
                    }
                }
            }
            // Page transparency /Group
            if (obj.getKey("/Type").isNameAndEquals("/Page") && obj.hasKey("/Group") &&
                obj.getKey("/Group").isDictionary()) {
                QPDFObjectHandle grp = obj.getKey("/Group");
                if (grp.getKey("/S").isNameAndEquals("/Transparency")) {
                    if (!grp.hasKey("/CS")) {
                        grp.replaceKey(
                            "/CS", QPDFObjectHandle::newName(useCmykOutputIntent ? "/DeviceCMYK"
                                                                                 : "/DeviceRGB"));
                        groupFixed = true;
                    } else if (useCmykOutputIntent &&
                               grp.getKey("/CS").isNameAndEquals("/DeviceRGB")) {
                        QPDFObjectHandle iccRgb = getIccRgb();
                        if (!iccRgb.isNull()) {
                            grp.replaceKey("/CS", iccRgb);
                            groupFixed = true;
                        }
                    } else if (!useCmykOutputIntent &&
                               grp.getKey("/CS").isNameAndEquals("/DeviceCMYK")) {
                        QPDFObjectHandle iccCmyk = getIccCmyk();
                        grp.replaceKey("/CS", !iccCmyk.isNull()
                                                  ? iccCmyk
                                                  : QPDFObjectHandle::newName("/DeviceRGB"));
                        groupFixed = true;
                    }
                }
            }
            // Strip /AA (Additional Actions, prohibited on Form fields/Annots/Pages by 6.4.1-2
            // / 6.5.1-1)
            if (obj.hasKey("/AA")) {
                obj.removeKey("/AA");
                annotsFixed = true;
            }
            // Strip non-PDF/A embedded file streams (/EF on FileSpec, /AF associated files -
            // fixes 6.8-5)
            if (pdfaPart != 3 && obj.hasKey("/EF")) {
                obj.removeKey("/EF");
                annotsFixed = true;
            }
            if (pdfaPart != 3 && obj.hasKey("/AF")) {
                obj.removeKey("/AF");
                annotsFixed = true;
            }
            // Strip prohibited /A actions (JavaScript, Launch, Sound, Movie, ResetForm, ImportData,
            // Hide, etc. - 6.5.1-1)
            if (obj.hasKey("/A") && obj.getKey("/A").isDictionary()) {
                QPDFObjectHandle act = obj.getKey("/A");
                if (act.hasKey("/S") && act.getKey("/S").isName()) {
                    std::string as = act.getKey("/S").getName();
                    if (as == "/JavaScript" || as == "/Launch" || as == "/Sound" ||
                        as == "/Movie" || as == "/ResetForm" || as == "/ImportData" ||
                        as == "/Hide" || as == "/SetState" || as == "/SetOCGState" ||
                        as == "/NOP" || as == "/SubmitForm" || as == "/Rendition" ||
                        as == "/Trans" || as == "/GoTo3DView" ||
                        (pdfaPart == 1 && as == "/GoToE")) {
                        obj.removeKey("/A");
                        annotsFixed = true;
                    } else if (as == "/Named" && act.hasKey("/N") && act.getKey("/N").isName()) {
                        std::string nn = act.getKey("/N").getName();
                        if (nn != "/NextPage" && nn != "/PrevPage" && nn != "/FirstPage" &&
                            nn != "/LastPage") {
                            act.replaceKey("/N", QPDFObjectHandle::newName("/NextPage"));
                            annotsFixed = true;
                        }
                    }
                }
            }
            // Ensure Form XObjects inside Page /Resources /XObject have explicit /Resources
            // (6.2.2-2)
            if (obj.getKey("/Type").isNameAndEquals("/Page") && obj.hasKey("/Resources") &&
                obj.getKey("/Resources").isDictionary()) {
                QPDFObjectHandle pRes = obj.getKey("/Resources");
                if (pRes.hasKey("/XObject") && pRes.getKey("/XObject").isDictionary()) {
                    QPDFObjectHandle xos = pRes.getKey("/XObject");
                    for (const auto& xk : xos.getKeys()) {
                        QPDFObjectHandle xo = xos.getKey(xk);
                        if (xo.isStream() &&
                            xo.getDict().getKey("/Subtype").isNameAndEquals("/Form")) {
                            QPDFObjectHandle xd = xo.getDict();
                            if (!xd.hasKey("/Resources")) {
                                xd.replaceKey("/Resources", pRes);
                            }
                        }
                    }
                }
            }
            // Strip invalidated cryptographic signature values (/FT /Sig -> remove /V, keeping
            // visual /AP, fixes 6.4.3-1 / 6.1.12-2)
            if (obj.getKey("/FT").isNameAndEquals("/Sig") && obj.hasKey("/V")) {
                obj.removeKey("/V");
                annotsFixed = true;
            }
            // Disallowed Named actions (6.5.1-2)
            if (obj.getKey("/S").isNameAndEquals("/Named") && obj.hasKey("/N") &&
                obj.getKey("/N").isName()) {
                std::string nn = obj.getKey("/N").getName();
                if (nn != "/NextPage" && nn != "/PrevPage" && nn != "/FirstPage" &&
                    nn != "/LastPage") {
                    obj.replaceKey("/N", QPDFObjectHandle::newName("/NextPage"));
                }
            }
            if (obj.hasKey("/Next")) {
                QPDFObjectHandle nxt = obj.getKey("/Next");
                if (nxt.isDictionary() && nxt.getKey("/S").isNameAndEquals("/Named") &&
                    nxt.hasKey("/N") && nxt.getKey("/N").isName()) {
                    std::string nn = nxt.getKey("/N").getName();
                    if (nn != "/NextPage" && nn != "/PrevPage" && nn != "/FirstPage" &&
                        nn != "/LastPage") {
                        obj.removeKey("/Next");
                    }
                }
            }
        } else if (obj.isArray() && obj.getArrayNItems() >= 4 &&
                   obj.getArrayItem(0).isNameAndEquals("/DeviceN")) {
            // Ensure every non-process spot colorant in a DeviceN array is defined in attributes
            // /Colorants (6.2.4.4-1)
            QPDFObjectHandle names = obj.getArrayItem(1);
            QPDFObjectHandle altCs = obj.getArrayItem(2);
            if (names.isArray()) {
                std::vector<std::string> missingSpots;
                QPDFObjectHandle attrs =
                    (obj.getArrayNItems() >= 5 && obj.getArrayItem(4).isDictionary())
                        ? obj.getArrayItem(4)
                        : QPDFObjectHandle::newNull();
                QPDFObjectHandle colDict = (!attrs.isNull() && attrs.hasKey("/Colorants") &&
                                            attrs.getKey("/Colorants").isDictionary())
                                               ? attrs.getKey("/Colorants")
                                               : QPDFObjectHandle::newNull();
                for (int ni = 0; ni < names.getArrayNItems(); ++ni) {
                    if (!names.getArrayItem(ni).isName()) continue;
                    std::string cn = names.getArrayItem(ni).getName();
                    if (cn == "/Cyan" || cn == "/Magenta" || cn == "/Yellow" || cn == "/Black" ||
                        cn == "/None" || cn == "/All")
                        continue;
                    if (colDict.isNull() || !colDict.hasKey(cn)) {
                        missingSpots.push_back(cn);
                    }
                }
                if (!missingSpots.empty()) {
                    if (attrs.isNull()) {
                        attrs = QPDFObjectHandle::newDictionary();
                        if (obj.getArrayNItems() >= 5)
                            obj.setArrayItem(4, attrs);
                        else
                            obj.appendItem(attrs);
                    }
                    if (colDict.isNull()) {
                        colDict = QPDFObjectHandle::newDictionary();
                        attrs.replaceKey("/Colorants", colDict);
                    }
                    // Determine alternate space component count (default 4 for CMYK, 3 for RGB, 1
                    // for Gray)
                    int altN = 4;
                    if (altCs.isNameAndEquals("/DeviceRGB"))
                        altN = 3;
                    else if (altCs.isNameAndEquals("/DeviceGray"))
                        altN = 1;
                    else if (altCs.isArray() && altCs.getArrayNItems() >= 2 &&
                             altCs.getArrayItem(0).isNameAndEquals("/ICCBased") &&
                             altCs.getArrayItem(1).isStream() &&
                             altCs.getArrayItem(1).getDict().getKey("/N").isInteger()) {
                        altN = static_cast<int>(
                            altCs.getArrayItem(1).getDict().getKey("/N").getIntValue());
                    }
                    QPDFObjectHandle fn = QPDFObjectHandle::newDictionary();
                    fn.replaceKey("/FunctionType", QPDFObjectHandle::newInteger(2));
                    QPDFObjectHandle dom = QPDFObjectHandle::newArray();
                    dom.appendItem(QPDFObjectHandle::newInteger(0));
                    dom.appendItem(QPDFObjectHandle::newInteger(1));
                    fn.replaceKey("/Domain", dom);
                    QPDFObjectHandle c0 = QPDFObjectHandle::newArray();
                    QPDFObjectHandle c1 = QPDFObjectHandle::newArray();
                    for (int ci = 0; ci < altN; ++ci) {
                        c0.appendItem(QPDFObjectHandle::newInteger(0));
                        c1.appendItem(QPDFObjectHandle::newInteger(ci == altN - 1 ? 1 : 0));
                    }
                    fn.replaceKey("/C0", c0);
                    fn.replaceKey("/C1", c1);
                    fn.replaceKey("/N", QPDFObjectHandle::newInteger(1));
                    for (const auto& cn : missingSpots) {
                        QPDFObjectHandle sep = QPDFObjectHandle::newArray();
                        sep.appendItem(QPDFObjectHandle::newName("/Separation"));
                        sep.appendItem(QPDFObjectHandle::newName(cn));
                        sep.appendItem(altCs);
                        sep.appendItem(fn);
                        colDict.replaceKey(cn, sep);
                    }
                    cmykOpsFixed = true;
                }
            }
        }
    }

    // 5b. Unify repeated /Separation color spaces (6.2.4.4-2) & sanitize non-UTF-8 Separation
    // colorant names (6.1.8-1)
    {
        std::map<std::string, std::pair<QPDFObjectHandle, QPDFObjectHandle>> sepCanonical;
        int sepSanIdx = 1;
        auto processSepArr = [&](QPDFObjectHandle arr) {
            if (!arr.isArray() || arr.getArrayNItems() < 4) return;
            if (!arr.getArrayItem(0).isNameAndEquals("/Separation")) return;
            QPDFObjectHandle nameObj = arr.getArrayItem(1);
            if (!nameObj.isName()) return;
            std::string nm = nameObj.getName();
            if (!isValidUtf8(nm)) {
                nm = "/Colorant" + std::to_string(sepSanIdx++);
                arr.setArrayItem(1, QPDFObjectHandle::newName(nm));
            }
            if (useCmykOutputIntent && arr.getArrayItem(2).isNameAndEquals("/DeviceRGB")) {
                QPDFObjectHandle iccRgb = getIccRgb();
                if (!iccRgb.isNull()) arr.setArrayItem(2, iccRgb);
            }
            auto it = sepCanonical.find(nm);
            if (it == sepCanonical.end()) {
                sepCanonical[nm] = {arr.getArrayItem(2), arr.getArrayItem(3)};
            } else {
                arr.setArrayItem(2, it->second.first);
                arr.setArrayItem(3, it->second.second);
            }
        };
        for (const auto& obj : allObjects) {
            if (obj.isArray())
                processSepArr(obj);
            else if (obj.isDictionary() && obj.hasKey("/ColorSpace") &&
                     obj.getKey("/ColorSpace").isDictionary()) {
                QPDFObjectHandle csd = obj.getKey("/ColorSpace");
                for (const auto& ck : csd.getKeys()) processSepArr(csd.getKey(ck));
            }
        }
    }

    // Helper to normalize an annotation dictionary (called on both /Type /Annot and every item in
    // page /Annots)
    auto normalizeAnnot = [&](QPDFObjectHandle obj) {
        if (!obj.isDictionary()) return;
        std::string sub = obj.getKey("/Subtype").isName() ? obj.getKey("/Subtype").getName() : "";
        int f = obj.hasKey("/F") && obj.getKey("/F").isInteger()
                    ? static_cast<int>(obj.getKey("/F").getIntValue())
                    : 0;
        int nf =
            (f | 4) & ~(1 | 2 | 32 | 256);  // Set Print, clear Invisible/Hidden/NoView/ToggleNoView
        if (nf != f || !obj.hasKey("/F")) {
            obj.replaceKey("/F", QPDFObjectHandle::newInteger(nf));
            annotsFixed = true;
        }
        if (obj.hasKey("/AA")) {
            obj.removeKey("/AA");
            annotsFixed = true;
        }
        if (sub == "/Widget" && obj.hasKey("/A")) {
            obj.removeKey("/A");
            annotsFixed = true;
        } else if (obj.hasKey("/A") && obj.getKey("/A").isDictionary()) {
            QPDFObjectHandle act = obj.getKey("/A");
            QPDFObjectHandle s = act.getKey("/S");
            if (s.isName() && (s.getName() == "/JavaScript" || s.getName() == "/Launch" ||
                               s.getName() == "/Sound" || s.getName() == "/Movie" ||
                               s.getName() == "/ResetForm" || s.getName() == "/ImportData")) {
                obj.removeKey("/A");
                annotsFixed = true;
            }
        }
        // Widget /C RGB array when using CMYK OutputIntent: remove /C or keep grayscale
        if (useCmykOutputIntent && obj.hasKey("/C") && obj.getKey("/C").isArray() &&
            obj.getKey("/C").getArrayNItems() == 3) {
            obj.removeKey("/C");
            annotsFixed = true;
        }
        // Ensure /AP /N exists and /AS matches a key in /N (and /V for Widget /FT /Btn,
        // fixes 6.3.3-2/3/4)
        auto makeEmptyApStream = [&]() -> QPDFObjectHandle {
            QPDFObjectHandle apStream = QPDFObjectHandle::newStream(&qpdf, "");
            apStream.getDict().replaceKey("/Type", QPDFObjectHandle::newName("/XObject"));
            apStream.getDict().replaceKey("/Subtype", QPDFObjectHandle::newName("/Form"));
            QPDFObjectHandle bb = QPDFObjectHandle::newArray();
            bb.appendItem(QPDFObjectHandle::newInteger(0));
            bb.appendItem(QPDFObjectHandle::newInteger(0));
            bb.appendItem(QPDFObjectHandle::newInteger(0));
            bb.appendItem(QPDFObjectHandle::newInteger(0));
            apStream.getDict().replaceKey("/BBox", bb);
            return apStream;
        };
        if (obj.hasKey("/AP") && obj.getKey("/AP").isDictionary()) {
            QPDFObjectHandle ap = obj.getKey("/AP");
            QPDFObjectHandle ftObj = obj.getKey("/FT");
            if (ftObj.isNull() && obj.hasKey("/Parent") && obj.getKey("/Parent").isDictionary()) {
                ftObj = obj.getKey("/Parent").getKey("/FT");
            }
            bool isBtn = (sub == "/Widget" && ftObj.isNameAndEquals("/Btn"));
            // For Widget /FT /Btn, /AP /N must be a subdictionary, not a direct Stream (6.3.3-3)
            if (isBtn && ap.hasKey("/N") && ap.getKey("/N").isStream()) {
                std::string stName = (obj.hasKey("/AS") && obj.getKey("/AS").isName())
                                         ? obj.getKey("/AS").getName()
                                         : "/Off";
                QPDFObjectHandle nDict = QPDFObjectHandle::newDictionary();
                nDict.replaceKey(stName, ap.getKey("/N"));
                ap.replaceKey("/N", nDict);
                obj.replaceKey("/AS", QPDFObjectHandle::newName(stName));
                obj.replaceKey("/V", QPDFObjectHandle::newName(stName));
                annotsFixed = true;
            }
            // For non-Btn annotations, /AP /N must be an appearance stream (6.3.3-4)
            if (!isBtn && (!ap.hasKey("/N") || !ap.getKey("/N").isStream())) {
                QPDFObjectHandle candidate;
                if (ap.hasKey("/N") && ap.getKey("/N").isDictionary()) {
                    QPDFObjectHandle nDict = ap.getKey("/N");
                    if (obj.hasKey("/AS") && obj.getKey("/AS").isName()) {
                        std::string asName = obj.getKey("/AS").getName();
                        if (nDict.hasKey(asName) && nDict.getKey(asName).isStream()) {
                            candidate = nDict.getKey(asName);
                        }
                    }
                    if (!candidate.isInitialized()) {
                        for (const auto& nk : nDict.getKeys()) {
                            QPDFObjectHandle v = nDict.getKey(nk);
                            if (v.isStream()) {
                                candidate = std::move(v);
                                break;
                            }
                        }
                    }
                }
                ap.replaceKey("/N", candidate.isInitialized() ? candidate : makeEmptyApStream());
                annotsFixed = true;
            }
            if (ap.hasKey("/N") && ap.getKey("/N").isDictionary() && !ap.getKey("/N").isStream()) {
                auto keys = ap.getKey("/N").getKeys();
                if (!keys.empty()) {
                    std::string desired;
                    if (sub == "/Widget" && obj.getKey("/FT").isNameAndEquals("/Btn") &&
                        obj.hasKey("/V") && obj.getKey("/V").isName() &&
                        keys.count(obj.getKey("/V").getName()) > 0) {
                        desired = obj.getKey("/V").getName();
                    } else if (obj.hasKey("/AS") && obj.getKey("/AS").isName() &&
                               keys.count(obj.getKey("/AS").getName()) > 0) {
                        desired = obj.getKey("/AS").getName();
                    } else if (keys.count("/Off") > 0) {
                        desired = "/Off";
                    } else {
                        desired = *keys.begin();
                    }
                    if (!obj.hasKey("/AS") || !obj.getKey("/AS").isName() ||
                        obj.getKey("/AS").getName() != desired) {
                        obj.replaceKey("/AS", QPDFObjectHandle::newName(desired));
                        annotsFixed = true;
                    }
                    if (sub == "/Widget" && obj.getKey("/FT").isNameAndEquals("/Btn")) {
                        if (!obj.hasKey("/V") || !obj.getKey("/V").isName() ||
                            obj.getKey("/V").getName() != desired) {
                            obj.replaceKey("/V", QPDFObjectHandle::newName(desired));
                            annotsFixed = true;
                        }
                    }
                    // A Widget without /FT /Btn must use a stream for /AP /N
                    // (ISO 19005-1 6.5.3). Orphan appearance-only widgets are
                    // common; flatten the selected state instead of inventing
                    // a form field that would need /FT, /V, and /T.
                    if (sub == "/Widget" && !isBtn) {
                        QPDFObjectHandle chosen = ap.getKey("/N").getKey(desired);
                        if (chosen.isStream()) {
                            ap.replaceKey("/N", chosen);
                            annotsFixed = true;
                        }
                    }
                }
            }
            if (ap.hasKey("/R")) {
                ap.removeKey("/R");
                annotsFixed = true;
            }
            if (ap.hasKey("/D")) {
                ap.removeKey("/D");
                annotsFixed = true;
            }
        } else if (sub != "/Link" && sub != "/Popup") {
            QPDFObjectHandle ap = QPDFObjectHandle::newDictionary();
            ap.replaceKey("/N", makeEmptyApStream());
            obj.replaceKey("/AP", ap);
            annotsFixed = true;
        }
    };
    for (const auto& obj : allObjects) {
        if (obj.isDictionary() && obj.getKey("/Type").isNameAndEquals("/Annot")) {
            normalizeAnnot(obj);
        }
    }

    // 6. Pages: normalize /Annots, sanitize non-UTF-8 resource names (6.1.8-1), and color-space ops
    QPDFPageDocumentHelper pdh(qpdf);
    int utf8NameIdx = 1;
    for (auto& page : pdh.getAllPages()) {
        QPDFObjectHandle po = page.getObjectHandle();
        // Materialize inherited /Resources from /Parent /Pages nodes onto each Page (fixes 6.2.2-2)
        {
            QPDFObjectHandle curRes = po.getKey("/Resources");
            std::set<QPDFObjGen> seenParents;
            if (po.isIndirect()) seenParents.insert(po.getObjGen());
            QPDFObjectHandle parent = po.getKey("/Parent");
            int pDepth = 0;
            while (parent.isDictionary() && pDepth++ < 16) {
                if (parent.isIndirect() && !seenParents.insert(parent.getObjGen()).second) {
                    break;
                }
                if (parent.hasKey("/Resources") && parent.getKey("/Resources").isDictionary()) {
                    QPDFObjectHandle pRes = parent.getKey("/Resources");
                    if (!curRes.isDictionary()) {
                        curRes = pRes.shallowCopy();
                        po.replaceKey("/Resources", curRes);
                    } else {
                        for (const auto& rk : pRes.getKeys()) {
                            if (!curRes.hasKey(rk)) {
                                curRes.replaceKey(rk, pRes.getKey(rk));
                            } else if (curRes.getKey(rk).isDictionary() &&
                                       pRes.getKey(rk).isDictionary()) {
                                QPDFObjectHandle cSub = curRes.getKey(rk);
                                QPDFObjectHandle pSub = pRes.getKey(rk);
                                for (const auto& sk : pSub.getKeys()) {
                                    if (!cSub.hasKey(sk)) cSub.replaceKey(sk, pSub.getKey(sk));
                                }
                            }
                        }
                    }
                }
                parent = parent.getKey("/Parent");
            }
        }
        // Canonicalize annotation subtypes (Table 169) and drop forbidden/unknown subtypes.
        if (po.hasKey("/Annots") && po.getKey("/Annots").isArray()) {
            static const std::map<std::string, std::string> kCanonicalAnnots = {
                {"/text", "/Text"},
                {"/link", "/Link"},
                {"/freetext", "/FreeText"},
                {"/line", "/Line"},
                {"/square", "/Square"},
                {"/circle", "/Circle"},
                {"/polygon", "/Polygon"},
                {"/polyline", "/PolyLine"},
                {"/highlight", "/Highlight"},
                {"/underline", "/Underline"},
                {"/squiggly", "/Squiggly"},
                {"/strikeout", "/StrikeOut"},
                {"/stamp", "/Stamp"},
                {"/caret", "/Caret"},
                {"/ink", "/Ink"},
                {"/popup", "/Popup"},
                {"/fileattachment", "/FileAttachment"},
                {"/widget", "/Widget"},
                {"/printermark", "/PrinterMark"},
                {"/trapnet", "/TrapNet"},
                {"/watermark", "/Watermark"},
                {"/redact", "/Redact"}};
            QPDFObjectHandle annots = po.getKey("/Annots");
            QPDFObjectHandle kept = QPDFObjectHandle::newArray();
            bool dropped = false;
            const int nAnnots = std::min(annots.getArrayNItems(), 4096);
            for (int i = 0; i < nAnnots; ++i) {
                QPDFObjectHandle a = annots.getArrayItem(i);
                std::string sub = (a.isDictionary() && a.getKey("/Subtype").isName())
                                      ? a.getKey("/Subtype").getName()
                                      : "";
                std::string lsub = toLowerAscii(sub);
                auto cit = kCanonicalAnnots.find(lsub);
                if (cit == kCanonicalAnnots.end() ||
                    (pdfaPart != 3 && cit->second == "/FileAttachment") ||
                    (pdfaPart == 1 && (cit->second == "/Polygon" || cit->second == "/PolyLine" ||
                                       cit->second == "/Caret" || cit->second == "/Watermark" ||
                                       cit->second == "/Redact"))) {
                    dropped = true;
                } else {
                    if (sub != cit->second) {
                        a.replaceKey("/Subtype", QPDFObjectHandle::newName(cit->second));
                        annotsFixed = true;
                    }
                    normalizeAnnot(a);
                    kept.appendItem(a);
                }
            }
            if (dropped || nAnnots < annots.getArrayNItems()) {
                po.replaceKey("/Annots", kept);
                annotsFixed = true;
            }
        }
        // Sanitize non-UTF-8 resource keys (6.1.8-1) in ALL /Resources subdictionaries
        std::map<std::string, std::string> renamedResKeys;
        auto sanitizeResDict = [&](const QPDFObjectHandle& r) {
            if (!r.isDictionary()) return;
            for (const auto& rk : r.getKeys()) {
                QPDFObjectHandle subDict = r.getKey(rk);
                if (!subDict.isDictionary()) continue;
                std::vector<std::string> badKeys;
                for (const auto& k : subDict.getKeys()) {
                    if (!isValidUtf8(k)) badKeys.push_back(k);
                }
                for (const auto& bk : badKeys) {
                    std::string nk = "/R_utf8_" + std::to_string(utf8NameIdx++);
                    subDict.replaceKey(nk, subDict.getKey(bk));
                    subDict.removeKey(bk);
                    renamedResKeys[bk] = nk;
                }
            }
        };
        QPDFObjectHandle res = po.getKey("/Resources");
        sanitizeResDict(res);
        auto processPageStream = [&](QPDFObjectHandle st) {
            if (!st.isStream()) return;
            std::string s;
            if (!readStreamBounded(st, s, qpdf_dl_generalized) || s.empty()) return;
            bool dirty = false;
            for (const auto& [oldK, newK] : renamedResKeys) {
                size_t pos = 0;
                while ((pos = s.find(oldK, pos)) != std::string::npos) {
                    s.replace(pos, oldK.size(), newK);
                    pos += newK.size();
                    dirty = true;
                }
            }
            if (disableInlineImageInterpolate(s)) {
                dirty = true;
                interpolateFixed = true;
            }
            if (useCmykOutputIntent) {
                if (convertRgbOpsToIcc(s, "/JPDFRgb")) {
                    dirty = true;
                    cmykOpsFixed = true;
                }
            } else {
                if (convertCmykOpsToRgb(s, "/JPDFCmyk")) {
                    dirty = true;
                    cmykOpsFixed = true;
                }
            }
            if (dirty) {
                st.replaceStreamData(s, QPDFObjectHandle::newNull(), QPDFObjectHandle::newNull());
            }
        };
        QPDFObjectHandle contents = po.getKey("/Contents");
        if (contents.isStream())
            processPageStream(contents);
        else if (contents.isArray()) {
            const int nContents = std::min(contents.getArrayNItems(), 4096);
            for (int i = 0; i < nContents; ++i) processPageStream(contents.getArrayItem(i));
        }
        if (useCmykOutputIntent) {
            QPDFObjectHandle iccRgb = getIccRgb();
            if (!iccRgb.isNull()) {
                if (!res.isDictionary()) {
                    res = QPDFObjectHandle::newDictionary();
                    po.replaceKey("/Resources", res);
                }
                QPDFObjectHandle csDict = res.getKey("/ColorSpace");
                if (!csDict.isDictionary()) {
                    csDict = QPDFObjectHandle::newDictionary();
                    res.replaceKey("/ColorSpace", csDict);
                }
                csDict.replaceKey("/JPDFRgb", iccRgb);
                if (!csDict.hasKey("/DefaultRGB")) csDict.replaceKey("/DefaultRGB", iccRgb);
            }
        } else {
            QPDFObjectHandle iccCmyk = getIccCmyk();
            if (!iccCmyk.isNull()) {
                if (!res.isDictionary()) {
                    res = QPDFObjectHandle::newDictionary();
                    po.replaceKey("/Resources", res);
                }
                QPDFObjectHandle csDict = res.getKey("/ColorSpace");
                if (!csDict.isDictionary()) {
                    csDict = QPDFObjectHandle::newDictionary();
                    res.replaceKey("/ColorSpace", csDict);
                }
                csDict.replaceKey("/JPDFCmyk", iccCmyk);
                if (!csDict.hasKey("/DefaultCMYK")) csDict.replaceKey("/DefaultCMYK", iccCmyk);
            }
        }
    }

    // 6b. Also sanitize non-UTF-8 /Resources keys on all Form XObjects, Tiling Patterns, and
    // /AcroForm /DR (6.1.8-1)
    for (auto obj : allObjects) {
        if (obj.isStream()) {
            QPDFObjectHandle d = obj.getDict();
            QPDFObjectHandle r = d.getKey("/Resources");
            if (!r.isDictionary()) continue;
            std::map<std::string, std::string> localRenames;
            for (const char* rk :
                 {"/Font", "/XObject", "/ColorSpace", "/ExtGState", "/Pattern", "/Shading"}) {
                QPDFObjectHandle subDict = r.getKey(rk);
                if (!subDict.isDictionary()) continue;
                std::vector<std::string> badKeys;
                for (const auto& k : subDict.getKeys()) {
                    if (!isValidUtf8(k)) badKeys.push_back(k);
                }
                for (const auto& bk : badKeys) {
                    std::string nk = "/R_utf8_" + std::to_string(utf8NameIdx++);
                    subDict.replaceKey(nk, subDict.getKey(bk));
                    subDict.removeKey(bk);
                    localRenames[bk] = nk;
                }
            }
            if (!localRenames.empty()) {
                std::string s;
                if (readStreamBounded(obj, s, qpdf_dl_generalized) && !s.empty()) {
                    bool dirty = false;
                    for (const auto& [oldK, newK] : localRenames) {
                        size_t pos = 0;
                        while ((pos = s.find(oldK, pos)) != std::string::npos) {
                            s.replace(pos, oldK.size(), newK);
                            pos += newK.size();
                            dirty = true;
                        }
                    }
                    if (dirty)
                        obj.replaceStreamData(s, QPDFObjectHandle::newNull(),
                                              QPDFObjectHandle::newNull());
                }
            }
        }
    }
    if (root.hasKey("/AcroForm") && root.getKey("/AcroForm").isDictionary()) {
        QPDFObjectHandle dr = root.getKey("/AcroForm").getKey("/DR");
        if (dr.isDictionary()) {
            for (const char* rk : {"/Font", "/XObject", "/ColorSpace", "/ExtGState"}) {
                QPDFObjectHandle subDict = dr.getKey(rk);
                if (!subDict.isDictionary()) continue;
                std::vector<std::string> badKeys;
                for (const auto& k : subDict.getKeys()) {
                    if (!isValidUtf8(k)) badKeys.push_back(k);
                }
                for (const auto& bk : badKeys) {
                    std::string nk = "/R_utf8_" + std::to_string(utf8NameIdx++);
                    subDict.replaceKey(nk, subDict.getKey(bk));
                    subDict.removeKey(bk);
                }
            }
        }
    }

    // 6c. Sanitize non-UTF-8 /BaseFont, /FontName, /Name values across all dictionaries (6.1.8-1)
    for (auto obj : allObjects) {
        if (!obj.isDictionary()) continue;
        for (const char* nk : {"/BaseFont", "/FontName", "/Name"}) {
            if (obj.hasKey(nk) && obj.getKey(nk).isName() &&
                !isValidUtf8(obj.getKey(nk).getName())) {
                obj.replaceKey(
                    nk, QPDFObjectHandle::newName("/Utf8Name_" + std::to_string(utf8NameIdx++)));
            }
        }
    }

    // 7. Remove undefined operators from page content streams (6.2.10-1).
    bool undefinedOpsFixed = false;
    try {
        QPDFPageDocumentHelper pageHelper(qpdf);
        auto fixStream = [&](QPDFObjectHandle stream) {
            if (!stream.isStream()) return;
            std::string data;
            if (!readStreamBounded(stream, data, qpdf_dl_generalized) || data.empty()) return;
            if (stripUndefinedContentOperator(data)) {
                stream.replaceStreamData(data, QPDFObjectHandle::newNull(),
                                         QPDFObjectHandle::newNull());
                undefinedOpsFixed = true;
            }
        };
        for (auto& page : pageHelper.getAllPages()) {
            QPDFObjectHandle contents = page.getObjectHandle().getKey("/Contents");
            if (contents.isArray()) {
                const int nItems = std::min(contents.getArrayNItems(), 4096);
                for (int i = 0; i < nItems; ++i) {
                    fixStream(contents.getArrayItem(i));
                }
            } else {
                fixStream(contents);
            }
        }
    } catch (...) {
        // Best-effort: malformed page trees are handled by the repair pipeline.
        (void)0;
    }
    if (undefinedOpsFixed) transforms.push_back("strip_undefined_operators");

    pctx.cmykOpsFixed = cmykOpsFixed;
    pctx.annotsFixed = annotsFixed;
    pctx.extGStateFixed = extGStateFixed;
    pctx.interpolateFixed = interpolateFixed;
    pctx.groupFixed = groupFixed;
}

}  // namespace jpdfium::rewriter
