// cv_resize.cpp - port of OpenCV 4.10 resize (INTER_LINEAR, 8-bit).
// Split out of the former cv_compat.cpp. Derived from OpenCV (Apache-2.0); see NOTICE.

#include <algorithm>
#include <array>
#include <cfloat>
#include <climits>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <numbers>

#include "cv_compat.h"
#include "cv_compat_internal.h"

namespace jpdf::ocr {

using detail::clip_row;
using detail::saturate_short;

namespace {

// ---------------------------------------------------------------------------------------------
// resize, INTER_LINEAR, 8-bit (resize.cpp: hal::resize, HResizeLinear, VResizeLinear<uchar>)

constexpr int kResizeCoefBits = 11;
constexpr int kResizeCoefScale = 1 << kResizeCoefBits;

}  // namespace

void resize_linear(const uint8_t* src, int sw, int sh, int sstride, int cn, uint8_t* dst, int dw,
                   int dh) {
    if (sw == dw && sh == dh) {
        for (int y = 0; y < sh; ++y) {
            std::memcpy(dst + static_cast<size_t>(y) * dw * cn,
                        src + static_cast<size_t>(y) * sstride, static_cast<size_t>(sw) * cn);
        }
        return;
    }
    // OpenCV promotes INTER_LINEAR to INTER_AREA when the reduction is exactly
    // 2x on both axes, so each destination pixel is the rounded mean of the 2x2
    // source block; the generic bilinear path below would not reproduce that.
    if (sw == 2 * dw && sh == 2 * dh) {
        const int width = dw * cn;
        for (int dy = 0; dy < dh; ++dy) {
            const uint8_t* r0 = src + static_cast<size_t>(2 * dy) * sstride;
            const uint8_t* r1 = r0 + sstride;
            uint8_t* d = dst + static_cast<size_t>(dy) * width;
            for (int dx = 0; dx < dw; ++dx) {
                const size_t s0 = static_cast<size_t>(2 * dx) * cn;
                const size_t s1 = s0 + cn;
                for (int k = 0; k < cn; ++k) {
                    const int sum = r0[s0 + k] + r0[s1 + k] + r1[s0 + k] + r1[s1 + k];
                    d[static_cast<size_t>(dx) * cn + k] = static_cast<uint8_t>((sum + 2) >> 2);
                }
            }
        }
        return;
    }
    const double inv_scale_x = static_cast<double>(dw) / sw;
    const double inv_scale_y = static_cast<double>(dh) / sh;
    const double scale_x = 1. / inv_scale_x;
    const double scale_y = 1. / inv_scale_y;

    std::vector<int> xofs(static_cast<size_t>(dw));
    std::vector<short> ialpha(static_cast<size_t>(dw) * 2);
    for (int dx = 0; dx < dw; ++dx) {
        float fx = static_cast<float>((dx + 0.5) * scale_x - 0.5);
        int sx = static_cast<int>(std::floor(fx));
        fx -= static_cast<float>(sx);
        if (sx < 0) fx = 0, sx = 0;
        if (sx >= sw - 1) fx = 0, sx = sw - 1;
        xofs[dx] = sx;
        const size_t at = static_cast<size_t>(dx) * 2;
        ialpha[at] = saturate_short((1.f - fx) * kResizeCoefScale);
        ialpha[at + 1] = saturate_short(fx * kResizeCoefScale);
    }

    const int width = dw * cn;
    std::vector<int> row0(static_cast<size_t>(width)), row1(static_cast<size_t>(width));
    auto hresize = [&](const uint8_t* s, int* d) {
        for (int dx = 0; dx < dw; ++dx) {
            const int sx = xofs[dx];
            const int a0 = ialpha[static_cast<size_t>(dx) * 2];
            const int a1 = ialpha[static_cast<size_t>(dx) * 2 + 1];
            for (int k = 0; k < cn; ++k) {
                if (sx + 1 < sw) {
                    d[dx * cn + k] = s[sx * cn + k] * a0 + s[(sx + 1) * cn + k] * a1;
                } else {
                    d[dx * cn + k] = s[sx * cn + k] * kResizeCoefScale;
                }
            }
        }
    };

    // Consecutive output rows usually share their two source rows, so each
    // source row is horizontally resized once instead of once per output row.
    int cached0 = -1;
    int cached1 = -1;
    for (int dy = 0; dy < dh; ++dy) {
        const float fy = static_cast<float>((dy + 0.5) * scale_y - 0.5);
        const int sy = static_cast<int>(std::floor(fy));
        const float frac = fy - static_cast<float>(sy);
        const int b0 = saturate_short((1.f - frac) * kResizeCoefScale);
        const int b1 = saturate_short(frac * kResizeCoefScale);
        const int sa = clip_row(sy, sh);
        const int sb = clip_row(sy + 1, sh);
        if (sa == cached1 && sb == cached0) {
            row0.swap(row1);
            std::swap(cached0, cached1);
        }
        if (sa != cached0) {
            hresize(src + static_cast<size_t>(sa) * sstride, row0.data());
            cached0 = sa;
        }
        if (sb != cached1) {
            hresize(src + static_cast<size_t>(sb) * sstride, row1.data());
            cached1 = sb;
        }
        uint8_t* d = dst + static_cast<size_t>(dy) * width;
        for (int x = 0; x < width; ++x) {
            d[x] = static_cast<uint8_t>(
                (((b0 * (row0[x] >> 4)) >> 16) + ((b1 * (row1[x] >> 4)) >> 16) + 2) >> 2);
        }
    }
}

}  // namespace jpdf::ocr
