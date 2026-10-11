// api_safety.cpp - real-PDFium tests for the safe jpdf_* API: lifecycle, ownership,
// stale handles, concurrency, close races, transactional output and shutdown.

#include <atomic>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <memory>
#include <string>
#include <thread>
#include <vector>

#if defined(_WIN32)
#include <process.h>
#else
#include <unistd.h>
#endif

#include "jpdfium.h"
#include "jpdfium_api.h"
#include "objects/object_table.h"

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

// --- object table suite (merged from api_object_table.cpp) ---
// The generation-checked handle table, tested without PDFium. Kept in its own
// namespace so its counter and helpers cannot collide with the host file's.
namespace object_table_suite {

int g_failures = 0;

using jpdf::Object;
using jpdf::ObjectType;

namespace {

struct Doc final : Object {
    Doc() : Object(ObjectType::Document) {}
};
struct Page final : Object {
    Page() : Object(ObjectType::Page) {}
};

void test_validation_order() {
    jpdf::BasicObjectTable<24> table;
    jpdf_handle doc = 0;
    CHECK(table.insert(std::make_shared<Doc>(), &doc) == JPDF_OK, "insert");
    CHECK(doc != 0, "token is non-zero");
    CHECK(table.resolve(doc, ObjectType::Document, nullptr) == JPDF_OK, "resolve live");
    CHECK(table.resolve(0, ObjectType::Document, nullptr) == JPDF_E_INVALID_HANDLE, "zero");
    CHECK(table.resolve(doc, ObjectType::Page, nullptr) == JPDF_E_WRONG_HANDLE_TYPE,
          "type mismatch");
    CHECK(table.resolve(0xFF00000100000000ull, ObjectType::Document, nullptr) ==
              JPDF_E_INVALID_HANDLE,
          "unknown type byte");
    CHECK(table.resolve(doc + 7, ObjectType::Document, nullptr) == JPDF_E_INVALID_HANDLE,
          "never-allocated slot");
    CHECK(table.resolve(doc + (1ull << 32), ObjectType::Document, nullptr) == JPDF_E_INVALID_HANDLE,
          "future generation is not stale, it was never issued");
    CHECK(table.retire(doc, ObjectType::Document, nullptr) == JPDF_OK, "retire");
    CHECK(table.resolve(doc, ObjectType::Document, nullptr) == JPDF_E_STALE_HANDLE, "stale");
    CHECK(table.retire(doc, ObjectType::Document, nullptr) == JPDF_E_STALE_HANDLE,
          "double retire is stale, not a double free");
}

void test_slot_reuse_keeps_old_tokens_stale() {
    jpdf::BasicObjectTable<24> table;
    jpdf_handle first = 0;
    table.insert(std::make_shared<Doc>(), &first);
    table.retire(first, ObjectType::Document, nullptr);
    jpdf_handle second = 0;
    table.insert(std::make_shared<Doc>(), &second);
    CHECK(decltype(table)::slot_of(first) == decltype(table)::slot_of(second), "slot reused");
    CHECK(first != second, "reuse changes the token");
    CHECK(table.resolve(first, ObjectType::Document, nullptr) == JPDF_E_STALE_HANDLE,
          "old token stays stale after reuse");
    CHECK(table.resolve(second, ObjectType::Document, nullptr) == JPDF_OK, "new token valid");
    jpdf_handle page = 0;
    table.retire(second, ObjectType::Document, nullptr);
    table.insert(std::make_shared<Page>(), &page);
    CHECK(table.resolve(second, ObjectType::Document, nullptr) == JPDF_E_STALE_HANDLE,
          "slot reused by another type keeps the old token stale");
}

void test_generation_exhaustion_retires_slot() {
    // Two generation bits: the slot is issued at generations 1, 2 and 3, then never again.
    jpdf::BasicObjectTable<2> table;
    std::vector<jpdf_handle> issued;
    for (int i = 0; i < 3; ++i) {
        jpdf_handle h = 0;
        table.insert(std::make_shared<Doc>(), &h);
        issued.push_back(h);
        table.retire(h, ObjectType::Document, nullptr);
    }
    CHECK(table.retired_slots() == 1, "exhausted slot is retired");
    jpdf_handle fresh = 0;
    table.insert(std::make_shared<Doc>(), &fresh);
    CHECK(decltype(table)::slot_of(fresh) != decltype(table)::slot_of(issued[0]),
          "retired slot is never reissued");
    for (jpdf_handle h : issued) {
        CHECK(table.resolve(h, ObjectType::Document, nullptr) == JPDF_E_STALE_HANDLE,
              "every exhausted-slot token stays stale");
    }
}

void test_retired_object_outlives_table_entry() {
    jpdf::BasicObjectTable<24> table;
    jpdf_handle h = 0;
    table.insert(std::make_shared<Doc>(), &h);
    std::shared_ptr<Object> lease;
    CHECK(table.resolve(h, ObjectType::Document, &lease) == JPDF_OK, "lease");
    table.retire(h, ObjectType::Document, nullptr);
    CHECK(lease.use_count() == 1, "the lease is the last owner after retire");
    CHECK(table.live(ObjectType::Document) == 0, "live count drops at retire");
}

void test_concurrent_churn() {
    jpdf::BasicObjectTable<24> table;
    constexpr int kThreads = 8;
    constexpr int kRounds = 20000;
    std::vector<std::thread> threads;
    threads.reserve(kThreads);
    std::vector<int> errors(kThreads, 0);
    for (int t = 0; t < kThreads; ++t) {
        threads.emplace_back([&, t] {
            for (int i = 0; i < kRounds; ++i) {
                jpdf_handle h = 0;
                if (table.insert(std::make_shared<Doc>(), &h) != JPDF_OK) errors[t]++;
                if (table.resolve(h, ObjectType::Document, nullptr) != JPDF_OK) errors[t]++;
                if (table.retire(h, ObjectType::Document, nullptr) != JPDF_OK) errors[t]++;
                if (table.resolve(h, ObjectType::Document, nullptr) != JPDF_E_STALE_HANDLE) {
                    errors[t]++;
                }
            }
        });
    }
    for (auto& th : threads) th.join();
    int total = 0;
    for (int e : errors) total += e;
    CHECK(total == 0, "concurrent insert/resolve/retire never confuses tokens");
    CHECK(table.live(ObjectType::Document) == 0, "all churned objects retired");
}

}  // namespace

int run_object_table_suite() {
    std::printf("=== jpdf object table ===\n");
    test_validation_order();
    test_slot_reuse_keeps_old_tokens_stale();
    test_generation_exhaustion_retires_slot();
    test_retired_object_outlives_table_entry();
    test_concurrent_churn();
    std::printf("=== object table failures: %d ===\n", g_failures);
    return g_failures;
}

}  // namespace object_table_suite

namespace {

constexpr int kPages = 4;

fs::path scratch_dir() {
#if defined(_WIN32)
    const long pid = static_cast<long>(::_getpid());
#else
    const long pid = static_cast<long>(::getpid());
#endif
    static const fs::path dir = fs::temp_directory_path() / ("jpdf-api-" + std::to_string(pid));
    fs::create_directories(dir);
    return dir;
}

// Page i is 612x792pt with a black 100x100pt square whose x origin moves with the index.
std::string fixture_pdf() {
    std::vector<std::string> objects;
    std::string kids;
    for (int i = 0; i < kPages; ++i) kids += std::to_string(3 + i * 2) + " 0 R ";
    objects.push_back("<< /Type /Catalog /Pages 2 0 R >>");
    objects.push_back("<< /Type /Pages /Kids [" + kids + "] /Count " + std::to_string(kPages) +
                      " >>");
    for (int i = 0; i < kPages; ++i) {
        const std::string content =
            "0 0 0 rg " + std::to_string(72 + i * 100) + " 600 100 100 re f";
        objects.push_back("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents " +
                          std::to_string(4 + i * 2) + " 0 R >>");
        objects.push_back("<< /Length " + std::to_string(content.size()) + " >>\nstream\n" +
                          content + "\nendstream");
    }
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

std::string write_fixture() {
    const fs::path path = scratch_dir() / "fixture.pdf";
    std::ofstream(path, std::ios::binary) << fixture_pdf();
    return path.string();
}

jpdf_render_options render_opts(double dpi, int32_t format) {
    jpdf_render_options o{};
    o.struct_size = sizeof(o);
    o.dpi_x = dpi;
    o.dpi_y = dpi;
    o.pixel_format = format;
    return o;
}

std::vector<uint8_t> pixels_of(jpdf_handle bitmap) {
    uint64_t size = 0;
    jpdf_bitmap_copy_pixels(bitmap, nullptr, 0, &size);
    std::vector<uint8_t> out(size);
    jpdf_bitmap_copy_pixels(bitmap, out.data(), out.size(), &size);
    return out;
}

std::vector<uint8_t> render_page(const std::string& path, int index) {
    jpdf_handle doc = 0, page = 0, bmp = 0;
    std::vector<uint8_t> out;
    jpdf_render_options o = render_opts(36, JPDF_PIXEL_GRAY8);
    if (jpdf_doc_open_file(path.c_str(), nullptr, &doc) != JPDF_OK) return out;
    if (jpdf_page_open(doc, index, &page) == JPDF_OK) {
        if (jpdf_page_render(page, &o, &bmp) == JPDF_OK) {
            out = pixels_of(bmp);
            jpdf_bitmap_close(bmp);
        }
        jpdf_page_close(page);
    }
    jpdf_doc_close(doc);
    return out;
}

jpdf_runtime_info runtime_info() {
    jpdf_runtime_info info{};
    info.struct_size = sizeof(info);
    jpdf_runtime_get_info(&info);
    return info;
}

std::string last_error() {
    uint64_t needed = 0;
    jpdf_last_error_message(nullptr, 0, &needed);
    std::string s(needed, '\0');
    jpdf_last_error_message(s.data(), s.size(), &needed);
    if (!s.empty()) s.pop_back();
    return s;
}

std::string read_file(const fs::path& p) {
    std::ifstream in(p, std::ios::binary);
    return std::string(std::istreambuf_iterator<char>(in), {});
}

void test_before_init() {
    jpdf_handle doc = 0;
    CHECK_STATUS(jpdf_doc_open_file("x.pdf", nullptr, &doc), JPDF_E_NOT_INITIALIZED,
                 "work before init is refused");
    CHECK(doc == 0, "no handle published on failure");
    CHECK(!last_error().empty(), "failure records detail");
    CHECK(jpdf_api_version() == ((JPDF_API_VERSION_MAJOR << 16) | JPDF_API_VERSION_MINOR),
          "api version");
    CHECK(jpdf_abi_struct_size(JPDF_STRUCT_RENDER_OPTIONS) == sizeof(jpdf_render_options),
          "layout probe");
    CHECK(jpdf_abi_struct_size(999) == -1, "unknown struct id");
}

void test_init_and_authority() {
    jpdf_runtime_options o{};
    o.struct_size = sizeof(o);
    o.renderer = JPDF_RENDERER_AUTO;
    CHECK_STATUS(jpdf_runtime_init(&o), JPDF_OK, "init");
    CHECK_STATUS(jpdf_runtime_init(nullptr), JPDF_OK, "init is idempotent");
    CHECK(runtime_info().state == JPDF_STATE_RUNNING, "running after init");
    // The legacy bridge would serialize with a different lock, so it must be refused now.
    CHECK(jpdfium_init() != JPDFIUM_OK, "legacy init refused once the safe runtime owns PDFium");
    o.flags = 1;
    CHECK_STATUS(jpdf_runtime_init(&o), JPDF_E_UNSUPPORTED, "reserved flags rejected");
}

void test_struct_validation(jpdf_handle page) {
    jpdf_render_options o = render_opts(72, JPDF_PIXEL_RGBA8);
    jpdf_handle bmp = 0;
    o.struct_size = 8;
    CHECK_STATUS(jpdf_page_render(page, &o, &bmp), JPDF_E_INVALID_ARGUMENT, "short struct");
    struct Bigger {
        jpdf_render_options base;
        uint64_t future;
    } big{};
    big.base = render_opts(72, JPDF_PIXEL_RGBA8);
    big.base.struct_size = sizeof(big);
    CHECK_STATUS(jpdf_page_render(page, &big.base, &bmp), JPDF_OK, "zero trailing bytes accepted");
    jpdf_bitmap_close(bmp);
    big.future = 1;
    CHECK_STATUS(jpdf_page_render(page, &big.base, &bmp), JPDF_E_UNSUPPORTED,
                 "unknown trailing field rejected");
    o = render_opts(72, JPDF_PIXEL_RGBA8);
    o.flags = 0x80000000u;
    CHECK_STATUS(jpdf_page_render(page, &o, &bmp), JPDF_E_UNSUPPORTED, "unknown flag");
    o = render_opts(72, JPDF_PIXEL_RGBA8);
    o.rotation = 1;
    CHECK_STATUS(jpdf_page_render(page, &o, &bmp), JPDF_E_UNSUPPORTED, "rotation reserved");
    o = render_opts(72, JPDF_PIXEL_RGB8);
    o.flags = JPDF_RENDER_TRANSPARENT;
    CHECK_STATUS(jpdf_page_render(page, &o, &bmp), JPDF_E_INVALID_ARGUMENT,
                 "transparent needs RGBA");
    o = render_opts(0, JPDF_PIXEL_RGBA8);
    CHECK_STATUS(jpdf_page_render(page, &o, &bmp), JPDF_E_INVALID_ARGUMENT, "zero dpi");
    o = render_opts(72, JPDF_PIXEL_RGBA8);
    o.max_pixels = 100;
    CHECK_STATUS(jpdf_page_render(page, &o, &bmp), JPDF_E_LIMIT, "pixel budget enforced");
}

void test_documents_and_pages(const std::string& path) {
    jpdf_handle doc = 0;
    CHECK_STATUS(jpdf_doc_open_file(path.c_str(), nullptr, &doc), JPDF_OK, "open");
    int32_t count = 0;
    CHECK_STATUS(jpdf_doc_page_count(doc, &count), JPDF_OK, "page count");
    CHECK(count == kPages, "fixture page count");

    jpdf_handle page = 0;
    CHECK_STATUS(jpdf_page_open(doc, kPages, &page), JPDF_E_NOT_FOUND, "index out of range");
    CHECK_STATUS(jpdf_page_open(doc, 1, &page), JPDF_OK, "page open");
    jpdf_page_info info{};
    info.struct_size = sizeof(info);
    CHECK_STATUS(jpdf_page_get_info(page, &info), JPDF_OK, "page info");
    CHECK(info.index == 1 && info.width_pt == 612 && info.height_pt == 792, "page geometry");

    CHECK_STATUS(jpdf_page_get_info(doc, &info), JPDF_E_WRONG_HANDLE_TYPE, "doc used as page");
    CHECK_STATUS(jpdf_doc_page_count(page, &count), JPDF_E_WRONG_HANDLE_TYPE, "page as doc");
    CHECK_STATUS(jpdf_doc_page_count(0x0100000100001234ull, &count), JPDF_E_INVALID_HANDLE,
                 "fabricated token");

    CHECK_STATUS(jpdf_doc_close(doc), JPDF_E_BUSY, "parent close refused with live child");
    CHECK_STATUS(jpdf_doc_page_count(doc, &count), JPDF_OK, "BUSY close left doc usable");
    test_struct_validation(page);

    CHECK_STATUS(jpdf_page_close(page), JPDF_OK, "page close");
    CHECK_STATUS(jpdf_page_close(page), JPDF_E_STALE_HANDLE, "second page close is stale");
    CHECK_STATUS(jpdf_page_get_info(page, &info), JPDF_E_STALE_HANDLE, "closed page rejected");
    CHECK_STATUS(jpdf_doc_close(doc), JPDF_OK, "doc close after children");
    CHECK_STATUS(jpdf_doc_page_count(doc, &count), JPDF_E_STALE_HANDLE, "closed doc rejected");

    // Slot reuse must not resurrect the old token.
    jpdf_handle again = 0;
    CHECK_STATUS(jpdf_doc_open_file(path.c_str(), nullptr, &again), JPDF_OK, "reopen");
    CHECK(again != doc, "reopened document gets a new token");
    CHECK_STATUS(jpdf_doc_page_count(doc, &count), JPDF_E_STALE_HANDLE, "old token still stale");
    jpdf_doc_close(again);

    jpdf_handle missing = 0;
    const std::string nowhere = (scratch_dir() / "missing.pdf").string();
    CHECK_STATUS(jpdf_doc_open_file(nowhere.c_str(), nullptr, &missing), JPDF_E_IO, "missing");
    const fs::path junk = scratch_dir() / "junk.pdf";
    std::ofstream(junk, std::ios::binary) << "not a pdf at all";
    CHECK_STATUS(jpdf_doc_open_file(junk.string().c_str(), nullptr, &missing), JPDF_E_FORMAT,
                 "corrupt input");
}

void test_render_and_ownership(const std::string& path) {
    jpdf_handle doc = 0, page = 0, bmp = 0;
    jpdf_doc_open_file(path.c_str(), nullptr, &doc);
    jpdf_page_open(doc, 0, &page);
    jpdf_render_options o = render_opts(72, JPDF_PIXEL_RGB8);
    CHECK_STATUS(jpdf_page_render(page, &o, &bmp), JPDF_OK, "render rgb");
    jpdf_bitmap_info bi{};
    bi.struct_size = sizeof(bi);
    jpdf_bitmap_get_info(bmp, &bi);
    CHECK(bi.width == 612 && bi.height == 792 && bi.stride == 612 * 3, "72 dpi size");
    CHECK(bi.byte_size == static_cast<uint64_t>(612) * 3 * 792, "rgb byte size");
    std::vector<uint8_t> px = pixels_of(bmp);
    // Square spans x 72-172pt, y 600-700pt; image rows run top down.
    const size_t inside = (static_cast<size_t>(792 - 650) * 612 + 122) * 3;
    const size_t outside = (static_cast<size_t>(792 - 650) * 612 + 400) * 3;
    CHECK(px[inside] == 0 && px[inside + 1] == 0 && px[inside + 2] == 0, "square is black");
    CHECK(px[outside] == 255, "background is white");

    // Bitmaps are independently owned: they outlive the page and the document.
    CHECK_STATUS(jpdf_page_close(page), JPDF_OK, "page close with bitmap alive");
    CHECK_STATUS(jpdf_doc_close(doc), JPDF_OK, "doc close with bitmap alive");
    CHECK(pixels_of(bmp) == px, "bitmap intact after its page and document closed");

    // A view pins pixels beyond the bitmap handle.
    jpdf_bitmap_view view{};
    view.struct_size = sizeof(view);
    CHECK_STATUS(jpdf_bitmap_map(bmp, &view), JPDF_OK, "map");
    CHECK_STATUS(jpdf_bitmap_close(bmp), JPDF_OK, "bitmap close with a view alive");
    CHECK_STATUS(jpdf_bitmap_get_info(bmp, &bi), JPDF_E_STALE_HANDLE, "closed bitmap rejected");
    CHECK(runtime_info().live_views == 1, "view still live");
    CHECK(std::memcmp(view.data, px.data(), px.size()) == 0, "view data valid after close");
    CHECK_STATUS(jpdf_view_release(view.view), JPDF_OK, "release view");
    CHECK_STATUS(jpdf_view_release(view.view), JPDF_E_STALE_HANDLE, "double release is stale");
    CHECK(runtime_info().live_views == 0 && runtime_info().live_bitmaps == 0, "no leaks");

    std::vector<uint8_t> buf(10);
    jpdf_handle b2 = 0;
    jpdf_doc_open_file(path.c_str(), nullptr, &doc);
    jpdf_page_open(doc, 0, &page);
    o = render_opts(10, JPDF_PIXEL_RGBA8);
    o.flags = JPDF_RENDER_TRANSPARENT;
    jpdf_page_render(page, &o, &b2);
    uint64_t written = 0;
    CHECK_STATUS(jpdf_bitmap_copy_pixels(b2, buf.data(), buf.size(), &written),
                 JPDF_E_BUFFER_TOO_SMALL, "short copy buffer");
    CHECK(written == 85u * 110 * 4, "copy reports required size");
    std::vector<uint8_t> rgba = pixels_of(b2);
    CHECK(rgba[3] == 0, "transparent background has alpha 0");
    jpdf_bitmap_close(b2);
    jpdf_page_close(page);
    jpdf_doc_close(doc);
}

void test_output_transaction(const std::string& path) {
    jpdf_handle doc = 0, page = 0;
    jpdf_doc_open_file(path.c_str(), nullptr, &doc);
    jpdf_page_open(doc, 2, &page);
    const fs::path out = scratch_dir() / "page.png";
    jpdf_render_options o = render_opts(50, JPDF_PIXEL_GRAY8);
    CHECK_STATUS(jpdf_page_render_to_file(page, &o, out.string().c_str(), nullptr), JPDF_OK,
                 "render to file");
    const std::string png = read_file(out);
    CHECK(png.size() > 8 && png.compare(0, 8, "\x89PNG\r\n\x1a\n") == 0, "png signature");
    CHECK(png.find("pHYs") != std::string::npos, "dpi metadata written");

    // A refused write leaves the existing destination byte-for-byte untouched.
    jpdf_write_options w{};
    w.struct_size = sizeof(w);
    w.image_format = JPDF_IMAGE_PNG;
    w.png_compression = -1;
    w.max_bytes = 64;
    o = render_opts(150, JPDF_PIXEL_RGB8);
    CHECK_STATUS(jpdf_page_render_to_file(page, &o, out.string().c_str(), &w), JPDF_E_LIMIT,
                 "max_bytes enforced");
    CHECK(read_file(out) == png, "destination untouched after failed write");
    size_t leftovers = 0;
    for (const auto& entry : fs::directory_iterator(scratch_dir())) {
        if (entry.path().filename().string().find(".jpdf-") != std::string::npos) leftovers++;
    }
    CHECK(leftovers == 0, "no staging file left behind");

    const fs::path no_dir = scratch_dir() / "absent" / "page.png";
    CHECK_STATUS(jpdf_page_render_to_file(page, &o, no_dir.string().c_str(), nullptr), JPDF_E_IO,
                 "missing directory");
    CHECK_STATUS(jpdf_page_render_to_file(page, &o, scratch_dir().string().c_str(), nullptr),
                 JPDF_E_INVALID_ARGUMENT, "directory destination");
    w.max_bytes = 0;
    w.flags = JPDF_WRITE_DURABLE;
    CHECK_STATUS(jpdf_page_render_to_file(page, &o, out.string().c_str(), &w), JPDF_OK,
                 "durable replace");
    CHECK(read_file(out) != png, "replace published new content");
    jpdf_page_close(page);
    jpdf_doc_close(doc);
}

void test_concurrent_renders(const std::string& path) {
    std::vector<std::vector<uint8_t>> expected;
    for (int i = 0; i < kPages; ++i) expected.push_back(render_page(path, i));
    for (const auto& e : expected) CHECK(!e.empty(), "baseline render");
    CHECK(expected[0] != expected[1], "pages differ");

    // Independent documents on many threads.
    std::atomic<int> mismatches{0};
    std::vector<std::thread> threads;
    for (int t = 0; t < 8; ++t) {
        threads.emplace_back([&, t] {
            for (int r = 0; r < 25; ++r) {
                const int index = (t + r) % kPages;
                if (render_page(path, index) != expected[index]) mismatches++;
            }
        });
    }
    for (auto& th : threads) th.join();
    CHECK(mismatches == 0, "independent documents render identically under concurrency");

    // One document and its pages shared by every thread: safe because the domain owns it.
    jpdf_handle doc = 0;
    jpdf_doc_open_file(path.c_str(), nullptr, &doc);
    std::vector<jpdf_handle> pages(kPages);
    for (int i = 0; i < kPages; ++i) jpdf_page_open(doc, i, &pages[i]);
    threads.clear();
    for (int t = 0; t < 8; ++t) {
        threads.emplace_back([&, t] {
            jpdf_render_options o = render_opts(36, JPDF_PIXEL_GRAY8);
            for (int r = 0; r < 25; ++r) {
                const int index = (t * 3 + r) % kPages;
                jpdf_handle bmp = 0;
                if (jpdf_page_render(pages[index], &o, &bmp) != JPDF_OK ||
                    pixels_of(bmp) != expected[index]) {
                    mismatches++;
                }
                jpdf_bitmap_close(bmp);
            }
        });
    }
    for (auto& th : threads) th.join();
    CHECK(mismatches == 0, "shared document renders identically under concurrency");
    for (jpdf_handle p : pages) jpdf_page_close(p);
    jpdf_doc_close(doc);
}

void test_close_races(const std::string& path) {
    // Renderers race a closer on the same page token: every call either succeeds or reports a
    // stale handle, never touches freed PDFium state.
    int unexpected = 0, stale_seen = 0;
    for (int round = 0; round < 100; ++round) {
        jpdf_handle doc = 0, page = 0;
        jpdf_doc_open_file(path.c_str(), nullptr, &doc);
        jpdf_page_open(doc, round % kPages, &page);
        std::atomic<bool> go{false};
        std::atomic<int> bad{0}, stale{0};
        std::vector<std::thread> threads;
        for (int t = 0; t < 4; ++t) {
            threads.emplace_back([&] {
                while (!go.load()) std::this_thread::yield();
                jpdf_render_options o = render_opts(18, JPDF_PIXEL_GRAY8);
                for (int r = 0; r < 5; ++r) {
                    jpdf_handle bmp = 0;
                    jpdf_status st = jpdf_page_render(page, &o, &bmp);
                    if (st == JPDF_OK) {
                        jpdf_bitmap_close(bmp);
                    } else if (st == JPDF_E_STALE_HANDLE) {
                        stale++;
                    } else {
                        bad++;
                    }
                }
            });
        }
        threads.emplace_back([&] {
            while (!go.load()) std::this_thread::yield();
            if (jpdf_page_close(page) != JPDF_OK) bad++;
            jpdf_status st = jpdf_doc_close(doc);
            if (st != JPDF_OK) bad++;
        });
        go = true;
        for (auto& th : threads) th.join();
        unexpected += bad.load();
        stale_seen += stale.load();
    }
    CHECK(unexpected == 0, "close race only produces success or stale");
    CHECK(runtime_info().live_documents == 0 && runtime_info().live_pages == 0,
          "close race leaks nothing");
    std::printf("info  close race: %d stale rejections observed\n", stale_seen);
}

void test_shutdown(const std::string& path) {
    jpdf_handle doc = 0, page = 0, bmp = 0;
    jpdf_doc_open_file(path.c_str(), nullptr, &doc);
    jpdf_page_open(doc, 0, &page);
    jpdf_render_options o = render_opts(10, JPDF_PIXEL_GRAY8);
    jpdf_page_render(page, &o, &bmp);

    CHECK_STATUS(jpdf_runtime_shutdown(), JPDF_E_BUSY, "shutdown refused with live resources");
    CHECK(last_error().find("1 documents") != std::string::npos, "busy reports what is open");
    CHECK(runtime_info().state == JPDF_STATE_QUIESCING, "refused shutdown leaves QUIESCING");
    jpdf_handle other = 0;
    CHECK_STATUS(jpdf_doc_open_file(path.c_str(), nullptr, &other), JPDF_E_QUIESCING,
                 "no new work while quiescing");
    CHECK_STATUS(jpdf_page_close(page), JPDF_OK, "teardown still allowed while quiescing");
    CHECK_STATUS(jpdf_doc_close(doc), JPDF_OK, "doc close while quiescing");
    CHECK_STATUS(jpdf_runtime_shutdown(), JPDF_OK, "shutdown after closing everything");
    CHECK(runtime_info().state == JPDF_STATE_STOPPED, "stopped");
    CHECK_STATUS(jpdf_runtime_shutdown(), JPDF_OK, "shutdown is idempotent");
    CHECK_STATUS(jpdf_runtime_init(nullptr), JPDF_E_STOPPED, "no silent reinitialization");
    CHECK_STATUS(jpdf_doc_open_file(path.c_str(), nullptr, &other), JPDF_E_STOPPED,
                 "work after stop refused");
    CHECK(pixels_of(bmp).size() == 85u * 110, "bitmaps survive runtime shutdown");
    CHECK_STATUS(jpdf_bitmap_close(bmp), JPDF_OK, "bitmap close after stop");
}

}  // namespace

int main() {
    std::printf("=== jpdf safe API (real PDFium) ===\n");
    const std::string path = write_fixture();
    test_before_init();
    test_init_and_authority();
    test_documents_and_pages(path);
    test_render_and_ownership(path);
    test_output_transaction(path);
    test_concurrent_renders(path);
    test_close_races(path);
    test_shutdown(path);
    g_failures += object_table_suite::run_object_table_suite();
    std::error_code ec;
    fs::remove_all(scratch_dir(), ec);
    std::printf("=== failures: %d ===\n", g_failures);
    return g_failures == 0 ? 0 : 1;
}
