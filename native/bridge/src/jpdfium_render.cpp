// jpdfium_render.cpp - Page rendering and page-to-image conversion.

#include <fpdf_edit.h>
#include <fpdf_formfill.h>
#include <fpdfview.h>

#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <cstring>

#include "jpdfium.h"
#include "jpdfium_internal.h"

namespace {

constexpr double kMaxRenderPixels = 268435456.0;

inline int renderFlagsForScreen() {
    return FPDF_ANNOT | FPDF_LCD_TEXT;
}

inline int bitmapFormatForRenderer() {
#ifdef JPDFIUM_HAS_SKIA
    if (g_jpdfiumUseSkia) return FPDFBitmap_BGRA_Premul;
#endif
    return FPDFBitmap_BGRA;
}

#ifdef JPDFIUM_HAS_SKIA
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

inline void unpremulAndSwapBgraInPlace(uint8_t* buf, int w, int h, int stride,
                                       bool reverse_byte_order) {
    for (int row = 0; row < h; ++row) {
        uint8_t* r = buf + static_cast<std::ptrdiff_t>(row) * stride;
        for (int col = 0; col < w; ++col, r += 4) {
            uint8_t b = r[0];
            uint8_t g = r[1];
            uint8_t red = r[2];
            uint8_t a = r[3];
            if (a != 0 && a != 255) {
                b = static_cast<uint8_t>((b * 255 + a / 2) / a);
                g = static_cast<uint8_t>((g * 255 + a / 2) / a);
                red = static_cast<uint8_t>((red * 255 + a / 2) / a);
            }
            if (reverse_byte_order) {
                r[0] = red;
                r[1] = g;
                r[2] = b;
            } else {
                r[0] = b;
                r[1] = g;
                r[2] = red;
            }
        }
    }
}
#endif

// Render a page (plus optional form widgets) into a caller-provided buffer.
// Shared by the zero-allocation FFM render paths so the Skia premultiply/
// matrix handling lives in one place.
int32_t renderIntoBuffer(FPDF_PAGE page, FPDF_FORMHANDLE form, uint8_t* target, int32_t width,
                         int32_t height, int32_t stride, int32_t flags) {
    if (!page || !target || width <= 0 || height <= 0 || stride < width * 4)
        return JPDFIUM_ERR_INVALID;

    int pdfium_flags = flags;
#ifdef JPDFIUM_HAS_SKIA
    bool reverse_byte_order = false;
    if (g_jpdfiumUseSkia && (flags & FPDF_REVERSE_BYTE_ORDER) != 0) {
        reverse_byte_order = true;
        pdfium_flags = flags & ~FPDF_REVERSE_BYTE_ORDER;
    }
#endif

    const int fmt = bitmapFormatForRenderer();
    FPDF_BITMAP bmp = FPDFBitmap_CreateEx(width, height, fmt, target, stride);
    if (!bmp) return JPDFIUM_ERR_NATIVE;

#ifdef JPDFIUM_HAS_SKIA
    if (g_jpdfiumUseSkia) {
        double w_pt = FPDF_GetPageWidth(page);
        double h_pt = FPDF_GetPageHeight(page);
        if (w_pt <= 0 || h_pt <= 0) {
            FPDFBitmap_Destroy(bmp);
            return JPDFIUM_ERR_INVALID;
        }
        FS_MATRIX matrix = {static_cast<float>(width) / static_cast<float>(w_pt),  0, 0,
                            static_cast<float>(height) / static_cast<float>(h_pt), 0, 0};
        FS_RECTF clip = {0, 0, static_cast<float>(width), static_cast<float>(height)};
        FPDF_RenderPageBitmapWithMatrix(bmp, page, &matrix, &clip, pdfium_flags);
    } else {
        FPDF_RenderPageBitmap(bmp, page, 0, 0, width, height, 0, pdfium_flags);
    }
#else
    FPDF_RenderPageBitmap(bmp, page, 0, 0, width, height, 0, pdfium_flags);
#endif

    if (form) {
        FPDF_FFLDraw(form, bmp, page, 0, 0, width, height, 0, pdfium_flags);
    }

#ifdef JPDFIUM_HAS_SKIA
    if (g_jpdfiumUseSkia) {
        unpremulAndSwapBgraInPlace(target, width, height, stride, reverse_byte_order);
    }
#endif

    FPDFBitmap_Destroy(bmp);
    return JPDFIUM_OK;
}

}  // namespace

int32_t jpdfium_render_page(int64_t page, int32_t dpi, uint8_t** rgba, int32_t* width,
                            int32_t* height) {
    PageWrapper* pw = decodePage(page);
    if (!pw || !pw->page || !rgba || !width || !height) return JPDFIUM_ERR_INVALID;
    if (dpi == INT32_MIN || dpi == 0) return JPDFIUM_ERR_INVALID;

    bool transparent = false;
    if (dpi < 0) {
        transparent = true;
        dpi = -dpi;
    }

    double w_pt = FPDF_GetPageWidth(pw->page);
    double h_pt = FPDF_GetPageHeight(pw->page);
    double w_px_d = w_pt * dpi / 72.0 + 0.5;
    double h_px_d = h_pt * dpi / 72.0 + 0.5;
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

    FPDFBitmap_FillRect(bmp, 0, 0, w_px, h_px, transparent ? 0x00000000 : 0xFFFFFFFF);
    int render_flags = transparent ? FPDF_ANNOT : renderFlagsForScreen();
#ifdef JPDFIUM_HAS_SKIA
    if (g_jpdfiumUseSkia) {
        FS_MATRIX matrix = {static_cast<float>(w_px) / static_cast<float>(w_pt), 0, 0,
                            static_cast<float>(h_px) / static_cast<float>(h_pt), 0, 0};
        FS_RECTF clip = {0, 0, static_cast<float>(w_px), static_cast<float>(h_px)};
        FPDF_RenderPageBitmapWithMatrix(bmp, pw->page, &matrix, &clip, render_flags);
        if (transparent) {
            unpremulAndSwapBgraInPlace(out, w_px, h_px, w_px * 4, true);
        } else {
            bgraToRgbaInPlace(out, w_px, h_px, w_px * 4);
        }
    } else {
        render_flags |= FPDF_REVERSE_BYTE_ORDER;
        FPDF_RenderPageBitmap(bmp, pw->page, 0, 0, w_px, h_px, 0, render_flags);
    }
#else
    render_flags |= FPDF_REVERSE_BYTE_ORDER;
    FPDF_RenderPageBitmap(bmp, pw->page, 0, 0, w_px, h_px, 0, render_flags);
#endif

    FPDFBitmap_Destroy(bmp);
    *rgba = out;
    *width = w_px;
    *height = h_px;
    return JPDFIUM_OK;
}

int32_t jpdfium_render_page_into(void* fpdf_page, uint8_t* target, int32_t width, int32_t height,
                                 int32_t stride, int32_t flags) {
    return renderIntoBuffer(static_cast<FPDF_PAGE>(fpdf_page), nullptr, target, width, height,
                            stride, flags);
}

int32_t jpdfium_render_page_form_into(void* fpdf_page, void* form, uint8_t* target, int32_t width,
                                      int32_t height, int32_t stride, int32_t flags) {
    return renderIntoBuffer(static_cast<FPDF_PAGE>(fpdf_page), static_cast<FPDF_FORMHANDLE>(form),
                            target, width, height, stride, flags);
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
#ifdef JPDFIUM_HAS_SKIA
    if (g_jpdfiumUseSkia) {
        FS_MATRIX matrix = {static_cast<float>(w_px) / static_cast<float>(w_pt), 0, 0,
                            static_cast<float>(h_px) / static_cast<float>(h_pt), 0, 0};
        FS_RECTF clip = {0, 0, static_cast<float>(w_px), static_cast<float>(h_px)};
        FPDF_RenderPageBitmapWithMatrix(bmp, page, &matrix, &clip, FPDF_ANNOT | FPDF_PRINTING);
    } else {
        FPDF_RenderPageBitmap(bmp, page, 0, 0, w_px, h_px, 0, FPDF_ANNOT | FPDF_PRINTING);
    }
#else
    FPDF_RenderPageBitmap(bmp, page, 0, 0, w_px, h_px, 0, FPDF_ANNOT | FPDF_PRINTING);
#endif

    FPDF_ClosePage(page);

    FPDF_PAGE newPage = FPDFPage_New(dw->core->doc, pageIndex + 1, w_pt, h_pt);
    if (!newPage) {
        FPDFBitmap_Destroy(bmp);
        return JPDFIUM_ERR_NATIVE;
    }

    FPDF_PAGEOBJECT imgObj = FPDFPageObj_NewImageObj(dw->core->doc);
    if (!imgObj) {
        FPDFBitmap_Destroy(bmp);
        FPDF_ClosePage(newPage);
        FPDFPage_Delete(dw->core->doc, pageIndex + 1);
        return JPDFIUM_ERR_NATIVE;
    }

    FPDF_BOOL ok = FPDFImageObj_SetBitmap(nullptr, 0, imgObj, bmp);
    FPDFBitmap_Destroy(bmp);
    if (!ok) {
        FPDFPageObj_Destroy(imgObj);
        FPDF_ClosePage(newPage);
        FPDFPage_Delete(dw->core->doc, pageIndex + 1);
        return JPDFIUM_ERR_NATIVE;
    }

    FS_MATRIX matrix = {static_cast<float>(w_pt), 0, 0, static_cast<float>(h_pt), 0, 0};
    FPDFPageObj_SetMatrix(imgObj, &matrix);

    FPDFPage_InsertObject(newPage, imgObj);
    if (!FPDFPage_GenerateContent(newPage)) {
        FPDF_ClosePage(newPage);
        FPDFPage_Delete(dw->core->doc, pageIndex + 1);
        return JPDFIUM_ERR_NATIVE;
    }

    FPDF_ClosePage(newPage);
    FPDFPage_Delete(dw->core->doc, pageIndex);
    return JPDFIUM_OK;
}
