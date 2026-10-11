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
