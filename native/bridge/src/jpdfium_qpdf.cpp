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
        info.width = static_cast<JDIMENSION>(std::llround(wd));
        info.height = static_cast<JDIMENSION>(std::llround(hd));

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

void applyDocumentOptions(QPDF& pdf, OptimizeOptions const& options) {
    clampPatternSteps(pdf);
    if (options.flags & JPDFIUM_QPDF_QDF) {
        if (options.flags & JPDFIUM_QPDF_LINEARIZE) {
            throw std::runtime_error("QDF mode cannot be combined with linearization");
        }
    }
    if (options.flags & JPDFIUM_QPDF_OPTIMIZE_IMAGES) {
        optimizeImages(pdf, options.jpegQuality);
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
void configureWriter(QPDFWriter& w, OptimizeOptions const& options) {
    if (options.flags & JPDFIUM_QPDF_QDF) {
        w.setQDFMode(true);
        w.setPreserveEncryption(false);
    }
    if (options.flags & JPDFIUM_QPDF_LINEARIZE) w.setLinearization(true);
    if (options.flags & JPDFIUM_QPDF_RECOMPRESS_FLATE) w.setRecompressFlate(true);
    if (options.flags & JPDFIUM_QPDF_COMPRESS_STREAMS) w.setCompressStreams(true);
    if (options.flags & JPDFIUM_QPDF_PRESERVE_UNREFERENCED) w.setPreserveUnreferencedObjects(true);
    if (options.flags & JPDFIUM_QPDF_NORMALIZE_CONTENT) w.setContentNormalization(true);
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
    applyDocumentOptions(*qpdf, options);
    std::unique_ptr<FILE, int (*)(FILE*)> out(createOutputFile(out_path), &std::fclose);
    if (!out) return -1;
    try {
        QPDFWriter w{*qpdf};
        w.setOutputFile("jpdfium-out", out.get(), false);
        configureWriter(w, options);
        WriterLock lock{options.compressionLevel};
        w.write();
        return 0;
    } catch (const std::exception& e) {
        std::fprintf(stderr, "jpdfium qpdf file: %s\n", e.what());
        return -1;
    } catch (...) {
        std::fprintf(stderr, "jpdfium qpdf file: unknown error\n");
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
        applyDocumentOptions(*qpdf, options);

        QPDFWriter w{*qpdf};
        w.setOutputMemory();
        configureWriter(w, options);
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
