// domain.cpp - native PDFium execution domain: lifecycle, admission and accounting.

#include "domain.h"

#include <new>
#include <string>
#include <system_error>

#include "api_status.h"
#include "jpdfium_core.h"

namespace jpdf {

namespace {

thread_local bool t_in_domain = false;

uint64_t elapsed_ns(std::chrono::steady_clock::time_point since) noexcept {
    auto d = std::chrono::steady_clock::now() - since;
    return static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::nanoseconds>(d).count());
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
    std::lock_guard<std::timed_mutex> lock(mutex_);
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
    std::unique_lock<std::timed_mutex> lock(mutex_, std::defer_lock);
    if (timeout_ms < 0) {
        lock.lock();
    } else if (!lock.try_lock_for(std::chrono::milliseconds(timeout_ms))) {
        // Bounded so a JVM exit hook never parks behind a long-running operation.
        return fail(JPDF_E_TIMEOUT, "PDFium domain stayed busy; shutdown skipped");
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
    if (docs != 0 || pages != 0) {
        // Stays QUIESCING so the caller can close what is open and retry.
        try {
            return fail(JPDF_E_BUSY, "shutdown refused with " + std::to_string(docs) +
                                         " documents and " + std::to_string(pages) + " pages open");
        } catch (...) {
            return fail(JPDF_E_BUSY, "shutdown refused with documents or pages open");
        }
    }
    state_.store(State::Destroying, std::memory_order_release);
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
    return DomainStats{acquisitions_.load(), wait_ns_.load(), hold_ns_.load(),
                       live_documents_.load(), live_pages_.load()};
}

void Domain::document_opened() noexcept {
    live_documents_.fetch_add(1);
}

void Domain::document_closed() noexcept {
    live_documents_.fetch_sub(1);
}

void Domain::page_opened() noexcept {
    live_pages_.fetch_add(1);
}

void Domain::page_closed() noexcept {
    live_pages_.fetch_sub(1);
}

Admission::Admission(Purpose purpose) noexcept {
    // Internal helpers run under the caller's admission; a public re-entry would self-deadlock.
    if (t_in_domain) {
        status_ = fail(JPDF_E_REENTRANT, "PDFium domain re-entered from inside an operation");
        return;
    }
    Domain& domain = Domain::instance();
    const auto requested = std::chrono::steady_clock::now();
    try {
        lock_ = std::unique_lock<std::timed_mutex>(domain.mutex_);
    } catch (const std::system_error&) {
        status_ = fail(JPDF_E_INTERNAL, "could not lock the PDFium domain");
        return;
    }
    acquired_at_ = std::chrono::steady_clock::now();
    domain.wait_ns_.fetch_add(elapsed_ns(requested), std::memory_order_relaxed);
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
