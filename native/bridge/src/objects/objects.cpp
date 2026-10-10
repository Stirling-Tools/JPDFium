// objects.cpp - the process-wide object table.

#include <new>

#include "object_table.h"

namespace jpdf {

ObjectTable& objects() noexcept {
    // Never destroyed: bindings may release views from finalizers during interpreter teardown.
    alignas(ObjectTable) static unsigned char storage[sizeof(ObjectTable)];
    static ObjectTable* table = ::new (storage) ObjectTable();
    return *table;
}

}  // namespace jpdf
