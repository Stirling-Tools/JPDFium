// jpdfium_qpdf.cpp - In-process qpdf structural optimization (FFM, no CLI).
//
// jpdfium_qpdf_optimize drives the bundled qpdf library entirely in memory via
// its QPDF/QPDFWriter C++ API.

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <expected>
#include <memory>
#include <span>

#include "jpdfium.h"

#ifdef JPDFIUM_HAS_QPDF

#include <qpdf/Constants.h>

#include <qpdf/Buffer.hh>
#include <qpdf/QPDF.hh>
#include <qpdf/QPDFExc.hh>
#include <qpdf/QPDFPageDocumentHelper.hh>
#include <qpdf/QPDFWriter.hh>

namespace {

// compressionLevel is intentionally ignored. The only qpdf knob for the zlib
// level, Pl_Flate::setCompressionLevel, mutates process-global state shared by
// every Pl_Flate instance in deflate mode -- not a per-QPDFWriter setting.
// Wiring it here would let concurrent requests with different levels silently
// clobber one another. The parameter is retained purely for ABI stability;
// revisit only if a per-instance alternative appears in qpdf.
std::expected<std::shared_ptr<Buffer>, std::string> optimize(std::span<const uint8_t> input,
                                                             int32_t flags,
                                                             int32_t objectStreamMode,
                                                             int32_t streamDataMode,
                                                             int32_t decodeLevel) {
    try {
        auto qpdf = QPDF::create();
        qpdf->processMemoryFile("jpdfium-input", reinterpret_cast<const char*>(input.data()),
                                input.size());

        QPDFWriter w{*qpdf};
        w.setOutputMemory();
        if (flags & JPDFIUM_QPDF_LINEARIZE) w.setLinearization(true);
        if (flags & JPDFIUM_QPDF_RECOMPRESS_FLATE) w.setRecompressFlate(true);
        if (flags & JPDFIUM_QPDF_COMPRESS_STREAMS) w.setCompressStreams(true);
        if (flags & JPDFIUM_QPDF_PRESERVE_UNREFERENCED) w.setPreserveUnreferencedObjects(true);
        if (flags & JPDFIUM_QPDF_NORMALIZE_CONTENT) w.setContentNormalization(true);
        if (objectStreamMode >= 0)
            w.setObjectStreamMode(static_cast<qpdf_object_stream_e>(objectStreamMode));
        if (streamDataMode >= 0)
            w.setStreamDataMode(static_cast<qpdf_stream_data_e>(streamDataMode));
        if (decodeLevel >= 0)
            w.setDecodeLevel(static_cast<qpdf_stream_decode_level_e>(decodeLevel));

        w.write();
        return w.getBufferSharedPointer();
    } catch (const std::exception& e) {
        return std::unexpected(e.what());
    }
}

std::expected<std::shared_ptr<Buffer>, std::string> mergePdfs(const uint8_t* const* inputs,
                                                              const int64_t* inputLens,
                                                              int32_t count) {
    try {
        if (!inputs || !inputLens || count <= 0) {
            return std::unexpected("invalid merge inputs");
        }
        auto dest = QPDF::create();
        dest->emptyPDF();
        QPDFPageDocumentHelper dest_pdh{*dest};

        for (int32_t i = 0; i < count; ++i) {
            const uint8_t* in = inputs[i];
            int64_t len = inputLens[i];
            if (!in || len <= 0) continue;

            auto src = QPDF::create();
            src->processMemoryFile("merge-src", reinterpret_cast<const char*>(in),
                                   static_cast<size_t>(len));
            QPDFPageDocumentHelper src_pdh{*src};
            for (auto& page : src_pdh.getAllPages()) {
                dest_pdh.addPage(page, false);
            }
        }

        QPDFWriter w{*dest};
        w.setOutputMemory();
        w.setObjectStreamMode(qpdf_o_generate);
        w.setCompressStreams(true);
        w.write();
        return w.getBufferSharedPointer();
    } catch (const std::exception& e) {
        return std::unexpected(e.what());
    }
}

std::expected<std::shared_ptr<Buffer>, std::string> extractPages(std::span<const uint8_t> input,
                                                                 const int32_t* pageIndices,
                                                                 int32_t pageCount) {
    try {
        if (!pageIndices || pageCount <= 0) {
            return std::unexpected("invalid page indices");
        }
        auto src = QPDF::create();
        src->processMemoryFile("extract-src", reinterpret_cast<const char*>(input.data()),
                               input.size());
        QPDFPageDocumentHelper src_pdh{*src};
        auto allPages = src_pdh.getAllPages();
        int32_t totalPages = static_cast<int32_t>(allPages.size());

        auto dest = QPDF::create();
        dest->emptyPDF();
        QPDFPageDocumentHelper dest_pdh{*dest};

        for (int32_t i = 0; i < pageCount; ++i) {
            int32_t idx = pageIndices[i];
            if (idx >= 0 && idx < totalPages) {
                dest_pdh.addPage(allPages[idx], false);
            }
        }

        QPDFWriter w{*dest};
        w.setOutputMemory();
        w.setObjectStreamMode(qpdf_o_generate);
        w.setCompressStreams(true);
        w.write();
        return w.getBufferSharedPointer();
    } catch (const std::exception& e) {
        return std::unexpected(e.what());
    }
}

std::expected<std::shared_ptr<Buffer>, std::string> encryptPdf(std::span<const uint8_t> input,
                                                               const char* userPassword,
                                                               const char* ownerPassword,
                                                               int32_t permissions,
                                                               int32_t keyLength) {
    try {
        auto qpdf = QPDF::create();
        qpdf->processMemoryFile("encrypt-in", reinterpret_cast<const char*>(input.data()),
                                input.size());

        QPDFWriter w{*qpdf};
        w.setOutputMemory();

        std::string userPass = userPassword ? userPassword : "";
        std::string ownerPass = ownerPassword ? ownerPassword : userPass;

        bool allowPrint =
            (permissions & JPDFIUM_PERM_PRINT_HIGH) || (permissions & JPDFIUM_PERM_PRINT_LOW);
        bool allowExtract = (permissions & JPDFIUM_PERM_EXTRACT) != 0;
        bool allowModify = (permissions & JPDFIUM_PERM_MODIFY) != 0;
        bool allowAccessibility = (permissions & JPDFIUM_PERM_ACCESSIBILITY) != 0;
        bool allowAssemble = (permissions & JPDFIUM_PERM_ASSEMBLE) != 0;
        bool allowAnnotate = (permissions & JPDFIUM_PERM_ANNOTATE) != 0;
        bool allowFillForms = (permissions & JPDFIUM_PERM_FILL_FORMS) != 0;
        qpdf_r3_print_e printMode = allowPrint ? qpdf_r3p_full : qpdf_r3p_none;

        if (keyLength == 256) {
            w.setR6EncryptionParameters(userPass.c_str(), ownerPass.c_str(), allowAccessibility,
                                        allowExtract, allowAssemble, allowAnnotate, allowFillForms,
                                        allowModify, printMode, true);
        } else {
            w.setR5EncryptionParameters(userPass.c_str(), ownerPass.c_str(), allowAccessibility,
                                        allowExtract, allowAssemble, allowAnnotate, allowFillForms,
                                        allowModify, printMode, true);
        }

        w.write();
        return w.getBufferSharedPointer();
    } catch (const std::exception& e) {
        return std::unexpected(e.what());
    }
}

std::expected<std::shared_ptr<Buffer>, std::string> decryptPdf(std::span<const uint8_t> input,
                                                               const char* password) {
    try {
        auto qpdf = QPDF::create();
        if (password && *password) {
            qpdf->processMemoryFile("decrypt-in", reinterpret_cast<const char*>(input.data()),
                                    input.size(), password);
        } else {
            qpdf->processMemoryFile("decrypt-in", reinterpret_cast<const char*>(input.data()),
                                    input.size());
        }

        QPDFWriter w{*qpdf};
        w.setOutputMemory();
        w.write();
        return w.getBufferSharedPointer();
    } catch (const std::exception& e) {
        return std::unexpected(e.what());
    }
}

}  // namespace

extern "C" {

JPDFIUM_EXPORT int32_t jpdfium_qpdf_optimize(const uint8_t* input, int64_t inputLen,
                                             uint8_t** output, int64_t* outputLen, int32_t flags,
                                             int32_t /*compressionLevel*/, int32_t objectStreamMode,
                                             int32_t streamDataMode, int32_t decodeLevel) {
    if (!input || inputLen <= 0 || !output || !outputLen) return -1;
    *output = nullptr;
    *outputLen = 0;

    auto result = optimize({input, static_cast<size_t>(inputLen)}, flags, objectStreamMode,
                           streamDataMode, decodeLevel);
    if (!result) {
        std::fprintf(stderr, "jpdfium qpdf optimize: %s\n", result.error().c_str());
        return -1;
    }

    auto& buf = *result;
    *outputLen = static_cast<int64_t>(buf->getSize());
    *output = static_cast<uint8_t*>(malloc(static_cast<size_t>(*outputLen)));
    if (!*output) {
        *outputLen = 0;
        return -1;
    }
    std::memcpy(*output, buf->getBuffer(), static_cast<size_t>(*outputLen));
    return 0;
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_merge(const uint8_t* const* inputs, const int64_t* inputLens,
                                          int32_t count, uint8_t** output, int64_t* outputLen) {
    if (!inputs || !inputLens || count <= 0 || !output || !outputLen) return -1;
    *output = nullptr;
    *outputLen = 0;

    auto result = mergePdfs(inputs, inputLens, count);
    if (!result) {
        std::fprintf(stderr, "jpdfium qpdf merge: %s\n", result.error().c_str());
        return -1;
    }

    auto& buf = *result;
    *outputLen = static_cast<int64_t>(buf->getSize());
    *output = static_cast<uint8_t*>(malloc(static_cast<size_t>(*outputLen)));
    if (!*output) {
        *outputLen = 0;
        return -1;
    }
    std::memcpy(*output, buf->getBuffer(), static_cast<size_t>(*outputLen));
    return 0;
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_extract_pages(const uint8_t* input, int64_t inputLen,
                                                  const int32_t* pageIndices, int32_t pageCount,
                                                  uint8_t** output, int64_t* outputLen) {
    if (!input || inputLen <= 0 || !pageIndices || pageCount <= 0 || !output || !outputLen)
        return -1;
    *output = nullptr;
    *outputLen = 0;

    auto result = extractPages({input, static_cast<size_t>(inputLen)}, pageIndices, pageCount);
    if (!result) {
        std::fprintf(stderr, "jpdfium qpdf extract: %s\n", result.error().c_str());
        return -1;
    }

    auto& buf = *result;
    *outputLen = static_cast<int64_t>(buf->getSize());
    *output = static_cast<uint8_t*>(malloc(static_cast<size_t>(*outputLen)));
    if (!*output) {
        *outputLen = 0;
        return -1;
    }
    std::memcpy(*output, buf->getBuffer(), static_cast<size_t>(*outputLen));
    return 0;
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_encrypt(const uint8_t* input, int64_t inputLen,
                                            const char* userPassword, const char* ownerPassword,
                                            int32_t permissions, int32_t keyLength,
                                            uint8_t** output, int64_t* outputLen) {
    if (!input || inputLen <= 0 || !output || !outputLen) return -1;
    *output = nullptr;
    *outputLen = 0;

    auto result = encryptPdf({input, static_cast<size_t>(inputLen)}, userPassword, ownerPassword,
                             permissions, keyLength);
    if (!result) {
        std::fprintf(stderr, "jpdfium qpdf encrypt: %s\n", result.error().c_str());
        return -1;
    }

    auto& buf = *result;
    *outputLen = static_cast<int64_t>(buf->getSize());
    *output = static_cast<uint8_t*>(malloc(static_cast<size_t>(*outputLen)));
    if (!*output) {
        *outputLen = 0;
        return -1;
    }
    std::memcpy(*output, buf->getBuffer(), static_cast<size_t>(*outputLen));
    return 0;
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_decrypt(const uint8_t* input, int64_t inputLen,
                                            const char* password, uint8_t** output,
                                            int64_t* outputLen) {
    if (!input || inputLen <= 0 || !output || !outputLen) return -1;
    *output = nullptr;
    *outputLen = 0;

    auto result = decryptPdf({input, static_cast<size_t>(inputLen)}, password);
    if (!result) {
        std::fprintf(stderr, "jpdfium qpdf decrypt: %s\n", result.error().c_str());
        return -1;
    }

    auto& buf = *result;
    *outputLen = static_cast<int64_t>(buf->getSize());
    *output = static_cast<uint8_t*>(malloc(static_cast<size_t>(*outputLen)));
    if (!*output) {
        *outputLen = 0;
        return -1;
    }
    std::memcpy(*output, buf->getBuffer(), static_cast<size_t>(*outputLen));
    return 0;
}

}  // extern "C"

#else  // !JPDFIUM_HAS_QPDF

// Stub when qpdf is not linked: pass the bytes through unchanged or return error.
extern "C" {

JPDFIUM_EXPORT int32_t jpdfium_qpdf_optimize(const uint8_t* input, int64_t inputLen,
                                             uint8_t** output, int64_t* outputLen, int32_t, int32_t,
                                             int32_t, int32_t, int32_t) {
    if (!input || inputLen <= 0 || !output || !outputLen) return -1;
    *outputLen = inputLen;
    *output = static_cast<uint8_t*>(malloc(static_cast<size_t>(inputLen)));
    if (!*output) {
        *outputLen = 0;
        return -1;
    }
    memcpy(*output, input, static_cast<size_t>(inputLen));
    return 0;
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_merge(const uint8_t* const*, const int64_t*, int32_t,
                                          uint8_t** output, int64_t* outputLen) {
    if (output) *output = nullptr;
    if (outputLen) *outputLen = 0;
    return -1;
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_extract_pages(const uint8_t*, int64_t, const int32_t*, int32_t,
                                                  uint8_t** output, int64_t* outputLen) {
    if (output) *output = nullptr;
    if (outputLen) *outputLen = 0;
    return -1;
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_encrypt(const uint8_t*, int64_t, const char*, const char*,
                                            int32_t, int32_t, uint8_t** output,
                                            int64_t* outputLen) {
    if (output) *output = nullptr;
    if (outputLen) *outputLen = 0;
    return -1;
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_decrypt(const uint8_t*, int64_t, const char*, uint8_t** output,
                                            int64_t* outputLen) {
    if (output) *output = nullptr;
    if (outputLen) *outputLen = 0;
    return -1;
}

}  // extern "C"

#endif  // JPDFIUM_HAS_QPDF
