// jpdfium_render.cpp — Page rendering and page-to-image conversion.

#include "jpdfium.h"
#include "jpdfium_internal.h"

#include <fpdfview.h>
#include <fpdf_edit.h>

#include <cstdlib>
#include <cstdint>

int32_t jpdfium_render_page(int64_t page, int32_t dpi, uint8_t** rgba, int32_t* width, int32_t* height) {
    PageWrapper* pw = decodePage(page);
    if (!pw || !pw->page) return JPDFIUM_ERR_INVALID;

    double w_pt = FPDF_GetPageWidth(pw->page);
    double h_pt = FPDF_GetPageHeight(pw->page);
    int    w_px = static_cast<int>(w_pt * dpi / 72.0 + 0.5);
    int    h_px = static_cast<int>(h_pt * dpi / 72.0 + 0.5);
    if (w_px <= 0 || h_px <= 0) return JPDFIUM_ERR_INVALID;

    FPDF_BITMAP bmp = FPDFBitmap_Create(w_px, h_px, 1 /*alpha*/);
    if (!bmp) return JPDFIUM_ERR_NATIVE;

    FPDFBitmap_FillRect(bmp, 0, 0, w_px, h_px, 0xFFFFFFFF);
    FPDF_RenderPageBitmap(bmp, pw->page, 0, 0, w_px, h_px, 0, FPDF_ANNOT);

    // PDFium returns BGRA; swap B<->R to produce RGBA
    const uint8_t* src    = static_cast<const uint8_t*>(FPDFBitmap_GetBuffer(bmp));
    int            stride = FPDFBitmap_GetStride(bmp);
    size_t         out_sz = static_cast<size_t>(w_px) * h_px * 4;
    uint8_t*       out    = static_cast<uint8_t*>(malloc(out_sz));
    if (!out) { FPDFBitmap_Destroy(bmp); return JPDFIUM_ERR_NATIVE; }

    for (int row = 0; row < h_px; ++row) {
        const uint8_t* s = src + row * stride;
        uint8_t*       d = out + row * w_px * 4;
        for (int col = 0; col < w_px; ++col, s += 4, d += 4) {
            d[0] = s[2];  // R <- B
            d[1] = s[1];  // G
            d[2] = s[0];  // B <- R
            d[3] = s[3];  // A
        }
    }

    FPDFBitmap_Destroy(bmp);
    *rgba   = out;
    *width  = w_px;
    *height = h_px;
    return JPDFIUM_OK;
}

void jpdfium_free_buffer(uint8_t* buffer) {
    free(buffer);
}

int32_t jpdfium_page_to_image(int64_t docHandle, int32_t pageIndex, int32_t dpi) {
    DocWrapper* dw = decodeDoc(docHandle);
    if (!dw || !dw->doc) return JPDFIUM_ERR_INVALID;

    FPDF_PAGE page = FPDF_LoadPage(dw->doc, pageIndex);
    if (!page) return JPDFIUM_ERR_NOT_FOUND;

    double w_pt = FPDF_GetPageWidth(page);
    double h_pt = FPDF_GetPageHeight(page);
    int w_px = static_cast<int>(w_pt * dpi / 72.0 + 0.5);
    int h_px = static_cast<int>(h_pt * dpi / 72.0 + 0.5);
    if (w_px <= 0 || h_px <= 0) { FPDF_ClosePage(page); return JPDFIUM_ERR_INVALID; }

    FPDF_BITMAP bmp = FPDFBitmap_Create(w_px, h_px, 0 /*no alpha*/);
    if (!bmp) { FPDF_ClosePage(page); return JPDFIUM_ERR_NATIVE; }
    FPDFBitmap_FillRect(bmp, 0, 0, w_px, h_px, 0xFFFFFFFF);
    FPDF_RenderPageBitmap(bmp, page, 0, 0, w_px, h_px, 0, FPDF_ANNOT | FPDF_PRINTING);

    FPDF_ClosePage(page);

    FPDFPage_Delete(dw->doc, pageIndex);
    FPDF_PAGE newPage = FPDFPage_New(dw->doc, pageIndex, w_pt, h_pt);
    if (!newPage) { FPDFBitmap_Destroy(bmp); return JPDFIUM_ERR_NATIVE; }

    FPDF_PAGEOBJECT imgObj = FPDFPageObj_NewImageObj(dw->doc);
    if (!imgObj) { FPDFBitmap_Destroy(bmp); FPDF_ClosePage(newPage); return JPDFIUM_ERR_NATIVE; }

    FPDF_BOOL ok = FPDFImageObj_SetBitmap(nullptr, 0, imgObj, bmp);
    FPDFBitmap_Destroy(bmp);
    if (!ok) { FPDF_ClosePage(newPage); return JPDFIUM_ERR_NATIVE; }

    FS_MATRIX matrix = {static_cast<float>(w_pt), 0, 0, static_cast<float>(h_pt), 0, 0};
    FPDFPageObj_SetMatrix(imgObj, &matrix);

    FPDFPage_InsertObject(newPage, imgObj);
    if (!FPDFPage_GenerateContent(newPage)) {
        FPDF_ClosePage(newPage);
        return JPDFIUM_ERR_NATIVE;
    }

    FPDF_ClosePage(newPage);
    return JPDFIUM_OK;
}
