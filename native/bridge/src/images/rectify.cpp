// rectify.cpp - quad normalization, homography solve and warping for OCR crops.

#include "rectify.h"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdlib>

#include "runtime/api_status.h"

namespace jpdf {

namespace {

constexpr double kMinArea = 1.0;

double cross(double ax, double ay, double bx, double by) noexcept {
    return ax * by - ay * bx;
}

double edge(const Quad& q, int a, int b) noexcept {
    return std::hypot(q[a * 2] - q[b * 2], q[a * 2 + 1] - q[b * 2 + 1]);
}

// OpenCV's interpolateCubic weights for the fractional offset t.
void cubic_weights(double t, double w[4]) noexcept {
    constexpr double a = -0.75;
    const double x0 = t + 1, x1 = t, x2 = 1 - t;
    w[0] = ((a * x0 - 5 * a) * x0 + 8 * a) * x0 - 4 * a;
    w[1] = ((a + 2) * x1 - (a + 3)) * x1 * x1 + 1;
    w[2] = ((a + 2) * x2 - (a + 3)) * x2 * x2 + 1;
    w[3] = 1 - w[0] - w[1] - w[2];
}

struct Sampler {
    const PixelStorage& src;
    int channels;
    bool replicate;

    // Returns false for a white border sample outside the source.
    bool at(int x, int y, int c, double* value) const noexcept {
        if (x < 0 || y < 0 || x >= src.width || y >= src.height) {
            if (!replicate) return false;
            x = std::clamp(x, 0, src.width - 1);
            y = std::clamp(y, 0, src.height - 1);
        }
        const uint8_t* const base = src.data.get();
        const uint8_t* row = base + static_cast<ptrdiff_t>(y) * src.stride;
        *value = row[static_cast<ptrdiff_t>(x) * channels + c];
        return true;
    }

    double get(int x, int y, int c) const noexcept {
        double v = 255;
        at(x, y, c, &v);
        return v;
    }
};

uint8_t saturate(double v) noexcept {
    return static_cast<uint8_t>(std::clamp(std::lround(v), 0L, 255L));
}

}  // namespace

jpdf_status normalize_quad(const double* corners, Quad* out) noexcept {
    std::array<std::array<double, 2>, 4> p{};
    double cx = 0, cy = 0;
    for (int i = 0; i < 4; ++i) {
        p[i] = {corners[i * 2], corners[i * 2 + 1]};
        if (!std::isfinite(p[i][0]) || !std::isfinite(p[i][1])) {
            return fail(JPDF_E_INVALID_ARGUMENT, "quad has a non-finite coordinate");
        }
        cx += p[i][0] / 4;
        cy += p[i][1] / 4;
    }
    // Image y grows downward, so increasing angle runs clockwise on screen.
    std::sort(p.begin(), p.end(), [&](const auto& a, const auto& b) {
        return std::atan2(a[1] - cy, a[0] - cx) < std::atan2(b[1] - cy, b[0] - cx);
    });
    const auto first = std::min_element(
        p.begin(), p.end(), [](const auto& a, const auto& b) { return a[0] + a[1] < b[0] + b[1]; });
    std::rotate(p.begin(), first, p.end());
    double area = 0;
    int sign = 0;
    for (int i = 0; i < 4; ++i) {
        const auto& a = p[i];
        const auto& b = p[(i + 1) % 4];
        const auto& c = p[(i + 2) % 4];
        const double turn = cross(b[0] - a[0], b[1] - a[1], c[0] - b[0], c[1] - b[1]);
        const int s = (turn > 0) - (turn < 0);
        if (s == 0 || (sign != 0 && s != sign)) {
            return fail(JPDF_E_INVALID_ARGUMENT, "quad is degenerate or not convex");
        }
        sign = s;
        area += cross(a[0], a[1], b[0], b[1]) / 2;
    }
    if (std::abs(area) < kMinArea) return fail(JPDF_E_INVALID_ARGUMENT, "quad has no area");
    for (int i = 0; i < 4; ++i) {
        (*out)[i * 2] = p[i][0];
        (*out)[i * 2 + 1] = p[i][1];
    }
    return JPDF_OK;
}

void derived_size(const Quad& q, int32_t* width, int32_t* height) noexcept {
    const double w = std::max(edge(q, 0, 1), edge(q, 2, 3));
    const double h = std::max(edge(q, 0, 3), edge(q, 1, 2));
    *width = std::max<int32_t>(1, static_cast<int32_t>(std::min(w, 1e9)));
    *height = std::max<int32_t>(1, static_cast<int32_t>(std::min(h, 1e9)));
}

jpdf_status solve_homography(const Quad& quad, int32_t width, int32_t height,
                             Homography* to_source) noexcept {
    const double dst[8] = {0,
                           0,
                           static_cast<double>(width),
                           0,
                           static_cast<double>(width),
                           static_cast<double>(height),
                           0,
                           static_cast<double>(height)};
    double m[8][9] = {};
    for (int i = 0; i < 4; ++i) {
        const double u = dst[i * 2], v = dst[i * 2 + 1];
        const double x = quad[i * 2], y = quad[i * 2 + 1];
        double* rx = m[i * 2];
        double* ry = m[i * 2 + 1];
        rx[0] = u, rx[1] = v, rx[2] = 1, rx[6] = -u * x, rx[7] = -v * x, rx[8] = x;
        ry[3] = u, ry[4] = v, ry[5] = 1, ry[6] = -u * y, ry[7] = -v * y, ry[8] = y;
    }
    // Gaussian elimination with partial pivoting.
    for (int col = 0; col < 8; ++col) {
        int pivot = col;
        for (int r = col + 1; r < 8; ++r) {
            if (std::abs(m[r][col]) > std::abs(m[pivot][col])) pivot = r;
        }
        if (std::abs(m[pivot][col]) < 1e-12) {
            return fail(JPDF_E_INVALID_ARGUMENT, "quad gives a singular transform");
        }
        if (pivot != col) std::swap(m[pivot], m[col]);
        for (int r = 0; r < 8; ++r) {
            if (r == col) continue;
            const double f = m[r][col] / m[col][col];
            for (int c = col; c < 9; ++c) m[r][c] -= f * m[col][c];
        }
    }
    for (int i = 0; i < 8; ++i) (*to_source)[i] = m[i][8] / m[i][i];
    (*to_source)[8] = 1;
    return JPDF_OK;
}

jpdf_status warp(const PixelStorage& source, const Homography& h, int32_t width, int32_t height,
                 int32_t interpolation, int32_t border, PixelStorage* out) {
    const int channels = channels_for(source.pixel_format);
    const size_t stride = static_cast<size_t>(width) * static_cast<size_t>(channels);
    out->data.reset(static_cast<uint8_t*>(std::malloc(stride * static_cast<size_t>(height))));
    if (!out->data) return fail(JPDF_E_NO_MEMORY, "rectified image allocation failed");
    const Sampler s{source, channels, border == JPDF_BORDER_REPLICATE};
    uint8_t* const base = out->data.get();
    for (int32_t v = 0; v < height; ++v) {
        uint8_t* row = base + static_cast<ptrdiff_t>(v) * static_cast<ptrdiff_t>(stride);
        for (int32_t u = 0; u < width; ++u) {
            const double w = h[6] * u + h[7] * v + h[8];
            const double x = (h[0] * u + h[1] * v + h[2]) / w;
            const double y = (h[3] * u + h[4] * v + h[5]) / w;
            uint8_t* px = row + static_cast<ptrdiff_t>(u) * channels;
            if (!std::isfinite(x) || !std::isfinite(y)) {
                std::fill(px, px + channels, uint8_t{255});
                continue;
            }
            for (int c = 0; c < channels; ++c) {
                if (interpolation == JPDF_INTERP_NEAREST) {
                    px[c] = saturate(s.get(static_cast<int>(std::lround(x)),
                                           static_cast<int>(std::lround(y)), c));
                    continue;
                }
                const int x0 = static_cast<int>(std::floor(x));
                const int y0 = static_cast<int>(std::floor(y));
                const double fx = x - x0, fy = y - y0;
                if (interpolation == JPDF_INTERP_BILINEAR) {
                    const double top = s.get(x0, y0, c) * (1 - fx) + s.get(x0 + 1, y0, c) * fx;
                    const double bottom =
                        s.get(x0, y0 + 1, c) * (1 - fx) + s.get(x0 + 1, y0 + 1, c) * fx;
                    px[c] = saturate(top * (1 - fy) + bottom * fy);
                    continue;
                }
                double wx[4], wy[4];
                cubic_weights(fx, wx);
                cubic_weights(fy, wy);
                double acc = 0;
                for (int j = 0; j < 4; ++j) {
                    double line = 0;
                    for (int i = 0; i < 4; ++i) line += s.get(x0 - 1 + i, y0 - 1 + j, c) * wx[i];
                    acc += line * wy[j];
                }
                px[c] = saturate(acc);
            }
        }
    }
    out->width = width;
    out->height = height;
    out->stride = static_cast<int32_t>(stride);
    out->pixel_format = source.pixel_format;
    out->byte_size = stride * static_cast<uint64_t>(height);
    out->dpi_x = source.dpi_x;
    out->dpi_y = source.dpi_y;
    return JPDF_OK;
}

}  // namespace jpdf
