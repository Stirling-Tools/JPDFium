// jpdfium_signature.cpp - Signature inspection via the EmbedPDF signature model.

#include <epdf_signature.h>
#include <fpdfview.h>

#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <sstream>
#include <string>
#include <vector>

#include "jpdfium.h"
#include "jpdfium_internal.h"

namespace {

std::string utf16leToUtf8(const uint16_t* s, size_t count) {
    std::string out;
    out.reserve(count);
    size_t i = 0;
    while (i < count) {
        uint32_t cp = s[i];
        size_t advance = 1;
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < count) {
            uint32_t lo = s[i + 1];
            if (lo >= 0xDC00 && lo <= 0xDFFF) {
                cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
                advance = 2;
            }
        }
        i += advance;
        if (cp < 0x80) {
            out.push_back(static_cast<char>(cp));
        } else if (cp < 0x800) {
            out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else if (cp < 0x10000) {
            out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else {
            out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        }
    }
    return out;
}

std::string jsonEscape(const std::string& in) {
    std::string out;
    out.reserve(in.size());
    for (char c : in) {
        switch (c) {
            case '"':
                out += "\\\"";
                break;
            case '\\':
                out += "\\\\";
                break;
            case '\n':
                out += "\\n";
                break;
            case '\r':
                out += "\\r";
                break;
            case '\t':
                out += "\\t";
                break;
            default:
                out.push_back(c);
        }
    }
    return out;
}

template <typename Fn>
std::string readUtf16(Fn&& fn) {
    unsigned long need = fn(nullptr, 0);
    if (need == 0) return {};
    std::vector<uint16_t> buf(need / sizeof(uint16_t) + 1, 0);
    unsigned long got = fn(reinterpret_cast<FPDF_WCHAR*>(buf.data()), need);
    if (got == 0) return {};
    size_t chars = got / sizeof(uint16_t);
    while (chars > 0 && buf[chars - 1] == 0) --chars;
    return utf16leToUtf8(buf.data(), chars);
}

char* dupString(const std::string& s) {
    char* out = static_cast<char*>(malloc(s.size() + 1));
    if (!out) return nullptr;
    memcpy(out, s.c_str(), s.size() + 1);
    return out;
}

std::string sigString(EPDF_SIGNATURE_MODEL model, int index, int key) {
    return readUtf16([&](FPDF_WCHAR* b, unsigned long n) {
        return EPDFSig_GetString(model, index, key, b, n);
    });
}

}  // namespace

int32_t jpdfium_signature_count(int64_t doc, int32_t* count) {
    if (!count) return JPDFIUM_ERR_INVALID;
    DocWrapper* w = decodeDoc(doc);
    if (!w || !w->core->doc) return JPDFIUM_ERR_INVALID;
    return jpdfium_guarded([&] {
        EPDF_SIGNATURE_MODEL model = EPDFSig_LoadModel(w->core->doc);
        if (!model) {
            *count = 0;
            return JPDFIUM_OK;
        }
        int n = EPDFSig_Count(model);
        EPDFSig_CloseModel(model);
        if (n < 0) return JPDFIUM_ERR_NATIVE;
        *count = n;
        return JPDFIUM_OK;
    });
}

// Number of byte revisions (signed prefixes) in the loaded document.
int32_t jpdfium_signature_revision_count(int64_t doc, int32_t* count) {
    if (!count) return JPDFIUM_ERR_INVALID;
    DocWrapper* w = decodeDoc(doc);
    if (!w || !w->core->doc) return JPDFIUM_ERR_INVALID;
    return jpdfium_guarded([&] {
        *count = EPDFDoc_GetRevisionCount(w->core->doc);
        return JPDFIUM_OK;
    });
}

// Flat JSON with the signature field facts:
//   {"fieldName":...,"signed":bool,"kind":N,"coverage":N,"revisionIndex":N,
//    "br0":N,"br1":N,"br2":N,"br3":N,"docMdpPermission":N,
//    "catalogCertification":bool,"revisionChainValid":bool,
//    "filter":...,"subFilter":...,"name":...,"reason":...,"location":...,
//    "contactInfo":...,"signingTime":...,"contentsLength":N}
int32_t jpdfium_signature_info(int64_t doc, int32_t index, char** json) {
    if (!json) return JPDFIUM_ERR_INVALID;
    DocWrapper* w = decodeDoc(doc);
    if (!w || !w->core->doc) return JPDFIUM_ERR_INVALID;
    return jpdfium_guarded([&] {
        EPDF_SIGNATURE_MODEL model = EPDFSig_LoadModel(w->core->doc);
        if (!model) return JPDFIUM_ERR_NATIVE;
        int n = EPDFSig_Count(model);
        if (index < 0 || index >= n) {
            EPDFSig_CloseModel(model);
            return JPDFIUM_ERR_NOT_FOUND;
        }

        std::string fieldName = readUtf16([&](FPDF_WCHAR* b, unsigned long l) {
            return EPDFSig_GetFieldName(model, index, b, l);
        });
        bool signedFlag = EPDFSig_IsSigned(model, index) != 0;
        unsigned long long range[4] = {0, 0, 0, 0};
        bool hasRange = EPDFSig_GetByteRange(model, index, range) != 0;
        unsigned long contentsLen = EPDFSig_GetContents(model, index, nullptr, 0);
        bool chainValid = EPDFSig_IsRevisionChainValid(model) != 0;

        std::ostringstream os;
        os << "{\"fieldName\":\"" << jsonEscape(fieldName) << "\",\"signed\":"
           << (signedFlag ? "true" : "false") << ",\"kind\":" << EPDFSig_GetKind(model, index)
           << ",\"coverage\":" << EPDFSig_GetCoverage(model, index)
           << ",\"revisionIndex\":" << EPDFSig_GetRevisionIndex(model, index) << ",\"br0\":"
           << (hasRange ? static_cast<int64_t>(range[0]) : -1) << ",\"br1\":"
           << (hasRange ? static_cast<int64_t>(range[1]) : -1) << ",\"br2\":"
           << (hasRange ? static_cast<int64_t>(range[2]) : -1) << ",\"br3\":"
           << (hasRange ? static_cast<int64_t>(range[3]) : -1) << ",\"docMdpPermission\":"
           << EPDFSig_GetDocMDPPermission(model, index) << ",\"catalogCertification\":"
           << (EPDFSig_IsCatalogCertification(model, index) ? "true" : "false")
           << ",\"revisionChainValid\":" << (chainValid ? "true" : "false") << ",\"filter\":\""
           << jsonEscape(sigString(model, index, EPDF_SIG_STRING_FILTER)) << "\",\"subFilter\":\""
           << jsonEscape(sigString(model, index, EPDF_SIG_STRING_SUBFILTER)) << "\",\"name\":\""
           << jsonEscape(sigString(model, index, EPDF_SIG_STRING_NAME)) << "\",\"reason\":\""
           << jsonEscape(sigString(model, index, EPDF_SIG_STRING_REASON)) << "\",\"location\":\""
           << jsonEscape(sigString(model, index, EPDF_SIG_STRING_LOCATION))
           << "\",\"contactInfo\":\""
           << jsonEscape(sigString(model, index, EPDF_SIG_STRING_CONTACT_INFO))
           << "\",\"signingTime\":\""
           << jsonEscape(sigString(model, index, EPDF_SIG_STRING_M))
           << "\",\"contentsLength\":" << static_cast<int64_t>(contentsLen) << '}';

        EPDFSig_CloseModel(model);
        char* out = dupString(os.str());
        if (!out) return JPDFIUM_ERR_NATIVE;
        *json = out;
        return JPDFIUM_OK;
    });
}

// Digest of the signature's /ByteRange (EPDF_DIGEST_* algorithm). Caller frees
// the digest with jpdfium_free_buffer. Fails for unsigned fields.
int32_t jpdfium_signature_digest(int64_t doc, int32_t index, int32_t algorithm, uint8_t** digest,
                                 int64_t* len) {
    if (!digest || !len) return JPDFIUM_ERR_INVALID;
    if (algorithm < EPDF_DIGEST_SHA1 || algorithm > EPDF_DIGEST_SHA512) {
        return JPDFIUM_ERR_INVALID;
    }
    DocWrapper* w = decodeDoc(doc);
    if (!w || !w->core->doc) return JPDFIUM_ERR_INVALID;
    return jpdfium_guarded([&] {
        EPDF_SIGNATURE_MODEL model = EPDFSig_LoadModel(w->core->doc);
        if (!model) return JPDFIUM_ERR_NATIVE;
        int n = EPDFSig_Count(model);
        if (index < 0 || index >= n) {
            EPDFSig_CloseModel(model);
            return JPDFIUM_ERR_NOT_FOUND;
        }
        unsigned long long range[4] = {0, 0, 0, 0};
        bool hasRange = EPDFSig_GetByteRange(model, index, range) != 0;
        EPDFSig_CloseModel(model);
        if (!hasRange) return JPDFIUM_ERR_NOT_FOUND;

        unsigned char buf[64];
        unsigned long bufLen = sizeof(buf);
        if (!EPDFSig_DigestByteRange(w->core->doc, range, algorithm, buf, &bufLen)) {
            return JPDFIUM_ERR_NATIVE;
        }
        auto* out = static_cast<uint8_t*>(malloc(bufLen));
        if (!out) return JPDFIUM_ERR_NATIVE;
        memcpy(out, buf, bufLen);
        *digest = out;
        *len = static_cast<int64_t>(bufLen);
        return JPDFIUM_OK;
    });
}
