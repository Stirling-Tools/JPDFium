// cv_contours.cpp - port of OpenCV 4.10 findContours (RETR_LIST + CHAIN_APPROX_SIMPLE) and
// contourArea/arcLength. Split out of the former cv_compat.cpp. Derived from OpenCV (Apache-2.0);
// see NOTICE.

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
