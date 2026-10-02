// Prices the validation cost added by the handle registries, and stress-tests
// the lock-free table's exactness.
//
// The registries replaced a bare pointer cast (which crashed on a fabricated
// handle) with a membership test paid on EVERY decode, including the geometry
// and page-count hot paths. This measures that cost against the two candidate
// designs so the correctness trade-off is a number rather than a guess:
//
//   A  unchecked cast          - the pre-registry behavior, unsafe
//   B  mutex + unordered_set   - the first registry implementation
//   C  lock-free open-addressed - what the production table now does
//
// The stress block churns 4000 objects through 1024 slots (well past the table
// size, exercising the overflow fallback and tombstone reuse) and asserts that
// membership is exact: a registered handle always validates, and a
// never-registered or already-removed address never does.

#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <mutex>
#include <string>
#include <unordered_set>
#include <vector>

namespace {

int g_failures = 0;

constexpr int kLiveHandles = 64;  // realistic: a handful of open documents
constexpr int kStaleProbes = 1;   // include a rejected lookup per iteration
constexpr int kIters = 2'000'000;

// A: the pre-registry decode. Rejects nothing; dereferences garbage.
inline void* decodeUnchecked(int64_t h) {
    return reinterpret_cast<void*>(static_cast<uintptr_t>(h));
}

// B: mutex + unordered_set. Correct, but pays a lock on every read.
class MutexRegistry {
   public:
    void add(const void* w) {
        std::lock_guard<std::mutex> lock(mutex_);
        live_.insert(w);
    }
    bool contains(const void* w) const {
        std::lock_guard<std::mutex> lock(mutex_);
        return live_.find(w) != live_.end();
    }

   private:
    mutable std::mutex mutex_;
    std::unordered_set<const void*> live_;
};

// C: the production algorithm - open addressing, tombstones, atomics only on
// the read path, mutex-guarded overflow set when the table fills.
class LockFreeRegistry {
   public:
    static constexpr size_t kSlots = 1024;

    void add(const void* w) {
        if (!w || w == tombstone()) return;
        for (size_t i = 0; i < kSlots; i++) {
            size_t idx = (i + hash(w)) % kSlots;
            const void* cur = slots_[idx].load(std::memory_order_acquire);
            if (cur == nullptr || cur == tombstone()) {
                slots_[idx].store(w, std::memory_order_release);
                count_.fetch_add(1, std::memory_order_relaxed);
                return;
            }
        }
        std::lock_guard<std::mutex> lock(overflow_mutex_);
        overflow_.insert(w);
    }

    // Erase from every slot holding w: add() skips a slot that already equals
    // the pointer, so a duplicate registration lands in a second slot and
    // clearing only the first left a live entry pointing at freed memory.
    void remove(const void* w) {
        if (!w || w == tombstone()) return;
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
        }
        std::lock_guard<std::mutex> lock(overflow_mutex_);
        return overflow_.find(w) != overflow_.end();
    }

    size_t size() const {
        std::lock_guard<std::mutex> lock(overflow_mutex_);
        return count_.load(std::memory_order_relaxed) + overflow_.size();
    }

   private:
    static constexpr uintptr_t kTombstoneAddr = 1;
    static const void* tombstone() {
        return reinterpret_cast<const void*>(kTombstoneAddr);
    }
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

template <typename F>
double time_ns(F&& f) {
    auto t0 = std::chrono::steady_clock::now();
    f();
    auto t1 = std::chrono::steady_clock::now();
    return std::chrono::duration<double, std::nano>(t1 - t0).count() / kIters;
}

void check(bool ok, const char* msg) {
    if (ok) {
        std::printf("ok   %s\n", msg);
    } else {
        std::printf("FAIL %s\n", msg);
        ++g_failures;
    }
}

}  // namespace

int main() {
    std::printf("=== handle registry validation cost ===\n");
    std::printf("live handles=%d, %d probes/iteration, %d iterations\n\n", kLiveHandles,
                kLiveHandles + kStaleProbes, kIters);

    std::vector<int64_t> handles;
    handles.reserve(kLiveHandles);
    for (int i = 0; i < kLiveHandles; i++) {
        auto* obj = new std::vector<uint8_t>(64);
        handles.push_back(static_cast<int64_t>(reinterpret_cast<uintptr_t>(obj)));
    }
    // The fabricated address from the original crash reproduction.
    const int64_t stale = 0x3039;

    MutexRegistry mutex_reg;
    LockFreeRegistry fast_reg;
    for (int64_t h : handles) {
        const void* p = reinterpret_cast<const void*>(static_cast<uintptr_t>(h));
        mutex_reg.add(p);
        fast_reg.add(p);
    }

    std::size_t sink = 0;
    // Warm both paths so the first measured iteration is not paying for
    // compilation of the probe loop itself.
    for (int i = 0; i < 100'000; i++) {
        int64_t h = handles[i % handles.size()];
        sink += decodeUnchecked(h) != nullptr;
        sink +=
            mutex_reg.contains(reinterpret_cast<const void*>(static_cast<uintptr_t>(h))) ? 1 : 0;
        sink += fast_reg.contains(reinterpret_cast<const void*>(static_cast<uintptr_t>(h))) ? 1 : 0;
    }

    double a = time_ns([&] {
        for (int i = 0; i < kIters; i++) {
            sink += decodeUnchecked(handles[i % handles.size()]) != nullptr;
            sink += decodeUnchecked(stale) != nullptr;
        }
    });
    double b = time_ns([&] {
        for (int i = 0; i < kIters; i++) {
            auto h = handles[i % handles.size()];
            sink += mutex_reg.contains(reinterpret_cast<const void*>(static_cast<uintptr_t>(h)))
                        ? 1
                        : 0;
            sink += mutex_reg.contains(reinterpret_cast<const void*>(static_cast<uintptr_t>(stale)))
                        ? 1
                        : 0;
        }
    });
    double c = time_ns([&] {
        for (int i = 0; i < kIters; i++) {
            auto h = handles[i % handles.size()];
            sink +=
                fast_reg.contains(reinterpret_cast<const void*>(static_cast<uintptr_t>(h))) ? 1 : 0;
            sink += fast_reg.contains(reinterpret_cast<const void*>(static_cast<uintptr_t>(stale)))
                        ? 1
                        : 0;
        }
    });

    std::printf("A unchecked cast  (pre-registry, unsafe)  : %6.2f ns/probe-pair\n", a);
    std::printf("B mutex + hash set (rejected alternative)  : %6.2f ns/probe-pair\n", b);
    std::printf("C production registry (lock-free table)    : %6.2f ns/probe-pair\n", c);
    std::printf("\n  C vs B: %.1fx cheaper;  C vs A: +%.2f ns per probe\n", b / c, (c - a) / 2.0);
    (void)b;

    // Exactness under churn, well past the table size.
    constexpr int kObjects = 4000;
    LockFreeRegistry reg;
    std::vector<void*> objs(kObjects);
    int false_rejects = 0, false_accepts = 0, use_after_remove = 0;
    for (int round = 0; round < 3; round++) {
        for (int i = 0; i < kObjects; i++) {
            objs[i] = new int64_t(round);
            reg.add(objs[i]);
        }
        for (int i = 0; i < kObjects; i++) {
            if (!reg.contains(objs[i])) false_rejects++;
        }
        for (int i = 0; i < 64; i++) {
            if (reg.contains(reinterpret_cast<const void*>(static_cast<uintptr_t>(0x3039 + i))))
                false_accepts++;
        }
        for (int i = 0; i < kObjects; i++) {
            reg.remove(objs[i]);
            delete reinterpret_cast<int64_t*>(objs[i]);
        }
        for (int i = 0; i < kObjects; i++) {
            if (reg.contains(objs[i])) use_after_remove++;
        }
    }

    std::printf(
        "\nE stress: false_rejects=%d false_accepts=%d use_after_remove=%d "
        "final_size=%zu (expected all zero, size 0)\n",
        false_rejects, false_accepts, use_after_remove, reg.size());
    check(false_rejects == 0, "every registered handle validates");
    check(false_accepts == 0, "no never-registered address validates");
    check(use_after_remove == 0, "no removed handle still validates");
    check(reg.size() == 0, "registry drains to empty after churn");

    // Regression from the lock-free table's defect: duplicate registration must
    // not survive a single remove. Kept permanently - the set-based design makes
    // it structurally impossible, and the test proves the invariant holds.
    {
        LockFreeRegistry dup;
        auto* obj = new int64_t(1);
        dup.add(obj);
        dup.add(obj);
        dup.remove(obj);
        check(!dup.contains(obj), "duplicate registration does not survive one remove");
        delete reinterpret_cast<int64_t*>(obj);
    }

    // Saturation: tombstone sentinel never validates, full table overflows,
    // remove-then-reuse keeps probing intact.
    {
        LockFreeRegistry sat;
        check(!sat.contains(reinterpret_cast<const void*>(1)),
              "tombstone address never validates on empty table");
        constexpr size_t kSlots = 1024;
        std::vector<void*> fill(kSlots);
        for (size_t i = 0; i < kSlots; i++) {
            fill[i] = new int64_t((int64_t)i);
            sat.add(fill[i]);
        }
        auto* extra = new int64_t(-1);
        sat.add(extra);  // table full -> overflow path, must still validate
        check(sat.contains(extra), "overflow entry validates past full table");
        check(!sat.contains(reinterpret_cast<const void*>(1)),
              "tombstone still rejected with full table + tombstones absent");
        for (size_t i = 0; i < kSlots; i += 2) {
            sat.remove(fill[i]);
            delete reinterpret_cast<int64_t*>(fill[i]);
            fill[i] = nullptr;
        }
        check(!sat.contains(reinterpret_cast<const void*>(1)),
              "tombstone still rejected after removals create tombstones");
        int live = 0;
        for (size_t i = 1; i < kSlots; i += 2) {
            if (sat.contains(fill[i])) live++;
        }
        check(live == 512, "removal through chains keeps survivors visible");
        for (size_t i = 0; i < kSlots; i += 2) {
            auto* o = new int64_t((int64_t)i);
            sat.add(o);  // reuse tombstone slots
            fill[i] = o;
        }
        int relive = 0;
        for (size_t i = 0; i < kSlots; i++) {
            if (sat.contains(fill[i])) relive++;
        }
        check(relive == 1024, "tombstone reuse restores full visibility");
        sat.remove(extra);
        delete reinterpret_cast<int64_t*>(extra);
        for (auto* o : fill) {
            sat.remove(o);
            delete reinterpret_cast<int64_t*>(o);
        }
        check(sat.size() == 0, "saturated registry drains to empty");
    }

    std::printf("sink=%zu\n", sink);
    std::printf("=== failures: %d ===\n", g_failures);
    return g_failures == 0 ? 0 : 1;
}
