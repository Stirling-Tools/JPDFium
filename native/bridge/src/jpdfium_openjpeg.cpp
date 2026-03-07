// jpdfium_openjpeg.cpp - JPEG2000 stream validation and partial recovery.
//
// Opt-in: requires JPDFIUM_HAS_OPENJPEG at build time.
// Validates /JPXDecode streams with non-strict mode for partial bitstream
// recovery, and can re-encode partially decoded images as raw pixels.

#include "jpdfium.h"
#include <cstdlib>
#include <cstring>
#include <sstream>
#include <string>

#ifdef JPDFIUM_HAS_OPENJPEG

#include <openjpeg.h>

// Memory-backed stream for reading from byte buffer
struct MemStream {
    const uint8_t* data;
    size_t len;
    size_t pos;
};

static OPJ_SIZE_T mem_read(void* buf, OPJ_SIZE_T bytes, void* userData) {
    auto* ms = static_cast<MemStream*>(userData);
    if (ms->pos >= ms->len) return static_cast<OPJ_SIZE_T>(-1);
    size_t toRead = (ms->pos + bytes > ms->len) ? (ms->len - ms->pos) : bytes;
    memcpy(buf, ms->data + ms->pos, toRead);
    ms->pos += toRead;
    return static_cast<OPJ_SIZE_T>(toRead);
}

static OPJ_OFF_T mem_skip(OPJ_OFF_T bytes, void* userData) {
    auto* ms = static_cast<MemStream*>(userData);
    if (bytes < 0) {
        if (ms->pos < static_cast<size_t>(-bytes)) ms->pos = 0;
        else ms->pos += bytes;
    } else {
        ms->pos += static_cast<size_t>(bytes);
        if (ms->pos > ms->len) ms->pos = ms->len;
    }
    return static_cast<OPJ_OFF_T>(bytes);
}

static OPJ_BOOL mem_seek(OPJ_OFF_T bytes, void* userData) {
    auto* ms = static_cast<MemStream*>(userData);
    if (bytes < 0 || static_cast<size_t>(bytes) > ms->len) return OPJ_FALSE;
    ms->pos = static_cast<size_t>(bytes);
    return OPJ_TRUE;
}

// Error capture
struct ErrorCtx { std::string msg; int count = 0; };

static void opj_error_cb(const char* msg, void* data) {
    auto* ctx = static_cast<ErrorCtx*>(data);
    if (msg && ctx->msg.empty()) ctx->msg = msg;
    ctx->count++;
}
static void opj_warn_cb(const char*, void*) {}
static void opj_info_cb(const char*, void*) {}

static std::string json_escape_opj(const std::string& s) {
    std::string out;
    out.reserve(s.size());
    for (char c : s) {
        if (c == '"' || c == '\\') out += '\\';
        if (c == '\n') { out += "\\n"; continue; }
        if (c == '\r') continue;
        out += c;
    }
    return out;
}

// Try to decode with a specific codec type
static opj_image_t* try_decode(
    const uint8_t* data, size_t len,
    OPJ_CODEC_FORMAT fmt,
    ErrorCtx& errCtx) {

    MemStream ms = {data, len, 0};

    opj_stream_t* stream = opj_stream_create(len, OPJ_TRUE);
    if (!stream) return nullptr;

    opj_stream_set_user_data(stream, &ms, nullptr);
    opj_stream_set_user_data_length(stream, len);
    opj_stream_set_read_function(stream, mem_read);
    opj_stream_set_seek_function(stream, mem_seek);
    opj_stream_set_skip_function(stream, mem_skip);

    opj_codec_t* codec = opj_create_decompress(fmt);
    if (!codec) { opj_stream_destroy(stream); return nullptr; }

    opj_set_error_handler(codec, opj_error_cb, &errCtx);
    opj_set_warning_handler(codec, opj_warn_cb, nullptr);
    opj_set_info_handler(codec, opj_info_cb, nullptr);

    // Non-strict mode for partial bitstream recovery
    opj_decoder_set_strict_mode(codec, OPJ_FALSE);

    opj_dparameters_t params;
    opj_set_default_decoder_parameters(&params);
    opj_setup_decoder(codec, &params);

    opj_image_t* image = nullptr;
    if (!opj_read_header(stream, codec, &image)) {
        opj_destroy_codec(codec);
        opj_stream_destroy(stream);
        return nullptr;
    }

    if (!opj_decode(codec, stream, image)) {
        // Partial - image may have partial data
        // Still return the image for inspection
    }

    opj_destroy_codec(codec);
    opj_stream_destroy(stream);
    return image;
}

extern "C" {

JPDFIUM_EXPORT int32_t jpdfium_validate_jpx_stream(
    const uint8_t* jpxData, int64_t jpxLen,
    char** resultJson) {

    if (!jpxData || jpxLen <= 0 || !resultJson)
        return JPDFIUM_ERR_INVALID;

    ErrorCtx errCtx;
    size_t len = static_cast<size_t>(jpxLen);

    // Try JP2 box format first, then raw J2K codestream
    opj_image_t* image = try_decode(jpxData, len, OPJ_CODEC_JP2, errCtx);
    if (!image) {
        errCtx = {};
        image = try_decode(jpxData, len, OPJ_CODEC_J2K, errCtx);
    }

    if (!image) {
        std::ostringstream os;
        os << "{\"status\":\"unreadable\""
           << ",\"width\":0,\"height\":0,\"components\":0"
           << ",\"error\":\"" << json_escape_opj(errCtx.msg) << "\"}";
        *resultJson = strdup(os.str().c_str());
        return -1;
    }

    int w = static_cast<int>(image->x1 - image->x0);
    int h = static_cast<int>(image->y1 - image->y0);
    int comps = static_cast<int>(image->numcomps);

    const char* status = (errCtx.count > 0) ? "partial" : "valid";
    int rc = (errCtx.count > 0) ? 1 : 0;

    std::ostringstream os;
    os << "{\"status\":\"" << status << "\""
       << ",\"width\":" << w << ",\"height\":" << h
       << ",\"components\":" << comps
       << ",\"error\":\"" << json_escape_opj(errCtx.msg) << "\"}";

    *resultJson = strdup(os.str().c_str());
    opj_image_destroy(image);
    return rc;
}

JPDFIUM_EXPORT int32_t jpdfium_jpx_to_raw(
    const uint8_t* jpxData, int64_t jpxLen,
    uint8_t** rawPixels, int64_t* rawLen,
    int32_t* width, int32_t* height, int32_t* components) {

    if (!jpxData || jpxLen <= 0 || !rawPixels || !rawLen ||
        !width || !height || !components)
        return JPDFIUM_ERR_INVALID;

    ErrorCtx errCtx;
    size_t len = static_cast<size_t>(jpxLen);

    opj_image_t* image = try_decode(jpxData, len, OPJ_CODEC_JP2, errCtx);
    if (!image) {
        errCtx = {};
        image = try_decode(jpxData, len, OPJ_CODEC_J2K, errCtx);
    }

    if (!image) return JPDFIUM_ERR_INVALID;

    *width = static_cast<int32_t>(image->x1 - image->x0);
    *height = static_cast<int32_t>(image->y1 - image->y0);
    *components = static_cast<int32_t>(image->numcomps);

    size_t pixelCount = static_cast<size_t>(*width) * (*height) * (*components);
    *rawPixels = static_cast<uint8_t*>(malloc(pixelCount));

    // Interleave component planes into contiguous pixel buffer
    for (int y = 0; y < *height; y++) {
        for (int x = 0; x < *width; x++) {
            for (int c = 0; c < *components; c++) {
                int val = image->comps[c].data[y * (*width) + x];
                // Clamp to 8-bit
                if (val < 0) val = 0;
                if (val > 255) val = 255;
                (*rawPixels)[(y * (*width) + x) * (*components) + c] =
                    static_cast<uint8_t>(val);
            }
        }
    }

    *rawLen = static_cast<int64_t>(pixelCount);
    opj_image_destroy(image);
    return JPDFIUM_OK;
}

} // extern "C"

#else // !JPDFIUM_HAS_OPENJPEG

extern "C" {

JPDFIUM_EXPORT int32_t jpdfium_validate_jpx_stream(
    const uint8_t*, int64_t, char** resultJson) {
    if (resultJson) *resultJson = strdup("{\"status\":\"unavailable\"}");
    return JPDFIUM_ERR_NATIVE;
}

JPDFIUM_EXPORT int32_t jpdfium_jpx_to_raw(
    const uint8_t*, int64_t, uint8_t**, int64_t*,
    int32_t*, int32_t*, int32_t*) {
    return JPDFIUM_ERR_NATIVE;
}

} // extern "C"

#endif // JPDFIUM_HAS_OPENJPEG
