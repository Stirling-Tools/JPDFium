// api_runtime.cpp - jpdf_* identity, error, capability and lifecycle entry points.

#include <cstring>

#include "api_status.h"
#include "domain.h"
#include "jpdfium_api.h"
#include "objects/object_table.h"

#ifndef JPDFIUM_BUILD_ID
#define JPDFIUM_BUILD_ID "unknown"
#endif

namespace {

struct Capability {
    const char* name;
    int32_t level;
};

// Qualified is reserved for operations that passed their parity gate; none have yet.
constexpr Capability kCapabilities[] = {
    {"runtime.lifecycle", JPDF_CAP_IMPLEMENTED},  {"pdf.open", JPDF_CAP_IMPLEMENTED},
    {"pdf.render", JPDF_CAP_IMPLEMENTED},         {"image.png_write", JPDF_CAP_IMPLEMENTED},
    {"output.transaction", JPDF_CAP_IMPLEMENTED},
};

}  // namespace

extern "C" {

uint32_t jpdf_api_version(void) noexcept {
    return (static_cast<uint32_t>(JPDF_API_VERSION_MAJOR) << 16) | JPDF_API_VERSION_MINOR;
}

int64_t jpdf_abi_struct_size(int32_t struct_id) noexcept {
    switch (struct_id) {
        case JPDF_STRUCT_RUNTIME_OPTIONS:
            return sizeof(jpdf_runtime_options);
        case JPDF_STRUCT_RUNTIME_INFO:
            return sizeof(jpdf_runtime_info);
        case JPDF_STRUCT_OPEN_OPTIONS:
            return sizeof(jpdf_open_options);
        case JPDF_STRUCT_PAGE_INFO:
            return sizeof(jpdf_page_info);
        case JPDF_STRUCT_RENDER_OPTIONS:
            return sizeof(jpdf_render_options);
        case JPDF_STRUCT_BITMAP_INFO:
            return sizeof(jpdf_bitmap_info);
        case JPDF_STRUCT_BITMAP_VIEW:
            return sizeof(jpdf_bitmap_view);
        case JPDF_STRUCT_WRITE_OPTIONS:
            return sizeof(jpdf_write_options);
        default:
            return -1;
    }
}

const char* jpdf_status_name(jpdf_status status) noexcept {
    switch (status) {
        case JPDF_OK:
            return "JPDF_OK";
        case JPDF_E_INVALID_ARGUMENT:
            return "JPDF_E_INVALID_ARGUMENT";
        case JPDF_E_INVALID_HANDLE:
            return "JPDF_E_INVALID_HANDLE";
        case JPDF_E_STALE_HANDLE:
            return "JPDF_E_STALE_HANDLE";
        case JPDF_E_WRONG_HANDLE_TYPE:
            return "JPDF_E_WRONG_HANDLE_TYPE";
        case JPDF_E_BUSY:
            return "JPDF_E_BUSY";
        case JPDF_E_UNSUPPORTED:
            return "JPDF_E_UNSUPPORTED";
        case JPDF_E_IO:
            return "JPDF_E_IO";
        case JPDF_E_PASSWORD:
            return "JPDF_E_PASSWORD";
        case JPDF_E_FORMAT:
            return "JPDF_E_FORMAT";
        case JPDF_E_NOT_FOUND:
            return "JPDF_E_NOT_FOUND";
        case JPDF_E_LIMIT:
            return "JPDF_E_LIMIT";
        case JPDF_E_NO_MEMORY:
            return "JPDF_E_NO_MEMORY";
        case JPDF_E_BUFFER_TOO_SMALL:
            return "JPDF_E_BUFFER_TOO_SMALL";
        case JPDF_E_NOT_INITIALIZED:
            return "JPDF_E_NOT_INITIALIZED";
        case JPDF_E_QUIESCING:
            return "JPDF_E_QUIESCING";
        case JPDF_E_STOPPED:
            return "JPDF_E_STOPPED";
        case JPDF_E_FAILED:
            return "JPDF_E_FAILED";
        case JPDF_E_AUTHORITY:
            return "JPDF_E_AUTHORITY";
        case JPDF_E_REENTRANT:
            return "JPDF_E_REENTRANT";
        case JPDF_E_CANCELLED:
            return "JPDF_E_CANCELLED";
        case JPDF_E_TIMEOUT:
            return "JPDF_E_TIMEOUT";
        case JPDF_E_INTERNAL:
            return "JPDF_E_INTERNAL";
        default:
            return "JPDF_E_UNKNOWN";
    }
}

jpdf_status jpdf_last_error_message(char* buffer, uint64_t capacity, uint64_t* required) noexcept {
    // Deliberately not guarded(): reading the detail must not clear it.
    return jpdf::copy_last_error(buffer, capacity, required);
}

jpdf_status jpdf_capability(const char* name, int32_t* out_level) noexcept {
    return jpdf::guarded([&] {
        if (!name || !out_level) return jpdf::fail(JPDF_E_INVALID_ARGUMENT, "null argument");
        *out_level = JPDF_CAP_ABSENT;
        for (const Capability& cap : kCapabilities) {
            if (std::strcmp(cap.name, name) == 0) *out_level = cap.level;
        }
        return JPDF_OK;
    });
}

jpdf_status jpdf_runtime_init(const jpdf_runtime_options* options) noexcept {
    return jpdf::guarded([&] {
        int32_t renderer = JPDF_RENDERER_AUTO;
        if (options) {
            jpdf_status st = jpdf::check_input_struct(
                options, options->struct_size, sizeof(jpdf_runtime_options),
                sizeof(jpdf_runtime_options), "jpdf_runtime_options");
            if (st != JPDF_OK) return st;
            if (options->flags != 0 || options->reserved0 != 0) {
                return jpdf::fail(JPDF_E_UNSUPPORTED, "jpdf_runtime_options reserved bits set");
            }
            renderer = options->renderer;
        }
        return jpdf::Domain::instance().init(renderer);
    });
}

jpdf_status jpdf_runtime_get_info(jpdf_runtime_info* out) noexcept {
    return jpdf::guarded([&] {
        if (!out) return jpdf::fail(JPDF_E_INVALID_ARGUMENT, "null output");
        jpdf_status st = jpdf::check_output_struct(out->struct_size, sizeof(jpdf_runtime_info),
                                                   "jpdf_runtime_info");
        if (st != JPDF_OK) return st;
        jpdf::Domain& domain = jpdf::Domain::instance();
        const jpdf::DomainStats stats = domain.stats();
        jpdf::ObjectTable& table = jpdf::objects();
        out->state = static_cast<int32_t>(domain.state());
        out->api_major = JPDF_API_VERSION_MAJOR;
        out->api_minor = JPDF_API_VERSION_MINOR;
        out->renderer = domain.renderer();
        out->reserved0 = 0;
        out->build_id = JPDFIUM_BUILD_ID;
        out->live_documents = stats.live_documents;
        out->live_pages = stats.live_pages;
        out->live_bitmaps = table.live(jpdf::ObjectType::Bitmap);
        out->live_views = table.live(jpdf::ObjectType::View);
        out->domain_acquisitions = stats.acquisitions;
        out->domain_wait_ns = stats.wait_ns;
        out->domain_hold_ns = stats.hold_ns;
        return JPDF_OK;
    });
}

jpdf_status jpdf_runtime_quiesce(void) noexcept {
    return jpdf::guarded([] { return jpdf::Domain::instance().quiesce(); });
}

jpdf_status jpdf_runtime_shutdown(void) noexcept {
    return jpdf::guarded([] { return jpdf::Domain::instance().shutdown(); });
}

}  // extern "C"
