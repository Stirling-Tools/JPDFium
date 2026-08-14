// jpdfium_fuzz - libFuzzer harness for the bridge's untrusted-input boundary.
//
// Fuzzes the call sequences the JVM performs on arbitrary PDF bytes:
//   doc_open_bytes -> page_count -> page_open -> render -> text -> redact
//   -> flatten -> save_bytes (incremental + full) plus the standalone repair
//   pipeline over the raw input. The bridge's input validation (length caps,
//   dimension caps, checked allocations) keeps the fuzz loop memory-bounded;
//   ASan/UBSan report any violation and libFuzzer fails the run.
//
// Build (Clang only - Apple's clang lacks the libFuzzer runtime, use apt clang):
//   cmake -DCMAKE_CXX_COMPILER=clang++ -DJPDFIUM_BUILD_FUZZERS=ON \
//         -DJPDFIUM_SANITIZE=address,undefined
//   ./jpdfium_fuzz -max_total_time=120 corpus/
//
// Any crash aborts with a non-zero exit, so the CI job fails.

#include <cstddef>
#include <cstdint>
#include <cstring>

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

void freeJson(char* json) {
    if (json) jpdfium_free_string(json);
}

void freeBuffer(uint8_t* buf) {
    if (buf) jpdfium_free_buffer(buf);
}

}  // namespace

extern "C" int LLVMFuzzerTestOneInput(const uint8_t* data, size_t size) {
    if (size == 0 || size > kMaxInputSize) return 0;
    ensureInit();

    // Standalone repair pipeline over the raw bytes (qpdf + Rust/lopdf paths).
    {
        uint8_t* repaired = nullptr;
        int64_t repairedLen = 0;
        if (jpdfium_repair_pdf(data, static_cast<int64_t>(size), &repaired, &repairedLen,
                               JPDFIUM_REPAIR_NORMALIZE_XREF | JPDFIUM_REPAIR_FIX_STARTXREF) ==
            JPDFIUM_OK) {
            freeBuffer(repaired);
        }
    }

    int64_t doc = 0;
    if (jpdfium_doc_open_bytes(data, static_cast<int64_t>(size), &doc) != JPDFIUM_OK) return 0;

    int32_t count = 0;
    if (jpdfium_doc_page_count(doc, &count) == JPDFIUM_OK && count > 0 && count <= 16) {
        int64_t page = 0;
        if (jpdfium_page_open(doc, 0, &page) == JPDFIUM_OK) {
            uint8_t* rgba = nullptr;
            int32_t w = 0, h = 0;
            if (jpdfium_render_page(page, 36, &rgba, &w, &h) == JPDFIUM_OK) {
                freeBuffer(rgba);
            }

            char* json = nullptr;
            if (jpdfium_text_get_chars(page, &json) == JPDFIUM_OK) freeJson(json);
            if (jpdfium_text_get_char_positions(page, &json) == JPDFIUM_OK) freeJson(json);
            if (jpdfium_text_find(page, "a", &json) == JPDFIUM_OK) freeJson(json);

            // Content-mutating paths: region redaction + flatten (Object Fission).
            jpdfium_redact_region(page, 0.0f, 0.0f, 100.0f, 100.0f, 0xFF000000, 1);
            jpdfium_page_flatten(page);

            jpdfium_page_close(page);
        }
    }

    uint8_t* out = nullptr;
    int64_t outLen = 0;
    if (jpdfium_doc_save_bytes(doc, &out, &outLen) == JPDFIUM_OK) freeBuffer(out);
    if (jpdfium_doc_save_incremental(doc, &out, &outLen) == JPDFIUM_OK) freeBuffer(out);

    jpdfium_doc_close(doc);
    return 0;
}
