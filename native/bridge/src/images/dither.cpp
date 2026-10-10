// dither.cpp - Pillow-compatible Floyd-Steinberg error diffusion to 1-bit.

#include "dither.h"

#include <algorithm>
#include <cstddef>
#include <cstring>
#include <vector>

namespace jpdf {

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
