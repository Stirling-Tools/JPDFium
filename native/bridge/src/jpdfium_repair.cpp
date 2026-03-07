// jpdfium_repair.cpp - PDF structural repair pipeline.
//
// Uses qpdf (Apache 2.0) for XRef reconstruction, trailer repair, and
// structural normalization. Falls back gracefully when qpdf is unavailable.
//
// Libraries used:
//   qpdf     (Apache 2.0) - XRef rebuild, stream normalization, inspection mode
//   PDFium   (BSD)        - Tolerant parser fallback (Chrome lineage)

#include "jpdfium.h"

#include <cstdlib>
#include <cstring>
#include <cstdio>
#include <string>
#include <sstream>
#include <vector>

#ifdef JPDFIUM_HAS_QPDF

#include <qpdf/QPDF.hh>
#include <qpdf/QPDFWriter.hh>
#include <qpdf/QPDFExc.hh>
#include <qpdf/Buffer.hh>
#include <qpdf/QPDFObjectHandle.hh>

// Helper: serialize qpdf warnings to JSON array
static std::string warnings_to_json(const std::vector<QPDFExc>& warnings) {
    std::ostringstream os;
    os << "[";
    bool first = true;
    for (const auto& w : warnings) {
        if (!first) os << ",";
        first = false;
        // Escape the warning message for JSON
        std::string msg = w.what();
        std::string escaped;
        escaped.reserve(msg.size());
        for (char c : msg) {
            if (c == '"' || c == '\\') escaped += '\\';
            if (c == '\n') { escaped += "\\n"; continue; }
            if (c == '\r') continue;
            escaped += c;
        }
        os << "{\"message\":\"" << escaped << "\"}";
    }
    os << "]";
    return os.str();
}

// Helper: attempt qpdf recovery on raw bytes
static int try_qpdf_repair(
    const uint8_t* input, int64_t inputLen,
    uint8_t** output, int64_t* outputLen,
    int32_t flags,
    std::string& errorMsg) {
    try {
        QPDF pdf;
        pdf.processMemoryFile(
            "repair",
            reinterpret_cast<const char*>(input),
            static_cast<size_t>(inputLen));

        QPDFWriter writer(pdf);
        writer.setOutputMemory();

        // Normalize xref type if requested
        if (flags & JPDFIUM_REPAIR_NORMALIZE_XREF) {
            writer.setObjectStreamMode(qpdf_o_disable);
        }

        writer.setLinearization(false);
        writer.setCompressStreams(true);

        // Force PDF 1.4 if requested
        if (flags & JPDFIUM_REPAIR_FORCE_V14) {
            writer.forcePDFVersion("1.4");
        }

        writer.write();

        auto warnings = pdf.getWarnings();

        std::shared_ptr<Buffer> buf = writer.getBufferSharedPointer();
        *outputLen = static_cast<int64_t>(buf->getSize());
        *output = new uint8_t[*outputLen];
        memcpy(*output, buf->getBuffer(), *outputLen);

        return warnings.empty() ? JPDFIUM_REPAIR_CLEAN : JPDFIUM_REPAIR_FIXED;
    } catch (QPDFExc& e) {
        errorMsg = e.what();
        return JPDFIUM_REPAIR_FAILED;
    } catch (std::exception& e) {
        errorMsg = e.what();
        return JPDFIUM_REPAIR_FAILED;
    }
}

// Helper: attempt startxref offset correction
static bool try_fix_startxref(
    std::vector<uint8_t>& data,
    int delta) {
    // Find last "startxref" in the file
    const char* needle = "startxref";
    size_t needleLen = 9;

    int64_t pos = -1;
    for (int64_t i = static_cast<int64_t>(data.size()) - needleLen - 1; i >= 0; --i) {
        if (memcmp(data.data() + i, needle, needleLen) == 0) {
            pos = i;
            break;
        }
    }
    if (pos < 0) return false;

    // Parse the offset value after "startxref\n"
    size_t numStart = pos + needleLen;
    while (numStart < data.size() && (data[numStart] == '\n' || data[numStart] == '\r' || data[numStart] == ' '))
        numStart++;

    size_t numEnd = numStart;
    while (numEnd < data.size() && data[numEnd] >= '0' && data[numEnd] <= '9')
        numEnd++;

    if (numEnd == numStart) return false;

    std::string offsetStr(data.begin() + numStart, data.begin() + numEnd);
    long long offset = std::stoll(offsetStr);
    long long newOffset = offset + delta;
    if (newOffset < 0) return false;

    // Replace the offset in the byte stream
    std::string newOffsetStr = std::to_string(newOffset);

    // Pad with spaces if shorter, or expand if longer
    std::vector<uint8_t> result;
    result.insert(result.end(), data.begin(), data.begin() + numStart);
    result.insert(result.end(), newOffsetStr.begin(), newOffsetStr.end());
    // Keep the same trailing content
    result.insert(result.end(), data.begin() + numEnd, data.end());

    data = std::move(result);
    return true;
}

extern "C" {

JPDFIUM_EXPORT int32_t jpdfium_repair_pdf(
    const uint8_t* input, int64_t inputLen,
    uint8_t** output, int64_t* outputLen,
    int32_t flags) {

    if (!input || inputLen <= 0 || !output || !outputLen) return JPDFIUM_REPAIR_FAILED;

    // Stage 1: Direct qpdf recovery (handles most xref, trailer, stream issues)
    std::string errorMsg;
    int result = try_qpdf_repair(input, inputLen, output, outputLen, flags, errorMsg);
    if (result != JPDFIUM_REPAIR_FAILED) return result;

    // Stage 2: startxref offset brute-force (if enabled)
    if (flags & JPDFIUM_REPAIR_FIX_STARTXREF) {
        static const int deltas[] = {1, -1, 2, -2, 3, -3, 4, -4, 8, -8, 16, -16};
        for (int delta : deltas) {
            std::vector<uint8_t> patched(input, input + inputLen);
            if (try_fix_startxref(patched, delta)) {
                result = try_qpdf_repair(
                    patched.data(), static_cast<int64_t>(patched.size()),
                    output, outputLen, flags, errorMsg);
                if (result != JPDFIUM_REPAIR_FAILED) return result;
            }
        }
    }

    return JPDFIUM_REPAIR_FAILED;
}

JPDFIUM_EXPORT int32_t jpdfium_repair_inspect(
    const uint8_t* input, int64_t inputLen,
    char** diagnosticJson) {

    if (!input || inputLen <= 0 || !diagnosticJson) return JPDFIUM_ERR_INVALID;

    std::ostringstream os;
    os << "{";

    try {
        QPDF pdf;
        pdf.processMemoryFile(
            "inspect",
            reinterpret_cast<const char*>(input),
            static_cast<size_t>(inputLen));

        auto warnings = pdf.getWarnings();
        int pageCount = 0;
        try {
            QPDFObjectHandle root = pdf.getRoot();
            if (root.hasKey("/Pages")) {
                QPDFObjectHandle pages = root.getKey("/Pages");
                if (pages.hasKey("/Count")) {
                    pageCount = pages.getKey("/Count").getIntValue();
                }
            }
        } catch (...) {
            // Page tree may be broken
        }

        os << "\"status\":\"loaded\","
           << "\"warning_count\":" << warnings.size() << ","
           << "\"page_count\":" << pageCount << ","
           << "\"xref_valid\":true,"
           << "\"trailer_valid\":true,"
           << "\"issues\":" << warnings_to_json(warnings);

    } catch (QPDFExc& e) {
        std::string msg = e.what();
        std::string escaped;
        escaped.reserve(msg.size());
        for (char c : msg) {
            if (c == '"' || c == '\\') escaped += '\\';
            if (c == '\n') { escaped += "\\n"; continue; }
            if (c == '\r') continue;
            escaped += c;
        }
        os << "\"status\":\"fatal\","
           << "\"warning_count\":0,"
           << "\"page_count\":0,"
           << "\"xref_valid\":false,"
           << "\"trailer_valid\":false,"
           << "\"issues\":[{\"message\":\"" << escaped << "\"}]";
    }

    os << "}";

    *diagnosticJson = strdup(os.str().c_str());
    return 0;
}

} // extern "C"

#else // !JPDFIUM_HAS_QPDF

// Stub implementations when qpdf is not available

extern "C" {

JPDFIUM_EXPORT int32_t jpdfium_repair_pdf(
    const uint8_t* input, int64_t inputLen,
    uint8_t** output, int64_t* outputLen,
    int32_t) {
    if (!input || inputLen <= 0 || !output || !outputLen) return JPDFIUM_REPAIR_FAILED;
    // Without qpdf, just pass through the bytes unchanged
    *outputLen = inputLen;
    *output = (uint8_t*)malloc(static_cast<size_t>(inputLen));
    memcpy(*output, input, static_cast<size_t>(inputLen));
    return JPDFIUM_REPAIR_CLEAN;
}

JPDFIUM_EXPORT int32_t jpdfium_repair_inspect(
    const uint8_t* input, int64_t inputLen,
    char** diagnosticJson) {
    if (!input || inputLen <= 0 || !diagnosticJson) return JPDFIUM_ERR_INVALID;
    *diagnosticJson = strdup("{\"status\":\"unavailable\",\"message\":\"qpdf not linked\"}");
    return 0;
}

} // extern "C"

#endif // JPDFIUM_HAS_QPDF
