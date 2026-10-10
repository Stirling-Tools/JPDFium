// api_object_table.cpp - generation-checked handle table, tested without PDFium.

#include <cstdio>
#include <memory>
#include <thread>
#include <vector>

#include "objects/object_table.h"

static int g_failures = 0;

#define CHECK(cond, msg)                                       \
    do {                                                       \
        if (!(cond)) {                                         \
            std::printf("FAIL %s (line %d)\n", msg, __LINE__); \
            ++g_failures;                                      \
        }                                                      \
    } while (0)

using jpdf::Object;
using jpdf::ObjectType;

namespace {

struct Doc final : Object {
    Doc() : Object(ObjectType::Document) {}
};
struct Page final : Object {
    Page() : Object(ObjectType::Page) {}
};

void test_validation_order() {
    jpdf::BasicObjectTable<24> table;
    jpdf_handle doc = 0;
    CHECK(table.insert(std::make_shared<Doc>(), &doc) == JPDF_OK, "insert");
    CHECK(doc != 0, "token is non-zero");
    CHECK(table.resolve(doc, ObjectType::Document, nullptr) == JPDF_OK, "resolve live");
    CHECK(table.resolve(0, ObjectType::Document, nullptr) == JPDF_E_INVALID_HANDLE, "zero");
    CHECK(table.resolve(doc, ObjectType::Page, nullptr) == JPDF_E_WRONG_HANDLE_TYPE,
          "type mismatch");
    CHECK(table.resolve(0xFF00000100000000ull, ObjectType::Document, nullptr) ==
              JPDF_E_INVALID_HANDLE,
          "unknown type byte");
    CHECK(table.resolve(doc + 7, ObjectType::Document, nullptr) == JPDF_E_INVALID_HANDLE,
          "never-allocated slot");
    CHECK(table.resolve(doc + (1ull << 32), ObjectType::Document, nullptr) == JPDF_E_INVALID_HANDLE,
          "future generation is not stale, it was never issued");
    CHECK(table.retire(doc, ObjectType::Document, nullptr) == JPDF_OK, "retire");
    CHECK(table.resolve(doc, ObjectType::Document, nullptr) == JPDF_E_STALE_HANDLE, "stale");
    CHECK(table.retire(doc, ObjectType::Document, nullptr) == JPDF_E_STALE_HANDLE,
          "double retire is stale, not a double free");
}

void test_slot_reuse_keeps_old_tokens_stale() {
    jpdf::BasicObjectTable<24> table;
    jpdf_handle first = 0;
    table.insert(std::make_shared<Doc>(), &first);
    table.retire(first, ObjectType::Document, nullptr);
    jpdf_handle second = 0;
    table.insert(std::make_shared<Doc>(), &second);
    CHECK(decltype(table)::slot_of(first) == decltype(table)::slot_of(second), "slot reused");
    CHECK(first != second, "reuse changes the token");
    CHECK(table.resolve(first, ObjectType::Document, nullptr) == JPDF_E_STALE_HANDLE,
          "old token stays stale after reuse");
    CHECK(table.resolve(second, ObjectType::Document, nullptr) == JPDF_OK, "new token valid");
    jpdf_handle page = 0;
    table.retire(second, ObjectType::Document, nullptr);
    table.insert(std::make_shared<Page>(), &page);
    CHECK(table.resolve(second, ObjectType::Document, nullptr) == JPDF_E_STALE_HANDLE,
          "slot reused by another type keeps the old token stale");
}

void test_generation_exhaustion_retires_slot() {
    // Two generation bits: the slot is issued at generations 1, 2 and 3, then never again.
    jpdf::BasicObjectTable<2> table;
    std::vector<jpdf_handle> issued;
    for (int i = 0; i < 3; ++i) {
        jpdf_handle h = 0;
        table.insert(std::make_shared<Doc>(), &h);
        issued.push_back(h);
        table.retire(h, ObjectType::Document, nullptr);
    }
    CHECK(table.retired_slots() == 1, "exhausted slot is retired");
    jpdf_handle fresh = 0;
    table.insert(std::make_shared<Doc>(), &fresh);
    CHECK(decltype(table)::slot_of(fresh) != decltype(table)::slot_of(issued[0]),
          "retired slot is never reissued");
    for (jpdf_handle h : issued) {
        CHECK(table.resolve(h, ObjectType::Document, nullptr) == JPDF_E_STALE_HANDLE,
              "every exhausted-slot token stays stale");
    }
}

void test_retired_object_outlives_table_entry() {
    jpdf::BasicObjectTable<24> table;
    jpdf_handle h = 0;
    table.insert(std::make_shared<Doc>(), &h);
    std::shared_ptr<Object> lease;
    CHECK(table.resolve(h, ObjectType::Document, &lease) == JPDF_OK, "lease");
    table.retire(h, ObjectType::Document, nullptr);
    CHECK(lease.use_count() == 1, "the lease is the last owner after retire");
    CHECK(table.live(ObjectType::Document) == 0, "live count drops at retire");
}

void test_concurrent_churn() {
    jpdf::BasicObjectTable<24> table;
    constexpr int kThreads = 8;
    constexpr int kRounds = 20000;
    std::vector<std::thread> threads;
    threads.reserve(kThreads);
    std::vector<int> errors(kThreads, 0);
    for (int t = 0; t < kThreads; ++t) {
        threads.emplace_back([&, t] {
            for (int i = 0; i < kRounds; ++i) {
                jpdf_handle h = 0;
                if (table.insert(std::make_shared<Doc>(), &h) != JPDF_OK) errors[t]++;
                if (table.resolve(h, ObjectType::Document, nullptr) != JPDF_OK) errors[t]++;
                if (table.retire(h, ObjectType::Document, nullptr) != JPDF_OK) errors[t]++;
                if (table.resolve(h, ObjectType::Document, nullptr) != JPDF_E_STALE_HANDLE) {
                    errors[t]++;
                }
            }
        });
    }
    for (auto& th : threads) th.join();
    int total = 0;
    for (int e : errors) total += e;
    CHECK(total == 0, "concurrent insert/resolve/retire never confuses tokens");
    CHECK(table.live(ObjectType::Document) == 0, "all churned objects retired");
}

}  // namespace

int main() {
    std::printf("=== jpdf object table ===\n");
    test_validation_order();
    test_slot_reuse_keeps_old_tokens_stale();
    test_generation_exhaustion_retires_slot();
    test_retired_object_outlives_table_entry();
    test_concurrent_churn();
    std::printf("=== failures: %d ===\n", g_failures);
    return g_failures == 0 ? 0 : 1;
}
