// api_ocr.cpp - jpdf_ocr_* entry points. Engines and results live in the core's handle table.

#include <algorithm>
#include <cstring>
#include <filesystem>
#include <memory>
#include <new>
#include <string>
#include <string_view>

#include "engine.h"
#include "jpdfium_api.h"
#include "jpdfium_ocr.h"

namespace {

using jpdf::ocr::Engine;
using jpdf::ocr::Image;
using jpdf::ocr::OcrResult;

constexpr uint32_t kEngineKind = 0x4F435245;  // "OCRE"
constexpr uint32_t kResultKind = 0x4F435252;  // "OCRR"
constexpr uint64_t kDefaultMaxPixels = 268435456ull;
constexpr int kMaxThreads = 1024;

jpdf_status fail(jpdf_status status, const std::string& detail) {
    return jpdf_module_fail(status, detail.c_str());
}

// No C++ exception crosses the ABI; error detail goes to the core's per-thread record.
template <typename Body>
jpdf_status guarded(Body&& body) noexcept {
    jpdf_module_begin_call();
    try {
        return body();
    } catch (const std::bad_alloc&) {
        return jpdf_module_fail(JPDF_E_NO_MEMORY, "OCR allocation failed");
    } catch (...) {
        return jpdf_module_fail(JPDF_E_INTERNAL, "unexpected OCR exception");
    }
}

// Known prefix present, unknown trailing bytes zero: the core's rule for input structs.
jpdf_status check_input(const void* data, uint32_t caller_size, uint32_t known, const char* name) {
    if (caller_size < known)
        return fail(JPDF_E_INVALID_ARGUMENT, std::string(name) + ".struct_size is too small");
    const auto* bytes = static_cast<const unsigned char*>(data);
    for (uint32_t i = known; i < caller_size; ++i) {
        if (bytes[i] != 0)
            return fail(JPDF_E_UNSUPPORTED, std::string(name) + " sets unknown fields");
    }
    return JPDF_OK;
}

// Pins a module object for the length of a call.
class Lease {
   public:
    ~Lease() {
        if (lease_) jpdf_module_lease_release(lease_);
    }
    template <typename T>
    jpdf_status acquire(jpdf_handle handle, uint32_t kind, T** out) {
        void* object = nullptr;
        jpdf_status st = jpdf_module_object_acquire(handle, kind, &object, &lease_);
        if (st == JPDF_OK) *out = static_cast<T*>(object);
        return st;
    }

   private:
    jpdf_handle lease_ = 0;
};

class MappedView {
   public:
    ~MappedView() {
        if (view.view) jpdf_view_release(view.view);
    }
    jpdf_bitmap_view view{};
};

// The bitmap as the BGR image cv2.imread would give: gray replicated, alpha dropped.
jpdf_status read_bitmap(jpdf_handle bitmap, uint64_t max_pixels, Image* out, double* dpi_x,
                        double* dpi_y) {
    jpdf_bitmap_info info{};
    info.struct_size = sizeof(info);
    jpdf_status st = jpdf_bitmap_get_info(bitmap, &info);
    if (st != JPDF_OK) return st;
    if (static_cast<uint64_t>(info.width) * static_cast<uint64_t>(info.height) > max_pixels) {
        return fail(JPDF_E_LIMIT, "bitmap exceeds max_pixels");
    }
    MappedView mapped;
    mapped.view.struct_size = sizeof(mapped.view);
    if ((st = jpdf_bitmap_map(bitmap, &mapped.view)) != JPDF_OK) return st;
    const jpdf_bitmap_view& v = mapped.view;
    // The pixel budget above was checked against the metadata, so the mapped view has to describe
    // the same bitmap, with a stride that covers a whole row, before its pixels are read.
    if (v.width != info.width || v.height != info.height || v.width < 0 || v.height < 0) {
        return fail(JPDF_E_FORMAT, "bitmap view does not match its metadata");
    }
    const int64_t bytes_per_pixel =
        v.pixel_format == JPDF_PIXEL_GRAY8 ? 1 : (v.pixel_format == JPDF_PIXEL_RGBA8 ? 4 : 3);
    if (static_cast<int64_t>(v.stride) < static_cast<int64_t>(v.width) * bytes_per_pixel) {
        return fail(JPDF_E_FORMAT, "bitmap view stride is too small");
    }
    out->width = v.width;
    out->height = v.height;
    out->pixels.resize(static_cast<size_t>(v.width) * v.height * 3);
    if (v.pixel_format == JPDF_PIXEL_GRAY8) {
        for (int y = 0; y < v.height; ++y) {
            const uint8_t* s = v.data + static_cast<size_t>(y) * v.stride;
            uint8_t* d = out->pixels.data() + static_cast<size_t>(y) * v.width * 3;
            for (int x = 0; x < v.width; ++x, d += 3) {
                const uint8_t gray = s[x];
                d[0] = gray;
                d[1] = gray;
                d[2] = gray;
            }
        }
    } else if (v.pixel_format == JPDF_PIXEL_RGBA8) {
        for (int y = 0; y < v.height; ++y) {
            const uint8_t* s = v.data + static_cast<size_t>(y) * v.stride;
            uint8_t* d = out->pixels.data() + static_cast<size_t>(y) * v.width * 3;
            for (int x = 0; x < v.width; ++x, d += 3, s += 4) {
                d[0] = s[2];
                d[1] = s[1];
                d[2] = s[0];
            }
        }
    } else {
        for (int y = 0; y < v.height; ++y) {
            const uint8_t* s = v.data + static_cast<size_t>(y) * v.stride;
            uint8_t* d = out->pixels.data() + static_cast<size_t>(y) * v.width * 3;
            for (int x = 0; x < v.width; ++x, d += 3, s += 3) {
                d[0] = s[2];
                d[1] = s[1];
                d[2] = s[0];
            }
        }
    }
    *dpi_x = info.dpi_x;
    *dpi_y = info.dpi_y;
    return JPDF_OK;
}

void destroy_engine(void* p) {
    delete static_cast<Engine*>(p);
}

void destroy_result(void* p) {
    delete static_cast<OcrResult*>(p);
}

void copy_cstr(char* dst, size_t capacity, std::string_view src) {
    const size_t n = std::min(capacity - 1, src.size());
    std::memcpy(dst, src.data(), n);
    dst[n] = '\0';
}

}  // namespace

extern "C" {

uint32_t jpdf_ocr_api_version(void) noexcept {
    return (static_cast<uint32_t>(JPDF_OCR_API_VERSION_MAJOR) << 16) | JPDF_OCR_API_VERSION_MINOR;
}

int64_t jpdf_ocr_abi_struct_size(int32_t struct_id) noexcept {
    switch (struct_id) {
        case JPDF_OCR_STRUCT_OPTIONS:
            return sizeof(jpdf_ocr_options);
        case JPDF_OCR_STRUCT_ENGINE_INFO:
            return sizeof(jpdf_ocr_engine_info);
        case JPDF_OCR_STRUCT_RECOGNIZE_OPTIONS:
            return sizeof(jpdf_ocr_recognize_options);
        case JPDF_OCR_STRUCT_RESULT_INFO:
            return sizeof(jpdf_ocr_result_info);
        case JPDF_OCR_STRUCT_LINE:
            return sizeof(jpdf_ocr_line);
        default:
            return -1;
    }
}

jpdf_status jpdf_ocr_capability(const char* name, int32_t* out_level) noexcept {
    return guarded([&] {
        if (!name || !out_level)
            return fail(JPDF_E_INVALID_ARGUMENT, "name and out_level are required");
        const std::string_view n(name);
        *out_level = JPDF_CAP_ABSENT;
        if (n == "ocr.recognize" && jpdf::ocr::ort_version()) *out_level = JPDF_CAP_IMPLEMENTED;
        return JPDF_OK;
    });
}

jpdf_status jpdf_ocr_engine_open(const jpdf_ocr_options* options,
                                 jpdf_handle* out_engine) noexcept {
    return guarded([&] {
        if (!options || !out_engine)
            return fail(JPDF_E_INVALID_ARGUMENT, "options and out_engine are required");
        jpdf_status st = check_input(options, options->struct_size, sizeof(jpdf_ocr_options),
                                     "jpdf_ocr_options");
        if (st != JPDF_OK) return st;
        if (options->flags != 0 || options->reserved0 != 0) {
            return fail(JPDF_E_UNSUPPORTED, "jpdf_ocr_options.flags and reserved0 must be 0");
        }
        if (!options->profile_dir_utf8 || !*options->profile_dir_utf8) {
            return fail(JPDF_E_INVALID_ARGUMENT, "profile_dir_utf8 is required");
        }
        if (options->intra_op_threads < 0 || options->intra_op_threads > kMaxThreads) {
            return fail(JPDF_E_INVALID_ARGUMENT, "intra_op_threads must be 0 to 1024");
        }
        const std::filesystem::path dir(
            std::u8string_view(reinterpret_cast<const char8_t*>(options->profile_dir_utf8)));
        const std::string expected =
            options->expected_profile_sha256 ? options->expected_profile_sha256 : "";
        std::unique_ptr<Engine> engine;
        std::string detail;
        st = Engine::open(dir, expected, options->intra_op_threads, &engine, &detail);
        if (st != JPDF_OK) return fail(st, detail);
        jpdf_handle token = 0;
        Engine* owned = engine.release();
        st = jpdf_module_object_insert(kEngineKind, owned, destroy_engine, &token);
        if (st != JPDF_OK) {
            delete owned;  // insert failed, so ownership never moved to the table
            return st;
        }
        *out_engine = token;
        return JPDF_OK;
    });
}

jpdf_status jpdf_ocr_engine_get_info(jpdf_handle engine, jpdf_ocr_engine_info* out) noexcept {
    return guarded([&] {
        if (!out) return fail(JPDF_E_INVALID_ARGUMENT, "null output");
        if (out->struct_size < sizeof(jpdf_ocr_engine_info)) {
            return fail(JPDF_E_INVALID_ARGUMENT, "jpdf_ocr_engine_info.struct_size is too small");
        }
        Lease lease;
        Engine* e = nullptr;
        jpdf_status st = lease.acquire(engine, kEngineKind, &e);
        if (st != JPDF_OK) return st;
        out->reserved0 = 0;
        copy_cstr(out->profile_id, sizeof(out->profile_id), e->profile().id);
        const char* version = jpdf::ocr::ort_version();
        copy_cstr(out->runtime_version, sizeof(out->runtime_version), version ? version : "");
        return JPDF_OK;
    });
}

jpdf_status jpdf_ocr_engine_close(jpdf_handle engine) noexcept {
    return guarded([&] { return jpdf_module_object_close(engine, kEngineKind); });
}

jpdf_status jpdf_ocr_recognize(jpdf_handle engine, jpdf_handle bitmap,
                               const jpdf_ocr_recognize_options* options,
                               jpdf_handle* out_result) noexcept {
    return guarded([&] {
        if (!out_result) return fail(JPDF_E_INVALID_ARGUMENT, "out_result is required");
        uint64_t max_pixels = kDefaultMaxPixels;
        if (options) {
            jpdf_status st =
                check_input(options, options->struct_size, sizeof(jpdf_ocr_recognize_options),
                            "jpdf_ocr_recognize_options");
            if (st != JPDF_OK) return st;
            if (options->flags != 0)
                return fail(JPDF_E_UNSUPPORTED, "jpdf_ocr_recognize_options.flags must be 0");
            if (options->max_pixels != 0) max_pixels = options->max_pixels;
        }
        Lease lease;
        Engine* e = nullptr;
        jpdf_status st = lease.acquire(engine, kEngineKind, &e);
        if (st != JPDF_OK) return st;
        Image image;
        double dpi_x = 0, dpi_y = 0;
        if ((st = read_bitmap(bitmap, max_pixels, &image, &dpi_x, &dpi_y)) != JPDF_OK) return st;
        auto result = std::make_unique<OcrResult>();
        std::string detail;
        if ((st = e->recognize(image, result.get(), &detail)) != JPDF_OK) return fail(st, detail);
        result->dpi_x = dpi_x;
        result->dpi_y = dpi_y;
        jpdf_handle token = 0;
        OcrResult* owned = result.release();
        st = jpdf_module_object_insert(kResultKind, owned, destroy_result, &token);
        if (st != JPDF_OK) {
            delete owned;
            return st;
        }
        *out_result = token;
        return JPDF_OK;
    });
}

jpdf_status jpdf_ocr_result_get_info(jpdf_handle result, jpdf_ocr_result_info* out) noexcept {
    return guarded([&] {
        if (!out) return fail(JPDF_E_INVALID_ARGUMENT, "null output");
        if (out->struct_size < sizeof(jpdf_ocr_result_info)) {
            return fail(JPDF_E_INVALID_ARGUMENT, "jpdf_ocr_result_info.struct_size is too small");
        }
        Lease lease;
        OcrResult* r = nullptr;
        jpdf_status st = lease.acquire(result, kResultKind, &r);
        if (st != JPDF_OK) return st;
        out->line_count = static_cast<int32_t>(r->lines.size());
        out->image_width = r->width;
        out->image_height = r->height;
        out->dpi_x = r->dpi_x;
        out->dpi_y = r->dpi_y;
        return JPDF_OK;
    });
}

jpdf_status jpdf_ocr_result_get_line(jpdf_handle result, int32_t index, jpdf_ocr_line* out,
                                     char* text, uint64_t capacity, uint64_t* required) noexcept {
    return guarded([&] {
        if (!out || !required)
            return fail(JPDF_E_INVALID_ARGUMENT, "out and required are required");
        if (!text && capacity != 0)
            return fail(JPDF_E_INVALID_ARGUMENT, "capacity without a buffer");
        if (out->struct_size < sizeof(jpdf_ocr_line)) {
            return fail(JPDF_E_INVALID_ARGUMENT, "jpdf_ocr_line.struct_size is too small");
        }
        Lease lease;
        OcrResult* r = nullptr;
        jpdf_status st = lease.acquire(result, kResultKind, &r);
        if (st != JPDF_OK) return st;
        if (index < 0 || static_cast<size_t>(index) >= r->lines.size()) {
            return fail(JPDF_E_NOT_FOUND, "line index out of range");
        }
        const jpdf::ocr::OcrLine& line = r->lines[static_cast<size_t>(index)];
        out->word_geometry = JPDF_OCR_WORDS_ABSENT;
        std::copy(line.polygon.begin(), line.polygon.end(), out->polygon);
        out->confidence = line.confidence;
        out->detection_score = line.detection_score;
        *required = static_cast<uint64_t>(line.text.size()) + 1;
        if (!text) return JPDF_OK;
        if (capacity < *required)
            return fail(JPDF_E_BUFFER_TOO_SMALL, "buffer is smaller than the line text");
        std::memcpy(text, line.text.c_str(), line.text.size() + 1);
        return JPDF_OK;
    });
}

jpdf_status jpdf_ocr_result_close(jpdf_handle result) noexcept {
    return guarded([&] { return jpdf_module_object_close(result, kResultKind); });
}

}  // extern "C"
