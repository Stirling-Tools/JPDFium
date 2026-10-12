// paddle.cpp - PaddleOCR's pre- and post-processing, step for step, so results match the
// reference pipeline. The polygon offset is ported from Clipper 6.4.2 (Boost 1.0); see NOTICE.

#include "paddle.h"

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <numeric>

namespace jpdf::ocr {

namespace {

// Python's round(): half to even.
double py_round(double v) {
    return std::nearbyint(v);
}

// --- DBPostProcess helpers ---

// get_mini_boxes: corners sorted by x, then split into top/bottom pairs.
std::array<Point2f, 4> mini_box(const std::vector<Point>& points, float* short_side) {
    const RotatedRect rect = min_area_rect(points);
    Point2f corners[4];
    box_points(rect, corners);
    std::array<Point2f, 4> sorted = {corners[0], corners[1], corners[2], corners[3]};
    std::stable_sort(sorted.begin(), sorted.end(),
                     [](const Point2f& a, const Point2f& b) { return a.x < b.x; });
    int i1 = 0, i2 = 1, i3 = 2, i4 = 3;
    if (sorted[1].y > sorted[0].y) {
        i1 = 0;
        i4 = 1;
    } else {
        i1 = 1;
        i4 = 0;
    }
    if (sorted[3].y > sorted[2].y) {
        i2 = 2;
        i3 = 3;
    } else {
        i2 = 3;
        i3 = 2;
    }
    *short_side = std::min(rect.width, rect.height);
    return {sorted[static_cast<size_t>(i1)], sorted[static_cast<size_t>(i2)],
            sorted[static_cast<size_t>(i3)], sorted[static_cast<size_t>(i4)]};
}

// box_score_fast: mean probability inside the box, rasterized with cv2.fillPoly.
double box_score(const float* pred, int w, int h, const std::array<Point2f, 4>& box) {
    float lo_x = box[0].x;
    float lo_y = box[0].y;
    float hi_x = lo_x;
    float hi_y = lo_y;
    for (const Point2f& p : box) {
        lo_x = std::min(lo_x, p.x);
        hi_x = std::max(hi_x, p.x);
        lo_y = std::min(lo_y, p.y);
        hi_y = std::max(hi_y, p.y);
    }
    const int xmin = std::max(0, std::min(static_cast<int>(std::floor(lo_x)), w - 1));
    const int xmax = std::max(0, std::min(static_cast<int>(std::ceil(hi_x)), w - 1));
    const int ymin = std::max(0, std::min(static_cast<int>(std::floor(lo_y)), h - 1));
    const int ymax = std::max(0, std::min(static_cast<int>(std::ceil(hi_y)), h - 1));
    const int mw = xmax - xmin + 1, mh = ymax - ymin + 1;
    std::vector<uint8_t> mask(static_cast<size_t>(mw) * mh, 0);
    std::vector<Point> polygon;
    polygon.reserve(4);
    for (const Point2f& p : box) {
        // numpy's astype(int32) truncates toward zero.
        polygon.push_back({static_cast<int>(p.x - static_cast<float>(xmin)),
                           static_cast<int>(p.y - static_cast<float>(ymin))});
    }
    fill_poly(mask.data(), mw, mh, polygon);
    double sum = 0;
    int64_t count = 0;
    for (int y = 0; y < mh; ++y) {
        const float* row = pred + static_cast<size_t>(ymin + y) * w + xmin;
        const uint8_t* m = mask.data() + static_cast<size_t>(y) * mw;
        for (int x = 0; x < mw; ++x) {
            if (m[x]) {
                sum += row[x];
                ++count;
            }
        }
    }
    return sum * (count ? 1. / static_cast<double>(count) : 0.);  // cv::mean scales by 1/n
}

// pyclipper.PyclipperOffset with JT_ROUND / ET_CLOSEDPOLYGON and the default arc tolerance,
// before its union clean-up (which cannot change the hull of a convex input).
struct IntPoint {
    long long x;
    long long y;
    bool operator==(const IntPoint& o) const {
        return x == o.x && y == o.y;
    }
};

// Clipper's Round(), kept as written: halves round away from zero by truncation.
long long clipper_round(double v) {
    return v < 0 ? static_cast<long long>(v - 0.5)   // NOLINT(bugprone-incorrect-roundings)
                 : static_cast<long long>(v + 0.5);  // NOLINT(bugprone-incorrect-roundings)
}

double clipper_area(const std::vector<IntPoint>& poly) {
    const int size = static_cast<int>(poly.size());
    if (size < 3) return 0;
    double a = 0;
    for (int i = 0, j = size - 1; i < size; ++i) {
        const IntPoint& pj = poly[static_cast<size_t>(j)];
        const IntPoint& pi = poly[static_cast<size_t>(i)];
        a += (static_cast<double>(pj.x) + static_cast<double>(pi.x)) *
             (static_cast<double>(pj.y) - static_cast<double>(pi.y));
        j = i;
    }
    return -a * 0.5;
}

std::vector<Point> clipper_offset(const std::array<Point2f, 4>& box, double delta) {
    constexpr double kPi = 3.141592653589793238;
    constexpr double kTwoPi = kPi * 2;
    constexpr double kArcTolerance = 0.25;
    std::vector<IntPoint> path;
    path.reserve(box.size());
    for (const Point2f& p : box)
        path.push_back({static_cast<long long>(p.x), static_cast<long long>(p.y)});

    // AddPath: drop repeated points; a closed polygon needs three distinct ones.
    int high = static_cast<int>(path.size()) - 1;
    while (high > 0 && path[0] == path[static_cast<size_t>(high)]) --high;
    std::vector<IntPoint> contour{path[0]};
    for (int i = 1; i <= high; ++i) {
        if (!(contour.back() == path[static_cast<size_t>(i)]))
            contour.push_back(path[static_cast<size_t>(i)]);
    }
    if (contour.size() < 3) return {};
    // FixOrientations: a single closed path is reversed when its area is negative.
    if (clipper_area(contour) < 0) std::reverse(contour.begin(), contour.end());

    std::vector<IntPoint> dest;
    if (std::fabs(delta) < 1.0E-20) {
        dest = contour;
    } else {
        double y;
        if (kArcTolerance > std::fabs(delta) * kArcTolerance) {
            y = std::fabs(delta) * kArcTolerance;
        } else {
            y = kArcTolerance;
        }
        double steps = kPi / std::acos(1 - y / std::fabs(delta));
        if (steps > std::fabs(delta) * kPi) steps = std::fabs(delta) * kPi;
        double sin_step = std::sin(kTwoPi / steps);
        const double cos_step = std::cos(kTwoPi / steps);
        const double steps_per_rad = steps / kTwoPi;
        if (delta < 0.0) sin_step = -sin_step;

        const int len = static_cast<int>(contour.size());
        std::vector<std::array<double, 2>> normals(static_cast<size_t>(len));
        for (int j = 0; j < len; ++j) {
            const IntPoint& a = contour[static_cast<size_t>(j)];
            const IntPoint& b = contour[static_cast<size_t>((j + 1) % len)];
            if (a == b) {
                normals[static_cast<size_t>(j)] = {0, 0};
                continue;
            }
            double dx = static_cast<double>(b.x - a.x);
            double dy = static_cast<double>(b.y - a.y);
            const double f = 1 * 1.0 / std::sqrt(dx * dx + dy * dy);
            dx *= f;
            dy *= f;
            normals[static_cast<size_t>(j)] = {dy, -dx};
        }
        auto push = [&](const IntPoint& base, double nx, double ny) {
            dest.push_back({clipper_round(static_cast<double>(base.x) + nx * delta),
                            clipper_round(static_cast<double>(base.y) + ny * delta)});
        };
        int k = len - 1;
        for (int j = 0; j < len; ++j) {
            const auto& nk = normals[static_cast<size_t>(k)];
            const auto& nj = normals[static_cast<size_t>(j)];
            const IntPoint& src = contour[static_cast<size_t>(j)];
            double sin_a = nk[0] * nj[1] - nj[0] * nk[1];
            if (std::fabs(sin_a * delta) < 1.0) {
                const double cos_a = nk[0] * nj[0] + nj[1] * nk[1];
                if (cos_a > 0) {
                    push(src, nk[0], nk[1]);
                    continue;  // OffsetPoint returns before k = j here
                }
            } else if (sin_a > 1.0) {
                sin_a = 1.0;
            } else if (sin_a < -1.0) {
                sin_a = -1.0;
            }
            if (sin_a * delta < 0) {
                push(src, nk[0], nk[1]);
                dest.push_back(src);
                push(src, nj[0], nj[1]);
            } else {
                const double a = std::atan2(sin_a, nk[0] * nj[0] + nk[1] * nj[1]);
                const int count =
                    std::max(static_cast<int>(clipper_round(steps_per_rad * std::fabs(a))), 1);
                double X = nk[0], Y = nk[1];
                for (int i = 0; i < count; ++i) {
                    push(src, X, Y);
                    const double X2 = X;
                    X = X * cos_step - sin_step * Y;
                    Y = X2 * sin_step + Y * cos_step;
                }
                push(src, nj[0], nj[1]);
            }
            k = j;
        }
    }
    std::vector<Point> out;
    out.reserve(dest.size());
    for (const IntPoint& p : dest) out.push_back({static_cast<int>(p.x), static_cast<int>(p.y)});
    return out;
}

// NormalizeImage's per-channel factors, applied to BGR channels in order.
constexpr double kMean[3] = {0.485, 0.456, 0.406};
constexpr double kStd[3] = {0.229, 0.224, 0.225};

// numpy's pairwise float32 summation, so np.mean on float32 agrees bit for bit.
float pairwise_sum(const float* a, size_t n) {
    if (n < 8) {
        float res = 0.f;
        for (size_t i = 0; i < n; ++i) res += a[i];
        return res;
    }
    if (n <= 128) {
        float r[8];
        for (int i = 0; i < 8; ++i) r[i] = a[i];
        size_t i = 8;
        for (; i < n - (n % 8); i += 8) {
            for (int j = 0; j < 8; ++j) r[j] += a[i + static_cast<size_t>(j)];
        }
        float res = ((r[0] + r[1]) + (r[2] + r[3])) + ((r[4] + r[5]) + (r[6] + r[7]));
        for (; i < n; ++i) res += a[i];
        return res;
    }
    size_t n2 = n / 2;
    n2 -= n2 % 8;
    return pairwise_sum(a, n2) + pairwise_sum(a + n2, n - n2);
}

}  // namespace

DetectorInput prepare_detector_input(const Image& source, const DetectorParams& params) {
    const Image* image = &source;
    Image padded;
    if (source.width + source.height < 64) {
        // image_padding: zero-pad up to 32 px per side; boxes still map to the source size.
        padded.width = std::max(32, source.width);
        padded.height = std::max(32, source.height);
        padded.pixels.assign(static_cast<size_t>(padded.width) * padded.height * 3, 0);
        for (int y = 0; y < source.height; ++y) {
            std::copy_n(source.pixels.data() + static_cast<size_t>(y) * source.width * 3,
                        static_cast<size_t>(source.width) * 3,
                        padded.pixels.data() + static_cast<size_t>(y) * padded.width * 3);
        }
        image = &padded;
    }
    const int h = image->height, w = image->width;
    double ratio = 1.0;
    if (params.limit_type_min) {
        if (std::min(h, w) < params.limit_side_len) {
            ratio = static_cast<double>(params.limit_side_len) / (h < w ? h : w);
        }
    } else if (std::max(h, w) > params.limit_side_len) {
        ratio = static_cast<double>(params.limit_side_len) / (h > w ? h : w);
    }
    // Clamp while the scaled size is still a double: `ratio` can drive the scaled width past
    // INT_MAX for a degenerate aspect ratio (a 40,000,000 x 1 bitmap with the default
    // limit_side_len of 64 scales the width to 2.56e9), and that cast would be undefined.
    double scaled_h = h * ratio;
    double scaled_w = w * ratio;
    const double longest = std::max(scaled_h, scaled_w);
    if (longest > params.max_side_limit) {
        ratio *= static_cast<double>(params.max_side_limit) / longest;
        scaled_h = h * ratio;
        scaled_w = w * ratio;
    }
    int resize_h = static_cast<int>(scaled_h);
    int resize_w = static_cast<int>(scaled_w);
    resize_h = std::max(static_cast<int>(py_round(resize_h / 32.0) * 32), 32);
    resize_w = std::max(static_cast<int>(py_round(resize_w / 32.0) * 32), 32);

    std::vector<uint8_t> resized;
    const uint8_t* pixels = image->pixels.data();
    if (resize_h != h || resize_w != w) {
        resized.resize(static_cast<size_t>(resize_w) * resize_h * 3);
        resize_linear(image->pixels.data(), w, h, w * 3, 3, resized.data(), resize_w, resize_h);
        pixels = resized.data();
    }
    DetectorInput input;
    input.width = resize_w;
    input.height = resize_h;
    const size_t plane = static_cast<size_t>(resize_w) * resize_h;
    input.tensor.resize(plane * 3);
    const double scale = 1. / 255.;
    for (int c = 0; c < 3; ++c) {
        const float alpha = static_cast<float>(scale / kStd[c]);
        const float beta = static_cast<float>(-kMean[c] / kStd[c]);
        float* out = input.tensor.data() + plane * static_cast<size_t>(c);
        for (size_t i = 0; i < plane; ++i) {
            float v = static_cast<float>(pixels[i * 3 + static_cast<size_t>(c)]);
            v *= alpha;
            v += beta;
            out[i] = v;
        }
    }
    return input;
}

std::vector<Detection> detect_boxes(const float* pred, int width, int height, int dest_width,
                                    int dest_height, const DetectorParams& params) {
    const float thresh = static_cast<float>(params.thresh);
    std::vector<uint8_t> mask(static_cast<size_t>(width) * height);
    for (size_t i = 0; i < mask.size(); ++i) mask[i] = pred[i] > thresh ? 255 : 0;
    const std::vector<std::vector<Point>> contours = find_contours(mask.data(), width, height);
    const double width_scale = static_cast<double>(dest_width) / width;
    const double height_scale = static_cast<double>(dest_height) / height;
    const size_t count = std::min(contours.size(), static_cast<size_t>(params.max_candidates));
    std::vector<Detection> boxes;
    for (size_t index = 0; index < count; ++index) {
        float short_side = 0;
        const std::array<Point2f, 4> points = mini_box(contours[index], &short_side);
        if (short_side < 3) continue;
        const double score = box_score(pred, width, height, points);
        if (params.box_thresh > score) continue;
        const double area = contour_area(points.data(), 4);
        const double length = arc_length_closed(points.data(), 4);
        const double distance = area * params.unclip_ratio / length;
        const std::vector<Point> expanded = clipper_offset(points, distance);
        const std::array<Point2f, 4> box = mini_box(expanded, &short_side);
        if (short_side < 5) continue;
        Detection detection;
        detection.score = static_cast<float>(score);
        const float sx = static_cast<float>(width_scale);
        const float sy = static_cast<float>(height_scale);
        for (size_t i = 0; i < 4; ++i) {
            const double x = py_round(static_cast<double>(box[i].x * sx));
            const double y = py_round(static_cast<double>(box[i].y * sy));
            detection.box[i] = {
                static_cast<int>(std::max(0.0, std::min(x, static_cast<double>(dest_width)))),
                static_cast<int>(std::max(0.0, std::min(y, static_cast<double>(dest_height))))};
        }
        boxes.push_back(detection);
    }
    return boxes;
}

void sort_boxes(std::vector<Detection>* boxes) {
    std::vector<Detection>& b = *boxes;
    std::stable_sort(b.begin(), b.end(), [](const Detection& l, const Detection& r) {
        if (l.box[0].y != r.box[0].y) return l.box[0].y < r.box[0].y;
        return l.box[0].x < r.box[0].x;
    });
    for (size_t i = 0; i + 1 < b.size(); ++i) {
        for (size_t j = i + 1; j-- > 0;) {
            if (std::abs(b[j + 1].box[0].y - b[j].box[0].y) < 10 &&
                b[j + 1].box[0].x < b[j].box[0].x) {
                std::swap(b[j], b[j + 1]);
            } else {
                break;
            }
        }
    }
}

bool plan_crop(const Quad& box, CropPlan* plan) {
    const std::vector<Point> corners(box.begin(), box.end());
    float short_side = 0;
    const std::array<Point2f, 4> q = mini_box(corners, &short_side);
    auto norm = [](const Point2f& a, const Point2f& b) {
        const float dx = a.x - b.x, dy = a.y - b.y;
        return std::sqrt(dx * dx + dy * dy);
    };
    const int crop_w = static_cast<int>(std::max(norm(q[0], q[1]), norm(q[2], q[3])));
    const int crop_h = static_cast<int>(std::max(norm(q[0], q[3]), norm(q[1], q[2])));
    if (crop_w <= 0 || crop_h <= 0) return false;
    const bool rotated = static_cast<double>(crop_h) / crop_w >= 1.5;
    plan->quad = q;
    plan->rotated = rotated;
    plan->width = rotated ? crop_h : crop_w;
    plan->height = rotated ? crop_w : crop_h;
    return true;
}

bool materialize_crop(const Image& image, const CropPlan& plan, Image* out) {
    const int warp_w = plan.rotated ? plan.height : plan.width;
    const int warp_h = plan.rotated ? plan.width : plan.height;
    std::vector<uint8_t> warped(static_cast<size_t>(warp_w) * warp_h * 3);
    if (!warp_perspective_cubic(image.pixels.data(), image.width, image.height, image.width * 3, 3,
                                plan.quad.data(), warp_w, warp_h, warped.data())) {
        return false;
    }
    if (plan.rotated) {
        // np.rot90: a quarter turn counter-clockwise.
        out->width = plan.width;
        out->height = plan.height;
        out->pixels.resize(warped.size());
        for (int r = 0; r < out->height; ++r) {
            for (int c = 0; c < out->width; ++c) {
                const uint8_t* s =
                    warped.data() + (static_cast<size_t>(c) * warp_w + (warp_w - 1 - r)) * 3;
                std::copy_n(s, 3,
                            out->pixels.data() + (static_cast<size_t>(r) * out->width + c) * 3);
            }
        }
    } else {
        out->width = plan.width;
        out->height = plan.height;
        out->pixels = std::move(warped);
    }
    return true;
}

bool crop_box(const Image& image, const Quad& box, Image* out) {
    CropPlan plan;
    return plan_crop(box, &plan) && materialize_crop(image, plan, out);
}

std::vector<size_t> crop_order(const std::vector<CropPlan>& plans) {
    std::vector<size_t> order(plans.size());
    std::iota(order.begin(), order.end(), size_t{0});
    std::stable_sort(order.begin(), order.end(), [&](size_t a, size_t b) {
        return plans[a].width / static_cast<double>(plans[a].height) <
               plans[b].width / static_cast<double>(plans[b].height);
    });
    return order;
}

int recognizer_width(const Image& crop, const RecognizerParams& params) {
    const double max_wh_ratio = std::max(static_cast<double>(params.min_width) / params.height,
                                         crop.width * 1.0 / crop.height);
    return std::min(static_cast<int>(params.height * max_wh_ratio), params.max_width);
}

void recognizer_input(const Image& crop, const RecognizerParams& params, int batch_width,
                      float* out) {
    const int img_h = params.height;
    const double max_wh_ratio = std::max(static_cast<double>(params.min_width) / params.height,
                                         crop.width * 1.0 / crop.height);
    int img_w = static_cast<int>(img_h * max_wh_ratio);
    int resized_w;
    if (img_w > params.max_width) {
        resized_w = params.max_width;
    } else {
        const double ratio = crop.width / static_cast<double>(crop.height);
        const double wanted = std::ceil(img_h * ratio);
        resized_w = wanted > img_w ? img_w : static_cast<int>(wanted);
    }
    std::vector<uint8_t> resized(static_cast<size_t>(resized_w) * img_h * 3);
    resize_linear(crop.pixels.data(), crop.width, crop.height, crop.width * 3, 3, resized.data(),
                  resized_w, img_h);
    const size_t plane = static_cast<size_t>(batch_width) * img_h;
    std::fill(out, out + plane * 3, 0.f);
    for (int c = 0; c < 3; ++c) {
        float* dst = out + plane * static_cast<size_t>(c);
        for (int y = 0; y < img_h; ++y) {
            for (int x = 0; x < resized_w; ++x) {
                float v = static_cast<float>(
                    resized[(static_cast<size_t>(y) * resized_w + x) * 3 + static_cast<size_t>(c)]);
                v /= 255.f;
                v -= 0.5f;
                v /= 0.5f;
                dst[static_cast<size_t>(y) * batch_width + x] = v;
            }
        }
    }
}

Recognition ctc_decode(const float* probs, int steps, int classes,
                       const std::vector<std::string>& characters) {
    Recognition result;
    std::vector<float> kept;
    int previous = -1;
    for (int t = 0; t < steps; ++t) {
        const float* row = probs + static_cast<size_t>(t) * classes;
        const int index = static_cast<int>(std::max_element(row, row + classes) - row);
        const bool repeat = t > 0 && index == previous;
        previous = index;
        if (repeat || index == 0) continue;
        kept.push_back(row[index]);
        if (static_cast<size_t>(index) < characters.size())
            result.text += characters[static_cast<size_t>(index)];
    }
    if (kept.empty()) kept.push_back(0.f);
    // np.mean: the reduction starts from the first element, then adds the rest pairwise.
    const float sum = kept[0] + pairwise_sum(kept.data() + 1, kept.size() - 1);
    result.score = static_cast<double>(sum / static_cast<float>(kept.size()));
    return result;
}

}  // namespace jpdf::ocr
