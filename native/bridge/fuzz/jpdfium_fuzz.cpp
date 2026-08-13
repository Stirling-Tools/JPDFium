// jpdfium_fuzz - libFuzzer harness for the bridge's untrusted-input boundary.
//
// Fuzzes the same call sequence the JVM performs on arbitrary PDF bytes:
//   doc_open_bytes -> page_count -> page_open -> render -> text -> save_bytes
//
// Build (Clang only):
//   cmake -DJPDFIUM_BUILD_FUZZERS=ON -DJPDFIUM_SANITIZE=address
//   ./jpdfium_fuzz -max_total_time=120 corpus/
//
// The bridge's input validation (length caps, dimension caps, checked
// allocations) keeps the fuzz loop memory-bounded; ASan reports any violation.

#include <cstddef>
#include <cstdint>

#include "jpdfium.h"

namespace {

// Keep fuzz inputs bounded: huge PDFs make save/render too slow to be useful.
constexpr size_t kMaxInputSize = 8 * 1024 * 1024;

bool g_initialized = false;

void ensureInit() {
    if (!g_initialized) {
        jpdfium_init();
        g_initialized = true;
    }
}

}  // namespace

extern "C" int LLVMFuzzerTestOneInput(const uint8_t* data, size_t size) {
    if (size == 0 || size > kMaxInputSize) return 0;
    ensureInit();

    int64_t doc = 0;
    if (jpdfium_doc_open_bytes(data, static_cast<int64_t>(size), &doc) != JPDFIUM_OK) return 0;

    int32_t count = 0;
    if (jpdfium_doc_page_count(doc, &count) == JPDFIUM_OK && count > 0 && count <= 16) {
        int64_t page = 0;
        if (jpdfium_page_open(doc, 0, &page) == JPDFIUM_OK) {
            uint8_t* rgba = nullptr;
            int32_t w = 0, h = 0;
            if (jpdfium_render_page(page, 36, &rgba, &w, &h) == JPDFIUM_OK) {
                jpdfium_free_buffer(rgba);
            }
            char* json = nullptr;
            if (jpdfium_text_get_chars(page, &json) == JPDFIUM_OK) {
                jpdfium_free_string(json);
            }
            jpdfium_page_close(page);
        }
    }

    uint8_t* out = nullptr;
    int64_t outLen = 0;
    if (jpdfium_doc_save_bytes(doc, &out, &outLen) == JPDFIUM_OK) {
        jpdfium_free_buffer(out);
    }

    jpdfium_doc_close(doc);
    return 0;
}
