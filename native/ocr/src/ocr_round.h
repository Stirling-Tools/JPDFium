// ocr_round.h - Python's round(), shared by the detector resize path and its tests.
#pragma once

#include <cmath>

namespace jpdf::ocr {

// Python's round(): half to even, from floor and the fraction so the result does not follow
// the thread's floating-point rounding mode (which std::nearbyint does).
inline double py_round(double v) {
    const double base = std::floor(v);
    const long long whole = static_cast<long long>(base);
    const double frac = v - base;
    if (frac > 0.5) return static_cast<double>(whole + 1);
    if (frac < 0.5) return static_cast<double>(whole);
    return static_cast<double>((whole & 1) == 0 ? whole : whole + 1);
}

}  // namespace jpdf::ocr
