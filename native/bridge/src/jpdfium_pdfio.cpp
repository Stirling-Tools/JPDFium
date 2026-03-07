// jpdfium_pdfio.cpp — PDFio-based third-opinion structural repair.
//
// Opt-in: requires JPDFIUM_HAS_PDFIO at build time.
// PDFio has its own independent XRef repair implementation (repair_xref),
// giving a third parse opinion after PDFium and qpdf.

#include "jpdfium.h"
#include <cstdlib>
#include <cstring>
#include <cstdio>

#ifdef JPDFIUM_HAS_PDFIO

#include <pdfio.h>

// Error callback — captures error message without aborting
static bool pdfio_error_cb(pdfio_file_t*, const char* message, void* data) {
    char* errBuf = static_cast<char*>(data);
    if (errBuf && message) {
        strncpy(errBuf, message, 511);
        errBuf[511] = '\0';
    }
    return false; // stop on fatal
}

extern "C" {

JPDFIUM_EXPORT int32_t jpdfium_pdfio_try_repair(
    const uint8_t* input, int64_t inputLen,
    uint8_t** output, int64_t* outputLen,
    int32_t* pagesRecovered) {

    if (!input || inputLen <= 0 || !output || !outputLen || !pagesRecovered)
        return JPDFIUM_REPAIR_FAILED;

    *pagesRecovered = 0;

    // PDFio requires file paths — write to temp files
    char tmpIn[]  = "/tmp/jpdfium_pdfio_in_XXXXXX";
    char tmpOut[] = "/tmp/jpdfium_pdfio_out_XXXXXX";

    int fdIn = mkstemp(tmpIn);
    if (fdIn < 0) return JPDFIUM_REPAIR_FAILED;

    ssize_t written = write(fdIn, input, static_cast<size_t>(inputLen));
    close(fdIn);
    if (written != inputLen) {
        unlink(tmpIn);
        return JPDFIUM_REPAIR_FAILED;
    }

    int fdOut = mkstemp(tmpOut);
    if (fdOut < 0) {
        unlink(tmpIn);
        return JPDFIUM_REPAIR_FAILED;
    }
    close(fdOut);

    // PDFio internally calls load_xref, falling back to repair_xref
    char errBuf[512] = {0};
    pdfio_file_t* inPdf = pdfioFileOpen(
        tmpIn, /*password_cb*/nullptr, /*password_data*/nullptr,
        pdfio_error_cb, errBuf);

    if (!inPdf) {
        unlink(tmpIn);
        unlink(tmpOut);
        return JPDFIUM_REPAIR_FAILED;
    }

    size_t numPages = pdfioFileGetNumPages(inPdf);
    if (numPages == 0) {
        pdfioFileClose(inPdf);
        unlink(tmpIn);
        unlink(tmpOut);
        return JPDFIUM_REPAIR_FAILED;
    }

    // Create clean output PDF by copying pages one by one
    pdfio_file_t* outPdf = pdfioFileCreate(
        tmpOut, /*version*/"1.7",
        /*media_box*/nullptr, /*crop_box*/nullptr,
        pdfio_error_cb, errBuf);

    if (!outPdf) {
        pdfioFileClose(inPdf);
        unlink(tmpIn);
        unlink(tmpOut);
        return JPDFIUM_REPAIR_FAILED;
    }

    for (size_t i = 0; i < numPages; i++) {
        pdfio_obj_t* page = pdfioFileGetPage(inPdf, i);
        if (page && pdfioPageCopy(outPdf, page)) {
            (*pagesRecovered)++;
        }
        // Skip unrecoverable pages — partial salvage
    }

    pdfioFileClose(outPdf);
    pdfioFileClose(inPdf);

    if (*pagesRecovered == 0) {
        unlink(tmpIn);
        unlink(tmpOut);
        return JPDFIUM_REPAIR_FAILED;
    }

    // Read output file into memory
    FILE* f = fopen(tmpOut, "rb");
    if (!f) {
        unlink(tmpIn);
        unlink(tmpOut);
        return JPDFIUM_REPAIR_FAILED;
    }

    fseek(f, 0, SEEK_END);
    long sz = ftell(f);
    fseek(f, 0, SEEK_SET);

    *output = static_cast<uint8_t*>(malloc(static_cast<size_t>(sz)));
    *outputLen = static_cast<int64_t>(fread(*output, 1, static_cast<size_t>(sz), f));
    fclose(f);

    unlink(tmpIn);
    unlink(tmpOut);

    int32_t status = (*pagesRecovered == static_cast<int32_t>(numPages))
        ? JPDFIUM_REPAIR_FIXED
        : JPDFIUM_REPAIR_PARTIAL;
    return status;
}

} // extern "C"

#else // !JPDFIUM_HAS_PDFIO

extern "C" {

JPDFIUM_EXPORT int32_t jpdfium_pdfio_try_repair(
    const uint8_t*, int64_t, uint8_t**, int64_t*, int32_t*) {
    return JPDFIUM_ERR_NATIVE;
}

} // extern "C"

#endif // JPDFIUM_HAS_PDFIO
