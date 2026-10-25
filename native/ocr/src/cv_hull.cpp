// cv_hull.cpp - port of OpenCV 4.10 convexHull (Sklansky + rotating calipers), minAreaRect and
// boxPoints. Split out of the former cv_compat.cpp. Derived from OpenCV (Apache-2.0); see NOTICE.

#include <algorithm>
#include <array>
#include <cfloat>
#include <climits>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <numbers>

#include "cv_compat.h"

namespace jpdf::ocr {

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

}  // namespace jpdf::ocr
