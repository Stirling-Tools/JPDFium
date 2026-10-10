// api_status.cpp - thread-local error detail and struct-size validation.

#include "api_status.h"

#include <cstring>
#include <string>

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
