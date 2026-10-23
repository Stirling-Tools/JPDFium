// cv_compat.h - the OpenCV 4.10 operations PaddleOCR's pipeline uses, ported so results match it.
// Derived from OpenCV (Apache-2.0); see NOTICE.
#pragma once

#include <cstdint>
#include <vector>

namespace jpdf::ocr {

struct Point {
    int x;
    int y;
};

struct Point2f {
    float x;
    float y;
};

struct RotatedRect {
    Point2f center{0, 0};
    float width = 0;
    float height = 0;
    float angle = 0;  // degrees
};

// cv::resize(INTER_LINEAR) on interleaved 8-bit pixels: OpenCV's fixed-point path, including its
// promotion to INTER_AREA when the reduction is exactly 2x on both axes.
void resize_linear(const uint8_t* src, int sw, int sh, int sstride, int cn, uint8_t* dst, int dw,
                   int dh);

// cv::warpPerspective(src, M, size, INTER_CUBIC, BORDER_REPLICATE) with
// M = cv::getPerspectiveTransform(quad, rect(dw, dh)). False if the quad is degenerate.
bool warp_perspective_cubic(const uint8_t* src, int sw, int sh, int sstride, int cn,
                            const Point2f quad[4], int dw, int dh, uint8_t* dst);

// cv::findContours(mask != 0, RETR_LIST, CHAIN_APPROX_SIMPLE), in OpenCV's output order.
std::vector<std::vector<Point>> find_contours(const uint8_t* mask, int w, int h);

// cv::minAreaRect on integer points, and cv::boxPoints.
RotatedRect min_area_rect(const std::vector<Point>& points);
void box_points(const RotatedRect& rect, Point2f out[4]);

// cv::fillPoly(mask, [polygon], 1) on an 8-bit mask, LINE_8, integer vertices.
void fill_poly(uint8_t* mask, int w, int h, const std::vector<Point>& polygon);

// cv::contourArea and cv::arcLength(closed) on float points.
double contour_area(const Point2f* points, int n);
double arc_length_closed(const Point2f* points, int n);

}  // namespace jpdf::ocr
