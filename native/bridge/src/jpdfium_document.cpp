// jpdfium_document.cpp - Library lifecycle, document and page management.

#include <fpdf_doc.h>
#include <fpdf_edit.h>
#include <fpdf_ppo.h>
#include <fpdf_save.h>
#include <fpdfview.h>

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

// Open a file for writing, restricting the permissions of a newly created
// file to owner read/write (0600) on POSIX so secrets written by the bridge
// (e.g. saved PDFs) are not world-readable. On Windows the CRT default ACL
// applies, as there is no POSIX mode argument.
FILE* safe_fopen_write(const char* path) {
#if defined(_WIN32)
    return std::fopen(path, "wb");
#else
    int fd = ::open(path, O_WRONLY | O_CREAT | O_TRUNC, 0600);
    if (fd < 0) return nullptr;
    return ::fdopen(fd, "wb");
#endif
}

}  // namespace

int32_t jpdfium_init() {
    FPDF_LIBRARY_CONFIG cfg{};
    cfg.version = 4;
    cfg.m_pUserFontPaths = nullptr;
    cfg.m_pIsolate = nullptr;
    cfg.m_v8EmbedderSlot = 0;
    cfg.m_pPlatform = nullptr;
#ifdef JPDFIUM_HAS_SKIA
    cfg.m_RendererType = FPDF_RENDERERTYPE_SKIA;
#else
    cfg.m_RendererType = FPDF_RENDERERTYPE_AGG;
#endif
    FPDF_InitLibraryWithConfig(&cfg);
    return JPDFIUM_OK;
}

void jpdfium_destroy() {
    FPDF_DestroyLibrary();
}

int32_t jpdfium_doc_create(int64_t* handle) {
    if (!handle) return JPDFIUM_ERR_INVALID;
    FPDF_DOCUMENT doc = FPDF_CreateNewDocument();
    if (!doc) return JPDFIUM_ERR_NATIVE;

    auto* w = new DocWrapper();
    w->core = makeDocCore(doc);
    *handle = encodeHandle(w);
    return JPDFIUM_OK;
}

int32_t jpdfium_doc_open(const char* path, int64_t* handle) {
    if (!path || !handle) return JPDFIUM_ERR_INVALID;
    FPDF_DOCUMENT doc = FPDF_LoadDocument(path, nullptr);
    if (!doc) return translatePdfiumError();

    auto* w = new DocWrapper();
    w->core = makeDocCore(doc);
    *handle = encodeHandle(w);
    return JPDFIUM_OK;
}

int32_t jpdfium_doc_open_bytes(const uint8_t* data, int64_t len, int64_t* handle) {
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

    auto* w = new DocWrapper();
    w->core = makeDocCore(doc);
    w->buf = copy;
    w->blen = len;
    *handle = encodeHandle(w);
    return JPDFIUM_OK;
}

int32_t jpdfium_doc_open_protected(const char* path, const char* password, int64_t* handle) {
    if (!path || !handle) return JPDFIUM_ERR_INVALID;
    FPDF_DOCUMENT doc = FPDF_LoadDocument(path, password);
    if (!doc) return translatePdfiumError();

    auto* w = new DocWrapper();
    w->core = makeDocCore(doc);
    *handle = encodeHandle(w);
    return JPDFIUM_OK;
}

int32_t jpdfium_doc_page_count(int64_t doc, int32_t* count) {
    DocWrapper* w = decodeDoc(doc);
    if (!w || !w->core || !w->core->doc) return JPDFIUM_ERR_INVALID;
    *count = FPDF_GetPageCount(w->core->doc);
    return JPDFIUM_OK;
}

namespace {

// Mandatory sanitize stage: every full save of a redacted document runs a
// qpdf pass (dead-object purge, metadata/XMP/annotation/form/outline scrub,
// ToUnicode filtering, hb-subset font erasure). Failure is a loud save
// refusal - a redacted document is never shipped without it.
int32_t applySanitizeStage(DocWrapper* w, std::vector<uint8_t>& bytes) {
    if (!w->core->contentRedacted) return JPDFIUM_OK;
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
int32_t jpdfium_doc_save(int64_t doc, const char* path) {
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

    FILE* f = safe_fopen_write(path);
    if (!f) return JPDFIUM_ERR_IO;
    size_t written = fwrite(bw.buf.data(), 1, bw.buf.size(), f);
    fclose(f);
    return written == bw.buf.size() ? JPDFIUM_OK : JPDFIUM_ERR_IO;
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
    uint8_t* out = static_cast<uint8_t*>(malloc(sz));
    if (!out) return JPDFIUM_ERR_NATIVE;
    memcpy(out, bw.buf.data(), sz);
    *data = out;
    *len = static_cast<int64_t>(sz);
    return JPDFIUM_OK;
}

void jpdfium_doc_close(int64_t doc) {
    delete decodeDoc(doc);
}

int32_t jpdfium_page_open(int64_t doc, int32_t idx, int64_t* handle) {
    DocWrapper* w = decodeDoc(doc);
    if (!w || !w->core || !w->core->doc) return JPDFIUM_ERR_INVALID;

    FPDF_PAGE page = FPDF_LoadPage(w->core->doc, idx);
    if (!page) return JPDFIUM_ERR_NOT_FOUND;

    *handle = encodeHandle(new PageWrapper(page, w->core->doc, idx, w->core));
    return JPDFIUM_OK;
}

int32_t jpdfium_page_width(int64_t page, float* width) {
    PageWrapper* pw = decodePage(page);
    if (!pw || !pw->page) return JPDFIUM_ERR_INVALID;
    *width = static_cast<float>(FPDF_GetPageWidth(pw->page));
    return JPDFIUM_OK;
}

int32_t jpdfium_page_height(int64_t page, float* height) {
    PageWrapper* pw = decodePage(page);
    if (!pw || !pw->page) return JPDFIUM_ERR_INVALID;
    *height = static_cast<float>(FPDF_GetPageHeight(pw->page));
    return JPDFIUM_OK;
}

void jpdfium_page_close(int64_t page) {
    delete decodePage(page);
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

int64_t jpdfium_doc_raw_handle(int64_t doc) {
    DocWrapper* w = decodeDoc(doc);
    return w && w->core && w->core->doc
               ? static_cast<int64_t>(reinterpret_cast<uintptr_t>(w->core->doc))
               : 0;
}

int64_t jpdfium_page_raw_handle(int64_t page) {
    PageWrapper* pw = decodePage(page);
    return pw && pw->page ? static_cast<int64_t>(reinterpret_cast<uintptr_t>(pw->page)) : 0;
}

int64_t jpdfium_page_doc_raw_handle(int64_t page) {
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
    uint8_t* out = static_cast<uint8_t*>(malloc(sz));
    if (!out) return JPDFIUM_ERR_NATIVE;
    memcpy(out, bw.buf.data(), sz);
    *output = out;
    *outputLen = static_cast<int64_t>(sz);
    return JPDFIUM_OK;
}
