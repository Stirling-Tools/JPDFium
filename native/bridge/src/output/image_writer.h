// image_writer.h - transactionally published image and document files.
#pragma once

#include <cstddef>
#include <cstdint>

#include "images/pixels.h"
#include "jpdfium_api.h"

namespace jpdf {

struct WriteRequest {
    bool durable = false;
    int32_t image_format = JPDF_IMAGE_PNG;
    int32_t png_compression = 6;
    uint64_t max_bytes = 0;
};

// NULL options select the defaults above.
jpdf_status parse_write_options(const jpdf_write_options* options, WriteRequest* out) noexcept;
// For non-image output: image_format and png_compression must be 0.
jpdf_status parse_document_write_options(const jpdf_write_options* options,
                                         WriteRequest* out) noexcept;

// Borrowed pixels for one PNG; channels 0 means 1-bit gray packed MSB first, 1 = white.
struct PngImage {
    const uint8_t* data = nullptr;
    int32_t width = 0;
    int32_t height = 0;
    int32_t stride = 0;
    int32_t channels = 0;
    double dpi_x = 0;
    double dpi_y = 0;
};

// Never touches PDFium, so callers run it outside the domain.
jpdf_status write_png_file(const PngImage& image, const char* path_utf8,
                           const WriteRequest& request) noexcept;
jpdf_status write_image_file(const PixelStorage& pixels, const char* path_utf8,
                             const WriteRequest& request) noexcept;
// Publishes bytes that are already complete, such as a serialized PDF.
jpdf_status write_bytes_file(const uint8_t* data, size_t size, const char* path_utf8,
                             const WriteRequest& request) noexcept;

}  // namespace jpdf

// Streaming encoders exported by the jpdfium-impl Rust crate.
extern "C" {
// Receives encoder output in order; a non-zero return aborts the encoder.
typedef int32_t (*jpdfium_write_sink)(void* ctx, const uint8_t* data, size_t len);

// Both return 0, -1 for invalid arguments, -2 when the sink aborted, -3 on an internal failure.
int32_t jpdfium_rust_write_png(const uint8_t* pixels, int32_t width, int32_t height, int32_t stride,
                               int32_t channels, uint32_t ppm_x, uint32_t ppm_y,
                               int32_t compression, jpdfium_write_sink sink, void* ctx);
int32_t jpdfium_rust_zlib_compress(const uint8_t* data, size_t len, int32_t level,
                                   jpdfium_write_sink sink, void* ctx);

// Returns a buffer of size bytes for decoded pixels, or null to refuse.
typedef uint8_t* (*jpdfium_alloc_fn)(void* ctx, size_t size);

typedef struct jpdfium_decoded_image {
    uint32_t width;
    uint32_t height;
    uint32_t channels;  // 1 gray or 3 RGB, rows tightly packed
    uint32_t ppm_x;     // pixels per metre from pHYs, 0 if absent
    uint32_t ppm_y;
    uint8_t* pixels;
} jpdfium_decoded_image;

// 0, -1 not a supported PNG, -2 allocation refused, -3 internal, -4 over max_pixels.
int32_t jpdfium_rust_decode_png(const uint8_t* data, size_t len, uint64_t max_pixels,
                                jpdfium_alloc_fn alloc, void* ctx, jpdfium_decoded_image* out);
}
