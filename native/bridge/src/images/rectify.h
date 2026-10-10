// rectify.h - perspective rectification of a quad into an upright image. No PDFium dependency.
#pragma once

#include <array>
#include <cstdint>

#include "jpdfium_api.h"
#include "pixels.h"

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

}  // namespace jpdf
