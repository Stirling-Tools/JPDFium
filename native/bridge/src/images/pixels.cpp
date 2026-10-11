// pixels.cpp - pixel format conversion and dithering for owned bitmaps.

#include "pixels.h"

#include <algorithm>
#include <cstddef>
#include <cstring>
#include <vector>

#include "jpdfium_api.h"

#if defined(__aarch64__) || defined(_M_ARM64)
#include <arm_neon.h>
#endif

// The NEON path is the aarch64 default; JPDF_PIXELS_NEON may be overridden to 0 to build the
// portable scalar kernel (used to measure the vector gain against an otherwise identical binary).
#ifndef JPDF_PIXELS_NEON
#if defined(__aarch64__) || defined(_M_ARM64)
#define JPDF_PIXELS_NEON 1
#else
#define JPDF_PIXELS_NEON 0
#endif
#endif

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
    // In-place narrowing of tightly packed straight RGBA to tightly packed RGB. Forward order is
    // safe: a block is fully read before it is written, and no write lands on a byte a later block
    // still has to read (3 * (i + n) + 3n <= 4 * (i + n) for every i and vector width n).
    const size_t count = static_cast<size_t>(width) * static_cast<size_t>(height);
    size_t i = 0;
#if JPDF_PIXELS_NEON
    for (; i + 16 <= count; i += 16) {
        const uint8x16x4_t px = vld4q_u8(data + i * 4);
        uint8x16x3_t rgb;
        rgb.val[0] = px.val[0];
        rgb.val[1] = px.val[1];
        rgb.val[2] = px.val[2];
        vst3q_u8(data + i * 3, rgb);
    }
#endif
    for (; i < count; ++i) {
        data[i * 3] = data[i * 4];
        data[i * 3 + 1] = data[i * 4 + 1];
        data[i * 3 + 2] = data[i * 4 + 2];
    }
}

#if JPDF_PIXELS_NEON
namespace {

// ITU-R 601-2 luma for eight pixels, widened to 32-bit lanes so the coefficients cannot wrap.
inline uint8x8_t luma8(uint8x8_t r, uint8x8_t g, uint8x8_t b) noexcept {
    const uint16x8_t r16 = vmovl_u8(r), g16 = vmovl_u8(g), b16 = vmovl_u8(b);
    const uint32x4_t rlo = vmovl_u16(vget_low_u16(r16)), rhi = vmovl_u16(vget_high_u16(r16));
    const uint32x4_t glo = vmovl_u16(vget_low_u16(g16)), ghi = vmovl_u16(vget_high_u16(g16));
    const uint32x4_t blo = vmovl_u16(vget_low_u16(b16)), bhi = vmovl_u16(vget_high_u16(b16));
    const uint32x4_t round = vdupq_n_u32(0x8000u);
    uint32x4_t lo = vaddq_u32(vmulq_n_u32(rlo, 19595u), vmulq_n_u32(glo, 38470u));
    lo = vaddq_u32(vaddq_u32(lo, vmulq_n_u32(blo, 7471u)), round);
    uint32x4_t hi = vaddq_u32(vmulq_n_u32(rhi, 19595u), vmulq_n_u32(ghi, 38470u));
    hi = vaddq_u32(vaddq_u32(hi, vmulq_n_u32(bhi, 7471u)), round);
    return vmovn_u16(vcombine_u16(vmovn_u32(vshrq_n_u32(lo, 16)), vmovn_u32(vshrq_n_u32(hi, 16))));
}

}  // namespace
#endif

void rgba_to_gray(uint8_t* data, int32_t width, int32_t height) noexcept {
    // Pillow-compatible ITU-R 601-2 luma; the vector path reproduces the scalar formula exactly.
    const size_t count = static_cast<size_t>(width) * static_cast<size_t>(height);
    size_t i = 0;
#if JPDF_PIXELS_NEON
    for (; i + 16 <= count; i += 16) {
        const uint8x16x4_t px = vld4q_u8(data + i * 4);
        const uint8x8_t lo =
            luma8(vget_low_u8(px.val[0]), vget_low_u8(px.val[1]), vget_low_u8(px.val[2]));
        const uint8x8_t hi =
            luma8(vget_high_u8(px.val[0]), vget_high_u8(px.val[1]), vget_high_u8(px.val[2]));
        vst1q_u8(data + i, vcombine_u8(lo, hi));
    }
#endif
    for (; i < count; ++i) {
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
