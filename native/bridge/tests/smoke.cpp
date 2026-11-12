// native_smoke - CTest smoke test for the bridge ABI.

#include <cmath>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "jpdfium.h"
#include "jpdfium_font_encoding.h"

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

    // Left-half hard crop: removes DROP_ME, preserves the straddling word at
    // x=306 whole (crop no longer splits a word that touches the boundary).
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

void runColorChecks();

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
    CHECK(jpdfium_crop_remove_content_ex(page, 0.0f, 0.0f, 10.0f, 10.0f, 0.0f) == JPDFIUM_OK,
          "crop AUTO margin");
    CHECK(jpdfium_crop_remove_content_ex(page, 0.0f, 0.0f, 10.0f, 10.0f, 1.0f) == JPDFIUM_OK,
          "crop 1pt margin");
    CHECK(jpdfium_crop_remove_content_ex(page, 0.0f, 0.0f, 10.0f, 10.0f, -1.0f) ==
              JPDFIUM_ERR_INVALID,
          "crop negative margin");
    CHECK(
        jpdfium_crop_remove_content_ex(page, 0.0f, 0.0f, 10.0f, 10.0f, NAN) == JPDFIUM_ERR_INVALID,
        "crop NaN margin");
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
    runColorChecks();
}

// DeviceCMYK pipeline: the capability probe must agree with the compiled-in
// backends; the stub reports none, so these checks are no-ops there.
void runColorChecks() {
    const uint32_t caps = jpdfium_color_capabilities();
    std::printf("native_smoke: colour capabilities = 0x%02x\n", caps);

    if ((caps & JPDFIUM_COLOR_CAP_CMYK_IMAGE) != 0u) {
        CHECK(jpdfium_has_rust() == 1, "cmyk image capability requires rust");
    }
    if (jpdfium_has_rust() == 1) {
        CHECK((caps & JPDFIUM_COLOR_CAP_CMYK_IMAGE) != 0u,
              "rust build must report cmyk image capability");
        // 2x2 RGB: red, green, blue, white.
        const uint8_t rgb[12] = {255, 0, 0, 0, 255, 0, 0, 0, 255, 255, 255, 255};
        uint8_t* jpeg = nullptr;
        int64_t jpegLen = 0;
        const int32_t rc = jpdfium_color_samples_to_cmyk_jpeg(
            rgb, static_cast<int64_t>(sizeof(rgb)), 2, 2, 3, 90, 0, &jpeg, &jpegLen);
        CHECK(rc == JPDFIUM_OK, "jpdfium_color_samples_to_cmyk_jpeg");
        if (rc == JPDFIUM_OK && jpeg != nullptr && jpegLen > 4) {
            CHECK(jpeg[0] == 0xFF && jpeg[1] == 0xD8, "cmyk jpeg SOI marker");
            CHECK(jpeg[jpegLen - 2] == 0xFF && jpeg[jpegLen - 1] == 0xD9, "cmyk jpeg EOI marker");
        }
        jpdfium_free_buffer(jpeg);
    }

    // Output intents are injected with QPDF; the capability must track the ABI.
    if (jpdfium_abi_query(JPDFIUM_ABI_QUERY_HAS_QPDF) == 1) {
        CHECK((caps & JPDFIUM_COLOR_CAP_OUTPUT_INTENT) != 0u, "output intent capability");
    }
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

static void runFontEncodingChecks() {
    using namespace jpdfium_font_encoding;
    CHECK(std::strcmp(adobeGlyphNameForUnicode(0x41), "A") == 0, "AGL A");
    CHECK(std::strcmp(adobeGlyphNameForUnicode(0x20AC), "Euro") == 0, "AGL Euro");
    CHECK(adobeGlyphNameForUnicode(0) == nullptr, "AGL null");
    CHECK(unicodeForGlyphName("A") == 0x41, "unicode A");
    CHECK(unicodeForGlyphName("Euro") == 0x20AC, "unicode Euro");
    CHECK(unicodeForGlyphName("nonexistent_glyph_xyz") == 0, "unicode unknown");
    CHECK(unicodeForGlyphName("uFACE") == 0xFACE, "unicode uFACE");
    CHECK(unicodeForGlyphName("uface") == 0, "unicode uface is a name, not a hex escape");
    CHECK(unicodeForGlyphName("u00e9") == 0xE9, "unicode lowercase u00e9");
    CHECK(unicodeForGlyphName("uABCD") == 0xABCD, "unicode uABCD");
    CHECK(unicodeForGlyphName("uni0041") == 0x41, "unicode uni0041");

    auto dalet = unicodeSequenceForGlyphName("dalethatafpatah");
    CHECK(dalet.size() == 2 && dalet[0] == 0x5D3 && dalet[1] == 0x5B2,
          "AGL dalethatafpatah sequence");
    auto lamed = unicodeSequenceForGlyphName("lamedholamdagesh");
    CHECK(lamed.size() == 3 && lamed[0] == 0x5DC && lamed[1] == 0x5B9 && lamed[2] == 0x5BC,
          "AGL lamedholamdagesh sequence");
    auto rehyeh = unicodeSequenceForGlyphName("rehyehaleflamarabic");
    CHECK(rehyeh.size() == 4 && rehyeh[0] == 0x631 && rehyeh[1] == 0xFEF3 && rehyeh[2] == 0xFE8E &&
              rehyeh[3] == 0x644,
          "AGL rehyehaleflamarabic sequence");

    // Targeted assertions for PDF-specific encoding exceptions:
    // 1. StandardEncoding apostrophe (0x27) and grave (0x60) mappings
    CHECK(baseEncodingUnicode(BaseEncoding::Standard, 0x27) == 0x2019, "Standard 0x27 quoteright");
    CHECK(baseEncodingUnicode(BaseEncoding::Standard, 0x60) == 0x2018, "Standard 0x60 quoteleft");
    CHECK(baseEncodingUnicode(BaseEncoding::Standard, 0x20) == 0x0020, "Standard 0x20 space");
    CHECK(baseEncodingUnicode(BaseEncoding::Standard, 0xA4) == 0x2044, "Standard 0xA4 fraction");
    CHECK(baseEncodingUnicode(BaseEncoding::Standard, 0xA8) == 0x00A4, "Standard 0xA8 currency");
    CHECK(baseEncodingUnicode(BaseEncoding::Standard, 0xAE) == 0xFB01, "Standard 0xAE fi ligature");
    CHECK(baseEncodingUnicode(BaseEncoding::Standard, 0xAF) == 0xFB02, "Standard 0xAF fl ligature");
    CHECK(baseEncodingUnicode(BaseEncoding::Standard, 0xD0) == 0x2014, "Standard 0xD0 emdash");
    CHECK(baseEncodingUnicode(BaseEncoding::Standard, 0x80) == kUnmapped, "Standard 0x80 unmapped");
    CHECK(baseEncodingUnicode(BaseEncoding::Standard, 0xFF) == kUnmapped, "Standard 0xFF unmapped");

    // 2. WinAnsi bullet substitutions (ISO 32000-1 Annex D.2 Notes 3-5)
    CHECK(baseEncodingUnicode(BaseEncoding::WinAnsi, 0x7F) == 0x2022, "WinAnsi 0x7F bullet");
    CHECK(baseEncodingUnicode(BaseEncoding::WinAnsi, 0x80) == 0x20AC, "WinAnsi 0x80 Euro");
    CHECK(baseEncodingUnicode(BaseEncoding::WinAnsi, 0x81) == 0x2022, "WinAnsi 0x81 bullet");
    CHECK(baseEncodingUnicode(BaseEncoding::WinAnsi, 0x8D) == 0x2022, "WinAnsi 0x8D bullet");
    CHECK(baseEncodingUnicode(BaseEncoding::WinAnsi, 0x8F) == 0x2022, "WinAnsi 0x8F bullet");
    CHECK(baseEncodingUnicode(BaseEncoding::WinAnsi, 0x90) == 0x2022, "WinAnsi 0x90 bullet");
    CHECK(baseEncodingUnicode(BaseEncoding::WinAnsi, 0x95) == 0x2022, "WinAnsi 0x95 bullet");
    CHECK(baseEncodingUnicode(BaseEncoding::WinAnsi, 0x9D) == 0x2022, "WinAnsi 0x9D bullet");
    CHECK(baseEncodingUnicode(BaseEncoding::WinAnsi, 0x99) == 0x2122, "WinAnsi 0x99 trademark");

    // 3. WinAnsi space (0xA0) and hyphen (0xAD) aliases
    CHECK(baseEncodingUnicode(BaseEncoding::WinAnsi, 0xA0) == 0x0020, "WinAnsi 0xA0 space alias");
    CHECK(baseEncodingUnicode(BaseEncoding::WinAnsi, 0xAD) == 0x002D, "WinAnsi 0xAD hyphen alias");
    CHECK(baseEncodingUnicode(BaseEncoding::WinAnsi, 0xA1) == 0x00A1, "WinAnsi 0xA1 exclamdown");
    CHECK(baseEncodingUnicode(BaseEncoding::WinAnsi, 0xFF) == 0x00FF, "WinAnsi 0xFF ydieresis");

    // 4. MacRoman currency (0xDB) vs Euro: must be currency U+00A4 per PDF Annex D.4
    CHECK(baseEncodingUnicode(BaseEncoding::MacRoman, 0xDB) == 0x00A4,
          "MacRoman 0xDB currency (not euro)");
    CHECK(baseEncodingUnicode(BaseEncoding::MacRoman, 0xA4) == 0x00A7, "MacRoman 0xA4 section");
    CHECK(baseEncodingUnicode(BaseEncoding::MacRoman, 0x80) == 0x00C4, "MacRoman 0x80 Adieresis");
    CHECK(baseEncodingUnicode(BaseEncoding::MacRoman, 0xCA) == 0x0020, "MacRoman 0xCA space");

    // 5. Exhaustive check of all 256 byte values for all three encodings:
    for (int code = 0; code < 256; ++code) {
        // Out-of-printable control codes (0x00..0x1F) must always be unmapped (0).
        if (code < 0x20) {
            CHECK(baseEncodingUnicode(BaseEncoding::Standard, code) == kUnmapped,
                  "Standard control code");
            CHECK(baseEncodingUnicode(BaseEncoding::WinAnsi, code) == kUnmapped,
                  "WinAnsi control code");
            CHECK(baseEncodingUnicode(BaseEncoding::MacRoman, code) == kUnmapped,
                  "MacRoman control code");
            continue;
        }

        // ASCII range 0x20..0x7E:
        if (code < 0x7F) {
            uint32_t win = baseEncodingUnicode(BaseEncoding::WinAnsi, code);
            uint32_t mac = baseEncodingUnicode(BaseEncoding::MacRoman, code);
            uint32_t std_val = baseEncodingUnicode(BaseEncoding::Standard, code);
            CHECK(win == static_cast<uint32_t>(code), "WinAnsi ASCII identity");
            CHECK(mac == static_cast<uint32_t>(code), "MacRoman ASCII identity");
            if (code == 0x27) {
                CHECK(std_val == 0x2019, "Standard 0x27");
            } else if (code == 0x60) {
                CHECK(std_val == 0x2018, "Standard 0x60");
            } else {
                CHECK(std_val == static_cast<uint32_t>(code), "Standard ASCII identity");
            }
            continue;
        }

        // DEL (0x7F):
        if (code == 0x7F) {
            CHECK(baseEncodingUnicode(BaseEncoding::WinAnsi, code) == 0x2022, "WinAnsi 0x7F");
            CHECK(baseEncodingUnicode(BaseEncoding::Standard, code) == kUnmapped, "Standard 0x7F");
            CHECK(baseEncodingUnicode(BaseEncoding::MacRoman, code) == kUnmapped, "MacRoman 0x7F");
            continue;
        }

        // High bytes 0x80..0xFF:
        uint32_t win = baseEncodingUnicode(BaseEncoding::WinAnsi, code);
        uint32_t mac = baseEncodingUnicode(BaseEncoding::MacRoman, code);
        uint32_t std_val = baseEncodingUnicode(BaseEncoding::Standard, code);

        // WinAnsi high bytes:
        if (code >= 0x80 && code <= 0x9F) {
            CHECK(win == kWinAnsiHigh[code - 0x80], "WinAnsi high table match");
            CHECK(std_val == kUnmapped, "Standard 0x80..0x9F must be unmapped");
        } else if (code == 0xA0) {
            CHECK(win == 0x0020, "WinAnsi 0xA0 space");
        } else if (code == 0xAD) {
            CHECK(win == 0x002D, "WinAnsi 0xAD hyphen");
        } else {
            CHECK(win == static_cast<uint32_t>(code), "WinAnsi 0xA1..0xFF identity");
        }

        // MacRoman high bytes:
        CHECK(mac == kMacRomanHigh[code - 0x80], "MacRoman high table match");

        // StandardEncoding high bytes:
        if (code >= 0xA0) {
            CHECK(std_val == kStandardHigh[code - 0xA0], "Standard high table match");
        }
    }

    // Out-of-bounds guards:
    CHECK(baseEncodingUnicode(BaseEncoding::Standard, -1) == kUnmapped, "negative code");
    CHECK(baseEncodingUnicode(BaseEncoding::WinAnsi, 256) == kUnmapped, "code 256");
    CHECK(baseEncodingUnicode(BaseEncoding::MacRoman, 1000) == kUnmapped, "code 1000");
}

}  // namespace

int main() {
    // PDFium contract: FPDF_InitLibrary must run before any other FPDF_*
    // call (the bridge relies on this from the JVM side too).
    CHECK(jpdfium_init() == JPDFIUM_OK, "jpdfium_init");

    runFontEncodingChecks();

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
