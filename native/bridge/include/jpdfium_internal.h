#pragma once
#include <fpdf_edit.h>
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

struct RedactZone {
    int32_t pageIndex = -1;
    float left = 0, bottom = 0, right = 0, top = 0;
};

// Shared document state.
struct DocCore {
    FPDF_DOCUMENT doc = nullptr;
    bool contentRedacted = false;
    bool sanitizeOnSave = false;
    int32_t unappliedRedactMarksCount = 0;

    std::vector<std::string> redactedLiterals{};
    std::vector<RedactZone> redactZones{};
    std::vector<std::string> touchedFontNames{};
    std::string sanitizeReport{};
    std::vector<FPDF_FONT> loadedFonts{};

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

    uint8_t* buf = nullptr;
    int64_t blen = 0;
};

inline std::shared_ptr<DocCore> makeDocCore(FPDF_DOCUMENT doc, uint8_t* buf = nullptr,
                                            int64_t blen = 0) {
    auto* core = new DocCore();
    core->doc = doc;
    core->buf = buf;
    core->blen = blen;
    return std::shared_ptr<DocCore>(core, [](DocCore* c) {
        for (FPDF_FONT f : c->loadedFonts) {
            FPDFFont_Close(f);
        }
        if (c->doc) {
            FPDF_CloseDocument(c->doc);
            c->doc = nullptr;
        }
        if (c->buf) {
            free(c->buf);
            c->buf = nullptr;
        }
        delete c;
    });
}

struct DocWrapper {
    std::shared_ptr<DocCore> core;

    DocWrapper() = default;
    DocWrapper(const DocWrapper&) = delete;
    DocWrapper& operator=(const DocWrapper&) = delete;
};

struct PageWrapper {
    FPDF_PAGE page = nullptr;
    FPDF_DOCUMENT doc = nullptr;  // non-owning reference; needed by page-level APIs that also
                                  // require the document
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

template <typename Fn>
inline int32_t jpdfium_guarded(Fn&& fn) noexcept {
    try {
        return fn();
    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

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
