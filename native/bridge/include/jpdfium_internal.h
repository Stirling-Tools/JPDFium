#pragma once
#include <fpdfview.h>

#include <array>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <memory_resource>
#include <string>
#include <unordered_map>
#include <vector>

#define JPDFIUM_OK (0)
#define JPDFIUM_ERR_INVALID (-1)
#define JPDFIUM_ERR_IO (-2)
#define JPDFIUM_ERR_PASSWORD (-3)
#define JPDFIUM_ERR_NOT_FOUND (-4)
#define JPDFIUM_ERR_REDACTED_SAVE (-5)        // incremental save refused after redaction
#define JPDFIUM_ERR_UNCOMMITTED_MARKS (-6)    // save refused: uncommitted REDACT annotations
#define JPDFIUM_ERR_REDACT_INCOMPLETE (-7)    // post-redaction audit found remaining text
#define JPDFIUM_ERR_REDACT_UNVERIFIABLE (-8)  // redaction could not run/verify (no silent degrade)
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

// A page-space redaction rectangle (PDF coords, y up).
struct RedactZone {
    int32_t pageIndex = -1;
    float left = 0, bottom = 0, right = 0, top = 0;
};

// Shared document core. Owns the FPDF_DOCUMENT handle and all document-scoped
// redaction bookkeeping. Both DocWrapper and every PageWrapper hold a
// shared_ptr: closing the DocWrapper while pages are still open must not
// leave pages with a dangling owner, and PDFium requires every page to be
// closed BEFORE the document (FPDF_CloseDocument with open pages is UB), so
// the document handle is only closed when the LAST reference (doc wrapper or
// any page wrapper) is released.
struct DocCore {
    FPDF_DOCUMENT doc = nullptr;

    // Set when any content-removing redaction mutated the page content.
    // An incremental save would append a new revision and keep the
    // un-redacted original revision recoverable in the file body, so it is
    // refused once this flag is set.
    bool contentRedacted = false;

    // Set when REDACT annotations were created (mark phase) and not yet
    // committed. Saving in this state ships a document whose text is fully
    // intact under the marks - the classic "marked but never applied" leak.
    // Tracked as a document-scoped counter so that committing or clearing
    // marks on one page does not clear the flag while other pages still have
    // live uncommitted marks.
    int32_t unappliedRedactMarksCount = 0;

    // ---- sanitize-stage bookkeeping (consumed by jpdfium_sanitize.cpp) ----
    // Words/patterns (UTF-8) used for redaction: metadata, annotation text,
    // outline titles and form-field values containing these are scrubbed on
    // every redacted save.
    std::vector<std::string> redactedLiterals{};
    // Page-space rectangles of every committed redaction: annotations that
    // intersect them are removed on save (their text may echo the redacted
    // content).
    std::vector<RedactZone> redactZones{};
    // BaseFont names of fonts whose text objects were redaction-touched:
    // their font programs are re-subset on save (hb-subset) so the redacted
    // glyph outlines cannot be recovered from the file (ACSC remnant class).
    std::vector<std::string> touchedFontNames{};
    // JSON report of the last sanitize run ("" = sanitize has not run).
    std::string sanitizeReport{};

    bool hasUnappliedRedactMarks() const {
        return unappliedRedactMarksCount > 0;
    }

    void addRedactLiteral(const char* s) {
        if (!s || !*s) return;
        std::string lit(s);
        for (const auto& e : redactedLiterals)
            if (e == lit) return;
        redactedLiterals.push_back(std::move(lit));
    }
    void addRedactZone(int32_t pageIndex, float l, float b, float r, float t) {
        redactZones.push_back(RedactZone{pageIndex, l, b, r, t});
    }
    void addTouchedFont(const char* name) {
        if (!name || !*name) return;
        std::string fn(name);
        for (const auto& e : touchedFontNames)
            if (e == fn) return;
        touchedFontNames.push_back(std::move(fn));
    }
};

inline std::shared_ptr<DocCore> makeDocCore(FPDF_DOCUMENT doc) {
    return std::shared_ptr<DocCore>(new DocCore{doc}, [](DocCore* c) {
        if (c->doc) {
            FPDF_CloseDocument(c->doc);
            c->doc = nullptr;
        }
        delete c;
    });
}

struct DocWrapper {
    std::shared_ptr<DocCore> core;  // document handle + redaction bookkeeping
    uint8_t* buf =
        nullptr;  // non-null when opened from bytes; PDFium requires it to outlive the doc
    int64_t blen = 0;

    ~DocWrapper() {
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
    int32_t pageIndex = -1;
    std::shared_ptr<DocCore> core;  // keeps the document (and bookkeeping) alive

    PageWrapper(FPDF_PAGE p, FPDF_DOCUMENT d, int32_t idx, std::shared_ptr<DocCore> o)
        : page(p), doc(d), pageIndex(idx), core(std::move(o)) {}
    PageWrapper(FPDF_PAGE p, FPDF_DOCUMENT d, std::shared_ptr<DocCore> o)
        : page(p), doc(d), pageIndex(-1), core(std::move(o)) {}

    ~PageWrapper() {
        if (page) {
            FPDF_ClosePage(page);
            page = nullptr;
        }
    }
};

// Mandatory qpdf sanitize stage for redacted saves (jpdfium_sanitize.cpp).
// Returns 0 and fills out/reportJson on success; -1 on failure.
int sanitizeRedactedPdf(const uint8_t* input, size_t inputLen, const DocCore& core,
                        std::vector<uint8_t>& out, std::string& reportJson);

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

// Scratch arena for redaction/crop hot paths. All per-call containers
// (vectors, maps, strings) allocate from a single monotonic buffer so a
// typical crop/redact performs zero heap allocations outside PDFium itself.
// The inline buffer covers small/medium pages; overflow spills to the default
// heap resource, which is still amortised to a handful of allocations.
class ScratchArena {
   public:
    ScratchArena() : mrb_(inlineBuf_.data(), inlineBuf_.size()) {}
    ScratchArena(const ScratchArena&) = delete;
    ScratchArena& operator=(const ScratchArena&) = delete;

    std::pmr::memory_resource* resource() noexcept {
        return &mrb_;
    }

   private:
    static constexpr std::size_t kInlineCapacity = 16 * 1024;
    std::array<std::byte, kInlineCapacity> inlineBuf_;
    std::pmr::monotonic_buffer_resource mrb_;
};

// Wraps an exported entry point so no C++ exception can ever cross the
// C ABI into a JNI/FFM downcall (which is undefined behaviour). Returns
// JPDFIUM_ERR_NATIVE on any exception, including std::bad_alloc.
template <typename Fn>
inline int32_t jpdfium_guarded(Fn&& fn) noexcept {
    try {
        return fn();
    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
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
