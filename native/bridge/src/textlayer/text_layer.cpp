// text_layer.cpp - validation and serialization of invisible text layer PDFs.

#include "text_layer.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <string_view>
#include <unordered_map>

#include "glyphless_font.h"
#include "output/rust_codecs.h"
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
constexpr long long kScale = 10000;  // numbers are written with up to four decimals

// --- Unicode classes ---

bool is_control(char32_t c) {
    return c < 0x20 || (c >= 0x7F && c <= 0x9F);
}

bool is_noncharacter(char32_t c) {
    return (c >= 0xFDD0 && c <= 0xFDEF) || (c & 0xFFFE) == 0xFFFE;
}

// Strong right-to-left scripts and RTL controls: extractors reorder these, so 1:1 layout breaks.
bool is_right_to_left(char32_t c) {
    return (c >= 0x0590 && c <= 0x08FF) || (c >= 0xFB1D && c <= 0xFDFF) ||
           (c >= 0xFE70 && c <= 0xFEFE) || (c >= 0x10800 && c <= 0x10FFF) ||
           (c >= 0x1E800 && c <= 0x1EFFF) || c == 0x200F || c == 0x202B || c == 0x202E ||
           c == 0x2067;
}

// OCRmyPDF's _is_cjk_only ranges: words written without separating spaces.
bool is_cjk(char32_t c) {
    return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF) ||
           (c >= 0x20000 && c <= 0x2A6DF) || (c >= 0x2A700 && c <= 0x2EBEF) ||
           (c >= 0x30000 && c <= 0x3134F) || (c >= 0x3040 && c <= 0x30FF) ||
           (c >= 0x31F0 && c <= 0x31FF) || (c >= 0xAC00 && c <= 0xD7AF) ||
           (c >= 0x1100 && c <= 0x11FF) || (c >= 0x3130 && c <= 0x318F) ||
           (c >= 0xA960 && c <= 0xA97F) || (c >= 0xD7B0 && c <= 0xD7FF) ||
           (c >= 0x3000 && c <= 0x303F) || (c >= 0xFF00 && c <= 0xFFEF);
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

bool box_ok(double x0, double y0, double x1, double y1) {
    for (double v : {x0, y0, x1, y1}) {
        if (!std::isfinite(v) || std::fabs(v) > kMaxCoordinate) return false;
    }
    return x1 >= x0 && y1 >= y0;
}

bool dpi_ok(double dpi) {
    return std::isfinite(dpi) && dpi >= kMinDpi && dpi <= kMaxDpi;
}

// Copies a caller array whose stride is the first element's struct_size.
template <typename T>
jpdf_status read_array(const T* items, int32_t count, std::string_view name, std::vector<T>* out) {
    if (count == 0) return JPDF_OK;
    if (!items) return fail(JPDF_E_INVALID_ARGUMENT, name, " array is NULL");
    uint32_t stride = 0;
    std::memcpy(&stride, items, sizeof(stride));
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
        jpdf_status st = check_input_struct(item, size, sizeof(T), sizeof(T), name);
        if (st != JPDF_OK) return st;
        T value;
        std::memcpy(&value, item, sizeof(T));
        out->push_back(value);
    }
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

jpdf_status parse_word(const jpdf_text_word& item, uint64_t* text_bytes, LayerWord* word) {
    if (item.flags != 0) return fail(JPDF_E_UNSUPPORTED, "jpdf_text_word.flags must be 0");
    if (!item.text_utf8) return fail(JPDF_E_INVALID_ARGUMENT, "word text is NULL");
    if (!box_ok(item.x0, item.y0, item.x1, item.y1)) {
        return fail(JPDF_E_INVALID_ARGUMENT, "word boxes need finite x1 >= x0 and y1 >= y0");
    }
    const size_t len = std::strlen(item.text_utf8);
    *text_bytes += len;
    if (*text_bytes > kMaxTextBytes) return fail(JPDF_E_LIMIT, "text layer exceeds 16 MiB of text");
    if (len == 0 || !decode_utf8(std::string_view(item.text_utf8, len), &word->text)) {
        return fail(JPDF_E_INVALID_ARGUMENT, "word text must be non-empty, valid UTF-8");
    }
    word->x0 = item.x0;
    word->x1 = item.x1;
    word->cjk_only = true;
    word->blank = true;
    for (char32_t c : word->text) {
        if (is_control(c) || is_noncharacter(c)) {
            return fail(JPDF_E_INVALID_ARGUMENT, "word text contains a control or noncharacter");
        }
        if (is_right_to_left(c)) {
            return fail(JPDF_E_UNSUPPORTED, "right-to-left text is not supported yet");
        }
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
void append_scaled(std::string& out, long long v) {
    if (v < 0) {
        out += '-';
        v = -v;
    }
    out += std::to_string(v / kScale);
    long long frac = v % kScale;
    if (frac == 0) return;
    char digits[4];
    for (int i = 3; i >= 0; --i) {
        digits[i] = static_cast<char>('0' + frac % 10);
        frac /= 10;
    }
    size_t len = 4;
    while (digits[len - 1] == '0') --len;
    out += '.';
    out.append(digits, len);
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

// One BT block per line with a Td and Tz per word, as OCRmyPDF's renderer emits them.
jpdf_status content_stream(const TextLayer& layer, CidMap* cids, std::string* out) {
    const double sx = 72.0 / layer.dpi_x;
    const double sy = 72.0 / layer.dpi_y;
    const double page_height = layer.image_height * sy;
    const double descent = -kGlyphlessDescent / 1000.0;
    const double advance = kGlyphlessAdvance / 1000.0;
    for (const LayerLine& line : layer.lines) {
        // Ascent + descent is one em, so glyph boxes fill the line box exactly.
        const double size = (line.y1 - line.y0) * sy;
        if (size < kMinFontSize)
            return fail(JPDF_E_INVALID_ARGUMENT, "line is too short to hold text");
        *out += "BT\n3 Tr\n/F1 ";
        append_number(*out, size);
        *out += " Tf\n";
        long long previous_x = 0;
        for (size_t i = 0; i < line.word_count; ++i) {
            const LayerWord& word = layer.words[line.first_word + i];
            const double width = (word.x1 - word.x0) * sx;
            const double natural = static_cast<double>(word.text.size()) * advance * size;
            double tz = natural > 0 && width > 0 ? 100.0 * width / natural : 100.0;
            bool space = false;
            if (i + 1 < line.word_count) {
                const LayerWord& next = layer.words[line.first_word + i + 1];
                const double step = (next.x0 - word.x0) * sx;
                const bool both_cjk = word.cjk_only && next.cjk_only;
                space = step > 0 && !both_cjk;
                // CJK words carry no separator, so stretch across a punctuation-sized gap.
                if (both_cjk && width > 0 && step > 0 && step - width <= 2 * size) {
                    tz *= step / width;
                }
            } else {
                space = !word.blank && !word.cjk_only;
            }
            const long long x = scaled(word.x0 * sx);
            if (i == 0) {
                append_scaled(*out, x);
                *out += ' ';
                append_number(*out, page_height - line.y1 * sy + descent * size);
            } else {
                append_scaled(*out, x - previous_x);  // exact, so rounding never accumulates
                *out += " 0";
            }
            *out += " Td\n";
            previous_x = x;
            append_number(*out, std::min(tz, kMaxTz));
            *out += " Tz\n<";
            uint32_t cid = 0;
            for (char32_t c : word.text) {
                jpdf_status st = cids->cid(c, &cid);
                if (st != JPDF_OK) return st;
                append_hex(*out, cid, 4);
            }
            if (space) {
                jpdf_status st = cids->cid(U' ', &cid);
                if (st != JPDF_OK) return st;
                append_hex(*out, cid, 4);
            }
            *out += "> Tj\n";
        }
        *out += "ET\n";
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
    if ((st = read_array(lines, line_count, "jpdf_text_line", &line_items)) != JPDF_OK) return st;
    if ((st = read_array(words, word_count, "jpdf_text_word", &word_items)) != JPDF_OK) return st;

    size_t next_word = 0;
    out->lines.reserve(line_items.size());
    for (const jpdf_text_line& item : line_items) {
        if (item.flags != 0 || item.reserved0 != 0) {
            return fail(JPDF_E_UNSUPPORTED, "jpdf_text_line.flags and reserved0 must be 0");
        }
        if (!box_ok(item.x0, item.y0, item.x1, item.y1) || !(item.y1 > item.y0)) {
            return fail(JPDF_E_INVALID_ARGUMENT, "line boxes need finite x1 >= x0 and y1 > y0");
        }
        if (item.word_count < 1 ||
            static_cast<size_t>(item.word_count) > word_items.size() - next_word) {
            return fail(JPDF_E_INVALID_ARGUMENT,
                        "each line needs 1 or more of the remaining words");
        }
        LayerLine line;
        line.x0 = item.x0;
        line.y0 = item.y0;
        line.x1 = item.x1;
        line.y1 = item.y1;
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
        if ((st = parse_word(word_items[i], &text_bytes, &out->words[i])) != JPDF_OK) return st;
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
    if ((st = pdf.stream("", content)) != JPDF_OK) return st;
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
        if ((st = pdf.stream(" /Length1 " + std::to_string(font.size()), font_bytes)) != JPDF_OK) {
            return st;
        }
        if ((st = pdf.stream("", to_unicode_cmap(cids.chars()))) != JPDF_OK) return st;
        if ((st = pdf.stream("", cid_to_gid_map(cids.chars().size()))) != JPDF_OK) return st;
    }
    return pdf.finish(out);
}

}  // namespace jpdf
