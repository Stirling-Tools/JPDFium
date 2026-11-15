// native_smoke - CTest smoke test for the bridge ABI.

#include <algorithm>
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

void runRepairEdgeChecks() {
    // 1. Broken /Pages tree (/Kids [] /Count 0) must be rebuilt from orphan /Page leaves.
    {
        std::string broken = "%PDF-1.4\n";
        size_t o1 = broken.size();
        broken += "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n";
        size_t o2 = broken.size();
        broken += "2 0 obj\n<< /Type /Pages /Kids [] /Count 0 /MediaBox [0 0 200 200] >>\nendobj\n";
        size_t o3 = broken.size();
        broken += "3 0 obj\n<< /Type /Page /Parent 2 0 R /Resources << >> >>\nendobj\n";
        size_t o4 = broken.size();
        broken += "4 0 obj\n<< /Type /Page /Parent 2 0 R /Resources << >> >>\nendobj\n";
        size_t xOff = broken.size();
        char xb[320];
        std::snprintf(xb, sizeof(xb),
                      "xref\n0 5\n0000000000 65535 f \n%010zu 00000 n \n%010zu 00000 n \n"
                      "%010zu 00000 n \n%010zu 00000 n \ntrailer\n<< /Root 1 0 R /Size 5 >>\n"
                      "startxref\n%zu\n%%%%EOF\n",
                      o1, o2, o3, o4, xOff);
        broken += xb;

        uint8_t* repaired = nullptr;
        int64_t repairedLen = 0;
        int32_t rc = jpdfium_repair_pdf(
            reinterpret_cast<const uint8_t*>(broken.data()), static_cast<int64_t>(broken.size()),
            &repaired, &repairedLen, JPDFIUM_REPAIR_NORMALIZE_XREF | JPDFIUM_REPAIR_FIX_STARTXREF);
        CHECK(rc >= 0 && repaired != nullptr && repairedLen > 0,
              "repair broken /Pages tree returns output");
        if (repaired != nullptr && repairedLen > 0) {
            int64_t doc = 0;
            CHECK(jpdfium_doc_open_bytes(repaired, repairedLen, &doc) == JPDFIUM_OK,
                  "repaired broken /Pages opens");
            if (doc != 0) {
                int32_t pages = 0;
                CHECK(jpdfium_doc_page_count(doc, &pages) == JPDFIUM_OK && pages == 2,
                      "repaired broken /Pages has 2 pages");
                jpdfium_doc_close(doc);
            }
            jpdfium_free_buffer(repaired);
        }
    }

    // 2. Truncated incremental update with >4 KiB tail and no trailing %%EOF must still recover.
    {
        std::string pdf;
        pdf += "%PDF-1.4\n";
        size_t off1 = pdf.size();
        pdf += "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n";
        size_t off2 = pdf.size();
        pdf += "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n";
        size_t off3 = pdf.size();
        pdf += "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] >>\nendobj\n";
        size_t xrefOff = pdf.size();
        char xrefBuf[256];
        std::snprintf(xrefBuf, sizeof(xrefBuf),
                      "xref\n0 4\n0000000000 65535 f \n%010zu 00000 n \n%010zu 00000 n \n"
                      "%010zu 00000 n \ntrailer\n<< /Root 1 0 R /Size 4 >>\nstartxref\n%zu\n"
                      "%%%%EOF\n",
                      off1, off2, off3, xrefOff);
        pdf += xrefBuf;
        // Append > 4 KiB unclosed incremental object tail without endstream/endobj/%%EOF.
        pdf += "\n4 0 obj\n<< /Length 5000 >>\nstream\n";
        pdf.append(4800, 'A');

        uint8_t* repaired = nullptr;
        int64_t repairedLen = 0;
        int32_t rc = jpdfium_repair_pdf(
            reinterpret_cast<const uint8_t*>(pdf.data()), static_cast<int64_t>(pdf.size()),
            &repaired, &repairedLen, JPDFIUM_REPAIR_NORMALIZE_XREF | JPDFIUM_REPAIR_FIX_STARTXREF);
        CHECK(rc >= 0 && repaired != nullptr && repairedLen > 0,
              "repair truncated >4KiB incremental update returns output");
        if (repaired != nullptr && repairedLen > 0) {
            int64_t doc = 0;
            CHECK(jpdfium_doc_open_bytes(repaired, repairedLen, &doc) == JPDFIUM_OK,
                  "repaired >4KiB truncated incremental update opens");
            if (doc != 0) {
                int32_t pages = 0;
                CHECK(jpdfium_doc_page_count(doc, &pages) == JPDFIUM_OK && pages == 1,
                      "repaired >4KiB truncated incremental update preserves page count");
                jpdfium_doc_close(doc);
            }
            jpdfium_free_buffer(repaired);
        }
    }
}

void appendU16BE(std::vector<uint8_t>& out, uint16_t v) {
    out.push_back(static_cast<uint8_t>((v >> 8) & 0xFF));
    out.push_back(static_cast<uint8_t>(v & 0xFF));
}

void appendU32BE(std::vector<uint8_t>& out, uint32_t v) {
    out.push_back(static_cast<uint8_t>((v >> 24) & 0xFF));
    out.push_back(static_cast<uint8_t>((v >> 16) & 0xFF));
    out.push_back(static_cast<uint8_t>((v >> 8) & 0xFF));
    out.push_back(static_cast<uint8_t>(v & 0xFF));
}

// Build a minimal valid TrueType binary with 1 + glyphCount glyphs (GID 0 = .notdef,
// GID 1..glyphCount mapped non-contiguously at Unicode U+0402, U+0404, ... so ToUnicode
// repair cannot fold them into beginbfrange and must split >100 beginbfchar entries).
std::vector<uint8_t> buildMinimalSparseTtf(uint16_t glyphCount) {
    const uint16_t numGlyphs = static_cast<uint16_t>(glyphCount + 1);

    // Build format 4 cmap subtable (one singleton segment per glyph + 0xFFFF sentinel).
    const uint16_t segCount = static_cast<uint16_t>(glyphCount + 1);
    const uint16_t segCountX2 = static_cast<uint16_t>(segCount * 2);
    uint16_t searchRange = 2;
    uint16_t entrySelector = 0;
    while (searchRange * 2 <= segCountX2) {
        searchRange *= 2;
        ++entrySelector;
    }
    const uint16_t rangeShift = static_cast<uint16_t>(segCountX2 - searchRange);
    const uint16_t subLen = static_cast<uint16_t>(16 + segCount * 8);

    std::vector<uint8_t> cmap;
    appendU16BE(cmap, 0);
    appendU16BE(cmap, 1);
    appendU16BE(cmap, 3);
    appendU16BE(cmap, 1);
    appendU32BE(cmap, 12);
    appendU16BE(cmap, 4);
    appendU16BE(cmap, subLen);
    appendU16BE(cmap, 0);
    appendU16BE(cmap, segCountX2);
    appendU16BE(cmap, searchRange);
    appendU16BE(cmap, entrySelector);
    appendU16BE(cmap, rangeShift);
    for (uint16_t g = 1; g <= glyphCount; ++g)
        appendU16BE(cmap, static_cast<uint16_t>(0x0400 + g * 2));
    appendU16BE(cmap, 0xFFFF);
    appendU16BE(cmap, 0);
    for (uint16_t g = 1; g <= glyphCount; ++g)
        appendU16BE(cmap, static_cast<uint16_t>(0x0400 + g * 2));
    appendU16BE(cmap, 0xFFFF);
    for (uint16_t g = 1; g <= glyphCount; ++g) {
        const uint16_t code = static_cast<uint16_t>(0x0400 + g * 2);
        appendU16BE(cmap, static_cast<uint16_t>((static_cast<int32_t>(g) - code) & 0xFFFF));
    }
    appendU16BE(cmap, 1);
    for (uint16_t i = 0; i < segCount; ++i) appendU16BE(cmap, 0);

    std::vector<uint8_t> head(54, 0);
    head[2] = 1;
    head[12] = 0x5F;
    head[13] = 0x0F;
    head[14] = 0x3C;
    head[15] = 0xF5;
    head[18] = 0x03;
    head[19] = 0xE8;  // unitsPerEm = 1000

    std::vector<uint8_t> hhea(36, 0);
    hhea[2] = 1;
    hhea[4] = 0x03;
    hhea[5] = 0x20;
    hhea[34] = static_cast<uint8_t>(numGlyphs >> 8);
    hhea[35] = static_cast<uint8_t>(numGlyphs & 0xFF);

    std::vector<uint8_t> hmtx;
    for (uint16_t i = 0; i < numGlyphs; ++i) {
        appendU16BE(hmtx, 500);
        appendU16BE(hmtx, 0);
    }

    std::vector<uint8_t> maxp(32, 0);
    maxp[1] = 1;
    maxp[4] = static_cast<uint8_t>(numGlyphs >> 8);
    maxp[5] = static_cast<uint8_t>(numGlyphs & 0xFF);

    std::vector<uint8_t> loca((static_cast<size_t>(numGlyphs) + 1) * 2, 0);
    std::vector<uint8_t> glyf(4, 0);
    std::vector<uint8_t> post(32, 0);
    post[1] = 3;  // format 3.0 (no PostScript names)

    struct TableEntry {
        const char* tag = nullptr;
        std::vector<uint8_t> data;
    };
    std::vector<TableEntry> tables = {
        {"cmap", std::move(cmap)}, {"glyf", std::move(glyf)}, {"head", std::move(head)},
        {"hhea", std::move(hhea)}, {"hmtx", std::move(hmtx)}, {"loca", std::move(loca)},
        {"maxp", std::move(maxp)}, {"post", std::move(post)},
    };
    std::sort(tables.begin(), tables.end(),
              [](auto const& a, auto const& b) { return std::strcmp(a.tag, b.tag) < 0; });

    const uint16_t numTables = static_cast<uint16_t>(tables.size());
    std::vector<uint8_t> sfnt;
    appendU32BE(sfnt, 0x00010000);
    appendU16BE(sfnt, numTables);
    appendU16BE(sfnt, 128);
    appendU16BE(sfnt, 3);
    appendU16BE(sfnt, static_cast<uint16_t>(numTables * 16 - 128));

    size_t offset = 12 + numTables * 16;
    for (auto const& t : tables) {
        for (int i = 0; i < 4; ++i) sfnt.push_back(static_cast<uint8_t>(t.tag[i]));
        appendU32BE(sfnt, 0);
        appendU32BE(sfnt, static_cast<uint32_t>(offset));
        appendU32BE(sfnt, static_cast<uint32_t>(t.data.size()));
        offset += (t.data.size() + 3) & ~size_t(3);
    }
    for (auto const& t : tables) {
        sfnt.insert(sfnt.end(), t.data.begin(), t.data.end());
        while (sfnt.size() & 3) sfnt.push_back(0);
    }
    return sfnt;
}

std::vector<uint8_t> assemblePdf(const char* header, std::vector<std::string> const& objs,
                                 int rootObj, int infoObj = 0) {
    std::string out = header;
    out += '\n';
    std::vector<size_t> offsets(objs.size() + 1, 0);
    for (size_t i = 0; i < objs.size(); ++i) {
        offsets[i + 1] = out.size();
        out += std::to_string(i + 1) + " 0 obj\n" + objs[i] + "\nendobj\n";
    }
    size_t xrefOff = out.size();
    char buf[128];
    std::snprintf(buf, sizeof(buf), "xref\n0 %zu\n0000000000 65535 f \n", objs.size() + 1);
    out += buf;
    for (size_t i = 1; i <= objs.size(); ++i) {
        std::snprintf(buf, sizeof(buf), "%010zu 00000 n \n", offsets[i]);
        out += buf;
    }
    out += "trailer\n<< /Size " + std::to_string(objs.size() + 1) + " /Root " +
           std::to_string(rootObj) + " 0 R";
    if (infoObj > 0) out += " /Info " + std::to_string(infoObj) + " 0 R";
    std::snprintf(buf, sizeof(buf), " >>\nstartxref\n%zu\n%%%%EOF\n", xrefOff);
    out += buf;
    return std::vector<uint8_t>(out.begin(), out.end());
}

void checkSignatureNullSafety() {
    int32_t cnt = 77;
    CHECK(jpdfium_signature_count(0, &cnt) != JPDFIUM_OK && cnt == 0,
          "signature_count(0) zeros out-param");
    char* sjson = const_cast<char*>("sentinel");
    CHECK(jpdfium_signature_info(0, 0, &sjson) != JPDFIUM_OK && sjson == nullptr,
          "signature_info(0) nulls out-param");
    uint8_t sentinelByte = 1;
    uint8_t* dig = &sentinelByte;
    int64_t dlen = 42;
    CHECK(
        jpdfium_signature_digest(0, 0, 1, &dig, &dlen) != JPDFIUM_OK && dig == nullptr && dlen == 0,
        "signature_digest(0) clears out-params");

    int64_t doc = 0;
    CHECK(
        jpdfium_doc_open_bytes(reinterpret_cast<const uint8_t*>(kMinimalPdf),
                               static_cast<int64_t>(std::strlen(kMinimalPdf)), &doc) == JPDFIUM_OK,
        "open minimal doc for signature checks");
    if (doc != 0) {
        CHECK(jpdfium_signature_count(doc, nullptr) == JPDFIUM_ERR_INVALID,
              "signature_count rejects null out-param");
        CHECK(jpdfium_signature_info(doc, 0, nullptr) == JPDFIUM_ERR_INVALID,
              "signature_info rejects null out-param");
        jpdfium_doc_close(doc);
    }
}

void checkPatternJsonEscaping() {
    int64_t pat = 0;
    int32_t prc = jpdfium_pcre2_compile("(?s)longprefix_.*_end", 0, &pat);
    if (prc != JPDFIUM_ERR_NOT_FOUND) {
        CHECK(prc == JPDFIUM_OK && pat != 0, "pcre2 compile long pattern");
        if (prc == JPDFIUM_OK && pat != 0) {
            std::string text = "head longprefix_\t\n\"\x01\\";
            text.append(300, 'x');
            text += "_end tail";
            char* json = nullptr;
            CHECK(jpdfium_pcre2_match_all(pat, text.c_str(), &json) == JPDFIUM_OK && json,
                  "pcre2 match long control payload");
            if (json) {
                std::string s(json);
                CHECK(s.find("\\t\\n\\\"\\u0001\\\\") != std::string::npos,
                      "pcre2 JSON escapes control chars and quotes");
                bool rawCtrl = false;
                for (unsigned char c : s) {
                    if (c < 0x20) rawCtrl = true;
                }
                CHECK(!rawCtrl, "pcre2 JSON emits no raw control bytes");
                jpdfium_free_string(json);
            }
            jpdfium_pcre2_free(pat);
        }
    }

    int64_t ft = 0;
    int32_t frc = jpdfium_flashtext_create(&ft);
    if (frc != JPDFIUM_ERR_NOT_FOUND) {
        CHECK(frc == JPDFIUM_OK && ft != 0, "flashtext create");
        if (frc == JPDFIUM_OK && ft != 0) {
            const char* kwJson =
                "[{\"keyword\":\"nolabel\"},{\"keyword\":\"second\",\"label\":\"L1\\\"\\\\\"}]";
            CHECK(jpdfium_flashtext_add_keywords_json(ft, kwJson) == JPDFIUM_OK,
                  "flashtext add_keywords_json with optional label");
            CHECK(jpdfium_flashtext_add_keyword(ft, "k1\t\"a", "L\n2") == JPDFIUM_OK,
                  "flashtext add_keyword with control chars");
            char* fjson = nullptr;
            CHECK(
                jpdfium_flashtext_find(ft, "nolabel second k1\t\"a", &fjson) == JPDFIUM_OK && fjson,
                "flashtext find object-scoped matches");
            if (fjson) {
                std::string fs(fjson);
                CHECK(fs.find("\"keyword\":\"nolabel\",\"label\":\"\"") != std::string::npos,
                      "flashtext scopes omitted label per JSON object");
                CHECK(fs.find("L1\\\"\\\\") != std::string::npos &&
                          fs.find("k1\\t\\\"a") != std::string::npos &&
                          fs.find("L\\n2") != std::string::npos,
                      "flashtext escapes keyword and label controls in JSON");
                jpdfium_free_string(fjson);
            }
            jpdfium_flashtext_free(ft);
        }
    }
}

void checkPdfxPartialDates() {
    // Minimal 152-byte CMYK ICC profile with 1 valid tag entry ('desc') so IccComponentCount
    // validates it even in builds without lcms2.
    std::vector<uint8_t> icc(152, 0);
    icc[3] = 152;
    icc[16] = 'C';
    icc[17] = 'M';
    icc[18] = 'Y';
    icc[19] = 'K';
    icc[36] = 'a';
    icc[37] = 'c';
    icc[38] = 's';
    icc[39] = 'p';
    icc[131] = 1;    // tag_count = 1
    icc[132] = 'd';  // tag signature 'desc'
    icc[133] = 'e';
    icc[134] = 's';
    icc[135] = 'c';
    icc[139] = 144;  // tag offset = 144
    icc[143] = 8;    // tag size = 8
    auto datePdf = assemblePdf("%PDF-1.4",
                               {
                                   "<< /Type /Catalog /Pages 2 0 R >>",
                                   "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                                   "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] >>",
                                   "<< /CreationDate (D:2024) /ModDate (D:202405) >>",
                               },
                               1, 4);
    uint8_t* out = nullptr;
    int64_t outLen = 0;
    CHECK(jpdfium_color_pdfx_bytes(datePdf.data(), static_cast<int64_t>(datePdf.size()), icc.data(),
                                   static_cast<int64_t>(icc.size()), "PDF/X-4", "PDF/X-4",
                                   "FOGRA39", "Test", &out, &outLen) == JPDFIUM_OK &&
              out && outLen > 0,
          "pdfx_bytes converts partial Info dates");
    if (out && outLen > 0) {
        uint8_t* v14 = nullptr;
        int64_t v14Len = 0;
        if (jpdfium_qpdf_force_version(out, outLen, "1.4", &v14, &v14Len) == JPDFIUM_OK && v14 &&
            v14Len > 0) {
            std::string raw(reinterpret_cast<const char*>(v14), static_cast<size_t>(v14Len));
            CHECK(raw.find("2024-01-01T00:00:00") != std::string::npos,
                  "partial PDF year date D:2024 expands to 2024-01-01T00:00:00");
            CHECK(raw.find("2024-05-01T00:00:00") != std::string::npos,
                  "partial PDF month date D:202405 expands to 2024-05-01T00:00:00");
            jpdfium_free_buffer(v14);
        }
        jpdfium_free_buffer(out);
    }
}

void checkSparseToUnicodeAndStructSanitize() {
    std::vector<uint8_t> ttf = buildMinimalSparseTtf(108);
    std::string fontStream = "<< /Length " + std::to_string(ttf.size()) + " /Length1 " +
                             std::to_string(ttf.size()) + " >>\nstream\n" +
                             std::string(ttf.begin(), ttf.end()) + "\nendstream";
    std::string content = "BT /F1 12 Tf 72 700 Td <0001006C> Tj ET";
    std::string pageStream =
        "<< /Length " + std::to_string(content.size()) + " >>\nstream\n" + content + "\nendstream";
    const std::string pageObj =
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] "
        "/Resources << /Font << /F1 4 0 R >> >> /Contents 8 0 R >>";
    const std::string type0Obj =
        "<< /Type /Font /Subtype /Type0 /BaseFont /SparseSmoke "
        "/Encoding /Identity-H /DescendantFonts [5 0 R] >>";
    const std::string cidFontObj =
        "<< /Type /Font /Subtype /CIDFontType2 /BaseFont /SparseSmoke "
        "/CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> "
        "/CIDToGIDMap /Identity /DW 500 /FontDescriptor 6 0 R >>";
    const std::string fdObj =
        "<< /Type /FontDescriptor /FontName /SparseSmoke /Flags 4 "
        "/FontBBox [0 0 500 500] /ItalicAngle 0 /Ascent 800 /Descent -200 "
        "/CapHeight 700 /StemV 80 /FontFile2 9 0 R >>";
    auto fontPdf = assemblePdf("%PDF-1.4",
                               {
                                   "<< /Type /Catalog /Pages 2 0 R /StructTreeRoot 7 0 R >>",
                                   "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                                   pageObj,
                                   type0Obj,
                                   cidFontObj,
                                   fdObj,
                                   "<< /Type /StructTreeRoot >>",
                                   pageStream,
                                   fontStream,
                               },
                               1);

    int64_t doc = 0;
    CHECK(jpdfium_doc_open_bytes(fontPdf.data(), static_cast<int64_t>(fontPdf.size()), &doc) ==
              JPDFIUM_OK,
          "open sparse >100-glyph PDF");
    if (doc == 0) return;

    int32_t fixed = 0;
    CHECK(jpdfium_font_fix_tounicode(doc, 0, &fixed) == JPDFIUM_OK && fixed == 1,
          "font_fix_tounicode repairs >100-glyph ToUnicode");
    uint8_t* saved = nullptr;
    int64_t savedLen = 0;
    CHECK(jpdfium_doc_save_bytes(doc, &saved, &savedLen) == JPDFIUM_OK && saved && savedLen > 0,
          "save repaired >100-glyph PDF");
    jpdfium_doc_close(doc);
    if (!saved || savedLen <= 0) return;

    int64_t reopened = 0;
    if (jpdfium_doc_open_bytes(saved, savedLen, &reopened) == JPDFIUM_OK && reopened) {
        int64_t page = 0;
        if (jpdfium_page_open(reopened, 0, &page) == JPDFIUM_OK && page) {
            char* charsJson = nullptr;
            CHECK(jpdfium_text_get_chars(page, &charsJson) == JPDFIUM_OK && charsJson,
                  "extract text across >100 beginbfchar chunks");
            if (charsJson) {
                std::string cj(charsJson);
                CHECK(cj.find("1026") != std::string::npos && cj.find("1240") != std::string::npos,
                      "decoded chars span GID 1 (0x0402) and GID 108 (0x04D8)");
                jpdfium_free_string(charsJson);
            }
            jpdfium_page_close(page);
        }
        jpdfium_doc_close(reopened);
    }

    uint8_t* san = nullptr;
    int64_t sanLen = 0;
    CHECK(jpdfium_qpdf_sanitize(saved, savedLen, &san, &sanLen, JPDFIUM_SANITIZE_STRUCTURE) ==
                  JPDFIUM_OK &&
              san && sanLen > 0,
          "qpdf_sanitize removes /StructTreeRoot");
    if (san && sanLen > 0) {
        std::string raw(reinterpret_cast<const char*>(san), static_cast<size_t>(sanLen));
        CHECK(raw.find("/StructTreeRoot") == std::string::npos,
              "sanitized output omits /StructTreeRoot");
        jpdfium_free_buffer(san);
    }
    jpdfium_free_buffer(saved);
}

void checkForceVersion14Metadata() {
    const std::string xmp =
        "<?xpacket begin=\"\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>"
        "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"/>"
        "<?xpacket end=\"w\"?>";
    std::string metaStream = "<< /Type /Metadata /Subtype /XML /Length " +
                             std::to_string(xmp.size()) + " >>\nstream\n" + xmp + "\nendstream";
    auto v17Pdf = assemblePdf("%PDF-1.7",
                              {
                                  "<< /Type /Catalog /Pages 2 0 R /Metadata 4 0 R >>",
                                  "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                                  "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] >>",
                                  metaStream,
                              },
                              1);
    uint8_t* out = nullptr;
    int64_t outLen = 0;
    CHECK(jpdfium_qpdf_force_version(v17Pdf.data(), static_cast<int64_t>(v17Pdf.size()), "1.4",
                                     &out, &outLen) == JPDFIUM_OK &&
              out && outLen > 8,
          "qpdf_force_version 1.4 succeeds");
    if (out && outLen > 8) {
        std::string raw(reinterpret_cast<const char*>(out), static_cast<size_t>(outLen));
        CHECK(raw.rfind("%PDF-1.4", 0) == 0, "force_version writes %PDF-1.4 header");
        CHECK(raw.find("/ObjStm") == std::string::npos, "force_version 1.4 avoids /ObjStm");
        CHECK(raw.find("<x:xmpmeta") != std::string::npos,
              "force_version 1.4 leaves /Metadata uncompressed");
        jpdfium_free_buffer(out);
    }
}

void runConformanceSafetyChecks() {
    checkSignatureNullSafety();
    checkPatternJsonEscaping();
    if (jpdfium_abi_query(JPDFIUM_ABI_QUERY_HAS_QPDF) != 1) return;
    checkPdfxPartialDates();
    checkSparseToUnicodeAndStructSanitize();
    checkForceVersion14Metadata();
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
        runRepairEdgeChecks();
        runConformanceSafetyChecks();
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
