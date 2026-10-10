// image_writer.h - encodes owned pixels into a transactionally published image file.
#pragma once

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

// Never touches PDFium, so callers run it outside the domain.
jpdf_status write_image_file(const PixelStorage& pixels, const char* path_utf8,
                             const WriteRequest& request) noexcept;

}  // namespace jpdf
