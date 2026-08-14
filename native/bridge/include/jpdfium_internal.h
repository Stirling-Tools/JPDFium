#pragma once
#include <fpdfview.h>

#include <cstdint>
#include <cstdlib>
#include <cstring>

#define JPDFIUM_OK (0)
#define JPDFIUM_ERR_INVALID (-1)
#define JPDFIUM_ERR_IO (-2)
#define JPDFIUM_ERR_PASSWORD (-3)
#define JPDFIUM_ERR_NOT_FOUND (-4)
#define JPDFIUM_ERR_NATIVE (-99)

inline int translatePdfiumError() {
    switch (FPDF_GetLastError()) {
        case FPDF_ERR_SUCCESS:
            return JPDFIUM_OK;
        case FPDF_ERR_FILE:
            return JPDFIUM_ERR_IO;
        case FPDF_ERR_FORMAT:
            return JPDFIUM_ERR_INVALID;
        case FPDF_ERR_PASSWORD:
            return JPDFIUM_ERR_PASSWORD;
        case FPDF_ERR_PAGE:
            return JPDFIUM_ERR_NOT_FOUND;
        default:
            return JPDFIUM_ERR_NATIVE;
    }
}

struct DocWrapper {
    FPDF_DOCUMENT doc = nullptr;
    uint8_t* buf =
        nullptr;  // non-null when opened from bytes; PDFium requires it to outlive the doc
    int64_t blen = 0;

    ~DocWrapper() {
        if (doc) {
            FPDF_CloseDocument(doc);
            doc = nullptr;
        }
        if (buf) {
            free(buf);
            buf = nullptr;
        }
    }
};

struct PageWrapper {
    FPDF_PAGE page = nullptr;
    FPDF_DOCUMENT doc =
        nullptr;  // non-owning reference; needed by page-level APIs that also require the document

    PageWrapper(FPDF_PAGE p, FPDF_DOCUMENT d) : page(p), doc(d) {}

    ~PageWrapper() {
        if (page) {
            FPDF_ClosePage(page);
            page = nullptr;
        }
    }
};

// Encode heap pointers as int64_t handles for the Java-visible ABI.
// The pointer stays alive until the matching close function deletes it.
inline DocWrapper* decodeDoc(int64_t h) {
    return reinterpret_cast<DocWrapper*>(static_cast<uintptr_t>(h));
}
inline PageWrapper* decodePage(int64_t h) {
    return reinterpret_cast<PageWrapper*>(static_cast<uintptr_t>(h));
}

inline int64_t encodeHandle(void* p) {
    return static_cast<int64_t>(reinterpret_cast<uintptr_t>(p));
}

// Allocate a width*height RGBA buffer with overflow checks. Returns nullptr
// on overflow or allocation failure. The caller frees with free() (matching
// the jpdfium_free_buffer FFI convention).
inline uint8_t* allocRgbaChecked(int w, int h) {
    if (w <= 0 || h <= 0) return nullptr;
    size_t uw = static_cast<size_t>(w);
    size_t uh = static_cast<size_t>(h);
    if (uw > SIZE_MAX / uh / 4) return nullptr;
    return static_cast<uint8_t*>(malloc(uw * uh * 4));
}

// Image placement position (matches JPDFIUM_POSITION_* constants in jpdfium.h)
enum Position : std::uint8_t {
    POSITION_TOP_LEFT = 0,
    POSITION_TOP_CENTER = 1,
    POSITION_TOP_RIGHT = 2,
    POSITION_MIDDLE_LEFT = 3,
    POSITION_CENTER = 4,
    POSITION_MIDDLE_RIGHT = 5,
    POSITION_BOTTOM_LEFT = 6,
    POSITION_BOTTOM_CENTER = 7,
    POSITION_BOTTOM_RIGHT = 8
};
