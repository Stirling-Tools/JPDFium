// jpdfium_unicode.cpp - Unicode utilities and content hashing.
//
// Libraries used:
//   simdutf   (MIT)  - SIMD-accelerated UTF-8/16/32 transcoding
//   utf8proc  (MIT)  - NFC normalisation, case folding, grapheme clusters
//   xxHash    (BSD)  - XXH3 non-cryptographic hashing (approximately 80 GB/s)

#include "jpdfium.h"
#include "jpdfium_internal.h"

#include <fpdfview.h>
#include <fpdf_text.h>
#include <fpdf_edit.h>

#include <cstdlib>
#include <cstring>
#include <cstdio>
#include <string>
#include <sstream>
#include <vector>

// ---------------------------------------------------------------------------
// simdutf - SIMD-accelerated UTF-8/16/32 transcoding (header-only).
// The actual UTF-8-UTF-16 conversion uses ICU4C's u_strFromUTF8 because
// simdutf's runtime dispatch doesn't export linkable symbols in shared libs.
// ---------------------------------------------------------------------------
#include "simdutf.h"

// ---------------------------------------------------------------------------
// utf8proc - single-file C library; no special defines needed.
// ---------------------------------------------------------------------------
extern "C" {
#include "utf8proc.h"
}

// ---------------------------------------------------------------------------
// xxHash - fully inline when XXH_INLINE_ALL is defined.
// ---------------------------------------------------------------------------
#define XXH_INLINE_ALL
#include "xxhash.h"

#include <unicode/ustring.h>

// ============================================================================
// Internal helpers
// ============================================================================

// UTF-8 - UTF-16LE (replaces the manual loop in text/redact TUs).
std::vector<uint16_t> simdutf_utf8_to_utf16le(const char* utf8, size_t len) {
    if (!utf8 || len == 0) return {0};
    UErrorCode status = U_ZERO_ERROR;
    int32_t destLen = 0;
    u_strFromUTF8(nullptr, 0, &destLen, utf8, static_cast<int32_t>(len), &status);
    status = U_ZERO_ERROR;
    std::vector<UChar> buf(destLen + 1, 0);
    u_strFromUTF8(buf.data(), destLen + 1, &destLen, utf8, static_cast<int32_t>(len), &status);
    return {buf.begin(), buf.begin() + destLen + 1};
}

// ============================================================================
// Exported: Unicode utilities (utf8proc)
// ============================================================================

// Normalise a UTF-8 string to NFC (canonical decomposition + composition).
// Essential before pattern matching - catches composed vs. decomposed forms of
// the same character that would otherwise silently escape redaction patterns.
// Returns a malloc'd string; caller must free with jpdfium_free_string.
extern "C" JPDFIUM_EXPORT char* jpdfium_unicode_nfc(const char* utf8) {
    if (!utf8) return nullptr;
    uint8_t* result = nullptr;
    utf8proc_ssize_t r = utf8proc_map(
        reinterpret_cast<const uint8_t*>(utf8), 0, &result,
        static_cast<utf8proc_option_t>(UTF8PROC_COMPOSE | UTF8PROC_STABLE));
    if (r < 0) return nullptr;
    return reinterpret_cast<char*>(result);   // utf8proc uses libc malloc
}

// Case-fold a UTF-8 string (locale-insensitive, Unicode-aware).
// Combines CASEFOLD + NFC normalisation in one pass - ready for
// case-insensitive search or redaction matching.
// Returns a malloc'd string; caller must free with jpdfium_free_string.
extern "C" JPDFIUM_EXPORT char* jpdfium_unicode_casefold(const char* utf8) {
    if (!utf8) return nullptr;
    uint8_t* result = nullptr;
    utf8proc_ssize_t r = utf8proc_map(
        reinterpret_cast<const uint8_t*>(utf8), 0, &result,
        static_cast<utf8proc_option_t>(
            UTF8PROC_COMPOSE | UTF8PROC_STABLE | UTF8PROC_CASEFOLD));
    if (r < 0) return nullptr;
    return reinterpret_cast<char*>(result);
}

// ============================================================================
// Exported: Content hashing (xxHash XXH3)
// ============================================================================

// Hash an arbitrary byte buffer with XXH3-64 (~80 GB/s on modern hardware).
// Typical uses: font deduplication, image deduplication, cache-key generation.
extern "C" JPDFIUM_EXPORT uint64_t jpdfium_xxh3_64(const uint8_t* data, uint64_t len) {
    if (!data || len == 0) return 0;
    return XXH3_64bits(data, static_cast<size_t>(len));
}

// Hash the visual content of a page at 36 DPI as a fast change-detection key.
// If the page content is unchanged between calls the hash is stable.
// Returns 0 on failure.
extern "C" JPDFIUM_EXPORT uint64_t jpdfium_page_content_hash(int64_t page) {
    PageWrapper* pw = decodePage(page);
    if (!pw || !pw->page) return 0;

    // Render at low DPI - just enough for reliable change detection, very fast.
    const int dpi    = 36;
    const double w72 = FPDF_GetPageWidth(pw->page);
    const double h72 = FPDF_GetPageHeight(pw->page);
    int w = static_cast<int>(w72 / 72.0 * dpi);
    int h = static_cast<int>(h72 / 72.0 * dpi);
    if (w <= 0) w = 1;
    if (h <= 0) h = 1;

    FPDF_BITMAP bmp = FPDFBitmap_Create(w, h, 0 /* no alpha */);
    if (!bmp) return 0;
    FPDFBitmap_FillRect(bmp, 0, 0, w, h, 0xFFFFFFFF);
    FPDF_RenderPageBitmap(bmp, pw->page, 0, 0, w, h, 0, 0);

    const void* buf    = FPDFBitmap_GetBuffer(bmp);
    int         stride = FPDFBitmap_GetStride(bmp);
    uint64_t    hash   = XXH3_64bits(buf, static_cast<size_t>(stride) * h);
    FPDFBitmap_Destroy(bmp);
    return hash;
}

// Hash every distinct font stream on a page and return as JSON.
// Format: [{"index":0,"name":"F1","hash":"0x1A2B3C4D5E6F7A8B","bytes":12345}, ...]
// Returns JPDFIUM_ERR_INVALID if the page is invalid.
// Caller must free *json with jpdfium_free_string.
extern "C" JPDFIUM_EXPORT int32_t jpdfium_page_font_hashes(int64_t page, char** json) {
    PageWrapper* pw = decodePage(page);
    if (!pw || !pw->page || !json) return JPDFIUM_ERR_INVALID;

    FPDF_TEXTPAGE tp = FPDFText_LoadPage(pw->page);
    if (!tp) return JPDFIUM_ERR_NATIVE;

    // Collect unique font names by scanning text objects on the page.
    int obj_count = FPDFPage_CountObjects(pw->page);
    std::ostringstream os;
    os << "[";
    bool first = true;
    int font_idx = 0;

    for (int i = 0; i < obj_count; i++) {
        FPDF_PAGEOBJECT obj = FPDFPage_GetObject(pw->page, i);
        if (!obj || FPDFPageObj_GetType(obj) != FPDF_PAGEOBJ_TEXT) continue;

        FPDF_FONT font = FPDFTextObj_GetFont(obj);
        if (!font) continue;

        // Get font name
        char name_buf[256] = {};
        FPDFFont_GetBaseFontName(font, name_buf, sizeof(name_buf));

        // Extract raw font data for hashing
        size_t font_bytes = 0;
        FPDFFont_GetFontData(font, nullptr, 0, &font_bytes);
        uint64_t hash = 0;
        if (font_bytes > 0) {
            std::vector<uint8_t> fdata(font_bytes);
            FPDFFont_GetFontData(font, fdata.data(), font_bytes, &font_bytes);
            hash = XXH3_64bits(fdata.data(), font_bytes);
        }

        char hash_str[32];
        snprintf(hash_str, sizeof(hash_str), "0x%016llX",
                 static_cast<unsigned long long>(hash));

        if (!first) os << ",";
        first = false;
        os << "{\"index\":" << font_idx++
           << ",\"name\":\"" << name_buf << "\""
           << ",\"hash\":\"" << hash_str << "\""
           << ",\"bytes\":" << font_bytes << "}";
    }
    os << "]";

    FPDFText_ClosePage(tp);

    std::string s = os.str();
    char* out = static_cast<char*>(malloc(s.size() + 1));
    if (!out) return JPDFIUM_ERR_NATIVE;
    memcpy(out, s.c_str(), s.size() + 1);
    *json = out;
    return JPDFIUM_OK;
}
