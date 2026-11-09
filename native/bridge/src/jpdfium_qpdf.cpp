// jpdfium_qpdf.cpp - In-process qpdf structural optimization (FFM, no CLI).
//
// jpdfium_qpdf_optimize drives the bundled qpdf library entirely in memory via
// its QPDF/QPDFWriter C++ API.

#include <algorithm>
#include <cctype>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <functional>
#include <limits>
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

#include <ft2build.h>
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
#include FT_FREETYPE_H
#include FT_ADVANCES_H
#include FT_FONT_FORMATS_H
#include FT_TRUETYPE_TABLES_H
#include <hb-subset.h>
#include <hb.h>

namespace {

// Create the output with owner-only permissions on POSIX (CodeQL
// cpp/world-writable-file-creation); Windows has no mode argument here.
//
// Callers pass Java-created staging files, so O_TRUNC (overwrite) is correct
// - but O_NOFOLLOW still refuses a symlink swapped in for the staging file
// after Java created it.
static FILE* createOutputFile(const char* path) {
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
            previous.getKey("/P").unparseResolved() == label.getKey("/P").unparseResolved() &&
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
    w.setCompressStreams(false);
    w.setDecodeLevel(qpdf_dl_none);
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

struct RangeSource {
    QPDF& pdf;
    std::vector<QPDFObjectHandle> const& pages;
    QPDFAcroFormDocumentHelper forms;
    QPDFPageLabelDocumentHelper labels;
};

QPDFObjectHandle copyInto(QPDF& dest, QPDF& source, QPDFObjectHandle value) {
    if (value.isIndirect()) return dest.copyForeignObject(value);
    return dest.copyForeignObject(source.makeIndirectObject(value));
}

void copyFieldsForPages(QPDF& dest, QPDF& source, std::set<QPDFObjGen> const& pageFields) {
    QPDFObjectHandle acroForm = source.getRoot().getKey("/AcroForm");
    if (!acroForm.isDictionary()) return;
    QPDFObjectHandle fields = acroForm.getKey("/Fields");
    if (!fields.isArray()) return;
    std::vector<QPDFObjectHandle> kept;
    for (auto const& field : fields.getArrayAsVector()) {
        if (field.isIndirect() && pageFields.count(field.getObjGen())) {
            kept.push_back(dest.copyForeignObject(field));
        }
    }
    if (kept.empty()) return;
    QPDFObjectHandle form = QPDFObjectHandle::newDictionary();
    for (auto const& key : acroForm.getKeys()) {
        if (key == "/Fields" || key == "/XFA") continue;
        form.replaceKey(key, copyInto(dest, source, acroForm.getKey(key)));
    }
    form.replaceKey("/Fields", QPDFObjectHandle::newArray(kept));
    dest.getRoot().replaceKey("/AcroForm", dest.makeIndirectObject(form));
}

QPDFObjectHandle ownedLabel(QPDF& dest, QPDFObjectHandle label) {
    QPDFObjectHandle owned = QPDFObjectHandle::newDictionary();
    for (auto const& key : label.getKeys()) {
        QPDFObjectHandle value = label.getKey(key);
        owned.replaceKey(key, value.isIndirect() ? dest.copyForeignObject(value) : value);
    }
    return owned;
}

int writeRange(RangeSource& source, int32_t first, int32_t last, const char* out_path) {
    if (first < 0 || last < first || static_cast<size_t>(last) >= source.pages.size()) return -1;
    auto dest = QPDF::create();
    dest->emptyPDF();
    QPDFObjectHandle srcRoot = source.pdf.getRoot();
    QPDFObjectHandle destRoot = dest->getRoot();
    for (auto const& key : srcRoot.getKeys()) {
        if (key == "/Type" || key == "/Pages" || key == "/AcroForm" || key == "/PageLabels" ||
            key == "/Outlines" || key == "/StructTreeRoot" || key == "/MarkInfo" ||
            key == "/NeedsRendering" || key == "/Perms") {
            continue;
        }
        destRoot.replaceKey(key, copyInto(*dest, source.pdf, srcRoot.getKey(key)));
    }
    QPDFObjectHandle info = source.pdf.getTrailer().getKey("/Info");
    if (info.isDictionary()) {
        dest->getTrailer().replaceKey("/Info", copyInto(*dest, source.pdf, info));
    }

    bool labelled = source.labels.hasPageLabels();
    std::set<QPDFObjGen> pageFields;
    std::vector<QPDFObjectHandle> labelNums;
    for (int32_t index = first; index <= last; ++index) {
        QPDFObjectHandle page = source.pages[static_cast<size_t>(index)];
        dest->addPage(page, false);
        for (auto& field : source.forms.getFormFieldsForPage(QPDFPageObjectHelper{page})) {
            pageFields.insert(field.getObjectHandle().getObjGen());
        }
        if (labelled) {
            long long outputIndex = index - first;
            appendLabel(labelNums, outputIndex,
                        ownedLabel(*dest, labelFor(source.labels, index, outputIndex)));
        }
    }
    if (labelled) {
        destRoot.replaceKey("/PageLabels", QPDFObjectHandle::newDictionary(
                                               {{"/Nums", QPDFObjectHandle::newArray(labelNums)}}));
    }
    copyFieldsForPages(*dest, source.pdf, pageFields);
    nullPagesOutsideTree(*dest);

    FILE* out = createOutputFile(out_path);
    if (!out) {
        std::fprintf(stderr, "jpdfium qpdf file: cannot open output %s\n", out_path);
        return -1;
    }
    bool writerOwnsFile = false;
    try {
        QPDFWriter w{*dest};
        w.setOutputFile("jpdfium-out", out, true);
        writerOwnsFile = true;
        configureAssembledWriter(w);
        w.setMinimumPDFVersion(source.pdf.getPDFVersion(), source.pdf.getExtensionLevel());
        WriterLock lock{-1};
        w.write();
        return 0;
    } catch (...) {
        if (!writerOwnsFile) std::fclose(out);
        throw;
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
            if (key == "/Trapped") {
                std::string val = values[i];
                if (val.empty() || val.front() != '/') {
                    val.insert(val.begin(), '/');
                }
                info.replaceKey(key, QPDFObjectHandle::newName(val));
            } else {
                info.replaceKey(key, QPDFObjectHandle::newUnicodeString(values[i]));
            }
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
        } catch (...) {
            if (!writerOwnsFile) std::fclose(out);
            return JPDFIUM_ERR_NATIVE;
        }
    } catch (std::exception const& e) {
        std::fprintf(stderr, "jpdfium qpdf force version file: %s\n", e.what());
        return -1;
    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
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

std::string stripPdfNamePrefix(std::string name) {
    if (!name.empty() && name.front() == '/') name.erase(name.begin());
    return name;
}

struct FontSource {
    std::string name;
    std::span<const uint8_t> data;
};

struct EmbedFontsResult {
    std::shared_ptr<Buffer> buffer;
    std::string error;
    int32_t rc = 0;
};

constexpr uint16_t kStandardHigh[96] = {
    0,      0xA1,   0xA2,   0xA3,   0x2044, 0xA5,   0x192,  0xA7,   0xA4, 0x27,  0x201C, 0xAB,
    0x2039, 0x203A, 0xFB01, 0xFB02, 0,      0x2013, 0x2020, 0x2021, 0xB7, 0,     0xB6,   0x2022,
    0x201A, 0x201E, 0x201D, 0xBB,   0x2026, 0x2030, 0,      0xBF,   0,    0x60,  0xB4,   0x2C6,
    0x2DC,  0xAF,   0x2D8,  0x2D9,  0xA8,   0,      0x2DA,  0xB8,   0,    0x2DD, 0x2DB,  0x2C7,
    0x2014, 0,      0,      0,      0,      0,      0,      0,      0,    0,     0,      0,
    0,      0,      0,      0,      0,      0xC6,   0,      0xAA,   0,    0,     0,      0,
    0x141,  0xD8,   0x152,  0xBA,   0,      0,      0,      0,      0,    0xE6,  0,      0,
    0,      0x131,  0,      0,      0x142,  0xF8,   0x153,  0xDF,   0,    0,     0,      0};
constexpr uint16_t kMacRomanHigh[128] = {
    0xC4,   0xC5,   0xC7,   0xC9,   0xD1,   0xD6,  0xDC,   0xE1,   0xE0,   0xE2,   0xE4,   0xE3,
    0xE5,   0xE7,   0xE9,   0xE8,   0xEA,   0xEB,  0xED,   0xEC,   0xEE,   0xEF,   0xF1,   0xF3,
    0xF2,   0xF4,   0xF6,   0xF5,   0xFA,   0xF9,  0xFB,   0xFC,   0x2020, 0xB0,   0xA2,   0xA3,
    0xA7,   0x2022, 0xB6,   0xDF,   0xAE,   0xA9,  0x2122, 0xB4,   0xA8,   0x2260, 0xC6,   0xD8,
    0x221E, 0xB1,   0x2264, 0x2265, 0xA5,   0xB5,  0x2202, 0x2211, 0x220F, 0x3C0,  0x222B, 0xAA,
    0xBA,   0x3A9,  0xE6,   0xF8,   0xBF,   0xA1,  0xAC,   0x221A, 0x192,  0x2248, 0x2206, 0xAB,
    0xBB,   0x2026, 0x20,   0xC0,   0xC3,   0xD5,  0x152,  0x153,  0x2013, 0x2014, 0x201C, 0x201D,
    0x2018, 0x2019, 0xF7,   0x25CA, 0xFF,   0x178, 0x2044, 0xA4,   0x2039, 0x203A, 0xFB01, 0xFB02,
    0x2021, 0xB7,   0x201A, 0x201E, 0x2030, 0xC2,  0xCA,   0xC1,   0xCB,   0xC8,   0xCD,   0xCE,
    0xCF,   0xCC,   0xD3,   0xD4,   0xF8FF, 0xD2,  0xDA,   0xDB,   0xD9,   0x131,  0x2C6,  0x2DC,
    0xAF,   0x2D8,  0x2D9,  0x2DA,  0xB8,   0x2DD, 0x2DB,  0x2C7};
constexpr uint16_t kWinAnsiHigh[32] = {
    0x20AC, 0x2022, 0x201A, 0x192,  0x201E, 0x2026, 0x2020, 0x2021, 0x2C6,  0x2030, 0x160,
    0x2039, 0x152,  0x2022, 0x17D,  0x2022, 0x2022, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022,
    0x2013, 0x2014, 0x2DC,  0x2122, 0x161,  0x203A, 0x153,  0x2022, 0x17E,  0x178};

enum class BaseEncoding { Standard, WinAnsi, MacRoman };

uint32_t baseEncodingUnicode(BaseEncoding enc, int code) {
    if (code < 0x20) return 0;
    if (code < 0x7F) {
        if (enc == BaseEncoding::Standard && code == 0x27) return 0x2019;
        if (enc == BaseEncoding::Standard && code == 0x60) return 0x2018;
        return code;
    }
    if (enc == BaseEncoding::MacRoman) return code >= 0x80 ? kMacRomanHigh[code - 0x80] : 0;
    if (enc == BaseEncoding::WinAnsi) {
        if (code == 0xA0 || code == 0xAD) return code - 0x80;
        return code >= 0xA0 ? code : (code >= 0x80 ? kWinAnsiHigh[code - 0x80] : 0x2022);
    }
    return code >= 0xA0 ? kStandardHigh[code - 0xA0] : 0;
}

enum class ProgramKind { TrueType, OpenType, BareCff, Type1 };

struct FontProgram {
    FT_Face face = nullptr;
    ProgramKind kind{};
    bool usable = false;
    bool noSubsetting = false;
    std::string bytes;
    long long length1 = 0, length2 = 0, length3 = 0;
    std::map<std::tuple<ProgramKind, bool, std::set<FT_UInt>>, QPDFObjectHandle> streams;
};

bool splitType1(std::span<const uint8_t> in, FontProgram& out) {
    std::string clear, binary, tail;
    if (in.size() > 6 && in[0] == 0x80) {
        for (size_t i = 0; i + 6 <= in.size() && in[i] == 0x80 && in[i + 1] != 3;) {
            size_t len = in[i + 2] | in[i + 3] << 8 | in[i + 4] << 16 | size_t(in[i + 5]) << 24;
            if (i + 6 + len > in.size()) return false;
            (in[i + 1] == 2 ? binary : (binary.empty() ? clear : tail))
                .append(reinterpret_cast<const char*>(in.data()) + i + 6, len);
            i += 6 + len;
        }
    } else {
        std::string_view s(reinterpret_cast<const char*>(in.data()), in.size());
        size_t eexec = s.find("eexec"), mark = s.rfind("cleartomark");
        if (eexec == s.npos) return false;
        size_t start = s.find_first_not_of(" \t\r\n", eexec + 5);
        if (start == s.npos) return false;
        size_t end = mark == s.npos || mark < start ? s.size() : mark;
        while (end < s.size() && end > start &&
               (s[end - 1] == '0' || std::isspace(uint8_t(s[end - 1]))))
            --end;
        clear = s.substr(0, start);
        tail = s.substr(end);
        auto body = s.substr(start, end - start);
        if (body.size() >= 4 && std::all_of(body.begin(), body.begin() + 4,
                                            [](char c) { return std::isxdigit(uint8_t(c)); })) {
            int hi = -1;
            for (char c : body) {
                if (!std::isxdigit(uint8_t(c))) continue;
                int v = std::isdigit(uint8_t(c)) ? c - '0' : (std::tolower(c) - 'a' + 10);
                if (hi < 0)
                    hi = v;
                else
                    binary.push_back(char(hi << 4 | v)), hi = -1;
            }
        } else {
            binary = body;
        }
    }
    if (clear.empty() || binary.empty()) return false;
    out.bytes = clear + binary + tail;
    out.length1 = clear.size(), out.length2 = binary.size(), out.length3 = tail.size();
    return true;
}

bool loadProgram(FT_Library lib, std::span<const uint8_t> data, FontProgram& p) {
    if (data.size() < 4 || std::memcmp(data.data(), "ttcf", 4) == 0 ||
        FT_New_Memory_Face(lib, data.data(), FT_Long(data.size()), 0, &p.face))
        return false;
    std::string_view format = FT_Get_Font_Format(p.face);
    FT_ULong len = 0;
    if (FT_IS_SFNT(p.face)) {
        if (!FT_Load_Sfnt_Table(p.face, FT_MAKE_TAG('C', 'F', 'F', '2'), 0, nullptr, &len))
            return false;
        bool glyf = !FT_Load_Sfnt_Table(p.face, FT_MAKE_TAG('g', 'l', 'y', 'f'), 0, nullptr, &len);
        p.kind = glyf ? ProgramKind::TrueType : ProgramKind::OpenType;
        FT_ULong os2Len = 0;
        if (!FT_Load_Sfnt_Table(p.face, FT_MAKE_TAG('O', 'S', '/', '2'), 0, nullptr, &os2Len) &&
            os2Len >= 10) {
            std::vector<uint8_t> os2(os2Len);
            if (!FT_Load_Sfnt_Table(p.face, FT_MAKE_TAG('O', 'S', '/', '2'), 0, os2.data(),
                                    &os2Len) &&
                os2Len >= 10) {
                uint16_t fsType = uint16_t(os2[8] << 8 | os2[9]);
                p.noSubsetting = (fsType & 0x0100) != 0;
            }
        }
    } else if (format == "CFF") {
        p.kind = ProgramKind::BareCff;
    } else if (format == "Type 1") {
        p.kind = ProgramKind::Type1;
        return splitType1(data, p);
    } else {
        return false;
    }
    p.bytes.assign(reinterpret_cast<const char*>(data.data()), data.size());
    return true;
}

std::string subsetProgram(FontProgram const& p, std::set<FT_UInt> const& glyphs) {
    if (p.bytes.size() > std::numeric_limits<unsigned int>::max()) return {};
    hb_blob_t* blob = hb_blob_create(p.bytes.data(), static_cast<unsigned int>(p.bytes.size()),
                                     HB_MEMORY_MODE_READONLY, nullptr, nullptr);
    hb_face_t* face = hb_face_create(blob, 0);
    hb_blob_destroy(blob);
    std::string out;
    if (hb_subset_input_t* input = hb_subset_input_create_or_fail()) {
        for (FT_UInt g : glyphs) hb_set_add(hb_subset_input_glyph_set(input), g);
        FT_UInt gid = 0;
        if (!FT_Select_Charmap(p.face, FT_ENCODING_UNICODE)) {
            for (FT_ULong u = FT_Get_First_Char(p.face, &gid); gid;
                 u = FT_Get_Next_Char(p.face, u, &gid))
                if (glyphs.contains(gid)) hb_set_add(hb_subset_input_unicode_set(input), u);
        }
        hb_subset_input_set_flags(input, HB_SUBSET_FLAGS_RETAIN_GIDS | HB_SUBSET_FLAGS_GLYPH_NAMES |
                                             HB_SUBSET_FLAGS_NOTDEF_OUTLINE);
        if (hb_face_t* subset = hb_subset_or_fail(face, input)) {
            hb_blob_t* result = hb_face_reference_blob(subset);
            unsigned length = 0;
            const char* data = hb_blob_get_data(result, &length);
            out.assign(data, length);
            hb_blob_destroy(result);
            hb_face_destroy(subset);
        }
        hb_subset_input_destroy(input);
    }
    hb_face_destroy(face);
    return out;
}

QPDFObjectHandle makeProgramStream(QPDF& qpdf, FontProgram const& p, ProgramKind kind, bool cid,
                                   std::string const& subset) {
    auto const& bytes = subset.empty() ? p.bytes : subset;
    auto stream = QPDFObjectHandle::newStream(&qpdf, bytes);
    auto dict = stream.getDict();
    if (kind == ProgramKind::TrueType)
        dict.replaceKey("/Length1", QPDFObjectHandle::newInteger(bytes.size()));
    if (kind == ProgramKind::Type1) {
        dict.replaceKey("/Length1", QPDFObjectHandle::newInteger(p.length1));
        dict.replaceKey("/Length2", QPDFObjectHandle::newInteger(p.length2));
        dict.replaceKey("/Length3", QPDFObjectHandle::newInteger(p.length3));
    }
    char const* subtype = kind == ProgramKind::OpenType  ? "/OpenType"
                          : kind != ProgramKind::BareCff ? nullptr
                          : cid                          ? "/CIDFontType0C"
                                                         : "/Type1C";
    if (subtype) dict.replaceKey("/Subtype", QPDFObjectHandle::newName(subtype));
    return stream;
}

FT_UInt glyphForName(FT_Face face, std::string const& name) {
    if (FT_HAS_GLYPH_NAMES(face)) {
        if (FT_UInt gid = FT_Get_Name_Index(face, name.c_str())) return gid;
    }
    uint32_t u = name.size() == 7 && name.starts_with("uni")
                     ? std::strtoul(name.c_str() + 3, nullptr, 16)
                 : name.size() == 1 ? uint8_t(name[0])
                                    : 0;
    return u && !FT_Select_Charmap(face, FT_ENCODING_UNICODE) ? FT_Get_Char_Index(face, u) : 0;
}

struct SimpleEncoding {
    BaseEncoding base = BaseEncoding::Standard;
    bool namedBase = false;
    std::map<int, std::string> differences;
};

SimpleEncoding parseEncoding(QPDFObjectHandle const& font) {
    SimpleEncoding enc;
    auto encoding = font.getKey("/Encoding");
    auto base = encoding.isDictionary() ? encoding.getKey("/BaseEncoding") : encoding;
    enc.namedBase =
        base.isNameAndEquals("/WinAnsiEncoding") || base.isNameAndEquals("/MacRomanEncoding");
    if (base.isNameAndEquals("/WinAnsiEncoding")) enc.base = BaseEncoding::WinAnsi;
    if (base.isNameAndEquals("/MacRomanEncoding")) enc.base = BaseEncoding::MacRoman;
    auto differences =
        encoding.isDictionary() ? encoding.getKey("/Differences") : QPDFObjectHandle();
    int code = 0;
    for (auto& item :
         differences.isArray() ? differences.getArrayAsVector() : std::vector<QPDFObjectHandle>{}) {
        if (item.isInteger())
            code = item.getIntValueAsInt();
        else if (item.isName()) {
            if (code >= 0 && code <= 255) enc.differences[code] = item.getName().substr(1);
            if (code <= 255) ++code;
        }
    }
    return enc;
}

FT_UInt glyphForCode(FT_Face face, int code, bool symbolic, SimpleEncoding const& enc) {
    if (auto it = enc.differences.find(code); it != enc.differences.end()) {
        FT_UInt gid = it->second == ".notdef" ? 0 : glyphForName(face, it->second);
        if (gid || !symbolic) return gid;
    } else if (uint32_t u = symbolic ? 0 : baseEncodingUnicode(enc.base, code)) {
        if (!FT_Select_Charmap(face, FT_ENCODING_UNICODE)) {
            if (FT_UInt gid = FT_Get_Char_Index(face, u)) return gid;
        }
    }
    for (int pass = 0; pass < 2; ++pass) {
        for (int i = 0; i < face->num_charmaps; ++i) {
            if ((face->charmaps[i]->encoding == FT_ENCODING_UNICODE) != (pass == 1)) continue;
            FT_Set_Charmap(face, face->charmaps[i]);
            if (FT_UInt gid = FT_Get_Char_Index(face, code)) return gid;
            if (FT_UInt gid = FT_Get_Char_Index(face, 0xF000 | code)) return gid;
        }
    }
    return 0;
}

void addWidths(QPDFObjectHandle font, FT_Face face, bool symbolic) {
    auto enc = parseEncoding(font);
    auto widths = QPDFObjectHandle::newArray();
    for (int code = 0; code < 256; ++code) {
        FT_Fixed advance = 0;
        FT_Get_Advance(face, glyphForCode(face, code, symbolic, enc), FT_LOAD_NO_SCALE, &advance);
        widths.appendItem(QPDFObjectHandle::newInteger(
            advance * 1000 / (face->units_per_EM ? face->units_per_EM : 1000)));
    }
    font.replaceKey("/FirstChar", QPDFObjectHandle::newInteger(0));
    font.replaceKey("/LastChar", QPDFObjectHandle::newInteger(255));
    font.replaceKey("/Widths", widths);
}

bool reachesSameGlyphs(FT_Library lib, std::string const& subset, FT_Face original,
                       SimpleEncoding const& enc) {
    FT_Face face = nullptr;
    if (subset.empty() || FT_New_Memory_Face(lib, reinterpret_cast<const FT_Byte*>(subset.data()),
                                             FT_Long(subset.size()), 0, &face))
        return false;
    bool same = true;
    for (int code = 0; same && code < 256; ++code)
        same = glyphForCode(face, code, false, enc) == glyphForCode(original, code, false, enc);
    FT_Done_Face(face);
    return same;
}

int unicodePreference(FT_ULong u, bool symbolic) {
    if (u >= 0xE000 && u <= 0xF8FF) return 0;
    if (!symbolic) return 1;
    if (u >= 0x2700 && u < 0x27C0) return 4;
    return u < 0x80 ? 1 : u >= 0x2000 && u < 0x2C00 ? 3 : 2;
}

std::map<FT_UInt, FT_ULong> unicodeByGlyph(FT_Face face, bool symbolic) {
    std::map<FT_UInt, FT_ULong> unicodeOf;
    if (FT_Select_Charmap(face, FT_ENCODING_UNICODE)) return unicodeOf;
    FT_UInt gid = 0;
    for (FT_ULong u = FT_Get_First_Char(face, &gid); gid; u = FT_Get_Next_Char(face, u, &gid)) {
        auto it = unicodeOf.try_emplace(gid, u).first;
        if (unicodePreference(u, symbolic) > unicodePreference(it->second, symbolic))
            it->second = u;
    }
    return unicodeOf;
}

void setToUnicode(QPDF& qpdf, QPDFObjectHandle font, std::map<uint32_t, FT_ULong> const& codes,
                  bool twoByte) {
    if (codes.empty() || font.hasKey("/ToUnicode")) return;
    std::string cmap = std::string(
                           "/CIDInit /ProcSet findresource begin 12 dict begin begincmap\n"
                           "/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> "
                           "def\n/CMapName /Adobe-Identity-UCS def /CMapType 2 def\n") +
                       (twoByte ? "1 begincodespacerange <0000> <FFFF> endcodespacerange\n"
                                : "1 begincodespacerange <00> <FF> endcodespacerange\n");
    std::string entries;
    size_t count = 0;
    for (auto [code, u] : codes) {
        char entry[40];
        int n = std::snprintf(entry, sizeof entry, twoByte ? "<%04X> " : "<%02X> ", code);
        if (u > 0xFFFF)
            std::snprintf(entry + n, sizeof entry - n, "<%04lX%04lX>\n",
                          0xD800 + ((u - 0x10000) >> 10), 0xDC00 + ((u - 0x10000) & 0x3FF));
        else
            std::snprintf(entry + n, sizeof entry - n, "<%04lX>\n", u);
        entries += entry;
        if (++count % 100 == 0 || count == codes.size()) {
            cmap +=
                std::to_string((count - 1) % 100 + 1) + " beginbfchar\n" + entries + "endbfchar\n";
            entries.clear();
        }
    }
    cmap += "endcmap CMapName currentdict /CMap defineresource pop end end\n";
    font.replaceKey("/ToUnicode", QPDFObjectHandle::newStream(&qpdf, cmap));
}

void pinTrueTypeMapping(QPDF& qpdf, QPDFObjectHandle font, FT_Face face, bool symbolic) {
    auto enc = parseEncoding(font);
    auto unicodeOf = unicodeByGlyph(face, symbolic);
    std::map<uint32_t, FT_ULong> codes;
    auto differences = QPDFObjectHandle::newArray();
    for (int code = 0; code < 256; ++code) {
        FT_UInt gid = glyphForCode(face, code, symbolic, enc);
        bool differs = enc.differences.contains(code);
        FT_ULong u = symbolic || differs ? 0 : baseEncodingUnicode(enc.base, code);
        if (auto it = unicodeOf.find(gid); !u && gid && it != unicodeOf.end()) u = it->second;
        if (!symbolic && !enc.namedBase &&
            (differs || u != baseEncodingUnicode(BaseEncoding::WinAnsi, code))) {
            char name[64] = ".notdef";
            if (differs)
                std::snprintf(name, sizeof name, "%s", enc.differences.at(code).c_str());
            else if (!gid || !FT_HAS_GLYPH_NAMES(face) ||
                     FT_Get_Glyph_Name(face, gid, name, sizeof name))
                std::snprintf(name, sizeof name, u ? "uni%04lX" : ".notdef", u);
            differences.appendItem(QPDFObjectHandle::newInteger(code));
            differences.appendItem(QPDFObjectHandle::newName(std::string("/") + name));
        }
        if (gid && u) codes[code] = u;
    }
    if (!symbolic && !enc.namedBase) {
        auto encoding = QPDFObjectHandle::newDictionary();
        encoding.replaceKey("/Type", QPDFObjectHandle::newName("/Encoding"));
        encoding.replaceKey("/BaseEncoding", QPDFObjectHandle::newName("/WinAnsiEncoding"));
        encoding.replaceKey("/Differences", differences);
        font.replaceKey("/Encoding", encoding);
    }
    setToUnicode(qpdf, font, codes, false);
}

QPDFObjectHandle newDescriptor(QPDF& qpdf, QPDFObjectHandle font, FT_Face face, bool symbolic) {
    auto scaled = [&](long v) {
        return QPDFObjectHandle::newInteger(v * 1000 /
                                            (face->units_per_EM ? face->units_per_EM : 1000));
    };
    bool italic = face->style_flags & FT_STYLE_FLAG_ITALIC;
    auto* os2 = static_cast<TT_OS2*>(FT_Get_Sfnt_Table(face, FT_SFNT_OS2));
    auto bbox = QPDFObjectHandle::newArray();
    for (long v : {face->bbox.xMin, face->bbox.yMin, face->bbox.xMax, face->bbox.yMax})
        bbox.appendItem(scaled(v));
    auto d = QPDFObjectHandle::newDictionary();
    d.replaceKey("/Type", QPDFObjectHandle::newName("/FontDescriptor"));
    d.replaceKey("/FontName", font.getKey("/BaseFont"));
    d.replaceKey("/Flags", QPDFObjectHandle::newInteger((symbolic ? 4 : 32) | (italic ? 64 : 0) |
                                                        (FT_IS_FIXED_WIDTH(face) ? 1 : 0)));
    d.replaceKey("/FontBBox", bbox);
    d.replaceKey("/ItalicAngle", QPDFObjectHandle::newInteger(italic ? -12 : 0));
    d.replaceKey("/Ascent", scaled(face->ascender));
    d.replaceKey("/Descent", scaled(face->descender));
    d.replaceKey("/CapHeight", scaled(os2 && os2->sCapHeight ? os2->sCapHeight : face->ascender));
    d.replaceKey("/StemV",
                 QPDFObjectHandle::newInteger(face->style_flags & FT_STYLE_FLAG_BOLD ? 120 : 80));
    return qpdf.makeIndirectObject(d);
}

EmbedFontsResult embedFonts(std::function<void(QPDF&)> const& load,
                            std::vector<FontSource> const& sources, bool onlyMissing,
                            FILE* out = nullptr) {
    struct Programs : std::vector<FontProgram> {
        FT_Library lib = nullptr;
        ~Programs() {
            for (auto& p : *this)
                if (p.face) FT_Done_Face(p.face);
            if (lib) FT_Done_FreeType(lib);
        }
    } programs;
    programs.resize(sources.size());
    FT_Library& lib = programs.lib;
    try {
        if (FT_Init_FreeType(&lib)) return {nullptr, "FreeType init failed", -1};
        auto qpdf = QPDF::create();
        load(*qpdf);

        auto hasSignatureValue = [](QPDFObjectHandle const& field) {
            if (!field.isDictionary()) return false;
            auto value = field.getKey("/V");
            return field.hasKey("/ByteRange") ||
                   (value.isDictionary() && value.hasKey("/ByteRange"));
        };
        std::vector<QPDFObjectHandle> fonts;
        auto collectFromResources = [&](QPDFObjectHandle const& res) {
            auto fontDict = res.isDictionary() ? res.getKey("/Font") : QPDFObjectHandle();
            if (!fontDict.isDictionary()) return;
            for (auto const& [name, font] : fontDict.getDictAsMap())
                if (font.isDictionary()) fonts.push_back(font);
        };
        for (auto& object : qpdf->getAllObjects()) {
            auto dict = object.isStream() ? object.getDict() : object;
            if (!dict.isDictionary()) continue;
            if (hasSignatureValue(dict)) {
                return {nullptr, "document is signed; refusing to rewrite", -3};
            }
            auto type = dict.getKey("/Type");
            auto subtypeName = dict.getKey("/Subtype");
            bool cidDescendant =
                subtypeName.isName() && (subtypeName.getName() == "/CIDFontType0" ||
                                         subtypeName.getName() == "/CIDFontType2");
            if (type.isName() && type.getName() == "/Font" && !cidDescendant) fonts.push_back(dict);
            collectFromResources(dict.getKey("/Resources"));
        }
        auto acroForm = qpdf->getRoot().getKey("/AcroForm");
        std::vector<QPDFObjectHandle> pendingFields{acroForm};
        std::set<QPDFObjGen> visitedFields;
        bool signedForm = false;
        while (!pendingFields.empty()) {
            QPDFObjectHandle field = pendingFields.back();
            pendingFields.pop_back();
            if (!field.isDictionary()) continue;
            if (field.isIndirect() && !visitedFields.insert(field.getObjGen()).second) continue;
            if (hasSignatureValue(field)) {
                signedForm = true;
                break;
            }
            for (char const* key : {"/Fields", "/Kids"}) {
                QPDFObjectHandle children = field.getKey(key);
                if (!children.isArray()) continue;
                for (auto& child : children.getArrayAsVector()) pendingFields.push_back(child);
            }
        }
        if (acroForm.isDictionary()) {
            if (signedForm) {
                return {nullptr, "document is signed; refusing to rewrite", -3};
            }
            collectFromResources(acroForm.getKey("/DR"));
        }

        for (size_t i = 0; i < sources.size(); ++i) {
            programs[i].usable = loadProgram(lib, sources[i].data, programs[i]);
            if (!programs[i].usable && !onlyMissing) {
                return {nullptr, "unusable font program for /BaseFont " + sources[i].name, -4};
            }
        }
        auto nameOf = [](QPDFObjectHandle const& font) {
            auto base = font.getKey("/BaseFont");
            return base.isName() ? stripPdfNamePrefix(base.getName()) : std::string();
        };
        auto asLatin1 = [](std::string const& raw) {
            std::string utf8;
            for (unsigned char c : raw) {
                if (c >= 0x80) utf8 += char(0xC0 | c >> 6), c = 0x80 | (c & 0x3F);
                utf8 += char(c);
            }
            return utf8;
        };
        int embedded = 0;
        bool openType = false;
        std::set<QPDFObjGen> seen;
        for (auto& font : fonts) {
            if (font.isIndirect() && !seen.insert(font.getObjGen()).second) continue;
            auto descendants = font.getKey("/DescendantFonts");
            bool cid = descendants.isArray() && descendants.getArrayNItems() > 0 &&
                       descendants.getArrayItem(0).isDictionary();
            auto target = cid ? descendants.getArrayItem(0) : font;
            std::string fontName = nameOf(font), targetName = nameOf(target);
            auto source = std::ranges::find_if(sources, [&](FontSource const& s) {
                auto name = stripPdfNamePrefix(s.name);
                return name == fontName || name == targetName || name == asLatin1(fontName) ||
                       name == asLatin1(targetName);
            });
            if (source == sources.end()) continue;
            auto subtype = target.getKey("/Subtype");
            if (subtype.isName() && subtype.getName() == "/Type3") continue;
            auto& program = programs[source - sources.begin()];
            if (!program.usable) continue;
            if (cid && program.kind == ProgramKind::Type1) {
                return {nullptr, "a Type 1 program cannot back a CIDFont: " + fontName, -4};
            }
            if (font.getKey("/Encoding").isDictionary()
                    ? font.getKey("/Encoding")
                          .getKey("/BaseEncoding")
                          .isNameAndEquals("/MacExpertEncoding")
                    : font.getKey("/Encoding").isNameAndEquals("/MacExpertEncoding")) {
                if (onlyMissing) continue;
                return {nullptr,
                        "MacExpertEncoding fonts cannot be substituted safely: " + fontName, -4};
            }
            auto descriptor = target.getKey("/FontDescriptor");
            bool hasProgram = descriptor.isDictionary() &&
                              (descriptor.hasKey("/FontFile") || descriptor.hasKey("/FontFile2") ||
                               descriptor.hasKey("/FontFile3"));
            if (hasProgram && onlyMissing) continue;
            if (descriptor.isIndirect()) {
                // A descriptor can be shared by a font that is not being
                // replaced. Always isolate it before installing a program so
                // the other font keeps its original glyphs.
                descriptor = qpdf->makeIndirectObject(
                    QPDFObjectHandle::newDictionary(descriptor.getDictAsMap()));
                for (char const* key : {"/FontFile", "/FontFile2", "/FontFile3"})
                    descriptor.removeKey(key);
                descriptor.replaceKey("/FontName", target.getKey("/BaseFont"));
                target.replaceKey("/FontDescriptor", descriptor);
            }
            auto flags =
                descriptor.isDictionary() ? descriptor.getKey("/Flags") : QPDFObjectHandle();
            bool symbolic = flags.isInteger()
                                ? (flags.getIntValue() & 4) != 0
                                : targetName == "Symbol" || targetName == "ZapfDingbats";
            if (!descriptor.isDictionary()) {
                descriptor = newDescriptor(*qpdf, target, program.face, symbolic);
                target.replaceKey("/FontDescriptor", descriptor);
            }
            if (!cid && !target.getKey("/Widths").isArray())
                addWidths(target, program.face, symbolic);
            std::set<FT_UInt> glyphs;
            std::string subset;
            if (!hasProgram && !cid && !symbolic && !program.noSubsetting &&
                program.kind != ProgramKind::BareCff && program.kind != ProgramKind::Type1) {
                auto enc = parseEncoding(target);
                bool resolvable = std::ranges::all_of(enc.differences, [&](auto const& entry) {
                    return entry.second == ".notdef" || glyphForName(program.face, entry.second);
                });
                for (int code = 0; resolvable && code < 256; ++code) {
                    glyphs.insert(glyphForCode(program.face, code, false, enc));
                    glyphs.insert(glyphForCode(program.face, code, true, enc));
                    if (FT_Select_Charmap(program.face, FT_ENCODING_UNICODE)) continue;
                    for (auto base :
                         {BaseEncoding::Standard, BaseEncoding::WinAnsi, BaseEncoding::MacRoman})
                        glyphs.insert(
                            FT_Get_Char_Index(program.face, baseEncodingUnicode(base, code)));
                }
                if (resolvable) subset = subsetProgram(program, glyphs);
                if (!reachesSameGlyphs(lib, subset, program.face, enc)) subset.clear();
                if (subset.empty()) glyphs.clear();
            }
            std::string current = subtype.isName() ? subtype.getName() : "";
            auto wrapper = descriptor.getKey("/FontFile3");
            bool wrapped = wrapper.isStream() &&
                           wrapper.getDict().getKey("/Subtype").isNameAndEquals("/OpenType");
            auto kind = program.kind == ProgramKind::TrueType && wrapped ? ProgramKind::OpenType
                                                                         : program.kind;
            bool glyf = program.kind == ProgramKind::TrueType;
            bool trueTypeFont = current == "/TrueType" || current == "/CIDFontType2";
            if (glyf != trueTypeFont) {
                target.replaceKey("/Subtype", QPDFObjectHandle::newName(
                                                  cid ? (glyf ? "/CIDFontType2" : "/CIDFontType0")
                                                      : (glyf ? "/TrueType" : "/Type1")));
                if (cid && glyf && !target.hasKey("/CIDToGIDMap"))
                    target.replaceKey("/CIDToGIDMap", QPDFObjectHandle::newName("/Identity"));
                if (!cid && glyf) pinTrueTypeMapping(*qpdf, target, program.face, symbolic);
            }
            auto gidMap = target.getKey("/CIDToGIDMap");
            if (cid && glyf && !hasProgram &&
                (font.getKey("/Encoding").isNameAndEquals("/Identity-H") ||
                 font.getKey("/Encoding").isNameAndEquals("/Identity-V")) &&
                (gidMap.isNull() || gidMap.isNameAndEquals("/Identity"))) {
                std::map<uint32_t, FT_ULong> codes;
                for (auto [gid, u] : unicodeByGlyph(program.face, symbolic)) codes[gid] = u;
                setToUnicode(*qpdf, font, codes, true);
            }
            auto& stream = program.streams[{kind, cid, glyphs}];
            if (!stream.isInitialized())
                stream = makeProgramStream(*qpdf, program, kind, cid, subset);
            if (!glyphs.empty() && !(targetName.size() > 7 && targetName[6] == '+')) {
                size_t hash = std::hash<std::string>{}(targetName);
                for (FT_UInt g : glyphs) hash = hash * 31 + g;
                std::string tag;
                for (int i = 0; i < 6; ++i, hash /= 26) tag += char('A' + hash % 26);
                auto tagged = QPDFObjectHandle::newName("/" + tag + "+" + targetName);
                target.replaceKey("/BaseFont", tagged);
                descriptor.replaceKey("/FontName", tagged);
            }
            for (char const* key : {"/FontFile", "/FontFile2", "/FontFile3"})
                descriptor.removeKey(key);
            descriptor.replaceKey(kind == ProgramKind::TrueType ? "/FontFile2"
                                  : kind == ProgramKind::Type1  ? "/FontFile"
                                                                : "/FontFile3",
                                  stream);
            openType |= kind == ProgramKind::OpenType;
            ++embedded;
        }
        if (embedded == 0) return {nullptr, "no matching font", 1};

        QPDFWriter writer{*qpdf};
        writer.setCompressStreams(true);
        writer.setDecodeLevel(qpdf_dl_none);
        if (openType) writer.setMinimumPDFVersion("1.6");
        if (out) {
            writer.setOutputFile("font-out", out, false);
        } else {
            writer.setOutputMemory();
        }
        WriterLock lock{-1};
        writer.write();
        return {out ? nullptr : writer.getBufferSharedPointer(), "", 0};
    } catch (QPDFExc const& e) {
        if (e.getErrorCode() == qpdf_e_password) return {nullptr, "password protected", -2};
        return {nullptr, e.what(), -1};
    } catch (std::exception const& e) {
        return {nullptr, e.what(), -1};
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

JPDFIUM_EXPORT int32_t jpdfium_qpdf_embed_fonts(const uint8_t* input, int64_t inputLen,
                                                int32_t count, const char* const* names,
                                                const uint8_t* const* fonts,
                                                const int64_t* fontLens, int32_t onlyMissing,
                                                uint8_t** output, int64_t* outputLen) {
    try {
        if (!input || inputLen <= 0 || count <= 0 || !names || !fonts || !fontLens || !output ||
            !outputLen) {
            return -1;
        }
        *output = nullptr;
        *outputLen = 0;
        std::vector<FontSource> sources;
        for (int32_t i = 0; i < count; ++i) {
            if (!names[i] || !*names[i] || !fonts[i] || fontLens[i] <= 0) return -1;
            sources.push_back({names[i], {fonts[i], static_cast<size_t>(fontLens[i])}});
        }
        auto result = embedFonts(
            [&](QPDF& qpdf) {
                qpdf.processMemoryFile("font-in", reinterpret_cast<const char*>(input),
                                       static_cast<size_t>(inputLen));
            },
            sources, onlyMissing != 0);
        if (result.rc != 0) {
            std::fprintf(stderr, "jpdfium qpdf embed fonts: %s\n", result.error.c_str());
            return result.rc;
        }
        *outputLen = static_cast<int64_t>(result.buffer->getSize());
        *output = static_cast<uint8_t*>(malloc(static_cast<size_t>(*outputLen)));
        if (!*output) {
            *outputLen = 0;
            return -1;
        }
        std::memcpy(*output, result.buffer->getBuffer(), static_cast<size_t>(*outputLen));
        return 0;
    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_embed_fonts_file(const char* in_path, const char* out_path,
                                                     int32_t count, const char* const* names,
                                                     const uint8_t* const* fonts,
                                                     const int64_t* fontLens, int32_t onlyMissing) {
    try {
        if (!in_path || !out_path || count <= 0 || !names || !fonts || !fontLens) return -1;
        std::vector<FontSource> sources;
        for (int32_t i = 0; i < count; ++i) {
            if (!names[i] || !*names[i] || !fonts[i] || fontLens[i] <= 0) return -1;
            sources.push_back({names[i], {fonts[i], static_cast<size_t>(fontLens[i])}});
        }
        FILE* out = createOutputFile(out_path);
        if (!out) return -1;
        auto result = embedFonts([&](QPDF& qpdf) { qpdf.processFile(in_path); }, sources,
                                 onlyMissing != 0, out);
        const bool closed = std::fclose(out) == 0;
        if (result.rc != 0) {
            std::fprintf(stderr, "jpdfium qpdf embed fonts file: %s\n", result.error.c_str());
            return result.rc;
        }
        return closed ? 0 : -1;
    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_replace_font_stream(const uint8_t* input, int64_t inputLen,
                                                        const char* baseFont,
                                                        const uint8_t* fontData, int64_t fontLen,
                                                        uint8_t** output, int64_t* outputLen) {
    if (!baseFont || !*baseFont) return -1;
    const char* names[1] = {baseFont};
    const uint8_t* fonts[1] = {fontData};
    const int64_t lens[1] = {fontLen};
    return jpdfium_qpdf_embed_fonts(input, inputLen, 1, names, fonts, lens, 0, output, outputLen);
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
    try {
        return forceVersionFile(in_path, out_path, version);
    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_force_version(const uint8_t* input, int64_t inputLen,
                                                  const char* version, uint8_t** output,
                                                  int64_t* outputLen) {
    if (!input || inputLen <= 0 || !version || !*version || !output || !outputLen) return -1;
    *output = nullptr;
    *outputLen = 0;
    try {
        auto pdf = QPDF::create();
        pdf->processMemoryFile("jpdfium-mem", reinterpret_cast<const char*>(input),
                               static_cast<size_t>(inputLen));
        pdf->getRoot().removeKey("/Version");
        QPDFWriter writer{*pdf};
        writer.setOutputMemory();
        writer.forcePDFVersion(version);
        WriterLock lock{-1};
        writer.write();
        auto buf = writer.getBufferSharedPointer();
        if (!buf || buf->getSize() == 0) return -1;
        *outputLen = static_cast<int64_t>(buf->getSize());
        *output = static_cast<uint8_t*>(std::malloc(static_cast<size_t>(*outputLen)));
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

JPDFIUM_EXPORT int32_t jpdfium_qpdf_extract_ranges_files(const char* in_path,
                                                         const int32_t* firstPages,
                                                         const int32_t* lastPages,
                                                         int32_t rangeCount,
                                                         const char* const* out_paths) {
    if (!in_path || !firstPages || !lastPages || rangeCount <= 0 || !out_paths) return -1;
    std::shared_ptr<QPDF> src;
    try {
        src = QPDF::create();
        src->processFile(in_path);
    } catch (const std::exception& e) {
        std::fprintf(stderr, "jpdfium qpdf extract ranges: %s\n", e.what());
        return -1;
    } catch (...) {
        std::fprintf(stderr, "jpdfium qpdf extract ranges: unknown error\n");
        return -1;
    }
    int32_t written = 0;
    try {
        std::vector<QPDFObjectHandle> srcPages = src->getAllPages();
        RangeSource source{*src, srcPages, QPDFAcroFormDocumentHelper{*src},
                           QPDFPageLabelDocumentHelper{*src}};
        for (; written < rangeCount; ++written) {
            if (!out_paths[written] || writeRange(source, firstPages[written], lastPages[written],
                                                  out_paths[written]) != 0) {
                break;
            }
        }
    } catch (const std::exception& e) {
        std::fprintf(stderr, "jpdfium qpdf extract ranges: %s\n", e.what());
    } catch (...) {
        std::fprintf(stderr, "jpdfium qpdf extract ranges: unknown error\n");
    }
    return written;
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
            w.setCompressStreams(false);
            w.setDecodeLevel(qpdf_dl_none);
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

JPDFIUM_EXPORT int32_t jpdfium_qpdf_extract_ranges_files(const char*, const int32_t*,
                                                         const int32_t*, int32_t,
                                                         const char* const*) {
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
