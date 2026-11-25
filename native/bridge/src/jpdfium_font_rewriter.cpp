// jpdfium_font_rewriter.cpp - Public C ABI entry points for font conformance
// audit, normalization, and PDF/A-2b structure rewriting.

#include "jpdfium_font_rewriter_internal.h"
#include "jpdfium_qpdf_writer_lock.h"

#if defined(JPDFIUM_HAS_QPDF) && defined(JPDFIUM_HAS_FREETYPE)
#include <qpdf/Pl_Buffer.hh>
#include <qpdf/QPDFExc.hh>
#include <qpdf/QPDFPageDocumentHelper.hh>
#include <qpdf/QPDFWriter.hh>
#endif

#include <cstdlib>
#include <cstring>
#include <sstream>
#include <string>
#include <vector>

#if defined(JPDFIUM_HAS_QPDF) && defined(JPDFIUM_HAS_FREETYPE)
namespace jpdfium::rewriter {
std::vector<QPDFObjectHandle> enumerateAllFonts(QPDF& qpdf);
}
#endif

extern "C" {

int32_t jpdfium_qpdf_audit_fonts(const uint8_t* input, int64_t inputLen,
                                 char** jsonOut) JPDFIUM_NOEXCEPT {
#if !defined(JPDFIUM_HAS_QPDF) || !defined(JPDFIUM_HAS_FREETYPE)
    (void)input; (void)inputLen; (void)jsonOut;
    return JPDFIUM_ERR_NATIVE;
#else
    using namespace jpdfium::rewriter;
    if (!input || inputLen <= 0 || !jsonOut) return JPDFIUM_ERR_INVALID;
    *jsonOut = nullptr;
    try {
        QPDF qpdf;
        qpdf.setSuppressWarnings(true);
        qpdf.setAttemptRecovery(true);
        qpdf.processMemoryFile("audit", reinterpret_cast<const char*>(input),
                               static_cast<size_t>(inputLen), "");
        auto fonts = enumerateAllFonts(qpdf);
        std::ostringstream js;
        js << "[";
        bool first = true;
        for (auto& f : fonts) {
            if (!first) js << ",";
            first = false;
            int objId = f.isIndirect() ? f.getObjectID() : 0;
            std::string sub = f.getKey("/Subtype").isName() ? f.getKey("/Subtype").getName() : "";
            std::string bf = f.getKey("/BaseFont").isName() ? f.getKey("/BaseFont").getName() : "";
            QPDFObjectHandle fd = f.getKey("/FontDescriptor");
            if (sub == "/Type0") {
                QPDFObjectHandle df = f.getKey("/DescendantFonts");
                if (df.isArray() && df.getArrayNItems() >= 1 && df.getArrayItem(0).isDictionary()) {
                    fd = df.getArrayItem(0).getKey("/FontDescriptor");
                }
            }
            bool embedded = fd.isDictionary() &&
                            (fd.hasKey("/FontFile") || fd.hasKey("/FontFile2") || fd.hasKey("/FontFile3"));
            bool hasToUnicode = f.hasKey("/ToUnicode");
            bool hasEncoding = f.hasKey("/Encoding");
            bool hasWidths = f.hasKey("/Widths") || f.hasKey("/W");
            js << "{\"objId\":" << objId
               << ",\"subtype\":\"" << jsonEscape(sub) << "\""
               << ",\"baseFont\":\"" << jsonEscape(bf) << "\""
               << ",\"embedded\":" << (embedded ? "true" : "false")
               << ",\"hasToUnicode\":" << (hasToUnicode ? "true" : "false")
               << ",\"hasEncoding\":" << (hasEncoding ? "true" : "false")
               << ",\"hasWidths\":" << (hasWidths ? "true" : "false") << "}";
        }
        js << "]";
        std::string res = js.str();
        char* out = static_cast<char*>(std::malloc(res.size() + 1));
        if (!out) return JPDFIUM_ERR_ALLOC;
        std::memcpy(out, res.data(), res.size() + 1);
        *jsonOut = out;
        return JPDFIUM_OK;
    } catch (QPDFExc const& e) {
        return (e.getErrorCode() == qpdf_e_password) ? JPDFIUM_ERR_PASSWORD : JPDFIUM_ERR_NATIVE;
    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
#endif
}

int32_t jpdfium_qpdf_rewrite_fonts(const uint8_t* input, int64_t inputLen, uint32_t flags,
                                        const char* donorDirs, const uint8_t* srgbIcc,
                                        int64_t srgbIccLen, const uint8_t* cmykIcc,
                                        int64_t cmykIccLen, uint8_t** output, int64_t* outputLen,
                                        char** reportJsonOut) JPDFIUM_NOEXCEPT {
#if !defined(JPDFIUM_HAS_QPDF) || !defined(JPDFIUM_HAS_FREETYPE)
    (void)input; (void)inputLen; (void)flags; (void)donorDirs;
    (void)srgbIcc; (void)srgbIccLen; (void)cmykIcc; (void)cmykIccLen;
    (void)output; (void)outputLen; (void)reportJsonOut;
    return JPDFIUM_ERR_NATIVE;
#else
    using namespace jpdfium::rewriter;
    if (!input || inputLen <= 0 || !output || !outputLen) return JPDFIUM_ERR_INVALID;
    *output = nullptr;
    *outputLen = 0;
    if (reportJsonOut) *reportJsonOut = nullptr;

    jpdfium::font::FreeTypeSession ftSession;
    if (!ftSession.init()) return JPDFIUM_ERR_NATIVE;

    try {
        QPDF qpdf;
        qpdf.setSuppressWarnings(true);
        qpdf.setAttemptRecovery(true);
        qpdf.processMemoryFile("rewrite", reinterpret_cast<const char*>(input),
                               static_cast<size_t>(inputLen), "");

        // Refuse digitally signed documents unless caller explicitly opted in to strip signatures.
        if (!(flags & kFlagStripSignatures) && jpdfium::qpdf_detail::pdfHasDigitalSignature(qpdf)) {
            return JPDFIUM_ERR_SIGNED;
        }

        FontRewriteStats stats;
        std::vector<std::string> transforms;
        rewriteDocumentFonts(qpdf, ftSession.lib, flags, donorDirs, stats, transforms);

        const int pdfaPart = (flags & kFlagConvertPdfA1b) ? 1
                           : (flags & kFlagConvertPdfA3b) ? 3
                           : (flags & kFlagConvertPdfA2b) ? 2 : 0;
        const char pdfaConf = (flags & kFlagPdfALevelA) ? 'A'
                            : ((flags & kFlagPdfALevelU) && pdfaPart >= 2) ? 'U' : 'B';

        if (pdfaPart > 0) {
            applyPdfAStructure(qpdf, pdfaPart, pdfaConf, srgbIcc, srgbIccLen, cmykIcc, cmykIccLen,
                               stats.unembeddedRemaining, transforms);
        } else if ((flags & kFlagInjectDefaultRgb) && srgbIcc && srgbIccLen > 0) {
            // PDF/X-4 colour-managed DeviceRGB: inject /DefaultRGB -> [/ICCBased sRGB] only on
            // pages whose content streams or resources still reference DeviceRGB.
            std::string srgbStr(reinterpret_cast<const char*>(srgbIcc), static_cast<size_t>(srgbIccLen));
            QPDFObjectHandle iccArr;
            auto getIccArr = [&]() -> QPDFObjectHandle {
                if (!iccArr.isInitialized()) {
                    QPDFObjectHandle srgbStream = QPDFObjectHandle::newStream(&qpdf, srgbStr);
                    srgbStream.getDict().replaceKey("/N", QPDFObjectHandle::newInteger(3));
                    srgbStream.getDict().replaceKey("/Alternate", QPDFObjectHandle::newName("/DeviceRGB"));
                    iccArr = QPDFObjectHandle::newArray();
                    iccArr.appendItem(QPDFObjectHandle::newName("/ICCBased"));
                    iccArr.appendItem(srgbStream);
                    iccArr = qpdf.makeIndirectObject(iccArr);
                }
                return iccArr;
            };
            auto streamHasRgbOps = [](QPDFObjectHandle st) -> bool {
                if (!st.isStream()) return false;
                try {
                    auto buf = st.getStreamData(qpdf_dl_generalized);
                    if (!buf || buf->getSize() == 0) return false;
                    std::string_view sv(reinterpret_cast<const char*>(buf->getBuffer()), buf->getSize());
                    return sv.find(" rg") != std::string_view::npos ||
                           sv.find(" RG") != std::string_view::npos ||
                           sv.find("\nrg") != std::string_view::npos ||
                           sv.find("\nRG") != std::string_view::npos ||
                           sv.find("/DeviceRGB") != std::string_view::npos;
                } catch (...) {
                    return false;
                }
            };
            std::function<bool(QPDFObjectHandle, std::set<QPDFObjGen>&, int)> resUsesRgb =
                [&](QPDFObjectHandle res, std::set<QPDFObjGen>& seen, int depth) -> bool {
                if (!res.isDictionary() || depth > 16) return false;
                if (res.isIndirect() && !seen.insert(res.getObjGen()).second) return false;
                QPDFObjectHandle csDict = res.getKey("/ColorSpace");
                if (csDict.isDictionary()) {
                    for (const auto& k : csDict.getKeys()) {
                        if (k == "/DefaultRGB") continue;
                        QPDFObjectHandle c = csDict.getKey(k);
                        if (c.isNameAndEquals("/DeviceRGB")) return true;
                        if (c.isArray() && c.getArrayNItems() >= 2 &&
                            c.getArrayItem(1).isNameAndEquals("/DeviceRGB")) return true;
                    }
                }
                QPDFObjectHandle shDict = res.getKey("/Shading");
                if (shDict.isDictionary()) {
                    for (const auto& k : shDict.getKeys()) {
                        QPDFObjectHandle sh = shDict.getKey(k);
                        QPDFObjectHandle sd = sh.isStream() ? sh.getDict() : sh;
                        if (sd.isDictionary() && sd.getKey("/ColorSpace").isNameAndEquals("/DeviceRGB")) {
                            return true;
                        }
                    }
                }
                QPDFObjectHandle xobjs = res.getKey("/XObject");
                if (xobjs.isDictionary()) {
                    for (const auto& k : xobjs.getKeys()) {
                        QPDFObjectHandle xo = xobjs.getKey(k);
                        if (!xo.isStream()) continue;
                        if (xo.isIndirect() && !seen.insert(xo.getObjGen()).second) continue;
                        QPDFObjectHandle xd = xo.getDict();
                        if (xd.getKey("/Subtype").isNameAndEquals("/Image")) {
                            QPDFObjectHandle ics = xd.getKey("/ColorSpace");
                            if (ics.isNameAndEquals("/DeviceRGB")) return true;
                            if (ics.isArray() && ics.getArrayNItems() >= 2 &&
                                ics.getArrayItem(1).isNameAndEquals("/DeviceRGB")) return true;
                        } else if (xd.getKey("/Subtype").isNameAndEquals("/Form")) {
                            if (streamHasRgbOps(xo)) return true;
                            if (resUsesRgb(xd.getKey("/Resources"), seen, depth + 1)) return true;
                        }
                    }
                }
                return false;
            };
            auto usesDeviceRgb = [&](QPDFPageObjectHelper& ph) -> bool {
                QPDFObjectHandle po = ph.getObjectHandle();
                std::set<QPDFObjGen> seen;
                if (resUsesRgb(po.getKey("/Resources"), seen, 0)) return true;
                try {
                    for (auto& cs : ph.getPageContents()) {
                        if (streamHasRgbOps(cs)) return true;
                    }
                } catch (...) {
                }
                return false;
            };
            QPDFPageDocumentHelper pages(qpdf);
            bool injected = false;
            for (auto& ph : pages.getAllPages()) {
                if (!usesDeviceRgb(ph)) continue;
                QPDFObjectHandle po = ph.getObjectHandle();
                QPDFObjectHandle res = po.getKey("/Resources");
                if (!res.isDictionary()) { res = QPDFObjectHandle::newDictionary(); po.replaceKey("/Resources", res); }
                QPDFObjectHandle cs = res.getKey("/ColorSpace");
                if (!cs.isDictionary()) { cs = QPDFObjectHandle::newDictionary(); res.replaceKey("/ColorSpace", cs); }
                if (!cs.hasKey("/DefaultRGB")) {
                    cs.replaceKey("/DefaultRGB", getIccArr());
                    injected = true;
                }
            }
            if (injected) transforms.push_back("inject_default_rgb_icc");
        }

        Pl_Buffer outBuf("font_rewrite_out");
        {
            jpdfium_qpdf_internal::WriterLock lock{-1};
            QPDFWriter w(qpdf);
            w.setOutputPipeline(&outBuf);
            w.setPreserveEncryption(false);
            w.setStreamDataMode(qpdf_s_compress);
            w.setNewlineBeforeEndstream(true);
            w.setDeterministicID(true);
            if (pdfaPart == 1) {
                w.forcePDFVersion("1.4");
                w.setObjectStreamMode(qpdf_o_disable);
            } else if (pdfaPart == 2 || pdfaPart == 3) {
                w.forcePDFVersion("1.7");
            }
            w.write();
        }

        // Build report JSON before allocating *output so a throw cannot leak *output.
        std::string reportStr;
        if (reportJsonOut) {
            std::ostringstream rj;
            rj << "{\"transforms\":[";
            for (size_t ti = 0; ti < transforms.size(); ++ti) {
                if (ti > 0) rj << ",";
                rj << "\"" << jsonEscape(transforms[ti]) << "\"";
            }
            rj << "],\"fontsAudited\":" << stats.fontsAudited
               << ",\"embedded\":" << stats.embeddedCount
               << ",\"widthsFixed\":" << stats.widthsFixedCount
               << ",\"cmapsFixed\":" << stats.cmapFixedCount
               << ",\"notdefFixed\":" << stats.notdefFixedCount
               << ",\"metadataFixed\":" << stats.metaFixedCount << "}";
            reportStr = rj.str();
        }

        auto bufPtr = outBuf.getBufferSharedPointer();
        size_t outSize = bufPtr->getSize();
        uint8_t* rawOut = static_cast<uint8_t*>(std::malloc(outSize));
        if (!rawOut) return JPDFIUM_ERR_ALLOC;
        std::memcpy(rawOut, bufPtr->getBuffer(), outSize);

        if (reportJsonOut) {
            char* rOut = static_cast<char*>(std::malloc(reportStr.size() + 1));
            if (rOut) {
                std::memcpy(rOut, reportStr.data(), reportStr.size() + 1);
                *reportJsonOut = rOut;
            }
        }
        *output = rawOut;
        *outputLen = static_cast<int64_t>(outSize);
        return JPDFIUM_OK;
    } catch (QPDFExc const& e) {
        return (e.getErrorCode() == qpdf_e_password) ? JPDFIUM_ERR_PASSWORD : JPDFIUM_ERR_NATIVE;
    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
#endif
}

}  // extern "C"
