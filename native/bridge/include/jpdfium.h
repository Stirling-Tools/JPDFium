#pragma once
#include <stdint.h>

#ifdef _WIN32
  #define JPDFIUM_EXPORT __declspec(dllexport)
#else
  #define JPDFIUM_EXPORT __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

// Return codes. 0 = success; all negative values are errors.
#define JPDFIUM_OK              0
#define JPDFIUM_ERR_INVALID    -1
#define JPDFIUM_ERR_IO         -2
#define JPDFIUM_ERR_PASSWORD   -3
#define JPDFIUM_ERR_NOT_FOUND  -4
#define JPDFIUM_ERR_NATIVE     -99

JPDFIUM_EXPORT int32_t jpdfium_init(void);
JPDFIUM_EXPORT void    jpdfium_destroy(void);

JPDFIUM_EXPORT int32_t jpdfium_doc_open(const char* path, int64_t* handle);
JPDFIUM_EXPORT int32_t jpdfium_doc_open_bytes(const uint8_t* data, int64_t len, int64_t* handle);
JPDFIUM_EXPORT int32_t jpdfium_doc_open_protected(const char* path, const char* password, int64_t* handle);
JPDFIUM_EXPORT int32_t jpdfium_doc_page_count(int64_t doc, int32_t* count);
JPDFIUM_EXPORT int32_t jpdfium_doc_save(int64_t doc, const char* path);
JPDFIUM_EXPORT int32_t jpdfium_doc_save_bytes(int64_t doc, uint8_t** data, int64_t* len);
JPDFIUM_EXPORT void    jpdfium_doc_close(int64_t doc);

JPDFIUM_EXPORT int32_t jpdfium_page_open(int64_t doc, int32_t idx, int64_t* handle);
JPDFIUM_EXPORT int32_t jpdfium_page_width(int64_t page, float* width);
JPDFIUM_EXPORT int32_t jpdfium_page_height(int64_t page, float* height);
JPDFIUM_EXPORT void    jpdfium_page_close(int64_t page);

JPDFIUM_EXPORT int32_t jpdfium_render_page(int64_t page, int32_t dpi, uint8_t** rgba, int32_t* width, int32_t* height);
JPDFIUM_EXPORT void    jpdfium_free_buffer(uint8_t* buffer);

// Returns per-character data as a compact JSON array: [{i,u,x,y,w,h,font,size}, ...]
// The caller owns the returned string and must free it with jpdfium_free_string.
JPDFIUM_EXPORT int32_t jpdfium_text_get_chars(int64_t page, char** json);
JPDFIUM_EXPORT int32_t jpdfium_text_find(int64_t page, const char* query, char** json);
JPDFIUM_EXPORT void    jpdfium_free_string(char* str);

JPDFIUM_EXPORT int32_t jpdfium_redact_region(int64_t page, float x, float y, float w, float h, uint32_t argb, int32_t remove_content);
JPDFIUM_EXPORT int32_t jpdfium_redact_pattern(int64_t page, const char* pattern, uint32_t argb, int32_t remove_content);
JPDFIUM_EXPORT int32_t jpdfium_redact_words(int64_t page, const char** words, int32_t wordCount, uint32_t argb, float padding, int32_t wholeWord, int32_t useRegex, int32_t remove_content);
JPDFIUM_EXPORT int32_t jpdfium_redact_words_ex(int64_t page, const char** words, int32_t wordCount, uint32_t argb, float padding, int32_t wholeWord, int32_t useRegex, int32_t remove_content, int32_t caseSensitive, int32_t* matchCount);
JPDFIUM_EXPORT int32_t jpdfium_page_flatten(int64_t page);
JPDFIUM_EXPORT int32_t jpdfium_page_to_image(int64_t doc, int32_t pageIndex, int32_t dpi);

// Returns JSON: [{"i":0,"ox":10.1,"oy":20.2,"l":10.0,"r":18.0,"b":15.0,"t":27.0}, ...]
// Each element contains the character index, origin (ox,oy), and bounding box (l,r,b,t).
// Used by tests to verify that text positions are preserved after redaction.
JPDFIUM_EXPORT int32_t jpdfium_text_get_char_positions(int64_t page, char** json);

#ifdef __cplusplus
}
#endif
