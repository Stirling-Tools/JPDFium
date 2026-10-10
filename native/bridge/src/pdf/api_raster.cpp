// api_raster.cpp - jpdf_rasterize_page_file: the OCRmyPDF rasterize_pdf_page contract, matched
// to the pypdfium reference with the deviations listed in docs/design/RASTER.md.

#include <fpdf_edit.h>
#include <fpdf_transformpage.h>
#include <fpdfview.h>

#include <algorithm>
#include <charconv>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <system_error>

#include "images/dither.h"
#include "images/pixels.h"
#include "jpdfium_api.h"
#include "jpdfium_internal.h"
#include "output/image_writer.h"
#include "pdf_support.h"
#include "runtime/api_status.h"
#include "runtime/domain.h"

namespace {

constexpr uint64_t kDefaultMaxPixels = 268435456ull;
constexpr uint32_t kKnownFlags = JPDF_RASTER_USE_CROPBOX | JPDF_RASTER_FILTER_VECTOR;
constexpr double kMaxDpi = 10000.0;

struct RasterRequest {
    uint32_t flags = 0;
    double dpi_x = 0;
    double dpi_y = 0;
    double out_dpi_x = 0;
    double out_dpi_y = 0;
    int32_t rotation_ccw = 0;
    int32_t device = 0;
    uint64_t max_pixels = kDefaultMaxPixels;
    const char* password = nullptr;
};

bool gray_device(int32_t device) noexcept {
    return device == JPDF_RASTER_MONO || device == JPDF_RASTER_MONOD || device == JPDF_RASTER_GRAY;
}

bool mono_device(int32_t device) noexcept {
    return device == JPDF_RASTER_MONO || device == JPDF_RASTER_MONOD;
}

bool valid_dpi(double dpi) noexcept {
    return std::isfinite(dpi) && dpi > 0 && dpi <= kMaxDpi;
}

jpdf_status parse(const jpdf_raster_options* o, RasterRequest* out) noexcept {
    if (!o) return jpdf::fail(JPDF_E_INVALID_ARGUMENT, "raster options are required");
    jpdf_status st = jpdf::check_input_struct(o, o->struct_size, sizeof(jpdf_raster_options),
                                              sizeof(jpdf_raster_options), "jpdf_raster_options");
    if (st != JPDF_OK) return st;
    if ((o->flags & ~kKnownFlags) != 0) {
        return jpdf::fail(JPDF_E_UNSUPPORTED, "unknown jpdf_raster_options.flags bits");
    }
    if (o->flags & JPDF_RASTER_FILTER_VECTOR) {
        return jpdf::fail(JPDF_E_UNSUPPORTED, "vector filtering is not implemented");
    }
    if (o->device < JPDF_RASTER_MONO || o->device > JPDF_RASTER_RGB_ALPHA) {
        return jpdf::fail(JPDF_E_INVALID_ARGUMENT, "unknown raster device");
    }
    if (o->rotation_ccw % 90 != 0 || o->rotation_ccw < 0 || o->rotation_ccw >= 360) {
        return jpdf::fail(JPDF_E_INVALID_ARGUMENT, "rotation_ccw must be 0, 90, 180 or 270");
    }
    if (!valid_dpi(o->raster_dpi_x) || !valid_dpi(o->raster_dpi_y)) {
        return jpdf::fail(JPDF_E_INVALID_ARGUMENT, "raster dpi must be finite, > 0 and <= 10000");
    }
    const bool out_set = o->output_dpi_x != 0 || o->output_dpi_y != 0;
    if (out_set && (!valid_dpi(o->output_dpi_x) || !valid_dpi(o->output_dpi_y))) {
        return jpdf::fail(JPDF_E_INVALID_ARGUMENT, "output dpi must both be 0 or both valid");
    }
    out->flags = o->flags;
    out->dpi_x = o->raster_dpi_x;
    out->dpi_y = o->raster_dpi_y;
    out->out_dpi_x = out_set ? o->output_dpi_x : o->raster_dpi_x;
    out->out_dpi_y = out_set ? o->output_dpi_y : o->raster_dpi_y;
    out->rotation_ccw = o->rotation_ccw;
    out->device = o->device;
    out->max_pixels = o->max_pixels == 0 ? kDefaultMaxPixels : o->max_pixels;
    out->password = o->password;
    return JPDF_OK;
}

// Python round(x, 6): the reference rounds DPI to Ghostscript's precision this way.
double round6(double value) noexcept {
    char buf[64];
    const auto [end, ec] =
        std::to_chars(buf, buf + sizeof(buf), value, std::chars_format::fixed, 6);
    if (ec != std::errc()) return value;
    double out = value;
    std::from_chars(buf, end, out);
    return out;
}

// Python int(round(x)): half to even.
int32_t round_half_even(double value) noexcept {
    return static_cast<int32_t>(std::nearbyint(value));
}

// The rendered page before trimming: 8-bit gray or packed RGB.
struct Canvas {
    jpdf::PixelBuffer data;
    int32_t width = 0;
    int32_t height = 0;
    int32_t stride = 0;
    int32_t channels = 0;
    bool bgr = false;
};

jpdf_status render(const char* path, int32_t index, const RasterRequest& req, Canvas* canvas,
                   int32_t* expected_w, int32_t* expected_h) {
    jpdf::Admission admission(jpdf::Purpose::Ordinary);
    if (!admission.admitted()) return admission.status();
    jpdf::LoadedDocument doc{FPDF_LoadDocument(path, req.password)};
    if (!doc.doc) return jpdf::pdfium_open_failure();
    if (index < 0 || index >= FPDF_GetPageCount(doc.doc)) {
        return jpdf::fail(JPDF_E_NOT_FOUND, "page index out of range");
    }
    jpdf::LoadedPage page{FPDF_LoadPage(doc.doc, index)};
    if (!page.page) return jpdf::fail(JPDF_E_FORMAT, "PDFium could not load the page");

    // Relative to the page's own /Rotate, as Ghostscript applies it. The document is never saved.
    if (req.rotation_ccw != 0) {
        const int current = FPDFPage_GetRotation(page.page);
        FPDFPage_SetRotation(page.page, (current + 4 - req.rotation_ccw / 90) % 4);
    }
    if (!(req.flags & JPDF_RASTER_USE_CROPBOX)) {
        float l = 0, b = 0, r = 612, t = 792;
        if (!FPDFPage_GetMediaBox(page.page, &l, &b, &r, &t)) {
            l = 0, b = 0, r = 612, t = 792;
        }
        FPDFPage_SetCropBox(page.page, l, b, r, t);
    }
    const double w_pt = FPDF_GetPageWidthF(page.page);
    const double h_pt = FPDF_GetPageHeightF(page.page);
    if (!std::isfinite(w_pt) || !std::isfinite(h_pt) || w_pt <= 0 || h_pt <= 0) {
        return jpdf::fail(JPDF_E_FORMAT, "page has no usable size");
    }
    // Ghostscript renders at (x, y) dpi and then turns the image, so after a quarter-turn
    // correction the page's horizontal axis carries the y resolution.
    const bool swap = req.rotation_ccw % 180 == 90;
    const double sx = round6(swap ? req.dpi_y : req.dpi_x) / 72.0;
    const double sy = round6(swap ? req.dpi_x : req.dpi_y) / 72.0;
    const double cw = std::ceil(w_pt * sx);
    const double ch = std::ceil(h_pt * sy);
    if (!(cw < INT32_MAX / 4) || !(ch < INT32_MAX / 4)) {
        return jpdf::fail(JPDF_E_LIMIT, "raster size exceeds the pixel budget");
    }
    *expected_w = std::max(1, round_half_even(w_pt * sx));
    *expected_h = std::max(1, round_half_even(h_pt * sy));
    canvas->width = std::max(1, static_cast<int32_t>(cw));
    canvas->height = std::max(1, static_cast<int32_t>(ch));
    const uint64_t biggest_w = static_cast<uint64_t>(std::max(canvas->width, *expected_w));
    const uint64_t biggest_h = static_cast<uint64_t>(std::max(canvas->height, *expected_h));
    if (biggest_w * biggest_h > req.max_pixels) {
        return jpdf::fail(JPDF_E_LIMIT, "raster size exceeds the pixel budget");
    }

    const bool gray = gray_device(req.device);
    const int flags = FPDF_ANNOT | (gray ? FPDF_GRAYSCALE : 0);
    if (jpdfium_core_uses_skia()) {
        // Skia renders BGRA only; convert after leaving the domain. Not parity-qualified.
        canvas->channels = 4;
        canvas->stride = canvas->width * 4;
    } else {
        // pypdfium2's formats: 8-bit gray for gray devices, packed BGR otherwise.
        canvas->channels = gray ? 1 : 3;
        canvas->stride = (canvas->width * canvas->channels + 3) & ~3;
        canvas->bgr = !gray;
    }
    const size_t bytes = static_cast<size_t>(canvas->stride) * static_cast<size_t>(canvas->height);
    canvas->data.reset(static_cast<uint8_t*>(std::malloc(bytes)));
    if (!canvas->data) return jpdf::fail(JPDF_E_NO_MEMORY, "raster buffer allocation failed");
    std::memset(canvas->data.get(), 0xFF, bytes);
    if (canvas->channels == 4) {
        if (jpdfium_render_into_buffer(page.page, canvas->data.get(), bytes, canvas->width,
                                       canvas->height, canvas->stride,
                                       flags | FPDF_REVERSE_BYTE_ORDER) != JPDFIUM_OK) {
            return jpdf::fail(JPDF_E_INTERNAL, "PDFium render failed");
        }
        return JPDF_OK;
    }
    const int format = gray ? FPDFBitmap_Gray : FPDFBitmap_BGR;
    FPDF_BITMAP bitmap = FPDFBitmap_CreateEx(canvas->width, canvas->height, format,
                                             canvas->data.get(), canvas->stride);
    if (!bitmap) return jpdf::fail(JPDF_E_NO_MEMORY, "PDFium bitmap creation failed");
    FPDFBitmap_FillRect(bitmap, 0, 0, canvas->width, canvas->height, 0xFFFFFFFF);
    FPDF_RenderPageBitmap(bitmap, page.page, 0, 0, canvas->width, canvas->height, 0, flags);
    FPDFBitmap_Destroy(bitmap);
    return JPDF_OK;
}

// The reference's two size corrections: trim a 1-2 px overshoot, then crop or white-pad to
// the expected size when either axis is within 2 px.
void fitted_size(const Canvas& c, int32_t ew, int32_t eh, int32_t* w, int32_t* h) {
    int32_t width = c.width, height = c.height;
    if (width - ew > 0 && width - ew <= 2) width = ew;
    if (height - eh > 0 && height - eh <= 2) height = eh;
    const int32_t wd = std::abs(width - ew), hd = std::abs(height - eh);
    if ((wd <= 2 || hd <= 2) && (wd > 0 || hd > 0)) {
        width = ew;
        height = eh;
    }
    *w = width;
    *h = height;
}

// Copies the canvas top-left into a w x h image of `channels` (1 or 3), white elsewhere.
jpdf::PixelBuffer fit(const Canvas& c, int32_t w, int32_t h, int32_t channels) {
    const size_t row = static_cast<size_t>(w) * static_cast<size_t>(channels);
    jpdf::PixelBuffer out(static_cast<uint8_t*>(std::malloc(row * static_cast<size_t>(h))));
    if (!out) return out;
    std::memset(out.get(), 0xFF, row * static_cast<size_t>(h));
    const int32_t rows = std::min(h, c.height);
    const int32_t cols = std::min(w, c.width);
    const uint8_t* const src_base = c.data.get();
    uint8_t* const dst_base = out.get();
    for (int32_t y = 0; y < rows; ++y) {
        const uint8_t* src = src_base + static_cast<ptrdiff_t>(y) * c.stride;
        uint8_t* dst = dst_base + static_cast<ptrdiff_t>(y) * static_cast<ptrdiff_t>(row);
        for (int32_t x = 0; x < cols; ++x) {
            const uint8_t* px = src + static_cast<ptrdiff_t>(x) * c.channels;
            if (channels == 1) {
                // Skia fallback: PDFium's own gray formula, FXRGB2GRAY.
                dst[x] = c.channels == 1
                             ? px[0]
                             : static_cast<uint8_t>((px[0] * 30 + px[1] * 59 + px[2] * 11) / 100);
            } else if (c.bgr) {
                dst[x * 3] = px[2];
                dst[x * 3 + 1] = px[1];
                dst[x * 3 + 2] = px[0];
            } else {
                std::memcpy(dst + static_cast<ptrdiff_t>(x) * 3, px, 3);
            }
        }
    }
    return out;
}

}  // namespace

extern "C" {

jpdf_status jpdf_rasterize_page_file(const char* path_utf8, int32_t page_index,
                                     const jpdf_raster_options* options,
                                     const char* output_path_utf8, const jpdf_write_options* write,
                                     jpdf_raster_result* out_result) noexcept {
    return jpdf::guarded([&] {
        if (!path_utf8 || !*path_utf8 || !output_path_utf8 || !*output_path_utf8) {
            return jpdf::fail(JPDF_E_INVALID_ARGUMENT, "input and output paths are required");
        }
        if (out_result) {
            jpdf_status st = jpdf::check_output_struct(
                out_result->struct_size, sizeof(jpdf_raster_result), "jpdf_raster_result");
            if (st != JPDF_OK) return st;
        }
        RasterRequest req;
        jpdf_status st = parse(options, &req);
        if (st != JPDF_OK) return st;
        jpdf::WriteRequest write_request;
        if ((st = jpdf::parse_write_options(write, &write_request)) != JPDF_OK) return st;

        Canvas canvas;
        int32_t ew = 0, eh = 0;
        if ((st = render(path_utf8, page_index, req, &canvas, &ew, &eh)) != JPDF_OK) return st;

        // Pixel work and output run after leaving the domain.
        int32_t w = 0, h = 0;
        fitted_size(canvas, ew, eh, &w, &h);
        const int32_t channels = gray_device(req.device) ? 1 : 3;
        jpdf::PixelBuffer image = fit(canvas, w, h, channels);
        if (!image) return jpdf::fail(JPDF_E_NO_MEMORY, "raster output allocation failed");
        canvas.data.reset();

        // Ghostscript swaps the DPI axes with the image for quarter-turn corrections.
        const bool swap = req.rotation_ccw % 180 == 90;
        jpdf::PngImage png{image.get(),
                           w,
                           h,
                           w * channels,
                           channels,
                           swap ? req.out_dpi_y : req.out_dpi_x,
                           swap ? req.out_dpi_x : req.out_dpi_y};
        jpdf::PixelBuffer bits;
        if (mono_device(req.device)) {
            const int32_t bits_stride = (w + 7) / 8;
            bits.reset(static_cast<uint8_t*>(
                std::malloc(static_cast<size_t>(bits_stride) * static_cast<size_t>(h))));
            if (!bits) return jpdf::fail(JPDF_E_NO_MEMORY, "raster output allocation failed");
            jpdf::dither_to_bits(image.get(), w, h, w, bits.get(), bits_stride);
            png.data = bits.get();
            png.stride = bits_stride;
            png.channels = 0;
        }
        if ((st = jpdf::write_png_file(png, output_path_utf8, write_request)) != JPDF_OK) {
            return st;
        }
        if (out_result) {
            out_result->width = w;
            out_result->height = h;
            out_result->bits_per_pixel = png.channels == 0 ? 1 : png.channels * 8;
            out_result->dpi_x = png.dpi_x;
            out_result->dpi_y = png.dpi_y;
        }
        return JPDF_OK;
    });
}

}  // extern "C"
