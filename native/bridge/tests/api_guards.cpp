// api_guards.cpp - jpdf_guard_* shims: legacy and raw PDFium calls admitted by the native domain.

#include <fpdf_save.h>
#include <fpdfview.h>

#include <atomic>
#include <chrono>
#include <cstdio>
#include <string>
#include <thread>
#include <vector>

#if defined(_WIN32)
#include <fcntl.h>
#include <io.h>
#include <share.h>
#include <sys/stat.h>
#else
#include <fcntl.h>
#include <unistd.h>
#endif

#include "jpdfium.h"
#include "jpdfium_api.h"

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
JPDF_API void jpdf_guard_jpdfium_free_buffer(uint8_t* buffer) noexcept;
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

static int g_failures = 0;

#define CHECK(cond, msg)                                       \
    do {                                                       \
        if (!(cond)) {                                         \
            std::printf("FAIL %s (line %d)\n", msg, __LINE__); \
            ++g_failures;                                      \
        }                                                      \
    } while (0)

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
            jpdf_guard_jpdfium_free_buffer(rgba);
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
    while (!in_callback.load()) std::this_thread::yield();
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

int main() {
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
    test_refused_before_init();
    if (jpdf_runtime_init(nullptr) != JPDF_OK) {
        std::printf("FAIL runtime init\n");
        return 1;
    }
    test_admitted_calls();
    test_callback_reentry_is_rejected();
    test_quiesce_admits_teardown_only();
    test_both_bindings_share_one_domain(path);
    test_raw_factory_lifetime_accounting();
    test_bounded_shutdown_then_stop();
    std::remove(path.c_str());
    std::printf("=== failures: %d ===\n", g_failures);
    return g_failures == 0 ? 0 : 1;
}
