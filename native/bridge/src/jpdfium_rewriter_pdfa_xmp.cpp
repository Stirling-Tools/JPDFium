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

void stripNonRootMetadataRecursive(QPDFObjectHandle obj, QPDFObjGen rootGen,
                                   std::set<QPDFObjGen>& visited, int depth) {
    if (depth > 32) return;
    if (obj.isIndirect()) {
        if (!visited.insert(obj.getObjGen()).second) return;
    }
    if (obj.isStream()) {
        QPDFObjectHandle d = obj.getDict();
        if (obj.getObjGen() != rootGen && d.hasKey("/Metadata")) {
            d.removeKey("/Metadata");
        }
        for (const auto& k : d.getKeys()) {
            if (k == "/Parent") continue;
            stripNonRootMetadataRecursive(d.getKey(k), rootGen, visited, depth + 1);
        }
    } else if (obj.isDictionary()) {
        if ((!obj.isIndirect() || obj.getObjGen() != rootGen) && obj.hasKey("/Metadata")) {
            obj.removeKey("/Metadata");
        }
        for (const auto& k : obj.getKeys()) {
            if (k == "/Parent") continue;
            stripNonRootMetadataRecursive(obj.getKey(k), rootGen, visited, depth + 1);
        }
    } else if (obj.isArray()) {
        for (int i = 0; i < obj.getArrayNItems(); ++i) {
            stripNonRootMetadataRecursive(obj.getArrayItem(i), rootGen, visited, depth + 1);
        }
    }
}

// ---- PDF/A-2b XMP & OutputIntent & Structure (Stage 7) ----

// Extract inner text of a simple XML element <tag ...>VALUE</tag> from existing XMP.
static std::string extractXmpTagText(std::string_view xmp, std::string_view tag) {
    std::string open = "<" + std::string(tag);
    std::string close = "</" + std::string(tag) + ">";
    size_t p = xmp.find(open);
    if (p == std::string_view::npos) return "";
    size_t gt = xmp.find('>', p + open.size());
    if (gt == std::string_view::npos || (gt > 0 && xmp[gt - 1] == '/')) return "";
    size_t end = xmp.find(close, gt + 1);
    if (end == std::string_view::npos || end <= gt + 1) return "";
    std::string_view inner = xmp.substr(gt + 1, end - (gt + 1));
    // Unwrap rdf:Alt / rdf:Seq -> rdf:li if present
    size_t li = inner.find("<rdf:li");
    if (li != std::string_view::npos) {
        size_t ligt = inner.find('>', li + 7);
        if (ligt == std::string_view::npos || (ligt > 0 && inner[ligt - 1] == '/')) return "";
        size_t liend = inner.find("</rdf:li>", ligt + 1);
        if (liend == std::string_view::npos || liend <= ligt + 1) return "";
        inner = inner.substr(ligt + 1, liend - (ligt + 1));
    }
    // Reject nested XML markup or unescaped angle brackets so we never emit malformed XMP.
    if (inner.find('<') != std::string_view::npos || inner.find('>') != std::string_view::npos) {
        return "";
    }
    while (!inner.empty() && std::isspace(static_cast<unsigned char>(inner.front()))) inner.remove_prefix(1);
    while (!inner.empty() && std::isspace(static_cast<unsigned char>(inner.back()))) inner.remove_suffix(1);
    return std::string(inner);
}

static bool isValidXmpDate(std::string_view d) {
    // ISO 8601 XMP Date: YYYY or YYYY-MM or YYYY-MM-DD[Thh:mm[:ss[.fff]][Z|+-hh:mm]]
    if (d.size() < 4) return false;
    for (int i = 0; i < 4; ++i) {
        if (!std::isdigit(static_cast<unsigned char>(d[i]))) return false;
    }
    if (d.size() == 4) return true;
    if (d.size() < 7 || d[4] != '-' || !std::isdigit(static_cast<unsigned char>(d[5])) ||
        !std::isdigit(static_cast<unsigned char>(d[6]))) return false;
    if (d.size() == 7) return true;
    if (d.size() < 10 || d[7] != '-' || !std::isdigit(static_cast<unsigned char>(d[8])) ||
        !std::isdigit(static_cast<unsigned char>(d[9]))) return false;
    if (d.size() == 10) return true;
    return d[10] == 'T';
}

std::string buildPdfAXmp(QPDFObjectHandle info, std::string_view existingXmp, int pdfaPart,
                         char pdfaConformance, bool stampPdfA) {
    auto getInfoStr = [&](const char* key) -> std::string {
        if (!info.isDictionary() || !info.hasKey(key)) return "";
        QPDFObjectHandle v = info.getKey(key);
        if (!v.isString()) return "";
        return v.getUTF8Value();
    };
    auto pickField = [&](const char* infoKey, std::string_view xmpTag) -> std::string {
        std::string v = getInfoStr(infoKey);
        if (!v.empty()) return xmlEscape(v);
        return extractXmpTagText(existingXmp, xmpTag);
    };
    std::string title = pickField("/Title", "dc:title");
    std::string author = pickField("/Author", "dc:creator");
    std::string subject = pickField("/Subject", "dc:description");
    std::string keywords = pickField("/Keywords", "pdf:Keywords");
    std::string creator = pickField("/Creator", "xmp:CreatorTool");
    std::string producer = pickField("/Producer", "pdf:Producer");
    std::string createDate = extractXmpTagText(existingXmp, "xmp:CreateDate");
    if (!isValidXmpDate(createDate)) createDate.clear();
    std::string modifyDate = extractXmpTagText(existingXmp, "xmp:ModifyDate");
    if (!isValidXmpDate(modifyDate)) modifyDate.clear();
    // PDF/A-1b (ISO 19005-1) forbids undeclared XMP extension namespaces like pdfuaid.
    std::string pdfuaPart = (pdfaPart == 1) ? "" : extractXmpTagText(existingXmp, "pdfuaid:part");
    if (pdfuaPart != "1" && pdfuaPart != "2") pdfuaPart.clear();
    if (producer.empty()) producer = "JPDFium";

    std::ostringstream x;
    x << "<?xpacket begin=\"\xEF\xBB\xBF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n"
      << "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">\n"
      << "  <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n"
      << "    <rdf:Description rdf:about=\"\"\n";
    if (stampPdfA) {
        x << "        xmlns:pdfaid=\"http://www.aiim.org/pdfa/ns/id/\"\n";
    }
    if (!pdfuaPart.empty()) {
        x << "        xmlns:pdfuaid=\"http://www.aiim.org/pdfua/ns/id/\"\n"
          << "        xmlns:pdfaExtension=\"http://www.aiim.org/pdfa/ns/extension/\"\n"
          << "        xmlns:pdfaSchema=\"http://www.aiim.org/pdfa/ns/schema#\"\n"
          << "        xmlns:pdfaProperty=\"http://www.aiim.org/pdfa/ns/property#\"\n";
    }
    x << "        xmlns:dc=\"http://purl.org/dc/elements/1.1/\"\n"
      << "        xmlns:pdf=\"http://ns.adobe.com/pdf/1.3/\"\n"
      << "        xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\">\n";
    if (stampPdfA) {
        x << "      <pdfaid:part>" << pdfaPart << "</pdfaid:part>\n"
          << "      <pdfaid:conformance>" << pdfaConformance << "</pdfaid:conformance>\n";
    }
    if (!pdfuaPart.empty()) {
        x << "      <pdfuaid:part>" << pdfuaPart << "</pdfuaid:part>\n"
          << "      <pdfaExtension:schemas>\n"
          << "        <rdf:Bag>\n"
          << "          <rdf:li rdf:parseType=\"Resource\">\n"
          << "            <pdfaSchema:schema>PDF/UA Universal Accessibility Schema</pdfaSchema:schema>\n"
          << "            <pdfaSchema:namespaceURI>http://www.aiim.org/pdfua/ns/id/</pdfaSchema:namespaceURI>\n"
          << "            <pdfaSchema:prefix>pdfuaid</pdfaSchema:prefix>\n"
          << "            <pdfaSchema:property>\n"
          << "              <rdf:Seq>\n"
          << "                <rdf:li rdf:parseType=\"Resource\">\n"
          << "                  <pdfaProperty:name>part</pdfaProperty:name>\n"
          << "                  <pdfaProperty:valueType>Integer</pdfaProperty:valueType>\n"
          << "                  <pdfaProperty:category>internal</pdfaProperty:category>\n"
          << "                  <pdfaProperty:description>Part of ISO 14289 standard</pdfaProperty:description>\n"
          << "                </rdf:li>\n"
          << "              </rdf:Seq>\n"
          << "            </pdfaSchema:property>\n"
          << "          </rdf:li>\n"
          << "        </rdf:Bag>\n"
          << "      </pdfaExtension:schemas>\n";
    }
    if (!title.empty()) {
        x << "      <dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">" << title
          << "</rdf:li></rdf:Alt></dc:title>\n";
    }
    if (!author.empty()) {
        x << "      <dc:creator><rdf:Seq><rdf:li>" << author << "</rdf:li></rdf:Seq></dc:creator>\n";
    }
    if (!subject.empty()) {
        x << "      <dc:description><rdf:Alt><rdf:li xml:lang=\"x-default\">" << subject
          << "</rdf:li></rdf:Alt></dc:description>\n";
    }
    if (!keywords.empty()) {
        x << "      <pdf:Keywords>" << keywords << "</pdf:Keywords>\n";
    }
    if (!creator.empty()) {
        x << "      <xmp:CreatorTool>" << creator << "</xmp:CreatorTool>\n";
    }
    if (!createDate.empty()) {
        x << "      <xmp:CreateDate>" << createDate << "</xmp:CreateDate>\n";
    }
    if (!modifyDate.empty()) {
        x << "      <xmp:ModifyDate>" << modifyDate << "</xmp:ModifyDate>\n";
    }
    x << "      <pdf:Producer>" << producer << "</pdf:Producer>\n"
      << "    </rdf:Description>\n"
      << "  </rdf:RDF>\n"
      << "</x:xmpmeta>\n"
      << "<?xpacket end=\"w\"?>\n";
    return x.str();
}

void applyPdfAXmpAndOutputIntent(PdfAStructureContext& pctx,
                                 const uint8_t* srgbIcc, int64_t srgbIccLen,
                                 const uint8_t* cmykIcc, int64_t cmykIccLen,
                                 int unembeddedFontsRemaining) {
    QPDF& qpdf = pctx.qpdf;
    int pdfaPart = pctx.pdfaPart;
    char pdfaConformance = pctx.pdfaConformance;
    auto& transforms = pctx.transforms;
    if (pdfaPart != 1 && pdfaPart != 2 && pdfaPart != 3) pdfaPart = 2;
    if (pdfaConformance != 'B' && pdfaConformance != 'U' && pdfaConformance != 'A') pdfaConformance = 'B';
    if (pdfaPart == 1 && pdfaConformance == 'U') pdfaConformance = 'B';
    QPDFObjectHandle root = qpdf.getRoot();
    // Capture existing root XMP packet before stripping/replacing metadata so we merge dates & fields.
    std::string existingXmp;
    if (root.hasKey("/Metadata") && root.getKey("/Metadata").isStream()) {
        try {
            auto mb = root.getKey("/Metadata").getStreamData(qpdf_dl_generalized);
            if (mb && mb->getSize() > 0 && mb->getSize() <= 2U * 1024U * 1024U) {
                existingXmp.assign(reinterpret_cast<const char*>(mb->getBuffer()), mb->getSize());
            }
        } catch (...) {
        }
    }
    QPDFObjectHandle trailer = qpdf.getTrailer();

    // Detect whether document uses DeviceCMYK. Whenever DeviceCMYK is used, prefer a CMYK
    // OutputIntent so DeviceCMYK stays 100% untouched (0% pixel diff!) and any DeviceRGB is
    // mapped to ICCBased sRGB (which matches PDFium's DeviceRGB with ~0% pixel diff!).
    bool usesDeviceCmyk = false;
    auto checkStreamCmyk = [&](QPDFObjectHandle st) {
        if (!st.isStream() || usesDeviceCmyk) return;
        try {
            auto buf = st.getStreamData(qpdf_dl_generalized);
            if (!buf || buf->getSize() == 0) return;
            const char* p = reinterpret_cast<const char*>(buf->getBuffer());
            size_t n = buf->getSize();
            for (size_t j = 0; j < n; ++j) {
                if ((p[j] == 'k' || p[j] == 'K') &&
                    (j == 0 || std::isspace(static_cast<unsigned char>(p[j - 1]))) &&
                    (j + 1 == n || std::isspace(static_cast<unsigned char>(p[j + 1])))) {
                    usesDeviceCmyk = true;
                    return;
                }
            }
            if (std::string_view(p, n).find("/DeviceCMYK") != std::string_view::npos) {
                usesDeviceCmyk = true;
            }
        } catch (...) {}
    };
    for (auto& obj : qpdf.getAllObjects()) {
        if (usesDeviceCmyk) break;
        if (obj.isArray() && obj.getArrayNItems() >= 2) {
            QPDFObjectHandle t0 = obj.getArrayItem(0);
            if ((t0.isNameAndEquals("/Separation") || t0.isNameAndEquals("/DeviceN")) &&
                obj.getArrayNItems() >= 3 && obj.getArrayItem(2).isNameAndEquals("/DeviceCMYK")) {
                usesDeviceCmyk = true;
                break;
            } else if (t0.isNameAndEquals("/Indexed") && obj.getArrayItem(1).isNameAndEquals("/DeviceCMYK")) {
                usesDeviceCmyk = true;
                break;
            }
        } else if (obj.isStream()) {
            QPDFObjectHandle d = obj.getDict();
            QPDFObjectHandle cs = d.getKey("/ColorSpace");
            if (cs.isNameAndEquals("/DeviceCMYK")) { usesDeviceCmyk = true; break; }
            if (cs.isArray() && cs.getArrayNItems() >= 2 &&
                cs.getArrayItem(0).isNameAndEquals("/Indexed") &&
                cs.getArrayItem(1).isNameAndEquals("/DeviceCMYK")) {
                usesDeviceCmyk = true;
                break;
            }
            if (d.getKey("/Subtype").isNameAndEquals("/Form") ||
                (d.hasKey("/PatternType") && d.getKey("/PatternType").isInteger() &&
                 d.getKey("/PatternType").getIntValue() == 1)) {
                checkStreamCmyk(obj);
            }
        } else if (obj.isDictionary()) {
            QPDFObjectHandle cs = obj.getKey("/ColorSpace");
            if (cs.isNameAndEquals("/DeviceCMYK")) { usesDeviceCmyk = true; break; }
            else if (cs.isDictionary()) {
                for (const auto& ck : cs.getKeys()) {
                    QPDFObjectHandle v = cs.getKey(ck);
                    if (v.isNameAndEquals("/DeviceCMYK")) { usesDeviceCmyk = true; break; }
                    if (v.isArray() && v.getArrayNItems() >= 3 &&
                        (v.getArrayItem(0).isNameAndEquals("/Separation") || v.getArrayItem(0).isNameAndEquals("/DeviceN")) &&
                        v.getArrayItem(2).isNameAndEquals("/DeviceCMYK")) {
                        usesDeviceCmyk = true;
                        break;
                    }
                    if (v.isArray() && v.getArrayNItems() >= 2 &&
                        v.getArrayItem(0).isNameAndEquals("/Indexed") &&
                        v.getArrayItem(1).isNameAndEquals("/DeviceCMYK")) {
                        usesDeviceCmyk = true;
                        break;
                    }
                }
            }
            if (obj.getKey("/Type").isNameAndEquals("/Page")) {
                QPDFObjectHandle contents = obj.getKey("/Contents");
                if (contents.isStream()) checkStreamCmyk(contents);
                else if (contents.isArray()) {
                    for (int i = 0; i < contents.getArrayNItems(); ++i) checkStreamCmyk(contents.getArrayItem(i));
                }
            }
        }
    }

    bool useCmykOutputIntent = usesDeviceCmyk && cmykIcc && cmykIccLen > 0;

    // Build shared 3-channel ICCBased sRGB color space array if using CMYK OutputIntent
    QPDFObjectHandle iccSrgbArr = QPDFObjectHandle::newNull();
    if (useCmykOutputIntent && srgbIcc && srgbIccLen > 0) {
        std::string rgbStr(reinterpret_cast<const char*>(srgbIcc), static_cast<size_t>(srgbIccLen));
        QPDFObjectHandle rgbStream = QPDFObjectHandle::newStream(&qpdf, rgbStr);
        rgbStream.getDict().replaceKey("/N", QPDFObjectHandle::newInteger(3));
        iccSrgbArr = QPDFObjectHandle::newArray();
        iccSrgbArr.appendItem(QPDFObjectHandle::newName("/ICCBased"));
        iccSrgbArr.appendItem(rgbStream);
        iccSrgbArr = qpdf.makeIndirectObject(iccSrgbArr);
    }

    // Build shared 4-channel ICCBased CMYK color space array when sRGB OutputIntent is used
    QPDFObjectHandle iccCmykArr = QPDFObjectHandle::newNull();
    if (!useCmykOutputIntent && cmykIcc && cmykIccLen > 0) {
        std::string cmykStr(reinterpret_cast<const char*>(cmykIcc), static_cast<size_t>(cmykIccLen));
        QPDFObjectHandle cmykStream = QPDFObjectHandle::newStream(&qpdf, cmykStr);
        cmykStream.getDict().replaceKey("/N", QPDFObjectHandle::newInteger(4));
        iccCmykArr = QPDFObjectHandle::newArray();
        iccCmykArr.appendItem(QPDFObjectHandle::newName("/ICCBased"));
        iccCmykArr.appendItem(cmykStream);
        iccCmykArr = qpdf.makeIndirectObject(iccCmykArr);
    }

    // 1. OutputIntent (GTS_PDFA1 with CMYK ICC for CMYK-only docs, else sRGB ICC)
    if (useCmykOutputIntent) {
        std::string iccStr(reinterpret_cast<const char*>(cmykIcc), static_cast<size_t>(cmykIccLen));
        QPDFObjectHandle iccStream = QPDFObjectHandle::newStream(&qpdf, iccStr);
        iccStream.getDict().replaceKey("/N", QPDFObjectHandle::newInteger(4));
        iccStream.getDict().replaceKey("/Alternate", QPDFObjectHandle::newName("/DeviceCMYK"));

        QPDFObjectHandle oi = QPDFObjectHandle::newDictionary();
        oi.replaceKey("/Type", QPDFObjectHandle::newName("/OutputIntent"));
        oi.replaceKey("/S", QPDFObjectHandle::newName("/GTS_PDFA1"));
        oi.replaceKey("/OutputConditionIdentifier", QPDFObjectHandle::newString("CGATS TR 001"));
        oi.replaceKey("/RegistryName", QPDFObjectHandle::newString("http://www.color.org"));
        oi.replaceKey("/Info", QPDFObjectHandle::newString("SWOP (Coated) CMYK"));
        oi.replaceKey("/DestOutputProfile", iccStream);

        QPDFObjectHandle oiArr = QPDFObjectHandle::newArray();
        oiArr.appendItem(qpdf.makeIndirectObject(oi));
        root.replaceKey("/OutputIntents", oiArr);
        transforms.push_back("pdfa2b_output_intent_cmyk");
    } else if (srgbIcc && srgbIccLen > 0) {
        std::string iccStr(reinterpret_cast<const char*>(srgbIcc), static_cast<size_t>(srgbIccLen));
        QPDFObjectHandle iccStream = QPDFObjectHandle::newStream(&qpdf, iccStr);
        iccStream.getDict().replaceKey("/N", QPDFObjectHandle::newInteger(3));
        iccStream.getDict().replaceKey("/Alternate", QPDFObjectHandle::newName("/DeviceRGB"));

        QPDFObjectHandle oi = QPDFObjectHandle::newDictionary();
        oi.replaceKey("/Type", QPDFObjectHandle::newName("/OutputIntent"));
        oi.replaceKey("/S", QPDFObjectHandle::newName("/GTS_PDFA1"));
        oi.replaceKey("/OutputConditionIdentifier", QPDFObjectHandle::newString("sRGB IEC61966-2.1"));
        oi.replaceKey("/RegistryName", QPDFObjectHandle::newString("http://www.color.org"));
        oi.replaceKey("/Info", QPDFObjectHandle::newString("sRGB IEC61966-2.1"));
        oi.replaceKey("/DestOutputProfile", iccStream);

        QPDFObjectHandle oiArr = QPDFObjectHandle::newArray();
        oiArr.appendItem(qpdf.makeIndirectObject(oi));
        root.replaceKey("/OutputIntents", oiArr);
        transforms.push_back("pdfa2b_output_intent_srgb");
    }

    // 2. Build merged XMP from /Info + existing root XMP, then remove /Info from trailer
    //    so there is never an Info-vs-XMP synchronization mismatch (6.6.2.3.1-1/2).
    //    Only stamp pdfaid:part=2 / conformance=B when no unembedded fonts remain.
    QPDFObjectHandle info = trailer.getKey("/Info");
    bool stampPdfA = (unembeddedFontsRemaining == 0);
    std::string xmp = buildPdfAXmp(info, existingXmp, pdfaPart, pdfaConformance, stampPdfA);
    if (trailer.hasKey("/Info")) {
        trailer.removeKey("/Info");
        transforms.push_back("sync_and_drop_info");
    }
    QPDFObjectHandle metaStream = QPDFObjectHandle::newStream(&qpdf, xmp);
    metaStream.getDict().replaceKey("/Type", QPDFObjectHandle::newName("/Metadata"));
    metaStream.getDict().replaceKey("/Subtype", QPDFObjectHandle::newName("/XML"));
    root.replaceKey("/Metadata", metaStream);
    if (stampPdfA) {
        std::string lvl(1, static_cast<char>(std::tolower(static_cast<unsigned char>(pdfaConformance))));
        transforms.push_back("pdfa" + std::to_string(pdfaPart) + lvl + "_xmp_metadata");
    } else {
        transforms.push_back("pdfaid_vetoed:unembedded_fonts:" + std::to_string(unembeddedFontsRemaining));
    }

    // 3. Remove prohibited catalog entries
    for (const char* k : {"/AA", "/OpenAction", "/JavaScript", "/Requirements", "/Collection"}) {
        if (root.hasKey(k)) {
            root.removeKey(k);
            transforms.push_back(std::string("strip_catalog_") + (k + 1));
        }
    }
    if (root.hasKey("/Names") && root.getKey("/Names").isDictionary()) {
        QPDFObjectHandle names = root.getKey("/Names");
        if (names.hasKey("/JavaScript")) {
            names.removeKey("/JavaScript");
            transforms.push_back("strip_names_JavaScript");
        }
        // PDF/A-3b (ISO 19005-3) permits arbitrary embedded file attachments; only strip for 1b/2b.
        if (pdfaPart != 3 && names.hasKey("/EmbeddedFiles")) {
            names.removeKey("/EmbeddedFiles");
            transforms.push_back("strip_names_EmbeddedFiles");
        }
    }
    if (root.hasKey("/AcroForm") && root.getKey("/AcroForm").isDictionary()) {
        // Generate real form field appearance streams before removing /NeedAppearances (matches jpdfium_pdfx.cpp).
        try {
            QPDFAcroFormDocumentHelper forms(qpdf);
            forms.generateAppearancesIfNeeded();
        } catch (...) {
        }
        QPDFObjectHandle acro = root.getKey("/AcroForm");
        if (acro.hasKey("/NeedAppearances")) {
            acro.removeKey("/NeedAppearances");
            transforms.push_back("strip_need_appearances");
        }
        if (acro.hasKey("/XFA")) {
            acro.removeKey("/XFA");
            transforms.push_back("strip_xfa");
        }
        if (acro.hasKey("/SigFlags")) {
            acro.removeKey("/SigFlags");
            transforms.push_back("strip_sig_flags");
        }
    }
    // Strip catalog /Perms (DocMDP/UR3) since rewriting invalidates cryptographic ByteRange signatures (6.4.3-1 / 6.1.12-2)
    if (root.hasKey("/Perms")) {
        root.removeKey("/Perms");
        transforms.push_back("strip_catalog_perms");
    }

    // 4. Fix /OCProperties (/D /Name required by 6.9-1, /Order completeness by 6.9-3)
    //    For PDF/A-1b (ISO 19005-1), optional content is forbidden -> strip /OCProperties entirely.
    if (pdfaPart == 1) {
        if (root.hasKey("/Version")) {
            root.removeKey("/Version");
            transforms.push_back("pdfa1b_strip_catalog_version");
        }
        if (root.hasKey("/OCProperties")) {
            root.removeKey("/OCProperties");
            transforms.push_back("pdfa1b_strip_ocproperties");
        }
    } else if (root.hasKey("/OCProperties") && root.getKey("/OCProperties").isDictionary()) {
        QPDFObjectHandle ocp = root.getKey("/OCProperties");
        std::vector<QPDFObjectHandle> allOcgs;
        if (ocp.hasKey("/OCGs") && ocp.getKey("/OCGs").isArray()) {
            QPDFObjectHandle ocgs = ocp.getKey("/OCGs");
            for (int i = 0; i < ocgs.getArrayNItems(); ++i) {
                allOcgs.push_back(ocgs.getArrayItem(i));
            }
        }
        auto fixOrder = [&](QPDFObjectHandle cfg) {
            if (!cfg.isDictionary() || !cfg.hasKey("/Order") || !cfg.getKey("/Order").isArray() || allOcgs.empty()) return;
            QPDFObjectHandle order = cfg.getKey("/Order");
            std::set<QPDFObjGen> inOrder;
            std::function<void(QPDFObjectHandle, int)> collectOrder = [&](QPDFObjectHandle arr, int depth) {
                if (!arr.isArray() || depth > 32) return;
                for (int i = 0; i < arr.getArrayNItems(); ++i) {
                    QPDFObjectHandle it = arr.getArrayItem(i);
                    if (it.isIndirect()) inOrder.insert(it.getObjGen());
                    else if (it.isArray()) collectOrder(it, depth + 1);
                }
            };
            collectOrder(order, 0);
            for (auto& ocg : allOcgs) {
                if (ocg.isIndirect() && inOrder.find(ocg.getObjGen()) == inOrder.end()) {
                    order.appendItem(ocg);
                    inOrder.insert(ocg.getObjGen());
                }
            }
        };
        QPDFObjectHandle d = ocp.getKey("/D");
        if (d.isDictionary()) {
            if (!d.hasKey("/Name") || !d.getKey("/Name").isString() || d.getKey("/Name").getStringValue().empty()) {
                d.replaceKey("/Name", QPDFObjectHandle::newString("Default"));
                transforms.push_back("fix_ocproperties_name");
            }
            if (d.hasKey("/AS")) d.removeKey("/AS");
            fixOrder(d);
        }
        if (ocp.hasKey("/Configs") && ocp.getKey("/Configs").isArray()) {
            QPDFObjectHandle cfgs = ocp.getKey("/Configs");
            for (int i = 0; i < cfgs.getArrayNItems(); ++i) {
                QPDFObjectHandle c = cfgs.getArrayItem(i);
                if (c.isDictionary()) {
                    if (!c.hasKey("/Name") || !c.getKey("/Name").isString() || c.getKey("/Name").getStringValue().empty()) {
                        c.replaceKey("/Name", QPDFObjectHandle::newString("Config" + std::to_string(i + 1)));
                    }
                    if (c.hasKey("/AS")) c.removeKey("/AS");
                    fixOrder(c);
                }
            }
        }
    }

    pctx.root = root;
    pctx.useCmykIntent = useCmykOutputIntent;
    pctx.iccRgbArr = iccSrgbArr;
    pctx.iccCmykArr = iccCmykArr;
}

}  // namespace jpdfium::rewriter
