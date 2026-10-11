// api_text_layer.cpp - jpdf_text_layer_write and jpdf_page_get_text, read back by real PDFium.

#include <atomic>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <string>
#include <thread>
#include <vector>

#if defined(_WIN32)
#include <process.h>
#else
#include <unistd.h>
#endif

#include "jpdfium_api.h"

namespace fs = std::filesystem;

static int g_failures = 0;

#define CHECK(cond, msg)                                       \
    do {                                                       \
        if (!(cond)) {                                         \
            std::printf("FAIL %s (line %d)\n", msg, __LINE__); \
            ++g_failures;                                      \
        }                                                      \
    } while (0)

#define CHECK_STATUS(expr, expected, msg)                                                   \
    do {                                                                                    \
        jpdf_status _st = (expr);                                                           \
        if (_st != (expected)) {                                                            \
            std::printf("FAIL %s: got %s, want %s (line %d)\n", msg, jpdf_status_name(_st), \
                        jpdf_status_name(expected), __LINE__);                              \
            ++g_failures;                                                                   \
        }                                                                                   \
    } while (0)

namespace {

fs::path scratch() {
#if defined(_WIN32)
    const long pid = static_cast<long>(::_getpid());
#else
    const long pid = static_cast<long>(::getpid());
#endif
    static const fs::path dir = fs::temp_directory_path() / ("jpdf-text-" + std::to_string(pid));
    fs::create_directories(dir);
    return dir;
}

std::string path(const std::string& name) {
    return (scratch() / name).string();
}

std::string read_file(const std::string& p) {
    std::ifstream in(p, std::ios::binary);
    return std::string((std::istreambuf_iterator<char>(in)), {});
}

jpdf_text_word word(const char* text, double x0, double y0, double x1, double y1) {
    jpdf_text_word w{};
    w.struct_size = sizeof(w);
    w.text_utf8 = text;
    w.x0 = x0;
    w.y0 = y0;
    w.x1 = x1;
    w.y1 = y1;
    return w;
}

jpdf_text_line line(double x0, double y0, double x1, double y1, int32_t word_count) {
    jpdf_text_line l{};
    l.struct_size = sizeof(l);
    l.x0 = x0;
    l.y0 = y0;
    l.x1 = x1;
    l.y1 = y1;
    l.word_count = word_count;
    return l;
}

jpdf_text_layer_options options(int32_t w, int32_t h, double dpi_x, double dpi_y) {
    jpdf_text_layer_options o{};
    o.struct_size = sizeof(o);
    o.image_width = w;
    o.image_height = h;
    o.dpi_x = dpi_x;
    o.dpi_y = dpi_y;
    return o;
}

// A 2550 x 3300 scan at 300 dpi: a US Letter page.
const jpdf_text_layer_options kLetter = options(2550, 3300, 300, 300);

jpdf_status write(const std::vector<jpdf_text_line>& lines,
                  const std::vector<jpdf_text_word>& words, const std::string& out,
                  const jpdf_text_layer_options& o = kLetter,
                  const jpdf_write_options* w = nullptr) {
    return jpdf_text_layer_write(&o, lines.data(), static_cast<int32_t>(lines.size()), words.data(),
                                 static_cast<int32_t>(words.size()), out.c_str(), w);
}

struct Opened {
    jpdf_handle doc = 0;
    jpdf_handle page = 0;
    ~Opened() {
        if (page) jpdf_page_close(page);
        if (doc) jpdf_doc_close(doc);
    }
};

bool open(const std::string& file, Opened* o) {
    return jpdf_doc_open_file(file.c_str(), nullptr, &o->doc) == JPDF_OK &&
           jpdf_page_open(o->doc, 0, &o->page) == JPDF_OK;
}

std::string page_text(jpdf_handle page) {
    uint64_t needed = 0;
    if (jpdf_page_get_text(page, nullptr, 0, &needed) != JPDF_OK || needed == 0) return "<error>";
    std::string text(static_cast<size_t>(needed), '\0');
    if (jpdf_page_get_text(page, text.data(), needed, &needed) != JPDF_OK) return "<error>";
    text.pop_back();
    return text;
}

// Collapses PDFium's generated line breaks and spacing to single spaces.
std::string squash(const std::string& s) {
    std::string out;
    for (char c : s) {
        const bool blank = c == ' ' || c == '\r' || c == '\n';
        if (blank) {
            if (!out.empty() && out.back() != ' ') out += ' ';
        } else {
            out += c;
        }
    }
    while (!out.empty() && out.back() == ' ') out.pop_back();
    return out;
}

bool renders_blank(jpdf_handle page) {
    jpdf_render_options r{};
    r.struct_size = sizeof(r);
    r.dpi_x = 150;
    r.dpi_y = 150;
    r.pixel_format = JPDF_PIXEL_GRAY8;
    jpdf_handle bmp = 0;
    if (jpdf_page_render(page, &r, &bmp) != JPDF_OK) return false;
    uint64_t size = 0;
    jpdf_bitmap_copy_pixels(bmp, nullptr, 0, &size);
    std::vector<uint8_t> px(size);
    jpdf_bitmap_copy_pixels(bmp, px.data(), size, &size);
    jpdf_bitmap_close(bmp);
    for (uint8_t v : px) {
        if (v != 255) return false;
    }
    return !px.empty();
}

const std::vector<jpdf_text_line> kTwoLines = {line(300, 300, 1500, 400, 2),
                                               line(300, 500, 1200, 600, 2)};
const std::vector<jpdf_text_word> kTwoLineWords = {
    word("Hello", 300, 300, 800, 400), word("world", 900, 300, 1500, 400),
    word("second", 300, 500, 900, 600), word("line", 950, 500, 1200, 600)};

void test_round_trip() {
    const std::string out = path("basic.pdf");
    CHECK_STATUS(write(kTwoLines, kTwoLineWords, out), JPDF_OK, "write two lines");
    const std::string bytes = read_file(out);
    CHECK(bytes.rfind("%PDF-1.7\n", 0) == 0, "PDF header");
    CHECK(bytes.find("/Font << /F1 5 0 R >>") != std::string::npos,
          "one font under /F1, the name OCRmyPDF's graft copies");
    CHECK(bytes.find("/Contents 4 0 R") != std::string::npos, "a single content stream");

    Opened o;
    CHECK(open(out, &o), "PDFium opens the text layer");
    jpdf_page_info info{};
    info.struct_size = sizeof(info);
    CHECK_STATUS(jpdf_page_get_info(o.page, &info), JPDF_OK, "page info");
    CHECK(std::fabs(info.width_pt - 612) < 1e-3 && std::fabs(info.height_pt - 792) < 1e-3,
          "page is the image size at its DPI");
    const std::string text = page_text(o.page);
    CHECK(squash(text) == "Hello world second line", "extracted text matches the words");
    if (squash(text) != "Hello world second line") std::printf("  got [%s]\n", text.c_str());
    CHECK(text.find("Hello world") != std::string::npos, "words on a line keep their space");
    CHECK(renders_blank(o.page), "nothing visible is drawn");
}

void test_unicode() {
    const std::vector<jpdf_text_word> words = {
        word("na\xC3\xAFve", 100, 100, 500, 200),              // naive with a diaeresis
        word("e\xCC\x81t\xC3\xA9", 600, 100, 900, 200),        // combining acute, then precomposed
        word("\xF0\x9F\x98\x80", 1000, 100, 1100, 200),        // U+1F600, outside the BMP
        word("\xE6\x97\xA5\xE6\x9C\xAC", 100, 300, 400, 400),  // two CJK words: no space between
        word("\xE8\xAA\x9E", 420, 300, 560, 400),
        word("\xF0\xA0\x80\x80", 100, 500, 200, 600),  // U+20000, CJK Extension B
    };
    const std::vector<jpdf_text_line> lines = {
        line(100, 100, 1100, 200, 3), line(100, 300, 560, 400, 2), line(100, 500, 200, 600, 1)};
    const std::string out = path("unicode.pdf");
    CHECK_STATUS(write(lines, words, out), JPDF_OK, "write unicode");
    Opened o;
    CHECK(open(out, &o), "open unicode layer");
    const std::string want =
        "na\xC3\xAFve e\xCC\x81t\xC3\xA9 \xF0\x9F\x98\x80 \xE6\x97\xA5\xE6\x9C\xAC\xE8\xAA\x9E "
        "\xF0\xA0\x80\x80";
    const std::string got = squash(page_text(o.page));
    CHECK(got == want, "code points survive extraction in logical order");
    if (got != want) std::printf("  got [%s]\n", got.c_str());
}

void test_empty_layer() {
    const std::string out = path("empty.pdf");
    CHECK_STATUS(jpdf_text_layer_write(&kLetter, nullptr, 0, nullptr, 0, out.c_str(), nullptr),
                 JPDF_OK, "a page with no text is still a valid layer");
    CHECK(read_file(out).find("/Font") == std::string::npos, "no font without text");
    Opened o;
    CHECK(open(out, &o), "open empty layer");
    CHECK(page_text(o.page).empty(), "empty layer has no text");
    CHECK(renders_blank(o.page), "empty layer renders blank");
}

void test_deterministic_and_repeatable() {
    const std::string a = path("a.pdf");
    const std::string b = path("b.pdf");
    CHECK_STATUS(write(kTwoLines, kTwoLineWords, a), JPDF_OK, "write a");
    for (int i = 0; i < 200; ++i) {
        if (write(kTwoLines, kTwoLineWords, b) != JPDF_OK) {
            CHECK(false, "repeated write");
            break;
        }
    }
    CHECK(read_file(a) == read_file(b), "equal input gives byte-identical output");
}

void test_rejections() {
    const std::string out = path("rejected.pdf");
    std::vector<jpdf_text_line> lines = {line(0, 0, 100, 50, 1)};
    auto with = [&](const char* text) {
        return std::vector<jpdf_text_word>{word(text, 0, 0, 100, 50)};
    };

    CHECK_STATUS(
        jpdf_text_layer_write(nullptr, lines.data(), 1, with("a").data(), 1, out.c_str(), nullptr),
        JPDF_E_INVALID_ARGUMENT, "options are required");
    CHECK_STATUS(write(lines, with("a"), ""), JPDF_E_INVALID_ARGUMENT, "path is required");
    jpdf_text_layer_options o = kLetter;
    o.font = 7;
    CHECK_STATUS(write(lines, with("a"), out, o), JPDF_E_UNSUPPORTED, "unknown font");
    o = kLetter;
    o.flags = 1;
    CHECK_STATUS(write(lines, with("a"), out, o), JPDF_E_UNSUPPORTED, "unknown option flag");
    o = kLetter;
    o.image_width = 0;
    CHECK_STATUS(write(lines, with("a"), out, o), JPDF_E_INVALID_ARGUMENT, "zero width");
    o = kLetter;
    o.dpi_y = std::nan("");
    CHECK_STATUS(write(lines, with("a"), out, o), JPDF_E_INVALID_ARGUMENT, "NaN dpi");

    CHECK_STATUS(write(lines, with("\xC0\xAF"), out), JPDF_E_INVALID_ARGUMENT, "overlong UTF-8");
    CHECK_STATUS(write(lines, with("\xED\xA0\x80"), out), JPDF_E_INVALID_ARGUMENT, "surrogate");
    CHECK_STATUS(write(lines, with("\xE6\x97"), out), JPDF_E_INVALID_ARGUMENT, "truncated UTF-8");
    CHECK_STATUS(write(lines, with("a\tb"), out), JPDF_E_INVALID_ARGUMENT, "control character");
    CHECK_STATUS(write(lines, with("\xEF\xBF\xBF"), out), JPDF_E_INVALID_ARGUMENT, "noncharacter");
    CHECK_STATUS(write(lines, with(""), out), JPDF_E_INVALID_ARGUMENT, "empty word");
    CHECK_STATUS(write(lines, with("\xD7\xA9\xD7\x9C\xD7\x95\xD7\x9D"), out), JPDF_E_UNSUPPORTED,
                 "Hebrew is refused until right-to-left layout exists");
    std::vector<jpdf_text_word> null_text = with("a");
    null_text[0].text_utf8 = nullptr;
    CHECK_STATUS(write(lines, null_text, out), JPDF_E_INVALID_ARGUMENT, "NULL text");

    std::vector<jpdf_text_word> inverted = with("a");
    inverted[0].x1 = -1;
    CHECK_STATUS(write(lines, inverted, out), JPDF_E_INVALID_ARGUMENT, "inverted word box");
    std::vector<jpdf_text_line> flat = {line(0, 50, 100, 50, 1)};
    CHECK_STATUS(write(flat, with("a"), out), JPDF_E_INVALID_ARGUMENT, "zero-height line");
    std::vector<jpdf_text_line> greedy = {line(0, 0, 100, 50, 2)};
    CHECK_STATUS(write(greedy, with("a"), out), JPDF_E_INVALID_ARGUMENT, "line takes too many");
    std::vector<jpdf_text_line> idle = {line(0, 0, 100, 50, 0)};
    CHECK_STATUS(write(idle, with("a"), out), JPDF_E_INVALID_ARGUMENT, "line with no words");
    CHECK_STATUS(write({}, with("a"), out), JPDF_E_INVALID_ARGUMENT, "orphan words");

    // Mixed strides cannot be walked, and a newer caller's unknown fields must be zero.
    std::vector<jpdf_text_word> two = {word("a", 0, 0, 10, 50), word("b", 20, 0, 30, 50)};
    std::vector<jpdf_text_line> both = {line(0, 0, 100, 50, 2)};
    two[1].struct_size = sizeof(jpdf_text_word) - 8;
    CHECK_STATUS(write(both, two, out), JPDF_E_INVALID_ARGUMENT, "elements share a struct_size");
    std::vector<jpdf_text_line> bad_line = lines;
    bad_line[0].reserved0 = 1;
    CHECK_STATUS(write(bad_line, with("a"), out), JPDF_E_UNSUPPORTED, "line reserved field");

    jpdf_write_options w{};
    w.struct_size = sizeof(w);
    w.image_format = JPDF_IMAGE_PNG;
    CHECK_STATUS(write(lines, with("a"), out, kLetter, &w), JPDF_E_UNSUPPORTED,
                 "image fields are refused for a PDF");
    CHECK(!fs::exists(out), "no rejected call created the output");
}

// A newer caller with a larger struct: the extra bytes are zero, so the call is accepted.
void test_larger_stride() {
    struct WideWord {
        jpdf_text_word base;
        double future;
    };
    std::vector<WideWord> words(2);
    words[0].base = word("wide", 0, 0, 200, 50);
    words[1].base = word("words", 250, 0, 500, 50);
    for (WideWord& w : words) {
        w.base.struct_size = sizeof(WideWord);
        w.future = 0;
    }
    const std::vector<jpdf_text_line> lines = {line(0, 0, 500, 50, 2)};
    const std::string out = path("wide.pdf");
    CHECK_STATUS(
        jpdf_text_layer_write(&kLetter, lines.data(), 1, &words[0].base, 2, out.c_str(), nullptr),
        JPDF_OK, "zero-tailed larger elements are read at their own stride");
    Opened o;
    CHECK(open(out, &o) && squash(page_text(o.page)) == "wide words", "both wide words found");
    words[1].future = 1;
    CHECK_STATUS(
        jpdf_text_layer_write(&kLetter, lines.data(), 1, &words[0].base, 2, out.c_str(), nullptr),
        JPDF_E_UNSUPPORTED, "a set unknown field is refused");
}

void test_output_transaction() {
    const std::string out = path("kept.pdf");
    std::ofstream(out, std::ios::binary) << "previous";
    jpdf_write_options w{};
    w.struct_size = sizeof(w);
    w.max_bytes = 64;
    CHECK_STATUS(write(kTwoLines, kTwoLineWords, out, kLetter, &w), JPDF_E_LIMIT,
                 "max_bytes bounds the PDF");
    CHECK(read_file(out) == "previous", "a failed write leaves the destination untouched");
    w.max_bytes = 0;
    w.flags = JPDF_WRITE_DURABLE;
    CHECK_STATUS(write(kTwoLines, kTwoLineWords, out, kLetter, &w), JPDF_OK, "durable write");
    CHECK(read_file(out).rfind("%PDF", 0) == 0, "durable write replaced the destination");
}

void test_get_text_contract() {
    const std::string out = path("contract.pdf");
    CHECK_STATUS(write(kTwoLines, kTwoLineWords, out), JPDF_OK, "write for get_text");
    Opened o;
    CHECK(open(out, &o), "open for get_text");
    uint64_t needed = 0;
    CHECK_STATUS(jpdf_page_get_text(o.page, nullptr, 0, &needed), JPDF_OK, "sizing call");
    CHECK(needed > 1, "size includes text and NUL");
    std::string small(4, '\0');
    uint64_t again = 0;
    CHECK_STATUS(jpdf_page_get_text(o.page, small.data(), small.size(), &again),
                 JPDF_E_BUFFER_TOO_SMALL, "small buffer");
    CHECK(again == needed, "small buffer still reports the size");
    CHECK_STATUS(jpdf_page_get_text(o.page, nullptr, 0, nullptr), JPDF_E_INVALID_ARGUMENT,
                 "required is mandatory");
    const jpdf_handle page = o.page;
    jpdf_page_close(page);
    o.page = 0;
    CHECK_STATUS(jpdf_page_get_text(page, nullptr, 0, &needed), JPDF_E_STALE_HANDLE,
                 "closed page is stale");
}

// Writers share only the lazily built font; they run while another thread reads page text.
void test_concurrent_writers() {
    const std::string source = path("shared.pdf");
    CHECK_STATUS(write(kTwoLines, kTwoLineWords, source), JPDF_OK, "write shared source");
    Opened o;
    CHECK(open(source, &o), "open shared source");
    std::atomic<int> failures{0};
    std::vector<std::thread> writers;
    for (int t = 0; t < 4; ++t) {
        writers.emplace_back([t, &failures] {
            for (int i = 0; i < 25; ++i) {
                const std::string out = path("w" + std::to_string(t) + "-" + std::to_string(i));
                if (write(kTwoLines, kTwoLineWords, out) != JPDF_OK) ++failures;
            }
        });
    }
    for (int i = 0; i < 25; ++i) {
        if (squash(page_text(o.page)) != "Hello world second line") ++failures;
    }
    for (std::thread& writer : writers) writer.join();
    CHECK(failures.load() == 0, "concurrent writes and reads all succeed");
}

}  // namespace

int main() {
    std::printf("=== jpdf text layer (real PDFium) ===\n");
    // The writer never touches PDFium, so it works before the runtime starts.
    CHECK_STATUS(write(kTwoLines, kTwoLineWords, path("before-init.pdf")), JPDF_OK,
                 "write before runtime init");
    if (jpdf_runtime_init(nullptr) != JPDF_OK) {
        std::printf("FAIL runtime init\n");
        return 1;
    }
    int32_t level = 0;
    CHECK(jpdf_capability("text_layer.write", &level) == JPDF_OK && level >= JPDF_CAP_IMPLEMENTED,
          "capability reported");
    test_round_trip();
    test_unicode();
    test_empty_layer();
    test_deterministic_and_repeatable();
    test_rejections();
    test_larger_stride();
    test_output_transaction();
    test_get_text_contract();
    test_concurrent_writers();
    std::error_code ec;
    fs::remove_all(scratch(), ec);
    std::printf("=== failures: %d ===\n", g_failures);
    return g_failures == 0 ? 0 : 1;
}
