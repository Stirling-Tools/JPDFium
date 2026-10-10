// pdf_support.h - PDFium error mapping and RAII owners shared by the jpdf_* PDF entry points.
#pragma once

#include <fpdfview.h>

#include "jpdfium_api.h"
#include "runtime/api_status.h"

namespace jpdf {

// Maps FPDF_GetLastError after a failed load; call while still admitted.
inline jpdf_status pdfium_open_failure() noexcept {
    switch (FPDF_GetLastError()) {
        case FPDF_ERR_FILE:
            return fail(JPDF_E_IO, "file not found or could not be read");
        case FPDF_ERR_FORMAT:
            return fail(JPDF_E_FORMAT, "not a PDF or corrupted");
        case FPDF_ERR_PASSWORD:
            return fail(JPDF_E_PASSWORD, "password required or incorrect");
        case FPDF_ERR_SECURITY:
            return fail(JPDF_E_UNSUPPORTED, "unsupported security handler");
        default:
            return fail(JPDF_E_FORMAT, "PDFium could not load the document");
    }
}

// Closes a document unless released; only destroyed while admitted.
struct LoadedDocument {
    FPDF_DOCUMENT doc;
    ~LoadedDocument() {
        if (doc) FPDF_CloseDocument(doc);
    }
};

struct LoadedPage {
    FPDF_PAGE page;
    ~LoadedPage() {
        if (page) FPDF_ClosePage(page);
    }
};

}  // namespace jpdf
