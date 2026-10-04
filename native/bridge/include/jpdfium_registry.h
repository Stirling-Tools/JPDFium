// jpdfium_registry.h - lock-free handle-validity table shared by the bridge
// and its tests.
//
// Design: open addressing with atomic slots and no lock on the read path,
// because validation is paid on every decode including the geometry hot paths.
// Measured at ~1.3 ns per probe-pair versus ~13 ns for a mutex + unordered_set,
// and ~0.6 ns for the original unchecked cast (bridge/tests/registry_cost.cpp).
// The remaining gap to unchecked is small enough that hardening is effectively
// free on leaf operations.
//
// Correctness properties that are load-bearing, not incidental:
//   - Deletion stores a tombstone rather than clearing the slot. Clearing would
//     let an in-flight reader see "absent" from a slot a concurrent insert had
//     just claimed, so probing must continue past tombstones.
//   - remove() erases from EVERY slot holding the pointer, not just the first.
//     add() skips a slot that already equals the pointer, so a duplicate
//     registration lands in a second slot; clearing only the first left a live
//     entry pointing at freed memory. That was a real use-after-free here,
//     found by the churn stress test and now permanently regression-tested.
//   - If the table fills, insertion falls back to a mutex-guarded overflow set
//     rather than dropping the handle. Dropping it would make a live object
//     fail validation and leak, which is worse than the slow path.
//
// Known limitation, unchanged: membership tests address identity, so a freed
// handle whose address is later reused by a new object would validate.
// Generation-tagged versioned handles are the fix, deferred to a future ABI
// change rather than smuggled in here. The default synchronous execution
// domain (see PdfiumRuntime) remains the end-game; an owner thread is an
// optional alternative, not a requirement for this fix.
//
// This header has no PDFium dependencies on purpose: the cost harness
// (bridge/tests/registry_cost.cpp) compiles it standalone, so the exactness
// checks run against production instead of a reimplementation that could
// drift from it.

#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <unordered_set>

class HandleRegistry {
   public:
    // 1024 slots holds far more live handles than any realistic document set;
    // the overflow path exists so the limit is never a correctness cliff.
    static constexpr size_t kSlots = 1024;

    static HandleRegistry& instance() {
        static HandleRegistry registry;
        return registry;
    }

    void add(const void* w) {
        if (!w || w == tombstone()) return;
        for (size_t i = 0; i < kSlots; i++) {
            size_t idx = (i + hash(w)) % kSlots;
            const void* cur = slots_[idx].load(std::memory_order_acquire);
            // CAS, not load-then-store: two concurrent adds could otherwise
            // claim the same slot and the second store would overwrite (leak)
            // the first handle. Costs almost nothing on this cold path.
            if ((cur == nullptr || cur == tombstone()) &&
                slots_[idx].compare_exchange_strong(cur, w, std::memory_order_acq_rel)) {
                count_.fetch_add(1, std::memory_order_relaxed);
                return;
            }
        }
        std::lock_guard<std::mutex> lock(overflow_mutex_);
        overflow_.insert(w);
    }

    void remove(const void* w) {
        if (!w || w == tombstone()) return;
        // Erase from every slot holding w. See the duplicate-registration note
        // above: stopping at the first hit is a use-after-free.
        for (size_t i = 0; i < kSlots; i++) {
            size_t idx = (i + hash(w)) % kSlots;
            const void* cur = slots_[idx].load(std::memory_order_acquire);
            if (cur == nullptr) break;
            if (cur == w) {
                slots_[idx].store(tombstone(), std::memory_order_release);
                count_.fetch_sub(1, std::memory_order_relaxed);
            }
        }
        std::lock_guard<std::mutex> lock(overflow_mutex_);
        overflow_.erase(w);
    }

    bool contains(const void* w) const {
        if (!w || w == tombstone()) return false;
        for (size_t i = 0; i < kSlots; i++) {
            size_t idx = (i + hash(w)) % kSlots;
            const void* cur = slots_[idx].load(std::memory_order_acquire);
            if (cur == nullptr) return false;
            if (cur == w) return true;
            // tombstone: keep probing, a later slot may still hold it.
        }
        std::lock_guard<std::mutex> lock(overflow_mutex_);
        return overflow_.find(w) != overflow_.end();
    }

    size_t size() const {
        std::lock_guard<std::mutex> lock(overflow_mutex_);
        return count_.load(std::memory_order_relaxed) + overflow_.size();
    }

   private:
    // Address 1 is in the unmapped first page and can never be a real
    // allocation, so it is a safe tombstone sentinel.
    static constexpr uintptr_t kTombstoneAddr = 1;
    static const void* tombstone() {
        return reinterpret_cast<const void*>(kTombstoneAddr);
    }
    // Mix the pointer so consecutive allocations do not cluster into the same
    // probe sequence. Cheap, and it runs once per probe.
    static size_t hash(const void* w) {
        uintptr_t x = reinterpret_cast<uintptr_t>(w) >> 4;
        x ^= x >> 13;
        x *= 0x9E3779B97F4A7C15ull;
        return static_cast<size_t>(x) % kSlots;
    }

    mutable std::atomic<const void*> slots_[kSlots]{};
    mutable std::atomic<size_t> count_{0};
    mutable std::mutex overflow_mutex_;
    std::unordered_set<const void*> overflow_;
};
