// api_text.cpp - jpdf_page_get_text. PDFium's text extraction, run admitted, returned as UTF-8.

#include <fpdf_text.h>

#include <cstring>
#include <memory>
#include <string>
#include <vector>

#include "jpdfium_api.h"
#include "objects/objects.h"
#include "runtime/api_status.h"
#include "runtime/domain.h"

namespace {

struct LoadedTextPage {
    FPDF_TEXTPAGE text;
    ~LoadedTextPage() {
        if (text) FPDFText_ClosePage(text);
    }
};

void append_utf8(std::string& out, char32_t c) {
    if (c < 0x80) {
        out += static_cast<char>(c);
    } else if (c < 0x800) {
        out += static_cast<char>(0xC0 | (c >> 6));
        out += static_cast<char>(0x80 | (c & 0x3F));
    } else if (c < 0x10000) {
        out += static_cast<char>(0xE0 | (c >> 12));
        out += static_cast<char>(0x80 | ((c >> 6) & 0x3F));
        out += static_cast<char>(0x80 | (c & 0x3F));
    } else {
        out += static_cast<char>(0xF0 | (c >> 18));
        out += static_cast<char>(0x80 | ((c >> 12) & 0x3F));
        out += static_cast<char>(0x80 | ((c >> 6) & 0x3F));
        out += static_cast<char>(0x80 | (c & 0x3F));
    }
}

// PDFium returns UTF-16LE; unpaired surrogates become U+FFFD.
std::string utf16le_to_utf8(const std::vector<uint8_t>& bytes, size_t units) {
    std::string out;
    out.reserve(units);
    size_t i = 0;
    while (i < units) {
        char32_t c = static_cast<char32_t>(bytes[2 * i] | (bytes[2 * i + 1] << 8));
        size_t next = i + 1;
        if (c >= 0xD800 && c <= 0xDBFF && next < units) {
            const char32_t low =
                static_cast<char32_t>(bytes[2 * next] | (bytes[2 * next + 1] << 8));
            if (low >= 0xDC00 && low <= 0xDFFF) {
                c = 0x10000 + ((c - 0xD800) << 10) + (low - 0xDC00);
                next = i + 2;
            }
        }
        if (c >= 0xD800 && c <= 0xDFFF) c = 0xFFFD;
        append_utf8(out, c);
        i = next;
    }
    return out;
}

}  // namespace

extern "C" {

jpdf_status jpdf_page_get_text(jpdf_handle page, char* buffer, uint64_t capacity,
                               uint64_t* required) noexcept {
    return jpdf::guarded([&] {
        if (!required) return jpdf::fail(JPDF_E_INVALID_ARGUMENT, "required is NULL");
        if (!buffer && capacity != 0) {
            return jpdf::fail(JPDF_E_INVALID_ARGUMENT, "capacity without a buffer");
        }
        std::vector<uint8_t> utf16;
        size_t units = 0;
        {
            jpdf::Admission admission(jpdf::Purpose::Ordinary);
            if (!admission.admitted()) return admission.status();
            std::shared_ptr<jpdf::PageObject> object;
            jpdf_status st = jpdf::objects().resolve_as(page, jpdf::ObjectType::Page, &object);
            if (st != JPDF_OK) return jpdf::fail(st, "page handle rejected");
            LoadedTextPage loaded{FPDFText_LoadPage(object->page)};
            if (!loaded.text) {
                return jpdf::fail(JPDF_E_FORMAT, "PDFium could not load the page text");
            }
            const int count = FPDFText_CountChars(loaded.text);
            if (count < 0) return jpdf::fail(JPDF_E_FORMAT, "PDFium could not count the page text");
            // GetText writes count units plus a terminating NUL.
            utf16.resize(2 * (static_cast<size_t>(count) + 1));
            const int written = FPDFText_GetText(loaded.text, 0, count,
                                                 reinterpret_cast<unsigned short*>(utf16.data()));
            units = written > 0 ? static_cast<size_t>(written) - 1 : 0;
        }
        const std::string text = utf16le_to_utf8(utf16, units);
        *required = static_cast<uint64_t>(text.size()) + 1;
        if (!buffer) return JPDF_OK;
        if (capacity < *required) {
            return jpdf::fail(JPDF_E_BUFFER_TOO_SMALL, "buffer is smaller than the page text");
        }
        std::memcpy(buffer, text.c_str(), text.size() + 1);
        return JPDF_OK;
    });
}

}  // extern "C"
