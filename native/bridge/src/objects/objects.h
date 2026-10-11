// objects.h - concrete objects behind jpdf_handle tokens.
#pragma once

#include <fpdfview.h>

#include <cstdint>
#include <memory>
#include <utility>

#include "images/pixels.h"
#include "object_table.h"
#include "runtime/api_status.h"

namespace jpdf {

// PDFium objects are created and destroyed only while admitted; destructors never call PDFium.
struct DocumentObject final : Object {
    DocumentObject() noexcept : Object(ObjectType::Document) {}
    FPDF_DOCUMENT doc = nullptr;
    int64_t open_pages = 0;  // guarded by the domain
};

struct PageObject final : Object {
    PageObject(std::shared_ptr<DocumentObject> parent, int32_t page_index) noexcept
        : Object(ObjectType::Page), index(page_index), document(std::move(parent)) {}
    FPDF_PAGE page = nullptr;
    const int32_t index;
    const std::shared_ptr<DocumentObject> document;  // the child retains its parent
};

struct BitmapObject final : Object {
    explicit BitmapObject(std::shared_ptr<const PixelStorage> p) noexcept
        : Object(ObjectType::Bitmap), pixels(std::move(p)) {}
    const std::shared_ptr<const PixelStorage> pixels;
};

// Pins pixels for a borrowed pointer; outlives the bitmap handle that produced it.
struct ViewObject final : Object {
    explicit ViewObject(std::shared_ptr<const PixelStorage> p) noexcept
        : Object(ObjectType::View), pixels(std::move(p)) {}
    const std::shared_ptr<const PixelStorage> pixels;
};

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
