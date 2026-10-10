// pixels.cpp - pixel format conversion for owned bitmaps.

#include "pixels.h"

#include <cstddef>

#include "jpdfium_api.h"

namespace jpdf {

int32_t channels_for(int32_t pixel_format) noexcept {
    switch (pixel_format) {
        case JPDF_PIXEL_RGBA8:
            return 4;
        case JPDF_PIXEL_RGB8:
            return 3;
        case JPDF_PIXEL_GRAY8:
            return 1;
        default:
            return 0;
    }
}

void rgba_to_rgb(uint8_t* data, int32_t width, int32_t height) noexcept {
    // Forward copy is safe in place: the write index never passes the read index.
    const size_t count = static_cast<size_t>(width) * static_cast<size_t>(height);
    for (size_t i = 0; i < count; ++i) {
        data[i * 3] = data[i * 4];
        data[i * 3 + 1] = data[i * 4 + 1];
        data[i * 3 + 2] = data[i * 4 + 2];
    }
}

void rgba_to_gray(uint8_t* data, int32_t width, int32_t height) noexcept {
    const size_t count = static_cast<size_t>(width) * static_cast<size_t>(height);
    for (size_t i = 0; i < count; ++i) {
        const uint32_t r = data[i * 4];
        const uint32_t g = data[i * 4 + 1];
        const uint32_t b = data[i * 4 + 2];
        data[i] = static_cast<uint8_t>((r * 19595 + g * 38470 + b * 7471 + 0x8000) >> 16);
    }
}

}  // namespace jpdf
