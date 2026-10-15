// jpdfium_color.cpp - DeviceCMYK pipeline: CMYK JPEG images and OutputIntent ICC.

#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <memory>
#include <random>
#include <stdexcept>
#include <string>

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

// Convert a chunky sample buffer (1 or 3 components, 8bpc) to CMYK JPEG bytes.
// Returns a malloc'd buffer in *out_ptr (or nullptr) plus its length.
int32_t EncodeSamplesToCmykJpeg(const uint8_t* samples, int64_t samples_len, int32_t width,
                                int32_t height, int32_t components, int32_t quality, int32_t flags,
                                uint8_t** out_ptr, int64_t* out_len) {
    if (!samples || samples_len <= 0 || !out_ptr || !out_len) return JPDFIUM_ERR_INVALID;
    if (width <= 0 || height <= 0) return JPDFIUM_ERR_INVALID;
    if (components != 1 && components != 3) return JPDFIUM_ERR_INVALID;
    // The JPEG encoder caps each dimension at 65535, so rejecting the same bound
    // here keeps pixels * components inside int64 (and the CMYK buffer bounded).
    if (width > 65535 || height > 65535) return JPDFIUM_ERR_INVALID;

    const int64_t pixels = static_cast<int64_t>(width) * static_cast<int64_t>(height);
    if (samples_len < pixels * components) return JPDFIUM_ERR_INVALID;

    const bool extract_black = (flags & JPDFIUM_COLOR_IMAGE_NO_GCR) == 0;
    // Overwrite-allocated: every byte is written below, so skip the zero-fill memset.
    const size_t cmyk_len = static_cast<size_t>(pixels) * 4u;
    auto cmyk = std::make_unique_for_overwrite<uint8_t[]>(cmyk_len);
    for (int64_t i = 0; i < pixels; ++i) {
        uint8_t* dst = cmyk.get() + static_cast<size_t>(i) * 4u;
        if (components == 3) {
            const uint8_t* src = samples + static_cast<size_t>(i) * 3u;
            RgbToCmyk(src[0], src[1], src[2], extract_black, dst);
        } else {
            GrayToCmyk(samples[static_cast<size_t>(i)], extract_black, dst);
        }
    }

    uint8_t* rust_ptr = nullptr;
    int64_t rust_len = 0;
    const int32_t rc = jpdfium_rust_encode_cmyk_jpeg(cmyk.get(), static_cast<int64_t>(cmyk_len),
                                                     width, height, quality, &rust_ptr, &rust_len);
    if (rc != JPDFIUM_OK || !rust_ptr || rust_len <= 0) {
        if (rust_ptr) jpdfium_rust_free(rust_ptr);
        return rc == JPDFIUM_OK ? JPDFIUM_ERR_NATIVE : rc;
    }

    auto* copy = static_cast<uint8_t*>(std::malloc(static_cast<size_t>(rust_len)));
    if (!copy) {
        jpdfium_rust_free(rust_ptr);
        return JPDFIUM_ERR_NATIVE;
    }
    std::memcpy(copy, rust_ptr, static_cast<size_t>(rust_len));
    jpdfium_rust_free(rust_ptr);
    *out_ptr = copy;
    *out_len = rust_len;
    return JPDFIUM_OK;
}

#ifdef JPDFIUM_HAVE_EPDF_JPEG

// An image with an /SMask or /Mask loses that mask when its stream dict is
// replaced by a re-encoded JPEG (CMYK JPEG has no alpha channel), so those
bool ImageHasTransparency(FPDF_DOCUMENT doc, FPDF_PAGE page, FPDF_PAGEOBJECT obj) {
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

// Returns 1 when the image was converted, 0 when it was skipped, -1 on failure.
int32_t ConvertImageToCmykJpeg(FPDF_DOCUMENT doc, FPDF_PAGE page, FPDF_PAGEOBJECT obj,
                               int32_t quality, int32_t flags) {
    FPDF_IMAGEOBJ_METADATA meta{};
    if (!FPDFImageObj_GetImageMetadata(obj, page, &meta)) return 0;

    int32_t components = 0;
    if (meta.colorspace == FPDF_COLORSPACE_DEVICERGB) {
        components = 3;
    } else if (meta.colorspace == FPDF_COLORSPACE_DEVICEGRAY) {
        components = 1;
    } else {
        return 0;  // Already CMYK, indexed, ICC-based, separation, or unknown.
    }
    if (meta.bits_per_pixel != static_cast<unsigned int>(8 * components)) return 0;
    if (meta.width == 0 || meta.height == 0) return 0;
    // Over the encoder's per-dimension limit: skipping avoids a huge allocation
    // that the JPEG encoder would reject only after the buffer is filled.
    if (meta.width > 65535u || meta.height > 65535u) return 0;

    if (ImageHasTransparency(doc, page, obj)) return 0;

    const int64_t pixels = static_cast<int64_t>(meta.width) * static_cast<int64_t>(meta.height);
    if (pixels <= 0 || pixels > (INT64_MAX / 4)) return 0;
    const size_t sample_len = static_cast<size_t>(pixels) * static_cast<size_t>(components);
    // Overwrite-allocated: the decode below fills the whole buffer.
    auto samples = std::make_unique_for_overwrite<uint8_t[]>(sample_len);

    const unsigned long got = FPDFImageObj_GetImageDataDecoded(
        obj, samples.get(), static_cast<unsigned long>(sample_len));
    if (got != sample_len) return 0;  // Not plain 8bpc gray/RGB after all.

    uint8_t* jpeg = nullptr;
    int64_t jpeg_len = 0;
    const int32_t rc = EncodeSamplesToCmykJpeg(
        samples.get(), static_cast<int64_t>(sample_len), static_cast<int32_t>(meta.width),
        static_cast<int32_t>(meta.height), components, quality, flags, &jpeg, &jpeg_len);
    if (rc != JPDFIUM_OK) return -1;

    // Own the encoder output so a throw from the embedding call cannot leak it.
    std::unique_ptr<uint8_t, void (*)(void*)> jpeg_owner(jpeg, std::free);
    FPDF_PAGE pages[1] = {page};
    const FPDF_BOOL ok =
        EPDFImageObj_SetJpeg(pages, 1, obj, jpeg_owner.get(), static_cast<size_t>(jpeg_len));
    return ok ? 1 : -1;
}

#endif  // JPDFIUM_HAVE_EPDF_JPEG

#ifdef JPDFIUM_HAS_QPDF

// ICC header "data colour space" signature at offset 16 identifies the profile's
// component count; /N on a DestOutputProfile must match.
int32_t IccComponentCount(const uint8_t* icc, size_t len) {
    if (!icc || len < 128) return 0;
    const uint32_t declared = (static_cast<uint32_t>(icc[0]) << 24) |
                              (static_cast<uint32_t>(icc[1]) << 16) |
                              (static_cast<uint32_t>(icc[2]) << 8) | static_cast<uint32_t>(icc[3]);
    if (declared == 0 || declared > len) return 0;
    // The "acsp" signature at offset 36 is mandatory; without it the bytes are
    // not an ICC profile no matter what the declared size or colour space says.
    if (std::memcmp(icc + 36, "acsp", 4) != 0) return 0;

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
        const int fd = ::open(candidate.c_str(), O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW, 0600);
        if (fd < 0) continue;
        ::close(fd);
#endif
        return candidate;
    }
    throw std::runtime_error("cannot create a temporary output file");
}

// Preserves an existing destination's mode; a brand-new file keeps the staging
// file's owner-only mode rather than being widened to the umask default.
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
    // rename() cannot replace an existing file on Windows, so replace explicitly.
    if (::MoveFileExA(staging.c_str(), destination,
                      MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH)) {
        return true;
    }
    return std::rename(staging.c_str(), destination) == 0;
#else
    return std::rename(staging.c_str(), destination) == 0;
#endif
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

// In-memory variant: replaces /OutputIntents and returns the rewritten document.
std::shared_ptr<Buffer> InjectOutputIntent(const uint8_t* input, size_t input_len,
                                           const uint8_t* icc, size_t icc_len, const char* subtype,
                                           const char* condition_identifier, const char* info) {
    auto qpdf = QPDF::create();
    qpdf->processMemoryFile("jpdfium-color-input", reinterpret_cast<const char*>(input), input_len);
    qpdf->getRoot().replaceKey(
        "/OutputIntents", QPDFObjectHandle::newArray({BuildOutputIntent(
                              qpdf.get(), icc, icc_len, subtype, condition_identifier, info)}));

    QPDFWriter writer{*qpdf};
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
        return EncodeSamplesToCmykJpeg(samples, samples_len, width, height, components, quality,
                                       flags, out_ptr, out_len);
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
    if (!out_converted) return JPDFIUM_ERR_INVALID;
    *out_converted = 0;
    try {
#ifndef JPDFIUM_HAVE_EPDF_JPEG
        (void)doc_handle;
        (void)page_index;
        (void)quality;
        (void)flags;
        return JPDFIUM_ERR_NOT_FOUND;
#else
        if (jpdfium_has_rust() == 0) return JPDFIUM_ERR_NOT_FOUND;
        DocWrapper* wrapper = decodeDoc(doc_handle);
        if (!wrapper || !wrapper->core || !wrapper->core->doc) return JPDFIUM_ERR_INVALID;

        UniquePage page(FPDF_LoadPage(wrapper->core->doc, page_index));
        if (!page) return JPDFIUM_ERR_NOT_FOUND;

        const bool strict = (flags & JPDFIUM_COLOR_IMAGE_STRICT) != 0;
        int64_t converted = 0;
        int32_t failure = JPDFIUM_OK;
        const int object_count = FPDFPage_CountObjects(page.get());
        for (int i = 0; i < object_count; ++i) {
            FPDF_PAGEOBJECT obj = FPDFPage_GetObject(page.get(), i);
            if (!obj || FPDFPageObj_GetType(obj) != FPDF_PAGEOBJ_IMAGE) continue;

            const int32_t result =
                ConvertImageToCmykJpeg(wrapper->core->doc, page.get(), obj, quality, flags);
            if (result < 0) {
                if (strict) {
                    failure = JPDFIUM_ERR_NATIVE;
                    break;  // strict: abort the page; earlier images may already be rewritten
                }
                continue;  // best-effort: skip this image, keep the rest
            }
            converted += result;
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
                "/OutputIntents",
                QPDFObjectHandle::newArray({BuildOutputIntent(
                    qpdf.get(), reinterpret_cast<const uint8_t*>(icc), static_cast<size_t>(icc_len),
                    subtype, condition_identifier, info)}));
            QPDFWriter writer{*qpdf};
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

}  // extern "C"
