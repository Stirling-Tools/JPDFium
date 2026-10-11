// api_module.cpp - jpdf_module_*: optional modules' objects in the core's handle table.

#include <memory>
#include <new>

#include "api_status.h"
#include "jpdfium_api.h"
#include "objects/module_objects.h"

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
        auto holder = std::make_shared<ModuleObject>(kind, object, destroy);
        jpdf_handle token = 0;
        jpdf_status st = jpdf::objects().insert(holder, &token);
        if (st != JPDF_OK) {
            holder->destroy = nullptr;  // ownership stays with the caller on failure
            return jpdf::fail(st, "object table rejected the module object");
        }
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
