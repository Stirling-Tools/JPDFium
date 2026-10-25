// cv_compat_internal.h - fixed-point helpers shared by the cv_*.cpp OpenCV ports.
// Derived from OpenCV (Apache-2.0); see NOTICE.
#pragma once

#include <algorithm>
#include <climits>
#include <cmath>

namespace jpdf::ocr::detail {

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

}  // namespace jpdf::ocr::detail
