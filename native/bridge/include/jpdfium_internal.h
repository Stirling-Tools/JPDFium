#pragma once
#include <fpdf_annot.h>
#include <fpdf_edit.h>
#include <fpdf_text.h>
#include <fpdfview.h>

#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <string>
#include <string_view>
#include <type_traits>
#include <unordered_map>
#include <unordered_set>
#include <vector>

struct TransparentStringHash {
    using is_transparent = void;
    size_t operator()(std::string_view sv) const noexcept {
        return std::hash<std::string_view>{}(sv);
    }
};

struct TransparentStringEqual {
    using is_transparent = void;
    bool operator()(std::string_view a, std::string_view b) const noexcept {
        return a == b;
    }
};

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
    // Structural generation: bumped whenever native state is freed and
    // replaced (document reload, page raster replacement). Pages capture it
    // at open; a mismatch means their FPDF_PAGE outlived its document.
    int64_t generation = 0;

    std::unordered_set<std::string, TransparentStringHash, TransparentStringEqual>
        redactedLiteralsSet{};
    std::vector<std::string> redactedLiterals{};
    std::vector<RedactZone> redactZones{};
    std::unordered_set<std::string, TransparentStringHash, TransparentStringEqual>
        touchedFontsSet{};
    std::vector<std::string> touchedFontNames{};
    std::string sanitizeReport{};
    std::vector<FPDF_FONT> loadedFonts{};

    void* redactPatternCache = nullptr;
    void (*redactPatternDeleter)(void*) = nullptr;

    ~DocCore() {
        clearRedactPatternCache();
    }

    void clearRedactPatternCache() {
        if (redactPatternCache && redactPatternDeleter) {
            redactPatternDeleter(redactPatternCache);
            redactPatternCache = nullptr;
        }
    }

    bool hasUnappliedRedactMarks() const {
        return unappliedRedactMarksCount > 0;
    }

    void addRedactLiteral(const char* s) {
        if (!s || !*s) return;
        std::string_view sv(s);
        if (redactedLiteralsSet.find(sv) != redactedLiteralsSet.end()) return;
        std::string lit(s);
        redactedLiteralsSet.insert(lit);
        redactedLiterals.push_back(std::move(lit));
    }
    void addRedactZone(int32_t pageIndex, float l, float b, float r, float t) {
        redactZones.push_back(RedactZone{pageIndex, l, b, r, t});
    }
    void addTouchedFont(const char* name) {
        if (!name || !*name) return;
        std::string_view sv(name);
        if (touchedFontsSet.find(sv) != touchedFontsSet.end()) return;
        std::string fn(name);
        touchedFontsSet.insert(fn);
        touchedFontNames.push_back(std::move(fn));
    }

    uint8_t* buf = nullptr;
    int64_t blen = 0;
};

inline std::shared_ptr<DocCore> makeDocCore(FPDF_DOCUMENT doc, uint8_t* buf = nullptr,
                                            int64_t blen = 0) {
    // Own the document and buffer until DocCore takes them: `new DocCore()` and
    // the shared_ptr construction below can both throw, and the caller has no
    // handle to clean up with at that point.
    struct PendingDoc {
        FPDF_DOCUMENT doc;
        uint8_t* buf;
        ~PendingDoc() {
            if (doc) FPDF_CloseDocument(doc);
            if (buf) free(buf);
        }
    } pending{doc, buf};

    auto* core = new DocCore();
    core->doc = pending.doc;
    core->buf = pending.buf;
    core->blen = blen;
    pending.doc = nullptr;
    pending.buf = nullptr;
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
    // DocCore generation at open. A mismatch means the document was freed and
    // replaced after this page was created, so page points into dead state.
    int64_t generation = 0;

    PageWrapper(FPDF_PAGE p, FPDF_DOCUMENT d, int32_t idx, std::shared_ptr<DocCore> o)
        : page(p), doc(d), pageIndex(idx), core(std::move(o)) {
        // Read back through the moved-to member: o itself is empty after move.
        generation = core->generation;
    }
    PageWrapper(FPDF_PAGE p, FPDF_DOCUMENT d, std::shared_ptr<DocCore> o)
        : page(p), doc(d), pageIndex(-1), core(std::move(o)) {
        generation = core->generation;
    }

    bool stale() const noexcept {
        return !core || !page || generation != core->generation;
    }

    ~PageWrapper() {
        // Never close a page whose document was already freed and replaced;
        // FPDF_ClosePage on it would be a use-after-free. The wrapper itself
        // is still destroyed, so only a few dozen bytes leak on misuse.
        if (page && !stale()) {
            FPDF_ClosePage(page);
            page = nullptr;
        }
    }
};

// True when a decoded page handle is live for its document generation.
inline bool pageAlive(PageWrapper* pw) {
    return pw && !pw->stale();
}

// Renderer selected at library init (0 = AGG, 1 = Skia). Skia is only
// selectable when the PDFium build includes it (JPDFIUM_HAS_SKIA); the
// render paths read this to pick the bitmap format and draw call.
extern bool g_jpdfiumUseSkia;

// Shared PDFium library init so every entry point that lazily initializes
// uses the same renderer configuration. Returns JPDFIUM_OK, or
// JPDFIUM_ERR_INVALID when Skia is requested from a non-Skia build.
int jpdfium_init_library(int renderer);

// Initialize once, preserving whatever renderer was already selected.
void jpdfium_ensure_library();

// Mandatory qpdf sanitize stage for redacted saves (jpdfium_sanitize.cpp).
// Returns 0 and fills out/reportJson on success; -1 on failure.
int sanitizeRedactedPdf(const uint8_t* input, size_t inputLen, const DocCore& core,
                        std::vector<uint8_t>& out, std::string& reportJson);

// Abandon a pending progressive render for a raw FPDF_PAGE without reporting
// (jpdfium_render.cpp). Called when the owning page is closed while a session
// is still pending so the map never retains a dangling page identity.
void jpdfium_render_abandon_progressive(void* fpdf_page) noexcept;

// Encode heap pointers as int64_t handles for the Java-visible ABI.
// The pointer stays alive until the matching close function deletes it.
inline DocWrapper* decodeDoc(int64_t h) {
    return reinterpret_cast<DocWrapper*>(static_cast<uintptr_t>(h));
}

// Decode a page handle, rejecting one whose document was freed and replaced.
// Returning nullptr here makes every handle-based entry point refuse a stale
// page (they all null-check the decoded pointer) instead of dereferencing
// freed PDFium state. jpdfium_page_close still deletes the wrapper, so the
// stale check must not be duplicated there.
inline PageWrapper* decodePage(int64_t h) {
    PageWrapper* pw = reinterpret_cast<PageWrapper*>(static_cast<uintptr_t>(h));
    return pageAlive(pw) ? pw : nullptr;
}

inline int64_t encodeHandle(void* p) {
    return static_cast<int64_t>(reinterpret_cast<uintptr_t>(p));
}

// RAII owners for PDFium handles and temporaries.
//
// Every acquisition below establishes its owner immediately, so a throwing
// operation between acquire and release cannot leak. Exception translation is a
// separate concern: jpdfium_guarded (below) converts a throw into a return code
// at the C boundary, and it is only correct on top of code whose resources
// already clean themselves up.
struct PageCloser {
    void operator()(FPDF_PAGE p) const noexcept {
        if (p) FPDF_ClosePage(p);
    }
};
struct TextPageCloser {
    void operator()(FPDF_TEXTPAGE p) const noexcept {
        if (p) FPDFText_ClosePage(p);
    }
};
struct SchCloser {
    void operator()(FPDF_SCHHANDLE s) const noexcept {
        if (s) FPDFText_FindClose(s);
    }
};
struct BitmapCloser {
    void operator()(FPDF_BITMAP b) const noexcept {
        if (b) FPDFBitmap_Destroy(b);
    }
};
struct PageObjectCloser {
    void operator()(FPDF_PAGEOBJECT o) const noexcept {
        if (o) FPDFPageObj_Destroy(o);
    }
};
struct AnnotCloser {
    void operator()(FPDF_ANNOTATION a) const noexcept {
        if (a) FPDFPage_CloseAnnot(a);
    }
};
struct FileCloser {
    void operator()(std::FILE* f) const noexcept {
        if (f) std::fclose(f);
    }
};

using UniquePage = std::unique_ptr<std::remove_pointer_t<FPDF_PAGE>, PageCloser>;
using UniqueTextPage = std::unique_ptr<std::remove_pointer_t<FPDF_TEXTPAGE>, TextPageCloser>;
using UniqueSch = std::unique_ptr<std::remove_pointer_t<FPDF_SCHHANDLE>, SchCloser>;
using UniqueBitmap = std::unique_ptr<std::remove_pointer_t<FPDF_BITMAP>, BitmapCloser>;
using UniquePageObject = std::unique_ptr<std::remove_pointer_t<FPDF_PAGEOBJECT>, PageObjectCloser>;
using UniqueAnnot = std::unique_ptr<std::remove_pointer_t<FPDF_ANNOTATION>, AnnotCloser>;
using UniqueFile = std::unique_ptr<std::FILE, FileCloser>;

// Scoped unlink for repair temp files. Untrusted input is written to these
// paths, so they must not survive the call - on success, on error, or on an
// exception thrown between creation and release.
class ScopedUnlink {
   public:
    explicit ScopedUnlink(const char* path) : path_(path) {}
    ~ScopedUnlink() {
        if (path_) std::remove(path_);
    }
    ScopedUnlink(const ScopedUnlink&) = delete;
    ScopedUnlink& operator=(const ScopedUnlink&) = delete;

    // Give up ownership (the file was already removed deliberately).
    void release() noexcept {
        path_ = nullptr;
    }

   private:
    const char* path_;
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
