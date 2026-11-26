// jpdfium_qpdf.cpp - In-process qpdf structural optimization and shared
// QPDF/output helpers (FFM, no CLI).
//
// Font embedding/subsetting, CMap/CID scanning, resource deduplication, and
// page/document helpers live in dedicated translation units (jpdfium_qpdf_*.cpp).

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <limits>
#include <memory>
#include <set>
#include <span>
#include <sstream>
#include <stdexcept>
#include <string>
#include <string_view>
#include <unordered_map>
#include <utility>
#include <vector>

#include "jpdfium_font_internal.h"
#include "jpdfium_qpdf_internal.h"
#include "jpdfium_qpdf_writer_lock.h"

#ifndef _WIN32
#include <fcntl.h>
#include <unistd.h>
#endif

#ifdef JPDFIUM_HAS_QPDF

#include <qpdf/Constants.h>

#include <qpdf/Buffer.hh>
#include <qpdf/Pl_Buffer.hh>
#include <qpdf/Pl_Count.hh>
#include <qpdf/Pl_DCT.hh>
#include <qpdf/Pl_Discard.hh>
#include <qpdf/QPDF.hh>
#include <qpdf/QPDFExc.hh>
#include <qpdf/QPDFObjectHandle.hh>
#include <qpdf/QPDFPageDocumentHelper.hh>
#include <qpdf/QPDFPageObjectHelper.hh>
#include <qpdf/QPDFWriter.hh>

namespace jpdfium::qpdf_detail {

// Create the output with owner-only permissions on POSIX (CodeQL
// cpp/world-writable-file-creation); Windows has no mode argument here.
//
// Callers pass Java-created staging files, so O_TRUNC (overwrite) is correct
// - but O_NOFOLLOW still refuses a symlink swapped in for the staging file
// after Java created it.
FILE* createOutputFile(const char* path) {
#ifdef _WIN32
    const auto* utf8 = reinterpret_cast<const char8_t*>(path);
    std::filesystem::path p{std::u8string(utf8)};
    return ::_wfopen(p.c_str(), L"wb");
#else
    int fd = ::open(path, O_WRONLY | O_CREAT | O_TRUNC | O_NOFOLLOW, 0600);
    if (fd < 0) return nullptr;
    FILE* file = ::fdopen(fd, "wb");
    if (!file) ::close(fd);
    return file;
#endif
}

const char* boolJson(bool value) {
    return value ? "true" : "false";
}

std::string warningsJson(std::vector<QPDFExc> const& warnings) {
    std::ostringstream os;
    os << "[";
    bool first = true;
    for (auto const& warning : warnings) {
        if (!first) os << ",";
        first = false;
        os << "{\"message\":\"" << jpdfium::font::jsonEscape(warning.what()) << "\"}";
    }
    os << "]";
    return os.str();
}

int32_t writeBufferToC(std::shared_ptr<Buffer> const& buf, uint8_t** output, int64_t* outputLen) {
    if (!buf || buf->getSize() == 0 || !output || !outputLen) return -1;
    *outputLen = static_cast<int64_t>(buf->getSize());
    *output = static_cast<uint8_t*>(std::malloc(static_cast<size_t>(*outputLen)));
    if (!*output) {
        *outputLen = 0;
        return -1;
    }
    std::memcpy(*output, buf->getBuffer(), static_cast<size_t>(*outputLen));
    return 0;
}

bool pdfHasDigitalSignature(QPDF& pdf) {
    auto hasSignatureValue = [](QPDFObjectHandle const& field) {
        if (!field.isDictionary()) return false;
        auto value = field.getKey("/V");
        return field.hasKey("/ByteRange") || (value.isDictionary() && value.hasKey("/ByteRange"));
    };
    for (auto& object : pdf.getAllObjects()) {
        auto dict = object.isStream() ? object.getDict() : object;
        if (hasSignatureValue(dict)) return true;
    }
    auto acroForm = pdf.getRoot().getKey("/AcroForm");
    std::vector<QPDFObjectHandle> pendingFields{acroForm};
    std::set<QPDFObjGen> visitedFields;
    while (!pendingFields.empty()) {
        QPDFObjectHandle field = pendingFields.back();
        pendingFields.pop_back();
        if (!field.isDictionary()) continue;
        if (field.isIndirect() && !visitedFields.insert(field.getObjGen()).second) continue;
        if (hasSignatureValue(field)) return true;
        for (char const* key : {"/Fields", "/Kids"}) {
            QPDFObjectHandle children = field.getKey(key);
            if (!children.isArray()) continue;
            for (auto& child : children.getArrayAsVector()) pendingFields.push_back(child);
        }
    }
    return false;
}

namespace {

// qpdf's --optimize-images pipeline: converts 8-bit RGB/Gray/CMYK images to
// DCT and recompresses existing DCT data when a quality is supplied.
class ImageOptimizer final : public QPDFObjectHandle::StreamDataProvider {
   public:
    ImageOptimizer(QPDFObjectHandle image, int32_t jpegQuality)
        : image_(std::move(image)),
          decodeLevel_(jpegQuality >= 0 ? qpdf_dl_all : qpdf_dl_specialized) {
        if (jpegQuality >= 0) {
            config_ = Pl_DCT::make_compress_config([jpegQuality](jpeg_compress_struct* cinfo) {
                jpeg_set_quality(cinfo, jpegQuality, FALSE);
            });
        }
    }

    bool evaluate() {
        ImageInfo info;
        if (!getImageInfo(info)) return false;
        if (!image_.pipeStreamData(nullptr, 0, decodeLevel_, true)) return false;
        Pl_Discard discard;
        Pl_Count counter("image-count", &discard);
        auto pipeline = makePipeline(&counter);
        if (!pipeline) return false;
        if (!image_.pipeStreamData(pipeline.get(), 0, decodeLevel_)) return false;
        QPDFObjectHandle lenObj = image_.getDict().getKey("/Length");
        if (!lenObj.isInteger()) return false;
        long long original = lenObj.getIntValue();
        return counter.getCount() < original;
    }

    void provideStreamData(QPDFObjGen const&, Pipeline* pipeline) override {
        auto p = makePipeline(pipeline);
        if (!p) {
            pipeline->finish();
            return;
        }
        image_.pipeStreamData(p.get(), 0, decodeLevel_, false, false);
    }

   private:
    struct ImageInfo {
        JDIMENSION width = 0;
        JDIMENSION height = 0;
        int components = 0;
        J_COLOR_SPACE colorSpace = JCS_UNKNOWN;
    };

    bool getImageInfo(ImageInfo& info) const {
        QPDFObjectHandle dict = image_.getDict();
        QPDFObjectHandle widthObj = dict.getKey("/Width");
        QPDFObjectHandle heightObj = dict.getKey("/Height");
        if (!(widthObj.isNumber() && heightObj.isNumber())) return false;

        QPDFObjectHandle bits = dict.getKey("/BitsPerComponent");
        if (!(bits.isInteger() && bits.getIntValue() == 8)) return false;

        const double wd = widthObj.getNumericValue();
        const double hd = heightObj.getNumericValue();
        if (!std::isfinite(wd) || !std::isfinite(hd) || wd < 1.0 || hd < 1.0 || wd > 65535.0 ||
            hd > 65535.0) {
            return false;
        }
        // The decoded sample count follows the truncated dimensions. Rounding a
        // fractional dimension up would make Pl_DCT reject the buffer.
        info.width = static_cast<JDIMENSION>(wd);
        info.height = static_cast<JDIMENSION>(hd);

        QPDFObjectHandle colorspaceObj = dict.getKey("/ColorSpace");
        std::string colorspace = colorspaceObj.isName() ? colorspaceObj.getName() : std::string();
        if (colorspace == "/DeviceRGB") {
            info.components = 3;
            info.colorSpace = JCS_RGB;
        } else if (colorspace == "/DeviceGray") {
            info.components = 1;
            info.colorSpace = JCS_GRAYSCALE;
        } else if (colorspace == "/DeviceCMYK") {
            info.components = 4;
            info.colorSpace = JCS_CMYK;
        } else {
            return false;
        }
        return true;
    }

    std::unique_ptr<Pipeline> makePipeline(Pipeline* next) {
        ImageInfo info;
        if (!getImageInfo(info)) return {};
        return std::make_unique<Pl_DCT>("jpg", next, info.width, info.height, info.components,
                                        info.colorSpace, config_.get());
    }

    QPDFObjectHandle image_;
    qpdf_stream_decode_level_e decodeLevel_;
    std::unique_ptr<Pl_DCT::CompressConfig> config_;
};

// Hash a stream's dictionary and raw bytes so only bit-identical image
// XObjects are candidates for deduplication. The dictionary check catches
// images with identical pixels but different color spaces or decode arrays.
size_t imageFingerprint(QPDFObjectHandle& image, std::string& dict) {
    dict = image.getDict().unparse();
    size_t hash = std::hash<std::string>{}(dict);
    auto data = image.getRawStreamData();
    if (data && data->getSize() > 0) {
        const auto* bytes = data->getBuffer();
        uint64_t fnv = 1469598103934665603ULL;
        for (size_t i = 0; i < data->getSize(); ++i) {
            fnv ^= bytes[i];
            fnv *= 1099511628211ULL;
        }
        hash ^= std::hash<uint64_t>{}(fnv) + 0x9e3779b97f4a7c15ULL + (hash << 6) + (hash >> 2);
    }
    return hash;
}

bool sameImage(QPDFObjectHandle& left, QPDFObjectHandle& right, std::string const& dict) {
    if (right.getDict().unparse() != dict) return false;
    auto a = left.getRawStreamData();
    auto b = right.getRawStreamData();
    if (!a || !b || a->getSize() != b->getSize()) return false;
    return std::memcmp(a->getBuffer(), b->getBuffer(), a->getSize()) == 0;
}

constexpr int64_t kBilevelMaxPixels = 64LL * 1024 * 1024;
constexpr int64_t kDedupeMaxStreamBytes = 32LL * 1024 * 1024;

bool hasFilter(QPDFObjectHandle& dict, std::string const& filter) {
    if (!dict.hasKey("/Filter")) return false;
    auto value = dict.getKey("/Filter");
    if (value.isName()) return value.getName() == filter;
    if (!value.isArray()) return false;
    for (int i = 0; i < value.getArrayNItems(); ++i) {
        auto item = value.getArrayItem(i);
        if (item.isName() && item.getName() == filter) return true;
    }
    return false;
}

// qpdf recompresses an unreferenced Flate stream on write, so a bilevel
// re-encode must beat the smaller of the current stream and a fresh Flate
// encode of the same decoded pixels.
size_t recompressedFlateSize(Buffer const* decoded) {
    if (decoded == nullptr || decoded->getSize() == 0) return 0;
    Pl_Buffer buffer("bilevel-flate");
    Pl_Flate flate("bilevel-flate", &buffer, Pl_Flate::a_deflate);
    flate.write(decoded->getBuffer(), decoded->getSize());
    flate.finish();
    std::unique_ptr<Buffer> compressed(buffer.getBuffer());
    return compressed ? compressed->getSize() : 0;
}

bool bilevelSmallEnough(int64_t encoded, size_t raw) {
    // Leave a 5% margin: qpdf may recompress an existing Flate stream below
    // the same size, so a codec that barely wins would not justify replacing it.
    return raw > 0 && encoded > 0 && encoded * 100 < static_cast<int64_t>(raw) * 95;
}

// Point every duplicate image resource at the first identical stream. The
// writer then drops the now-unreferenced copies. Duplicate images are common
// in generated documents that repeat a cover, logo, or page background.
void deduplicateImages(QPDF& pdf) {
    QPDFPageDocumentHelper helper{pdf};
    std::unordered_map<size_t, std::vector<QPDFObjectHandle>> seen;
    // A page can reference one indirect image many times. Hashing the same
    // 32 MiB stream for every reference is quadratic work; remember the
    // canonical replacement for each indirect image object instead.
    std::map<QPDFObjGen, QPDFObjectHandle> canonical;
    for (auto& page : helper.getAllPages()) {
        page.forEachImage(true,
                          [&seen, &canonical](QPDFObjectHandle& image, QPDFObjectHandle& xobjDict,
                                              std::string const& key) {
                              QPDFObjGen identity = image.getObjGen();
                              if (identity.isIndirect()) {
                                  auto known = canonical.find(identity);
                                  if (known != canonical.end()) {
                                      if (!image.isSameObjectAs(known->second)) {
                                          xobjDict.replaceKey(key, known->second);
                                      }
                                      return;
                                  }
                              }
                              if (!image.getDict().hasKey("/Length")) return;
                              auto declaredLength = image.getDict().getKey("/Length");
                              if (!declaredLength.isInteger() ||
                                  declaredLength.getIntValue() > kDedupeMaxStreamBytes) {
                                  return;
                              }
                              std::string dict;
                              size_t fingerprint = imageFingerprint(image, dict);
                              auto& bucket = seen[fingerprint];
                              QPDFObjectHandle replacement = image;
                              for (auto& candidate : bucket) {
                                  if (!sameImage(image, candidate, dict)) continue;
                                  replacement = candidate;
                                  break;
                              }
                              if (replacement.isSameObjectAs(image)) {
                                  bucket.push_back(image);
                              } else {
                                  xobjDict.replaceKey(key, replacement);
                              }
                              if (identity.isIndirect()) canonical.emplace(identity, replacement);
                          });
    }
}

extern "C" int32_t jpdfium_rust_encode_ccitt_g4(const uint8_t*, int64_t, int32_t, int32_t, int32_t,
                                                uint8_t**, int64_t*);
extern "C" void jpdfium_rust_free(uint8_t*);

struct RustBuffer {
    uint8_t* ptr = nullptr;
    ~RustBuffer() {
        if (ptr) jpdfium_rust_free(ptr);
    }
    RustBuffer() = default;
    RustBuffer(RustBuffer const&) = delete;
    RustBuffer& operator=(RustBuffer const&) = delete;
};

// Shared eligibility check for the bilevel re-encoders. Both the CCITT and
// JBIG2 passes accept exactly the same images: 1-bit DeviceGray, no mask or
// soft mask, default decode array, no existing DCT/JPX/JBIG2 filter, and a
// pixel count the encoders can handle. Keeping this in one place stops the two
// passes from drifting apart.
struct BilevelImage {
    QPDFObjectHandle dict;
    int width = 0;
    int height = 0;
    std::shared_ptr<Buffer> decoded;
};

bool readBilevelImage(QPDFObjectHandle& image, BilevelImage& out) {
    auto dict = image.getDict();
    if (hasFilter(dict, "/JBIG2Decode") || hasFilter(dict, "/DCTDecode") ||
        hasFilter(dict, "/JPXDecode")) {
        return false;
    }
    if (!dict.hasKey("/Width") || !dict.hasKey("/Height") || !dict.hasKey("/BitsPerComponent")) {
        return false;
    }
    if (dict.getKey("/BitsPerComponent").getIntValue() != 1) return false;
    if (dict.hasKey("/ImageMask") || dict.hasKey("/Mask") || dict.hasKey("/SMask")) {
        return false;
    }
    if (!dict.hasKey("/ColorSpace") || !dict.getKey("/ColorSpace").isName() ||
        dict.getKey("/ColorSpace").getName() != "/DeviceGray") {
        return false;
    }
    if (dict.hasKey("/Decode")) {
        auto decode = dict.getKey("/Decode");
        if (!decode.isArray() || decode.getArrayNItems() != 2 ||
            !decode.getArrayItem(0).isNumber() || !decode.getArrayItem(1).isNumber() ||
            decode.getArrayItem(0).getNumericValue() != 0 ||
            decode.getArrayItem(1).getNumericValue() != 1) {
            return false;
        }
    }
    int64_t widthValue = dict.getKey("/Width").getIntValue();
    int64_t heightValue = dict.getKey("/Height").getIntValue();
    if (widthValue <= 0 || heightValue <= 0 || widthValue > 32768 || heightValue > 32768 ||
        widthValue * heightValue > kBilevelMaxPixels) {
        return false;
    }
    auto decoded = readStreamBufferBounded(image, qpdf_dl_all);
    if (!decoded) return false;
    size_t rowBytes = (static_cast<size_t>(widthValue) + 7) / 8;
    if (decoded->getSize() < rowBytes * static_cast<size_t>(heightValue)) return false;
    out.dict = std::move(dict);
    out.width = static_cast<int>(widthValue);
    out.height = static_cast<int>(heightValue);
    out.decoded = std::move(decoded);
    return true;
}

// Walks every image on every page once, applying the shared eligibility and
// decode logic and asking `encode` for a replacement. `encode` receives the
// decoded samples and the smallest current encoded size it must beat; returning
// a null handle leaves the image untouched. Repeated indirect images are
// encoded once and the cached replacement is reused for every placement.
template <typename Encode>
int32_t forEachBilevelImage(QPDF& pdf, Encode encode) {
    QPDFPageDocumentHelper helper{pdf};
    std::map<std::string, QPDFObjectHandle> replacements;
    std::set<std::string> attempted;
    int32_t replaced = 0;
    for (auto& page : helper.getAllPages()) {
        page.forEachImage(true, [&](QPDFObjectHandle& image, QPDFObjectHandle& resources,
                                    std::string const& key) {
            try {
                BilevelImage candidate;
                if (!readBilevelImage(image, candidate)) return;
                std::string objectKey =
                    image.isIndirect() ? image.getObjGen().unparse() : std::string();
                if (!objectKey.empty()) {
                    auto cached = replacements.find(objectKey);
                    if (cached != replacements.end()) {
                        resources.replaceKey(key, cached->second);
                        return;
                    }
                    if (!attempted.insert(objectKey).second) return;
                }
                auto raw = image.getRawStreamData();
                if (!raw) return;
                size_t baseline = raw->getSize();
                size_t flateSize = recompressedFlateSize(candidate.decoded.get());
                if (flateSize > 0 && flateSize < baseline) baseline = flateSize;
                QPDFObjectHandle replacement = encode(pdf, candidate, baseline);
                if (replacement.isNull()) return;
                if (!objectKey.empty()) replacements[objectKey] = replacement;
                resources.replaceKey(key, replacement);
                ++replaced;
            } catch (...) {
                // A malformed image must not abort the structural pass.
                return;
            }
        });
    }
    return replaced;
}

// Re-encode 1-bit DeviceGray images as JBIG2 when the page and globals streams
// together are smaller. Returns the number of streams actually replaced.
int32_t encodeBilevelJbig2(QPDF& pdf) {
    return forEachBilevelImage(
        pdf, [](QPDF& pdf, BilevelImage const& image, size_t baseline) -> QPDFObjectHandle {
            RustBuffer pageData;
            RustBuffer globalsData;
            int64_t pageLen = 0;
            int64_t globalsLen = 0;
            if (jpdfium_rust_encode_jbig2(image.decoded->getBuffer(),
                                          static_cast<int64_t>(image.decoded->getSize()),
                                          image.width, image.height, 0, &pageData.ptr, &pageLen,
                                          &globalsData.ptr, &globalsLen) != 0 ||
                pageData.ptr == nullptr || pageLen <= 0) {
                return QPDFObjectHandle::newNull();
            }
            if (!bilevelSmallEnough(pageLen + globalsLen, baseline)) {
                return QPDFObjectHandle::newNull();
            }

            auto replacement = pdf.newStream();
            QPDFObjectHandle dictCopy = image.dict;
            auto replacementDict = dictCopy.shallowCopy();
            replacementDict.removeKey("/Filter");
            replacementDict.removeKey("/DecodeParms");
            replacement.replaceDict(replacementDict);
            QPDFObjectHandle parms = QPDFObjectHandle::newNull();
            if (globalsLen > 0) {
                auto globalsStream = pdf.newStream();
                globalsStream.replaceStreamData(
                    std::string(reinterpret_cast<const char*>(globalsData.ptr),
                                static_cast<size_t>(globalsLen)),
                    QPDFObjectHandle::newNull(), QPDFObjectHandle::newNull());
                auto globalsRef = pdf.makeIndirectObject(globalsStream);
                parms = QPDFObjectHandle::newDictionary();
                parms.replaceKey("/JBIG2Globals", globalsRef);
            }
            replacement.replaceStreamData(std::string(reinterpret_cast<const char*>(pageData.ptr),
                                                      static_cast<size_t>(pageLen)),
                                          QPDFObjectHandle::newName("/JBIG2Decode"), parms);
            return replacement;
        });
}

// Re-encode 1-bit DeviceGray images as CCITT G4 when the encoded stream is
// smaller. Returns the number of streams actually replaced.
int32_t encodeBilevelImages(QPDF& pdf) {
    return forEachBilevelImage(
        pdf, [](QPDF& pdf, BilevelImage const& image, size_t baseline) -> QPDFObjectHandle {
            RustBuffer out;
            int64_t outLen = 0;
            if (jpdfium_rust_encode_ccitt_g4(
                    image.decoded->getBuffer(), static_cast<int64_t>(image.decoded->getSize()),
                    image.width, image.height, 0, &out.ptr, &outLen) != 0 ||
                out.ptr == nullptr || outLen <= 0) {
                return QPDFObjectHandle::newNull();
            }
            if (!bilevelSmallEnough(outLen, baseline)) return QPDFObjectHandle::newNull();

            auto replacement = pdf.newStream();
            QPDFObjectHandle dictCopy = image.dict;
            auto replacementDict = dictCopy.shallowCopy();
            replacementDict.removeKey("/Filter");
            replacementDict.removeKey("/DecodeParms");
            auto parms = QPDFObjectHandle::newDictionary();
            parms.replaceKey("/K", QPDFObjectHandle::newInteger(-1));
            parms.replaceKey("/Columns", QPDFObjectHandle::newInteger(image.width));
            parms.replaceKey("/Rows", QPDFObjectHandle::newInteger(image.height));
            parms.replaceKey("/BlackIs1", QPDFObjectHandle::newBool(false));
            replacement.replaceDict(replacementDict);
            replacement.replaceStreamData(
                std::string(reinterpret_cast<const char*>(out.ptr), static_cast<size_t>(outLen)),
                QPDFObjectHandle::newName("/CCITTFaxDecode"), parms);
            return replacement;
        });
}

// Chromium 445740322: a non-finite or unbounded tiling-pattern step overflows
// the renderer's tile raster dimensions. Clamp to the positive range that PDF
// rasterisers accept.
void clampTilingPatternStep(QPDFObjectHandle const& pattern) {
    if (!pattern.isDictionary() && !pattern.isStream()) return;
    QPDFObjectHandle dict = pattern.isStream() ? pattern.getDict() : pattern;
    QPDFObjectHandle type = dict.getKey("/PatternType");
    if (!type.isInteger() || type.getIntValueAsInt() != 1) return;
    for (char const* key : {"/XStep", "/YStep"}) {
        QPDFObjectHandle value = dict.getKey(key);
        bool replace = !value.isNumber();
        double step = value.isNumber() ? value.getNumericValue() : 1.0;
        if (!std::isfinite(step) || std::fabs(step) < std::numeric_limits<double>::min()) {
            step = 1.0;
            replace = true;
        } else if (std::fabs(step) < 1.0) {
            step = std::copysign(1.0, step);
            replace = true;
        } else if (std::fabs(step) > 32767.0) {
            step = std::copysign(32767.0, step);
            replace = true;
        }
        if (replace) dict.replaceKey(key, QPDFObjectHandle::newReal(step, 4));
    }
}

void clampPatternsInResources(QPDFObjectHandle const& resources, std::set<QPDFObjGen>& seen,
                              int depth) {
    if (!resources.isDictionary() || depth > 32) return;
    if (resources.isIndirect() && !seen.insert(resources.getObjGen()).second) return;
    try {
        QPDFObjectHandle patterns = resources.getKey("/Pattern");
        if (patterns.isDictionary()) {
            for (auto const& entry : patterns.getDictAsMap()) {
                QPDFObjectHandle pattern = entry.second;
                clampTilingPatternStep(pattern);
                if (pattern.isStream())
                    clampPatternsInResources(pattern.getDict().getKey("/Resources"), seen,
                                             depth + 1);
            }
        }
        QPDFObjectHandle xobjects = resources.getKey("/XObject");
        if (xobjects.isDictionary()) {
            for (auto const& entry : xobjects.getDictAsMap()) {
                QPDFObjectHandle xobject = entry.second;
                if (!xobject.isStream()) continue;
                if (xobject.isIndirect() && !seen.insert(xobject.getObjGen()).second) continue;
                clampPatternsInResources(xobject.getDict().getKey("/Resources"), seen, depth + 1);
            }
        }
        QPDFObjectHandle fonts = resources.getKey("/Font");
        if (fonts.isDictionary()) {
            for (auto const& entry : fonts.getDictAsMap()) {
                QPDFObjectHandle font = entry.second;
                if (!font.isDictionary() || !font.getKey("/Subtype").isNameAndEquals("/Type3"))
                    continue;
                clampPatternsInResources(font.getKey("/Resources"), seen, depth + 1);
            }
        }
    } catch (...) {
        // A malformed resource tree must not abort optimization.
        return;
    }
}

}  // namespace

void optimizeImages(QPDF& pdf, int32_t jpegQuality) {
    QPDFPageDocumentHelper helper{pdf};
    for (auto& page : helper.getAllPages()) {
        page.externalizeInlineImages(0);
        page.forEachImage(
            true, [&pdf, jpegQuality](QPDFObjectHandle& image, QPDFObjectHandle& resources,
                                      std::string const& key) {
                auto optimizer = std::make_shared<ImageOptimizer>(image, jpegQuality);
                if (!optimizer->evaluate()) return;
                QPDFObjectHandle replacement = pdf.newStream();
                replacement.replaceDict(image.getDict().shallowCopy());
                replacement.replaceStreamData(optimizer, QPDFObjectHandle::newName("/DCTDecode"),
                                              QPDFObjectHandle::newNull());
                resources.replaceKey(key, replacement);
            });
    }
}

void clampPatternSteps(QPDF& pdf) {
    std::set<QPDFObjGen> seen;
    try {
        QPDFPageDocumentHelper helper{pdf};
        for (auto& page : helper.getAllPages()) {
            clampPatternsInResources(page.getAttribute("/Resources", false), seen, 0);
            QPDFObjectHandle annots = page.getObjectHandle().getKey("/Annots");
            if (!annots.isArray()) continue;
            for (auto& annot : annots.getArrayAsVector()) {
                if (!annot.isDictionary()) continue;
                QPDFObjectHandle ap = annot.getKey("/AP");
                if (!ap.isDictionary()) continue;
                for (char const* key : {"/N", "/R", "/D"}) {
                    QPDFObjectHandle appearance = ap.getKey(key);
                    if (appearance.isStream()) {
                        clampPatternsInResources(appearance.getDict().getKey("/Resources"), seen,
                                                 0);
                    } else if (appearance.isDictionary()) {
                        for (auto const& state : appearance.getDictAsMap()) {
                            QPDFObjectHandle sub = state.second;
                            if (sub.isStream())
                                clampPatternsInResources(sub.getDict().getKey("/Resources"), seen,
                                                         0);
                        }
                    }
                }
            }
        }
    } catch (...) {
        // Pattern repair is best-effort; the writer still emits the document.
        return;
    }
}

int32_t applyDocumentOptions(QPDF& pdf, const OptimizeOptions& options) {
    clampPatternSteps(pdf);
    if (options.flags & JPDFIUM_QPDF_QDF) {
        if (options.flags & JPDFIUM_QPDF_LINEARIZE) {
            throw std::runtime_error("QDF mode cannot be combined with linearization");
        }
    }
    if (options.flags & JPDFIUM_QPDF_OPTIMIZE_IMAGES) {
        optimizeImages(pdf, options.jpegQuality);
    }
    // JBIG2 needs PDF 1.4, but only when it actually replaced a stream. Count
    // JBIG2 replacements separately: a CCITT-only replacement must not bump the
    // version just because both passes were requested.
    int32_t jbig2Replaced = 0;
    if (options.flags & JPDFIUM_QPDF_JBIG2) {
        jbig2Replaced = encodeBilevelJbig2(pdf);
    }
    if (options.flags & JPDFIUM_QPDF_CCITT_G4) {
        encodeBilevelImages(pdf);
    }
    if (options.flags & JPDFIUM_QPDF_DEDUPLICATE_IMAGES) {
        deduplicateImages(pdf);
    }
    if (options.flags & JPDFIUM_QPDF_SUBSET_FONTS) {
        subsetEmbeddedFonts(pdf);
    }
    if ((options.flags & JPDFIUM_QPDF_REMOVE_UNREFERENCED_RESOURCES) &&
        !(options.flags & JPDFIUM_QPDF_PRESERVE_UNREFERENCED)) {
        dedupeResources(pdf);
    }
    if (options.flags & JPDFIUM_QPDF_REMOVE_UNREFERENCED_RESOURCES) {
        QPDFPageDocumentHelper{pdf}.removeUnreferencedResources();
    }
    return jbig2Replaced;
}

}  // namespace jpdfium::qpdf_detail

namespace {

using jpdfium::qpdf_detail::applyDocumentOptions;
using jpdfium::qpdf_detail::createOutputFile;
using jpdfium::qpdf_detail::OptimizeOptions;
using jpdfium::qpdf_detail::QpdfResult;
using jpdfium::qpdf_detail::writeBufferToC;
using jpdfium_qpdf_internal::WriterLock;

// Shared writer setup; QDF defaults are applied before explicit flags.
void configureWriter(QPDFWriter& w, const OptimizeOptions& options, int32_t jbig2Replaced = 0) {
    // PDF/A 6.1.7.1-2 requires an EOL before the endstream keyword. qpdf's
    // default omits it on generated streams (recompressed content, object
    // streams), so a conformant input can leave this route nonconformant.
    w.setNewlineBeforeEndstream(true);
    if (options.flags & JPDFIUM_QPDF_QDF) {
        w.setQDFMode(true);
        w.setPreserveEncryption(false);
    }
    if (options.flags & JPDFIUM_QPDF_LINEARIZE) w.setLinearization(true);
    if (options.flags & JPDFIUM_QPDF_RECOMPRESS_FLATE) w.setRecompressFlate(true);
    if (options.flags & JPDFIUM_QPDF_COMPRESS_STREAMS) w.setCompressStreams(true);
    if (options.flags & JPDFIUM_QPDF_PRESERVE_UNREFERENCED) w.setPreserveUnreferencedObjects(true);
    if (options.flags & JPDFIUM_QPDF_NORMALIZE_CONTENT) w.setContentNormalization(true);
    // Only raise the floor when a JBIG2 stream was actually written. qpdf's
    // minimum-version setter never lowers an already higher version, so a 1.7
    // source stays 1.7.
    if ((options.flags & JPDFIUM_QPDF_JBIG2) && jbig2Replaced > 0) {
        w.setMinimumPDFVersion("1.4");
    }
    if (options.objectStreamMode >= 0)
        w.setObjectStreamMode(static_cast<qpdf_object_stream_e>(options.objectStreamMode));
    if (options.streamDataMode >= 0)
        w.setStreamDataMode(static_cast<qpdf_stream_data_e>(options.streamDataMode));
    if (options.decodeLevel >= 0)
        w.setDecodeLevel(static_cast<qpdf_stream_decode_level_e>(options.decodeLevel));
}

bool validCompressionLevel(int32_t level) {
    // -1 is qpdf's default. 0 is accepted for compatibility with callers that
    // passed it while the parameter was ignored.
    return level == -1 || level == 0 || (level >= 1 && level <= 9);
}

int writeToFile(std::shared_ptr<QPDF> const& qpdf, const char* out_path,
                OptimizeOptions const& options) {
    if (!validCompressionLevel(options.compressionLevel)) {
        std::fprintf(stderr, "jpdfium qpdf file: invalid compression level %d\n",
                     options.compressionLevel);
        return -1;
    }
    int32_t jbig2Replaced = applyDocumentOptions(*qpdf, options);
    FILE* out = createOutputFile(out_path);
    if (!out) return -1;
    bool writerOwnsFile = false;
    try {
        QPDFWriter w{*qpdf};
        // Encryption is deliberately preserved here: optimize, merge, and
        // extract are round-trip operations, and silently dropping the
        // source encryption would change the document's protection. QDF mode
        // opts out explicitly because readable output cannot stay encrypted.
        // QPDFWriter::setOutputFile(..., true) takes ownership of `out` and closes it.
        w.setOutputFile("jpdfium-out", out, true);
        writerOwnsFile = true;  // NOLINT(clang-analyzer-unix.Stream) -- owned by QPDFWriter
        configureWriter(w, options, jbig2Replaced);
        WriterLock lock{options.compressionLevel};
        w.write();
        return 0;
    } catch (const std::exception& e) {
        std::fprintf(stderr, "jpdfium qpdf file: %s\n", e.what());
        if (!writerOwnsFile) std::fclose(out);
        return -1;
    } catch (...) {
        std::fprintf(stderr, "jpdfium qpdf file: unknown error\n");
        if (!writerOwnsFile) std::fclose(out);
        return -1;
    }
}

QpdfResult optimize(std::span<const uint8_t> input, OptimizeOptions const& options) {
    if (!validCompressionLevel(options.compressionLevel)) {
        return {nullptr, "invalid compression level"};
    }
    try {
        auto qpdf = QPDF::create();
        qpdf->processMemoryFile("jpdfium-input", reinterpret_cast<const char*>(input.data()),
                                input.size());
        int32_t jbig2Replaced = applyDocumentOptions(*qpdf, options);

        QPDFWriter w{*qpdf};
        w.setOutputMemory();
        configureWriter(w, options, jbig2Replaced);
        WriterLock lock{options.compressionLevel};
        w.write();
        return {w.getBufferSharedPointer(), ""};
    } catch (const std::exception& e) {
        return {nullptr, e.what()};
    }
}

}  // namespace

extern "C" {

JPDFIUM_EXPORT int32_t jpdfium_qpdf_optimize_ex(const uint8_t* input, int64_t inputLen,
                                                uint8_t** output, int64_t* outputLen, int32_t flags,
                                                int32_t compressionLevel, int32_t objectStreamMode,
                                                int32_t streamDataMode, int32_t decodeLevel,
                                                int32_t jpegQuality) {
    try {
        if (!input || inputLen <= 0 || !output || !outputLen) return -1;
        *output = nullptr;
        *outputLen = 0;

        OptimizeOptions options;
        options.flags = flags;
        options.compressionLevel = compressionLevel;
        options.objectStreamMode = objectStreamMode;
        options.streamDataMode = streamDataMode;
        options.decodeLevel = decodeLevel;
        options.jpegQuality = jpegQuality;

        auto result = optimize({input, static_cast<size_t>(inputLen)}, options);
        if (!result.ok()) {
            std::fprintf(stderr, "jpdfium qpdf optimize: %s\n", result.error.c_str());
            return -1;
        }
        return writeBufferToC(result.buffer, output, outputLen);
    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_optimize(const uint8_t* input, int64_t inputLen,
                                             uint8_t** output, int64_t* outputLen, int32_t flags,
                                             int32_t compressionLevel, int32_t objectStreamMode,
                                             int32_t streamDataMode, int32_t decodeLevel) {
    return jpdfium_qpdf_optimize_ex(input, inputLen, output, outputLen, flags, compressionLevel,
                                    objectStreamMode, streamDataMode, decodeLevel, -1);
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_optimize_ex_file(const char* in_path, const char* out_path,
                                                     int32_t flags, int32_t compressionLevel,
                                                     int32_t objectStreamMode,
                                                     int32_t streamDataMode, int32_t decodeLevel,
                                                     int32_t jpegQuality) {
    if (!in_path || !*in_path || !out_path || !*out_path) return -1;
    try {
        OptimizeOptions options;
        options.flags = flags;
        options.compressionLevel = compressionLevel;
        options.objectStreamMode = objectStreamMode;
        options.streamDataMode = streamDataMode;
        options.decodeLevel = decodeLevel;
        options.jpegQuality = jpegQuality;

        auto qpdf = QPDF::create();
        qpdf->processFile(in_path);
        return writeToFile(qpdf, out_path, options);
    } catch (const std::exception& e) {
        std::fprintf(stderr, "jpdfium qpdf optimize file: %s\n", e.what());
        return -1;
    } catch (...) {
        std::fprintf(stderr, "jpdfium qpdf optimize file: unknown error\n");
        return -1;
    }
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_optimize_file(const char* in_path, const char* out_path,
                                                  int32_t flags, int32_t objectStreamMode,
                                                  int32_t streamDataMode, int32_t decodeLevel) {
    return jpdfium_qpdf_optimize_ex_file(in_path, out_path, flags, -1, objectStreamMode,
                                         streamDataMode, decodeLevel, -1);
}

}  // extern "C"

#else  // !JPDFIUM_HAS_QPDF

// Stub when qpdf is not linked: pass the bytes through unchanged or return error.
extern "C" {

JPDFIUM_EXPORT int32_t jpdfium_qpdf_optimize_ex(const uint8_t* input, int64_t inputLen,
                                                uint8_t** output, int64_t* outputLen, int32_t,
                                                int32_t, int32_t, int32_t, int32_t, int32_t) {
    if (!input || inputLen <= 0 || !output || !outputLen) return -1;
    *outputLen = inputLen;
    *output = static_cast<uint8_t*>(malloc(static_cast<size_t>(inputLen)));
    if (!*output) {
        *outputLen = 0;
        return -1;
    }
    memcpy(*output, input, static_cast<size_t>(inputLen));
    return 0;
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_optimize(const uint8_t* input, int64_t inputLen,
                                             uint8_t** output, int64_t* outputLen, int32_t flags,
                                             int32_t compressionLevel, int32_t objectStreamMode,
                                             int32_t streamDataMode, int32_t decodeLevel) {
    return jpdfium_qpdf_optimize_ex(input, inputLen, output, outputLen, flags, compressionLevel,
                                    objectStreamMode, streamDataMode, decodeLevel, -1);
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_optimize_ex_file(const char*, const char*, int32_t, int32_t,
                                                     int32_t, int32_t, int32_t, int32_t) {
    return -1;
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_optimize_file(const char* in_path, const char* out_path,
                                                  int32_t flags, int32_t objectStreamMode,
                                                  int32_t streamDataMode, int32_t decodeLevel) {
    return jpdfium_qpdf_optimize_ex_file(in_path, out_path, flags, -1, objectStreamMode,
                                         streamDataMode, decodeLevel, -1);
}

}  // extern "C"

#endif  // JPDFIUM_HAS_QPDF
