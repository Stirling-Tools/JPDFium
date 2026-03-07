// jpdfium_document.cpp — Library lifecycle, document and page management.

#include "jpdfium.h"
#include "jpdfium_internal.h"

#include <fpdfview.h>
#include <fpdf_save.h>

#include <cstdlib>
#include <cstring>
#include <cstdio>
#include <string>
#include <vector>

int32_t jpdfium_init() {
    FPDF_LIBRARY_CONFIG cfg;
    cfg.version          = 2;
    cfg.m_pUserFontPaths = nullptr;
    cfg.m_pIsolate       = nullptr;
    cfg.m_v8EmbedderSlot = 0;
    FPDF_InitLibraryWithConfig(&cfg);
    return JPDFIUM_OK;
}

void jpdfium_destroy() {
    FPDF_DestroyLibrary();
}

int32_t jpdfium_doc_open(const char* path, int64_t* handle) {
    FPDF_DOCUMENT doc = FPDF_LoadDocument(path, nullptr);
    if (!doc) return translatePdfiumError();

    auto* w = new DocWrapper();
    w->doc  = doc;
    *handle = encodeHandle(w);
    return JPDFIUM_OK;
}

int32_t jpdfium_doc_open_bytes(const uint8_t* data, int64_t len, int64_t* handle) {
    uint8_t* copy = static_cast<uint8_t*>(malloc(static_cast<size_t>(len)));
    if (!copy) return JPDFIUM_ERR_NATIVE;
    memcpy(copy, data, static_cast<size_t>(len));

    FPDF_DOCUMENT doc = FPDF_LoadMemDocument(copy, static_cast<int>(len), nullptr);
    if (!doc) { free(copy); return translatePdfiumError(); }

    auto* w  = new DocWrapper();
    w->doc   = doc;
    w->buf   = copy;
    w->blen  = len;
    *handle  = encodeHandle(w);
    return JPDFIUM_OK;
}

int32_t jpdfium_doc_open_protected(const char* path, const char* password, int64_t* handle) {
    FPDF_DOCUMENT doc = FPDF_LoadDocument(path, password);
    if (!doc) return translatePdfiumError();

    auto* w = new DocWrapper();
    w->doc  = doc;
    *handle = encodeHandle(w);
    return JPDFIUM_OK;
}

int32_t jpdfium_doc_page_count(int64_t doc, int32_t* count) {
    DocWrapper* w = decodeDoc(doc);
    if (!w || !w->doc) return JPDFIUM_ERR_INVALID;
    *count = FPDF_GetPageCount(w->doc);
    return JPDFIUM_OK;
}

int32_t jpdfium_doc_save(int64_t doc, const char* path) {
    DocWrapper* w = decodeDoc(doc);
    if (!w || !w->doc) return JPDFIUM_ERR_INVALID;

    FILE* f = fopen(path, "wb");
    if (!f) return JPDFIUM_ERR_IO;

    struct FileWriter : FPDF_FILEWRITE {
        FILE* fp;
        static int Write(FPDF_FILEWRITE* self, const void* data, unsigned long size) {
            return fwrite(data, 1, size, static_cast<FileWriter*>(self)->fp) == size ? 1 : 0;
        }
    } fw;
    fw.version    = 1;
    fw.WriteBlock = FileWriter::Write;
    fw.fp         = f;

    int ok = FPDF_SaveAsCopy(w->doc, &fw, FPDF_NO_INCREMENTAL);
    fclose(f);
    return ok ? JPDFIUM_OK : JPDFIUM_ERR_IO;
}

int32_t jpdfium_doc_save_bytes(int64_t doc, uint8_t** data, int64_t* len) {
    DocWrapper* w = decodeDoc(doc);
    if (!w || !w->doc) return JPDFIUM_ERR_INVALID;

    struct BufWriter : FPDF_FILEWRITE {
        std::vector<uint8_t> buf;
        static int Write(FPDF_FILEWRITE* self, const void* data, unsigned long size) {
            auto* bw  = static_cast<BufWriter*>(self);
            auto* src = static_cast<const uint8_t*>(data);
            bw->buf.insert(bw->buf.end(), src, src + size);
            return 1;
        }
    } bw;
    bw.version    = 1;
    bw.WriteBlock = BufWriter::Write;

    if (!FPDF_SaveAsCopy(w->doc, &bw, FPDF_NO_INCREMENTAL)) return JPDFIUM_ERR_IO;

    size_t   sz  = bw.buf.size();
    uint8_t* out = static_cast<uint8_t*>(malloc(sz));
    if (!out) return JPDFIUM_ERR_NATIVE;
    memcpy(out, bw.buf.data(), sz);
    *data = out;
    *len  = static_cast<int64_t>(sz);
    return JPDFIUM_OK;
}

void jpdfium_doc_close(int64_t doc) {
    delete decodeDoc(doc);
}

int32_t jpdfium_page_open(int64_t doc, int32_t idx, int64_t* handle) {
    DocWrapper* w = decodeDoc(doc);
    if (!w || !w->doc) return JPDFIUM_ERR_INVALID;

    FPDF_PAGE page = FPDF_LoadPage(w->doc, idx);
    if (!page) return JPDFIUM_ERR_NOT_FOUND;

    *handle = encodeHandle(new PageWrapper(page, w->doc));
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

int64_t jpdfium_doc_raw_handle(int64_t doc) {
    DocWrapper* w = decodeDoc(doc);
    return w && w->doc ? static_cast<int64_t>(reinterpret_cast<uintptr_t>(w->doc)) : 0;
}

int64_t jpdfium_page_raw_handle(int64_t page) {
    PageWrapper* pw = decodePage(page);
    return pw && pw->page ? static_cast<int64_t>(reinterpret_cast<uintptr_t>(pw->page)) : 0;
}

int64_t jpdfium_page_doc_raw_handle(int64_t page) {
    PageWrapper* pw = decodePage(page);
    return pw && pw->doc ? static_cast<int64_t>(reinterpret_cast<uintptr_t>(pw->doc)) : 0;
}
