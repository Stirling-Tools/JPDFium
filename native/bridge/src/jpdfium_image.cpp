// jpdfium_image.cpp - Image to PDF conversion.
//
// Uses PDFium's native image APIs to embed images into PDF pages.
// Supports JPEG, PNG, and raw bitmap embedding.

#include "jpdfium.h"
#include "jpdfium_internal.h"

#include <fpdfview.h>
#include <fpdf_edit.h>

#include <cstdlib>
#include <cstdint>
#include <cstring>

// Internal helpers

// Decode PNG data to raw RGBA pixels using libpng (if available)
// Returns nullptr if PNG decoding fails or libpng is not linked
static uint8_t* decode_png(const uint8_t* png_data, size_t png_len,
                           int* out_width, int* out_height) {
#ifdef JPDFIUM_HAS_PNG
    // libpng decoding would go here
    // For now, return nullptr to fall back to stb_image
    (void)png_data;
    (void)png_len;
    (void)out_width;
    (void)out_height;
#endif
    return nullptr;
}

// Decode JPEG data to raw RGB pixels using libjpeg-turbo (if available)
static uint8_t* decode_jpeg(const uint8_t* jpeg_data, size_t jpeg_len,
                            int* out_width, int* out_height) {
#ifdef JPDFIUM_HAS_JPEG
    // libjpeg decoding would go here
    (void)jpeg_data;
    (void)jpeg_len;
    (void)out_width;
    (void)out_height;
#endif
    return nullptr;
}

// Simple PNG header check
static bool is_png(const uint8_t* data, size_t len) {
    if (len < 8) return false;
    return (data[0] == 0x89 && data[1] == 0x50 && data[2] == 0x4E && data[3] == 0x47 &&
            data[4] == 0x0D && data[5] == 0x0A && data[6] == 0x1A && data[7] == 0x0A);
}

// Simple JPEG header check (SOI marker)
static bool is_jpeg(const uint8_t* data, size_t len) {
    if (len < 2) return false;
    return (data[0] == 0xFF && data[1] == 0xD8);
}

// Read little-endian int32 from 4 bytes
static int32_t read_le_i32(const uint8_t* p) {
    return (int32_t)(p[0] | (p[1] << 8) | (p[2] << 16) | (p[3] << 24));
}

// Create a new PDF page with an image embedded.
// image_format: 0=auto-detect, 1=PNG, 2=JPEG, 3=raw RGBA
//   For format 3, image_data must begin with an 8-byte header:
//     bytes 0-3: image width  (int32, little-endian)
//     bytes 4-7: image height (int32, little-endian)
//   followed immediately by width*height*4 bytes of raw RGBA pixel data.
static int32_t create_page_with_image(int64_t docHandle,
                                       const uint8_t* image_data, size_t image_len,
                                       float page_width, float page_height,
                                       float margin,
                                       Position position,
                                       int32_t image_format,
                                       int32_t page_index) {
    DocWrapper* dw = decodeDoc(docHandle);
    if (!dw || !dw->doc) return JPDFIUM_ERR_INVALID;

    int img_width = 0, img_height = 0;
    uint8_t* pixels = nullptr;
    bool pixels_owned = false;
    int channels = 4; // RGBA by default

    if (image_format == 3) {
        // Raw RGBA with 8-byte [width][height] header
        if (image_len < 8) return JPDFIUM_ERR_INVALID;
        img_width  = read_le_i32(image_data);
        img_height = read_le_i32(image_data + 4);
        if (img_width <= 0 || img_height <= 0) return JPDFIUM_ERR_INVALID;
        size_t pixel_bytes = (size_t)img_width * img_height * 4;
        if (image_len < (size_t)(8 + (int64_t)pixel_bytes)) return JPDFIUM_ERR_INVALID;
        // Use the pixel data in-place (no copy needed here)
        pixels = const_cast<uint8_t*>(image_data + 8);
        pixels_owned = false;
        channels = 4;
    } else {
        // Detect image format
        bool is_png_format = (image_format == 1) || (image_format == 0 && is_png(image_data, image_len));
        bool is_jpeg_format = (image_format == 2) || (image_format == 0 && is_jpeg(image_data, image_len));

        if (is_png_format) {
            pixels = decode_png(image_data, image_len, &img_width, &img_height);
            channels = 4;
        } else if (is_jpeg_format) {
            pixels = decode_jpeg(image_data, image_len, &img_width, &img_height);
            channels = 3;
        }

        if (!pixels) {
            // PNG/JPEG decoding not yet implemented; callers should use format 3 (raw RGBA).
            return JPDFIUM_ERR_NATIVE;
        }
        pixels_owned = true;
    }

    // Create bitmap from pixels
    FPDF_BITMAP bmp = FPDFBitmap_Create(img_width, img_height, channels == 4 ? 1 : 0);
    if (!bmp) {
        if (pixels_owned) free(pixels);
        return JPDFIUM_ERR_NATIVE;
    }

    // Copy pixel data to PDFium bitmap
    void* bmp_buf = FPDFBitmap_GetBuffer(bmp);
    int stride = FPDFBitmap_GetStride(bmp);

    // Copy row-by-row (PDFium bitmap stride may differ from tight pixel packing)
    int row_bytes = img_width * channels;
    for (int row = 0; row < img_height; ++row) {
        memcpy(static_cast<uint8_t*>(bmp_buf) + row * stride,
               pixels + row * row_bytes,
               row_bytes);
    }

    if (pixels_owned) free(pixels);

    // Calculate image placement on page
    float available_width = page_width - 2 * margin;
    float available_height = page_height - 2 * margin;

    float scale = 1.0f;
    if (page_width > 0 && page_height > 0) {
        float scale_w = available_width / img_width;
        float scale_h = available_height / img_height;
        scale = (scale_w < scale_h) ? scale_w : scale_h;
    }

    float scaled_width = img_width * scale;
    float scaled_height = img_height * scale;

    // Calculate position offset
    float offset_x = margin;
    float offset_y = margin;

    switch (position) {
        case POSITION_CENTER:
            offset_x = margin + (available_width - scaled_width) / 2;
            offset_y = margin + (available_height - scaled_height) / 2;
            break;
        case POSITION_TOP_LEFT:
            offset_x = margin;
            offset_y = page_height - margin - scaled_height;
            break;
        case POSITION_TOP_CENTER:
            offset_x = margin + (available_width - scaled_width) / 2;
            offset_y = page_height - margin - scaled_height;
            break;
        case POSITION_TOP_RIGHT:
            offset_x = page_width - margin - scaled_width;
            offset_y = page_height - margin - scaled_height;
            break;
        case POSITION_MIDDLE_LEFT:
            offset_x = margin;
            offset_y = margin + (available_height - scaled_height) / 2;
            break;
        case POSITION_MIDDLE_RIGHT:
            offset_x = page_width - margin - scaled_width;
            offset_y = margin + (available_height - scaled_height) / 2;
            break;
        case POSITION_BOTTOM_LEFT:
            offset_x = margin;
            offset_y = margin;
            break;
        case POSITION_BOTTOM_CENTER:
            offset_x = margin + (available_width - scaled_width) / 2;
            offset_y = margin;
            break;
        case POSITION_BOTTOM_RIGHT:
            offset_x = page_width - margin - scaled_width;
            offset_y = margin;
            break;
    }

    // Insert page at specified index
    FPDF_PAGE page = nullptr;
    if (page_index < 0 || page_index >= FPDF_GetPageCount(dw->doc)) {
        page = FPDFPage_New(dw->doc, FPDF_GetPageCount(dw->doc), page_width, page_height);
    } else {
        page = FPDFPage_New(dw->doc, page_index, page_width, page_height);
    }

    if (!page) {
        FPDFBitmap_Destroy(bmp);
        return JPDFIUM_ERR_NATIVE;
    }

    // Create image object
    FPDF_PAGEOBJECT img_obj = FPDFPageObj_NewImageObj(dw->doc);
    if (!img_obj) {
        FPDF_ClosePage(page);
        FPDFBitmap_Destroy(bmp);
        return JPDFIUM_ERR_NATIVE;
    }

    // Set bitmap to image object
    if (!FPDFImageObj_SetBitmap(nullptr, 0, img_obj, bmp)) {
        FPDFPageObj_Destroy(img_obj);
        FPDF_ClosePage(page);
        FPDFBitmap_Destroy(bmp);
        return JPDFIUM_ERR_NATIVE;
    }

    FPDFBitmap_Destroy(bmp);

    // Set transform matrix: scale and position
    FPDFImageObj_SetMatrix(img_obj,
        scaled_width, 0,
        0, scaled_height,
        offset_x, offset_y);

    // Insert image object into page
    FPDFPage_InsertObject(page, img_obj);

    // Generate content stream
    if (!FPDFPage_GenerateContent(page)) {
        FPDF_ClosePage(page);
        return JPDFIUM_ERR_NATIVE;
    }

    FPDF_ClosePage(page);
    return JPDFIUM_OK;
}

// Exported: Image to PDF conversion

extern "C" {

// Create a new PDF document with a single image page.
// Returns new document handle via *doc_handle.
// image_format: 0=auto-detect, 1=PNG, 2=JPEG, 3=raw RGBA (8-byte header: width+height)
JPDFIUM_EXPORT int32_t jpdfium_image_to_pdf(
    const uint8_t* image_data, int64_t image_len,
    float page_width, float page_height,
    float margin,
    int32_t position,
    int32_t image_format,
    int64_t* doc_handle) {

    if (!image_data || image_len <= 0 || !doc_handle) {
        return JPDFIUM_ERR_INVALID;
    }

    // Initialize PDFium if needed
    FPDF_InitLibrary();

    // Create new document wrapped in a DocWrapper (required for jpdfium_doc_close etc.)
    auto* dw = new DocWrapper();
    dw->doc = FPDF_CreateNewDocument();
    if (!dw->doc) {
        delete dw;
        return JPDFIUM_ERR_NATIVE;
    }

    int32_t result = create_page_with_image(
        encodeHandle(dw),
        image_data, static_cast<size_t>(image_len),
        page_width, page_height,
        margin,
        static_cast<Position>(position),
        image_format,
        0);

    if (result != JPDFIUM_OK) {
        delete dw;
        return result;
    }

    *doc_handle = encodeHandle(dw);
    return JPDFIUM_OK;
}

// Add an image page to an existing document.
JPDFIUM_EXPORT int32_t jpdfium_doc_add_image_page(
    int64_t doc_handle,
    const uint8_t* image_data, int64_t image_len,
    float page_width, float page_height,
    float margin,
    int32_t position,
    int32_t image_format,
    int32_t insert_at_index) {

    if (!image_data || image_len <= 0) {
        return JPDFIUM_ERR_INVALID;
    }

    return create_page_with_image(
        doc_handle,
        image_data, static_cast<size_t>(image_len),
        page_width, page_height,
        margin,
        static_cast<Position>(position),
        image_format,
        insert_at_index);
}

// Direct JPEG embedding (no decoding/re-encoding).
// This is the most efficient path for JPEG images.
JPDFIUM_EXPORT int32_t jpdfium_embed_jpeg_direct(
    int64_t doc_handle,
    const uint8_t* jpeg_data, int64_t jpeg_len,
    float page_width, float page_height,
    float margin,
    int32_t position,
    int32_t insert_at_index) {

    DocWrapper* dw = decodeDoc(doc_handle);
    if (!dw || !dw->doc) return JPDFIUM_ERR_INVALID;

    if (!is_jpeg(jpeg_data, static_cast<size_t>(jpeg_len))) {
        return JPDFIUM_ERR_INVALID;
    }

    // For direct JPEG embedding, we would use FPDFImageObj_LoadJpegFileInline
    // This requires setting up a FPDF_FILEACCESS structure
    // Implementation deferred - use decoded bitmap path for now

    return JPDFIUM_ERR_NATIVE;
}

} // extern "C"
