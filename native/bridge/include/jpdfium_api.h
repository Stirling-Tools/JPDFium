// jpdfium_api.h - safe, versioned C API shared by the Java and Python bindings.
// Contracts live in docs/design/NATIVE_ABI.md and docs/design/LIFETIME.md.
#pragma once
#include <stdint.h>

#if defined(_WIN32)
#if defined(JPDF_BUILDING_CORE)
#define JPDF_API __declspec(dllexport)
#else
#define JPDF_API __declspec(dllimport)
#endif
#else
#define JPDF_API __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
#define JPDF_NOEXCEPT noexcept
extern "C" {
#else
#define JPDF_NOEXCEPT
#endif

// API version: a binding requires an equal major and a minor >= the one it was compiled against.
#define JPDF_API_VERSION_MAJOR 1
#define JPDF_API_VERSION_MINOR 2

typedef int32_t jpdf_status;
// Opaque generation-checked token; 0 is never valid. The bit layout is private.
typedef uint64_t jpdf_handle;

#define JPDF_OK 0
#define JPDF_E_INVALID_ARGUMENT (-1)
#define JPDF_E_INVALID_HANDLE (-2)
#define JPDF_E_STALE_HANDLE (-3)
#define JPDF_E_WRONG_HANDLE_TYPE (-4)
#define JPDF_E_BUSY (-5)
#define JPDF_E_UNSUPPORTED (-6)
#define JPDF_E_IO (-7)
#define JPDF_E_PASSWORD (-8)
#define JPDF_E_FORMAT (-9)
#define JPDF_E_NOT_FOUND (-10)
#define JPDF_E_LIMIT (-11)
#define JPDF_E_NO_MEMORY (-12)
#define JPDF_E_BUFFER_TOO_SMALL (-13)
#define JPDF_E_NOT_INITIALIZED (-14)
#define JPDF_E_QUIESCING (-15)
#define JPDF_E_STOPPED (-16)
#define JPDF_E_FAILED (-17)
#define JPDF_E_AUTHORITY (-18)
#define JPDF_E_REENTRANT (-19)
#define JPDF_E_CANCELLED (-20)
#define JPDF_E_TIMEOUT (-21)
#define JPDF_E_INTERNAL (-99)

// Runtime lifecycle states reported by jpdf_runtime_get_info.
#define JPDF_STATE_UNINITIALIZED 0
#define JPDF_STATE_RUNNING 1
#define JPDF_STATE_QUIESCING 2
#define JPDF_STATE_DESTROYING 3
#define JPDF_STATE_STOPPED 4
#define JPDF_STATE_FAILED 5

#define JPDF_RENDERER_AUTO (-1)
#define JPDF_RENDERER_AGG 0
#define JPDF_RENDERER_SKIA 1

// Capability levels: implemented means callable, qualified means it passed its parity gate.
#define JPDF_CAP_ABSENT 0
#define JPDF_CAP_IMPLEMENTED 1
#define JPDF_CAP_QUALIFIED 2

// Struct ids for jpdf_abi_struct_size, so bindings can verify layouts at load time.
#define JPDF_STRUCT_RUNTIME_OPTIONS 1
#define JPDF_STRUCT_RUNTIME_INFO 2
#define JPDF_STRUCT_OPEN_OPTIONS 3
#define JPDF_STRUCT_PAGE_INFO 4
#define JPDF_STRUCT_RENDER_OPTIONS 5
#define JPDF_STRUCT_BITMAP_INFO 6
#define JPDF_STRUCT_BITMAP_VIEW 7
#define JPDF_STRUCT_WRITE_OPTIONS 8
#define JPDF_STRUCT_RASTER_OPTIONS 9
#define JPDF_STRUCT_RASTER_RESULT 10
#define JPDF_STRUCT_RECTIFY_OPTIONS 11
#define JPDF_STRUCT_RECTIFY_RESULT 12
#define JPDF_STRUCT_TEXT_WORD 13
#define JPDF_STRUCT_TEXT_LINE 14
#define JPDF_STRUCT_TEXT_LAYER_OPTIONS 15

typedef struct jpdf_runtime_options {
    uint32_t struct_size;
    uint32_t flags;  // reserved, must be 0
    int32_t renderer;
    int32_t reserved0;
} jpdf_runtime_options;

typedef struct jpdf_runtime_info {
    uint32_t struct_size;
    int32_t state;
    uint32_t api_major;
    uint32_t api_minor;
    int32_t renderer;      // -1 until initialized
    int32_t reserved0;     // written as 0
    const char* build_id;  // static, owned by the library
    uint64_t live_documents;
    uint64_t live_pages;
    uint64_t live_bitmaps;
    uint64_t live_views;
    uint64_t domain_acquisitions;
    uint64_t domain_wait_ns;
    uint64_t domain_hold_ns;
} jpdf_runtime_info;

typedef struct jpdf_open_options {
    uint32_t struct_size;
    uint32_t flags;        // reserved, must be 0
    const char* password;  // UTF-8 or Latin-1, may be NULL
} jpdf_open_options;

typedef struct jpdf_page_info {
    uint32_t struct_size;
    int32_t index;
    double width_pt;  // as displayed, after /Rotate
    double height_pt;
    int32_t rotation;  // /Rotate in quarter turns clockwise
    int32_t reserved0;
} jpdf_page_info;

#define JPDF_RENDER_ANNOTATIONS 0x0001u
#define JPDF_RENDER_LCD_TEXT 0x0002u
#define JPDF_RENDER_GRAYSCALE 0x0004u
#define JPDF_RENDER_PRINTING 0x0008u
#define JPDF_RENDER_NO_SMOOTH_TEXT 0x0010u
#define JPDF_RENDER_NO_SMOOTH_IMAGE 0x0020u
#define JPDF_RENDER_NO_SMOOTH_PATH 0x0040u
// Leave the background transparent instead of white. RGBA8 only.
#define JPDF_RENDER_TRANSPARENT 0x0100u

#define JPDF_PIXEL_RGBA8 1  // straight (non-premultiplied) alpha
#define JPDF_PIXEL_RGB8 2
#define JPDF_PIXEL_GRAY8 3

typedef struct jpdf_render_options {
    uint32_t struct_size;
    uint32_t flags;  // JPDF_RENDER_*
    double dpi_x;    // used when width and height are 0
    double dpi_y;
    int32_t width;  // explicit pixel size: both set or both 0
    int32_t height;
    int32_t rotation;  // reserved, must be 0 in 1.0
    int32_t pixel_format;
    uint64_t max_pixels;  // 0 selects the library budget
} jpdf_render_options;

typedef struct jpdf_bitmap_info {
    uint32_t struct_size;
    int32_t pixel_format;
    int32_t width;
    int32_t height;
    int32_t stride;
    int32_t reserved0;
    double dpi_x;
    double dpi_y;
    uint64_t byte_size;
} jpdf_bitmap_info;

// A view pins the pixels; data stays valid until jpdf_view_release, even after bitmap close.
typedef struct jpdf_bitmap_view {
    uint32_t struct_size;
    uint32_t reserved0;
    jpdf_handle view;
    const uint8_t* data;
    uint64_t byte_size;
    int32_t width;
    int32_t height;
    int32_t stride;
    int32_t pixel_format;
} jpdf_bitmap_view;

#define JPDF_IMAGE_PNG 1

// fsync the staged file and its directory before reporting success.
#define JPDF_WRITE_DURABLE 0x0001u

typedef struct jpdf_write_options {
    uint32_t struct_size;
    uint32_t flags;  // JPDF_WRITE_*
    int32_t image_format;
    int32_t png_compression;  // 0-9, or -1 for the default (6)
    uint64_t max_bytes;       // 0 = unlimited
} jpdf_write_options;

// Raster devices for jpdf_rasterize_page_file, named after the Ghostscript devices OCRmyPDF uses.
#define JPDF_RASTER_MONO 1       // pngmono: 1-bit, Floyd-Steinberg from a gray render
#define JPDF_RASTER_MONOD 2      // pngmonod: same pixels as MONO
#define JPDF_RASTER_GRAY 3       // pnggray
#define JPDF_RASTER_INDEXED 4    // png256: written as 24-bit RGB, no palette quantization
#define JPDF_RASTER_RGB 5        // png16m
#define JPDF_RASTER_RGB_ALPHA 6  // pngalpha: opaque white background, so written as RGB

// Render the CropBox instead of expanding it to the MediaBox.
#define JPDF_RASTER_USE_CROPBOX 0x0001u
// Ghostscript -dFILTERVECTOR; not implemented, so setting it is JPDF_E_UNSUPPORTED.
#define JPDF_RASTER_FILTER_VECTOR 0x0002u

typedef struct jpdf_raster_options {
    uint32_t struct_size;
    uint32_t flags;  // JPDF_RASTER_* flags
    double raster_dpi_x;
    double raster_dpi_y;
    double output_dpi_x;  // DPI written to the file; 0 uses the raster DPI
    double output_dpi_y;
    int32_t rotation_ccw;  // 0, 90, 180 or 270, applied on top of the page's /Rotate
    int32_t device;        // JPDF_RASTER_MONO ... JPDF_RASTER_RGB_ALPHA
    uint64_t max_pixels;   // 0 selects the library budget
    const char* password;  // may be NULL
} jpdf_raster_options;

typedef struct jpdf_raster_result {
    uint32_t struct_size;
    int32_t width;
    int32_t height;
    int32_t bits_per_pixel;  // 1, 8 or 24
    double dpi_x;            // as written to the file
    double dpi_y;
} jpdf_raster_result;

#define JPDF_INTERP_NEAREST 0
#define JPDF_INTERP_BILINEAR 1
#define JPDF_INTERP_BICUBIC 2  // OpenCV INTER_CUBIC kernel (a = -0.75)

#define JPDF_BORDER_WHITE 0      // samples outside the source are white (transparent for RGBA)
#define JPDF_BORDER_REPLICATE 1  // clamp to the nearest edge pixel

typedef struct jpdf_rectify_options {
    uint32_t struct_size;
    uint32_t flags;  // reserved, must be 0
    int32_t width;   // output size; 0 derives it from the quad's longer opposite edges
    int32_t height;
    int32_t interpolation;
    int32_t border;
    uint64_t max_pixels;  // 0 selects the library budget
} jpdf_rectify_options;

typedef struct jpdf_rectify_result {
    uint32_t struct_size;
    int32_t width;
    int32_t height;
    int32_t reserved0;
    double quad[8];  // the input corners reordered top-left, top-right, bottom-right, bottom-left
    double to_source[9];  // row-major homography mapping output pixel centres to source pixels
} jpdf_rectify_result;

// Built-in glyphless font: every glyph is blank; /ToUnicode carries the exact text.
#define JPDF_FONT_GLYPHLESS 1

// Boxes are in image pixels, origin top-left. Arrays of these structs are read with the first
// element's struct_size as the stride, and every element must carry the same struct_size.
typedef struct jpdf_text_word {
    uint32_t struct_size;
    uint32_t flags;         // reserved, must be 0
    const char* text_utf8;  // logical order, valid UTF-8, not empty, no control characters
    double x0;              // x1 >= x0; the line sets the vertical placement
    double y0;
    double x1;
    double y1;
} jpdf_text_word;

typedef struct jpdf_text_line {
    uint32_t struct_size;
    uint32_t flags;  // reserved, must be 0
    double x0;
    double y0;  // y1 > y0: the line height is the font size
    double x1;
    double y1;
    int32_t word_count;  // takes the next word_count words, at least 1
    int32_t reserved0;
} jpdf_text_line;

typedef struct jpdf_text_layer_options {
    uint32_t struct_size;
    uint32_t flags;  // reserved, must be 0
    int32_t image_width;
    int32_t image_height;
    double dpi_x;  // the page is image_width / dpi_x * 72 points wide
    double dpi_y;
    int32_t font;  // JPDF_FONT_GLYPHLESS, or 0 for it
    int32_t reserved0;
} jpdf_text_layer_options;

// --- Identity, errors, capabilities. Callable in every runtime state. ---

// (major << 16) | minor of the loaded library.
JPDF_API uint32_t jpdf_api_version(void) JPDF_NOEXCEPT;
// sizeof the named struct in this library, or -1 for an unknown id.
JPDF_API int64_t jpdf_abi_struct_size(int32_t struct_id) JPDF_NOEXCEPT;
// Static name for a status, e.g. "JPDF_E_STALE_HANDLE"; unknown values map to "JPDF_E_UNKNOWN".
JPDF_API const char* jpdf_status_name(jpdf_status status) JPDF_NOEXCEPT;
// Copies this thread's detail for its last failed call; required includes the NUL.
JPDF_API jpdf_status jpdf_last_error_message(char* buffer, uint64_t capacity,
                                             uint64_t* required) JPDF_NOEXCEPT;
// Readiness of a named operation such as "pdf.render"; unknown names report JPDF_CAP_ABSENT.
JPDF_API jpdf_status jpdf_capability(const char* name, int32_t* out_level) JPDF_NOEXCEPT;

// --- Runtime lifecycle. ---

// Explicit lazy init: idempotent while RUNNING, refused once stopped. NULL selects defaults.
JPDF_API jpdf_status jpdf_runtime_init(const jpdf_runtime_options* options) JPDF_NOEXCEPT;
JPDF_API jpdf_status jpdf_runtime_get_info(jpdf_runtime_info* out) JPDF_NOEXCEPT;
// Stop admitting new work; closes are still allowed. Idempotent.
JPDF_API jpdf_status jpdf_runtime_quiesce(void) JPDF_NOEXCEPT;
// Quiesce, then destroy PDFium. JPDF_E_BUSY while documents or pages remain; STOPPED is final.
JPDF_API jpdf_status jpdf_runtime_shutdown(void) JPDF_NOEXCEPT;
// As jpdf_runtime_shutdown, but JPDF_E_TIMEOUT if the domain stays busy past timeout_ms.
JPDF_API jpdf_status jpdf_runtime_shutdown_within(uint32_t timeout_ms) JPDF_NOEXCEPT;
// Back to RUNNING after a quiesce or refused shutdown. Never leaves STOPPED or FAILED.
JPDF_API jpdf_status jpdf_runtime_resume(void) JPDF_NOEXCEPT;

// --- Legacy surface. jpdf_guard_<name> admits jpdfium_* and raw PDFium calls to the domain. ---

// A refused jpdf_guard_* call returns a zero value; this reports and clears why, per thread.
JPDF_API jpdf_status jpdf_guard_take_refusal(void) JPDF_NOEXCEPT;

// --- Documents and pages. All PDFium work runs inside the native domain. ---

JPDF_API jpdf_status jpdf_doc_open_file(const char* path_utf8, const jpdf_open_options* options,
                                        jpdf_handle* out_doc) JPDF_NOEXCEPT;
// JPDF_E_BUSY while pages of this document are open; the document stays usable.
JPDF_API jpdf_status jpdf_doc_close(jpdf_handle doc) JPDF_NOEXCEPT;
JPDF_API jpdf_status jpdf_doc_page_count(jpdf_handle doc, int32_t* out_count) JPDF_NOEXCEPT;

JPDF_API jpdf_status jpdf_page_open(jpdf_handle doc, int32_t index,
                                    jpdf_handle* out_page) JPDF_NOEXCEPT;
JPDF_API jpdf_status jpdf_page_close(jpdf_handle page) JPDF_NOEXCEPT;
JPDF_API jpdf_status jpdf_page_get_info(jpdf_handle page, jpdf_page_info* out) JPDF_NOEXCEPT;
// UTF-8 text in PDFium's extraction order. required includes the NUL; a NULL buffer only sizes.
JPDF_API jpdf_status jpdf_page_get_text(jpdf_handle page, char* buffer, uint64_t capacity,
                                        uint64_t* required) JPDF_NOEXCEPT;

// Renders into an independently owned bitmap; closing the page or document does not free it.
JPDF_API jpdf_status jpdf_page_render(jpdf_handle page, const jpdf_render_options* options,
                                      jpdf_handle* out_bitmap) JPDF_NOEXCEPT;
// Render and publish an image file through an output transaction; failure leaves path untouched.
JPDF_API jpdf_status jpdf_page_render_to_file(jpdf_handle page, const jpdf_render_options* render,
                                              const char* path_utf8,
                                              const jpdf_write_options* write) JPDF_NOEXCEPT;

// --- Bitmaps. No PDFium state: these never enter the domain. ---

JPDF_API jpdf_status jpdf_bitmap_get_info(jpdf_handle bitmap, jpdf_bitmap_info* out) JPDF_NOEXCEPT;
// Copies byte_size bytes; JPDF_E_BUFFER_TOO_SMALL reports the size needed in out_written.
JPDF_API jpdf_status jpdf_bitmap_copy_pixels(jpdf_handle bitmap, uint8_t* dst, uint64_t capacity,
                                             uint64_t* out_written) JPDF_NOEXCEPT;
JPDF_API jpdf_status jpdf_bitmap_map(jpdf_handle bitmap, jpdf_bitmap_view* out_view) JPDF_NOEXCEPT;
JPDF_API jpdf_status jpdf_view_release(jpdf_handle view) JPDF_NOEXCEPT;
JPDF_API jpdf_status jpdf_bitmap_write_file(jpdf_handle bitmap, const char* path_utf8,
                                            const jpdf_write_options* options) JPDF_NOEXCEPT;
// Retires the handle; pixels pinned by views are freed when the last view is released.
JPDF_API jpdf_status jpdf_bitmap_close(jpdf_handle bitmap) JPDF_NOEXCEPT;
// Perspective-correct the quad (8 source-pixel coordinates, any corner order) into a new bitmap.
JPDF_API jpdf_status jpdf_bitmap_rectify(jpdf_handle bitmap, const double quad[8],
                                         const jpdf_rectify_options* options,
                                         jpdf_handle* out_bitmap,
                                         jpdf_rectify_result* out_result) JPDF_NOEXCEPT;

// --- Invisible text layers (OCRmyPDF generate_pdf). No PDFium: never enters the domain. ---

// Writes a one-page PDF sized to the image that holds only invisible text, through an output
// transaction. write may be NULL; its image fields must be 0. See docs/design/TEXT_LAYER.md.
JPDF_API jpdf_status jpdf_text_layer_write(const jpdf_text_layer_options* options,
                                           const jpdf_text_line* lines, int32_t line_count,
                                           const jpdf_text_word* words, int32_t word_count,
                                           const char* output_path_utf8,
                                           const jpdf_write_options* write) JPDF_NOEXCEPT;

// --- Rasterization for OCR pipelines (the OCRmyPDF rasterize_pdf_page contract). ---

// Opens path_utf8, rasterizes page_index (0-based) and publishes a PNG through an output
// transaction. out_result may be NULL. See docs/design/RASTER.md for the reference contract.
JPDF_API jpdf_status jpdf_rasterize_page_file(const char* path_utf8, int32_t page_index,
                                              const jpdf_raster_options* options,
                                              const char* output_path_utf8,
                                              const jpdf_write_options* write,
                                              jpdf_raster_result* out_result) JPDF_NOEXCEPT;

#ifdef __cplusplus
}
#endif
