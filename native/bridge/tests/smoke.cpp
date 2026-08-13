// native_smoke - CTest smoke test for the bridge ABI, run without the JVM.
//
// Exercises the core lifecycle end-to-end against either build variant:
//   - stub bridge (JPDFIUM_USE_PDFIUM=OFF): deterministic fake data
//   - real bridge (JPDFIUM_USE_PDFIUM=ON):  actual PDFium-backed behavior
//
// When the real bridge is detected (the stub's Rust stubs return
// JPDFIUM_ERR_NATIVE=-99 while the real bridge does not), additional
// boundary checks on untrusted-input validation run too.

#include <cstdio>
#include <cstring>

#include "jpdfium.h"

namespace {

// Minimal valid one-page PDF (xref offsets generated programmatically).
const char kMinimalPdf[] =
    "%PDF-1.4\n"
    "1 0 obj\n"
    "<< /Type /Catalog /Pages 2 0 R >>\n"
    "endobj\n"
    "2 0 obj\n"
    "<< /Type /Pages /Kids [3 0 R] /Count 1 >>\n"
    "endobj\n"
    "3 0 obj\n"
    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>\n"
    "endobj\n"
    "xref\n"
    "0 4\n"
    "0000000000 65535 f \n"
    "0000000009 00000 n \n"
    "0000000058 00000 n \n"
    "0000000115 00000 n \n"
    "trailer\n"
    "<< /Size 4 /Root 1 0 R >>\n"
    "startxref\n"
    "186\n"
    "%%EOF\n";

int g_failures = 0;

#define CHECK(cond, what)                                                                         \
    do {                                                                                          \
        if (!(cond)) {                                                                            \
            std::fprintf(stderr, "native_smoke: FAIL %s (line %d): %s\n", what, __LINE__, #cond); \
            ++g_failures;                                                                         \
        }                                                                                         \
    } while (0)

bool isRealBridge() {
    uint8_t* out = nullptr;
    int64_t outLen = 0;
    int32_t rc =
        jpdfium_rust_compress_pdf(reinterpret_cast<const uint8_t*>(kMinimalPdf),
                                  static_cast<int64_t>(sizeof(kMinimalPdf) - 1), &out, &outLen, 1);
    if (out) jpdfium_rust_free(out);
    // Stub returns JPDFIUM_ERR_NATIVE (-99) without touching out params.
    return rc != JPDFIUM_ERR_NATIVE;
}

void runPositivePath() {
    int32_t rc = JPDFIUM_OK;
    int64_t doc = 0;
    rc = jpdfium_doc_open_bytes(reinterpret_cast<const uint8_t*>(kMinimalPdf),
                                static_cast<int64_t>(sizeof(kMinimalPdf) - 1), &doc);
    CHECK(rc == JPDFIUM_OK, "jpdfium_doc_open_bytes");

    int32_t count = 0;
    rc = jpdfium_doc_page_count(doc, &count);
    CHECK(rc == JPDFIUM_OK && count >= 1, "jpdfium_doc_page_count");

    int64_t page = 0;
    rc = jpdfium_page_open(doc, 0, &page);
    CHECK(rc == JPDFIUM_OK, "jpdfium_page_open");

    float w = 0, h = 0;
    rc = jpdfium_page_width(page, &w);
    CHECK(rc == JPDFIUM_OK && w > 0, "jpdfium_page_width");
    rc = jpdfium_page_height(page, &h);
    CHECK(rc == JPDFIUM_OK && h > 0, "jpdfium_page_height");

    uint8_t* rgba = nullptr;
    int32_t rw = 0, rh = 0;
    rc = jpdfium_render_page(page, 72, &rgba, &rw, &rh);
    CHECK(rc == JPDFIUM_OK && rgba && rw > 0 && rh > 0, "jpdfium_render_page");
    jpdfium_free_buffer(rgba);

    char* json = nullptr;
    rc = jpdfium_text_get_chars(page, &json);
    CHECK(rc == JPDFIUM_OK && json && json[0] == '[', "jpdfium_text_get_chars");
    jpdfium_free_string(json);

    uint8_t* out = nullptr;
    int64_t outLen = 0;
    rc = jpdfium_doc_save_bytes(doc, &out, &outLen);
    CHECK(rc == JPDFIUM_OK && out && outLen > 0, "jpdfium_doc_save_bytes");
    jpdfium_free_buffer(out);

    jpdfium_page_close(page);
    jpdfium_doc_close(doc);
}

void runBoundaryChecks() {
    int64_t doc = 0;

    // Null data must be rejected, not dereferenced.
    CHECK(jpdfium_doc_open_bytes(nullptr, 10, &doc) == JPDFIUM_ERR_INVALID,
          "doc_open_bytes null data");

    // Zero/negative lengths must be rejected.
    CHECK(jpdfium_doc_open_bytes(reinterpret_cast<const uint8_t*>(kMinimalPdf), 0, &doc) ==
              JPDFIUM_ERR_INVALID,
          "doc_open_bytes zero length");
    CHECK(jpdfium_doc_open_bytes(reinterpret_cast<const uint8_t*>(kMinimalPdf), -5, &doc) ==
              JPDFIUM_ERR_INVALID,
          "doc_open_bytes negative length");

    // Garbage bytes must fail cleanly (translatePdfiumError path), not crash.
    const uint8_t garbage[] = {0xde, 0xad, 0xbe, 0xef};
    int32_t rc = jpdfium_doc_open_bytes(garbage, sizeof(garbage), &doc);
    CHECK(rc != JPDFIUM_OK, "doc_open_bytes garbage");
    if (rc == JPDFIUM_OK) jpdfium_doc_close(doc);

    // Null handles must be rejected.
    CHECK(jpdfium_doc_page_count(0, nullptr) != JPDFIUM_OK, "doc_page_count null args");
}

}  // namespace

int main() {
    // PDFium contract: FPDF_InitLibrary must run before any other FPDF_*
    // call (the bridge relies on this from the JVM side too).
    CHECK(jpdfium_init() == JPDFIUM_OK, "jpdfium_init");

    if (isRealBridge()) {
        std::puts("native_smoke: real bridge detected, running boundary checks too");
        runBoundaryChecks();
    } else {
        std::puts("native_smoke: stub bridge detected");
    }
    runPositivePath();
    jpdfium_destroy();

    if (g_failures) {
        std::fprintf(stderr, "native_smoke: %d check(s) FAILED\n", g_failures);
        return 1;
    }
    std::puts("native_smoke: all checks passed");
    return 0;
}
