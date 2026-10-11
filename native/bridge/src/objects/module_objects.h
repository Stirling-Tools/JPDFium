// module_objects.h - objects owned by optional native modules, kept in the core's handle table.
#pragma once

#include <cstdint>
#include <memory>
#include <utility>

#include "object_table.h"

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

}  // namespace jpdf
