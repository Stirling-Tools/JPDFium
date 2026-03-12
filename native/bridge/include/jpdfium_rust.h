#pragma once
#include <stdint.h>

// Rust-powered PDF processing functions.
//
// When compiled with -DJPDFIUM_USE_RUST, these symbols are provided by the
// linked libjpdfium_rust.a static library (built from native/rust/).
// When Rust is NOT available, jpdfium_rust.cpp provides stub implementations
// that return JPDFIUM_ERR_NATIVE (-99).

#ifdef __cplusplus
extern "C" {
#endif

// Compress a PDF using lopdf + zopfli for superior FlateDecode streams.
//
// Loads the PDF with lopdf, decompresses all streams, then recompresses each
// stream with zopfli (ZLib format) for 10-25% better compression than standard
// DEFLATE. Also runs PNG image streams through oxipng.
//
// @param input          PDF bytes
// @param input_len      Length of input
// @param out_ptr        [out] Pointer to allocated output buffer (caller must free
//                       with jpdfium_rust_free)
// @param out_len        [out] Length of output buffer
// @param zopfli_iters   Number of zopfli iterations (5=fast, 15=default, 100=max)
// @return 0 on success, -1 on error, JPDFIUM_ERR_NATIVE(-99) if unavailable
JPDFIUM_EXPORT int32_t jpdfium_rust_compress_pdf(
    const uint8_t* input, int64_t input_len,
    uint8_t** out_ptr, int64_t* out_len,
    int32_t zopfli_iters);

// Repair a PDF using lopdf's tolerant XRef parser.
//
// Loads the PDF with lopdf (which tolerantly parses broken XRef tables), then
// immediately saves it back — this rebuilds the cross-reference table correctly.
// Returns JPDFIUM_REPAIR_FIXED(1) on success.
//
// @param input          PDF bytes (may be damaged)
// @param input_len      Length of input
// @param out_ptr        [out] Pointer to allocated output buffer (caller must free
//                       with jpdfium_rust_free)
// @param out_len        [out] Length of output buffer
// @return JPDFIUM_REPAIR_FIXED(1) on success, JPDFIUM_REPAIR_FAILED(-1) on failure,
//         JPDFIUM_ERR_NATIVE(-99) if unavailable
JPDFIUM_EXPORT int32_t jpdfium_rust_repair_lopdf(
    const uint8_t* input, int64_t input_len,
    uint8_t** out_ptr, int64_t* out_len);

// Resize raw pixel data using SIMD-accelerated fast_image_resize.
//
// @param src_pixels     Source pixel buffer (interleaved)
// @param src_len        Length of source buffer
// @param src_width      Source image width in pixels
// @param src_height     Source image height in pixels
// @param components     Number of channels: 1=gray, 3=rgb, 4=rgba
// @param dst_width      Target width in pixels
// @param dst_height     Target height in pixels
// @param out_ptr        [out] Allocated output buffer (caller must free with
//                       jpdfium_rust_free)
// @param out_len        [out] Length of output buffer
// @return 0 on success, -1 on error, JPDFIUM_ERR_NATIVE(-99) if unavailable
JPDFIUM_EXPORT int32_t jpdfium_rust_resize_pixels(
    const uint8_t* src_pixels, int64_t src_len,
    int32_t src_width, int32_t src_height,
    int32_t components,
    int32_t dst_width, int32_t dst_height,
    uint8_t** out_ptr, int64_t* out_len);

// Optimise a standalone PNG byte stream using oxipng (lossless).
//
// Useful for PNG images extracted from a PDF before re-embedding. oxipng
// removes superfluous metadata, optimises filter selection, and re-deflates
// with zopfli for the smallest possible lossless PNG.
//
// @param input      Raw PNG file bytes
// @param input_len  Length of input
// @param out_ptr    [out] Optimised PNG bytes (caller must free with jpdfium_rust_free)
// @param out_len    [out] Length of output
// @param level      oxipng preset 0-6 (2=fast, 6=maximum)
// @return 0 if smaller output produced, -1 if no improvement or not a valid PNG,
//         JPDFIUM_ERR_NATIVE(-99) if unavailable
JPDFIUM_EXPORT int32_t jpdfium_rust_compress_png(
    const uint8_t* input, int64_t input_len,
    uint8_t** out_ptr, int64_t* out_len,
    int32_t level);

// Free a buffer allocated by any jpdfium_rust_* function.
//
// Must be called to release memory returned in out_ptr by the Rust functions.
// Safe to call with a NULL pointer.
JPDFIUM_EXPORT void jpdfium_rust_free(uint8_t* ptr);

#ifdef __cplusplus
}
#endif
