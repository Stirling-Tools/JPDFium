// domain.h - the single native PDFium execution domain and its admission guard.
#pragma once

#include <atomic>
#include <chrono>
#include <cstdint>
#include <mutex>

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

struct DomainStats {
    uint64_t acquisitions;
    uint64_t wait_ns;
    uint64_t hold_ns;
    uint64_t live_documents;
    uint64_t live_pages;
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

    // Live PDFium resource accounting; only called while admitted.
    void document_opened() noexcept;
    void document_closed() noexcept;
    void page_opened() noexcept;
    void page_closed() noexcept;

   private:
    friend class Admission;
    Domain() = default;

    std::timed_mutex mutex_;
    std::atomic<State> state_{State::Uninitialized};
    std::atomic<int32_t> renderer_{-1};
    std::atomic<uint64_t> live_documents_{0};
    std::atomic<uint64_t> live_pages_{0};
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
    std::unique_lock<std::timed_mutex> lock_;
    std::chrono::steady_clock::time_point acquired_at_{};
    jpdf_status status_ = JPDF_E_INTERNAL;
};

// True while the calling thread holds the domain; used to reject public re-entry.
bool in_domain() noexcept;

}  // namespace jpdf
