// text_layer.h - invisible text layer PDFs for OCRmyPDF's sandwich renderer. No PDFium.
#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

#include "jpdfium_api.h"

namespace jpdf {

struct LayerWord {
    std::vector<char32_t> text;
    double x0 = 0;  // image pixels
    double x1 = 0;
    bool cjk_only = false;
    bool blank = false;  // whitespace only
};

struct LayerLine {
    double x0 = 0;
    double y0 = 0;
    double x1 = 0;
    double y1 = 0;
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

}  // namespace jpdf
