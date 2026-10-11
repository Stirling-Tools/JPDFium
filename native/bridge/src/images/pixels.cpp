// pixels.cpp - pixel format conversion and dithering for owned bitmaps.

#include "pixels.h"

#include <algorithm>
#include <cstddef>
#include <cstring>
#include <vector>

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

// Pillow-compatible Floyd-Steinberg error diffusion to 1-bit.
void dither_to_bits(const uint8_t* gray, int32_t width, int32_t height, int32_t gray_stride,
                    uint8_t* bits, int32_t bits_stride) {
    // errors[x + 1] carries the next row's error for column x; C division truncates like Pillow.
    std::vector<int> errors(static_cast<size_t>(width) + 1, 0);
    for (int32_t y = 0; y < height; ++y) {
        const uint8_t* in = gray + static_cast<ptrdiff_t>(y) * gray_stride;
        uint8_t* out = bits + static_cast<ptrdiff_t>(y) * bits_stride;
        std::memset(out, 0, static_cast<size_t>(bits_stride));
        int l = 0, l0 = 0, l1 = 0;
        int32_t x = 0;
        for (; x < width; ++x) {
            l = std::clamp(in[x] + (l + errors[static_cast<size_t>(x) + 1]) / 16, 0, 255);
            const int white = l > 128 ? 255 : 0;
            if (white) out[x >> 3] |= static_cast<uint8_t>(0x80u >> (x & 7));
            l -= white;
            const int l2 = l;
            const int d2 = l + l;
            l += d2;
            errors[static_cast<size_t>(x)] = l + l0;
            l += d2;
            l0 = l + l1;
            l1 = l2;
            l += d2;
        }
        errors[static_cast<size_t>(x)] = l0;
    }
}

}  // namespace jpdf
