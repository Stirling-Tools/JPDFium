// pixels.h - owned, immutable pixel storage and format conversion. No PDFium dependency.
#pragma once

#include <cstdint>
#include <cstdlib>
#include <memory>

namespace jpdf {

struct FreeDeleter {
    void operator()(uint8_t* p) const noexcept {
        std::free(p);
    }
};
using PixelBuffer = std::unique_ptr<uint8_t, FreeDeleter>;

// Never mutated once published, so views can read it without locking.
struct PixelStorage {
    PixelBuffer data;
    uint64_t byte_size = 0;
    int32_t width = 0;
    int32_t height = 0;
    int32_t stride = 0;
    int32_t pixel_format = 0;  // JPDF_PIXEL_*
    double dpi_x = 0;
    double dpi_y = 0;
};

int32_t channels_for(int32_t pixel_format) noexcept;

// In-place narrowing of tightly packed straight RGBA; the result is tightly packed too.
void rgba_to_rgb(uint8_t* data, int32_t width, int32_t height) noexcept;
// Pillow-compatible ITU-R 601-2 luma, so later OCRmyPDF parity needs no second conversion.
void rgba_to_gray(uint8_t* data, int32_t width, int32_t height) noexcept;

}  // namespace jpdf
