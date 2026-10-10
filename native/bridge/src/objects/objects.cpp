// objects.cpp - the process-wide object table.

#include "object_table.h"

namespace jpdf {

ObjectTable& objects() noexcept {
    // Never destroyed: bindings may release views from finalizers during interpreter teardown.
    static ObjectTable* table = new ObjectTable();
    return *table;
}

}  // namespace jpdf
