// jpdfium_brotli.cpp - Brotli codec for PDF 2.0+ /BrotliDecode streams.
//
// Opt-in: requires JPDFIUM_HAS_BROTLI at build time.
// Transcodes /BrotliDecode - /FlateDecode for backward compatibility.

#include "jpdfium.h"
#include <cstdlib>
#include <cstring>

#ifdef JPDFIUM_HAS_BROTLI

#include <brotli/decode.h>
#include <zlib.h>

extern "C" {

JPDFIUM_EXPORT int32_t jpdfium_brotli_decode(
    const uint8_t* compressed, int64_t compressedLen,
    uint8_t** output, int64_t* outputLen) {

    if (!compressed || compressedLen <= 0 || !output || !outputLen)
        return JPDFIUM_ERR_INVALID;

    BrotliDecoderState* state = BrotliDecoderCreateInstance(nullptr, nullptr, nullptr);
    if (!state) return JPDFIUM_ERR_INVALID;

    size_t outCapacity = static_cast<size_t>(compressedLen) * 4;
    if (outCapacity < 4096) outCapacity = 4096;
    uint8_t* outBuf = static_cast<uint8_t*>(malloc(outCapacity));
    size_t totalOut = 0;

    const uint8_t* nextIn = compressed;
    size_t availableIn = static_cast<size_t>(compressedLen);

    BrotliDecoderResult result;
    do {
        size_t availableOut = outCapacity - totalOut;
        uint8_t* nextOut = outBuf + totalOut;

        result = BrotliDecoderDecompressStream(
            state, &availableIn, &nextIn, &availableOut, &nextOut, &totalOut);

        if (result == BROTLI_DECODER_RESULT_NEEDS_MORE_OUTPUT) {
            outCapacity *= 2;
            outBuf = static_cast<uint8_t*>(realloc(outBuf, outCapacity));
        }
    } while (result == BROTLI_DECODER_RESULT_NEEDS_MORE_OUTPUT);

    BrotliDecoderDestroyInstance(state);

    if (result != BROTLI_DECODER_RESULT_SUCCESS) {
        free(outBuf);
        *output = nullptr;
        *outputLen = 0;
        return JPDFIUM_ERR_INVALID;
    }

    *output = outBuf;
    *outputLen = static_cast<int64_t>(totalOut);
    return JPDFIUM_OK;
}

JPDFIUM_EXPORT int32_t jpdfium_brotli_to_flate(
    const uint8_t* compressed, int64_t compressedLen,
    uint8_t** flateOutput, int64_t* flateLen) {

    if (!compressed || compressedLen <= 0 || !flateOutput || !flateLen)
        return JPDFIUM_ERR_INVALID;

    // Step 1: Brotli decompress
    uint8_t* raw = nullptr;
    int64_t rawLen = 0;
    int rc = jpdfium_brotli_decode(compressed, compressedLen, &raw, &rawLen);
    if (rc != JPDFIUM_OK || !raw || rawLen <= 0) return JPDFIUM_ERR_INVALID;

    // Step 2: Flate (zlib) recompress
    uLongf destLen = compressBound(static_cast<uLong>(rawLen));
    *flateOutput = static_cast<uint8_t*>(malloc(destLen));
    int zrc = compress2(*flateOutput, &destLen,
                        raw, static_cast<uLong>(rawLen), Z_DEFAULT_COMPRESSION);
    free(raw);

    if (zrc != Z_OK) {
        free(*flateOutput);
        *flateOutput = nullptr;
        *flateLen = 0;
        return JPDFIUM_ERR_INVALID;
    }

    *flateLen = static_cast<int64_t>(destLen);
    return JPDFIUM_OK;
}

} // extern "C"

#else // !JPDFIUM_HAS_BROTLI

extern "C" {

JPDFIUM_EXPORT int32_t jpdfium_brotli_decode(
    const uint8_t*, int64_t, uint8_t**, int64_t*) {
    return JPDFIUM_ERR_NATIVE;
}

JPDFIUM_EXPORT int32_t jpdfium_brotli_to_flate(
    const uint8_t*, int64_t, uint8_t**, int64_t*) {
    return JPDFIUM_ERR_NATIVE;
}

} // extern "C"

#endif // JPDFIUM_HAS_BROTLI
