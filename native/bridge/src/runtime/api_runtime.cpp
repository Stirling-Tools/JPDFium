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
// The stub links only the runtime and the guard surface, so it must not advertise the
// safe-API operations whose implementation files are compiled into the real core alone.
#ifdef JPDFIUM_STUB_GUARDS
constexpr Capability kCapabilities[] = {
    {"runtime.lifecycle", JPDF_CAP_IMPLEMENTED},
    {"legacy.guards", JPDF_CAP_IMPLEMENTED},
};
#else
constexpr Capability kCapabilities[] = {
    {"runtime.lifecycle", JPDF_CAP_IMPLEMENTED},  {"pdf.open", JPDF_CAP_IMPLEMENTED},
    {"pdf.render", JPDF_CAP_IMPLEMENTED},         {"image.png_write", JPDF_CAP_IMPLEMENTED},
    {"output.transaction", JPDF_CAP_IMPLEMENTED}, {"legacy.guards", JPDF_CAP_IMPLEMENTED},
    {"pdf.raster", JPDF_CAP_IMPLEMENTED},         {"image.rectify", JPDF_CAP_IMPLEMENTED},
    {"pdf.text", JPDF_CAP_IMPLEMENTED},           {"text_layer.write", JPDF_CAP_IMPLEMENTED},
    {"image.load", JPDF_CAP_IMPLEMENTED},         {"text_layer.bidi", JPDF_CAP_IMPLEMENTED},
    {"text_layer.quad", JPDF_CAP_IMPLEMENTED},
};
#endif

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
        case JPDF_STRUCT_RASTER_OPTIONS:
            return sizeof(jpdf_raster_options);
        case JPDF_STRUCT_RASTER_RESULT:
            return sizeof(jpdf_raster_result);
        case JPDF_STRUCT_RECTIFY_OPTIONS:
            return sizeof(jpdf_rectify_options);
        case JPDF_STRUCT_RECTIFY_RESULT:
            return sizeof(jpdf_rectify_result);
        case JPDF_STRUCT_TEXT_WORD:
            return sizeof(jpdf_text_word);
        case JPDF_STRUCT_TEXT_LINE:
            return sizeof(jpdf_text_line);
        case JPDF_STRUCT_TEXT_LAYER_OPTIONS:
            return sizeof(jpdf_text_layer_options);
        case JPDF_STRUCT_IMAGE_LOAD_OPTIONS:
            return sizeof(jpdf_image_load_options);
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
        case JPDF_E_UNSYNCED:
            return "JPDF_E_UNSYNCED";
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

jpdf_status jpdf_runtime_shutdown_within(uint32_t timeout_ms) noexcept {
    return jpdf::guarded([&] { return jpdf::Domain::instance().shutdown(timeout_ms); });
}

jpdf_status jpdf_runtime_resume(void) noexcept {
    return jpdf::guarded([] { return jpdf::Domain::instance().resume(); });
}

}  // extern "C"

// api_status.cpp - thread-local error detail and struct-size validation.

#include <cstring>
#include <string>

#include "api_status.h"

namespace jpdf {

namespace {
thread_local std::string t_detail;
thread_local bool t_failed = false;
}  // namespace

void begin_call() noexcept {
    t_detail.clear();
    t_failed = false;
}

jpdf_status fail(jpdf_status status, std::string_view detail, std::string_view more) noexcept {
    // First failure wins so cleanup errors cannot overwrite the original cause.
    if (!t_failed) {
        t_failed = true;
        try {
            t_detail.assign(detail);
            t_detail.append(more);
        } catch (...) {
            t_detail.clear();
        }
    }
    return status;
}

jpdf_status copy_last_error(char* buffer, uint64_t capacity, uint64_t* required) noexcept {
    const uint64_t needed = static_cast<uint64_t>(t_detail.size()) + 1;
    if (required) *required = needed;
    if (!buffer || capacity == 0) return JPDF_OK;
    if (capacity < needed) return JPDF_E_BUFFER_TOO_SMALL;
    std::memcpy(buffer, t_detail.c_str(), static_cast<size_t>(needed));
    return JPDF_OK;
}

jpdf_status check_input_struct(const void* data, uint32_t caller_size, uint32_t min_size,
                               uint32_t known_size, std::string_view name) noexcept {
    if (caller_size < min_size) {
        return fail(JPDF_E_INVALID_ARGUMENT, name, ".struct_size is below the 1.0 layout");
    }
    // A newer caller may only rely on fields this library knows, so unknown bytes must be zero.
    const auto* bytes = static_cast<const unsigned char*>(data);
    for (uint32_t i = known_size; i < caller_size; ++i) {
        if (bytes[i] != 0) {
            return fail(JPDF_E_UNSUPPORTED, name, " sets fields this library does not know");
        }
    }
    return JPDF_OK;
}

jpdf_status check_output_struct(uint32_t caller_size, uint32_t min_size,
                                std::string_view name) noexcept {
    if (caller_size < min_size) {
        return fail(JPDF_E_INVALID_ARGUMENT, name, ".struct_size is below the 1.0 layout");
    }
    return JPDF_OK;
}

}  // namespace jpdf

// api_module.cpp - jpdf_module_*: optional modules' objects in the core's handle table.

#include <memory>
#include <new>
#include <utility>

#include "api_status.h"
#include "jpdfium_api.h"
#include "objects/object_table.h"

namespace jpdf {

// An optional module's object, destroyed by the module's own function once nothing holds it.
struct ModuleObject final : Object {
    using Destroy = void (*)(void*);
    ModuleObject(uint32_t object_kind, void* p, Destroy d) noexcept
        : Object(ObjectType::Module), kind(object_kind), pointer(p), destroy(d) {}
    ~ModuleObject() override {
        if (destroy) destroy(pointer);
    }
    const uint32_t kind;
    void* const pointer;
    Destroy destroy;  // cleared when ownership never transferred
};

// Keeps a module object alive across one module call, even if its handle closes meanwhile.
struct LeaseObject final : Object {
    explicit LeaseObject(std::shared_ptr<ModuleObject> o) noexcept
        : Object(ObjectType::Lease), object(std::move(o)) {}
    const std::shared_ptr<ModuleObject> object;
};

// The process-wide object table. Never destroyed: bindings may release views from finalizers
// during interpreter teardown.
ObjectTable& objects() noexcept {
    alignas(ObjectTable) static unsigned char storage[sizeof(ObjectTable)];
    static ObjectTable* table = ::new (storage) ObjectTable();
    return *table;
}

}  // namespace jpdf

namespace {

using jpdf::ModuleObject;

// Like jpdf::guarded, but keeps the error detail: these run inside a module's own entry point,
// often after it has recorded why it is failing.
template <typename Body>
jpdf_status nested(Body&& body) noexcept {
    try {
        return body();
    } catch (const std::bad_alloc&) {
        return jpdf::fail(JPDF_E_NO_MEMORY, "native allocation failed");
    } catch (...) {
        return jpdf::fail(JPDF_E_INTERNAL, "unexpected native exception");
    }
}

// Resolves a module handle of the given kind; a live handle of another kind is the wrong type.
jpdf_status resolve_module(jpdf_handle handle, uint32_t kind, std::shared_ptr<ModuleObject>* out) {
    jpdf_status st = jpdf::objects().resolve_as(handle, jpdf::ObjectType::Module, out);
    if (st != JPDF_OK) return jpdf::fail(st, "module handle rejected");
    if ((*out)->kind != kind) {
        out->reset();
        return jpdf::fail(JPDF_E_WRONG_HANDLE_TYPE, "module handle is of another kind");
    }
    return JPDF_OK;
}

}  // namespace

extern "C" {

void jpdf_module_begin_call(void) noexcept {
    jpdf::begin_call();
}

jpdf_status jpdf_module_fail(jpdf_status status, const char* detail) noexcept {
    return jpdf::fail(status, detail ? detail : "");
}

jpdf_status jpdf_module_object_insert(uint32_t kind, void* object, jpdf_module_destroy_fn destroy,
                                      jpdf_handle* out_handle) noexcept {
    return nested([&] {
        if (kind == 0 || !object || !destroy || !out_handle) {
            return jpdf::fail(JPDF_E_INVALID_ARGUMENT,
                              "kind, object, destroy and out are required");
        }
        // No destroyer until the table owns it: a throwing insert must leave ownership
        // with the caller, and ~ModuleObject must not free an object it never accepted.
        auto holder = std::make_shared<ModuleObject>(kind, object, nullptr);
        jpdf_handle token = 0;
        jpdf_status st = jpdf::objects().insert(holder, &token);
        if (st != JPDF_OK) return jpdf::fail(st, "object table rejected the module object");
        holder->destroy = destroy;
        *out_handle = token;
        return JPDF_OK;
    });
}

jpdf_status jpdf_module_object_acquire(jpdf_handle handle, uint32_t kind, void** out_object,
                                       jpdf_handle* out_lease) noexcept {
    return nested([&] {
        if (!out_object || !out_lease) return jpdf::fail(JPDF_E_INVALID_ARGUMENT, "null output");
        std::shared_ptr<ModuleObject> object;
        jpdf_status st = resolve_module(handle, kind, &object);
        if (st != JPDF_OK) return st;
        void* const pointer = object->pointer;
        jpdf_handle lease = 0;
        st = jpdf::objects().insert(std::make_shared<jpdf::LeaseObject>(std::move(object)), &lease);
        if (st != JPDF_OK) return jpdf::fail(st, "object table rejected the lease");
        *out_object = pointer;
        *out_lease = lease;
        return JPDF_OK;
    });
}

jpdf_status jpdf_module_lease_release(jpdf_handle lease) noexcept {
    return nested([&] {
        jpdf_status st = jpdf::objects().retire(lease, jpdf::ObjectType::Lease, nullptr);
        return st == JPDF_OK ? st : jpdf::fail(st, "lease handle rejected");
    });
}

jpdf_status jpdf_module_object_close(jpdf_handle handle, uint32_t kind) noexcept {
    return nested([&] {
        std::shared_ptr<ModuleObject> object;
        jpdf_status st = resolve_module(handle, kind, &object);
        if (st != JPDF_OK) return st;
        st = jpdf::objects().retire(handle, jpdf::ObjectType::Module, nullptr);
        return st == JPDF_OK ? st : jpdf::fail(st, "module handle rejected");
    });
}

}  // extern "C"
