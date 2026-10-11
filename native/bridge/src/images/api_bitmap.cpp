// api_bitmap.cpp - jpdf_bitmap_* and jpdf_view_*: owned pixels, never inside the PDFium domain.

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <memory>
#include <system_error>
#include <vector>

#include "jpdfium_api.h"
#include "objects/objects.h"
#include "output/image_writer.h"
#include "output/output_transaction.h"
#include "runtime/api_status.h"

namespace {

// Same ceiling as rendering: 256 MiP.
constexpr uint64_t kDefaultMaxPixels = 268435456ull;

jpdf_status resolve_bitmap(jpdf_handle bitmap, std::shared_ptr<jpdf::BitmapObject>* out) {
    jpdf_status st = jpdf::objects().resolve_as(bitmap, jpdf::ObjectType::Bitmap, out);
    return st == JPDF_OK ? st : jpdf::fail(st, "bitmap handle rejected");
}
// PNG file decoding, folded in from api_image.cpp.

constexpr uintmax_t kMaxFileBytes = uintmax_t{1} << 30;

// Receives the decoder's single pixel allocation, so the C++ side owns it however decoding ends.
struct DecodeTarget {
    jpdf::PixelBuffer pixels;
    size_t size = 0;
};

uint8_t* allocate_pixels(void* ctx, size_t size) noexcept {
    auto* target = static_cast<DecodeTarget*>(ctx);
    target->pixels.reset(static_cast<uint8_t*>(std::malloc(size == 0 ? 1 : size)));
    target->size = size;
    return target->pixels.get();
}

jpdf_status read_file(const char* path_utf8, std::vector<uint8_t>* out) {
    const std::filesystem::path path = jpdf::path_from_utf8(path_utf8);
    std::error_code ec;
    const uintmax_t size = std::filesystem::file_size(path, ec);
    if (ec) return jpdf::fail(JPDF_E_IO, "image file not found or unreadable");
    if (size > kMaxFileBytes) return jpdf::fail(JPDF_E_LIMIT, "image file is larger than 1 GiB");
    std::unique_ptr<FILE, int (*)(FILE*)> file(
#if defined(_WIN32)
        _wfopen(path.c_str(), L"rb"),
#else
        std::fopen(path.c_str(), "rb"),
#endif
        &std::fclose);
    if (!file) return jpdf::fail(JPDF_E_IO, "cannot open image file");
    out->resize(static_cast<size_t>(size));
    if (size > 0 && std::fread(out->data(), 1, out->size(), file.get()) != out->size()) {
        return jpdf::fail(JPDF_E_IO, "cannot read image file");
    }
    return JPDF_OK;
}

double dpi_from_ppm(uint32_t ppm) noexcept {
    return ppm == 0 ? 0.0 : ppm * 0.0254;
}

}  // namespace

// Quad rectification for OCR crops, folded in from rectify.h/rectify.cpp.
namespace jpdf {

using Quad = std::array<double, 8>;
using Homography = std::array<double, 9>;

// Reorders corners clockwise from top-left; rejects non-convex, non-finite or tiny quads.
jpdf_status normalize_quad(const double* corners, Quad* out) noexcept;

// PaddleOCR's crop size: truncated lengths of the longer opposite edges, at least 1.
void derived_size(const Quad& quad, int32_t* width, int32_t* height) noexcept;

// Maps output pixels (OpenCV convention, (0,0)-(width,height)) onto the quad's source pixels.
jpdf_status solve_homography(const Quad& quad, int32_t width, int32_t height,
                             Homography* to_source) noexcept;

// Samples `source` through `to_source` into a new image with the source's pixel format.
jpdf_status warp(const PixelStorage& source, const Homography& to_source, int32_t width,
                 int32_t height, int32_t interpolation, int32_t border, PixelStorage* out);

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
            double x = (h[0] * u + h[1] * v + h[2]) / w;
            double y = (h[3] * u + h[4] * v + h[5]) / w;
            uint8_t* px = row + static_cast<ptrdiff_t>(u) * channels;
            if (!std::isfinite(x) || !std::isfinite(y)) {
                std::fill(px, px + channels, uint8_t{255});
                continue;
            }
            // Every sample more than 4 px outside the source is a border sample, so clamping
            // keeps the result identical while keeping the int conversions defined for a
            // caller-supplied quad (or a vanishing denominator) far outside the int range.
            x = std::clamp(x, -4.0, static_cast<double>(source.width) + 4.0);
            y = std::clamp(y, -4.0, static_cast<double>(source.height) + 4.0);
            // The sample position and interpolation weights are the same for every colour
            // channel, so derive them once per pixel instead of once per channel.
            const int xn = static_cast<int>(std::lround(x));
            const int yn = static_cast<int>(std::lround(y));
            const int x0 = static_cast<int>(std::floor(x));
            const int y0 = static_cast<int>(std::floor(y));
            const double fx = x - x0, fy = y - y0;
            double wx[4] = {}, wy[4] = {};
            if (interpolation == JPDF_INTERP_BICUBIC) {
                cubic_weights(fx, wx);
                cubic_weights(fy, wy);
            }
            for (int c = 0; c < channels; ++c) {
                if (interpolation == JPDF_INTERP_NEAREST) {
                    px[c] = saturate(s.get(xn, yn, c));
                    continue;
                }
                if (interpolation == JPDF_INTERP_BILINEAR) {
                    const double top = s.get(x0, y0, c) * (1 - fx) + s.get(x0 + 1, y0, c) * fx;
                    const double bottom =
                        s.get(x0, y0 + 1, c) * (1 - fx) + s.get(x0 + 1, y0 + 1, c) * fx;
                    px[c] = saturate(top * (1 - fy) + bottom * fy);
                    continue;
                }
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

extern "C" {

jpdf_status jpdf_bitmap_get_info(jpdf_handle bitmap, jpdf_bitmap_info* out) noexcept {
    return jpdf::guarded([&] {
        if (!out) return jpdf::fail(JPDF_E_INVALID_ARGUMENT, "null output");
        jpdf_status st = jpdf::check_output_struct(out->struct_size, sizeof(jpdf_bitmap_info),
                                                   "jpdf_bitmap_info");
        if (st != JPDF_OK) return st;
        std::shared_ptr<jpdf::BitmapObject> object;
        if ((st = resolve_bitmap(bitmap, &object)) != JPDF_OK) return st;
        const jpdf::PixelStorage& px = *object->pixels;
        out->pixel_format = px.pixel_format;
        out->width = px.width;
        out->height = px.height;
        out->stride = px.stride;
        out->reserved0 = 0;
        out->dpi_x = px.dpi_x;
        out->dpi_y = px.dpi_y;
        out->byte_size = px.byte_size;
        return JPDF_OK;
    });
}

jpdf_status jpdf_bitmap_copy_pixels(jpdf_handle bitmap, uint8_t* dst, uint64_t capacity,
                                    uint64_t* out_written) noexcept {
    return jpdf::guarded([&] {
        if (!out_written) return jpdf::fail(JPDF_E_INVALID_ARGUMENT, "null out_written");
        std::shared_ptr<jpdf::BitmapObject> object;
        jpdf_status st = resolve_bitmap(bitmap, &object);
        if (st != JPDF_OK) return st;
        const jpdf::PixelStorage& px = *object->pixels;
        *out_written = px.byte_size;
        if (!dst || capacity < px.byte_size) {
            return jpdf::fail(JPDF_E_BUFFER_TOO_SMALL, "destination smaller than byte_size");
        }
        std::memcpy(dst, px.data.get(), static_cast<size_t>(px.byte_size));
        return JPDF_OK;
    });
}

jpdf_status jpdf_bitmap_map(jpdf_handle bitmap, jpdf_bitmap_view* out_view) noexcept {
    return jpdf::guarded([&] {
        if (!out_view) return jpdf::fail(JPDF_E_INVALID_ARGUMENT, "null output");
        jpdf_status st = jpdf::check_output_struct(out_view->struct_size, sizeof(jpdf_bitmap_view),
                                                   "jpdf_bitmap_view");
        if (st != JPDF_OK) return st;
        std::shared_ptr<jpdf::BitmapObject> object;
        if ((st = resolve_bitmap(bitmap, &object)) != JPDF_OK) return st;
        auto view = std::make_shared<jpdf::ViewObject>(object->pixels);
        jpdf_handle token = 0;
        st = jpdf::objects().insert(view, &token);
        if (st != JPDF_OK) return jpdf::fail(st, "object table rejected the view");
        const jpdf::PixelStorage& px = *view->pixels;
        out_view->reserved0 = 0;
        out_view->view = token;
        out_view->data = px.data.get();
        out_view->byte_size = px.byte_size;
        out_view->width = px.width;
        out_view->height = px.height;
        out_view->stride = px.stride;
        out_view->pixel_format = px.pixel_format;
        return JPDF_OK;
    });
}

jpdf_status jpdf_view_release(jpdf_handle view) noexcept {
    return jpdf::guarded([&] {
        jpdf_status st = jpdf::objects().retire(view, jpdf::ObjectType::View, nullptr);
        return st == JPDF_OK ? st : jpdf::fail(st, "view handle rejected");
    });
}

jpdf_status jpdf_bitmap_write_file(jpdf_handle bitmap, const char* path_utf8,
                                   const jpdf_write_options* options) noexcept {
    return jpdf::guarded([&] {
        if (!path_utf8 || !*path_utf8) return jpdf::fail(JPDF_E_INVALID_ARGUMENT, "empty path");
        jpdf::WriteRequest request;
        jpdf_status st = jpdf::parse_write_options(options, &request);
        if (st != JPDF_OK) return st;
        // The resolved reference is the lease: a concurrent close cannot free these pixels.
        std::shared_ptr<jpdf::BitmapObject> object;
        if ((st = resolve_bitmap(bitmap, &object)) != JPDF_OK) return st;
        return jpdf::write_image_file(*object->pixels, path_utf8, request);
    });
}

jpdf_status jpdf_bitmap_rectify(jpdf_handle bitmap, const double quad[8],
                                const jpdf_rectify_options* options, jpdf_handle* out_bitmap,
                                jpdf_rectify_result* out_result) noexcept {
    return jpdf::guarded([&] {
        if (!quad || !out_bitmap) return jpdf::fail(JPDF_E_INVALID_ARGUMENT, "null argument");
        jpdf_status st = JPDF_OK;
        if (out_result &&
            (st = jpdf::check_output_struct(out_result->struct_size, sizeof(jpdf_rectify_result),
                                            "jpdf_rectify_result")) != JPDF_OK) {
            return st;
        }
        jpdf_rectify_options o{};
        o.struct_size = sizeof(o);
        o.interpolation = JPDF_INTERP_BILINEAR;
        if (options) {
            st = jpdf::check_input_struct(options, options->struct_size,
                                          sizeof(jpdf_rectify_options),
                                          sizeof(jpdf_rectify_options), "jpdf_rectify_options");
            if (st != JPDF_OK) return st;
            o = *options;
        }
        if (o.flags != 0) return jpdf::fail(JPDF_E_UNSUPPORTED, "rectify flags must be 0");
        if (o.interpolation < JPDF_INTERP_NEAREST || o.interpolation > JPDF_INTERP_BICUBIC ||
            (o.border != JPDF_BORDER_WHITE && o.border != JPDF_BORDER_REPLICATE)) {
            return jpdf::fail(JPDF_E_INVALID_ARGUMENT, "unknown interpolation or border mode");
        }
        if (o.width < 0 || o.height < 0 || (o.width == 0) != (o.height == 0)) {
            return jpdf::fail(JPDF_E_INVALID_ARGUMENT, "width and height must both be set or 0");
        }
        std::shared_ptr<jpdf::BitmapObject> object;
        if ((st = resolve_bitmap(bitmap, &object)) != JPDF_OK) return st;

        jpdf::Quad ordered{};
        if ((st = jpdf::normalize_quad(quad, &ordered)) != JPDF_OK) return st;
        int32_t width = o.width, height = o.height;
        if (width == 0) jpdf::derived_size(ordered, &width, &height);
        const uint64_t budget = o.max_pixels == 0 ? kDefaultMaxPixels : o.max_pixels;
        // warp() narrows stride = width * channels to int32; bound it before the allocation
        // so a wide, short source cannot store a wrapped (negative) stride in the bitmap info.
        const int32_t channels = jpdf::channels_for(object->pixels->pixel_format);
        if (channels == 0 || width > INT32_MAX / channels ||
            static_cast<uint64_t>(width) * static_cast<uint64_t>(height) > budget) {
            return jpdf::fail(JPDF_E_LIMIT, "rectified size exceeds the pixel budget");
        }
        jpdf::Homography to_source{};
        if ((st = jpdf::solve_homography(ordered, width, height, &to_source)) != JPDF_OK) {
            return st;
        }
        auto pixels = std::make_shared<jpdf::PixelStorage>();
        if ((st = jpdf::warp(*object->pixels, to_source, width, height, o.interpolation, o.border,
                             pixels.get())) != JPDF_OK) {
            return st;
        }
        jpdf_handle token = 0;
        st =
            jpdf::objects().insert(std::make_shared<jpdf::BitmapObject>(std::move(pixels)), &token);
        if (st != JPDF_OK) return jpdf::fail(st, "object table rejected the bitmap");
        *out_bitmap = token;
        if (out_result) {
            out_result->width = width;
            out_result->height = height;
            out_result->reserved0 = 0;
            std::copy(ordered.begin(), ordered.end(), out_result->quad);
            std::copy(to_source.begin(), to_source.end(), out_result->to_source);
        }
        return JPDF_OK;
    });
}

jpdf_status jpdf_bitmap_close(jpdf_handle bitmap) noexcept {
    return jpdf::guarded([&] {
        jpdf_status st = jpdf::objects().retire(bitmap, jpdf::ObjectType::Bitmap, nullptr);
        return st == JPDF_OK ? st : jpdf::fail(st, "bitmap handle rejected");
    });
}

jpdf_status jpdf_image_load_file(const char* path_utf8, const jpdf_image_load_options* options,
                                 jpdf_handle* out_bitmap) noexcept {
    return jpdf::guarded([&] {
        if (!path_utf8 || !*path_utf8 || !out_bitmap) {
            return jpdf::fail(JPDF_E_INVALID_ARGUMENT, "path and out_bitmap are required");
        }
        uint64_t max_pixels = kDefaultMaxPixels;
        if (options) {
            jpdf_status st = jpdf::check_input_struct(
                options, options->struct_size, sizeof(jpdf_image_load_options),
                sizeof(jpdf_image_load_options), "jpdf_image_load_options");
            if (st != JPDF_OK) return st;
            if (options->flags != 0) {
                return jpdf::fail(JPDF_E_UNSUPPORTED, "jpdf_image_load_options.flags must be 0");
            }
            if (options->max_pixels != 0) max_pixels = options->max_pixels;
        }
        std::vector<uint8_t> bytes;
        jpdf_status st = read_file(path_utf8, &bytes);
        if (st != JPDF_OK) return st;

        DecodeTarget target;
        jpdfium_decoded_image image{};
        const int32_t rc = jpdfium_rust_decode_png(bytes.data(), bytes.size(), max_pixels,
                                                   allocate_pixels, &target, &image);
        if (rc == -4) return jpdf::fail(JPDF_E_LIMIT, "image exceeds max_pixels");
        if (rc == -2) return jpdf::fail(JPDF_E_NO_MEMORY, "cannot allocate decoded pixels");
        if (rc == -1) return jpdf::fail(JPDF_E_FORMAT, "not a PNG this library can decode");
        if (rc != 0 || !target.pixels) return jpdf::fail(JPDF_E_INTERNAL, "PNG decoder failed");
        if (image.width > INT32_MAX / image.channels || image.height > INT32_MAX) {
            return jpdf::fail(JPDF_E_LIMIT, "image dimensions exceed the bitmap range");
        }

        auto pixels = std::make_shared<jpdf::PixelStorage>();
        pixels->data = std::move(target.pixels);
        pixels->byte_size = target.size;
        pixels->width = static_cast<int32_t>(image.width);
        pixels->height = static_cast<int32_t>(image.height);
        pixels->stride = static_cast<int32_t>(image.width * image.channels);
        pixels->pixel_format = image.channels == 1 ? JPDF_PIXEL_GRAY8 : JPDF_PIXEL_RGB8;
        pixels->dpi_x = dpi_from_ppm(image.ppm_x);
        pixels->dpi_y = dpi_from_ppm(image.ppm_y);
        jpdf_handle token = 0;
        st =
            jpdf::objects().insert(std::make_shared<jpdf::BitmapObject>(std::move(pixels)), &token);
        if (st != JPDF_OK) return jpdf::fail(st, "object table rejected the bitmap");
        *out_bitmap = token;
        return JPDF_OK;
    });
}

}  // extern "C"
