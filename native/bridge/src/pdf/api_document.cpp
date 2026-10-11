// api_document.cpp - jpdf_* document and page lifecycle. Every PDFium call runs admitted.

#include <fpdf_edit.h>
#include <fpdfview.h>

#include <memory>

#include "jpdfium_api.h"
#include "objects/objects.h"
#include "runtime/api_status.h"
#include "runtime/domain.h"

namespace {

using jpdf::Admission;
using jpdf::LoadedDocument;
using jpdf::LoadedPage;
using jpdf::pdfium_open_failure;
using jpdf::Purpose;

}  // namespace

extern "C" {

jpdf_status jpdf_doc_open_file(const char* path_utf8, const jpdf_open_options* options,
                               jpdf_handle* out_doc) noexcept {
    return jpdf::guarded([&] {
        if (!path_utf8 || !*path_utf8 || !out_doc) {
            return jpdf::fail(JPDF_E_INVALID_ARGUMENT, "path and out_doc are required");
        }
        const char* password = nullptr;
        if (options) {
            jpdf_status st =
                jpdf::check_input_struct(options, options->struct_size, sizeof(jpdf_open_options),
                                         sizeof(jpdf_open_options), "jpdf_open_options");
            if (st != JPDF_OK) return st;
            if (options->flags != 0) {
                return jpdf::fail(JPDF_E_UNSUPPORTED, "jpdf_open_options.flags must be 0");
            }
            password = options->password;
        }
        Admission admission(Purpose::Ordinary);
        if (!admission.admitted()) return admission.status();

        LoadedDocument loaded{FPDF_LoadDocument(path_utf8, password)};
        if (!loaded.doc) return pdfium_open_failure();
        auto object = std::make_shared<jpdf::DocumentObject>();
        object->doc = loaded.doc;
        jpdf_handle token = 0;
        jpdf_status st = jpdf::objects().insert(object, &token);
        if (st != JPDF_OK) {
            object->doc = nullptr;  // LoadedDocument still owns it and closes it here
            return jpdf::fail(st, "object table rejected the document");
        }
        loaded.doc = nullptr;
        jpdf::Domain::instance().document_opened();
        *out_doc = token;
        return JPDF_OK;
    });
}

jpdf_status jpdf_doc_close(jpdf_handle doc) noexcept {
    return jpdf::guarded([&] {
        Admission admission(Purpose::Teardown);
        if (!admission.admitted()) return admission.status();
        std::shared_ptr<jpdf::DocumentObject> object;
        jpdf_status st = jpdf::objects().resolve_as(doc, jpdf::ObjectType::Document, &object);
        if (st != JPDF_OK) return jpdf::fail(st, "document handle rejected");
        if (object->open_pages > 0) {
            return jpdf::fail(JPDF_E_BUSY, "close the document's pages first");
        }
        st = jpdf::objects().retire(doc, jpdf::ObjectType::Document, nullptr);
        if (st != JPDF_OK) return jpdf::fail(st, "document handle rejected");
        FPDF_CloseDocument(object->doc);
        object->doc = nullptr;
        jpdf::Domain::instance().document_closed();
        return JPDF_OK;
    });
}

jpdf_status jpdf_doc_page_count(jpdf_handle doc, int32_t* out_count) noexcept {
    return jpdf::guarded([&] {
        if (!out_count) return jpdf::fail(JPDF_E_INVALID_ARGUMENT, "null out_count");
        Admission admission(Purpose::Ordinary);
        if (!admission.admitted()) return admission.status();
        std::shared_ptr<jpdf::DocumentObject> object;
        jpdf_status st = jpdf::objects().resolve_as(doc, jpdf::ObjectType::Document, &object);
        if (st != JPDF_OK) return jpdf::fail(st, "document handle rejected");
        *out_count = FPDF_GetPageCount(object->doc);
        return JPDF_OK;
    });
}

jpdf_status jpdf_page_open(jpdf_handle doc, int32_t index, jpdf_handle* out_page) noexcept {
    return jpdf::guarded([&] {
        if (!out_page) return jpdf::fail(JPDF_E_INVALID_ARGUMENT, "null out_page");
        Admission admission(Purpose::Ordinary);
        if (!admission.admitted()) return admission.status();
        std::shared_ptr<jpdf::DocumentObject> document;
        jpdf_status st = jpdf::objects().resolve_as(doc, jpdf::ObjectType::Document, &document);
        if (st != JPDF_OK) return jpdf::fail(st, "document handle rejected");
        if (index < 0 || index >= FPDF_GetPageCount(document->doc)) {
            return jpdf::fail(JPDF_E_NOT_FOUND, "page index out of range");
        }
        LoadedPage loaded{FPDF_LoadPage(document->doc, index)};
        if (!loaded.page) return jpdf::fail(JPDF_E_FORMAT, "PDFium could not load the page");
        auto object = std::make_shared<jpdf::PageObject>(document, index);
        object->page = loaded.page;
        jpdf_handle token = 0;
        st = jpdf::objects().insert(object, &token);
        if (st != JPDF_OK) {
            object->page = nullptr;
            return jpdf::fail(st, "object table rejected the page");
        }
        loaded.page = nullptr;
        document->open_pages++;
        jpdf::Domain::instance().page_opened();
        *out_page = token;
        return JPDF_OK;
    });
}

jpdf_status jpdf_page_close(jpdf_handle page) noexcept {
    return jpdf::guarded([&] {
        Admission admission(Purpose::Teardown);
        if (!admission.admitted()) return admission.status();
        std::shared_ptr<jpdf::PageObject> object;
        jpdf_status st = jpdf::objects().retire_as(page, jpdf::ObjectType::Page, &object);
        if (st != JPDF_OK) return jpdf::fail(st, "page handle rejected");
        FPDF_ClosePage(object->page);
        object->page = nullptr;
        object->document->open_pages--;
        jpdf::Domain::instance().page_closed();
        return JPDF_OK;
    });
}

jpdf_status jpdf_page_get_info(jpdf_handle page, jpdf_page_info* out) noexcept {
    return jpdf::guarded([&] {
        if (!out) return jpdf::fail(JPDF_E_INVALID_ARGUMENT, "null output");
        jpdf_status st =
            jpdf::check_output_struct(out->struct_size, sizeof(jpdf_page_info), "jpdf_page_info");
        if (st != JPDF_OK) return st;
        Admission admission(Purpose::Ordinary);
        if (!admission.admitted()) return admission.status();
        std::shared_ptr<jpdf::PageObject> object;
        st = jpdf::objects().resolve_as(page, jpdf::ObjectType::Page, &object);
        if (st != JPDF_OK) return jpdf::fail(st, "page handle rejected");
        out->index = object->index;
        out->width_pt = FPDF_GetPageWidthF(object->page);
        out->height_pt = FPDF_GetPageHeightF(object->page);
        out->rotation = FPDFPage_GetRotation(object->page);
        out->reserved0 = 0;
        return JPDF_OK;
    });
}

}  // extern "C"
