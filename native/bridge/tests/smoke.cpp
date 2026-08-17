// native_smoke - CTest smoke test for the bridge ABI, run without the JVM.
//
// Exercises the core lifecycle end-to-end against either build variant:
//   - stub bridge (JPDFIUM_USE_PDFIUM=OFF): deterministic fake data
//   - real bridge (JPDFIUM_USE_PDFIUM=ON):  actual PDFium-backed behavior
//
// When the real bridge is detected (the stub's Rust stubs return
// JPDFIUM_ERR_NATIVE=-99 while the real bridge does not), additional
// boundary checks on untrusted-input validation run too.

#include <cmath>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

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

// One-page PDF with three text objects at known coordinates:
//   KEEP_ME     at x=100  (inside a left-half crop)
//   STRADDLING  at x=300  (straddles the x=306 crop boundary -> char fission)
//   DROP_ME     at x=400  (entirely outside -> removed)
// Xref offsets are computed programmatically so the buffer stays valid.
std::vector<uint8_t> makeCropPdf() {
    std::string objs[6];
    objs[1] = "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n";
    objs[2] = "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n";
    objs[3] =
        "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
        "/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>\nendobj\n";
    objs[4] = "4 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n";
    std::string stream =
        "BT /F1 14 Tf 100 700 Td (KEEP_ME) Tj ET\n"
        "BT /F1 14 Tf 300 700 Td (STRADDLING) Tj ET\n"
        "BT /F1 14 Tf 400 700 Td (DROP_ME) Tj ET\n";
    char lenBuf[64];
    std::snprintf(lenBuf, sizeof(lenBuf), "%zu", stream.size());
    objs[5] = "5 0 obj\n<< /Length " + std::string(lenBuf) + " >>\nstream\n" + stream +
              "endstream\nendobj\n";

    std::string body = "%PDF-1.4\n";
    size_t offsets[6] = {};
    for (int i = 1; i <= 5; ++i) {
        offsets[i] = body.size();
        body += objs[i];
    }

    std::string xref = "xref\n0 6\n0000000000 65535 f \n";
    char line[128];
    for (int i = 1; i <= 5; ++i) {
        std::snprintf(line, sizeof(line), "%010zu 00000 n \n", offsets[i]);
        xref += line;
    }
    size_t xrefOff = body.size();
    body += xref;
    char tail[128];
    std::snprintf(tail, sizeof(tail), "trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n%zu\n%%EOF\n",
                  xrefOff);
    body += tail;
    return std::vector<uint8_t>(body.begin(), body.end());
}

// Crop + redact + incremental save on the content-bearing fixture. On the
// real bridge this drives the full Object Fission engine (char-to-object
// mapping, arena containers, fragment materialisation, insert-at-index,
// removals, GenerateContent); under an ASan/UBSan build it is the memory
// safety gate for that path. The stub bridge accepts the same calls.
void runCropEngineChecks() {
    auto cropPdf = makeCropPdf();
    int64_t cdoc = 0;
    int32_t rc =
        jpdfium_doc_open_bytes(cropPdf.data(), static_cast<int64_t>(cropPdf.size()), &cdoc);
    CHECK(rc == JPDFIUM_OK, "open crop fixture");
    if (rc != JPDFIUM_OK) return;

    int64_t cpage = 0;
    rc = jpdfium_page_open(cdoc, 0, &cpage);
    CHECK(rc == JPDFIUM_OK, "open crop fixture page");
    if (rc != JPDFIUM_OK) {
        jpdfium_doc_close(cdoc);
        return;
    }

    // Left-half hard crop: removes DROP_ME, fissions STRADDLING at x=306.
    rc = jpdfium_crop_remove_content(cpage, 0.0f, 0.0f, 306.0f, 792.0f);
    CHECK(rc == JPDFIUM_OK, "crop left half");

    // Region redact on surviving text (paints a cover rect + removes objects).
    rc = jpdfium_redact_region(cpage, 90.0f, 690.0f, 30.0f, 20.0f, 0xFF000000, 1);
    CHECK(rc == JPDFIUM_OK, "redact region");

    // Full-page no-op crop (fast path: nothing outside -> no content rewrite).
    rc = jpdfium_crop_remove_content(cpage, 0.0f, 0.0f, 306.0f, 792.0f);
    CHECK(rc == JPDFIUM_OK, "crop fast path");

    uint8_t* incSaved = nullptr;
    int64_t incSavedLen = 0;
    int32_t incRc = jpdfium_doc_save_incremental(cdoc, &incSaved, &incSavedLen);
    CHECK(incRc == JPDFIUM_ERR_REDACTED_SAVE, "incremental save refused after redaction");

    uint8_t* saved = nullptr;
    int64_t savedLen = 0;
    rc = jpdfium_doc_save_bytes(cdoc, &saved, &savedLen);
    CHECK(rc == JPDFIUM_OK && saved && savedLen > 0, "full save after crop and redact");
    jpdfium_free_buffer(saved);

    jpdfium_page_close(cpage);
    jpdfium_doc_close(cdoc);
}

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

    // Degenerate geometry (NaN/Inf/non-positive) must be rejected, never
    // silently misinterpreted: comparisons with NaN are all false, so a NaN
    // crop rect would otherwise remove everything (or nothing).
    CHECK(jpdfium_crop_remove_content(page, 0.0f, 0.0f, NAN, 10.0f) == JPDFIUM_ERR_INVALID,
          "crop NaN width");
    CHECK(jpdfium_crop_remove_content(page, 0.0f, 0.0f, INFINITY, 10.0f) == JPDFIUM_ERR_INVALID,
          "crop +Inf width");
    CHECK(jpdfium_crop_remove_content(page, 0.0f, 0.0f, 10.0f, -5.0f) == JPDFIUM_ERR_INVALID,
          "crop negative height");
    CHECK(jpdfium_crop_remove_content(page, 0.0f, 0.0f, 10.0f, 10.0f) == JPDFIUM_OK,
          "crop valid rect");
    CHECK(
        jpdfium_redact_region(page, NAN, 0.0f, 10.0f, 10.0f, 0xFF000000, 1) == JPDFIUM_ERR_INVALID,
        "redact_region NaN x");

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

    runCropEngineChecks();
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
