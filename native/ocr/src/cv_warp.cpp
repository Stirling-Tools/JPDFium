// cv_warp.cpp - port of OpenCV 4.10 warpPerspective (INTER_CUBIC, BORDER_REPLICATE).
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

using detail::round_int;
using detail::saturate_short;

// warpPerspective, INTER_CUBIC, BORDER_REPLICATE (imgwarp.cpp, lapack.cpp, matrix_decomp.cpp)

namespace {

constexpr int kInterBits = 5;
constexpr int kInterTabSize = 1 << kInterBits;
constexpr int kRemapCoefBits = 15;
constexpr int kRemapCoefScale = 1 << kRemapCoefBits;

void interpolate_cubic(float x, float* coeffs) {
    const float A = -0.75f;
    coeffs[0] = ((A * (x + 1) - 5 * A) * (x + 1) + 8 * A) * (x + 1) - 4 * A;
    coeffs[1] = ((A + 2) * x - (A + 3)) * x * x + 1;
    coeffs[2] = ((A + 2) * (1 - x) - (A + 3)) * (1 - x) * (1 - x) + 1;
    coeffs[3] = 1.f - coeffs[0] - coeffs[1] - coeffs[2];
}

constexpr size_t kInterTabSize2 = size_t{kInterTabSize} * kInterTabSize;
using CubicTable = std::array<std::array<short, 16>, kInterTabSize2>;

// initInterTab2D(INTER_CUBIC, fixpt): products of the 1-D taps, nudged so each sums to 1 << 15.
const CubicTable& cubic_table() {
    static const CubicTable table = [] {
        CubicTable t{};
        float tab1d[kInterTabSize * 4];
        const float scale = 1.f / kInterTabSize;
        for (int i = 0; i < kInterTabSize; ++i) {
            interpolate_cubic(static_cast<float>(i) * scale, tab1d + static_cast<size_t>(i) * 4);
        }
        for (int i = 0; i < kInterTabSize; ++i) {
            for (int j = 0; j < kInterTabSize; ++j) {
                short* itab =
                    t[static_cast<size_t>(i) * kInterTabSize + static_cast<size_t>(j)].data();
                int isum = 0;
                for (int k1 = 0; k1 < 4; ++k1) {
                    const float vy = tab1d[i * 4 + k1];
                    for (int k2 = 0; k2 < 4; ++k2) {
                        const float v = vy * tab1d[j * 4 + k2];
                        isum += itab[k1 * 4 + k2] = saturate_short(v * kRemapCoefScale);
                    }
                }
                if (isum != kRemapCoefScale) {
                    const int diff = isum - kRemapCoefScale;
                    int mk1 = 2, mk2 = 2, big1 = 2, big2 = 2;
                    for (int k1 = 2; k1 < 4; ++k1) {
                        for (int k2 = 2; k2 < 4; ++k2) {
                            if (itab[k1 * 4 + k2] < itab[mk1 * 4 + mk2]) {
                                mk1 = k1, mk2 = k2;
                            } else if (itab[k1 * 4 + k2] > itab[big1 * 4 + big2]) {
                                big1 = k1, big2 = k2;
                            }
                        }
                    }
                    if (diff < 0) {
                        itab[big1 * 4 + big2] = static_cast<short>(itab[big1 * 4 + big2] - diff);
                    } else {
                        itab[mk1 * 4 + mk2] = static_cast<short>(itab[mk1 * 4 + mk2] - diff);
                    }
                }
            }
        }
        return t;
    }();
    return table;
}

// hal::LU64f with partial pivoting; solves a*x = b in place, b becoming x.
bool lu_solve(double a[8][8], double b[8]) {
    const double eps = DBL_EPSILON * 100;
    const int m = 8;
    for (int i = 0; i < m; ++i) {
        int k = i;
        for (int j = i + 1; j < m; ++j) {
            if (std::abs(a[j][i]) > std::abs(a[k][i])) k = j;
        }
        if (std::abs(a[k][i]) < eps) return false;
        if (k != i) {
            for (int j = i; j < m; ++j) std::swap(a[i][j], a[k][j]);
            std::swap(b[i], b[k]);
        }
        const double d = -1 / a[i][i];
        for (int j = i + 1; j < m; ++j) {
            const double alpha = a[j][i] * d;
            for (int c = i + 1; c < m; ++c) a[j][c] += alpha * a[i][c];
            b[j] += alpha * b[i];
        }
    }
    for (int i = m - 1; i >= 0; --i) {
        double s = b[i];
        for (int c = i + 1; c < m; ++c) s -= a[i][c] * b[c];
        b[i] = s / a[i][i];
    }
    return true;
}

bool perspective_transform(const Point2f src[4], const Point2f dst[4], double m[9]) {
    double a[8][8], b[8];
    for (int i = 0; i < 4; ++i) {
        a[i][0] = a[i + 4][3] = src[i].x;
        a[i][1] = a[i + 4][4] = src[i].y;
        a[i][2] = a[i + 4][5] = 1;
        a[i][3] = a[i][4] = a[i][5] = a[i + 4][0] = a[i + 4][1] = a[i + 4][2] = 0;
        a[i][6] = -src[i].x * dst[i].x;
        a[i][7] = -src[i].y * dst[i].x;
        a[i + 4][6] = -src[i].x * dst[i].y;
        a[i + 4][7] = -src[i].y * dst[i].y;
        b[i] = dst[i].x;
        b[i + 4] = dst[i].y;
    }
    if (!lu_solve(a, b)) return false;
    std::copy(b, b + 8, m);
    m[8] = 1.;
    return true;
}

// cv::invert(DECOMP_LU) for 3x3 doubles: the explicit cofactor formula.
bool invert3(const double s[9], double d[9]) {
    auto S = [&](int r, int c) { return s[r * 3 + c]; };
    double det = S(0, 0) * (S(1, 1) * S(2, 2) - S(1, 2) * S(2, 1)) -
                 S(0, 1) * (S(1, 0) * S(2, 2) - S(1, 2) * S(2, 0)) +
                 S(0, 2) * (S(1, 0) * S(2, 1) - S(1, 1) * S(2, 0));
    if (det == 0.) return false;
    det = 1. / det;
    d[0] = (S(1, 1) * S(2, 2) - S(1, 2) * S(2, 1)) * det;
    d[1] = (S(0, 2) * S(2, 1) - S(0, 1) * S(2, 2)) * det;
    d[2] = (S(0, 1) * S(1, 2) - S(0, 2) * S(1, 1)) * det;
    d[3] = (S(1, 2) * S(2, 0) - S(1, 0) * S(2, 2)) * det;
    d[4] = (S(0, 0) * S(2, 2) - S(0, 2) * S(2, 0)) * det;
    d[5] = (S(0, 2) * S(1, 0) - S(0, 0) * S(1, 2)) * det;
    d[6] = (S(1, 0) * S(2, 1) - S(1, 1) * S(2, 0)) * det;
    d[7] = (S(0, 1) * S(2, 0) - S(0, 0) * S(2, 1)) * det;
    d[8] = (S(0, 0) * S(1, 1) - S(0, 1) * S(1, 0)) * det;
    return true;
}

inline int saturate_int(double v) {
    return round_int(
        std::max(static_cast<double>(INT_MIN), std::min(static_cast<double>(INT_MAX), v)));
}

}  // namespace

bool warp_perspective_cubic(const uint8_t* src, int sw, int sh, int sstride, int cn,
                            const Point2f quad[4], int dw, int dh, uint8_t* dst) {
    const Point2f rect[4] = {{0.f, 0.f},
                             {static_cast<float>(dw), 0.f},
                             {static_cast<float>(dw), static_cast<float>(dh)},
                             {0.f, static_cast<float>(dh)}};
    double forward[9], m[9];
    if (!perspective_transform(quad, rect, forward) || !invert3(forward, m)) return false;
    const CubicTable& table = cubic_table();

    // WarpPerspectiveInvoker evaluates the map relative to 32x32-pixel blocks; mirror its origin.
    if (dw <= 0 || dh <= 0) return false;
    const int bh0 = std::min(16, dh);
    const int bw0 = std::min(1024 / bh0, dw);
    const unsigned width1 = static_cast<unsigned>(std::max(sw - 3, 0));
    const unsigned height1 = static_cast<unsigned>(std::max(sh - 3, 0));
    for (int dy = 0; dy < dh; ++dy) {
        uint8_t* drow = dst + static_cast<size_t>(dy) * dw * cn;
        for (int x0 = 0; x0 < dw; x0 += bw0) {
            const int bw = std::min(bw0, dw - x0);
            const double X0 = m[0] * x0 + m[1] * dy + m[2];
            const double Y0 = m[3] * x0 + m[4] * dy + m[5];
            const double W0 = m[6] * x0 + m[7] * dy + m[8];
            for (int x1 = 0; x1 < bw; ++x1) {
                double W = W0 + m[6] * x1;
                W = W != 0.0 ? kInterTabSize / W : 0;
                const int X = saturate_int((X0 + m[0] * x1) * W);
                const int Y = saturate_int((Y0 + m[3] * x1) * W);
                const int sx = saturate_short(X >> kInterBits) - 1;
                const int sy = saturate_short(Y >> kInterBits) - 1;
                const short* w =
                    table[static_cast<size_t>(Y & (kInterTabSize - 1)) * kInterTabSize +
                          static_cast<size_t>(X & (kInterTabSize - 1))]
                        .data();
                uint8_t* d = drow + static_cast<size_t>(x0 + x1) * cn;
                int xs[4], ys[4];
                const bool inside =
                    static_cast<unsigned>(sx) < width1 && static_cast<unsigned>(sy) < height1;
                for (int i = 0; i < 4; ++i) {
                    xs[i] = inside ? sx + i : std::clamp(sx + i, 0, sw - 1);
                    ys[i] = inside ? sy + i : std::clamp(sy + i, 0, sh - 1);
                }
                for (int k = 0; k < cn; ++k) {
                    int sum = 0;
                    for (int i = 0; i < 4; ++i) {
                        const uint8_t* row = src + static_cast<size_t>(ys[i]) * sstride;
                        for (int j = 0; j < 4; ++j) sum += row[xs[j] * cn + k] * w[i * 4 + j];
                    }
                    d[k] = static_cast<uint8_t>(
                        std::clamp((sum + (1 << (kRemapCoefBits - 1))) >> kRemapCoefBits, 0, 255));
                }
            }
        }
    }
    return true;
}

}  // namespace jpdf::ocr
