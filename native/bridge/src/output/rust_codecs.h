// rust_codecs.h - streaming encoders exported by the jpdfium-impl Rust crate.
#pragma once

#include <cstddef>
#include <cstdint>

extern "C" {
// Receives encoder output in order; a non-zero return aborts the encoder.
typedef int32_t (*jpdfium_write_sink)(void* ctx, const uint8_t* data, size_t len);

// Both return 0, -1 for invalid arguments, -2 when the sink aborted, -3 on an internal failure.
int32_t jpdfium_rust_write_png(const uint8_t* pixels, int32_t width, int32_t height, int32_t stride,
                               int32_t channels, uint32_t ppm_x, uint32_t ppm_y,
                               int32_t compression, jpdfium_write_sink sink, void* ctx);
int32_t jpdfium_rust_zlib_compress(const uint8_t* data, size_t len, int32_t level,
                                   jpdfium_write_sink sink, void* ctx);
}
