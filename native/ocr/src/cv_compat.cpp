// cv_compat.cpp - ports of OpenCV 4.10 resize, warpPerspective, findContours, convexHull,
// minAreaRect, boxPoints and fillPoly. Derived from OpenCV (Apache-2.0); see NOTICE.

#include "cv_compat.h"

#include <algorithm>
#include <array>
#include <cfloat>
#include <climits>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <numbers>

namespace jpdf::ocr {

namespace {

// cvRound / saturate_cast<int>(double): round half to even. std::lrint follows the thread's
// floating-point rounding mode, matching OpenCV's cvRound, so these conversions are intentionally
// NOT mode-independent; the raster/OCR entry points call them under the default mode.
inline int round_int(double v) {
    return static_cast<int>(std::lrint(v));
}

inline short saturate_short(float v) {
    const long r = std::lrint(v);
    return static_cast<short>(std::clamp<long>(r, SHRT_MIN, SHRT_MAX));
}

inline short saturate_short(int v) {
    return static_cast<short>(std::clamp(v, SHRT_MIN, SHRT_MAX));
}

inline int clip_row(int y, int height) {
    return y >= 0 ? (y < height ? y : height - 1) : 0;
}

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

// ---------------------------------------------------------------------------------------------
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

// ---------------------------------------------------------------------------------------------
// findContours, RETR_LIST + CHAIN_APPROX_SIMPLE (contours_new.cpp)

namespace {

constexpr int8_t kRight = static_cast<int8_t>(0x80);
constexpr int8_t kNew = 0x02;
constexpr int kChainDx[8] = {1, 1, 0, -1, -1, -1, 0, 1};
constexpr int kChainDy[8] = {0, -1, -1, -1, 0, 1, 1, 1};

class ContourTracer {
   public:
    ContourTracer(const uint8_t* mask, int w, int h)
        : width_(w + 2), height_(h + 2), image_(static_cast<size_t>(width_) * height_, 0) {
        for (int y = 0; y < h; ++y) {
            for (int x = 0; x < w; ++x) {
                image_[static_cast<size_t>(y + 1) * width_ + x + 1] =
                    mask[static_cast<size_t>(y) * w + x] ? 1 : 0;
            }
        }
    }

    std::vector<std::vector<Point>> run() {
        std::vector<std::vector<Point>> found;
        for (int y = 1; y < height_ - 1; ++y) {
            int prev = 0;
            for (int x = 1; x < width_ - 1; ++x) {
                int p = 0;
                // Marks are signed by design: right-bound pixels are negative, as in OpenCV.
                // NOLINTNEXTLINE(bugprone-signed-char-misuse)
                for (; x < width_ - 1 && (p = at(x, y)) == prev; ++x) {
                }
                if (x >= width_ - 1) break;
                bool hole = false;
                if (!(prev == 0 && p == 1)) {
                    if (p != 0 || prev < 1) {
                        prev = p;
                        continue;
                    }
                    hole = true;
                }
                found.push_back(fetch(x - (hole ? 1 : 0), y, hole));
                // Scanning resumes past the start with the start pixel's new mark as prev.
                prev = at(x, y);  // NOLINT(bugprone-signed-char-misuse)
            }
        }
        std::reverse(found.begin(), found.end());  // RETR_LIST lists the last contour first
        return found;
    }

   private:
    int8_t& at(int x, int y) {
        return image_[static_cast<size_t>(y) * width_ + x];
    }
    int delta(int s) const {
        return kChainDx[s & 7] + kChainDy[s & 7] * width_;
    }

    std::vector<Point> fetch(int x, int y, bool hole) {
        std::vector<Point> points;
        Point pt{x - 1, y - 1};
        int8_t* base = image_.data();
        const ptrdiff_t i0 = static_cast<ptrdiff_t>(y) * width_ + x;
        ptrdiff_t i1 = 0, i3 = 0, i4 = 0;
        int s_end = hole ? 0 : 4;
        int s = s_end;
        do {
            s = (s - 1) & 7;
            i1 = i0 + delta(s);
        } while (base[i1] == 0 && s != s_end);
        if (s == s_end) {
            base[i0] = static_cast<int8_t>(kNew | kRight);
            points.push_back(pt);
            return points;
        }
        i3 = i0;
        int prev_s = s ^ 4;
        for (;;) {
            s_end = s;
            s = std::min(s, 15);
            while (s < 15) {
                ++s;
                i4 = i3 + delta(s);
                if (base[i4] != 0) break;
            }
            s &= 7;
            if (static_cast<unsigned>(s - 1) < static_cast<unsigned>(s_end)) {
                base[i3] = static_cast<int8_t>(kNew | kRight);
            } else if (base[i3] == 1) {
                base[i3] = kNew;
            }
            if (s != prev_s) points.push_back(pt);
            prev_s = s;
            pt.x += kChainDx[s];
            pt.y += kChainDy[s];
            if (i4 == i0 && i3 == i1) break;
            i3 = i4;
            s = (s + 4) & 7;
        }
        return points;
    }

    const int width_;
    const int height_;
    std::vector<int8_t> image_;
};

}  // namespace

std::vector<std::vector<Point>> find_contours(const uint8_t* mask, int w, int h) {
    return ContourTracer(mask, w, h).run();
}

// ---------------------------------------------------------------------------------------------
// convexHull (convhull.cpp, Sklansky, integer points) and minAreaRect (rotcalipers.cpp)

namespace {

inline int sign(long long v) {
    return (v > 0) - (v < 0);
}

int sklansky(const Point* const* array, int start, int end, int* stack, int nsign, int sign2) {
    const int incr = end > start ? 1 : -1;
    int pprev = start, pcur = pprev + incr, pnext = pcur + incr;
    int stacksize = 3;
    if (start == end || (array[start]->x == array[end]->x && array[start]->y == array[end]->y)) {
        stack[0] = start;
        return 1;
    }
    stack[0] = pprev;
    stack[1] = pcur;
    stack[2] = pnext;
    end += incr;
    while (pnext != end) {
        const int cury = array[pcur]->y;
        const int nexty = array[pnext]->y;
        const int by = nexty - cury;
        if (sign(by) != nsign) {
            const int ax = array[pcur]->x - array[pprev]->x;
            const int bx = array[pnext]->x - array[pcur]->x;
            const int ay = cury - array[pprev]->y;
            const long long convexity =
                static_cast<long long>(ay) * bx - static_cast<long long>(ax) * by;
            if (sign(convexity) == sign2 && (ax != 0 || ay != 0)) {
                pprev = pcur;
                pcur = pnext;
                pnext += incr;
                stack[stacksize] = pnext;
                stacksize++;
            } else if (pprev == start) {
                pcur = pnext;
                stack[1] = pcur;
                pnext += incr;
                stack[2] = pnext;
            } else {
                stack[stacksize - 2] = pnext;
                pcur = pprev;
                pprev = stack[stacksize - 4];
                stacksize--;
            }
        } else {
            pnext += incr;
            stack[stacksize - 1] = pnext;
        }
    }
    return --stacksize;
}

// cv::convexHull(points, clockwise=false, returnPoints=true) for integer points.
std::vector<Point> convex_hull(const std::vector<Point>& data) {
    const int total = static_cast<int>(data.size());
    if (total == 0) return {};
    std::vector<const Point*> pointer(static_cast<size_t>(total));
    for (int i = 0; i < total; ++i) pointer[static_cast<size_t>(i)] = &data[static_cast<size_t>(i)];
    std::sort(pointer.begin(), pointer.end(), [](const Point* a, const Point* b) {
        if (a->x != b->x) return a->x < b->x;
        if (a->y != b->y) return a->y < b->y;
        return a < b;
    });
    int miny_ind = 0, maxy_ind = 0;
    for (int i = 1; i < total; ++i) {
        const int y = pointer[static_cast<size_t>(i)]->y;
        if (pointer[static_cast<size_t>(miny_ind)]->y > y) miny_ind = i;
        if (pointer[static_cast<size_t>(maxy_ind)]->y < y) maxy_ind = i;
    }
    std::vector<int> stack(static_cast<size_t>(total) + 2), hullbuf(static_cast<size_t>(total));
    int nout = 0;
    const Point* const* ptr = pointer.data();
    auto index_of = [&](int i) { return static_cast<int>(ptr[i] - data.data()); };
    if (ptr[0]->x == ptr[total - 1]->x && ptr[0]->y == ptr[total - 1]->y) {
        hullbuf[nout++] = 0;
    } else {
        int* tl_stack = stack.data();
        int tl_count = sklansky(ptr, 0, maxy_ind, tl_stack, -1, 1);
        int* tr_stack = stack.data() + tl_count;
        int tr_count = sklansky(ptr, total - 1, maxy_ind, tr_stack, -1, -1);
        std::swap(tl_stack, tr_stack);  // counter-clockwise (clockwise == false)
        std::swap(tl_count, tr_count);
        for (int i = 0; i < tl_count - 1; ++i) hullbuf[nout++] = index_of(tl_stack[i]);
        for (int i = tr_count - 1; i > 0; --i) hullbuf[nout++] = index_of(tr_stack[i]);
        const int stop_idx = tr_count > 2   ? tr_stack[1]
                             : tl_count > 2 ? tl_stack[tl_count - 2]
                                            : -1;

        int* bl_stack = stack.data();
        int bl_count = sklansky(ptr, 0, miny_ind, bl_stack, 1, -1);
        int* br_stack = stack.data() + bl_count;
        int br_count = sklansky(ptr, total - 1, miny_ind, br_stack, 1, 1);
        if (stop_idx >= 0) {
            const int check_idx = bl_count > 2              ? bl_stack[1]
                                  : bl_count + br_count > 2 ? br_stack[2 - bl_count]
                                                            : -1;
            if (check_idx == stop_idx || (check_idx >= 0 && ptr[check_idx]->x == ptr[stop_idx]->x &&
                                          ptr[check_idx]->y == ptr[stop_idx]->y)) {
                bl_count = std::min(bl_count, 2);
                br_count = std::min(br_count, 2);
            }
        }
        for (int i = 0; i < bl_count - 1; ++i) hullbuf[nout++] = index_of(bl_stack[i]);
        for (int i = br_count - 1; i > 0; --i) hullbuf[nout++] = index_of(br_stack[i]);

        // Cyclic shift so the indices ascend or descend, as OpenCV does.
        if (nout >= 3) {
            int min_idx = 0, max_idx = 0, lt = 0;
            for (int i = 1; i < nout; ++i) {
                const int idx = hullbuf[static_cast<size_t>(i)];
                lt += hullbuf[static_cast<size_t>(i - 1)] < idx;
                if (lt > 1 && lt <= i - 2) break;
                if (idx < hullbuf[static_cast<size_t>(min_idx)]) min_idx = i;
                if (idx > hullbuf[static_cast<size_t>(max_idx)]) max_idx = i;
            }
            const int mmdist = std::abs(max_idx - min_idx);
            if ((mmdist == 1 || mmdist == nout - 1) && (lt <= 1 || lt >= nout - 2)) {
                const int ascending = (max_idx + 1) % nout == min_idx;
                const int i0 = ascending ? min_idx : max_idx;
                int j = i0;
                if (i0 > 0) {
                    int i = 0;
                    for (; i < nout; ++i) {
                        const int curr_idx = stack[static_cast<size_t>(i)] =
                            hullbuf[static_cast<size_t>(j)];
                        const int next_j = j + 1 < nout ? j + 1 : 0;
                        const int next_idx = hullbuf[static_cast<size_t>(next_j)];
                        if (i < nout - 1 && (ascending != (curr_idx < next_idx))) break;
                        j = next_j;
                    }
                    if (i == nout) std::copy(stack.begin(), stack.begin() + nout, hullbuf.begin());
                }
            }
        }
    }
    std::vector<Point> hull(static_cast<size_t>(nout));
    for (int i = 0; i < nout; ++i) {
        hull[static_cast<size_t>(i)] = data[static_cast<size_t>(hullbuf[static_cast<size_t>(i)])];
    }
    return hull;
}

void rotate90_ccw(const Point2f& in, Point2f& out) {
    out.x = -in.y;
    out.y = in.x;
}
void rotate90_cw(const Point2f& in, Point2f& out) {
    out.x = in.y;
    out.y = -in.x;
}
void rotate180(const Point2f& in, Point2f& out) {
    out.x = -in.x;
    out.y = -in.y;
}
bool first_vec_is_right(const Point2f& vec1, const Point2f& vec2) {
    Point2f tmp;
    rotate90_cw(vec1, tmp);
    return tmp.x * vec2.x + tmp.y * vec2.y < 0;
}

// rotatingCalipers(CALIPERS_MINAREARECT): corner, then the two side vectors.
void rotating_calipers(const Point2f* points, int n, Point2f out[3]) {
    float minarea = FLT_MAX;
    std::vector<float> inv_vect_length(static_cast<size_t>(n));
    std::vector<Point2f> vect(static_cast<size_t>(n));
    int left = 0, bottom = 0, right = 0, top = 0;
    int seq[4] = {-1, -1, -1, -1};
    Point2f rot_vect[4];
    float base_a = 0;
    float base_b = 0;
    Point2f pt0 = points[0];
    float left_x = pt0.x, right_x = pt0.x, top_y = pt0.y, bottom_y = pt0.y;
    for (int i = 0; i < n; ++i) {
        if (pt0.x < left_x) left_x = pt0.x, left = i;
        if (pt0.x > right_x) right_x = pt0.x, right = i;
        if (pt0.y > top_y) top_y = pt0.y, top = i;
        if (pt0.y < bottom_y) bottom_y = pt0.y, bottom = i;
        const Point2f pt = points[(i + 1) & (i + 1 < n ? -1 : 0)];
        const double dx = pt.x - pt0.x;
        const double dy = pt.y - pt0.y;
        vect[static_cast<size_t>(i)].x = static_cast<float>(dx);
        vect[static_cast<size_t>(i)].y = static_cast<float>(dy);
        inv_vect_length[static_cast<size_t>(i)] =
            static_cast<float>(1. / std::sqrt(dx * dx + dy * dy));
        pt0 = pt;
    }
    // OpenCV also derives the hull's orientation here, only to assert and then overwrite it.
    seq[0] = bottom;
    seq[1] = right;
    seq[2] = top;
    seq[3] = left;
    int best_left = 0, best_bottom = 0;
    float best_a = 0, best_b = 0, best_width = 0, best_height = 0;
    for (int k = 0; k < n; ++k) {
        int main_element = 0;
        rot_vect[0] = vect[static_cast<size_t>(seq[0])];
        rotate90_cw(vect[static_cast<size_t>(seq[1])], rot_vect[1]);
        rotate180(vect[static_cast<size_t>(seq[2])], rot_vect[2]);
        rotate90_ccw(vect[static_cast<size_t>(seq[3])], rot_vect[3]);
        for (int i = 1; i < 4; ++i) {
            if (first_vec_is_right(rot_vect[i], rot_vect[main_element])) main_element = i;
        }
        const int pindex = seq[main_element];
        const float lead_x =
            vect[static_cast<size_t>(pindex)].x * inv_vect_length[static_cast<size_t>(pindex)];
        const float lead_y =
            vect[static_cast<size_t>(pindex)].y * inv_vect_length[static_cast<size_t>(pindex)];
        switch (main_element) {
            case 0:
                base_a = lead_x;
                base_b = lead_y;
                break;
            case 1:
                base_a = lead_y;
                base_b = -lead_x;
                break;
            case 2:
                base_a = -lead_x;
                base_b = -lead_y;
                break;
            default:
                base_a = -lead_y;
                base_b = lead_x;
                break;
        }
        seq[main_element] += 1;
        seq[main_element] = (seq[main_element] == n) ? 0 : seq[main_element];

        float dx = points[seq[1]].x - points[seq[3]].x;
        float dy = points[seq[1]].y - points[seq[3]].y;
        const float width = dx * base_a + dy * base_b;
        dx = points[seq[2]].x - points[seq[0]].x;
        dy = points[seq[2]].y - points[seq[0]].y;
        const float height = -dx * base_b + dy * base_a;
        const float area = width * height;
        if (area <= minarea) {
            minarea = area;
            best_left = seq[3];
            best_a = base_a;
            best_width = width;
            best_b = base_b;
            best_height = height;
            best_bottom = seq[0];
        }
    }
    const float A1 = best_a;
    const float B1 = best_b;
    const float A2 = -best_b;
    const float B2 = best_a;
    const float C1 = A1 * points[best_left].x + points[best_left].y * B1;
    const float C2 = A2 * points[best_bottom].x + points[best_bottom].y * B2;
    const float idet = 1.f / (A1 * B2 - A2 * B1);
    out[0].x = (C1 * B2 - C2 * B1) * idet;
    out[0].y = (A1 * C2 - A2 * C1) * idet;
    out[1].x = A1 * best_width;
    out[1].y = B1 * best_width;
    out[2].x = A2 * best_height;
    out[2].y = B2 * best_height;
}

}  // namespace

RotatedRect min_area_rect(const std::vector<Point>& points) {
    RotatedRect box;
    const std::vector<Point> hull = convex_hull(points);
    std::vector<Point2f> h(hull.size());
    for (size_t i = 0; i < hull.size(); ++i) {
        h[i] = {static_cast<float>(hull[i].x), static_cast<float>(hull[i].y)};
    }
    const int n = static_cast<int>(h.size());
    if (n > 2) {
        Point2f out[3];
        rotating_calipers(h.data(), n, out);
        box.center.x = out[0].x + (out[1].x + out[2].x) * 0.5f;
        box.center.y = out[0].y + (out[1].y + out[2].y) * 0.5f;
        box.width = static_cast<float>(std::sqrt(static_cast<double>(out[1].x) * out[1].x +
                                                 static_cast<double>(out[1].y) * out[1].y));
        box.height = static_cast<float>(std::sqrt(static_cast<double>(out[2].x) * out[2].x +
                                                  static_cast<double>(out[2].y) * out[2].y));
        box.angle = static_cast<float>(
            std::atan2(static_cast<double>(out[1].y), static_cast<double>(out[1].x)));
    } else if (n == 2) {
        box.center.x = (h[0].x + h[1].x) * 0.5f;
        box.center.y = (h[0].y + h[1].y) * 0.5f;
        const double dx = h[1].x - h[0].x;
        const double dy = h[1].y - h[0].y;
        box.width = static_cast<float>(std::sqrt(dx * dx + dy * dy));
        box.height = 0;
        box.angle = static_cast<float>(std::atan2(dy, dx));
    } else if (n == 1) {
        box.center = h[0];
    }
    box.angle = static_cast<float>(box.angle * 180 / std::numbers::pi);
    return box;
}

void box_points(const RotatedRect& rect, Point2f pt[4]) {
    const double angle = rect.angle * std::numbers::pi / 180.;
    const float b = static_cast<float>(std::cos(angle)) * 0.5f;
    const float a = static_cast<float>(std::sin(angle)) * 0.5f;
    pt[0].x = rect.center.x - a * rect.height - b * rect.width;
    pt[0].y = rect.center.y + b * rect.height - a * rect.width;
    pt[1].x = rect.center.x + a * rect.height - b * rect.width;
    pt[1].y = rect.center.y - b * rect.height - a * rect.width;
    pt[2].x = 2 * rect.center.x - pt[0].x;
    pt[2].y = 2 * rect.center.y - pt[0].y;
    pt[3].x = 2 * rect.center.x - pt[1].x;
    pt[3].y = 2 * rect.center.y - pt[1].y;
}

// ---------------------------------------------------------------------------------------------
// fillPoly, LINE_8, shift 0 (drawing.cpp: CollectPolyEdges, FillEdgeCollection, Line)

namespace {

constexpr int kXYShift = 16;
constexpr int64_t kXYOne = int64_t{1} << kXYShift;

struct PolyEdge {
    int y0 = 0, y1 = 0;
    int64_t x = 0, dx = 0;
    PolyEdge* next = nullptr;
};

void set_pixel(uint8_t* mask, int w, int h, int x, int y) {
    if (x >= 0 && x < w && y >= 0 && y < h) mask[static_cast<size_t>(y) * w + x] = 1;
}

// LineIterator with 8-connectivity, left to right; endpoints included.
void draw_line(uint8_t* mask, int w, int h, Point p1, Point p2) {
    int dx = p2.x - p1.x, dy = p2.y - p1.y;
    if (dx < 0) {
        std::swap(p1, p2);
        dx = -dx;
        dy = -dy;
    }
    const int ystep = dy < 0 ? -1 : 1;
    dy = std::abs(dy);
    const bool steep = dy > dx;
    const int major = steep ? dy : dx;
    const int minor = steep ? dx : dy;
    int err = major - (minor + minor);
    const int plus_delta = major + major;
    const int minus_delta = -(minor + minor);
    int x = p1.x, y = p1.y;
    for (int i = 0; i <= major; ++i) {
        set_pixel(mask, w, h, x, y);
        const bool step_minor = err < 0;
        err += minus_delta + (step_minor ? plus_delta : 0);
        if (steep) {
            y += ystep;
            if (step_minor) x += 1;
        } else {
            x += 1;
            if (step_minor) y += ystep;
        }
    }
}

}  // namespace

void fill_poly(uint8_t* mask, int w, int h, const std::vector<Point>& polygon) {
    const int count = static_cast<int>(polygon.size());
    if (count == 0) return;
    std::vector<PolyEdge> edges;
    edges.reserve(static_cast<size_t>(count) + 1);
    Point pt0 = polygon[static_cast<size_t>(count - 1)];
    for (int i = 0; i < count; ++i) {
        const Point pt1 = polygon[static_cast<size_t>(i)];
        draw_line(mask, w, h, pt0, pt1);
        if (pt0.y != pt1.y) {
            const int64_t x0 = (static_cast<int64_t>(pt0.x) << kXYShift) + (kXYOne >> 1);
            const int64_t x1 = (static_cast<int64_t>(pt1.x) << kXYShift) + (kXYOne >> 1);
            PolyEdge edge;
            edge.dx = (x1 - x0) / (pt1.y - pt0.y);
            if (pt0.y < pt1.y) {
                edge.y0 = pt0.y;
                edge.y1 = pt1.y;
                edge.x = x0;
            } else {
                edge.y0 = pt1.y;
                edge.y1 = pt0.y;
                edge.x = x1;
            }
            edges.push_back(edge);
        }
        pt0 = pt1;
    }
    const int total = static_cast<int>(edges.size());
    if (total < 2) return;
    int y_max = INT_MIN, y_min = INT_MAX;
    for (const PolyEdge& e : edges) {
        y_min = std::min(y_min, e.y0);
        y_max = std::max(y_max, e.y1);
    }
    if (y_max < 0 || y_min >= h) return;
    std::sort(edges.begin(), edges.end(), [](const PolyEdge& a, const PolyEdge& b) {
        if (a.y0 != b.y0) return a.y0 < b.y0;
        if (a.x != b.x) return a.x < b.x;
        return a.dx < b.dx;
    });
    PolyEdge tail;
    tail.y0 = INT_MAX;
    edges.push_back(tail);
    PolyEdge head;
    int i = 0;
    PolyEdge* e = &edges[0];
    y_max = std::min(y_max, h);
    for (int y = e->y0; y < y_max; ++y) {
        PolyEdge *last, *prelast, *keep_prelast;
        int draw = 0;
        const bool clipline = y < 0;
        prelast = &head;
        last = head.next;
        while (last || e->y0 == y) {
            if (last && last->y1 == y) {
                prelast->next = last->next;
                last = last->next;
                continue;
            }
            keep_prelast = prelast;
            if (last && (e->y0 > y || last->x < e->x)) {
                prelast = last;
                last = last->next;
            } else if (i < total) {
                prelast->next = e;
                e->next = last;
                prelast = e;
                e = &edges[static_cast<size_t>(++i)];
            } else {
                break;
            }
            if (draw) {
                if (!clipline) {
                    int x1, x2;
                    if (keep_prelast->x > prelast->x) {
                        x1 = static_cast<int>(prelast->x >> kXYShift);
                        x2 = static_cast<int>(keep_prelast->x >> kXYShift);
                    } else {
                        x1 = static_cast<int>(keep_prelast->x >> kXYShift);
                        x2 = static_cast<int>(prelast->x >> kXYShift);
                    }
                    if (x1 < w && x2 >= 0) {
                        x1 = std::max(x1, 0);
                        x2 = std::min(x2, w - 1);
                        for (int x = x1; x <= x2; ++x) mask[static_cast<size_t>(y) * w + x] = 1;
                    }
                }
                keep_prelast->x += keep_prelast->dx;
                prelast->x += prelast->dx;
            }
            draw ^= 1;
        }
        // Sort the active edges by x (bubble sort, as OpenCV does).
        keep_prelast = nullptr;
        do {
            prelast = &head;
            last = head.next;
            PolyEdge* last_exchange = nullptr;
            while (last != keep_prelast && last->next != nullptr) {
                PolyEdge* te = last->next;
                if (last->x > te->x) {
                    prelast->next = te;
                    last->next = te->next;
                    te->next = last;
                    prelast = te;
                    last_exchange = prelast;
                } else {
                    prelast = last;
                    last = te;
                }
            }
            if (!last_exchange) break;
            keep_prelast = last_exchange;
        } while (keep_prelast != head.next && keep_prelast != &head);
    }
}

double contour_area(const Point2f* points, int n) {
    if (n == 0) return 0.;
    double a00 = 0;
    Point2f prev = points[n - 1];
    for (int i = 0; i < n; ++i) {
        const Point2f p = points[i];
        a00 += static_cast<double>(prev.x) * p.y - static_cast<double>(prev.y) * p.x;
        prev = p;
    }
    return std::fabs(a00 * 0.5);
}

double arc_length_closed(const Point2f* points, int n) {
    if (n <= 1) return 0.;
    double perimeter = 0;
    Point2f prev = points[n - 1];
    for (int i = 0; i < n; ++i) {
        const Point2f p = points[i];
        const float dx = p.x - prev.x, dy = p.y - prev.y;
        perimeter += std::sqrt(dx * dx + dy * dy);
        prev = p;
    }
    return perimeter;
}

}  // namespace jpdf::ocr
