// api_image.cpp - jpdf_image_load_file: PNG files decoded into owned bitmaps. No PDFium.

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <memory>
#include <system_error>
#include <vector>

#include "jpdfium_api.h"
#include "objects/objects.h"
#include "output/output_transaction.h"
#include "output/rust_codecs.h"
#include "runtime/api_status.h"

namespace {

constexpr uint64_t kDefaultMaxPixels = 268435456ull;  // same ceiling as rendering
constexpr uintmax_t kMaxFileBytes = uintmax_t{1} << 30;

// Receives the decoder's single pixel allocation, so the C++ side owns it however decoding ends.
struct DecodeTarget {
    jpdf::PixelBuffer pixels;
    size_t size = 0;
};

uint8_t* allocate_pixels(void* ctx, size_t size) noexcept {
    auto* target = static_cast<DecodeTarget*>(ctx);
    target->pixels.reset(static_cast<uint8_t*>(std::malloc(size == 0 ? 1 : size)));
    target->size = size;
    return target->pixels.get();
}

jpdf_status read_file(const char* path_utf8, std::vector<uint8_t>* out) {
    const std::filesystem::path path = jpdf::path_from_utf8(path_utf8);
    std::error_code ec;
    const uintmax_t size = std::filesystem::file_size(path, ec);
    if (ec) return jpdf::fail(JPDF_E_IO, "image file not found or unreadable");
    if (size > kMaxFileBytes) return jpdf::fail(JPDF_E_LIMIT, "image file is larger than 1 GiB");
    std::unique_ptr<FILE, int (*)(FILE*)> file(
#if defined(_WIN32)
        _wfopen(path.c_str(), L"rb"),
#else
        std::fopen(path.c_str(), "rb"),
#endif
        &std::fclose);
    if (!file) return jpdf::fail(JPDF_E_IO, "cannot open image file");
    out->resize(static_cast<size_t>(size));
    if (size > 0 && std::fread(out->data(), 1, out->size(), file.get()) != out->size()) {
        return jpdf::fail(JPDF_E_IO, "cannot read image file");
    }
    return JPDF_OK;
}

double dpi_from_ppm(uint32_t ppm) noexcept {
    return ppm == 0 ? 0.0 : ppm * 0.0254;
}

}  // namespace

extern "C" {

jpdf_status jpdf_image_load_file(const char* path_utf8, const jpdf_image_load_options* options,
                                 jpdf_handle* out_bitmap) noexcept {
    return jpdf::guarded([&] {
        if (!path_utf8 || !*path_utf8 || !out_bitmap) {
            return jpdf::fail(JPDF_E_INVALID_ARGUMENT, "path and out_bitmap are required");
        }
        uint64_t max_pixels = kDefaultMaxPixels;
        if (options) {
            jpdf_status st = jpdf::check_input_struct(
                options, options->struct_size, sizeof(jpdf_image_load_options),
                sizeof(jpdf_image_load_options), "jpdf_image_load_options");
            if (st != JPDF_OK) return st;
            if (options->flags != 0) {
                return jpdf::fail(JPDF_E_UNSUPPORTED, "jpdf_image_load_options.flags must be 0");
            }
            if (options->max_pixels != 0) max_pixels = options->max_pixels;
        }
        std::vector<uint8_t> bytes;
        jpdf_status st = read_file(path_utf8, &bytes);
        if (st != JPDF_OK) return st;

        DecodeTarget target;
        jpdfium_decoded_image image{};
        const int32_t rc = jpdfium_rust_decode_png(bytes.data(), bytes.size(), max_pixels,
                                                   allocate_pixels, &target, &image);
        if (rc == -4) return jpdf::fail(JPDF_E_LIMIT, "image exceeds max_pixels");
        if (rc == -2) return jpdf::fail(JPDF_E_NO_MEMORY, "cannot allocate decoded pixels");
        if (rc == -1) return jpdf::fail(JPDF_E_FORMAT, "not a PNG this library can decode");
        if (rc != 0 || !target.pixels) return jpdf::fail(JPDF_E_INTERNAL, "PNG decoder failed");
        if (image.width > INT32_MAX / image.channels || image.height > INT32_MAX) {
            return jpdf::fail(JPDF_E_LIMIT, "image dimensions exceed the bitmap range");
        }

        auto pixels = std::make_shared<jpdf::PixelStorage>();
        pixels->data = std::move(target.pixels);
        pixels->byte_size = target.size;
        pixels->width = static_cast<int32_t>(image.width);
        pixels->height = static_cast<int32_t>(image.height);
        pixels->stride = static_cast<int32_t>(image.width * image.channels);
        pixels->pixel_format = image.channels == 1 ? JPDF_PIXEL_GRAY8 : JPDF_PIXEL_RGB8;
        pixels->dpi_x = dpi_from_ppm(image.ppm_x);
        pixels->dpi_y = dpi_from_ppm(image.ppm_y);
        jpdf_handle token = 0;
        st =
            jpdf::objects().insert(std::make_shared<jpdf::BitmapObject>(std::move(pixels)), &token);
        if (st != JPDF_OK) return jpdf::fail(st, "object table rejected the bitmap");
        *out_bitmap = token;
        return JPDF_OK;
    });
}

}  // extern "C"
