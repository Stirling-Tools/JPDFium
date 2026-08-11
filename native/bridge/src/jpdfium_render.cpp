// jpdfium_render.cpp - Page rendering and page-to-image conversion.

#include <fpdf_edit.h>
#include <fpdfview.h>

#include <cstdint>
#include <cstdlib>
#include <cstring>

#include "jpdfium.h"
#include "jpdfium_internal.h"

namespace {

inline int renderFlagsForScreen() {
    return FPDF_ANNOT | FPDF_LCD_TEXT;
}

inline int bitmapFormatForRenderer() {
#ifdef PDF_USE_SKIA
    return FPDFBitmap_BGRA_Premul;
#else
    return FPDFBitmap_BGRA;
#endif
}

inline void bgraToRgbaInPlace(uint8_t* buf, int w, int h, int stride) {
    for (int row = 0; row < h; ++row) {
        uint8_t* r = buf + row * stride;
        for (int col = 0; col < w; ++col, r += 4) {
            uint8_t b = r[0];
            r[0] = r[2];
            r[2] = b;
        }
    }
}

inline void unpremulInPlace(uint8_t* buf, int w, int h, int stride) {
    for (int row = 0; row < h; ++row) {
        uint8_t* r = buf + row * stride;
        for (int col = 0; col < w; ++col, r += 4) {
            uint8_t a = r[3];
            if (a == 0 || a == 255) continue;
            r[0] = static_cast<uint8_t>((r[0] * 255 + a / 2) / a);
            r[1] = static_cast<uint8_t>((r[1] * 255 + a / 2) / a);
            r[2] = static_cast<uint8_t>((r[2] * 255 + a / 2) / a);
        }
    }
}

}  // namespace

int32_t jpdfium_render_page(int64_t page, int32_t dpi, uint8_t** rgba, int32_t* width,
                            int32_t* height) {
    PageWrapper* pw = decodePage(page);
    if (!pw || !pw->page) return JPDFIUM_ERR_INVALID;

    double w_pt = FPDF_GetPageWidth(pw->page);
    double h_pt = FPDF_GetPageHeight(pw->page);
    int w_px = static_cast<int>(w_pt * dpi / 72.0 + 0.5);
    int h_px = static_cast<int>(h_pt * dpi / 72.0 + 0.5);
    if (w_px <= 0 || h_px <= 0) return JPDFIUM_ERR_INVALID;

    const int fmt = bitmapFormatForRenderer();
    size_t out_sz = static_cast<size_t>(w_px) * h_px * 4;
    uint8_t* out = static_cast<uint8_t*>(malloc(out_sz));
    if (!out) return JPDFIUM_ERR_NATIVE;

    FPDF_BITMAP bmp = FPDFBitmap_CreateEx(w_px, h_px, fmt, out, w_px * 4);
    if (!bmp) {
        free(out);
        return JPDFIUM_ERR_NATIVE;
    }

    FPDFBitmap_FillRect(bmp, 0, 0, w_px, h_px, 0xFFFFFFFF);
#ifdef PDF_USE_SKIA
    FS_MATRIX matrix = {static_cast<float>(w_px) / static_cast<float>(w_pt), 0, 0,
                        static_cast<float>(h_px) / static_cast<float>(h_pt), 0, 0};
    FS_RECTF clip = {0, 0, static_cast<float>(w_px), static_cast<float>(h_px)};
    FPDF_RenderPageBitmapWithMatrix(bmp, pw->page, &matrix, &clip, renderFlagsForScreen());
#else
    FPDF_RenderPageBitmap(bmp, pw->page, 0, 0, w_px, h_px, 0, renderFlagsForScreen());
#endif

#ifdef PDF_USE_SKIA
    unpremulInPlace(out, w_px, h_px, w_px * 4);
#endif
    bgraToRgbaInPlace(out, w_px, h_px, w_px * 4);

    FPDFBitmap_Destroy(bmp);
    *rgba = out;
    *width = w_px;
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
    if (w_px <= 0 || h_px <= 0) {
        FPDF_ClosePage(page);
        return JPDFIUM_ERR_INVALID;
    }

    FPDF_BITMAP bmp = FPDFBitmap_Create(w_px, h_px, 0 /*no alpha*/);
    if (!bmp) {
        FPDF_ClosePage(page);
        return JPDFIUM_ERR_NATIVE;
    }
    FPDFBitmap_FillRect(bmp, 0, 0, w_px, h_px, 0xFFFFFFFF);
    FPDF_RenderPageBitmap(bmp, page, 0, 0, w_px, h_px, 0, FPDF_ANNOT | FPDF_PRINTING);

    FPDF_ClosePage(page);

    FPDFPage_Delete(dw->doc, pageIndex);
    FPDF_PAGE newPage = FPDFPage_New(dw->doc, pageIndex, w_pt, h_pt);
    if (!newPage) {
        FPDFBitmap_Destroy(bmp);
        return JPDFIUM_ERR_NATIVE;
    }

    FPDF_PAGEOBJECT imgObj = FPDFPageObj_NewImageObj(dw->doc);
    if (!imgObj) {
        FPDFBitmap_Destroy(bmp);
        FPDF_ClosePage(newPage);
        return JPDFIUM_ERR_NATIVE;
    }

    FPDF_BOOL ok = FPDFImageObj_SetBitmap(nullptr, 0, imgObj, bmp);
    FPDFBitmap_Destroy(bmp);
    if (!ok) {
        FPDF_ClosePage(newPage);
        return JPDFIUM_ERR_NATIVE;
    }

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
