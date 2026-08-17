// jpdfium_render.cpp - Page rendering and page-to-image conversion.

#include <fpdf_edit.h>
#include <fpdfview.h>

#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <cstring>

#include "jpdfium.h"
#include "jpdfium_internal.h"

namespace {

// Hard cap on total rendered pixels per page: 1 GiB of RGBA output
// (268,435,456 px). Requests above this are treated as invalid input rather
// than risking multi-gigabyte allocations from untrusted dpi values.
constexpr double kMaxRenderPixels = 268435456.0;

inline int renderFlagsForScreen() {
    return FPDF_ANNOT | FPDF_LCD_TEXT;
}

inline int bitmapFormatForRenderer() {
#ifdef JPDFIUM_HAS_SKIA
    return FPDFBitmap_BGRA_Premul;
#else
    return FPDFBitmap_BGRA;
#endif
}

inline void bgraToRgbaInPlace(uint8_t* buf, int w, int h, int stride) {
    for (int row = 0; row < h; ++row) {
        uint8_t* r = buf + static_cast<std::ptrdiff_t>(row) * stride;
        for (int col = 0; col < w; ++col, r += 4) {
            uint8_t b = r[0];
            r[0] = r[2];
            r[2] = b;
        }
    }
}

#ifdef JPDFIUM_HAS_SKIA
inline void unpremulInPlace(uint8_t* buf, int w, int h, int stride) {
    for (int row = 0; row < h; ++row) {
        uint8_t* r = buf + static_cast<std::ptrdiff_t>(row) * stride;
        for (int col = 0; col < w; ++col, r += 4) {
            uint8_t a = r[3];
            if (a == 0 || a == 255) continue;
            r[0] = static_cast<uint8_t>((r[0] * 255 + a / 2) / a);
            r[1] = static_cast<uint8_t>((r[1] * 255 + a / 2) / a);
            r[2] = static_cast<uint8_t>((r[2] * 255 + a / 2) / a);
        }
    }
}
#endif

}  // namespace

int32_t jpdfium_render_page(int64_t page, int32_t dpi, uint8_t** rgba, int32_t* width,
                            int32_t* height) {
    PageWrapper* pw = decodePage(page);
    if (!pw || !pw->page || !rgba || !width || !height) return JPDFIUM_ERR_INVALID;

    double w_pt = FPDF_GetPageWidth(pw->page);
    double h_pt = FPDF_GetPageHeight(pw->page);
    double w_px_d = w_pt * dpi / 72.0 + 0.5;
    double h_px_d = h_pt * dpi / 72.0 + 0.5;
    // dpi comes from the Java layer as untrusted input; reject non-positive
    // sizes and total pixel counts that would exceed 1 GiB of RGBA output
    // (double->int conversion outside int range is UB otherwise).
    if (w_px_d <= 0 || h_px_d <= 0 || w_px_d > INT32_MAX || h_px_d > INT32_MAX ||
        w_px_d * h_px_d > kMaxRenderPixels)
        return JPDFIUM_ERR_INVALID;
    int w_px = static_cast<int>(w_px_d);
    int h_px = static_cast<int>(h_px_d);

    const int fmt = bitmapFormatForRenderer();
    uint8_t* out = allocRgbaChecked(w_px, h_px);
    if (!out) return JPDFIUM_ERR_NATIVE;

    FPDF_BITMAP bmp = FPDFBitmap_CreateEx(w_px, h_px, fmt, out, w_px * 4);
    if (!bmp) {
        free(out);
        return JPDFIUM_ERR_NATIVE;
    }

    FPDFBitmap_FillRect(bmp, 0, 0, w_px, h_px, 0xFFFFFFFF);
#ifdef JPDFIUM_HAS_SKIA
    FS_MATRIX matrix = {static_cast<float>(w_px) / static_cast<float>(w_pt), 0, 0,
                        static_cast<float>(h_px) / static_cast<float>(h_pt), 0, 0};
    FS_RECTF clip = {0, 0, static_cast<float>(w_px), static_cast<float>(h_px)};
    FPDF_RenderPageBitmapWithMatrix(bmp, pw->page, &matrix, &clip, renderFlagsForScreen());
#else
    FPDF_RenderPageBitmap(bmp, pw->page, 0, 0, w_px, h_px, 0, renderFlagsForScreen());
#endif

#ifdef JPDFIUM_HAS_SKIA
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
    if (!dw || !dw->core->doc) return JPDFIUM_ERR_INVALID;

    FPDF_PAGE page = FPDF_LoadPage(dw->core->doc, pageIndex);
    if (!page) return JPDFIUM_ERR_NOT_FOUND;

    double w_pt = FPDF_GetPageWidth(page);
    double h_pt = FPDF_GetPageHeight(page);
    double w_px_d = w_pt * dpi / 72.0 + 0.5;
    double h_px_d = h_pt * dpi / 72.0 + 0.5;
    if (w_px_d <= 0 || h_px_d <= 0 || w_px_d > INT32_MAX || h_px_d > INT32_MAX ||
        w_px_d * h_px_d > kMaxRenderPixels) {
        FPDF_ClosePage(page);
        return JPDFIUM_ERR_INVALID;
    }
    int w_px = static_cast<int>(w_px_d);
    int h_px = static_cast<int>(h_px_d);

    FPDF_BITMAP bmp = FPDFBitmap_Create(w_px, h_px, 0 /*no alpha*/);
    if (!bmp) {
        FPDF_ClosePage(page);
        return JPDFIUM_ERR_NATIVE;
    }
    FPDFBitmap_FillRect(bmp, 0, 0, w_px, h_px, 0xFFFFFFFF);
    FPDF_RenderPageBitmap(bmp, page, 0, 0, w_px, h_px, 0, FPDF_ANNOT | FPDF_PRINTING);

    FPDF_ClosePage(page);

    FPDFPage_Delete(dw->core->doc, pageIndex);
    FPDF_PAGE newPage = FPDFPage_New(dw->core->doc, pageIndex, w_pt, h_pt);
    if (!newPage) {
        FPDFBitmap_Destroy(bmp);
        return JPDFIUM_ERR_NATIVE;
    }

    FPDF_PAGEOBJECT imgObj = FPDFPageObj_NewImageObj(dw->core->doc);
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
