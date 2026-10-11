// glyphless_font.h - a TrueType font with no drawable glyphs, built in code for text layers.
#pragma once

#include <cstdint>
#include <vector>

namespace jpdf {

// Metrics in font units of a 1000-unit em, shared by the font program and its PDF descriptor.
inline constexpr int kGlyphlessAdvance = 500;
inline constexpr int kGlyphlessAscent = 800;
inline constexpr int kGlyphlessDescent = -200;

// Two blank glyphs, .notdef and the one text layers draw, shaped like OCRmyPDF's Occulta.
const std::vector<uint8_t>& glyphless_font();  // built once

}  // namespace jpdf
