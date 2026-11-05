// jpdfium_podofo.cpp - optional PoDoFo bridge.
//
// PoDoFo is linked only when the build defines JPDFIUM_HAS_PODOFO. The
// functions here are deliberately small: load/save repair and replacing the
// raw stream behind an existing font descriptor. They do not invent glyph
// mappings; callers must supply font data that matches the font dictionary.

#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <memory>
#include <string>
#include <unistd.h>
#include <vector>

#include "jpdfium.h"

#if defined(JPDFIUM_HAS_PODOFO) && !defined(_WIN32)

#include <podofo/podofo.h>

namespace {

using namespace PoDoFo;

struct FileCloser {
    void operator()(FILE* f) const noexcept {
        if (f) std::fclose(f);
    }
};

using UniqueFile = std::unique_ptr<FILE, FileCloser>;

struct TempFile {
    std::string path;
    ~TempFile() {
        if (!path.empty()) std::remove(path.c_str());
    }
};

bool make_temp(TempFile& temp, const char* suffix) {
    std::string templ = std::string("/tmp/jpdfium-podofo-XXXXXX") + suffix;
    std::vector<char> buf(templ.begin(), templ.end());
    buf.push_back('\0');
    int fd = mkstemp(buf.data());
    if (fd < 0) return false;
    close(fd);
    temp.path.assign(buf.data());
    return true;
}

bool read_file(const std::string& path, uint8_t** output, int64_t* outputLen) {
    UniqueFile file(std::fopen(path.c_str(), "rb"));
    if (!file) return false;
    if (std::fseek(file.get(), 0, SEEK_END) != 0) return false;
    long size = std::ftell(file.get());
    if (size <= 0 || size > (1L << 32)) return false;
    if (std::fseek(file.get(), 0, SEEK_SET) != 0) return false;
    uint8_t* bytes = static_cast<uint8_t*>(std::malloc(static_cast<size_t>(size)));
    if (!bytes) return false;
    if (std::fread(bytes, 1, static_cast<size_t>(size), file.get()) != static_cast<size_t>(size)) {
        std::free(bytes);
        return false;
    }
    *output = bytes;
    *outputLen = size;
    return true;
}

bool save_document(PdfMemDocument& doc, const std::string& path) {
    doc.Save(path, PdfSaveOptions::None);
    return true;
}

bool replace_font_data_by_name(PdfMemDocument& doc, const std::string& fontName,
                                   const uint8_t* fontData, int64_t fontLen) {
    bool replaced = false;
    for (auto& object : doc.GetObjects()) {
        if (!object->IsDictionary()) continue;
        PdfDictionary* dict = nullptr;
        if (!object->TryGetDictionary(dict) || !dict) continue;
        PdfObject* baseFont = dict->FindKey("BaseFont");
        if (!baseFont || !baseFont->IsName()) continue;
        if (baseFont->GetName().GetString() != fontName) continue;
        PdfObject* descriptor = dict->FindKey("FontDescriptor");
        if (!descriptor) continue;
        PdfDictionary* descriptorDict = nullptr;
        if (!descriptor->TryGetDictionary(descriptorDict) || !descriptorDict) continue;
        PdfObject* fontFile = descriptorDict->FindKey("FontFile");
        if (!fontFile) fontFile = descriptorDict->FindKey("FontFile2");
        if (!fontFile) fontFile = descriptorDict->FindKey("FontFile3");
        if (!fontFile) continue;
        PdfObjectStream* stream = fontFile->GetStream();
        if (!stream) continue;
        stream->SetData({reinterpret_cast<const char*>(fontData), static_cast<size_t>(fontLen)});
        replaced = true;
    }
    return replaced;
}

std::string reference_key(const PdfReference& ref) {
    return std::to_string(ref.ObjectNumber()) + " " + std::to_string(ref.GenerationNumber());
}

int dedup_images(PdfMemDocument& doc) {
    struct ImageInfo {
        PdfReference ref;
        std::string digest;
    };
    std::map<std::string, PdfReference> canonical;
    std::map<PdfReference, PdfReference> replacements;
    for (auto& object : doc.GetObjects()) {
        if (!object->IsDictionary()) continue;
        PdfDictionary* dict = nullptr;
        if (!object->TryGetDictionary(dict) || !dict) continue;
        PdfObject* type = dict->FindKey("Type");
        PdfObject* subtype = dict->FindKey("Subtype");
        if (!type || !subtype || !type->IsName() || !subtype->IsName()) continue;
        if (type->GetName().GetString() != "XObject"
                || subtype->GetName().GetString() != "Image") {
            continue;
        }
        PdfObjectStream* stream = object->GetStream();
        if (!stream) continue;
        charbuff data = stream->GetCopySafe();
        std::string key(data.data(), data.size());
        PdfObject* width = dict->FindKey("Width");
        PdfObject* height = dict->FindKey("Height");
        if (width && height) {
            key += "|" + std::to_string(static_cast<long long>(width->GetNumber())) + "x" + std::to_string(static_cast<long long>(height->GetNumber()));
        }
        PdfObject* smask = dict->FindKey("SMask");
        if (smask && smask->IsReference()) key += "|" + reference_key(smask->GetReference());
        auto found = canonical.find(key);
        if (found == canonical.end()) {
            canonical.emplace(key, object->GetReference());
        } else if (found->second != object->GetReference()) {
            replacements.emplace(object->GetReference(), found->second);
        }
    }
    if (replacements.empty()) return 0;
    for (auto& object : doc.GetObjects()) {
        if (!object->IsDictionary()) continue;
        PdfDictionary* dict = nullptr;
        if (!object->TryGetDictionary(dict) || !dict) continue;
        PdfObject* resources = dict->FindKey("Resources");
        PdfDictionary* resourceDict = nullptr;
        if (!resources || !resources->TryGetDictionary(resourceDict) || !resourceDict) continue;
        PdfObject* xobjects = resourceDict->FindKey("XObject");
        PdfDictionary* xobjectDict = nullptr;
        if (!xobjects || !xobjects->TryGetDictionary(xobjectDict) || !xobjectDict) continue;
        for (auto& entry : *xobjectDict) {
            if (!entry.second.IsReference()) continue;
            auto replacement = replacements.find(entry.second.GetReference());
            if (replacement == replacements.end()) continue;
            PdfObject* target = doc.GetObjects().GetObject(replacement->second);
            if (target) xobjectDict->AddKeyIndirect(entry.first, *target);
        }
    }
    return static_cast<int>(replacements.size());
}

}  // namespace

extern "C" {

JPDFIUM_EXPORT int32_t jpdfium_podofo_available(void) {
    return 1;
}

JPDFIUM_EXPORT int32_t jpdfium_podofo_repair(const uint8_t* input, int64_t inputLen,
                                             uint8_t** output, int64_t* outputLen) {
    if (!input || inputLen <= 0 || !output || !outputLen) return JPDFIUM_REPAIR_FAILED;
    *output = nullptr;
    *outputLen = 0;
    TempFile temp;
    if (!make_temp(temp, ".pdf")) return JPDFIUM_REPAIR_FAILED;
    try {
        PdfMemDocument doc;
        doc.LoadFromBuffer({reinterpret_cast<const char*>(input), static_cast<size_t>(inputLen)});
        save_document(doc, temp.path);
        return read_file(temp.path, output, outputLen) ? JPDFIUM_REPAIR_FIXED
                                                       : JPDFIUM_REPAIR_FAILED;
    } catch (...) {
        return JPDFIUM_REPAIR_FAILED;
    }
}

JPDFIUM_EXPORT int32_t jpdfium_podofo_replace_font_data(
        const uint8_t* input, int64_t inputLen, int64_t fontNum, int32_t fontGen,
        const uint8_t* fontData, int64_t fontLen, uint8_t** output, int64_t* outputLen) {
    if (!input || inputLen <= 0 || !fontData || fontLen <= 0 || !output || !outputLen) {
        return JPDFIUM_REPAIR_FAILED;
    }
    *output = nullptr;
    *outputLen = 0;
    TempFile temp;
    if (!make_temp(temp, ".pdf")) return JPDFIUM_REPAIR_FAILED;
    try {
        PdfMemDocument doc;
        doc.LoadFromBuffer({reinterpret_cast<const char*>(input), static_cast<size_t>(inputLen)});
        PdfObject* font = doc.GetObjects().GetObject(
                PdfReference(static_cast<uint32_t>(fontNum), static_cast<uint16_t>(fontGen)));
        if (!font) return JPDFIUM_REPAIR_FAILED;
        PdfDictionary* dict = nullptr;
        if (!font->TryGetDictionary(dict) || !dict) return JPDFIUM_REPAIR_FAILED;
        PdfObject* descriptor = dict->FindKey("FontDescriptor");
        if (!descriptor) return JPDFIUM_REPAIR_FAILED;
        PdfDictionary* descriptorDict = nullptr;
        if (!descriptor->TryGetDictionary(descriptorDict) || !descriptorDict) {
            return JPDFIUM_REPAIR_FAILED;
        }
        PdfObject* fontFile = descriptorDict->FindKey("FontFile");
        if (!fontFile) fontFile = descriptorDict->FindKey("FontFile2");
        if (!fontFile) fontFile = descriptorDict->FindKey("FontFile3");
        if (!fontFile) return JPDFIUM_REPAIR_FAILED;
        PdfObjectStream* stream = fontFile->GetStream();
        if (!stream) return JPDFIUM_REPAIR_FAILED;
        stream->SetData({reinterpret_cast<const char*>(fontData), static_cast<size_t>(fontLen)});
        save_document(doc, temp.path);
        return read_file(temp.path, output, outputLen) ? JPDFIUM_REPAIR_FIXED
                                                       : JPDFIUM_REPAIR_FAILED;
    } catch (...) {
        return JPDFIUM_REPAIR_FAILED;
    }
}


JPDFIUM_EXPORT int32_t jpdfium_podofo_embed_font_data(
        const uint8_t* input, int64_t inputLen, const char* fontName,
        const uint8_t* fontData, int64_t fontLen, uint8_t** output, int64_t* outputLen) {
    if (!input || inputLen <= 0 || !fontName || !*fontName || !fontData || fontLen <= 0
            || !output || !outputLen) {
        return JPDFIUM_REPAIR_FAILED;
    }
    *output = nullptr;
    *outputLen = 0;
    TempFile temp;
    if (!make_temp(temp, ".pdf")) return JPDFIUM_REPAIR_FAILED;
    try {
        PdfMemDocument doc;
        doc.LoadFromBuffer({reinterpret_cast<const char*>(input), static_cast<size_t>(inputLen)});
        if (!replace_font_data_by_name(doc, fontName, fontData, fontLen)) {
            return JPDFIUM_REPAIR_FAILED;
        }
        save_document(doc, temp.path);
        return read_file(temp.path, output, outputLen) ? JPDFIUM_REPAIR_FIXED
                                                       : JPDFIUM_REPAIR_FAILED;
    } catch (...) {
        return JPDFIUM_REPAIR_FAILED;
    }
}


JPDFIUM_EXPORT int32_t jpdfium_podofo_dedup_images(const uint8_t* input, int64_t inputLen,
                                                   uint8_t** output, int64_t* outputLen,
                                                   int32_t* removed) {
    if (!input || inputLen <= 0 || !output || !outputLen || !removed) return JPDFIUM_REPAIR_FAILED;
    *output = nullptr;
    *outputLen = 0;
    *removed = 0;
    TempFile temp;
    if (!make_temp(temp, ".pdf")) return JPDFIUM_REPAIR_FAILED;
    try {
        PdfMemDocument doc;
        doc.LoadFromBuffer({reinterpret_cast<const char*>(input), static_cast<size_t>(inputLen)});
        *removed = dedup_images(doc);
        doc.Save(temp.path, PdfSaveOptions::Clean);
        return read_file(temp.path, output, outputLen) ? JPDFIUM_REPAIR_FIXED
                                                       : JPDFIUM_REPAIR_FAILED;
    } catch (...) {
        return JPDFIUM_REPAIR_FAILED;
    }
}

}  // extern "C"

#else

extern "C" {

JPDFIUM_EXPORT int32_t jpdfium_podofo_available(void) {
    return 0;
}

JPDFIUM_EXPORT int32_t jpdfium_podofo_repair(const uint8_t*, int64_t, uint8_t**, int64_t*) {
    return JPDFIUM_ERR_NATIVE;
}

JPDFIUM_EXPORT int32_t jpdfium_podofo_replace_font_data(
        const uint8_t*, int64_t, int64_t, int32_t, const uint8_t*, int64_t, uint8_t**, int64_t*) {
    return JPDFIUM_ERR_NATIVE;
}

}  // extern "C"

#endif
