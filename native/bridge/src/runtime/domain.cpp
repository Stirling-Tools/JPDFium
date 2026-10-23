// domain.cpp - native PDFium execution domain: lifecycle, admission and accounting.

#include "domain.h"

#include <algorithm>
#include <new>
#include <string>
#include <system_error>
#include <thread>

#include "api_status.h"
#include "guard.h"
#include "jpdfium_core.h"

namespace jpdf {

namespace {

thread_local bool t_in_domain = false;

uint64_t elapsed_ns(std::chrono::steady_clock::time_point since) noexcept {
    auto d = std::chrono::steady_clock::now() - since;
    return static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::nanoseconds>(d).count());
}

uint64_t between_ns(std::chrono::steady_clock::time_point from,
                    std::chrono::steady_clock::time_point to) noexcept {
    auto d = to - from;
    return static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::nanoseconds>(d).count());
}

// Saturating so a mismatched close cannot wrap a counter and mask a real leak from shutdown.
void saturating_decrement(std::atomic<uint64_t>& count) noexcept {
    for (uint64_t current = count.load(std::memory_order_relaxed); current != 0;) {
        if (count.compare_exchange_weak(current, current - 1, std::memory_order_relaxed)) return;
    }
}

jpdf_status reject_for_state(State state, Purpose purpose) noexcept {
    switch (state) {
        case State::Running:
            return JPDF_OK;
        case State::Quiescing:
            if (purpose == Purpose::Teardown) return JPDF_OK;
            return fail(JPDF_E_QUIESCING, "PDFium runtime is quiescing; no new work is admitted");
        case State::Destroying:
            return fail(JPDF_E_QUIESCING, "PDFium runtime is being destroyed");
        case State::Uninitialized:
            return fail(JPDF_E_NOT_INITIALIZED, "call jpdf_runtime_init first");
        case State::Stopped:
            return fail(JPDF_E_STOPPED, "PDFium runtime was shut down; restart the process");
        case State::Failed:
            return fail(JPDF_E_FAILED, "PDFium teardown failed; restart the process");
    }
    return fail(JPDF_E_INTERNAL, "unknown runtime state");
}

}  // namespace

bool in_domain() noexcept {
    return t_in_domain;
}

Domain::Domain() noexcept {
    for (auto& count : live_) count.store(0, std::memory_order_relaxed);
}

Domain& Domain::instance() noexcept {
    // Never destroyed: worker threads may still be leaving the domain during static teardown.
    alignas(Domain) static unsigned char storage[sizeof(Domain)];
    static Domain* domain = ::new (storage) Domain();
    return *domain;
}

jpdf_status Domain::init(int32_t renderer) noexcept {
    if (t_in_domain) return fail(JPDF_E_REENTRANT, "runtime init called from inside the domain");
    if (renderer != JPDF_RENDERER_AUTO && renderer != JPDF_RENDERER_AGG &&
        renderer != JPDF_RENDERER_SKIA) {
        return fail(JPDF_E_INVALID_ARGUMENT, "unknown renderer");
    }
    // std::mutex::lock can throw std::system_error; this function is noexcept, so a throw
    // here would terminate before any ABI firewall could map it to a status.
    std::unique_lock<std::mutex> lock(mutex_, std::defer_lock);
    try {
        lock.lock();
    } catch (const std::system_error&) {
        return fail(JPDF_E_INTERNAL, "could not lock the PDFium domain");
    }
    switch (state()) {
        case State::Running:
            if (renderer == JPDF_RENDERER_AUTO || renderer == renderer_.load()) return JPDF_OK;
            return fail(JPDF_E_UNSUPPORTED, "runtime already runs with a different renderer");
        case State::Uninitialized:
            break;
        default:
            return reject_for_state(state(), Purpose::Ordinary);
    }
    if (!jpdfium_claim_authority(PdfiumAuthority::Safe)) {
        return fail(JPDF_E_AUTHORITY, "the legacy jpdfium_* bridge already owns PDFium here");
    }
    if (jpdfium_init_pdfium_core(renderer) != 0) {
        return fail(JPDF_E_UNSUPPORTED, "requested renderer is not in this build");
    }
    renderer_.store(jpdfium_core_uses_skia() ? JPDF_RENDERER_SKIA : JPDF_RENDERER_AGG);
    state_.store(State::Running, std::memory_order_release);
    return JPDF_OK;
}

jpdf_status Domain::quiesce() noexcept {
    State expected = State::Running;
    if (state_.compare_exchange_strong(expected, State::Quiescing)) return JPDF_OK;
    switch (expected) {
        case State::Uninitialized:
            return fail(JPDF_E_NOT_INITIALIZED, "runtime was never initialized");
        case State::Failed:
            return reject_for_state(expected, Purpose::Ordinary);
        default:
            return JPDF_OK;
    }
}

jpdf_status Domain::resume() noexcept {
    State expected = State::Quiescing;
    if (state_.compare_exchange_strong(expected, State::Running)) return JPDF_OK;
    if (expected == State::Running) return JPDF_OK;
    return reject_for_state(expected, Purpose::Ordinary);
}

jpdf_status Domain::shutdown(int64_t timeout_ms) noexcept {
    if (t_in_domain) return fail(JPDF_E_REENTRANT, "shutdown called from inside the domain");
    std::unique_lock<std::mutex> lock(mutex_, std::defer_lock);
    // A plain mutex with a try_lock backoff is cheaper than std::timed_mutex on MSVC, where
    // timed waits are built from a mutex and a condition variable. The bounded loop lets a JVM
    // exit hook give up instead of parking behind a long operation.
    const auto deadline =
        timeout_ms < 0 ? std::chrono::steady_clock::time_point::max()
                       : std::chrono::steady_clock::now() + std::chrono::milliseconds(timeout_ms);
    auto backoff = std::chrono::microseconds(50);
    for (;;) {
        if (mutex_.try_lock()) {
            lock = std::unique_lock<std::mutex>(mutex_, std::adopt_lock);
            break;
        }
        if (timeout_ms >= 0 && std::chrono::steady_clock::now() >= deadline) {
            return fail(JPDF_E_TIMEOUT, "PDFium domain stayed busy; shutdown skipped");
        }
        try {
            std::this_thread::sleep_for(backoff);
        } catch (...) {
            return fail(JPDF_E_INTERNAL, "could not wait for the PDFium domain");
        }
        backoff = std::min(backoff * 2, std::chrono::microseconds(2000));
    }
    switch (state()) {
        case State::Stopped:
            return JPDF_OK;
        case State::Failed:
            return reject_for_state(State::Failed, Purpose::Ordinary);
        case State::Uninitialized:
            state_.store(State::Stopped, std::memory_order_release);
            return JPDF_OK;
        case State::Running:
            state_.store(State::Quiescing, std::memory_order_release);
            break;
        default:
            break;
    }
    const uint64_t docs = live_documents_.load();
    const uint64_t pages = live_pages_.load();
    uint64_t others = 0;
    for (size_t i = static_cast<size_t>(Resource::TextPage);
         i < static_cast<size_t>(Resource::Count); ++i) {
        others += live_[i].load(std::memory_order_relaxed);
    }
    if (docs != 0 || pages != 0 || others != 0) {
        // Stays QUIESCING so the caller can close what is open and retry. Text pages, form
        // environments, searches, annotations and raw bitmaps can outlive a guarded call, and
        // PDFium must not be destroyed while any of them is still alive.
        try {
            return fail(JPDF_E_BUSY, "shutdown refused with " + std::to_string(docs) +
                                         " documents, " + std::to_string(pages) + " pages and " +
                                         std::to_string(others) + " other PDFium resources open");
        } catch (...) {
            return fail(JPDF_E_BUSY, "shutdown refused with open PDFium resources");
        }
    }
    // Only a still-quiescing domain may be destroyed: resume() is lock-free and can
    // succeed between the check above and here, and it has already told its caller JPDF_OK.
    State expected = State::Quiescing;
    if (!state_.compare_exchange_strong(expected, State::Destroying, std::memory_order_acq_rel)) {
        return fail(JPDF_E_BUSY, "domain was resumed while shutting down");
    }
    t_in_domain = true;
    try {
        jpdfium_destroy_pdfium_core();
    } catch (...) {
        t_in_domain = false;
        state_.store(State::Failed, std::memory_order_release);
        return fail(JPDF_E_FAILED, "PDFium destroy failed; outcome uncertain");
    }
    t_in_domain = false;
    state_.store(State::Stopped, std::memory_order_release);
    return JPDF_OK;
}

DomainStats Domain::stats() const noexcept {
    uint64_t others = 0;
    for (size_t i = static_cast<size_t>(Resource::TextPage);
         i < static_cast<size_t>(Resource::Count); ++i) {
        others += live_[i].load(std::memory_order_relaxed);
    }
    return DomainStats{acquisitions_.load(), wait_ns_.load(),
                       hold_ns_.load(),      live_documents_.load(),
                       live_pages_.load(),   live_documents_.load() + live_pages_.load() + others};
}

void Domain::document_opened(const void* document) noexcept {
    bool added = true;
    if (document) {
        try {
            std::lock_guard<std::mutex> lock(handle_mutex_);
            added = live_document_handles_.insert(document).second;
        } catch (...) {
            // The registry is an optimization against double counting; failing to record the
            // handle must not terminate a noexcept PDFium call.
            added = true;
        }
    }
    if (added) live_documents_.fetch_add(1);
}

void Domain::document_closed(const void* document) noexcept {
    bool removed = true;
    if (document) {
        std::lock_guard<std::mutex> lock(handle_mutex_);
        removed = live_document_handles_.erase(document) != 0;
    }
    if (removed) saturating_decrement(live_documents_);
}

void Domain::page_opened(const void* page) noexcept {
    bool added = true;
    if (page) {
        try {
            std::lock_guard<std::mutex> lock(handle_mutex_);
            added = live_page_handles_.insert(page).second;
        } catch (...) {
            added = true;
        }
    }
    if (added) live_pages_.fetch_add(1);
}

void Domain::page_closed(const void* page) noexcept {
    bool removed = true;
    if (page) {
        std::lock_guard<std::mutex> lock(handle_mutex_);
        removed = live_page_handles_.erase(page) != 0;
    }
    if (removed) saturating_decrement(live_pages_);
}

void Domain::resource_opened(Resource resource, const void* handle) noexcept {
    switch (resource) {
        case Resource::Document:
            document_opened(handle);
            return;
        case Resource::Page:
            page_opened(handle);
            return;
        case Resource::Count:
            return;
        default:
            live_[static_cast<size_t>(resource)].fetch_add(1, std::memory_order_relaxed);
            return;
    }
}

void Domain::resource_closed(Resource resource, const void* handle) noexcept {
    switch (resource) {
        case Resource::Document:
            document_closed(handle);
            return;
        case Resource::Page:
            page_closed(handle);
            return;
        case Resource::Count:
            return;
        default:
            saturating_decrement(live_[static_cast<size_t>(resource)]);
            return;
    }
}

Admission::Admission(Purpose purpose) noexcept {
    // Internal helpers run under the caller's admission; a public re-entry would self-deadlock.
    if (t_in_domain) {
        status_ = fail(JPDF_E_REENTRANT, "PDFium domain re-entered from inside an operation");
        return;
    }
    Domain& domain = Domain::instance();
    if (domain.mutex_.try_lock()) {
        // Uncontended, the common case: nothing was waited for, so no wait telemetry needs a
        // clock read and the acquisition is a plain try_lock rather than a blocking lock.
        lock_ = std::unique_lock<std::mutex>(domain.mutex_, std::adopt_lock);
        acquired_at_ = std::chrono::steady_clock::now();
    } else {
        const auto requested = std::chrono::steady_clock::now();
        auto backoff = std::chrono::microseconds(50);
        while (!domain.mutex_.try_lock()) {
            // Backoff keeps a queue of tiny calls from spinning while a render holds the domain.
            try {
                std::this_thread::sleep_for(backoff);
            } catch (...) {
                status_ = fail(JPDF_E_INTERNAL, "could not wait for the PDFium domain");
                return;
            }
            backoff = std::min(backoff * 2, std::chrono::microseconds(2000));
        }
        lock_ = std::unique_lock<std::mutex>(domain.mutex_, std::adopt_lock);
        // The wait ends exactly where the hold begins, so reuse the acquisition timestamp
        // instead of taking a redundant second clock read.
        acquired_at_ = std::chrono::steady_clock::now();
        domain.wait_ns_.fetch_add(between_ns(requested, acquired_at_), std::memory_order_relaxed);
    }
    status_ = reject_for_state(domain.state(), purpose);
    if (status_ != JPDF_OK) {
        lock_.unlock();
        return;
    }
    domain.acquisitions_.fetch_add(1, std::memory_order_relaxed);
    t_in_domain = true;
}

Admission::~Admission() {
    if (!admitted()) return;
    t_in_domain = false;
    Domain::instance().hold_ns_.fetch_add(elapsed_ns(acquired_at_), std::memory_order_relaxed);
}

}  // namespace jpdf
