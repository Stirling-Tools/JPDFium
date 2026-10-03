// jpdfium_qpdf_sanitize.cpp - In-process qpdf structural sanitization (FFM, no CLI).
//
// jpdfium_qpdf_sanitize drives the bundled qpdf library entirely in memory via
// its QPDF/QPDFWriter C++ API. Scrubs metadata/info/structure, JavaScript
// actions, embedded files, AcroForm widgets, and flattens annotations. Visual
// redaction of content streams is handled by the pdfium side; this pass cleans
// up the structural copies it leaves behind.

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <memory>
#include <span>
#include <string>
#include <vector>

#include "jpdfium.h"

#ifdef JPDFIUM_HAS_QPDF

#ifndef _WIN32
#include <fcntl.h>
#include <unistd.h>
#endif

#include <qpdf/Constants.h>

#include <qpdf/Buffer.hh>
#include <qpdf/QPDF.hh>
#include <qpdf/QPDFAcroFormDocumentHelper.hh>
#include <qpdf/QPDFEmbeddedFileDocumentHelper.hh>
#include <qpdf/QPDFExc.hh>
#include <qpdf/QPDFObjectHandle.hh>
#include <qpdf/QPDFPageDocumentHelper.hh>
#include <qpdf/QPDFWriter.hh>

namespace {

struct QpdfResult {
    std::shared_ptr<Buffer> buffer;
    std::string error;
    bool ok() const {
        return buffer != nullptr;
    }
};

// Single copy of the sanitize mutations so memory and file paths cannot
// drift (a scrub rule added to only one path would leave JS/metadata in the
// other path's output).
static void applySanitize(QPDF& qpdf, int32_t flags) {
    auto root = qpdf.getRoot();

    // JavaScript / action removal. Scrub both the catalog and every
    // page/annotation: a document can carry executable JS in Page /AA
    // (or annotation /AA) with a completely clean catalog.
    if (flags & JPDFIUM_SANITIZE_JAVASCRIPT) {
        root.removeKey("/OpenAction");
        root.removeKey("/AA");
        if (root.hasKey("/Names")) {
            auto names = root.getKey("/Names");
            if (names.hasKey("/JavaScript")) {
                names.removeKey("/JavaScript");
            }
        }
        QPDFPageDocumentHelper pdh(qpdf);
        for (auto& page : pdh.getAllPages()) {
            page.getObjectHandle().removeKey("/AA");
            for (auto& ann : page.getAnnotations()) {
                ann.getObjectHandle().removeKey("/AA");
            }
        }
    }

    // Tagged-PDF structure tree
    if (flags & JPDFIUM_SANITIZE_STRUCTURE) {
        root.removeKey("/StructTreeRoot");
    }

    // Embedded files
    if (flags & JPDFIUM_SANITIZE_ATTACHMENTS) {
        QPDFEmbeddedFileDocumentHelper efdh(qpdf);
        for (auto const& [name, spec] : efdh.getEmbeddedFiles()) {
            efdh.removeEmbeddedFile(name);
        }
    }

    // AcroForm: drop the catalog pointer and strip widget annotations.
    if (flags & JPDFIUM_SANITIZE_ACROFORM) {
        root.removeKey("/AcroForm");
        QPDFPageDocumentHelper pdh(qpdf);
        for (auto& page : pdh.getAllPages()) {
            QPDFObjectHandle ph = page.getObjectHandle();
            if (!ph.hasKey("/Annots")) continue;
            QPDFObjectHandle annots = ph.getKey("/Annots");
            if (!annots.isArray()) continue;
            std::vector<QPDFObjectHandle> kept;
            int n = annots.getArrayNItems();
            for (int i = 0; i < n; ++i) {
                QPDFObjectHandle a = annots.getArrayItem(i);
                bool isWidget = a.isDictionaryOfType("/Annot", "/Widget");
                if (!isWidget) kept.push_back(a);
            }
            if (kept.empty()) {
                ph.removeKey("/Annots");
            } else {
                ph.replaceKey("/Annots", QPDFObjectHandle::newArray(kept));
            }
        }
    }

    // Annotation flattening (bakes appearances into the page content)
    if (flags & JPDFIUM_SANITIZE_FLATTEN) {
        QPDFPageDocumentHelper pdh(qpdf);
        pdh.flattenAnnotations();
    }

    // Metadata / Info stripping happens just before write.
    if (flags & JPDFIUM_SANITIZE_METADATA) {
        root.removeKey("/Metadata");
    }
    if (flags & JPDFIUM_SANITIZE_INFO) {
        qpdf.getTrailer().removeKey("/Info");
    }
}

QpdfResult sanitize(std::span<const uint8_t> input, int32_t flags) {
    try {
        auto qpdf = QPDF::create();
        qpdf->processMemoryFile("jpdfium-sanitize-in", reinterpret_cast<const char*>(input.data()),
                                input.size());

        applySanitize(*qpdf, flags);

        QPDFWriter w(*qpdf);
        w.setOutputMemory();
        w.write();

        return {w.getBufferSharedPointer(), ""};
    } catch (const std::exception& e) {
        return {nullptr, e.what()};
    }
}

// Shared file-backed sanitize body: processFile + same mutations + file output.
// Factored so memory and file paths cannot drift in which flags they honour.
static int sanitizeFile(const char* in_path, const char* out_path, int32_t flags) {
    if (!in_path || !*in_path || !out_path || !*out_path) return -1;
    try {
        auto qpdf = QPDF::create();
        qpdf->processFile(in_path);
        applySanitize(*qpdf, flags);

        FILE* out = nullptr;
#ifdef _WIN32
        out = std::fopen(out_path, "wb");
#else
        int fd = ::open(out_path, O_WRONLY | O_CREAT | O_TRUNC, 0600);
        if (fd >= 0) {
            out = ::fdopen(fd, "wb");
            if (!out) ::close(fd);
        }
#endif
        if (!out) return -1;
        bool writerOwnsFile = false;
        try {
            QPDFWriter w(*qpdf);
            w.setOutputFile("jpdfium-out", out, true);
            writerOwnsFile = true;
            w.write();
            return 0;
        } catch (const std::exception& e) {
            std::fprintf(stderr, "jpdfium qpdf sanitize file: %s\n", e.what());
            if (!writerOwnsFile) std::fclose(out);
            return -1;
        } catch (...) {
            std::fprintf(stderr, "jpdfium qpdf sanitize file: unknown error\n");
            if (!writerOwnsFile) std::fclose(out);
            return -1;
        }
    } catch (const std::exception& e) {
        std::fprintf(stderr, "jpdfium qpdf sanitize file: %s\n", e.what());
        return -1;
    } catch (...) {
        std::fprintf(stderr, "jpdfium qpdf sanitize file: unknown error\n");
        return -1;
    }
}

}  // namespace

extern "C" {

JPDFIUM_EXPORT int32_t jpdfium_qpdf_sanitize(const uint8_t* input, int64_t inputLen,
                                             uint8_t** output, int64_t* outputLen, int32_t flags) {
    try {
        if (!input || inputLen <= 0 || !output || !outputLen) return -1;
        *output = nullptr;
        *outputLen = 0;

        auto result = sanitize({input, static_cast<size_t>(inputLen)}, flags);
        if (!result.ok()) {
            std::fprintf(stderr, "jpdfium qpdf sanitize: %s\n", result.error.c_str());
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

JPDFIUM_EXPORT int32_t jpdfium_qpdf_sanitize_file(const char* in_path, const char* out_path,
                                                  int32_t flags) {
    return sanitizeFile(in_path, out_path, flags);
}

}  // extern "C"

#else  // !JPDFIUM_HAS_QPDF

// Stub when qpdf is not linked: sanitization cannot be performed, so signal
// failure loudly rather than pretending the document was cleaned.
extern "C" {

JPDFIUM_EXPORT int32_t jpdfium_qpdf_sanitize(const uint8_t* input, int64_t inputLen,
                                             uint8_t** output, int64_t* outputLen, int32_t) {
    if (output) *output = nullptr;
    if (outputLen) *outputLen = 0;
    (void)input;
    (void)inputLen;
    return -1;
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_sanitize_file(const char*, const char*, int32_t) {
    return -1;
}

}  // extern "C"

#endif  // JPDFIUM_HAS_QPDF
