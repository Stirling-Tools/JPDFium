// jpdfium_pdfio.cpp - PDFio-based third-opinion structural repair.
//
// Opt-in: requires JPDFIUM_HAS_PDFIO at build time.
// PDFio has its own independent XRef repair implementation (repair_xref),
// giving a third parse opinion after PDFium and qpdf.

#include <climits>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <type_traits>

#include "jpdfium.h"
#include "jpdfium_internal.h"

#ifdef JPDFIUM_HAS_PDFIO

#include <pdfio.h>

// Error callback - captures error message without aborting
static bool pdfio_error_cb(pdfio_file_t*, const char* message, void* data) {
    char* errBuf = static_cast<char*>(data);
    if (errBuf && message) {
        strncpy(errBuf, message, 511);
        errBuf[511] = '\0';
    }
    return false;  // stop on fatal
}

namespace {

// PDFio file handle owner: the only thing that must survive an exception here.
struct PdfFileCloser {
    void operator()(pdfio_file_t* f) const noexcept {
        if (f) pdfioFileClose(f);
    }
};

using UniquePdfFile = std::unique_ptr<std::remove_pointer_t<pdfio_file_t>, PdfFileCloser>;

// Upper bound on the bytes a repair may read back out of its temp file.
constexpr unsigned long kMaxRepairOutputBytes = 1UL << 32;  // 4 GiB

}  // namespace

extern "C" {

JPDFIUM_EXPORT int32_t jpdfium_pdfio_try_repair(const uint8_t* input, int64_t inputLen,
                                                uint8_t** output, int64_t* outputLen,
                                                int32_t* pagesRecovered) {
    try {
        if (!input || inputLen <= 0 || !output || !outputLen || !pagesRecovered)
            return JPDFIUM_REPAIR_FAILED;

        *pagesRecovered = 0;

        // PDFio requires file paths. Both temp files hold untrusted input, so
        // each name is owned the moment it exists and released on every exit,
        // including an exception thrown between creation and release.
        char tmpIn[] = "/tmp/jpdfium_pdfio_in_XXXXXX";
        int fdIn = mkstemp(tmpIn);
        if (fdIn < 0) return JPDFIUM_REPAIR_FAILED;
        ScopedUnlink unlinkIn(tmpIn);

        ssize_t written = write(fdIn, input, static_cast<size_t>(inputLen));
        close(fdIn);
        if (written != static_cast<ssize_t>(inputLen)) return JPDFIUM_REPAIR_FAILED;

        char tmpOut[] = "/tmp/jpdfium_pdfio_out_XXXXXX";
        int fdOut = mkstemp(tmpOut);
        if (fdOut < 0) return JPDFIUM_REPAIR_FAILED;
        close(fdOut);
        ScopedUnlink unlinkOut(tmpOut);

        // PDFio internally calls load_xref, falling back to repair_xref.
        char errBuf[512] = {0};
        UniquePdfFile pdf(pdfioFileOpen(tmpIn, /*password_cb*/ nullptr,
                                        /*password_data*/ nullptr, pdfio_error_cb, errBuf));
        if (!pdf) return JPDFIUM_REPAIR_FAILED;

        const size_t numPages = pdfioFileGetNumPages(pdf.get());
        if (numPages == 0) return JPDFIUM_REPAIR_FAILED;

        // Create clean output PDF by copying pages one by one.
        UniquePdfFile outPdf(pdfioFileCreate(tmpOut, /*version*/ "1.7",
                                             /*media_box*/ nullptr, /*crop_box*/ nullptr,
                                             pdfio_error_cb, errBuf));
        if (!outPdf) return JPDFIUM_REPAIR_FAILED;

        size_t recovered = 0;
        for (size_t i = 0; i < numPages; i++) {
            pdfio_obj_t* page = pdfioFileGetPage(pdf.get(), i);
            if (page && pdfioPageCopy(outPdf.get(), page)) {
                recovered++;
            }
            // Skip unrecoverable pages - partial salvage
        }
        if (recovered == 0) return JPDFIUM_REPAIR_FAILED;
        if (recovered > static_cast<size_t>(INT32_MAX)) return JPDFIUM_REPAIR_FAILED;

        // RAII is a fallback, not a finalisation step: pdfioFileClose() writes the
        // cross-reference table and trailer and reports whether that succeeded.
        // Release first so the checked close below is the only one, and only
        // read the file once the output is complete and finalised.
        if (!pdfioFileClose(outPdf.release())) return JPDFIUM_REPAIR_FAILED;
        pdfioFileClose(pdf.release());

        UniqueFile f(std::fopen(tmpOut, "rb"));
        if (!f) return JPDFIUM_REPAIR_FAILED;
        if (std::fseek(f.get(), 0, SEEK_END) != 0) return JPDFIUM_REPAIR_FAILED;
        const long endPos = std::ftell(f.get());
        if (endPos <= 0) return JPDFIUM_REPAIR_FAILED;  // an empty file is not a repair
        if (static_cast<unsigned long>(endPos) > kMaxRepairOutputBytes)
            return JPDFIUM_REPAIR_FAILED;
        if (std::fseek(f.get(), 0, SEEK_SET) != 0) return JPDFIUM_REPAIR_FAILED;

        const size_t want = static_cast<size_t>(endPos);
        uint8_t* bytes = static_cast<uint8_t*>(malloc(want));
        if (!bytes) return JPDFIUM_ERR_NATIVE;
        if (std::fread(bytes, 1, want, f.get()) != want) {
            free(bytes);
            return JPDFIUM_REPAIR_FAILED;
        }

        // Publish every output only now that the operation has succeeded.
        *output = bytes;
        *outputLen = static_cast<int64_t>(want);
        *pagesRecovered = static_cast<int32_t>(recovered);
        return recovered == numPages ? JPDFIUM_REPAIR_FIXED : JPDFIUM_REPAIR_PARTIAL;
    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

}  // extern "C"

#else  // !JPDFIUM_HAS_PDFIO

extern "C" {

JPDFIUM_EXPORT int32_t jpdfium_pdfio_try_repair(const uint8_t*, int64_t, uint8_t**, int64_t*,
                                                int32_t*) {
    return JPDFIUM_ERR_NATIVE;
}

}  // extern "C"

#endif  // JPDFIUM_HAS_PDFIO
