// text_layer_pdf.cpp - PDF serialization for the invisible text layer and its C entry point.
// Split out of the former text_layer.cpp; the moved code is unchanged.

#include <unicode/ubidi.h>
#include <unicode/uchar.h>
#include <unicode/utf16.h>

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <initializer_list>
#include <limits>
#include <numeric>
#include <string>
#include <string_view>
#include <unordered_map>
#include <utility>
#include <vector>

#include "jpdfium_api.h"
#include "output/image_writer.h"
#include "runtime/api_status.h"
#include "text_layer.h"
#include "text_layer_internal.h"

namespace jpdf {

namespace {

// PDF serialization

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

}  // namespace jpdf

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
        st = jpdf::build_text_layer_pdf(layer, &pdf);
        if (st != JPDF_OK) return st;
        return jpdf::write_bytes_file(reinterpret_cast<const uint8_t*>(pdf.data()), pdf.size(),
                                      output_path_utf8, request);
    });
}

}  // extern "C"
