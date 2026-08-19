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
#include <expected>
#include <map>
#include <memory>
#include <span>
#include <vector>

#include "jpdfium.h"

#ifdef JPDFIUM_HAS_QPDF

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

std::expected<std::shared_ptr<Buffer>, std::string> sanitize(std::span<const uint8_t> input,
                                                             int32_t flags) {
    try {
        auto qpdf = QPDF::create();
        qpdf->processMemoryFile("jpdfium-sanitize-in", reinterpret_cast<const char*>(input.data()),
                                input.size());

        auto root = qpdf->getRoot();

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
            QPDFPageDocumentHelper pdh(*qpdf);
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
            QPDFEmbeddedFileDocumentHelper efdh(*qpdf);
            for (auto const& [name, spec] : efdh.getEmbeddedFiles()) {
                efdh.removeEmbeddedFile(name);
            }
        }

        // AcroForm: drop the catalog pointer and strip widget annotations.
        if (flags & JPDFIUM_SANITIZE_ACROFORM) {
            root.removeKey("/AcroForm");
            QPDFAcroFormDocumentHelper afdh(*qpdf);
            QPDFPageDocumentHelper pdh(*qpdf);
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
            QPDFPageDocumentHelper pdh(*qpdf);
            pdh.flattenAnnotations();
        }

        // Metadata / Info stripping happens just before write.
        if (flags & JPDFIUM_SANITIZE_METADATA) {
            root.removeKey("/Metadata");
        }
        if (flags & JPDFIUM_SANITIZE_INFO) {
            qpdf->getTrailer().removeKey("/Info");
        }

        QPDFWriter w(*qpdf);
        w.setOutputMemory();
        w.write();

        return w.getBufferSharedPointer();
    } catch (const std::exception& e) {
        return std::unexpected(e.what());
    }
}

}  // namespace

extern "C" {

JPDFIUM_EXPORT int32_t jpdfium_qpdf_sanitize(const uint8_t* input, int64_t inputLen,
                                             uint8_t** output, int64_t* outputLen, int32_t flags) {
    if (!input || inputLen <= 0 || !output || !outputLen) return -1;
    *output = nullptr;
    *outputLen = 0;

    auto result = sanitize({input, static_cast<size_t>(inputLen)}, flags);
    if (!result) {
        std::fprintf(stderr, "jpdfium qpdf sanitize: %s\n", result.error().c_str());
        return -1;
    }

    auto& buf = *result;
    *outputLen = static_cast<int64_t>(buf->getSize());
    *output = static_cast<uint8_t*>(malloc(static_cast<size_t>(*outputLen)));
    if (!*output) {
        *outputLen = 0;
        return -1;
    }
    std::memcpy(*output, buf->getBuffer(), static_cast<size_t>(*outputLen));
    return 0;
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

}  // extern "C"

#endif  // JPDFIUM_HAS_QPDF
