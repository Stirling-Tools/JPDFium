// api_text_layer.cpp - jpdf_text_layer_write and jpdf_page_get_text, read back by real PDFium.

#include <fpdf_save.h>
#include <fpdfview.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstddef>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <string>
#include <thread>
#include <vector>

#if defined(_WIN32)
#include <fcntl.h>
#include <io.h>
#include <process.h>
#include <share.h>
#include <sys/stat.h>
#else
#include <fcntl.h>
#include <unistd.h>
#endif

#include "jpdfium.h"
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

// --- guard shim suite (merged from api_guards.cpp) ---
// jpdf_guard_* shims: legacy and raw PDFium calls admitted by the native domain.
// Split into a pre-init probe and the main body so the "refused before init"
// assertion still runs before the host initialises the runtime; the main body
// initialises (idempotent) and runs to STOPPED. Its own counter keeps it clear
// of the host file's.
namespace guards_suite {

int g_failures = 0;

// Guard prototypes, as a binding resolves them by name.
extern "C" {
JPDF_API int32_t jpdf_guard_jpdfium_doc_open_bytes(const uint8_t* data, int64_t len,
                                                   int64_t* handle) noexcept;
JPDF_API void jpdf_guard_jpdfium_doc_close(int64_t doc) noexcept;
JPDF_API int32_t jpdf_guard_jpdfium_doc_page_count(int64_t doc, int32_t* count) noexcept;
JPDF_API int32_t jpdf_guard_jpdfium_page_open(int64_t doc, int32_t idx, int64_t* handle) noexcept;
JPDF_API void jpdf_guard_jpdfium_page_close(int64_t page) noexcept;
JPDF_API int32_t jpdf_guard_jpdfium_render_page_flags(int64_t page, int32_t dpi, int32_t flags,
                                                      uint8_t** rgba, int32_t* width,
                                                      int32_t* height) noexcept;
JPDF_API int64_t jpdf_guard_jpdfium_doc_raw_handle(int64_t doc) noexcept;
JPDF_API int jpdf_guard_FPDF_GetPageCount(FPDF_DOCUMENT document) noexcept;
JPDF_API FPDF_BOOL jpdf_guard_FPDF_SaveAsCopy(FPDF_DOCUMENT document, FPDF_FILEWRITE* pFileWrite,
                                              FPDF_DWORD flags) noexcept;
JPDF_API FPDF_DOCUMENT jpdf_guard_FPDF_CreateNewDocument(void) noexcept;
JPDF_API void jpdf_guard_FPDF_CloseDocument(FPDF_DOCUMENT document) noexcept;
JPDF_API FPDF_PAGE jpdf_guard_FPDFPage_New(FPDF_DOCUMENT document, int page_index, double width,
                                           double height) noexcept;
JPDF_API void jpdf_guard_FPDF_ClosePage(FPDF_PAGE page) noexcept;
}

namespace {

std::string fixture_pdf() {
    std::vector<std::string> objects = {
        "<< /Type /Catalog /Pages 2 0 R >>",
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R >>",
    };
    const std::string content = "0 0 0 rg 72 600 100 100 re f";
    objects.push_back("<< /Length " + std::to_string(content.size()) + " >>\nstream\n" + content +
                      "\nendstream");
    std::string pdf = "%PDF-1.7\n";
    std::vector<size_t> offsets;
    for (size_t i = 0; i < objects.size(); ++i) {
        offsets.push_back(pdf.size());
        pdf += std::to_string(i + 1) + " 0 obj\n" + objects[i] + "\nendobj\n";
    }
    const size_t xref = pdf.size();
    pdf += "xref\n0 " + std::to_string(objects.size() + 1) + "\n0000000000 65535 f \n";
    for (size_t off : offsets) {
        char line[32];
        std::snprintf(line, sizeof(line), "%010zu 00000 n \n", off);
        pdf += line;
    }
    pdf += "trailer\n<< /Size " + std::to_string(objects.size() + 1) +
           " /Root 1 0 R >>\nstartxref\n" + std::to_string(xref) + "\n%%EOF\n";
    return pdf;
}

const std::string& pdf() {
    static const std::string bytes = fixture_pdf();
    return bytes;
}

int64_t open_legacy() {
    int64_t doc = 0;
    jpdf_guard_jpdfium_doc_open_bytes(reinterpret_cast<const uint8_t*>(pdf().data()),
                                      static_cast<int64_t>(pdf().size()), &doc);
    return doc;
}

// Renders page 0 through the legacy guarded path, as the Java binding does.
std::vector<uint8_t> render_legacy() {
    std::vector<uint8_t> out;
    int64_t doc = open_legacy(), page = 0;
    if (doc && jpdf_guard_jpdfium_page_open(doc, 0, &page) == JPDFIUM_OK) {
        uint8_t* rgba = nullptr;
        int32_t w = 0, h = 0;
        if (jpdf_guard_jpdfium_render_page_flags(page, 36, 0, &rgba, &w, &h) == JPDFIUM_OK) {
            out.assign(rgba, rgba + static_cast<size_t>(w) * h * 4);
            jpdfium_free_buffer(rgba);
        }
        jpdf_guard_jpdfium_page_close(page);
    }
    if (doc) jpdf_guard_jpdfium_doc_close(doc);
    return out;
}

// Renders the same page through the safe API, as the Python binding does.
std::vector<uint8_t> render_safe(const std::string& path) {
    std::vector<uint8_t> out;
    jpdf_handle doc = 0, page = 0, bmp = 0;
    jpdf_render_options o{};
    o.struct_size = sizeof(o);
    o.dpi_x = o.dpi_y = 36;
    o.pixel_format = JPDF_PIXEL_RGBA8;
    if (jpdf_doc_open_file(path.c_str(), nullptr, &doc) != JPDF_OK) return out;
    if (jpdf_page_open(doc, 0, &page) == JPDF_OK) {
        if (jpdf_page_render(page, &o, &bmp) == JPDF_OK) {
            uint64_t size = 0;
            jpdf_bitmap_copy_pixels(bmp, nullptr, 0, &size);
            out.resize(size);
            jpdf_bitmap_copy_pixels(bmp, out.data(), size, &size);
            jpdf_bitmap_close(bmp);
        }
        jpdf_page_close(page);
    }
    jpdf_doc_close(doc);
    return out;
}

void test_refused_before_init() {
    int64_t doc = 123;
    const int32_t rc = jpdf_guard_jpdfium_doc_open_bytes(
        reinterpret_cast<const uint8_t*>(pdf().data()), static_cast<int64_t>(pdf().size()), &doc);
    CHECK(rc == 0 && doc == 123, "a refused guard returns a zero value and does not run the call");
    CHECK(jpdf_guard_take_refusal() == JPDF_E_NOT_INITIALIZED, "refusal is reported");
    CHECK(jpdf_guard_take_refusal() == JPDF_OK, "taking the refusal clears it");
}

void test_admitted_calls() {
    jpdf_runtime_info before{};
    before.struct_size = sizeof(before);
    jpdf_runtime_get_info(&before);
    const int64_t doc = open_legacy();
    CHECK(doc != 0, "legacy open through the guard");
    int32_t count = 0;
    CHECK(jpdf_guard_jpdfium_doc_page_count(doc, &count) == JPDFIUM_OK && count == 1, "count");
    auto raw = reinterpret_cast<FPDF_DOCUMENT>(jpdf_guard_jpdfium_doc_raw_handle(doc));
    CHECK(jpdf_guard_FPDF_GetPageCount(raw) == 1, "raw PDFium call through the guard");
    CHECK(jpdf_guard_take_refusal() == JPDF_OK, "admitted calls record no refusal");
    jpdf_guard_jpdfium_doc_close(doc);
    jpdf_runtime_info after{};
    after.struct_size = sizeof(after);
    jpdf_runtime_get_info(&after);
    CHECK(after.domain_acquisitions - before.domain_acquisitions == 5, "one admission per call");
}

struct ReentrantWriter : FPDF_FILEWRITE {
    FPDF_DOCUMENT doc = nullptr;
    int nested_count = -1;
    jpdf_status nested_refusal = JPDF_OK;
    std::atomic<bool>* in_callback = nullptr;
    std::atomic<bool>* release = nullptr;

    static int Write(FPDF_FILEWRITE* self, const void*, unsigned long) {
        auto* w = static_cast<ReentrantWriter*>(self);
        if (w->nested_count == -1) {
            w->nested_count = jpdf_guard_FPDF_GetPageCount(w->doc);
            w->nested_refusal = jpdf_guard_take_refusal();
        }
        if (w->in_callback) {
            w->in_callback->store(true);
            while (!w->release->load()) std::this_thread::yield();
        }
        return 1;
    }
};

void test_callback_reentry_is_rejected() {
    const int64_t doc = open_legacy();
    ReentrantWriter writer;
    writer.version = 1;
    writer.WriteBlock = ReentrantWriter::Write;
    writer.doc = reinterpret_cast<FPDF_DOCUMENT>(jpdf_guard_jpdfium_doc_raw_handle(doc));
    CHECK(jpdf_guard_FPDF_SaveAsCopy(writer.doc, &writer, 0) != 0, "save through the guard");
    CHECK(writer.nested_count == 0, "a call from inside a callback is not run");
    CHECK(writer.nested_refusal == JPDF_E_REENTRANT, "and is reported as re-entry");
    jpdf_guard_jpdfium_doc_close(doc);
}

void test_quiesce_admits_teardown_only() {
    const int64_t doc = open_legacy();
    CHECK(jpdf_runtime_quiesce() == JPDF_OK, "quiesce");
    int32_t count = -1;
    CHECK(jpdf_guard_jpdfium_doc_page_count(doc, &count) == 0 && count == -1, "ordinary refused");
    CHECK(jpdf_guard_take_refusal() == JPDF_E_QUIESCING, "refused as quiescing");
    CHECK(jpdf_runtime_resume() == JPDF_OK, "resume");
    CHECK(jpdf_guard_jpdfium_doc_page_count(doc, &count) == JPDFIUM_OK && count == 1, "resumed");
    jpdf_runtime_quiesce();
    jpdf_guard_jpdfium_doc_close(doc);
    CHECK(jpdf_guard_take_refusal() == JPDF_OK, "close is admitted while quiescing");
    jpdf_runtime_resume();
}

void test_both_bindings_share_one_domain(const std::string& path) {
    const std::vector<uint8_t> expected = render_legacy();
    CHECK(!expected.empty() && render_safe(path) == expected, "legacy and safe paths agree");
    std::atomic<int> mismatches{0};
    std::vector<std::thread> threads;
    for (int t = 0; t < 8; ++t) {
        threads.emplace_back([&, t] {
            for (int r = 0; r < 25; ++r) {
                auto px = (t % 2 == 0) ? render_legacy() : render_safe(path);
                if (px != expected) mismatches++;
            }
        });
    }
    for (auto& th : threads) th.join();
    CHECK(mismatches == 0, "legacy and safe callers interleave safely in one process");
}

// Documents and pages created through the raw PDFium guards must hold the domain open, exactly
// as ones created through the legacy bridge do, so shutdown cannot free PDFium underneath them.
// The matching raw close must release the accounting.
void test_raw_factory_lifetime_accounting() {
    jpdf_runtime_info before{};
    before.struct_size = sizeof(before);
    jpdf_runtime_get_info(&before);

    FPDF_DOCUMENT raw_doc = jpdf_guard_FPDF_CreateNewDocument();
    CHECK(raw_doc != nullptr, "raw FPDF_CreateNewDocument admitted by the guard");
    jpdf_runtime_info opened{};
    opened.struct_size = sizeof(opened);
    jpdf_runtime_get_info(&opened);
    CHECK(opened.live_documents == before.live_documents + 1, "the raw document is counted");
    CHECK(jpdf_runtime_shutdown_within(50) == JPDF_E_BUSY,
          "shutdown refuses while a raw-created document is live");
    CHECK(jpdf_runtime_resume() == JPDF_OK, "resume after the refused shutdown");

    FPDF_PAGE raw_page = jpdf_guard_FPDFPage_New(raw_doc, 0, 612, 792);
    if (raw_page != nullptr) {
        jpdf_runtime_info paged{};
        paged.struct_size = sizeof(paged);
        jpdf_runtime_get_info(&paged);
        CHECK(paged.live_pages == before.live_pages + 1, "the raw page is counted");
        jpdf_guard_FPDF_ClosePage(raw_page);
    }

    jpdf_guard_FPDF_CloseDocument(raw_doc);
    jpdf_runtime_info after{};
    after.struct_size = sizeof(after);
    jpdf_runtime_get_info(&after);
    CHECK(after.live_documents == before.live_documents, "document count returns to baseline");
    CHECK(after.live_pages == before.live_pages, "page count returns to baseline");
}

void test_bounded_shutdown_then_stop() {
    const int64_t doc = open_legacy();
    std::atomic<bool> in_callback{false}, release{false};
    ReentrantWriter writer;
    writer.version = 1;
    writer.WriteBlock = ReentrantWriter::Write;
    writer.doc = reinterpret_cast<FPDF_DOCUMENT>(jpdf_guard_jpdfium_doc_raw_handle(doc));
    writer.nested_count = 0;
    writer.in_callback = &in_callback;
    writer.release = &release;
    std::thread holder([&] { jpdf_guard_FPDF_SaveAsCopy(writer.doc, &writer, 0); });
    // A refused shim or a null writer.doc never enters the callback, so the wait needs a
    // deadline instead of parking until the ctest timeout.
    const auto callbackDeadline = std::chrono::steady_clock::now() + std::chrono::seconds(10);
    while (!in_callback.load()) {
        if (std::chrono::steady_clock::now() > callbackDeadline) {
            release = true;
            holder.join();
            CHECK(in_callback.load(), "save callback entered before the deadline");
            return;
        }
        std::this_thread::yield();
    }
    const auto t0 = std::chrono::steady_clock::now();
    CHECK(jpdf_runtime_shutdown_within(50) == JPDF_E_TIMEOUT, "busy domain times out");
    CHECK(std::chrono::steady_clock::now() - t0 < std::chrono::seconds(5), "within the bound");
    release = true;
    holder.join();
    jpdf_guard_jpdfium_doc_close(doc);
    CHECK(jpdf_runtime_shutdown_within(1000) == JPDF_OK, "idle domain shuts down");
    int32_t count = -1;
    jpdf_guard_jpdfium_doc_page_count(1, &count);
    CHECK(jpdf_guard_take_refusal() == JPDF_E_STOPPED, "guards refuse after stop");
    CHECK(jpdf_runtime_resume() == JPDF_E_STOPPED, "resume never leaves STOPPED");
}

}  // namespace

// Exercised before the host starts the runtime, so the refusal path sees an
// uninitialised process. Failures accumulate into this suite's counter.
void run_guards_suite_preinit() {
    test_refused_before_init();
}

int run_guards_suite() {
    std::printf("=== jpdf guard shims (real PDFium) ===\n");
    const std::string path = "jpdf-guards-fixture.pdf";
    // Owner-only from creation: fopen(path, "wb") creates 0666 before umask
    // (CodeQL cpp/world-writable-file-creation) and warns as C4996 under /WX.
    FILE* f = nullptr;
#if defined(_WIN32)
    int fd = -1;
    if (::_sopen_s(&fd, path.c_str(), _O_WRONLY | _O_CREAT | _O_TRUNC | _O_BINARY, _SH_DENYRW,
                   _S_IREAD | _S_IWRITE) == 0) {
        f = ::_fdopen(fd, "wb");
        if (!f) ::_close(fd);
    }
#else
    const int fd = ::open(path.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (fd >= 0) {
        f = ::fdopen(fd, "wb");
        if (!f) ::close(fd);
    }
#endif
    if (f) {
        std::fwrite(pdf().data(), 1, pdf().size(), f);
        std::fclose(f);
    }
    if (jpdf_runtime_init(nullptr) != JPDF_OK) {
        std::printf("FAIL runtime init\n");
        return g_failures + 1;
    }
    test_admitted_calls();
    test_callback_reentry_is_rejected();
    test_quiesce_admits_teardown_only();
    test_both_bindings_share_one_domain(path);
    test_raw_factory_lifetime_accounting();
    test_bounded_shutdown_then_stop();
    std::remove(path.c_str());
    std::printf("=== guard failures: %d ===\n", g_failures);
    return g_failures;
}

}  // namespace guards_suite

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

// A box's corners rotated about (cx, cy), clockwise from its top-left as read.
std::vector<double> rotated(double x0, double y0, double x1, double y1, double degrees, double cx,
                            double cy, double skew = 0) {
    const double a = degrees * 3.14159265358979323846 / 180.0;
    const double c = std::cos(a);
    const double s = std::sin(a);
    const double pts[8] = {x0 + skew, y0, x1 + skew, y0, x1, y1, x0, y1};
    std::vector<double> out(8);
    for (size_t k = 0; k < 4; ++k) {
        const double dx = pts[2 * k] - cx;
        const double dy = pts[2 * k + 1] - cy;
        out[2 * k] = cx + dx * c - dy * s;
        out[2 * k + 1] = cy + dx * s + dy * c;
    }
    return out;
}

jpdf_text_word quad_word(const char* text, const std::vector<double>& q) {
    jpdf_text_word w = word(text, 0, 0, 0, 0);
    w.flags = JPDF_TEXT_WORD_QUAD;
    std::copy(q.begin(), q.end(), w.quad);
    return w;
}

jpdf_text_line quad_line(const std::vector<double>& q, int32_t word_count, uint32_t flags = 0) {
    jpdf_text_line l = line(0, 0, 0, 0, word_count);
    l.flags = JPDF_TEXT_LINE_QUAD | flags;
    std::copy(q.begin(), q.end(), l.quad);
    return l;
}

// A 2550 x 3300 scan at 300 dpi: a US Letter page.
const jpdf_text_layer_options kLetter = [] {
    jpdf_text_layer_options o{};
    o.struct_size = sizeof(o);
    o.image_width = 2550;
    o.image_height = 3300;
    o.dpi_x = 300;
    o.dpi_y = 300;
    return o;
}();

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

// Right-to-left words keep logical order in the stream, so PDFium returns logical text.
void test_right_to_left() {
    const std::vector<jpdf_text_word> words = {
        word("\xD7\xA9\xD7\x9C\xD7\x95\xD7\x9D", 1300, 300, 1600, 400),  // shalom
        word("\xD7\xA2\xD7\x95\xD7\x9C\xD7\x9D", 900, 300, 1250, 400),   // olam
        // marhaban ya, with harakat: combining marks inside right-to-left runs
        word("\xD9\x85\xD9\x8E\xD8\xB1\xD9\x92\xD8\xAD\xD9\x8E\xD8\xA8\xD9\x8B\xD8\xA7", 1100, 500,
             1600, 600),
        word("\xD9\x8A\xD9\x8E\xD8\xA7", 900, 500, 1050, 600),
        word("see", 300, 700, 450, 800),  // a Hebrew word inside a left-to-right line
        word("\xD7\xA9\xD7\x9C\xD7\x95\xD7\x9D", 500, 700, 700, 800),
        word("here", 750, 700, 950, 800),
    };
    const std::vector<jpdf_text_line> lines = {
        line(900, 300, 1600, 400, 2), line(900, 500, 1600, 600, 2), line(300, 700, 950, 800, 3)};
    const std::string out = path("rtl.pdf");
    CHECK_STATUS(write(lines, words, out), JPDF_OK, "write right-to-left lines");
    Opened o;
    CHECK(open(out, &o), "open right-to-left layer");
    const std::string want =
        "\xD7\xA9\xD7\x9C\xD7\x95\xD7\x9D \xD7\xA2\xD7\x95\xD7\x9C\xD7\x9D "
        "\xD9\x85\xD9\x8E\xD8\xB1\xD9\x92\xD8\xAD\xD9\x8E\xD8\xA8\xD9\x8B\xD8\xA7 "
        "\xD9\x8A\xD9\x8E\xD8\xA7 see \xD7\xA9\xD7\x9C\xD7\x95\xD7\x9D here";
    const std::string got = squash(page_text(o.page));
    CHECK(got == want, "right-to-left and mixed lines come back in logical order");
    if (got != want) std::printf("  got [%s]\n", got.c_str());
    CHECK(renders_blank(o.page), "mirrored runs draw nothing");

    // Forcing the direction changes only placement: the same words, read back the same.
    std::vector<jpdf_text_line> forced = lines;
    forced[0].flags = JPDF_TEXT_LINE_RTL;
    forced[2].flags = JPDF_TEXT_LINE_LTR;
    CHECK_STATUS(write(forced, words, out), JPDF_OK, "explicit directions");
    Opened again;
    CHECK(open(out, &again) && squash(page_text(again.page)) == want, "explicit directions agree");
}

// Rotated and skewed lines: text runs along each quad's top edge.
void test_quads() {
    const std::vector<jpdf_text_word> words = {
        quad_word("rotated", rotated(1000, 1000, 1350, 1100, 90, 1200, 1050)),
        quad_word("ninety", rotated(1400, 1000, 1700, 1100, 90, 1200, 1050)),
        quad_word("slanted", rotated(300, 2000, 650, 2100, 15, 500, 2050)),
        quad_word("line", rotated(700, 2000, 900, 2100, 15, 500, 2050)),
        quad_word("italic", rotated(300, 2500, 600, 2600, 0, 0, 0, 25)),
        word("box", 650, 2500, 800, 2600),  // a plain box word on a quad line
    };
    const std::vector<jpdf_text_line> lines = {
        quad_line(rotated(1000, 1000, 1700, 1100, 90, 1200, 1050), 2),
        quad_line(rotated(300, 2000, 900, 2100, 15, 500, 2050), 2),
        quad_line(rotated(300, 2500, 800, 2600, 0, 0, 0, 25), 2),
    };
    const std::string out = path("quads.pdf");
    CHECK_STATUS(write(lines, words, out), JPDF_OK, "write quad lines");
    Opened o;
    CHECK(open(out, &o), "open quad layer");
    const std::string got = squash(page_text(o.page));
    CHECK(got == "rotated ninety slanted line italic box", "quad lines read back in order");
    if (got != "rotated ninety slanted line italic box") std::printf("  got [%s]\n", got.c_str());
    CHECK(renders_blank(o.page), "quad lines draw nothing");

    const std::string again = path("quads-again.pdf");
    CHECK_STATUS(write(lines, words, again), JPDF_OK, "rewrite quad lines");
    CHECK(read_file(out) == read_file(again), "quad output is deterministic");
}

// Callers built against 1.3 pass the shorter structs; they still work, without quads.
void test_previous_layout() {
    struct WordV13 {
        uint32_t struct_size;
        uint32_t flags;
        const char* text_utf8;
        double x0, y0, x1, y1;
    };
    struct LineV13 {
        uint32_t struct_size;
        uint32_t flags;
        double x0, y0, x1, y1;
        int32_t word_count;
        int32_t reserved0;
    };
    static_assert(sizeof(WordV13) == offsetof(jpdf_text_word, quad), "1.3 word layout");
    static_assert(sizeof(LineV13) == offsetof(jpdf_text_line, quad), "1.3 line layout");
    WordV13 words[2] = {{sizeof(WordV13), 0, "old", 0, 0, 200, 50},
                        {sizeof(WordV13), 0, "layout", 250, 0, 500, 50}};
    LineV13 lines[1] = {{sizeof(LineV13), 0, 0, 0, 500, 50, 2, 0}};
    const std::string out = path("v13.pdf");
    const auto* w = reinterpret_cast<const jpdf_text_word*>(words);
    const auto* l = reinterpret_cast<const jpdf_text_line*>(lines);
    CHECK_STATUS(jpdf_text_layer_write(&kLetter, l, 1, w, 2, out.c_str(), nullptr), JPDF_OK,
                 "1.3 structs are accepted");
    Opened o;
    CHECK(open(out, &o) && squash(page_text(o.page)) == "old layout", "1.3 words read back");
    words[0].flags = JPDF_TEXT_WORD_QUAD;
    CHECK_STATUS(jpdf_text_layer_write(&kLetter, l, 1, w, 2, out.c_str(), nullptr),
                 JPDF_E_INVALID_ARGUMENT, "a quad needs the 1.4 word struct");
    words[0].flags = 0;
    lines[0].flags = JPDF_TEXT_LINE_QUAD;
    CHECK_STATUS(jpdf_text_layer_write(&kLetter, l, 1, w, 2, out.c_str(), nullptr),
                 JPDF_E_INVALID_ARGUMENT, "a quad needs the 1.4 line struct");
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
    CHECK_STATUS(write(lines,
                       with("a\xE2\x80\xAE"
                            "b"),
                       out),
                 JPDF_E_UNSUPPORTED, "a right-to-left override is refused");
    CHECK_STATUS(write(lines,
                       with("\xE2\x81\xA7"
                            "a\xE2\x81\xA9"),
                       out),
                 JPDF_E_UNSUPPORTED, "an isolate is refused");
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
    bad_line = lines;
    bad_line[0].flags = 0x8;
    CHECK_STATUS(write(bad_line, with("a"), out), JPDF_E_UNSUPPORTED, "unknown line flag");
    bad_line[0].flags = JPDF_TEXT_LINE_LTR | JPDF_TEXT_LINE_RTL;
    CHECK_STATUS(write(bad_line, with("a"), out), JPDF_E_INVALID_ARGUMENT, "both directions");
    std::vector<jpdf_text_word> bad_word = with("a");
    bad_word[0].flags = 0x2;
    CHECK_STATUS(write(lines, bad_word, out), JPDF_E_UNSUPPORTED, "unknown word flag");
    bad_word = {quad_word("a", {0, 0, 10, 0, 10, 50, 0, std::nan("")})};
    CHECK_STATUS(write(lines, bad_word, out), JPDF_E_INVALID_ARGUMENT, "NaN word quad");

    const std::vector<double> square = {0, 0, 100, 0, 100, 50, 0, 50};
    CHECK_STATUS(write({quad_line(square, 1)}, with("a"), path("square.pdf")), JPDF_OK,
                 "a plain quad line");
    CHECK_STATUS(write({quad_line({0, 0, 0, 0, 0, 50, 0, 50}, 1)}, with("a"), out),
                 JPDF_E_INVALID_ARGUMENT, "a quad line with no length");
    CHECK_STATUS(write({quad_line({0, 0, 100, 0, 100, 0, 0, 0}, 1)}, with("a"), out),
                 JPDF_E_INVALID_ARGUMENT, "a quad line with no height");
    CHECK_STATUS(write({quad_line({0, 50, 100, 50, 100, 0, 0, 0}, 1)}, with("a"), out),
                 JPDF_E_INVALID_ARGUMENT, "corners running anticlockwise are mirrored");
    CHECK_STATUS(write({quad_line({0, 0, 100, 0, 1000, 5, 900, 5}, 1)}, with("a"), out),
                 JPDF_E_INVALID_ARGUMENT, "sides nearly parallel to the baseline");
    CHECK_STATUS(write({quad_line({0, 0, 100, 0, 100, 1e300, 0, 50}, 1)}, with("a"), out),
                 JPDF_E_INVALID_ARGUMENT, "a quad coordinate out of range");

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
    // Named `tiny`, not `small`: MSVC's <rpcndr.h> defines `small` as a macro.
    std::string tiny(4, '\0');
    uint64_t again = 0;
    CHECK_STATUS(jpdf_page_get_text(o.page, tiny.data(), tiny.size(), &again),
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
    writers.reserve(4);
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
    guards_suite::run_guards_suite_preinit();
    if (jpdf_runtime_init(nullptr) != JPDF_OK) {
        std::printf("FAIL runtime init\n");
        return 1;
    }
    int32_t level = 0;
    CHECK(jpdf_capability("text_layer.write", &level) == JPDF_OK && level >= JPDF_CAP_IMPLEMENTED,
          "capability reported");
    test_round_trip();
    CHECK(jpdf_capability("text_layer.bidi", &level) == JPDF_OK && level >= JPDF_CAP_IMPLEMENTED,
          "bidi capability reported");
    CHECK(jpdf_capability("text_layer.quad", &level) == JPDF_OK && level >= JPDF_CAP_IMPLEMENTED,
          "quad capability reported");
    test_unicode();
    test_right_to_left();
    test_quads();
    test_previous_layout();
    test_empty_layer();
    test_deterministic_and_repeatable();
    test_rejections();
    test_larger_stride();
    test_output_transaction();
    test_get_text_contract();
    test_concurrent_writers();
    g_failures += guards_suite::run_guards_suite();
    std::error_code ec;
    fs::remove_all(scratch(), ec);
    std::printf("=== failures: %d ===\n", g_failures);
    return g_failures == 0 ? 0 : 1;
}
