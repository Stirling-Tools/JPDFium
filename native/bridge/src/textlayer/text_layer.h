// text_layer.h - invisible text layer PDFs for OCRmyPDF's sandwich renderer. No PDFium.
#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

#include "jpdfium_api.h"

namespace jpdf {

struct Point {
    double x = 0;
    double y = 0;
};

// Corners in image pixels: top-left, top-right, bottom-right, bottom-left as read.
using Quad = std::array<Point, 4>;

struct LayerWord {
    std::vector<char32_t> text;
    double x0 = 0;  // image pixels; the quad's horizontal extent for a quad word
    double x1 = 0;
    Quad corners;
    bool quad = false;
    bool bidi = false;  // holds a class that needs bidi layout
    bool cjk_only = false;
    bool blank = false;  // whitespace only
};

enum class Direction : uint8_t { Auto, Ltr, Rtl };

struct LayerLine {
    double x0 = 0;
    double y0 = 0;
    double x1 = 0;
    double y1 = 0;
    Quad corners;
    bool quad = false;
    Direction direction = Direction::Auto;
    size_t first_word = 0;
    size_t word_count = 0;
};

struct TextLayer {
    int32_t image_width = 0;
    int32_t image_height = 0;
    double dpi_x = 0;
    double dpi_y = 0;
    std::vector<LayerLine> lines;
    std::vector<LayerWord> words;
};

// Validates and copies the caller's arrays; the result borrows nothing. May throw bad_alloc.
jpdf_status parse_text_layer(const jpdf_text_layer_options* options, const jpdf_text_line* lines,
                             int32_t line_count, const jpdf_text_word* words, int32_t word_count,
                             TextLayer* out);

// Serializes the one-page PDF; equal layers give byte-identical files. May throw bad_alloc.
jpdf_status build_text_layer_pdf(const TextLayer& layer, std::string* out);

// A TrueType font with no drawable glyphs, built in code for text layers.
// Metrics in font units of a 1000-unit em, shared by the font program and its PDF descriptor.
inline constexpr int kGlyphlessAdvance = 500;
inline constexpr int kGlyphlessAscent = 800;
inline constexpr int kGlyphlessDescent = -200;

// Two blank glyphs, .notdef and the one text layers draw, shaped like OCRmyPDF's Occulta.
const std::vector<uint8_t>& glyphless_font();  // built once

}  // namespace jpdf
