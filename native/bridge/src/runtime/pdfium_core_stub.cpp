// pdfium_core_stub.cpp - jpdfium_core.h for the stub bridge, which has no PDFium to start.

#include <atomic>

#include "jpdfium_core.h"

namespace {
std::atomic<int> g_authority{static_cast<int>(PdfiumAuthority::None)};
}  // namespace

bool jpdfium_claim_authority(PdfiumAuthority who) noexcept {
    int expected = static_cast<int>(PdfiumAuthority::None);
    if (g_authority.compare_exchange_strong(expected, static_cast<int>(who))) return true;
    return expected == static_cast<int>(who);
}

int jpdfium_init_pdfium_core(int renderer) {
    // Matches the real build: Skia (1) is refused because the stub has no Skia.
    return renderer == 1 ? -1 : 0;
}

void jpdfium_destroy_pdfium_core() noexcept {}

bool jpdfium_core_uses_skia() noexcept {
    return false;
}
