// guard.h - admission for legacy jpdfium_* and raw PDFium entry points (jpdf_guard_* shims).
#pragma once

#include "domain.h"

namespace jpdf {

// Clears per-call error detail; a base so it runs before the admission member is built.
struct CallStart {
    CallStart() noexcept;
};

// Admits one legacy call; a refusal is kept for jpdf_guard_take_refusal so bindings can throw.
class GuardScope : private CallStart {
   public:
    explicit GuardScope(Purpose purpose) noexcept;
    GuardScope(const GuardScope&) = delete;
    GuardScope& operator=(const GuardScope&) = delete;

    bool admitted() const noexcept {
        return admission_.admitted();
    }

   private:
    Admission admission_;
};

void set_guard_refusal(jpdf_status status) noexcept;

}  // namespace jpdf
