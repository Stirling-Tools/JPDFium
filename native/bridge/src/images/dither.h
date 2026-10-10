// dither.h - 8-bit gray to 1-bit conversion. No PDFium dependency.
#pragma once

#include <cstdint>

namespace jpdf {

// Floyd-Steinberg with Pillow's integer arithmetic (Image.convert("1")), so 1-bit output matches
// the OCRmyPDF pypdfium reference bit for bit. Output is packed MSB first, 1 = white.
void dither_to_bits(const uint8_t* gray, int32_t width, int32_t height, int32_t gray_stride,
                    uint8_t* bits, int32_t bits_stride);

}  // namespace jpdf
