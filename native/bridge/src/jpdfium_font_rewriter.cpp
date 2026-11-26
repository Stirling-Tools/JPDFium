// jpdfium_font_rewriter.cpp - Public C ABI entry points for font conformance
// audit, normalization, and PDF/A-2b structure rewriting.

#include "jpdfium_font_rewriter_internal.h"
#include "jpdfium_qpdf_writer_lock.h"

#if defined(JPDFIUM_HAS_QPDF) && defined(JPDFIUM_HAS_FREETYPE)
#include <qpdf/Pipeline.hh>
#include <qpdf/Pl_Buffer.hh>
#include <qpdf/QPDFExc.hh>
#include <qpdf/QPDFPageDocumentHelper.hh>
#include <qpdf/QPDFWriter.hh>
#endif

#include <array>
#include <cctype>
#include <cstdlib>
#include <cstring>
#include <functional>
#include <mutex>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

#if defined(JPDFIUM_HAS_QPDF) && defined(JPDFIUM_HAS_FREETYPE)
namespace jpdfium::rewriter {
std::vector<QPDFObjectHandle> enumerateAllFonts(QPDF& qpdf);

namespace {

class BoundedStringSink final : public Pipeline {
   public:
    BoundedStringSink(const char* identifier, size_t maxBytes)
        : Pipeline(identifier, nullptr), maxBytes_(maxBytes) {}

    void write(unsigned char const* data, size_t len) override {
        if (len > maxBytes_ - out_.size()) {
            throw std::runtime_error("stream exceeds decompressed byte budget");
        }
        if (len > 0) {
            out_.append(reinterpret_cast<const char*>(data), len);
        }
    }

    void finish() override {}

    std::string take() {
        return std::move(out_);
    }

   private:
    size_t maxBytes_;
    std::string out_;
};

class BoundedBytesSink final : public Pipeline {
   public:
    BoundedBytesSink(const char* identifier, size_t maxBytes)
        : Pipeline(identifier, nullptr), maxBytes_(maxBytes) {}

    void write(unsigned char const* data, size_t len) override {
        if (len > maxBytes_ - out_.size()) {
            throw std::runtime_error("stream exceeds decompressed byte budget");
        }
        if (len > 0) {
            out_.insert(out_.end(), data, data + len);
        }
    }

    void finish() override {}

    std::vector<uint8_t> take() {
        return std::move(out_);
    }

   private:
    size_t maxBytes_;
    std::vector<uint8_t> out_;
};

struct FtLibPool {
    std::mutex mu;
    std::array<FT_Library, 16> idle{};
    size_t idleCount = 0;

    ~FtLibPool() {
        for (size_t i = 0; i < idleCount; ++i) {
            if (idle[i]) FT_Done_FreeType(idle[i]);
            idle[i] = nullptr;
        }
        idleCount = 0;
    }
};

FtLibPool& ftLibPool() {
    static FtLibPool pool;
    return pool;
}

}  // namespace

bool readStreamBounded(QPDFObjectHandle stream, std::string& out, qpdf_stream_decode_level_e dl,
                       size_t maxBytes) {
    out.clear();
    if (!stream.isStream()) return false;
    try {
        BoundedStringSink sink("rewriter-bounded-str", maxBytes);
        bool filtered = false;
        if (!stream.pipeStreamData(&sink, &filtered, 0, dl, true)) {
            return false;
        }
        out = sink.take();
        return true;
    } catch (std::exception&) {
        out.clear();
        return false;
    }
}

bool readStreamBytesBounded(QPDFObjectHandle stream, std::vector<uint8_t>& out,
                            qpdf_stream_decode_level_e dl, size_t maxBytes) {
    out.clear();
    if (!stream.isStream()) return false;
    try {
        BoundedBytesSink sink("rewriter-bounded-bytes", maxBytes);
        bool filtered = false;
        if (!stream.pipeStreamData(&sink, &filtered, 0, dl, true)) {
            return false;
        }
        out = sink.take();
        return true;
    } catch (std::exception&) {
        out.clear();
        return false;
    }
}

PooledFreeTypeSession::~PooledFreeTypeSession() {
    if (!lib) return;
    auto& pool = ftLibPool();
    std::lock_guard<std::mutex> lk(pool.mu);
    if (pool.idleCount < pool.idle.size()) {
        pool.idle[pool.idleCount++] = lib;
        lib = nullptr;
        return;
    }
    FT_Done_FreeType(lib);
    lib = nullptr;
}

bool PooledFreeTypeSession::init() {
    if (lib) return true;
    {
        auto& pool = ftLibPool();
        std::lock_guard<std::mutex> lk(pool.mu);
        if (pool.idleCount > 0) {
            lib = pool.idle[--pool.idleCount];
            pool.idle[pool.idleCount] = nullptr;
            return true;
        }
    }
    return FT_Init_FreeType(&lib) == 0 && lib != nullptr;
}

void PdfRewriterDocIndex::build(QPDF& qpdf) {
    if (built) return;
    allObjects = qpdf.getAllObjects();
    hasOversizedArray = false;
    usesDeviceCmyk = false;
    auto checkStreamCmyk = [&](QPDFObjectHandle const& st) {
        if (!st.isStream() || usesDeviceCmyk) return;
        std::string buf;
        if (!readStreamBounded(st, buf, qpdf_dl_generalized, kMaxRewriterStreamBytes) ||
            buf.empty()) {
            return;
        }
        const size_t n = buf.size();
        for (size_t j = 0; j < n; ++j) {
            if ((buf[j] == 'k' || buf[j] == 'K') &&
                (j == 0 || std::isspace(static_cast<unsigned char>(buf[j - 1]))) &&
                (j + 1 == n || std::isspace(static_cast<unsigned char>(buf[j + 1])))) {
                usesDeviceCmyk = true;
                return;
            }
        }
        if (buf.find("/DeviceCMYK") != std::string::npos) {
            usesDeviceCmyk = true;
        }
    };
    for (auto& obj : allObjects) {
        if (obj.isArray()) {
            if (obj.getArrayNItems() > 8191) hasOversizedArray = true;
            if (!usesDeviceCmyk && obj.getArrayNItems() >= 2) {
                QPDFObjectHandle t0 = obj.getArrayItem(0);
                if (((t0.isNameAndEquals("/Separation") || t0.isNameAndEquals("/DeviceN")) &&
                     obj.getArrayNItems() >= 3 &&
                     obj.getArrayItem(2).isNameAndEquals("/DeviceCMYK")) ||
                    (t0.isNameAndEquals("/Indexed") &&
                     obj.getArrayItem(1).isNameAndEquals("/DeviceCMYK"))) {
                    usesDeviceCmyk = true;
                }
            }
        } else if (!usesDeviceCmyk && obj.isStream()) {
            QPDFObjectHandle d = obj.getDict();
            QPDFObjectHandle cs = d.getKey("/ColorSpace");
            if (cs.isNameAndEquals("/DeviceCMYK") ||
                (cs.isArray() && cs.getArrayNItems() >= 2 &&
                 cs.getArrayItem(0).isNameAndEquals("/Indexed") &&
                 cs.getArrayItem(1).isNameAndEquals("/DeviceCMYK"))) {
                usesDeviceCmyk = true;
            } else if (d.getKey("/Subtype").isNameAndEquals("/Form") ||
                       (d.hasKey("/PatternType") && d.getKey("/PatternType").isInteger() &&
                        d.getKey("/PatternType").getIntValue() == 1)) {
                checkStreamCmyk(obj);
            }
        } else if (!usesDeviceCmyk && obj.isDictionary()) {
            QPDFObjectHandle cs = obj.getKey("/ColorSpace");
            if (cs.isNameAndEquals("/DeviceCMYK")) {
                usesDeviceCmyk = true;
            } else if (cs.isDictionary()) {
                for (const auto& ck : cs.getKeys()) {
                    QPDFObjectHandle v = cs.getKey(ck);
                    if (v.isNameAndEquals("/DeviceCMYK")) {
                        usesDeviceCmyk = true;
                        break;
                    }
                    if (v.isArray() && v.getArrayNItems() >= 3 &&
                        (v.getArrayItem(0).isNameAndEquals("/Separation") ||
                         v.getArrayItem(0).isNameAndEquals("/DeviceN")) &&
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
            if (!usesDeviceCmyk && obj.getKey("/Type").isNameAndEquals("/Page")) {
                QPDFObjectHandle contents = obj.getKey("/Contents");
                if (contents.isStream()) {
                    checkStreamCmyk(contents);
                } else if (contents.isArray()) {
                    const int n = std::min(contents.getArrayNItems(), 4096);
                    for (int i = 0; i < n && !usesDeviceCmyk; ++i) {
                        checkStreamCmyk(contents.getArrayItem(i));
                    }
                }
            }
        }
    }
    built = true;
}

QPDFObjectHandle PdfAStructureContext::ensureIccRgbArr() {
    if (!iccRgbArr.isInitialized() || iccRgbArr.isNull()) {
        if (srgbIcc && srgbIccLen > 0) {
            std::string rgbStr(reinterpret_cast<const char*>(srgbIcc),
                               static_cast<size_t>(srgbIccLen));
            QPDFObjectHandle rgbStream = QPDFObjectHandle::newStream(&qpdf, rgbStr);
            rgbStream.getDict().replaceKey("/N", QPDFObjectHandle::newInteger(3));
            QPDFObjectHandle arr = QPDFObjectHandle::newArray();
            arr.appendItem(QPDFObjectHandle::newName("/ICCBased"));
            arr.appendItem(rgbStream);
            iccRgbArr = qpdf.makeIndirectObject(arr);
        } else {
            iccRgbArr = QPDFObjectHandle::newNull();
        }
    }
    return iccRgbArr;
}

QPDFObjectHandle PdfAStructureContext::ensureIccCmykArr() {
    if (!iccCmykArr.isInitialized() || iccCmykArr.isNull()) {
        if (cmykIcc && cmykIccLen > 0) {
            std::string cmykStr(reinterpret_cast<const char*>(cmykIcc),
                                static_cast<size_t>(cmykIccLen));
            QPDFObjectHandle cmykStream = QPDFObjectHandle::newStream(&qpdf, cmykStr);
            cmykStream.getDict().replaceKey("/N", QPDFObjectHandle::newInteger(4));
            QPDFObjectHandle arr = QPDFObjectHandle::newArray();
            arr.appendItem(QPDFObjectHandle::newName("/ICCBased"));
            arr.appendItem(cmykStream);
            iccCmykArr = qpdf.makeIndirectObject(arr);
        } else {
            iccCmykArr = QPDFObjectHandle::newNull();
        }
    }
    return iccCmykArr;
}

}  // namespace jpdfium::rewriter
#endif

extern "C" {

int32_t jpdfium_qpdf_audit_fonts(const uint8_t* input, int64_t inputLen,
                                 char** jsonOut) JPDFIUM_NOEXCEPT {
#if !defined(JPDFIUM_HAS_QPDF) || !defined(JPDFIUM_HAS_FREETYPE)
    (void)input;
    (void)inputLen;
    (void)jsonOut;
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
            bool embedded =
                fd.isDictionary() &&
                (fd.hasKey("/FontFile") || fd.hasKey("/FontFile2") || fd.hasKey("/FontFile3"));
            bool hasToUnicode = f.hasKey("/ToUnicode");
            bool hasEncoding = f.hasKey("/Encoding");
            bool hasWidths = f.hasKey("/Widths") || f.hasKey("/W");
            js << "{\"objId\":" << objId << ",\"subtype\":\"" << jsonEscape(sub) << "\""
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
        return (e.getErrorCode() == qpdf_e_password) ? JPDFIUM_ERR_PASSWORD : JPDFIUM_ERR_INVALID;
    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
#endif
}

int32_t jpdfium_qpdf_rewrite_fonts(const uint8_t* input, int64_t inputLen, uint32_t flags,
                                   const char* donorDirs, const uint8_t* srgbIcc,
                                   int64_t srgbIccLen, const uint8_t* cmykIcc, int64_t cmykIccLen,
                                   uint8_t** output, int64_t* outputLen,
                                   char** reportJsonOut) JPDFIUM_NOEXCEPT {
#if !defined(JPDFIUM_HAS_QPDF) || !defined(JPDFIUM_HAS_FREETYPE)
    (void)input;
    (void)inputLen;
    (void)flags;
    (void)donorDirs;
    (void)srgbIcc;
    (void)srgbIccLen;
    (void)cmykIcc;
    (void)cmykIccLen;
    (void)output;
    (void)outputLen;
    (void)reportJsonOut;
    return JPDFIUM_ERR_NATIVE;
#else
    using namespace jpdfium::rewriter;
    if (!input || inputLen <= 0 || !output || !outputLen) return JPDFIUM_ERR_INVALID;
    *output = nullptr;
    *outputLen = 0;
    if (reportJsonOut) *reportJsonOut = nullptr;

    PooledFreeTypeSession ftSession;
    if (!ftSession.init()) return JPDFIUM_ERR_NATIVE;

    try {
        QPDF qpdf;
        qpdf.setSuppressWarnings(true);
        qpdf.setAttemptRecovery(true);
        try {
            qpdf.processMemoryFile("rewrite", reinterpret_cast<const char*>(input),
                                   static_cast<size_t>(inputLen), "");
        } catch (QPDFExc const& e) {
            return (e.getErrorCode() == qpdf_e_password) ? JPDFIUM_ERR_PASSWORD
                                                         : JPDFIUM_ERR_INVALID;
        }

        // Refuse digitally signed documents unless caller explicitly opted in to strip signatures.
        if (!(flags & kFlagStripSignatures) && jpdfium::qpdf_detail::pdfHasDigitalSignature(qpdf)) {
            return JPDFIUM_ERR_SIGNED;
        }

        FontRewriteStats stats;
        std::vector<std::string> transforms;
        std::map<QPDFObjGen, std::set<uint32_t>> usedFontCodes;
        std::set<QPDFObjGen> donorSubstitutedType0;
        rewriteDocumentFonts(qpdf, ftSession.lib, flags, donorDirs, stats, transforms,
                             &usedFontCodes, &donorSubstitutedType0);

        const int pdfaPart = (flags & kFlagConvertPdfA1b)   ? 1
                             : (flags & kFlagConvertPdfA3b) ? 3
                             : (flags & kFlagConvertPdfA2b) ? 2
                                                            : 0;
        const char pdfaConf = (flags & kFlagPdfALevelA)                      ? 'A'
                              : ((flags & kFlagPdfALevelU) && pdfaPart >= 2) ? 'U'
                                                                             : 'B';

        if (pdfaPart > 0) {
            applyPdfAStructure(qpdf, pdfaPart, pdfaConf, srgbIcc, srgbIccLen, cmykIcc, cmykIccLen,
                               stats.unembeddedRemaining, transforms, &usedFontCodes, ftSession.lib,
                               &donorSubstitutedType0);
        } else if ((flags & kFlagInjectDefaultRgb) && srgbIcc && srgbIccLen > 0) {
            // PDF/X-4 colour-managed DeviceRGB: inject /DefaultRGB -> [/ICCBased sRGB] on pages,
            // Form XObjects, and Patterns whose content streams or resources reference DeviceRGB.
            std::string srgbStr(reinterpret_cast<const char*>(srgbIcc),
                                static_cast<size_t>(srgbIccLen));
            QPDFObjectHandle iccArr;
            auto getIccArr = [&]() -> QPDFObjectHandle {
                if (!iccArr.isInitialized()) {
                    QPDFObjectHandle srgbStream = QPDFObjectHandle::newStream(&qpdf, srgbStr);
                    srgbStream.getDict().replaceKey("/N", QPDFObjectHandle::newInteger(3));
                    srgbStream.getDict().replaceKey("/Alternate",
                                                    QPDFObjectHandle::newName("/DeviceRGB"));
                    iccArr = QPDFObjectHandle::newArray();
                    iccArr.appendItem(QPDFObjectHandle::newName("/ICCBased"));
                    iccArr.appendItem(srgbStream);
                    iccArr = qpdf.makeIndirectObject(iccArr);
                }
                return iccArr;
            };
            auto csUsesDeviceRgb = [](QPDFObjectHandle const& c) -> bool {
                if (c.isNameAndEquals("/DeviceRGB")) return true;
                if (c.isArray() && c.getArrayNItems() >= 2) {
                    QPDFObjectHandle fam = c.getArrayItem(0);
                    if (fam.isNameAndEquals("/Indexed") &&
                        c.getArrayItem(1).isNameAndEquals("/DeviceRGB")) {
                        return true;
                    }
                    if ((fam.isNameAndEquals("/Separation") || fam.isNameAndEquals("/DeviceN")) &&
                        c.getArrayNItems() >= 3 &&
                        c.getArrayItem(2).isNameAndEquals("/DeviceRGB")) {
                        return true;
                    }
                }
                return false;
            };
            std::map<QPDFObjGen, bool> streamRgbCache;
            auto streamHasRgbOps = [&](QPDFObjectHandle const& st) -> bool {
                if (!st.isStream()) return false;
                if (st.isIndirect()) {
                    auto cit = streamRgbCache.find(st.getObjGen());
                    if (cit != streamRgbCache.end()) return cit->second;
                }
                bool found = false;
                std::string buf;
                if (readStreamBounded(st, buf, qpdf_dl_generalized, kMaxRewriterStreamBytes) &&
                    !buf.empty()) {
                    if (buf.find("/DeviceRGB") != std::string::npos) {
                        found = true;
                    } else {
                        auto isBoundary = [](char ch) {
                            return std::isspace(static_cast<unsigned char>(ch)) != 0 || ch == '[' ||
                                   ch == ']' || ch == '<' || ch == '>' || ch == '(' || ch == ')' ||
                                   ch == '/';
                        };
                        const size_t bn = buf.size();
                        for (size_t p = 0; p + 1 < bn; ++p) {
                            if (buf[p] == 'r' && buf[p + 1] == 'g' &&
                                (p == 0 || isBoundary(buf[p - 1])) &&
                                (p + 2 == bn || isBoundary(buf[p + 2]))) {
                                found = true;
                                break;
                            }
                            if (buf[p] == 'R' && buf[p + 1] == 'G' &&
                                (p == 0 || isBoundary(buf[p - 1])) &&
                                (p + 2 == bn || isBoundary(buf[p + 2]))) {
                                found = true;
                                break;
                            }
                        }
                    }
                }
                if (st.isIndirect()) streamRgbCache[st.getObjGen()] = found;
                return found;
            };
            bool injected = false;
            auto attachDefaultRgb = [&](QPDFObjectHandle ownerDict) {
                QPDFObjectHandle res = ownerDict.getKey("/Resources");
                if (!res.isDictionary()) {
                    res = QPDFObjectHandle::newDictionary();
                    ownerDict.replaceKey("/Resources", res);
                }
                QPDFObjectHandle cs = res.getKey("/ColorSpace");
                if (!cs.isDictionary()) {
                    cs = QPDFObjectHandle::newDictionary();
                    res.replaceKey("/ColorSpace", cs);
                }
                if (!cs.hasKey("/DefaultRGB")) {
                    cs.replaceKey("/DefaultRGB", getIccArr());
                    injected = true;
                }
            };
            std::map<QPDFObjGen, bool> resRgbCache;
            std::set<QPDFObjGen> processedForms;
            std::function<bool(QPDFObjectHandle const&, std::set<QPDFObjGen>&, size_t&, int)>
                resUsesRgb = [&](QPDFObjectHandle const& res, std::set<QPDFObjGen>& seen,
                                 size_t& nodeCount, int depth) -> bool {
                if (!res.isDictionary() || depth > 16 || nodeCount >= kMaxRewriterGraphNodes) {
                    return false;
                }
                if (res.isIndirect()) {
                    auto rit = resRgbCache.find(res.getObjGen());
                    if (rit != resRgbCache.end()) return rit->second;
                    if (!seen.insert(res.getObjGen()).second) return false;
                }
                ++nodeCount;
                bool uses = false;
                QPDFObjectHandle csDict = res.getKey("/ColorSpace");
                if (csDict.isDictionary()) {
                    for (const auto& k : csDict.getKeys()) {
                        if (k == "/DefaultRGB") continue;
                        if (csUsesDeviceRgb(csDict.getKey(k))) {
                            uses = true;
                            break;
                        }
                    }
                }
                if (!uses) {
                    QPDFObjectHandle shDict = res.getKey("/Shading");
                    if (shDict.isDictionary()) {
                        for (const auto& k : shDict.getKeys()) {
                            QPDFObjectHandle sh = shDict.getKey(k);
                            QPDFObjectHandle sd = sh.isStream() ? sh.getDict() : sh;
                            if (sd.isDictionary() && csUsesDeviceRgb(sd.getKey("/ColorSpace"))) {
                                uses = true;
                                break;
                            }
                        }
                    }
                }
                QPDFObjectHandle pats = res.getKey("/Pattern");
                if (pats.isDictionary()) {
                    for (const auto& k : pats.getKeys()) {
                        if (nodeCount >= kMaxRewriterGraphNodes) break;
                        QPDFObjectHandle pat = pats.getKey(k);
                        if (pat.isStream()) {
                            if (pat.isIndirect() && !seen.insert(pat.getObjGen()).second) continue;
                            QPDFObjectHandle pd = pat.getDict();
                            bool patRgb = streamHasRgbOps(pat);
                            QPDFObjectHandle pRes = pd.getKey("/Resources");
                            if (pRes.isDictionary() &&
                                resUsesRgb(pRes, seen, nodeCount, depth + 1)) {
                                patRgb = true;
                            }
                            if (patRgb) {
                                if (pRes.isDictionary()) {
                                    attachDefaultRgb(pd);
                                } else {
                                    uses = true;
                                }
                            }
                        } else if (pat.isDictionary()) {
                            QPDFObjectHandle sh = pat.getKey("/Shading");
                            QPDFObjectHandle sd = sh.isStream() ? sh.getDict() : sh;
                            if (sd.isDictionary() && csUsesDeviceRgb(sd.getKey("/ColorSpace"))) {
                                uses = true;
                            }
                        }
                    }
                }
                QPDFObjectHandle states = res.getKey("/ExtGState");
                if (states.isDictionary()) {
                    for (const auto& k : states.getKeys()) {
                        QPDFObjectHandle gs = states.getKey(k);
                        if (!gs.isDictionary()) continue;
                        QPDFObjectHandle sm = gs.getKey("/SMask");
                        if (!sm.isDictionary()) continue;
                        QPDFObjectHandle g = sm.getKey("/G");
                        if (g.isStream() &&
                            (!g.isIndirect() || processedForms.insert(g.getObjGen()).second)) {
                            QPDFObjectHandle gd = g.getDict();
                            bool gRgb = streamHasRgbOps(g);
                            QPDFObjectHandle gRes = gd.getKey("/Resources");
                            if (gRes.isDictionary() &&
                                resUsesRgb(gRes, seen, nodeCount, depth + 1)) {
                                gRgb = true;
                            }
                            if (gRgb) {
                                if (gRes.isDictionary()) {
                                    attachDefaultRgb(gd);
                                } else {
                                    uses = true;
                                }
                            }
                        }
                    }
                }
                QPDFObjectHandle xobjs = res.getKey("/XObject");
                if (xobjs.isDictionary()) {
                    for (const auto& k : xobjs.getKeys()) {
                        if (nodeCount >= kMaxRewriterGraphNodes) break;
                        QPDFObjectHandle xo = xobjs.getKey(k);
                        if (!xo.isStream()) continue;
                        if (xo.isIndirect() && !seen.insert(xo.getObjGen()).second) continue;
                        QPDFObjectHandle xd = xo.getDict();
                        if (xd.getKey("/Subtype").isNameAndEquals("/Image")) {
                            if (csUsesDeviceRgb(xd.getKey("/ColorSpace"))) uses = true;
                        } else if (xd.getKey("/Subtype").isNameAndEquals("/Form")) {
                            if (xo.isIndirect() && !processedForms.insert(xo.getObjGen()).second) {
                                continue;
                            }
                            bool formRgb = streamHasRgbOps(xo);
                            QPDFObjectHandle fRes = xd.getKey("/Resources");
                            if (fRes.isDictionary() &&
                                resUsesRgb(fRes, seen, nodeCount, depth + 1)) {
                                formRgb = true;
                            }
                            if (formRgb) {
                                if (fRes.isDictionary()) {
                                    attachDefaultRgb(xd);
                                } else {
                                    uses = true;
                                }
                            }
                        }
                    }
                }
                if (res.isIndirect()) resRgbCache[res.getObjGen()] = uses;
                return uses;
            };
            auto usesDeviceRgb = [&](QPDFPageObjectHelper& ph) -> bool {
                QPDFObjectHandle po = ph.getObjectHandle();
                std::set<QPDFObjGen> seen;
                size_t nodeCount = 0;
                bool pageUses = resUsesRgb(po.getKey("/Resources"), seen, nodeCount, 0);
                if (!pageUses) {
                    try {
                        for (auto& cs : ph.getPageContents()) {
                            if (streamHasRgbOps(cs)) {
                                pageUses = true;
                                break;
                            }
                        }
                    } catch (std::exception&) {
                        return false;
                    }
                }
                return pageUses;
            };
            QPDFPageDocumentHelper pages(qpdf);
            for (auto& ph : pages.getAllPages()) {
                if (usesDeviceRgb(ph)) {
                    attachDefaultRgb(ph.getObjectHandle());
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
        return (e.getErrorCode() == qpdf_e_password) ? JPDFIUM_ERR_PASSWORD : JPDFIUM_ERR_INVALID;
    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
#endif
}

}  // extern "C"
