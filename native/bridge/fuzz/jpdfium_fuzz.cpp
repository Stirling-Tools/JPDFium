// jpdfium_fuzz - libFuzzer harness for the bridge's untrusted-input boundary.
//
// Fuzzes the call sequences the JVM performs on arbitrary PDF bytes, covering
// the main feature surface at the C bridge:
//   - document open / save (full + incremental)
//   - render + text extraction (crop / auto-crop detection path)
//   - redaction: region, words, pattern and the mark->commit Object Fission
//     engine (with content removal)
//   - flatten
//   - merge / N-up layout (import_n_pages_to_one)
//   - repair: qpdf pipeline, PDFio XRef recovery, Rust/lopdf tolerant rebuild
//   - Rust/zopfli compression
//
// The bridge's input validation (length caps, dimension caps, checked
// allocations) keeps the fuzz loop memory-bounded; ASan/UBSan report any
// violation and libFuzzer fails the run.
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
#include "jpdfium_rust.h"

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

// Derive a bounded, NUL-terminated word from the fuzz bytes so the redaction
// engine sees attacker-shaped search terms (not just a fixed dictionary).
void makeWord(const uint8_t* data, size_t size, uint32_t seed, char* out, size_t outLen) {
    size_t n = outLen - 1;
    for (size_t i = 0; i < n; i++) {
        out[i] = static_cast<char>(data[(i * 7 + seed) % size]);
    }
    out[n] = '\0';
}

}  // namespace

extern "C" int LLVMFuzzerTestOneInput(const uint8_t* data, size_t size) {
    if (size == 0 || size > kMaxInputSize) return 0;
    ensureInit();

    // Standalone pipelines over the raw bytes - the repair feature (qpdf,
    // PDFio XRef recovery, Rust/lopdf) and Rust/zopfli compression.
    {
        uint8_t* out = nullptr;
        int64_t outLen = 0;
        if (jpdfium_repair_pdf(data, static_cast<int64_t>(size), &out, &outLen,
                               JPDFIUM_REPAIR_NORMALIZE_XREF | JPDFIUM_REPAIR_FIX_STARTXREF) ==
            JPDFIUM_OK) {
            freeBuffer(out);
        }
        int32_t pagesRecovered = 0;
        if (jpdfium_pdfio_try_repair(data, static_cast<int64_t>(size), &out, &outLen,
                                     &pagesRecovered) == JPDFIUM_OK) {
            freeBuffer(out);
        }
        if (jpdfium_rust_repair_lopdf(data, static_cast<int64_t>(size), &out, &outLen) ==
            JPDFIUM_OK) {
            freeBuffer(out);
        }
        if (jpdfium_rust_compress_pdf(data, static_cast<int64_t>(size), &out, &outLen, 5) ==
            JPDFIUM_OK) {
            freeBuffer(out);
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
            if (jpdfium_render_page(page, 36, &rgba, &w, &h) == JPDFIUM_OK) freeBuffer(rgba);

            char* json = nullptr;
            if (jpdfium_text_get_chars(page, &json) == JPDFIUM_OK) freeJson(json);
            if (jpdfium_text_get_char_positions(page, &json) == JPDFIUM_OK) freeJson(json);
            if (jpdfium_text_find(page, "a", &json) == JPDFIUM_OK) freeJson(json);

            // Redaction (Object Fission) with content removal: region, derived
            // words, regex pattern, and the mark->commit two-phase path.
            jpdfium_redact_region(page, 0.0f, 0.0f, 100.0f, 100.0f, 0xFF000000, 1);

            char w0[8], w1[8];
            makeWord(data, size, 0, w0, sizeof(w0));
            makeWord(data, size, 1, w1, sizeof(w1));
            const char* words[2] = {w0, w1};
            jpdfium_redact_words(page, words, 2, 0xFF000000, 2.0f, 0, 0, 1);
            jpdfium_redact_pattern(page, "[A-Za-z]{1,4}", 0xFF000000, 1);

            int32_t commitCount = 0;
            jpdfium_annot_create_redact(page, 10.0f, 10.0f, 50.0f, 50.0f, 0, nullptr);
            jpdfium_redact_commit(page, 0xFF000000, 1, &commitCount);

            jpdfium_page_flatten(page);
            jpdfium_page_close(page);
        }

        // Merge / N-up layout - imports pages of the same doc into a grid.
        uint8_t* nup = nullptr;
        int64_t nupLen = 0;
        if (jpdfium_import_n_pages_to_one(reinterpret_cast<void*>(doc), 595.0f, 842.0f, 2, 2, &nup,
                                          &nupLen) == JPDFIUM_OK) {
            freeBuffer(nup);
        }

        // Crop / auto-crop detection path: bitmap content-bounds scan.
        jpdfium_page_to_image(doc, 0, 72);
    }

    uint8_t* out = nullptr;
    int64_t outLen = 0;
    if (jpdfium_doc_save_bytes(doc, &out, &outLen) == JPDFIUM_OK) freeBuffer(out);
    if (jpdfium_doc_save_incremental(doc, &out, &outLen) == JPDFIUM_OK) freeBuffer(out);

    jpdfium_doc_close(doc);
    return 0;
}
