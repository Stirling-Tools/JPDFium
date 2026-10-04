// jpdfium_redact_image.cpp - pixel erasure for redaction.
//
// Rasterizes the covered portion of partially overlapped images so only
// visible pixels are removed.

#include <epdf_redact.h>
#include <fpdf_annot.h>
#include <fpdf_edit.h>
#include <fpdf_flatten.h>
#include <fpdf_save.h>
#include <fpdf_text.h>
#include <fpdfview.h>

#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <functional>
#include <limits>
#include <map>
#include <mutex>
#include <optional>
#include <set>
#include <span>
#include <sstream>
#include <string>
#include <type_traits>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#include "jpdfium.h"
#include "jpdfium_internal.h"
#include "jpdfium_redact_internal.h"

// Erases pixels whose center falls in |rects| (page space). Pixel (ix, iy)
// maps to unit (ix/w, 1 - iy/h): row 0 is the top, so the mirror erases the
// visible half. Rendered at source size because GetBitmap drops /SMask and
// SetBitmap rebuilds the image from whatever bitmap it gets. Only the
// affected pixel bbox is scanned; already-erased pixels are skipped.
// Returns false when the caller must remove the whole image.
bool eraseImagePixels(FPDF_DOCUMENT doc, FPDF_PAGE page, FPDF_PAGEOBJECT imageObj,
                      const FS_MATRIX& imgMatrix, std::span<const FS_RECTF> rects, uint32_t argb,
                      bool* pixelsChanged) {
    // |pixelsChanged| accumulates: it may be shared across images, so a call
    // that changes nothing must not clear an earlier call's true.
    if (rects.empty()) return true;
    unsigned int srcW = 0, srcH = 0;
    if (!FPDFImageObj_GetImagePixelSize(imageObj, &srcW, &srcH) || srcW == 0 || srcH == 0) {
        return false;
    }
    FPDF_BITMAP bmp = nullptr;
    FS_MATRIX original;
    if (FPDFPageObj_GetMatrix(imageObj, &original)) {
        // Unit matrix: 1 source pixel == 1 unit -> render at source size.
        const FS_MATRIX unit{static_cast<float>(srcW), 0.0f, 0.0f,
                             static_cast<float>(srcH), 0.0f, 0.0f};
        if (FPDFPageObj_SetMatrix(imageObj, &unit)) {
            bmp = FPDFImageObj_GetRenderedBitmap(doc, page, imageObj);
            if (!FPDFPageObj_SetMatrix(imageObj, &original)) {
                // Never ship an image left at the temporary unit matrix: the
                // caller removes the whole image instead.
                if (bmp) FPDFBitmap_Destroy(bmp);
                return false;
            }
        }
    }
    if (!bmp) {
        // Renderer unavailable: fall back to the mask-less base bitmap. The
        // erase still removes pixels; a soft mask (if any) cannot survive
        // this path.
        bmp = FPDFImageObj_GetBitmap(imageObj);
    }
    if (!bmp) return false;
    int w = FPDFBitmap_GetWidth(bmp);
    int h = FPDFBitmap_GetHeight(bmp);
    int fmt = FPDFBitmap_GetFormat(bmp);
    int stride = FPDFBitmap_GetStride(bmp);
    void* buf = FPDFBitmap_GetBuffer(bmp);
    if (!buf || w <= 0 || h <= 0) {
        FPDFBitmap_Destroy(bmp);
        return false;
    }
    int bpp = 4;
    if (fmt == FPDFBitmap_Gray) {
        bpp = 1;
    } else if (fmt == FPDFBitmap_BGR) {
        bpp = 3;
    } else if (fmt == FPDFBitmap_BGRx || fmt == FPDFBitmap_BGRA) {
        bpp = 4;
    } else {
        FPDFBitmap_Destroy(bmp);
        return false;
    }
    unsigned int r = (argb >> 16) & 0xFF;
    unsigned int g = (argb >> 8) & 0xFF;
    unsigned int b = argb & 0xFF;

    // Pixel-center -> page map (row-vector convention, row 0 = top):
    //   px = pa*ix + pc*iy + pe,  py = pb*ix + pd*iy + pf
    const double pa = imgMatrix.a / w;
    const double pc = -static_cast<double>(imgMatrix.c) / h;
    const double pb = imgMatrix.b / w;
    const double pd = -static_cast<double>(imgMatrix.d) / h;
    const double pe = imgMatrix.e + imgMatrix.c + 0.5 * pa + 0.5 * pc;
    const double pf = imgMatrix.f + imgMatrix.d + 0.5 * pb + 0.5 * pd;

    int minIx = 0, minIy = 0, maxIx = w - 1, maxIy = h - 1;
    const double det = pa * pd - pb * pc;
    if (std::abs(det) < 1e-12) {
        // Degenerate (zero-area) image: it paints nothing; nothing to erase.
        FPDFBitmap_Destroy(bmp);
        return true;
    }
    {
        // Inverse linear map applied to each erase rect's corners gives the
        // pixel-space bbox that can possibly be affected.
        const double ia = pd / det, ib = -pc / det;
        const double ic = -pb / det, id = pa / det;
        double pMinX = std::numeric_limits<double>::max();
        double pMinY = std::numeric_limits<double>::max();
        double pMaxX = std::numeric_limits<double>::lowest();
        double pMaxY = std::numeric_limits<double>::lowest();
        for (const FS_RECTF& rc : rects) {
            const double cx[4] = {rc.left, rc.right, rc.right, rc.left};
            const double cy[4] = {rc.bottom, rc.bottom, rc.top, rc.top};
            for (int k = 0; k < 4; k++) {
                double dx = cx[k] - pe;
                double dy = cy[k] - pf;
                double px = ia * dx + ib * dy;
                double py = ic * dx + id * dy;
                if (px < pMinX) pMinX = px;
                if (py < pMinY) pMinY = py;
                if (px > pMaxX) pMaxX = px;
                if (py > pMaxY) pMaxY = py;
            }
        }
        // Clamp to the bitmap; an erase rect far outside leaves an empty box.
        // Reject out-of-range boxes before the int cast: a tiny image scale
        // maps far margins past INT_MAX, and the cast would be undefined.
        if (pMinX > w - 1 || pMinY > h - 1 || pMaxX < 0 || pMaxY < 0) {
            FPDFBitmap_Destroy(bmp);
            return true;
        }
        minIx = static_cast<int>(std::max(0.0, std::floor(pMinX)));
        minIy = static_cast<int>(std::max(0.0, std::floor(pMinY)));
        maxIx = static_cast<int>(std::min(static_cast<double>(w - 1), std::ceil(pMaxX)));
        maxIy = static_cast<int>(std::min(static_cast<double>(h - 1), std::ceil(pMaxY)));
        if (maxIx < minIx || maxIy < minIy) {
            FPDFBitmap_Destroy(bmp);
            return true;
        }
    }

    bool changed = false;
    for (int iy = minIy; iy <= maxIy; iy++) {
        const double rowX = pc * iy + pe;
        const double rowY = pd * iy + pf;
        const size_t rowOffset = static_cast<size_t>(iy) * stride;
        for (int ix = minIx; ix <= maxIx; ix++) {
            const double px = pa * ix + rowX;
            const double py = pb * ix + rowY;
            bool inside = false;
            for (const FS_RECTF& rc : rects) {
                if (px >= rc.left && px <= rc.right && py >= rc.bottom && py <= rc.top) {
                    inside = true;
                    break;
                }
            }
            if (!inside) continue;
            size_t offset = rowOffset + static_cast<size_t>(ix) * bpp;
            if (offset + static_cast<size_t>(bpp) > static_cast<size_t>(stride) * h) continue;
            uint8_t* p = static_cast<uint8_t*>(buf) + offset;
            uint8_t want[4] = {0, 0, 0, 255};
            // BGRx has an unused 4th byte: compare/write only the color bytes.
            size_t n = (fmt == FPDFBitmap_BGRx) ? 3u : static_cast<size_t>(bpp);
            if (bpp == 1) {
                want[0] = static_cast<uint8_t>((r * 299 + g * 587 + b * 114) / 1000);
            } else {
                want[0] = static_cast<uint8_t>(b);
                want[1] = static_cast<uint8_t>(g);
                want[2] = static_cast<uint8_t>(r);
            }
            if (std::memcmp(p, want, n) == 0) continue;  // already erased
            std::memcpy(p, want, n);
            changed = true;
        }
    }
    bool ok = true;
    if (changed) {
        ok = FPDFImageObj_SetBitmap(nullptr, 0, imageObj, bmp) != 0;
        if (ok && pixelsChanged) *pixelsChanged = true;
    }
    FPDFBitmap_Destroy(bmp);
    return ok;
}
