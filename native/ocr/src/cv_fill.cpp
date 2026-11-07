// cv_fill.cpp - port of OpenCV 4.10 fillPoly (LINE_8, shift 0).
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

namespace jpdf::ocr {

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

}  // namespace jpdf::ocr
