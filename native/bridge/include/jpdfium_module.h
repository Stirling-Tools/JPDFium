// jpdfium_module.h - for optional native modules (OCR) that keep their objects in the core's
// generation-checked handle table and report errors through jpdf_last_error_message.
// Bindings never call these. See docs/design/OCR.md.
#pragma once
#include <stdint.h>

#include "jpdfium_api.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef void (*jpdf_module_destroy_fn)(void* object);

// Starts a module entry point: clears this thread's error detail, as every jpdf_* call does.
// The other jpdf_module_* functions keep it, so a failure already recorded survives cleanup.
JPDF_API void jpdf_module_begin_call(void) JPDF_NOEXCEPT;
// Records detail for the current call's first failure; returns status unchanged.
JPDF_API jpdf_status jpdf_module_fail(jpdf_status status, const char* detail) JPDF_NOEXCEPT;

// Takes ownership on success: destroy runs once the handle is closed and no lease remains.
// On failure the caller still owns object. kind must be non-zero.
JPDF_API jpdf_status jpdf_module_object_insert(uint32_t kind, void* object,
                                               jpdf_module_destroy_fn destroy,
                                               jpdf_handle* out_handle) JPDF_NOEXCEPT;
// Pins the object for one call; a handle of another kind is JPDF_E_WRONG_HANDLE_TYPE.
JPDF_API jpdf_status jpdf_module_object_acquire(jpdf_handle handle, uint32_t kind,
                                                void** out_object,
                                                jpdf_handle* out_lease) JPDF_NOEXCEPT;
JPDF_API jpdf_status jpdf_module_lease_release(jpdf_handle lease) JPDF_NOEXCEPT;
// Retires the handle; leases already taken keep the object alive until released.
JPDF_API jpdf_status jpdf_module_object_close(jpdf_handle handle, uint32_t kind) JPDF_NOEXCEPT;

#ifdef __cplusplus
}
#endif
