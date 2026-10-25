// domain.h - the single native PDFium execution domain and its admission guard.
#pragma once

#include <array>
#include <atomic>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <unordered_set>

#include "jpdfium_api.h"

namespace jpdf {

enum class State : uint8_t {
    Uninitialized = JPDF_STATE_UNINITIALIZED,
    Running = JPDF_STATE_RUNNING,
    Quiescing = JPDF_STATE_QUIESCING,
    Destroying = JPDF_STATE_DESTROYING,
    Stopped = JPDF_STATE_STOPPED,
    Failed = JPDF_STATE_FAILED,
};

// Ordinary work needs RUNNING; teardown of existing resources is also allowed while QUIESCING.
enum class Purpose : uint8_t { Ordinary, Teardown };

// Handles that can outlive one guarded call and therefore must block PDFium destruction.
// Documents and pages are opened by both the bridge and the generated raw shims; the rest are
// only reachable through the raw surface today, where the generated close must balance them.
enum class Resource : uint8_t {
    Document,
    Page,
    TextPage,
    FormEnvironment,
    Search,
    Annotation,
    Bitmap,
    Count
};

struct DomainStats {
    uint64_t acquisitions;
    uint64_t wait_ns;
    uint64_t hold_ns;
    uint64_t live_documents;
    uint64_t live_pages;
    uint64_t live_resources;
};

// Process-wide owner of PDFium serialization, lifecycle and live-resource accounting.
class Domain {
   public:
    static Domain& instance() noexcept;

    State state() const noexcept {
        return state_.load(std::memory_order_acquire);
    }
    int32_t renderer() const noexcept {
        return renderer_.load(std::memory_order_acquire);
    }

    jpdf_status init(int32_t renderer) noexcept;
    jpdf_status quiesce() noexcept;
    // QUIESCING back to RUNNING, for callers that abandon a refused shutdown.
    jpdf_status resume() noexcept;
    // timeout_ms < 0 waits for the domain indefinitely.
    jpdf_status shutdown(int64_t timeout_ms = -1) noexcept;
    DomainStats stats() const noexcept;

    // Live PDFium resource accounting; only called while admitted. Document and page handles are
    // registered by identity so a close through the raw guard and the bridge for the same handle
    // cannot decrement twice.
    void document_opened(const void* document) noexcept;
    void document_closed(const void* document) noexcept;
    void page_opened(const void* page) noexcept;
    void page_closed(const void* page) noexcept;
    void resource_opened(Resource resource, const void* handle) noexcept;
    void resource_closed(Resource resource, const void* handle) noexcept;

   private:
    friend class Admission;
    Domain() noexcept;

    std::mutex mutex_;
    std::mutex handle_mutex_;
    std::unordered_set<const void*> live_document_handles_;
    std::unordered_set<const void*> live_page_handles_;
    std::atomic<State> state_{State::Uninitialized};
    std::atomic<int32_t> renderer_{-1};
    std::atomic<uint64_t> live_documents_{0};
    std::atomic<uint64_t> live_pages_{0};
    std::array<std::atomic<uint64_t>, static_cast<size_t>(Resource::Count)> live_{};
    std::atomic<uint64_t> acquisitions_{0};
    std::atomic<uint64_t> wait_ns_{0};
    std::atomic<uint64_t> hold_ns_{0};
};

// RAII admission: failed admission holds nothing, successful admission releases exactly once.
class Admission {
   public:
    explicit Admission(Purpose purpose) noexcept;
    ~Admission();
    Admission(const Admission&) = delete;
    Admission& operator=(const Admission&) = delete;

    jpdf_status status() const noexcept {
        return status_;
    }
    bool admitted() const noexcept {
        return status_ == JPDF_OK;
    }

   private:
    std::unique_lock<std::mutex> lock_;
    std::chrono::steady_clock::time_point acquired_at_{};
    jpdf_status status_ = JPDF_E_INTERNAL;
};

// True while the calling thread holds the domain; used to reject public re-entry.
bool in_domain() noexcept;

}  // namespace jpdf
