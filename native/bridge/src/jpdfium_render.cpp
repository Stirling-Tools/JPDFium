// jpdfium_render.cpp - Page rendering and page-to-image conversion.

#include <fpdf_edit.h>
#include <fpdf_formfill.h>
#include <fpdfview.h>

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <optional>
#include <unordered_map>

#include "jpdfium.h"
#include "jpdfium_internal.h"
#if defined(__ARM_NEON) || defined(__aarch64__)
#include <arm_neon.h>
#elif defined(__x86_64__) || defined(_M_X64)
#include <immintrin.h>
#endif

#include <fpdf_progressive.h>

namespace {

// Integer ceiling for w_px * h_px (1 GiB at 4 bytes per pixel), so the budget
// is validated on the geometry that is actually allocated rather than on a
// double product.
constexpr int64_t kMaxRenderPixelsInt = 268435456;

struct RasterTarget {
    double w_pt = 0.0;
    double h_pt = 0.0;
    int32_t w_px = 0;
    int32_t h_px = 0;
};

// Page box in points -> validated integer pixel size, or nullopt when the page,
// the dpi or the resulting geometry is unusable.
std::optional<RasterTarget> measure_raster_target(double w_pt, double h_pt, int32_t dpi) {
    // Reject non-finite geometry explicitly: every comparison against NaN is
    // false, so a NaN page size or dpi would otherwise reach the
    // floating-point to integer conversion below, which is undefined.
    if (!std::isfinite(w_pt) || !std::isfinite(h_pt) || w_pt <= 0.0 || h_pt <= 0.0 || dpi <= 0) {
        return std::nullopt;
    }
    // Truncate (not round) so pixel sizes match PDFBox and pdf.js viewports.
    const double w_px_d = w_pt * static_cast<double>(dpi) / 72.0;
    const double h_px_d = h_pt * static_cast<double>(dpi) / 72.0;
    if (!std::isfinite(w_px_d) || !std::isfinite(h_px_d) || w_px_d <= 0.0 || h_px_d <= 0.0 ||
        w_px_d > INT32_MAX || h_px_d > INT32_MAX) {
        return std::nullopt;
    }
    // Clamp positive dimensions to at least one pixel: truncation alone would
    // reject a page that rounds to one pixel, which PDFBox and the Java bounds
    // check both accept.
    const int32_t w_px = std::max(1, static_cast<int32_t>(w_px_d));
    const int32_t h_px = std::max(1, static_cast<int32_t>(h_px_d));
    // Validate the geometry that is actually allocated: a sub-pixel side clamps
    // to one pixel and can push the real frame over the checked double product,
    // and the row stride must stay representable as the int FPDFBitmap_CreateEx
    // takes.
    const int64_t pixels = static_cast<int64_t>(w_px) * static_cast<int64_t>(h_px);
    if (pixels > kMaxRenderPixelsInt) return std::nullopt;
    if (static_cast<int64_t>(w_px) * 4 > INT32_MAX) return std::nullopt;
    return RasterTarget{w_pt, h_pt, w_px, h_px};
}

std::optional<RasterTarget> measure_raster_target(FPDF_PAGE page, int32_t dpi) {
    return measure_raster_target(FPDF_GetPageWidth(page), FPDF_GetPageHeight(page), dpi);
}

inline int renderFlagsForScreen() {
    return FPDF_ANNOT | FPDF_LCD_TEXT;
}

inline int bitmapFormatForRenderer() {
#ifdef JPDFIUM_HAS_SKIA
    if (g_jpdfiumUseSkia) return FPDFBitmap_BGRA_Premul;
#endif
    return FPDFBitmap_BGRA;
}

#ifdef JPDFIUM_HAS_SKIA
consteval auto generateUnpremulTable() {
    std::array<std::array<uint8_t, 256>, 256> table{};
    for (uint32_t a = 1; a < 256; ++a) {
        for (uint32_t v = 0; v <= a; ++v) {
            table[a][v] = static_cast<uint8_t>((v * 255 + a / 2) / a);
        }
        for (uint32_t v = a + 1; v < 256; ++v) {
            table[a][v] = 255;
        }
    }
    return table;
}
static constexpr auto kUnpremulTable = generateUnpremulTable();

inline void bgraToRgbaInPlace(uint8_t* __restrict buf, int w, int h, int stride) {
    if (stride == w * 4) {
        int total = w * h;
        int i = 0;
#if defined(__ARM_NEON) || defined(__aarch64__)
        for (; i + 16 <= total; i += 16) {
            uint8x16x4_t pixels = vld4q_u8(buf + i * 4);
            uint8x16_t b = pixels.val[0];
            pixels.val[0] = pixels.val[2];
            pixels.val[2] = b;
            vst4q_u8(buf + i * 4, pixels);
        }
#elif defined(__SSSE3__)
        const __m128i mask = _mm_setr_epi8(2, 1, 0, 3, 6, 5, 4, 7, 10, 9, 8, 11, 14, 13, 12, 15);
        for (; i + 4 <= total; i += 4) {
            __m128i p = _mm_loadu_si128(reinterpret_cast<const __m128i*>(buf + i * 4));
            p = _mm_shuffle_epi8(p, mask);
            _mm_storeu_si128(reinterpret_cast<__m128i*>(buf + i * 4), p);
        }
#endif
        // Unaligned-safe: caller buffers only guarantee byte alignment.
        for (; i < total; ++i) {
            uint32_t px = 0;
            std::memcpy(&px, buf + i * 4, sizeof(px));
            uint32_t swapped =
                (px & 0xFF00FF00u) | ((px & 0x00FF0000u) >> 16) | ((px & 0x000000FFu) << 16);
            std::memcpy(buf + i * 4, &swapped, sizeof(swapped));
        }
        return;
    }

    for (int row = 0; row < h; ++row) {
        uint8_t* r = buf + static_cast<std::ptrdiff_t>(row) * stride;
        for (int col = 0; col < w; ++col) {
            uint32_t px = 0;
            std::memcpy(&px, r + col * 4, sizeof(px));
            uint32_t swapped =
                (px & 0xFF00FF00u) | ((px & 0x00FF0000u) >> 16) | ((px & 0x000000FFu) << 16);
            std::memcpy(r + col * 4, &swapped, sizeof(swapped));
        }
    }
}

inline void unpremulAndSwapBgraInPlace(uint8_t* __restrict buf, int w, int h, int stride,
                                       bool reverse_byte_order) {
    if (stride == w * 4) {
        int total = w * h;
        uint8_t* __restrict r = buf;
        for (int i = 0; i < total; ++i, r += 4) {
            uint8_t a = r[3];
            if (a == 255) {
                if (reverse_byte_order) {
                    uint8_t b = r[0];
                    r[0] = r[2];
                    r[2] = b;
                }
                continue;
            }
            if (a == 0) {
                r[0] = 0;
                r[1] = 0;
                r[2] = 0;
                continue;
            }
            const auto& row = kUnpremulTable[a];
            uint8_t b = row[r[0]];
            uint8_t g = row[r[1]];
            uint8_t red = row[r[2]];
            if (reverse_byte_order) {
                r[0] = red;
                r[1] = g;
                r[2] = b;
            } else {
                r[0] = b;
                r[1] = g;
                r[2] = red;
            }
        }
        return;
    }

    for (int row = 0; row < h; ++row) {
        uint8_t* __restrict r = buf + static_cast<std::ptrdiff_t>(row) * stride;
        for (int col = 0; col < w; ++col, r += 4) {
            uint8_t a = r[3];
            if (a == 255) {
                if (reverse_byte_order) {
                    uint8_t b = r[0];
                    r[0] = r[2];
                    r[2] = b;
                }
                continue;
            }
            if (a == 0) {
                r[0] = 0;
                r[1] = 0;
                r[2] = 0;
                continue;
            }
            const auto& row_tbl = kUnpremulTable[a];
            uint8_t b = row_tbl[r[0]];
            uint8_t g = row_tbl[r[1]];
            uint8_t red = row_tbl[r[2]];
            if (reverse_byte_order) {
                r[0] = red;
                r[1] = g;
                r[2] = b;
            } else {
                r[0] = b;
                r[1] = g;
                r[2] = red;
            }
        }
    }
}
#endif

struct ProgressiveState {
    FPDF_BITMAP bmp = nullptr;
    uint8_t* target = nullptr;
    int32_t width = 0;
    int32_t height = 0;
    int32_t stride = 0;
    bool reverse_byte_order = false;
    IFSDK_PAUSE pause{};
};

// Per-continue time budget so progressive rendering yields chunks instead of
// completing in a single Start call. The deadline is armed before each
// Start/Continue; the callback pauses once the budget is exceeded. A set
// cancel flag pauses immediately so cancellation is observed promptly.
static thread_local std::chrono::steady_clock::time_point g_pauseDeadline{};
static constexpr std::chrono::milliseconds kProgressiveSliceBudget{8};

static FPDF_BOOL NeedToPauseNowCallback(IFSDK_PAUSE* pause) {
    if (!pause || !pause->user) return 0;
    const auto* cancel = static_cast<const int32_t*>(pause->user);
    if (*cancel != 0) return 1;
    return std::chrono::steady_clock::now() >= g_pauseDeadline ? 1 : 0;
}

static bool isCancelRequested(const void* cancel_flag) {
    if (!cancel_flag) return false;
    return *static_cast<const volatile int32_t*>(cancel_flag) != 0;
}

static std::mutex g_progLock;
static std::unordered_map<void*, ProgressiveState> g_progMap;

// Render a page (plus optional form widgets) into a caller-provided buffer.
// Shared by the zero-allocation FFM render paths so the Skia premultiply/
// matrix handling lives in one place.
//
// The caller contract requires capacity for all padded rows (stride * height).
// Java validates the full contract (null, read-only, scope, budget, capacity)
// before the downcall; the checks below independently reject malformed
// dimensions with overflow-safe 64-bit math so raw bridge callers cannot
// smuggle a wrapped stride past the native write paths.
static bool renderIntoArgsValid(uint64_t target_capacity, int32_t width, int32_t height,
                                int32_t stride) {
    if (width <= 0 || height <= 0 || stride <= 0) return false;
    int64_t minStride = static_cast<int64_t>(width) * 4;
    if (minStride > INT32_MAX) return false;
    if (static_cast<int64_t>(stride) < minStride) return false;
    // Capacity must cover all padded rows, not just the last pixel: every
    // native write path (bitmap creation, background fill, conversion) walks
    // full stride * height bytes.
    uint64_t required =
        static_cast<uint64_t>(static_cast<int64_t>(stride)) * static_cast<uint64_t>(height);
    return target_capacity >= required;
}

int32_t renderIntoBuffer(FPDF_PAGE page, FPDF_FORMHANDLE form, uint8_t* target,
                         uint64_t target_capacity, int32_t width, int32_t height, int32_t stride,
                         int32_t flags) {
    if (!page || !target || !renderIntoArgsValid(target_capacity, width, height, stride))
        return JPDFIUM_ERR_INVALID;

    int pdfium_flags = flags;
#ifdef JPDFIUM_HAS_SKIA
    bool reverse_byte_order = false;
    if (g_jpdfiumUseSkia && (flags & FPDF_REVERSE_BYTE_ORDER) != 0) {
        reverse_byte_order = true;
        pdfium_flags = flags & ~FPDF_REVERSE_BYTE_ORDER;
    }
#endif

    const int fmt = bitmapFormatForRenderer();
    FPDF_BITMAP bmp = FPDFBitmap_CreateEx(width, height, fmt, target, stride);
    if (!bmp) return JPDFIUM_ERR_NATIVE;

#ifdef JPDFIUM_HAS_SKIA
    if (g_jpdfiumUseSkia) {
        double w_pt = FPDF_GetPageWidth(page);
        double h_pt = FPDF_GetPageHeight(page);
        if (!std::isfinite(w_pt) || !std::isfinite(h_pt) || w_pt <= 0 || h_pt <= 0) {
            FPDFBitmap_Destroy(bmp);
            return JPDFIUM_ERR_INVALID;
        }
        FS_MATRIX matrix = {static_cast<float>(width) / static_cast<float>(w_pt),  0, 0,
                            static_cast<float>(height) / static_cast<float>(h_pt), 0, 0};
        FS_RECTF clip = {0, 0, static_cast<float>(width), static_cast<float>(height)};
        FPDF_RenderPageBitmapWithMatrix(bmp, page, &matrix, &clip, pdfium_flags);
    } else {
        FPDF_RenderPageBitmap(bmp, page, 0, 0, width, height, 0, pdfium_flags);
    }
#else
    FPDF_RenderPageBitmap(bmp, page, 0, 0, width, height, 0, pdfium_flags);
#endif

    if (form) {
        FPDF_FFLDraw(form, bmp, page, 0, 0, width, height, 0, pdfium_flags);
    }

#ifdef JPDFIUM_HAS_SKIA
    if (g_jpdfiumUseSkia) {
        unpremulAndSwapBgraInPlace(target, width, height, stride, reverse_byte_order);
    }
#endif

    FPDFBitmap_Destroy(bmp);
    return JPDFIUM_OK;
}

}  // namespace

int32_t jpdfium_render_page_flags(int64_t page, int32_t dpi, int32_t flags, uint8_t** rgba,
                                  int32_t* width, int32_t* height) JPDFIUM_NOEXCEPT {
    PageWrapper* pw = decodePage(page);
    if (!pw || !pw->page || !rgba || !width || !height) return JPDFIUM_ERR_INVALID;
    if (dpi == INT32_MIN || dpi == 0) return JPDFIUM_ERR_INVALID;

    bool transparent = false;
    if (dpi < 0) {
        transparent = true;
        dpi = -dpi;
    }

    const std::optional<RasterTarget> target =
        measure_raster_target(FPDF_GetPageWidth(pw->page), FPDF_GetPageHeight(pw->page), dpi);
    if (!target) return JPDFIUM_ERR_INVALID;
    const int w_px = target->w_px;
    const int h_px = target->h_px;

    const int fmt = bitmapFormatForRenderer();
    uint8_t* out = allocRgbaChecked(w_px, h_px);
    if (!out) return JPDFIUM_ERR_NATIVE;

    FPDF_BITMAP bmp = FPDFBitmap_CreateEx(w_px, h_px, fmt, out, w_px * 4);
    if (!bmp) {
        free(out);
        return JPDFIUM_ERR_NATIVE;
    }

    FPDFBitmap_FillRect(bmp, 0, 0, w_px, h_px, transparent ? 0x00000000 : 0xFFFFFFFF);
    int render_flags = flags != 0 ? flags : (transparent ? FPDF_ANNOT : renderFlagsForScreen());
#ifdef JPDFIUM_HAS_SKIA
    if (g_jpdfiumUseSkia) {
        // Skia renders premultiplied and the channel swap happens in software
        // below, so FPDF_REVERSE_BYTE_ORDER must not reach PDFium: honouring it
        // there swaps the premultiplied bytes and the software pass swaps them
        // again, swapping the channels twice. Strip it exactly as
        // renderIntoBuffer does, so callers that pass the bit get the same
        // straight RGBA output on both renderers.
        render_flags &= ~FPDF_REVERSE_BYTE_ORDER;
        FS_MATRIX matrix = {static_cast<float>(w_px) / static_cast<float>(target->w_pt), 0, 0,
                            static_cast<float>(h_px) / static_cast<float>(target->h_pt), 0, 0};
        FS_RECTF clip = {0, 0, static_cast<float>(w_px), static_cast<float>(h_px)};
        FPDF_RenderPageBitmapWithMatrix(bmp, pw->page, &matrix, &clip, render_flags);
        if (transparent) {
            unpremulAndSwapBgraInPlace(out, w_px, h_px, w_px * 4, true);
        } else {
            bgraToRgbaInPlace(out, w_px, h_px, w_px * 4);
        }
    } else {
        render_flags |= FPDF_REVERSE_BYTE_ORDER;
        FPDF_RenderPageBitmap(bmp, pw->page, 0, 0, w_px, h_px, 0, render_flags);
    }
#else
    render_flags |= FPDF_REVERSE_BYTE_ORDER;
    FPDF_RenderPageBitmap(bmp, pw->page, 0, 0, w_px, h_px, 0, render_flags);
#endif

    FPDFBitmap_Destroy(bmp);
    *rgba = out;
    *width = w_px;
    *height = h_px;
    return JPDFIUM_OK;
}

int32_t jpdfium_render_page(int64_t page, int32_t dpi, uint8_t** rgba, int32_t* width,
                            int32_t* height) {
    try {
        return jpdfium_render_page_flags(page, dpi, 0, rgba, width, height);

    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

int32_t jpdfium_render_page_into(void* fpdf_page, uint8_t* target, uint64_t target_capacity,
                                 int32_t width, int32_t height, int32_t stride, int32_t flags) {
    try {
        return renderIntoBuffer(static_cast<FPDF_PAGE>(fpdf_page), nullptr, target, target_capacity,
                                width, height, stride, flags);

    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

int32_t jpdfium_render_page_form_into(void* fpdf_page, void* form, uint8_t* target,
                                      uint64_t target_capacity, int32_t width, int32_t height,
                                      int32_t stride, int32_t flags) {
    try {
        return renderIntoBuffer(static_cast<FPDF_PAGE>(fpdf_page),
                                static_cast<FPDF_FORMHANDLE>(form), target, target_capacity, width,
                                height, stride, flags);

    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

int32_t jpdfium_render_page_progressive_start(void* fpdf_page, uint8_t* target,
                                              uint64_t target_capacity, int32_t width,
                                              int32_t height, int32_t stride, int32_t flags,
                                              void* cancel_flag) JPDFIUM_NOEXCEPT {
    if (!fpdf_page || !target || !renderIntoArgsValid(target_capacity, width, height, stride))
        return JPDFIUM_ERR_INVALID;

    FPDF_PAGE page = static_cast<FPDF_PAGE>(fpdf_page);
    int pdfium_flags = flags;
    bool reverse_byte_order = false;
#ifdef JPDFIUM_HAS_SKIA
    if (g_jpdfiumUseSkia && (flags & FPDF_REVERSE_BYTE_ORDER) != 0) {
        reverse_byte_order = true;
        pdfium_flags = flags & ~FPDF_REVERSE_BYTE_ORDER;
    }
#endif

    const int fmt = bitmapFormatForRenderer();
    FPDF_BITMAP bmp = FPDFBitmap_CreateEx(width, height, fmt, target, stride);
    if (!bmp) return JPDFIUM_ERR_NATIVE;

    // PDFium keeps a single progressive context per FPDF_PAGE. Tear down any
    // stale entry BEFORE installing the new context: closing after Start
    // would destroy the render that was just begun. This runs ahead of the
    // pre-cancelled path too, so a cancelled restart cannot leave the old
    // entry behind to resume into a freed caller buffer later.
    //
    // Map operations can throw (allocation failure); this function is noexcept,
    // so translate any C++ exception to an error instead of terminating.
    try {
        {
            std::lock_guard<std::mutex> lock(g_progLock);
            auto existing = g_progMap.find(fpdf_page);
            if (existing != g_progMap.end()) {
                FPDF_RenderPage_Close(page);
                if (existing->second.bmp) FPDFBitmap_Destroy(existing->second.bmp);
                g_progMap.erase(existing);
            }
        }
    } catch (...) {
        FPDFBitmap_Destroy(bmp);
        return JPDFIUM_ERR_NATIVE;
    }

    if (isCancelRequested(cancel_flag)) {
        FPDFBitmap_Destroy(bmp);
        return JPDFIUM_RENDER_FAILED;
    }

    ProgressiveState state;
    state.bmp = bmp;
    state.target = target;
    state.width = width;
    state.height = height;
    state.stride = stride;
    state.reverse_byte_order = reverse_byte_order;
    state.pause.version = 1;
    state.pause.NeedToPauseNow = NeedToPauseNowCallback;
    state.pause.user = cancel_flag;

    g_pauseDeadline = std::chrono::steady_clock::now() + kProgressiveSliceBudget;
    int status =
        FPDF_RenderPageBitmap_Start(bmp, page, 0, 0, width, height, 0, pdfium_flags, &state.pause);
    if (status == FPDF_RENDER_TOBECONTINUED || status == FPDF_RENDER_READY) {
        try {
            std::lock_guard<std::mutex> lock(g_progLock);
            g_progMap[fpdf_page] = state;
        } catch (...) {
            FPDF_RenderPage_Close(page);
            FPDFBitmap_Destroy(bmp);
            return JPDFIUM_ERR_NATIVE;
        }
        return JPDFIUM_RENDER_TOBECONTINUED;
    }

    FPDF_RenderPage_Close(page);
    FPDFBitmap_Destroy(bmp);
    if (status == FPDF_RENDER_DONE) {
#ifdef JPDFIUM_HAS_SKIA
        if (g_jpdfiumUseSkia) {
            unpremulAndSwapBgraInPlace(target, width, height, stride, reverse_byte_order);
        }
#endif
        return JPDFIUM_RENDER_DONE;
    }
    return JPDFIUM_RENDER_FAILED;
}

int32_t jpdfium_render_page_progressive_continue(void* fpdf_page,
                                                 void* cancel_flag) JPDFIUM_NOEXCEPT {
    if (!fpdf_page) return JPDFIUM_ERR_INVALID;

    // noexcept boundary: map operations may throw on allocation failure.
    ProgressiveState state;
    try {
        std::lock_guard<std::mutex> lock(g_progLock);
        auto it = g_progMap.find(fpdf_page);
        if (it == g_progMap.end()) return JPDFIUM_ERR_NOT_FOUND;
        state = it->second;
    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
    // Cancellation is terminal: tear down the render so callers looping on
    // TO_BE_CONTINUED always terminate instead of spinning forever.
    if (isCancelRequested(cancel_flag)) {
        try {
            std::lock_guard<std::mutex> lock(g_progLock);
            g_progMap.erase(fpdf_page);
        } catch (...) {
            // Map erase cannot usefully fail here; teardown continues below.
        }
        FPDF_PAGE page = static_cast<FPDF_PAGE>(fpdf_page);
        FPDF_RenderPage_Close(page);
        if (state.bmp) FPDFBitmap_Destroy(state.bmp);
        return JPDFIUM_RENDER_FAILED;
    }
    state.pause.user = cancel_flag;
    FPDF_PAGE page = static_cast<FPDF_PAGE>(fpdf_page);
    g_pauseDeadline = std::chrono::steady_clock::now() + kProgressiveSliceBudget;
    int status = FPDF_RenderPage_Continue(page, &state.pause);
    if (status == FPDF_RENDER_TOBECONTINUED || status == FPDF_RENDER_READY) {
        return JPDFIUM_RENDER_TOBECONTINUED;
    }

    try {
        std::lock_guard<std::mutex> lock(g_progLock);
        g_progMap.erase(fpdf_page);
    } catch (...) {
        // Best-effort erase on the terminal path; Close/Destroy still run.
    }
    FPDF_RenderPage_Close(page);
    FPDFBitmap_Destroy(state.bmp);
    if (status == FPDF_RENDER_DONE) {
#ifdef JPDFIUM_HAS_SKIA
        if (g_jpdfiumUseSkia) {
            unpremulAndSwapBgraInPlace(state.target, state.width, state.height, state.stride,
                                       state.reverse_byte_order);
        }
#endif
        return JPDFIUM_RENDER_DONE;
    }
    return JPDFIUM_RENDER_FAILED;
}

void jpdfium_render_page_progressive_close(void* fpdf_page) JPDFIUM_NOEXCEPT {
    if (!fpdf_page) return;

    ProgressiveState state;
    bool found = false;
    try {
        std::lock_guard<std::mutex> lock(g_progLock);
        auto it = g_progMap.find(fpdf_page);
        if (it != g_progMap.end()) {
            state = it->second;
            g_progMap.erase(it);
            found = true;
        }
    } catch (...) {
        // Lookup-only failure in a void closer; nothing to report or clean.
        return;
    }
    if (found) {
        FPDF_PAGE page = static_cast<FPDF_PAGE>(fpdf_page);
        FPDF_RenderPage_Close(page);
        if (state.bmp) FPDFBitmap_Destroy(state.bmp);
    }
}

void jpdfium_render_abandon_progressive(void* fpdf_page) noexcept {
    jpdfium_render_page_progressive_close(fpdf_page);
}

void jpdfium_render_forget_progressive(void* fpdf_page) noexcept {
    // Stale-page path: the owning document was already freed and replaced, so
    // calling FPDF_RenderPage_Close on the dangling page would be a
    // use-after-free. Drop the map entry and destroy only the caller-owned
    // bitmap wrapper; the PDFium progressive context dies with its document.
    if (!fpdf_page) return;
    try {
        std::lock_guard<std::mutex> lock(g_progLock);
        auto it = g_progMap.find(fpdf_page);
        if (it != g_progMap.end()) {
            if (it->second.bmp) FPDFBitmap_Destroy(it->second.bmp);
            g_progMap.erase(it);
        }
    } catch (...) {
        // Best-effort cleanup in a noexcept closer.
    }
}

void jpdfium_free_buffer(uint8_t* buffer) noexcept {
    free(buffer);
}

namespace {

// A raster frame kept for reuse between renders must not stay mapped for the
// lifetime of a long-lived document. Ordinary page geometry is worth keeping
// (A4 at 150 dpi is ~8.7 MiB); past this budget the frame is released as soon as
// the pass ends, so per-document retention stays bounded instead of approaching
// the kMaxRenderPixelsInt ceiling (1 GiB at 4 bytes per pixel). This bounds one
// document, not the process: a trim or process-level budget would be the lever
// for many open documents.
constexpr size_t kRetainRasterFrameBytes = 32u * 1024u * 1024u;

// Renders `page` (geometry in `target`) into the caller-owned `bmp` and replaces the page at
// `pageIndex` with an image of that bitmap. `bmp` is filled white first: a page does not paint its
// own background, and a reused bitmap may still hold the previous page's pixels. `page` is closed
// before the page tree is restructured.
int32_t rasterize_page_into(DocWrapper* dw, int32_t pageIndex, UniquePage& page, FPDF_BITMAP bmp,
                            const RasterTarget& target) {
    try {
        const double w_pt = target.w_pt;
        const double h_pt = target.h_pt;
        const int w_px = target.w_px;
        const int h_px = target.h_px;
        FPDFBitmap_FillRect(bmp, 0, 0, w_px, h_px, 0xFFFFFFFF);
#ifdef JPDFIUM_HAS_SKIA
        if (g_jpdfiumUseSkia) {
            FS_MATRIX matrix = {static_cast<float>(w_px) / static_cast<float>(w_pt), 0, 0,
                                static_cast<float>(h_px) / static_cast<float>(h_pt), 0, 0};
            FS_RECTF clip = {0, 0, static_cast<float>(w_px), static_cast<float>(h_px)};
            FPDF_RenderPageBitmapWithMatrix(bmp, page.get(), &matrix, &clip,
                                            FPDF_ANNOT | FPDF_PRINTING);
        } else {
            FPDF_RenderPageBitmap(bmp, page.get(), 0, 0, w_px, h_px, 0, FPDF_ANNOT | FPDF_PRINTING);
        }
#else
        FPDF_RenderPageBitmap(bmp, page.get(), 0, 0, w_px, h_px, 0, FPDF_ANNOT | FPDF_PRINTING);
#endif

        page.reset();  // done with the source page before restructuring the document

        // Replacement page is created at pageIndex + 1; the original stays at
        // pageIndex until the replacement is fully generated, so any failure
        // here leaves the document exactly as it was.
        //
        // Rollback: closing the replacement handle must happen BEFORE deleting
        // the page object, which is the order FPDFPage_New requires. The guard
        // exists so an exception mid-way performs the same ordered cleanup as
        // the explicit failure branches below.
        UniquePage newPage(FPDFPage_New(dw->core->doc, pageIndex + 1, w_pt, h_pt));
        if (!newPage) return JPDFIUM_ERR_NATIVE;

        struct ReplacementPageRollback {
            DocWrapper* dw;
            int index;
            UniquePage* page;
            ~ReplacementPageRollback() {
                if (!page) return;
                (*page).reset();                        // close the loaded page
                FPDFPage_Delete(dw->core->doc, index);  // then delete the page
            }
        } rollback{dw, pageIndex + 1, &newPage};

        // Own the image object until it is inserted; the page takes ownership at
        // FPDFPage_InsertObject.
        UniquePageObject imgObj(FPDFPageObj_NewImageObj(dw->core->doc));
        if (!imgObj) return JPDFIUM_ERR_NATIVE;

        // SetBitmap copies the pixels into the object, so the bitmap is no longer
        // needed afterwards and is released with bmp.
        if (!FPDFImageObj_SetBitmap(nullptr, 0, imgObj.get(), bmp)) return JPDFIUM_ERR_NATIVE;

        FS_MATRIX matrix = {static_cast<float>(w_pt), 0, 0, static_cast<float>(h_pt), 0, 0};
        FPDFPageObj_SetMatrix(imgObj.get(), &matrix);

        FPDFPage_InsertObject(newPage.get(), imgObj.get());
        imgObj.release();  // the page owns it now
        if (!FPDFPage_GenerateContent(newPage.get())) return JPDFIUM_ERR_NATIVE;

        // A generated replacement page must carry the image object; PDFium drops
        // an image it cannot stream, so never commit a content-less page.
        if (FPDFPage_CountObjects(newPage.get()) < 1) return JPDFIUM_ERR_NATIVE;

        // Commit: close the replacement page handle, then delete the ORIGINAL
        // page at pageIndex (the replacement now occupies that slot).
        newPage.reset();
        FPDFPage_Delete(dw->core->doc, pageIndex);
        rollback.page = nullptr;
        ++dw->core->generation;
        return JPDFIUM_OK;

    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

}  // namespace

int32_t jpdfium_page_to_image(int64_t docHandle, int32_t pageIndex, int32_t dpi) {
    try {
        DocWrapper* dw = decodeDoc(docHandle);
        if (!dw || !dw->core->doc) return JPDFIUM_ERR_INVALID;

        UniquePage page(FPDF_LoadPage(dw->core->doc, pageIndex));
        if (!page) return JPDFIUM_ERR_NOT_FOUND;

        const std::optional<RasterTarget> target = measure_raster_target(page.get(), dpi);
        if (!target) return JPDFIUM_ERR_INVALID;

        // One scratch frame per open document: a fresh page-size bitmap per call
        // costs a new mapping and its first-touch faults on every page.
        DocCore& core = *dw->core;
        FPDF_BITMAP frame = core.rasterFrame.acquire(target->w_px, target->h_px);
        if (!frame) return JPDFIUM_ERR_NATIVE;
        const int32_t rc = rasterize_page_into(dw, pageIndex, page, frame, *target);
        // Reuse only pays off for ordinary page sizes, so an oversized frame is
        // released as soon as the page has been rendered.
        core.rasterFrame.trimTo(kRetainRasterFrameBytes);
        return rc;
    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

int32_t jpdfium_doc_flatten_to_images(int64_t docHandle, int32_t dpi) noexcept {
    // Set once the first page has actually been replaced: a later failure then
    // reports JPDFIUM_ERR_CONTENT_MUTATED so the caller knows the document is no
    // longer the one it opened.
    bool mutated = false;
    try {
        DocWrapper* dw = decodeDoc(docHandle);
        if (!dw || !dw->core->doc) return JPDFIUM_ERR_INVALID;
        if (dpi <= 0) return JPDFIUM_ERR_INVALID;

        DocCore& core = *dw->core;
        // Release an oversized scratch frame however this pass exits, so a failed
        // pass cannot leave a large frame mapped until the document closes.
        struct TrimOnExit {
            RasterFrame* frame;
            ~TrimOnExit() {
                frame->trimTo(kRetainRasterFrameBytes);
            }
        } trim_on_exit{&core.rasterFrame};

        const int count = FPDF_GetPageCount(core.doc);
        for (int i = 0; i < count; ++i) {
            UniquePage page(FPDF_LoadPage(core.doc, i));
            if (!page) return mutated ? JPDFIUM_ERR_CONTENT_MUTATED : JPDFIUM_ERR_NOT_FOUND;
            const std::optional<RasterTarget> target = measure_raster_target(page.get(), dpi);
            if (!target) return mutated ? JPDFIUM_ERR_CONTENT_MUTATED : JPDFIUM_ERR_INVALID;
            // Borrow the document scratch frame: the per-page and batch paths
            // share one frame, so a pass never holds two page-size bitmaps.
            FPDF_BITMAP frame = core.rasterFrame.acquire(target->w_px, target->h_px);
            if (!frame) return mutated ? JPDFIUM_ERR_CONTENT_MUTATED : JPDFIUM_ERR_NATIVE;
            const int32_t rc = rasterize_page_into(dw, i, page, frame, *target);
            if (rc != JPDFIUM_OK) return mutated ? JPDFIUM_ERR_CONTENT_MUTATED : rc;
            mutated = true;
        }
        return JPDFIUM_OK;
    } catch (...) {
        return mutated ? JPDFIUM_ERR_CONTENT_MUTATED : JPDFIUM_ERR_NATIVE;
    }
}
