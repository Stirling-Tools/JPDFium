// Stub implementation - returns hardcoded valid data so the Java layer can be
// tested before the real PDFium native library is compiled.
#include "jpdfium.h"
#include <cstring>
#include <cstdlib>

int32_t jpdfium_init()    { return JPDFIUM_OK; }
void    jpdfium_destroy() {}

int32_t jpdfium_doc_open(const char*, int64_t* handle) {
    *handle = 12345LL;
    return JPDFIUM_OK;
}
int32_t jpdfium_doc_open_bytes(const uint8_t*, int64_t, int64_t* handle) {
    *handle = 12345LL;
    return JPDFIUM_OK;
}
int32_t jpdfium_doc_open_protected(const char*, const char*, int64_t* handle) {
    *handle = 12345LL;
    return JPDFIUM_OK;
}
int32_t jpdfium_doc_page_count(int64_t, int32_t* count) {
    *count = 3;
    return JPDFIUM_OK;
}
int32_t jpdfium_doc_save(int64_t, const char*)  { return JPDFIUM_OK; }
int32_t jpdfium_doc_save_bytes(int64_t, uint8_t** data, int64_t* len) {
    const char* stub = "%PDF-1.4 stub";
    *len  = (int64_t)strlen(stub);
    *data = (uint8_t*)strdup(stub);
    return JPDFIUM_OK;
}
void jpdfium_doc_close(int64_t) {}

int32_t jpdfium_page_open(int64_t, int32_t, int64_t* handle) {
    *handle = 99999LL;
    return JPDFIUM_OK;
}
int32_t jpdfium_page_width(int64_t, float* w)  { *w = 595.0f; return JPDFIUM_OK; }
int32_t jpdfium_page_height(int64_t, float* h) { *h = 842.0f; return JPDFIUM_OK; }
void    jpdfium_page_close(int64_t) {}

int32_t jpdfium_render_page(int64_t, int32_t, uint8_t** rgba, int32_t* w, int32_t* h) {
    *w = 100; *h = 100;
    *rgba = (uint8_t*)calloc(100 * 100 * 4, 1);
    return JPDFIUM_OK;
}
void jpdfium_free_buffer(uint8_t* buf) { free(buf); }

int32_t jpdfium_text_get_chars(int64_t, char** json) {
    const char* s = "[{\"i\":0,\"u\":72,\"x\":10.0,\"y\":800.0,\"w\":10.0,\"h\":12.0,"
                    "\"font\":\"Helvetica\",\"size\":12.0}]";
    *json = strdup(s);
    return JPDFIUM_OK;
}
int32_t jpdfium_text_find(int64_t, const char*, char** json) {
    *json = strdup("[]");
    return JPDFIUM_OK;
}
void jpdfium_free_string(char* s) { free(s); }

int32_t jpdfium_redact_region(int64_t, float, float, float, float, uint32_t, int32_t) {
    return JPDFIUM_OK;
}
int32_t jpdfium_redact_pattern(int64_t, const char*, uint32_t, int32_t) {
    return JPDFIUM_OK;
}
int32_t jpdfium_redact_words(int64_t, const char**, int32_t, uint32_t, float, int32_t, int32_t, int32_t) {
    return JPDFIUM_OK;
}
int32_t jpdfium_redact_words_ex(int64_t, const char**, int32_t, uint32_t, float, int32_t, int32_t, int32_t, int32_t, int32_t* matchCount) {
    if (matchCount) *matchCount = 0;
    return JPDFIUM_OK;
}
int32_t jpdfium_page_flatten(int64_t) { return JPDFIUM_OK; }
int32_t jpdfium_page_to_image(int64_t, int32_t, int32_t) { return JPDFIUM_OK; }

int32_t jpdfium_text_get_char_positions(int64_t, char** json) {
    const char* s = "[{\"i\":0,\"u\":72,\"ox\":10.0,\"oy\":800.0,\"l\":10.0,\"r\":20.0,"
                    "\"b\":788.0,\"t\":800.0}]";
    *json = strdup(s);
    return JPDFIUM_OK;
}
