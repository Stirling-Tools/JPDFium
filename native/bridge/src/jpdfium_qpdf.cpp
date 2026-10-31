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
#include <span>
#include <string>
#include <vector>

#ifndef _WIN32
#include <fcntl.h>
#include <unistd.h>
#endif

#include "jpdfium.h"

#ifdef JPDFIUM_HAS_QPDF

#include <qpdf/Constants.h>

#include <qpdf/Buffer.hh>
#include <qpdf/QPDF.hh>
#include <qpdf/QPDFAcroFormDocumentHelper.hh>
#include <qpdf/QPDFExc.hh>
#include <qpdf/QPDFPageLabelDocumentHelper.hh>
#include <qpdf/QPDFPageObjectHelper.hh>
#include <qpdf/QPDFWriter.hh>
#include <qpdf/QUtil.hh>

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

// compressionLevel is intentionally ignored. The only qpdf knob for the zlib
// level, Pl_Flate::setCompressionLevel, mutates process-global state shared by
// every Pl_Flate instance in deflate mode -- not a per-QPDFWriter setting.
// Wiring it here would let concurrent requests with different levels silently
// clobber one another. The parameter is retained purely for ABI stability;
// revisit only if a per-instance alternative appears in qpdf.
// Apply the writer configuration. Shared by the memory and file paths so the
// two cannot drift apart in which flags they honour.
void configureWriter(QPDFWriter& w, int32_t flags, int32_t objectStreamMode, int32_t streamDataMode,
                     int32_t decodeLevel) {
    if (flags & JPDFIUM_QPDF_LINEARIZE) w.setLinearization(true);
    if (flags & JPDFIUM_QPDF_RECOMPRESS_FLATE) w.setRecompressFlate(true);
    if (flags & JPDFIUM_QPDF_COMPRESS_STREAMS) w.setCompressStreams(true);
    if (flags & JPDFIUM_QPDF_PRESERVE_UNREFERENCED) w.setPreserveUnreferencedObjects(true);
    if (flags & JPDFIUM_QPDF_NORMALIZE_CONTENT) w.setContentNormalization(true);
    if (objectStreamMode >= 0)
        w.setObjectStreamMode(static_cast<qpdf_object_stream_e>(objectStreamMode));
    if (streamDataMode >= 0) w.setStreamDataMode(static_cast<qpdf_stream_data_e>(streamDataMode));
    if (decodeLevel >= 0) w.setDecodeLevel(static_cast<qpdf_stream_decode_level_e>(decodeLevel));
}

// Write an already-parsed document straight to a file. QPDFWriter takes
// ownership of the FILE* once setOutputFile returns, so an exception before
// that point closes it here rather than leaking the descriptor.
int writeToFile(const std::shared_ptr<QPDF>& qpdf, const char* out_path, int32_t flags,
                int32_t objectStreamMode, int32_t streamDataMode, int32_t decodeLevel) {
    FILE* out = createOutputFile(out_path);
    if (!out) return -1;
    bool writerOwnsFile = false;
    try {
        QPDFWriter w{*qpdf};
        // Encryption is deliberately preserved here: optimize, merge, and
        // extract are round-trip operations, and silently dropping the
        // source encryption would change the document's protection.
        w.setOutputFile("jpdfium-out", out, true);
        writerOwnsFile = true;
        configureWriter(w, flags, objectStreamMode, streamDataMode, decodeLevel);
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

QpdfResult optimize(std::span<const uint8_t> input, int32_t flags, int32_t objectStreamMode,
                    int32_t streamDataMode, int32_t decodeLevel) {
    try {
        auto qpdf = QPDF::create();
        qpdf->processMemoryFile("jpdfium-input", reinterpret_cast<const char*>(input.data()),
                                input.size());

        QPDFWriter w{*qpdf};
        w.setOutputMemory();
        configureWriter(w, flags, objectStreamMode, streamDataMode, decodeLevel);
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

        w.write();
        return {w.getBufferSharedPointer(), ""};
    } catch (const std::exception& e) {
        return {nullptr, e.what()};
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
        w.write();
        return {w.getBufferSharedPointer(), ""};
    } catch (const std::exception& e) {
        return {nullptr, e.what()};
    }
}

}  // namespace

extern "C" {

JPDFIUM_EXPORT int32_t jpdfium_qpdf_optimize(const uint8_t* input, int64_t inputLen,
                                             uint8_t** output, int64_t* outputLen, int32_t flags,
                                             int32_t /*compressionLevel*/, int32_t objectStreamMode,
                                             int32_t streamDataMode, int32_t decodeLevel) {
    try {
        if (!input || inputLen <= 0 || !output || !outputLen) return -1;
        *output = nullptr;
        *outputLen = 0;

        auto result = optimize({input, static_cast<size_t>(inputLen)}, flags, objectStreamMode,
                               streamDataMode, decodeLevel);
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
JPDFIUM_EXPORT int32_t jpdfium_qpdf_optimize_file(const char* in_path, const char* out_path,
                                                  int32_t flags, int32_t objectStreamMode,
                                                  int32_t streamDataMode, int32_t decodeLevel) {
    if (!in_path || !*in_path || !out_path || !*out_path) return -1;
    try {
        auto qpdf = QPDF::create();
        qpdf->processFile(in_path);
        return writeToFile(qpdf, out_path, flags, objectStreamMode, streamDataMode, decodeLevel);
    } catch (const std::exception& e) {
        std::fprintf(stderr, "jpdfium qpdf optimize file: %s\n", e.what());
        return -1;
    } catch (...) {
        std::fprintf(stderr, "jpdfium qpdf optimize file: unknown error\n");
        return -1;
    }
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

JPDFIUM_EXPORT int32_t jpdfium_qpdf_optimize(const uint8_t* input, int64_t inputLen,
                                             uint8_t** output, int64_t* outputLen, int32_t, int32_t,
                                             int32_t, int32_t, int32_t) {
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

JPDFIUM_EXPORT int32_t jpdfium_qpdf_optimize_file(const char*, const char*, int32_t, int32_t,
                                                  int32_t, int32_t) {
    return -1;
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
