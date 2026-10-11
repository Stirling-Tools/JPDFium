// api_render.cpp - jpdf_page_render*: PDFium work admitted, conversion and output outside.

#include <fpdfview.h>

#include <cmath>
#include <cstdint>
#include <cstring>
#include <memory>

#include "jpdfium_api.h"
#include "jpdfium_internal.h"
#include "objects/objects.h"
#include "output/image_writer.h"
#include "runtime/api_status.h"
#include "runtime/domain.h"

namespace {

// Same ceiling as the legacy render path: 256 MiP, 1 GiB of RGBA.
constexpr uint64_t kDefaultMaxPixels = 268435456ull;
constexpr double kMaxDpi = 10000.0;
constexpr uint32_t kKnownFlags = JPDF_RENDER_ANNOTATIONS | JPDF_RENDER_LCD_TEXT |
                                 JPDF_RENDER_GRAYSCALE | JPDF_RENDER_PRINTING |
                                 JPDF_RENDER_NO_SMOOTH_TEXT | JPDF_RENDER_NO_SMOOTH_IMAGE |
                                 JPDF_RENDER_NO_SMOOTH_PATH | JPDF_RENDER_TRANSPARENT;

struct RenderRequest {
    uint32_t flags = 0;
    double dpi_x = 0;
    double dpi_y = 0;
    int32_t width = 0;
    int32_t height = 0;
    int32_t pixel_format = JPDF_PIXEL_RGBA8;
    uint64_t max_pixels = kDefaultMaxPixels;
};

bool valid_dpi(double dpi) noexcept {
    return std::isfinite(dpi) && dpi > 0 && dpi <= kMaxDpi;
}

jpdf_status parse_render_options(const jpdf_render_options* o, RenderRequest* out) noexcept {
    if (!o) return jpdf::fail(JPDF_E_INVALID_ARGUMENT, "render options are required");
    jpdf_status st = jpdf::check_input_struct(o, o->struct_size, sizeof(jpdf_render_options),
                                              sizeof(jpdf_render_options), "jpdf_render_options");
    if (st != JPDF_OK) return st;
    if ((o->flags & ~kKnownFlags) != 0) {
        return jpdf::fail(JPDF_E_UNSUPPORTED, "unknown jpdf_render_options.flags bits");
    }
    if (o->rotation != 0) return jpdf::fail(JPDF_E_UNSUPPORTED, "render rotation is reserved");
    if (jpdf::channels_for(o->pixel_format) == 0) {
        return jpdf::fail(JPDF_E_UNSUPPORTED, "unknown pixel_format");
    }
    if ((o->flags & JPDF_RENDER_TRANSPARENT) && o->pixel_format != JPDF_PIXEL_RGBA8) {
        return jpdf::fail(JPDF_E_INVALID_ARGUMENT, "a transparent render needs RGBA8");
    }
    if (o->width < 0 || o->height < 0 || (o->width == 0) != (o->height == 0)) {
        return jpdf::fail(JPDF_E_INVALID_ARGUMENT, "width and height must both be set or 0");
    }
    if (o->width == 0 && (!valid_dpi(o->dpi_x) || !valid_dpi(o->dpi_y))) {
        return jpdf::fail(JPDF_E_INVALID_ARGUMENT, "dpi must be finite, > 0 and <= 10000");
    }
    out->flags = o->flags;
    out->dpi_x = o->dpi_x;
    out->dpi_y = o->dpi_y;
    out->width = o->width;
    out->height = o->height;
    out->pixel_format = o->pixel_format;
    out->max_pixels = o->max_pixels == 0 ? kDefaultMaxPixels : o->max_pixels;
    return JPDF_OK;
}

int pdfium_flags(uint32_t flags) noexcept {
    int f = FPDF_REVERSE_BYTE_ORDER;
    if (flags & JPDF_RENDER_ANNOTATIONS) f |= FPDF_ANNOT;
    if (flags & JPDF_RENDER_LCD_TEXT) f |= FPDF_LCD_TEXT;
    if (flags & JPDF_RENDER_GRAYSCALE) f |= FPDF_GRAYSCALE;
    if (flags & JPDF_RENDER_PRINTING) f |= FPDF_PRINTING;
    if (flags & JPDF_RENDER_NO_SMOOTH_TEXT) f |= FPDF_RENDER_NO_SMOOTHTEXT;
    if (flags & JPDF_RENDER_NO_SMOOTH_IMAGE) f |= FPDF_RENDER_NO_SMOOTHIMAGE;
    if (flags & JPDF_RENDER_NO_SMOOTH_PATH) f |= FPDF_RENDER_NO_SMOOTHPATH;
    return f;
}

// Pixel size from points: round half up, never below one pixel.
jpdf_status pixel_extent(double points, double dpi, int32_t* out) noexcept {
    const double px = std::floor(points * dpi / 72.0 + 0.5);
    if (!std::isfinite(px) || px > static_cast<double>(INT32_MAX / 4)) {
        return jpdf::fail(JPDF_E_LIMIT, "render size exceeds the pixel budget");
    }
    *out = px < 1 ? 1 : static_cast<int32_t>(px);
    return JPDF_OK;
}

jpdf_status render_pixels(jpdf_handle page, const RenderRequest& req,
                          std::shared_ptr<jpdf::PixelStorage>* out) {
    auto storage = std::make_shared<jpdf::PixelStorage>();
    int32_t width = req.width;
    int32_t height = req.height;
    {
        jpdf::Admission admission(jpdf::Purpose::Ordinary);
        if (!admission.admitted()) return admission.status();
        std::shared_ptr<jpdf::PageObject> object;
        jpdf_status st = jpdf::objects().resolve_as(page, jpdf::ObjectType::Page, &object);
        if (st != JPDF_OK) return jpdf::fail(st, "page handle rejected");

        const double w_pt = FPDF_GetPageWidthF(object->page);
        const double h_pt = FPDF_GetPageHeightF(object->page);
        if (!std::isfinite(w_pt) || !std::isfinite(h_pt) || w_pt <= 0 || h_pt <= 0) {
            return jpdf::fail(JPDF_E_FORMAT, "page has no usable size");
        }
        if (width == 0) {
            if ((st = pixel_extent(w_pt, req.dpi_x, &width)) != JPDF_OK) return st;
            if ((st = pixel_extent(h_pt, req.dpi_y, &height)) != JPDF_OK) return st;
            storage->dpi_x = req.dpi_x;
            storage->dpi_y = req.dpi_y;
        } else {
            storage->dpi_x = width * 72.0 / w_pt;
            storage->dpi_y = height * 72.0 / h_pt;
        }
        if (width > INT32_MAX / 4 ||
            static_cast<uint64_t>(width) * static_cast<uint64_t>(height) > req.max_pixels) {
            return jpdf::fail(JPDF_E_LIMIT, "render size exceeds the pixel budget");
        }
        const int32_t stride = width * 4;
        const uint64_t bytes = static_cast<uint64_t>(stride) * static_cast<uint64_t>(height);
        storage->data.reset(static_cast<uint8_t*>(std::malloc(static_cast<size_t>(bytes))));
        if (!storage->data) return jpdf::fail(JPDF_E_NO_MEMORY, "render buffer allocation failed");
        const bool transparent = (req.flags & JPDF_RENDER_TRANSPARENT) != 0;
        std::memset(storage->data.get(), transparent ? 0x00 : 0xFF, static_cast<size_t>(bytes));
        if (jpdfium_render_into_buffer(object->page, storage->data.get(), bytes, width, height,
                                       stride, pdfium_flags(req.flags)) != JPDFIUM_OK) {
            return jpdf::fail(JPDF_E_INTERNAL, "PDFium render failed");
        }
    }
    // Pixel work needs no PDFium state, so it runs after leaving the domain.
    int32_t stride = width * 4;
    if (req.pixel_format == JPDF_PIXEL_RGB8) {
        jpdf::rgba_to_rgb(storage->data.get(), width, height);
        stride = width * 3;
    } else if (req.pixel_format == JPDF_PIXEL_GRAY8) {
        jpdf::rgba_to_gray(storage->data.get(), width, height);
        stride = width;
    }
    storage->width = width;
    storage->height = height;
    storage->stride = stride;
    storage->pixel_format = req.pixel_format;
    storage->byte_size = static_cast<uint64_t>(stride) * static_cast<uint64_t>(height);
    // The in-place narrowing wrote its result at the front of the RGBA buffer, but the allocation
    // is still RGBA-sized. Hand the tail back to the allocator so a gray/RGB bitmap does not retain
    // four times its logical pixels for the whole life of the handle. Nothing holds a pointer into
    // the buffer yet (it is published only at *out below), so shrinking here is safe.
    const uint64_t rgba_bytes = static_cast<uint64_t>(width) * 4 * static_cast<uint64_t>(height);
    if (storage->byte_size < rgba_bytes) {
        uint8_t* original = storage->data.release();
        void* shrunk = std::realloc(original, static_cast<size_t>(storage->byte_size));
        storage->data.reset(shrunk ? static_cast<uint8_t*>(shrunk) : original);
    }
    *out = std::move(storage);
    return JPDF_OK;
}

}  // namespace

extern "C" {

jpdf_status jpdf_page_render(jpdf_handle page, const jpdf_render_options* options,
                             jpdf_handle* out_bitmap) noexcept {
    return jpdf::guarded([&] {
        if (!out_bitmap) return jpdf::fail(JPDF_E_INVALID_ARGUMENT, "null out_bitmap");
        RenderRequest request;
        jpdf_status st = parse_render_options(options, &request);
        if (st != JPDF_OK) return st;
        std::shared_ptr<jpdf::PixelStorage> pixels;
        if ((st = render_pixels(page, request, &pixels)) != JPDF_OK) return st;
        auto bitmap = std::make_shared<jpdf::BitmapObject>(std::move(pixels));
        jpdf_handle token = 0;
        st = jpdf::objects().insert(std::move(bitmap), &token);
        if (st != JPDF_OK) return jpdf::fail(st, "object table rejected the bitmap");
        *out_bitmap = token;
        return JPDF_OK;
    });
}

jpdf_status jpdf_page_render_to_file(jpdf_handle page, const jpdf_render_options* render,
                                     const char* path_utf8,
                                     const jpdf_write_options* write) noexcept {
    return jpdf::guarded([&] {
        if (!path_utf8 || !*path_utf8) return jpdf::fail(JPDF_E_INVALID_ARGUMENT, "empty path");
        RenderRequest request;
        jpdf_status st = parse_render_options(render, &request);
        if (st != JPDF_OK) return st;
        jpdf::WriteRequest write_request;
        if ((st = jpdf::parse_write_options(write, &write_request)) != JPDF_OK) return st;
        std::shared_ptr<jpdf::PixelStorage> pixels;
        if ((st = render_pixels(page, request, &pixels)) != JPDF_OK) return st;
        return jpdf::write_image_file(*pixels, path_utf8, write_request);
    });
}

}  // extern "C"
