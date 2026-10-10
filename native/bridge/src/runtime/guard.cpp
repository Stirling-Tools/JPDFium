// guard.cpp - refusal reporting for jpdf_guard_* shims.

#include "guard.h"

#include "api_status.h"

namespace jpdf {

namespace {
// A refused shim returns a zero value its caller cannot tell from success, so it is kept here.
thread_local jpdf_status t_refusal = JPDF_OK;
}  // namespace

CallStart::CallStart() noexcept {
    begin_call();
}

GuardScope::GuardScope(Purpose purpose) noexcept : admission_(purpose) {
    if (!admission_.admitted()) t_refusal = admission_.status();
}

}  // namespace jpdf

extern "C" {

jpdf_status jpdf_guard_take_refusal(void) noexcept {
    const jpdf_status status = jpdf::t_refusal;
    jpdf::t_refusal = JPDF_OK;
    return status;
}

}  // extern "C"
