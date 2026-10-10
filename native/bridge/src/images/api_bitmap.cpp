// api_bitmap.cpp - jpdf_bitmap_* and jpdf_view_*: owned pixels, never inside the PDFium domain.

#include <cstring>
#include <memory>

#include "jpdfium_api.h"
#include "objects/objects.h"
#include "output/image_writer.h"
#include "runtime/api_status.h"

namespace {

jpdf_status resolve_bitmap(jpdf_handle bitmap, std::shared_ptr<jpdf::BitmapObject>* out) {
    jpdf_status st = jpdf::objects().resolve_as(bitmap, jpdf::ObjectType::Bitmap, out);
    return st == JPDF_OK ? st : jpdf::fail(st, "bitmap handle rejected");
}

}  // namespace

extern "C" {

jpdf_status jpdf_bitmap_get_info(jpdf_handle bitmap, jpdf_bitmap_info* out) noexcept {
    return jpdf::guarded([&] {
        if (!out) return jpdf::fail(JPDF_E_INVALID_ARGUMENT, "null output");
        jpdf_status st = jpdf::check_output_struct(out->struct_size, sizeof(jpdf_bitmap_info),
                                                   "jpdf_bitmap_info");
        if (st != JPDF_OK) return st;
        std::shared_ptr<jpdf::BitmapObject> object;
        if ((st = resolve_bitmap(bitmap, &object)) != JPDF_OK) return st;
        const jpdf::PixelStorage& px = *object->pixels;
        out->pixel_format = px.pixel_format;
        out->width = px.width;
        out->height = px.height;
        out->stride = px.stride;
        out->reserved0 = 0;
        out->dpi_x = px.dpi_x;
        out->dpi_y = px.dpi_y;
        out->byte_size = px.byte_size;
        return JPDF_OK;
    });
}

jpdf_status jpdf_bitmap_copy_pixels(jpdf_handle bitmap, uint8_t* dst, uint64_t capacity,
                                    uint64_t* out_written) noexcept {
    return jpdf::guarded([&] {
        if (!out_written) return jpdf::fail(JPDF_E_INVALID_ARGUMENT, "null out_written");
        std::shared_ptr<jpdf::BitmapObject> object;
        jpdf_status st = resolve_bitmap(bitmap, &object);
        if (st != JPDF_OK) return st;
        const jpdf::PixelStorage& px = *object->pixels;
        *out_written = px.byte_size;
        if (!dst || capacity < px.byte_size) {
            return jpdf::fail(JPDF_E_BUFFER_TOO_SMALL, "destination smaller than byte_size");
        }
        std::memcpy(dst, px.data.get(), static_cast<size_t>(px.byte_size));
        return JPDF_OK;
    });
}

jpdf_status jpdf_bitmap_map(jpdf_handle bitmap, jpdf_bitmap_view* out_view) noexcept {
    return jpdf::guarded([&] {
        if (!out_view) return jpdf::fail(JPDF_E_INVALID_ARGUMENT, "null output");
        jpdf_status st = jpdf::check_output_struct(out_view->struct_size, sizeof(jpdf_bitmap_view),
                                                   "jpdf_bitmap_view");
        if (st != JPDF_OK) return st;
        std::shared_ptr<jpdf::BitmapObject> object;
        if ((st = resolve_bitmap(bitmap, &object)) != JPDF_OK) return st;
        auto view = std::make_shared<jpdf::ViewObject>(object->pixels);
        jpdf_handle token = 0;
        st = jpdf::objects().insert(view, &token);
        if (st != JPDF_OK) return jpdf::fail(st, "object table rejected the view");
        const jpdf::PixelStorage& px = *view->pixels;
        out_view->reserved0 = 0;
        out_view->view = token;
        out_view->data = px.data.get();
        out_view->byte_size = px.byte_size;
        out_view->width = px.width;
        out_view->height = px.height;
        out_view->stride = px.stride;
        out_view->pixel_format = px.pixel_format;
        return JPDF_OK;
    });
}

jpdf_status jpdf_view_release(jpdf_handle view) noexcept {
    return jpdf::guarded([&] {
        jpdf_status st = jpdf::objects().retire(view, jpdf::ObjectType::View, nullptr);
        return st == JPDF_OK ? st : jpdf::fail(st, "view handle rejected");
    });
}

jpdf_status jpdf_bitmap_write_file(jpdf_handle bitmap, const char* path_utf8,
                                   const jpdf_write_options* options) noexcept {
    return jpdf::guarded([&] {
        if (!path_utf8 || !*path_utf8) return jpdf::fail(JPDF_E_INVALID_ARGUMENT, "empty path");
        jpdf::WriteRequest request;
        jpdf_status st = jpdf::parse_write_options(options, &request);
        if (st != JPDF_OK) return st;
        // The resolved reference is the lease: a concurrent close cannot free these pixels.
        std::shared_ptr<jpdf::BitmapObject> object;
        if ((st = resolve_bitmap(bitmap, &object)) != JPDF_OK) return st;
        return jpdf::write_image_file(*object->pixels, path_utf8, request);
    });
}

jpdf_status jpdf_bitmap_close(jpdf_handle bitmap) noexcept {
    return jpdf::guarded([&] {
        jpdf_status st = jpdf::objects().retire(bitmap, jpdf::ObjectType::Bitmap, nullptr);
        return st == JPDF_OK ? st : jpdf::fail(st, "bitmap handle rejected");
    });
}

}  // extern "C"
