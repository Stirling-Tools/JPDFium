// jpdfium_document.cpp - Library lifecycle, document and page management.

#include <fpdf_doc.h>
#include <fpdf_edit.h>
#include <fpdf_ppo.h>
#include <fpdf_save.h>
#include <fpdfview.h>

#include <cstddef>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#if !defined(_WIN32)
#include <fcntl.h>
#endif

#include "jpdfium.h"
#include "jpdfium_internal.h"

namespace {

FILE* safe_fopen_write(const char* path) {
#if defined(_WIN32)
    return std::fopen(path, "wb");
#else
    int fd = ::open(path, O_WRONLY | O_CREAT | O_TRUNC, 0600);
    if (fd < 0) return nullptr;
    return ::fdopen(fd, "wb");
#endif
}

// Direct-to-file FPDF_FILEWRITE sink: streams PDFium serialization blocks
// straight to a native FILE* with zero document-sized buffering. The callback
// is allocation-free, must not let exceptions escape, and enforces the byte
// budget inline so an unbounded save is aborted mid-write instead of after the
// fact.
//
// Failure model, so the caller can tell the stages apart:
//   - budget exhaustion / short write -> failed = true, write_errno untouched
//     for the budget case (no I/O error occurred), set from errno otherwise.
//   - first failure wins: once failed is set every later block short-circuits,
//     so PDFium sees the error and stops instead of writing more.
//   - fflush and fclose are checked separately by the caller, so a flush
//     failure is distinguishable from a close failure.
struct FileWriteContext : FPDF_FILEWRITE {
    FILE* file = nullptr;
    std::uint64_t bytes_written = 0;
    std::uint64_t max_bytes = 0;  // 0 = unlimited
    int write_errno = 0;
    bool failed = false;
    // True when the failure was the byte budget rather than the filesystem, so
    // callers can report "output too large" instead of "I/O error".
    bool budget_exceeded = false;

    // Named Write, not WriteBlock: a member function with the same name as the
    // inherited FPDF_FILEWRITE field would hide that field, and the
    // assignment `ctx.WriteBlock = FileWriteContext::WriteBlock` would then try
    // to assign a function to itself instead of to the inherited pointer.
    //
    // A C callback must not propagate exceptions across the ABI boundary, so
    // this is not marked noexcept: the operations below (fwrite, errno) are
    // C calls that cannot throw, and the body contains no throwing operation.
    static int Write(FPDF_FILEWRITE* writer, const void* data, unsigned long size) {
        auto* ctx = static_cast<FileWriteContext*>(writer);
        if (!ctx || ctx->failed || !ctx->file) return 0;
        // A zero-length block is legal and is not an error; PDFium may emit one
        // at the end of a stream.
        if (size == 0) return 1;
        if (!data) {
            ctx->failed = true;
            return 0;
        }
        if (ctx->max_bytes > 0) {
            // Subtraction form: never computes bytes_written + size, so the
            // cumulative total cannot overflow uint64_t.
            if (ctx->bytes_written >= ctx->max_bytes ||
                static_cast<std::uint64_t>(size) > ctx->max_bytes - ctx->bytes_written) {
                ctx->failed = true;
                ctx->budget_exceeded = true;
                return 0;
            }
        }
        const size_t written = std::fwrite(data, 1, size, ctx->file);
        if (written != size) {
            // Partial write: the destination now holds a prefix that is not a
            // valid PDF. Marking failed makes the caller discard the file
            // rather than publish it.
            ctx->failed = true;
            ctx->write_errno = errno;
            return 0;
        }
        ctx->bytes_written += size;
        return 1;
    }
};

}  // namespace

bool g_jpdfiumUseSkia = false;

namespace {
bool g_libraryInitialized = false;
}

int jpdfium_init_library(int renderer) {
    // Re-initialising would reset g_jpdfiumUseSkia while PDFium keeps the
    // renderer it was configured with, so the first selection wins.
    if (g_libraryInitialized) return JPDFIUM_OK;
    if (renderer != JPDFIUM_RENDERER_AUTO && renderer != JPDFIUM_RENDERER_AGG &&
        renderer != JPDFIUM_RENDERER_SKIA) {
        return JPDFIUM_ERR_INVALID;
    }
#ifdef JPDFIUM_HAS_SKIA
    // AUTO (-1) and SKIA (1) both pick Skia; AGG (0) forces the legacy path.
    bool useSkia = renderer != JPDFIUM_RENDERER_AGG;
    FPDF_LIBRARY_CONFIG config{};
    config.version = 4;
    config.m_RendererType = useSkia ? FPDF_RENDERERTYPE_SKIA : FPDF_RENDERERTYPE_AGG;
    FPDF_InitLibraryWithConfig(&config);
    g_jpdfiumUseSkia = useSkia;
#else
    // Selecting Skia from a build without it crashes PDFium, so refuse.
    if (renderer == JPDFIUM_RENDERER_SKIA) return JPDFIUM_ERR_INVALID;
    FPDF_InitLibrary();
    g_jpdfiumUseSkia = false;
#endif
    g_libraryInitialized = true;
    return JPDFIUM_OK;
}

void jpdfium_ensure_library() {
    if (!g_libraryInitialized) {
        jpdfium_init_library(JPDFIUM_RENDERER_AUTO);
    }
}

int32_t jpdfium_init() noexcept {
    return jpdfium_init_library(JPDFIUM_RENDERER_AUTO);
}

uint32_t jpdfium_abi_version() JPDFIUM_NOEXCEPT {
    return JPDFIUM_ABI_VERSION;
}

int64_t jpdfium_abi_query(int32_t query) JPDFIUM_NOEXCEPT {
    switch (query) {
        case JPDFIUM_ABI_QUERY_PTR_SIZE:
            return static_cast<int64_t>(sizeof(void*));
        case JPDFIUM_ABI_QUERY_RECTF_SIZE:
            return static_cast<int64_t>(sizeof(FS_RECTF));
        case JPDFIUM_ABI_QUERY_RECTF_RIGHT_OFFSET:
            return static_cast<int64_t>(offsetof(FS_RECTF, right));
        case JPDFIUM_ABI_QUERY_ULONG_SIZE:
            return static_cast<int64_t>(sizeof(unsigned long));
        case JPDFIUM_ABI_QUERY_RECTF_LEFT_OFFSET:
            return static_cast<int64_t>(offsetof(FS_RECTF, left));
        case JPDFIUM_ABI_QUERY_RECTF_BOTTOM_OFFSET:
            return static_cast<int64_t>(offsetof(FS_RECTF, bottom));
        case JPDFIUM_ABI_QUERY_RECTF_TOP_OFFSET:
            return static_cast<int64_t>(offsetof(FS_RECTF, top));
        case JPDFIUM_ABI_QUERY_MATRIX_SIZE:
            return static_cast<int64_t>(sizeof(FS_MATRIX));
        case JPDFIUM_ABI_QUERY_FILEWRITE_SIZE:
            return static_cast<int64_t>(sizeof(FPDF_FILEWRITE));
        case JPDFIUM_ABI_QUERY_FILEWRITE_VERSION:
            // FPDF_FILEWRITE.version must be 1 per fpdf_save.h; the save path
            // sets it explicitly and the handshake pins the expected value.
            return 1;
        case JPDFIUM_ABI_QUERY_HAS_SKIA:
#ifdef JPDFIUM_HAS_SKIA
            return 1;
#else
            return 0;
#endif
        case JPDFIUM_ABI_QUERY_HAS_QPDF:
#ifdef JPDFIUM_HAS_QPDF
            return 1;
#else
            return 0;
#endif
        default:
            return -1;
    }
}

int32_t jpdfium_init_ex(int32_t renderer) noexcept {
    return jpdfium_init_library(renderer);
}

int32_t jpdfium_active_renderer() noexcept {
    return g_jpdfiumUseSkia ? JPDFIUM_RENDERER_SKIA : JPDFIUM_RENDERER_AGG;
}

void jpdfium_destroy() noexcept {
    FPDF_DestroyLibrary();
    g_libraryInitialized = false;
    g_jpdfiumUseSkia = false;
}

int32_t jpdfium_doc_create(int64_t* handle) {
    try {
        if (!handle) return JPDFIUM_ERR_INVALID;
        FPDF_DOCUMENT doc = FPDF_CreateNewDocument();
        if (!doc) return JPDFIUM_ERR_NATIVE;

        auto w = std::make_unique<DocWrapper>();
        w->core = makeDocCore(doc);
        auto* raw = w.release();
        DocHandleRegistry::instance().add(raw);
        *handle = encodeHandle(raw);
        return JPDFIUM_OK;

    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

int32_t jpdfium_doc_open(const char* path, int64_t* handle) {
    try {
        if (!path || !handle) return JPDFIUM_ERR_INVALID;
        FPDF_DOCUMENT doc = FPDF_LoadDocument(path, nullptr);
        if (!doc) return translatePdfiumError();

        auto w = std::make_unique<DocWrapper>();
        // Record the source path so a later save can refuse to overwrite it.
        w->core = makeDocCore(doc, nullptr, 0, path);
        auto* raw = w.release();
        DocHandleRegistry::instance().add(raw);
        *handle = encodeHandle(raw);
        return JPDFIUM_OK;

    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

int32_t jpdfium_doc_open_bytes(const uint8_t* data, int64_t len, int64_t* handle) {
    try {
        // FPDF_LoadMemDocument takes a 32-bit int length; reject negative lengths
        // and documents larger than PDFium's API can address before copying.
        if (!data || !handle || len <= 0 || len > INT32_MAX) return JPDFIUM_ERR_INVALID;
        uint8_t* copy = static_cast<uint8_t*>(malloc(static_cast<size_t>(len)));
        if (!copy) return JPDFIUM_ERR_NATIVE;
        memcpy(copy, data, static_cast<size_t>(len));

        FPDF_DOCUMENT doc = FPDF_LoadMemDocument(copy, static_cast<int>(len), nullptr);
        if (!doc) {
            free(copy);
            return translatePdfiumError();
        }

        auto w = std::make_unique<DocWrapper>();
        w->core = makeDocCore(doc, copy, len);
        auto* raw = w.release();
        DocHandleRegistry::instance().add(raw);
        *handle = encodeHandle(raw);
        return JPDFIUM_OK;

    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

int32_t jpdfium_doc_open_bytes_protected(const uint8_t* data, int64_t len, const char* password,
                                         int64_t* handle) {
    try {
        if (!data || !handle || len <= 0 || len > INT32_MAX) return JPDFIUM_ERR_INVALID;
        uint8_t* copy = static_cast<uint8_t*>(malloc(static_cast<size_t>(len)));
        if (!copy) return JPDFIUM_ERR_NATIVE;
        memcpy(copy, data, static_cast<size_t>(len));

        FPDF_DOCUMENT doc = FPDF_LoadMemDocument(copy, static_cast<int>(len), password);
        if (!doc) {
            free(copy);
            return translatePdfiumError();
        }

        auto w = std::make_unique<DocWrapper>();
        w->core = makeDocCore(doc, copy, len);
        auto* raw = w.release();
        DocHandleRegistry::instance().add(raw);
        *handle = encodeHandle(raw);
        return JPDFIUM_OK;

    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

int32_t jpdfium_doc_open_protected(const char* path, const char* password, int64_t* handle) {
    try {
        if (!path || !handle) return JPDFIUM_ERR_INVALID;
        FPDF_DOCUMENT doc = FPDF_LoadDocument(path, password);
        if (!doc) return translatePdfiumError();

        auto w = std::make_unique<DocWrapper>();
        // Record the source path like jpdfium_doc_open so a later save refuses
        // to overwrite its own backing file (O_TRUNC would destroy the input
        // PDFium is still reading).
        w->core = makeDocCore(doc, nullptr, 0, path);
        auto* raw = w.release();
        DocHandleRegistry::instance().add(raw);
        *handle = encodeHandle(raw);
        return JPDFIUM_OK;

    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

int32_t jpdfium_doc_page_count(int64_t doc, int32_t* count) {
    try {
        DocWrapper* w = decodeDoc(doc);
        if (!w || !w->core || !w->core->doc) return JPDFIUM_ERR_INVALID;
        *count = FPDF_GetPageCount(w->core->doc);
        return JPDFIUM_OK;

    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

namespace {

// Opt-in sanitize stage: when sanitizeOnSave is enabled on a redacted document,
// runs a qpdf pass (dead-object purge, metadata/XMP/annotation/form/outline scrub,
// ToUnicode filtering, hb-subset font erasure).
int32_t applySanitizeStage(DocWrapper* w, std::vector<uint8_t>& bytes) {
    if (!w->core->contentRedacted || !w->core->sanitizeOnSave) return JPDFIUM_OK;
    std::vector<uint8_t> sanitized;
    std::string report;
    if (sanitizeRedactedPdf(bytes.data(), bytes.size(), *w->core, sanitized, report) != 0) {
        w->core->sanitizeReport = report;
        return JPDFIUM_ERR_REDACT_UNVERIFIABLE;
    }
    w->core->sanitizeReport = report;
    bytes = std::move(sanitized);
    return JPDFIUM_OK;
}

}  // namespace
int32_t jpdfium_doc_save_to_file(int64_t doc, const char* path, int64_t max_bytes,
                                 int64_t* out_bytes) {
    try {
        DocWrapper* w = decodeDoc(doc);
        if (!w || !w->core || !w->core->doc) return JPDFIUM_ERR_INVALID;
        if (!path || !*path) return JPDFIUM_ERR_INVALID;
        if (max_bytes < 0) return JPDFIUM_ERR_INVALID;
        if (w->core->hasUnappliedRedactMarks()) return JPDFIUM_ERR_UNCOMMITTED_MARKS;
        if (out_bytes) *out_bytes = 0;

        // Opt-in sanitize pass needs the whole serialization post-hoc, so it
        // keeps the buffered path. The common case streams with no large buffer.
        if (w->core->contentRedacted && w->core->sanitizeOnSave) {
            struct BufWriter : FPDF_FILEWRITE {
                std::vector<uint8_t> buf;
                static int Write(FPDF_FILEWRITE* self, const void* data, unsigned long size) {
                    auto* bw = static_cast<BufWriter*>(self);
                    try {
                        auto* src = static_cast<const uint8_t*>(data);
                        bw->buf.insert(bw->buf.end(), src, src + size);
                        return 1;
                    } catch (...) {
                        return 0;  // never let exceptions cross the C callback
                    }
                }
            } bw;
            bw.version = 1;
            bw.WriteBlock = BufWriter::Write;

            if (!FPDF_SaveAsCopy(w->core->doc, &bw, FPDF_NO_INCREMENTAL)) return JPDFIUM_ERR_IO;
            int32_t sanitizeRc = applySanitizeStage(w, bw.buf);
            if (sanitizeRc != JPDFIUM_OK) return sanitizeRc;
            if (max_bytes > 0 && static_cast<int64_t>(bw.buf.size()) > max_bytes)
                return JPDFIUM_ERR_IO;

            FILE* f = safe_fopen_write(path);
            if (!f) return JPDFIUM_ERR_IO;
            size_t written = bw.buf.empty() ? 0 : fwrite(bw.buf.data(), 1, bw.buf.size(), f);
            int closeRc = fclose(f);
            if (written != bw.buf.size() || closeRc != 0) {
                std::remove(path);
                return JPDFIUM_ERR_IO;
            }
            if (out_bytes) *out_bytes = static_cast<int64_t>(written);
            return JPDFIUM_OK;
        }

        // Alias protection: saving over the document's own backing file would
        // truncate the input PDFium is still reading from. Checked before the
        // output is opened, because O_TRUNC would destroy the source first.
        if (!w->core->sourcePath.empty() && w->core->sourcePath == path) {
            return JPDFIUM_ERR_INVALID;
        }

        FILE* f = safe_fopen_write(path);
        if (!f) return JPDFIUM_ERR_IO;

        FileWriteContext ctx;
        ctx.version = 1;
        ctx.WriteBlock = FileWriteContext::Write;
        ctx.file = f;
        ctx.max_bytes = max_bytes > 0 ? static_cast<std::uint64_t>(max_bytes) : 0;

        // PDFium's own outcome and the sink's are recorded separately: a save can
        // return true while the sink failed, or fail while everything written so
        // far was fine. Reporting only one of them would misattribute the cause.
        const int pdfium_ok = FPDF_SaveAsCopy(w->core->doc, &ctx, FPDF_NO_INCREMENTAL);
        const int flushRc = std::fflush(f);
        const int closeRc = std::fclose(f);
        ctx.file = nullptr;

        if (!pdfium_ok || ctx.failed || flushRc != 0 || closeRc != 0) {
            // Never publish a partial destination: a truncated PDF is worse than
            // no output, so the file is removed and the stage that failed is what
            // gets reported.
            std::remove(path);
            if (ctx.budget_exceeded) return JPDFIUM_ERR_TOO_LARGE;
            if (!pdfium_ok) return JPDFIUM_ERR_IO;
            if (flushRc != 0) return JPDFIUM_ERR_IO;  // flush failure
            if (closeRc != 0) return JPDFIUM_ERR_IO;  // close failure
            return JPDFIUM_ERR_IO;                    // sink failure
        }
        // An empty result is not a valid PDF, so treat it as a failure rather than
        // reporting a zero-byte success.
        if (ctx.bytes_written == 0) {
            std::remove(path);
            return JPDFIUM_ERR_IO;
        }
        if (out_bytes) *out_bytes = static_cast<int64_t>(ctx.bytes_written);
        return JPDFIUM_OK;
    } catch (...) {
        // Never let a C++ exception cross the FFM boundary; also remove any
        // partial file the failed save may have created.
        if (path && *path) std::remove(path);
        return JPDFIUM_ERR_NATIVE;
    }
}

int32_t jpdfium_doc_save(int64_t doc, const char* path) {
    return jpdfium_doc_save_to_file(doc, path, 0, nullptr);
}

int32_t jpdfium_doc_save_bytes(int64_t doc, uint8_t** data, int64_t* len) {
    DocWrapper* w = decodeDoc(doc);
    if (!w || !w->core || !w->core->doc) return JPDFIUM_ERR_INVALID;
    if (w->core->hasUnappliedRedactMarks()) return JPDFIUM_ERR_UNCOMMITTED_MARKS;

    struct BufWriter : FPDF_FILEWRITE {
        std::vector<uint8_t> buf;
        static int Write(FPDF_FILEWRITE* self, const void* data, unsigned long size) {
            auto* bw = static_cast<BufWriter*>(self);
            try {
                auto* src = static_cast<const uint8_t*>(data);
                bw->buf.insert(bw->buf.end(), src, src + size);
                return 1;
            } catch (...) {
                return 0;  // never let exceptions cross the C callback
            }
        }
    } bw;
    bw.version = 1;
    bw.WriteBlock = BufWriter::Write;

    if (!FPDF_SaveAsCopy(w->core->doc, &bw, FPDF_NO_INCREMENTAL)) return JPDFIUM_ERR_IO;

    int32_t sanitizeRc = applySanitizeStage(w, bw.buf);
    if (sanitizeRc != JPDFIUM_OK) return sanitizeRc;

    size_t sz = bw.buf.size();
    // malloc(0) is permitted to return NULL, which is indistinguishable from an
    // allocation failure; an empty save result is legitimate, so allocate one
    // byte for it.
    uint8_t* out = static_cast<uint8_t*>(malloc(sz ? sz : 1));
    if (!out) return JPDFIUM_ERR_NATIVE;
    if (sz) memcpy(out, bw.buf.data(), sz);
    *data = out;
    *len = static_cast<int64_t>(sz);
    return JPDFIUM_OK;
}

void jpdfium_doc_close(int64_t doc) noexcept {
    // Remove from the registry before deleting, and delete only what the
    // registry vouches for. Previously this was `delete decodeDoc(doc)` with an
    // unvalidated cast, so closing a bogus or already-closed handle reached
    // operator delete on an arbitrary address.
    DocWrapper* w = decodeDoc(doc);
    if (!w) return;
    DocHandleRegistry::instance().remove(w);
    delete w;
}

int32_t jpdfium_page_open(int64_t doc, int32_t idx, int64_t* handle) {
    try {
        DocWrapper* w = decodeDoc(doc);
        if (!w || !w->core || !w->core->doc) return JPDFIUM_ERR_INVALID;

        FPDF_PAGE page = FPDF_LoadPage(w->core->doc, idx);
        if (!page) return JPDFIUM_ERR_NOT_FOUND;

        auto* pw = new PageWrapper(page, w->core->doc, idx, w->core);
        PageHandleRegistry::instance().add(pw);
        *handle = encodeHandle(pw);
        return JPDFIUM_OK;

    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

int32_t jpdfium_page_width(int64_t page, float* width) {
    try {
        PageWrapper* pw = decodePage(page);
        if (!pw || !pw->page) return JPDFIUM_ERR_INVALID;
        *width = static_cast<float>(FPDF_GetPageWidth(pw->page));
        return JPDFIUM_OK;

    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

int32_t jpdfium_page_height(int64_t page, float* height) {
    try {
        PageWrapper* pw = decodePage(page);
        if (!pw || !pw->page) return JPDFIUM_ERR_INVALID;
        *height = static_cast<float>(FPDF_GetPageHeight(pw->page));
        return JPDFIUM_OK;

    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

int32_t jpdfium_page_info(int64_t page, float* width, float* height) {
    try {
        PageWrapper* pw = decodePage(page);
        if (!pw || !pw->page || !width || !height) return JPDFIUM_ERR_INVALID;
        *width = static_cast<float>(FPDF_GetPageWidth(pw->page));
        *height = static_cast<float>(FPDF_GetPageHeight(pw->page));
        return JPDFIUM_OK;

    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

void jpdfium_page_close(int64_t page) noexcept {
    // Validate against the registry before touching the pointer. Close must
    // still free the wrapper of a page whose document was replaced, so the
    // staleness check below deliberately does not gate the delete - but
    // membership does, because an unregistered address must never be deleted.
    PageWrapper* pw = reinterpret_cast<PageWrapper*>(static_cast<uintptr_t>(page));
    if (!pw || !PageHandleRegistry::instance().contains(pw)) return;
    if (pw->page) {
        // A pending progressive render holds this raw page identity in its
        // map; retire it before the page itself is freed so a later session
        // step or close can never operate on the freed identity. The stale
        // path must not call into PDFium (its document is gone), so it only
        // forgets the map entry and destroys the caller-owned bitmap.
        if (!pw->stale()) {
            jpdfium_render_abandon_progressive(pw->page);
        } else {
            jpdfium_render_forget_progressive(pw->page);
        }
    }
    PageHandleRegistry::instance().remove(pw);
    delete pw;
}

// JSON report of the last sanitize stage ("" when none has run).
int32_t jpdfium_doc_sanitize_report(int64_t doc, char** json) noexcept {
    DocWrapper* w = decodeDoc(doc);
    if (!w || !w->core || !json) return JPDFIUM_ERR_INVALID;
    const std::string& rep = w->core->sanitizeReport;
    char* out = static_cast<char*>(malloc(rep.size() + 1));
    if (!out) return JPDFIUM_ERR_NATIVE;
    memcpy(out, rep.data(), rep.size());
    out[rep.size()] = 0;
    *json = out;
    return JPDFIUM_OK;
}

int32_t jpdfium_doc_set_sanitize_on_save(int64_t doc, int32_t enable) noexcept {
    DocWrapper* w = decodeDoc(doc);
    if (!w || !w->core) return JPDFIUM_ERR_INVALID;
    w->core->sanitizeOnSave = (enable != 0);
    return JPDFIUM_OK;
}

int64_t jpdfium_doc_raw_handle(int64_t doc) noexcept {
    DocWrapper* w = decodeDoc(doc);
    return w && w->core && w->core->doc
               ? static_cast<int64_t>(reinterpret_cast<uintptr_t>(w->core->doc))
               : 0;
}

int64_t jpdfium_page_raw_handle(int64_t page) noexcept {
    PageWrapper* pw = decodePage(page);
    return pw && pw->page ? static_cast<int64_t>(reinterpret_cast<uintptr_t>(pw->page)) : 0;
}

int64_t jpdfium_page_doc_raw_handle(int64_t page) noexcept {
    PageWrapper* pw = decodePage(page);
    return pw && pw->doc ? static_cast<int64_t>(reinterpret_cast<uintptr_t>(pw->doc)) : 0;
}

int32_t jpdfium_import_n_pages_to_one(void* srcDoc, float outputWidth, float outputHeight,
                                      int32_t cols, int32_t rows, uint8_t** output,
                                      int64_t* outputLen) {
    if (!srcDoc || !output || !outputLen || cols < 1 || rows < 1) return JPDFIUM_ERR_INVALID;

    FPDF_DOCUMENT nupDoc =
        FPDF_ImportNPagesToOne(static_cast<FPDF_DOCUMENT>(srcDoc), outputWidth, outputHeight,
                               static_cast<size_t>(cols), static_cast<size_t>(rows));

    if (!nupDoc) return JPDFIUM_ERR_NATIVE;

    struct BufWriter : FPDF_FILEWRITE {
        std::vector<uint8_t> buf;
        static int Write(FPDF_FILEWRITE* self, const void* data, unsigned long size) {
            auto* bw = static_cast<BufWriter*>(self);
            try {
                auto* src = static_cast<const uint8_t*>(data);
                bw->buf.insert(bw->buf.end(), src, src + size);
                return 1;
            } catch (...) {
                return 0;  // never let exceptions cross the C callback
            }
        }
    } bw;
    bw.version = 1;
    bw.WriteBlock = BufWriter::Write;

    int ok = FPDF_SaveAsCopy(nupDoc, &bw, FPDF_NO_INCREMENTAL);
    FPDF_CloseDocument(nupDoc);

    if (!ok) return JPDFIUM_ERR_IO;

    size_t sz = bw.buf.size();
    uint8_t* out = static_cast<uint8_t*>(malloc(sz ? sz : 1));
    if (!out) return JPDFIUM_ERR_NATIVE;
    if (sz) memcpy(out, bw.buf.data(), sz);
    *output = out;
    *outputLen = static_cast<int64_t>(sz);
    return JPDFIUM_OK;
}
