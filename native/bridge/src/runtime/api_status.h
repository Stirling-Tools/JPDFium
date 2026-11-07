// api_status.h - per-call error detail and the exception firewall for jpdf_* entry points.
#pragma once

#include <new>
#include <string_view>

#include "jpdfium_api.h"

namespace jpdf {

// Clears this thread's error detail; every exported call starts with it.
void begin_call() noexcept;

// Records detail for the first failure of the current call and returns status unchanged.
jpdf_status fail(jpdf_status status, std::string_view detail, std::string_view more = {}) noexcept;

// Copies the detail recorded on this thread.
jpdf_status copy_last_error(char* buffer, uint64_t capacity, uint64_t* required) noexcept;

// Runs an entry point body so no exception can cross the C ABI.
template <typename Body>
jpdf_status guarded(Body&& body) noexcept {
    begin_call();
    try {
        return body();
    } catch (const std::bad_alloc&) {
        return fail(JPDF_E_NO_MEMORY, "native allocation failed");
    } catch (...) {
        return fail(JPDF_E_INTERNAL, "unexpected native exception");
    }
}

// Validates a caller-sized input struct: known prefix present, unknown trailing bytes zero.
jpdf_status check_input_struct(const void* data, uint32_t caller_size, uint32_t min_size,
                               uint32_t known_size, std::string_view name) noexcept;

// Validates a caller-sized output struct: at least the 1.0 prefix.
jpdf_status check_output_struct(uint32_t caller_size, uint32_t min_size,
                                std::string_view name) noexcept;

}  // namespace jpdf
