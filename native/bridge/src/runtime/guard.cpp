// guard.cpp - refusal reporting for jpdf_guard_* shims.

#include "guard.h"

#include <new>
#include <string>
#include <system_error>

#include "api_status.h"
#include "domain.h"
#include "jpdfium_core.h"

namespace jpdf {

namespace {
// A refused shim returns a zero value its caller cannot tell from success, so it is kept here.
thread_local jpdf_status t_refusal = JPDF_OK;
}  // namespace

CallStart::CallStart() noexcept {
    begin_call();
}

GuardScope::GuardScope(Purpose purpose) noexcept : admission_(purpose) {
    // The refusal belongs to this call only: an admitted call clears any stale
    // status so a caller that never took the refusal cannot leak it to the next one.
    t_refusal = admission_.admitted() ? JPDF_OK : admission_.status();
}

void set_guard_refusal(jpdf_status status) noexcept {
    t_refusal = status;
}

}  // namespace jpdf

extern "C" {

jpdf_status jpdf_guard_take_refusal(void) noexcept {
    const jpdf_status status = jpdf::t_refusal;
    jpdf::t_refusal = JPDF_OK;
    return status;
}

}  // extern "C"
