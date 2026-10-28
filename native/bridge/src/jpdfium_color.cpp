// jpdfium_color.cpp - DeviceCMYK pipeline: CMYK JPEG images and OutputIntent ICC.

#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <memory>
#include <random>
#include <sstream>
#include <stdexcept>
#include <string>
#include <unordered_set>
#include <vector>

#ifdef JPDFIUM_HAS_PUGIXML
#include <pugixml.hpp>
#endif

#if defined(_WIN32)
#include <fcntl.h>
#include <io.h>
#include <share.h>
#include <sys/stat.h>
#include <windows.h>
#else
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
#endif

#include "jpdfium.h"
#include "jpdfium_internal.h"
#include "jpdfium_rust.h"

#ifdef JPDFIUM_HAVE_EPDF_JPEG
#if __has_include(<epdf_edit.h>)
#include <epdf_edit.h>
#else
#include <fpdf_edit.h>
#endif
#endif

#ifdef JPDFIUM_HAS_QPDF
#include <qpdf/Buffer.hh>
#include <qpdf/QPDF.hh>
#include <qpdf/QPDFExc.hh>
#include <qpdf/QPDFObjectHandle.hh>
#include <qpdf/QPDFWriter.hh>
#endif

namespace {

// ---------------------------------------------------------------------------
// CMYK pixel maths

// Naive GCR. `extract_black` false keeps pure CMY (JPDFIUM_COLOR_IMAGE_NO_GCR):
// no K is generated, so neutral tones are built from equal CMY only.
void RgbToCmyk(uint8_t r, uint8_t g, uint8_t b, bool extract_black, uint8_t* out) {
    uint8_t c = static_cast<uint8_t>(255 - r);
    uint8_t m = static_cast<uint8_t>(255 - g);
    uint8_t y = static_cast<uint8_t>(255 - b);
    uint8_t k = 0;
    if (extract_black) {
        k = std::min(c, std::min(m, y));
        c = static_cast<uint8_t>(c - k);
        m = static_cast<uint8_t>(m - k);
        y = static_cast<uint8_t>(y - k);
    }
    out[0] = c;
    out[1] = m;
    out[2] = y;
    out[3] = k;
}

void GrayToCmyk(uint8_t v, bool extract_black, uint8_t* out) {
    if (extract_black) {
        out[0] = 0;
        out[1] = 0;
        out[2] = 0;
        out[3] = static_cast<uint8_t>(255 - v);
    } else {
        const uint8_t cmy = static_cast<uint8_t>(255 - v);
        out[0] = cmy;
        out[1] = cmy;
        out[2] = cmy;
        out[3] = 0;
    }
}

// Trilinear interpolation over the same RGB->CMYK grid the Java vector path
// builds. Keeping the order of operations identical makes an sRGB vector fill
// and an sRGB image pixel quantise to the same ink values.
void RgbToCmykLut(const uint8_t* lut, int32_t grid, uint8_t r, uint8_t g, uint8_t b, uint8_t* out) {
    const int32_t last = grid - 1;
    const float scale = static_cast<float>(last) / 255.0f;
    const float fr = static_cast<float>(r) * scale;
    const float fg = static_cast<float>(g) * scale;
    const float fb = static_cast<float>(b) * scale;
    const int32_t r0 = static_cast<int32_t>(fr);
    const int32_t g0 = static_cast<int32_t>(fg);
    const int32_t b0 = static_cast<int32_t>(fb);
    const int32_t r1 = std::min(r0 + 1, last);
    const int32_t g1 = std::min(g0 + 1, last);
    const int32_t b1 = std::min(b0 + 1, last);
    const float dr = fr - static_cast<float>(r0);
    const float dg = fg - static_cast<float>(g0);
    const float db = fb - static_cast<float>(b0);

    for (int32_t c = 0; c < 4; ++c) {
        const size_t i000 =
            ((static_cast<size_t>(r0) * grid + static_cast<size_t>(g0)) * grid + b0) * 4 + c;
        const size_t i100 =
            ((static_cast<size_t>(r1) * grid + static_cast<size_t>(g0)) * grid + b0) * 4 + c;
        const size_t i010 =
            ((static_cast<size_t>(r0) * grid + static_cast<size_t>(g1)) * grid + b0) * 4 + c;
        const size_t i110 =
            ((static_cast<size_t>(r1) * grid + static_cast<size_t>(g1)) * grid + b0) * 4 + c;
        const size_t i001 =
            ((static_cast<size_t>(r0) * grid + static_cast<size_t>(g0)) * grid + b1) * 4 + c;
        const size_t i101 =
            ((static_cast<size_t>(r1) * grid + static_cast<size_t>(g0)) * grid + b1) * 4 + c;
        const size_t i011 =
            ((static_cast<size_t>(r0) * grid + static_cast<size_t>(g1)) * grid + b1) * 4 + c;
        const size_t i111 =
            ((static_cast<size_t>(r1) * grid + static_cast<size_t>(g1)) * grid + b1) * 4 + c;

        const float v000 = static_cast<float>(lut[i000]);
        const float v100 = static_cast<float>(lut[i100]);
        const float v010 = static_cast<float>(lut[i010]);
        const float v110 = static_cast<float>(lut[i110]);
        const float v001 = static_cast<float>(lut[i001]);
        const float v101 = static_cast<float>(lut[i101]);
        const float v011 = static_cast<float>(lut[i011]);
        const float v111 = static_cast<float>(lut[i111]);

        const float v00 = v000 + (v100 - v000) * dr;
        const float v10 = v010 + (v110 - v010) * dr;
        const float v01 = v001 + (v101 - v001) * dr;
        const float v11 = v011 + (v111 - v011) * dr;
        const float v0 = v00 + (v10 - v00) * dg;
        const float v1 = v01 + (v11 - v01) * dg;
        const float value = v0 + (v1 - v0) * db;
        out[c] = static_cast<uint8_t>(std::clamp(static_cast<int32_t>(value + 0.5f), 0, 255));
    }
}

// Reusable buffers for one image-conversion pass. The image pass can see many
// XObjects; resizing vectors would zero-fill and reallocating would churn the
// allocator. Grow only when an image is larger than the previous one.
constexpr size_t kMaxRetainedScratchBytes = 32u * 1024u * 1024u;

struct ImageScratch {
    std::unique_ptr<uint8_t[]> samples;
    size_t samples_capacity = 0;
    std::unique_ptr<uint8_t[]> cmyk;
    size_t cmyk_capacity = 0;

    uint8_t* samples_for(size_t size) {
        if (size > samples_capacity) {
            samples = std::make_unique_for_overwrite<uint8_t[]>(size);
            samples_capacity = size;
        }
        return samples.get();
    }

    uint8_t* cmyk_for(size_t size) {
        if (size > cmyk_capacity) {
            cmyk = std::make_unique_for_overwrite<uint8_t[]>(size);
            cmyk_capacity = size;
        }
        return cmyk.get();
    }

    // Do not pin a very large image/RGBA buffer to a long-lived worker forever.
    void trim() {
        if (samples_capacity > kMaxRetainedScratchBytes) {
            samples.reset();
            samples_capacity = 0;
        }
        if (cmyk_capacity > kMaxRetainedScratchBytes) {
            cmyk.reset();
            cmyk_capacity = 0;
        }
    }
};

struct ImageScratchTrim {
    ImageScratch* scratch;
    ~ImageScratchTrim() {
        scratch->trim();
    }
};

// Convert a chunky sample buffer (1 or 3 components, 8bpc) to CMYK JPEG bytes.
// Returns a malloc'd buffer in *out_ptr (or nullptr) plus its length.
int32_t EncodeSamplesToCmykJpeg(const uint8_t* samples, int64_t samples_len, int32_t width,
                                int32_t height, int32_t components, int32_t quality, int32_t flags,
                                const uint8_t* lut, int32_t lut_grid, ImageScratch* scratch,
                                uint8_t** out_ptr, int64_t* out_len) {
    if (!samples || samples_len <= 0 || !out_ptr || !out_len) return JPDFIUM_ERR_INVALID;
    if (width <= 0 || height <= 0) return JPDFIUM_ERR_INVALID;
    if (components != 1 && components != 3) return JPDFIUM_ERR_INVALID;
    if (lut && (lut_grid < 2 || lut_grid > 256)) return JPDFIUM_ERR_INVALID;
    // The JPEG encoder caps each dimension at 65535, so rejecting the same bound
    // here keeps pixels * components inside int64 (and the CMYK buffer bounded).
    if (width > 65535 || height > 65535) return JPDFIUM_ERR_INVALID;

    const int64_t pixels = static_cast<int64_t>(width) * static_cast<int64_t>(height);
    if (samples_len < pixels * components) return JPDFIUM_ERR_INVALID;

    const bool extract_black = (flags & JPDFIUM_COLOR_IMAGE_NO_GCR) == 0;
    // Overwrite-allocated: every byte is written below, so skip the zero-fill memset.
    const size_t cmyk_len = static_cast<size_t>(pixels) * 4u;
    std::unique_ptr<uint8_t[]> local_cmyk;
    uint8_t* cmyk;
    if (scratch) {
        cmyk = scratch->cmyk_for(cmyk_len);
    } else {
        local_cmyk = std::make_unique_for_overwrite<uint8_t[]>(cmyk_len);
        cmyk = local_cmyk.get();
    }
    for (int64_t i = 0; i < pixels; ++i) {
        uint8_t* dst = cmyk + static_cast<size_t>(i) * 4u;
        if (components == 3) {
            const uint8_t* src = samples + static_cast<size_t>(i) * 3u;
            if (lut) {
                RgbToCmykLut(lut, lut_grid, src[0], src[1], src[2], dst);
            } else {
                RgbToCmyk(src[0], src[1], src[2], extract_black, dst);
            }
        } else {
            GrayToCmyk(samples[static_cast<size_t>(i)], extract_black, dst);
        }
    }

    uint8_t* rust_ptr = nullptr;
    int64_t rust_len = 0;
    const int32_t rc = jpdfium_rust_encode_cmyk_jpeg(cmyk, static_cast<int64_t>(cmyk_len), width,
                                                     height, quality, &rust_ptr, &rust_len);
    if (rc != JPDFIUM_OK || !rust_ptr || rust_len <= 0) {
        if (rust_ptr) jpdfium_rust_free(rust_ptr);
        return rc == JPDFIUM_OK ? JPDFIUM_ERR_NATIVE : rc;
    }

    // jpdfium_rust_* output is libc malloc; callers free it with std::free or
    // jpdfium_free_buffer, so ownership can transfer without a second copy.
    *out_ptr = rust_ptr;
    *out_len = rust_len;
    return JPDFIUM_OK;
}

#ifdef JPDFIUM_HAVE_EPDF_JPEG

// An image with an /SMask or /Mask loses that mask when its stream dict is
// replaced by a re-encoded JPEG (CMYK JPEG has no alpha channel), so those
// images are skipped.
[[maybe_unused]] bool ImageHasTransparency(FPDF_DOCUMENT doc, FPDF_PAGE page, FPDF_PAGEOBJECT obj) {
    FPDF_BITMAP bitmap = FPDFImageObj_GetRenderedBitmap(doc, page, obj);
    if (!bitmap) return true;  // Unknown -> conservatively skip.

    bool transparent = false;
    const int width = FPDFBitmap_GetWidth(bitmap);
    const int height = FPDFBitmap_GetHeight(bitmap);
    const int stride = FPDFBitmap_GetStride(bitmap);
    const int format = FPDFBitmap_GetFormat(bitmap);
    const uint8_t* buffer = static_cast<const uint8_t*>(FPDFBitmap_GetBuffer(bitmap));
    if (buffer && format == FPDFBitmap_BGRA && width > 0 && height > 0 && stride >= width * 4) {
        for (int y = 0; y < height && !transparent; ++y) {
            const uint8_t* row = buffer + static_cast<size_t>(y) * static_cast<size_t>(stride);
            for (int x = 0; x < width; ++x) {
                if (row[static_cast<size_t>(x) * 4u + 3u] != 255) {
                    transparent = true;
                    break;
                }
            }
        }
    }
    FPDFBitmap_Destroy(bitmap);
    return transparent;
}

// Some PDFs wrap a JPEG in another filter. GetImageDataDecoded() then returns
// the intermediate bytes, so decode through the image bitmap instead.
bool DecodeImageBitmap(FPDF_PAGEOBJECT obj, int width, int height, uint8_t* samples) {
    FPDF_BITMAP bitmap = FPDFImageObj_GetBitmap(obj);
    if (!bitmap) return false;
    const int bw = FPDFBitmap_GetWidth(bitmap);
    const int bh = FPDFBitmap_GetHeight(bitmap);
    const int stride = FPDFBitmap_GetStride(bitmap);
    const int format = FPDFBitmap_GetFormat(bitmap);
    const uint8_t* buffer = static_cast<const uint8_t*>(FPDFBitmap_GetBuffer(bitmap));
    int bytes_per_pixel = 0;
    if (format == FPDFBitmap_BGR) {
        bytes_per_pixel = 3;
    } else if (format == FPDFBitmap_BGRx || format == FPDFBitmap_BGRA) {
        bytes_per_pixel = 4;
    }
    bool ok = buffer && bytes_per_pixel != 0 && bw == width && bh == height &&
              stride >= width * bytes_per_pixel;
    if (ok) {
        for (int y = 0; y < height; ++y) {
            const uint8_t* row = buffer + static_cast<size_t>(y) * static_cast<size_t>(stride);
            for (int x = 0; x < width; ++x) {
                const uint8_t* pixel = row + static_cast<size_t>(x) * bytes_per_pixel;
                uint8_t* dst = samples + (static_cast<size_t>(y) * width + x) * 3;
                dst[0] = pixel[2];
                dst[1] = pixel[1];
                dst[2] = pixel[0];
            }
        }
    }
    FPDFBitmap_Destroy(bitmap);
    return ok;
}

// True when the source stream carries a DCT/JPEG payload, possibly wrapped in
// another filter such as FlateDecode. Only these sources are re-encoded by
// default; plain Flate line art and screenshots stay lossless.
bool ImageUsesDct(FPDF_PAGEOBJECT obj) {
    const int count = FPDFImageObj_GetImageFilterCount(obj);
    for (int i = 0; i < count; ++i) {
        char filter[32] = {};
        const unsigned long len = FPDFImageObj_GetImageFilter(obj, i, filter, sizeof(filter));
        if (len == 0 || len >= sizeof(filter)) continue;
        if (std::strcmp(filter, "DCTDecode") == 0 || std::strcmp(filter, "DCT") == 0) {
            return true;
        }
    }
    return false;
}

struct PendingImage {
    FPDF_PAGE page;
    FPDF_PAGEOBJECT object;
    uint8_t* jpeg;
    int64_t length;
};

// Returns 1 when the image was converted, 0 when it was skipped, -1 on failure.
int32_t ConvertImageToCmykJpeg(FPDF_DOCUMENT doc, FPDF_PAGE page, FPDF_PAGEOBJECT obj,
                               FPDF_PAGE* pages, int32_t page_count, int32_t quality, int32_t flags,
                               const uint8_t* lut, int32_t lut_grid, ImageScratch* scratch,
                               std::vector<PendingImage>* pending) {
    FPDF_IMAGEOBJ_METADATA meta{};
    if (!FPDFImageObj_GetImageMetadata(obj, page, &meta)) return 0;
    // DeviceGray is already ink-only (K) and valid in PDF/X. DeviceCMYK,
    // indexed, ICC-based, separation and unknown images are preserved.
    if (meta.colorspace != FPDF_COLORSPACE_DEVICERGB) return 0;
    constexpr int32_t components = 3;
    if (meta.bits_per_pixel != 24) return -2;
    if (meta.width == 0 || meta.height == 0) return 0;
    const bool uses_dct = ImageUsesDct(obj);
    // Lossless sources are only converted with an explicit opt-in; otherwise a
    // Flate screenshot or QR code would be silently turned into a lossy JPEG.
    if (!uses_dct && (flags & JPDFIUM_COLOR_IMAGE_CONVERT_LOSSLESS) == 0) return -2;
    // Over the encoder's per-dimension limit: skipping avoids a huge allocation
    // that the JPEG encoder would reject only after the buffer is filled.
    if (meta.width > 65535u || meta.height > 65535u) return -2;

#ifdef JPDFIUM_HAVE_CMYK_API
    // Only render when a mask is present; an opaque SMask is safe to drop.
    if (FPDFImageObj_HasTransparency(obj) && ImageHasTransparency(doc, page, obj)) {
        return -2;
    }
#else
    if (ImageHasTransparency(doc, page, obj)) return -2;
#endif

    const int64_t pixels = static_cast<int64_t>(meta.width) * static_cast<int64_t>(meta.height);
    constexpr int64_t kMaxImagePixels = 64LL * 1024 * 1024;
    if (pixels <= 0 || pixels > kMaxImagePixels || pixels > (INT64_MAX / 4)) return -2;
    const size_t sample_len = static_cast<size_t>(pixels) * static_cast<size_t>(components);
    std::unique_ptr<uint8_t[]> local_samples;
    uint8_t* samples;
    if (scratch) {
        samples = scratch->samples_for(sample_len);
    } else {
        local_samples = std::make_unique_for_overwrite<uint8_t[]>(sample_len);
        samples = local_samples.get();
    }

    // The raw decoder does not apply /Decode. Render lossless sources through
    // the bitmap path so inverted or remapped samples keep their meaning.
    if (!uses_dct) {
        if (!DecodeImageBitmap(obj, static_cast<int>(meta.width), static_cast<int>(meta.height),
                               samples)) {
            return -2;
        }
    } else {
        const unsigned long got =
            FPDFImageObj_GetImageDataDecoded(obj, samples, static_cast<unsigned long>(sample_len));
        if (got != sample_len && !DecodeImageBitmap(obj, static_cast<int>(meta.width),
                                                    static_cast<int>(meta.height), samples)) {
            return -2;
        }
    }

    uint8_t* jpeg = nullptr;
    int64_t jpeg_len = 0;
    const int32_t rc = EncodeSamplesToCmykJpeg(
        samples, static_cast<int64_t>(sample_len), static_cast<int32_t>(meta.width),
        static_cast<int32_t>(meta.height), components, quality, flags, lut, lut_grid, scratch,
        &jpeg, &jpeg_len);
    if (rc != JPDFIUM_OK) return -1;

    if (pending) {
        pending->push_back({page, obj, jpeg, jpeg_len});
        return 1;
    }

    // Own the encoder output so a throw from the embedding call cannot leak it.
    std::unique_ptr<uint8_t, void (*)(void*)> jpeg_owner(jpeg, std::free);
    const FPDF_BOOL ok = EPDFImageObj_SetJpeg(pages, page_count, obj, jpeg_owner.get(),
                                              static_cast<size_t>(jpeg_len));
    return ok ? 1 : -1;
}

// Recurse into forms only when dirty-stream write-back is available.
void ConvertImagesInObject(FPDF_DOCUMENT doc, FPDF_PAGE page, FPDF_PAGEOBJECT obj, FPDF_PAGE* pages,
                           int32_t page_count, int32_t quality, int32_t flags, const uint8_t* lut,
                           int32_t lut_grid, bool strict, int64_t* converted,
                           std::unordered_set<FPDF_PAGEOBJECT>* visited, int32_t* failure,
                           ImageScratch* scratch, std::vector<PendingImage>* pending) {
    if (!obj || *failure != JPDFIUM_OK) return;

    const int type = FPDFPageObj_GetType(obj);
    if (type == FPDF_PAGEOBJ_IMAGE) {
        const int32_t result = ConvertImageToCmykJpeg(doc, page, obj, pages, page_count, quality,
                                                      flags, lut, lut_grid, scratch, pending);
        if (result < 0) {
            if (strict) *failure = JPDFIUM_ERR_NATIVE;
            return;
        }
        *converted += result;
        return;
    }

    if (type != FPDF_PAGEOBJ_FORM) return;
#ifdef JPDFIUM_HAVE_CMYK_API
    if (!visited->insert(obj).second) return;

    const int count = FPDFFormObj_CountObjects(obj);
    if (count < 0) return;
    const int64_t before = *converted;
    for (int i = 0; i < count && *failure == JPDFIUM_OK; ++i) {
        FPDF_PAGEOBJECT child = FPDFFormObj_GetObject(obj, static_cast<unsigned long>(i));
        ConvertImagesInObject(doc, page, child, pages, page_count, quality, flags, lut, lut_grid,
                              strict, converted, visited, failure, scratch, pending);
    }
    if (*converted != before) {
        FPDFFormObj_MarkDirty(obj);
    }
#else
    (void)visited;
#endif
}

#endif  // JPDFIUM_HAVE_EPDF_JPEG

#ifdef JPDFIUM_HAS_QPDF

uint32_t ReadUint32Be(const uint8_t* p) {
    return (static_cast<uint32_t>(p[0]) << 24) | (static_cast<uint32_t>(p[1]) << 16) |
           (static_cast<uint32_t>(p[2]) << 8) | static_cast<uint32_t>(p[3]);
}

// ICC header "data colour space" signature at offset 16 identifies the profile's
// component count; /N on a DestOutputProfile must match.
int32_t IccComponentCount(const uint8_t* icc, size_t len) {
    if (!icc || len < 132) return 0;
    const uint32_t declared = ReadUint32Be(icc);
    if (declared < 132 || declared > len) return 0;
    // The "acsp" signature at offset 36 is mandatory; without it the bytes are
    // not an ICC profile no matter what the declared size or colour space says.
    if (std::memcmp(icc + 36, "acsp", 4) != 0) return 0;
    // Header-only blobs are not valid profiles; require a tag table whose
    // entries all point inside the declared profile.
    const uint32_t tag_count = ReadUint32Be(icc + 128);
    if (tag_count == 0 || tag_count > (declared - 132) / 12) return 0;
    for (uint32_t i = 0; i < tag_count; ++i) {
        const uint8_t* entry = icc + 132 + i * 12;
        const uint32_t offset = ReadUint32Be(entry + 4);
        const uint32_t size = ReadUint32Be(entry + 8);
        if (size == 0 || offset < 132 || offset > declared || size > declared - offset) {
            return 0;
        }
    }

    const char* space = reinterpret_cast<const char*>(icc + 16);
    if (std::memcmp(space, "CMYK", 4) == 0) return 4;
    if (std::memcmp(space, "RGB ", 4) == 0) return 3;
    if (std::memcmp(space, "GRAY", 4) == 0) return 1;
    return 0;
}

// Normalise a caller-supplied subtype to a PDF name ("GTS_PDFX" -> "/GTS_PDFX").
std::string NormaliseSubtype(const char* subtype) {
    if (!subtype || !*subtype) return "/GTS_PDFX";
    std::string value(subtype);
    if (value[0] != '/') value.insert(value.begin(), '/');
    return value;
}

std::string NormaliseString(const char* value, const char* fallback) {
    if (value && *value) return std::string(value);
    return std::string(fallback);
}

// Creates a unique sibling of out_path with an exclusive, owner-only create, so a
// planted or aliased path cannot truncate anything while qpdf streams the input.
std::string CreateUniqueSibling(const std::string& out_path) {
    const std::filesystem::path target(out_path);
    const std::filesystem::path dir =
        target.has_parent_path() ? target.parent_path() : std::filesystem::path(".");
    const std::string stem = target.filename().string();
    std::random_device rd;
    std::mt19937_64 rng(rd());
    for (int attempt = 0; attempt < 64; ++attempt) {
        const std::string candidate =
            (dir / (stem + "." + std::to_string(rng()) + ".jpdfium-tmp")).string();
#if defined(_WIN32)
        int fd = -1;
        if (::_sopen_s(&fd, candidate.c_str(), _O_WRONLY | _O_CREAT | _O_EXCL | _O_NOINHERIT,
                       _SH_DENYRW, _S_IREAD | _S_IWRITE) != 0) {
            continue;
        }
        ::_close(fd);
#else
        const int fd = ::open(candidate.c_str(), O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW, 0666);
        if (fd < 0) continue;
        ::close(fd);
#endif
        return candidate;
    }
    throw std::runtime_error("cannot create a temporary output file");
}

// Preserves an existing destination's mode; a brand-new file keeps the mode
// created by the normal umask-aware 0666 staging file.
void ApplyPublishMode(const std::string& staging, const char* destination) {
#ifndef _WIN32
    struct stat st;
    std::memset(&st, 0, sizeof(st));
    if (::stat(destination, &st) == 0) ::chmod(staging.c_str(), st.st_mode & 07777);
#else
    (void)staging;
    (void)destination;
#endif
}

// Publishes the staging file over the destination. The staging file survives a
// failed publish so a written document is never lost.
bool PublishStaged(const std::string& staging, const char* destination) {
#if defined(_WIN32)
    // MoveFileEx replaces an existing destination atomically. std::rename
    // cannot replace on Windows, so a fallback would only hide the failure.
    if (::MoveFileExA(staging.c_str(), destination,
                      MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH)) {
        return true;
    }
#else
    if (std::rename(staging.c_str(), destination) == 0) return true;
#endif
    // Keep the staging file so a fully written document is never lost, and
    // make the retained path visible to the caller's logs.
    std::fprintf(stderr, "jpdfium: failed to publish %s over %s; staging file retained\n",
                 staging.c_str(), destination);
    return false;
}

// Preserve the input stream representation. Letting QPDFWriter recompress
// unfiltered metadata streams produced zlib data without a /Filter entry on
// some Acrobat-generated files, which breaks XMP readers.
void ConfigureWriter(QPDFWriter& writer) {
    writer.setCompressStreams(false);
    writer.setDecodeLevel(qpdf_dl_none);
}

// PDF/X fixes the base PDF version: X-4 is PDF 1.6 and X-3 is PDF 1.3.
// Force the header instead of only raising it, so a PDF 1.7 source does not
// claim a version the selected standard does not allow.
void ConfigurePdfxWriter(QPDFWriter& writer, const char* version) {
    ConfigureWriter(writer);
    if (!version) return;
    const std::string value(version);
    if (value.find("PDF/X-4") != std::string::npos) {
        writer.forcePDFVersion("1.6");
    } else if (value.find("PDF/X-3") != std::string::npos) {
        writer.forcePDFVersion("1.3");
    }
}

// Builds the /OutputIntent dictionary embedding `icc` as the /DestOutputProfile.
QPDFObjectHandle BuildOutputIntent(QPDF* qpdf, const uint8_t* icc, size_t icc_len,
                                   const char* subtype, const char* condition_identifier,
                                   const char* info) {
    const int32_t components = IccComponentCount(icc, icc_len);
    if (components == 0) {
        throw std::runtime_error("output intent ICC profile is not a valid ICC profile");
    }

    QPDFObjectHandle profile =
        QPDFObjectHandle::newStream(qpdf, std::string(reinterpret_cast<const char*>(icc), icc_len));
    // Streams expose their dictionary through getDict(); replaceKey() on the
    // stream handle itself is ignored (qpdf warns and drops the key).
    profile.getDict().replaceKey("/N", QPDFObjectHandle::newInteger(components));

    const std::string subtype_name = NormaliseSubtype(subtype);
    QPDFObjectHandle intent = QPDFObjectHandle::newDictionary();
    intent.replaceKey("/Type", QPDFObjectHandle::newName("/OutputIntent"));
    intent.replaceKey("/S", QPDFObjectHandle::newName(subtype_name));
    const std::string cid = NormaliseString(condition_identifier, subtype_name.c_str() + 1);
    intent.replaceKey("/OutputConditionIdentifier", QPDFObjectHandle::newUnicodeString(cid));
    intent.replaceKey("/OutputCondition", QPDFObjectHandle::newUnicodeString(cid));
    intent.replaceKey("/RegistryName", QPDFObjectHandle::newUnicodeString("http://www.color.org"));
    intent.replaceKey("/Info", QPDFObjectHandle::newUnicodeString(
                                   NormaliseString(info, "JPDFium CMYK output intent")));
    intent.replaceKey("/DestOutputProfile", profile);
    return intent;
}

// Replace only intents with the same /S subtype. Unrelated intents (notably a
// PDF/A GTS_PDFA1 intent on a PDF/A source) must survive a PDF/X rewrite.
QPDFObjectHandle MergeOutputIntents(QPDF* qpdf, const uint8_t* icc, size_t icc_len,
                                    const char* subtype, const char* condition_identifier,
                                    const char* info) {
    const std::string subtype_name = NormaliseSubtype(subtype);
    QPDFObjectHandle merged = QPDFObjectHandle::newArray();
    QPDFObjectHandle existing = qpdf->getRoot().getKey("/OutputIntents");
    if (existing.isArray()) {
        for (QPDFObjectHandle item : existing.getArrayAsVector()) {
            bool replace = false;
            if (item.isDictionary()) {
                QPDFObjectHandle s = item.getKey("/S");
                replace = s.isName() && s.getName() == subtype_name;
            }
            if (!replace) merged.appendItem(item);
        }
    }
    merged.appendItem(BuildOutputIntent(qpdf, icc, icc_len, subtype, condition_identifier, info));
    return merged;
}

// In-memory variant: merges /OutputIntents and returns the rewritten document.
std::shared_ptr<Buffer> InjectOutputIntent(const uint8_t* input, size_t input_len,
                                           const uint8_t* icc, size_t icc_len, const char* subtype,
                                           const char* condition_identifier, const char* info) {
    auto qpdf = QPDF::create();
    qpdf->processMemoryFile("jpdfium-color-input", reinterpret_cast<const char*>(input), input_len);
    qpdf->getRoot().replaceKey(
        "/OutputIntents",
        MergeOutputIntents(qpdf.get(), icc, icc_len, subtype, condition_identifier, info));

    QPDFWriter writer{*qpdf};
    ConfigureWriter(writer);
    writer.setOutputMemory();
    writer.write();
    return writer.getBufferSharedPointer();
}

// ---------------------------------------------------------------------------
// PDF/X metadata

std::string XmlEscape(const std::string& value) {
    std::string out;
    out.reserve(value.size());
    for (char c : value) {
        switch (c) {
            case '&':
                out += "&amp;";
                break;
            case '<':
                out += "&lt;";
                break;
            case '>':
                out += "&gt;";
                break;
            case '"':
                out += "&quot;";
                break;
            case '\'':
                out += "&apos;";
                break;
            default:
                out += c;
                break;
        }
    }
    return out;
}

std::string BuildPdfxXmp(const std::string& version, const std::string& conformance) {
    const std::string v = XmlEscape(version);
    const std::string c = XmlEscape(conformance);
    return "<?xpacket begin=\"\xEF\xBB\xBF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n"
           "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">\n"
           " <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n"
           "  <rdf:Description rdf:about=\"\""
           " xmlns:pdfxid=\"http://www.npes.org/pdfx/ns/id/\""
           " xmlns:pdfx=\"http://ns.adobe.com/pdfx/1.3/\""
           " pdfxid:GTS_PDFXVersion=\"" +
           v +
           "\""
           " pdfx:GTS_PDFXVersion=\"" +
           v +
           "\""
           " pdfx:GTS_PDFXConformance=\"" +
           c +
           "\"/>\n"
           " </rdf:RDF>\n"
           "</x:xmpmeta>\n"
           "<?xpacket end=\"w\"?>";
}

// XMP packets are edited as XML, never with libstdc++'s backtracking regex
// engine: a multi-megabyte hostile packet could otherwise overflow the stack.
// Packets above the cap are replaced with a minimal valid PDF/X packet.
constexpr size_t kMaxXmpBytes = 8u * 1024u * 1024u;
constexpr int kMaxXmpDepth = 64;
constexpr const char* kRdfNs = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
constexpr const char* kPdfxNs = "http://ns.adobe.com/pdfx/1.3/";
constexpr const char* kPdfxIdNs = "http://www.npes.org/pdfx/ns/id/";

#ifdef JPDFIUM_HAS_PUGIXML
std::string XmlLocalName(const char* name) {
    const char* colon = std::strrchr(name, ':');
    return colon ? std::string(colon + 1) : std::string(name);
}

std::string XmlPrefix(const char* name) {
    const char* colon = std::strrchr(name, ':');
    return colon ? std::string(name, static_cast<size_t>(colon - name)) : std::string();
}

// Resolve a prefix through xmlns declarations on the node or its ancestors.
std::string XmlNamespaceUri(const pugi::xml_node& node, const std::string& prefix) {
    const std::string declaration = prefix.empty() ? "xmlns" : "xmlns:" + prefix;
    for (pugi::xml_node current = node; current; current = current.parent()) {
        pugi::xml_attribute attr = current.attribute(declaration.c_str());
        if (attr) return attr.value();
    }
    return {};
}

std::string XmlFindPrefix(const pugi::xml_node& node, const std::string& uri,
                          const std::string& preferred) {
    const std::string declaration = "xmlns:" + preferred;
    for (pugi::xml_node current = node; current; current = current.parent()) {
        pugi::xml_attribute attr = current.attribute(declaration.c_str());
        if (attr && uri == attr.value()) return preferred;
    }
    for (pugi::xml_node current = node; current; current = current.parent()) {
        for (pugi::xml_attribute attr : current.attributes()) {
            const std::string name = attr.name();
            if (name.rfind("xmlns:", 0) == 0 && uri == attr.value()) {
                return name.substr(6);
            }
        }
    }
    return {};
}

std::string XmlEnsurePrefix(pugi::xml_node node, const std::string& uri,
                            const std::string& preferred) {
    std::string prefix = XmlFindPrefix(node, uri, preferred);
    if (!prefix.empty()) return prefix;
    node.append_attribute(("xmlns:" + preferred).c_str()).set_value(uri.c_str());
    return preferred;
}

void XmlCollectDescriptions(const pugi::xml_node& node, int depth,
                            std::vector<pugi::xml_node>* out) {
    if (depth > kMaxXmpDepth) return;
    for (pugi::xml_node child : node.children()) {
        if (child.type() != pugi::node_element) continue;
        if (XmlLocalName(child.name()) == "Description") {
            const std::string ns = XmlNamespaceUri(child, XmlPrefix(child.name()));
            if (ns.empty() || ns == kRdfNs) out->push_back(child);
        }
        XmlCollectDescriptions(child, depth + 1, out);
    }
}

bool XmlUpdateAttribute(const pugi::xml_node& description, const std::string& local,
                        const std::string& uri1, const std::string& uri2,
                        const std::string& value) {
    bool found = false;
    for (pugi::xml_attribute attr : description.attributes()) {
        if (XmlLocalName(attr.name()) != local) continue;
        const std::string ns = XmlNamespaceUri(description, XmlPrefix(attr.name()));
        if (ns == uri1 || (!uri2.empty() && ns == uri2)) {
            attr.set_value(value.c_str());
            found = true;
        }
    }
    return found;
}

bool XmlUpdateElement(const pugi::xml_node& node, const std::string& local, const std::string& uri1,
                      const std::string& uri2, const std::string& value, int depth = 0) {
    if (depth > kMaxXmpDepth) return false;
    bool found = false;
    for (pugi::xml_node child : node.children()) {
        if (child.type() != pugi::node_element) continue;
        if (XmlLocalName(child.name()) == local) {
            const std::string ns = XmlNamespaceUri(child, XmlPrefix(child.name()));
            if (ns == uri1 || (!uri2.empty() && ns == uri2)) {
                child.text().set(value.c_str());
                found = true;
            }
        }
        if (XmlUpdateElement(child, local, uri1, uri2, value, depth + 1)) found = true;
    }
    return found;
}

bool XmlUpdateProperty(const pugi::xml_node& description, const std::string& local,
                       const std::string& uri1, const std::string& uri2, const std::string& value) {
    const bool attr = XmlUpdateAttribute(description, local, uri1, uri2, value);
    const bool element = XmlUpdateElement(description, local, uri1, uri2, value);
    return attr || element;
}

bool EditPdfxXml(pugi::xml_document* document, const std::string& version,
                 const std::string& conformance) {
    std::vector<pugi::xml_node> descriptions;
    XmlCollectDescriptions(*document, 0, &descriptions);
    if (descriptions.empty()) return false;

    // Update every metadata block so a second rdf:Description cannot keep a
    // stale, conflicting PDF/X version.
    bool has_pdfxid_version = false;
    bool has_pdfx_version = false;
    bool has_conformance = false;
    for (const pugi::xml_node& description : descriptions) {
        has_pdfxid_version |=
            XmlUpdateProperty(description, "GTS_PDFXVersion", kPdfxIdNs, {}, version);
        has_pdfx_version |= XmlUpdateProperty(description, "GTS_PDFXVersion", kPdfxNs, {}, version);
        has_conformance |=
            XmlUpdateProperty(description, "GTS_PDFXConformance", kPdfxNs, kPdfxIdNs, conformance);
    }

    pugi::xml_node first = descriptions.front();
    if (!has_pdfxid_version) {
        const std::string prefix = XmlEnsurePrefix(first, kPdfxIdNs, "pdfxid");
        first.append_attribute((prefix + ":GTS_PDFXVersion").c_str()).set_value(version.c_str());
    }
    if (!has_pdfx_version) {
        const std::string prefix = XmlEnsurePrefix(first, kPdfxNs, "pdfx");
        first.append_attribute((prefix + ":GTS_PDFXVersion").c_str()).set_value(version.c_str());
    }
    if (!has_conformance) {
        const std::string prefix = XmlEnsurePrefix(first, kPdfxNs, "pdfx");
        first.append_attribute((prefix + ":GTS_PDFXConformance").c_str())
            .set_value(conformance.c_str());
    }
    return true;
}
#endif

void UpdateOrCreatePdfxMetadata(QPDF* qpdf, const char* version, const char* conformance) {
    const std::string ver = NormaliseString(version, "");
    if (ver.empty()) return;
    const std::string conf = NormaliseString(conformance, ver.c_str());

    std::string xml;
    QPDFObjectHandle root = qpdf->getRoot();
    if (root.hasKey("/Metadata")) {
        QPDFObjectHandle metadata = root.getKey("/Metadata");
        if (metadata.isStream()) {
            std::shared_ptr<Buffer> buffer = metadata.getStreamData(qpdf_dl_all);
            if (buffer && buffer->getSize() > 0) {
                xml.assign(reinterpret_cast<const char*>(buffer->getBuffer()),
                           static_cast<size_t>(buffer->getSize()));
            }
        }
    }

    if (!xml.empty()) {
#ifdef JPDFIUM_HAS_PUGIXML
        if (xml.size() > kMaxXmpBytes) {
            // Pathological packet: do not hand it to any parser. Replace it
            // with the minimal PDF/X packet instead of risking stack or heap
            // exhaustion.
            xml = BuildPdfxXmp(ver, conf);
        } else {
            pugi::xml_document document;
            const pugi::xml_parse_result parsed =
                document.load_buffer(xml.data(), xml.size(),
                                     pugi::parse_default | pugi::parse_pi |
                                         pugi::parse_declaration | pugi::parse_comments,
                                     pugi::encoding_auto);
            std::string edited;
            if (parsed && EditPdfxXml(&document, ver, conf)) {
                std::ostringstream serialized;
                document.save(serialized, "", pugi::format_raw | pugi::format_no_declaration,
                              pugi::encoding_utf8);
                edited = serialized.str();
            }
            xml = edited.empty() ? BuildPdfxXmp(ver, conf) : edited;
        }
#else
        xml = BuildPdfxXmp(ver, conf);
#endif
    } else {
        xml = BuildPdfxXmp(ver, conf);
    }

    QPDFObjectHandle metadata = QPDFObjectHandle::newStream(qpdf, xml);
    metadata.getDict().replaceKey("/Type", QPDFObjectHandle::newName("/Metadata"));
    metadata.getDict().replaceKey("/Subtype", QPDFObjectHandle::newName("/XML"));
    root.replaceKey("/Metadata", metadata);
}

std::shared_ptr<Buffer> InjectPdfx(const uint8_t* input, size_t input_len, const uint8_t* icc,
                                   size_t icc_len, const char* version, const char* conformance,
                                   const char* condition_identifier, const char* info) {
    auto qpdf = QPDF::create();
    qpdf->processMemoryFile("jpdfium-pdfx-input", reinterpret_cast<const char*>(input), input_len);
    qpdf->getRoot().replaceKey(
        "/OutputIntents",
        MergeOutputIntents(qpdf.get(), icc, icc_len, "GTS_PDFX", condition_identifier, info));
    UpdateOrCreatePdfxMetadata(qpdf.get(), version, conformance);

    QPDFWriter writer{*qpdf};
    ConfigurePdfxWriter(writer, version);
    writer.setOutputMemory();
    writer.write();
    return writer.getBufferSharedPointer();
}

#endif  // JPDFIUM_HAS_QPDF

}  // namespace

extern "C" {

JPDFIUM_EXPORT uint32_t jpdfium_color_capabilities(void) JPDFIUM_NOEXCEPT {
    uint32_t caps = 0;
#ifdef JPDFIUM_HAVE_CMYK_API
    caps |= JPDFIUM_COLOR_CAP_CMYK_VECTOR;
#endif
#if defined(JPDFIUM_HAVE_EPDF_JPEG)
    if (jpdfium_has_rust() != 0) caps |= JPDFIUM_COLOR_CAP_CMYK_IMAGE;
#endif
#ifdef JPDFIUM_HAS_QPDF
    caps |= JPDFIUM_COLOR_CAP_OUTPUT_INTENT;
#endif
    return caps;
}

JPDFIUM_EXPORT int32_t jpdfium_color_samples_to_cmyk_jpeg(const uint8_t* samples,
                                                          int64_t samples_len, int32_t width,
                                                          int32_t height, int32_t components,
                                                          int32_t quality, int32_t flags,
                                                          uint8_t** out_ptr, int64_t* out_len) {
    if (out_ptr) *out_ptr = nullptr;
    if (out_len) *out_len = 0;
    try {
        if (jpdfium_has_rust() == 0) return JPDFIUM_ERR_NOT_FOUND;
        thread_local ImageScratch scratch;
        ImageScratchTrim trim{&scratch};
        return EncodeSamplesToCmykJpeg(samples, samples_len, width, height, components, quality,
                                       flags, nullptr, 0, &scratch, out_ptr, out_len);
    } catch (const std::exception& e) {
        std::fprintf(stderr, "jpdfium color samples: %s\n", e.what());
        return JPDFIUM_ERR_NATIVE;
    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

JPDFIUM_EXPORT int32_t jpdfium_color_page_images_to_cmyk(int64_t doc_handle, int32_t page_index,
                                                         int32_t quality, int32_t flags,
                                                         int64_t* out_converted) {
    return jpdfium_color_page_images_to_cmyk_ex(doc_handle, page_index, quality, flags, nullptr, 0,
                                                out_converted);
}

JPDFIUM_EXPORT int32_t jpdfium_color_page_images_to_cmyk_ex(int64_t doc_handle, int32_t page_index,
                                                            int32_t quality, int32_t flags,
                                                            const uint8_t* lut, int32_t lut_grid,
                                                            int64_t* out_converted) {
    if (!out_converted) return JPDFIUM_ERR_INVALID;
    *out_converted = 0;
    if (lut && (lut_grid < 2 || lut_grid > 256)) return JPDFIUM_ERR_INVALID;
    try {
#ifndef JPDFIUM_HAVE_EPDF_JPEG
        (void)doc_handle;
        (void)page_index;
        (void)quality;
        (void)flags;
        (void)lut;
        (void)lut_grid;
        return JPDFIUM_ERR_NOT_FOUND;
#else
        if (jpdfium_has_rust() == 0) return JPDFIUM_ERR_NOT_FOUND;
        DocWrapper* wrapper = decodeDoc(doc_handle);
        if (!wrapper || !wrapper->core || !wrapper->core->doc) return JPDFIUM_ERR_INVALID;

        UniquePage page(FPDF_LoadPage(wrapper->core->doc, page_index));
        if (!page) return JPDFIUM_ERR_NOT_FOUND;
        FPDF_PAGE page_handles[1] = {page.get()};

        const bool strict = (flags & JPDFIUM_COLOR_IMAGE_STRICT) != 0;
        int64_t converted = 0;
        int32_t failure = JPDFIUM_OK;
        std::unordered_set<FPDF_PAGEOBJECT> visited_forms;
        // Retain decode/encode scratch across pages on the same worker thread.
        // The buffers only grow to the largest image seen, so multi-page jobs do
        // not reallocate for every page.
        thread_local ImageScratch scratch;
        ImageScratchTrim trim{&scratch};
        const int object_count = FPDFPage_CountObjects(page.get());
        for (int i = 0; i < object_count && failure == JPDFIUM_OK; ++i) {
            FPDF_PAGEOBJECT obj = FPDFPage_GetObject(page.get(), i);
            ConvertImagesInObject(wrapper->core->doc, page.get(), obj, page_handles, 1, quality,
                                  flags, lut, lut_grid, strict, &converted, &visited_forms,
                                  &failure, &scratch, nullptr);
        }

        if (failure != JPDFIUM_OK) return failure;
        if (converted > 0 && !FPDFPage_GenerateContent(page.get())) return JPDFIUM_ERR_NATIVE;

        *out_converted = converted;
        return JPDFIUM_OK;
#endif
    } catch (const std::exception& e) {
        std::fprintf(stderr, "jpdfium color images: %s\n", e.what());
        return JPDFIUM_ERR_NATIVE;
    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

JPDFIUM_EXPORT int32_t jpdfium_color_document_images_to_cmyk_ex(int64_t doc_handle, int32_t quality,
                                                                int32_t flags, const uint8_t* lut,
                                                                int32_t lut_grid,
                                                                int64_t* out_converted) {
    if (!out_converted) return JPDFIUM_ERR_INVALID;
    *out_converted = 0;
    if (lut && (lut_grid < 2 || lut_grid > 256)) return JPDFIUM_ERR_INVALID;
    try {
#ifndef JPDFIUM_HAVE_EPDF_JPEG
        (void)doc_handle;
        (void)quality;
        (void)flags;
        (void)lut;
        (void)lut_grid;
        return JPDFIUM_ERR_NOT_FOUND;
#else
        if (jpdfium_has_rust() == 0) return JPDFIUM_ERR_NOT_FOUND;
        DocWrapper* wrapper = decodeDoc(doc_handle);
        if (!wrapper || !wrapper->core || !wrapper->core->doc) return JPDFIUM_ERR_INVALID;

        const int page_count = FPDF_GetPageCount(wrapper->core->doc);
        std::vector<UniquePage> pages;
        std::vector<FPDF_PAGE> page_handles;
        pages.reserve(static_cast<size_t>(page_count));
        page_handles.reserve(static_cast<size_t>(page_count));
        for (int i = 0; i < page_count; ++i) {
            UniquePage loaded(FPDF_LoadPage(wrapper->core->doc, i));
            if (!loaded) return JPDFIUM_ERR_NOT_FOUND;
            page_handles.push_back(loaded.get());
            pages.push_back(std::move(loaded));
        }

        const bool strict = (flags & JPDFIUM_COLOR_IMAGE_STRICT) != 0;
        int32_t failure = JPDFIUM_OK;
        int64_t converted = 0;
        std::unordered_set<FPDF_PAGEOBJECT> visited_forms;
        std::vector<PendingImage> pending;
        thread_local ImageScratch scratch;
        ImageScratchTrim trim{&scratch};
        for (int i = 0; i < page_count && failure == JPDFIUM_OK; ++i) {
            const int objects = FPDFPage_CountObjects(page_handles[static_cast<size_t>(i)]);
            for (int j = 0; j < objects && failure == JPDFIUM_OK; ++j) {
                FPDF_PAGEOBJECT obj = FPDFPage_GetObject(page_handles[static_cast<size_t>(i)], j);
                ConvertImagesInObject(wrapper->core->doc, page_handles[static_cast<size_t>(i)], obj,
                                      page_handles.data(), page_count, quality, flags, lut,
                                      lut_grid, strict, &converted, &visited_forms, &failure,
                                      &scratch, &pending);
            }
        }
        if (failure != JPDFIUM_OK) {
            for (PendingImage& image : pending) std::free(image.jpeg);
            return failure;
        }

        std::unordered_set<FPDF_PAGE> changed_pages;
        for (PendingImage& image : pending) {
            FPDF_PAGE page = image.page;
            if (!EPDFImageObj_SetJpeg(&page, 1, image.object, image.jpeg,
                                      static_cast<size_t>(image.length))) {
                for (PendingImage& remaining : pending) std::free(remaining.jpeg);
                return JPDFIUM_ERR_NATIVE;
            }
            std::free(image.jpeg);
            image.jpeg = nullptr;
            changed_pages.insert(image.page);
        }
        for (FPDF_PAGE page : changed_pages) {
            if (!FPDFPage_GenerateContent(page)) return JPDFIUM_ERR_NATIVE;
        }
        *out_converted = converted;
        return JPDFIUM_OK;
#endif
    } catch (const std::exception& e) {
        std::fprintf(stderr, "jpdfium color document images: %s\n", e.what());
        return JPDFIUM_ERR_NATIVE;
    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

JPDFIUM_EXPORT int32_t jpdfium_color_output_intent_bytes(const uint8_t* input, int64_t input_len,
                                                         const uint8_t* icc, int64_t icc_len,
                                                         const char* subtype,
                                                         const char* condition_identifier,
                                                         const char* info, uint8_t** output,
                                                         int64_t* output_len) {
    if (output) *output = nullptr;
    if (output_len) *output_len = 0;
    try {
#ifndef JPDFIUM_HAS_QPDF
        (void)input;
        (void)input_len;
        (void)icc;
        (void)icc_len;
        (void)subtype;
        (void)condition_identifier;
        (void)info;
        return JPDFIUM_ERR_NOT_FOUND;
#else
        if (!input || input_len <= 0 || !icc || icc_len <= 0 || !output || !output_len) {
            return JPDFIUM_ERR_INVALID;
        }
        std::shared_ptr<Buffer> buffer = InjectOutputIntent(
            input, static_cast<size_t>(input_len), reinterpret_cast<const uint8_t*>(icc),
            static_cast<size_t>(icc_len), subtype, condition_identifier, info);
        if (!buffer) return JPDFIUM_ERR_NATIVE;

        const int64_t length = static_cast<int64_t>(buffer->getSize());
        auto* copy = static_cast<uint8_t*>(std::malloc(static_cast<size_t>(length)));
        if (!copy) return JPDFIUM_ERR_NATIVE;
        std::memcpy(copy, buffer->getBuffer(), static_cast<size_t>(length));
        *output = copy;
        *output_len = length;
        return JPDFIUM_OK;
#endif
    } catch (const std::exception& e) {
        std::fprintf(stderr, "jpdfium color output intent: %s\n", e.what());
        if (output) *output = nullptr;
        if (output_len) *output_len = 0;
        return JPDFIUM_ERR_NATIVE;
    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

JPDFIUM_EXPORT int32_t jpdfium_color_output_intent_file(const char* in_path, const char* out_path,
                                                        const uint8_t* icc, int64_t icc_len,
                                                        const char* subtype,
                                                        const char* condition_identifier,
                                                        const char* info) {
    try {
#ifndef JPDFIUM_HAS_QPDF
        (void)in_path;
        (void)out_path;
        (void)icc;
        (void)icc_len;
        (void)subtype;
        (void)condition_identifier;
        (void)info;
        return JPDFIUM_ERR_NOT_FOUND;
#else
        if (!in_path || !out_path || !icc || icc_len <= 0) return JPDFIUM_ERR_INVALID;

        // Write a unique sibling then replace the destination, so an aliased or
        // in-place target can never be truncated while qpdf still streams it.
        const std::string target = CreateUniqueSibling(out_path);
        try {
            auto qpdf = QPDF::create();
            qpdf->processFile(in_path);
            qpdf->getRoot().replaceKey(
                "/OutputIntents", MergeOutputIntents(qpdf.get(), icc, static_cast<size_t>(icc_len),
                                                     subtype, condition_identifier, info));
            QPDFWriter writer{*qpdf};
            ConfigureWriter(writer);
            writer.setOutputFilename(target.c_str());
            writer.write();
        } catch (...) {
            std::remove(target.c_str());
            throw;
        }

        std::error_code ec;
        if (std::filesystem::file_size(target, ec) == 0 || ec) {
            std::remove(target.c_str());
            return JPDFIUM_ERR_IO;
        }
        ApplyPublishMode(target, out_path);
        if (!PublishStaged(target, out_path)) return JPDFIUM_ERR_IO;
        return JPDFIUM_OK;
#endif
    } catch (const std::exception& e) {
        std::fprintf(stderr, "jpdfium color output intent file: %s\n", e.what());
        return JPDFIUM_ERR_NATIVE;
    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

// Write a GTS_PDFX output intent and create/update the catalog XMP packet.
JPDFIUM_EXPORT int32_t jpdfium_color_pdfx_bytes(const uint8_t* input, int64_t input_len,
                                                const uint8_t* icc, int64_t icc_len,
                                                const char* version, const char* conformance,
                                                const char* condition_identifier, const char* info,
                                                uint8_t** output, int64_t* output_len) {
    if (output) *output = nullptr;
    if (output_len) *output_len = 0;
    try {
#ifndef JPDFIUM_HAS_QPDF
        (void)input;
        (void)input_len;
        (void)icc;
        (void)icc_len;
        (void)version;
        (void)conformance;
        (void)condition_identifier;
        (void)info;
        return JPDFIUM_ERR_NOT_FOUND;
#else
        if (!input || input_len <= 0 || !icc || icc_len <= 0 || !version || !*version || !output ||
            !output_len) {
            return JPDFIUM_ERR_INVALID;
        }
        std::shared_ptr<Buffer> buffer =
            InjectPdfx(input, static_cast<size_t>(input_len), icc, static_cast<size_t>(icc_len),
                       version, conformance, condition_identifier, info);
        if (!buffer) return JPDFIUM_ERR_NATIVE;

        const int64_t length = static_cast<int64_t>(buffer->getSize());
        auto* copy = static_cast<uint8_t*>(std::malloc(static_cast<size_t>(length)));
        if (!copy) return JPDFIUM_ERR_NATIVE;
        std::memcpy(copy, buffer->getBuffer(), static_cast<size_t>(length));
        *output = copy;
        *output_len = length;
        return JPDFIUM_OK;
#endif
    } catch (const std::exception& e) {
        std::fprintf(stderr, "jpdfium pdfx: %s\n", e.what());
        if (output) *output = nullptr;
        if (output_len) *output_len = 0;
        return JPDFIUM_ERR_NATIVE;
    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

JPDFIUM_EXPORT int32_t jpdfium_color_pdfx_file(const char* in_path, const char* out_path,
                                               const uint8_t* icc, int64_t icc_len,
                                               const char* version, const char* conformance,
                                               const char* condition_identifier, const char* info) {
    try {
#ifndef JPDFIUM_HAS_QPDF
        (void)in_path;
        (void)out_path;
        (void)icc;
        (void)icc_len;
        (void)version;
        (void)conformance;
        (void)condition_identifier;
        (void)info;
        return JPDFIUM_ERR_NOT_FOUND;
#else
        if (!in_path || !out_path || !icc || icc_len <= 0 || !version || !*version) {
            return JPDFIUM_ERR_INVALID;
        }
        const std::string target = CreateUniqueSibling(out_path);
        try {
            auto qpdf = QPDF::create();
            qpdf->processFile(in_path);
            qpdf->getRoot().replaceKey(
                "/OutputIntents", MergeOutputIntents(qpdf.get(), icc, static_cast<size_t>(icc_len),
                                                     "GTS_PDFX", condition_identifier, info));
            UpdateOrCreatePdfxMetadata(qpdf.get(), version, conformance);
            QPDFWriter writer{*qpdf};
            ConfigurePdfxWriter(writer, version);
            writer.setOutputFilename(target.c_str());
            writer.write();
        } catch (...) {
            std::remove(target.c_str());
            throw;
        }

        std::error_code ec;
        if (std::filesystem::file_size(target, ec) == 0 || ec) {
            std::remove(target.c_str());
            return JPDFIUM_ERR_IO;
        }
        ApplyPublishMode(target, out_path);
        if (!PublishStaged(target, out_path)) return JPDFIUM_ERR_IO;
        return JPDFIUM_OK;
#endif
    } catch (const std::exception& e) {
        std::fprintf(stderr, "jpdfium pdfx file: %s\n", e.what());
        return JPDFIUM_ERR_NATIVE;
    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

}  // extern "C"
