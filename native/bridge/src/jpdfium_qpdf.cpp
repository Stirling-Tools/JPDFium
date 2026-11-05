// jpdfium_qpdf.cpp - In-process qpdf structural optimization (FFM, no CLI).
//
// jpdfium_qpdf_optimize drives the bundled qpdf library entirely in memory via
// its QPDF/QPDFWriter C++ API.

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <memory>
#include <set>
#include <shared_mutex>
#include <span>
#include <sstream>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

#ifndef _WIN32
#include <fcntl.h>
#include <unistd.h>
#endif

#include "jpdfium.h"

#ifdef JPDFIUM_HAS_QPDF

#include <qpdf/Constants.h>
#include <qpdf/DLL.h>

#include <qpdf/Buffer.hh>
#include <qpdf/Pl_Count.hh>
#include <qpdf/Pl_DCT.hh>
#include <qpdf/Pl_Discard.hh>
#include <qpdf/Pl_Flate.hh>
#include <qpdf/QPDF.hh>
#include <qpdf/QPDFAcroFormDocumentHelper.hh>
#include <qpdf/QPDFEmbeddedFileDocumentHelper.hh>
#include <qpdf/QPDFExc.hh>
#include <qpdf/QPDFFormFieldObjectHelper.hh>
#include <qpdf/QPDFLogger.hh>
#include <qpdf/QPDFObjectHandle.hh>
#include <qpdf/QPDFPageDocumentHelper.hh>
#include <qpdf/QPDFPageLabelDocumentHelper.hh>
#include <qpdf/QPDFPageObjectHelper.hh>
#include <qpdf/QPDFWriter.hh>
#include <qpdf/QUtil.hh>

#include "jpdfium_qpdf_writer_lock.h"

namespace {

// Create the output with owner-only permissions on POSIX (CodeQL
// cpp/world-writable-file-creation); Windows has no mode argument here.
//
// Callers pass Java-created staging files, so O_TRUNC (overwrite) is correct
// - but O_NOFOLLOW still refuses a symlink swapped in for the staging file
// after Java created it.
static FILE* createOutputFile(const char* path) {
#ifdef _WIN32
    return std::fopen(path, "wb");
#else
    int fd = ::open(path, O_WRONLY | O_CREAT | O_TRUNC | O_NOFOLLOW, 0600);
    if (fd < 0) return nullptr;
    FILE* file = ::fdopen(fd, "wb");
    if (!file) ::close(fd);
    return file;
#endif
}

struct QpdfResult {
    std::shared_ptr<Buffer> buffer;
    std::string error;
    bool ok() const {
        return buffer != nullptr;
    }
};

struct OptimizeOptions {
    int32_t flags = 0;
    int32_t compressionLevel = -1;
    int32_t objectStreamMode = -1;
    int32_t streamDataMode = -1;
    int32_t decodeLevel = -1;
    int32_t jpegQuality = -1;
};

using jpdfium_qpdf_internal::WriterLock;

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
        // Check dictionary metadata before paying for a decode probe.
        ImageInfo info;
        if (!getImageInfo(info)) return false;
        // Null pipeline: tests decodability without producing output.
        if (!image_.pipeStreamData(nullptr, 0, decodeLevel_, true)) return false;
        Pl_Discard discard;
        Pl_Count counter("image-count", &discard);
        auto pipeline = makePipeline(&counter);
        if (!pipeline) return false;
        if (!image_.pipeStreamData(pipeline.get(), 0, decodeLevel_)) return false;
        long long original = image_.getDict().getKey("/Length").getIntValue();
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

        info.width = widthObj.isInteger() ? widthObj.getUIntValueAsUInt()
                                          : static_cast<JDIMENSION>(widthObj.getNumericValue());
        info.height = heightObj.isInteger() ? heightObj.getUIntValueAsUInt()
                                            : static_cast<JDIMENSION>(heightObj.getNumericValue());

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

void applyDocumentOptions(QPDF& pdf, const OptimizeOptions& options) {
    if (options.flags & JPDFIUM_QPDF_QDF) {
        if (options.flags & JPDFIUM_QPDF_LINEARIZE) {
            throw std::runtime_error("QDF mode cannot be combined with linearization");
        }
    }
    if (options.flags & JPDFIUM_QPDF_OPTIMIZE_IMAGES) {
        optimizeImages(pdf, options.jpegQuality);
    }
    if (options.flags & JPDFIUM_QPDF_REMOVE_UNREFERENCED_RESOURCES) {
        QPDFPageDocumentHelper{pdf}.removeUnreferencedResources();
    }
}

// Shared writer setup; QDF defaults are applied before explicit flags.
void configureWriter(QPDFWriter& w, const OptimizeOptions& options) {
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

// Write an already-parsed document straight to a file. QPDFWriter takes
// ownership of the FILE* once setOutputFile returns, so an exception before
// that point closes it here rather than leaking the descriptor.
int writeToFile(const std::shared_ptr<QPDF>& qpdf, const char* out_path,
                const OptimizeOptions& options) {
    if (!validCompressionLevel(options.compressionLevel)) {
        std::fprintf(stderr, "jpdfium qpdf file: invalid compression level %d\n",
                     options.compressionLevel);
        return -1;
    }
    applyDocumentOptions(*qpdf, options);
    FILE* out = createOutputFile(out_path);
    if (!out) return -1;
    bool writerOwnsFile = false;
    try {
        QPDFWriter w{*qpdf};
        // Encryption is deliberately preserved here: optimize, merge, and
        // extract are round-trip operations, and silently dropping the
        // source encryption would change the document's protection. QDF mode
        // opts out explicitly because readable output cannot stay encrypted.
        w.setOutputFile("jpdfium-out", out, true);
        writerOwnsFile = true;
        configureWriter(w, options);
        WriterLock lock{options.compressionLevel};
        w.write();
        return 0;
    } catch (const std::exception& e) {
        std::fprintf(stderr, "jpdfium qpdf file: %s\n", e.what());
        if (!writerOwnsFile) std::fclose(out);
        return -1;
    } catch (...) {
        // Non-std throws (e.g. QPDF internal) must still close the descriptor
        // and never cross the C ABI (which would terminate the JVM).
        std::fprintf(stderr, "jpdfium qpdf file: unknown error\n");
        if (!writerOwnsFile) std::fclose(out);
        return -1;
    }
}

QpdfResult optimize(std::span<const uint8_t> input, const OptimizeOptions& options) {
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

// Pages taken from one input, in output order. Out-of-range indices are skipped.
struct PageSelection {
    QPDF* source;
    std::vector<int32_t> pages;
};

std::vector<int32_t> allPageIndices(QPDF& qpdf) {
    std::vector<int32_t> indices(qpdf.getAllPages().size());
    for (size_t i = 0; i < indices.size(); ++i) indices[i] = static_cast<int32_t>(i);
    return indices;
}

// A page with no label in its source is labelled with its physical number in the output, which
// is what a viewer shows for a file without labels. qpdf's own fabricated label has no /S, which
// renders as a blank label.
QPDFObjectHandle labelFor(QPDFPageLabelDocumentHelper& labels, long long sourceIndex,
                          long long outputIndex) {
    QPDFObjectHandle label = labels.getLabelForPage(sourceIndex);
    if (!label.isDictionary()) {
        label = QPDFObjectHandle::newDictionary();
        label.replaceKey("/S", QPDFObjectHandle::newName("/D"));
        label.replaceKey("/St", QPDFObjectHandle::newInteger(outputIndex + 1));
    }
    return label;
}

void appendLabel(std::vector<QPDFObjectHandle>& nums, long long outputIndex,
                 QPDFObjectHandle label) {
    if (nums.size() >= 2) {
        QPDFObjectHandle previousIndex = nums[nums.size() - 2];
        QPDFObjectHandle previous = nums.back();
        bool continuesPrevious =
            previous.getKey("/S").unparse() == label.getKey("/S").unparse() &&
            previous.getKey("/P").unparse() == label.getKey("/P").unparse() &&
            previous.getKey("/St").isInteger() && label.getKey("/St").isInteger() &&
            label.getKey("/St").getIntValue() - previous.getKey("/St").getIntValue() ==
                outputIndex - previousIndex.getIntValue();
        if (continuesPrevious) return;
    }
    nums.push_back(QPDFObjectHandle::newInteger(outputIndex));
    nums.push_back(label);
}

void nullPagesOutsideTree(QPDF& qpdf) {
    std::set<QPDFObjGen> placed;
    for (auto const& page : qpdf.getAllPages()) placed.insert(page.getObjGen());
    for (auto& object : qpdf.getAllObjects()) {
        if (object.isDictionary() && object.getKey("/Type").isNameAndEquals("/Page") &&
            !placed.count(object.getObjGen())) {
            qpdf.replaceObject(object.getObjGen(), QPDFObjectHandle::newNull());
        }
    }
}

void keepOnlyFields(QPDF& qpdf, std::set<QPDFObjGen> const& keptFields) {
    QPDFObjectHandle root = qpdf.getRoot();
    QPDFObjectHandle acroForm = root.getKey("/AcroForm");
    QPDFObjectHandle fields =
        acroForm.isDictionary() ? acroForm.getKey("/Fields") : QPDFObjectHandle::newNull();
    if (fields.isArray()) {
        std::vector<QPDFObjectHandle> kept;
        for (auto const& field : fields.getArrayAsVector()) {
            if (keptFields.count(field.getObjGen())) kept.push_back(field);
        }
        if (kept.empty()) {
            root.removeKey("/AcroForm");
        } else {
            QPDFObjectHandle keptArray = QPDFObjectHandle::newArray(kept);
            acroForm.replaceKey(
                "/Fields", fields.isIndirect() ? qpdf.makeIndirectObject(keptArray) : keptArray);
        }
    }
    QPDFObjectHandle remainingForm = root.getKey("/AcroForm");
    if (remainingForm.isDictionary()) remainingForm.removeKey("/XFA");
}

// Lays the selected pages out in `primary` the way `qpdf <primary> --pages ... --` does, so the
// primary keeps its document-level data (named destinations, viewer preferences, XMP, language,
// optional content, document info) instead of ending up with a bare catalog. Form fields from
// every input survive, page labels are rebuilt for the new page order, and every page object
// left outside the output's page tree is nulled, so a destination or annotation still pointing
// at one cannot pull it, and everything it draws, back into the file.
//
// Dropped, unlike qpdf: outlines, which the Java callers rebuild for each output from the source
// bookmarks; the structure tree, which describes the source's page order and would carry every
// removed page's elements; the XFA form, which viewers would show in place of the assembled
// fields; and usage-rights signatures (/Perms), which any change invalidates.
void assemblePages(QPDF& primary, std::vector<PageSelection> const& selections) {
    std::vector<QPDFObjectHandle> primaryPages = primary.getAllPages();
    for (auto const& page : primaryPages) primary.removePage(page);

    bool anyLabels = false;
    for (auto const& selection : selections) {
        if (QPDFPageLabelDocumentHelper{*selection.source}.hasPageLabels()) anyLabels = true;
    }

    QPDFAcroFormDocumentHelper primaryForms{primary};
    std::map<QPDF*, std::vector<bool>> copied;
    copied[&primary].assign(primaryPages.size(), false);
    std::set<QPDFObjGen> keptFields;
    std::vector<QPDFObjectHandle> labelNums;
    long long outputIndex = 0;

    for (auto const& selection : selections) {
        QPDF& source = *selection.source;
        bool fromPrimary = &source == &primary;
        std::vector<QPDFObjectHandle> sourcePages =
            fromPrimary ? primaryPages : source.getAllPages();
        std::vector<bool>& seen =
            copied.try_emplace(&source, sourcePages.size(), false).first->second;
        QPDFPageLabelDocumentHelper sourceLabels{source};
        std::unique_ptr<QPDFAcroFormDocumentHelper> foreignForms;
        if (!fromPrimary) foreignForms = std::make_unique<QPDFAcroFormDocumentHelper>(source);
        QPDFAcroFormDocumentHelper& sourceForms = fromPrimary ? primaryForms : *foreignForms;

        for (int32_t index : selection.pages) {
            if (index < 0 || static_cast<size_t>(index) >= sourcePages.size()) continue;
            QPDFPageObjectHelper page{sourcePages[static_cast<size_t>(index)]};
            bool repeat = seen[static_cast<size_t>(index)];
            if (repeat) {
                page = page.shallowCopyPage();
            } else {
                seen[static_cast<size_t>(index)] = true;
            }
            primary.addPage(page.getObjectHandle(), false);
            QPDFObjectHandle added = primary.getAllPages().back();
            // A repeated page would otherwise share its widgets with the first copy.
            if (repeat || (!fromPrimary && sourceForms.hasAcroForm())) {
                try {
                    primaryForms.fixCopiedAnnotations(added, page.getObjectHandle(), sourceForms,
                                                      &keptFields);
                } catch (const std::exception& e) {
                    std::fprintf(stderr, "jpdfium qpdf: form fields on a copied page lost: %s\n",
                                 e.what());
                }
            }
            if (anyLabels) {
                appendLabel(labelNums, outputIndex, labelFor(sourceLabels, index, outputIndex));
            }
            ++outputIndex;
        }
    }

    QPDFObjectHandle root = primary.getRoot();
    if (anyLabels) {
        root.replaceKey("/PageLabels", QPDFObjectHandle::newDictionary(
                                           {{"/Nums", QPDFObjectHandle::newArray(labelNums)}}));
    }

    std::vector<bool> const& primaryKept = copied[&primary];
    for (size_t i = 0; i < primaryPages.size(); ++i) {
        if (!primaryKept[i]) continue;
        for (auto& field :
             primaryForms.getFormFieldsForPage(QPDFPageObjectHelper{primaryPages[i]})) {
            keptFields.insert(field.getObjectHandle().getObjGen());
        }
    }

    nullPagesOutsideTree(primary);

    keepOnlyFields(primary, keptFields);

    for (char const* key :
         {"/Outlines", "/StructTreeRoot", "/MarkInfo", "/NeedsRendering", "/Perms"}) {
        root.removeKey(key);
    }
}

// Fast structural settings, not an optimization pass: optimize_file owns size tuning. The
// primary's encryption is not carried over, matching the unencrypted output merge and extract
// have always produced.
void configureAssembledWriter(QPDFWriter& w) {
    w.setPreserveEncryption(false);
    w.setObjectStreamMode(qpdf_o_generate);
    w.setCompressStreams(true);
}

QpdfResult mergePdfs(const uint8_t* const* inputs, const int64_t* inputLens, int32_t count) {
    try {
        if (!inputs || !inputLens || count <= 0) {
            return {nullptr, "invalid merge inputs"};
        }
        std::vector<std::shared_ptr<QPDF>> sources;
        sources.reserve(static_cast<size_t>(count));
        std::vector<PageSelection> selections;

        for (int32_t i = 0; i < count; ++i) {
            const uint8_t* in = inputs[i];
            int64_t len = inputLens[i];
            if (!in || len <= 0) continue;

            auto src = QPDF::create();
            src->processMemoryFile("merge-src", reinterpret_cast<const char*>(in),
                                   static_cast<size_t>(len));
            sources.push_back(src);
            selections.push_back({src.get(), allPageIndices(*src)});
        }
        if (sources.empty()) {
            return {nullptr, "no readable merge inputs"};
        }
        assemblePages(*sources.front(), selections);

        QPDFWriter w{*sources.front()};
        w.setOutputMemory();
        configureAssembledWriter(w);
        WriterLock lock{-1};
        w.write();
        return {w.getBufferSharedPointer(), ""};
    } catch (const std::exception& e) {
        return {nullptr, e.what()};
    }
}

QpdfResult extractPages(std::span<const uint8_t> input, const int32_t* pageIndices,
                        int32_t pageCount) {
    try {
        if (!pageIndices || pageCount <= 0) {
            return {nullptr, "invalid page indices"};
        }
        auto src = QPDF::create();
        src->processMemoryFile("extract-src", reinterpret_cast<const char*>(input.data()),
                               input.size());
        assemblePages(*src,
                      {{src.get(), std::vector<int32_t>(pageIndices, pageIndices + pageCount)}});

        QPDFWriter w{*src};
        w.setOutputMemory();
        configureAssembledWriter(w);
        WriterLock lock{-1};
        w.write();
        return {w.getBufferSharedPointer(), ""};
    } catch (const std::exception& e) {
        return {nullptr, e.what()};
    }
}

QpdfResult encryptPdf(std::span<const uint8_t> input, const char* userPassword,
                      const char* ownerPassword, int32_t permissions, int32_t keyLength) {
    try {
        auto qpdf = QPDF::create();
        qpdf->processMemoryFile("encrypt-in", reinterpret_cast<const char*>(input.data()),
                                input.size());

        QPDFWriter w{*qpdf};
        w.setOutputMemory();

        std::string userPass = userPassword ? userPassword : "";
        std::string ownerPass = ownerPassword ? ownerPassword : userPass;

        bool allowPrint =
            (permissions & JPDFIUM_PERM_PRINT_HIGH) || (permissions & JPDFIUM_PERM_PRINT_LOW);
        bool allowExtract = (permissions & JPDFIUM_PERM_EXTRACT) != 0;
        bool allowModify = (permissions & JPDFIUM_PERM_MODIFY) != 0;
        bool allowAccessibility = (permissions & JPDFIUM_PERM_ACCESSIBILITY) != 0;
        bool allowAssemble = (permissions & JPDFIUM_PERM_ASSEMBLE) != 0;
        bool allowAnnotate = (permissions & JPDFIUM_PERM_ANNOTATE) != 0;
        bool allowFillForms = (permissions & JPDFIUM_PERM_FILL_FORMS) != 0;
        qpdf_r3_print_e printMode = allowPrint ? qpdf_r3p_full : qpdf_r3p_none;

        if (keyLength == 256) {
            w.setR6EncryptionParameters(userPass.c_str(), ownerPass.c_str(), allowAccessibility,
                                        allowExtract, allowAssemble, allowAnnotate, allowFillForms,
                                        allowModify, printMode, true);
        } else {
            w.setR5EncryptionParameters(userPass.c_str(), ownerPass.c_str(), allowAccessibility,
                                        allowExtract, allowAssemble, allowAnnotate, allowFillForms,
                                        allowModify, printMode, true);
        }

        WriterLock lock{-1};
        w.write();
        return {w.getBufferSharedPointer(), ""};
    } catch (const std::exception& e) {
        return {nullptr, e.what()};
    }
}

std::string jsonEscape(std::string const& value) {
    std::string escaped;
    escaped.reserve(value.size() + 8);
    for (unsigned char c : value) {
        switch (c) {
            case '"':
                escaped += "\\\"";
                break;
            case '\\':
                escaped += "\\\\";
                break;
            case '\n':
                escaped += "\\n";
                break;
            case '\r':
                escaped += "\\r";
                break;
            case '\t':
                escaped += "\\t";
                break;
            default:
                if (c < 0x20) {
                    char buf[7];
                    std::snprintf(buf, sizeof(buf), "\\u%04x", c);
                    escaped += buf;
                } else {
                    escaped += static_cast<char>(c);
                }
                break;
        }
    }
    return escaped;
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
        os << "{\"message\":\"" << jsonEscape(warning.what()) << "\"}";
    }
    os << "]";
    return os.str();
}

std::string inspectLoadedPdf(QPDF& pdf) {
    std::ostringstream os;

    int pages = -1;
    bool pageTreeValid = true;
    try {
        pages = static_cast<int>(QPDFPageDocumentHelper{pdf}.getAllPages().size());
    } catch (...) {
        pageTreeValid = false;
    }

    int formFields = -1;
    int signatures = -1;
    try {
        auto fields = QPDFAcroFormDocumentHelper{pdf}.getFormFields();
        formFields = static_cast<int>(fields.size());
        signatures = 0;
        for (auto& field : fields) {
            if (field.getFieldType() == "/Sig") ++signatures;
        }
    } catch (...) {
        // A damaged form tree should not hide the rest of the inspection.
    }

    int attachments = -1;
    try {
        attachments =
            static_cast<int>(QPDFEmbeddedFileDocumentHelper{pdf}.getEmbeddedFiles().size());
    } catch (...) {
        // Ignore malformed embedded-file trees.
    }

    bool pageLabels = false;
    try {
        pageLabels = QPDFPageLabelDocumentHelper::get(pdf).hasPageLabels();
    } catch (...) {
        // Ignore malformed PageLabels trees.
    }

    bool linearized = pdf.isLinearized();
    bool linearizationValid = true;
    if (linearized) {
        try {
            auto logger = QPDFLogger::create();
            auto discard = logger->discard();
            logger->setInfo(discard);
            logger->setWarn(discard);
            logger->setError(discard);
            pdf.setLogger(logger);
            linearizationValid = pdf.checkLinearization();
        } catch (...) {
            linearizationValid = false;
        }
    }

    int revision = 0;
    int permissions = 0;
    int encryptionVersion = 0;
    bool encrypted = false;
    bool ownerPasswordMatched = false;
    bool userPasswordMatched = false;
    QPDF::encryption_method_e streamMethod = QPDF::e_none;
    QPDF::encryption_method_e stringMethod = QPDF::e_none;
    QPDF::encryption_method_e fileMethod = QPDF::e_none;
    try {
        encrypted = pdf.isEncrypted(revision, permissions, encryptionVersion, streamMethod,
                                    stringMethod, fileMethod);
        if (encrypted) {
            ownerPasswordMatched = pdf.ownerPasswordMatched();
            userPasswordMatched = pdf.userPasswordMatched();
        }
    } catch (...) {
        // Report the rest of the document even when the encryption dict is damaged.
    }

    auto warnings = pdf.getWarnings();
    const size_t warningCount = warnings.size();

    os << "{";
    os << "\"status\":\"ok\",";
    os << "\"qpdf_version\":\"" << jsonEscape(QPDF_VERSION) << "\",";
    os << "\"pdf_version\":\"" << jsonEscape(pdf.getPDFVersion()) << "\",";
    os << "\"pages\":" << pages << ",";
    os << "\"page_tree_valid\":" << boolJson(pageTreeValid) << ",";
    os << "\"object_count\":" << pdf.getObjectCount() << ",";
    os << "\"linearized\":" << boolJson(linearized) << ",";
    os << "\"linearization_valid\":" << boolJson(linearizationValid) << ",";
    os << "\"encrypted\":" << boolJson(encrypted) << ",";
    os << "\"requires_password\":false,";
    os << "\"encryption_revision\":" << revision << ",";
    os << "\"encryption_version\":" << encryptionVersion << ",";
    os << "\"permissions\":" << permissions << ",";
    os << "\"owner_password_matched\":" << boolJson(ownerPasswordMatched) << ",";
    os << "\"user_password_matched\":" << boolJson(userPasswordMatched) << ",";
    os << "\"page_labels\":" << boolJson(pageLabels) << ",";
    os << "\"attachments\":" << attachments << ",";
    os << "\"form_fields\":" << formFields << ",";
    os << "\"signatures\":" << signatures << ",";
    os << "\"warning_count\":" << warningCount << ",";
    os << "\"warnings\":" << warningsJson(warnings);
    os << "}";
    return os.str();
}

std::string inspectErrorJson(std::exception const& e) {
    std::ostringstream os;
    auto* qpdfError = dynamic_cast<QPDFExc const*>(&e);
    const bool passwordRequired = qpdfError && qpdfError->getErrorCode() == qpdf_e_password;
    os << "{";
    os << "\"status\":\"" << (passwordRequired ? "password_required" : "error") << "\",";
    os << "\"qpdf_version\":\"" << jsonEscape(QPDF_VERSION) << "\",";
    os << "\"encrypted\":" << boolJson(passwordRequired) << ",";
    os << "\"requires_password\":" << boolJson(passwordRequired) << ",";
    if (qpdfError) {
        os << "\"error_code\":" << static_cast<int>(qpdfError->getErrorCode()) << ",";
    }
    os << "\"error\":\"" << jsonEscape(e.what()) << "\",";
    os << "\"warnings\":[]";
    os << "}";
    return os.str();
}

std::string inspectPdf(const uint8_t* input, int64_t inputLen, const char* password) {
    try {
        QPDF pdf;
        pdf.setSuppressWarnings(false);
        pdf.processMemoryFile("inspect", reinterpret_cast<const char*>(input),
                              static_cast<size_t>(inputLen), password ? password : "");
        return inspectLoadedPdf(pdf);
    } catch (std::exception const& e) {
        return inspectErrorJson(e);
    }
}

std::string inspectPdfFile(const char* path, const char* password) {
    try {
        QPDF pdf;
        pdf.setSuppressWarnings(false);
        if (password && *password) {
            pdf.processFile(path, password);
        } else {
            pdf.processFile(path);
        }
        return inspectLoadedPdf(pdf);
    } catch (std::exception const& e) {
        return inspectErrorJson(e);
    }
}

bool applyDocInfo(QPDF& pdf, const char* const* keys, const char* const* values, int32_t count,
                  std::string& error) {
    if (count < 0 || (count > 0 && (!keys || !values))) {
        error = "invalid metadata arguments";
        return false;
    }

    QPDFObjectHandle info = pdf.getTrailer().getKey("/Info");
    if (!info.isDictionary()) info = QPDFObjectHandle::newDictionary();
    if (!info.isIndirect()) info = pdf.makeIndirectObject(info);
    pdf.getTrailer().replaceKey("/Info", info);

    for (int32_t i = 0; i < count; ++i) {
        if (!keys[i] || !*keys[i]) {
            error = "metadata key must not be empty";
            return false;
        }
        std::string key = keys[i];
        if (key.front() != '/') key.insert(key.begin(), '/');
        if (values[i]) {
            info.replaceKey(key, QPDFObjectHandle::newUnicodeString(values[i]));
        } else {
            info.removeKey(key);
        }
    }
    return true;
}

QpdfResult setDocInfo(std::span<const uint8_t> input, const char* const* keys,
                      const char* const* values, int32_t count, const char* password) {
    try {
        auto pdf = QPDF::create();
        pdf->processMemoryFile("jpdfium-docinfo", reinterpret_cast<const char*>(input.data()),
                               input.size(), password ? password : "");
        std::string error;
        if (!applyDocInfo(*pdf, keys, values, count, error)) {
            return {nullptr, error};
        }

        QPDFWriter writer{*pdf};
        writer.setOutputMemory();
        WriterLock lock{-1};
        writer.write();
        return {writer.getBufferSharedPointer(), ""};
    } catch (std::exception const& e) {
        return {nullptr, e.what()};
    }
}

int setDocInfoFile(const char* in_path, const char* out_path, const char* const* keys,
                   const char* const* values, int32_t count, const char* password) {
    if (!in_path || !out_path) return -1;
    try {
        auto pdf = QPDF::create();
        if (password && *password) {
            pdf->processFile(in_path, password);
        } else {
            pdf->processFile(in_path);
        }
        std::string error;
        if (!applyDocInfo(*pdf, keys, values, count, error)) {
            std::fprintf(stderr, "jpdfium qpdf docinfo file: %s\n", error.c_str());
            return -1;
        }

        FILE* out = createOutputFile(out_path);
        if (!out) return -1;
        bool writerOwnsFile = false;
        try {
            QPDFWriter writer{*pdf};
            writer.setOutputFile("jpdfium-out", out, true);
            writerOwnsFile = true;
            WriterLock lock{-1};
            writer.write();
            return 0;
        } catch (std::exception const& e) {
            std::fprintf(stderr, "jpdfium qpdf docinfo file: %s\n", e.what());
            if (!writerOwnsFile) std::fclose(out);
            return -1;
        } catch (...) {
            std::fprintf(stderr, "jpdfium qpdf docinfo file: unknown error\n");
            if (!writerOwnsFile) std::fclose(out);
            return -1;
        }
    } catch (std::exception const& e) {
        std::fprintf(stderr, "jpdfium qpdf docinfo file: %s\n", e.what());
        return -1;
    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

int forceVersionFile(const char* in_path, const char* out_path, const char* version) {
    if (!in_path || !out_path || !version || !*version) return -1;
    try {
        auto pdf = QPDF::create();
        pdf->processFile(in_path);
        pdf->getRoot().removeKey("/Version");

        FILE* out = createOutputFile(out_path);
        if (!out) return -1;
        bool writerOwnsFile = false;
        try {
            QPDFWriter writer{*pdf};
            writer.setOutputFile("jpdfium-out", out, true);
            writerOwnsFile = true;
            writer.forcePDFVersion(version);
            WriterLock lock{-1};
            writer.write();
            return 0;
        } catch (std::exception const& e) {
            std::fprintf(stderr, "jpdfium qpdf force version file: %s\n", e.what());
            if (!writerOwnsFile) std::fclose(out);
            return -1;
        }
    } catch (std::exception const& e) {
        std::fprintf(stderr, "jpdfium qpdf force version file: %s\n", e.what());
        return -1;
    }
}

QpdfResult decryptPdf(std::span<const uint8_t> input, const char* password) {
    try {
        auto qpdf = QPDF::create();
        if (password && *password) {
            qpdf->processMemoryFile("decrypt-in", reinterpret_cast<const char*>(input.data()),
                                    input.size(), password);
        } else {
            qpdf->processMemoryFile("decrypt-in", reinterpret_cast<const char*>(input.data()),
                                    input.size());
        }

        QPDFWriter w{*qpdf};
        // QPDFWriter preserves the source document's encryption by default, so
        // without this the output would still carry /Encrypt and the caller
        // would be told the decryption succeeded.
        w.setPreserveEncryption(false);
        w.setOutputMemory();
        WriterLock lock{-1};
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

        auto& buf = result.buffer;
        *outputLen = static_cast<int64_t>(buf->getSize());
        *output = static_cast<uint8_t*>(malloc(static_cast<size_t>(*outputLen)));
        if (!*output) {
            *outputLen = 0;
            return -1;
        }
        std::memcpy(*output, buf->getBuffer(), static_cast<size_t>(*outputLen));
        return 0;

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

JPDFIUM_EXPORT int32_t jpdfium_qpdf_force_version_file(const char* in_path, const char* out_path,
                                                       const char* version) {
    return forceVersionFile(in_path, out_path, version);
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_inspect(const uint8_t* input, int64_t inputLen,
                                            const char* password, char** json) {
    if (!input || inputLen <= 0 || !json) return -1;
    *json = nullptr;
    try {
        std::string result = inspectPdf(input, inputLen, password);
        *json = strdup(result.c_str());
        return *json ? 0 : -1;
    } catch (...) {
        return -1;
    }
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_inspect_file(const char* in_path, const char* password,
                                                 char** json) {
    if (!in_path || !*in_path || !json) return -1;
    *json = nullptr;
    try {
        std::string result = inspectPdfFile(in_path, password);
        *json = strdup(result.c_str());
        return *json ? 0 : -1;
    } catch (...) {
        return -1;
    }
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_set_docinfo(const uint8_t* input, int64_t inputLen,
                                                const char* const* keys, const char* const* values,
                                                int32_t count, const char* password,
                                                uint8_t** output, int64_t* outputLen) {
    if (!input || inputLen <= 0 || !output || !outputLen) return -1;
    *output = nullptr;
    *outputLen = 0;
    try {
        auto result =
            setDocInfo({input, static_cast<size_t>(inputLen)}, keys, values, count, password);
        if (!result.ok()) {
            std::fprintf(stderr, "jpdfium qpdf docinfo: %s\n", result.error.c_str());
            return -1;
        }
        auto& buffer = result.buffer;
        *outputLen = static_cast<int64_t>(buffer->getSize());
        *output = static_cast<uint8_t*>(malloc(static_cast<size_t>(*outputLen)));
        if (!*output) {
            *outputLen = 0;
            return -1;
        }
        std::memcpy(*output, buffer->getBuffer(), static_cast<size_t>(*outputLen));
        return 0;
    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_set_docinfo_file(const char* in_path, const char* out_path,
                                                     const char* const* keys,
                                                     const char* const* values, int32_t count,
                                                     const char* password) {
    return setDocInfoFile(in_path, out_path, keys, values, count, password);
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_merge(const uint8_t* const* inputs, const int64_t* inputLens,
                                          int32_t count, uint8_t** output, int64_t* outputLen) {
    try {
        if (!inputs || !inputLens || count <= 0 || !output || !outputLen) return -1;
        *output = nullptr;
        *outputLen = 0;

        auto result = mergePdfs(inputs, inputLens, count);
        if (!result.ok()) {
            std::fprintf(stderr, "jpdfium qpdf merge: %s\n", result.error.c_str());
            return -1;
        }

        auto& buf = result.buffer;
        *outputLen = static_cast<int64_t>(buf->getSize());
        *output = static_cast<uint8_t*>(malloc(static_cast<size_t>(*outputLen)));
        if (!*output) {
            *outputLen = 0;
            return -1;
        }
        std::memcpy(*output, buf->getBuffer(), static_cast<size_t>(*outputLen));
        return 0;

    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

// File-backed optimize: parses from disk and writes straight to disk. No
// document-sized buffer exists on either side, so peak Java heap is
// independent of document size - the byte[] variant necessarily holds both the
// input and the output simultaneously.
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

JPDFIUM_EXPORT int32_t jpdfium_qpdf_merge_files(const char* const* paths, int32_t count,
                                                const char* out_path) {
    if (!paths || count <= 0 || !out_path) return -1;
    try {
        std::vector<std::shared_ptr<QPDF>> sources;
        sources.reserve(static_cast<size_t>(count));
        std::vector<PageSelection> selections;

        for (int32_t i = 0; i < count; ++i) {
            const char* path = paths[i];
            if (!path || !*path) continue;

            auto src = QPDF::create();
            src->processFile(path);
            sources.push_back(src);
            selections.push_back({src.get(), allPageIndices(*src)});
        }
        if (sources.empty()) {
            std::fprintf(stderr, "jpdfium qpdf merge files: no readable inputs\n");
            return -1;
        }
        auto& dest = sources.front();
        assemblePages(*dest, selections);

        FILE* out = createOutputFile(out_path);
        if (!out) {
            std::fprintf(stderr, "jpdfium qpdf file: cannot open output %s\n", out_path);
            return -1;
        }
        // QPDFWriter assumes ownership of the FILE* only once setOutputFile
        // returns; if the constructor or setOutputFile throws first, close it
        // here so a failed write does not leak the descriptor.
        bool writerOwnsFile = false;
        try {
            QPDFWriter w{*dest};
            w.setOutputFile("jpdfium-out", out, true);
            writerOwnsFile = true;
            configureAssembledWriter(w);
            WriterLock lock{-1};
            w.write();
            return 0;
        } catch (...) {
            if (!writerOwnsFile) std::fclose(out);
            throw;
        }
    } catch (const std::exception& e) {
        std::fprintf(stderr, "jpdfium qpdf merge files: %s\n", e.what());
        return -1;
    } catch (...) {
        // Never let a non-std exception escape an extern "C" boundary.
        std::fprintf(stderr, "jpdfium qpdf merge files: unknown error\n");
        return -1;
    }
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_extract_pages_file(const char* in_path,
                                                       const int32_t* pageIndices,
                                                       int32_t pageCount, const char* out_path) {
    if (!in_path || !pageIndices || pageCount <= 0 || !out_path) return -1;
    try {
        auto dest = QPDF::create();
        dest->processFile(in_path);
        assemblePages(*dest,
                      {{dest.get(), std::vector<int32_t>(pageIndices, pageIndices + pageCount)}});

        FILE* out = createOutputFile(out_path);
        if (!out) {
            std::fprintf(stderr, "jpdfium qpdf file: cannot open output %s\n", out_path);
            return -1;
        }
        // QPDFWriter assumes ownership of the FILE* only once setOutputFile
        // returns; if the constructor or setOutputFile throws first, close it
        // here so a failed write does not leak the descriptor.
        bool writerOwnsFile = false;
        try {
            QPDFWriter w{*dest};
            w.setOutputFile("jpdfium-out", out, true);
            writerOwnsFile = true;
            configureAssembledWriter(w);
            WriterLock lock{-1};
            w.write();
            return 0;
        } catch (...) {
            if (!writerOwnsFile) std::fclose(out);
            throw;
        }
    } catch (const std::exception& e) {
        std::fprintf(stderr, "jpdfium qpdf extract file: %s\n", e.what());
        return -1;
    } catch (...) {
        // Never let a non-std exception escape an extern "C" boundary.
        std::fprintf(stderr, "jpdfium qpdf extract file: unknown error\n");
        return -1;
    }
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_extract_pages(const uint8_t* input, int64_t inputLen,
                                                  const int32_t* pageIndices, int32_t pageCount,
                                                  uint8_t** output, int64_t* outputLen) {
    try {
        if (!input || inputLen <= 0 || !pageIndices || pageCount <= 0 || !output || !outputLen)
            return -1;
        *output = nullptr;
        *outputLen = 0;

        auto result = extractPages({input, static_cast<size_t>(inputLen)}, pageIndices, pageCount);
        if (!result.ok()) {
            std::fprintf(stderr, "jpdfium qpdf extract: %s\n", result.error.c_str());
            return -1;
        }

        auto& buf = result.buffer;
        *outputLen = static_cast<int64_t>(buf->getSize());
        *output = static_cast<uint8_t*>(malloc(static_cast<size_t>(*outputLen)));
        if (!*output) {
            *outputLen = 0;
            return -1;
        }
        std::memcpy(*output, buf->getBuffer(), static_cast<size_t>(*outputLen));
        return 0;

    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_encrypt(const uint8_t* input, int64_t inputLen,
                                            const char* userPassword, const char* ownerPassword,
                                            int32_t permissions, int32_t keyLength,
                                            uint8_t** output, int64_t* outputLen) {
    try {
        if (!input || inputLen <= 0 || !output || !outputLen) return -1;
        *output = nullptr;
        *outputLen = 0;

        auto result = encryptPdf({input, static_cast<size_t>(inputLen)}, userPassword,
                                 ownerPassword, permissions, keyLength);
        if (!result.ok()) {
            std::fprintf(stderr, "jpdfium qpdf encrypt: %s\n", result.error.c_str());
            return -1;
        }

        auto& buf = result.buffer;
        *outputLen = static_cast<int64_t>(buf->getSize());
        *output = static_cast<uint8_t*>(malloc(static_cast<size_t>(*outputLen)));
        if (!*output) {
            *outputLen = 0;
            return -1;
        }
        std::memcpy(*output, buf->getBuffer(), static_cast<size_t>(*outputLen));
        return 0;

    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_decrypt(const uint8_t* input, int64_t inputLen,
                                            const char* password, uint8_t** output,
                                            int64_t* outputLen) {
    try {
        if (!input || inputLen <= 0 || !output || !outputLen) return -1;
        *output = nullptr;
        *outputLen = 0;

        auto result = decryptPdf({input, static_cast<size_t>(inputLen)}, password);
        if (!result.ok()) {
            std::fprintf(stderr, "jpdfium qpdf decrypt: %s\n", result.error.c_str());
            return -1;
        }

        auto& buf = result.buffer;
        *outputLen = static_cast<int64_t>(buf->getSize());
        *output = static_cast<uint8_t*>(malloc(static_cast<size_t>(*outputLen)));
        if (!*output) {
            *outputLen = 0;
            return -1;
        }
        std::memcpy(*output, buf->getBuffer(), static_cast<size_t>(*outputLen));
        return 0;

    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

static void applyEncryption(QPDFWriter& w, const char* userPassword, const char* ownerPassword,
                            int32_t permissions, int32_t keyLength) {
    std::string userPass = userPassword ? userPassword : "";
    std::string ownerPass = ownerPassword ? ownerPassword : userPass;
    bool allowPrint =
        (permissions & JPDFIUM_PERM_PRINT_HIGH) || (permissions & JPDFIUM_PERM_PRINT_LOW);
    bool allowExtract = (permissions & JPDFIUM_PERM_EXTRACT) != 0;
    bool allowModify = (permissions & JPDFIUM_PERM_MODIFY) != 0;
    bool allowAccessibility = (permissions & JPDFIUM_PERM_ACCESSIBILITY) != 0;
    bool allowAssemble = (permissions & JPDFIUM_PERM_ASSEMBLE) != 0;
    bool allowAnnotate = (permissions & JPDFIUM_PERM_ANNOTATE) != 0;
    bool allowFillForms = (permissions & JPDFIUM_PERM_FILL_FORMS) != 0;
    qpdf_r3_print_e printMode = allowPrint ? qpdf_r3p_full : qpdf_r3p_none;
    if (keyLength == 256) {
        w.setR6EncryptionParameters(userPass.c_str(), ownerPass.c_str(), allowAccessibility,
                                    allowExtract, allowAssemble, allowAnnotate, allowFillForms,
                                    allowModify, printMode, true);
    } else {
        w.setR5EncryptionParameters(userPass.c_str(), ownerPass.c_str(), allowAccessibility,
                                    allowExtract, allowAssemble, allowAnnotate, allowFillForms,
                                    allowModify, printMode, true);
    }
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_encrypt_file(const char* in_path, const char* out_path,
                                                 const char* userPassword,
                                                 const char* ownerPassword, int32_t permissions,
                                                 int32_t keyLength) {
    if (!in_path || !*in_path || !out_path || !*out_path) return -1;
    try {
        auto qpdf = QPDF::create();
        qpdf->processFile(in_path);
        FILE* out = createOutputFile(out_path);
        if (!out) return -1;
        bool writerOwnsFile = false;
        try {
            QPDFWriter w{*qpdf};
            w.setOutputFile("jpdfium-out", out, true);
            writerOwnsFile = true;
            applyEncryption(w, userPassword, ownerPassword, permissions, keyLength);
            WriterLock lock{-1};
            w.write();
            return 0;
        } catch (const std::exception& e) {
            std::fprintf(stderr, "jpdfium qpdf encrypt file: %s\n", e.what());
            if (!writerOwnsFile) std::fclose(out);
            return -1;
        } catch (...) {
            std::fprintf(stderr, "jpdfium qpdf encrypt file: unknown error\n");
            if (!writerOwnsFile) std::fclose(out);
            return -1;
        }
    } catch (const std::exception& e) {
        std::fprintf(stderr, "jpdfium qpdf encrypt file: %s\n", e.what());
        return -1;
    } catch (...) {
        std::fprintf(stderr, "jpdfium qpdf encrypt file: unknown error\n");
        return -1;
    }
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_decrypt_file(const char* in_path, const char* out_path,
                                                 const char* password) {
    if (!in_path || !*in_path || !out_path || !*out_path) return -1;
    try {
        auto qpdf = QPDF::create();
        if (password && *password) {
            qpdf->processFile(in_path, password);
        } else {
            qpdf->processFile(in_path);
        }
        FILE* out = createOutputFile(out_path);
        if (!out) return -1;
        bool writerOwnsFile = false;
        try {
            QPDFWriter w{*qpdf};
            // See decryptPdf: QPDFWriter preserves the source encryption unless
            // it is told not to, so without this the output would still carry
            // /Encrypt and the caller would be told the decryption succeeded.
            w.setPreserveEncryption(false);
            w.setOutputFile("jpdfium-out", out, true);
            writerOwnsFile = true;
            WriterLock lock{-1};
            w.write();
            return 0;
        } catch (const std::exception& e) {
            std::fprintf(stderr, "jpdfium qpdf decrypt file: %s\n", e.what());
            if (!writerOwnsFile) std::fclose(out);
            return -1;
        } catch (...) {
            std::fprintf(stderr, "jpdfium qpdf decrypt file: unknown error\n");
            if (!writerOwnsFile) std::fclose(out);
            return -1;
        }
    } catch (const std::exception& e) {
        std::fprintf(stderr, "jpdfium qpdf decrypt file: %s\n", e.what());
        return -1;
    } catch (...) {
        std::fprintf(stderr, "jpdfium qpdf decrypt file: unknown error\n");
        return -1;
    }
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_copy_encryption_file(const char* in_path, const char* out_path,
                                                         const char* source_path,
                                                         const char* password) {
    if (!in_path || !*in_path || !out_path || !*out_path || !source_path || !*source_path ||
        !password) {
        return -1;
    }
    try {
        // Open the encrypted source with whichever password the caller knows.
        // The source document is only read for its encryption dictionary, so it
        // never has to be written back out.
        //
        // Pre-256-bit encryption imposes single-byte PDFDocEncoding passwords
        // while our caller hands us UTF-8, and QPDF::processFile performs no
        // conversion, so walk the same alternative encodings that QPDFJob tries
        // for the command line. Without this an AES-128 source protected with a
        // password that needs a second encoding would refuse a password that
        // the equivalent qpdf command line accepts.
        std::vector<std::string> candidates = QUtil::possible_repaired_encodings(password);
        if (candidates.size() > 1) {
            // Report the supplied password's own failure if every form fails.
            candidates.push_back(password);
        }
        std::shared_ptr<QPDF> source;
        for (auto iter = candidates.begin(); iter != candidates.end(); ++iter) {
            auto attempt = QPDF::create();
            try {
                attempt->processFile(source_path, iter->c_str());
                source = std::move(attempt);
                break;
            } catch (const QPDFExc&) {
                if (iter + 1 == candidates.end()) throw;
            }
        }
        // A plaintext source carries no protection to copy. Refusing here keeps
        // a caller from publishing an unprotected file and believing it is
        // protected.
        if (!source->isEncrypted()) return -1;

        auto dest = QPDF::create();
        dest->processFile(in_path);
        // The working input must already be unencrypted: copying protection onto
        // an encrypted input would silently replace its existing protection.
        if (dest->isEncrypted()) return -1;

        FILE* out = createOutputFile(out_path);
        if (!out) return -1;
        bool writerOwnsFile = false;
        try {
            QPDFWriter w{*dest};
            w.setOutputFile("jpdfium-out", out, true);
            writerOwnsFile = true;
            // Verbatim copy of the source's O/U keys, permissions and revision:
            // the second password is retained without ever being known here, and
            // no fresh key is generated (callers wanting a new key use
            // jpdfium_qpdf_encrypt_file instead).
            w.copyEncryptionParameters(*source);
            WriterLock lock{-1};
            w.write();
            return 0;
        } catch (const std::exception& e) {
            std::fprintf(stderr, "jpdfium qpdf copy encryption file: %s\n", e.what());
            if (!writerOwnsFile) std::fclose(out);
            return -1;
        } catch (...) {
            std::fprintf(stderr, "jpdfium qpdf copy encryption file: unknown error\n");
            if (!writerOwnsFile) std::fclose(out);
            return -1;
        }
    } catch (const std::exception& e) {
        // Wrong password, unreadable source or a malformed input all land here.
        std::fprintf(stderr, "jpdfium qpdf copy encryption file: %s\n", e.what());
        return -1;
    } catch (...) {
        std::fprintf(stderr, "jpdfium qpdf copy encryption file: unknown error\n");
        return -1;
    }
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

JPDFIUM_EXPORT int32_t jpdfium_qpdf_inspect(const uint8_t*, int64_t, const char*, char** json) {
    if (!json) return -1;
    *json = strdup("{\"status\":\"unavailable\",\"message\":\"qpdf not linked\",\"warnings\":[]}");
    return *json ? 0 : -1;
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_set_docinfo(const uint8_t* input, int64_t inputLen,
                                                const char* const*, const char* const*, int32_t,
                                                const char*, uint8_t** output, int64_t* outputLen) {
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

JPDFIUM_EXPORT int32_t jpdfium_qpdf_set_docinfo_file(const char*, const char*, const char* const*,
                                                     const char* const*, int32_t, const char*) {
    return -1;
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_merge(const uint8_t* const*, const int64_t*, int32_t,
                                          uint8_t** output, int64_t* outputLen) {
    if (output) *output = nullptr;
    if (outputLen) *outputLen = 0;
    return -1;
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_extract_pages(const uint8_t*, int64_t, const int32_t*, int32_t,
                                                  uint8_t** output, int64_t* outputLen) {
    if (output) *output = nullptr;
    if (outputLen) *outputLen = 0;
    return -1;
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_merge_files(const char* const*, int32_t, const char*) {
    return -1;
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

JPDFIUM_EXPORT int32_t jpdfium_qpdf_extract_pages_file(const char*, const int32_t*, int32_t,
                                                       const char*) {
    return -1;
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_encrypt(const uint8_t*, int64_t, const char*, const char*,
                                            int32_t, int32_t, uint8_t** output,
                                            int64_t* outputLen) {
    if (output) *output = nullptr;
    if (outputLen) *outputLen = 0;
    return -1;
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_decrypt(const uint8_t*, int64_t, const char*, uint8_t** output,
                                            int64_t* outputLen) {
    if (output) *output = nullptr;
    if (outputLen) *outputLen = 0;
    return -1;
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_sanitize_file(const char*, const char*, int32_t) {
    return -1;
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_encrypt_file(const char*, const char*, const char*, const char*,
                                                 int32_t, int32_t) {
    return -1;
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_decrypt_file(const char*, const char*, const char*) {
    return -1;
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_copy_encryption_file(const char*, const char*, const char*,
                                                         const char*) {
    return -1;
}

}  // extern "C"

#endif  // JPDFIUM_HAS_QPDF
