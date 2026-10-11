// jpdfium_ocr.h - the optional OCR module: PP-OCR models on ONNX Runtime, outside the PDFium
// domain. Handles share the core's table; errors are read with jpdf_last_error_message.
// See docs/design/OCR.md.
#pragma once
#include <stdint.h>

#include "jpdfium_api.h"

#if defined(_WIN32)
#if defined(JPDF_BUILDING_OCR)
#define JPDF_OCR_API __declspec(dllexport)
#else
#define JPDF_OCR_API __declspec(dllimport)
#endif
#else
#define JPDF_OCR_API __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

#define JPDF_OCR_API_VERSION_MAJOR 1
#define JPDF_OCR_API_VERSION_MINOR 0

#define JPDF_OCR_STRUCT_OPTIONS 1
#define JPDF_OCR_STRUCT_ENGINE_INFO 2
#define JPDF_OCR_STRUCT_RECOGNIZE_OPTIONS 3
#define JPDF_OCR_STRUCT_RESULT_INFO 4
#define JPDF_OCR_STRUCT_LINE 5

typedef struct jpdf_ocr_options {
    uint32_t struct_size;
    uint32_t flags;                // reserved, must be 0
    const char* profile_dir_utf8;  // holds profile.txt and every file it pins
    int32_t intra_op_threads;      // 0 lets ONNX Runtime choose
    int32_t reserved0;
} jpdf_ocr_options;

typedef struct jpdf_ocr_engine_info {
    uint32_t struct_size;
    int32_t reserved0;
    char profile_id[64];       // NUL-terminated
    char runtime_version[32];  // ONNX Runtime version, NUL-terminated
} jpdf_ocr_engine_info;

typedef struct jpdf_ocr_recognize_options {
    uint32_t struct_size;
    uint32_t flags;       // reserved, must be 0
    uint64_t max_pixels;  // input budget; 0 selects the library budget
} jpdf_ocr_recognize_options;

typedef struct jpdf_ocr_result_info {
    uint32_t struct_size;
    int32_t line_count;
    int32_t image_width;
    int32_t image_height;
    double dpi_x;  // the bitmap's DPI, 0 when unknown
    double dpi_y;
} jpdf_ocr_result_info;

// Word geometry: the recognizer reports lines only; words are never invented by splitting.
#define JPDF_OCR_WORDS_ABSENT 0

typedef struct jpdf_ocr_line {
    uint32_t struct_size;
    int32_t word_geometry;  // JPDF_OCR_WORDS_ABSENT
    double polygon[8];      // x0,y0..x3,y3 clockwise from top-left, source image pixels
    double confidence;      // mean probability of the decoded characters, 0..1
    double detection_score;
} jpdf_ocr_line;

// (major << 16) | minor of the loaded module.
JPDF_OCR_API uint32_t jpdf_ocr_api_version(void) JPDF_NOEXCEPT;
// sizeof the named JPDF_OCR_STRUCT_*, or -1.
JPDF_OCR_API int64_t jpdf_ocr_abi_struct_size(int32_t struct_id) JPDF_NOEXCEPT;
// Readiness of "ocr.recognize" or "ocr.word_geometry"; unknown names report JPDF_CAP_ABSENT.
JPDF_OCR_API jpdf_status jpdf_ocr_capability(const char* name, int32_t* out_level) JPDF_NOEXCEPT;

// Verifies every profile file against its pinned digest, then loads both models.
JPDF_OCR_API jpdf_status jpdf_ocr_engine_open(const jpdf_ocr_options* options,
                                              jpdf_handle* out_engine) JPDF_NOEXCEPT;
JPDF_OCR_API jpdf_status jpdf_ocr_engine_get_info(jpdf_handle engine,
                                                  jpdf_ocr_engine_info* out) JPDF_NOEXCEPT;
// Running recognitions keep the engine alive until they finish.
JPDF_OCR_API jpdf_status jpdf_ocr_engine_close(jpdf_handle engine) JPDF_NOEXCEPT;

// Reads the bitmap's pixels, so the bitmap can be closed afterwards. options may be NULL.
JPDF_OCR_API jpdf_status jpdf_ocr_recognize(jpdf_handle engine, jpdf_handle bitmap,
                                            const jpdf_ocr_recognize_options* options,
                                            jpdf_handle* out_result) JPDF_NOEXCEPT;
JPDF_OCR_API jpdf_status jpdf_ocr_result_get_info(jpdf_handle result,
                                                  jpdf_ocr_result_info* out) JPDF_NOEXCEPT;
// Line index in reading order. The text is copied as UTF-8; required includes the NUL and a
// NULL buffer only sizes it.
JPDF_OCR_API jpdf_status jpdf_ocr_result_get_line(jpdf_handle result, int32_t index,
                                                  jpdf_ocr_line* out, char* text, uint64_t capacity,
                                                  uint64_t* required) JPDF_NOEXCEPT;
JPDF_OCR_API jpdf_status jpdf_ocr_result_close(jpdf_handle result) JPDF_NOEXCEPT;

#ifdef __cplusplus
}
#endif
