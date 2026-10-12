// text_layer.cpp - validation and serialization of invisible text layer PDFs.

#include "text_layer.h"

#include <unicode/ubidi.h>
#include <unicode/uchar.h>
#include <unicode/utf16.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstring>
#include <initializer_list>
#include <limits>
#include <numeric>
#include <string>
#include <string_view>
#include <unordered_map>
#include <utility>

#include "jpdfium_api.h"
#include "output/image_writer.h"
#include "runtime/api_status.h"

namespace jpdf {

namespace {

constexpr int32_t kMaxLines = 1 << 20;
constexpr int32_t kMaxWords = 1 << 21;
constexpr uint64_t kMaxTextBytes = uint64_t{16} << 20;
constexpr int32_t kMaxImageSide = 1 << 20;
constexpr double kMinDpi = 1.0;
constexpr double kMaxDpi = 100000.0;
constexpr double kMaxCoordinate = 1e7;  // pixels
constexpr double kMinFontSize = 1e-4;   // points
constexpr double kMaxTz = 1e9;
constexpr size_t kMaxCids = 0xFFFF;  // two-byte Identity-H codes; CID 0 stays .notdef
constexpr int32_t kDeflateLevel = 6;
constexpr long long kScale = 10000;        // numbers are written with up to four decimals
constexpr long long kUnitScale = 1000000;  // matrix directions get six decimals
constexpr double kMinQuadLength = 1e-4;    // points
constexpr double kMinShear = 0.1;          // sine of the angle between a quad's baseline and sides

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
    const size_t len = std::strlen(item.text_utf8);
    *text_bytes += len;
    if (*text_bytes > kMaxTextBytes) return fail(JPDF_E_LIMIT, "text layer exceeds 16 MiB of text");
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

// --- PDF serialization ---

long long scaled(double v) {
    if (!std::isfinite(v)) return 0;  // inputs are validated; this keeps llround defined
    return std::llround(std::clamp(v, -1e14, 1e14) * static_cast<double>(kScale));
}

// Fixed-point with no exponent, independent of the C locale.
void append_fixed(std::string& out, long long v, long long scale, int places) {
    if (v < 0) {
        out += '-';
        v = -v;
    }
    out += std::to_string(v / scale);
    long long frac = v % scale;
    if (frac == 0) return;
    char digits[8];
    for (int i = places - 1; i >= 0; --i) {
        digits[i] = static_cast<char>('0' + frac % 10);
        frac /= 10;
    }
    auto len = static_cast<size_t>(places);
    while (digits[len - 1] == '0') --len;
    out += '.';
    out.append(digits, len);
}

void append_scaled(std::string& out, long long v) {
    append_fixed(out, v, kScale, 4);
}

void append_unit(std::string& out, double v) {
    append_fixed(out, std::llround(std::clamp(v, -1.0, 1.0) * static_cast<double>(kUnitScale)),
                 kUnitScale, 6);
}

void append_number(std::string& out, double v) {
    append_scaled(out, scaled(v));
}

void append_hex(std::string& out, uint64_t v, int digits) {
    static constexpr char kHex[] = "0123456789ABCDEF";
    for (int shift = 4 * (digits - 1); shift >= 0; shift -= 4) out += kHex[(v >> shift) & 0xF];
}

void append_utf16be(std::string& out, char32_t c) {
    if (c < 0x10000) {
        append_hex(out, c, 4);
        return;
    }
    const char32_t v = c - 0x10000;
    append_hex(out, 0xD800 + (v >> 10), 4);
    append_hex(out, 0xDC00 + (v & 0x3FF), 4);
}

// CIDs in order of first use, so equal input gives equal output.
class CidMap {
   public:
    jpdf_status cid(char32_t c, uint32_t* out) {
        auto found = ids_.find(c);
        if (found != ids_.end()) {
            *out = found->second;
            return JPDF_OK;
        }
        if (chars_.size() >= kMaxCids) {
            return fail(JPDF_E_LIMIT, "text layer uses more than 65535 distinct characters");
        }
        chars_.push_back(c);
        *out = static_cast<uint32_t>(chars_.size());
        ids_.emplace(c, *out);
        return JPDF_OK;
    }
    const std::vector<char32_t>& chars() const {
        return chars_;
    }

   private:
    std::unordered_map<char32_t, uint32_t> ids_;
    std::vector<char32_t> chars_;  // chars_[cid - 1]
};

jpdf_status append_cids(const char32_t* text, size_t count, CidMap* cids, std::string* out) {
    uint32_t cid = 0;
    for (size_t k = 0; k < count; ++k) {
        jpdf_status st = cids->cid(text[k], &cid);
        if (st != JPDF_OK) return st;
        append_hex(*out, cid, 4);
    }
    return JPDF_OK;
}

// ICU bidi levels per code point, reusing one UBiDi object across words.
class Bidi {
   public:
    Bidi() : bidi_(ubidi_open()) {}
    ~Bidi() {
        if (bidi_) ubidi_close(bidi_);
    }
    Bidi(const Bidi&) = delete;
    Bidi& operator=(const Bidi&) = delete;

    jpdf_status levels(const std::vector<char32_t>& text, bool rtl, std::vector<uint8_t>* out) {
        if (!bidi_) return fail(JPDF_E_NO_MEMORY, "out of memory for bidi layout");
        units_.clear();
        starts_.clear();
        for (char32_t c : text) {
            starts_.push_back(units_.size());
            if (c < 0x10000) {
                units_.push_back(static_cast<UChar>(c));
            } else {
                units_.push_back(static_cast<UChar>(U16_LEAD(c)));
                units_.push_back(static_cast<UChar>(U16_TRAIL(c)));
            }
        }
        UErrorCode err = U_ZERO_ERROR;
        ubidi_setPara(bidi_, units_.data(), static_cast<int32_t>(units_.size()),
                      static_cast<UBiDiLevel>(rtl ? 1 : 0), nullptr, &err);
        const UBiDiLevel* levels = U_SUCCESS(err) ? ubidi_getLevels(bidi_, &err) : nullptr;
        if (U_FAILURE(err) || !levels) {
            return fail(err == U_MEMORY_ALLOCATION_ERROR ? JPDF_E_NO_MEMORY : JPDF_E_INTERNAL,
                        "bidi layout failed");
        }
        out->resize(text.size());
        for (size_t i = 0; i < text.size(); ++i) (*out)[i] = levels[starts_[i]];
        return JPDF_OK;
    }

   private:
    UBiDi* bidi_;
    std::vector<UChar> units_;
    std::vector<size_t> starts_;  // first UTF-16 unit of each code point
};

// UAX #9 rule L2 on code points: the visual position of each logical character.
void visual_positions(const std::vector<uint8_t>& levels, std::vector<size_t>* out) {
    const size_t n = levels.size();
    std::vector<size_t> order(n);  // order[visual] = logical
    std::iota(order.begin(), order.end(), size_t{0});
    const auto [low, high] = std::minmax_element(levels.begin(), levels.end());
    if (n > 0) {
        for (int level = *high; level >= (*low | 1); --level) {
            for (size_t i = 0; i < n;) {
                if (levels[order[i]] < level) {
                    ++i;
                    continue;
                }
                size_t j = i;
                while (j < n && levels[order[j]] >= level) ++j;
                std::reverse(order.begin() + static_cast<std::ptrdiff_t>(i),
                             order.begin() + static_cast<std::ptrdiff_t>(j));
                i = j;
            }
        }
    }
    out->resize(n);
    for (size_t v = 0; v < n; ++v) (*out)[order[v]] = v;
}

// First strong character of the line (UAX #9 P2), left-to-right without one.
bool line_is_rtl(const TextLayer& layer, const LayerLine& line) {
    if (line.direction != Direction::Auto) return line.direction == Direction::Rtl;
    for (size_t i = 0; i < line.word_count; ++i) {
        for (char32_t c : layer.words[line.first_word + i].text) {
            const UCharDirection d = u_charDirection(static_cast<UChar32>(c));
            if (d == U_LEFT_TO_RIGHT) return false;
            if (d == U_RIGHT_TO_LEFT || d == U_RIGHT_TO_LEFT_ARABIC) return true;
        }
    }
    return false;
}

double cross(Point a, Point b) {
    return a.x * b.y - a.y * b.x;
}

// A line's text space in points: u runs along the baseline, w from the bottom edge to the top.
struct Frame {
    Point origin;  // the bottom-left corner raised by the descent
    Point u{1, 0};
    Point w{0, 1};
    double det = 1;   // cross(u, w)
    double size = 0;  // font size: one em spans the line's height along w
};

class PageSpace {
   public:
    explicit PageSpace(const TextLayer& layer)
        : sx_(72.0 / layer.dpi_x), sy_(72.0 / layer.dpi_y), height_(layer.image_height * sy_) {}

    Point map(Point p) const {
        return {p.x * sx_, height_ - p.y * sy_};
    }
    double sx() const {
        return sx_;
    }
    double sy() const {
        return sy_;
    }
    double height() const {
        return height_;
    }

    jpdf_status frame(const LayerLine& line, Frame* f) const {
        Point bottom_left = map({line.x0, line.y1});
        f->size = (line.y1 - line.y0) * sy_;
        if (line.quad) {
            Point q[4];
            for (size_t k = 0; k < 4; ++k) q[k] = map(line.corners[k]);
            const Point u{(q[1].x - q[0].x + q[2].x - q[3].x) / 2,
                          (q[1].y - q[0].y + q[2].y - q[3].y) / 2};
            const Point w{(q[0].x - q[3].x + q[1].x - q[2].x) / 2,
                          (q[0].y - q[3].y + q[1].y - q[2].y) / 2};
            const double u_len = std::hypot(u.x, u.y);
            const double w_len = std::hypot(w.x, w.y);
            if (!(u_len >= kMinQuadLength)) {
                return fail(JPDF_E_INVALID_ARGUMENT, "line quad has no length");
            }
            if (!(w_len >= kMinFontSize)) {
                return fail(JPDF_E_INVALID_ARGUMENT, "line is too short to hold text");
            }
            f->u = {u.x / u_len, u.y / u_len};
            f->w = {w.x / w_len, w.y / w_len};
            f->det = cross(f->u, f->w);
            if (!(f->det >= kMinShear)) {
                return fail(JPDF_E_INVALID_ARGUMENT,
                            "line quad corners must run clockwise from the top-left, "
                            "with sides within 84 degrees of upright");
            }
            f->size = w_len;
            bottom_left = q[3];
        }
        if (!(f->size >= kMinFontSize)) {
            return fail(JPDF_E_INVALID_ARGUMENT, "line is too short to hold text");
        }
        const double raise = -kGlyphlessDescent / 1000.0 * f->size;
        f->origin = {bottom_left.x + f->w.x * raise, bottom_left.y + f->w.y * raise};
        return JPDF_OK;
    }

    // The word's extent along the baseline, in points from the frame's origin.
    void extent(const Frame& f, const LayerWord& word, double* s0, double* s1) const {
        *s0 = std::numeric_limits<double>::infinity();
        *s1 = -*s0;
        for (const Point& corner : word.corners) {
            const Point p = map(corner);
            const double s = cross({p.x - f.origin.x, p.y - f.origin.y}, f.w) / f.det;
            *s0 = std::min(*s0, s);
            *s1 = std::max(*s1, s);
        }
    }

   private:
    double sx_;
    double sy_;
    double height_;
};

// Horizontal scale and trailing space for word i, shared by both line writers.
struct Spacing {
    double tz = 100;
    bool space = false;
};

// starts times scale and widths are points along the line's reading direction.
Spacing spacing(const TextLayer& layer, const LayerLine& line, size_t i, double size,
                const std::vector<double>& starts, double scale,
                const std::vector<double>& widths) {
    const double advance = kGlyphlessAdvance / 1000.0;
    const LayerWord& word = layer.words[line.first_word + i];
    const double width = widths[i];
    const double natural = static_cast<double>(word.text.size()) * advance * size;
    Spacing out;
    out.tz = natural > 0 && width > 0 ? 100.0 * width / natural : 100.0;
    if (i + 1 < line.word_count) {
        const LayerWord& next = layer.words[line.first_word + i + 1];
        const double step = (starts[i + 1] - starts[i]) * scale;
        const bool both_cjk = word.cjk_only && next.cjk_only;
        out.space = step > 0 && !both_cjk;
        // CJK words carry no separator, so stretch across a punctuation-sized gap.
        if (both_cjk && width > 0 && step > 0 && step - width <= 2 * size) {
            out.tz *= step / width;
        }
    } else {
        out.space = !word.blank && !word.cjk_only;
    }
    out.tz = std::min(out.tz, kMaxTz);
    return out;
}

// Left-to-right box lines: one BT block with a Td and Tz per word, as OCRmyPDF emits them.
jpdf_status simple_line(const TextLayer& layer, const PageSpace& page, const LayerLine& line,
                        CidMap* cids, std::string* out) {
    const double size = (line.y1 - line.y0) * page.sy();
    if (!(size >= kMinFontSize)) {
        return fail(JPDF_E_INVALID_ARGUMENT, "line is too short to hold text");
    }
    std::vector<double> starts(line.word_count);
    std::vector<double> widths(line.word_count);
    for (size_t i = 0; i < line.word_count; ++i) {
        const LayerWord& word = layer.words[line.first_word + i];
        starts[i] = word.x0;
        widths[i] = (word.x1 - word.x0) * page.sx();
    }
    *out += "BT\n3 Tr\n/F1 ";
    append_number(*out, size);
    *out += " Tf\n";
    long long previous_x = 0;
    for (size_t i = 0; i < line.word_count; ++i) {
        const LayerWord& word = layer.words[line.first_word + i];
        const Spacing sp = spacing(layer, line, i, size, starts, page.sx(), widths);
        const long long x = scaled(word.x0 * page.sx());
        if (i == 0) {
            append_scaled(*out, x);
            *out += ' ';
            // Ascent + descent is one em, so glyph boxes fill the line box exactly.
            append_number(*out,
                          page.height() - line.y1 * page.sy() - kGlyphlessDescent / 1000.0 * size);
        } else {
            append_scaled(*out, x - previous_x);  // exact, so rounding never accumulates
            *out += " 0";
        }
        *out += " Td\n";
        previous_x = x;
        append_number(*out, sp.tz);
        *out += " Tz\n<";
        jpdf_status st = append_cids(word.text.data(), word.text.size(), cids, out);
        if (st != JPDF_OK) return st;
        if (sp.space) {
            const char32_t blank = U' ';
            st = append_cids(&blank, 1, cids, out);
            if (st != JPDF_OK) return st;
        }
        *out += "> Tj\n";
    }
    *out += "ET\n";
    return JPDF_OK;
}

void append_matrix(std::string& out, const Frame& f, bool mirrored, double s) {
    const double a = mirrored ? -f.u.x : f.u.x;
    const double b = mirrored ? -f.u.y : f.u.y;
    for (double v : {a, b, f.w.x, f.w.y}) {
        append_unit(out, v);
        out += ' ';
    }
    append_number(out, f.origin.x + s * f.u.x);
    out += ' ';
    append_number(out, f.origin.y + s * f.u.y);
    out += " Tm\n";
}

// Quad or bidi lines: characters stay in logical order, and each directional run gets its own
// text matrix, mirrored for right-to-left runs, so extractors recover logical text.
jpdf_status general_line(const TextLayer& layer, const PageSpace& page, const LayerLine& line,
                         bool rtl, Bidi* bidi, CidMap* cids, std::string* out) {
    Frame f;
    jpdf_status st = page.frame(line, &f);
    if (st != JPDF_OK) return st;
    std::vector<double> lo(line.word_count);
    std::vector<double> hi(line.word_count);
    std::vector<double> starts(line.word_count);
    std::vector<double> widths(line.word_count);
    for (size_t i = 0; i < line.word_count; ++i) {
        page.extent(f, layer.words[line.first_word + i], &lo[i], &hi[i]);
        starts[i] = rtl ? -hi[i] : lo[i];
        widths[i] = hi[i] - lo[i];
    }
    *out += "BT\n3 Tr\n/F1 ";
    append_number(*out, f.size);
    *out += " Tf\n";
    std::vector<uint8_t> levels;
    std::vector<size_t> visual;
    for (size_t i = 0; i < line.word_count; ++i) {
        const LayerWord& word = layer.words[line.first_word + i];
        const Spacing sp = spacing(layer, line, i, f.size, starts, 1.0, widths);
        std::vector<char32_t> text = word.text;
        if (sp.space) text.push_back(U' ');
        st = bidi->levels(text, rtl, &levels);
        if (st != JPDF_OK) return st;
        visual_positions(levels, &visual);
        // Glyph cells start at the word's leading edge; a right-to-left line's space sits left.
        const double cell = kGlyphlessAdvance / 1000.0 * f.size * sp.tz / 100.0;
        const double cells = static_cast<double>(word.text.size());
        const double base = rtl ? hi[i] - cells * cell : lo[i];
        const double shift = rtl && sp.space ? 1.0 : 0.0;
        append_number(*out, sp.tz);
        *out += " Tz\n";
        for (size_t k = 0; k < text.size();) {
            const bool odd = (levels[k] & 1) != 0;
            size_t end = k + 1;
            while (
                end < text.size() && ((levels[end] & 1) != 0) == odd &&
                (odd ? visual[end] + 1 == visual[end - 1] : visual[end] == visual[end - 1] + 1)) {
                ++end;
            }
            const double slot = static_cast<double>(visual[k]) - shift + (odd ? 1.0 : 0.0);
            append_matrix(*out, f, odd, base + slot * cell);
            *out += '<';
            st = append_cids(text.data() + k, end - k, cids, out);
            if (st != JPDF_OK) return st;
            *out += "> Tj\n";
            k = end;
        }
    }
    *out += "ET\n";
    return JPDF_OK;
}

jpdf_status content_stream(const TextLayer& layer, CidMap* cids, std::string* out) {
    const PageSpace page(layer);
    Bidi bidi;
    for (const LayerLine& line : layer.lines) {
        const bool rtl = line_is_rtl(layer, line);
        bool simple = !line.quad && !rtl;
        for (size_t i = 0; simple && i < line.word_count; ++i) {
            const LayerWord& word = layer.words[line.first_word + i];
            simple = !word.quad && !word.bidi;
        }
        jpdf_status st = simple ? simple_line(layer, page, line, cids, out)
                                : general_line(layer, page, line, rtl, &bidi, cids, out);
        if (st != JPDF_OK) return st;
    }
    return JPDF_OK;
}

std::string to_unicode_cmap(const std::vector<char32_t>& chars) {
    std::string cmap =
        "/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n"
        "/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n"
        "/CMapName /Adobe-Identity-UCS def\n/CMapType 2 def\n"
        "1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n";
    for (size_t start = 0; start < chars.size(); start += 100) {
        const size_t count = std::min<size_t>(100, chars.size() - start);
        cmap += std::to_string(count) + " beginbfchar\n";
        for (size_t k = start; k < start + count; ++k) {
            cmap += '<';
            append_hex(cmap, k + 1, 4);
            cmap += "> <";
            append_utf16be(cmap, chars[k]);
            cmap += ">\n";
        }
        cmap += "endbfchar\n";
    }
    cmap += "endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n";
    return cmap;
}

// Every used CID draws glyph 1, the blank one.
std::string cid_to_gid_map(size_t cid_count) {
    std::string map(2 * (cid_count + 1), '\0');
    for (size_t cid = 1; cid <= cid_count; ++cid) map[2 * cid + 1] = 1;
    return map;
}

int32_t append_to_string(void* ctx, const uint8_t* data, size_t len) noexcept {
    try {
        static_cast<std::string*>(ctx)->append(reinterpret_cast<const char*>(data), len);
        return 0;
    } catch (...) {
        return 1;
    }
}

jpdf_status deflate(std::string_view in, std::string* out) {
    const int32_t rc = jpdfium_rust_zlib_compress(reinterpret_cast<const uint8_t*>(in.data()),
                                                  in.size(), kDeflateLevel, append_to_string, out);
    if (rc == -2) return fail(JPDF_E_NO_MEMORY, "out of memory compressing a stream");
    if (rc != 0) return fail(JPDF_E_INTERNAL, "stream compression failed");
    return JPDF_OK;
}

uint64_t fnv1a(std::string_view data, uint64_t hash) {
    for (unsigned char c : data) {
        hash ^= c;
        hash *= 0x100000001B3ull;
    }
    return hash;
}

// Objects are numbered in the order they are added.
class PdfFile {
   public:
    PdfFile() : out_("%PDF-1.7\n%\xE2\xE3\xCF\xD3\n") {}

    void object(std::string_view body) {
        open();
        out_ += body;
        close();
    }

    jpdf_status stream(std::string_view extra, std::string_view data) {
        std::string packed;
        jpdf_status st = deflate(data, &packed);
        if (st != JPDF_OK) return st;
        open();
        out_ += "<< /Length " + std::to_string(packed.size()) + " /Filter /FlateDecode";
        out_ += extra;
        out_ += " >>\nstream\n";
        out_ += packed;
        out_ += "\nendstream";
        close();
        return JPDF_OK;
    }

    jpdf_status finish(std::string* out) {
        const size_t xref = out_.size();
        const std::string size = std::to_string(offsets_.size() + 1);
        std::string id;
        append_hex(id, fnv1a(out_, 0xCBF29CE484222325ull), 16);
        append_hex(id, fnv1a(out_, 0x84222325CBF29CE4ull), 16);
        out_ += "xref\n0 " + size + "\n0000000000 65535 f \n";
        for (size_t offset : offsets_) {
            const std::string digits = std::to_string(offset);
            out_.append(10 - digits.size(), '0');
            out_ += digits + " 00000 n \n";
        }
        out_ += "trailer\n<< /Size " + size + " /Root 1 0 R /ID [<" + id + "> <" + id +
                ">] >>\nstartxref\n" + std::to_string(xref) + "\n%%EOF\n";
        // Validate before publication: every xref entry lands on its own object header.
        for (size_t i = 0; i < offsets_.size(); ++i) {
            const std::string header = std::to_string(i + 1) + " 0 obj\n";
            if (out_.compare(offsets_[i], header.size(), header) != 0) {
                return fail(JPDF_E_INTERNAL, "text layer PDF failed its layout check");
            }
        }
        *out = std::move(out_);
        return JPDF_OK;
    }

   private:
    void open() {
        offsets_.push_back(out_.size());
        out_ += std::to_string(offsets_.size()) + " 0 obj\n";
    }
    void close() {
        out_ += "\nendobj\n";
    }

    std::string out_;
    std::vector<size_t> offsets_;
};

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

jpdf_status build_text_layer_pdf(const TextLayer& layer, std::string* out) {
    CidMap cids;
    std::string content;
    jpdf_status st = content_stream(layer, &cids, &content);
    if (st != JPDF_OK) return st;
    const bool has_text = !cids.chars().empty();
    const std::string advance = std::to_string(kGlyphlessAdvance);
    const std::string ascent = std::to_string(kGlyphlessAscent);
    const std::string descent = std::to_string(kGlyphlessDescent);

    PdfFile pdf;
    pdf.object("<< /Type /Catalog /Pages 2 0 R >>");
    pdf.object("<< /Type /Pages /Kids [3 0 R] /Count 1 >>");
    std::string page = "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ";
    append_number(page, layer.image_width * 72.0 / layer.dpi_x);
    page += ' ';
    append_number(page, layer.image_height * 72.0 / layer.dpi_y);
    // OCRmyPDF's sandwich graft copies only this one font, found by its resource name.
    page += has_text ? "] /Resources << /Font << /F1 5 0 R >> >>" : "] /Resources << >>";
    page += " /Contents 4 0 R >>";
    pdf.object(page);
    st = pdf.stream("", content);
    if (st != JPDF_OK) return st;
    if (has_text) {
        pdf.object(
            "<< /Type /Font /Subtype /Type0 /BaseFont /GlyphLessFont /Encoding /Identity-H "
            "/DescendantFonts [6 0 R] /ToUnicode 9 0 R >>");
        pdf.object(
            "<< /Type /Font /Subtype /CIDFontType2 /BaseFont /GlyphLessFont /CIDSystemInfo "
            "<< /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> /FontDescriptor 7 0 R "
            "/DW " +
            advance + " /CIDToGIDMap 10 0 R >>");
        pdf.object("<< /Type /FontDescriptor /FontName /GlyphLessFont /Flags 5 /FontBBox [0 " +
                   descent + " " + advance + " " + ascent + "] /ItalicAngle 0 /Ascent " + ascent +
                   " /Descent " + descent + " /CapHeight 700 /StemV 80 /FontFile2 8 0 R >>");
        const std::vector<uint8_t>& font = glyphless_font();
        const std::string_view font_bytes(reinterpret_cast<const char*>(font.data()), font.size());
        st = pdf.stream(" /Length1 " + std::to_string(font.size()), font_bytes);
        if (st != JPDF_OK) return st;
        st = pdf.stream("", to_unicode_cmap(cids.chars()));
        if (st != JPDF_OK) return st;
        st = pdf.stream("", cid_to_gid_map(cids.chars().size()));
        if (st != JPDF_OK) return st;
    }
    return pdf.finish(out);
}

// Glyphless TrueType program, folded in from glyphless_font.cpp.

namespace {

constexpr int kUnitsPerEm = 1000;
constexpr int kGlyphCount = 2;
constexpr int kCapHeight = 700;

class Bytes {
   public:
    void u8(uint8_t v) {
        data_.push_back(v);
    }
    void u16(int v) {
        u8(static_cast<uint8_t>((v >> 8) & 0xFF));
        u8(static_cast<uint8_t>(v & 0xFF));
    }
    void i16(int v) {
        u16(v);
    }
    void u32(uint32_t v) {
        u16(static_cast<int>(v >> 16));
        u16(static_cast<int>(v & 0xFFFF));
    }
    void tag(std::string_view t) {
        for (char c : t) u8(static_cast<uint8_t>(c));
    }
    void zeros(size_t n) {
        data_.insert(data_.end(), n, 0);
    }
    std::vector<uint8_t>& data() {
        return data_;
    }

   private:
    std::vector<uint8_t> data_;
};

uint32_t checksum(const std::vector<uint8_t>& d, size_t begin, size_t length) {
    uint32_t sum = 0;
    for (size_t i = 0; i < length; i += 4) {
        uint32_t word = 0;
        for (size_t k = 0; k < 4; ++k) {
            word = (word << 8) | (i + k < length ? d[begin + i + k] : 0u);
        }
        sum += word;
    }
    return sum;
}

std::vector<uint8_t> os2() {
    Bytes b;
    b.u16(4);                  // version
    b.i16(kGlyphlessAdvance);  // xAvgCharWidth
    b.u16(400);                // usWeightClass
    b.u16(5);                  // usWidthClass
    b.u16(0);                  // fsType: installable embedding
    for (int v : {650, 600, 0, 75, 650, 600, 0, 350, 50, 300})
        b.i16(v);               // sub/superscript, strikeout
    b.i16(0);                   // sFamilyClass
    b.zeros(10);                // panose
    b.zeros(16);                // ulUnicodeRange1-4
    b.tag("NONE");              // achVendID
    b.u16(0x0040);              // fsSelection: regular
    b.u16(0xFFFF);              // usFirstCharIndex: the cmap maps only U+FFFF
    b.u16(0xFFFF);              // usLastCharIndex
    b.i16(kGlyphlessAscent);    // sTypoAscender
    b.i16(kGlyphlessDescent);   // sTypoDescender
    b.i16(0);                   // sTypoLineGap
    b.u16(kGlyphlessAscent);    // usWinAscent
    b.u16(-kGlyphlessDescent);  // usWinDescent
    b.zeros(8);                 // ulCodePageRange1-2
    b.i16(500);                 // sxHeight
    b.i16(kCapHeight);          // sCapHeight
    b.u16(0);                   // usDefaultChar
    b.u16(32);                  // usBreakChar
    b.u16(0);                   // usMaxContext
    return std::move(b.data());
}

// Format 4 with only the mandatory final segment, so no character maps to a glyph.
std::vector<uint8_t> cmap() {
    Bytes b;
    b.u16(0);       // version
    b.u16(1);       // numTables
    b.u16(3);       // platform: Windows
    b.u16(1);       // encoding: Unicode BMP
    b.u32(12);      // subtable offset
    b.u16(4);       // format
    b.u16(24);      // length
    b.u16(0);       // language
    b.u16(2);       // segCountX2
    b.u16(2);       // searchRange
    b.u16(0);       // entrySelector
    b.u16(0);       // rangeShift
    b.u16(0xFFFF);  // endCode
    b.u16(0);       // reservedPad
    b.u16(0xFFFF);  // startCode
    b.u16(1);       // idDelta: 0xFFFF maps to glyph 0
    b.u16(0);       // idRangeOffset
    return std::move(b.data());
}

// Two single-point contours span the em box but enclose no area, so nothing is ever painted.
// PDFium skips text whose glyph box is empty, which a truly empty glyph would have.
std::vector<uint8_t> blank_glyph() {
    Bytes b;
    b.i16(2);  // numberOfContours
    b.i16(0);
    b.i16(kGlyphlessDescent);
    b.i16(kGlyphlessAdvance);
    b.i16(kGlyphlessAscent);
    b.u16(0);  // endPtsOfContours
    b.u16(1);
    b.u16(0);  // instructionLength
    b.u8(1);   // on-curve, 16-bit deltas
    b.u8(1);
    b.i16(0);
    b.i16(kGlyphlessAdvance);
    b.i16(kGlyphlessDescent);
    b.i16(kGlyphlessAscent - kGlyphlessDescent);
    return std::move(b.data());
}

// .notdef and glyph 1 share the blank outline.
std::vector<uint8_t> glyf() {
    std::vector<uint8_t> glyph = blank_glyph();
    std::vector<uint8_t> out = glyph;
    out.insert(out.end(), glyph.begin(), glyph.end());
    return out;
}

std::vector<uint8_t> head() {
    Bytes b;
    b.u32(0x00010000);  // version
    b.u32(0x00010000);  // fontRevision
    b.u32(0);           // checkSumAdjustment, patched after assembly
    b.u32(0x5F0F3CF5);  // magicNumber
    b.u16(0x000B);      // flags: baseline at 0, lsb at 0, integer ppem
    b.u16(kUnitsPerEm);
    b.zeros(16);  // created, modified: fixed for reproducible output
    b.i16(0);
    b.i16(kGlyphlessDescent);
    b.i16(kGlyphlessAdvance);
    b.i16(kGlyphlessAscent);
    b.u16(0);  // macStyle
    b.u16(3);  // lowestRecPPEM
    b.i16(2);  // fontDirectionHint
    b.i16(0);  // indexToLocFormat: short
    b.i16(0);  // glyphDataFormat
    return std::move(b.data());
}

std::vector<uint8_t> hhea() {
    Bytes b;
    b.u32(0x00010000);
    b.i16(kGlyphlessAscent);
    b.i16(kGlyphlessDescent);
    b.i16(0);                  // lineGap
    b.u16(kGlyphlessAdvance);  // advanceWidthMax
    b.i16(0);                  // minLeftSideBearing
    b.i16(0);                  // minRightSideBearing
    b.i16(kGlyphlessAdvance);  // xMaxExtent
    b.i16(1);                  // caretSlopeRise
    b.i16(0);                  // caretSlopeRun
    b.zeros(10);               // caretOffset, reserved
    b.i16(0);                  // metricDataFormat
    b.u16(kGlyphCount);        // numberOfHMetrics
    return std::move(b.data());
}

std::vector<uint8_t> hmtx() {
    Bytes b;
    for (int glyph = 0; glyph < kGlyphCount; ++glyph) {
        b.u16(kGlyphlessAdvance);
        b.i16(0);  // left side bearing
    }
    return std::move(b.data());
}

// Short offsets, stored halved; both glyphs are the same length.
std::vector<uint8_t> loca(size_t glyf_length) {
    Bytes b;
    for (int glyph = 0; glyph <= kGlyphCount; ++glyph) {
        b.u16(static_cast<int>(glyf_length / kGlyphCount * glyph / 2));
    }
    return std::move(b.data());
}

std::vector<uint8_t> maxp() {
    Bytes b;
    b.u32(0x00010000);
    b.u16(kGlyphCount);
    b.u16(2);     // maxPoints
    b.u16(2);     // maxContours
    b.zeros(4);   // maxCompositePoints, maxCompositeContours
    b.u16(2);     // maxZones
    b.zeros(16);  // maxTwilightPoints through maxComponentDepth
    return std::move(b.data());
}

std::vector<uint8_t> name() {
    constexpr std::array<std::pair<uint16_t, std::string_view>, 5> records = {{
        {1, "GlyphLessFont"},
        {2, "Regular"},
        {4, "GlyphLessFont"},
        {5, "Version 1.000"},
        {6, "GlyphLessFont"},
    }};
    Bytes b;
    b.u16(0);
    b.u16(static_cast<int>(records.size()));
    b.u16(static_cast<int>(6 + 12 * records.size()));
    int offset = 0;
    for (const auto& [id, text] : records) {
        b.u16(3);       // platform: Windows
        b.u16(1);       // encoding: Unicode BMP
        b.u16(0x0409);  // language: en-US
        b.u16(id);
        b.u16(static_cast<int>(text.size() * 2));
        b.u16(offset);
        offset += static_cast<int>(text.size() * 2);
    }
    for (const auto& record : records) {
        for (char c : record.second) b.u16(c);  // ASCII as UTF-16BE
    }
    return std::move(b.data());
}

std::vector<uint8_t> post() {
    Bytes b;
    b.u32(0x00030000);  // version 3: no glyph names
    b.u32(0);           // italicAngle
    b.i16(-100);        // underlinePosition
    b.i16(50);          // underlineThickness
    b.u32(1);           // isFixedPitch
    b.zeros(16);        // memory hints
    return std::move(b.data());
}

std::vector<uint8_t> build() {
    std::vector<uint8_t> glyph_data = glyf();
    const std::array<std::pair<std::string_view, std::vector<uint8_t>>, 10> tables = {{
        {"OS/2", os2()},
        {"cmap", cmap()},
        {"glyf", glyph_data},
        {"head", head()},
        {"hhea", hhea()},
        {"hmtx", hmtx()},
        {"loca", loca(glyph_data.size())},
        {"maxp", maxp()},
        {"name", name()},
        {"post", post()},
    }};
    Bytes font;
    font.u32(0x00010000);  // sfntVersion: TrueType outlines
    font.u16(10);          // numTables
    font.u16(128);         // searchRange: 16 * 2^3
    font.u16(3);           // entrySelector
    font.u16(32);          // rangeShift: 10 * 16 - 128
    size_t offset = 12 + 16 * tables.size();
    size_t head_offset = 0;
    for (const auto& [tag, data] : tables) {
        font.tag(tag);
        font.u32(checksum(data, 0, data.size()));
        font.u32(static_cast<uint32_t>(offset));
        font.u32(static_cast<uint32_t>(data.size()));
        if (tag == "head") head_offset = offset;
        offset += (data.size() + 3) & ~size_t{3};
    }
    for (const auto& table : tables) {
        const std::vector<uint8_t>& data = table.second;
        font.data().insert(font.data().end(), data.begin(), data.end());
        font.zeros(((data.size() + 3) & ~size_t{3}) - data.size());
    }
    std::vector<uint8_t> out = std::move(font.data());
    const uint32_t adjustment = 0xB1B0AFBAu - checksum(out, 0, out.size());
    for (int i = 0; i < 4; ++i) {
        out[head_offset + 8 + static_cast<size_t>(i)] =
            static_cast<uint8_t>(adjustment >> (24 - 8 * i));
    }
    return out;
}

}  // namespace

const std::vector<uint8_t>& glyphless_font() {
    static const std::vector<uint8_t> font = build();
    return font;
}

}  // namespace jpdf

// --- api_text_layer.cpp (merged) ---

extern "C" {

jpdf_status jpdf_text_layer_write(const jpdf_text_layer_options* options,
                                  const jpdf_text_line* lines, int32_t line_count,
                                  const jpdf_text_word* words, int32_t word_count,
                                  const char* output_path_utf8,
                                  const jpdf_write_options* write) noexcept {
    return jpdf::guarded([&] {
        if (!output_path_utf8 || !*output_path_utf8) {
            return jpdf::fail(JPDF_E_INVALID_ARGUMENT, "output path is required");
        }
        jpdf::WriteRequest request;
        jpdf_status st = jpdf::parse_document_write_options(write, &request);
        if (st != JPDF_OK) return st;
        jpdf::TextLayer layer;
        st = jpdf::parse_text_layer(options, lines, line_count, words, word_count, &layer);
        if (st != JPDF_OK) return st;
        std::string pdf;
        if ((st = jpdf::build_text_layer_pdf(layer, &pdf)) != JPDF_OK) return st;
        return jpdf::write_bytes_file(reinterpret_cast<const uint8_t*>(pdf.data()), pdf.size(),
                                      output_path_utf8, request);
    });
}

}  // extern "C"
