// jpdfium_redact_image.cpp - pixel erasure for redaction.
//
// Rasterizes the covered portion of partially overlapped images so only
// visible pixels are removed.

#include <epdf_redact.h>
#include <fpdf_annot.h>
#include <fpdf_edit.h>
#include <fpdf_flatten.h>
#include <fpdf_save.h>
#include <fpdf_text.h>
#include <fpdfview.h>

#include <algorithm>
#include <atomic>
#include <cctype>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <functional>
#include <limits>
#include <map>
#include <mutex>
#include <optional>
#include <set>
#include <span>
#include <sstream>
#include <string>
#include <type_traits>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#include "jpdfium.h"
#include "jpdfium_internal.h"
#include "jpdfium_redact_internal.h"

namespace {

std::atomic<jpdfium_image_decoder_fn> g_imageDecoder{nullptr};

// Stream filters PDFium undoes before the image decoder runs.
bool isStreamFilter(const std::string& f) {
    return f == "FlateDecode" || f == "Fl" || f == "LZWDecode" || f == "LZW" ||
           f == "ASCII85Decode" || f == "A85" || f == "ASCIIHexDecode" || f == "AHx" ||
           f == "RunLengthDecode" || f == "RL" || f == "Crypt";
}

// Image filters PDFium decodes itself.
bool isPdfiumImageFilter(const std::string& f) {
    return f == "DCTDecode" || f == "DCT" || f == "CCITTFaxDecode" || f == "CCF" ||
           f == "JBIG2Decode" || f == "JPXDecode";
}

// Only JPEG XL, HEIF and AVIF bytes reach the external decoder. Anything else
// (a PDF, an SVG) never does: libvips can load those through its own PDFium.
bool hasExternalImageSignature(const std::vector<uint8_t>& d) {
    if (d.size() >= 2 && d[0] == 0xFF && d[1] == 0x0A) return true;  // JPEG XL codestream
    static const uint8_t kJxlBox[12] = {0, 0, 0, 0x0C, 'J', 'X', 'L', ' ', 0x0D, 0x0A, 0x87, 0x0A};
    if (d.size() >= 12 && std::memcmp(d.data(), kJxlBox, 12) == 0) return true;
    if (d.size() >= 12 && std::memcmp(d.data() + 4, "ftyp", 4) == 0) {
        static const char* const kBrands[] = {"heic", "heix", "hevc", "hevx", "heim", "heis",
                                              "hevm", "hevs", "mif1", "msf1", "avif", "avis"};
        for (const char* brand : kBrands) {
            if (std::memcmp(d.data() + 8, brand, 4) == 0) return true;
        }
    }
    return false;
}

// Peak memory per foreign image is several copies of the frame (bridge,
// decoder, Java), so bound the pixels before allocating any of them. Same
// ceiling as PdfImageOptimizer's per-image budget.
constexpr uint64_t kMaxExternalDecodePixels = 100'000'000;

uint32_t readLe32(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) | (static_cast<uint32_t>(p[3]) << 24);
}

// The image's own pixels as BGRA when its last filter is one PDFium cannot
// decode and the registered decoder returns exactly srcW x srcH. nullptr
// otherwise, and the caller keeps the PDFium path (which fails closed). The
// image's /SMask, if any, does not survive this path: SetBitmap rebuilds the
// dictionary, and PDFium cannot load the mask of a base it cannot decode.
FPDF_BITMAP decodeWithExternalDecoder(FPDF_PAGEOBJECT imageObj, unsigned int srcW,
                                      unsigned int srcH) {
    jpdfium_image_decoder_fn decoder = g_imageDecoder.load(std::memory_order_acquire);
    if (!decoder) return nullptr;
    int filterCount = FPDFImageObj_GetImageFilterCount(imageObj);
    bool foreign = false;
    for (int i = 0; i < filterCount; i++) {
        char name[64] = {0};
        unsigned long n = FPDFImageObj_GetImageFilter(imageObj, i, name, sizeof(name));
        if (n == 0 || n > sizeof(name)) return nullptr;
        std::string f(name);
        if (isStreamFilter(f)) continue;
        // PDFium stops at the first non-stream filter; only a last-position
        // filter it has no codec for is ours to decode.
        if (i != filterCount - 1 || isPdfiumImageFilter(f)) return nullptr;
        foreign = true;
    }
    if (!foreign) return nullptr;
    // Over budget: no decode, so the image fails closed and is removed whole.
    const uint64_t pixels = static_cast<uint64_t>(srcW) * srcH;
    if (pixels > kMaxExternalDecodePixels) return nullptr;
    const uint64_t pixelBytes = pixels * 4u;
    std::vector<uint8_t> data;
    std::vector<uint8_t> frame;
    try {
        unsigned long len = FPDFImageObj_GetImageDataDecoded(imageObj, nullptr, 0);
        // Encoded data never needs more room than the raw frame (plus slack
        // for container headers on tiny images); larger is not a real image.
        if (len == 0 || len > pixelBytes + 65536u) return nullptr;
        data.resize(len);
        if (FPDFImageObj_GetImageDataDecoded(imageObj, data.data(), len) != len) return nullptr;
        if (!hasExternalImageSignature(data)) return nullptr;
        frame.resize(static_cast<size_t>(pixelBytes) + 8u);
    } catch (const std::bad_alloc&) {
        return nullptr;
    }
    if (decoder(data.data(), static_cast<int64_t>(data.size()), frame.data(),
                static_cast<int64_t>(frame.size())) != 1) {
        return nullptr;
    }
    // A frame of other dimensions would put the erase on the wrong pixels.
    if (readLe32(frame.data()) != srcW || readLe32(frame.data() + 4) != srcH) return nullptr;
    FPDF_BITMAP bmp = FPDFBitmap_Create(static_cast<int>(srcW), static_cast<int>(srcH), 1);
    if (!bmp) return nullptr;
    auto* dst = static_cast<uint8_t*>(FPDFBitmap_GetBuffer(bmp));
    const int stride = FPDFBitmap_GetStride(bmp);
    if (!dst || stride < static_cast<int>(srcW) * 4) {
        FPDFBitmap_Destroy(bmp);
        return nullptr;
    }
    const uint8_t* src = frame.data() + 8;
    for (unsigned int y = 0; y < srcH; y++) {
        uint8_t* row = dst + static_cast<size_t>(y) * stride;
        for (unsigned int x = 0; x < srcW; x++, src += 4) {
            row[x * 4 + 0] = src[2];
            row[x * 4 + 1] = src[1];
            row[x * 4 + 2] = src[0];
            row[x * 4 + 3] = src[3];
        }
    }
    return bmp;
}

constexpr uint64_t kFnvOffset = 14695981039346656037ull;

uint64_t fnv1a(uint64_t hash, const uint8_t* data, size_t len) {
    for (size_t i = 0; i < len; i++) {
        hash ^= data[i];
        hash *= 1099511628211ull;
    }
    return hash;
}

// Remembers a gray image SetBitmap just stored, for compactErasedGrayImages.
void recordGrayImage(std::vector<ErasedGrayImage>* grayImages, const uint8_t* gray, int w, int h,
                     int stride) {
    if (!grayImages) return;
    uint64_t hash = kFnvOffset;
    for (int y = 0; y < h; y++) {
        hash = fnv1a(hash, gray + static_cast<size_t>(y) * stride, static_cast<size_t>(w));
    }
    try {
        grayImages->push_back({static_cast<uint32_t>(w), static_cast<uint32_t>(h), hash});
    } catch (const std::bad_alloc&) {
        // Only costs the compaction: the image is already erased and stored.
    }
}

// Stores |bmp| as the image in the smallest form SetBitmap offers without
// losing a pixel: Gray when every pixel is gray, BGR when every pixel is
// opaque, else BGRA (RGB plus an /SMask). SetBitmap writes BGRA as an
// /SMask even when it is all opaque, which grew every erased image.
bool setCompactBitmap(FPDF_PAGEOBJECT imageObj, FPDF_BITMAP bmp, int fmt, int w, int h, int stride,
                      const uint8_t* buf, std::vector<ErasedGrayImage>* grayImages) {
    const int bpp = fmt == FPDFBitmap_Gray ? 1 : (fmt == FPDFBitmap_BGR ? 3 : 4);
    if (bpp == 1) {
        if (FPDFImageObj_SetBitmap(nullptr, 0, imageObj, bmp) == 0) return false;
        recordGrayImage(grayImages, buf, w, h, stride);
        return true;
    }
    bool opaque = true;
    bool gray = true;
    for (int y = 0; y < h && (opaque || gray); y++) {
        const uint8_t* p = buf + static_cast<size_t>(y) * stride;
        for (int x = 0; x < w; x++, p += bpp) {
            if (fmt == FPDFBitmap_BGRA && p[3] != 255) opaque = false;
            if (p[0] != p[1] || p[1] != p[2]) gray = false;
        }
    }
    if (!opaque || (!gray && fmt != FPDFBitmap_BGRA)) {
        return FPDFImageObj_SetBitmap(nullptr, 0, imageObj, bmp) != 0;
    }
    const int outFmt = gray ? FPDFBitmap_Gray : FPDFBitmap_BGR;
    const int outBpp = gray ? 1 : 3;
    FPDF_BITMAP out = FPDFBitmap_CreateEx(w, h, outFmt, nullptr, 0);
    if (!out) return FPDFImageObj_SetBitmap(nullptr, 0, imageObj, bmp) != 0;
    auto* dst = static_cast<uint8_t*>(FPDFBitmap_GetBuffer(out));
    const int dstStride = FPDFBitmap_GetStride(out);
    if (!dst || dstStride < w * outBpp) {
        FPDFBitmap_Destroy(out);
        return FPDFImageObj_SetBitmap(nullptr, 0, imageObj, bmp) != 0;
    }
    for (int y = 0; y < h; y++) {
        const uint8_t* s = buf + static_cast<size_t>(y) * stride;
        uint8_t* d = dst + static_cast<size_t>(y) * dstStride;
        for (int x = 0; x < w; x++, s += bpp, d += outBpp) {
            std::memcpy(d, s, static_cast<size_t>(outBpp));
        }
    }
    bool ok = FPDFImageObj_SetBitmap(nullptr, 0, imageObj, out) != 0;
    if (ok && gray) recordGrayImage(grayImages, dst, w, h, dstStride);
    FPDFBitmap_Destroy(out);
    return ok;
}

}  // namespace

void jpdfium_set_image_decoder(jpdfium_image_decoder_fn decoder) JPDFIUM_NOEXCEPT {
    g_imageDecoder.store(decoder, std::memory_order_release);
}

// Erases pixels whose center falls in |rects| (page space). Pixel (ix, iy)
// maps to unit (ix/w, 1 - iy/h): row 0 is the top, so the mirror erases the
// visible half. Rendered at source size because GetBitmap drops /SMask and
// SetBitmap rebuilds the image from whatever bitmap it gets. An encoding
// PDFium cannot decode goes through the registered image decoder instead.
// Only the affected pixel bbox is scanned; already-erased pixels are skipped.
// Returns false when the caller must remove the whole image.
bool eraseImagePixels(FPDF_DOCUMENT doc, FPDF_PAGE page, FPDF_PAGEOBJECT imageObj,
                      const FS_MATRIX& imgMatrix, std::span<const FS_RECTF> rects, uint32_t argb,
                      bool* pixelsChanged, std::vector<ErasedGrayImage>* grayImages) {
    // |pixelsChanged| accumulates: it may be shared across images, so a call
    // that changes nothing must not clear an earlier call's true.
    if (rects.empty()) return true;
    unsigned int srcW = 0, srcH = 0;
    if (!FPDFImageObj_GetImagePixelSize(imageObj, &srcW, &srcH) || srcW == 0 || srcH == 0) {
        return false;
    }
    FPDF_BITMAP bmp = decodeWithExternalDecoder(imageObj, srcW, srcH);
    FS_MATRIX original;
    if (!bmp && FPDFPageObj_GetMatrix(imageObj, &original)) {
        // Unit matrix: 1 source pixel == 1 unit -> render at source size.
        const FS_MATRIX unit{static_cast<float>(srcW), 0.0f, 0.0f,
                             static_cast<float>(srcH), 0.0f, 0.0f};
        if (FPDFPageObj_SetMatrix(imageObj, &unit)) {
            bmp = FPDFImageObj_GetRenderedBitmap(doc, page, imageObj);
            if (!FPDFPageObj_SetMatrix(imageObj, &original)) {
                // Never ship an image left at the temporary unit matrix: the
                // caller removes the whole image instead.
                if (bmp) FPDFBitmap_Destroy(bmp);
                return false;
            }
        }
    }
    if (!bmp) {
        // Renderer unavailable: fall back to the mask-less base bitmap. The
        // erase still removes pixels; a soft mask (if any) cannot survive
        // this path.
        bmp = FPDFImageObj_GetBitmap(imageObj);
    }
    if (!bmp) return false;
    int w = FPDFBitmap_GetWidth(bmp);
    int h = FPDFBitmap_GetHeight(bmp);
    int fmt = FPDFBitmap_GetFormat(bmp);
    int stride = FPDFBitmap_GetStride(bmp);
    void* buf = FPDFBitmap_GetBuffer(bmp);
    if (!buf || w <= 0 || h <= 0) {
        FPDFBitmap_Destroy(bmp);
        return false;
    }
    int bpp = 4;
    if (fmt == FPDFBitmap_Gray) {
        bpp = 1;
    } else if (fmt == FPDFBitmap_BGR) {
        bpp = 3;
    } else if (fmt == FPDFBitmap_BGRx || fmt == FPDFBitmap_BGRA) {
        bpp = 4;
    } else {
        FPDFBitmap_Destroy(bmp);
        return false;
    }
    unsigned int r = (argb >> 16) & 0xFF;
    unsigned int g = (argb >> 8) & 0xFF;
    unsigned int b = argb & 0xFF;

    // Pixel-center -> page map (row-vector convention, row 0 = top):
    //   px = pa*ix + pc*iy + pe,  py = pb*ix + pd*iy + pf
    const double pa = static_cast<double>(imgMatrix.a) / w;
    const double pc = -static_cast<double>(imgMatrix.c) / h;
    const double pb = static_cast<double>(imgMatrix.b) / w;
    const double pd = -static_cast<double>(imgMatrix.d) / h;
    const double pe = imgMatrix.e + imgMatrix.c + 0.5 * pa + 0.5 * pc;
    const double pf = imgMatrix.f + imgMatrix.d + 0.5 * pb + 0.5 * pd;

    int minIx = 0, minIy = 0, maxIx = w - 1, maxIy = h - 1;
    const double det = pa * pd - pb * pc;
    if (std::abs(det) < 1e-12) {
        // Degenerate (zero-area) image: it paints nothing; nothing to erase.
        FPDFBitmap_Destroy(bmp);
        return true;
    }
    {
        // Inverse linear map applied to each erase rect's corners gives the
        // pixel-space bbox that can possibly be affected.
        const double ia = pd / det, ib = -pc / det;
        const double ic = -pb / det, id = pa / det;
        double pMinX = std::numeric_limits<double>::max();
        double pMinY = std::numeric_limits<double>::max();
        double pMaxX = std::numeric_limits<double>::lowest();
        double pMaxY = std::numeric_limits<double>::lowest();
        for (const FS_RECTF& rc : rects) {
            const double cx[4] = {rc.left, rc.right, rc.right, rc.left};
            const double cy[4] = {rc.bottom, rc.bottom, rc.top, rc.top};
            for (int k = 0; k < 4; k++) {
                double dx = cx[k] - pe;
                double dy = cy[k] - pf;
                double px = ia * dx + ib * dy;
                double py = ic * dx + id * dy;
                if (px < pMinX) pMinX = px;
                if (py < pMinY) pMinY = py;
                if (px > pMaxX) pMaxX = px;
                if (py > pMaxY) pMaxY = py;
            }
        }
        // Clamp to the bitmap; an erase rect far outside leaves an empty box.
        // Reject out-of-range boxes before the int cast: a tiny image scale
        // maps far margins past INT_MAX, and the cast would be undefined.
        if (pMinX > w - 1 || pMinY > h - 1 || pMaxX < 0 || pMaxY < 0) {
            FPDFBitmap_Destroy(bmp);
            return true;
        }
        minIx = static_cast<int>(std::max(0.0, std::floor(pMinX)));
        minIy = static_cast<int>(std::max(0.0, std::floor(pMinY)));
        maxIx = static_cast<int>(std::min(static_cast<double>(w - 1), std::ceil(pMaxX)));
        maxIy = static_cast<int>(std::min(static_cast<double>(h - 1), std::ceil(pMaxY)));
        if (maxIx < minIx || maxIy < minIy) {
            FPDFBitmap_Destroy(bmp);
            return true;
        }
    }

    bool changed = false;
    for (int iy = minIy; iy <= maxIy; iy++) {
        const double rowX = pc * iy + pe;
        const double rowY = pd * iy + pf;
        const size_t rowOffset = static_cast<size_t>(iy) * stride;
        for (int ix = minIx; ix <= maxIx; ix++) {
            const double px = pa * ix + rowX;
            const double py = pb * ix + rowY;
            bool inside = false;
            for (const FS_RECTF& rc : rects) {
                if (px >= rc.left && px <= rc.right && py >= rc.bottom && py <= rc.top) {
                    inside = true;
                    break;
                }
            }
            if (!inside) continue;
            size_t offset = rowOffset + static_cast<size_t>(ix) * bpp;
            if (offset + static_cast<size_t>(bpp) > static_cast<size_t>(stride) * h) continue;
            uint8_t* p = static_cast<uint8_t*>(buf) + offset;
            uint8_t want[4] = {0, 0, 0, 255};
            // BGRx has an unused 4th byte: compare/write only the color bytes.
            size_t n = (fmt == FPDFBitmap_BGRx) ? 3u : static_cast<size_t>(bpp);
            if (bpp == 1) {
                want[0] = static_cast<uint8_t>((r * 299 + g * 587 + b * 114) / 1000);
            } else {
                want[0] = static_cast<uint8_t>(b);
                want[1] = static_cast<uint8_t>(g);
                want[2] = static_cast<uint8_t>(r);
            }
            if (std::memcmp(p, want, n) == 0) continue;  // already erased
            std::memcpy(p, want, n);
            changed = true;
        }
    }
    bool ok = true;
    if (changed) {
        ok = setCompactBitmap(imageObj, bmp, fmt, w, h, stride, static_cast<const uint8_t*>(buf),
                              grayImages);
        if (ok && pixelsChanged) *pixelsChanged = true;
    }
    FPDFBitmap_Destroy(bmp);
    return ok;
}

#ifdef JPDFIUM_HAS_QPDF

#include <qpdf/Buffer.hh>
#include <qpdf/QPDF.hh>
#include <qpdf/QPDFObjectHandle.hh>
#include <qpdf/QPDFWriter.hh>

#include "jpdfium_qpdf_writer_lock.h"

namespace {

// [/Indexed /DeviceRGB 255 lookup] whose lookup maps every index i to (i,i,i):
// what SetBitmap writes for a gray bitmap. Only then is index == gray level.
bool isGrayRamp(QPDFObjectHandle cs) {
    if (!cs.isArray() || cs.getArrayNItems() != 4) return false;
    if (!cs.getArrayItem(0).isNameAndEquals("/Indexed") ||
        !cs.getArrayItem(1).isNameAndEquals("/DeviceRGB")) {
        return false;
    }
    QPDFObjectHandle hival = cs.getArrayItem(2);
    if (!hival.isInteger() || hival.getIntValue() != 255) return false;
    QPDFObjectHandle lookup = cs.getArrayItem(3);
    std::string table;
    if (lookup.isString()) {
        table = lookup.getStringValue();
    } else if (lookup.isStream()) {
        std::shared_ptr<Buffer> data = lookup.getStreamData(qpdf_dl_generalized);
        table.assign(reinterpret_cast<const char*>(data->getBuffer()), data->getSize());
    } else {
        return false;
    }
    if (table.size() != 768) return false;
    for (size_t i = 0; i < 768; i++) {
        if (static_cast<uint8_t>(table[i]) != i / 3) return false;
    }
    return true;
}

// Rewrites every recorded gray image found in |q|. Returns true when at least
// one image changed; the caller chooses memory or file output.
bool rewriteErasedGrayImages(QPDF& q, const DocCore& core) {
    bool changed = false;
    for (QPDFObjectHandle obj : q.getAllObjects()) {
        if (!obj.isStream()) continue;
        QPDFObjectHandle dict = obj.getDict();
        if (!dict.getKey("/Subtype").isNameAndEquals("/Image")) continue;
        QPDFObjectHandle wObj = dict.getKey("/Width");
        QPDFObjectHandle hObj = dict.getKey("/Height");
        QPDFObjectHandle bpc = dict.getKey("/BitsPerComponent");
        if (!wObj.isInteger() || !hObj.isInteger() || !bpc.isInteger() || bpc.getIntValue() != 8) {
            continue;
        }
        // SetBitmap never writes these for a gray image; their presence
        // means the image is not one of ours.
        if (dict.hasKey("/SMask") || dict.hasKey("/Mask") || dict.hasKey("/Decode") ||
            dict.hasKey("/ImageMask")) {
            continue;
        }
        const long long w = wObj.getIntValue();
        const long long h = hObj.getIntValue();
        if (w <= 0 || h <= 0) continue;
        bool sizeMatches = false;
        for (const ErasedGrayImage& img : core.erasedGrayImages) {
            if (img.width == w && img.height == h) {
                sizeMatches = true;
                break;
            }
        }
        if (!sizeMatches || !isGrayRamp(dict.getKey("/ColorSpace"))) continue;
        std::shared_ptr<Buffer> data = obj.getStreamData(qpdf_dl_generalized);
        const size_t n = static_cast<size_t>(w) * static_cast<size_t>(h);
        if (data->getSize() != n) continue;
        const uint8_t* gray = data->getBuffer();
        const uint64_t hash = fnv1a(kFnvOffset, gray, n);
        bool ours = false;
        for (const ErasedGrayImage& img : core.erasedGrayImages) {
            if (img.width == w && img.height == h && img.hash == hash) {
                ours = true;
                break;
            }
        }
        if (!ours) continue;

        bool bilevel = true;
        for (size_t i = 0; i < n && bilevel; i++) bilevel = gray[i] == 0 || gray[i] == 255;
        std::string samples;
        if (bilevel) {
            // DeviceGray at 1 bpc: 0 is black, 1 is white; rows pad to a byte.
            const size_t rowBytes = (static_cast<size_t>(w) + 7) / 8;
            samples.assign(rowBytes * static_cast<size_t>(h), '\0');
            for (long long y = 0; y < h; y++) {
                const uint8_t* row = gray + static_cast<size_t>(y) * w;
                char* out = samples.data() + static_cast<size_t>(y) * rowBytes;
                for (long long x = 0; x < w; x++) {
                    if (row[x]) out[x >> 3] |= static_cast<char>(0x80 >> (x & 7));
                }
            }
            dict.replaceKey("/BitsPerComponent", QPDFObjectHandle::newInteger(1));
        } else {
            samples.assign(reinterpret_cast<const char*>(gray), n);
        }
        // Unfiltered here; QPDFWriter Flate-compresses it on write.
        obj.replaceStreamData(samples, QPDFObjectHandle::newNull(), QPDFObjectHandle::newNull());
        dict.replaceKey("/ColorSpace", QPDFObjectHandle::newName("/DeviceGray"));
        changed = true;
    }
    return changed;
}

}  // namespace

bool compactErasedGrayImages(const DocCore& core, std::vector<uint8_t>& bytes) {
    if (core.erasedGrayImages.empty() || bytes.empty()) return false;
    try {
        QPDF q;
        q.processMemoryFile("erased-images.pdf", reinterpret_cast<const char*>(bytes.data()),
                            bytes.size());
        if (!rewriteErasedGrayImages(q, core)) return false;
        QPDFWriter writer(q);
        writer.setOutputMemory();
        {
            jpdfium_qpdf_internal::WriterLock lock{-1};
            writer.write();
        }
        std::shared_ptr<Buffer> out = writer.getBufferSharedPointer();
        if (!out || out->getSize() == 0) return false;
        bytes.assign(out->getBuffer(), out->getBuffer() + out->getSize());
        return true;
    } catch (...) {
        // PDFium's bytes are already a complete redaction; keep them.
        return false;
    }
}

bool compactErasedGrayImagesFile(const DocCore& core, const char* inputPath, std::FILE* output) {
    if (core.erasedGrayImages.empty() || !inputPath || !*inputPath || !output) return false;
    try {
        // processFile keeps stream data file-backed; QPDFWriter writes straight
        // to |output|, so the save never holds a document-sized buffer.
        QPDF q;
        q.processFile(inputPath);
        if (!rewriteErasedGrayImages(q, core)) return false;
        try {
            QPDFWriter writer(q);
            // The staging FILE* stays owned by the caller, which closes it and
            // discards the file when this function returns false.
            writer.setOutputFile("erased-images-out", output, false);
            jpdfium_qpdf_internal::WriterLock lock{-1};
            writer.write();
            return true;
        } catch (...) {
            return false;
        }
    } catch (...) {
        // PDFium's file is already a complete redaction; keep it.
        return false;
    }
}

#else  // !JPDFIUM_HAS_QPDF

bool compactErasedGrayImages(const DocCore&, std::vector<uint8_t>&) {
    return false;
}

bool compactErasedGrayImagesFile(const DocCore&, const char*, std::FILE*) {
    return false;
}

#endif  // JPDFIUM_HAS_QPDF
