// text_layer_parse.cpp - validation and parsing of the text layer caller arrays.
// Split out of the former text_layer.cpp; the moved code is unchanged.

#include <unicode/uchar.h>

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstring>
#include <initializer_list>
#include <string_view>
#include <utility>
#include <vector>

#include "jpdfium_api.h"
#include "runtime/api_status.h"
#include "text_layer.h"
#include "text_layer_internal.h"

namespace jpdf {

namespace {

// --- Unicode classes ---

bool is_control(char32_t c) {
    return c < 0x20 || (c >= 0x7F && c <= 0x9F);
}

bool is_noncharacter(char32_t c) {
    return (c >= 0xFDD0 && c <= 0xFDEF) || (c & 0xFFFE) == 0xFFFE;
}

// Embeddings, overrides and isolates nest across words, which per-word layout cannot honour.
bool is_explicit_bidi(UCharDirection d) {
    return d == U_LEFT_TO_RIGHT_EMBEDDING || d == U_LEFT_TO_RIGHT_OVERRIDE ||
           d == U_RIGHT_TO_LEFT_EMBEDDING || d == U_RIGHT_TO_LEFT_OVERRIDE ||
           d == U_POP_DIRECTIONAL_FORMAT || d == U_LEFT_TO_RIGHT_ISOLATE ||
           d == U_RIGHT_TO_LEFT_ISOLATE || d == U_FIRST_STRONG_ISOLATE ||
           d == U_POP_DIRECTIONAL_ISOLATE;
}

// Classes that can raise a level above 0 in a left-to-right line.
bool is_right_to_left(UCharDirection d) {
    return d == U_RIGHT_TO_LEFT || d == U_RIGHT_TO_LEFT_ARABIC || d == U_ARABIC_NUMBER;
}

// OCRmyPDF's _is_cjk_only ranges: words written without separating spaces.
bool is_cjk(char32_t c) {
    static constexpr char32_t kRanges[][2] = {
        {0x4E00, 0x9FFF},   {0x3400, 0x4DBF}, {0x20000, 0x2A6DF}, {0x2A700, 0x2EBEF},
        {0x30000, 0x3134F}, {0x3040, 0x30FF}, {0x31F0, 0x31FF},   {0xAC00, 0xD7AF},
        {0x1100, 0x11FF},   {0x3130, 0x318F}, {0xA960, 0xA97F},   {0xD7B0, 0xD7FF},
        {0x3000, 0x303F},   {0xFF00, 0xFFEF},
    };
    for (const auto& range : kRanges) {
        if (c >= range[0] && c <= range[1]) return true;
    }
    return false;
}

// Python's str.isspace once controls are excluded.
bool is_space(char32_t c) {
    return c == 0x20 || c == 0xA0 || c == 0x1680 || (c >= 0x2000 && c <= 0x200A) || c == 0x2028 ||
           c == 0x2029 || c == 0x202F || c == 0x205F || c == 0x3000;
}

// Strict UTF-8: rejects overlong forms, surrogates and values above U+10FFFF.
bool decode_utf8(std::string_view s, std::vector<char32_t>* out) {
    size_t i = 0;
    while (i < s.size()) {
        const auto lead = static_cast<unsigned char>(s[i]);
        char32_t cp = 0;
        char32_t min = 0;
        size_t len = 0;
        if (lead < 0x80) {
            cp = lead;
            len = 1;
        } else if ((lead & 0xE0) == 0xC0) {
            cp = lead & 0x1Fu;
            len = 2;
            min = 0x80;
        } else if ((lead & 0xF0) == 0xE0) {
            cp = lead & 0x0Fu;
            len = 3;
            min = 0x800;
        } else if ((lead & 0xF8) == 0xF0) {
            cp = lead & 0x07u;
            len = 4;
            min = 0x10000;
        } else {
            return false;
        }
        if (len > s.size() - i) return false;
        for (size_t k = 1; k < len; ++k) {
            const auto next = static_cast<unsigned char>(s[i + k]);
            if ((next & 0xC0) != 0x80) return false;
            cp = (cp << 6) | (next & 0x3Fu);
        }
        if (cp < min || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) return false;
        out->push_back(cp);
        i += len;
    }
    return true;
}

bool coordinate_ok(double v) {
    return std::isfinite(v) && std::fabs(v) <= kMaxCoordinate;
}

bool box_ok(double x0, double y0, double x1, double y1) {
    const std::initializer_list<double> values = {x0, y0, x1, y1};
    return std::all_of(values.begin(), values.end(), coordinate_ok) && x1 >= x0 && y1 >= y0;
}

Quad box_corners(double x0, double y0, double x1, double y1) {
    return {Point{x0, y0}, Point{x1, y0}, Point{x1, y1}, Point{x0, y1}};
}

bool quad_ok(const double (&v)[8], Quad* out) {
    for (size_t k = 0; k < 4; ++k) {
        if (!coordinate_ok(v[2 * k]) || !coordinate_ok(v[2 * k + 1])) return false;
        (*out)[k] = Point{v[2 * k], v[2 * k + 1]};
    }
    return true;
}

bool dpi_ok(double dpi) {
    return std::isfinite(dpi) && dpi >= kMinDpi && dpi <= kMaxDpi;
}

// Copies a caller array whose stride is the first element's struct_size. Elements may stop at
// min_size (the 1.2 layout); missing fields read as zero.
template <typename T>
jpdf_status read_array(const T* items, int32_t count, uint32_t min_size, std::string_view name,
                       std::vector<T>* out, uint32_t* out_stride) {
    if (count == 0) return JPDF_OK;
    if (!items) return fail(JPDF_E_INVALID_ARGUMENT, name, " array is NULL");
    uint32_t stride = 0;
    std::memcpy(&stride, items, sizeof(stride));
    if (stride < min_size) {
        return fail(JPDF_E_INVALID_ARGUMENT, name, ".struct_size is below the minimum layout");
    }
    if (stride % alignof(T) != 0) {
        return fail(JPDF_E_INVALID_ARGUMENT, name, ".struct_size breaks element alignment");
    }
    const auto* base = reinterpret_cast<const unsigned char*>(items);
    out->reserve(static_cast<size_t>(count));
    for (int32_t i = 0; i < count; ++i) {
        const unsigned char* item = base + static_cast<size_t>(i) * stride;
        uint32_t size = 0;
        std::memcpy(&size, item, sizeof(size));
        if (size != stride) {
            return fail(JPDF_E_INVALID_ARGUMENT, name, " elements must share one struct_size");
        }
        jpdf_status st = check_input_struct(item, size, min_size, sizeof(T), name);
        if (st != JPDF_OK) return st;
        T value{};
        std::memcpy(&value, item, std::min<size_t>(size, sizeof(T)));
        out->push_back(value);
    }
    *out_stride = stride;
    return JPDF_OK;
}

jpdf_status parse_options(const jpdf_text_layer_options* options, TextLayer* out) {
    if (!options) return fail(JPDF_E_INVALID_ARGUMENT, "jpdf_text_layer_options is required");
    jpdf_status st =
        check_input_struct(options, options->struct_size, sizeof(jpdf_text_layer_options),
                           sizeof(jpdf_text_layer_options), "jpdf_text_layer_options");
    if (st != JPDF_OK) return st;
    if (options->flags != 0 || options->reserved0 != 0) {
        return fail(JPDF_E_UNSUPPORTED, "jpdf_text_layer_options.flags and reserved0 must be 0");
    }
    if (options->font != 0 && options->font != JPDF_FONT_GLYPHLESS) {
        return fail(JPDF_E_UNSUPPORTED, "unknown text layer font");
    }
    if (options->image_width < 1 || options->image_width > kMaxImageSide ||
        options->image_height < 1 || options->image_height > kMaxImageSide) {
        return fail(JPDF_E_INVALID_ARGUMENT, "image_width and image_height must be 1 to 1048576");
    }
    if (!dpi_ok(options->dpi_x) || !dpi_ok(options->dpi_y)) {
        return fail(JPDF_E_INVALID_ARGUMENT, "dpi_x and dpi_y must be finite, 1 to 100000");
    }
    out->image_width = options->image_width;
    out->image_height = options->image_height;
    out->dpi_x = options->dpi_x;
    out->dpi_y = options->dpi_y;
    return JPDF_OK;
}

jpdf_status parse_word(const jpdf_text_word& item, uint32_t size, uint64_t* text_bytes,
                       LayerWord* word) {
    if ((item.flags & ~JPDF_TEXT_WORD_QUAD) != 0) {
        return fail(JPDF_E_UNSUPPORTED, "unknown jpdf_text_word.flags");
    }
    if (!item.text_utf8) return fail(JPDF_E_INVALID_ARGUMENT, "word text is NULL");
    word->quad = (item.flags & JPDF_TEXT_WORD_QUAD) != 0;
    if (word->quad) {
        if (size < sizeof(jpdf_text_word)) {
            return fail(JPDF_E_INVALID_ARGUMENT, "JPDF_TEXT_WORD_QUAD needs the 1.4 struct");
        }
        if (!quad_ok(item.quad, &word->corners)) {
            return fail(JPDF_E_INVALID_ARGUMENT, "word quads need finite coordinates");
        }
        const auto [lo, hi] = std::minmax(
            {word->corners[0].x, word->corners[1].x, word->corners[2].x, word->corners[3].x});
        word->x0 = lo;
        word->x1 = hi;
    } else {
        if (!box_ok(item.x0, item.y0, item.x1, item.y1)) {
            return fail(JPDF_E_INVALID_ARGUMENT, "word boxes need finite x1 >= x0 and y1 >= y0");
        }
        word->corners = box_corners(item.x0, item.y0, item.x1, item.y1);
        word->x0 = item.x0;
        word->x1 = item.x1;
    }
    // Bound the scan to the remaining budget: check budget before reading caller memory,
    // and scan at most (remaining + 1) bytes so we never scan beyond budget or walk arbitrarily
    // far.
    if (*text_bytes >= kMaxTextBytes) {
        return fail(JPDF_E_LIMIT, "text layer exceeds 16 MiB of text");
    }
    const size_t max_scan = static_cast<size_t>(kMaxTextBytes - *text_bytes) + 1;
    const void* nul = std::memchr(item.text_utf8, '\0', max_scan);
    if (!nul) return fail(JPDF_E_LIMIT, "text layer exceeds 16 MiB of text");
    const auto len = static_cast<uint64_t>(static_cast<const char*>(nul) - item.text_utf8);
    if (len > (kMaxTextBytes - *text_bytes)) {
        return fail(JPDF_E_LIMIT, "text layer exceeds 16 MiB of text");
    }
    *text_bytes += len;
    if (len == 0 || !decode_utf8(std::string_view(item.text_utf8, len), &word->text)) {
        return fail(JPDF_E_INVALID_ARGUMENT, "word text must be non-empty, valid UTF-8");
    }
    word->cjk_only = true;
    word->blank = true;
    for (char32_t c : word->text) {
        if (is_control(c) || is_noncharacter(c)) {
            return fail(JPDF_E_INVALID_ARGUMENT, "word text contains a control or noncharacter");
        }
        const UCharDirection d = u_charDirection(static_cast<UChar32>(c));
        if (is_explicit_bidi(d)) {
            return fail(JPDF_E_UNSUPPORTED, "bidi embeddings, overrides and isolates are refused");
        }
        word->bidi = word->bidi || is_right_to_left(d);
        word->cjk_only = word->cjk_only && is_cjk(c);
        word->blank = word->blank && is_space(c);
    }
    return JPDF_OK;
}

}  // namespace

jpdf_status parse_text_layer(const jpdf_text_layer_options* options, const jpdf_text_line* lines,
                             int32_t line_count, const jpdf_text_word* words, int32_t word_count,
                             TextLayer* out) {
    jpdf_status st = parse_options(options, out);
    if (st != JPDF_OK) return st;
    if (line_count < 0 || word_count < 0) {
        return fail(JPDF_E_INVALID_ARGUMENT, "line_count and word_count must not be negative");
    }
    if (line_count > kMaxLines || word_count > kMaxWords) {
        return fail(JPDF_E_LIMIT, "text layer exceeds 1048576 lines or 2097152 words");
    }
    std::vector<jpdf_text_line> line_items;
    std::vector<jpdf_text_word> word_items;
    uint32_t line_size = 0;
    uint32_t word_size = 0;
    st = read_array(lines, line_count, offsetof(jpdf_text_line, quad), "jpdf_text_line",
                    &line_items, &line_size);
    if (st != JPDF_OK) return st;
    st = read_array(words, word_count, offsetof(jpdf_text_word, quad), "jpdf_text_word",
                    &word_items, &word_size);
    if (st != JPDF_OK) return st;

    size_t next_word = 0;
    out->lines.reserve(line_items.size());
    for (const jpdf_text_line& item : line_items) {
        constexpr uint32_t kLineFlags =
            JPDF_TEXT_LINE_QUAD | JPDF_TEXT_LINE_LTR | JPDF_TEXT_LINE_RTL;
        if ((item.flags & ~kLineFlags) != 0 || item.reserved0 != 0) {
            return fail(JPDF_E_UNSUPPORTED, "unknown jpdf_text_line.flags, or reserved0 is set");
        }
        LayerLine line;
        if ((item.flags & JPDF_TEXT_LINE_LTR) && (item.flags & JPDF_TEXT_LINE_RTL)) {
            return fail(JPDF_E_INVALID_ARGUMENT, "a line cannot be both LTR and RTL");
        }
        if (item.flags & JPDF_TEXT_LINE_LTR) line.direction = Direction::Ltr;
        if (item.flags & JPDF_TEXT_LINE_RTL) line.direction = Direction::Rtl;
        line.quad = (item.flags & JPDF_TEXT_LINE_QUAD) != 0;
        if (line.quad) {
            if (line_size < sizeof(jpdf_text_line)) {
                return fail(JPDF_E_INVALID_ARGUMENT, "JPDF_TEXT_LINE_QUAD needs the 1.4 struct");
            }
            if (!quad_ok(item.quad, &line.corners)) {
                return fail(JPDF_E_INVALID_ARGUMENT, "line quads need finite coordinates");
            }
        } else if (!box_ok(item.x0, item.y0, item.x1, item.y1) || !(item.y1 > item.y0)) {
            return fail(JPDF_E_INVALID_ARGUMENT, "line boxes need finite x1 >= x0 and y1 > y0");
        } else {
            line.x0 = item.x0;
            line.y0 = item.y0;
            line.x1 = item.x1;
            line.y1 = item.y1;
            line.corners = box_corners(item.x0, item.y0, item.x1, item.y1);
        }
        if (item.word_count < 1 ||
            static_cast<size_t>(item.word_count) > word_items.size() - next_word) {
            return fail(JPDF_E_INVALID_ARGUMENT,
                        "each line needs 1 or more of the remaining words");
        }
        line.first_word = next_word;
        line.word_count = static_cast<size_t>(item.word_count);
        out->lines.push_back(line);
        next_word += line.word_count;
    }
    if (next_word != word_items.size()) {
        return fail(JPDF_E_INVALID_ARGUMENT, "the lines must use every word exactly once");
    }

    uint64_t text_bytes = 0;
    out->words.resize(word_items.size());
    for (size_t i = 0; i < word_items.size(); ++i) {
        st = parse_word(word_items[i], word_size, &text_bytes, &out->words[i]);
        if (st != JPDF_OK) return st;
    }
    return JPDF_OK;
}

}  // namespace jpdf
