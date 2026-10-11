// ocr_geometry.cpp - the OCR pre/post-processing against outputs recorded from the reference
// stack (OpenCV 4.10, pyclipper, paddlex 3.7) by make_golden.py. No models, no ONNX Runtime.

#include <array>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "cv_compat.h"
#include "paddle.h"

namespace {

#include "ocr_golden.inc"

int g_failures = 0;

void check(bool ok, const std::string& what) {
    if (!ok) {
        std::printf("FAIL %s\n", what.c_str());
        ++g_failures;
    }
}

uint32_t lcg_next(uint32_t& state) {
    state = state * 1664525u + 1013904223u;
    return state >> 8;
}

std::vector<uint8_t> image(uint32_t seed, int w, int h) {
    std::vector<uint8_t> out(static_cast<size_t>(w) * h * 3);
    for (uint8_t& v : out) v = static_cast<uint8_t>(lcg_next(seed) & 0xFF);
    return out;
}

template <typename T>
std::string digest(const std::vector<T>& data) {
    jpdf::ocr::Sha256 hash;
    hash.update(reinterpret_cast<const uint8_t*>(data.data()), data.size() * sizeof(T));
    return hash.hex_digest();
}

struct Rect {
    int x0, y0, x1, y1;
};

std::vector<float> prob_map(uint32_t seed, int w, int h, const std::vector<Rect>& rects) {
    std::vector<float> out(static_cast<size_t>(w) * h);
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            bool inside = false;
            for (const Rect& r : rects) inside |= r.x0 <= x && x <= r.x1 && r.y0 <= y && y <= r.y1;
            const uint32_t k = lcg_next(seed) % 500;
            out[static_cast<size_t>(y) * w + x] =
                inside ? static_cast<float>((500 + k) / 1000.0) : static_cast<float>(k / 2000.0);
        }
    }
    return out;
}

void test_resize() {
    struct Case {
        uint32_t seed;
        int sw, sh, dw, dh;
        const char* want;
    };
    const Case cases[] = {{1, 7, 5, 11, 9, kResize0},
                          {2, 13, 11, 4, 3, kResize1},
                          {3, 40, 9, 97, 48, kResize2},
                          {4, 300, 60, 160, 48, kResize3},
                          {5, 33, 17, 32, 32, kResize4}};
    for (const Case& c : cases) {
        const std::vector<uint8_t> src = image(c.seed, c.sw, c.sh);
        std::vector<uint8_t> dst(static_cast<size_t>(c.dw) * c.dh * 3);
        jpdf::ocr::resize_linear(src.data(), c.sw, c.sh, c.sw * 3, 3, dst.data(), c.dw, c.dh);
        check(digest(dst) == c.want, "resize " + std::to_string(c.seed));
    }
}

void test_warp() {
    struct Case {
        uint32_t seed;
        int sw, sh;
        std::array<jpdf::ocr::Point2f, 4> quad;
        int dw, dh;
        const char* want;
    };
    const Case cases[] = {
        {6, 40, 30, {{{3, 4}, {33, 4}, {33, 20}, {3, 20}}}, 30, 16, kWarp0},
        {7,
         40,
         30,
         {{{5.5f, 3.25f}, {35.75f, 6.5f}, {33.0f, 24.0f}, {2.5f, 21.0f}}},
         31,
         18,
         kWarp1},
        {8, 64, 48, {{{10, 40}, {12, 5}, {30, 7}, {28, 42}}}, 18, 35, kWarp2},
    };
    for (const Case& c : cases) {
        const std::vector<uint8_t> src = image(c.seed, c.sw, c.sh);
        std::vector<uint8_t> dst(static_cast<size_t>(c.dw) * c.dh * 3);
        const bool ok = jpdf::ocr::warp_perspective_cubic(src.data(), c.sw, c.sh, c.sw * 3, 3,
                                                          c.quad.data(), c.dw, c.dh, dst.data());
        check(ok && digest(dst) == c.want, "warpPerspective " + std::to_string(c.seed));
    }
}

template <size_t N>
void check_boxes(const std::vector<jpdf::ocr::Detection>& got, int want_count,
                 const std::array<int, N>& want, const std::string& name) {
    std::vector<int> flat;
    for (const jpdf::ocr::Detection& d : got) {
        for (const jpdf::ocr::Point& p : d.box) {
            flat.push_back(p.x);
            flat.push_back(p.y);
        }
    }
    const bool ok = static_cast<int>(got.size()) == want_count &&
                    std::vector<int>(want.begin(), want.end()) == flat;
    check(ok, name);
    if (!ok) {
        std::printf("  got:");
        for (int v : flat) std::printf(" %d", v);
        std::printf("\n");
    }
}

void test_detect() {
    jpdf::ocr::DetectorParams params;
    {
        const std::vector<float> pred =
            prob_map(9, 64, 48, {{5, 5, 40, 12}, {8, 20, 55, 30}, {50, 35, 60, 44}});
        auto boxes = jpdf::ocr::detect_boxes(pred.data(), 64, 48, 128, 96, params);
        jpdf::ocr::sort_boxes(&boxes);
        check_boxes(boxes, kDetect0Count, kDetect0Boxes, "DBPostProcess 0");
    }
    {
        const std::vector<float> pred = prob_map(
            10, 96, 64, {{2, 2, 90, 8}, {10, 15, 20, 60}, {30, 20, 34, 24}, {40, 30, 80, 50}});
        auto boxes = jpdf::ocr::detect_boxes(pred.data(), 96, 64, 101, 77, params);
        jpdf::ocr::sort_boxes(&boxes);
        check_boxes(boxes, kDetect1Count, kDetect1Boxes, "DBPostProcess 1");
    }
    {
        const std::vector<float> pred =
            prob_map(11, 48, 48, {{0, 0, 47, 3}, {20, 20, 21, 21}, {10, 30, 40, 40}});
        auto boxes = jpdf::ocr::detect_boxes(pred.data(), 48, 48, 48, 48, params);
        jpdf::ocr::sort_boxes(&boxes);
        check_boxes(boxes, kDetect2Count, kDetect2Boxes, "DBPostProcess 2");
    }
}

void test_detector_input() {
    struct Case {
        uint32_t seed;
        int w, h;
        std::array<int, 2> shape;
        const char* want;
    };
    const Case cases[] = {{12, 50, 30, kDetInput0Shape, kDetInput0},
                          {13, 130, 70, kDetInput1Shape, kDetInput1},
                          {14, 20, 12, kDetInput2Shape, kDetInput2}};
    for (const Case& c : cases) {
        jpdf::ocr::Image img{image(c.seed, c.w, c.h), c.w, c.h};
        const jpdf::ocr::DetectorInput in = jpdf::ocr::prepare_detector_input(img, {});
        check(in.height == c.shape[0] && in.width == c.shape[1] && digest(in.tensor) == c.want,
              "detector input " + std::to_string(c.seed));
    }
}

void test_crop() {
    struct Case {
        uint32_t seed;
        jpdf::ocr::Quad box;
        std::array<int, 2> shape;
        const char* want;
    };
    const Case cases[] = {
        {15, {{{4, 6}, {70, 6}, {70, 20}, {4, 20}}}, kCrop0Shape, kCrop0},
        {16, {{{10, 10}, {60, 14}, {58, 30}, {8, 26}}}, kCrop1Shape, kCrop1},
        {17, {{{30, 2}, {44, 2}, {44, 55}, {30, 55}}}, kCrop2Shape, kCrop2},
    };
    for (const Case& c : cases) {
        const jpdf::ocr::Image img{image(c.seed, 80, 60), 80, 60};
        jpdf::ocr::Image out;
        const bool ok = jpdf::ocr::crop_box(img, c.box, &out);
        check(ok && out.width == c.shape[0] && out.height == c.shape[1] &&
                  digest(out.pixels) == c.want,
              "crop " + std::to_string(c.seed));
    }
}

void test_recognizer_input() {
    struct Case {
        uint32_t seed;
        int w, h, width;
        const char* want;
    };
    const Case cases[] = {{18, 90, 30, kRec0Width, kRec0},
                          {19, 700, 40, kRec1Width, kRec1},
                          {20, 2000, 20, kRec2Width, kRec2},
                          {21, 25, 60, kRec3Width, kRec3}};
    const jpdf::ocr::RecognizerParams params;
    for (const Case& c : cases) {
        const jpdf::ocr::Image crop{image(c.seed, c.w, c.h), c.w, c.h};
        const int width = jpdf::ocr::recognizer_width(crop, params);
        std::vector<float> tensor(static_cast<size_t>(3) * params.height * width);
        jpdf::ocr::recognizer_input(crop, params, width, tensor.data());
        check(width == c.width && digest(tensor) == c.want,
              "recognizer input " + std::to_string(c.seed));
    }
}

void test_ctc() {
    // Classes: blank, "a", "b", " ". Steps: a a blank b b " " -> "ab ".
    const std::vector<std::string> chars = {"blank", "a", "b", " "};
    const float probs[] = {0.1f, 0.8f,  0.05f, 0.05f, 0.1f, 0.6f, 0.2f, 0.1f,
                           0.9f, 0.05f, 0.03f, 0.02f, 0.1f, 0.1f, 0.7f, 0.1f,
                           0.2f, 0.1f,  0.5f,  0.2f,  0.1f, 0.1f, 0.1f, 0.7f};
    const jpdf::ocr::Recognition r = jpdf::ocr::ctc_decode(probs, 6, 4, chars);
    check(r.text == "ab ", "CTC text");
    check(std::fabs(r.score - (0.8 + 0.7 + 0.7) / 3) < 1e-6,
          "CTC score is the mean kept probability");
    const jpdf::ocr::Recognition empty = jpdf::ocr::ctc_decode(probs + 8, 1, 4, chars);
    check(empty.text.empty() && empty.score == 0.0, "all blank decodes to nothing with score 0");
}

}  // namespace

int main() {
    std::printf("=== OCR geometry against the reference stack ===\n");
    test_resize();
    test_warp();
    test_detect();
    test_detector_input();
    test_crop();
    test_recognizer_input();
    test_ctc();
    std::printf("=== failures: %d ===\n", g_failures);
    return g_failures == 0 ? 0 : 1;
}
