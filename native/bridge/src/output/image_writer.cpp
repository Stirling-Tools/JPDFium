// image_writer.cpp - PNG and document output through an OutputTransaction.

#include "image_writer.h"

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <cmath>
#include <cstddef>
#include <memory>
#include <string>
#include <string_view>
#include <system_error>

#if defined(_WIN32)
#include <fcntl.h>
#include <io.h>
#include <share.h>
#include <sys/stat.h>
#include <windows.h>
#else
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
#endif

#include "output_transaction.h"
#include "runtime/api_status.h"

namespace jpdf {

namespace {

struct SinkContext {
    OutputTransaction* tx;
    jpdf_status status;
};

int32_t transaction_sink(void* ctx, const uint8_t* data, size_t len) noexcept {
    auto* sink = static_cast<SinkContext*>(ctx);
    sink->status = sink->tx->write(data, len);
    return sink->status == JPDF_OK ? 0 : 1;
}

// Pillow's formula is int(dpi / 0.0254 + 0.5), so pHYs matches the OCRmyPDF reference exactly.
// std::lround computes that round-half-away-from-zero directly, without the precision loss of
// forming `dpi / 0.0254 + 0.5` as a double and truncating it.
uint32_t pixels_per_meter(double dpi) noexcept {
    if (!std::isfinite(dpi) || dpi <= 0 || dpi > 1e6) return 0;
    return static_cast<uint32_t>(std::lround(dpi / 0.0254));
}

}  // namespace

jpdf_status parse_write_options(const jpdf_write_options* options, WriteRequest* out) noexcept {
    *out = WriteRequest{};
    if (!options) return JPDF_OK;
    jpdf_status st = check_input_struct(options, options->struct_size, sizeof(jpdf_write_options),
                                        sizeof(jpdf_write_options), "jpdf_write_options");
    if (st != JPDF_OK) return st;
    if ((options->flags & ~JPDF_WRITE_DURABLE) != 0) {
        return fail(JPDF_E_UNSUPPORTED, "unknown jpdf_write_options.flags bits");
    }
    if (options->image_format != JPDF_IMAGE_PNG) {
        return fail(JPDF_E_UNSUPPORTED, "only JPDF_IMAGE_PNG output is implemented");
    }
    if (options->png_compression < -1 || options->png_compression > 9) {
        return fail(JPDF_E_INVALID_ARGUMENT, "png_compression must be -1 or 0-9");
    }
    out->durable = (options->flags & JPDF_WRITE_DURABLE) != 0;
    out->image_format = options->image_format;
    out->png_compression = options->png_compression < 0 ? 6 : options->png_compression;
    out->max_bytes = options->max_bytes;
    return JPDF_OK;
}

jpdf_status parse_document_write_options(const jpdf_write_options* options,
                                         WriteRequest* out) noexcept {
    *out = WriteRequest{};
    if (!options) return JPDF_OK;
    jpdf_status st = check_input_struct(options, options->struct_size, sizeof(jpdf_write_options),
                                        sizeof(jpdf_write_options), "jpdf_write_options");
    if (st != JPDF_OK) return st;
    if ((options->flags & ~JPDF_WRITE_DURABLE) != 0) {
        return fail(JPDF_E_UNSUPPORTED, "unknown jpdf_write_options.flags bits");
    }
    if (options->image_format != 0 || options->png_compression != 0) {
        return fail(JPDF_E_UNSUPPORTED, "image_format and png_compression must be 0 here");
    }
    out->durable = (options->flags & JPDF_WRITE_DURABLE) != 0;
    out->max_bytes = options->max_bytes;
    return JPDF_OK;
}

jpdf_status write_bytes_file(const uint8_t* data, size_t size, const char* path_utf8,
                             const WriteRequest& request) noexcept {
    std::unique_ptr<OutputTransaction> tx;
    jpdf_status st = OutputTransaction::begin(path_utf8, request.durable, request.max_bytes, &tx);
    if (st != JPDF_OK) return st;
    st = tx->write(data, size);
    if (st != JPDF_OK) return st;
    st = tx->finalize();
    if (st != JPDF_OK) return st;
    st = tx->validate();
    if (st != JPDF_OK) return st;
    return tx->publish();
}

jpdf_status write_image_file(const PixelStorage& pixels, const char* path_utf8,
                             const WriteRequest& request) noexcept {
    const int32_t channels = channels_for(pixels.pixel_format);
    if (channels == 0 || !pixels.data) return fail(JPDF_E_INTERNAL, "bitmap has no pixels");
    return write_png_file(PngImage{pixels.data.get(), pixels.width, pixels.height, pixels.stride,
                                   channels, pixels.dpi_x, pixels.dpi_y},
                          path_utf8, request);
}

jpdf_status write_png_file(const PngImage& image, const char* path_utf8,
                           const WriteRequest& request) noexcept {
    if (!image.data) return fail(JPDF_E_INTERNAL, "image has no pixels");
    std::unique_ptr<OutputTransaction> tx;
    jpdf_status st = OutputTransaction::begin(path_utf8, request.durable, request.max_bytes, &tx);
    if (st != JPDF_OK) return st;

    SinkContext sink{tx.get(), JPDF_OK};
    const int32_t rc =
        jpdfium_rust_write_png(image.data, image.width, image.height, image.stride, image.channels,
                               pixels_per_meter(image.dpi_x), pixels_per_meter(image.dpi_y),
                               request.png_compression, transaction_sink, &sink);
    if (rc != 0) {
        // The sink already recorded why it stopped (I/O or max_bytes); keep that cause.
        if (sink.status != JPDF_OK) return sink.status;
        return fail(JPDF_E_INTERNAL, "PNG encoder failed");
    }
    st = tx->finalize();
    if (st != JPDF_OK) return st;
    st = tx->validate();
    if (st != JPDF_OK) return st;
    return tx->publish();
}

}  // namespace jpdf
